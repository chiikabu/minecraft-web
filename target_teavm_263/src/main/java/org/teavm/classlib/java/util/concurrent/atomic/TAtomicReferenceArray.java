// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent.atomic;

public class TAtomicReferenceArray<E> {
	private final Object[] array;

	public TAtomicReferenceArray(int length) {
		this.array = new Object[length];
	}

	public TAtomicReferenceArray(E[] source) {
		this.array = source.clone();
	}

	public int length() { return array.length; }
	@SuppressWarnings("unchecked")
	public E get(int i) { return (E)array[i]; }
	public void set(int i, E value) { array[i] = value; }
	public void lazySet(int i, E value) { array[i] = value; }
	@SuppressWarnings("unchecked")
	public E getAndSet(int i, E value) { Object old = array[i]; array[i] = value; return (E)old; }
	public boolean compareAndSet(int i, E expect, E update) {
		if (array[i] == expect) { array[i] = update; return true; }
		return false;
	}
	public boolean weakCompareAndSet(int i, E expect, E update) { return compareAndSet(i, expect, update); }
	@SuppressWarnings("unchecked")
	public E compareAndExchange(int i, E expect, E update) {
		Object witness = array[i];
		if (witness == expect) { array[i] = update; }
		return (E)witness;
	}
	@SuppressWarnings("unchecked")
	public E getAndUpdate(int i, java.util.function.UnaryOperator<E> op) { E old = (E)array[i]; array[i] = op.apply(old); return old; }
	@SuppressWarnings("unchecked")
	public E updateAndGet(int i, java.util.function.UnaryOperator<E> op) { array[i] = op.apply((E)array[i]); return (E)array[i]; }
	@SuppressWarnings("unchecked")
	public E getAndAccumulate(int i, E x, java.util.function.BinaryOperator<E> op) { E old = (E)array[i]; array[i] = op.apply(old, x); return old; }
	@SuppressWarnings("unchecked")
	public E accumulateAndGet(int i, E x, java.util.function.BinaryOperator<E> op) { array[i] = op.apply((E)array[i], x); return (E)array[i]; }
	@Override public String toString() { return java.util.Arrays.toString(array); }
}
