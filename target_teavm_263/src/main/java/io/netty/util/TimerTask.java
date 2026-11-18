// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

public interface TimerTask {
	void run(Timeout timeout) throws Exception;
}
