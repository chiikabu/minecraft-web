// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.logging.log4j.core;

import org.apache.logging.log4j.message.Message;

public interface LogEvent {
    String getLoggerName();
    String getThreadName();
    Message getMessage();
    Throwable getThrown();
}
