// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.buffer;

public final class Unpooled {
	public static final ByteBuf EMPTY_BUFFER = new ByteBuf(new byte[0]);

	private Unpooled() {
	}

	public static ByteBuf wrappedBuffer(final byte[] array) {
		return new ByteBuf(array);
	}

	public static ByteBuf wrappedBuffer(final byte[] array, final int offset, final int length) {
		ByteBuf buf = new ByteBuf(array);
		return buf.slice(offset, length);
	}

	public static ByteBuf copiedBuffer(final byte[] array) {
		return new ByteBuf(array.clone());
	}

	public static ByteBuf buffer() {
		return new ByteBuf();
	}

	public static ByteBuf buffer(final int initialCapacity) {
		return new ByteBuf(new byte[initialCapacity], true, Integer.MAX_VALUE);
	}

	public static ByteBuf buffer(final int initialCapacity, final int maxCapacity) {
		return new ByteBuf(new byte[initialCapacity], true, maxCapacity);
	}

	public static ByteBuf directBuffer(final int initialCapacity) {
		return buffer(initialCapacity);
	}
}
