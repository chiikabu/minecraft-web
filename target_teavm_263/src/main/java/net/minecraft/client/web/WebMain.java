// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import com.mojang.renderpearl.backend.opengl.GlApi;
import com.mojang.renderpearl.backend.opengl.GlCompat;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.webgl.WebGL2RenderingContext;
import java.io.File;
import java.net.Proxy;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.telemetry.TelemetryProperty;
import net.minecraft.client.telemetry.events.GameLoadTimesEvent;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.teavm.classlib.PlatformDetector;
import org.teavm.classlib.impl.Trampoline;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.core.JSString;
import org.teavm.jso.dom.html.HTMLCanvasElement;

public class WebMain {
	private static final long DEFERRED_BUDGET_MS = 40L;
	private static final int RING_BIOME_SEARCH_RADIUS = 8;

	private static Minecraft minecraft;
	private static int frames;
	private static long bootStartMs;
	private static boolean deferredIdle;
	private static String lastStatus = "";
	private static boolean statusInitialized;
	private static Object lastScreen;
	private static Object lastOverlay;
	private static boolean lastHasLevel;
	private static boolean lastHasPlayer;
	private static boolean lastHasConn;
	private static boolean lastSrvReady;

	public static int framesPresented() {
		return frames;
	}

	private static int savesReconcileFrames;

	public static void main(String[] args) {
		if (WebWorkerTransport.inWorker()) {
			WebServerMain.main(args);
			return;
		}
		bootStartMs = System.currentTimeMillis();
		System.out.println(Credits.banner());
		if (PlatformDetector.isWebAssemblyGC()) {
			WasmGC.touch();
		}
		WebMemProbe.install();
		final boolean tryWebGpu = com.mojang.blaze3d.webgpu.WebGpuBackend.wanted();
		if (tryWebGpu) {
			com.mojang.blaze3d.webgpu.WebGpuNatives.hookEarlyProbe();
			com.mojang.blaze3d.webgpu.WebGpuBackend.startProbe();
		}
		System.setProperty(Credits.open(16150, 30252, 50378, 23864, 65121, 56953, 20163, 9233, 4513, 32803, 63638, 55468, 13928, 63354, 22928, 47281, 34039, 2192, 25633, 23918, 62245, 16541), "true");
		System.setProperty("java.awt.headless", "true");
		final boolean constrainedMemory = lowRamOrWeakGpu();
		System.setProperty("web263.stagingBufferSize", constrainedMemory ? "8388608" : "33554432");
		System.setProperty("web263.vertexHeapSize", constrainedMemory ? "16777216" : "33554432");
		System.setProperty("web263.indexHeapSize", constrainedMemory ? "4194304" : "8388608");
		System.setProperty("web263.skipMapErrorDrain", "true");
		System.setProperty("web263.fogCulling", Boolean.toString(!queryFlag("nofogculling")));
		System.setProperty("web263.entityCulling", Boolean.toString(!queryFlag("noentityculling")));
		System.setProperty("web263.visibleAnimations", Boolean.toString(!queryFlag("noanimationculling")));
		System.setProperty("web263.tickCulling", Boolean.toString(!queryFlag("notickculling")));
		if (!queryFlag("fullanim")) {
			System.setProperty("web263.animationUploadInterval", "2");
		}
		System.setProperty("web263.sodiumChunks", Boolean.toString(!queryFlag("nosodiumchunks")));
		System.setProperty("web263.fastGui", Boolean.toString(!queryFlag("nofastgui")));
		System.setProperty("web263.hudCache", Boolean.toString(!queryFlag("nohudcache")));
		System.setProperty("web263.tbdrFuse", Boolean.toString(!queryFlag("notbdr")));
		if (!queryFlag("fullterrain")) {
			System.setProperty("web263.fastTerrainShader", "true");
		}
		System.setProperty("web263.glBindFilter", Boolean.toString(!queryFlag("noglfilter")));
		if (queryFlag("chunknetdebug")) {
			System.setProperty("web263.chunkTransportDebug", "true");
		}
		if (locationSearch().contains("readback")) {
			System.setProperty("web263.readback", "true");
		}
		SharedConstants.CHECK_DATA_FIXER_SCHEMA = false;
		SharedConstants.tryDetectVersion();
		long bootstrapStart = System.currentTimeMillis();
		Bootstrap.bootStrap();
		ClientBootstrap.bootstrap();
		System.out.println("[26.3-web] registries bootstrapped in " + (System.currentTimeMillis() - bootstrapStart) + "ms");
		GameLoadTimesEvent.INSTANCE.beginStep(TelemetryProperty.LOAD_TIME_TOTAL_TIME_MS);
		GameLoadTimesEvent.INSTANCE.beginStep(TelemetryProperty.LOAD_TIME_PRE_WINDOW_MS);

		HTMLCanvasElement canvas = canvas();
		if (canvas == null) {
			System.out.println("[26.3-web] no #game_frame canvas; cannot boot");
			return;
		}
		if (tryWebGpu) {
			com.mojang.blaze3d.webgpu.WebGpuBackend.whenReady(() -> chooseBackend(canvas, true));
			return;
		}
		chooseBackend(canvas, false);
	}

	private static void chooseBackend(HTMLCanvasElement canvas, boolean tryWebGpu) {
		if (tryWebGpu && com.mojang.blaze3d.webgpu.WebGpuBackend.claimCanvas()) {
			System.out.println("[26.3-web] graphics: WebGPU - " + com.mojang.blaze3d.webgpu.WebGpuBackend.reason());
			initRenderThread();
		} else {
			System.out.println("[26.3-web] graphics: WebGL2 - " + com.mojang.blaze3d.webgpu.WebGpuBackend.reason());
			if (!initGl(canvas)) {
				return;
			}
		}
		afterContext(canvas);
	}

	private static void afterContext(HTMLCanvasElement canvas) {
		net.minecraft.client.Screenshot.saveHandler = WebScreenshot::save;

		net.minecraft.client.Minecraft.workerWorldHost = WebWorkerLifecycle.HOST;
		if (WebWorkerTransport.supported()) {
			try {
				WebWorkerNet.startPage();
				System.out.println("[26.3-web] worker: waiting for server realm READY");
			} catch (Throwable t) {
				System.out.println("[26.3-web] worker spawn failed; singleplayer is unavailable: " + t);
			}
		} else {
			System.out.println("[26.3-web] Web Workers are unavailable; singleplayer is unavailable");
		}

		net.minecraft.TracingExecutor.namedExecutorOverride = WebYieldingExecutor::forName;
		net.minecraft.client.ClientPhaseProbe.enabled = true;
		WebChunkBuildQueue.setBudgetMs(queryInt("chunkbudget", 0));
		WebMeshWorker.install(queryFlag("nomeshworker"));
		if (!queryFlag("nosimd")) {
			WebSimdKernels.install();
			WebSimdKernels.installClient();
		}
		WebFrameProbe.spikeLogMs = queryInt("spikelog", 0);
		ChunkGeneratorStructureState.ringBiomeSearchRadius = RING_BIOME_SEARCH_RADIUS;
		ChunkGeneratorStructureState.nonBlockingRingPositions = true;
		net.minecraft.server.MinecraftServer.maxPrepareLevelsSteps = 200;
		net.minecraft.server.MinecraftServer.worldScreenshotsSupported = false;
		net.minecraft.server.packs.resources.ReloadableResourceManager.forceProfiledReload = true;

		WebPackResources.preload();

		WebProfile.load();
		WebSaves.restore(() -> boot(canvas));
	}

	private static final String KEY_DEFAULTS = "minecraft_web:defaults";
	private static final String FAST_DEFAULTS_VERSION = "3";

	private static void applyFastDefaults(final net.minecraft.client.Options options) {
		options.renderDistance().set(4);
		options.simulationDistance().set(5);
		options.chunkSectionFadeInTime().set(0.0);
		options.cutoutLeaves().set(false);
		options.improvedTransparency().set(false);
		options.entityShadows().set(false);
		options.entityDistanceScaling().set(0.5);
		options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL);
		options.biomeBlendRadius().set(0);
		options.ambientOcclusion().set(false);
		options.menuBackgroundBlurriness().set(0);
		options.vignette().set(false);
		options.mipmapLevels().set(1);
		options.weatherRadius().set(5);
		System.out.println("[26.3-web] fast defaults: view 4, simulation 5, fast leaves, no shadows,"
			+ " minimal particles, no biome blend, flat lighting, no menu blur, no vignette, mipmap 1, weather radius 5");
	}

	@JSBody(params = { "k" }, script = "try { return window.localStorage.getItem(k) || ''; } catch (e) { return ''; }")
	private static native String lsGet(String k);

	@JSBody(params = { "k", "v" }, script = "try { window.localStorage.setItem(k, v); } catch (e) {}")
	private static native void lsSet(String k, String v);

	private static String seedFastOptions() {
		return "version:" + SharedConstants.getCurrentVersion().dataVersion().version() + "\n"
			+ "renderDistance:4\n"
			+ "simulationDistance:5\n"
			+ "chunkSectionFadeInTime:0.0\n"
			+ "cutoutLeaves:false\n"
			+ "improvedTransparency:false\n"
			+ "entityShadows:false\n"
			+ "entityDistanceScaling:0.5\n"
			+ "particles:2\n"
			+ "biomeBlendRadius:0\n"
			+ "ao:false\n"
			+ "menuBackgroundBlurriness:0\n"
			+ "vignette:false\n"
			+ "mipmapLevels:1\n"
			+ "weatherRadius:5\n";
	}

	private static void boot(HTMLCanvasElement canvas) {
		long t = System.currentTimeMillis();
		try {
			File optionsFile = new File("/options.txt");
			boolean firstRun = net.minecraft.client.OptionsStorage.read(optionsFile) == null;
			if (firstRun) {
				net.minecraft.client.OptionsStorage.write(optionsFile, seedFastOptions());
			}
			Minecraft.allowOfflineProfileKeys = true;
			Minecraft.web263MaxTicksPerFrame = Math.max(1, Math.min(10, queryInt("maxticks", 4)));
			net.minecraft.client.FramerateLimiter.externallyPaced = true;
			minecraft = new Minecraft(gameConfig(canvas));
			WebProfile.applySkin();
			if (queryFlag("memcheck")) {
				WebMemCheck.run();
			}
			WebProfile.install();
			WebRelayAuth.restore();
			WebPacks.install();
			net.minecraft.util.Util.openUriHandler = WebMain::openUri;
			minecraft.options.prioritizeChunkUpdates().set(net.minecraft.client.PrioritizeChunkUpdates.PLAYER_AFFECTED);
			if (firstRun || !FAST_DEFAULTS_VERSION.equals(lsGet(KEY_DEFAULTS))) {
				applyFastDefaults(minecraft.options);
				lsSet(KEY_DEFAULTS, FAST_DEFAULTS_VERSION);
			}
			if (!com.mojang.blaze3d.webgpu.WebGpuBackend.live()) {
				minecraft.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
			}
		} catch (Throwable e) {
			System.out.println("[26.3-web] construction failed: " + e.getClass().getName() + " msg=" + e.getMessage());
			if (com.mojang.blaze3d.webgpu.WebGpuBackend.live()) {
				com.mojang.blaze3d.webgpu.WebGpuBackend.reloadWithWebGl("construction failed: " + e);
				return;
			}
			org.slf4j.LoggerFactory.getLogger("26.3-web").error("Minecraft construction failed", e);
			Throwable c = e;
			for (int depth = 0; c != null && depth < 6; depth++) {
				StackTraceElement[] st = c.getStackTrace();
				System.out.println("[26.3-web] cause" + depth + " " + c.getClass().getName() + " nframes=" + (st == null ? -1 : st.length));
				if (st != null) {
					for (int i = 0; i < Math.min(st.length, 24); i++) {
						System.out.println("[26.3-web]   at " + st[i]);
					}
				}
				c = c.getCause();
			}
			return;
		}
		System.out.println("[26.3-web] Minecraft constructed in " + (System.currentTimeMillis() - t) + "ms; starting frame loop");
		installFullscreenSync();
		scheduleFrame();
	}

	private static void installFullscreenSync() {
		Window.current().getDocument().addEventListener("fullscreenchange", event -> {
			if (minecraft.getWindow() == null || isDocumentFullscreen()) {
				return;
			}
			minecraft.fullscreenStateChanged(false);
		});
	}

	public static boolean ensureGl() {
		if (GlApi.ready()) {
			return true;
		}
		HTMLCanvasElement canvas = canvas();
		return canvas != null && initGl(canvas);
	}

	private static boolean renderThreadReady;

	private static void initRenderThread() {
		if (!renderThreadReady) {
			renderThreadReady = true;
			RenderSystem.initRenderThread();
		}
	}

	private static boolean initGl(HTMLCanvasElement canvas) {
		boolean capture = locationSearch().contains("capture");
		JSObject rawContext = getWebGL2Context(canvas, capture);
		if (rawContext == null) {
			System.out.println("[26.3-web] WebGL2 unavailable; cannot boot");
			return false;
		}
		System.out.println("[26.3-web] GL " + glIdentity(rawContext) + "; canvas "
			+ canvas.getWidth() + "x" + canvas.getHeight() + " backing");
		initRenderThread();
		WebGL2RenderingContext gl = rawContext.cast();
		GlApi.captureMode = capture;
		GlApi.init(gl);
		GlCompat.setup(gl.getExtension(JSString.valueOf("EXT_texture_filter_anisotropic")) != null);
		return true;
	}

	private static GameConfig gameConfig(HTMLCanvasElement canvas) {
		User user = new User(WebProfile.username(), WebProfile.uuid(), "", Optional.empty(), Optional.empty());
		return new GameConfig(
			new GameConfig.UserData(user, Proxy.NO_PROXY),
			new DisplayData(canvas.getClientWidth(), canvas.getClientHeight(), OptionalInt.empty(), OptionalInt.empty(), false),
			new GameConfig.FolderData(new File("/"), new File("/resourcepacks"), new File("/assets"), null),
			new GameConfig.GameData(
				false,
				SharedConstants.getCurrentVersion().name(),
				"release",
				false,
				false,
				false,
				false,
				false,
				PreferredGraphicsApi.OPENGL,
				true
			),
			quickPlay()
		);
	}

	private static GameConfig.QuickPlayData quickPlay() {
		String server = quickPlayServer();
		if (server == null || server.isBlank()) {
			return new GameConfig.QuickPlayData(null, GameConfig.QuickPlayVariant.DISABLED);
		}
		System.out.println("[26.3-web] quick play: joining " + server);
		return new GameConfig.QuickPlayData(null, new GameConfig.QuickPlayMultiplayerData(server));
	}

	@JSBody(script = "var s = window.__minecraftWebServer; return typeof s === 'string' ? s : null;")
	private static native String quickPlayServer();

	private static final FrameCallback PUMP = WebMain::pumpFrame;
	private static final FrameCallback SCHEDULE = WebMain::scheduleReadyFrame;
	private static JSObject guardedPump;

	private static long frameStartMs;
	private static final FramePacer FRAME_PACER = new FramePacer();
	private static JSObject pacerPort;

	private static int trackedFramerateLimit() {
		final var renderer = minecraft.gameRenderer;
		final var state = renderer == null ? null : renderer.gameRenderState();
		int limit = state != null && state.framerateLimit > 0 ? state.framerateLimit : minecraft.options.framerateLimit().get();
		return FramePacer.backgroundLimit(limit, !minecraft.isWindowActive() || pageHidden());
	}

	@JSBody(script = "return document.hidden;")
	private static native boolean pageHidden();

	private static JSObject guardedPump() {
		if (guardedPump == null) {
			guardedPump = wrapFrameCallback(PUMP);
		}
		return guardedPump;
	}

	private static void scheduleFrame() {
		if (com.mojang.blaze3d.webgpu.WebGpuBackend.live()) {
			paceGpuFrame(SCHEDULE);
		} else {
			scheduleReadyFrame();
		}
	}

	private static void scheduleReadyFrame() {
		final long delay = minecraft == null ? -1L
			: FRAME_PACER.nextDelayMs(minecraft.options.enableVsync().get(),
				trackedFramerateLimit(), frameStartMs, System.nanoTime() / 1_000_000L);
		if (delay < 0L) {
			requestGuardedFrame(guardedPump());
		} else if (delay == 0L) {
			if (pacerPort == null) {
				pacerPort = createRawPacerPort(guardedPump());
			}
			pacerPost(pacerPort);
		} else {
			setTimeoutRaw(guardedPump(), (int) delay);
		}
	}

	@org.teavm.jso.JSFunctor
	private interface FrameCallback extends JSObject {
		void run();
	}

	@JSBody(params = {"fn"}, script = ""
		+ "var traps = 0;"
		+ "var run = function() {"
		+ "  try {"
		+ "    fn();"
		+ "    traps = 0;"
		+ "  } catch (err) {"
		+ "    traps++;"
		+ "    console.error('[26.3-web] frame trap (' + traps + '/5):', err);"
		+ "    if (traps <= 5) {"
		+ "      window.requestAnimationFrame(run);"
		+ "    } else {"
		+ "      console.error('[26.3-web] FRAME LOOP STOPPED after ' + traps + ' consecutive JS/WASM traps; reload to recover.');"
		+ "    }"
		+ "  }"
		+ "};"
		+ "return run;")
	private static native JSObject wrapFrameCallback(FrameCallback fn);

	@JSBody(params = {"run"}, script = "window.requestAnimationFrame(run);")
	private static native void requestGuardedFrame(JSObject run);

	@JSBody(params = {"run", "ms"}, script = "window.setTimeout(run, ms);")
	private static native void setTimeoutRaw(JSObject run, int ms);

	@JSBody(params = {"run"}, script = "var ch = new MessageChannel(); ch.port1.onmessage = function() { run(); }; return ch.port2;")
	private static native JSObject createRawPacerPort(JSObject run);

	@JSBody(params = {"cb"}, script = """
		var gpu = window.__mcgpu;
		var q = gpu.inFlight || (gpu.inFlight = []);
		var rec = { done: false };
		rec.p = gpu.dev.queue.onSubmittedWorkDone().then(function () { rec.done = true; }, function (error) {
			rec.done = true;
			gpu.errors++;
			gpu.lastError = String(error);
			console.error('[webgpu] frame completion failed: ' + error);
		});
		q.push(rec);
		while (q.length && q[0].done) { q.shift(); }
		if (q.length <= 3) { cb(); } else { q[0].p.then(cb); }
		""")
	private static native void paceGpuFrame(FrameCallback cb);

	@JSBody(params = {"cb"}, script = "var ch = new MessageChannel(); ch.port1.onmessage = function() { cb(); }; return ch.port2;")
	private static native JSObject createPacerPort(FrameCallback cb);

	@JSBody(params = {"port"}, script = "port.postMessage(0);")
	private static native void pacerPost(JSObject port);

	private static final long FAILURE_WINDOW_MS = 10_000L;
	private static final int MAX_FAILURES_PER_WINDOW = 200;
	private static long failureWindowStart;
	private static int failuresInWindow;
	private static int consecutiveFrameFailures;

	private static int recordFailure() {
		long now = System.currentTimeMillis();
		if (now - failureWindowStart > FAILURE_WINDOW_MS) {
			failureWindowStart = now;
			failuresInWindow = 0;
		}
		return ++failuresInWindow;
	}

	private static long frameAttempts;

	private static long meshBudgetNanos(final long[] mark) {
		if (minecraft == null || minecraft.options == null) {
			return (long)(WebChunkBuildQueue.DEFAULT_BUDGET_MS * 1_000_000.0);
		}
		if (meshThroughputMode()) {
			return (long)(WebChunkBuildQueue.LOADING_BUDGET_MS * 1_000_000.0);
		}
		int limit = minecraft.options.framerateLimit().get();
		long other = mark[3] > mark[2] ? (mark[1] - mark[0]) + (mark[3] - mark[2]) + (mark[5] - mark[4]) : 0L;
		return WebChunkBuildQueue.frameBudgetNanos(limit, other);
	}

	private static boolean meshThroughputMode() {
		return minecraft == null || minecraft.level == null || minecraft.gui.overlay() != null
			|| minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen;
	}

	private static final long[] frameMarks = new long[6];
	private static final int[] innerPhaseMicros = new int[net.minecraft.client.ClientPhaseProbe.PHASES];

	private static void pumpFrame() {
		frameStartMs = System.nanoTime() / 1_000_000L;
		frameAttempts++;
		try {
			WebWorkerNet.drainMessages();
			WebWorkerLifecycle.updateDistances(minecraft.options.renderDistance().get(), minecraft.options.simulationDistance().get());
			long[] mark = frameMarks;
			mark[0] = System.nanoTime();
			drainDeferredWork();
			mark[1] = System.nanoTime();
			net.minecraft.client.renderer.chunk.Web263MeshOffload.frame();
			WebChunkBuildQueue.drainForFrame(meshBudgetNanos(mark), meshThroughputMode());
			mark[2] = System.nanoTime();
			minecraft.runFrame();
			try {
				net.minecraft.client.web.mods.WebMods.onFrame();
			} catch (Throwable modErr) {
				System.out.println("[webmods] onFrame error: " + modErr);
			}
			long elapsedNanos = System.nanoTime() - mark[0];
			if (!net.minecraft.client.renderer.chunk.Web263MeshOffload.saturated() && elapsedNanos < 12_000_000L) {
				WebChunkBuildQueue.drainPostFrameForWorker(Math.min(2_500_000L, 14_000_000L - elapsedNanos));
			}
			mark[3] = System.nanoTime();
			mark[4] = System.nanoTime();
			WebSaves.tick();
			if ((++savesReconcileFrames & 15) == 0 && Minecraft.getInstance() != null && Minecraft.getInstance().level == null) {
				WebSavesIpc.reconcile();
			}
			mark[5] = System.nanoTime();
			net.minecraft.client.ClientPhaseProbe.takeFrame(innerPhaseMicros);
			WebFrameProbe.record(mark, innerPhaseMicros);
			consecutiveFrameFailures = 0;
		} catch (Throwable e) {
			if (com.mojang.blaze3d.webgpu.WebGpuBackend.live() && frames < com.mojang.blaze3d.webgpu.WebGpuBackend.STARTUP_FRAMES
					&& consecutiveFrameFailures >= 2) {
				com.mojang.blaze3d.webgpu.WebGpuBackend.reloadWithWebGl("frames failing: " + e);
				return;
			}
			int inWindow = recordFailure();
			if (++consecutiveFrameFailures == 1) {
				org.slf4j.LoggerFactory.getLogger("26.3-web").error("frame attempt " + frameAttempts
						+ " failed, continuing (presented frames stay at " + frames + ")", e);
			} else {
				System.out.println("[26.3-web] frame attempt " + frameAttempts + " failed ("
						+ consecutiveFrameFailures + " in a row, " + inWindow + " in " + (FAILURE_WINDOW_MS / 1000)
						+ "s; presented frames stuck at " + frames + "): " + e);
			}
			if (inWindow >= MAX_FAILURES_PER_WINDOW) {
				org.slf4j.LoggerFactory.getLogger("26.3-web").error("FRAME LOOP STOPPED — the client is no"
						+ " longer rendering. " + inWindow + " failures in " + (FAILURE_WINDOW_MS / 1000)
						+ "s, after " + frames + " presented frames and " + frameAttempts
						+ " attempts. The canvas is frozen from here; reload to recover.", e);
				return;
			}
			scheduleFrame();
			return;
		}
		reportStatus();
		if (com.mojang.blaze3d.webgpu.WebGpuBackend.live() && !com.mojang.blaze3d.webgpu.WebGpuBackend.startupHealthy(frames)) {
			return;
		}
		org.lwjgl.glfw.GLFW.setUnloadPrompt(minecraft.level != null);
		if (frames++ == 0) {
			System.out.println("[26.3-web] MILESTONE 4: first client frame presented "
					+ (System.currentTimeMillis() - bootStartMs) + "ms after Play");
		}
		if (minecraft.isRunning()) {
			scheduleFrame();
			armWorkPump();
		} else {
			System.out.println("[26.3-web] client stopped after " + frames + " frames");
		}
	}

	private static void reportStatus() {
		Object screen = minecraft.gui.screen();
		Object overlay = minecraft.gui.overlay();
		boolean hasLevel = minecraft.level != null;
		boolean hasPlayer = minecraft.player != null;
		boolean hasConn = minecraft.getConnection() != null;
		boolean srvReady = minecraft.getSingleplayerServer() != null && minecraft.getSingleplayerServer().isReady();
		if (statusInitialized && screen == lastScreen && overlay == lastOverlay
				&& hasLevel == lastHasLevel && hasPlayer == lastHasPlayer
				&& hasConn == lastHasConn && srvReady == lastSrvReady) {
			return;
		}
		statusInitialized = true;
		lastScreen = screen;
		lastOverlay = overlay;
		lastHasLevel = hasLevel;
		lastHasPlayer = hasPlayer;
		lastHasConn = hasConn;
		lastSrvReady = srvReady;
		String status = "screen=" + (screen == null ? "-" : screen.getClass().getSimpleName())
				+ " overlay=" + (overlay == null ? "-" : overlay.getClass().getSimpleName())
				+ " level=" + hasLevel
				+ " player=" + hasPlayer
				+ " conn=" + hasConn
				+ " srvReady=" + srvReady;
		if (!status.equals(lastStatus)) {
			lastStatus = status;
			System.out.println("[26.3-web] frame " + frames + " " + status);
		}
	}

	private static boolean openUri(java.net.URI uri) {
		String scheme = uri.getScheme();
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			return false;
		}
		String url = uri.toString();
		if (openTab(url)) {
			return true;
		}
		System.out.println("[26.3-web] popup blocked, URL copied to the clipboard instead: " + url);
		if (minecraft != null) {
			minecraft.keyboardHandler.setClipboard(url);
		}
		return true;
	}

	@JSBody(params = { "url" }, script = "try { return !!window.open(url, '_blank', 'noopener,noreferrer'); }"
		+ " catch (e) { return false; }")
	private static native boolean openTab(String url);

	private static void drainDeferredWork() {
		if (!Trampoline.hasDeferred()) {
			deferredIdle = true;
			return;
		}
		if (deferredIdle) {
			deferredIdle = false;
			System.out.println("[26.3-web] background work queued at frame " + frames
					+ " (collapses so far: " + Trampoline.collapseCount() + ")");
			return;
		}
		long budget = minecraft != null && minecraft.level != null ? 4L : DEFERRED_BUDGET_MS;
		if (Trampoline.drainDeferred(budget)) {
			System.out.println("[26.3-web] background work drained at frame " + frames
					+ ", " + (System.currentTimeMillis() - bootStartMs) + "ms after boot");
		}
	}

	private static final long WORK_SLICE_MS = 8L;
	private static final FrameCallback WORK = WebMain::pumpBackgroundWork;
	private static JSObject workPort;
	private static boolean workPumpArmed;

	private static boolean backgroundWorkWanted() {
		return minecraft != null && minecraft.level == null && !deferredIdle && Trampoline.hasDeferred();
	}

	private static void armWorkPump() {
		if (workPumpArmed || !backgroundWorkWanted()) {
			return;
		}
		workPumpArmed = true;
		if (workPort == null) {
			workPort = createPacerPort(WORK);
		}
		pacerPost(workPort);
	}

	private static void pumpBackgroundWork() {
		workPumpArmed = false;
		if (!backgroundWorkWanted()) {
			return;
		}
		try {
			if (Trampoline.drainDeferred(WORK_SLICE_MS)) {
				System.out.println("[26.3-web] background work drained between frames, "
						+ (System.currentTimeMillis() - bootStartMs) + "ms after boot");
				return;
			}
		} catch (Throwable e) {
			org.slf4j.LoggerFactory.getLogger("26.3-web").error("background work slice failed", e);
			return;
		}
		armWorkPump();
	}

	private static HTMLCanvasElement canvas() {
		return (HTMLCanvasElement)Window.current().getDocument().getElementById("game_frame");
	}

	@JSBody(params = {}, script = "return window.location.search;")
	private static native String locationSearch();

	@JSBody(script = "return (navigator.hardwareConcurrency || 8) <= 4 || (navigator.deviceMemory || 8) <= 4;")
	private static native boolean lowEndDevice();

	@JSBody(script = "try { return ((navigator.deviceMemory || 8) <= 4) || !!(typeof window.__minecraftWebWeakGpu === 'function' && window.__minecraftWebWeakGpu()) || !!(typeof window.__twitcraftWeakGpu === 'function' && window.__twitcraftWeakGpu()); } catch (e) { return false; }")
	private static native boolean lowRamOrWeakGpu();

	@JSBody(script = "return !!(window.matchMedia && matchMedia('(pointer: coarse)').matches && !matchMedia('(hover: hover)').matches);")
	private static native boolean touchDevice();

	static boolean queryFlag(String name) {
		String search = locationSearch();
		if (search == null) {
			return false;
		}
		String params = "&" + search.replaceFirst("^\\?", "") + "&";
		return params.contains("&" + name + "&") || params.contains("&" + name + "=1&");
	}

	private static int queryInt(String name, int fallback) {
		String search = locationSearch();
		if (search == null) {
			return fallback;
		}
		for (String param : search.replaceFirst("^\\?", "").split("&")) {
			int eq = param.indexOf('=');
			if (eq > 0 && param.substring(0, eq).equals(name)) {
				try {
					return Math.max(2, Math.min(16, Integer.parseInt(param.substring(eq + 1).trim())));
				} catch (NumberFormatException ignored) {
					return fallback;
				}
			}
		}
		return fallback;
	}

	@JSBody(params = {"canvas", "capture"}, script = "return canvas.getContext('webgl2', {alpha:false, antialias:false, depth:false, stencil:false, powerPreference:'high-performance', preserveDrawingBuffer:capture});")
	private static native JSObject getWebGL2Context(HTMLCanvasElement canvas, boolean capture);

	@JSBody(params = {"gl"}, script = "var d = gl.getExtension('WEBGL_debug_renderer_info');"
		+ " return (d ? gl.getParameter(d.UNMASKED_VENDOR_WEBGL) + ' / ' + gl.getParameter(d.UNMASKED_RENDERER_WEBGL)"
		+ " : gl.getParameter(gl.VENDOR) + ' / ' + gl.getParameter(gl.RENDERER)) + ' | ' + gl.getParameter(gl.VERSION);")
	private static native String glIdentity(JSObject gl);

	@JSBody(script = "return !!document.fullscreenElement;")
	private static native boolean isDocumentFullscreen();
}
