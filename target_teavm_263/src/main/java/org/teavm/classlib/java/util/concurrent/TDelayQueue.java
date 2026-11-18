// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

public class TDelayQueue<E extends Delayed> extends AbstractQueue<E> implements BlockingQueue<E> {
    private final PriorityQueue<E> queue = new PriorityQueue<>();

    public TDelayQueue() { }
    public TDelayQueue(Collection<? extends E> values) { addAll(values); }

    @Override public boolean add(E value) { return offer(value); }
    @Override public synchronized boolean offer(E value) {
        queue.offer(Objects.requireNonNull(value));
        notifyAll();
        return true;
    }
    @Override public void put(E value) { offer(value); }
    @Override public boolean offer(E value, long timeout, TimeUnit unit) { return offer(value); }
    @Override public int remainingCapacity() { return Integer.MAX_VALUE; }
    @Override public synchronized int size() { return queue.size(); }
    @Override public synchronized E peek() { return queue.peek(); }
    @Override public E remove() { return super.remove(); }
    @Override public synchronized E poll() {
        E first = queue.peek();
        return first != null && first.getDelay(TimeUnit.NANOSECONDS) <= 0 ? queue.poll() : null;
    }
    @Override public synchronized E take() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        for (;;) {
            E first = queue.peek();
            if (first != null && first.getDelay(TimeUnit.NANOSECONDS) <= 0) return queue.poll();
            if (!org.teavm.classlib.impl.Trampoline.waitStep("DelayQueue.take")) throw org.teavm.classlib.impl.Trampoline.wouldBlockForever("DelayQueue.take");
        }
    }
    @Override public synchronized E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long budget = unit.toNanos(timeout);
        if (Thread.interrupted()) throw new InterruptedException();
        long start = System.nanoTime();
        for (;;) {
            E first = queue.peek();
            long delay = first == null ? Long.MAX_VALUE : first.getDelay(TimeUnit.NANOSECONDS);
            if (delay <= 0) return queue.poll();
            if (budget <= 0) return null;
            long remaining = budget - (System.nanoTime() - start);
            if (remaining <= 0) return null;
            if (!org.teavm.classlib.impl.Trampoline.waitStep("DelayQueue.poll(timeout)")) return null;
        }
    }
    @Override public synchronized boolean remove(Object value) {
        boolean removed = queue.remove(value);
        if (removed) notifyAll();
        return removed;
    }
    @Override public synchronized void clear() { queue.clear(); notifyAll(); }
    @Override public int drainTo(Collection<? super E> target) { return drainTo(target, Integer.MAX_VALUE); }
    @Override public synchronized int drainTo(Collection<? super E> target, int limit) {
        Objects.requireNonNull(target);
        if (target == this) throw new IllegalArgumentException();
        int count = 0;
        while (count < limit) {
            E first = queue.peek();
            if (first == null || first.getDelay(TimeUnit.NANOSECONDS) > 0) break;
            target.add(first);
            queue.poll();
            count++;
        }
        return count;
    }
    @Override public synchronized Iterator<E> iterator() {
        Iterator<E> snapshot = new ArrayList<>(queue).iterator();
        return new Iterator<E>() {
            E last;
            boolean removable;
            public boolean hasNext() { return snapshot.hasNext(); }
            public E next() { last = snapshot.next(); removable = true; return last; }
            public void remove() {
                if (!removable) throw new IllegalStateException();
                synchronized (TDelayQueue.this) {
                    Iterator<E> live = queue.iterator();
                    while (live.hasNext()) {
                        if (live.next() == last) { live.remove(); TDelayQueue.this.notifyAll(); break; }
                    }
                }
                removable = false;
            }
        };
    }
}
