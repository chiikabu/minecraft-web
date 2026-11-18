// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.local;

import io.netty.channel.IoHandlerFactory;

public final class LocalIoHandler {
	private static final IoHandlerFactory FACTORY = new IoHandlerFactory() {
	};

	private LocalIoHandler() {
	}

	public static IoHandlerFactory newFactory() {
		return FACTORY;
	}
}
