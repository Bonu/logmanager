package sgcib.tmon.logger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Date: 02/01/14
 * Time: 09:31
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
public class CyclicCounter {
    private final int MAX_COUNTER_VALUE = 4096;
    private final int maximum;
    private AtomicInteger counter;

    public CyclicCounter(int maximum) {
        if (maximum < 1) { throw new IllegalArgumentException(); }
        this.maximum = maximum;
        counter = new AtomicInteger(0);
    }

    public int getCounter() {
        return counter.get();
    }

    public int next() {
        counter.compareAndSet(MAX_COUNTER_VALUE,0);
        return counter.incrementAndGet() % maximum;
    }
}
