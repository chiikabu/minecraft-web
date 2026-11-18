// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

public interface Timeout {
	Timer timer();

	TimerTask task();

	boolean isExpired();

	boolean isCancelled();

	boolean cancel();
}
