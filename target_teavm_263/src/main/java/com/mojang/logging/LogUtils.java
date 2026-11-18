// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class LogUtils {
	public static final Marker FATAL_MARKER = MarkerFactory.getMarker("FATAL");

	public static Logger getLogger() {
		return LoggerFactory.getLogger("minecraft");
	}

	public static boolean isLoggerActive() {
		return true;
	}

	public static Object defer(final java.util.function.Supplier<Object> result) {
		return new Object() {
			@Override
			public String toString() {
				return String.valueOf(result.get());
			}
		};
	}
}
