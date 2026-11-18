// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public interface ChannelPromise extends ChannelFuture {
	ChannelPromise setSuccess();

	ChannelPromise setSuccess(Void result);

	boolean trySuccess();

	ChannelPromise setFailure(Throwable cause);

	boolean tryFailure(Throwable cause);

	ChannelPromise unvoid();

	@Override
	ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener);

	@Override
	ChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener);

	@Override
	ChannelPromise sync() throws InterruptedException;

	@Override
	ChannelPromise syncUninterruptibly();

	@Override
	ChannelPromise await() throws InterruptedException;

	@Override
	ChannelPromise awaitUninterruptibly();
}
