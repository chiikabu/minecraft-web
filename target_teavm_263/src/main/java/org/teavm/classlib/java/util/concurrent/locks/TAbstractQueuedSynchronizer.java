// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent.locks;

public abstract class TAbstractQueuedSynchronizer extends TAbstractOwnableSynchronizer {
    private int state;

    protected TAbstractQueuedSynchronizer() { }
    protected final synchronized int getState() { return state; }
    protected final synchronized void setState(int state) { this.state = state; }
    protected final synchronized boolean compareAndSetState(int expected, int update) {
        if (state != expected) return false;
        state = update;
        return true;
    }

    protected int tryAcquireShared(int arg) { throw new UnsupportedOperationException(); }
    protected boolean tryReleaseShared(int arg) { throw new UnsupportedOperationException(); }

    public final synchronized void acquireShared(int arg) {
        boolean interrupted = false;
        try {
            while (tryAcquireShared(arg) < 0) {
                if (!org.teavm.classlib.impl.Trampoline.waitStep("AbstractQueuedSynchronizer.acquireShared")) {
                    throw org.teavm.classlib.impl.Trampoline.wouldBlockForever("AbstractQueuedSynchronizer.acquireShared");
                }
                if (Thread.interrupted()) interrupted = true;
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public final synchronized void acquireSharedInterruptibly(int arg) throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        while (tryAcquireShared(arg) < 0) {
            if (!org.teavm.classlib.impl.Trampoline.waitStep("AbstractQueuedSynchronizer.acquireSharedInterruptibly")) {
                throw org.teavm.classlib.impl.Trampoline.wouldBlockForever("AbstractQueuedSynchronizer.acquireSharedInterruptibly");
            }
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }

    public final synchronized boolean tryAcquireSharedNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        long start = System.nanoTime();
        long remaining = nanosTimeout;
        while (tryAcquireShared(arg) < 0) {
            if (remaining <= 0 || !org.teavm.classlib.impl.Trampoline.waitStep("AbstractQueuedSynchronizer.tryAcquireSharedNanos")) return false;
            remaining = nanosTimeout - (System.nanoTime() - start);
        }
        return true;
    }

    public final synchronized boolean releaseShared(int arg) {
        if (!tryReleaseShared(arg)) return false;
        notifyAll();
        return true;
    }
}
