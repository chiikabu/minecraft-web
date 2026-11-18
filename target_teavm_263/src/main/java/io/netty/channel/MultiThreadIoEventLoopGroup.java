// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class MultiThreadIoEventLoopGroup implements EventLoopGroup {
	public MultiThreadIoEventLoopGroup(final IoHandlerFactory ioHandlerFactory) {
	}

	public MultiThreadIoEventLoopGroup(final int nThreads, final IoHandlerFactory ioHandlerFactory) {
	}

	public MultiThreadIoEventLoopGroup(final ThreadFactory threadFactory, final IoHandlerFactory ioHandlerFactory) {
	}

	public MultiThreadIoEventLoopGroup(final Executor executor, final IoHandlerFactory ioHandlerFactory) {
	}

	public MultiThreadIoEventLoopGroup(final int nThreads, final ThreadFactory threadFactory, final IoHandlerFactory ioHandlerFactory) {
	}

	public MultiThreadIoEventLoopGroup(final int nThreads, final Executor executor, final IoHandlerFactory ioHandlerFactory) {
	}

	public EventLoop next() {
		return InlineEventLoop.INSTANCE;
	}

	@Override
	public ChannelFuture register(final Channel channel) {
		return InlineEventLoop.INSTANCE.register(channel);
	}

	@Override
	public void execute(final Runnable task) {
		InlineEventLoop.INSTANCE.execute(task);
	}

	@Override
	public ScheduledFuture<?> schedule(final Runnable task, final long delay, final TimeUnit unit) {
		return InlineEventLoop.INSTANCE.schedule(task, delay, unit);
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(final Runnable task, final long initialDelay, final long period, final TimeUnit unit) {
		return InlineEventLoop.INSTANCE.scheduleAtFixedRate(task, initialDelay, period, unit);
	}

	@Override
	public Future<?> shutdownGracefully() {
		return InlineEventLoop.INSTANCE.shutdownGracefully();
	}
}
