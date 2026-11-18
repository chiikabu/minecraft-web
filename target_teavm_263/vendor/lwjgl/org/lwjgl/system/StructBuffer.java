// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.system;

public abstract class StructBuffer<T extends Struct<T>, SELF extends StructBuffer<T, SELF>> extends CustomBuffer implements NativeResource {
	protected final long address;
	protected final int capacity;
	protected int position;
	protected int limit;

	protected StructBuffer(long address, int capacity) {
		if (capacity < 0) {
			throw new IllegalArgumentException("Negative struct buffer capacity");
		}
		this.address = address;
		this.capacity = capacity;
		this.limit = capacity;
	}

	public abstract int sizeof();

	protected abstract T newStruct(long address);

	@SuppressWarnings("unchecked")
	private SELF self() {
		return (SELF) this;
	}

	public long address0() {
		return address;
	}

	@Override
	public long address() {
		return address + (long) position * sizeof();
	}

	public long address(int index) {
		return address + (long) checkIndex(index) * sizeof();
	}

	public int capacity() {
		return capacity;
	}

	public int position() {
		return position;
	}

	public SELF position(int newPosition) {
		if (newPosition < 0 || newPosition > limit) {
			throw new IllegalArgumentException("position out of range");
		}
		position = newPosition;
		return self();
	}

	public int limit() {
		return limit;
	}

	public SELF limit(int newLimit) {
		if (newLimit < 0 || newLimit > capacity) {
			throw new IllegalArgumentException("limit out of range");
		}
		limit = newLimit;
		if (position > limit) {
			position = limit;
		}
		return self();
	}

	public int remaining() {
		return limit - position;
	}

	public boolean hasRemaining() {
		return position < limit;
	}

	public SELF rewind() {
		position = 0;
		return self();
	}

	public SELF flip() {
		limit = position;
		position = 0;
		return self();
	}

	public T get() {
		if (position >= limit) {
			throw new java.nio.BufferUnderflowException();
		}
		return newStruct(address + (long) position++ * sizeof());
	}

	public T get(int index) {
		return newStruct(address + (long) checkIndex(index) * sizeof());
	}

	private int checkIndex(int index) {
		if (index < 0 || index >= limit) {
			throw new IndexOutOfBoundsException("struct index " + index + " outside [0, " + limit + ")");
		}
		return index;
	}

	@Override
	public void free() {
		MemoryUtil.nmemFree(address);
	}
}
