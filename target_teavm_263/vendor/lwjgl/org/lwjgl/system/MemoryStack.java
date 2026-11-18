// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.system;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;

public final class MemoryStack implements AutoCloseable {
	private static final MemoryStack INSTANCE = new MemoryStack();

	private long[] addrs = new long[64];
	private int addrCount;
	private int[] marks = new int[16];
	private int depth = 1;

	private MemoryStack() {
	}

	public static MemoryStack stackPush() {
		MemoryStack s = INSTANCE;
		if (s.depth == s.marks.length) {
			s.marks = java.util.Arrays.copyOf(s.marks, s.depth * 2);
		}
		s.marks[s.depth++] = s.addrCount;
		return s;
	}

	public static MemoryStack stackGet() {
		return INSTANCE;
	}

	public int getPointer() {
		return Integer.MAX_VALUE - addrCount;
	}

	public void setPointer(int pointer) {
		int start = Integer.MAX_VALUE - pointer;
		if (start < marks[depth - 1] || start > addrCount) {
			throw new IllegalArgumentException("MemoryStack mark is outside the current frame");
		}
		release(start);
	}

	public void pop() {
		if (depth <= 1) {
			throw new IllegalStateException("MemoryStack shim: frame underflow (pop()/close() without a matching push())");
		}

		release(marks[--depth]);
	}

	private void release(int start) {
		for (int i = addrCount - 1; i >= start; i--) {
			MemoryUtil.nmemFree(addrs[i]);
			addrs[i] = 0L;
		}
		addrCount = start;
	}

	private void record(final long address) {
		if (addrCount == addrs.length) {
			addrs = java.util.Arrays.copyOf(addrs, addrCount * 2);
		}
		addrs[addrCount++] = address;
	}

	@Override
	public void close() {
		pop();
	}

	public long nmalloc(int size) {
		return nmalloc(Pointer.POINTER_SIZE, size);
	}

	public long nmalloc(int alignment, int size) {
		if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
			throw new IllegalArgumentException("MemoryStack: invalid alignment: " + alignment);
		}
		if (size < 0) {
			throw new IllegalArgumentException("MemoryStack: negative allocation size: " + size);
		}
		long address = MemoryUtil.nmemAlloc((long) Math.max(1, size) + alignment - 1);
		record(address);
		return (address + alignment - 1) & -(long) alignment;
	}

	public ByteBuffer malloc(int size) {
		return MemoryUtil.memByteBuffer(nmalloc(size), size);
	}

	public IntBuffer mallocInt(int capacity) {
		long address = MemoryUtil.nmemAlloc((long) capacity * 4);
		record(address);
		return MemoryUtil.memIntBuffer(address, capacity);
	}

	public PointerBuffer mallocPointer(int capacity) {
		long address = MemoryUtil.nmemAlloc((long) capacity * 8);
		record(address);
		return new PointerBuffer(MemoryUtil.memLongBuffer(address, capacity));
	}

	public MemoryStack push() {
		return stackPush();
	}

	public long ncalloc(int alignment, int num, int size) {
		long bytes = (long) num * size;
		if (num < 0 || size < 0 || bytes > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("MemoryStack: calloc size out of range: " + num + " * " + size);
		}
		long address = nmalloc(alignment, (int) bytes);
		if (bytes > 0) {
			MemoryUtil.memSet(address, 0, bytes);
		}
		return address;
	}

	public ByteBuffer calloc(int size) {
		return MemoryUtil.memByteBuffer(ncalloc(Pointer.POINTER_SIZE, 1, size), size);
	}

	public IntBuffer callocInt(int capacity) {
		return MemoryUtil.memIntBuffer(ncalloc(4, capacity, 4), capacity);
	}

	public IntBuffer ints(int value) {
		IntBuffer buffer = mallocInt(1);
		buffer.put(0, value);
		return buffer;
	}

	public java.nio.LongBuffer mallocLong(int capacity) {
		return MemoryUtil.memLongBuffer(nmalloc(8, capacity * 8), capacity);
	}

	public java.nio.LongBuffer callocLong(int capacity) {
		return MemoryUtil.memLongBuffer(ncalloc(8, capacity, 8), capacity);
	}

	public java.nio.LongBuffer longs(long value) {
		java.nio.LongBuffer buffer = mallocLong(1);
		buffer.put(0, value);
		return buffer;
	}

	public java.nio.FloatBuffer mallocFloat(int capacity) {
		return MemoryUtil.memByteBuffer(nmalloc(4, capacity * 4), capacity * 4).asFloatBuffer();
	}

	public java.nio.FloatBuffer callocFloat(int capacity) {
		return MemoryUtil.memByteBuffer(ncalloc(4, capacity, 4), capacity * 4).asFloatBuffer();
	}

	public PointerBuffer callocPointer(int capacity) {
		return new PointerBuffer(MemoryUtil.memLongBuffer(ncalloc(8, capacity, 8), capacity));
	}

	public ByteBuffer UTF8(CharSequence text) {
		return UTF8(text, true);
	}

	public ByteBuffer UTF8(CharSequence text, boolean nullTerminated) {
		ByteBuffer encoded = MemoryUtil.memUTF8(text, nullTerminated);
		record(MemoryUtil.memAddress(encoded));
		return encoded;
	}

	public ByteBuffer ASCII(CharSequence text) {
		return ASCII(text, true);
	}

	public ByteBuffer ASCII(CharSequence text, boolean nullTerminated) {
		int length = text.length();
		ByteBuffer buffer = malloc(length + (nullTerminated ? 1 : 0));
		for (int i = 0; i < length; i++) {
			buffer.put(i, (byte) text.charAt(i));
		}
		if (nullTerminated) {
			buffer.put(length, (byte) 0);
		}
		return buffer;
	}
}
