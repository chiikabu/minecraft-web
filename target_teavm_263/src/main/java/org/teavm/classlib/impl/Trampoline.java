// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.impl;

import java.util.ArrayDeque;

public final class Trampoline {
	private static final ArrayDeque<Runnable> PENDING = new ArrayDeque<>();
	private static final ArrayDeque<Runnable> DEFERRED = new ArrayDeque<>();
	private static final ArrayDeque<Runnable> LOW_PRIORITY = new ArrayDeque<>();
	private static boolean draining;
	private static int collapses;
	private static boolean preferDeferred = true;

	private Trampoline() {
	}

	public static void drainInline() {
		runQueue(PENDING);
	}

	public static void drainPending() {
		boolean collapsed = false;
		while (!PENDING.isEmpty() || !DEFERRED.isEmpty() || !LOW_PRIORITY.isEmpty()) {
			runQueue(PENDING);
			if (!DEFERRED.isEmpty()) {
				collapsed = true;
				runQueue(DEFERRED);
				continue;
			}
			if (!LOW_PRIORITY.isEmpty()) {
				collapsed = true;
				runQueue(LOW_PRIORITY);
			}
		}
		if (collapsed) {
			collapses++;
		}
	}

	public static void drainUntilDone(final java.util.function.BooleanSupplier done) {
		boolean collapsed = false;
		while (!done.getAsBoolean()) {
			Runnable task = PENDING.poll();
			if (task == null) {
				task = pollDeferred();
				if (task == null) {
					break;
				}
				collapsed = true;
			}
			runOne(task);
		}
		if (collapsed) {
			collapses++;
		}
	}

	public static void drainUntil(final long deadlineNanos) {
		while (runOneFair()) {
			if (System.nanoTime() >= deadlineNanos) {
				return;
			}
		}
	}

	public static void yield() {
		runOneFair();
	}

	public static boolean waitStep(final String site) {
		if (runOneFair()) {
			return true;
		}
		if (WOULD_BLOCK_LOGGED.add(site)) {
			System.out.println("[26.3-web] would block in " + site + " on thread \"" + Thread.currentThread().getName()
					+ "\": nothing queued can satisfy it, and a browser fiber cannot wait");
		}
		return false;
	}

	private static final java.util.HashSet<String> WOULD_BLOCK_LOGGED = new java.util.HashSet<>();

	public static IllegalStateException wouldBlockForever(final String site) {
		return new IllegalStateException(site + " would block forever: nothing queued can satisfy it and browser fibers cannot wait");
	}

	public static void execute(Runnable command) {
		PENDING.add(command);
		if (draining) {
			return;
		}
		draining = true;
		try {
			runQueueUntil(PENDING, inlineDeadlineNanos);
		} finally {
			draining = false;
		}
	}

	public static void setInlineDeadline(long deadlineNanos) {
		inlineDeadlineNanos = deadlineNanos;
	}

	private static long inlineDeadlineNanos;

	private static void runQueueUntil(ArrayDeque<Runnable> queue, long deadlineNanos) {
		Runnable r;
		while ((r = queue.poll()) != null) {
			r.run();
			if (deadlineNanos != 0L && System.nanoTime() >= deadlineNanos) {
				return;
			}
		}
	}

	public static void defer(Runnable command) {
		DEFERRED.add(command);
	}

	public static void deferLowPriority(Runnable command) {
		LOW_PRIORITY.add(command);
	}

	public static boolean hasDeferred() {
		return !DEFERRED.isEmpty() || !LOW_PRIORITY.isEmpty();
	}

	public static boolean hasWork() {
		return !PENDING.isEmpty() || hasDeferred();
	}

	public static boolean drainDeferred(long budgetMs) {
		if (draining) {
			return DEFERRED.isEmpty() && LOW_PRIORITY.isEmpty();
		}
		long deadline = System.currentTimeMillis() + budgetMs;
		Runnable r;
		while ((r = pollDeferred()) != null) {
			try {
				execute(r);
			} catch (Throwable e) {
				reportTaskFailure(r, e);
			}
			if (System.currentTimeMillis() >= deadline) {
				break;
			}
		}
		return DEFERRED.isEmpty() && LOW_PRIORITY.isEmpty();
	}

	private static Runnable pollDeferred() {
		Runnable task = DEFERRED.poll();
		return task != null ? task : LOW_PRIORITY.poll();
	}

	private static int taskFailures;

	private static void reportTaskFailure(Runnable task, Throwable e) {
		String name;
		try {
			name = task.getClass().getSimpleName() + " (" + task.getClass().getName() + ")";
		} catch (Throwable ignored) {
			name = "<unnameable task>";
		}
		if (taskFailures < 20) {
			System.out.println("[teavm] deferred task failed: " + name + " -> " + e);
			for (Throwable c = e.getCause(); c != null && c != e; c = c.getCause()) {
				System.out.println("[teavm]   caused by: " + c);
			}
		} else if (taskFailures == 20) {
			System.out.println("[teavm] deferred task failures past 20, silencing");
		}
		taskFailures++;
	}

	public static int collapseCount() {
		return collapses;
	}

	private static void runQueue(ArrayDeque<Runnable> queue) {
		Runnable r;
		while ((r = queue.poll()) != null) {
			r.run();
		}
	}

	private static boolean runOneFair() {
		Runnable task;
		if (preferDeferred) {
			task = DEFERRED.poll();
			if (task != null) {
				preferDeferred = false;
				collapses++;
				runOne(task);
				return true;
			}
		}
		task = PENDING.poll();
		if (task != null) {
			preferDeferred = true;
			runOne(task);
			return true;
		}
		task = DEFERRED.poll();
		if (task != null) {
			preferDeferred = false;
			collapses++;
			runOne(task);
			return true;
		}
		task = LOW_PRIORITY.poll();
		if (task != null) {
			collapses++;
			runOne(task);
			return true;
		}
		return false;
	}

	private static void runOne(Runnable task) {
		if (draining) {
			task.run();
			return;
		}
		draining = true;
		try {
			task.run();
		} finally {
			draining = false;
		}
	}
}
