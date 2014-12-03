

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;


/**
 * Date: 02/01/14
 * Time: 08:56
 * This file is part of tmon-logger.
 * tmon-logger is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * tmon-logger is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with tmon-logger.  If not, see <http://www.gnu.org/licenses/>.
 */
public class Connector {

    private final ImmutableList<ConnectorWorker> workers;
    private final ExecutorService executor;
    private final BlockingQueue<ConnectorMessage> messages;
    private final boolean useCompression;

    public Connector(List<String> servers, boolean useCompression, String indexBase, String indexPattern) throws MalformedURLException, UnknownHostException {
        this.messages = new LinkedBlockingQueue<ConnectorMessage>();
        ImmutableList.Builder<ConnectorWorker> builder = new ImmutableList.Builder<ConnectorWorker>();
        for (String server : servers) {
            builder.add(new ConnectorWorker(server, messages, 4000, indexBase, DateTimeFormat.forPattern(indexPattern)));
        }
        this.workers = builder.build();
        this.executor = Executors.newFixedThreadPool(servers.size());
        this.useCompression = useCompression;
    }

    public Connector(List<String> servers, boolean useCompression) throws MalformedURLException, UnknownHostException {
        this(servers, useCompression, "logstash-", "YYYY.MM.dd");
    }


    public void start() throws MalformedURLException {
        for (ConnectorWorker worker : this.workers) {
            this.executor.execute(worker);
        }
    }

    public void send(ConnectorMessage message) throws InterruptedException {
        this.messages.put(message);
    }

    public void send(Event event) throws InterruptedException, JsonProcessingException {
        if (event != null) {
            this.messages.put(new ConnectorMessage(event.getTimestampAsDate(), event.getType(), event.toString(), event.getKey(), event.isUpsert()));
        }
    }

    public void stop() {
        for (ConnectorWorker worker : this.workers) {
            worker.cancel();
        }
        this.executor.shutdownNow();
    }

    private class ConnectorWorker implements Runnable {
        private final URL url;
        private final BlockingQueue<ConnectorMessage> messages;
        private final int bulkSize;
        private final CloseableHttpClient httpClient;
        private final ImmutableList<Integer> validReturnCodes;
        private final String indexBase;
        private final DateTimeFormatter indexFormatter;
        private int MAX_RETRY = 3;
        private boolean cancelled;

        private ConnectorWorker(String server, BlockingQueue<ConnectorMessage> messages, int bulkSize, String indexBase, DateTimeFormatter indexPattern) throws MalformedURLException {
            this.bulkSize = bulkSize;
            this.url = getBulkUrl(server);
            this.messages = messages;
            PoolingHttpClientConnectionManager connexionManager = new PoolingHttpClientConnectionManager();
            connexionManager.setMaxTotal(10);
            httpClient = HttpClients.custom().setConnectionManager(connexionManager).build();
            this.validReturnCodes = new ImmutableList.Builder<Integer>()
                    .add(HttpURLConnection.HTTP_OK)
                    .add(HttpURLConnection.HTTP_CREATED)
                    .add(HttpURLConnection.HTTP_ACCEPTED)
                    .add(HttpURLConnection.HTTP_NOT_AUTHORITATIVE)
                    .add(HttpURLConnection.HTTP_NO_CONTENT)
                    .add(HttpURLConnection.HTTP_RESET)
                    .add(HttpURLConnection.HTTP_PARTIAL)
                    .build();
            this.indexBase = indexBase;
            this.indexFormatter = indexPattern;
        }

        private boolean isCancelled() {
            return cancelled;
        }

        private void cancel() {
            cancelled = true;
        }

        private byte[] compress(String data) throws IOException {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            try {
                GZIPOutputStream compressedStream = new GZIPOutputStream(byteStream);
                try {
                    compressedStream.write(data.getBytes("UTF-8"));
                } finally {
                    if (compressedStream != null) {
                        compressedStream.close();
                    }
                }
                return byteStream.toByteArray();
            } finally {
                byteStream.close();
            }
        }

        @Override
        public void run() {
            List<ConnectorMessage> pendingMessages = new ArrayList<ConnectorMessage>();
            try {

                do {
                    ConnectorMessage message = messages.poll(1000, TimeUnit.MILLISECONDS);
                    if (message != null) {
                        pendingMessages.add(message);
                        if (pendingMessages.size() > bulkSize) {
                            int status = send(new ImmutableList.Builder<ConnectorMessage>().addAll(pendingMessages).build());
                            if (status < MAX_RETRY) {
                                pendingMessages.clear();
                            } else {
                                cancel();
                            }
                        }
                    } else {
                        if (pendingMessages.size() > 0) {
                            send(pendingMessages);
                            pendingMessages.clear();
                        }
                    }
                } while (!isCancelled());

                purge(pendingMessages);

            } catch (InterruptedException error) {
                purge(pendingMessages);
            }
        }

        private void purge(List<ConnectorMessage> pendingMessages) {
            try {
                while (!messages.isEmpty()) {
                    ConnectorMessage message = messages.poll(100, TimeUnit.MILLISECONDS);
                    if (message != null) {
                        pendingMessages.add(message);
                    }
                }
                send(pendingMessages);
            } catch (InterruptedException error) {
                //Nothing to do as it is not likely to happen.
            }
        }

        private int send(List<ConnectorMessage> messages) throws InterruptedException {
            int retry = 0;
            while (retry < MAX_RETRY) {
                StringBuilder data = new StringBuilder();
                for (ConnectorMessage message : messages) {
                    if (message != null && message.getData() != null) {
                        if (!message.isUpsert()) {
                            data.append("{\"index\":{\"_index\":\"");
                        } else {
                            data.append("{\"update\":{\"_index\":\"");
                        }
                        data.append(BuildIndex(message.getDate()));
                        data.append("\",\"_type\":\"");
                        data.append(message.getType());
                        if (message.getId() != null) {
                            data.append("\",\"_id\":\"");
                            data.append(message.getId());
                        }

                        data.append("\"}}\n");
                        data.append(message.getData());
                        data.append("\n");
                    }
                }

                try {
                    HttpPost post = new HttpPost(url.toURI());
                    post.setHeader("Content-type", "application/json");
                    if (!useCompression) {
                        StringEntity entity = new StringEntity(data.toString());
                        post.setEntity(entity);
                    } else {
                        byte[] compressedData = compress(data.toString());
                        InputStreamEntity entity = new InputStreamEntity(new ByteArrayInputStream(compressedData), compressedData.length);
                        entity.setChunked(false);
                        post.setEntity(entity);
                        post.setHeader("Content-Encoding", "gzip");
                    }
                    HttpContext context = new BasicHttpContext();
                    CloseableHttpResponse response = httpClient.execute(post, context);
                    try {
                        if (!validReturnCodes.contains(response.getStatusLine().getStatusCode())) {
                            retry++;
                        } else {
                            return retry;
                        }

                    } finally {
                        response.close();
                    }

                } catch (Exception error) {
                    retry++;
                }
                Thread.sleep(retry * 500);
            }
            return retry;
        }

        private URL getBulkUrl(String server) throws MalformedURLException {
            StringBuilder url = new StringBuilder(server);
            url.append("/_bulk");
            return new URL(url.toString());
        }

        private String BuildIndex(DateTime date) {
            StringBuilder index = new StringBuilder(indexBase);
            index.append(indexFormatter.print(date));
            return index.toString();
        }
    }


}
