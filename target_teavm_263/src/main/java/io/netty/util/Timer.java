// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

import java.util.Set;
import java.util.concurrent.TimeUnit;

public interface Timer {
	Timeout newTimeout(TimerTask task, long delay, TimeUnit unit);

	Set<Timeout> stop();
}
