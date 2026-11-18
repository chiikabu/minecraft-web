// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.system;

public final class APIUtil {
	private APIUtil() {
	}

	public static void apiArrayFree(long pointers, int count) {
		for (int i = count - 1; i >= 0; i--) {
			MemoryUtil.nmemFree(MemoryUtil.memGetAddress(pointers + (long) i * Long.BYTES));
		}
	}
}
