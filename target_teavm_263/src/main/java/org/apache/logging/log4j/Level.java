// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.logging.log4j;

public final class Level implements Comparable<Level> {
    public static final Level OFF = new Level("OFF", 0);
    public static final Level FATAL = new Level("FATAL", 100);
    public static final Level ERROR = new Level("ERROR", 200);
    public static final Level WARN = new Level("WARN", 300);
    public static final Level INFO = new Level("INFO", 400);
    public static final Level DEBUG = new Level("DEBUG", 500);
    public static final Level TRACE = new Level("TRACE", 600);
    public static final Level ALL = new Level("ALL", Integer.MAX_VALUE);
    private final String name;
    private final int severity;

    private static final Level[] ALL_LEVELS = {OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL};
    private Level(String name, int severity) { this.name = name; this.severity = severity; }
    public static Level valueOf(String name) {
        Level level = toLevel(name, null);
        if (level == null) throw new IllegalArgumentException("Unknown level constant [" + name + "].");
        return level;
    }
    public static Level toLevel(String name, Level fallback) {
        if (name != null) for (Level level : ALL_LEVELS) if (level.name.equalsIgnoreCase(name.trim())) return level;
        return fallback;
    }
    public String name() { return name; }
    public int intLevel() { return severity; }
    public boolean isMoreSpecificThan(Level other) { return severity <= other.severity; }
    public boolean isLessSpecificThan(Level other) { return severity >= other.severity; }
    public boolean isInRange(Level min, Level max) { return severity >= min.severity && severity <= max.severity; }
    public int compareTo(Level other) { return Integer.compare(severity, other.severity); }
    @Override public String toString() { return name; }
}
