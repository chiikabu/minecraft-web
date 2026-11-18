// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.epoll;

import io.netty.channel.IoHandlerFactory;

public final class EpollIoHandler {
	private EpollIoHandler() {
	}

	public static IoHandlerFactory newFactory() {
		throw new UnsupportedOperationException("unavailable in browser");
	}
}
