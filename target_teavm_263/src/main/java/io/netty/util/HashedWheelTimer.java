// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class HashedWheelTimer implements Timer {
	public HashedWheelTimer() {
	}

	public HashedWheelTimer(final ThreadFactory threadFactory) {
	}

	@Override
	public Timeout newTimeout(final TimerTask task, final long delay, final TimeUnit unit) {
		return new HashedWheelTimer.NeverTimeout(this, task);
	}

	@Override
	public Set<Timeout> stop() {
		return Collections.emptySet();
	}

	private record NeverTimeout(Timer timer, TimerTask task) implements Timeout {
		@Override
		public boolean isExpired() {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean cancel() {
			return true;
		}
	}
}
