// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public final class BufferUtils {
	private BufferUtils() {
	}

	public static ByteBuffer createByteBuffer(int capacity) {
		return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
	}

	public static IntBuffer createIntBuffer(int capacity) {
		checkWordCapacity(capacity);
		return IntBuffer.allocate(capacity);
	}

	public static FloatBuffer createFloatBuffer(int capacity) {
		checkWordCapacity(capacity);
		return FloatBuffer.allocate(capacity);
	}

	private static void checkWordCapacity(int capacity) {
		if (capacity < 0 || capacity > Integer.MAX_VALUE / Integer.BYTES) {
			throw new IllegalArgumentException("Invalid buffer element count: " + capacity);
		}
	}
}
