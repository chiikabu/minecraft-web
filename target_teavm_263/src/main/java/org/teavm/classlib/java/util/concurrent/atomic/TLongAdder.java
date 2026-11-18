// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent.atomic;

public class TLongAdder extends Number {
	private long value;

	public TLongAdder() {
	}

	public void add(long x) {
		value += x;
	}

	public void increment() {
		value++;
	}

	public void decrement() {
		value--;
	}

	public long sum() {
		return value;
	}

	public void reset() {
		value = 0;
	}

	public long sumThenReset() {
		long v = value;
		value = 0;
		return v;
	}

	@Override
	public String toString() {
		return Long.toString(value);
	}

	@Override
	public long longValue() {
		return value;
	}

	@Override
	public int intValue() {
		return (int) value;
	}

	@Override
	public float floatValue() {
		return value;
	}

	@Override
	public double doubleValue() {
		return value;
	}
}
