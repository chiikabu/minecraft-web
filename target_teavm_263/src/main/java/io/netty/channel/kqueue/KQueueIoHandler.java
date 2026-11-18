// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.kqueue;

import io.netty.channel.IoHandlerFactory;

public final class KQueueIoHandler {
	private KQueueIoHandler() {
	}

	public static IoHandlerFactory newFactory() {
		throw new UnsupportedOperationException("unavailable in browser");
	}
}
