package net.minecraft.client.web.mods;

import com.mojang.blaze3d.platform.Window;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.web.WebLifecycleMsg;
import net.minecraft.client.web.WebSaves;
import net.minecraft.client.web.WebWorkerLifecycle;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * In-browser Mod Manager, Pure-Data/Asset Mod Extractor, Baked WASM Profile Selector, and
 * {@code window.EaglerMods} JavaScript Mod Runtime.
 *
 * <p>Storage lives under {@code /config/mods} (and extracted resource packs under
 * {@code /resourcepacks}), both of which {@link WebSaves} mirrors to IndexedDB on the page realm so
 * installed mods survive page reloads without touching the worker-owned {@code /saves} root.
 *
 * <p>Extracted data packs from pure data/asset mods are staged in {@code /config/mods/datapacks}
 * and forwarded into newly created worlds via {@link #appendDataPacksTo(WebLifecycleMsg.OpenSpec)}.
 */
public final class WebMods {
	/** Stored mod files (.js, .mjs, .jar, .mcmod.zip) — mirrored to IndexedDB under /config. */
	public static final String DIR = "/config/mods";
	/** Extracted data packs from pure data/asset mods — mirrored under /config. */
	public static final String DATAPACKS_DIR = DIR + "/datapacks";
	/** Vanilla resource packs folder — mirrored under /resourcepacks. */
	private static final String RESOURCEPACKS_DIR = "/resourcepacks";
	/** Persisted disable list ({@code disabled=<filename>}). */
	private static final String STATE_FILE = DIR + "/state.txt";
	/** localStorage key read synchronously by index.html to choose a baked WASM profile. */
	public static final String LS_MOD_PROFILE = "minecraft.modprofile.selected";
	/** Optional localStorage override for the local mod-builder base URL. */
	public static final String LS_MOD_BUILDER_URL = "minecraft.modbuilder.url";

	private static final int FALLBACK_RESOURCE_FORMAT = 97;
	private static final int FALLBACK_DATA_FORMAT = 121;
	private static final long TICK_INTERVAL_NANOS = 50_000_000L; // 20 Hz fallback tick pulse

	private static boolean installed;
	private static boolean scanned;
	private static final Map<String, InstalledMod> mods = new LinkedHashMap<>();
	private static final Set<String> disabledFiles = new HashSet<>();
	private static final Set<String> runningJsFiles = new HashSet<>();
	private static final Set<String> runningJvmFiles = new HashSet<>();
	private static String status = "";
	private static String bootedProfile = "";
	private static Runnable onChanged;
	private static int lastBuilderRev = -1;
	private static boolean pendingReloadAfterSave;
	private static long pendingReloadDeadlineMs;

	// Reference-counted batching so multi-mod imports perform AT MOST ONE resource reload & save flush
	private static int batchDepth;
	private static final Set<String> batchAddedPacks = new LinkedHashSet<>();
	private static final Set<String> batchRemovedPacks = new LinkedHashSet<>();
	private static boolean batchSavesDirty;
	private static boolean batchNotifyDirty;

	// Fast static active-mod & hook flags (zero overhead in Camera / LightmapRenderStateExtractor / onFrame when false)
	private static boolean hasActiveJsMods;
	private static boolean hasActiveJvmMods;
	private static boolean hasJvmHooks;
	private static boolean hasFovHooks;
	private static boolean hasGammaHooks;
	private static boolean hasHudHooks;
	private static boolean hasTickHooks;
	private static boolean hasFrameHooks;
	private static boolean hasJsTickListeners;
	private static boolean hasJsScreenListeners;
	private static boolean hasJsWorldJoinListeners;
	private static boolean hasJsWorldLeaveListeners;
	private static boolean hasJvmTickStartHooks;
	private static boolean hasJvmPlayerTickHooks;
	private static boolean hasJvmTickEndHooks;
	private static boolean hasJvmScreenHooks;
	private static boolean hasJvmWorldJoinHooks;
	private static boolean hasJvmWorldLeaveHooks;
	private static boolean builderActive;

	// Lifecycle tracking for JS/JVM event hooks
	private static long frameCounter;
	private static long tickCounter;
	private static long lastFrameNanos;
	private static long lastTickPulseNanos;
	private static long lastLevelGameTime = Long.MIN_VALUE;
	private static boolean explicitTickHookSeen;
	private static String lastScreenName = "";
	private static boolean lastInWorld;

	// Zero-allocation reference caches for syncLiveState string fields
	private static boolean liveStateStringsInit;
	private static Object lastDimension;
	private static Screen lastScreen;
	private static Object lastMainItem;
	private static Object lastOffItem;
	private static LocalPlayer lastPlayer;
	private static GameType lastGameType;
	private static Object lastUser;
	private static String cachedDimension = "";
	private static String cachedScreenName = "";
	private static String cachedScreenTitle = "";

	private WebMods() {
	}

	/** View of one mod stored in {@code /config/mods}. */
	public static final class InstalledMod {
		public final String file;
		public final ModJarInspector.ModInfo info;
		public final int byteSize;
		public boolean enabled;
		public boolean jsRunning;
		public boolean jvmTranslated;
		public String jvmSummary = "";
		public boolean jvmHasUnsupportedMixins;
		public int jvmClassesLoaded;
		public int jvmEntrypointsRun;
		public int jvmMixinsAttached;
		public int jvmMixinsUnsupported;
		public String extractedResourcePack;
		public String extractedDataPack;
		public String runtimeError = "";

		InstalledMod(final String file, final ModJarInspector.ModInfo info, final int byteSize, final boolean enabled) {
			this.file = file;
			this.info = info;
			this.byteSize = byteSize;
			this.enabled = enabled;
		}

		public boolean canRunInBrowser() {
			return this.info.canRunInBrowser();
		}

		public boolean canRunViaTranslator() {
			return this.info.kind == ModJarInspector.ModKind.FABRIC_NEEDS_BAKE;
		}

		public boolean isFabricBakeable() {
			return this.info.kind == ModJarInspector.ModKind.FABRIC_NEEDS_BAKE;
		}

		public boolean isToggleable() {
			return this.canRunInBrowser() || this.canRunViaTranslator();
		}

		public boolean canExtractPack() {
			return this.info.canExtractPack();
		}

		public boolean hasExtractedPacks() {
			return this.extractedResourcePack != null || this.extractedDataPack != null;
		}
	}

	// ---- Lifecycle & Installation ----

	/**
	 * Install the hidden mod file input, {@code window.EaglerMods} runtime API, offline JVM-to-JS
	 * translator hooks, optional local auto-builder HTTP bridge, and load stored mods from
	 * IndexedDB-restored {@code /config/mods}. Call once after {@code Minecraft} exists.
	 */
	public static void install() {
		if (installed) {
			return;
		}
		installed = true;
		bootedProfile = readBootedProfile();
		installFileInput(WebMods::accept, WebMods::beginBatch, WebMods::endBatch);
		installRuntimeBridge(
			WebMods::toast,
			WebMods::chatLocal,
			WebMods::sendChatOrCommand,
			WebMods::actionBar,
			WebMods::reloadResources,
			WebMods::openModsScreen,
			WebMods::syncLiveStateFromJs,
			WebMods::buildModsListJson,
			WebMods::status,
			WebMods::buildProfileJson,
			WebMods::selectProfile,
			WebMods::acceptFromJsText,
			WebMods::setEnabledFromJs,
			WebMods::removeFromJs,
			WebMods::extractPack,
			WebMods::reloadAllMods,
			WebMods::refreshJvmHookFlags);
		installBuilderBridge(
			WebMods::toast,
			WebMods::isSafeAutoReload,
			WebMods::isInWorld,
			WebMods::reloadNow,
			WebMods::flushSaves,
			WebMods::bootedProfile,
			WebMods::selectedProfile);
		installExtendedJsApi(
			WebMods::getGamma,
			WebMods::setGamma,
			WebMods::getFov,
			WebMods::setFov,
			WebMods::setSprinting,
			WebMods::setRotation,
			WebMods::reloadNow,
			WebMods::triggerRebuild);
		loadPersistedState();
		scanStorage();
		activateEnabledMods(false);
		refreshJvmHookFlags();
		net.minecraft.client.Camera.webFovModifier = fov -> applyFovModifier((float) fov);
		jsProbeBuilderStatus();
		System.out.println("[webmods] installed (" + mods.size() + " mod(s), "
			+ runningJsFiles.size() + " JS active, " + runningJvmFiles.size() + " Java translated, profile="
			+ (bootedProfile.isEmpty() ? "vanilla" : bootedProfile) + ")");
	}

	/**
	 * Live FOV modifier hook ready to be called from {@code Camera.calculateFov(float)}.
	 * Zero overhead (single static boolean check) when no JVM FOV hook is active.
	 */
	public static float applyFovModifier(final float fov) {
		if (!hasFovHooks) {
			return fov;
		}
		double out = jsDispatchNumericHook("fovModifier", fov);
		if (!Double.isFinite(out) || out <= 0.0) {
			return fov;
		}
		return (float) Math.max(1.0, Math.min(175.0, out));
	}

	/**
	 * Live gamma/brightness modifier hook called from {@code LightmapRenderStateExtractor}.
	 * Zero overhead (single static boolean check) when no JVM gamma hook is active.
	 */
	public static float applyGammaModifier(final float gamma) {
		if (!hasGammaHooks) {
			return gamma;
		}
		double out = jsDispatchNumericHook("gammaModifier", gamma);
		if (!Double.isFinite(out)) {
			return gamma;
		}
		return (float) Math.max(0.0, Math.min(32.0, out));
	}

	private static void refreshJvmHookFlags() {
		hasJsTickListeners = jsHasEventListeners("tick");
		hasFrameHooks = jsHasEventListeners("frame");
		hasJsScreenListeners = jsHasEventListeners("screen");
		hasJsWorldJoinListeners = jsHasEventListeners("worldJoin");
		hasJsWorldLeaveListeners = jsHasEventListeners("worldLeave");
		boolean anyJsListeners = hasJsTickListeners || hasFrameHooks
			|| hasJsScreenListeners || hasJsWorldJoinListeners || hasJsWorldLeaveListeners;
		hasActiveJsMods = !runningJsFiles.isEmpty() || anyJsListeners;

		boolean anyRunningJvm = !runningJvmFiles.isEmpty();
		hasActiveJvmMods = anyRunningJvm || jsJvmHasTranslatedMods();
		hasJvmHooks = hasActiveJvmMods && jsJvmHasAnyHooks(anyRunningJvm);
		if (!hasJvmHooks) {
			hasFovHooks = false;
			hasGammaHooks = false;
			hasHudHooks = false;
			hasJvmTickStartHooks = false;
			hasJvmPlayerTickHooks = false;
			hasJvmTickEndHooks = false;
			hasJvmScreenHooks = false;
			hasJvmWorldJoinHooks = false;
			hasJvmWorldLeaveHooks = false;
		} else {
			hasFovHooks = jsJvmHasHook("fovModifier", anyRunningJvm);
			hasGammaHooks = jsJvmHasHook("gammaModifier", anyRunningJvm);
			hasHudHooks = jsJvmHasHook("hudRender", anyRunningJvm);
			hasJvmTickStartHooks = jsJvmHasHook("tickStart", anyRunningJvm);
			hasJvmPlayerTickHooks = jsJvmHasHook("playerTick", anyRunningJvm);
			hasJvmTickEndHooks = jsJvmHasHook("tickEnd", anyRunningJvm);
			hasJvmScreenHooks = jsJvmHasHook("screenInit", anyRunningJvm);
			hasJvmWorldJoinHooks = jsJvmHasHook("worldJoin", anyRunningJvm);
			hasJvmWorldLeaveHooks = jsJvmHasHook("worldLeave", anyRunningJvm);
		}
		hasTickHooks = hasJvmTickStartHooks || hasJvmPlayerTickHooks || hasJvmTickEndHooks;
		jsSetLiveStateAutoSync(hasActiveJsMods || hasActiveJvmMods);
	}

	/**
	 * Per-frame hook (call once per frame from {@code WebMain.pumpFrame()}).
	 *
	 * <p>Zero WASM&lt;-&gt;JS overhead in vanilla when no JS or JVM mods are active (only polls
	 * {@code jsBuilderRev()} every 15 frames while {@link ModsScreen} is closed). When active,
	 * pushes live state once per frame via flat WASM primitives (zero JSON/object allocations),
	 * tracks screen/world transitions, fires JS {@code frame} listeners, renders JVM HUD overlays
	 * only when {@code hasHudHooks} is true, and pulses 20 Hz ticks only when tick hooks exist.
	 */
	public static void onFrame() {
		if (!installed) {
			return;
		}
		frameCounter++;

		if (pendingReloadAfterSave) {
			if (!WebWorkerLifecycle.active() || System.currentTimeMillis() >= pendingReloadDeadlineMs) {
				pendingReloadAfterSave = false;
				WebSaves.flush();
				jsReloadPage(120);
			}
		}

		if (builderActive || onChanged != null || (frameCounter % 15L == 0L)) {
			int rev = jsBuilderRev();
			if (rev != lastBuilderRev) {
				lastBuilderRev = rev;
				builderActive = jsIsBuilding() || jsIsBuildReadyForReload();
				notifyChanged();
			}
		}

		boolean hasActiveMods = hasActiveJsMods || hasActiveJvmMods;
		if (!hasActiveMods) {
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}

		// Push live state once per frame using flat WASM primitives (zero JSON / zero allocations)
		syncLiveState(mc);

		// 1. Screen change detection (reuses cachedScreenName / cachedScreenTitle from syncLiveState)
		String screenName = cachedScreenName;
		if (!screenName.equals(lastScreenName)) {
			String prev = lastScreenName;
			lastScreenName = screenName;
			if (hasJsScreenListeners) {
				jsEmitScreenChange(screenName, prev, cachedScreenTitle);
			}
			if (hasJvmScreenHooks) {
				jsDispatchJvmScreenInit(screenName, prev, cachedScreenTitle);
				refreshJvmHookFlags();
			}
		}

		// 2. World join / leave detection
		boolean inWorld = mc.level != null;
		if (inWorld != lastInWorld) {
			lastInWorld = inWorld;
			lastLevelGameTime = Long.MIN_VALUE;
			if (inWorld) {
				if (hasJsWorldJoinListeners) {
					jsEmitWorldJoin(cachedDimension);
				}
				if (hasJvmWorldJoinHooks) {
					jsDispatchJvmWorldJoin(cachedDimension);
					refreshJvmHookFlags();
				}
			} else {
				if (hasJsWorldLeaveListeners) {
					jsEmitWorldLeave();
				}
				if (hasJvmWorldLeaveHooks) {
					jsDispatchJvmWorldLeave();
					refreshJvmHookFlags();
				}
			}
		}

		// 3. Per-frame JS callbacks (only when JS frame listeners are registered)
		if (hasFrameHooks) {
			long nowNanos = System.nanoTime();
			double dtMs = lastFrameNanos == 0L ? 16.67 : (nowNanos - lastFrameNanos) / 1_000_000.0;
			lastFrameNanos = nowNanos;
			jsEmitFrame((double) frameCounter, (double) System.currentTimeMillis(), dtMs, mc.getFps(), screenName, inWorld);
		}

		// 4. Per-frame JVM HUD render + pixel-aligned overlay canvas (only when HUD hooks exist)
		if (hasHudHooks) {
			Window win = mc.getWindow();
			int guiWidth = win != null ? win.getGuiScaledWidth() : 0;
			int guiHeight = win != null ? win.getGuiScaledHeight() : 0;
			int guiScale = win != null ? win.getGuiScale() : 1;
			float partialTick = 1.0F;
			if (mc.getDeltaTracker() != null) {
				float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
				if (Float.isFinite(pt)) {
					partialTick = pt;
				}
			}
			jsEmitJvmHudRender(guiWidth, guiHeight, guiScale, partialTick, lastScreen != null, inWorld, mc.isPaused());
		}

		// 5. Fallback 20 Hz tick dispatch when WebMods.onClientTick() is not hooked directly
		if (!explicitTickHookSeen && (hasJsTickListeners || hasTickHooks)) {
			boolean shouldTick = false;
			long nowNanos = 0L;
			if (mc.level != null) {
				long gt = mc.level.getGameTime();
				if (gt != lastLevelGameTime) {
					lastLevelGameTime = gt;
					shouldTick = true;
				}
			} else {
				nowNanos = System.nanoTime();
				if (lastTickPulseNanos == 0L || nowNanos - lastTickPulseNanos >= TICK_INTERVAL_NANOS) {
					shouldTick = true;
				}
			}
			if (shouldTick) {
				if (nowNanos != 0L) {
					lastTickPulseNanos = nowNanos;
				}
				tickCounter++;
				if (hasJsTickListeners) {
					jsEmitTick((double) tickCounter);
				}
				if (hasTickHooks) {
					jsEmitJvmTick((double) tickCounter, mc.player != null,
						hasJvmTickStartHooks, hasJvmPlayerTickHooks, hasJvmTickEndHooks);
				}
			}
		}
	}

	/** Optional direct 20 Hz client tick hook (e.g. from {@code Minecraft.tick()}). */
	public static void onClientTick() {
		if (!installed) {
			return;
		}
		explicitTickHookSeen = true;
		tickCounter++;
		if (!hasJsTickListeners && !hasTickHooks) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) {
			syncLiveState(mc);
		}
		if (hasJsTickListeners) {
			jsEmitTick((double) tickCounter);
		}
		if (hasTickHooks) {
			jsEmitJvmTick((double) tickCounter, mc != null && mc.player != null,
				hasJvmTickStartHooks, hasJvmPlayerTickHooks, hasJvmTickEndHooks);
		}
	}

	// ---- Storage, Inspection & Ingestion ----

	/** Quick check for {@code WebPacks} drag-and-drop routing. */
	public static boolean looksLikeMod(final String filename, final byte[] bytes) {
		if (ModJarInspector.looksLikeMod(filename, bytes)) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.gui != null && mc.gui.screen() instanceof ModsScreen) {
			ModJarInspector.ModInfo info = ModJarInspector.inspect(filename, bytes);
			return info.kind == ModJarInspector.ModKind.DATA_ASSET_MOD;
		}
		return false;
	}

	/** Open the native file picker for {@code .js}, {@code .mcmod.zip}, and {@code .jar} mods. */
	public static void chooseMod(final Runnable afterChange) {
		onChanged = afterChange;
		clickPicker();
	}

	/** Subscribe a live UI listener (such as an open {@link ModsScreen}) to mod/build updates. */
	public static void setOnChanged(final Runnable listener) {
		onChanged = listener;
	}

	/** Unsubscribe a live UI listener if it is still the registered callback. */
	public static void clearOnChanged(final Runnable listener) {
		if (onChanged == listener) {
			onChanged = null;
		}
	}

	/** Returns true when at least one Fabric bytecode JAR is stored in {@code /config/mods}. */
	public static boolean hasFabricMods() {
		if (!scanned) {
			loadPersistedState();
			scanStorage();
		}
		for (InstalledMod m : mods.values()) {
			if (m.isFabricBakeable()) {
				return true;
			}
		}
		return false;
	}

	/** Returns all stored mods sorted alphabetically by display name. */
	public static List<InstalledMod> listMods() {
		if (!scanned) {
			loadPersistedState();
			scanStorage();
		}
		List<InstalledMod> list = new ArrayList<>(mods.values());
		list.sort((a, b) -> {
			int cmp = a.info.name.compareToIgnoreCase(b.info.name);
			return cmp != 0 ? cmp : a.file.compareToIgnoreCase(b.file);
		});
		return list;
	}

	public static String status() {
		return status;
	}

	/**
	 * Begin a reference-counted multi-mod batch so resource-pack reloads, IndexedDB flushes,
	 * and UI change notifications are deferred until the outermost {@link #endBatch()} finishes.
	 */
	public static void beginBatch() {
		batchDepth++;
	}

	/**
	 * End a multi-mod batch started by {@link #beginBatch()}. When the outermost batch exits,
	 * applies all deferred mod asset overlay pack changes in a single {@code reloadResourcePacks()}
	 * (or zero reloads if no asset overlays changed), flushes saves once, and notifies UI once.
	 */
	public static void endBatch() {
		if (batchDepth <= 0) {
			batchDepth = 0;
			return;
		}
		batchDepth--;
		if (batchDepth > 0) {
			return;
		}
		if (!batchAddedPacks.isEmpty() || !batchRemovedPacks.isEmpty()) {
			List<String> added = new ArrayList<>(batchAddedPacks);
			List<String> removed = new ArrayList<>(batchRemovedPacks);
			batchAddedPacks.clear();
			batchRemovedPacks.clear();
			applyBatchResourcePackChanges(added, removed);
		}
		if (batchSavesDirty) {
			batchSavesDirty = false;
			WebSaves.flush();
		}
		if (batchNotifyDirty) {
			batchNotifyDirty = false;
			notifyChanged();
		}
	}

	/** Accept a picked or dropped mod file from JS. Returns null on success or an error sentence. */
	public static String accept(final String filename, final Int8Array data) {
		return accept(filename, data.copyToJavaArray());
	}

	/** Inspect, persist, and activate (when browser-runnable) an uploaded mod file. */
	public static String accept(final String filename, final byte[] bytes) {
		if (!scanned) {
			loadPersistedState();
			scanStorage();
		}
		ModJarInspector.ModInfo info = ModJarInspector.inspect(filename, bytes);
		if (info.kind == ModJarInspector.ModKind.INVALID) {
			status = "Could not add " + filename + ": " + info.compatibilitySummary;
			toast("Could not add " + filename, info.compatibilitySummary);
			return info.compatibilitySummary;
		}

		String storedName = ModJarInspector.sanitizeFilename(filename);
		// Unload prior instance if overwriting an existing mod file.
		InstalledMod existing = mods.get(storedName);
		if (existing != null) {
			unloadModRuntime(existing, false);
		}

		try {
			WebSaves.mkdirs(DIR);
			WebSaves.writeFile(DIR + "/" + storedName, bytes);
		} catch (Throwable e) {
			status = "Could not store " + storedName + " (out of browser space?)";
			toast("Could not add " + filename, "The browser refused to store it (out of space?)");
			return "store failed";
		}

		boolean enableNow = info.canRunInBrowser() || info.kind == ModJarInspector.ModKind.FABRIC_NEEDS_BAKE;
		if (enableNow) {
			disabledFiles.remove(storedName);
		} else {
			disabledFiles.add(storedName);
		}
		savePersistedState();

		InstalledMod mod = new InstalledMod(storedName, info, bytes.length, enableNow);
		mods.put(storedName, mod);

		if (info.isJavaScript()) {
			runJsMod(mod, bytes);
			if (mod.runtimeError.isEmpty()) {
				status = "Loaded JS mod: " + info.displayTitle();
				toast("Added JS mod", info.displayTitle());
			} else {
				status = "JS error in " + storedName + ": " + mod.runtimeError;
				toast("JS mod error: " + info.name, truncate(mod.runtimeError, 80));
			}
		} else if (info.kind == ModJarInspector.ModKind.DATA_ASSET_MOD) {
			String extracted = extractModPacksInternal(mod, bytes, true);
			status = "Loaded " + info.displayTitle() + " (" + extracted + ")";
			toast("Added data/asset mod", info.name + ": " + extracted);
		} else if (info.kind == ModJarInspector.ModKind.FABRIC_NEEDS_BAKE) {
			if (info.canExtractPack()) {
				extractModPacksInternal(mod, bytes, true);
			}
			runJavaMod(mod, bytes);
			if (mod.jvmTranslated) {
				if (!mod.jvmHasUnsupportedMixins) {
					status = "Loaded Java mod: " + info.name + " (offline translator: " + mod.jvmSummary + ")";
					toast("Loaded Java mod", info.name + " (offline translator: " + truncate(mod.jvmSummary, 72) + ")");
				} else {
					status = "Loaded Java mod (partial): " + info.name + " (" + mod.jvmSummary + ")";
					if (isBuilderConnected()) {
						toast("Loaded Java mod (partial)", info.name + " running offline; use Full Bake in Mods for deep mixins.");
					} else {
						toast("Loaded Java mod (partial)", info.name + " (" + truncate(mod.jvmSummary, 72) + ")");
					}
				}
			} else if (isModInBootedProfile(storedName, info.id)) {
				status = "Active in WASM profile: " + info.displayTitle();
				toast("Mod active", info.displayTitle() + " is active in running WASM profile.");
			} else if (isBuilderConnected()) {
				status = "Offline translator skipped (" + truncate(mod.runtimeError, 56) + ") - baking via local builder...";
				toast("Installing Fabric mod", info.displayTitle() + " - baking with local builder...");
				WebSaves.flush();
				builderActive = true;
				jsImportFabricJar(storedName, info.displayTitle(), Int8Array.copyFromJavaArray(bytes));
			} else {
				status = "Java mod error in " + info.name + ": " + mod.runtimeError;
				toast("Java mod error: " + info.name, truncate(mod.runtimeError, 80));
			}
		} else if (info.kind == ModJarInspector.ModKind.FORGE_UNSUPPORTED) {
			status = info.name + ": Forge/NeoForge bytecode is unsupported in browser";
			toast("Forge mod unsupported", "Stored for inspection; Forge bytecode cannot run in TeaVM WASM.");
		} else {
			status = info.name + ": " + info.compatibilitySummary;
			toast("Stored mod archive", info.compatibilitySummary);
		}

		if (batchDepth > 0) {
			batchSavesDirty = true;
			batchNotifyDirty = true;
		} else {
			WebSaves.flush();
			notifyChanged();
		}
		System.out.println("[webmods] added " + storedName + " kind=" + info.kind + " (" + bytes.length + " bytes)");
		return null;
	}

	/** Enable or disable an installed browser-runnable or Fabric auto-build mod. Returns true if state changed. */
	public static boolean setEnabled(final String filename, final boolean enabled) {
		InstalledMod mod = mods.get(filename);
		if (mod == null) {
			return false;
		}
		if (!mod.isToggleable()) {
			status = mod.info.name + " " + mod.info.kind.badge() + ": " + mod.info.compatibilitySummary;
			return false;
		}
		if (mod.enabled == enabled) {
			return false;
		}
		mod.enabled = enabled;
		if (mod.canRunViaTranslator()) {
			if (enabled) {
				disabledFiles.remove(filename);
				byte[] bytes = WebSaves.readFile(DIR + "/" + filename);
				if (bytes != null) {
					if (mod.info.canExtractPack()) {
						extractModPacksInternal(mod, bytes, true);
					}
					runJavaMod(mod, bytes);
					if (mod.jvmTranslated) {
						if (!mod.jvmHasUnsupportedMixins) {
							status = "Loaded Java mod: " + mod.info.name + " (offline translator: " + mod.jvmSummary + ")";
							toast("Loaded Java mod", mod.info.name + " (offline translator: " + truncate(mod.jvmSummary, 72) + ")");
						} else {
							status = "Loaded Java mod (partial): " + mod.info.name + " (" + mod.jvmSummary + ")";
							toast("Loaded Java mod (partial)", mod.info.name + " (" + truncate(mod.jvmSummary, 72) + ")");
						}
					} else if (isModInBootedProfile(filename, mod.info.id)) {
						status = "Enabled " + mod.info.displayTitle() + " (active in WASM profile)";
					} else if (isBuilderConnected()) {
						status = "Enabling " + mod.info.displayTitle() + " - baking with local builder...";
						toast("Enabling Fabric mod", mod.info.displayTitle() + " - baking with local builder...");
						builderActive = true;
						jsImportFabricJar(filename, mod.info.displayTitle(), Int8Array.copyFromJavaArray(bytes));
					} else {
						status = "Java mod error in " + mod.info.name + ": " + mod.runtimeError;
						toast("Java mod error: " + mod.info.name, truncate(mod.runtimeError, 80));
					}
				} else {
					status = "Could not read " + filename + " from browser storage.";
				}
			} else {
				disabledFiles.add(filename);
				unloadModRuntime(mod, true);
				if (isBuilderConnected() || isModInBootedProfile(filename, mod.info.id)) {
					status = "Disabled " + mod.info.name + " - updating engine...";
					builderActive = true;
					jsRemoveFabricJar(filename);
				} else {
					status = "Disabled " + mod.info.name;
				}
			}
			savePersistedState();
			WebSaves.flush();
			notifyChanged();
			return true;
		}
		if (enabled) {
			disabledFiles.remove(filename);
			byte[] bytes = WebSaves.readFile(DIR + "/" + filename);
			if (bytes != null) {
				if (mod.info.isJavaScript()) {
					runJsMod(mod, bytes);
					status = mod.runtimeError.isEmpty()
						? "Enabled JS mod: " + mod.info.displayTitle()
						: "JS error in " + mod.file + ": " + mod.runtimeError;
				} else if (mod.info.kind == ModJarInspector.ModKind.DATA_ASSET_MOD) {
					String summary = extractModPacksInternal(mod, bytes, true);
					status = "Enabled " + mod.info.name + " (" + summary + ")";
				}
			}
		} else {
			disabledFiles.add(filename);
			unloadModRuntime(mod, true);
			status = "Disabled " + mod.info.name;
		}
		savePersistedState();
		WebSaves.flush();
		return true;
	}

	/** Delete a stored mod and clean up live JS listeners, extracted packs, and staged Fabric builds. */
	public static void remove(final String filename) {
		InstalledMod mod = mods.remove(filename);
		boolean wasFabric = mod != null && mod.isFabricBakeable();
		String modId = mod != null ? mod.info.id : "";
		boolean syncBuilder = wasFabric && (isBuilderConnected() || isModInBootedProfile(filename, modId));
		if (mod != null) {
			unloadModRuntime(mod, true);
		}
		ModrinthClient.forgetInstalled(modId, filename);
		disabledFiles.remove(filename);
		new File(DIR + "/" + filename).delete();
		savePersistedState();
		WebSaves.flush();
		if (syncBuilder) {
			status = "Removing " + filename + " - updating engine...";
			builderActive = true;
			jsRemoveFabricJar(filename);
		} else {
			status = "Removed " + filename;
		}
		System.out.println("[webmods] removed " + filename);
		notifyChanged();
	}

	/**
	 * Extract {@code assets/} into {@code /resourcepacks/} (activating it immediately) and/or
	 * {@code data/} into {@code /config/mods/datapacks/} for any stored mod containing pack files.
	 */
	public static String extractPack(final String filename) {
		InstalledMod mod = mods.get(filename);
		if (mod == null) {
			status = "Mod not found: " + filename;
			return status;
		}
		if (!mod.canExtractPack()) {
			status = mod.info.name + " has no extractable assets/ or data/ files.";
			return status;
		}
		byte[] bytes = WebSaves.readFile(DIR + "/" + filename);
		if (bytes == null) {
			status = "Could not read " + filename + " from browser storage.";
			return status;
		}
		String result = extractModPacksInternal(mod, bytes, true);
		WebSaves.flush();
		status = mod.info.name + ": " + result;
		toast("Extracted mod pack", mod.info.name + ": " + result);
		return result;
	}

	/**
	 * Append extracted mod datapacks from {@code /config/mods/datapacks/} into a newly created
	 * world's {@link WebLifecycleMsg.OpenSpec}. Call from {@code WebWorkerLifecycle.collectDataPacks}.
	 */
	public static void appendDataPacksTo(final WebLifecycleMsg.OpenSpec spec) {
		if (spec == null) {
			return;
		}
		File dir = new File(DATAPACKS_DIR);
		String[] names = dir.list();
		if (names == null || names.length == 0) {
			return;
		}
		int added = 0;
		long totalBytes = 0;
		for (String name : names) {
			if (!isSafePackName(name)) {
				continue;
			}
			if (spec.packNames.contains(name)) {
				continue;
			}
			byte[] data = WebSaves.readFile(DATAPACKS_DIR + "/" + name);
			if (data != null && data.length > 0) {
				spec.packNames.add(name);
				spec.packData.add(data);
				String packId = "file/" + name;
				if (!spec.enabledPacks.contains(packId) && !spec.disabledPacks.contains(packId)) {
					spec.enabledPacks.add(packId);
				}
				added++;
				totalBytes += data.length;
			}
		}
		if (added > 0) {
			System.out.println("[webmods] attached " + added + " mod data pack(s) to new world (" + totalBytes + " bytes)");
		}
	}

	private static boolean isSafePackName(final String name) {
		return name != null && !name.isEmpty() && name.length() <= 128
			&& !name.contains("/") && !name.contains("\\")
			&& !name.contains(":") && !name.contains("..")
			&& name.toLowerCase(Locale.ROOT).endsWith(".zip");
	}

	// ---- Baked WASM Mod Profile Selector ----

	/** Selected baked WASM profile from {@code localStorage["minecraft.modprofile.selected"]}, or "". */
	public static String selectedProfile() {
		String val = lsGet(LS_MOD_PROFILE);
		if (val == null) {
			return "";
		}
		String trimmed = val.trim();
		return "vanilla".equalsIgnoreCase(trimmed) ? "" : trimmed;
	}

	/** Profile that the current page booted with (set by index.html as {@code window.__eaglerBootedModProfile}). */
	public static String bootedProfile() {
		return bootedProfile;
	}

	/** True when the selected WASM profile differs from the currently running WASM binary. */
	public static boolean profileNeedsReload() {
		return !selectedProfile().equals(bootedProfile);
	}

	/** Persist the baked WASM profile choice; reload the page to boot that WASM binary. */
	public static void selectProfile(final String rawProfile) {
		String clean = sanitizeProfileName(rawProfile);
		lsSet(LS_MOD_PROFILE, clean);
		if (clean.isEmpty()) {
			status = profileNeedsReload()
				? "Selected vanilla WASM profile - reload page to apply"
				: "Using default vanilla WASM profile";
		} else {
			status = profileNeedsReload()
				? "Selected WASM profile '" + clean + "' - reload page to boot"
				: "Running baked WASM profile '" + clean + "'";
		}
	}

	/** Available baked WASM profiles advertised by {@code window.__eaglerModProfiles} plus vanilla. */
	public static List<String> availableProfiles() {
		List<String> out = new ArrayList<>();
		out.add("vanilla");
		String raw = readAvailableProfilesCsv();
		if (raw != null && !raw.isEmpty()) {
			for (String p : raw.split(",")) {
				String clean = sanitizeProfileName(p);
				if (!clean.isEmpty() && !out.contains(clean)) {
					out.add(clean);
				}
			}
		}
		String sel = selectedProfile();
		if (!sel.isEmpty() && !out.contains(sel)) {
			out.add(sel);
		}
		return out;
	}

	private static String sanitizeProfileName(final String raw) {
		if (raw == null) {
			return "";
		}
		String trimmed = raw.trim();
		if (trimmed.isEmpty() || "vanilla".equalsIgnoreCase(trimmed)) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < trimmed.length() && sb.length() < 48; i++) {
			char c = trimmed.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	// ---- Zero-CLI Auto-Builder Status & Control ----

	/** Human-readable live status of the local Fabric WASM auto-builder (or empty when idle). */
	public static String buildStatus() {
		String s = jsGetBuildStatus();
		return s == null ? "" : s;
	}

	/** True while a Fabric mod upload or background WASM build job is actively running. */
	public static boolean isBuilding() {
		return jsIsBuilding();
	}

	/** True when a newly built (or vanilla-reset) WASM profile is ready and awaiting page reload. */
	public static boolean buildReadyForReload() {
		return jsIsBuildReadyForReload();
	}

	/** True when the local auto-builder HTTP endpoint responded available. */
	public static boolean isBuilderConnected() {
		return jsIsBuilderConnected();
	}

	/** Re-probe the local auto-builder asynchronously if not currently connected. */
	public static void probeBuilderIfNeeded() {
		if (!isBuilderConnected()) {
			jsProbeBuilderStatus();
		}
	}

	/** Per-file auto-builder state: building, ready, blocked, conflict, failed, offline, or "". */
	public static String modBuildState(final String filename) {
		if (filename == null || filename.isEmpty()) {
			return "";
		}
		String s = jsGetModBuildState(filename);
		return s == null ? "" : s;
	}

	/** Exact incompatibility / conflict / build error detail for a specific mod file, if any. */
	public static String modBuildError(final String filename) {
		if (filename == null || filename.isEmpty()) {
			return "";
		}
		String s = jsGetModBuildError(filename);
		return s == null ? "" : s;
	}

	/** True when a Fabric JAR is part of the currently booted baked WASM profile. */
	public static boolean isModInBootedProfile(final String filename, final String modId) {
		if (bootedProfile.isEmpty() || filename == null || filename.isEmpty()) {
			return false;
		}
		if (jsIsModInProfile(bootedProfile, filename, modId == null ? "" : modId)) {
			return true;
		}
		if ("dropped".equals(bootedProfile) && !profileNeedsReload()) {
			InstalledMod m = mods.get(filename);
			if (m != null && m.enabled && m.isFabricBakeable()) {
				String st = modBuildState(filename);
				return st.isEmpty() || "baked".equals(st);
			}
		}
		return false;
	}

	/**
	 * Safely reload the page to activate the selected WASM profile. When a singleplayer worker
	 * world is open, stops & flushes the world to IndexedDB before reloading so progress is saved.
	 */
	public static void reloadNow() {
		WebSaves.flush();
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.level != null && WebWorkerLifecycle.active()) {
			pendingReloadAfterSave = true;
			pendingReloadDeadlineMs = System.currentTimeMillis() + 6000L;
			status = "Saving world before reloading...";
			jsSetSavingReloadState();
			mc.disconnectWithProgressScreen();
			return;
		}
		jsReloadPage(150);
	}

	/** Trigger a rebuild of the default {@code dropped} Fabric profile (re-syncing offline JARs first). */
	public static void triggerRebuild() {
		triggerRebuild("dropped");
	}

	/** Trigger a rebuild of a named Fabric profile via {@code POST /api/mods/build}. */
	public static void triggerRebuild(final String rawProfile) {
		if (!scanned) {
			loadPersistedState();
			scanStorage();
		}
		String clean = sanitizeProfileName(rawProfile);
		if (clean.isEmpty()) {
			clean = "dropped";
		}
		if ("dropped".equals(clean)) {
			int synced = 0;
			boolean connected = isBuilderConnected();
			for (InstalledMod mod : mods.values()) {
				if (!mod.enabled || !mod.isFabricBakeable()) {
					continue;
				}
				String st = modBuildState(mod.file);
				if (!connected || "offline".equals(st) || st.isEmpty()) {
					byte[] bytes = WebSaves.readFile(DIR + "/" + mod.file);
					if (bytes != null && bytes.length > 0) {
						builderActive = true;
						jsImportFabricJar(mod.file, mod.info.displayTitle(), Int8Array.copyFromJavaArray(bytes));
						synced++;
					}
				}
			}
			if (synced > 0) {
				status = "Syncing " + synced + " Fabric mod(s) & starting build...";
				notifyChanged();
				return;
			}
		}
		status = "Requesting Fabric mod build (" + clean + ")...";
		builderActive = true;
		jsTriggerBuild(clean);
		notifyChanged();
	}

	private static boolean isSafeAutoReload() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.level == null && mc.gui != null && mc.gui.screen() instanceof TitleScreen;
	}

	private static boolean isInWorld() {
		Minecraft mc = Minecraft.getInstance();
		return mc != null && mc.level != null;
	}

	private static void flushSaves() {
		WebSaves.flush();
	}

	private static double getGamma() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.options == null) {
			return 0.5;
		}
		Double v = mc.options.gamma().get();
		return v == null ? 0.5 : v.doubleValue();
	}

	private static double setGamma(final double val) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.options == null || Double.isNaN(val)) {
			return getGamma();
		}
		double clamped = Math.max(0.0, Math.min(1.0, val));
		mc.options.gamma().set(clamped);
		return clamped;
	}

	private static int getFov() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.options == null) {
			return 70;
		}
		Integer v = mc.options.fov().get();
		return v == null ? 70 : v.intValue();
	}

	private static int setFov(final int val) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.options == null) {
			return getFov();
		}
		int clamped = Math.max(30, Math.min(110, val));
		mc.options.fov().set(clamped);
		return clamped;
	}

	private static boolean setSprinting(final boolean sprinting) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return false;
		}
		mc.player.setSprinting(sprinting);
		return mc.player.isSprinting();
	}

	private static void setRotation(final double yaw, final double pitch) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return;
		}
		if (Double.isFinite(yaw)) {
			mc.player.setYRot((float) yaw);
		}
		if (Double.isFinite(pitch)) {
			float clampedPitch = (float) Math.max(-90.0, Math.min(90.0, pitch));
			mc.player.setXRot(clampedPitch);
		}
	}

	// ---- Internal storage & extraction ----

	private static void loadPersistedState() {
		disabledFiles.clear();
		byte[] raw = WebSaves.readFile(STATE_FILE);
		if (raw == null || raw.length == 0) {
			return;
		}
		String text = new String(raw, StandardCharsets.UTF_8);
		for (String line : text.split("\\r?\\n")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("disabled=")) {
				String file = trimmed.substring("disabled=".length()).trim();
				if (!file.isEmpty()) {
					disabledFiles.add(file);
				}
			}
		}
	}

	private static void savePersistedState() {
		try {
			WebSaves.mkdirs(DIR);
			StringBuilder sb = new StringBuilder();
			List<String> sorted = new ArrayList<>(disabledFiles);
			Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
			for (String f : sorted) {
				sb.append("disabled=").append(f).append('\n');
			}
			WebSaves.writeFile(STATE_FILE, sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Throwable e) {
			System.out.println("[webmods] could not save mod state: " + e);
		}
	}

	private static void scanStorage() {
		scanned = true;
		mods.clear();
		WebSaves.mkdirs(DIR);
		File dir = new File(DIR);
		String[] names = dir.list();
		if (names == null) {
			return;
		}
		for (String name : names) {
			if (name.equals("state.txt") || name.equals("datapacks")) {
				continue;
			}
			if (!ModJarInspector.looksLikeModFilename(name) && !name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
				continue;
			}
			byte[] bytes = WebSaves.readFile(DIR + "/" + name);
			if (bytes == null) {
				continue;
			}
			ModJarInspector.ModInfo info = ModJarInspector.inspect(name, bytes);
			boolean enabled = (info.canRunInBrowser() || info.kind == ModJarInspector.ModKind.FABRIC_NEEDS_BAKE)
				&& !disabledFiles.contains(name);
			InstalledMod mod = new InstalledMod(name, info, bytes.length, enabled);
			String rpName = resourcePackFileName(name);
			String legacyRpName = legacyResourcePackFileName(name);
			File rpFile = new File(RESOURCEPACKS_DIR + "/" + rpName);
			File legacyRpFile = new File(RESOURCEPACKS_DIR + "/" + legacyRpName);
			if (rpFile.exists()) {
				mod.extractedResourcePack = rpName;
				if (legacyRpFile.exists()) {
					legacyRpFile.delete();
					removeResourcePackFromRepo("file/" + legacyRpName);
				}
			} else if (legacyRpFile.exists()) {
				// Migrate legacy visible mod_*_assets.zip to hidden __mcweb_mod_*_assets.zip
				byte[] legacyBytes = WebSaves.readFile(RESOURCEPACKS_DIR + "/" + legacyRpName);
				if (legacyBytes != null && legacyBytes.length > 0) {
					try {
						WebSaves.mkdirs(RESOURCEPACKS_DIR);
						WebSaves.writeFile(RESOURCEPACKS_DIR + "/" + rpName, legacyBytes);
						legacyRpFile.delete();
						mod.extractedResourcePack = rpName;
						removeResourcePackFromRepo("file/" + legacyRpName);
					} catch (Throwable ignored) {
						mod.extractedResourcePack = legacyRpName;
					}
				} else {
					mod.extractedResourcePack = legacyRpName;
				}
			}
			String dpName = dataPackFileName(name);
			if (new File(DATAPACKS_DIR + "/" + dpName).exists()) {
				mod.extractedDataPack = dpName;
			}
			mod.jsRunning = runningJsFiles.contains(name);
			mod.jvmTranslated = runningJvmFiles.contains(name);
			if (mod.jvmTranslated) {
				mod.jvmClassesLoaded = jsJvmReportClassesLoaded(name);
				mod.jvmEntrypointsRun = jsJvmReportEntrypointsRun(name);
				mod.jvmMixinsAttached = jsJvmReportMixinsAttached(name);
				mod.jvmMixinsUnsupported = jsJvmReportMixinsUnsupported(name);
				mod.jvmHasUnsupportedMixins = mod.jvmMixinsUnsupported > 0;
				mod.jvmSummary = jsJvmReportSummary(name);
			}
			mods.put(name, mod);
		}
	}

	private static void activateEnabledMods(final boolean reloadResourcesIfChanged) {
		boolean resourcePacksChanged = false;
		for (InstalledMod mod : mods.values()) {
			if (!mod.enabled) {
				continue;
			}
			byte[] bytes = WebSaves.readFile(DIR + "/" + mod.file);
			if (bytes == null) {
				continue;
			}
			if (mod.info.isJavaScript()) {
				runJsMod(mod, bytes);
			} else if (mod.info.kind == ModJarInspector.ModKind.DATA_ASSET_MOD) {
				boolean hadRp = mod.extractedResourcePack != null;
				boolean hadDp = mod.extractedDataPack != null;
				if ((mod.info.hasAssets() && !hadRp) || (mod.info.hasData() && !hadDp)) {
					extractModPacksInternal(mod, bytes, false);
					if (mod.extractedResourcePack != null) {
						resourcePacksChanged = true;
					}
				}
			} else if (mod.canRunViaTranslator()) {
				boolean hadRp = mod.extractedResourcePack != null;
				boolean hadDp = mod.extractedDataPack != null;
				if ((mod.info.hasAssets() && !hadRp) || (mod.info.hasData() && !hadDp)) {
					extractModPacksInternal(mod, bytes, false);
					if (mod.extractedResourcePack != null) {
						resourcePacksChanged = true;
					}
				}
				runJavaMod(mod, bytes);
			}
		}
		if (resourcePacksChanged && reloadResourcesIfChanged) {
			enableExtractedResourcePacksInRepo();
		}
	}

	private static void runJavaMod(final InstalledMod mod, final byte[] bytes) {
		mod.jvmTranslated = false;
		mod.jvmSummary = "";
		mod.jvmHasUnsupportedMixins = false;
		mod.jvmClassesLoaded = 0;
		mod.jvmEntrypointsRun = 0;
		mod.jvmMixinsAttached = 0;
		mod.jvmMixinsUnsupported = 0;
		mod.runtimeError = "";

		if (isModInBootedProfile(mod.file, mod.info.id)) {
			runningJvmFiles.remove(mod.file);
			refreshJvmHookFlags();
			return;
		}

		jsUnloadJavaMod(mod.file);
		boolean ok = jsRunJavaJar(mod.file, mod.info.id, mod.info.name, mod.info.version, Int8Array.copyFromJavaArray(bytes));
		mod.jvmClassesLoaded = jsJvmReportClassesLoaded(mod.file);
		mod.jvmEntrypointsRun = jsJvmReportEntrypointsRun(mod.file);
		mod.jvmMixinsAttached = jsJvmReportMixinsAttached(mod.file);
		mod.jvmMixinsUnsupported = jsJvmReportMixinsUnsupported(mod.file);
		mod.jvmHasUnsupportedMixins = mod.jvmMixinsUnsupported > 0;
		String summary = jsJvmReportSummary(mod.file);
		String err = jsJvmReportError(mod.file);

		if (ok) {
			mod.jvmTranslated = true;
			mod.jvmSummary = (summary == null || summary.isEmpty()) ? formatJvmFallbackSummary(mod) : summary;
			mod.runtimeError = "";
			runningJvmFiles.add(mod.file);
			System.out.println("[webmods] Java mod translated offline (" + mod.file + "): " + mod.jvmSummary);
		} else {
			mod.jvmTranslated = false;
			mod.jvmSummary = summary == null ? "" : summary;
			mod.runtimeError = (err == null || err.isEmpty()) ? "Offline Java translator could not load this mod" : err;
			runningJvmFiles.remove(mod.file);
			System.out.println("[webmods] Java mod translation error (" + mod.file + "): " + mod.runtimeError);
		}
		refreshJvmHookFlags();
	}

	private static String formatJvmFallbackSummary(final InstalledMod mod) {
		StringBuilder sb = new StringBuilder();
		sb.append(mod.jvmClassesLoaded).append(mod.jvmClassesLoaded == 1 ? " class" : " classes");
		if (mod.jvmEntrypointsRun > 0) {
			sb.append(", ").append(mod.jvmEntrypointsRun).append(mod.jvmEntrypointsRun == 1 ? " entrypoint" : " entrypoints");
		}
		if (mod.jvmMixinsAttached > 0) {
			sb.append(", ").append(mod.jvmMixinsAttached).append(mod.jvmMixinsAttached == 1 ? " mixin active" : " mixins active");
		}
		if (mod.jvmMixinsUnsupported > 0) {
			sb.append(", ").append(mod.jvmMixinsUnsupported).append(" deep mixin").append(mod.jvmMixinsUnsupported == 1 ? "" : "s").append(" skipped");
		}
		return sb.toString();
	}

	private static void runJsMod(final InstalledMod mod, final byte[] bytes) {
		String source = ModJarInspector.extractJsSource(mod.file, bytes, mod.info);
		if (source == null || source.trim().isEmpty()) {
			mod.runtimeError = "Could not extract JS source";
			mod.jsRunning = false;
			runningJsFiles.remove(mod.file);
			refreshJvmHookFlags();
			return;
		}
		jsUnloadMod(mod.file);
		String err = jsEvalMod(mod.file, mod.info.id, mod.info.name, mod.info.version, source);
		if (err == null || err.isEmpty()) {
			mod.runtimeError = "";
			mod.jsRunning = true;
			runningJsFiles.add(mod.file);
		} else {
			mod.runtimeError = err;
			mod.jsRunning = false;
			runningJsFiles.remove(mod.file);
			System.out.println("[webmods] JS mod error (" + mod.file + "): " + err);
		}
		refreshJvmHookFlags();
	}

	private static void unloadModRuntime(final InstalledMod mod, final boolean reloadResourcePacks) {
		boolean runtimeChanged = false;
		if (mod.info.isJavaScript()) {
			jsUnloadMod(mod.file);
			mod.jsRunning = false;
			runningJsFiles.remove(mod.file);
			runtimeChanged = true;
		}
		if (mod.canRunViaTranslator()) {
			jsUnloadJavaMod(mod.file);
			mod.jvmTranslated = false;
			mod.jvmSummary = "";
			mod.jvmHasUnsupportedMixins = false;
			mod.jvmClassesLoaded = 0;
			mod.jvmEntrypointsRun = 0;
			mod.jvmMixinsAttached = 0;
			mod.jvmMixinsUnsupported = 0;
			mod.runtimeError = "";
			runningJvmFiles.remove(mod.file);
			runtimeChanged = true;
		}
		if (runtimeChanged) {
			refreshJvmHookFlags();
		}
		boolean removedRp = false;
		if (mod.extractedResourcePack != null) {
			String rpId = "file/" + mod.extractedResourcePack;
			new File(RESOURCEPACKS_DIR + "/" + mod.extractedResourcePack).delete();
			mod.extractedResourcePack = null;
			removedRp = true;
			if (reloadResourcePacks) {
				removeResourcePackFromRepo(rpId);
			}
		}
		String legacyRpName = legacyResourcePackFileName(mod.file);
		File legacyRpFile = new File(RESOURCEPACKS_DIR + "/" + legacyRpName);
		if (legacyRpFile.exists()) {
			legacyRpFile.delete();
			if (reloadResourcePacks) {
				removeResourcePackFromRepo("file/" + legacyRpName);
			}
		}
		if (mod.extractedDataPack != null) {
			new File(DATAPACKS_DIR + "/" + mod.extractedDataPack).delete();
			mod.extractedDataPack = null;
		}
		if (removedRp && !reloadResourcePacks) {
			// Caller will reload once after batch operation.
		}
	}

	private static String extractModPacksInternal(final InstalledMod mod, final byte[] bytes, final boolean activateNow) {
		int resourceMajor = currentPackMajor(PackType.CLIENT_RESOURCES, FALLBACK_RESOURCE_FORMAT);
		int dataMajor = currentPackMajor(PackType.SERVER_DATA, FALLBACK_DATA_FORMAT);

		boolean extractedAssets = false;
		boolean extractedData = false;

		if (mod.info.hasAssets()) {
			byte[] rpZip = ModJarInspector.buildResourcePackZip(bytes, mod.info, resourceMajor);
			if (rpZip != null) {
				String rpName = resourcePackFileName(mod.file);
				String legacyRpName = legacyResourcePackFileName(mod.file);
				try {
					WebSaves.mkdirs(RESOURCEPACKS_DIR);
					WebSaves.writeFile(RESOURCEPACKS_DIR + "/" + rpName, rpZip);
					mod.extractedResourcePack = rpName;
					extractedAssets = true;
					File legacyFile = new File(RESOURCEPACKS_DIR + "/" + legacyRpName);
					if (legacyFile.exists()) {
						legacyFile.delete();
						removeResourcePackFromRepo("file/" + legacyRpName);
					}
					System.out.println("[webmods] extracted resource pack " + rpName + " (" + rpZip.length + " bytes)");
				} catch (Throwable e) {
					System.out.println("[webmods] failed to write resource pack " + rpName + ": " + e);
				}
			}
		}

		if (mod.info.hasData()) {
			byte[] dpZip = ModJarInspector.buildDataPackZip(bytes, mod.info, dataMajor);
			if (dpZip != null) {
				String dpName = dataPackFileName(mod.file);
				try {
					WebSaves.mkdirs(DATAPACKS_DIR);
					WebSaves.writeFile(DATAPACKS_DIR + "/" + dpName, dpZip);
					mod.extractedDataPack = dpName;
					extractedData = true;
					System.out.println("[webmods] extracted data pack " + dpName + " (" + dpZip.length + " bytes)");
				} catch (Throwable e) {
					System.out.println("[webmods] failed to write data pack " + dpName + ": " + e);
				}
			}
		}

		if (extractedAssets && activateNow) {
			activateResourcePackInRepo("file/" + mod.extractedResourcePack);
		}

		if (extractedAssets && extractedData) {
			return "resource pack active & data pack ready for new worlds";
		}
		if (extractedAssets) {
			return "resource pack extracted & active";
		}
		if (extractedData) {
			return "data pack ready for new worlds";
		}
		return "no pack entries extracted";
	}

	private static void activateResourcePackInRepo(final String packId) {
		if (batchDepth > 0) {
			batchRemovedPacks.remove(packId);
			batchAddedPacks.add(packId);
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}
		PackRepository repo = mc.getResourcePackRepository();
		repo.reload();
		if (!repo.getAvailableIds().contains(packId)) {
			System.out.println("[webmods] extracted resource pack " + packId + " not listed by PackRepository");
			return;
		}
		List<String> selected = new ArrayList<>(repo.getSelectedIds());
		if (!selected.contains(packId)) {
			selected.add(packId);
		}
		repo.setSelected(selected);
		List<String> previous = List.copyOf(mc.options.resourcePacks);
		mc.options.updateResourcePacks(repo);
		if (previous.equals(mc.options.resourcePacks)) {
			mc.reloadResourcePacks();
		}
	}

	private static void applyBatchResourcePackChanges(final List<String> added, final List<String> removed) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}
		PackRepository repo = mc.getResourcePackRepository();
		List<String> selected = new ArrayList<>(repo.getSelectedIds());
		boolean changed = false;
		for (String id : removed) {
			if (selected.remove(id)) {
				changed = true;
			}
		}
		repo.reload();
		boolean anyAddedAvailable = false;
		for (String id : added) {
			if (repo.getAvailableIds().contains(id)) {
				anyAddedAvailable = true;
				if (!selected.contains(id)) {
					selected.add(id);
					changed = true;
				}
			}
		}
		if (!changed && !anyAddedAvailable) {
			return;
		}
		repo.setSelected(selected);
		List<String> previous = List.copyOf(mc.options.resourcePacks);
		mc.options.updateResourcePacks(repo);
		if (anyAddedAvailable && previous.equals(mc.options.resourcePacks)) {
			mc.reloadResourcePacks();
		}
	}

	private static void enableExtractedResourcePacksInRepo() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}
		PackRepository repo = mc.getResourcePackRepository();
		repo.reload();
		List<String> selected = new ArrayList<>(repo.getSelectedIds());
		boolean changed = false;
		for (InstalledMod mod : mods.values()) {
			if (mod.enabled && mod.extractedResourcePack != null) {
				String id = "file/" + mod.extractedResourcePack;
				if (repo.getAvailableIds().contains(id) && !selected.contains(id)) {
					selected.add(id);
					changed = true;
				}
			}
		}
		if (changed) {
			repo.setSelected(selected);
			mc.options.updateResourcePacks(repo);
		}
	}

	private static void removeResourcePackFromRepo(final String packId) {
		if (batchDepth > 0) {
			batchAddedPacks.remove(packId);
			batchRemovedPacks.add(packId);
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}
		PackRepository repo = mc.getResourcePackRepository();
		List<String> selected = new ArrayList<>(repo.getSelectedIds());
		boolean wasSelected = selected.remove(packId);
		repo.reload();
		if (wasSelected) {
			repo.setSelected(selected);
			mc.options.updateResourcePacks(repo);
		}
	}

	private static int currentPackMajor(final PackType type, final int fallback) {
		try {
			PackFormat fmt = SharedConstants.getCurrentVersion().packVersion(type);
			return fmt != null ? fmt.major() : fallback;
		} catch (Throwable e) {
			return fallback;
		}
	}

	private static String resourcePackFileName(final String modFile) {
		return "__mcweb_mod_" + ModJarInspector.stemForPack(modFile) + "_assets.zip";
	}

	private static String legacyResourcePackFileName(final String modFile) {
		return "mod_" + ModJarInspector.stemForPack(modFile) + "_assets.zip";
	}

	private static String dataPackFileName(final String modFile) {
		return "mod_" + ModJarInspector.stemForPack(modFile) + "_data.zip";
	}

	private static void notifyChanged() {
		Runnable r = onChanged;
		if (r != null) {
			r.run();
		}
	}

	private static String truncate(final String s, final int max) {
		if (s == null) {
			return "";
		}
		return s.length() > max ? s.substring(0, max) + "..." : s;
	}

	// ---- In-game actions exposed to window.EaglerMods ----

	public static void toast(final String title, final String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.gui == null) {
			return;
		}
		SystemToast.addOrUpdate(
			mc.gui.toastManager(),
			SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
			Component.literal(title == null ? "Mod" : title),
			Component.literal(message == null ? "" : message));
	}

	public static void chatLocal(final String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.gui == null || mc.gui.hud == null || message == null) {
			return;
		}
		mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
	}

	public static void sendChatOrCommand(final String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || text == null || text.isEmpty()) {
			return;
		}
		ClientPacketListener conn = mc.getConnection();
		if (conn == null) {
			return;
		}
		if (text.startsWith("/")) {
			String cmd = text.substring(1).trim();
			if (!cmd.isEmpty()) {
				conn.sendCommand(cmd);
			}
		} else {
			conn.sendChat(text);
		}
	}

	public static void actionBar(final String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.gui == null || mc.gui.hud == null) {
			return;
		}
		mc.gui.hud.setOverlayMessage(Component.literal(message == null ? "" : message), false);
	}

	private static void reloadResources() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) {
			mc.reloadResourcePacks();
		}
	}

	private static void openModsScreen() {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.gui != null) {
			mc.gui.setScreen(new ModsScreen(mc.gui.screen(), mc.options));
		}
	}

	private static String acceptFromJsText(final String filename, final String source) {
		if (filename == null || source == null) {
			return "missing filename or source";
		}
		String err = accept(filename, source.getBytes(StandardCharsets.UTF_8));
		return err == null ? "" : err;
	}

	private static boolean setEnabledFromJs(final String filename, final boolean enabled) {
		boolean changed = setEnabled(filename, enabled);
		if (changed) {
			notifyChanged();
		}
		return changed;
	}

	private static void removeFromJs(final String filename) {
		remove(filename);
		notifyChanged();
	}

	private static void reloadAllMods() {
		for (InstalledMod mod : mods.values()) {
			if (mod.info.isJavaScript()) {
				jsUnloadMod(mod.file);
				mod.jsRunning = false;
				runningJsFiles.remove(mod.file);
			}
			if (mod.canRunViaTranslator()) {
				jsUnloadJavaMod(mod.file);
				mod.jvmTranslated = false;
				runningJvmFiles.remove(mod.file);
			}
		}
		refreshJvmHookFlags();
		loadPersistedState();
		scanStorage();
		activateEnabledMods(true);
		refreshJvmHookFlags();
		notifyChanged();
	}

	// ---- JSON snapshots for window.EaglerMods ----

	private static double safeDouble(final double v) {
		return Double.isFinite(v) ? v : 0.0;
	}

	private static float safeFloat(final float v) {
		return Float.isFinite(v) ? v : 0.0F;
	}

	private static String itemIdString(final ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "minecraft:air";
		}
		try {
			Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
			return key != null ? key.toString() : "minecraft:air";
		} catch (Throwable e) {
			return "minecraft:air";
		}
	}

	private static void syncLiveStateFromJs() {
		syncLiveState(Minecraft.getInstance());
	}

	private static void syncLiveState(final Minecraft mc) {
		int fps = mc == null ? 0 : mc.getFps();
		ClientLevel lvl = mc == null ? null : mc.level;
		boolean inWorld = lvl != null;
		boolean paused = mc != null && mc.isPaused();
		Screen scr = mc != null && mc.gui != null ? mc.gui.screen() : null;
		Window win = mc == null ? null : mc.getWindow();
		int guiScale = win != null ? win.getGuiScale() : 1;
		int guiW = win != null ? win.getGuiScaledWidth() : 0;
		int guiH = win != null ? win.getGuiScaledHeight() : 0;
		int winW = win != null ? (win.getScreenWidth() > 0 ? win.getScreenWidth() : win.getWidth()) : 0;
		int winH = win != null ? (win.getScreenHeight() > 0 ? win.getScreenHeight() : win.getHeight()) : 0;

		int renderDistance = 8;
		int fov = 70;
		double gamma = 0.5;
		if (mc != null && mc.options != null) {
			Integer rdVal = mc.options.renderDistance().get();
			if (rdVal != null) {
				renderDistance = rdVal.intValue();
			}
			Integer fovVal = mc.options.fov().get();
			if (fovVal != null) {
				fov = fovVal.intValue();
			}
			Double gammaVal = mc.options.gamma().get();
			if (gammaVal != null) {
				gamma = safeDouble(gammaVal.doubleValue());
			}
		}

		LocalPlayer p = mc == null ? null : mc.player;
		boolean hasPlayer = p != null;
		double x = 0.0;
		double y = 0.0;
		double z = 0.0;
		float yaw = 0.0F;
		float pitch = 0.0F;
		float health = 20.0F;
		float maxHealth = 20.0F;
		float absorption = 0.0F;
		float saturation = 0.0F;
		float exhaustion = 0.0F;
		float xpProgress = 0.0F;
		int food = 20;
		int armor = 0;
		int xpLevel = 0;
		int mainCount = 0;
		int mainDmg = 0;
		int mainMaxDmg = 0;
		int offCount = 0;
		int offDmg = 0;
		int offMaxDmg = 0;
		int flagsBitmask = 0;
		if (inWorld) {
			flagsBitmask |= 1;
		}
		if (paused) {
			flagsBitmask |= 2;
		}

		ItemStack mainHand = null;
		ItemStack offHand = null;
		boolean mainEmpty = true;
		boolean offEmpty = true;
		GameType gt = null;
		if (hasPlayer) {
			flagsBitmask |= 4;
			x = safeDouble(p.getX());
			y = safeDouble(p.getY());
			z = safeDouble(p.getZ());
			yaw = safeFloat(p.getYRot());
			pitch = safeFloat(p.getXRot());
			health = safeFloat(p.getHealth());
			maxHealth = safeFloat(p.getMaxHealth());
			absorption = safeFloat(p.getAbsorptionAmount());
			FoodData foodData = p.getFoodData();
			if (foodData != null) {
				food = foodData.getFoodLevel();
				saturation = safeFloat(foodData.getSaturationLevel());
			}
			armor = p.getArmorValue();
			xpLevel = p.experienceLevel;
			xpProgress = safeFloat(p.experienceProgress);
			if (p.isSprinting()) {
				flagsBitmask |= 8;
			}
			if (p.isShiftKeyDown()) {
				flagsBitmask |= 16;
			}
			if (p.isCrouching()) {
				flagsBitmask |= 32;
			}
			if (p.onGround()) {
				flagsBitmask |= 64;
			}
			if (p.isInWater()) {
				flagsBitmask |= 128;
			}
			if (p.isUnderWater()) {
				flagsBitmask |= 256;
			}
			if (p.isFallFlying()) {
				flagsBitmask |= 512;
			}
			if (p.getVehicle() != null) {
				flagsBitmask |= 1024;
			}
			mainHand = p.getMainHandItem();
			offHand = p.getOffhandItem();
			mainEmpty = mainHand == null || mainHand.isEmpty();
			offEmpty = offHand == null || offHand.isEmpty();
			if (!mainEmpty) {
				mainCount = mainHand.getCount();
				mainDmg = mainHand.getDamageValue();
				mainMaxDmg = mainHand.getMaxDamage();
			}
			if (!offEmpty) {
				offCount = offHand.getCount();
				offDmg = offHand.getDamageValue();
				offMaxDmg = offHand.getMaxDamage();
			}
			if (mc.gameMode != null) {
				gt = mc.gameMode.getPlayerMode();
			}
		}

		double gameTime = 0.0;
		double dayTime = 0.0;
		if (inWorld) {
			gameTime = (double) lvl.getGameTime();
			dayTime = (double) lvl.getDefaultClockTime();
			if (lvl.isRaining()) {
				flagsBitmask |= 2048;
			}
			if (lvl.isThundering()) {
				flagsBitmask |= 4096;
			}
		}

		// Only resolve and push string fields when the underlying Java reference actually changed
		Object dimObj = inWorld ? lvl.dimension() : null;
		Object mainItemObj = mainEmpty ? null : mainHand.getItem();
		Object offItemObj = offEmpty ? null : offHand.getItem();
		Object userObj = mc != null ? mc.getUser() : null;

		String dimOut = null;
		String scrNameOut = null;
		String scrTitleOut = null;
		String mainOut = null;
		String offOut = null;
		String pNameOut = null;
		String gmOut = null;
		String userOut = null;
		String profOut = null;
		boolean stringsDirty = false;

		if (!liveStateStringsInit) {
			liveStateStringsInit = true;
			lastDimension = dimObj;
			lastScreen = scr;
			lastMainItem = mainItemObj;
			lastOffItem = offItemObj;
			lastPlayer = p;
			lastGameType = gt;
			lastUser = userObj;
			cachedDimension = inWorld ? lvl.dimension().identifier().toString() : "";
			cachedScreenName = scr == null ? "" : scr.getClass().getSimpleName();
			cachedScreenTitle = scr == null ? "" : scr.getTitle().getString();
			dimOut = cachedDimension;
			scrNameOut = cachedScreenName;
			scrTitleOut = cachedScreenTitle;
			mainOut = mainEmpty ? "minecraft:air" : itemIdString(mainHand);
			offOut = offEmpty ? "minecraft:air" : itemIdString(offHand);
			pNameOut = hasPlayer ? p.getName().getString() : "";
			gmOut = gt != null ? gt.getName() : "";
			userOut = userObj != null ? mc.getUser().getName() : "";
			profOut = bootedProfile.isEmpty() ? "vanilla" : bootedProfile;
			stringsDirty = true;
		} else {
			if (dimObj != lastDimension) {
				lastDimension = dimObj;
				cachedDimension = inWorld ? lvl.dimension().identifier().toString() : "";
				dimOut = cachedDimension;
				stringsDirty = true;
			}
			if (scr != lastScreen) {
				lastScreen = scr;
				cachedScreenName = scr == null ? "" : scr.getClass().getSimpleName();
				cachedScreenTitle = scr == null ? "" : scr.getTitle().getString();
				scrNameOut = cachedScreenName;
				scrTitleOut = cachedScreenTitle;
				stringsDirty = true;
			}
			if (mainItemObj != lastMainItem) {
				lastMainItem = mainItemObj;
				mainOut = mainEmpty ? "minecraft:air" : itemIdString(mainHand);
				stringsDirty = true;
			}
			if (offItemObj != lastOffItem) {
				lastOffItem = offItemObj;
				offOut = offEmpty ? "minecraft:air" : itemIdString(offHand);
				stringsDirty = true;
			}
			if (p != lastPlayer) {
				lastPlayer = p;
				pNameOut = hasPlayer ? p.getName().getString() : "";
				stringsDirty = true;
			}
			if (gt != lastGameType) {
				lastGameType = gt;
				gmOut = gt != null ? gt.getName() : "";
				stringsDirty = true;
			}
			if (userObj != lastUser) {
				lastUser = userObj;
				userOut = userObj != null ? mc.getUser().getName() : "";
				stringsDirty = true;
			}
		}

		if (stringsDirty) {
			jsPushLiveStrings(dimOut, scrNameOut, scrTitleOut, mainOut, offOut, pNameOut, gmOut, userOut, profOut);
		}
		if (hasPlayer) {
			jsPushLivePlayer(
				x, y, z,
				yaw, pitch, health, maxHealth, absorption, saturation, exhaustion, xpProgress,
				food, armor, xpLevel, flagsBitmask,
				mainCount, mainDmg, mainMaxDmg, offCount, offDmg, offMaxDmg);
		}
		jsPushLiveStateCore(
			flagsBitmask, guiScale, guiW, guiH, winW, winH, fps,
			gameTime, dayTime, (double) frameCounter, (double) tickCounter,
			renderDistance, fov, gamma);
	}

	private static String buildModsListJson() {
		List<InstalledMod> list = listMods();
		StringBuilder sb = new StringBuilder(256);
		sb.append('[');
		for (int i = 0; i < list.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			InstalledMod m = list.get(i);
			sb.append('{');
			sb.append("\"file\":\"").append(ModJarInspector.escapeJson(m.file)).append('"');
			sb.append(",\"id\":\"").append(ModJarInspector.escapeJson(m.info.id)).append('"');
			sb.append(",\"name\":\"").append(ModJarInspector.escapeJson(m.info.name)).append('"');
			sb.append(",\"version\":\"").append(ModJarInspector.escapeJson(m.info.version)).append('"');
			sb.append(",\"description\":\"").append(ModJarInspector.escapeJson(m.info.description)).append('"');
			sb.append(",\"author\":\"").append(ModJarInspector.escapeJson(m.info.author)).append('"');
			sb.append(",\"kind\":\"").append(m.info.kind.name()).append('"');
			sb.append(",\"badge\":\"").append(ModJarInspector.escapeJson(m.info.kind.badge())).append('"');
			sb.append(",\"loader\":\"").append(ModJarInspector.escapeJson(m.info.loader)).append('"');
			sb.append(",\"enabled\":").append(m.enabled);
			sb.append(",\"running\":").append(m.jsRunning || m.jvmTranslated);
			sb.append(",\"jvmTranslated\":").append(m.jvmTranslated);
			sb.append(",\"jvmHasUnsupportedMixins\":").append(m.jvmHasUnsupportedMixins);
			sb.append(",\"jvmSummary\":\"").append(ModJarInspector.escapeJson(m.jvmSummary)).append('"');
			sb.append(",\"classesLoaded\":").append(m.jvmClassesLoaded);
			sb.append(",\"entrypointsRun\":").append(m.jvmEntrypointsRun);
			sb.append(",\"mixinsAttached\":").append(m.jvmMixinsAttached);
			sb.append(",\"mixinsUnsupported\":").append(m.jvmMixinsUnsupported);
			sb.append(",\"canRunInBrowser\":").append(m.canRunInBrowser() || m.canRunViaTranslator());
			sb.append(",\"hasAssets\":").append(m.info.hasAssets());
			sb.append(",\"hasData\":").append(m.info.hasData());
			sb.append(",\"classCount\":").append(m.info.classCount);
			sb.append(",\"summary\":\"").append(ModJarInspector.escapeJson(m.jvmTranslated && !m.jvmSummary.isEmpty() ? m.jvmSummary : m.info.compatibilitySummary)).append('"');
			sb.append(",\"error\":\"").append(ModJarInspector.escapeJson(m.runtimeError)).append('"');
			sb.append('}');
		}
		sb.append(']');
		return sb.toString();
	}

	private static String buildProfileJson() {
		String sel = selectedProfile();
		List<String> avail = availableProfiles();
		StringBuilder sb = new StringBuilder(128);
		sb.append('{');
		sb.append("\"selected\":\"").append(ModJarInspector.escapeJson(sel.isEmpty() ? "vanilla" : sel)).append('"');
		sb.append(",\"booted\":\"").append(ModJarInspector.escapeJson(bootedProfile.isEmpty() ? "vanilla" : bootedProfile)).append('"');
		sb.append(",\"needsReload\":").append(profileNeedsReload());
		sb.append(",\"available\":[");
		for (int i = 0; i < avail.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('"').append(ModJarInspector.escapeJson(avail.get(i))).append('"');
		}
		sb.append("]}");
		return sb.toString();
	}

	// ---- TeaVM JSO Bridges (strictly ES5, no statement-position IIFEs) ----

	@JSFunctor
	private interface FileHandler extends JSObject {
		void onFile(String filename, Int8Array data);
	}

	@JSFunctor
	private interface TwoStringAction extends JSObject {
		void call(String a, String b);
	}

	@JSFunctor
	private interface OneStringAction extends JSObject {
		void call(String a);
	}

	@JSFunctor
	private interface VoidAction extends JSObject {
		void call();
	}

	@JSFunctor
	private interface StringSupplier extends JSObject {
		String get();
	}

	@JSFunctor
	private interface TwoStringToString extends JSObject {
		String call(String a, String b);
	}

	@JSFunctor
	private interface StringBoolToBool extends JSObject {
		boolean call(String a, boolean b);
	}

	@JSFunctor
	private interface StringToString extends JSObject {
		String call(String a);
	}

	@JSFunctor
	private interface BooleanSupplierJs extends JSObject {
		boolean get();
	}

	@JSFunctor
	private interface DoubleSupplierJs extends JSObject {
		double get();
	}

	@JSFunctor
	private interface DoubleToDouble extends JSObject {
		double call(double v);
	}

	@JSFunctor
	private interface IntSupplierJs extends JSObject {
		int get();
	}

	@JSFunctor
	private interface IntToInt extends JSObject {
		int call(int v);
	}

	@JSFunctor
	private interface BoolToBool extends JSObject {
		boolean call(boolean v);
	}

	@JSFunctor
	private interface TwoDoubleAction extends JSObject {
		void call(double a, double b);
	}

	@JSBody(params = {"k"}, script = "try { return window.localStorage.getItem(k); } catch (e) { return null; }")
	private static native String lsGet(String k);

	@JSBody(params = {"k", "v"}, script = "try { if (!v) { window.localStorage.removeItem(k); } else { window.localStorage.setItem(k, v); } } catch (e) {}")
	private static native void lsSet(String k, String v);

	@JSBody(script = "try { var p = window.__eaglerBootedModProfile; return typeof p === 'string' && p !== 'vanilla' ? p : ''; } catch (e) { return ''; }")
	private static native String readBootedProfile();

	@JSBody(script = ""
		+ "try {"
		+ "  var arr = window.__eaglerModProfiles;"
		+ "  if (!Array.isArray(arr)) { return ''; }"
		+ "  var out = [];"
		+ "  for (var i = 0; i < arr.length; i++) {"
		+ "    var item = arr[i];"
		+ "    var name = typeof item === 'string' ? item : (item && typeof item.id === 'string' ? item.id : '');"
		+ "    if (name) { out.push(name); }"
		+ "  }"
		+ "  return out.join(',');"
		+ "} catch (e) { return ''; }")
	private static native String readAvailableProfilesCsv();

	@JSBody(params = {"onFile", "onBeginBatch", "onEndBatch"}, script = ""
		+ "if (document.getElementById('minecraft-mod-file')) { return; }"
		+ "window.__mcWebModsBeginBatch = function() { onBeginBatch(); };"
		+ "window.__mcWebModsEndBatch = function() { onEndBatch(); };"
		+ "var input = document.createElement('input');"
		+ "input.type = 'file';"
		+ "input.accept = '.js,.mjs,.jar,.zip,application/java-archive,application/javascript,application/zip';"
		+ "input.multiple = true;"
		+ "input.id = 'minecraft-mod-file';"
		+ "input.style.display = 'none';"
		+ "input.onchange = function() {"
		+ "  var files = input.files;"
		+ "  if (files && files.length) {"
		+ "    var total = files.length;"
		+ "    var loaded = new Array(total);"
		+ "    var remaining = total;"
		+ "    var finishAll = function() {"
		+ "      if (total > 1) { onBeginBatch(); }"
		+ "      try {"
		+ "        for (var j = 0; j < total; j++) {"
		+ "          if (loaded[j]) { onFile(loaded[j].name, loaded[j].data); }"
		+ "        }"
		+ "      } finally {"
		+ "        if (total > 1) { onEndBatch(); }"
		+ "      }"
		+ "    };"
		+ "    var loadOne = function(idx, f) {"
		+ "      var fr = new FileReader();"
		+ "      fr.onload = function() {"
		+ "        loaded[idx] = { name: f.name, data: new Int8Array(fr.result) };"
		+ "        if (--remaining === 0) { finishAll(); }"
		+ "      };"
		+ "      fr.onerror = function() {"
		+ "        if (--remaining === 0) { finishAll(); }"
		+ "      };"
		+ "      fr.readAsArrayBuffer(f);"
		+ "    };"
		+ "    for (var i = 0; i < total; i++) { loadOne(i, files[i]); }"
		+ "  }"
		+ "  input.value = '';"
		+ "};"
		+ "document.body.appendChild(input);")
	private static native void installFileInput(FileHandler onFile, VoidAction onBeginBatch, VoidAction onEndBatch);

	@JSBody(script = ""
		+ "var input = document.getElementById('minecraft-mod-file');"
		+ "if (!input) { console.error('[webmods] file input missing'); return; }"
		+ "input.click();")
	private static native void clickPicker();

	@JSBody(params = {
			"fnToast", "fnChat", "fnSendChat", "fnActionBar", "fnReloadRes", "fnOpenScreen",
			"fnSyncLiveState", "fnListJson", "fnStatus", "fnProfileJson", "fnSelectProfile",
			"fnAddText", "fnSetEnabled", "fnRemove", "fnExtractPack", "fnReloadAll", "fnOnListenersChanged"},
		script = ""
		+ "var listeners = { tick: [], frame: [], screen: [], worldJoin: [], worldLeave: [] };"
		+ "var unloads = {};"
		+ "var hudItems = {};"
		+ "var currentOwner = null;"
		+ "var hudRoot = null;"
		+ "var suppressListenerNotify = false;"
		+ "var livePlayer = {"
		+ "  name: '', x: 0, y: 0, z: 0, vx: 0, vy: 0, vz: 0,"
		+ "  yaw: 0, pitch: 0, health: 20, maxHealth: 20, absorption: 0,"
		+ "  food: 20, saturation: 5, exhaustion: 0, armor: 0,"
		+ "  xpLevel: 0, xpProgress: 0,"
		+ "  sprinting: false, sneaking: false, crouching: false,"
		+ "  onGround: false, inWater: false, underWater: false,"
		+ "  fallFlying: false, inVehicle: false, gameMode: '',"
		+ "  mainHand: 'minecraft:air', mainHandCount: 0, mainHandDamage: 0, mainHandMaxDamage: 0,"
		+ "  offHand: 'minecraft:air', offHandCount: 0, offHandDamage: 0, offHandMaxDamage: 0"
		+ "};"
		+ "var liveWorld = {"
		+ "  dimension: 'minecraft:overworld', gameTime: 0, dayTime: 0, clockTime: 0,"
		+ "  raining: false, thundering: false"
		+ "};"
		+ "var liveState = {"
		+ "  frameSeq: 0, fps: 0, frame: 0, tick: 0,"
		+ "  inWorld: false, paused: false,"
		+ "  screen: null, screenName: '', screenTitle: '',"
		+ "  guiScale: 1, guiWidth: 0, guiHeight: 0, windowWidth: 0, windowHeight: 0,"
		+ "  username: '', profile: 'vanilla',"
		+ "  renderDistance: 8, fov: 70, gamma: 0.5,"
		+ "  player: null, world: null,"
		+ "  x: 0, y: 0, z: 0, yaw: 0, pitch: 0,"
		+ "  health: 20, maxHealth: 20, absorption: 0,"
		+ "  food: 20, saturation: 5, exhaustion: 0, armor: 0,"
		+ "  xpLevel: 0, xpProgress: 0,"
		+ "  sprinting: false, sneaking: false, onGround: false,"
		+ "  inWater: false, underWater: false, fallFlying: false,"
		+ "  mainHand: 'minecraft:air', mainHandCount: 0, mainHandDamage: 0, mainHandMaxDamage: 0,"
		+ "  offHand: 'minecraft:air', offHandCount: 0,"
		+ "  gameTime: 0, dayTime: 0, raining: false, thundering: false, dimension: ''"
		+ "};"
		+ "var frameInfo = { frame: 0, nowMs: 0, dtMs: 16.67, fps: 0, screen: null, inWorld: false };"
		+ "function notifyListenersChanged() {"
		+ "  if (!suppressListenerNotify) {"
		+ "    try { fnOnListenersChanged(); } catch (e) {}"
		+ "  }"
		+ "}"
		+ "function ensureHudRoot() {"
		+ "  if (hudRoot && document.body.contains(hudRoot)) { return hudRoot; }"
		+ "  var el = document.getElementById('eagler-mods-hud');"
		+ "  if (!el) {"
		+ "    el = document.createElement('div');"
		+ "    el.id = 'eagler-mods-hud';"
		+ "    el.style.position = 'fixed';"
		+ "    el.style.pointerEvents = 'none';"
		+ "    el.style.zIndex = '20';"
		+ "    el.style.overflow = 'hidden';"
		+ "    el.style.fontFamily = 'ui-monospace, Menlo, Consolas, monospace';"
		+ "    el.style.fontSize = '12px';"
		+ "    el.style.lineHeight = '1.35';"
		+ "    el.style.color = '#ffffff';"
		+ "    el.style.textShadow = '1px 1px 0 #000000';"
		+ "    document.body.appendChild(el);"
		+ "  }"
		+ "  hudRoot = el;"
		+ "  syncHudBounds();"
		+ "  return hudRoot;"
		+ "}"
		+ "function syncHudBounds() {"
		+ "  if (!hudRoot) { return; }"
		+ "  var c = document.getElementById('game_frame');"
		+ "  if (!c) { hudRoot.style.left = '0'; hudRoot.style.top = '0'; hudRoot.style.width = '100%'; hudRoot.style.height = '100%'; return; }"
		+ "  var r = c.getBoundingClientRect();"
		+ "  hudRoot.style.left = Math.round(r.left) + 'px';"
		+ "  hudRoot.style.top = Math.round(r.top) + 'px';"
		+ "  hudRoot.style.width = Math.round(r.width) + 'px';"
		+ "  hudRoot.style.height = Math.round(r.height) + 'px';"
		+ "}"
		+ "window.addEventListener('resize', syncHudBounds);"
		+ "document.addEventListener('fullscreenchange', syncHudBounds);"
		+ "function setHudItem(id, spec, owner) {"
		+ "  var key = String(id || 'default');"
		+ "  var root = ensureHudRoot();"
		+ "  var rec = hudItems[key];"
		+ "  if (!rec) {"
		+ "    var node = document.createElement('div');"
		+ "    node.style.position = 'absolute';"
		+ "    node.style.pointerEvents = 'none';"
		+ "    node.style.whiteSpace = 'pre';"
		+ "    node.style.borderRadius = '2px';"
		+ "    root.appendChild(node);"
		+ "    rec = { id: key, owner: owner || currentOwner || '__global__', node: node };"
		+ "    hudItems[key] = rec;"
		+ "  }"
		+ "  var opts = (spec && typeof spec === 'object') ? spec : { text: String(spec == null ? '' : spec) };"
		+ "  var n = rec.node;"
		+ "  n.style.display = opts.visible === false ? 'none' : 'block';"
		+ "  n.style.left = ''; n.style.right = ''; n.style.top = ''; n.style.bottom = ''; n.style.transform = '';"
		+ "  var anchor = opts.anchor || 'top-left';"
		+ "  var x = typeof opts.x === 'number' ? opts.x : 8;"
		+ "  var y = typeof opts.y === 'number' ? opts.y : 8;"
		+ "  if (anchor === 'top-right') { n.style.right = x + 'px'; n.style.top = y + 'px'; }"
		+ "  else if (anchor === 'bottom-left') { n.style.left = x + 'px'; n.style.bottom = y + 'px'; }"
		+ "  else if (anchor === 'bottom-right') { n.style.right = x + 'px'; n.style.bottom = y + 'px'; }"
		+ "  else if (anchor === 'top-center') { n.style.left = '50%'; n.style.top = y + 'px'; n.style.transform = 'translateX(-50%)'; }"
		+ "  else if (anchor === 'bottom-center') { n.style.left = '50%'; n.style.bottom = y + 'px'; n.style.transform = 'translateX(-50%)'; }"
		+ "  else { n.style.left = x + 'px'; n.style.top = y + 'px'; }"
		+ "  n.style.color = opts.color || '#ffffff';"
		+ "  n.style.background = opts.background != null ? opts.background : 'rgba(0,0,0,0.45)';"
		+ "  n.style.padding = opts.padding != null ? opts.padding : '2px 5px';"
		+ "  if (opts.fontSize) { n.style.fontSize = typeof opts.fontSize === 'number' ? (opts.fontSize + 'px') : String(opts.fontSize); }"
		+ "  if (typeof opts.html === 'string') { n.innerHTML = opts.html; }"
		+ "  else { n.textContent = opts.text == null ? '' : String(opts.text); }"
		+ "  syncHudBounds();"
		+ "  return key;"
		+ "}"
		+ "function removeHudItem(id) {"
		+ "  var key = String(id || '');"
		+ "  var rec = hudItems[key];"
		+ "  if (rec) {"
		+ "    if (rec.node && rec.node.parentNode) { rec.node.parentNode.removeChild(rec.node); }"
		+ "    delete hudItems[key];"
		+ "  }"
		+ "}"
		+ "function clearHudItems(owner) {"
		+ "  var keys = Object.keys(hudItems);"
		+ "  for (var i = 0; i < keys.length; i++) {"
		+ "    var rec = hudItems[keys[i]];"
		+ "    if (!owner || rec.owner === owner) { removeHudItem(keys[i]); }"
		+ "  }"
		+ "}"
		+ "function addListener(ev, fn, owner) {"
		+ "  if (!listeners[ev] || typeof fn !== 'function') { return function() {}; }"
		+ "  var entry = { fn: fn, owner: owner || currentOwner || '__global__' };"
		+ "  listeners[ev].push(entry);"
		+ "  notifyListenersChanged();"
		+ "  return function() { removeListener(ev, fn); };"
		+ "}"
		+ "function removeListener(ev, fn) {"
		+ "  var arr = listeners[ev];"
		+ "  if (!arr) { return; }"
		+ "  var removed = false;"
		+ "  for (var i = arr.length - 1; i >= 0; i--) {"
		+ "    if (arr[i].fn === fn) { arr.splice(i, 1); removed = true; }"
		+ "  }"
		+ "  if (removed) { notifyListenersChanged(); }"
		+ "}"
		+ "function parseSafe(json, fallback) {"
		+ "  try { return JSON.parse(json); } catch (e) { return fallback; }"
		+ "}"
		+ "var internal = {"
		+ "  listeners: listeners,"
		+ "  unloads: unloads,"
		+ "  liveState: liveState,"
		+ "  livePlayer: livePlayer,"
		+ "  liveWorld: liveWorld,"
		+ "  liveStatePushed: false,"
		+ "  liveStateAutoSync: false,"
		+ "  hasAnyListeners: function() {"
		+ "    return listeners.tick.length > 0 || listeners.frame.length > 0"
		+ "      || listeners.screen.length > 0 || listeners.worldJoin.length > 0 || listeners.worldLeave.length > 0;"
		+ "  },"
		+ "  hasEventListeners: function(ev) { return !!(listeners[ev] && listeners[ev].length > 0); },"
		+ "  emitTick: function(tick) {"
		+ "    var raw = listeners.tick;"
		+ "    var len = raw.length;"
		+ "    if (!len) { return; }"
		+ "    liveState.tick = tick;"
		+ "    var st = getState();"
		+ "    if (len === 1) {"
		+ "      var single = raw[0];"
		+ "      try { single.fn(st, tick); } catch (e) { console.error('[webmods] tick listener error (' + single.owner + '):', e); }"
		+ "      return;"
		+ "    }"
		+ "    var arr = raw.slice();"
		+ "    for (var i = 0; i < arr.length; i++) {"
		+ "      try { arr[i].fn(st, tick); } catch (e) { console.error('[webmods] tick listener error (' + arr[i].owner + '):', e); }"
		+ "    }"
		+ "  },"
		+ "  emitFrame: function(frame, nowMs, dtMs, fps, screen, inWorld) {"
		+ "    var raw = listeners.frame;"
		+ "    var len = raw.length;"
		+ "    if (!len) { return; }"
		+ "    frameInfo.frame = frame;"
		+ "    frameInfo.nowMs = nowMs;"
		+ "    frameInfo.dtMs = dtMs;"
		+ "    frameInfo.fps = fps;"
		+ "    frameInfo.screen = screen || null;"
		+ "    frameInfo.inWorld = !!inWorld;"
		+ "    if (len === 1) {"
		+ "      var single = raw[0];"
		+ "      try { single.fn(frameInfo); } catch (e) { console.error('[webmods] frame listener error (' + single.owner + '):', e); }"
		+ "      return;"
		+ "    }"
		+ "    var arr = raw.slice();"
		+ "    for (var i = 0; i < arr.length; i++) {"
		+ "      try { arr[i].fn(frameInfo); } catch (e) { console.error('[webmods] frame listener error (' + arr[i].owner + '):', e); }"
		+ "    }"
		+ "  },"
		+ "  emitScreen: function(next, prev, title) {"
		+ "    var arr = listeners.screen.slice();"
		+ "    for (var i = 0; i < arr.length; i++) {"
		+ "      try { arr[i].fn(next || null, prev || null, title || ''); } catch (e) { console.error('[webmods] screen listener error (' + arr[i].owner + '):', e); }"
		+ "    }"
		+ "  },"
		+ "  emitWorldJoin: function(dim) {"
		+ "    var arr = listeners.worldJoin.slice();"
		+ "    for (var i = 0; i < arr.length; i++) {"
		+ "      try { arr[i].fn(dim); } catch (e) { console.error('[webmods] worldJoin listener error (' + arr[i].owner + '):', e); }"
		+ "    }"
		+ "  },"
		+ "  emitWorldLeave: function() {"
		+ "    var arr = listeners.worldLeave.slice();"
		+ "    for (var i = 0; i < arr.length; i++) {"
		+ "      try { arr[i].fn(); } catch (e) { console.error('[webmods] worldLeave listener error (' + arr[i].owner + '):', e); }"
		+ "    }"
		+ "  },"
		+ "  unloadMod: function(file) {"
		+ "    var fns = unloads[file];"
		+ "    if (fns) {"
		+ "      for (var i = 0; i < fns.length; i++) {"
		+ "        try { fns[i](); } catch (e) { console.error('[webmods] unload error (' + file + '):', e); }"
		+ "      }"
		+ "      delete unloads[file];"
		+ "    }"
		+ "    var evNames = Object.keys(listeners);"
		+ "    var removedAny = false;"
		+ "    for (var eIdx = 0; eIdx < evNames.length; eIdx++) {"
		+ "      var arr = listeners[evNames[eIdx]];"
		+ "      for (var j = arr.length - 1; j >= 0; j--) {"
		+ "        if (arr[j].owner === file) { arr.splice(j, 1); removedAny = true; }"
		+ "      }"
		+ "    }"
		+ "    clearHudItems(file);"
		+ "    if (removedAny) { notifyListenersChanged(); }"
		+ "  },"
		+ "  evalMod: function(file, id, name, version, source) {"
		+ "    var prevOwner = currentOwner;"
		+ "    var prevSuppress = suppressListenerNotify;"
		+ "    currentOwner = file;"
		+ "    suppressListenerNotify = true;"
		+ "    unloads[file] = [];"
		+ "    var storagePrefix = 'eagler.mod.' + id + '.';"
		+ "    var modCtx = {"
		+ "      file: file, id: id, name: name, version: version,"
		+ "      on: function(ev, fn) { return addListener(ev, fn, file); },"
		+ "      onTick: function(fn) { return addListener('tick', fn, file); },"
		+ "      onFrame: function(fn) { return addListener('frame', fn, file); },"
		+ "      onScreenChange: function(fn) { return addListener('screen', fn, file); },"
		+ "      onWorldJoin: function(fn) { return addListener('worldJoin', fn, file); },"
		+ "      onWorldLeave: function(fn) { return addListener('worldLeave', fn, file); },"
		+ "      onUnload: function(fn) { if (typeof fn === 'function') { unloads[file].push(fn); } },"
		+ "      toast: api.toast, chat: api.chat, sendChat: api.sendChat, actionBar: api.actionBar,"
		+ "      getState: api.getState,"
		+ "      getGamma: function() { return api.getGamma ? api.getGamma() : 0.5; },"
		+ "      setGamma: function(v) { return api.setGamma ? api.setGamma(v) : 0.5; },"
		+ "      getFov: function() { return api.getFov ? api.getFov() : 70; },"
		+ "      setFov: function(v) { return api.setFov ? api.setFov(v) : 70; },"
		+ "      setSprinting: function(v) { return api.setSprinting ? api.setSprinting(v) : false; },"
		+ "      setRotation: function(yaw, pitch) { if (api.setRotation) { api.setRotation(yaw, pitch); } },"
		+ "      getBuilderStatus: function() { return api.getBuilderStatus ? api.getBuilderStatus() : null; },"
		+ "      reload: function() { if (api.reload) { api.reload(); } },"
		+ "      buildProfile: function(p) { if (api.buildProfile) { api.buildProfile(p); } },"
		+ "      hud: {"
		+ "        set: function(localId, spec) { return setHudItem(id + ':' + localId, spec, file); },"
		+ "        remove: function(localId) { removeHudItem(id + ':' + localId); }"
		+ "      },"
		+ "      storage: {"
		+ "        get: function(k, fallback) { try { var v = window.localStorage.getItem(storagePrefix + k); return v === null ? fallback : v; } catch (e) { return fallback; } },"
		+ "        set: function(k, v) { try { window.localStorage.setItem(storagePrefix + k, String(v)); } catch (e) {} },"
		+ "        remove: function(k) { try { window.localStorage.removeItem(storagePrefix + k); } catch (e) {} }"
		+ "      },"
		+ "      log: function() { var args = Array.prototype.slice.call(arguments); args.unshift('[mod:' + id + ']'); console.info.apply(console, args); }"
		+ "    };"
		+ "    try {"
		+ "      var runner = new Function('EaglerMods', 'mod', source + '\\n//# sourceURL=eagler-mod://' + file);"
		+ "      runner(api, modCtx);"
		+ "      currentOwner = prevOwner;"
		+ "      suppressListenerNotify = prevSuppress;"
		+ "      notifyListenersChanged();"
		+ "      return '';"
		+ "    } catch (err) {"
		+ "      currentOwner = prevOwner;"
		+ "      suppressListenerNotify = prevSuppress;"
		+ "      internal.unloadMod(file);"
		+ "      return err && err.message ? String(err.message) : String(err);"
		+ "    }"
		+ "  }"
		+ "};"
		+ "function getState() {"
		+ "  if (!internal.liveStatePushed || !internal.liveStateAutoSync) {"
		+ "    fnSyncLiveState();"
		+ "  }"
		+ "  return liveState;"
		+ "}"
		+ "var api = {"
		+ "  version: '1.0.0',"
		+ "  apiVersion: 1,"
		+ "  minecraftVersion: '26.3',"
		+ "  _liveState: liveState,"
		+ "  _livePlayer: livePlayer,"
		+ "  _liveWorld: liveWorld,"
		+ "  _refreshHooks: notifyListenersChanged,"
		+ "  on: function(ev, fn) { return addListener(ev, fn, null); },"
		+ "  off: function(ev, fn) { removeListener(ev, fn); },"
		+ "  onTick: function(fn) { return addListener('tick', fn, null); },"
		+ "  onFrame: function(fn) { return addListener('frame', fn, null); },"
		+ "  onScreenChange: function(fn) { return addListener('screen', fn, null); },"
		+ "  onWorldJoin: function(fn) { return addListener('worldJoin', fn, null); },"
		+ "  onWorldLeave: function(fn) { return addListener('worldLeave', fn, null); },"
		+ "  toast: function(title, msg) { fnToast(String(title == null ? '' : title), String(msg == null ? '' : msg)); },"
		+ "  chat: function(msg) { fnChat(String(msg == null ? '' : msg)); },"
		+ "  sendChat: function(msg) { fnSendChat(String(msg == null ? '' : msg)); },"
		+ "  actionBar: function(msg) { fnActionBar(String(msg == null ? '' : msg)); },"
		+ "  reloadResources: function() { fnReloadRes(); },"
		+ "  openModsScreen: function() { fnOpenScreen(); },"
		+ "  getState: getState,"
		+ "  list: function() { return parseSafe(fnListJson(), []); },"
		+ "  status: function() { return fnStatus(); },"
		+ "  profile: function() { return parseSafe(fnProfileJson(), { selected: 'vanilla', booted: 'vanilla', needsReload: false, available: ['vanilla'] }); },"
		+ "  selectProfile: function(name) { fnSelectProfile(name == null ? '' : String(name)); },"
		+ "  addFromText: function(filename, source) { var e = fnAddText(String(filename), String(source)); return e ? e : null; },"
		+ "  enable: function(filename) { return !!fnSetEnabled(String(filename), true); },"
		+ "  disable: function(filename) { return !!fnSetEnabled(String(filename), false); },"
		+ "  remove: function(filename) { fnRemove(String(filename)); },"
		+ "  extractPack: function(filename) { return fnExtractPack(String(filename)); },"
		+ "  reload: function() { fnReloadAll(); },"
		+ "  hud: {"
		+ "    set: function(id, spec) { return setHudItem(id, spec, null); },"
		+ "    remove: function(id) { removeHudItem(id); },"
		+ "    clear: function(owner) { clearHudItems(owner || null); },"
		+ "    list: function() { return Object.keys(hudItems); }"
		+ "  }"
		+ "};"
		+ "window.__mcWebModsDrivesJvm = true;"
		+ "window.__eaglerModsInternal = internal;"
		+ "window.EaglerMods = api;")
	private static native void installRuntimeBridge(
			TwoStringAction fnToast,
			OneStringAction fnChat,
			OneStringAction fnSendChat,
			OneStringAction fnActionBar,
			VoidAction fnReloadRes,
			VoidAction fnOpenScreen,
			VoidAction fnSyncLiveState,
			StringSupplier fnListJson,
			StringSupplier fnStatus,
			StringSupplier fnProfileJson,
			OneStringAction fnSelectProfile,
			TwoStringToString fnAddText,
			StringBoolToBool fnSetEnabled,
			OneStringAction fnRemove,
			StringToString fnExtractPack,
			VoidAction fnReloadAll,
			VoidAction fnOnListenersChanged);

	@JSBody(params = {"autoSync"}, script = "var rt = window.__eaglerModsInternal; if (rt) { rt.liveStateAutoSync = !!autoSync; }")
	private static native void jsSetLiveStateAutoSync(boolean autoSync);

	@JSBody(params = {
			"dimension", "screenName", "screenTitle", "mainHand", "offHand",
			"playerName", "gameMode", "username", "profile"},
		script = ""
		+ "var rt = window.__eaglerModsInternal;"
		+ "if (!rt) { return; }"
		+ "var s = rt.liveState;"
		+ "var p = rt.livePlayer;"
		+ "var w = rt.liveWorld;"
		+ "if (dimension !== null) { w.dimension = dimension; if (s.world) { s.dimension = dimension; } }"
		+ "if (screenName !== null) { s.screenName = screenName; s.screen = screenName ? screenName : null; }"
		+ "if (screenTitle !== null) { s.screenTitle = screenTitle; }"
		+ "if (mainHand !== null) { p.mainHand = mainHand; if (s.player) { s.mainHand = mainHand; } }"
		+ "if (offHand !== null) { p.offHand = offHand; if (s.player) { s.offHand = offHand; } }"
		+ "if (playerName !== null) { p.name = playerName; }"
		+ "if (gameMode !== null) { p.gameMode = gameMode; }"
		+ "if (username !== null) { s.username = username; }"
		+ "if (profile !== null) { s.profile = profile; }")
	private static native void jsPushLiveStrings(
			String dimension,
			String screenName,
			String screenTitle,
			String mainHand,
			String offHand,
			String playerName,
			String gameMode,
			String username,
			String profile);

	@JSBody(params = {
			"x", "y", "z",
			"yaw", "pitch", "health", "maxHealth", "absorption", "saturation", "exhaustion", "xpProgress",
			"food", "armor", "xpLevel", "flagsBitmask",
			"mainCount", "mainDmg", "mainMaxDmg", "offCount", "offDmg", "offMaxDmg"},
		script = ""
		+ "var rt = window.__eaglerModsInternal;"
		+ "if (!rt) { return; }"
		+ "var s = rt.liveState;"
		+ "var p = rt.livePlayer;"
		+ "p.x = x; p.y = y; p.z = z;"
		+ "p.yaw = yaw; p.pitch = pitch;"
		+ "p.health = health; p.maxHealth = maxHealth; p.absorption = absorption;"
		+ "p.food = food; p.saturation = saturation; p.exhaustion = exhaustion;"
		+ "p.armor = armor; p.xpLevel = xpLevel; p.xpProgress = xpProgress;"
		+ "p.sprinting = (flagsBitmask & 8) !== 0;"
		+ "p.sneaking = (flagsBitmask & 16) !== 0;"
		+ "p.crouching = (flagsBitmask & 32) !== 0;"
		+ "p.onGround = (flagsBitmask & 64) !== 0;"
		+ "p.inWater = (flagsBitmask & 128) !== 0;"
		+ "p.underWater = (flagsBitmask & 256) !== 0;"
		+ "p.fallFlying = (flagsBitmask & 512) !== 0;"
		+ "p.inVehicle = (flagsBitmask & 1024) !== 0;"
		+ "p.mainHandCount = mainCount;"
		+ "p.mainHandDamage = mainDmg;"
		+ "p.mainHandMaxDamage = mainMaxDmg;"
		+ "p.offHandCount = offCount;"
		+ "p.offHandDamage = offDmg;"
		+ "p.offHandMaxDamage = offMaxDmg;"
		+ "s.player = p;"
		+ "s.x = x; s.y = y; s.z = z;"
		+ "s.yaw = yaw; s.pitch = pitch;"
		+ "s.health = health; s.maxHealth = maxHealth; s.absorption = absorption;"
		+ "s.food = food; s.saturation = saturation; s.exhaustion = exhaustion;"
		+ "s.armor = armor; s.xpLevel = xpLevel; s.xpProgress = xpProgress;"
		+ "s.sprinting = p.sprinting; s.sneaking = p.sneaking; s.onGround = p.onGround;"
		+ "s.inWater = p.inWater; s.underWater = p.underWater; s.fallFlying = p.fallFlying;"
		+ "s.mainHand = p.mainHand; s.mainHandCount = mainCount;"
		+ "s.mainHandDamage = mainDmg; s.mainHandMaxDamage = mainMaxDmg;"
		+ "s.offHand = p.offHand; s.offHandCount = offCount;")
	private static native void jsPushLivePlayer(
			double x, double y, double z,
			float yaw, float pitch, float health, float maxHealth, float absorption, float saturation, float exhaustion, float xpProgress,
			int food, int armor, int xpLevel, int flagsBitmask,
			int mainCount, int mainDmg, int mainMaxDmg, int offCount, int offDmg, int offMaxDmg);

	@JSBody(params = {
			"flagsBitmask", "guiScale", "guiW", "guiH", "winW", "winH", "fps",
			"gameTime", "dayTime", "frame", "tick",
			"renderDistance", "fov", "gamma"},
		script = ""
		+ "var rt = window.__eaglerModsInternal;"
		+ "if (!rt) { return; }"
		+ "var s = rt.liveState;"
		+ "var w = rt.liveWorld;"
		+ "var inWorld = (flagsBitmask & 1) !== 0;"
		+ "var hasPlayer = (flagsBitmask & 4) !== 0;"
		+ "s.fps = fps;"
		+ "s.frame = frame;"
		+ "s.tick = tick;"
		+ "s.inWorld = inWorld;"
		+ "s.paused = (flagsBitmask & 2) !== 0;"
		+ "s.guiScale = guiScale;"
		+ "s.guiWidth = guiW;"
		+ "s.guiHeight = guiH;"
		+ "s.windowWidth = winW;"
		+ "s.windowHeight = winH;"
		+ "s.renderDistance = renderDistance;"
		+ "s.fov = fov;"
		+ "s.gamma = gamma;"
		+ "if (!hasPlayer) {"
		+ "  s.player = null;"
		+ "}"
		+ "if (inWorld) {"
		+ "  w.gameTime = gameTime;"
		+ "  w.dayTime = dayTime;"
		+ "  w.clockTime = dayTime;"
		+ "  w.raining = (flagsBitmask & 2048) !== 0;"
		+ "  w.thundering = (flagsBitmask & 4096) !== 0;"
		+ "  s.world = w;"
		+ "  s.gameTime = gameTime;"
		+ "  s.dayTime = dayTime;"
		+ "  s.raining = w.raining;"
		+ "  s.thundering = w.thundering;"
		+ "  s.dimension = w.dimension;"
		+ "} else {"
		+ "  s.world = null;"
		+ "}"
		+ "s.frameSeq++;"
		+ "rt.liveStatePushed = true;")
	private static native void jsPushLiveStateCore(
			int flagsBitmask, int guiScale, int guiW, int guiH, int winW, int winH, int fps,
			double gameTime, double dayTime, double frame, double tick,
			int renderDistance, int fov, double gamma);

	@JSBody(script = ""
		+ "var t = window.EaglerJvmTranslator;"
		+ "if (!t) { return false; }"
		+ "if (typeof t.hasAnyHooks === 'function' && t.hasAnyHooks()) { return true; }"
		+ "if (typeof t.listTranslatedMods === 'function') {"
		+ "  var list = t.listTranslatedMods();"
		+ "  if (Array.isArray(list) && list.length > 0) { return true; }"
		+ "}"
		+ "return false;")
	private static native boolean jsJvmHasTranslatedMods();

	@JSBody(params = {"anyRunning"}, script = ""
		+ "var t = window.EaglerJvmTranslator;"
		+ "if (!t || typeof t.dispatchHook !== 'function') { return false; }"
		+ "if (typeof t.hasAnyHooks === 'function') { return !!t.hasAnyHooks(); }"
		+ "if (anyRunning) { return true; }"
		+ "if (t.hooks && typeof t.hooks === 'object') {"
		+ "  var keys = Object.keys(t.hooks);"
		+ "  for (var i = 0; i < keys.length; i++) {"
		+ "    var a = t.hooks[keys[i]];"
		+ "    if (Array.isArray(a) && a.length > 0) { return true; }"
		+ "  }"
		+ "}"
		+ "if (typeof t.listTranslatedMods === 'function') {"
		+ "  var mods = t.listTranslatedMods();"
		+ "  if (Array.isArray(mods) && mods.length > 0) { return true; }"
		+ "}"
		+ "return false;")
	private static native boolean jsJvmHasAnyHooks(boolean anyRunning);

	@JSBody(params = {"hookName", "anyRunning"}, script = ""
		+ "var t = window.EaglerJvmTranslator;"
		+ "if (!t || typeof t.dispatchHook !== 'function') { return false; }"
		+ "if (typeof t.hasHook === 'function') { return !!t.hasHook(hookName); }"
		+ "if (t.hooks && typeof t.hooks === 'object') {"
		+ "  var a = t.hooks[hookName];"
		+ "  if (Array.isArray(a)) { return a.length > 0; }"
		+ "}"
		+ "if (typeof t.listTranslatedMods === 'function') {"
		+ "  var mods = t.listTranslatedMods();"
		+ "  if (Array.isArray(mods)) { return mods.length > 0; }"
		+ "}"
		+ "return !!anyRunning;")
	private static native boolean jsJvmHasHook(String hookName, boolean anyRunning);

	@JSBody(params = {"filename", "id", "name", "version", "int8Data"}, script = ""
		+ "var reports = window.__eaglerJvmReports || (window.__eaglerJvmReports = {});"
		+ "var t = window.EaglerJvmTranslator;"
		+ "if (!t || typeof t.loadJar !== 'function') {"
		+ "  reports[filename] = { ok: false, classesLoaded: 0, entrypointsRun: 0, mixinsAttached: 0, mixinsUnsupported: 0, summary: '', error: 'Offline Java translator not loaded' };"
		+ "  return false;"
		+ "}"
		+ "try {"
		+ "  var u8 = new Uint8Array(int8Data.buffer.slice(int8Data.byteOffset, int8Data.byteOffset + int8Data.byteLength));"
		+ "  var res = t.loadJar(filename, u8, { id: id || '', name: name || '', version: version || '', filename: filename });"
		+ "  var ok = !!(res && res.ok);"
		+ "  var cls = res && res.classesLoaded ? (res.classesLoaded | 0) : 0;"
		+ "  var ep = res && res.entrypointsRun ? (res.entrypointsRun | 0) : 0;"
		+ "  var mx = res && res.mixinsAttached ? (res.mixinsAttached | 0) : 0;"
		+ "  var mxU = res && res.mixinsUnsupported ? (res.mixinsUnsupported | 0) : 0;"
		+ "  var sum = (res && typeof res.summary === 'string') ? res.summary.trim() : '';"
		+ "  var err = '';"
		+ "  if (res && Array.isArray(res.errors) && res.errors.length) {"
		+ "    var parts = [];"
		+ "    for (var i = 0; i < res.errors.length && parts.length < 2; i++) {"
		+ "      var item = res.errors[i];"
		+ "      if (item != null && String(item).trim()) { parts.push(String(item).trim()); }"
		+ "    }"
		+ "    err = parts.join('; ');"
		+ "  } else if (res && typeof res.error === 'string') {"
		+ "    err = res.error.trim();"
		+ "  }"
		+ "  if (!ok && !err) { err = 'Java mod translation failed'; }"
		+ "  reports[filename] = { ok: ok, classesLoaded: cls, entrypointsRun: ep, mixinsAttached: mx, mixinsUnsupported: mxU, summary: sum, error: err };"
		+ "  return ok;"
		+ "} catch (e) {"
		+ "  var msg = (e && e.message) ? String(e.message) : String(e);"
		+ "  reports[filename] = { ok: false, classesLoaded: 0, entrypointsRun: 0, mixinsAttached: 0, mixinsUnsupported: 0, summary: '', error: msg };"
		+ "  console.error('[webmods] EaglerJvmTranslator.loadJar error (' + filename + '):', e);"
		+ "  return false;"
		+ "}")
	private static native boolean jsRunJavaJar(String filename, String id, String name, String version, Int8Array int8Data);

	@JSBody(params = {"filename"}, script = ""
		+ "var reports = window.__eaglerJvmReports;"
		+ "if (reports && reports[filename]) { delete reports[filename]; }"
		+ "var t = window.EaglerJvmTranslator;"
		+ "var ok = false;"
		+ "if (t && typeof t.unloadMod === 'function') {"
		+ "  try { ok = !!t.unloadMod(filename); } catch (e) { console.error('[webmods] unloadMod error (' + filename + '):', e); }"
		+ "}"
		+ "var b = window.EaglerJvmBridge;"
		+ "if (b) {"
		+ "  if (typeof b.clearHudOverlay === 'function') {"
		+ "    try { b.clearHudOverlay(); } catch (e) {}"
		+ "  } else if (typeof b.getOverlayCanvas === 'function') {"
		+ "    try {"
		+ "      var ov = b.getOverlayCanvas();"
		+ "      if (ov) { var c2 = ov.getContext('2d'); if (c2) { c2.clearRect(0, 0, ov.width, ov.height); } }"
		+ "    } catch (e) {}"
		+ "  }"
		+ "}"
		+ "return ok;")
	private static native boolean jsUnloadJavaMod(String filename);

	@JSBody(params = {"filename"}, script = "var r = window.__eaglerJvmReports && window.__eaglerJvmReports[filename]; return r && r.summary ? String(r.summary) : '';")
	private static native String jsJvmReportSummary(String filename);

	@JSBody(params = {"filename"}, script = "var r = window.__eaglerJvmReports && window.__eaglerJvmReports[filename]; return r && r.error ? String(r.error) : '';")
	private static native String jsJvmReportError(String filename);

	@JSBody(params = {"filename"}, script = "var r = window.__eaglerJvmReports && window.__eaglerJvmReports[filename]; return r ? (r.classesLoaded | 0) : 0;")
	private static native int jsJvmReportClassesLoaded(String filename);

	@JSBody(params = {"filename"}, script = "var r = window.__eaglerJvmReports && window.__eaglerJvmReports[filename]; return r ? (r.entrypointsRun | 0) : 0;")
	private static native int jsJvmReportEntrypointsRun(String filename);

	@JSBody(params = {"filename"}, script = "var r = window.__eaglerJvmReports && window.__eaglerJvmReports[filename]; return r ? (r.mixinsAttached | 0) : 0;")
	private static native int jsJvmReportMixinsAttached(String filename);

	@JSBody(params = {"filename"}, script = "var r = window.__eaglerJvmReports && window.__eaglerJvmReports[filename]; return r ? (r.mixinsUnsupported | 0) : 0;")
	private static native int jsJvmReportMixinsUnsupported(String filename);

	@JSBody(params = {"hookName", "val"}, script = ""
		+ "var t = window.EaglerJvmTranslator;"
		+ "if (!t || typeof t.dispatchHook !== 'function') { return val; }"
		+ "var ctx = window.__eaglerJvmNumCtx;"
		+ "if (!ctx) { ctx = { value: val, returnValue: undefined, result: undefined, cancelled: false }; window.__eaglerJvmNumCtx = ctx; }"
		+ "ctx.value = val; ctx.returnValue = undefined; ctx.result = undefined; ctx.cancelled = false;"
		+ "try {"
		+ "  var res = t.dispatchHook(hookName, ctx);"
		+ "  if (typeof res === 'number' && isFinite(res)) { return res; }"
		+ "  if (res && typeof res === 'object') {"
		+ "    if (typeof res.returnValue === 'number' && isFinite(res.returnValue)) { return res.returnValue; }"
		+ "    if (typeof res.value === 'number' && isFinite(res.value)) { return res.value; }"
		+ "    if (typeof res.result === 'number' && isFinite(res.result)) { return res.result; }"
		+ "  }"
		+ "  if (typeof ctx.returnValue === 'number' && isFinite(ctx.returnValue)) { return ctx.returnValue; }"
		+ "  if (typeof ctx.value === 'number' && isFinite(ctx.value)) { return ctx.value; }"
		+ "  if (typeof ctx.result === 'number' && isFinite(ctx.result)) { return ctx.result; }"
		+ "} catch (e) {"
		+ "  console.error('[webmods] JVM hook error (' + hookName + '):', e);"
		+ "}"
		+ "return val;")
	private static native double jsDispatchNumericHook(String hookName, double val);

	@JSBody(params = {"tick", "hasPlayer", "doStart", "doPlayer", "doEnd"}, script = ""
		+ "var rt = window.__eaglerModsInternal;"
		+ "if (rt && rt.liveState) { rt.liveState.tick = tick; }"
		+ "var bridge = window.EaglerJvmBridge;"
		+ "if (bridge && typeof bridge.prepareTick === 'function') {"
		+ "  try { bridge.prepareTick(tick, rt ? rt.liveState : null); } catch (e) {}"
		+ "}"
		+ "var t = window.EaglerJvmTranslator;"
		+ "if (!t || typeof t.dispatchHook !== 'function') { return; }"
		+ "var ctx = window.__eaglerJvmTickCtx;"
		+ "if (!ctx) { ctx = { tick: 0, hasPlayer: false, state: null, cancelled: false }; window.__eaglerJvmTickCtx = ctx; }"
		+ "ctx.tick = tick; ctx.hasPlayer = !!hasPlayer; ctx.state = rt ? rt.liveState : null; ctx.cancelled = false;"
		+ "if (doStart) {"
		+ "  try { t.dispatchHook('tickStart', ctx); } catch (e) { console.error('[webmods] JVM tickStart error:', e); }"
		+ "}"
		+ "if (hasPlayer && doPlayer) {"
		+ "  try { t.dispatchHook('playerTick', ctx); } catch (e) { console.error('[webmods] JVM playerTick error:', e); }"
		+ "}"
		+ "if (doEnd) {"
		+ "  try { t.dispatchHook('tickEnd', ctx); } catch (e) { console.error('[webmods] JVM tickEnd error:', e); }"
		+ "}")
	private static native void jsEmitJvmTick(double tick, boolean hasPlayer, boolean doStart, boolean doPlayer, boolean doEnd);

	@JSBody(params = {"guiWidth", "guiHeight", "guiScale", "partialTick", "screenOpen", "inWorld", "paused"}, script = ""
		+ "var rt = window.__eaglerModsInternal;"
		+ "var ctx = window.__eaglerJvmHudCtx;"
		+ "if (!ctx) { ctx = { rt: null, state: null, guiWidth: 0, guiHeight: 0, guiScale: 1, partialTick: 1, tickDelta: 1, screenOpen: false, inWorld: false, paused: false, guiGraphics: null }; window.__eaglerJvmHudCtx = ctx; }"
		+ "ctx.guiWidth = guiWidth; ctx.guiHeight = guiHeight; ctx.guiScale = guiScale;"
		+ "ctx.partialTick = partialTick; ctx.tickDelta = partialTick;"
		+ "ctx.screenOpen = !!screenOpen; ctx.inWorld = !!inWorld; ctx.paused = !!paused;"
		+ "ctx.state = rt ? rt.liveState : null;"
		+ "var bridge = window.EaglerJvmBridge;"
		+ "var t = window.EaglerJvmTranslator;"
		+ "ctx.rt = (t && typeof t.getSharedRuntime === 'function') ? t.getSharedRuntime() : null;"
		+ "if (bridge && typeof bridge.renderHudOverlay === 'function' && bridge.renderHudOverlayDispatchesHud) {"
		+ "  try { bridge.renderHudOverlay(ctx); } catch (e) { console.error('[webmods] renderHudOverlay error:', e); }"
		+ "  return;"
		+ "}"
		+ "if (bridge && typeof bridge.beginHudFrame === 'function') {"
		+ "  try { bridge.beginHudFrame(ctx); } catch (e) { console.error('[webmods] beginHudFrame error:', e); }"
		+ "}"
		+ "if (bridge && typeof bridge.createGuiGraphics === 'function') {"
		+ "  try { ctx.guiGraphics = bridge.createGuiGraphics(ctx); } catch (e) {}"
		+ "}"
		+ "if (t && typeof t.dispatchHook === 'function') {"
		+ "  try { t.dispatchHook('hudRender', ctx); } catch (e) { console.error('[webmods] JVM hudRender error:', e); }"
		+ "}"
		+ "if (bridge && typeof bridge.renderHudOverlay === 'function') {"
		+ "  try { bridge.renderHudOverlay(ctx); } catch (e) { console.error('[webmods] renderHudOverlay error:', e); }"
		+ "}")
	private static native void jsEmitJvmHudRender(int guiWidth, int guiHeight, int guiScale, float partialTick, boolean screenOpen, boolean inWorld, boolean paused);

	@JSBody(params = {"screen", "prev", "title"}, script = ""
		+ "var bridge = window.EaglerJvmBridge;"
		+ "if (bridge && typeof bridge.invalidateStateCache === 'function') { try { bridge.invalidateStateCache(); } catch (e) {} }"
		+ "var t = window.EaglerJvmTranslator;"
		+ "if (!t || typeof t.dispatchHook !== 'function') { return; }"
		+ "var screenObj = (bridge && typeof bridge.makeScreenObj === 'function') ? bridge.makeScreenObj(screen || null, title || '') : null;"
		+ "try { t.dispatchHook('screenInit', { screen: screen || null, screenName: screen || '', prev: prev || null, prevScreen: prev || null, title: title || '', screenObj: screenObj }); }"
		+ "catch (e) { console.error('[webmods] JVM screenInit error:', e); }")
	private static native void jsDispatchJvmScreenInit(String screen, String prev, String title);

	@JSBody(params = {"dim"}, script = ""
		+ "var bridge = window.EaglerJvmBridge;"
		+ "if (bridge && typeof bridge.invalidateStateCache === 'function') { try { bridge.invalidateStateCache(); } catch (e) {} }"
		+ "var t = window.EaglerJvmTranslator;"
		+ "if (!t || typeof t.dispatchHook !== 'function') { return; }"
		+ "try { t.dispatchHook('worldJoin', { dimension: dim || '' }); }"
		+ "catch (e) { console.error('[webmods] JVM worldJoin error:', e); }")
	private static native void jsDispatchJvmWorldJoin(String dim);

	@JSBody(script = ""
		+ "var bridge = window.EaglerJvmBridge;"
		+ "if (bridge && typeof bridge.invalidateStateCache === 'function') { try { bridge.invalidateStateCache(); } catch (e) {} }"
		+ "var t = window.EaglerJvmTranslator;"
		+ "if (!t || typeof t.dispatchHook !== 'function') { return; }"
		+ "try { t.dispatchHook('worldLeave', {}); }"
		+ "catch (e) { console.error('[webmods] JVM worldLeave error:', e); }")
	private static native void jsDispatchJvmWorldLeave();

	@JSBody(params = {"ev"}, script = "var rt = window.__eaglerModsInternal; return !!(rt && rt.hasEventListeners(ev));")
	private static native boolean jsHasEventListeners(String ev);

	@JSBody(params = {"tick"}, script = "var rt = window.__eaglerModsInternal; if (rt) { rt.emitTick(tick); }")
	private static native void jsEmitTick(double tick);

	@JSBody(params = {"frame", "nowMs", "dtMs", "fps", "screen", "inWorld"},
		script = "var rt = window.__eaglerModsInternal; if (rt) { rt.emitFrame(frame, nowMs, dtMs, fps, screen, inWorld); }")
	private static native void jsEmitFrame(double frame, double nowMs, double dtMs, int fps, String screen, boolean inWorld);

	@JSBody(params = {"next", "prev", "title"},
		script = "var rt = window.__eaglerModsInternal; if (rt) { rt.emitScreen(next, prev, title); }")
	private static native void jsEmitScreenChange(String next, String prev, String title);

	@JSBody(params = {"dim"}, script = "var rt = window.__eaglerModsInternal; if (rt) { rt.emitWorldJoin(dim); }")
	private static native void jsEmitWorldJoin(String dim);

	@JSBody(script = "var rt = window.__eaglerModsInternal; if (rt) { rt.emitWorldLeave(); }")
	private static native void jsEmitWorldLeave();

	@JSBody(params = {"file"}, script = "var rt = window.__eaglerModsInternal; if (rt) { rt.unloadMod(file); }")
	private static native void jsUnloadMod(String file);

	@JSBody(params = {"file", "id", "name", "version", "source"},
		script = "var rt = window.__eaglerModsInternal; return rt ? rt.evalMod(file, id, name, version, source) : 'runtime not installed';")
	private static native String jsEvalMod(String file, String id, String name, String version, String source);

	@JSBody(params = {"fnGetGamma", "fnSetGamma", "fnGetFov", "fnSetFov", "fnSetSprinting", "fnSetRotation", "fnReloadNow", "fnBuildProfile"},
		script = ""
		+ "var api = window.EaglerMods;"
		+ "if (!api) { return; }"
		+ "api.reloadMods = api.reload;"
		+ "api.reload = function() { fnReloadNow(); };"
		+ "api.beginBatch = function() { if (window.__mcWebModsBeginBatch) { window.__mcWebModsBeginBatch(); } };"
		+ "api.endBatch = function() { if (window.__mcWebModsEndBatch) { window.__mcWebModsEndBatch(); } };"
		+ "api.getGamma = function() { return fnGetGamma(); };"
		+ "api.setGamma = function(val) { var n = Number(val); return isNaN(n) ? fnGetGamma() : fnSetGamma(n); };"
		+ "api.getFov = function() { return fnGetFov(); };"
		+ "api.setFov = function(val) { var n = Number(val); return isNaN(n) ? fnGetFov() : fnSetFov(Math.round(n) | 0); };"
		+ "api.setSprinting = function(val) { return !!fnSetSprinting(!!val); };"
		+ "api.setRotation = function(yaw, pitch) {"
		+ "  var y = (yaw == null) ? NaN : Number(yaw); var p = (pitch == null) ? NaN : Number(pitch);"
		+ "  if (isFinite(y) || isFinite(p)) { fnSetRotation(isFinite(y) ? y : NaN, isFinite(p) ? p : NaN); }"
		+ "};"
		+ "api.getJvmReport = function(key) {"
		+ "  var t = window.EaglerJvmTranslator;"
		+ "  return (t && typeof t.getModReport === 'function') ? t.getModReport(key) : null;"
		+ "};"
		+ "api.buildProfile = function(profile) { fnBuildProfile(profile == null ? '' : String(profile)); };"
		+ "api.getBuilderStatus = function() {"
		+ "  var b = window.__eaglerModBuilder;"
		+ "  return b ? b.getStatusSnapshot() : { connected: false, building: false, readyForReload: false, state: 'idle', status: '', progress: 0, elapsedSec: 0, error: '' };"
		+ "};")
	private static native void installExtendedJsApi(
			DoubleSupplierJs fnGetGamma,
			DoubleToDouble fnSetGamma,
			IntSupplierJs fnGetFov,
			IntToInt fnSetFov,
			BoolToBool fnSetSprinting,
			TwoDoubleAction fnSetRotation,
			VoidAction fnReloadNow,
			OneStringAction fnBuildProfile);

	@JSBody(params = {
			"fnToast", "fnIsSafeAutoReload", "fnIsInWorld", "fnReloadNow",
			"fnFlushSaves", "fnBootedProfile", "fnSelectedProfile"},
		script = ""
		+ "var state = {"
		+ "  connected: false, probed: false, baseUrl: '', building: false, readyForReload: false,"
		+ "  buildState: 'idle', statusText: '', activeProfile: 'dropped', jobId: '', stage: '',"
		+ "  progress: 0, elapsedSec: 0, lastError: '', lastToastStage: '', rev: 0,"
		+ "  fileStates: {}, fileErrors: {}, profileMods: {},"
		+ "  pollTimer: null, pollFailures: 0, autoReloadTimer: null, autoHideTimer: null, probePromise: null"
		+ "};"
		+ "function bump() { state.rev = (state.rev + 1) | 0; }"
		+ "function lsGetSafe(k) { try { return window.localStorage.getItem(k); } catch (e) { return null; } }"
		+ "function lsSetSafe(k, v) { try { if (!v) { window.localStorage.removeItem(k); } else { window.localStorage.setItem(k, v); } } catch (e) {} }"
		+ "function trimSlash(u) { return String(u || '').replace(/\\/+$/, ''); }"
		+ "function shortText(s, max) { var t = String(s == null ? '' : s); return t.length > max ? (t.substring(0, max - 3) + '...') : t; }"
		+ "function ensureMcToastStyles() {"
		+ "  if (document.getElementById('eagler-mod-build-style')) { return; }"
		+ "  var st = document.createElement('style');"
		+ "  st.id = 'eagler-mod-build-style';"
		+ "  st.textContent = ''"
		+ "    + '#eagler-mod-build-toast, #eagler-mod-build-pill {'"
		+ "    + '  position: fixed; top: 10px; right: 10px; z-index: 1250; box-sizing: border-box;'"
		+ "    + '  width: min(328px, calc(100vw - 20px)); padding: 8px 10px; border-radius: 0;'"
		+ "    + '  background: rgba(16, 16, 16, 0.93); border: 2px solid #000000;'"
		+ "    + '  box-shadow: inset 2px 2px 0 #6b6b6b, inset -2px -2px 0 #373737;'"
		+ "    + '  color: #ffffff; font-family: var(--mc-font, \"Minecraft\", \"Minecraftia\", ui-monospace, Menlo, Consolas, monospace);'"
		+ "    + '  font-size: 12px; line-height: 1.35; text-shadow: 1px 1px 0 #000000;'"
		+ "    + '  pointer-events: none; user-select: none; -webkit-user-select: none; image-rendering: pixelated;'"
		+ "    + '}'"
		+ "    + '#eagler-mod-build-toast.mc-ready, #eagler-mod-build-pill.mc-ready { box-shadow: inset 2px 2px 0 #55ff55, inset -2px -2px 0 #1f6f1f; }'"
		+ "    + '#eagler-mod-build-toast.mc-error, #eagler-mod-build-pill.mc-error { box-shadow: inset 2px 2px 0 #ff5555, inset -2px -2px 0 #6f1f1f; }'"
		+ "    + '#eagler-mod-build-toast.mc-building, #eagler-mod-build-pill.mc-building { box-shadow: inset 2px 2px 0 #80ff20, inset -2px -2px 0 #3a5c1a; }'"
		+ "    + '#eagler-mod-build-toast .mc-hdr, #eagler-mod-build-pill .mc-hdr { display: flex; align-items: center; justify-content: space-between; gap: 6px; }'"
		+ "    + '#eagler-mod-build-toast .mc-title, #eagler-mod-build-pill .mc-title { font-size: 13px; color: #ffff55; text-shadow: 2px 2px 0 #3f3f15; }'"
		+ "    + '#eagler-mod-build-toast.mc-ready .mc-title, #eagler-mod-build-pill.mc-ready .mc-title { color: #55ff55; text-shadow: 2px 2px 0 #153f15; }'"
		+ "    + '#eagler-mod-build-toast.mc-error .mc-title, #eagler-mod-build-pill.mc-error .mc-title { color: #ff5555; text-shadow: 2px 2px 0 #3f1515; }'"
		+ "    + '#eagler-mod-build-toast.mc-offline .mc-title, #eagler-mod-build-pill.mc-offline .mc-title { color: #ffaa00; text-shadow: 2px 2px 0 #3f2a00; }'"
		+ "    + '#eagler-mod-build-toast .mc-msg, #eagler-mod-build-pill .mc-msg { margin-top: 4px; color: #e8e8e8; word-break: break-word; }'"
		+ "    + '#eagler-mod-build-toast .mc-track, #eagler-mod-build-pill .mc-track {'"
		+ "    + '  margin-top: 6px; height: 10px; box-sizing: border-box; padding: 1px; border-radius: 0;'"
		+ "    + '  background: #181818; border: 2px solid #000000; box-shadow: inset 1px 1px 0 #333333;'"
		+ "    + '}'"
		+ "    + '#eagler-mod-build-toast .mc-fill, #eagler-mod-build-pill .mc-fill {'"
		+ "    + '  height: 100%; width: 0%; border-radius: 0;'"
		+ "    + '  background: linear-gradient(to bottom, #80ff20 0%, #55ff55 60%, #34a81a 100%);'"
		+ "    + '  transition: width 0.25s steps(20, end);'"
		+ "    + '}'"
		+ "    + '#eagler-mod-build-toast .mc-actions, #eagler-mod-build-pill .mc-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 7px; }'"
		+ "    + '#eagler-mod-build-toast .mc-btn, #eagler-mod-build-pill .mc-btn {'"
		+ "    + '  pointer-events: auto; cursor: pointer; border-radius: 0;'"
		+ "    + '  font-family: inherit; font-size: 12px; line-height: 1; padding: 5px 10px 6px;'"
		+ "    + '  color: #ffffff; text-shadow: 2px 2px 0 #3f3f3f; background: #6f6f6f;'"
		+ "    + '  border: 2px solid #000000; box-shadow: inset 2px 2px 0 #a9a9a9, inset -2px -3px 0 #4a4a4a;'"
		+ "    + '}'"
		+ "    + '#eagler-mod-build-toast .mc-btn:hover, #eagler-mod-build-pill .mc-btn:hover { border-color: #ffffff; background: #7e8a9c; color: #ffffa0; }'"
		+ "    + '#eagler-mod-build-toast .mc-btn:active, #eagler-mod-build-pill .mc-btn:active { box-shadow: inset 2px 2px 0 #4a4a4a, inset -2px -2px 0 #a9a9a9; }'"
		+ "    + '#eagler-mod-build-toast .mc-close, #eagler-mod-build-pill .mc-close { padding: 2px 6px 3px; font-size: 11px; }';"
		+ "  (document.head || document.documentElement).appendChild(st);"
		+ "}"
		+ "function clearAutoHide() {"
		+ "  if (state.autoHideTimer) { clearTimeout(state.autoHideTimer); state.autoHideTimer = null; }"
		+ "}"
		+ "function cancelAutoReload() {"
		+ "  if (state.autoReloadTimer) { clearTimeout(state.autoReloadTimer); state.autoReloadTimer = null; }"
		+ "}"
		+ "function hideToastEl() {"
		+ "  clearAutoHide();"
		+ "  var el = document.getElementById('eagler-mod-build-toast');"
		+ "  if (el && el.parentNode) { el.parentNode.removeChild(el); }"
		+ "}"
		+ "function ensureToastEl() {"
		+ "  ensureMcToastStyles();"
		+ "  var el = document.getElementById('eagler-mod-build-toast');"
		+ "  if (!el) {"
		+ "    el = document.createElement('div');"
		+ "    el.id = 'eagler-mod-build-toast';"
		+ "    el.setAttribute('role', 'status');"
		+ "    document.body.appendChild(el);"
		+ "  }"
		+ "  return el;"
		+ "}"
		+ "function renderMcToast(mode, title, detail, showBar, pct, buttons, dismissible, autoHideMs) {"
		+ "  clearAutoHide();"
		+ "  var el = ensureToastEl();"
		+ "  el.className = 'mc-' + mode;"
		+ "  while (el.firstChild) { el.removeChild(el.firstChild); }"
		+ "  var hdr = document.createElement('div');"
		+ "  hdr.className = 'mc-hdr';"
		+ "  var tNode = document.createElement('div');"
		+ "  tNode.className = 'mc-title';"
		+ "  tNode.textContent = title;"
		+ "  hdr.appendChild(tNode);"
		+ "  if (dismissible) {"
		+ "    var xBtn = document.createElement('button');"
		+ "    xBtn.type = 'button';"
		+ "    xBtn.className = 'mc-btn mc-close';"
		+ "    xBtn.textContent = 'X';"
		+ "    xBtn.onclick = function() { cancelAutoReload(); hideToastEl(); };"
		+ "    hdr.appendChild(xBtn);"
		+ "  }"
		+ "  el.appendChild(hdr);"
		+ "  if (detail) {"
		+ "    var mNode = document.createElement('div');"
		+ "    mNode.className = 'mc-msg';"
		+ "    mNode.textContent = detail;"
		+ "    el.appendChild(mNode);"
		+ "  }"
		+ "  if (showBar) {"
		+ "    var track = document.createElement('div');"
		+ "    track.className = 'mc-track';"
		+ "    var fill = document.createElement('div');"
		+ "    fill.className = 'mc-fill';"
		+ "    var clamped = Math.max(0, Math.min(100, pct | 0));"
		+ "    fill.style.width = clamped + '%';"
		+ "    track.appendChild(fill);"
		+ "    el.appendChild(track);"
		+ "  }"
		+ "  if (buttons && buttons.length) {"
		+ "    var row = document.createElement('div');"
		+ "    row.className = 'mc-actions';"
		+ "    for (var i = 0; i < buttons.length; i++) {"
		+ "      var spec = buttons[i];"
		+ "      var btn = document.createElement('button');"
		+ "      btn.type = 'button';"
		+ "      btn.className = 'mc-btn';"
		+ "      btn.textContent = spec.label;"
		+ "      btn.onclick = spec.onClick;"
		+ "      row.appendChild(btn);"
		+ "    }"
		+ "    el.appendChild(row);"
		+ "  }"
		+ "  if (autoHideMs && autoHideMs > 0) {"
		+ "    state.autoHideTimer = setTimeout(function() { hideToastEl(); }, autoHideMs);"
		+ "  }"
		+ "}"
		+ "function normalizePct(val, fallback) {"
		+ "  if (typeof val !== 'number' || isNaN(val)) { return fallback; }"
		+ "  if (val > 0 && val < 1) { return Math.round(val * 100); }"
		+ "  return Math.max(0, Math.min(100, Math.round(val)));"
		+ "}"
		+ "function stageTitle(st, stage) {"
		+ "  if (stage && String(stage).trim()) { return String(stage).trim(); }"
		+ "  if (st === 'debouncing') { return 'Waiting for dropped mods...'; }"
		+ "  if (st === 'scanning') { return 'Scanning Fabric JARs...'; }"
		+ "  if (st === 'baking') { return 'Baking Fabric Mixins...'; }"
		+ "  if (st === 'linking') { return 'Linking WebAssembly...'; }"
		+ "  if (st === 'splitting') { return 'Optimizing WASM chunks...'; }"
		+ "  return 'Preparing mod engine...';"
		+ "}"
		+ "function extractReason(res) {"
		+ "  if (!res || typeof res !== 'object') { return ''; }"
		+ "  if (res.error && String(res.error).trim()) { return String(res.error).trim(); }"
		+ "  if (res.message && String(res.message).trim()) { return String(res.message).trim(); }"
		+ "  if (Array.isArray(res.issues) && res.issues.length) {"
		+ "    var first = res.issues[0];"
		+ "    if (typeof first === 'string' && first.trim()) { return first.trim(); }"
		+ "    if (first && typeof first === 'object') {"
		+ "      var m = first.message || first.reason || first.detail || first.summary;"
		+ "      if (m) { return String(m).trim(); }"
		+ "    }"
		+ "  }"
		+ "  return '';"
		+ "}"
		+ "function lastLogLine(logTail) {"
		+ "  if (!logTail) { return ''; }"
		+ "  var lines = Array.isArray(logTail) ? logTail : String(logTail).split(/\\r?\\n/);"
		+ "  for (var i = lines.length - 1; i >= 0; i--) {"
		+ "    var t = String(lines[i] || '').trim();"
		+ "    if (t) { return t; }"
		+ "  }"
		+ "  return '';"
		+ "}"
		+ "function recordProfileMods(profiles) {"
		+ "  if (!profiles) { return; }"
		+ "  var list = [];"
		+ "  if (Array.isArray(profiles)) { list = profiles; }"
		+ "  else if (typeof profiles === 'object') {"
		+ "    var keys = Object.keys(profiles);"
		+ "    for (var ki = 0; ki < keys.length; ki++) {"
		+ "      var entry = profiles[keys[ki]];"
		+ "      if (entry && typeof entry === 'object') { list.push(Object.assign({ id: entry.id || entry.name || keys[ki] }, entry)); }"
		+ "    }"
		+ "  }"
		+ "  if (!list.length) { return; }"
		+ "  var existing = Array.isArray(window.__eaglerModProfiles) ? window.__eaglerModProfiles.slice() : [];"
		+ "  for (var i = 0; i < list.length; i++) {"
		+ "    var item = list[i];"
		+ "    var pid = typeof item === 'string' ? item : (item && (item.id || item.name));"
		+ "    if (!pid) { continue; }"
		+ "    var modEntries = [];"
		+ "    var sources = [item && item.mods, item && item.files, item && item.source && item.source.mods, item && item.source && item.source.files];"
		+ "    for (var si = 0; si < sources.length; si++) {"
		+ "      var arr = sources[si];"
		+ "      if (!Array.isArray(arr)) { continue; }"
		+ "      for (var ai = 0; ai < arr.length; ai++) {"
		+ "        var m = arr[ai];"
		+ "        if (typeof m === 'string') { modEntries.push(m.toLowerCase()); }"
		+ "        else if (m && typeof m === 'object') {"
		+ "          if (m.filename) { modEntries.push(String(m.filename).toLowerCase()); }"
		+ "          if (m.file) { modEntries.push(String(m.file).toLowerCase()); }"
		+ "          if (m.id) { modEntries.push(String(m.id).toLowerCase()); }"
		+ "          if (m.modId) { modEntries.push(String(m.modId).toLowerCase()); }"
		+ "        }"
		+ "      }"
		+ "    }"
		+ "    state.profileMods[pid] = modEntries;"
		+ "    var found = false;"
		+ "    for (var ei = 0; ei < existing.length; ei++) {"
		+ "      var exId = typeof existing[ei] === 'string' ? existing[ei] : (existing[ei] && (existing[ei].id || existing[ei].name));"
		+ "      if (exId === pid) { found = true; break; }"
		+ "    }"
		+ "    if (!found) { existing.push(typeof item === 'object' ? item : { id: pid }); }"
		+ "  }"
		+ "  window.__eaglerModProfiles = existing;"
		+ "}"
		+ "function candidateBases() {"
		+ "  var configured = lsGetSafe('minecraft.modbuilder.url');"
		+ "  if (configured && configured.trim()) { return [trimSlash(configured.trim())]; }"
		+ "  var proto = String(window.location && window.location.protocol || '');"
		+ "  var fallback = 'http://127.0.0.1:8262';"
		+ "  if (proto === 'http:' || proto === 'https:') {"
		+ "    var origin = String(window.location.origin || '');"
		+ "    if (origin === fallback) { return ['']; }"
		+ "    return ['', fallback];"
		+ "  }"
		+ "  return [];"
		+ "}"
		+ "function probeCandidates(bases, idx) {"
		+ "  if (idx >= bases.length) {"
		+ "    state.connected = false; state.baseUrl = ''; state.probed = true; bump();"
		+ "    return Promise.resolve(null);"
		+ "  }"
		+ "  var base = bases[idx];"
		+ "  return fetch(base + '/api/mods/status', { method: 'GET', cache: 'no-store' }).then(function(resp) {"
		+ "    if (!resp.ok) { return probeCandidates(bases, idx + 1); }"
		+ "    return resp.json().then(function(data) {"
		+ "      if (!data || data.available === false) { return probeCandidates(bases, idx + 1); }"
		+ "      state.connected = true; state.baseUrl = base; state.probed = true;"
		+ "      if (data.profiles) { recordProfileMods(data.profiles); }"
		+ "      if (data.activeJob) {"
		+ "        if (typeof data.activeJob === 'object') { applyJobPayload(data.activeJob, false); }"
		+ "        else if (typeof data.activeJob === 'string') { startPolling(data.activeJob, true); }"
		+ "      } else if (data.busy) {"
		+ "        startPolling('latest', true);"
		+ "      }"
		+ "      bump();"
		+ "      return base;"
		+ "    }, function() { return probeCandidates(bases, idx + 1); });"
		+ "  }, function() { return probeCandidates(bases, idx + 1); });"
		+ "}"
		+ "function discoverBase(forceRetry) {"
		+ "  if (state.connected && !forceRetry) { return Promise.resolve(state.baseUrl); }"
		+ "  if (state.probePromise) { return state.probePromise; }"
		+ "  var p = probeCandidates(candidateBases(), 0).then(function(res) { state.probePromise = null; return res; });"
		+ "  state.probePromise = p;"
		+ "  return p;"
		+ "}"
		+ "function stopPolling() {"
		+ "  if (state.pollTimer) { clearTimeout(state.pollTimer); state.pollTimer = null; }"
		+ "  state.pollFailures = 0;"
		+ "}"
		+ "function showReadyState(title, detail, allowAutoCountdown, reloadLabelOverride) {"
		+ "  cancelAutoReload();"
		+ "  var inWorld = !!fnIsInWorld();"
		+ "  var btnLabel = reloadLabelOverride || (inWorld ? 'Save & Reload' : 'Reload Now');"
		+ "  var doReload = function() { cancelAutoReload(); fnReloadNow(); };"
		+ "  if (allowAutoCountdown && !inWorld && fnIsSafeAutoReload()) {"
		+ "    var countdownDetail = detail + ' (reloading in 1.5s...)';"
		+ "    renderMcToast('ready', title, countdownDetail, true, 100, ["
		+ "      { label: btnLabel, onClick: doReload },"
		+ "      { label: 'Cancel', onClick: function() { cancelAutoReload(); renderMcToast('ready', title, detail, true, 100, [{ label: btnLabel, onClick: doReload }], true, 0); } }"
		+ "    ], true, 0);"
		+ "    fnToast(title, shortText(countdownDetail, 96));"
		+ "    state.autoReloadTimer = setTimeout(function() {"
		+ "      state.autoReloadTimer = null;"
		+ "      if (fnIsSafeAutoReload()) { fnFlushSaves(); fnReloadNow(); }"
		+ "      else { renderMcToast('ready', title, detail, true, 100, [{ label: fnIsInWorld() ? 'Save & Reload' : btnLabel, onClick: doReload }], true, 0); }"
		+ "    }, 1500);"
		+ "    return;"
		+ "  }"
		+ "  renderMcToast('ready', title, detail, true, 100, [{ label: btnLabel, onClick: doReload }], true, 0);"
		+ "  fnToast(title, shortText(detail, 96));"
		+ "}"
		+ "function markBuildingFiles(newState, errMsg, jobMods) {"
		+ "  var keys = Object.keys(state.fileStates);"
		+ "  for (var i = 0; i < keys.length; i++) {"
		+ "    if (state.fileStates[keys[i]] === 'building') {"
		+ "      state.fileStates[keys[i]] = newState;"
		+ "      if (errMsg) { state.fileErrors[keys[i]] = errMsg; } else { delete state.fileErrors[keys[i]]; }"
		+ "    }"
		+ "  }"
		+ "  if (Array.isArray(jobMods)) {"
		+ "    for (var j = 0; j < jobMods.length; j++) {"
		+ "      var m = jobMods[j];"
		+ "      var fn = typeof m === 'string' ? m : (m && (m.filename || m.file));"
		+ "      if (fn) {"
		+ "        state.fileStates[fn] = newState;"
		+ "        if (errMsg) { state.fileErrors[fn] = errMsg; } else { delete state.fileErrors[fn]; }"
		+ "      }"
		+ "    }"
		+ "  }"
		+ "}"
		+ "function applyJobPayload(job, fromPoll) {"
		+ "  if (!job || typeof job !== 'object') { return; }"
		+ "  var jState = String(job.state || '').toLowerCase();"
		+ "  if (job.id) { state.jobId = String(job.id); }"
		+ "  if (job.profile) { state.activeProfile = String(job.profile); }"
		+ "  var pct = normalizePct(job.progress, state.progress);"
		+ "  var elapsed = typeof job.elapsedSec === 'number' && !isNaN(job.elapsedSec) ? Math.max(0, Math.round(job.elapsedSec)) : state.elapsedSec;"
		+ "  state.progress = pct; state.elapsedSec = elapsed; state.buildState = jState;"
		+ "  if (jState === 'ready') {"
		+ "    stopPolling();"
		+ "    var prof = state.activeProfile || 'dropped';"
		+ "    lsSetSafe('minecraft.modprofile.selected', prof);"
		+ "    state.building = false; state.readyForReload = true; state.progress = 100; state.lastError = '';"
		+ "    markBuildingFiles('ready', '', job.mods);"
		+ "    state.statusText = 'Mods ready! Click Reload Now to activate';"
		+ "    bump();"
		+ "    if (fromPoll || fnSelectedProfile() !== fnBootedProfile()) {"
		+ "      showReadyState('Mods ready!', fnIsInWorld() ? 'Baked engine ready. Save & Reload when ready.' : 'Baked engine ready. Click Reload Now to activate.', false, null);"
		+ "    }"
		+ "    return;"
		+ "  }"
		+ "  if (jState === 'failed') {"
		+ "    stopPolling();"
		+ "    var errMsg = (job.error && String(job.error).trim()) || lastLogLine(job.logTail) || 'Fabric WASM build failed';"
		+ "    state.building = false; state.readyForReload = false; state.lastError = errMsg;"
		+ "    markBuildingFiles('failed', errMsg, job.mods);"
		+ "    state.statusText = 'Build failed: ' + errMsg;"
		+ "    bump();"
		+ "    renderMcToast('error', 'Mod Build Failed', shortText(errMsg, 160), false, 0, ["
		+ "      { label: 'Retry', onClick: function() { triggerBuild(state.activeProfile || 'dropped'); } }"
		+ "    ], true, 0);"
		+ "    fnToast('Mod Build Failed', shortText(errMsg, 92));"
		+ "    return;"
		+ "  }"
		+ "  if (jState === 'debouncing' || jState === 'scanning' || jState === 'baking' || jState === 'linking' || jState === 'splitting' || jState === 'queued' || jState === 'building') {"
		+ "    var stageText = stageTitle(jState, job.stage);"
		+ "    state.building = true; state.readyForReload = false; state.stage = stageText;"
		+ "    var elapsedSuffix = elapsed > 0 ? (' (' + elapsed + 's)') : '';"
		+ "    state.statusText = 'Building mods (' + pct + '%): ' + stageText + elapsedSuffix;"
		+ "    markBuildingFiles('building', '', job.mods);"
		+ "    bump();"
		+ "    renderMcToast('building', 'Building Fabric Mods (' + pct + '%)', stageText + elapsedSuffix, true, pct, [], false, 0);"
		+ "    if (state.lastToastStage !== jState) {"
		+ "      state.lastToastStage = jState;"
		+ "      fnToast('Building Fabric Mods', shortText(stageText + ' (' + pct + '%)', 90));"
		+ "    }"
		+ "    if (!fromPoll) { startPolling(state.jobId || 'latest', false); }"
		+ "  }"
		+ "}"
		+ "function pollOnce() {"
		+ "  state.pollTimer = null;"
		+ "  if (!state.connected) { stopPolling(); return; }"
		+ "  var path = state.jobId ? ('/api/mods/jobs/' + encodeURIComponent(state.jobId)) : '/api/mods/jobs/latest';"
		+ "  fetch(state.baseUrl + path, { method: 'GET', cache: 'no-store' }).then(function(resp) {"
		+ "    if (!resp.ok) { throw new Error('HTTP ' + resp.status); }"
		+ "    return resp.json();"
		+ "  }).then(function(job) {"
		+ "    state.pollFailures = 0;"
		+ "    applyJobPayload(job, true);"
		+ "    if (state.building) { state.pollTimer = setTimeout(pollOnce, 2000); }"
		+ "  }, function() {"
		+ "    state.pollFailures++;"
		+ "    if (state.pollFailures >= 3) {"
		+ "      stopPolling();"
		+ "      state.connected = false; state.building = false; state.buildState = 'offline';"
		+ "      state.statusText = 'Lost connection to local mod builder';"
		+ "      bump();"
		+ "      renderMcToast('offline', 'Builder Offline', 'Lost connection to serve_minecraft_web.py.', false, 0, [], true, 7000);"
		+ "    } else {"
		+ "      state.pollTimer = setTimeout(pollOnce, 2000);"
		+ "    }"
		+ "  });"
		+ "}"
		+ "function startPolling(jobId, immediate) {"
		+ "  stopPolling();"
		+ "  state.jobId = (jobId && jobId !== 'latest') ? String(jobId) : '';"
		+ "  state.pollTimer = setTimeout(pollOnce, immediate ? 50 : 2000);"
		+ "}"
		+ "function handleOfflineImport(filename) {"
		+ "  state.connected = false; state.building = false; state.buildState = 'offline';"
		+ "  if (filename) { state.fileStates[filename] = 'offline'; }"
		+ "  state.statusText = 'Stored locally (run serve_minecraft_web.py to auto-build Fabric JARs)';"
		+ "  bump();"
		+ "  renderMcToast('offline', 'Fabric Mod Stored Locally', 'Run serve_minecraft_web.py to auto-build Fabric JARs. Assets/data active now.', false, 0, [], true, 8000);"
		+ "  fnToast('Stored locally', 'Run serve_minecraft_web.py to auto-build Fabric JARs.');"
		+ "}"
		+ "function importFabricJar(filename, displayTitle, int8Data) {"
		+ "  cancelAutoReload();"
		+ "  var payload = new Uint8Array(int8Data.buffer.slice(int8Data.byteOffset, int8Data.byteOffset + int8Data.byteLength));"
		+ "  state.fileStates[filename] = 'building';"
		+ "  delete state.fileErrors[filename];"
		+ "  state.building = true; state.readyForReload = false; state.buildState = 'uploading'; state.lastToastStage = '';"
		+ "  state.progress = 5; state.elapsedSec = 0;"
		+ "  state.statusText = 'Preparing ' + displayTitle + '...';"
		+ "  bump();"
		+ "  renderMcToast('building', 'Installing Fabric Mod', displayTitle + ' - preparing engine...', true, 5, [], false, 0);"
		+ "  discoverBase(!state.connected).then(function(base) {"
		+ "    if (base === null) { handleOfflineImport(filename); return; }"
		+ "    var url = base + '/api/mods/import?filename=' + encodeURIComponent(filename) + '&profile=dropped&autobuild=1&side=client';"
		+ "    return fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: payload }).then(function(resp) {"
		+ "      return resp.json().then(function(res) { return { ok: resp.ok, status: resp.status, data: res }; }, function() { return { ok: resp.ok, status: resp.status, data: null }; });"
		+ "    }).then(function(out) {"
		+ "      var res = out.data;"
		+ "      if (!res) { throw new Error('HTTP ' + out.status); }"
		+ "      var st = String(res.state || (res.ok ? 'queued' : 'failed')).toLowerCase();"
		+ "      var prof = String(res.profile || 'dropped');"
		+ "      var reason = extractReason(res);"
		+ "      state.activeProfile = prof; state.buildState = st;"
		+ "      if (st === 'ready') {"
		+ "        stopPolling();"
		+ "        lsSetSafe('minecraft.modprofile.selected', prof);"
		+ "        state.building = false; state.readyForReload = true; state.progress = 100; state.lastError = '';"
		+ "        state.fileStates[filename] = 'ready'; delete state.fileErrors[filename];"
		+ "        var readyDetail = res.cached ? (displayTitle + ' (cached build ready)') : (displayTitle + ' is ready to load');"
		+ "        state.statusText = 'Mod ready! ' + readyDetail;"
		+ "        bump();"
		+ "        showReadyState('Mod ready!', readyDetail, true, null);"
		+ "      } else if (st === 'queued' || st === 'building' || st === 'debouncing' || st === 'scanning' || st === 'baking' || st === 'linking' || st === 'splitting') {"
		+ "        state.building = true; state.readyForReload = false; state.fileStates[filename] = 'building';"
		+ "        var extra = (Array.isArray(res.fetchedDeps) && res.fetchedDeps.length) ? (' (+ ' + res.fetchedDeps.join(', ') + ')') : '';"
		+ "        var bMsg = (reason || ('Building ' + displayTitle + '...')) + extra;"
		+ "        state.statusText = bMsg; state.progress = Math.max(state.progress, 10);"
		+ "        bump();"
		+ "        renderMcToast('building', 'Building Fabric Mods', bMsg, true, state.progress, [], false, 0);"
		+ "        startPolling(res.jobId || 'latest', false);"
		+ "      } else if (st === 'blocked' || st === 'conflict') {"
		+ "        stopPolling();"
		+ "        var blockReason = reason || (st === 'conflict' ? 'Mixin conflict with browser runtime' : 'Mod is incompatible with browser runtime');"
		+ "        state.building = false; state.readyForReload = false; state.lastError = blockReason;"
		+ "        state.fileStates[filename] = st; state.fileErrors[filename] = blockReason;"
		+ "        state.statusText = blockReason;"
		+ "        bump();"
		+ "        var errTitle = st === 'conflict' ? 'Mixin Conflict' : 'Mod Incompatible';"
		+ "        renderMcToast('error', errTitle, shortText(blockReason, 180), false, 0, [], true, 0);"
		+ "        fnToast(errTitle, shortText(blockReason, 92));"
		+ "      } else if (st === 'runtime_only') {"
		+ "        state.building = false; delete state.fileStates[filename];"
		+ "        state.statusText = reason || (displayTitle + ' runs directly in browser');"
		+ "        bump();"
		+ "        if (!state.readyForReload) { hideToastEl(); }"
		+ "      } else {"
		+ "        stopPolling();"
		+ "        var failMsg = reason || ('Import failed (HTTP ' + out.status + ')');"
		+ "        state.building = false; state.buildState = 'failed'; state.lastError = failMsg;"
		+ "        state.fileStates[filename] = 'failed'; state.fileErrors[filename] = failMsg;"
		+ "        state.statusText = 'Build error: ' + failMsg;"
		+ "        bump();"
		+ "        renderMcToast('error', 'Mod Build Failed', shortText(failMsg, 160), false, 0, ["
		+ "          { label: 'Retry', onClick: function() { triggerBuild(prof); } }"
		+ "        ], true, 0);"
		+ "        fnToast('Mod Build Failed', shortText(failMsg, 92));"
		+ "      }"
		+ "    });"
		+ "  }).catch(function() { handleOfflineImport(filename); });"
		+ "}"
		+ "function removeFabricJar(filename) {"
		+ "  cancelAutoReload();"
		+ "  delete state.fileStates[filename]; delete state.fileErrors[filename];"
		+ "  bump();"
		+ "  discoverBase(false).then(function(base) {"
		+ "    if (base === null) { return; }"
		+ "    return fetch(base + '/api/mods/remove', {"
		+ "      method: 'POST',"
		+ "      headers: { 'Content-Type': 'application/json' },"
		+ "      body: JSON.stringify({ profile: 'dropped', filename: filename, autobuild: true })"
		+ "    }).then(function(resp) { return resp.json(); }).then(function(res) {"
		+ "      if (!res) { return; }"
		+ "      var st = String(res.state || '').toLowerCase();"
		+ "      if (st === 'vanilla' || res.remainingCodeMods === 0) {"
		+ "        stopPolling();"
		+ "        lsSetSafe('minecraft.modprofile.selected', '');"
		+ "        state.building = false; state.buildState = 'vanilla';"
		+ "        if (fnBootedProfile()) {"
		+ "          state.readyForReload = true;"
		+ "          state.statusText = 'All Fabric mods removed - reload to return to Vanilla';"
		+ "          bump();"
		+ "          showReadyState('Switched to Vanilla', 'All Fabric mods removed. Reload to boot Vanilla.', false, fnIsInWorld() ? 'Save & Reload' : 'Reload to Vanilla');"
		+ "        } else {"
		+ "          state.readyForReload = false; state.buildState = 'idle'; state.statusText = '';"
		+ "          bump(); hideToastEl();"
		+ "        }"
		+ "      } else if (st === 'ready') {"
		+ "        stopPolling();"
		+ "        lsSetSafe('minecraft.modprofile.selected', String(res.profile || 'dropped'));"
		+ "        state.building = false; state.readyForReload = true; state.buildState = 'ready';"
		+ "        state.statusText = extractReason(res) || 'Updated mod engine ready! Click Reload Now';"
		+ "        bump();"
		+ "        showReadyState('Mods updated!', state.statusText, false, null);"
		+ "      } else if (st === 'queued' || st === 'building') {"
		+ "        state.building = true; state.readyForReload = false; state.buildState = st; state.progress = 10;"
		+ "        state.statusText = extractReason(res) || 'Rebuilding remaining Fabric mods...';"
		+ "        bump();"
		+ "        renderMcToast('building', 'Rebuilding Fabric Mods', state.statusText, true, 10, [], false, 0);"
		+ "        startPolling(res.jobId || 'latest', false);"
		+ "      }"
		+ "    });"
		+ "  }).catch(function() {});"
		+ "}"
		+ "function triggerBuild(profile) {"
		+ "  cancelAutoReload();"
		+ "  var prof = profile || 'dropped';"
		+ "  state.building = true; state.readyForReload = false; state.buildState = 'queued'; state.lastToastStage = ''; state.progress = 5;"
		+ "  state.statusText = 'Starting Fabric build (' + prof + ')...';"
		+ "  bump();"
		+ "  renderMcToast('building', 'Building Fabric Mods', state.statusText, true, 5, [], false, 0);"
		+ "  discoverBase(true).then(function(base) {"
		+ "    if (base === null) { handleOfflineImport(''); return; }"
		+ "    return fetch(base + '/api/mods/build', {"
		+ "      method: 'POST',"
		+ "      headers: { 'Content-Type': 'application/json' },"
		+ "      body: JSON.stringify({ profile: prof, side: 'client' })"
		+ "    }).then(function(resp) { return resp.json(); }).then(function(res) {"
		+ "      if (!res) { throw new Error('Empty response'); }"
		+ "      var st = String(res.state || (res.ok ? 'queued' : 'failed')).toLowerCase();"
		+ "      var reason = extractReason(res);"
		+ "      state.activeProfile = String(res.profile || prof); state.buildState = st;"
		+ "      if (st === 'ready') {"
		+ "        stopPolling();"
		+ "        lsSetSafe('minecraft.modprofile.selected', state.activeProfile);"
		+ "        state.building = false; state.readyForReload = true; state.progress = 100;"
		+ "        state.statusText = reason || 'Mods ready! Click Reload Now';"
		+ "        bump();"
		+ "        showReadyState('Mods ready!', state.statusText, false, null);"
		+ "      } else if (st === 'queued' || st === 'building') {"
		+ "        state.building = true; state.statusText = reason || 'Building Fabric mods...';"
		+ "        bump();"
		+ "        startPolling(res.jobId || 'latest', false);"
		+ "      } else {"
		+ "        stopPolling();"
		+ "        var msg = reason || 'Build failed';"
		+ "        state.building = false; state.lastError = msg; state.statusText = msg;"
		+ "        bump();"
		+ "        renderMcToast('error', 'Mod Build Failed', shortText(msg, 160), false, 0, [{ label: 'Retry', onClick: function() { triggerBuild(prof); } }], true, 0);"
		+ "        fnToast('Mod Build Failed', shortText(msg, 92));"
		+ "      }"
		+ "    });"
		+ "  }).catch(function() { handleOfflineImport(''); });"
		+ "}"
		+ "function isModInProfile(profileName, filename, modId) {"
		+ "  if (!profileName) { return false; }"
		+ "  var fLow = String(filename || '').toLowerCase();"
		+ "  var idLow = String(modId || '').toLowerCase();"
		+ "  var arr = state.profileMods[profileName];"
		+ "  if (Array.isArray(arr)) {"
		+ "    for (var i = 0; i < arr.length; i++) {"
		+ "      if ((fLow && arr[i] === fLow) || (idLow && arr[i] === idLow)) { return true; }"
		+ "    }"
		+ "  }"
		+ "  var wArr = window.__eaglerModProfiles;"
		+ "  if (Array.isArray(wArr)) {"
		+ "    for (var wi = 0; wi < wArr.length; wi++) {"
		+ "      var pe = wArr[wi];"
		+ "      var pid = typeof pe === 'string' ? pe : (pe && (pe.id || pe.name));"
		+ "      if (pid !== profileName || !pe || typeof pe !== 'object') { continue; }"
		+ "      var modsArr = pe.mods || pe.files;"
		+ "      if (Array.isArray(modsArr)) {"
		+ "        for (var mi = 0; mi < modsArr.length; mi++) {"
		+ "          var entry = modsArr[mi];"
		+ "          if (typeof entry === 'string') {"
		+ "            var elow = entry.toLowerCase();"
		+ "            if ((fLow && elow === fLow) || (idLow && elow === idLow)) { return true; }"
		+ "          } else if (entry && typeof entry === 'object') {"
		+ "            var ef = String(entry.filename || entry.file || '').toLowerCase();"
		+ "            var eid = String(entry.id || entry.modId || '').toLowerCase();"
		+ "            if ((fLow && ef === fLow) || (idLow && eid === idLow)) { return true; }"
		+ "          }"
		+ "        }"
		+ "      }"
		+ "    }"
		+ "  }"
		+ "  return false;"
		+ "}"
		+ "window.__eaglerModBuilder = {"
		+ "  state: state,"
		+ "  probe: function() { setTimeout(function() { discoverBase(true); }, 0); },"
		+ "  importJar: importFabricJar,"
		+ "  removeJar: removeFabricJar,"
		+ "  triggerBuild: triggerBuild,"
		+ "  isModInProfile: isModInProfile,"
		+ "  setSavingState: function() {"
		+ "    cancelAutoReload();"
		+ "    state.statusText = 'Saving world before reloading...';"
		+ "    bump();"
		+ "    renderMcToast('building', 'Saving World...', 'Flushing saves to browser storage before reload.', true, 100, [], false, 0);"
		+ "  },"
		+ "  getStatusSnapshot: function() {"
		+ "    var sel = fnSelectedProfile(); var booted = fnBootedProfile();"
		+ "    return {"
		+ "      connected: !!state.connected, baseUrl: state.baseUrl || '', building: !!state.building,"
		+ "      readyForReload: !!(state.readyForReload || sel !== booted), state: state.buildState,"
		+ "      status: state.statusText, profile: state.activeProfile, bootedProfile: booted || 'vanilla',"
		+ "      selectedProfile: sel || 'vanilla', needsReload: sel !== booted, jobId: state.jobId,"
		+ "      stage: state.stage, progress: state.progress, elapsedSec: state.elapsedSec, error: state.lastError"
		+ "    };"
		+ "  }"
		+ "};")
	private static native void installBuilderBridge(
			TwoStringAction fnToast,
			BooleanSupplierJs fnIsSafeAutoReload,
			BooleanSupplierJs fnIsInWorld,
			VoidAction fnReloadNow,
			VoidAction fnFlushSaves,
			StringSupplier fnBootedProfile,
			StringSupplier fnSelectedProfile);

	@JSBody(script = "var b = window.__eaglerModBuilder; if (b) { b.probe(); }")
	private static native void jsProbeBuilderStatus();

	@JSBody(params = {"filename", "displayTitle", "data"},
		script = "var b = window.__eaglerModBuilder; if (b) { b.importJar(filename, displayTitle, data); }")
	private static native void jsImportFabricJar(String filename, String displayTitle, Int8Array data);

	@JSBody(params = {"filename"},
		script = "var b = window.__eaglerModBuilder; if (b) { b.removeJar(filename); }")
	private static native void jsRemoveFabricJar(String filename);

	@JSBody(params = {"profile"},
		script = "var b = window.__eaglerModBuilder; if (b) { b.triggerBuild(profile); }")
	private static native void jsTriggerBuild(String profile);

	@JSBody(script = "var b = window.__eaglerModBuilder; return b ? (b.state.rev | 0) : 0;")
	private static native int jsBuilderRev();

	@JSBody(script = "var b = window.__eaglerModBuilder; return b ? (b.state.statusText || '') : '';")
	private static native String jsGetBuildStatus();

	@JSBody(script = "var b = window.__eaglerModBuilder; return !!(b && b.state.building);")
	private static native boolean jsIsBuilding();

	@JSBody(script = "var b = window.__eaglerModBuilder; return !!(b && b.state.readyForReload);")
	private static native boolean jsIsBuildReadyForReload();

	@JSBody(script = "var b = window.__eaglerModBuilder; return !!(b && b.state.connected);")
	private static native boolean jsIsBuilderConnected();

	@JSBody(params = {"filename"},
		script = "var b = window.__eaglerModBuilder; return (b && b.state.fileStates[filename]) ? String(b.state.fileStates[filename]) : '';")
	private static native String jsGetModBuildState(String filename);

	@JSBody(params = {"filename"},
		script = "var b = window.__eaglerModBuilder; return (b && b.state.fileErrors[filename]) ? String(b.state.fileErrors[filename]) : '';")
	private static native String jsGetModBuildError(String filename);

	@JSBody(params = {"profileName", "filename", "modId"},
		script = "var b = window.__eaglerModBuilder; return !!(b && b.isModInProfile(profileName, filename, modId));")
	private static native boolean jsIsModInProfile(String profileName, String filename, String modId);

	@JSBody(script = "var b = window.__eaglerModBuilder; if (b) { b.setSavingState(); }")
	private static native void jsSetSavingReloadState();

	@JSBody(params = {"delayMs"},
		script = "setTimeout(function() { window.location.reload(); }, delayMs > 0 ? delayMs : 0);")
	private static native void jsReloadPage(int delayMs);
}
