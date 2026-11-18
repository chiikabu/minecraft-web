// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

public class IllegalReferenceCountException extends IllegalStateException {
	public IllegalReferenceCountException() {
	}

	public IllegalReferenceCountException(final int refCnt) {
		this("refCnt: " + refCnt);
	}

	public IllegalReferenceCountException(final int refCnt, final int increment) {
		this("refCnt: " + refCnt + ", " + (increment > 0 ? "increment: " + increment : "decrement: " + -increment));
	}

	public IllegalReferenceCountException(final String message) {
		super(message);
	}

	public IllegalReferenceCountException(final String message, final Throwable cause) {
		super(message, cause);
	}

	public IllegalReferenceCountException(final Throwable cause) {
		super(cause);
	}
}
