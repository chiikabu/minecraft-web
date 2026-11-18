// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.TimeUnit;

public class TCountDownLatch {
    private int count;

    public TCountDownLatch(int count) {
        if (count < 0) throw new IllegalArgumentException("count < 0");
        this.count = count;
    }

    public synchronized void await() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        while (count != 0) {
            if (!org.teavm.classlib.impl.Trampoline.waitStep("CountDownLatch.await")) throw org.teavm.classlib.impl.Trampoline.wouldBlockForever("CountDownLatch.await");
        }
    }

    public synchronized boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        if (Thread.interrupted()) throw new InterruptedException();
        long start = System.nanoTime();
        while (count != 0) {
            if (remaining <= 0 || !org.teavm.classlib.impl.Trampoline.waitStep("CountDownLatch.await(timeout)")) return false;
            remaining = unit.toNanos(timeout) - (System.nanoTime() - start);
        }
        return true;
    }

    public synchronized void countDown() {
        if (count != 0 && --count == 0) notifyAll();
    }

    public synchronized long getCount() { return count; }

    @Override
    public String toString() { return super.toString() + "[Count = " + getCount() + "]"; }
}
