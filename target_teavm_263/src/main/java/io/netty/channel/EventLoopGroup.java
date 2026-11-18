// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public interface EventLoopGroup {
	ChannelFuture register(Channel channel);

	void execute(Runnable task);

	ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit);

	ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit);

	Future<?> shutdownGracefully();
}
