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

public final class ShaderPacks {
	private static final String DIR = "/config/shaderpacks";
	private static final String NL = String.valueOf((char) 10);
	private static final String SELECTED = DIR + "/selected.txt";

	private static String selected;
	private static boolean loadedSelection;
	private static ShaderPackRuntime runtime;
	private static ShaderUniforms uniforms;
	private static String builtFor;
	private static String status = "";
	private static final List<String> lastLog = new ArrayList<>();
	private static Runnable onAdded;

	private ShaderPacks() {
	}

	public static void install() {
		installFileInput(ShaderPacks::accept);
		installDebugHook((passes, show) -> {
			ShaderPackRuntime.debugPasses = passes;
			ShaderPackRuntime.debugShow = show;
		});
		installDumpHook(pass -> runtime == null || uniforms == null ? "no pack running" : runtime.describe(pass, uniforms));
		installPageHooks(name -> select(name == null || name.isEmpty() ? null : name),
			() -> String.join(NL, selected() == null ? "off" : selected(), status, String.join(NL, lastLog), "stored: " + String.join(", ", list())));
	}

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

	public static String selected() {
		if (!loadedSelection) {
			loadedSelection = true;
			byte[] b = WebSaves.readFile(SELECTED);
			String s = b == null ? "" : new String(b, StandardCharsets.UTF_8).trim();
			selected = s.isEmpty() || !new File(DIR + "/" + s).exists() ? null : s;
			lsSet(LS_SELECTED, selected == null ? "" : selected);
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

	private static final String LS_SELECTED = "minecraft.shaderpack.selected";

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

	@JSBody(params = {"k", "v"}, script = "try { window.localStorage.setItem(k, v); } catch (e) {}")
	private static native void lsSet(String k, String v);

	public static void remove(final String name) {
		if (name.equals(selected())) {
			select(null);
		}
		new File(DIR + "/" + name).delete();
		new File(optionsFile(name)).delete();
		optionsCache.remove(name);
	}

	private static final Map<String, PackOptions> optionsCache = new HashMap<>();

	private static String optionsFile(final String name) {
		return DIR + "/" + name + ".txt";
	}

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

	public static Map<String, String> optionValues(final String name) {
		byte[] b = WebSaves.readFile(optionsFile(name));
		return PackOptions.readValues(b == null ? null : new String(b, StandardCharsets.UTF_8));
	}

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
		optionsCache.remove(name);
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

	private static void unload() {
		setTagging(false);
		if (runtime != null) {
			runtime.close();
		}
		runtime = null;
		uniforms = null;
		builtFor = null;
	}

	public static void afterLevel(final GameRenderer gameRenderer, final Matrix4f projection, final GameRenderState state) {
		String name = selected();
		if (name == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		String dimension = mc.level.dimension().identifier().toString();
		String key = name + "|" + dimension;
		if (!key.equals(builtFor)) {
			unload();
			builtFor = key;
			build(name, dimension);
		}
		if (runtime == null || runtime.broken()) {
			return;
		}
		com.mojang.blaze3d.pipeline.RenderTarget main = gameRenderer.mainRenderTarget();
		if (!(main.getColorTexture() instanceof GlTexture color) || !(main.getDepthTexture() instanceof GlTexture depth)) {
			if (!reloadHintShown) {
				reloadHintShown = true;
				status = "Reload the page to use shader packs (they need WebGL2)";
				toast("Reload to use shaders", "Shader packs need WebGL2; reloading the page switches to it.");
			}
			return;
		}
		if (!uniformsUpdatedBeforeLevel) {
			uniforms.update(mc, state, projection, main.width, main.height);
		}
		uniformsUpdatedBeforeLevel = false;
		runtime.render(color.glId(), depth.glId(), main.width, main.height, uniforms);
		if (runtime.broken()) {
			setTagging(false);
			status = "Stopped: " + runtime.log.get(runtime.log.size() - 1);
			toast("Shader pack stopped", runtime.log.get(runtime.log.size() - 1));
		}
	}

	private static boolean uniformsUpdatedBeforeLevel;
	private static boolean reloadHintShown;

	public static void beforeLevel(final GameRenderer gameRenderer, final Matrix4f projection, final GameRenderState state) {
		if (runtime == null || runtime.broken() || !runtime.hasWorldProgram()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !(mc.level.dimension().identifier().toString() + "").equals(builtFor == null ? "" : builtFor.substring(builtFor.indexOf('|') + 1))) {
			return;
		}
		com.mojang.blaze3d.pipeline.RenderTarget main = gameRenderer.mainRenderTarget();
		if (!(main.getColorTexture() instanceof GlTexture color) || !(main.getDepthTexture() instanceof GlTexture depth)) {
			return;
		}
		uniforms.update(mc, state, projection, main.width, main.height);
		uniformsUpdatedBeforeLevel = true;
		runtime.beginWorld(color.glId(), depth.glId(), main.width, main.height, uniforms);
	}

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

	public static boolean rebind;
	public static int shadowScissor;

	public static boolean beginShadow() {
		ShaderPackRuntime rt = runtime;
		if (rt == null || !rt.beginShadow()) {
			return false;
		}
		rebind = true;
		shadowScissor = rt.shadowResolution();
		return true;
	}

	public static int atlasSampler;
	public static int atlasUnit;

	public static boolean dirtyPass;

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

	public static void onPipelineBound(final com.mojang.renderpearl.backend.opengl.GlRenderPipeline pipeline) {
		ShaderPackRuntime rt = runtime;
		if (rt != null) {
			rt.onPipelineBound(pipeline);
		}
	}

	private static void build(final String name, final String dimension) {
		long t0 = System.currentTimeMillis();
		lastLog.clear();
		byte[] zip = WebSaves.readFile(DIR + "/" + name);
		Map<String, byte[]> entries = zip == null ? null : WebZipPack.unzip(zip);
		ShaderPack pack = entries == null ? null : ShaderPack.fromZipEntries(name, entries);
		if (pack == null) {
			fail(name, "could not read the pack");
			return;
		}
		Map<String, String> overrides;
		Map<String, String> optionMacros = new java.util.HashMap<>();
		try {
			PackOptions packOptions = packOptions(name, pack);
			overrides = packOptions.effective(optionValues(name));
			optionMacros = packOptions.macros(optionValues(name));
			if (!overrides.isEmpty()) {
				lastLog.add(overrides.size() + " option(s) changed from the defaults: " + overrides);
			}
		} catch (RuntimeException e) {
			lastLog.add("could not read the pack's options, using its defaults: " + e);
			overrides = null;
		}
		PackPrograms programs = new PackPrograms(pack, dimension, overrides);
		programs.optionMacros.putAll(optionMacros);
		programs.macros.putAll(ShaderUniforms.biomeMacros(Minecraft.getInstance().level));
		try {
			programs.load();
		} catch (RuntimeException e) {
			fail(name, "could not preprocess: " + e);
			return;
		}
		lastLog.addAll(programs.log);
		Map<String, byte[]> images = customTextures(pack, programs);
		try {
			ShaderBlockIds.build(programs.blockProperties, lastLog);
		} catch (RuntimeException e) {
			ShaderBlockIds.clear();
			lastLog.add("block.properties not understood: " + e);
		}
		ShaderPackRuntime rt = new ShaderPackRuntime(programs.directives);
		boolean ok;
		try {
			ok = rt.build(programs, images, ShaderPacks::uploadPng);
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
			rt.close();
			ShaderPackRuntime.resyncGlState();
			fail(name, rt.log.isEmpty() ? "no program compiled" : rt.log.get(rt.log.size() - 1));
			return;
		}
		runtime = rt;
		uniforms = new ShaderUniforms(programs);
		setTagging(rt.hasWorldProgram());
		long ms = System.currentTimeMillis() - t0;
		StringBuilder s = new StringBuilder();
		s.append(programs.programs.size()).append(" passes ready in ").append(ms).append(" ms");
		if (rt.hasShadowPass()) {
			s.append("; shadows on");
		} else if (!programs.unsupported.isEmpty()) {
			s.append("; no shadows");
		}
		s.append(rt.hasWorldProgram() ? "; pack shades terrain" : "; world shading is vanilla");
		status = s.toString();
		System.out.println("[shaderpack] " + name + " for " + dimension + ": " + status + ", targets " + (rt.targetBytes() >> 20) + " MB");
	}

	private static void fail(final String name, final String why) {
		status = "Failed: " + why;
		lastLog.add(why);
		System.out.println("[shaderpack] " + name + ": " + why);
		toast("Shader pack failed", why.length() > 80 ? why.substring(0, 80) + "..." : why);
	}

	private static Map<String, byte[]> customTextures(final ShaderPack pack, final PackPrograms programs) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : programs.properties.asMap().entrySet()) {
			String k = e.getKey();
			String sampler;
			if (k.equals("texture.noise")) {
				sampler = "noisetex";
			} else if (k.startsWith("texture.composite.") || k.startsWith("texture.deferred.") || k.startsWith("texture.prepare.")
				|| k.startsWith("texture.final.") || k.startsWith("texture.gbuffers.") || k.startsWith("texture.shadow.")) {
				int dot = k.indexOf('.', "texture.".length());
				String stage = k.substring("texture.".length(), dot);
				sampler = (stage.equals("final") ? "composite" : stage.equals("shadow") ? "gbuffers" : stage) + ":" + k.substring(dot + 1);
			} else {
				continue;
			}
			String path = e.getValue().trim();
			int space = path.indexOf(' ');
			if (space > 0) {
				path = path.substring(0, space);
			}
			String abs = GlslPreprocessor.resolve("/" + programs.folder + "/x", path);
			byte[] bytes = pack.bytes(abs);
			if (bytes == null) {
				bytes = pack.bytes(GlslPreprocessor.resolve("/x", path));
			}
			if (bytes != null && !out.containsKey(sampler)) {
				out.put(sampler, bytes);
			} else if (bytes == null) {
				programs.log.add("custom texture " + path + " for " + sampler + " not found");
			}
		}
		return out;
	}

	private static int uploadPng(final byte[] png) {
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
			GlApi.glTexParameteri(ShaderPackRuntime.TEXTURE_2D, ShaderPackRuntime.WRAP_S, ShaderPackRuntime.REPEAT);
			GlApi.glTexParameteri(ShaderPackRuntime.TEXTURE_2D, ShaderPackRuntime.WRAP_T, ShaderPackRuntime.REPEAT);
			return tex;
		} catch (Throwable e) {
			System.out.println("[shaderpack] could not decode a custom texture: " + e);
			return 0;
		}
	}

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
