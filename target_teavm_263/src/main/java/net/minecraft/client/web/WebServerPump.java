// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.IOException;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;

public final class WebServerPump implements MinecraftServer.ServerLoopHost {
	private static final long CATCHUP_BUDGET_NANOS = 30L * 1000000L;

	private static final long WORKER_CATCHUP_BUDGET_NANOS = 40L * 1000000L;

	private static long workerBudgetNanos = -1L;

	private static long workerBudgetNanos() {
		if (workerBudgetNanos < 0L) {
			int ms = WebWorkerTransport.pageServerDrainMs();
			if (ms < 0) {
				ms = workerCoresAtMostTwo() ? 25 : 40;
			}
			ms = Math.max(5, Math.min(45, ms));
			workerBudgetNanos = ms * 1000000L;
			System.out.println("[26.3-server] worker tick budget " + ms + " ms");
		}
		return workerBudgetNanos;
	}

	@org.teavm.jso.JSBody(script = "return (typeof navigator !== 'undefined' && navigator.hardwareConcurrency || 2) <= 2;")
	private static native boolean workerCoresAtMostTwo();

	private static final int MIN_CATCHUP_TICKS = 1;

	private static final int MAX_CATCHUP_TICKS = 20;

	private static final long MIN_DRAIN_NANOS = 2L * 1000000L;

	private static MinecraftServer server;

	private static int churnMicros;
	private static int churnTicks;
	private static int churnWorstMicros;
	private static int churnWorstTicks;

	public static int[] churnCounters() {
		return new int[] {churnMicros, churnTicks, churnWorstMicros, churnWorstTicks};
	}

	private WebServerPump() {
	}

	public static void install() {
		MinecraftServer.loopHost = new WebServerPump();
	}

	@Override
	public <S extends MinecraftServer> S spin(final Function<Thread, S> factory) {
		S newServer = factory.apply(Thread.currentThread());
		server = newServer;
		try {
			if (!newServer.startServerLoop()) {
				throw new IOException("initServer() failed");
			}
		} catch (IOException e) {
			server = null;
			newServer.exitServerLoop();
			throw new IllegalStateException("Failed to initialize server", e);
		}
		newServer.runServerFrame(false);
		return newServer;
	}

	private static boolean shuttingDown;

	@Override
	public void halted(final MinecraftServer haltedServer) {
		if (server == haltedServer) {
			server = null;
		}
		if (shuttingDown) {
			return;
		}
		if (!haltedServer.isShutdown()) {
			shuttingDown = true;
			try {
				WebGenLane.stop();
				haltedServer.exitServerLoop();
			} finally {
				shuttingDown = false;
			}
		}
	}

	public static boolean drainIdle(final long budgetNanos) {
		MinecraftServer current = server;
		if (current == null || !current.isRunning()) {
			return false;
		}
		long now = Util.getNanos();
		if (now >= current.getNextTickTime()) {
			return true;
		}
		try {
			long deadline = Math.min(now + budgetNanos, current.getNextTickTime());
			if (current.drainIdleTasks(deadline)) {
				return true;
			}
			org.teavm.classlib.impl.Trampoline.drainUntil(deadline);
			return org.teavm.classlib.impl.Trampoline.hasWork();
		} catch (Throwable e) {
			org.slf4j.LoggerFactory.getLogger("26.3-web").error("idle drain failed, halting integrated server", e);
			current.halt(false);
			return false;
		}
	}

	public static int millisUntilNextTick(final int cap) {
		MinecraftServer current = server;
		if (current == null || !current.isRunning()) {
			return cap;
		}
		long nanos = current.getNextTickTime() - Util.getNanos();
		return nanos <= 0L ? 0 : (int)Math.min(cap, nanos / 1_000_000L);
	}

	public static void pumpIfRunning() {
		MinecraftServer current = server;
		if (current == null) {
			return;
		}
		if (!current.isRunning()) {
			server = null;
			return;
		}
		long pumpStart = System.nanoTime();
		int ticks = 0;
		try {
			long deadline = Util.getNanos()
				+ (WebWorkerTransport.inWorker() ? workerBudgetNanos() : CATCHUP_BUDGET_NANOS);
			for (int i = 0; i < MAX_CATCHUP_TICKS && Util.getNanos() >= current.getNextTickTime(); i++) {
				MinecraftServer.taskDrainBudgetNanos = Math.max(MIN_DRAIN_NANOS, deadline - Util.getNanos());
				long tickStart = System.nanoTime();
				current.runServerFrame(false);
				net.minecraft.server.Web263ServerTickProbe.endTick(System.nanoTime() - tickStart);
				ticks++;
				if (i >= MIN_CATCHUP_TICKS - 1 && Util.getNanos() >= deadline) {
					break;
				}
			}
			int pumpMicros = (int)((System.nanoTime() - pumpStart) / 1000L);
			churnMicros += pumpMicros;
			churnTicks += ticks;
			if (pumpMicros > churnWorstMicros) {
				churnWorstMicros = pumpMicros;
				churnWorstTicks = ticks;
			}
		} catch (Throwable e) {
			org.slf4j.LoggerFactory.getLogger("26.3-web").error("server tick failed, halting integrated server", e);
			current.halt(false);
		} finally {
			MinecraftServer.taskDrainBudgetNanos = 0L;
		}
	}
}
