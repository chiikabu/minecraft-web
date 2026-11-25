// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.backend.opengl.GlApi;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.web.WebSaves;
import net.minecraft.client.web.WebZipPack;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Shader packs in the browser build: storage, selection and the per-frame hook.
 *
 * <p>Packs are zips stored in the browser's file system at {@code /config/shaderpacks} (persisted to
 * IndexedDB like resource packs); the chosen pack's name is in {@code /config/shaderpacks/selected.txt}.
 * The pack is compiled lazily on the render thread, on the first world frame after it is chosen
 * or after the dimension changes (packs keep per-dimension program folders).
 *
 * <p>{@link #afterLevel} is called by the GameRenderer twin right after the world is drawn and
 * before the hand, so post-processing sees the world's depth, not the hand's.
 */
public final class ShaderPacks {
	/**
	 * Under /config because WebSaves mirrors only /saves, /resourcepacks, /downloads and /config to
	 * IndexedDB, and /config stays with the page when the integrated server owns /saves.
	 */
	private static final String DIR = "/config/shaderpacks";
	private static final String NL = String.valueOf((char) 10);
	private static final String SELECTED = DIR + "/selected.txt";

	private static String selected;
	private static boolean loadedSelection;
	private static ShaderPackRuntime runtime;
	private static ShaderUniforms uniforms;
	private static String builtFor; // "<pack>|<dimension>"
	private static String status = "";
	private static final List<String> lastLog = new ArrayList<>();
	private static Runnable onAdded;
	private static boolean targetBytesLogged;

	private ShaderPacks() {
	}

	/** Install the hidden file input. Call once after the client exists. */
	public static void install() {
		installFileInput(ShaderPacks::accept);
		// ?shadowevery=N: redraw the shadow map every N frames (default 1 = every frame)
		ShaderPackRuntime.shadowInterval = Math.max(1, Math.min(8, queryInt("shadowevery", ShaderPackRuntime.shadowInterval)));
		// test and support hooks: window.__shaderPack("name.zip" | null), window.__shaderPackStatus()
		// window.__shaderPackDebug(passes, colortex): run only the first passes and show one buffer; (-1, -1) = normal
		installDebugHook((passes, show) -> {
			ShaderPackRuntime.debugPasses = passes;
			ShaderPackRuntime.debugShow = show;
		});
		// window.__shaderPackDump("composite6"): that pass's final GLSL ES and its live uniform values
		installDumpHook(pass -> runtime == null || uniforms == null ? "no pack running" : runtime.describe(pass, uniforms));
		installPageHooks(name -> select(name == null || name.isEmpty() ? null : name),
			() -> String.join(NL, selected() == null ? "off" : selected(), status, String.join(NL, lastLog), "stored: " + String.join(", ", list())));
	}

	@JSBody(params = {"name", "fallback"}, script = "try { var m = new RegExp('[?&]' + name + '=(-?[0-9]+)').exec(location.search || '');"
		+ " return m ? parseInt(m[1], 10) : fallback; } catch (e) { return fallback; }")
	private static native int queryInt(String name, int fallback);

	@JSFunctor
	private interface DebugHook extends JSObject {
		void debug(int passes, int show);
	}

	@JSBody(params = {"debug"}, script = "window.__shaderPackDebug = debug;")
	private static native void installDebugHook(DebugHook debug);

	@JSFunctor
	private interface DumpHook extends JSObject {
		String dump(String pass);
	}

	@JSBody(params = {"dump"}, script = "window.__shaderPackDump = dump;")
	private static native void installDumpHook(DumpHook dump);

	@JSFunctor
	private interface SelectHook extends JSObject {
		void select(String name);
	}

	@JSFunctor
	private interface StatusHook extends JSObject {
		String status();
	}

	@JSBody(params = {"select", "status"}, script = "window.__shaderPack = select; window.__shaderPackStatus = status;")
	private static native void installPageHooks(SelectHook select, StatusHook status);

	// ---- storage ----

	public static List<String> list() {
		File dir = new File(DIR);
		String[] names = dir.list();
		List<String> out = new ArrayList<>();
		if (names != null) {
			for (String n : names) {
				if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) {
					out.add(n);
				}
			}
		}
		Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	/** The chosen pack's file name, or null for Off. */
	public static String selected() {
		if (!loadedSelection) {
			loadedSelection = true;
			byte[] b = WebSaves.readFile(SELECTED);
			String s = b == null ? "" : new String(b, StandardCharsets.UTF_8).trim();
			selected = s.isEmpty() || !new File(DIR + "/" + s).exists() ? null : s;
			lsSet(LS_SELECTED, selected == null ? "" : selected); // keep the boot-time mirror in step
		}
		return selected;
	}

	public static void select(final String name) {
		selected();
		if (name == null ? selected == null : name.equals(selected)) {
			return;
		}
		selected = name;
		try {
			WebSaves.mkdirs(DIR);
			WebSaves.writeFile(SELECTED, (name == null ? "" : name).getBytes(StandardCharsets.UTF_8));
		} catch (Throwable e) {
			System.out.println("[shaderpack] could not save the selection: " + e);
		}
		lsSet(LS_SELECTED, name == null ? "" : name);
		unload();
		status = name == null ? "" : "Loads when the world is drawn";
	}

	/** localStorage mirror of the selection: readable synchronously at boot, before the VFS is restored. */
	private static final String LS_SELECTED = "minecraft.shaderpack.selected";

	/**
	 * True when a shader pack is selected, so the page should boot the WebGL2 backend (the pack
	 * runtime is WebGL2-only). Cheap, safe before Minecraft exists, false on any error.
	 */
	public static boolean wantsWebGL() {
		try {
			String v = lsGet(LS_SELECTED);
			return v != null && !v.isEmpty();
		} catch (Throwable e) {
			return false;
		}
	}

	@JSBody(params = {"k"}, script = "try { return window.localStorage.getItem(k); } catch (e) { return null; }")
	private static native String lsGet(String k);

	@JSBody(params = {"k", "v"}, script = "try { window.localStorage.setItem(k, v);"
		+ " window.dispatchEvent(new Event('minecraft-web:shaderpack')); } catch (e) {}")
	private static native void lsSet(String k, String v);

	public static void remove(final String name) {
		if (name.equals(selected())) {
			select(null);
		}
		new File(DIR + "/" + name).delete();
		new File(optionsFile(name)).delete();
		optionsCache.remove(name);
	}

	// ---- per-pack options ----

	/** Parsed option menus by pack file name; parsing scans every source file, so it is done once. */
	private static final Map<String, PackOptions> optionsCache = new HashMap<>();

	/** OptiFine keeps a pack's option values next to it as {@code <pack>.zip.txt}; so do we. */
	private static String optionsFile(final String name) {
		return DIR + "/" + name + ".txt";
	}

	/** The pack's options, read from its zip on first use; null if the pack cannot be read. */
	public static PackOptions packOptions(final String name) {
		PackOptions cached = optionsCache.get(name);
		if (cached != null) {
			return cached;
		}
		byte[] zip = WebSaves.readFile(DIR + "/" + name);
		Map<String, byte[]> entries = zip == null ? null : WebZipPack.unzip(zip);
		ShaderPack pack = entries == null ? null : ShaderPack.fromZipEntries(name, entries);
		return pack == null ? null : packOptions(name, pack);
	}

	private static PackOptions packOptions(final String name, final ShaderPack pack) {
		PackOptions cached = optionsCache.get(name);
		if (cached == null) {
			cached = PackOptions.parse(pack);
			optionsCache.put(name, cached);
		}
		return cached;
	}

	/** The saved values ({@code NAME=value}, non-defaults only); empty when nothing was changed. */
	public static Map<String, String> optionValues(final String name) {
		byte[] b = WebSaves.readFile(optionsFile(name));
		return PackOptions.readValues(b == null ? null : new String(b, StandardCharsets.UTF_8));
	}

	/**
	 * Saves the values that differ from the pack's defaults and, if this pack is running, drops it
	 * so the next world frame rebuilds it with them (a recompile, so callers only save on a change).
	 */
	public static void saveOptionValues(final String name, final Map<String, String> nonDefault) {
		try {
			WebSaves.mkdirs(DIR);
			WebSaves.writeFile(optionsFile(name), PackOptions.writeValues(nonDefault).getBytes(StandardCharsets.UTF_8));
		} catch (Throwable e) {
			System.out.println("[shaderpack] could not save the options of " + name + ": " + e);
		}
		if (name.equals(selected())) {
			unload();
			status = "Reloads with the new options when the world is drawn";
		}
	}

	public static String status() {
		return status;
	}

	public static List<String> lastLog() {
		return lastLog;
	}

	public static void choosePack(final Runnable afterAdd) {
		onAdded = afterAdd;
		clickPicker();
	}

	/** A picked or dropped file: stored if it is a shader pack. Returns a problem sentence, or null. */
	public static String accept(final String filename, final Int8Array data) {
		return accept(filename, data.copyToJavaArray());
	}

	public static String accept(final String filename, final byte[] bytes) {
		Map<String, byte[]> entries = WebZipPack.unzip(bytes);
		if (entries == null) {
			toast("Could not add " + filename, "Not a .zip file.");
			return "not a zip";
		}
		if (ShaderPack.findRoot(entries.keySet()) == null) {
			toast("Could not add " + filename, "No shaders/ folder inside: not a shader pack.");
			return "not a shader pack";
		}
		String name = sanitize(filename);
		optionsCache.remove(name); // a new version of the pack may have different options
		try {
			WebSaves.mkdirs(DIR);
			WebSaves.writeFile(DIR + "/" + name, bytes);
		} catch (Throwable e) {
			toast("Could not add " + filename, "The browser refused to store it (out of space?)");
			return "store failed";
		}
		System.out.println("[shaderpack] added " + name + " (" + bytes.length + " bytes)");
		Runnable r = onAdded;
		onAdded = null;
		if (r != null) {
			r.run();
		}
		return null;
	}

	private static String sanitize(final String filename) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < filename.length() && out.length() < 80; i++) {
			char c = filename.charAt(i);
			out.append(c == '/' || c == '\\' || c == ':' || c < ' ' ? '_' : c);
		}
		String name = out.toString();
		return name.toLowerCase(Locale.ROOT).endsWith(".zip") ? name : name + ".zip";
	}

	static void toast(final String title, final String message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return;
		}
		net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(
			minecraft.gui.toastManager(),
			net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PACK_LOAD_FAILURE,
			Component.literal(title), Component.literal(message));
	}

	// ---- lifecycle & per frame ----

	/** True when a shader pack runtime is active and healthy. */
	public static boolean isActive() {
		return runtime != null && !runtime.broken();
	}

	/** Alias of {@link #isActive()} for callers checking active state. */
	public static boolean active() {
		return isActive();
	}

	/** Unloads the active shader pack runtime and releases all GPU resources cleanly. */
	public static void unload() {
		setTagging(false);
		boolean closedAny = false;
		if (job != null) {
			if (job.runtime != null) {
				job.runtime.close();
				closedAny = true;
			}
			job = null;
		}
		if (runtime != null) {
			runtime.close();
			closedAny = true;
		}
		if (closedAny && com.mojang.renderpearl.backend.opengl.GlApi.ready()) {
			ShaderPackRuntime.resyncGlState();
		}
		runtime = null;
		uniforms = null;
		builtFor = null;
		uniformsUpdatedBeforeLevel = false;
		reloadHintShown = false;
		targetBytesLogged = false;
		atlasSampler = 0;
		shadowScissor = 0;
		rebind = false;
		dirtyPass = false;
	}

	/**
	 * Called from GameRenderer when {@code minecraft.level == null} (title screen / world exit) so
	 * 100-300 MB of FBOs and colortex targets are released and per-world state is reset cleanly.
	 */
	public static void onNoLevel() {
		if (runtime != null || job != null || builtFor != null) {
			unload();
			if (selected != null) {
				status = "Loads when the world is drawn";
			}
		}
	}

	/**
	 * Called from LevelRenderer right after the {@code "Main"} RenderPass closes, before
	 * {@code executeOutline} / {@code executeSeeThrough}, so those passes draw to vanilla targets.
	 */
	public static void endWorldPass() {
		ShaderPackRuntime rt = runtime;
		if (rt != null) {
			rt.endWorldPass();
		}
	}

	/** Called from GameRenderer.renderLevel after the world pass, before the hand. */
	public static void afterLevel(final GameRenderer gameRenderer, final Matrix4f projection, final GameRenderState state) {
		String name = selected();
		if (name == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		if (com.mojang.blaze3d.webgpu.WebGpuBackend.live() || !com.mojang.renderpearl.backend.opengl.GlApi.ready()) {
			if (!reloadHintShown) {
				reloadHintShown = true;
				status = "Reload the page to use shader packs (they need WebGL2)";
				toast("Reload to use shaders", "Shader packs need WebGL2; reloading the page switches to it.");
			}
			return;
		}
		String dimension = mc.level.dimension().identifier().toString();
		String key = name + "|" + dimension;
		if (!key.equals(builtFor)) {
			unload();
			builtFor = key;
			job = begin(name, dimension);
		}
		if (job != null) {
			// the pack is still being built or warmed up: one step per frame while vanilla keeps drawing
			step();
			return;
		}
		if (runtime == null || runtime.broken()) {
			return;
		}
		com.mojang.blaze3d.pipeline.RenderTarget main = gameRenderer.mainRenderTarget();
		if (!(main.getColorTexture() instanceof GlTexture color) || !(main.getDepthTexture() instanceof GlTexture depth)) {
			// WebGPU backend: packs need WebGL2, which the page picks at boot when a pack is selected
			if (!reloadHintShown) {
				reloadHintShown = true;
				status = "Reload the page to use shader packs (they need WebGL2)";
				toast("Reload to use shaders", "Shader packs need WebGL2; reloading the page switches to it.");
			}
			return;
		}
		if (!uniformsUpdatedBeforeLevel) {
			uniforms.reuseShadow = false;
			uniforms.update(mc, state, projection, main.width, main.height);
		}
		uniformsUpdatedBeforeLevel = false;
		runtime.render(color.glId(), depth.glId(), main.width, main.height, uniforms);
		if (runtime.broken()) {
			setTagging(false);
			status = "Stopped: " + runtime.log.get(runtime.log.size() - 1);
			toast("Shader pack stopped", runtime.log.get(runtime.log.size() - 1));
		} else if (!targetBytesLogged && runtime.targetBytes() > 0L) {
			targetBytesLogged = true;
			System.out.println("[shaderpack] " + name + " targets " + (runtime.targetBytes() >> 20) + " MB (" + main.width + "x" + main.height + ")");
		}
	}

	private static boolean uniformsUpdatedBeforeLevel;
	private static boolean reloadHintShown;

	/**
	 * Called from GameRenderer.renderLevel before the world is drawn. With a pack that has a world
	 * program (gbuffers_terrain), its buffers are cleared, prepare* passes run, and its uniforms
	 * are computed here so the terrain it shades during the world pass sees this frame's values.
	 */
	public static void beforeLevel(final GameRenderer gameRenderer, final Matrix4f projection, final GameRenderState state) {
		if (runtime == null || runtime.broken() || !runtime.hasWorldProgram()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !(mc.level.dimension().identifier().toString()).equals(builtFor == null ? "" : builtFor.substring(builtFor.indexOf('|') + 1))) {
			return; // a dimension change rebuilds in afterLevel first
		}
		com.mojang.blaze3d.pipeline.RenderTarget main = gameRenderer.mainRenderTarget();
		if (!(main.getColorTexture() instanceof GlTexture color) || !(main.getDepthTexture() instanceof GlTexture depth)) {
			return;
		}
		uniforms.reuseShadow = runtime.reuseShadowThisFrame();
		uniforms.update(mc, state, projection, main.width, main.height);
		uniformsUpdatedBeforeLevel = true;
		runtime.beginWorld(color.glId(), depth.glId(), main.width, main.height, uniforms);
	}

	/**
	 * Block ids and face directions in new terrain meshes (ShaderBlockIds), on while a pack's world
	 * program draws terrain. Switching rebuilds every section: vanilla's terrain shader must not
	 * see the extra lightmap bits, and the pack's program needs them on every section.
	 */
	private static void setTagging(final boolean on) {
		if (ShaderBlockIds.active == on) {
			return;
		}
		ShaderBlockIds.active = on;
		Minecraft mc = Minecraft.getInstance();
		if (mc.levelExtractor != null) {
			mc.levelExtractor.allChanged();
		}
	}

	/** Set to make the GlCommandEncoder twin re-bind (and report) the next draw's pipeline. */
	public static boolean rebind;
	/** While the shadow pass draws: the shadow map's size, which the GlCommandEncoder twin keeps as the scissor box (0 = off). */
	public static int shadowScissor;

	/**
	 * Called by the ChunkSectionsToRender twin before opaque terrain: true if the pack's shadow
	 * program is ready, in which case the caller draws solid and cutout terrain once more (into the
	 * shadow map) and then calls {@link #endShadow}.
	 */
	public static boolean beginShadow() {
		ShaderPackRuntime rt = runtime;
		if (rt == null || !rt.beginShadow()) {
			return false;
		}
		rebind = true;
		shadowScissor = rt.shadowResolution();
		return true;
	}

	/** While a pack's world program draws: its atlas sampler object (0 = none) and vanilla's atlas texture unit. */
	public static int atlasSampler;
	public static int atlasUnit;

	/** Set after the pack's passes ran inside a render pass: the GlCommandEncoder twin re-binds all of that pass's state. */
	public static boolean dirtyPass;

	/**
	 * Called by the ChunkSectionsToRender twin before translucent terrain: the pack's deferred
	 * passes run now, as in Iris, so they light the opaque world only and water is drawn over
	 * their result.
	 */
	public static void beforeTranslucent() {
		ShaderPackRuntime rt = runtime;
		if (rt != null && rt.beforeTranslucent()) {
			rebind = true;
			dirtyPass = true;
		}
	}

	public static void endShadow() {
		shadowScissor = 0;
		ShaderPackRuntime rt = runtime;
		if (rt != null) {
			rt.endShadow();
		}
		rebind = true;
	}

	/** Called by the GlCommandEncoder twin whenever a pipeline is bound for drawing. */
	public static void onPipelineBound(final com.mojang.renderpearl.backend.opengl.GlRenderPipeline pipeline) {
		ShaderPackRuntime rt = runtime;
		if (rt != null) {
			rt.onPipelineBound(pipeline);
		}
	}

	/**
	 * A pack being built. Translating, compiling, linking and warming up run incrementally (one
	 * small piece per frame) so large packs do not freeze the browser page or stall Frame 1.
	 */
	private static final class Job {
		String name;
		String dimension;
		long startedMs;
		PackPrograms programs;
		ShaderPackRuntime runtime;
		/** 0 translating, 1 compiling, 2 waiting for links, 3 warming up 1x1 PSO draws. */
		int phase;
		String shown = "";
	}

	private static Job job;

	/** True while a pack is being built (the Shader Packs screen shows the progress line). */
	public static boolean building() {
		return job != null;
	}

	private static Job begin(final String name, final String dimension) {
		lastLog.clear();
		byte[] zip = WebSaves.readFile(DIR + "/" + name);
		Map<String, byte[]> entries = zip == null ? null : WebZipPack.unzip(zip);
		ShaderPack pack = entries == null ? null : ShaderPack.fromZipEntries(name, entries);
		if (pack == null) {
			fail(name, "could not read the pack");
			return null;
		}
		Map<String, String> overrides;
		Map<String, String> optionMacros = new HashMap<>();
		PackOptions packOptions = null;
		Set<String> disabledPrograms = Collections.emptySet();
		try {
			packOptions = packOptions(name, pack);
			Map<String, String> saved = optionValues(name);
			overrides = packOptions.effective(saved);
			optionMacros = packOptions.macros(saved);
			disabledPrograms = packOptions.disabledPrograms(packOptions.values(saved));
			if (!overrides.isEmpty()) {
				lastLog.add(overrides.size() + " option(s) changed from the defaults: " + overrides);
			}
			if (!disabledPrograms.isEmpty()) {
				lastLog.add("profile disables programs: " + disabledPrograms);
			}
		} catch (RuntimeException e) {
			// a menu the parser does not understand must not stop the pack from running
			lastLog.add("could not read the pack's options, using its defaults: " + e);
			overrides = null;
		}
		PackPrograms programs = new PackPrograms(pack, dimension, overrides);
		programs.packOptions = packOptions;
		programs.optionMacros.putAll(optionMacros);
		programs.disabledPrograms.addAll(disabledPrograms);
		// Iris's BIOME_* macros, numbered like the biome uniform
		programs.macros.putAll(ShaderUniforms.biomeMacros(Minecraft.getInstance().level));
		try {
			programs.loadBegin();
		} catch (RuntimeException e) {
			fail(name, "could not preprocess: " + e);
			return null;
		}
		Job j = new Job();
		j.name = name;
		j.dimension = dimension;
		j.startedMs = System.currentTimeMillis();
		j.programs = programs;
		progress(j, "Loading: reading the pack");
		return j;
	}

	/** One piece of the build; ends the job when the pack is ready or has failed. */
	private static void step() {
		Job j = job;
		try {
			if (j.phase == 0) {
				boolean more;
				try {
					more = j.programs.loadStep();
				} catch (RuntimeException e) {
					job = null;
					fail(j.name, "could not preprocess: " + e);
					return;
				}
				progress(j, "Loading: translated " + j.programs.stepsDone() + " of " + j.programs.stepsTotal() + " programs");
				if (!more) {
					startCompile(j);
				}
			} else if (j.phase == 1) {
				boolean more = j.runtime.buildStep();
				progress(j, "Loading: compiled " + j.runtime.buildStepsDone() + " of " + j.runtime.buildStepsTotal() + " programs");
				if (!more) {
					j.phase = 2;
				}
			} else if (j.phase == 2) {
				int started = j.runtime.linksStarted();
				int done = j.runtime.linksDone();
				progress(j, "Loading: the graphics driver has built " + done + " of " + started + " programs");
				if (done >= started) {
					startWarmup(j);
				}
			} else {
				boolean more = j.runtime.warmupStep();
				progress(j, "Warming up: " + j.runtime.warmupDone() + " of " + j.runtime.warmupTotal() + " passes");
				if (!more) {
					finish(j);
				}
			}
		} catch (RuntimeException e) {
			job = null;
			if (j.runtime != null) {
				j.runtime.log.add("build failed: " + e);
				lastLog.addAll(j.runtime.log);
				j.runtime.close();
				if (com.mojang.renderpearl.backend.opengl.GlApi.ready()) {
					ShaderPackRuntime.resyncGlState();
				}
			}
			fail(j.name, "build failed: " + e);
		}
	}

	/** Everything is translated: the runtime's own GL objects, then one program per frame. */
	private static void startCompile(final Job j) {
		PackPrograms programs = j.programs;
		lastLog.addAll(programs.log);
		Map<String, Integer> textureFlags = new HashMap<>();
		Map<String, byte[]> images = customTextures(programs.pack, programs, textureFlags);
		try {
			ShaderBlockIds.build(programs.blockProperties, lastLog);
		} catch (RuntimeException e) {
			ShaderBlockIds.clear();
			lastLog.add("block.properties not understood: " + e);
		}
		ShaderPackRuntime rt = new ShaderPackRuntime(programs.directives);
		j.runtime = rt;
		boolean ok = rt.buildBegin(programs, images, textureFlags, ShaderPacks::uploadPng);
		ShaderPackRuntime.resyncGlState();
		if (!ok) {
			job = null;
			lastLog.addAll(rt.log);
			rt.close();
			ShaderPackRuntime.resyncGlState();
			fail(j.name, rt.log.isEmpty() ? "no program compiled" : rt.log.get(rt.log.size() - 1));
			return;
		}
		j.phase = 1;
	}

	/** Collects finished links and enters staggered 1x1 PSO warmup (1 pass per frame). */
	private static void startWarmup(final Job j) {
		ShaderPackRuntime rt = j.runtime;
		boolean ok;
		try {
			ok = rt.buildFinish();
		} catch (RuntimeException e) {
			rt.log.add("build failed: " + e);
			ok = false;
		}
		ShaderPackRuntime.resyncGlState();
		lastLog.addAll(rt.log);
		for (String line : lastLog) {
			System.out.println("[shaderpack] " + line);
		}
		if (!ok) {
			job = null;
			rt.close();
			ShaderPackRuntime.resyncGlState();
			fail(j.name, rt.log.isEmpty() ? "no program compiled" : rt.log.get(rt.log.size() - 1));
			return;
		}
		if (rt.warmupTotal() > 0) {
			j.phase = 3;
			progress(j, "Warming up: 0 of " + rt.warmupTotal() + " passes");
		} else {
			finish(j);
		}
	}

	private static void finish(final Job j) {
		job = null;
		ShaderPackRuntime rt = j.runtime;
		PackPrograms programs = j.programs;
		runtime = rt;
		uniforms = new ShaderUniforms(programs);
		targetBytesLogged = false;
		setTagging(rt.hasWorldProgram());
		long ms = System.currentTimeMillis() - j.startedMs;
		StringBuilder s = new StringBuilder();
		s.append(programs.programs.size()).append(" passes ready in ").append(ms).append(" ms");
		if (rt.hasShadowPass()) {
			s.append("; shadows on");
		} else if (!programs.unsupported.isEmpty()) {
			s.append("; no shadows");
		}
		s.append(rt.hasWorldProgram() ? "; pack shades terrain" : "; world shading is vanilla");
		status = s.toString();
		System.out.println("[shaderpack] " + j.name + " for " + j.dimension + ": " + status);
		if (ms > 3000) {
			toast("Shader pack ready", shortName(j.name));
		}
	}

	/** The status line while loading, and a toast when the stage changes (not every frame). */
	private static void progress(final Job j, final String text) {
		status = text;
		String stage = text.indexOf(':') < 0 ? text : j.phase == 0 ? "translating" : j.phase == 1 ? "compiling" : j.phase == 2 ? "linking" : "warming";
		if (!stage.equals(j.shown) && System.currentTimeMillis() - j.startedMs > 1500) {
			j.shown = stage;
			toast("Loading shader pack", shortName(j.name) + (j.phase == 3 ? ": warming up passes" : ""));
		}
	}

	private static String shortName(final String file) {
		String n = file.toLowerCase(Locale.ROOT).endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
		return n.length() > 40 ? n.substring(0, 40) + "..." : n;
	}

	private static void fail(final String name, final String why) {
		status = "Failed: " + why;
		lastLog.add(why);
		System.out.println("[shaderpack] " + name + ": " + why);
		toast("Shader pack failed", why.length() > 80 ? why.substring(0, 80) + "..." : why);
	}

	private static final Pattern MCMETA_BLUR = Pattern.compile("\"blur\"\\s*:\\s*(true|false)");
	private static final Pattern MCMETA_CLAMP = Pattern.compile("\"clamp\"\\s*:\\s*(true|false)");

	/**
	 * Parses a custom texture's optional {@code .png.mcmeta} into bit flags:
	 * bit 0 = blur (LINEAR vs NEAREST), bit 1 = clamp (CLAMP_TO_EDGE vs REPEAT).
	 * Without an {@code .mcmeta}, defaults to blur=true, clamp=false (flag 1) to match legacy repeat/linear.
	 */
	private static int parseMcmetaFlags(final String mcmeta) {
		if (mcmeta == null || mcmeta.isEmpty()) {
			return ShaderPackRuntime.CUSTOM_FLAG_BLUR;
		}
		boolean blur = false;
		boolean clamp = false;
		Matcher bm = MCMETA_BLUR.matcher(mcmeta);
		if (bm.find()) {
			blur = bm.group(1).equals("true");
		}
		Matcher cm = MCMETA_CLAMP.matcher(mcmeta);
		if (cm.find()) {
			clamp = cm.group(1).equals("true");
		}
		return (blur ? ShaderPackRuntime.CUSTOM_FLAG_BLUR : 0) | (clamp ? ShaderPackRuntime.CUSTOM_FLAG_CLAMP : 0);
	}

	/** {@code texture.noise} and {@code texture.<stage>.<sampler>} images, keeping each stage isolated. */
	private static Map<String, byte[]> customTextures(final ShaderPack pack, final PackPrograms programs,
		final Map<String, Integer> flagsOut) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : programs.properties.asMap().entrySet()) {
			String k = e.getKey();
			String sampler;
			if (k.equals("texture.noise")) {
				sampler = "noisetex";
			} else if (k.startsWith("texture.composite.") || k.startsWith("texture.deferred.") || k.startsWith("texture.prepare.")
				|| k.startsWith("texture.final.") || k.startsWith("texture.gbuffers.") || k.startsWith("texture.shadow.")) {
				// Keep each stage in its own namespace (final: and shadow: stay isolated, with fallback lookup in runtime)
				int dot = k.indexOf('.', "texture.".length());
				String stage = k.substring("texture.".length(), dot);
				sampler = stage + ":" + k.substring(dot + 1);
			} else {
				continue;
			}
			String path = e.getValue().trim();
			int space = path.indexOf(' ');
			if (space > 0) {
				path = path.substring(0, space); // raw-texture parameters are not supported
			}
			String abs = GlslPreprocessor.resolve("/" + programs.folder + "/x", path);
			String resolvedPath = abs;
			byte[] bytes = pack.bytes(abs);
			if (bytes == null) {
				resolvedPath = GlslPreprocessor.resolve("/x", path);
				bytes = pack.bytes(resolvedPath);
			}
			if (bytes != null && !out.containsKey(sampler)) {
				out.put(sampler, bytes);
				String mcmeta = pack.text(resolvedPath + ".mcmeta");
				flagsOut.put(sampler, parseMcmetaFlags(mcmeta));
			} else if (bytes == null) {
				programs.log.add("custom texture " + path + " for " + sampler + " not found");
			}
		}
		return out;
	}

	private static int uploadPng(final byte[] png, final int flags) {
		try (NativeImage image = NativeImage.read(png)) {
			int w = image.getWidth();
			int h = image.getHeight();
			int[] abgr = image.getPixelsABGR();
			byte[] rgba = new byte[w * h * 4];
			for (int i = 0; i < abgr.length; i++) {
				int p = abgr[i];
				rgba[i * 4] = (byte) p;
				rgba[i * 4 + 1] = (byte) (p >> 8);
				rgba[i * 4 + 2] = (byte) (p >> 16);
				rgba[i * 4 + 3] = (byte) (p >>> 24);
			}
			int tex = ShaderPackRuntime.rgbaTexture(w, h, rgba);
			boolean blur = (flags & ShaderPackRuntime.CUSTOM_FLAG_BLUR) != 0;
			boolean clamp = (flags & ShaderPackRuntime.CUSTOM_FLAG_CLAMP) != 0;
			int filter = blur ? ShaderPackRuntime.LINEAR : ShaderPackRuntime.NEAREST;
			int wrap = clamp ? ShaderPackRuntime.CLAMP_TO_EDGE : ShaderPackRuntime.REPEAT;
			GlApi.glTexParameteri(ShaderPackRuntime.TEXTURE_2D, ShaderPackRuntime.MIN_FILTER, filter);
			GlApi.glTexParameteri(ShaderPackRuntime.TEXTURE_2D, ShaderPackRuntime.MAG_FILTER, filter);
			GlApi.glTexParameteri(ShaderPackRuntime.TEXTURE_2D, ShaderPackRuntime.WRAP_S, wrap);
			GlApi.glTexParameteri(ShaderPackRuntime.TEXTURE_2D, ShaderPackRuntime.WRAP_T, wrap);
			return tex;
		} catch (Throwable e) {
			System.out.println("[shaderpack] could not decode a custom texture: " + e);
			return 0;
		}
	}

	// ---- file picker ----

	@JSFunctor
	private interface PackHandler extends JSObject {
		void pack(String filename, Int8Array data);
	}

	@JSBody(params = {"onPack"}, script = ""
		+ "if (document.getElementById('minecraft-shaderpack-file')) { return; }"
		+ "var input = document.createElement('input');"
		+ "input.type = 'file'; input.accept = '.zip,application/zip';"
		+ "input.id = 'minecraft-shaderpack-file'; input.style.display = 'none';"
		+ "input.onchange = function() {"
		+ "  var f = input.files && input.files[0];"
		+ "  input.value = '';"
		+ "  if (!f) { return; }"
		+ "  var fr = new FileReader();"
		+ "  fr.onload = function() { onPack(f.name, new Int8Array(fr.result)); };"
		+ "  fr.readAsArrayBuffer(f);"
		+ "};"
		+ "document.body.appendChild(input);")
	private static native void installFileInput(PackHandler onPack);

	@JSBody(params = {}, script = ""
		+ "var input = document.getElementById('minecraft-shaderpack-file');"
		+ "if (!input) { console.error('[shaderpack] file input missing'); return; }"
		+ "input.click();")
	private static native void clickPicker();
}
