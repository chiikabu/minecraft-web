// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.epoll;

public final class Epoll {
	private Epoll() {
	}

	public static boolean isAvailable() {
		return false;
	}
}
