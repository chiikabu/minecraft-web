// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.timeout;

import io.netty.channel.ChannelException;

public class TimeoutException extends ChannelException {
	public TimeoutException() {
	}

	public TimeoutException(final String message) {
		super(message);
	}
}
