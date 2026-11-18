// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class TScheduledThreadPoolExecutor extends TThreadPoolExecutor implements TScheduledExecutorService {
    private long sequence;
    private boolean removeOnCancel;

    public TScheduledThreadPoolExecutor(int core) { this(core, TExecutors.defaultThreadFactory()); }
    public TScheduledThreadPoolExecutor(int core, ThreadFactory factory) { this(core, factory, new AbortPolicy()); }
    public TScheduledThreadPoolExecutor(int core, TRejectedExecutionHandler handler) {
        this(core, TExecutors.defaultThreadFactory(), handler);
    }
    public TScheduledThreadPoolExecutor(int core, ThreadFactory factory, TRejectedExecutionHandler handler) {
        super(core, Integer.MAX_VALUE, Long.MAX_VALUE, TimeUnit.NANOSECONDS, delayedQueue(), factory, handler);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockingQueue<Runnable> delayedQueue() { return (BlockingQueue) new DelayQueue<>(); }

    private void enqueue(Task<?> task) {
        if (isShutdown()) getRejectedExecutionHandler().rejectedExecution(task, this);
        else { getQueue().add(task); ensurePrestart(); }
    }
    @Override public void execute(Runnable command) { schedule(command, 0, TimeUnit.NANOSECONDS); }
    @Override public TScheduledFuture<?> submit(Runnable command) { return schedule(command, 0, TimeUnit.NANOSECONDS); }
    @Override public <V> TScheduledFuture<V> submit(Runnable command, V result) {
        return schedule(TExecutors.callable(command, result), 0, TimeUnit.NANOSECONDS);
    }
    @Override public <V> TScheduledFuture<V> submit(Callable<V> command) { return schedule(command, 0, TimeUnit.NANOSECONDS); }
    @Override public TScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return schedule(TExecutors.callable(command), delay, unit);
    }
    @Override public synchronized <V> TScheduledFuture<V> schedule(Callable<V> command, long delay, TimeUnit unit) {
        Task<V> task = new Task<>(command, unit.toNanos(delay), 0);
        enqueue(task);
        return task;
    }
    @Override public synchronized TScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
            long period, TimeUnit unit) {
        var callable = TExecutors.callable(command);
        Objects.requireNonNull(unit);
        if (period <= 0) throw new IllegalArgumentException();
        Task<?> task = new Task<>(callable, unit.toNanos(initialDelay), unit.toNanos(period));
        enqueue(task);
        return task;
    }
    @Override public synchronized TScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
            long delay, TimeUnit unit) {
        var callable = TExecutors.callable(command);
        Objects.requireNonNull(unit);
        if (delay <= 0) throw new IllegalArgumentException();
        Task<?> task = new Task<>(callable, unit.toNanos(initialDelay), -unit.toNanos(delay));
        enqueue(task);
        return task;
    }
    private final class Task<V> extends TFutureTask<V> implements TScheduledFuture<V> {
        private long deadline;
        private final long period;
        private final long order = sequence++;
        Task(Callable<V> command, long delay, long period) {
            super(command);
            deadline = System.nanoTime() + Math.max(0, delay);
            this.period = period;
        }
        public long getDelay(TimeUnit unit) { return unit.convert(deadline - System.nanoTime(), TimeUnit.NANOSECONDS); }
        public int compareTo(Delayed other) {
            if (other == this) return 0;
            if (other instanceof TScheduledThreadPoolExecutor.Task<?> task) {
                long now = System.nanoTime();
                int comparison = Long.compare(deadline - now, task.deadline - now);
                return comparison != 0 ? comparison : Long.compare(order, task.order);
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
        @Override public void run() {
            if (period == 0) { super.run(); return; }
            if (isShutdown()) { cancel(false); return; }
            if (runAndReset()) {
                synchronized (TScheduledThreadPoolExecutor.this) {
                    if (isShutdown()) cancel(false);
                    else {
                        deadline = period > 0 ? deadline + period : System.nanoTime() - period;
                        enqueue(this);
                    }
                }
            }
        }
        @Override public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            synchronized (TScheduledThreadPoolExecutor.this) {
                if (cancelled && (removeOnCancel || isShutdown())) remove(this);
            }
            return cancelled;
        }
    }
    public synchronized void setRemoveOnCancelPolicy(boolean value) { removeOnCancel = value; }
    public synchronized boolean getRemoveOnCancelPolicy() { return removeOnCancel; }
    @Override public synchronized void shutdown() {
        for (Runnable entry : new ArrayList<>(getQueue())) {
            Task<?> task = (Task<?>) entry;
            if (task.period != 0 || task.isCancelled()) { task.cancel(false); remove(task); }
        }
        super.shutdown();
    }
    @Override public synchronized List<Runnable> shutdownNow() {
        List<Runnable> pending = super.shutdownNow();
        for (Runnable entry : new ArrayList<>(getQueue())) {
            if (getQueue().remove(entry)) pending.add(entry);
        }
        return pending;
    }
}
