// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.logging.log4j.core;

import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.message.Message;

public class LoggerContext implements org.apache.logging.log4j.spi.LoggerContext {
    private final LoggerConfig root = new LoggerConfig();
    private final Configuration configuration = () -> root;
    private volatile LoggerConfig activeRoot = root;

    public Configuration getConfiguration() { return configuration; }
    public void updateLoggers() { activeRoot = configuration.getRootLogger(); }

    public boolean isFiltered(String loggerName, String message, Throwable thrown) {
        LoggerConfig current = activeRoot;
        if (!current.hasFilter()) return false;
        String threadName = Thread.currentThread().getName();
        return current.isFiltered(new LogEvent() {
            public String getLoggerName() { return loggerName; }
            public String getThreadName() { return threadName; }
            public Message getMessage() { return () -> message; }
            public Throwable getThrown() { return thrown; }
        });
    }
}
