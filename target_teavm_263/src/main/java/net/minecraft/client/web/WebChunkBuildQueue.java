// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

public final class WebChunkBuildQueue implements Executor {
	private static final WebChunkBuildQueue INSTANCE = new WebChunkBuildQueue();

	public static final double DEFAULT_BUDGET_MS = 8.0;
	public static final double MIN_BUDGET_MS = 2.5;
	public static final double MAX_BUDGET_MS = 12.0;
	public static final double LOADING_BUDGET_MS = 50.0;
	private static double fixedBudgetMs;
	private static int churnBacklogFrames;
	private static int churnMaxQueueDepth;

	private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

	private static int churnMicros;
	private static int churnTasks;
	private static int churnFrames;

	private WebChunkBuildQueue() {
	}

	public static WebChunkBuildQueue instance() {
		return INSTANCE;
	}

	public static long frameBudgetNanos(final int limit, final long otherNanos) {
		double targetMs = limit > 0 && limit < 260 ? 1000.0 / limit : 1000.0 / 60.0;
		double budgetMs = targetMs * 0.92 - otherNanos / 1_000_000.0;
		return (long)(Math.max(MIN_BUDGET_MS, Math.min(MAX_BUDGET_MS, budgetMs)) * 1_000_000.0);
	}

	public static void setBudgetMs(final double ms) {
		if (ms > 0.0) {
			fixedBudgetMs = ms;
		}
	}

	@Override
	public void execute(final Runnable command) {
		this.queue.add(command);
	}

	public static void drainForFrame(final long budgetNanos, final boolean throughput) {
		ArrayDeque<Runnable> queue = INSTANCE.queue;
		if (queue.isEmpty()) {
			return;
		}
		int depth = queue.size();
		if (depth > churnMaxQueueDepth) {
			churnMaxQueueDepth = depth;
		}
		long start = System.nanoTime();
		long deadline = start + (fixedBudgetMs > 0.0 ? (long)(fixedBudgetMs * 1_000_000.0) : budgetNanos);
		if (throughput) {
			churnBacklogFrames++;
		}
		int ran = 0;
		Runnable task;
		while ((task = queue.poll()) != null) {
			try {
				task.run();
			} catch (Throwable e) {
				System.out.println("[26.3-web] chunk build task failed: "
						+ task.getClass().getSimpleName() + " -> " + e);
			}
			ran++;
			if (System.nanoTime() >= deadline) {
				break;
			}
		}
		churnMicros += (int)((System.nanoTime() - start) / 1000L);
		churnTasks += ran;
		churnFrames++;
	}

	public static void drainPostFrameForWorker(final long maxNanos) {
		ArrayDeque<Runnable> queue = INSTANCE.queue;
		if (queue.isEmpty()) {
			return;
		}
		long start = System.nanoTime();
		long deadline = start + maxNanos;
		int ran = 0;
		while (!queue.isEmpty() && !net.minecraft.client.renderer.chunk.Web263MeshOffload.saturated()) {
			Runnable task = queue.poll();
			if (task == null) {
				break;
			}
			try {
				task.run();
			} catch (Throwable e) {
				System.out.println("[26.3-web] chunk build task failed: "
						+ task.getClass().getSimpleName() + " -> " + e);
			}
			ran++;
			if (System.nanoTime() >= deadline) {
				break;
			}
		}
		if (ran > 0) {
			churnMicros += (int)((System.nanoTime() - start) / 1000L);
			churnTasks += ran;
		}
	}

	public static int[] churnCounters() {
		return new int[] {
			churnMicros, churnTasks, churnFrames, INSTANCE.queue.size(), churnBacklogFrames, churnMaxQueueDepth
		};
	}

	public static void resetPeak() {
		churnMaxQueueDepth = INSTANCE.queue.size();
	}

}
