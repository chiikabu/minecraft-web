// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;

public final class SDL_DialogFileFilter extends Struct<SDL_DialogFileFilter> {
    public static final int SIZEOF = 16;
    private final long address;

    private SDL_DialogFileFilter(long address) {
        this.address = address;
    }

    public SDL_DialogFileFilter name(ByteBuffer value) {
        MemoryUtil.memPutLong(address, MemoryUtil.memAddress(value));
        return this;
    }

    public SDL_DialogFileFilter pattern(ByteBuffer value) {
        MemoryUtil.memPutLong(address + 8, MemoryUtil.memAddress(value));
        return this;
    }

    public ByteBuffer name() {
        return stringBuffer(MemoryUtil.memGetAddress(address));
    }

    public ByteBuffer pattern() {
        return stringBuffer(MemoryUtil.memGetAddress(address + 8));
    }

    private static ByteBuffer stringBuffer(long pointer) {
        if (pointer == 0) return null;
        int length = 0;
        while (MemoryUtil.memGetByte(pointer + length) != 0) length++;
        return MemoryUtil.memByteBuffer(pointer, length);
    }

    public static Buffer calloc(int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Negative filter capacity");
        return new Buffer(MemoryUtil.memCalloc(capacity, SIZEOF), capacity);
    }

    public static final class Buffer implements AutoCloseable {
        private final ByteBuffer memory;
        private final int capacity;

        private Buffer(ByteBuffer memory, int capacity) {
            this.memory = memory;
            this.capacity = capacity;
        }

        public Struct<?> get(int index) {
            if (index < 0 || index >= capacity) throw new IndexOutOfBoundsException();
            return new SDL_DialogFileFilter(address() + (long) index * SIZEOF);
        }

        public long address() { return MemoryUtil.memAddress(memory); }
        public int capacity() { return capacity; }
        public int remaining() { return capacity; }
        public void free() { MemoryUtil.memFree(memory); }
        @Override public void close() { free(); }
    }
}
