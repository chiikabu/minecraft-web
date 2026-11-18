// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.timeout;

public class ReadTimeoutException extends TimeoutException {
	public ReadTimeoutException() {
	}

	public ReadTimeoutException(final String message) {
		super(message);
	}
}
