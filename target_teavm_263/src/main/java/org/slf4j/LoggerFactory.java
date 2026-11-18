// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.slf4j;

import org.slf4j.helpers.ConsoleLogger;

public class LoggerFactory {

	private LoggerFactory() {
	}

	public static Logger getLogger(Class<?> clazz) {
		return getLogger(clazz.getName());
	}

	public static Logger getLogger(String name) {
		return new ConsoleLogger(name);
	}
}
