// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class TLinkedBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {
    private static final class Entry<E> {
        final E value;
        Entry(E value) { this.value = value; }
    }
    private final ArrayDeque<Entry<E>> entries = new ArrayDeque<>();
    private final int capacity;

    public TLinkedBlockingQueue() { this(Integer.MAX_VALUE); }
    public TLinkedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }
    public TLinkedBlockingQueue(Collection<? extends E> source) { this(); addAll(source); }

    @Override public synchronized boolean offer(E value) {
        Objects.requireNonNull(value);
        if (entries.size() == capacity) return false;
        entries.add(new Entry<>(value));
        notifyAll();
        return true;
    }
    @Override public synchronized E poll() {
        Entry<E> entry = entries.poll();
        if (entry == null) return null;
        notifyAll();
        return entry.value;
    }
    @Override public synchronized E peek() {
        Entry<E> entry = entries.peek();
        return entry == null ? null : entry.value;
    }
    @Override public synchronized int size() { return entries.size(); }
    @Override public synchronized int remainingCapacity() { return capacity - entries.size(); }
    @Override public synchronized void put(E value) throws InterruptedException {
        Objects.requireNonNull(value);
        if (Thread.interrupted()) throw new InterruptedException();
        while (entries.size() == capacity) {
            if (!org.teavm.classlib.impl.Trampoline.waitStep("LinkedBlockingQueue.put")) throw org.teavm.classlib.impl.Trampoline.wouldBlockForever("LinkedBlockingQueue.put");
        }
        offer(value);
    }
    @Override public synchronized E take() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        while (entries.isEmpty()) {
            if (!org.teavm.classlib.impl.Trampoline.waitStep("LinkedBlockingQueue.take")) throw org.teavm.classlib.impl.Trampoline.wouldBlockForever("LinkedBlockingQueue.take");
        }
        return poll();
    }
    @Override public synchronized boolean offer(E value, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(value);
        long nanos = unit.toNanos(timeout);
        if (Thread.interrupted()) throw new InterruptedException();
        long start = System.nanoTime();
        long remaining = nanos;
        while (entries.size() == capacity) {
            if (remaining <= 0 || !org.teavm.classlib.impl.Trampoline.waitStep("LinkedBlockingQueue.offer(timeout)")) return false;
            remaining = nanos - (System.nanoTime() - start);
        }
        return offer(value);
    }
    @Override public synchronized E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        if (Thread.interrupted()) throw new InterruptedException();
        long start = System.nanoTime();
        long remaining = nanos;
        while (entries.isEmpty()) {
            if (remaining <= 0 || !org.teavm.classlib.impl.Trampoline.waitStep("LinkedBlockingQueue.poll(timeout)")) return null;
            remaining = nanos - (System.nanoTime() - start);
        }
        return poll();
    }
    @Override public synchronized void clear() { entries.clear(); notifyAll(); }
    @Override public int drainTo(Collection<? super E> target) { return drainTo(target, Integer.MAX_VALUE); }
    @Override public synchronized int drainTo(Collection<? super E> target, int limit) {
        Objects.requireNonNull(target);
        if (target == this) throw new IllegalArgumentException("Cannot drain into this queue");
        int count = 0;
        while (count < limit && !entries.isEmpty()) {
            target.add(entries.peek().value);
            entries.remove();
            count++;
            notifyAll();
        }
        return count;
    }
    @Override public synchronized Iterator<E> iterator() {
        Iterator<Entry<E>> snapshot = new ArrayList<>(entries).iterator();
        return new Iterator<>() {
            Entry<E> last;
            public boolean hasNext() { return snapshot.hasNext(); }
            public E next() { last = snapshot.next(); return last.value; }
            public void remove() {
                if (last == null) throw new IllegalStateException();
                synchronized (TLinkedBlockingQueue.this) {
                    entries.remove(last);
                    TLinkedBlockingQueue.this.notifyAll();
                }
                last = null;
            }
        };
    }
}
