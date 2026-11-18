// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public interface ChannelFuture extends Future<Void> {
	Channel channel();

	boolean isVoid();

	@Override
	ChannelFuture addListener(GenericFutureListener<? extends Future<? super Void>> listener);

	@Override
	ChannelFuture removeListener(GenericFutureListener<? extends Future<? super Void>> listener);

	@Override
	ChannelFuture sync() throws InterruptedException;

	@Override
	ChannelFuture syncUninterruptibly();

	@Override
	ChannelFuture await() throws InterruptedException;

	@Override
	ChannelFuture awaitUninterruptibly();
}
