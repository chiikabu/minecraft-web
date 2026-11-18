// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.logging.log4j.core.config;

import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Configurator {
    private Configurator() { }

    public static void setLevel(String loggerName, Level level) { LogManager.setLevel(loggerName, level); }
    public static void setLevel(String loggerName, String level) { LogManager.setLevel(loggerName, Level.valueOf(level)); }
    public static void setLevel(Logger logger, Level level) { LogManager.setLevel(logger.getName(), level); }
    public static void setLevel(Map<String, Level> levels) { levels.forEach(LogManager::setLevel); }
    public static void setRootLevel(Level level) { LogManager.setLevel(LogManager.ROOT_LOGGER_NAME, level); }
    public static void setAllLevels(String parentLogger, Level level) { LogManager.setAllLevels(parentLogger, level); }
}
