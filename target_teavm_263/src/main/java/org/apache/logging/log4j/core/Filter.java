// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.logging.log4j.core;

public interface Filter {
    enum Result { ACCEPT, NEUTRAL, DENY }
    Result filter(LogEvent event);
}
