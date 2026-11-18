// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec;

public class CorruptedFrameException extends DecoderException {
	public CorruptedFrameException() {
	}

	public CorruptedFrameException(final String message) {
		super(message);
	}

	public CorruptedFrameException(final String message, final Throwable cause) {
		super(message, cause);
	}

	public CorruptedFrameException(final Throwable cause) {
		super(cause);
	}
}
