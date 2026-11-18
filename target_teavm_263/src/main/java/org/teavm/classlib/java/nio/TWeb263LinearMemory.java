// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio;

import org.teavm.interop.Address;

public final class TWeb263LinearMemory {
	private TWeb263LinearMemory() {
	}

	public static java.nio.ByteBuffer wrap(int address, int capacity) {
		TByteBufferWasmGC buf = new TByteBufferWasmGC(null, null, 0, null, Address.fromInt(address), capacity, false);
		buf.limit(capacity);
		buf.order(TByteOrder.LITTLE_ENDIAN);
		return (java.nio.ByteBuffer) (Object) buf;
	}

	public static long address(java.nio.ByteBuffer buffer) {
		Object o = buffer;
		if (o instanceof TByteBufferNative) {
			TByteBufferNative b = (TByteBufferNative) o;
			return (b.address.toInt() & 0xFFFFFFFFL) + b.position();
		}
		return -1L;
	}

	public static long base(java.nio.ByteBuffer buffer) {
		Object o = buffer;
		if (o instanceof TByteBufferNative) {
			return ((TByteBufferNative) o).address.toInt() & 0xFFFFFFFFL;
		}
		return -1L;
	}

	public static long address(java.nio.IntBuffer buffer) {
		Object o = buffer;
		if (o instanceof TIntBufferNative) {
			TIntBufferNative b = (TIntBufferNative) o;
			return (b.address.toInt() & 0xFFFFFFFFL) + (long) b.position() * 4L;
		}
		return -1L;
	}

	public static long address(java.nio.LongBuffer buffer) {
		Object o = buffer;
		if (o instanceof TLongBufferNative) {
			TLongBufferNative b = (TLongBufferNative) o;
			return (b.address.toInt() & 0xFFFFFFFFL) + (long) b.position() * 8L;
		}
		return -1L;
	}
}
