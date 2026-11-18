// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util.concurrent;

public interface Future<V> {
	boolean isDone();

	boolean isSuccess();

	boolean isCancelled();

	Throwable cause();

	boolean cancel(boolean mayInterruptIfRunning);

	Future<V> addListener(GenericFutureListener<? extends Future<? super V>> listener);

	Future<V> removeListener(GenericFutureListener<? extends Future<? super V>> listener);

	Future<V> sync() throws InterruptedException;

	Future<V> syncUninterruptibly();

	Future<V> await() throws InterruptedException;

	Future<V> awaitUninterruptibly();
}
