// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.webgpu;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLBrowser;
import org.lwjgl.sdl.SDLVideo;
import org.teavm.jso.JSBody;

public class WebGpuBackend implements GpuBackend {
	public static final boolean DEFAULT_ON = true;
	public static final int PROBE_TIMEOUT_MS = 2500;
	private static boolean live;
	private static String reason = "not decided";

	@Override
	public String getName() {
		return "WebGPU";
	}

	public static boolean live() {
		return live;
	}

	public static String reason() {
		return reason;
	}

	public static int override() {
		return overrideJs();
	}

	public static boolean wanted() {
		int o = override();
		if (o < 0) {
			reason = fallbackMarked() ? "WebGL2 forced after a WebGPU failure earlier in this tab (" + fallbackReason() + ")" : "WebGL2 forced by URL";
			return false;
		}
		if (o == 0 && net.minecraft.client.web.shaderpack.ShaderPacks.wantsWebGL()) {
			reason = "a shader pack is selected (shader packs need WebGL2)";
			return false;
		}
		if (o == 0 && !DEFAULT_ON) {
			reason = "WebGPU is opt-in (?webgpu)";
			return false;
		}
		if (!hasWebGpu()) {
			reason = "navigator.gpu is absent";
			return false;
		}
		return true;
	}

	public static boolean claimCanvas() {
		if (!deviceReady()) {
			reason = "WebGPU probe gave no device (" + WebGpuNatives.state() + ")";
			return false;
		}
		if (!WebGpuDevice.manifestPresent()) {
			reason = "the asset bundle has no WGSL (" + WebGpuDevice.WGSL_ROOT + "manifest.json)";
			WebGpuNatives.release();
			return false;
		}
		WebGpuNatives.install();
		if (!WebGpuNatives.configureCanvas()) {
			reason = "the canvas refused a WebGPU context";
			WebGpuNatives.release();
			return false;
		}
		live = true;
		reason = "WebGPU adapter and device ready (" + WebGpuNatives.adapterIdentity() + ")";
		return true;
	}

	public static void fallBackToGl(String why) {
		if (live) {
			live = false;
			GlslCompiler.reflectionOnly = false;
			WebGpuNatives.release();
			SDLBrowser.replaceCanvas();
			reason = "fell back to WebGL2: " + why;
			System.out.println("[26.3-web] graphics: " + reason);
		}
	}

	public static void startProbe() {
		startProbeJs();
	}

	public static void whenReady(final Runnable next) {
		startProbeJs();
		WebGpuNatives.waitSettled(next::run, PROBE_TIMEOUT_MS);
	}

	public static boolean deviceReady() {
		return "ready".equals(WebGpuNatives.state()) && WebGpuNatives.hasDevice();
	}

	@Override
	public void loadLibrary() throws BackendCreationException {
		if (!live) {
			throw unavailable("canvas not bound to WebGPU (" + reason + ")");
		}
	}

	@Override
	public void unloadLibrary() {
		fallBackToGl("device creation failed");
	}

	@Override
	public long createWindow(final @Nullable String title, final int width, final int height, final long flags) {
		return SDLVideo.SDL_CreateWindow(title, width, height, flags);
	}

	@Override
	public GpuDevice createDevice(final GpuDebugOptions debugOptions) throws BackendCreationException {
		if (!live) {
			throw unavailable("canvas not bound (probe state: " + WebGpuNatives.state() + ")");
		}
		GlslCompiler.reflectionOnly = true;
		try {
			return new FrontendGpuDevice(new WebGpuDevice());
		} catch (RuntimeException e) {
			System.out.println("[26.3-web] webgpu: device creation failed: " + e);
			fallBackToGl("device creation failed: " + e);
			throw unavailable(String.valueOf(e));
		}
	}

	private static BackendCreationException unavailable(final String detail) {
		return new BackendCreationException("WebGPU unavailable: " + detail, BackendCreationException.Reason.OPENGL_MISSING);
	}

	public static final int STARTUP_FRAMES = 300;
	private static int reportedErrors;

	public static boolean startupHealthy(int frames) {
		String state = WebGpuNatives.state();
		if (state.startsWith("lost")) {
			reloadWithWebGl("device " + state);
			return false;
		}
		int errors = WebGpuNatives.errorCount();
		if (errors > reportedErrors) {
			reportedErrors = errors;
			if (frames < STARTUP_FRAMES) {
				reloadWithWebGl(errors + " WebGPU error(s), last: " + WebGpuNatives.lastError());
				return false;
			}
		}
		return true;
	}

	public static void reloadWithWebGl(String why) {
		System.out.println("[26.3-web] graphics: WebGPU failed after start (" + why + "); reloading with WebGL2");
		reloadWithWebGlJs(why);
	}

	@JSBody(script = "var p = new URLSearchParams(window.location.search);\n"
		+ "var fb = false; try { fb = !!sessionStorage.getItem('minecraft-web.webgpuFailed'); } catch (e) {}\n"
		+ "if (p.has('webgl') && p.get('webgl') !== '0') { return -1; }\n"
		+ "if (fb) { return -1; }\n"
		+ "if (p.has('webgpu')) { return p.get('webgpu') === '0' ? -1 : 1; }\n"
		+ "return 0;")
	private static native int overrideJs();

	@JSBody(script = "try { return !!sessionStorage.getItem('minecraft-web.webgpuFailed'); } catch (e) { return false; }")
	private static native boolean fallbackMarked();

	@JSBody(script = "try { return sessionStorage.getItem('minecraft-web.webgpuFailed') || ''; } catch (e) { return ''; }")
	private static native String fallbackReason();

	@JSBody(params = {"why"}, script = "try { sessionStorage.setItem('minecraft-web.webgpuFailed', String(why).slice(0, 200)); } catch (e) {}\n"
		+ "window.location.reload();")
	private static native void reloadWithWebGlJs(String why);

	@JSBody(script = "return !!(navigator.gpu && navigator.gpu.requestAdapter);")
	private static native boolean hasWebGpu();

	@JSBody(script = "if (window.__minecraftWebGpuState) { return; }\n"
		+ "window.__minecraftWebGpuState = 'probing';\n"
		+ "var t0 = performance.now();\n"
		+ "navigator.gpu.requestAdapter({powerPreference: 'high-performance'}).then(function (adapter) {\n"
		+ "  if (window.__minecraftWebGpuState !== 'probing') { return; }\n"
		+ "  if (!adapter) { window.__minecraftWebGpuState = 'no adapter'; return; }\n"
		+ "  var limits = {};\n"
		+ "  for (var k in adapter.limits) { limits[k] = adapter.limits[k]; }\n"
		+ "  window.__minecraftWebGpuLimits = limits;\n"
		+ "  window.__minecraftWebGpuAdapterInfo = adapter.info || {};\n"
		+ "  if (adapter.isFallbackAdapter) { window.__minecraftWebGpuState = 'software fallback adapter'; return; }\n"
		+ "  return adapter.requestDevice().then(function (device) {\n"
		+ "    if (window.__minecraftWebGpuState !== 'probing') { try { device.destroy(); } catch (e) {} return; }\n"
		+ "    window.__minecraftWebGpuDevice = device;\n"
		+ "    window.__minecraftWebGpuState = 'ready';\n"
		+ "    window.__minecraftWebGpuProbeMs = Math.round(performance.now() - t0);\n"
		+ "    device.lost.then(function (info) { window.__minecraftWebGpuState = 'lost: ' + info.reason; });\n"
		+ "  });\n"
		+ "}).catch(function (e) {\n"
		+ "  if (window.__minecraftWebGpuState === 'probing') { window.__minecraftWebGpuState = 'error: ' + e; }\n"
		+ "});")
	private static native void startProbeJs();
}
