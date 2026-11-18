// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;

public class TConcurrentLinkedQueue<E> extends AbstractQueue<E> {
	private final Deque<E> delegate = new ArrayDeque<>();

	public TConcurrentLinkedQueue() {
	}

	public TConcurrentLinkedQueue(Collection<? extends E> source) {
		delegate.addAll(source);
	}

	@Override public boolean offer(E e) { return delegate.offer(e); }
	@Override public E poll() { return delegate.poll(); }
	@Override public E peek() { return delegate.peek(); }
	@Override public Iterator<E> iterator() { return delegate.iterator(); }
	@Override public int size() { return delegate.size(); }
}
