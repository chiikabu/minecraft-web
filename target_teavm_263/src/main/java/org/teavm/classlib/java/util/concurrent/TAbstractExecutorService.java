// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;

public abstract class TAbstractExecutorService implements ExecutorService {
	@Override
	public <T> Future<T> submit(final Callable<T> task) {
		RunnableFuture<T> future = newTaskFor(task);
		execute(future);
		return future;
	}

	@Override
	public <T> Future<T> submit(final Runnable task, final T result) {
		RunnableFuture<T> future = newTaskFor(task, result);
		execute(future);
		return future;
	}

	protected <T> RunnableFuture<T> newTaskFor(Callable<T> task) { return new FutureTask<>(task); }
	protected <T> RunnableFuture<T> newTaskFor(Runnable task, T result) { return new FutureTask<>(task, result); }

	@Override
	public Future<?> submit(final Runnable task) {
		return submit(task, null);
	}

	@Override
	public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks, final long timeout, final java.util.concurrent.TimeUnit unit) {
		return invokeAll(tasks);
	}

	@Override
	public <T> T invokeAny(final Collection<? extends Callable<T>> tasks) throws java.util.concurrent.ExecutionException {
		Throwable last = null;
		for (Callable<T> task : tasks) {
			try {
				return task.call();
			} catch (Throwable t) {
				last = t;
			}
		}
		throw new java.util.concurrent.ExecutionException(last);
	}

	@Override
	public <T> T invokeAny(final Collection<? extends Callable<T>> tasks, final long timeout, final java.util.concurrent.TimeUnit unit) throws java.util.concurrent.ExecutionException {
		return invokeAny(tasks);
	}

	@Override
	public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) {
		List<Future<T>> futures = new ArrayList<>();
		for (Callable<T> task : tasks) {
			futures.add(submit(task));
		}
		return futures;
	}
}
