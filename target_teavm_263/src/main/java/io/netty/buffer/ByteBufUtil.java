// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.buffer;

import io.netty.util.IllegalReferenceCountException;

import java.nio.charset.StandardCharsets;

public final class ByteBufUtil {
	private ByteBufUtil() {
	}

	public static <T extends ByteBuf> T ensureAccessible(final T buffer) {
		if (buffer.refCnt() <= 0) {
			throw new IllegalReferenceCountException(buffer.refCnt());
		}
		return buffer;
	}

	public static int utf8MaxBytes(final int seqLength) {
		return seqLength * 3;
	}

	public static int utf8MaxBytes(final CharSequence seq) {
		return utf8MaxBytes(seq.length());
	}

	public static int utf8Bytes(final CharSequence seq) {
		return seq.toString().getBytes(StandardCharsets.UTF_8).length;
	}

	public static int writeUtf8(final ByteBuf buffer, final CharSequence seq) {
		byte[] bytes = seq.toString().getBytes(StandardCharsets.UTF_8);
		buffer.writeBytes(bytes);
		return bytes.length;
	}

	public static byte[] getBytes(final ByteBuf buffer) {
		return getBytes(buffer, buffer.readerIndex(), buffer.readableBytes());
	}

	public static byte[] getBytes(final ByteBuf buffer, final int start, final int length) {
		byte[] out = new byte[length];
		buffer.getBytes(start, out);
		return out;
	}

	public static boolean equals(final ByteBuf a, final ByteBuf b) {
		return a.equals(b);
	}

	public static int compare(final ByteBuf a, final ByteBuf b) {
		return a.compareTo(b);
	}

	public static int hashCode(final ByteBuf buffer) {
		return buffer.hashCode();
	}

	public static String hexDump(final ByteBuf buffer) {
		StringBuilder out = new StringBuilder();
		for (int i = buffer.readerIndex(); i < buffer.writerIndex(); i++) {
			int value = buffer.getUnsignedByte(i);
			if (value < 16) {
				out.append('0');
			}
			out.append(Integer.toHexString(value));
		}
		return out.toString();
	}
}
