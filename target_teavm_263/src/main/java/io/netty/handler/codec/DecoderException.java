// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec;

public class DecoderException extends RuntimeException {
	public DecoderException() {
	}

	public DecoderException(final String message) {
		super(message);
	}

	public DecoderException(final String message, final Throwable cause) {
		super(message, cause);
	}

	public DecoderException(final Throwable cause) {
		super(cause);
	}
}
