// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util.concurrent;

public final class ImmediateFuture<V> implements ScheduledFuture<V> {
	private static final ImmediateFuture<Object> NEVER = new ImmediateFuture<>(false);
	private static final ImmediateFuture<Object> SUCCESS = new ImmediateFuture<>(true);

	private final boolean done;

	private ImmediateFuture(final boolean done) {
		this.done = done;
	}

	@SuppressWarnings("unchecked")
	public static <V> ImmediateFuture<V> never() {
		return (ImmediateFuture<V>)NEVER;
	}

	@SuppressWarnings("unchecked")
	public static <V> ImmediateFuture<V> success() {
		return (ImmediateFuture<V>)SUCCESS;
	}

	@Override
	public boolean isDone() {
		return this.done;
	}

	@Override
	public boolean isSuccess() {
		return this.done;
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Override
	public Throwable cause() {
		return null;
	}

	@Override
	public boolean cancel(final boolean mayInterruptIfRunning) {
		return false;
	}

	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public Future<V> addListener(final GenericFutureListener<? extends Future<? super V>> listener) {
		if (this.done) {
			try {
				((GenericFutureListener)listener).operationComplete(this);
			} catch (Throwable var3) {
				throw new RuntimeException(var3);
			}
		}
		return this;
	}

	@Override
	public Future<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
		return this;
	}

	@Override
	public Future<V> sync() {
		return this;
	}

	@Override
	public Future<V> syncUninterruptibly() {
		return this;
	}

	@Override
	public Future<V> await() {
		return this;
	}

	@Override
	public Future<V> awaitUninterruptibly() {
		return this;
	}
}
