// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

public final class ReferenceCountUtil {
	private ReferenceCountUtil() {
	}

	public static boolean release(final Object msg) {
		return msg instanceof ReferenceCounted counted && counted.release();
	}

	public static boolean release(final Object msg, final int decrement) {
		return msg instanceof ReferenceCounted counted && counted.release(decrement);
	}

	@SuppressWarnings("unchecked")
	public static <T> T retain(final T msg) {
		if (msg instanceof ReferenceCounted counted) {
			return (T)counted.retain();
		}
		return msg;
	}

	@SuppressWarnings("unchecked")
	public static <T> T touch(final T msg) {
		if (msg instanceof ReferenceCounted counted) {
			return (T)counted.touch();
		}
		return msg;
	}

	public static void safeRelease(final Object msg) {
		try {
			release(msg);
		} catch (Throwable var2) {
		}
	}
}
