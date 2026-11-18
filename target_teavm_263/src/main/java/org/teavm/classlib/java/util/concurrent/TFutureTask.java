// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TFutureTask<V> implements RunnableFuture<V> {
    private enum State { PENDING, NORMAL, FAILED, CANCELLED }
    private State state = State.PENDING;
    private Callable<V> callable;
    private Thread runner;
    private V value;
    private Throwable failure;
    private final CountDownLatch completion = new CountDownLatch(1);

    public TFutureTask(Callable<V> callable) { this.callable = Objects.requireNonNull(callable); }
    public TFutureTask(Runnable runnable, V result) {
        Objects.requireNonNull(runnable);
        callable = () -> { runnable.run(); return result; };
    }

    public synchronized boolean isDone() { return state != State.PENDING; }
    public synchronized boolean isCancelled() { return state == State.CANCELLED; }

    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean changed = false;
        try {
            synchronized (this) {
                if (state != State.PENDING) return false;
                state = State.CANCELLED;
                callable = null;
                changed = true;
                if (mayInterruptIfRunning && runner != null) runner.interrupt();
            }
            return true;
        } finally {
            if (changed) finish();
        }
    }

    public V get() throws InterruptedException, ExecutionException {
        if (!isDone()) completion.await();
        return report();
    }

    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Objects.requireNonNull(unit);
        if (!isDone() && !completion.await(timeout, unit)) throw new TimeoutException();
        return report();
    }

    private synchronized V report() throws ExecutionException {
        if (state == State.CANCELLED) throw new CancellationException();
        if (state == State.FAILED) throw new ExecutionException(failure);
        return value;
    }

    protected void set(V value) {
        synchronized (this) {
            if (state != State.PENDING) return;
            this.value = value;
            state = State.NORMAL;
            callable = null;
        }
        finish();
    }

    protected void setException(Throwable failure) {
        synchronized (this) {
            if (state != State.PENDING) return;
            this.failure = failure;
            state = State.FAILED;
            callable = null;
        }
        finish();
    }

    private void finish() {
        completion.countDown();
        done();
    }

    protected void done() { }
    public void run() { execute(false); }
    protected boolean runAndReset() { return execute(true); }

    private boolean execute(boolean reset) {
        Callable<V> task;
        synchronized (this) {
            if (state != State.PENDING || runner != null) return false;
            runner = Thread.currentThread();
            task = callable;
        }
        try {
            if (isDone()) return false;
            V result;
            try { result = task.call(); }
            catch (Throwable error) { setException(error); return false; }
            if (!reset) set(result);
            return !isDone();
        } finally {
            synchronized (this) { runner = null; }
        }
    }
}
