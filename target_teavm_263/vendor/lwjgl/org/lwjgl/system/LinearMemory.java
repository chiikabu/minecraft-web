// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.system;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

public final class LinearMemory {
	private LinearMemory() {
	}

	public static ByteBuffer wrap(int address, int capacity) {
		throw new UnsupportedOperationException("LinearMemory is only linked by TeaVM");
	}

	public static long address(ByteBuffer b) {
		throw new UnsupportedOperationException("LinearMemory is only linked by TeaVM");
	}

	public static long base(ByteBuffer b) {
		throw new UnsupportedOperationException("LinearMemory is only linked by TeaVM");
	}

	public static long address(IntBuffer b) {
		throw new UnsupportedOperationException("LinearMemory is only linked by TeaVM");
	}

	public static long address(LongBuffer b) {
		throw new UnsupportedOperationException("LinearMemory is only linked by TeaVM");
	}
}
