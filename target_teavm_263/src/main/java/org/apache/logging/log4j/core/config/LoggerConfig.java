// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.logging.log4j.core.config;

import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;

public class LoggerConfig {
    private final CopyOnWriteArrayList<Filter> filters = new CopyOnWriteArrayList<>();

    public void addFilter(Filter filter) { if (filter != null) filters.addIfAbsent(filter); }
    public void removeFilter(Filter filter) { filters.remove(filter); }
    public boolean hasFilter() { return !filters.isEmpty(); }
    public boolean isFiltered(LogEvent event) {
        for (Filter filter : filters) {
            Filter.Result result = filter.filter(event);
            if (result == Filter.Result.DENY) return true;
            if (result == Filter.Result.ACCEPT) return false;
        }
        return false;
    }
}
