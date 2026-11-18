// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

public class ChannelException extends RuntimeException {
	public ChannelException() {
	}

	public ChannelException(final String message) {
		super(message);
	}

	public ChannelException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
