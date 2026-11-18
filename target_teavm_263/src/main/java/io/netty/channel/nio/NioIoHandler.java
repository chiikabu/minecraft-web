// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.nio;

import io.netty.channel.IoHandlerFactory;

public final class NioIoHandler {
	private static final IoHandlerFactory FACTORY = new IoHandlerFactory() {
	};

	private NioIoHandler() {
	}

	public static IoHandlerFactory newFactory() {
		return FACTORY;
	}
}
