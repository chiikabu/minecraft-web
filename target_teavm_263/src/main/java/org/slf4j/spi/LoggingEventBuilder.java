// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.slf4j.spi;

public interface LoggingEventBuilder {
	LoggingEventBuilder setMessage(String message);

	LoggingEventBuilder addArgument(Object argument);

	void log();
}
