// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;

public final class TScopedValue<T> {
	private final ThreadLocal<Binding<T>> binding = new ThreadLocal<>();
	private static final class Binding<T> {
		final T value;
		Binding(T value) { this.value = value; }
	}
	private TScopedValue() { }

	public static <T> TScopedValue<T> newInstance() { return new TScopedValue<>(); }

	public T get() {
		Binding<T> current = binding.get();
		if (current == null) {
			throw new NoSuchElementException("ScopedValue not bound");
		}
		return current.value;
	}

	public boolean isBound() { return binding.get() != null; }
	public T orElse(T other) { return isBound() ? get() : other; }
	public <X extends Throwable> T orElseThrow(Supplier<? extends X> supplier) throws X {
		Objects.requireNonNull(supplier);
		if (!isBound()) throw supplier.get();
		return get();
	}
	@FunctionalInterface
	public interface CallableOp<R, X extends Throwable> { R call() throws X; }

	public static <T> Carrier where(TScopedValue<T> key, T value) {
		return new Carrier(key, value, null);
	}

	public static final class Carrier {
		private final TScopedValue<Object> key;
		private final Object value;
		private final Carrier previous;

		@SuppressWarnings("unchecked")
		private <T> Carrier(TScopedValue<T> k, T v, Carrier previous) {
			this.key = (TScopedValue<Object>)Objects.requireNonNull(k);
			this.value = v;
			this.previous = previous;
		}

		public void run(Runnable op) {
			Objects.requireNonNull(op);
			call(() -> { op.run(); return null; });
		}

		public <R, X extends Throwable> R call(CallableOp<? extends R, X> op) throws X {
			Objects.requireNonNull(op);
			return previous == null ? bind(op) : previous.call(() -> bind(op));
		}

		private <R, X extends Throwable> R bind(CallableOp<? extends R, X> op) throws X {
			Binding<Object> old = key.binding.get();
			key.binding.set(new Binding<>(value));
			try {
				return op.call();
			} finally {
				if (old == null) key.binding.remove();
				else key.binding.set(old);
			}
		}

		public <T> Carrier where(TScopedValue<T> k, T v) { return new Carrier(k, v, this); }
		@SuppressWarnings("unchecked")
		public <T> T get(TScopedValue<T> key) {
			Objects.requireNonNull(key);
			for (Carrier item = this; item != null; item = item.previous) {
				if (item.key == key) return (T)item.value;
			}
			throw new NoSuchElementException("ScopedValue not in carrier");
		}
	}

	@SuppressWarnings("unchecked")
	public static void runWhere(TScopedValue<?> key, Object value, Runnable op) {
		where((TScopedValue<Object>)key, value).run(op);
	}
}
