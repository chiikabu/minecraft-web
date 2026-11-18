// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.buffer;

public interface ByteBufAllocator {
	ByteBufAllocator DEFAULT = new ByteBufAllocator() {
	};

	default ByteBuf buffer() {
		return Unpooled.buffer();
	}

	default ByteBuf buffer(int initialCapacity) {
		return Unpooled.buffer(initialCapacity);
	}

	default ByteBuf buffer(int initialCapacity, int maxCapacity) {
		return Unpooled.buffer(initialCapacity, maxCapacity);
	}

	default ByteBuf heapBuffer() {
		return Unpooled.buffer();
	}

	default ByteBuf heapBuffer(int initialCapacity) {
		return Unpooled.buffer(initialCapacity);
	}

	default ByteBuf directBuffer() {
		return Unpooled.buffer();
	}

	default ByteBuf directBuffer(int initialCapacity) {
		return Unpooled.buffer(initialCapacity);
	}

	default ByteBuf ioBuffer() {
		return Unpooled.buffer();
	}

	default ByteBuf ioBuffer(int initialCapacity) {
		return Unpooled.buffer(initialCapacity);
	}

	default boolean isDirectBufferPooled() {
		return false;
	}
}
