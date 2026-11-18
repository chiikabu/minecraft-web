// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;
import org.teavm.classlib.impl.Trampoline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class TCompletableFuture<T> implements TCompletionStage<T>, java.util.concurrent.Future<T> {
	private static final Executor SYNC_EXECUTOR = Runnable::run;

	private boolean done;
	private T value;
	private Throwable exception;
	private List<Runnable> callbacks;

	public TCompletableFuture() {
	}

	private boolean doComplete(T v, Throwable ex) {
		if (done) {
			return false;
		}
		value = v;
		exception = ex;
		done = true;
		if (callbacks != null) {
			List<Runnable> cbs = callbacks;
			callbacks = null;
			for (Runnable r : cbs) {
				r.run();
			}
		}
		return true;
	}

	public boolean complete(T value) {
		return doComplete(value, null);
	}

	public boolean completeExceptionally(Throwable ex) {
		if (ex == null) {
			throw new NullPointerException();
		}
		return doComplete(null, ex);
	}

	public void obtrudeValue(T value) {
		this.value = value;
		this.exception = null;
		this.done = true;
	}

	public void obtrudeException(Throwable ex) {
		if (ex == null) {
			throw new NullPointerException();
		}
		this.exception = ex;
		this.value = null;
		this.done = true;
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		boolean cancelled = !done && doComplete(null, new CancellationException());
		return cancelled || isCancelled();
	}

	@Override
	public boolean isCancelled() {
		return done && exception instanceof CancellationException;
	}

	@Override
	public boolean isDone() {
		return done;
	}

	public boolean isCompletedExceptionally() {
		return done && exception != null;
	}

	private void drainOrFail() {
		if (!done) {
			Trampoline.drainUntilDone(() -> done);
		}
		if (!done) {
			throw new IllegalStateException("cannot block on the JS main thread");
		}
	}

	@Override
	public T get() throws InterruptedException, ExecutionException {
		drainOrFail();
		return reportGet();
	}

	@Override
	public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		drainOrFail();
		return reportGet();
	}

	public T join() {
		drainOrFail();
		return reportJoin();
	}

	public T getNow(T valueIfAbsent) {
		if (!done) {
			return valueIfAbsent;
		}
		return reportJoin();
	}

	public Throwable exceptionNow() {
		Throwable failure = exception;
		if (!done || failure == null || failure instanceof CancellationException) {
			throw new IllegalStateException();
		}
		if (failure instanceof CompletionException) {
			Throwable cause = failure.getCause();
			if (cause != null) {
				return cause;
			}
		}
		return failure;
	}

	private T reportGet() throws ExecutionException {
		if (exception != null) {
			if (exception instanceof CancellationException) {
				throw (CancellationException) exception;
			}
			Throwable cause = exception;
			if (cause instanceof CompletionException && cause.getCause() != null) {
				cause = cause.getCause();
			}
			throw new ExecutionException(cause);
		}
		return value;
	}

	private T reportJoin() {
		if (exception != null) {
			if (exception instanceof CancellationException) {
				throw (CancellationException) exception;
			}
			if (exception instanceof CompletionException) {
				throw (CompletionException) exception;
			}
			throw new CompletionException(exception);
		}
		return value;
	}

	public TCompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
		return this;
	}

	public TCompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
		return this;
	}

	private static Throwable wrapIfNeeded(Throwable x) {
		return x instanceof CompletionException ? x : new CompletionException(x);
	}

	private static void runVia(Runnable action, Executor e) {
		if (e != null) {
			e.execute(action);
		} else {
			action.run();
		}
	}

	private void addOrRun(Runnable action, Executor e) {
		if (done) {
			runVia(action, e);
		} else {
			if (callbacks == null) {
				callbacks = new ArrayList<>();
			}
			callbacks.add(() -> runVia(action, e));
		}
	}

	public <U> TCompletableFuture<U> newIncompleteFuture() {
		return new TCompletableFuture<>();
	}

	public Executor defaultExecutor() {
		return SYNC_EXECUTOR;
	}

	public TCompletionStage<T> minimalCompletionStage() {
		return this;
	}

	public TCompletableFuture<T> copy() {
		TCompletableFuture<T> d = newIncompleteFuture();
		addOrRun(() -> {
			if (exception != null) {
				d.completeExceptionally(wrapIfNeeded(exception));
			} else {
				d.complete(value);
			}
		}, null);
		return d;
	}

	@Override
	public TCompletableFuture<T> toCompletableFuture() {
		return this;
	}

	private <U> TCompletableFuture<U> uniApplyStage(Executor e, Function<? super T, ? extends U> fn) {
		if (fn == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<U> d = newIncompleteFuture();
		addOrRun(() -> {
			if (exception != null) {
				d.completeExceptionally(wrapIfNeeded(exception));
			} else {
				try {
					d.complete(fn.apply(value));
				} catch (Throwable t) {
					d.completeExceptionally(wrapIfNeeded(t));
				}
			}
		}, e);
		return d;
	}

	@Override
	public <U> TCompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
		return uniApplyStage(null, fn);
	}

	@Override
	public <U> TCompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
		return uniApplyStage(SYNC_EXECUTOR, fn);
	}

	@Override
	public <U> TCompletableFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return uniApplyStage(executor, fn);
	}

	private TCompletableFuture<Void> uniAcceptStage(Executor e, Consumer<? super T> action) {
		if (action == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<Void> d = newIncompleteFuture();
		addOrRun(() -> {
			if (exception != null) {
				d.completeExceptionally(wrapIfNeeded(exception));
			} else {
				try {
					action.accept(value);
					d.complete(null);
				} catch (Throwable t) {
					d.completeExceptionally(wrapIfNeeded(t));
				}
			}
		}, e);
		return d;
	}

	@Override
	public TCompletableFuture<Void> thenAccept(Consumer<? super T> action) {
		return uniAcceptStage(null, action);
	}

	@Override
	public TCompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
		return uniAcceptStage(SYNC_EXECUTOR, action);
	}

	@Override
	public TCompletableFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return uniAcceptStage(executor, action);
	}

	private TCompletableFuture<Void> uniRunStage(Executor e, Runnable action) {
		if (action == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<Void> d = newIncompleteFuture();
		addOrRun(() -> {
			if (exception != null) {
				d.completeExceptionally(wrapIfNeeded(exception));
			} else {
				try {
					action.run();
					d.complete(null);
				} catch (Throwable t) {
					d.completeExceptionally(wrapIfNeeded(t));
				}
			}
		}, e);
		return d;
	}

	@Override
	public TCompletableFuture<Void> thenRun(Runnable action) {
		return uniRunStage(null, action);
	}

	@Override
	public TCompletableFuture<Void> thenRunAsync(Runnable action) {
		return uniRunStage(SYNC_EXECUTOR, action);
	}

	@Override
	public TCompletableFuture<Void> thenRunAsync(Runnable action, Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return uniRunStage(executor, action);
	}

	private <U> TCompletableFuture<U> uniComposeStage(Executor e, Function<? super T, ? extends TCompletionStage<U>> fn) {
		if (fn == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<U> d = newIncompleteFuture();
		addOrRun(() -> {
			if (exception != null) {
				d.completeExceptionally(wrapIfNeeded(exception));
			} else {
				try {
					TCompletableFuture<U> inner = fn.apply(value).toCompletableFuture();
					inner.addOrRun(() -> {
						if (inner.exception != null) {
							d.completeExceptionally(wrapIfNeeded(inner.exception));
						} else {
							d.complete(inner.value);
						}
					}, null);
				} catch (Throwable t) {
					d.completeExceptionally(wrapIfNeeded(t));
				}
			}
		}, e);
		return d;
	}

	@Override
	public <U> TCompletableFuture<U> thenCompose(Function<? super T, ? extends TCompletionStage<U>> fn) {
		return uniComposeStage(null, fn);
	}

	@Override
	public <U> TCompletableFuture<U> thenComposeAsync(Function<? super T, ? extends TCompletionStage<U>> fn) {
		return uniComposeStage(SYNC_EXECUTOR, fn);
	}

	@Override
	public <U> TCompletableFuture<U> thenComposeAsync(Function<? super T, ? extends TCompletionStage<U>> fn,
			Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return uniComposeStage(executor, fn);
	}

	private TCompletableFuture<T> uniExceptionallyStage(Executor e, Function<Throwable, ? extends T> fn) {
		if (fn == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<T> d = newIncompleteFuture();
		addOrRun(() -> {
			if (exception == null) {
				d.complete(value);
			} else {
				try {
					d.complete(fn.apply(exception));
				} catch (Throwable t) {
					d.completeExceptionally(wrapIfNeeded(t));
				}
			}
		}, e);
		return d;
	}

	@Override
	public TCompletableFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
		return uniExceptionallyStage(null, fn);
	}

	@Override
	public TCompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn) {
		return uniExceptionallyStage(SYNC_EXECUTOR, fn);
	}

	@Override
	public TCompletableFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return uniExceptionallyStage(executor, fn);
	}

	private TCompletableFuture<T> uniExceptionallyComposeStage(Executor e,
			Function<Throwable, ? extends TCompletionStage<T>> fn) {
		if (fn == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<T> d = newIncompleteFuture();
		addOrRun(() -> {
			if (exception == null) {
				d.complete(value);
			} else {
				try {
					TCompletableFuture<T> inner = fn.apply(exception).toCompletableFuture();
					inner.addOrRun(() -> {
						if (inner.exception != null) {
							d.completeExceptionally(wrapIfNeeded(inner.exception));
						} else {
							d.complete(inner.value);
						}
					}, null);
				} catch (Throwable t) {
					d.completeExceptionally(wrapIfNeeded(t));
				}
			}
		}, e);
		return d;
	}

	public TCompletableFuture<T> exceptionallyCompose(Function<Throwable, ? extends TCompletionStage<T>> fn) {
		return uniExceptionallyComposeStage(null, fn);
	}

	public TCompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends TCompletionStage<T>> fn) {
		return uniExceptionallyComposeStage(SYNC_EXECUTOR, fn);
	}

	public TCompletableFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends TCompletionStage<T>> fn,
			Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return uniExceptionallyComposeStage(executor, fn);
	}

	private TCompletableFuture<T> uniWhenCompleteStage(Executor e, BiConsumer<? super T, ? super Throwable> action) {
		if (action == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<T> d = newIncompleteFuture();
		addOrRun(() -> {
			Throwable failure = exception;
			try {
				action.accept(value, exception);
			} catch (Throwable t) {
				if (failure == null) {
					failure = t;
				}
			}
			if (failure != null) {
				d.completeExceptionally(wrapIfNeeded(failure));
			} else {
				d.complete(value);
			}
		}, e);
		return d;
	}

	@Override
	public TCompletableFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
		return uniWhenCompleteStage(null, action);
	}

	@Override
	public TCompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
		return uniWhenCompleteStage(SYNC_EXECUTOR, action);
	}

	@Override
	public TCompletableFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action,
			Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return uniWhenCompleteStage(executor, action);
	}

	private <U> TCompletableFuture<U> uniHandleStage(Executor e, BiFunction<? super T, Throwable, ? extends U> fn) {
		if (fn == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<U> d = newIncompleteFuture();
		addOrRun(() -> {
			try {
				d.complete(fn.apply(value, exception));
			} catch (Throwable t) {
				d.completeExceptionally(wrapIfNeeded(t));
			}
		}, e);
		return d;
	}

	@Override
	public <U> TCompletableFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
		return uniHandleStage(null, fn);
	}

	@Override
	public <U> TCompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
		return uniHandleStage(SYNC_EXECUTOR, fn);
	}

	@Override
	public <U> TCompletableFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return uniHandleStage(executor, fn);
	}

	@SuppressWarnings("unchecked")
	private <U> TCompletableFuture<U> asFuture(TCompletionStage<? extends U> stage) {
		return (TCompletableFuture<U>) stage.toCompletableFuture();
	}

	private <U, V> TCompletableFuture<V> biApplyStage(Executor e, TCompletionStage<? extends U> other,
			BiFunction<? super T, ? super U, ? extends V> fn) {
		if (other == null || fn == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<U> b = asFuture(other);
		TCompletableFuture<V> d = newIncompleteFuture();
		int[] remaining = { 2 };
		Runnable arrive = () -> {
			if (--remaining[0] == 0) {
				runVia(() -> {
					if (exception != null) {
						d.completeExceptionally(wrapIfNeeded(exception));
					} else if (b.exception != null) {
						d.completeExceptionally(wrapIfNeeded(b.exception));
					} else {
						try {
							d.complete(fn.apply(value, b.value));
						} catch (Throwable t) {
							d.completeExceptionally(wrapIfNeeded(t));
						}
					}
				}, e);
			}
		};
		addOrRun(arrive, null);
		b.addOrRun(arrive, null);
		return d;
	}

	@Override
	public <U, V> TCompletableFuture<V> thenCombine(TCompletionStage<? extends U> other,
			BiFunction<? super T, ? super U, ? extends V> fn) {
		return biApplyStage(null, other, fn);
	}

	@Override
	public <U, V> TCompletableFuture<V> thenCombineAsync(TCompletionStage<? extends U> other,
			BiFunction<? super T, ? super U, ? extends V> fn) {
		return biApplyStage(SYNC_EXECUTOR, other, fn);
	}

	@Override
	public <U, V> TCompletableFuture<V> thenCombineAsync(TCompletionStage<? extends U> other,
			BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return biApplyStage(executor, other, fn);
	}

	private <U> TCompletableFuture<Void> biAcceptStage(Executor e, TCompletionStage<? extends U> other,
			BiConsumer<? super T, ? super U> action) {
		if (other == null || action == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<U> b = asFuture(other);
		TCompletableFuture<Void> d = newIncompleteFuture();
		int[] remaining = { 2 };
		Runnable arrive = () -> {
			if (--remaining[0] == 0) {
				runVia(() -> {
					if (exception != null) {
						d.completeExceptionally(wrapIfNeeded(exception));
					} else if (b.exception != null) {
						d.completeExceptionally(wrapIfNeeded(b.exception));
					} else {
						try {
							action.accept(value, b.value);
							d.complete(null);
						} catch (Throwable t) {
							d.completeExceptionally(wrapIfNeeded(t));
						}
					}
				}, e);
			}
		};
		addOrRun(arrive, null);
		b.addOrRun(arrive, null);
		return d;
	}

	@Override
	public <U> TCompletableFuture<Void> thenAcceptBoth(TCompletionStage<? extends U> other,
			BiConsumer<? super T, ? super U> action) {
		return biAcceptStage(null, other, action);
	}

	@Override
	public <U> TCompletableFuture<Void> thenAcceptBothAsync(TCompletionStage<? extends U> other,
			BiConsumer<? super T, ? super U> action) {
		return biAcceptStage(SYNC_EXECUTOR, other, action);
	}

	@Override
	public <U> TCompletableFuture<Void> thenAcceptBothAsync(TCompletionStage<? extends U> other,
			BiConsumer<? super T, ? super U> action, Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return biAcceptStage(executor, other, action);
	}

	private TCompletableFuture<Void> biRunStage(Executor e, TCompletionStage<?> other, Runnable action) {
		if (other == null || action == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<?> b = other.toCompletableFuture();
		TCompletableFuture<Void> d = newIncompleteFuture();
		int[] remaining = { 2 };
		Runnable arrive = () -> {
			if (--remaining[0] == 0) {
				runVia(() -> {
					if (exception != null) {
						d.completeExceptionally(wrapIfNeeded(exception));
					} else if (b.exception != null) {
						d.completeExceptionally(wrapIfNeeded(b.exception));
					} else {
						try {
							action.run();
							d.complete(null);
						} catch (Throwable t) {
							d.completeExceptionally(wrapIfNeeded(t));
						}
					}
				}, e);
			}
		};
		addOrRun(arrive, null);
		b.addOrRun(arrive, null);
		return d;
	}

	@Override
	public TCompletableFuture<Void> runAfterBoth(TCompletionStage<?> other, Runnable action) {
		return biRunStage(null, other, action);
	}

	@Override
	public TCompletableFuture<Void> runAfterBothAsync(TCompletionStage<?> other, Runnable action) {
		return biRunStage(SYNC_EXECUTOR, other, action);
	}

	@Override
	public TCompletableFuture<Void> runAfterBothAsync(TCompletionStage<?> other, Runnable action, Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return biRunStage(executor, other, action);
	}

	private <U> TCompletableFuture<U> orApplyStage(Executor e, TCompletionStage<? extends T> other,
			Function<? super T, U> fn) {
		if (other == null || fn == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<T> b = asFuture(other);
		TCompletableFuture<U> d = newIncompleteFuture();
		boolean[] fired = { false };
		Runnable fromThis = () -> {
			if (!fired[0]) {
				fired[0] = true;
				runVia(() -> {
					if (exception != null) {
						d.completeExceptionally(wrapIfNeeded(exception));
					} else {
						try {
							d.complete(fn.apply(value));
						} catch (Throwable t) {
							d.completeExceptionally(wrapIfNeeded(t));
						}
					}
				}, e);
			}
		};
		Runnable fromB = () -> {
			if (!fired[0]) {
				fired[0] = true;
				runVia(() -> {
					if (b.exception != null) {
						d.completeExceptionally(wrapIfNeeded(b.exception));
					} else {
						try {
							d.complete(fn.apply(b.value));
						} catch (Throwable t) {
							d.completeExceptionally(wrapIfNeeded(t));
						}
					}
				}, e);
			}
		};
		addOrRun(fromThis, null);
		b.addOrRun(fromB, null);
		return d;
	}

	@Override
	public <U> TCompletableFuture<U> applyToEither(TCompletionStage<? extends T> other, Function<? super T, U> fn) {
		return orApplyStage(null, other, fn);
	}

	@Override
	public <U> TCompletableFuture<U> applyToEitherAsync(TCompletionStage<? extends T> other,
			Function<? super T, U> fn) {
		return orApplyStage(SYNC_EXECUTOR, other, fn);
	}

	@Override
	public <U> TCompletableFuture<U> applyToEitherAsync(TCompletionStage<? extends T> other,
			Function<? super T, U> fn, Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return orApplyStage(executor, other, fn);
	}

	private TCompletableFuture<Void> orAcceptStage(Executor e, TCompletionStage<? extends T> other,
			Consumer<? super T> action) {
		if (other == null || action == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<T> b = asFuture(other);
		TCompletableFuture<Void> d = newIncompleteFuture();
		boolean[] fired = { false };
		Runnable fromThis = () -> {
			if (!fired[0]) {
				fired[0] = true;
				runVia(() -> {
					if (exception != null) {
						d.completeExceptionally(wrapIfNeeded(exception));
					} else {
						try {
							action.accept(value);
							d.complete(null);
						} catch (Throwable t) {
							d.completeExceptionally(wrapIfNeeded(t));
						}
					}
				}, e);
			}
		};
		Runnable fromB = () -> {
			if (!fired[0]) {
				fired[0] = true;
				runVia(() -> {
					if (b.exception != null) {
						d.completeExceptionally(wrapIfNeeded(b.exception));
					} else {
						try {
							action.accept(b.value);
							d.complete(null);
						} catch (Throwable t) {
							d.completeExceptionally(wrapIfNeeded(t));
						}
					}
				}, e);
			}
		};
		addOrRun(fromThis, null);
		b.addOrRun(fromB, null);
		return d;
	}

	@Override
	public TCompletableFuture<Void> acceptEither(TCompletionStage<? extends T> other, Consumer<? super T> action) {
		return orAcceptStage(null, other, action);
	}

	@Override
	public TCompletableFuture<Void> acceptEitherAsync(TCompletionStage<? extends T> other,
			Consumer<? super T> action) {
		return orAcceptStage(SYNC_EXECUTOR, other, action);
	}

	@Override
	public TCompletableFuture<Void> acceptEitherAsync(TCompletionStage<? extends T> other,
			Consumer<? super T> action, Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return orAcceptStage(executor, other, action);
	}

	private TCompletableFuture<Void> orRunStage(Executor e, TCompletionStage<?> other, Runnable action) {
		if (other == null || action == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<?> b = other.toCompletableFuture();
		TCompletableFuture<Void> d = newIncompleteFuture();
		boolean[] fired = { false };
		Runnable fromThis = () -> {
			if (!fired[0]) {
				fired[0] = true;
				runVia(() -> {
					if (exception != null) {
						d.completeExceptionally(wrapIfNeeded(exception));
					} else {
						try {
							action.run();
							d.complete(null);
						} catch (Throwable t) {
							d.completeExceptionally(wrapIfNeeded(t));
						}
					}
				}, e);
			}
		};
		Runnable fromB = () -> {
			if (!fired[0]) {
				fired[0] = true;
				runVia(() -> {
					if (b.exception != null) {
						d.completeExceptionally(wrapIfNeeded(b.exception));
					} else {
						try {
							action.run();
							d.complete(null);
						} catch (Throwable t) {
							d.completeExceptionally(wrapIfNeeded(t));
						}
					}
				}, e);
			}
		};
		addOrRun(fromThis, null);
		b.addOrRun(fromB, null);
		return d;
	}

	@Override
	public TCompletableFuture<Void> runAfterEither(TCompletionStage<?> other, Runnable action) {
		return orRunStage(null, other, action);
	}

	@Override
	public TCompletableFuture<Void> runAfterEitherAsync(TCompletionStage<?> other, Runnable action) {
		return orRunStage(SYNC_EXECUTOR, other, action);
	}

	@Override
	public TCompletableFuture<Void> runAfterEitherAsync(TCompletionStage<?> other, Runnable action,
			Executor executor) {
		if (executor == null) {
			throw new NullPointerException();
		}
		return orRunStage(executor, other, action);
	}

	public static <U> TCompletableFuture<U> completedFuture(U value) {
		TCompletableFuture<U> f = new TCompletableFuture<>();
		f.complete(value);
		return f;
	}

	public static <U> TCompletableFuture<U> failedFuture(Throwable ex) {
		if (ex == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<U> f = new TCompletableFuture<>();
		f.completeExceptionally(ex);
		return f;
	}

	public static <U> TCompletableFuture<U> supplyAsync(Supplier<U> supplier) {
		return supplyAsync(supplier, SYNC_EXECUTOR);
	}

	public static <U> TCompletableFuture<U> supplyAsync(Supplier<U> supplier, Executor executor) {
		if (supplier == null || executor == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<U> f = new TCompletableFuture<>();
		executor.execute(() -> {
			try {
				f.complete(supplier.get());
			} catch (Throwable t) {
				f.completeExceptionally(wrapIfNeeded(t));
			}
		});
		return f;
	}

	public static TCompletableFuture<Void> runAsync(Runnable runnable) {
		return runAsync(runnable, SYNC_EXECUTOR);
	}

	public static TCompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
		if (runnable == null || executor == null) {
			throw new NullPointerException();
		}
		TCompletableFuture<Void> f = new TCompletableFuture<>();
		executor.execute(() -> {
			try {
				runnable.run();
				f.complete(null);
			} catch (Throwable t) {
				f.completeExceptionally(wrapIfNeeded(t));
			}
		});
		return f;
	}

	public static TCompletableFuture<Void> allOf(TCompletableFuture<?>... cfs) {
		return allOfTree(cfs, 0, cfs.length - 1);
	}

	private static TCompletableFuture<Void> allOfTree(TCompletableFuture<?>[] cfs, int low, int high) {
		TCompletableFuture<Void> result = new TCompletableFuture<>();
		if (low > high) {
			result.complete(null);
			return result;
		}
		int middle = (low + high) >>> 1;
		TCompletableFuture<?> a = low == middle ? cfs[low] : allOfTree(cfs, low, middle);
		TCompletableFuture<?> b = low == high ? a
				: high == middle + 1 ? cfs[high] : allOfTree(cfs, middle + 1, high);
		if (a == null || b == null) {
			throw new NullPointerException();
		}
		int[] remaining = { 2 };
		Runnable arrive = () -> {
			if (--remaining[0] == 0) {
				Throwable failure = a.exception != null ? a.exception : b.exception;
				if (failure != null) {
					result.completeExceptionally(wrapIfNeeded(failure));
				} else {
					result.complete(null);
				}
			}
		};
		a.addOrRun(arrive, null);
		b.addOrRun(arrive, null);
		return result;
	}

	public static TCompletableFuture<Object> anyOf(TCompletableFuture<?>... cfs) {
		TCompletableFuture<Object> result = new TCompletableFuture<>();
		for (TCompletableFuture<?> cf : cfs) {
			cf.addOrRun(() -> {
				if (cf.exception != null) {
					result.completeExceptionally(wrapIfNeeded(cf.exception));
				} else {
					result.complete(cf.value);
				}
			}, null);
		}
		return result;
	}
}
