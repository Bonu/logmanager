package sgcib.tmon.logger;

import org.joda.time.DateTime;

/**
 * Date: 02/01/14
 * Time: 11:23
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
public class ConnectorMessage {
    private final DateTime date;
    private final String type;
    private final String data;
    private final String id;
    private final boolean isUpsert;


    public ConnectorMessage(DateTime date, String type, String message, String id, boolean isUpsert) {
        this.date = date;
        this.type = type;
        this.data = message;
        this.id = id;
        this.isUpsert = isUpsert;
    }

    public DateTime getDate() {
        return date;
    }

    public String getType() {
        return type;
    }

    public String getData() {
        return data;
    }

    public String getId() {
        return id;
    }

    public boolean isUpsert() {
        return isUpsert;
    }
}
