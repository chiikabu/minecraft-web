// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;
import org.teavm.classlib.impl.Trampoline;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class TForkJoinPool extends TAbstractExecutorService {
	private static final TForkJoinPool COMMON = new TForkJoinPool();

	public TForkJoinPool() {
	}

	public TForkJoinPool(int parallelism) {
	}

	public TForkJoinPool(int parallelism, ForkJoinWorkerThreadFactory factory, Thread.UncaughtExceptionHandler handler, boolean asyncMode) {
	}

	public interface ForkJoinWorkerThreadFactory {
		TForkJoinWorkerThread newThread(TForkJoinPool pool);
	}

	public static TForkJoinPool commonPool() { return COMMON; }
	public static int getCommonPoolParallelism() { return 1; }
	public int getParallelism() { return 1; }
	public <T> T invoke(TForkJoinTask<T> task) { return Objects.requireNonNull(task).invokeIn(this); }

	@Override
	public <T> TForkJoinTask<T> submit(Callable<T> callable) {
		Objects.requireNonNull(callable);
		return new TForkJoinTask<T>() {
			private T value;
			@Override public T getRawResult() { return value; }
			@Override protected void setRawResult(T value) { this.value = value; }
			@Override protected boolean exec() {
				try { value = callable.call(); return true; }
				catch (Throwable error) { TForkJoinTask.<RuntimeException>rethrow(error); return false; }
			}
		}.forkIn(this);
	}
	@Override public void execute(Runnable command) { Trampoline.execute(command); }
	@Override public void shutdown() { }
	@Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
	@Override public boolean isShutdown() { return false; }
	@Override public boolean isTerminated() { return false; }
	@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
}
