// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec;

public class EncoderException extends RuntimeException {
	public EncoderException() {
	}

	public EncoderException(final String message) {
		super(message);
	}

	public EncoderException(final String message, final Throwable cause) {
		super(message, cause);
	}

	public EncoderException(final Throwable cause) {
		super(cause);
	}
}
