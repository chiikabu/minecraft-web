// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.logging.log4j;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.helpers.ConsoleLogger;

public final class LogManager {
    public static final String ROOT_LOGGER_NAME = "";
    private static final ConcurrentHashMap<String, NamedLogger> LOGGERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Level> LEVELS = new ConcurrentHashMap<>();
    private static final org.apache.logging.log4j.core.LoggerContext CONTEXT = new org.apache.logging.log4j.core.LoggerContext();

    private LogManager() { }
    public static org.apache.logging.log4j.spi.LoggerContext getContext(boolean currentContext) { return CONTEXT; }
    public static boolean isFiltered(String name, String message, Throwable thrown) {
        return CONTEXT.isFiltered(name, message, thrown);
    }
    public static Logger getLogger(String name) {
        return LOGGERS.computeIfAbsent(name == null ? ROOT_LOGGER_NAME : name, NamedLogger::new);
    }
    public static Logger getLogger(Class<?> type) { return getLogger(type.getName()); }
    public static Logger getLogger() { return getLogger(ROOT_LOGGER_NAME); }
    public static Logger getRootLogger() { return getLogger(ROOT_LOGGER_NAME); }
    public static Logger getFormatterLogger(String name) { return getLogger(name); }

    public static void setLevel(String name, Level level) {
        if (name == null) name = ROOT_LOGGER_NAME;
        if (level == null) LEVELS.remove(name); else LEVELS.put(name, level);
        refresh();
    }

    public static void setAllLevels(String name, Level level) {
        if (name == null) name = ROOT_LOGGER_NAME;
        for (String key : new ArrayList<>(LEVELS.keySet())) if (key.startsWith(name)) LEVELS.remove(key);
        setLevel(name, level);
    }

    private static Level levelFor(String name) {
        for (String key = name; ; ) {
            Level level = LEVELS.get(key);
            if (level != null) return level;
            int dot = key.lastIndexOf('.');
            if (dot < 0) return LEVELS.getOrDefault(ROOT_LOGGER_NAME, Level.INFO);
            key = key.substring(0, dot);
        }
    }

    private static void refresh() {
        for (NamedLogger logger : LOGGERS.values()) logger.effective = levelFor(logger.getName());
    }

    private static final class NamedLogger extends ConsoleLogger implements Logger {
        volatile Level effective;

        NamedLogger(String name) { super(name); effective = levelFor(name); }

        public Level getLevel() { return effective; }
        public boolean isEnabled(Level level) {
            return level != null && level != Level.OFF && level.isMoreSpecificThan(effective);
        }
        public void log(Level level, String message, Object... arguments) {
            if (level == null) level = Level.ERROR;
            if (!isEnabled(level)) return;
            log(level.name(), message, arguments);
        }
        @Override public boolean isTraceEnabled() { return isEnabled(Level.TRACE); }
        @Override public boolean isDebugEnabled() { return isEnabled(Level.DEBUG); }
        @Override public boolean isInfoEnabled() { return isEnabled(Level.INFO); }
        @Override public boolean isWarnEnabled() { return isEnabled(Level.WARN); }
        @Override public boolean isErrorEnabled() { return isEnabled(Level.ERROR); }
        @Override public boolean isTraceEnabled(org.slf4j.Marker marker) { return isTraceEnabled(); }
        @Override public boolean isDebugEnabled(org.slf4j.Marker marker) { return isDebugEnabled(); }
        @Override public boolean isInfoEnabled(org.slf4j.Marker marker) { return isInfoEnabled(); }
        @Override public boolean isWarnEnabled(org.slf4j.Marker marker) { return isWarnEnabled(); }
        @Override public boolean isErrorEnabled(org.slf4j.Marker marker) { return isErrorEnabled(); }
    }
}
