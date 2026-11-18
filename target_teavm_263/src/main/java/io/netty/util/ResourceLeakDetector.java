// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

public class ResourceLeakDetector<T> {
	public enum Level {
		DISABLED,
		SIMPLE,
		ADVANCED,
		PARANOID
	}

	private static Level level = Level.DISABLED;

	public static void setLevel(Level newLevel) { level = newLevel; }
	public static Level getLevel() { return level; }
	public static boolean isEnabled() { return false; }
}
