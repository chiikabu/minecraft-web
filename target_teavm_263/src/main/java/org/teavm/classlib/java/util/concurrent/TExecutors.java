// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;
import org.teavm.classlib.impl.Trampoline;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class TExecutors {
	private TExecutors() {
	}

	public static <T> Callable<T> callable(Runnable task, T result) {
		Objects.requireNonNull(task);
		return () -> { task.run(); return result; };
	}

	public static Callable<Object> callable(Runnable task) { return callable(task, null); }

	public static ExecutorService newFixedThreadPool(int n) { return newFixedThreadPool(n, defaultThreadFactory()); }
	public static ExecutorService newFixedThreadPool(int n, ThreadFactory f) {
		return new TThreadPoolExecutor(n, n, 0, TimeUnit.MILLISECONDS,
				new java.util.concurrent.LinkedBlockingQueue<Runnable>(), f);
	}
	public static ExecutorService newCachedThreadPool() { return new DirectExecutorService(); }
	public static ExecutorService newCachedThreadPool(ThreadFactory f) { return new DirectExecutorService(); }
	public static ExecutorService newSingleThreadExecutor() { return newSingleThreadExecutor(defaultThreadFactory()); }
	public static ExecutorService newSingleThreadExecutor(ThreadFactory f) { return newFixedThreadPool(1, f); }
	public static ThreadFactory defaultThreadFactory() { return Thread::new; }
	public static TScheduledExecutorService newSingleThreadScheduledExecutor() { return new TScheduledThreadPoolExecutor(1); }
	public static TScheduledExecutorService newSingleThreadScheduledExecutor(ThreadFactory f) { return new TScheduledThreadPoolExecutor(1, f); }
	public static TScheduledExecutorService newScheduledThreadPool(int n) { return new TScheduledThreadPoolExecutor(n); }
	public static TScheduledExecutorService newScheduledThreadPool(int n, ThreadFactory f) { return new TScheduledThreadPoolExecutor(n, f); }

	static class DirectExecutorService extends TAbstractExecutorService {
		private boolean shutdown;

		@Override public void execute(Runnable command) { Trampoline.execute(command); }
		@Override public void shutdown() { shutdown = true; }
		@Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
		@Override public boolean isShutdown() { return shutdown; }
		@Override public boolean isTerminated() { return shutdown; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
	}

}
