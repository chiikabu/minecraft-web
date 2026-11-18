// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateFuture;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.teavm.classlib.impl.Trampoline;

public final class InlineEventLoop implements EventLoop {
	public static final InlineEventLoop INSTANCE = new InlineEventLoop();

	private InlineEventLoop() {
	}

	@Override
	public boolean inEventLoop() {
		return true;
	}

	@Override
	public void execute(final Runnable task) {
		Trampoline.execute(task);
	}

	@Override
	public ChannelFuture register(final Channel channel) {
		return DefaultChannelPromise.succeeded(channel);
	}

	@Override
	public ScheduledFuture<?> schedule(final Runnable task, final long delay, final TimeUnit unit) {
		return ImmediateFuture.never();
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(final Runnable task, final long initialDelay, final long period, final TimeUnit unit) {
		return ImmediateFuture.never();
	}

	@Override
	public Future<?> shutdownGracefully() {
		return ImmediateFuture.success();
	}
}
