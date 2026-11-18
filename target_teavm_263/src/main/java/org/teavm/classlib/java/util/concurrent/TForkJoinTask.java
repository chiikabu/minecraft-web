// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.teavm.classlib.impl.Trampoline;

public abstract class TForkJoinTask<V> implements Future<V>, Serializable {
    private static TForkJoinPool currentPool;
    private final Completion completion = new Completion();
    private boolean running;

    private static final class Completion extends TFutureTask<Void> {
        Completion() { super(() -> null); }
        void succeed() { set(null); }
        void fail(Throwable error) { setException(error); }
    }

    public TForkJoinTask() { }

    public abstract V getRawResult();
    protected abstract void setRawResult(V value);
    protected abstract boolean exec();

    public static TForkJoinPool getPool() { return currentPool; }

    public final TForkJoinTask<V> fork() {
        return forkIn(currentPool != null ? currentPool : TForkJoinPool.commonPool());
    }

    final TForkJoinTask<V> forkIn(TForkJoinPool pool) {
        Trampoline.execute(() -> executeIn(pool));
        return this;
    }

    private void executeIn(TForkJoinPool pool) {
        if (isDone() || running) return;
        TForkJoinPool previous = currentPool;
        currentPool = pool;
        running = true;
        try {
            if (exec()) completion.succeed();
        } catch (Throwable error) {
            completion.fail(error);
        } finally {
            running = false;
            currentPool = previous;
        }
    }

    public final V invoke() { return invokeIn(currentPool); }

    final V invokeIn(TForkJoinPool pool) {
        executeIn(pool);
        return join();
    }

    private void awaitCompletion() {
        if (!isDone()) Trampoline.drainPending();
        if (!isDone()) throw new IllegalStateException("cannot block on an incomplete fork/join task on the browser thread");
    }

    public final V join() {
        awaitCompletion();
        try {
            completion.get();
        } catch (ExecutionException error) {
            TForkJoinTask.<RuntimeException>rethrow(error.getCause());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
        return getRawResult();
    }

    @Override
    public final V get() throws InterruptedException, ExecutionException {
        if (!isDone() && Thread.interrupted()) throw new InterruptedException();
        awaitCompletion();
        completion.get();
        return getRawResult();
    }

    @Override
    public final V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit);
        if (!isDone()) {
            if (Thread.interrupted()) throw new InterruptedException();
            long nanos = unit.toNanos(timeout);
            if (nanos <= 0L) throw new TimeoutException();
            long now = System.nanoTime();
            long deadline = now + nanos;
            if (nanos > 0L && deadline < now) deadline = Long.MAX_VALUE;
            Trampoline.drainUntil(deadline);
        }
        completion.get(0L, TimeUnit.NANOSECONDS);
        return getRawResult();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return completion.cancel(false) || completion.isCancelled();
    }

    @Override public final boolean isCancelled() { return completion.isCancelled(); }
    @Override public final boolean isDone() { return completion.isDone(); }

    public static void invokeAll(TForkJoinTask<?> first, TForkJoinTask<?> second) {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        second.fork();
        try {
            first.invoke();
        } catch (Throwable error) {
            second.cancel(false);
            TForkJoinTask.<RuntimeException>rethrow(error);
        }
        second.join();
    }

    public static void invokeAll(TForkJoinTask<?>... tasks) {
        Throwable failure = null;
        for (int i = tasks.length - 1; i >= 0; i--) {
            if (tasks[i] == null) {
                failure = new NullPointerException();
                break;
            }
            if (i != 0) tasks[i].fork();
            else {
                try { tasks[i].invoke(); }
                catch (Throwable error) { failure = error; }
            }
        }
        if (failure == null) {
            for (int i = 1; i < tasks.length; i++) {
                try { tasks[i].join(); }
                catch (Throwable error) { failure = error; break; }
            }
        }
        if (failure != null) {
            for (int i = 1; i < tasks.length; i++) {
                if (tasks[i] != null) tasks[i].cancel(false);
            }
            TForkJoinTask.<RuntimeException>rethrow(failure);
        }
    }

    @SuppressWarnings("unchecked")
    static <E extends Throwable> void rethrow(Throwable error) throws E { throw (E) error; }
}
