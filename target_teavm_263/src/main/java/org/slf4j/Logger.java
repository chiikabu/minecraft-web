// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.slf4j;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.spi.LoggingEventBuilder;

public interface Logger {

	String ROOT_LOGGER_NAME = "ROOT";

	String getName();

	boolean isTraceEnabled();

	void trace(String msg);

	void trace(String format, Object arg);

	void trace(String format, Object arg1, Object arg2);

	void trace(String format, Object... arguments);

	void trace(String msg, Throwable t);

	boolean isTraceEnabled(Marker marker);

	void trace(Marker marker, String msg);

	void trace(Marker marker, String format, Object arg);

	void trace(Marker marker, String format, Object arg1, Object arg2);

	void trace(Marker marker, String format, Object... argArray);

	void trace(Marker marker, String msg, Throwable t);

	boolean isDebugEnabled();

	void debug(String msg);

	void debug(String format, Object arg);

	void debug(String format, Object arg1, Object arg2);

	void debug(String format, Object... arguments);

	void debug(String msg, Throwable t);

	boolean isDebugEnabled(Marker marker);

	void debug(Marker marker, String msg);

	void debug(Marker marker, String format, Object arg);

	void debug(Marker marker, String format, Object arg1, Object arg2);

	void debug(Marker marker, String format, Object... argArray);

	void debug(Marker marker, String msg, Throwable t);

	boolean isInfoEnabled();

	default LoggingEventBuilder atInfo() {
		boolean enabled = isInfoEnabled();
		return new LoggingEventBuilder() {
			private String message;
			private final List<Object> arguments = new ArrayList<>();

			@Override
			public LoggingEventBuilder setMessage(String message) {
				this.message = message;
				return this;
			}

			@Override
			public LoggingEventBuilder addArgument(Object argument) {
				if (enabled) arguments.add(argument);
				return this;
			}

			@Override
			public void log() {
				if (enabled) Logger.this.info(message, arguments.toArray());
			}
		};
	}

	void info(String msg);

	void info(String format, Object arg);

	void info(String format, Object arg1, Object arg2);

	void info(String format, Object... arguments);

	void info(String msg, Throwable t);

	boolean isInfoEnabled(Marker marker);

	void info(Marker marker, String msg);

	void info(Marker marker, String format, Object arg);

	void info(Marker marker, String format, Object arg1, Object arg2);

	void info(Marker marker, String format, Object... argArray);

	void info(Marker marker, String msg, Throwable t);

	boolean isWarnEnabled();

	void warn(String msg);

	void warn(String format, Object arg);

	void warn(String format, Object... arguments);

	void warn(String format, Object arg1, Object arg2);

	void warn(String msg, Throwable t);

	boolean isWarnEnabled(Marker marker);

	void warn(Marker marker, String msg);

	void warn(Marker marker, String format, Object arg);

	void warn(Marker marker, String format, Object arg1, Object arg2);

	void warn(Marker marker, String format, Object... argArray);

	void warn(Marker marker, String msg, Throwable t);

	boolean isErrorEnabled();

	void error(String msg);

	void error(String format, Object arg);

	void error(String format, Object arg1, Object arg2);

	void error(String format, Object... arguments);

	void error(String msg, Throwable t);

	boolean isErrorEnabled(Marker marker);

	void error(Marker marker, String msg);

	void error(Marker marker, String format, Object arg);

	void error(Marker marker, String format, Object arg1, Object arg2);

	void error(Marker marker, String format, Object... argArray);

	void error(Marker marker, String msg, Throwable t);
}
