// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import org.lwjgl.system.MemoryUtil;

public final class PointerBuffer {
	private final LongBuffer buffer;

	public PointerBuffer(LongBuffer buffer) {
		this.buffer = buffer;
	}

	public long get() {
		return buffer.get();
	}

	public long get(int index) {
		return buffer.get(index);
	}

	public PointerBuffer put(long value) {
		buffer.put(value);
		return this;
	}

	public PointerBuffer put(ByteBuffer value) {
		return put(MemoryUtil.memAddress(value));
	}

	public PointerBuffer put(int index, long value) {
		buffer.put(index, value);
		return this;
	}

	public int capacity() {
		return buffer.capacity();
	}

	public int limit() {
		return buffer.limit();
	}

	public int remaining() {
		return buffer.remaining();
	}

	public int position() {
		return buffer.position();
	}

	public PointerBuffer position(int newPosition) {
		((Buffer) buffer).position(newPosition);
		return this;
	}

	public long address() {
		return MemoryUtil.memAddress(buffer);
	}

	public long address0() {
		return address() - (long) buffer.position() * Long.BYTES;
	}
}
