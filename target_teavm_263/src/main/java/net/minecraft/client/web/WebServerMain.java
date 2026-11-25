// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.teavm.classlib.PlatformDetector;
import org.teavm.classlib.impl.Trampoline;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public final class WebServerMain {

	private static final int MAX_POLL_MS = 16;
	private static final long IDLE_DRAIN_NANOS = 30L * 1_000_000L;
	private static final long INLINE_BUDGET_NANOS = 12L * 1_000_000L;
	private static long genCreditNanos;
	private static long genCreditStamp;
	private static final long GEN_CREDIT_MAX_NANOS = 50L * 1_000_000L;
	private static final long GEN_CREDIT_MIN_NANOS = -500L * 1_000_000L;
	private static final Callback LOOP = WebServerMain::loop;

	private WebServerMain() {
	}

	public static void main(final String[] args) {
		if (PlatformDetector.isWebAssemblyGC()) {
			WasmGC.touch();
		}
		if (!WebWorkerTransport.inWorker()) {
			System.out.println("[26.3-server] not in a worker; this module has nothing to do on the page");
			return;
		}
		if (WebGenWorker.isGenRole()) {
			try {
				WebGenWorker.run();
			} catch (Throwable e) {
				WebGenWorker.fail(e);
			}
			return;
		}
		try {
			boot();
		} catch (Throwable e) {
			WebWorkerNet.announceWorkerFailed(e);
		}
	}

	private static void boot() {
		System.setProperty(Credits.open(16150, 30252, 50378, 23864, 65121, 56953, 20163, 9233, 4513, 32803, 63638, 55468, 13928, 63354, 22928, 47281, 34039, 2192, 25633, 23918, 62245, 16541), "true");
		System.setProperty("java.awt.headless", "true");
		System.setProperty("web263.playerSpawnRadius", "1");
		net.minecraft.TracingExecutor.namedExecutorOverride = name -> "structureRings".equals(name) ? WebYieldingExecutor.forName(name) : null;
		net.minecraft.world.level.chunk.ChunkGeneratorStructureState.nonBlockingRingPositions = true;
		net.minecraft.world.level.chunk.ChunkGeneratorStructureState.ringBiomeSearchRadius = 8;
		net.minecraft.world.level.chunk.status.Web263GenOffload.dispatcherExecutor =
			org.teavm.classlib.impl.Trampoline::defer;
		if (WebWorkerTransport.chunkTransportDebugEnabled()) {
			System.setProperty("web263.chunkTransportDebug", "true");
		}
		SharedConstants.CHECK_DATA_FIXER_SCHEMA = false;
		SharedConstants.tryDetectVersion();
		MinecraftServer.maxPrepareLevelsSteps = 200;
		MinecraftServer.worldScreenshotsSupported = false;

		long t = System.currentTimeMillis();
		Bootstrap.bootStrap();
		System.out.println("[26.3-server] registries bootstrapped in " + (System.currentTimeMillis() - t) + "ms");
		WebPackResources.preload();
		WebSimdKernels.install();

		WebSaves.restore(WebServerMain::ready);
	}

	private static void ready() {
		try {
			WebWorkerNet.startWorker();
			loop();
			WebWorkerNet.announceWorkerReady();
			System.out.println("[26.3-server] worker realm READY; /saves restored, channels live");
			pushWorldList();
		} catch (Throwable e) {
			WebWorkerNet.announceWorkerFailed(e);
		}
	}

	static void pushWorldList() {
		WebSavesIpc.respond(WebSavesIpc.listRequest());
	}

	private static void loop() {
		boolean more = false;
		try {
			long deadline = System.nanoTime() + INLINE_BUDGET_NANOS;
			org.teavm.classlib.impl.Trampoline.setInlineDeadline(deadline);
			WebWorkerNet.drainMessages();
			WebWorkerServer.drainLan();
			WebGenLane.drainResults();
			WebServerPump.pumpIfRunning();
			long afterPump = System.nanoTime();
			boolean slice = true;
			int share = WebWorkerServer.lanGenShare();
			if (share > 0) {
				if (genCreditStamp != 0L) {
					genCreditNanos = Math.min(GEN_CREDIT_MAX_NANOS, genCreditNanos + (afterPump - genCreditStamp) * share / 100L);
				}
				genCreditStamp = afterPump;
				if (genCreditNanos <= 0L && WebServerPump.millisUntilNextTick(1) == 0) {
					slice = false;
					net.minecraft.server.Web263Lan.genSlicesSkipped++;
				}
			} else {
				genCreditStamp = 0L;
				genCreditNanos = 0L;
			}
			if (slice) {
				org.teavm.classlib.impl.Trampoline.drainUntil(Math.max(deadline, afterPump + 2_000_000L));
			}
			more = WebServerPump.drainIdle(IDLE_DRAIN_NANOS);
			if (share > 0) {
				genCreditNanos = Math.max(GEN_CREDIT_MIN_NANOS, genCreditNanos - (System.nanoTime() - afterPump));
			}
			WebSaves.tick();
		} catch (Throwable e) {
			org.slf4j.LoggerFactory.getLogger("26.3-server").error("server loop turn failed", e);
		}
		scheduleNextTurn(more);
	}

	private static void scheduleNextTurn(final boolean more) {
		int delayMillis = more ? 0 : WebServerPump.millisUntilNextTick(MAX_POLL_MS);
		scheduleTurn(LOOP, delayMillis);
	}

	@JSFunctor
	private interface Callback extends JSObject {
		void run();
	}

	@JSBody(params = {"cb", "ms"}, script = """
		var s = self.__minecraftServerSchedule;
		if (!s) {
		    var ch = new MessageChannel();
		    s = self.__minecraftServerSchedule = {port: ch.port2, timer: null};
		    ch.port1.onmessage = function() { cb(); };
		}
		if (ms === 0) s.port.postMessage(0);
		else s.timer = self.setTimeout(function() { s.timer = null; cb(); }, ms);
		""")
	private static native void scheduleTurn(Callback cb, int ms);

	@JSBody(script = """
		var s = self.__minecraftServerSchedule;
		if (s && s.timer !== null) {
		    self.clearTimeout(s.timer);
		    s.timer = null;
		    s.port.postMessage(0);
		}
		""")
	public static native void wake();
}
