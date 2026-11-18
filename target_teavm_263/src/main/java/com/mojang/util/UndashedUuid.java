// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.util;

import java.util.UUID;

public final class UndashedUuid {
	private UndashedUuid() {
	}

	public static UUID fromStringLenient(String string) {
		return string.contains("-") ? UUID.fromString(string) : fromString(string);
	}

	public static UUID fromString(String string) {
		return UUID.fromString(string.replaceFirst(
			"(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
	}

	public static String toString(UUID uuid) {
		return uuid.toString().replace("-", "");
	}
}
