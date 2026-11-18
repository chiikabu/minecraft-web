// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.kqueue;

public final class KQueue {
	private KQueue() {
	}

	public static boolean isAvailable() {
		return false;
	}
}
