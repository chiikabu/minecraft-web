// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.ArrayList;
import java.util.List;

public class DefaultChannelPromise implements ChannelPromise {
	private final Channel channel;
	private final boolean voidPromise;
	private List<GenericFutureListener<? extends Future<? super Void>>> listeners;
	private boolean done;
	private Throwable cause;

	public DefaultChannelPromise(final Channel channel) {
		this(channel, false);
	}

	DefaultChannelPromise(final Channel channel, final boolean voidPromise) {
		this.channel = channel;
		this.voidPromise = voidPromise;
	}

	public static DefaultChannelPromise succeeded(final Channel channel) {
		DefaultChannelPromise promise = new DefaultChannelPromise(channel);
		promise.setSuccess();
		return promise;
	}

	public static DefaultChannelPromise failed(final Channel channel, final Throwable cause) {
		DefaultChannelPromise promise = new DefaultChannelPromise(channel);
		promise.setFailure(cause);
		return promise;
	}

	@Override
	public Channel channel() {
		return this.channel;
	}

	@Override
	public boolean isVoid() {
		return this.voidPromise;
	}

	@Override
	public boolean isDone() {
		return this.done;
	}

	@Override
	public boolean isSuccess() {
		return this.done && this.cause == null;
	}

	@Override
	public Throwable cause() {
		return this.cause;
	}

	@Override
	public ChannelPromise setSuccess() {
		return this.setSuccess(null);
	}

	@Override
	public ChannelPromise setSuccess(final Void result) {
		this.complete(null);
		return this;
	}

	@Override
	public boolean trySuccess() {
		if (this.done) {
			return false;
		}
		this.complete(null);
		return true;
	}

	@Override
	public ChannelPromise setFailure(final Throwable cause) {
		this.complete(cause);
		return this;
	}

	@Override
	public boolean tryFailure(final Throwable cause) {
		if (this.done) {
			return false;
		}
		this.complete(cause);
		return true;
	}

	private void complete(final Throwable cause) {
		if (this.voidPromise) {
			this.cause = cause;
			return;
		}
		if (this.done) {
			return;
		}
		this.done = true;
		this.cause = cause;
		this.notifyListeners();
	}

	private void notifyListeners() {
		if (this.listeners == null) {
			return;
		}
		List<GenericFutureListener<? extends Future<? super Void>>> pending = this.listeners;
		this.listeners = null;
		for (GenericFutureListener<? extends Future<? super Void>> listener : pending) {
			notifyListener(this, listener);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void notifyListener(final ChannelPromise promise, final GenericFutureListener<?> listener) {
		try {
			((GenericFutureListener)listener).operationComplete(promise);
		} catch (Throwable var3) {
			promise.channel().pipeline().fireExceptionCaught(var3);
		}
	}

	@Override
	public ChannelPromise addListener(final GenericFutureListener<? extends Future<? super Void>> listener) {
		if (this.voidPromise) {
			throw new IllegalStateException("void future");
		}
		if (this.done) {
			notifyListener(this, listener);
			return this;
		}
		if (this.listeners == null) {
			this.listeners = new ArrayList<>(2);
		}
		this.listeners.add(listener);
		return this;
	}

	@Override
	public ChannelPromise removeListener(final GenericFutureListener<? extends Future<? super Void>> listener) {
		if (this.listeners != null) {
			this.listeners.remove(listener);
		}
		return this;
	}

	@Override
	public boolean cancel(final boolean mayInterruptIfRunning) {
		return false;
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Override
	public ChannelPromise sync() {
		return this.syncUninterruptibly();
	}

	@Override
	public ChannelPromise syncUninterruptibly() {
		if (this.cause != null) {
			rethrow(this.cause);
		}
		return this;
	}

	@Override
	public ChannelPromise await() {
		return this;
	}

	@Override
	public ChannelPromise awaitUninterruptibly() {
		return this;
	}

	@Override
	public ChannelPromise unvoid() {
		return this.voidPromise ? new DefaultChannelPromise(this.channel) : this;
	}

	private static void rethrow(final Throwable cause) {
		if (cause instanceof RuntimeException runtime) {
			throw runtime;
		}
		if (cause instanceof Error error) {
			throw error;
		}
		throw new ChannelException(cause.getMessage(), cause);
	}
}
