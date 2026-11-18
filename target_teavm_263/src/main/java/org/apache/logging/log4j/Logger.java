// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.logging.log4j;

public interface Logger extends org.slf4j.Logger {
    Level getLevel();
    boolean isEnabled(Level level);
    void log(Level level, String message, Object... arguments);

    default boolean isFatalEnabled() { return isEnabled(Level.FATAL); }
    default void log(Level level, String message) { log(level, message, new Object[0]); }
    default void log(Level level, String message, Throwable thrown) { log(level, message, (Object) thrown); }

    default void trace(Object message) { log(Level.TRACE, "{}", message); }
    default void trace(String format, Object p0, Object p1, Object p2) { log(Level.TRACE, format, p0, p1, p2); }
    default void trace(String format, Object p0, Object p1, Object p2, Object p3) { log(Level.TRACE, format, p0, p1, p2, p3); }
    default void trace(String format, Object p0, Object p1, Object p2, Object p3, Object p4) { log(Level.TRACE, format, p0, p1, p2, p3, p4); }
    default void trace(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { log(Level.TRACE, format, p0, p1, p2, p3, p4, p5); }
    default void trace(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { log(Level.TRACE, format, p0, p1, p2, p3, p4, p5, p6); }
    default void trace(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { log(Level.TRACE, format, p0, p1, p2, p3, p4, p5, p6, p7); }
    default void trace(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { log(Level.TRACE, format, p0, p1, p2, p3, p4, p5, p6, p7, p8); }
    default void trace(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { log(Level.TRACE, format, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9); }

    default void debug(Object message) { log(Level.DEBUG, "{}", message); }
    default void debug(String format, Object p0, Object p1, Object p2) { log(Level.DEBUG, format, p0, p1, p2); }
    default void debug(String format, Object p0, Object p1, Object p2, Object p3) { log(Level.DEBUG, format, p0, p1, p2, p3); }
    default void debug(String format, Object p0, Object p1, Object p2, Object p3, Object p4) { log(Level.DEBUG, format, p0, p1, p2, p3, p4); }
    default void debug(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { log(Level.DEBUG, format, p0, p1, p2, p3, p4, p5); }
    default void debug(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { log(Level.DEBUG, format, p0, p1, p2, p3, p4, p5, p6); }
    default void debug(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { log(Level.DEBUG, format, p0, p1, p2, p3, p4, p5, p6, p7); }
    default void debug(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { log(Level.DEBUG, format, p0, p1, p2, p3, p4, p5, p6, p7, p8); }
    default void debug(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { log(Level.DEBUG, format, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9); }

    default void info(Object message) { log(Level.INFO, "{}", message); }
    default void info(String format, Object p0, Object p1, Object p2) { log(Level.INFO, format, p0, p1, p2); }
    default void info(String format, Object p0, Object p1, Object p2, Object p3) { log(Level.INFO, format, p0, p1, p2, p3); }
    default void info(String format, Object p0, Object p1, Object p2, Object p3, Object p4) { log(Level.INFO, format, p0, p1, p2, p3, p4); }
    default void info(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { log(Level.INFO, format, p0, p1, p2, p3, p4, p5); }
    default void info(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { log(Level.INFO, format, p0, p1, p2, p3, p4, p5, p6); }
    default void info(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { log(Level.INFO, format, p0, p1, p2, p3, p4, p5, p6, p7); }
    default void info(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { log(Level.INFO, format, p0, p1, p2, p3, p4, p5, p6, p7, p8); }
    default void info(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { log(Level.INFO, format, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9); }

    default void warn(Object message) { log(Level.WARN, "{}", message); }
    default void warn(String format, Object p0, Object p1, Object p2) { log(Level.WARN, format, p0, p1, p2); }
    default void warn(String format, Object p0, Object p1, Object p2, Object p3) { log(Level.WARN, format, p0, p1, p2, p3); }
    default void warn(String format, Object p0, Object p1, Object p2, Object p3, Object p4) { log(Level.WARN, format, p0, p1, p2, p3, p4); }
    default void warn(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { log(Level.WARN, format, p0, p1, p2, p3, p4, p5); }
    default void warn(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { log(Level.WARN, format, p0, p1, p2, p3, p4, p5, p6); }
    default void warn(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { log(Level.WARN, format, p0, p1, p2, p3, p4, p5, p6, p7); }
    default void warn(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { log(Level.WARN, format, p0, p1, p2, p3, p4, p5, p6, p7, p8); }
    default void warn(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { log(Level.WARN, format, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9); }

    default void error(Object message) { log(Level.ERROR, "{}", message); }
    default void error(String format, Object p0, Object p1, Object p2) { log(Level.ERROR, format, p0, p1, p2); }
    default void error(String format, Object p0, Object p1, Object p2, Object p3) { log(Level.ERROR, format, p0, p1, p2, p3); }
    default void error(String format, Object p0, Object p1, Object p2, Object p3, Object p4) { log(Level.ERROR, format, p0, p1, p2, p3, p4); }
    default void error(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { log(Level.ERROR, format, p0, p1, p2, p3, p4, p5); }
    default void error(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { log(Level.ERROR, format, p0, p1, p2, p3, p4, p5, p6); }
    default void error(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { log(Level.ERROR, format, p0, p1, p2, p3, p4, p5, p6, p7); }
    default void error(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { log(Level.ERROR, format, p0, p1, p2, p3, p4, p5, p6, p7, p8); }
    default void error(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { log(Level.ERROR, format, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9); }

    default void fatal(Object message) { log(Level.FATAL, "{}", message); }
    default void fatal(String message) { log(Level.FATAL, message); }
    default void fatal(String format, Object a) { log(Level.FATAL, format, a); }
    default void fatal(String format, Object a, Object b) { log(Level.FATAL, format, a, b); }
    default void fatal(String format, Object... arguments) { log(Level.FATAL, format, arguments); }
    default void fatal(String message, Throwable thrown) { log(Level.FATAL, message, thrown); }
    default void fatal(String format, Object p0, Object p1, Object p2) { log(Level.FATAL, format, p0, p1, p2); }
    default void fatal(String format, Object p0, Object p1, Object p2, Object p3) { log(Level.FATAL, format, p0, p1, p2, p3); }
    default void fatal(String format, Object p0, Object p1, Object p2, Object p3, Object p4) { log(Level.FATAL, format, p0, p1, p2, p3, p4); }
    default void fatal(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) { log(Level.FATAL, format, p0, p1, p2, p3, p4, p5); }
    default void fatal(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6) { log(Level.FATAL, format, p0, p1, p2, p3, p4, p5, p6); }
    default void fatal(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { log(Level.FATAL, format, p0, p1, p2, p3, p4, p5, p6, p7); }
    default void fatal(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8) { log(Level.FATAL, format, p0, p1, p2, p3, p4, p5, p6, p7, p8); }
    default void fatal(String format, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7, Object p8, Object p9) { log(Level.FATAL, format, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9); }
}
