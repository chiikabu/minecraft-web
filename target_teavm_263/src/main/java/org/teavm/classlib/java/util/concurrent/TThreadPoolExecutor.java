// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class TThreadPoolExecutor extends TAbstractExecutorService {
    private final int coreSize;
    private final int maximumSize;
    private final long keepAliveNanos;
    private final BlockingQueue<Runnable> queue;
    private final ThreadFactory factory;
    private final ArrayList<Worker> workers = new ArrayList<>();
    private TRejectedExecutionHandler handler;
    private boolean shutdown;
    private boolean stop;
    private boolean terminated;

    public TThreadPoolExecutor(int core, int maximum, long keepAlive, TimeUnit unit, BlockingQueue<Runnable> queue) {
        this(core, maximum, keepAlive, unit, queue, TExecutors.defaultThreadFactory(), new AbortPolicy());
    }
    public TThreadPoolExecutor(int core, int maximum, long keepAlive, TimeUnit unit,
            BlockingQueue<Runnable> queue, ThreadFactory factory) {
        this(core, maximum, keepAlive, unit, queue, factory, new AbortPolicy());
    }
    public TThreadPoolExecutor(int core, int maximum, long keepAlive, TimeUnit unit,
            BlockingQueue<Runnable> queue, TRejectedExecutionHandler handler) {
        this(core, maximum, keepAlive, unit, queue, TExecutors.defaultThreadFactory(), handler);
    }
    public TThreadPoolExecutor(int core, int maximum, long keepAlive, TimeUnit unit,
            BlockingQueue<Runnable> queue, ThreadFactory factory, TRejectedExecutionHandler handler) {
        if (core < 0 || maximum <= 0 || maximum < core || keepAlive < 0) throw new IllegalArgumentException();
        coreSize = core;
        maximumSize = maximum;
        keepAliveNanos = unit.toNanos(keepAlive);
        this.queue = Objects.requireNonNull(queue);
        this.factory = Objects.requireNonNull(factory);
        this.handler = Objects.requireNonNull(handler);
    }

    @Override public void execute(Runnable task) {
        Objects.requireNonNull(task);
        synchronized (this) {
            if (!shutdown) {
                if (workers.size() < coreSize && addWorker(task, true)) return;
                if (queue.offer(task)) {
                    if (workers.isEmpty()) addWorker(null, false);
                    return;
                }
                if (workers.size() < maximumSize && addWorker(task, false)) return;
            }
        }
        getRejectedExecutionHandler().rejectedExecution(task, this);
    }

    final synchronized void ensurePrestart() {
        if (workers.size() < coreSize) addWorker(null, true);
        else if (workers.isEmpty()) addWorker(null, false);
    }

    private boolean addWorker(Runnable first, boolean core) {
        Worker worker = new Worker(first, core);
        worker.thread = factory.newThread(worker);
        if (worker.thread == null) return false;
        workers.add(worker);
        try { worker.thread.start(); }
        catch (RuntimeException | Error failure) { workers.remove(worker); throw failure; }
        return true;
    }

    private final class Worker implements Runnable {
        Runnable first;
        final boolean core;
        Thread thread;
        boolean busy;
        Worker(Runnable first, boolean core) { this.first = first; this.core = core; }

        Runnable next() {
            for (;;) {
                synchronized (TThreadPoolExecutor.this) {
                    if (stop || shutdown && queue.isEmpty()) return null;
                }
                Runnable task = queue.poll();
                if (task == null) web263ArmWake();
                return task;
            }
        }

        public void run() {
            boolean abrupt = true;
            try {
                Runnable task = first;
                first = null;
                while (task != null || (task = next()) != null) {
                    synchronized (TThreadPoolExecutor.this) {
                        busy = true;
                        if (!stop) Thread.interrupted();
                        else Thread.currentThread().interrupt();
                    }
                    try {
                        beforeExecute(thread, task);
                        Throwable failure = null;
                        try { task.run(); }
                        catch (RuntimeException | Error error) { failure = error; throw error; }
                        finally { afterExecute(task, failure); }
                    } finally {
                        synchronized (TThreadPoolExecutor.this) { busy = false; }
                        task = null;
                    }
                }
                abrupt = false;
            } finally {
                synchronized (TThreadPoolExecutor.this) {
                    workers.remove(this);
                    if (!stop && (!shutdown && abrupt || web263HasReadyTask()) && workers.size() < maximumSize) {
                        addWorker(null, workers.size() < coreSize);
                    }
                    finishTermination();
                }
            }
        }
    }

    private boolean web263HasReadyTask() {
        Runnable head = queue.peek();
        if (head == null) return false;
        return !(head instanceof java.util.concurrent.Delayed) || ((java.util.concurrent.Delayed) head).getDelay(TimeUnit.NANOSECONDS) <= 0;
    }

    private void web263ArmWake() {
        Runnable head = queue.peek();
        if (!(head instanceof java.util.concurrent.Delayed)) return;
        long delayMillis = Math.max(0L, (((java.util.concurrent.Delayed) head).getDelay(TimeUnit.NANOSECONDS) + 999_999L) / 1_000_000L);
        org.teavm.runtime.EventQueue.offer(() -> {
            synchronized (TThreadPoolExecutor.this) {
                if (!shutdown && workers.isEmpty() && !queue.isEmpty()) {
                    if (web263HasReadyTask()) ensurePrestart();
                    else web263ArmWake();
                }
            }
        }, System.currentTimeMillis() + delayMillis);
    }

    @Override public synchronized void shutdown() {
        shutdown = true;
        for (Worker worker : workers) if (!worker.busy) worker.thread.interrupt();
        finishTermination();
    }
    @Override public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        stop = true;
        for (Worker worker : workers) worker.thread.interrupt();
        List<Runnable> pending = new ArrayList<>();
        queue.drainTo(pending);
        finishTermination();
        return pending;
    }
    private void finishTermination() {
        if (shutdown && queue.isEmpty()) {
            for (Worker worker : workers) if (!worker.busy) worker.thread.interrupt();
        }
        if (!terminated && shutdown && workers.isEmpty() && (stop || queue.isEmpty())) {
            terminated = true;
            try { terminated(); } finally { notifyAll(); }
        }
    }
    @Override public synchronized boolean isShutdown() { return shutdown; }
    @Override public synchronized boolean isTerminated() { return terminated; }
    @Override public synchronized boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        if (Thread.interrupted()) throw new InterruptedException();
        long start = System.nanoTime();
        long remaining = nanos;
        while (!terminated) {
            if (remaining <= 0 || !org.teavm.classlib.impl.Trampoline.waitStep("ThreadPoolExecutor.awaitTermination")) return false;
            remaining = nanos - (System.nanoTime() - start);
        }
        return true;
    }
    public BlockingQueue<Runnable> getQueue() { return queue; }
    public synchronized int getPoolSize() { return workers.size(); }
    public synchronized int getActiveCount() {
        int count = 0;
        for (Worker worker : workers) if (worker.busy) count++;
        return count;
    }
    public synchronized TRejectedExecutionHandler getRejectedExecutionHandler() { return handler; }
    public synchronized void setRejectedExecutionHandler(TRejectedExecutionHandler handler) {
        this.handler = Objects.requireNonNull(handler);
    }
    public synchronized boolean remove(Runnable task) {
        boolean removed = queue.remove(task);
        finishTermination();
        return removed;
    }
    public synchronized void purge() {
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof Future<?> future && future.isCancelled()) iterator.remove();
        }
        finishTermination();
    }
    protected void beforeExecute(Thread thread, Runnable task) { }
    protected void afterExecute(Runnable task, Throwable failure) { }
    protected void terminated() { }

    public static class AbortPolicy implements TRejectedExecutionHandler {
        public void rejectedExecution(Runnable task, TThreadPoolExecutor executor) {
            throw new TRejectedExecutionException("Task rejected by executor");
        }
    }
    public static class CallerRunsPolicy implements TRejectedExecutionHandler {
        public void rejectedExecution(Runnable task, TThreadPoolExecutor executor) {
            if (!executor.isShutdown()) task.run();
        }
    }
    public static class DiscardPolicy implements TRejectedExecutionHandler {
        public void rejectedExecution(Runnable task, TThreadPoolExecutor executor) { }
    }
}
