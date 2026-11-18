// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.logging.log4j.message;

import org.slf4j.helpers.MessageFormatter;

public final class ParameterizedMessage {
    private ParameterizedMessage() { }
    public static String format(String pattern, Object[] arguments) {
        return MessageFormatter.arrayFormat(pattern, arguments, null).getMessage();
    }
}
