// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

public class TSemaphore {
    private final Sync sync;

    public TSemaphore(int permits) { this(permits, false); }

    public TSemaphore(int permits, boolean fair) {
        sync = new Sync(permits);
    }

    private static final class Sync extends AbstractQueuedSynchronizer {
        Sync(int permits) { setState(permits); }

        @Override protected int tryAcquireShared(int n) {
            for (;;) {
                int permits = getState();
                if (permits < n) return -1;
                int remaining = permits - n;
                if (compareAndSetState(permits, remaining)) return remaining;
            }
        }

        @Override protected boolean tryReleaseShared(int n) {
            for (;;) {
                int permits = getState();
                long next = (long) permits + n;
                if (next > Integer.MAX_VALUE) throw new Error("Maximum permit count exceeded");
                if (compareAndSetState(permits, (int) next)) return true;
            }
        }

        int available() { return getState(); }

        int drain() {
            for (;;) {
                int permits = getState();
                if (compareAndSetState(permits, 0)) return permits;
            }
        }
    }

    private static void checkPermits(int n) {
        if (n < 0) throw new IllegalArgumentException();
    }

    public void acquire() throws InterruptedException { acquire(1); }

    public void acquire(int n) throws InterruptedException {
        checkPermits(n);
        sync.acquireSharedInterruptibly(n);
    }

    public void acquireUninterruptibly() { acquireUninterruptibly(1); }

    public void acquireUninterruptibly(int n) {
        checkPermits(n);
        sync.acquireShared(n);
    }

    public boolean tryAcquire() { return tryAcquire(1); }

    public boolean tryAcquire(int n) {
        checkPermits(n);
        return sync.tryAcquireShared(n) >= 0;
    }

    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return tryAcquire(1, timeout, unit);
    }

    public boolean tryAcquire(int n, long timeout, TimeUnit unit) throws InterruptedException {
        checkPermits(n);
        return sync.tryAcquireSharedNanos(n, unit.toNanos(timeout));
    }

    public void release() { release(1); }

    public void release(int n) {
        checkPermits(n);
        sync.releaseShared(n);
    }

    public int availablePermits() { return sync.available(); }
    public int drainPermits() { return sync.drain(); }

    @Override public String toString() { return "Semaphore[permits=" + availablePermits() + "]"; }
}
