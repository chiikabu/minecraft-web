// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.webgpu;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebGpuNatives {
	private WebGpuNatives() {
	}

	@JSFunctor
	interface ReadyFn extends JSObject {
		void run();
	}

	@JSFunctor
	interface ReadFn extends JSObject {
		void run(Int8Array data);
	}

	static final int BUF_MAP_READ = 1;
	static final int BUF_MAP_WRITE = 2;
	static final int BUF_COPY_SRC = 4;
	static final int BUF_COPY_DST = 8;
	static final int BUF_INDEX = 16;
	static final int BUF_VERTEX = 32;
	static final int BUF_UNIFORM = 64;
	static final int BUF_STORAGE = 128;
	static final int BUF_INDIRECT = 256;

	static final int TEX_COPY_SRC = 1;
	static final int TEX_COPY_DST = 2;
	static final int TEX_BINDING = 4;
	static final int TEX_RENDER = 16;

	static final int VIS_VERTEX = 1;
	static final int VIS_FRAGMENT = 2;

	@JSBody(params = {}, script = "return !!(navigator.gpu && navigator.gpu.requestAdapter);")
	static native boolean hasGpu();

	@JSBody(params = {}, script = "return window.__minecraftWebGpuState || 'not-started';")
	static native String state();

	@JSBody(params = {}, script = "return !!window.__minecraftWebGpuDevice;")
	static native boolean hasDevice();

	@JSBody(params = {}, script =
		"if (window.__minecraftWebGpuState) { return; }\n"
		+ "var early = window.__minecraftEarlyWebGpu;\n"
		+ "if (!early || typeof early.then !== 'function') { return; }\n"
		+ "window.__minecraftWebGpuState = 'probing';\n"
		+ "var t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : 0;\n"
		+ "function applyAdapter(adapter) {\n"
		+ "  var limits = {};\n"
		+ "  if (adapter.limits) { for (var k in adapter.limits) { limits[k] = adapter.limits[k]; } }\n"
		+ "  window.__minecraftWebGpuLimits = limits;\n"
		+ "  if (adapter.info || !window.__minecraftWebGpuAdapterInfo) { window.__minecraftWebGpuAdapterInfo = adapter.info || {}; }\n"
		+ "}\n"
		+ "function applyDevice(device) {\n"
		+ "  if (window.__minecraftWebGpuState !== 'probing') { try { device.destroy(); } catch (e) {} return; }\n"
		+ "  window.__minecraftWebGpuDevice = device;\n"
		+ "  window.__minecraftWebGpuState = 'ready';\n"
		+ "  window.__minecraftWebGpuProbeMs = Math.round(((typeof performance !== 'undefined' && performance.now) ? performance.now() : 0) - t0);\n"
		+ "  if (device.lost && typeof device.lost.then === 'function') {\n"
		+ "    device.lost.then(function (info) { window.__minecraftWebGpuState = 'lost: ' + (info ? info.reason : 'unknown'); });\n"
		+ "  }\n"
		+ "}\n"
		+ "function buildDeviceDesc(adapter) {\n"
		+ "  var desc = {};\n"
		+ "  var reqFeatures = [];\n"
		+ "  var wantFeatures = ['indirect-first-instance', 'timestamp-query', 'float32-filterable', 'depth-clip-control', 'bgra8unorm-storage'];\n"
		+ "  if (adapter.features && typeof adapter.features.has === 'function') {\n"
		+ "    for (var fi = 0; fi < wantFeatures.length; fi++) {\n"
		+ "      if (adapter.features.has(wantFeatures[fi])) { reqFeatures.push(wantFeatures[fi]); }\n"
		+ "    }\n"
		+ "  }\n"
		+ "  if (reqFeatures.length) { desc.requiredFeatures = reqFeatures; }\n"
		+ "  var reqLimits = {};\n"
		+ "  var hasLimits = false;\n"
		+ "  var desired = {\n"
		+ "    maxBufferSize: 536870912,\n"
		+ "    maxStorageBufferBindingSize: 268435456,\n"
		+ "    maxTextureDimension2D: 16384,\n"
		+ "    maxUniformBufferBindingSize: 262144,\n"
		+ "    maxComputeWorkgroupStorageSize: 32768,\n"
		+ "    maxComputeInvocationsPerWorkgroup: 1024,\n"
		+ "    maxColorAttachmentBytesPerSample: 64\n"
		+ "  };\n"
		+ "  if (adapter.limits) {\n"
		+ "    for (var lk in desired) {\n"
		+ "      var av = adapter.limits[lk];\n"
		+ "      if (typeof av === 'number' && isFinite(av) && av > 0) {\n"
		+ "        reqLimits[lk] = Math.min(desired[lk], av);\n"
		+ "        hasLimits = true;\n"
		+ "      }\n"
		+ "    }\n"
		+ "  }\n"
		+ "  if (hasLimits) { desc.requiredLimits = reqLimits; }\n"
		+ "  return desc;\n"
		+ "}\n"
		+ "early.then(function (res) {\n"
		+ "  if (window.__minecraftWebGpuState !== 'probing') { return; }\n"
		+ "  if (!res) { window.__minecraftWebGpuState = window.__minecraftWebGpuDevice ? 'ready' : 'no adapter'; return; }\n"
		+ "  if (typeof res.requestDevice === 'function') {\n"
		+ "    applyAdapter(res);\n"
		+ "    if (res.isFallbackAdapter) { window.__minecraftWebGpuState = 'software fallback adapter'; return; }\n"
		+ "    var desc = buildDeviceDesc(res);\n"
		+ "    return res.requestDevice(desc).catch(function () { return res.requestDevice(); }).then(applyDevice);\n"
		+ "  }\n"
		+ "  if (res.device && res.device.queue) {\n"
		+ "    if (res.adapter) {\n"
		+ "      applyAdapter(res.adapter);\n"
		+ "      if (res.adapter.isFallbackAdapter) { try { res.device.destroy(); } catch (e) {} window.__minecraftWebGpuState = 'software fallback adapter'; return; }\n"
		+ "    }\n"
		+ "    applyDevice(res.device);\n"
		+ "    return;\n"
		+ "  }\n"
		+ "  if (res.queue && res.limits) {\n"
		+ "    applyDevice(res);\n"
		+ "    return;\n"
		+ "  }\n"
		+ "  window.__minecraftWebGpuState = window.__minecraftWebGpuDevice ? 'ready' : 'no adapter';\n"
		+ "}).catch(function (e) {\n"
		+ "  if (window.__minecraftWebGpuState === 'probing') { window.__minecraftWebGpuState = 'error: ' + e; }\n"
		+ "});")
	public static native void hookEarlyProbe();

	@JSBody(params = {"fn", "timeoutMs"}, script =
		"var cur = window.__minecraftWebGpuState || 'not-started';\n"
		+ "if (cur !== 'probing') { fn(); return; }\n"
		+ "var done = false;\n"
		+ "var timer = null;\n"
		+ "function finish() {\n"
		+ "  if (done) { return; }\n"
		+ "  done = true;\n"
		+ "  if (timer !== null) { clearTimeout(timer); timer = null; }\n"
		+ "  fn();\n"
		+ "}\n"
		+ "function check() {\n"
		+ "  if ((window.__minecraftWebGpuState || 'not-started') !== 'probing') { finish(); }\n"
		+ "}\n"
		+ "timer = setTimeout(function () {\n"
		+ "  timer = null;\n"
		+ "  if (!done && (window.__minecraftWebGpuState || 'not-started') === 'probing') {\n"
		+ "    window.__minecraftWebGpuState = 'timeout after ' + timeoutMs + ' ms';\n"
		+ "  }\n"
		+ "  finish();\n"
		+ "}, timeoutMs);\n"
		+ "var watched = false;\n"
		+ "try {\n"
		+ "  var waiters = window.__minecraftWebGpuWaiters || (window.__minecraftWebGpuWaiters = []);\n"
		+ "  waiters.push(finish);\n"
		+ "  if (!window.__minecraftWebGpuStateHooked) {\n"
		+ "    var val = window.__minecraftWebGpuState;\n"
		+ "    Object.defineProperty(window, '__minecraftWebGpuState', {\n"
		+ "      configurable: true,\n"
		+ "      enumerable: true,\n"
		+ "      get: function () { return val; },\n"
		+ "      set: function (next) {\n"
		+ "        val = next;\n"
		+ "        if (next && next !== 'probing') {\n"
		+ "          var list = window.__minecraftWebGpuWaiters;\n"
		+ "          if (list && list.length) {\n"
		+ "            window.__minecraftWebGpuWaiters = [];\n"
		+ "            Promise.resolve().then(function () {\n"
		+ "              for (var i = 0; i < list.length; i++) { list[i](); }\n"
		+ "            });\n"
		+ "          }\n"
		+ "        }\n"
		+ "      }\n"
		+ "    });\n"
		+ "    window.__minecraftWebGpuStateHooked = true;\n"
		+ "  }\n"
		+ "  watched = true;\n"
		+ "} catch (e) {}\n"
		+ "var early = window.__minecraftEarlyWebGpu;\n"
		+ "if (early && typeof early.then === 'function') {\n"
		+ "  early.then(check, check);\n"
		+ "  watched = true;\n"
		+ "}\n"
		+ "if (!watched) {\n"
		+ "  function poll() {\n"
		+ "    if (done) { return; }\n"
		+ "    if ((window.__minecraftWebGpuState || 'not-started') !== 'probing') { finish(); return; }\n"
		+ "    setTimeout(poll, 10);\n"
		+ "  }\n"
		+ "  setTimeout(poll, 10);\n"
		+ "}")
	static native void waitSettled(ReadyFn fn, int timeoutMs);

	@JSBody(params = {}, script = "return !!window.__mcgpu;")
	private static native boolean installed();

	@JSBody(script = HELPER)
	private static native void installHelper();

	static void install() {
		if (!installed()) {
			installHelper();
		}
	}

	@JSBody(params = {}, script =
		"var canvas = document.getElementById('game_frame');\n"
		+ "if (!canvas || !window.__minecraftWebGpuDevice || !window.__mcgpu) { return false; }\n"
		+ "var ctx = canvas.getContext('webgpu');\n"
		+ "if (!ctx) { return false; }\n"
		+ "var format = navigator.gpu.getPreferredCanvasFormat();\n"
		+ "ctx.configure({ device: window.__minecraftWebGpuDevice, format: format, alphaMode: 'opaque' });\n"
		+ "window.__minecraftWebGpuContext = ctx;\n"
		+ "window.__minecraftWebGpuCanvasFormat = format;\n"
		+ "window.__mcgpu.init(window.__minecraftWebGpuDevice, ctx, format);\n"
		+ "return true;")
	static native boolean configureCanvas();

	@JSBody(params = {}, script =
		"try { if (window.__minecraftWebGpuContext) { window.__minecraftWebGpuContext.unconfigure(); } } catch (e) {}\n"
		+ "try { if (window.__minecraftWebGpuDevice) { window.__minecraftWebGpuDevice.destroy(); } } catch (e) {}\n"
		+ "window.__minecraftWebGpuContext = null; window.__minecraftWebGpuDevice = null;\n"
		+ "window.__minecraftWebGpuState = 'released';")
	static native void release();

	@JSBody(params = {}, script =
		"var info = window.__minecraftWebGpuAdapterInfo || {};\n"
		+ "return (info.vendor || 'unknown') + '|' + (info.device || info.description || 'WebGPU') + '|' + (info.architecture || '');")
	static native String adapterIdentity();

	@JSBody(params = {"key", "fallback"}, script =
		"var d = window.__minecraftWebGpuDevice;\n"
		+ "var l = d && d.limits;\n"
		+ "var v = l ? l[key] : undefined;\n"
		+ "return (typeof v === 'number' && isFinite(v)) ? v : fallback;")
	static native double limit(String key, double fallback);

	@JSBody(params = {}, script =
		"var d = window.__minecraftWebGpuDevice;\n"
		+ "return !!(d && d.features && typeof d.features.has === 'function' && d.features.has('indirect-first-instance'));")
	static native boolean hasIndirectFirstInstance();

	@JSBody(params = {}, script =
		"try {\n"
		+ "  var p = new URLSearchParams(window.location.search);\n"
		+ "  var v = p.get('indirect');\n"
		+ "  return v ? String(v).toLowerCase() : '';\n"
		+ "} catch (e) { return ''; }")
	static native String indirectModeOverride();

	@JSBody(params = {}, script = "return window.__mcgpu ? window.__mcgpu.errors : 0;")
	static native int errorCount();

	@JSBody(params = {}, script = "return window.__mcgpu ? window.__mcgpu.lastError : '';")
	static native String lastError();

	@JSBody(params = {"size", "usage", "label"}, script = "return window.__mcgpu.buffer(size, usage, label);")
	static native JSObject createBuffer(int size, int usage, String label);

	@JSBody(params = {"width", "height", "layers", "mips", "format", "usage", "label"}, script =
		"return window.__mcgpu.texture(width, height, layers, mips, format, usage, label);")
	static native JSObject createTexture(int width, int height, int layers, int mips, String format, int usage, String label);

	@JSBody(params = {"tex", "baseMip", "mips", "dimension"}, script = "return window.__mcgpu.view(tex, baseMip, mips, dimension);")
	static native JSObject createView(JSObject tex, int baseMip, int mips, String dimension);

	@JSBody(params = {"addressU", "addressV", "mag", "min", "aniso", "hasMaxLod", "maxLod"}, script =
		"return window.__mcgpu.sampler(addressU, addressV, mag, min, aniso, hasMaxLod, maxLod);")
	static native JSObject createSampler(String addressU, String addressV, String mag, String min, int aniso, boolean hasMaxLod, float maxLod);

	@JSBody(params = {"obj"}, script = "if (obj && obj.destroy) { window.__mcgpu.trash.push(obj); }")
	static native void destroy(JSObject obj);

	@JSBody(params = {"buf", "off", "data"}, script = "var G = window.__mcgpu; if (G.tracing && G.frames < 2) { G.tr('write ' + (buf.label || 'buf') + '#' + buf.size + '@' + off + '+' + data.length + ' [' + G.f32(data, 0, Math.min(20, data.length >> 2)) + ']'); } G.dev.queue.writeBuffer(buf, off, data.buffer, data.byteOffset, data.length);")
	static native void writeBuffer(JSObject buf, int off, Int8Array data);

	@JSBody(params = {"staging", "stagingOff", "buf", "off", "len"}, script = "var G = window.__mcgpu; if (G.tracing && G.frames < 2) { G.tr('copy ' + (staging.label || 'stg') + '@' + stagingOff + ' -> ' + (buf.label || 'buf') + '#' + buf.size + '@' + off + '+' + len); } G.encoder().copyBufferToBuffer(staging, stagingOff, buf, off, len);")
	static native void copyBuffer(JSObject staging, int stagingOff, JSObject buf, int off, int len);

	@JSBody(params = {"tex", "mip", "x", "y", "layer", "w", "h", "data", "bytesPerRow"}, script =
		"window.__mcgpu.dev.queue.writeTexture({ texture: tex, mipLevel: mip, origin: { x: x, y: y, z: layer } }, data.buffer,"
		+ " { offset: data.byteOffset, bytesPerRow: bytesPerRow, rowsPerImage: h }, { width: w, height: h, depthOrArrayLayers: 1 });")
	static native void writeTexture(JSObject tex, int mip, int x, int y, int layer, int w, int h, Int8Array data, int bytesPerRow);

	@JSBody(params = {"buf", "off", "addr", "len"}, script = "var M = globalThis.__teavmMemory; if (!M) { throw new Error('teavm.memory not published'); }\n"
		+ "window.__mcgpu.dev.queue.writeBuffer(buf, off, M.buffer, addr >>> 0, len);")
	static native void writeBufferMem(JSObject buf, int off, int addr, int len);

	@JSBody(params = {"tex", "mip", "x", "y", "layer", "w", "h", "addr", "bytesPerRow"}, script = "var M = globalThis.__teavmMemory; if (!M) { throw new Error('teavm.memory not published'); }\n"
		+ "window.__mcgpu.dev.queue.writeTexture({ texture: tex, mipLevel: mip, origin: { x: x, y: y, z: layer } }, M.buffer, { offset: addr >>> 0, bytesPerRow: bytesPerRow, rowsPerImage: h }, { width: w, height: h, depthOrArrayLayers: 1 });")
	static native void writeTextureMem(JSObject tex, int mip, int x, int y, int layer, int w, int h, int addr, int bytesPerRow);

	@JSBody(params = {"addr", "data", "n"}, script = "var M = globalThis.__teavmMemory; if (!M) { throw new Error('teavm.memory not published'); }\n"
		+ "new Int8Array(M.buffer, addr >>> 0, n).set(n === data.length ? data : data.subarray(0, n));")
	static native void copyToMem(int addr, Int8Array data, int n);

	@JSBody(params = {"src", "sm", "sx", "sy", "dst", "dm", "dx", "dy", "w", "h"}, script =
		"window.__mcgpu.encoder().copyTextureToTexture({ texture: src, mipLevel: sm, origin: { x: sx, y: sy, z: 0 } },"
		+ " { texture: dst, mipLevel: dm, origin: { x: dx, y: dy, z: 0 } }, { width: w, height: h, depthOrArrayLayers: 1 });")
	static native void copyTexture(JSObject src, int sm, int sx, int sy, JSObject dst, int dm, int dx, int dy, int w, int h);

	@JSBody(params = {"tex", "mip", "x", "y", "w", "h", "bpp", "fn"}, script = "window.__mcgpu.readTexture(tex, mip, x, y, w, h, bpp, fn);")
	static native void readTexture(JSObject tex, int mip, int x, int y, int w, int h, int bpp, ReadFn fn);

	@JSBody(params = {"tex", "r", "g", "b", "a"}, script = "window.__mcgpu.clearColor(tex, r, g, b, a);")
	static native void clearColor(JSObject tex, float r, float g, float b, float a);

	@JSBody(params = {"tex", "depth"}, script = "window.__mcgpu.clearDepth(tex, depth);")
	static native void clearDepth(JSObject tex, float depth);

	@JSBody(params = {"tex", "r", "g", "b", "a", "depthTex", "depth"}, script = "window.__mcgpu.clearColorAndDepth(tex, r, g, b, a, depthTex, depth);")
	static native void clearColorAndDepth(JSObject tex, float r, float g, float b, float a, JSObject depthTex, float depth);

	@JSBody(params = {"tex", "depthTex", "r", "g", "b", "a", "depth", "x", "y", "w", "h", "mip"}, script = "window.__mcgpu.clearRegion(tex, depthTex, r, g, b, a, depth, x, y, w, h, mip);")
	static native void clearRegion(JSObject tex, JSObject depthTex, float r, float g, float b, float a, float depth, int x, int y, int w, int h, int mip);

	@JSBody(params = {"code", "label"}, script = "return window.__mcgpu.module(code, label);")
	static native JSObject createModule(String code, String label);

	@JSBody(params = {"entriesJson", "label"}, script = "return window.__mcgpu.layout(entriesJson, label);")
	static native JSObject createLayout(String entriesJson, String label);

	@JSBody(params = {"vert", "frag", "layout", "descJson", "label"}, script = "return window.__mcgpu.pipeline(vert, frag, layout, descJson, label);")
	static native JSObject createPipeline(JSObject vert, JSObject frag, JSObject layout, String descJson, String label);

	@JSBody(params = {}, script = "window.__mcgpu.bg = [];")
	static native void bindGroupBegin();

	@JSBody(params = {"binding", "buf", "off", "size"}, script = "window.__mcgpu.bg.push({ binding: binding, resource: { buffer: buf, offset: off, size: Math.min((size + 15) & ~15, buf.size - off) } });")
	static native void bindGroupBuffer(int binding, JSObject buf, int off, int size);

	@JSBody(params = {"binding", "view"}, script = "window.__mcgpu.bg.push({ binding: binding, resource: view });")
	static native void bindGroupTexture(int binding, JSObject view);

	@JSBody(params = {"binding", "sampler"}, script = "window.__mcgpu.bg.push({ binding: binding, resource: sampler });")
	static native void bindGroupSampler(int binding, JSObject sampler);

	@JSBody(params = {"layout"}, script = "var G = window.__mcgpu; var g = G.dev.createBindGroup({ layout: layout.bgl, entries: G.bg });"
		+ " if (G.tracing) { g.__desc = G.bg.map(function (e) { return e.binding + '=' + G.desc(e.resource); }).join(' '); } G.bg = null; return g;")
	static native JSObject bindGroupEnd(JSObject layout);

	@JSBody(params = {}, script = "window.__mcgpu.passBegin();")
	static native void passBegin();

	@JSBody(params = {"view", "tex", "clear", "r", "g", "b", "a"}, script = "window.__mcgpu.passColor(view, tex, clear, r, g, b, a);")
	static native void passColor(JSObject view, JSObject tex, boolean clear, float r, float g, float b, float a);

	@JSBody(params = {}, script = "window.__mcgpu.pc.push(null);")
	static native void passColorNull();

	@JSBody(params = {"view", "clear", "depth"}, script = "window.__mcgpu.passDepth(view, clear, depth);")
	static native void passDepth(JSObject view, boolean clear, float depth);

	@JSBody(params = {"label"}, script = "return window.__mcgpu.passStart(label);")
	static native JSObject passStart(String label);

	@JSBody(params = {}, script = "window.__mcgpu.passEnd();")
	static native void passEnd();

	static final int CMD_PIPELINE = 1;
	static final int CMD_BIND_GROUP = 2;
	static final int CMD_VERTEX_BUFFER = 3;
	static final int CMD_INDEX_BUFFER = 4;
	static final int CMD_SCISSOR = 5;
	static final int CMD_DRAW_INDEXED = 6;
	static final int CMD_DRAW = 7;
	static final int CMD_MULTI_DRAW_INDEXED_INDIRECT = 8;
	static final int CMD_MULTI_DRAW_INDEXED_MEM = 9;
	static final int CMD_MULTI_DRAW_INDIRECT = 10;
	static final int CMD_MULTI_DRAW_MEM = 11;

	@JSBody(params = {"obj"}, script = "return window.__mcgpu.put(obj);")
	static native int put(JSObject obj);

	@JSBody(params = {"id"}, script = "window.__mcgpu.dropIds.push(id);")
	static native void dropLater(int id);

	@JSBody(params = {"pass", "addr", "n"}, script = "window.__mcgpu.replay(pass, addr, n);")
	static native void replay(JSObject pass, int addr, int n);

	@JSBody(params = {}, script = "window.__mcgpu.submit();")
	static native void submit();

	@JSBody(params = {"view"}, script = "return window.__mcgpu.blit(view);")
	static native boolean blit(JSObject view);

	@JSBody(params = {}, script = "return window.__mcgpu.draws;")
	static native int drawCount();

	@JSBody(params = {"n"}, script = "var a = new Int8Array(n); return a;")
	static native Int8Array alloc(int n);

	static final String HELPER = ""
		+ "var G = window.__mcgpu = {};\n"
		+ "G.dev = null; G.ctx = null; G.fmt = 'bgra8unorm'; G.enc = null; G.pass = null; G.bg = null; G.pc = null; G.pd = null;\n"
		+ "G.u32 = new Uint32Array(64); G.memBuf = null; G.memI32 = null; G.memU32 = null; G.errors = 0; G.lastError = ''; G.draws = 0; G.frames = 0; G.reads = []; G.blitState = null;\n"
		+ "G.trash = []; G.blits = 0; G.passes = 0; G.lastColorTex = null;\n"
		+ "G.traceLog = []; G.tracing = /[?&]gputrace/.test(location.search);\n"
		+ "G.tr = function (line) { if (G.tracing && G.frames < 2 && G.traceLog.length < 600) { G.traceLog.push(G.frames + ': ' + line); } };\n"
		+ "G.desc = function (r) { if (!r) { return 'null'; } if (r.buffer) { return (r.buffer.label || 'buf') + '#' + r.buffer.size + '@' + (r.offset || 0) + '+' + (r.size || '*'); } return r.label || (r.constructor && r.constructor.name) || 'res'; };\n"
		+ "G.f32 = function (data, off, n) { try { var b = data.buffer || data; var o = (data.byteOffset || 0) + off; var f = new Float32Array(b.slice(o, o + n * 4)); return Array.prototype.slice.call(f).map(function (v) { return +v.toFixed(3); }).join(','); } catch (e) { return '?' + e; } };\n"
		+ "G.init = function (dev, ctx, fmt) {\n"
		+ "  G.dev = dev; G.ctx = ctx; G.fmt = fmt;\n"
		+ "  G.clearBuf = null; G.clearMod = null; G.clearPipes = {}; G.clearSlot = 0; G.blitState = null;\n"
		+ "  dev.addEventListener('uncapturederror', function (e) {\n"
		+ "    G.errors++; G.lastError = String(e.error && e.error.message || e.error);\n"
		+ "    if (G.errors <= 20) { console.error('[webgpu] ' + G.lastError); }\n"
		+ "  });\n"
		+ "  dev.lost.then(function (info) { G.lastError = 'device lost: ' + info.reason + ' ' + info.message; G.errors++; console.error('[webgpu] ' + G.lastError); });\n"
		+ "};\n"
		+ "G.reg = [null]; G.freeIds = []; G.dropIds = [];\n"
		+ "G.put = function (o) { var id = G.freeIds.length ? G.freeIds.pop() : G.reg.length; G.reg[id] = o; return id; };\n"
		+ "G.replay = function (pass, addr, n) {\n"
		+ "  var buf = globalThis.__teavmMemory.buffer;\n"
		+ "  if (G.memBuf !== buf) { G.memBuf = buf; G.memI32 = new Int32Array(buf); G.memU32 = new Uint32Array(buf); }\n"
		+ "  var M = G.memI32, U = G.memU32, R = G.reg, u = G.u32, base = addr >>> 2, i = base, end = base + n, c, k, b, off, cnt, p;\n"
		+ "  while (i < end) {\n"
		+ "    switch (M[i]) {\n"
		+ "      case 1: pass.setPipeline(R[M[i + 1]]); i += 2; break;\n"
		+ "      case 2: c = M[i + 2]; if (c === 0) { pass.setBindGroup(0, R[M[i + 1]]); } else { for (k = 0; k < c; k++) { u[k] = M[i + 3 + k]; } pass.setBindGroup(0, R[M[i + 1]], u, 0, c); } i += 3 + c; break;\n"
		+ "      case 3: pass.setVertexBuffer(M[i + 1], R[M[i + 2]], M[i + 3], M[i + 4]); i += 5; break;\n"
		+ "      case 4: pass.setIndexBuffer(R[M[i + 1]], M[i + 2] ? 'uint32' : 'uint16', M[i + 3], M[i + 4]); i += 5; break;\n"
		+ "      case 5: pass.setScissorRect(M[i + 1], M[i + 2], M[i + 3], M[i + 4]); i += 5; break;\n"
		+ "      case 6: pass.drawIndexed(M[i + 1], M[i + 2], M[i + 3], M[i + 4], M[i + 5]); G.draws++; i += 6; break;\n"
		+ "      case 7: pass.draw(M[i + 1], M[i + 2], M[i + 3], M[i + 4]); G.draws++; i += 5; break;\n"
		+ "      case 8: b = R[M[i + 1]]; off = M[i + 2] >>> 0; cnt = M[i + 3]; for (k = 0; k < cnt; k++) { pass.drawIndexedIndirect(b, off + k * 20); } G.draws += cnt; i += 4; break;\n"
		+ "      case 9: p = M[i + 1] >>> 2; cnt = M[i + 2]; for (k = 0; k < cnt; k++, p += 5) { pass.drawIndexed(U[p], U[p + 1], U[p + 2], M[p + 3], U[p + 4]); } G.draws += cnt; i += 3; break;\n"
		+ "      case 10: b = R[M[i + 1]]; off = M[i + 2] >>> 0; cnt = M[i + 3]; for (k = 0; k < cnt; k++) { pass.drawIndirect(b, off + k * 16); } G.draws += cnt; i += 4; break;\n"
		+ "      case 11: p = M[i + 1] >>> 2; cnt = M[i + 2]; for (k = 0; k < cnt; k++, p += 4) { pass.draw(U[p], U[p + 1], U[p + 2], U[p + 3]); } G.draws += cnt; i += 3; break;\n"
		+ "      default: throw new Error('webgpu: bad command ' + M[i] + ' at ' + (i - base) + '/' + n);\n"
		+ "    }\n"
		+ "  }\n"
		+ "};\n"
		+ "G.encoder = function () { if (!G.enc) { G.enc = G.dev.createCommandEncoder(); } return G.enc; };\n"
		+ "G.submit = function () {\n"
		+ "  if (G.pass) { G.pass.end(); G.pass = null; }\n"
		+ "  if (G.enc) { G.dev.queue.submit([G.enc.finish()]); G.enc = null; }\n"
		+ "  G.frames++;\n"
		+ "  G.clearSlot = 0;\n"
		+ "  if (G.dropIds.length) { for (var di = 0; di < G.dropIds.length; di++) { var id = G.dropIds[di]; if (G.reg[id] !== null && G.reg[id] !== undefined) { G.reg[id] = null; G.freeIds.push(id); } } G.dropIds.length = 0; }\n"
		+ "  if (G.trash.length) { var trash = G.trash; G.trash = []; for (var i = 0; i < trash.length; i++) { try { trash[i].destroy(); } catch (e) {} } }\n"
		+ "  if (G.reads.length) { var reads = G.reads; G.reads = []; reads.forEach(G.finishRead); }\n"
		+ "};\n"
		+ "G.buffer = function (size, usage, label) { return G.dev.createBuffer({ size: size, usage: usage, label: label || undefined }); };\n"
		+ "G.texture = function (w, h, layers, mips, format, usage, label) {\n"
		+ "  return G.dev.createTexture({ size: { width: w, height: h, depthOrArrayLayers: layers }, mipLevelCount: mips, format: format, usage: usage, label: label || undefined });\n"
		+ "};\n"
		+ "G.view = function (tex, baseMip, mips, dimension) { return tex.createView({ dimension: dimension, baseMipLevel: baseMip, mipLevelCount: mips }); };\n"
		+ "G.sampler = function (au, av, mag, min, aniso, hasMaxLod, maxLod) {\n"
		+ "  var mip = min === 'nearest' ? 'nearest' : 'linear';\n"
		+ "  var d = { addressModeU: au, addressModeV: av, addressModeW: 'clamp-to-edge', magFilter: mag, minFilter: min, mipmapFilter: mip };\n"
		+ "  if (hasMaxLod) { d.lodMaxClamp = Math.max(0, maxLod); }\n"
		+ "  if (aniso > 1 && mag === 'linear' && min === 'linear' && mip === 'linear') { d.maxAnisotropy = aniso; }\n"
		+ "  return G.dev.createSampler(d);\n"
		+ "};\n"
		+ "G.module = function (code, label) {\n"
		+ "  var m = G.dev.createShaderModule({ code: code, label: label });\n"
		+ "  m.getCompilationInfo().then(function (info) {\n"
		+ "    for (var i = 0; i < info.messages.length; i++) { var msg = info.messages[i]; if (msg.type === 'error') { G.errors++; G.lastError = label + ': ' + msg.message; console.error('[webgpu] shader ' + label + ':' + msg.lineNum + ' ' + msg.message); } }\n"
		+ "  });\n"
		+ "  return m;\n"
		+ "};\n"
		+ "G.layout = function (entriesJson, label) {\n"
		+ "  var src = JSON.parse(entriesJson), entries = [];\n"
		+ "  for (var i = 0; i < src.length; i++) {\n"
		+ "    var e = src[i], out = { binding: e.b, visibility: e.v };\n"
		+ "    if (e.k === 'uniform') { out.buffer = { type: 'uniform', hasDynamicOffset: true }; }\n"
		+ "    else if (e.k === 'storage') { out.buffer = { type: 'read-only-storage', hasDynamicOffset: false }; }\n"
		+ "    else if (e.k === 'sampler') { out.sampler = { type: e.d ? 'non-filtering' : 'filtering' }; }\n"
		+ "    else if (e.k === 'texture_cube') { out.texture = { sampleType: 'float', viewDimension: 'cube' }; }\n"
		+ "    else { out.texture = { sampleType: e.d ? 'unfilterable-float' : 'float', viewDimension: '2d' }; }\n"
		+ "    entries.push(out);\n"
		+ "  }\n"
		+ "  var bgl = G.dev.createBindGroupLayout({ entries: entries, label: label });\n"
		+ "  return { bgl: bgl, pl: G.dev.createPipelineLayout({ bindGroupLayouts: [bgl], label: label }) };\n"
		+ "};\n"
		+ "G.pipeline = function (vert, frag, layout, descJson, label) {\n"
		+ "  var d = JSON.parse(descJson);\n"
		+ "  var desc = { label: label, layout: layout.pl,\n"
		+ "    vertex: { module: vert, entryPoint: 'main', buffers: d.buffers },\n"
		+ "    primitive: { topology: d.topology, cullMode: d.cull, frontFace: 'cw' },\n"
		+ "    multisample: { count: 1 } };\n"
		+ "  if (d.strip) { desc.primitive.stripIndexFormat = d.strip; }\n"
		+ "  if (d.depth) { desc.depthStencil = { format: d.depth.format, depthWriteEnabled: d.depth.write, depthCompare: d.depth.compare, depthBias: d.depth.bias, depthBiasSlopeScale: d.depth.slope }; }\n"
		+ "  if (frag) { desc.fragment = { module: frag, entryPoint: 'main', targets: d.targets }; }\n"
		+ "  G.dev.pushErrorScope('validation');\n"
		+ "  var p = G.dev.createRenderPipeline(desc);\n"
		+ "  G.dev.popErrorScope().then(function (err) { if (err) { G.errors++; G.lastError = label + ': ' + err.message; console.error('[webgpu] pipeline ' + label + ': ' + err.message); } });\n"
		+ "  return p;\n"
		+ "};\n"
		+ "G.passBegin = function () { G.pc = []; G.pd = null; };\n"
		+ "G.passColor = function (view, tex, clear, r, g, b, a) {\n"
		+ "  G.lastColorTex = tex;\n"
		+ "  G.pc.push({ view: view, loadOp: clear ? 'clear' : 'load', storeOp: 'store', clearValue: { r: r, g: g, b: b, a: a } });\n"
		+ "};\n"
		+ "G.passDepth = function (view, clear, depth) {\n"
		+ "  G.pd = { view: view, depthLoadOp: clear ? 'clear' : 'load', depthStoreOp: 'store', depthClearValue: depth };\n"
		+ "};\n"
		+ "G.passStart = function (label) {\n"
		+ "  if (G.pass) { G.pass.end(); G.pass = null; }\n"
		+ "  var d = { colorAttachments: G.pc, label: label };\n"
		+ "  if (G.pd) { d.depthStencilAttachment = G.pd; }\n"
		+ "  G.pass = G.encoder().beginRenderPass(d); G.passes++;\n"
		+ "  if (G.tracing && G.frames < 2) { G.tr('pass ' + label + ' color=' + d.colorAttachments.map(function (c) { return c ? (c.loadOp + ' ' + (c.view.label || '')) : 'null'; }).join(';') + ' depth=' + (d.depthStencilAttachment ? d.depthStencilAttachment.depthLoadOp + ' ' + d.depthStencilAttachment.depthClearValue : 'none')); G.pass = G.wrapPass(G.pass); }\n"
		+ "  G.pc = null; G.pd = null;\n"
		+ "  return G.pass;\n"
		+ "};\n"
		+ "G.passEnd = function () { if (G.pass) { G.pass.end(); G.pass = null; } };\n"
		+ "G.wrapPass = function (p) {\n"
		+ "  var w = { end: function () { p.end(); } };\n"
		+ "  w.setPipeline = function (x) { G.tr('  pipeline ' + x.label); p.setPipeline(x); };\n"
		+ "  w.setBindGroup = function (i, g, o, st, n) { G.tr('  bind ' + (g.__desc || '?') + ' dyn=[' + (o ? Array.prototype.slice.call(o, st || 0, (st || 0) + (n === undefined ? o.length : n)).join(',') : '') + ']'); p.setBindGroup.apply(p, arguments); };\n"
		+ "  w.setVertexBuffer = function (s, b, o, n) { G.tr('  vb' + s + ' ' + (b.label || 'buf') + '#' + b.size + '@' + o + '+' + n); p.setVertexBuffer(s, b, o, n); };\n"
		+ "  w.setIndexBuffer = function (b, f, o, n) { G.tr('  ib ' + (b.label || 'buf') + '#' + b.size + '@' + o + '+' + n + ' ' + f); p.setIndexBuffer(b, f, o, n); };\n"
		+ "  w.setScissorRect = function (x, y, ww, h) { G.tr('  scissor ' + [x, y, ww, h].join(',')); p.setScissorRect(x, y, ww, h); };\n"
		+ "  w.drawIndexed = function (c, i, f, bv, fi) { G.tr('  drawIndexed ' + [c, i, f, bv, fi].join(',')); p.drawIndexed(c, i, f, bv, fi); };\n"
		+ "  w.draw = function (c, i, f, fi) { G.tr('  draw ' + [c, i, f, fi].join(',')); p.draw(c, i, f, fi); };\n"
		+ "  w.drawIndexedIndirect = function (b, o) { G.tr('  drawIndexedIndirect ' + (b.label || 'buf') + '@' + o); p.drawIndexedIndirect(b, o); };\n"
		+ "  w.drawIndirect = function (b, o) { G.tr('  drawIndirect ' + (b.label || 'buf') + '@' + o); p.drawIndirect(b, o); };\n"
		+ "  return w;\n"
		+ "};\n"
		+ "G.mipView = function (tex, m) { var c = tex.__mipViews || (tex.__mipViews = []); return c[m] || (c[m] = tex.createView({ dimension: '2d', baseMipLevel: m, mipLevelCount: 1, baseArrayLayer: 0, arrayLayerCount: 1 })); };\n"
		+ "G.clearColor = function (tex, r, g, b, a) {\n"
		+ "  if (G.pass) { G.pass.end(); G.pass = null; }\n"
		+ "  for (var m = 0; m < tex.mipLevelCount; m++) {\n"
		+ "    var p = G.encoder().beginRenderPass({ colorAttachments: [{ view: G.mipView(tex, m), loadOp: 'clear', storeOp: 'store', clearValue: { r: r, g: g, b: b, a: a } }] });\n"
		+ "    p.end();\n"
		+ "  }\n"
		+ "};\n"
		+ "G.clearDepth = function (tex, depth) {\n"
		+ "  if (G.pass) { G.pass.end(); G.pass = null; }\n"
		+ "  for (var m = 0; m < tex.mipLevelCount; m++) {\n"
		+ "    var p = G.encoder().beginRenderPass({ colorAttachments: [], depthStencilAttachment: { view: G.mipView(tex, m), depthLoadOp: 'clear', depthStoreOp: 'store', depthClearValue: depth } });\n"
		+ "    p.end();\n"
		+ "  }\n"
		+ "};\n"
		+ "G.clearColorAndDepth = function (tex, r, g, b, a, depthTex, depth) {\n"
		+ "  if (G.pass) { G.pass.end(); G.pass = null; }\n"
		+ "  if (tex.mipLevelCount !== depthTex.mipLevelCount || tex.width !== depthTex.width || tex.height !== depthTex.height) {\n"
		+ "    G.clearColor(tex, r, g, b, a);\n"
		+ "    G.clearDepth(depthTex, depth);\n"
		+ "    return;\n"
		+ "  }\n"
		+ "  for (var m = 0; m < tex.mipLevelCount; m++) {\n"
		+ "    var p = G.encoder().beginRenderPass({\n"
		+ "      colorAttachments: [{ view: G.mipView(tex, m), loadOp: 'clear', storeOp: 'store', clearValue: { r: r, g: g, b: b, a: a } }],\n"
		+ "      depthStencilAttachment: { view: G.mipView(depthTex, m), depthLoadOp: 'clear', depthStoreOp: 'store', depthClearValue: depth }\n"
		+ "    });\n"
		+ "    p.end();\n"
		+ "  }\n"
		+ "};\n"
		+ "G.clearCode = 'struct C { color: vec4<f32>, depth: f32 }; @group(0) @binding(0) var<uniform> c: C;\\n'\n"
		+ "  + '@vertex fn vs(@builtin(vertex_index) i: u32) -> @builtin(position) vec4<f32> { let x = f32(i32(i & 1u) * 4 - 1); let y = f32(i32(i & 2u) * 2 - 1); return vec4<f32>(x, y, 0.0, 1.0); }\\n'\n"
		+ "  + 'struct FO { @location(0) color: vec4<f32>, @builtin(frag_depth) depth: f32 };\\n'\n"
		+ "  + '@fragment fn fs() -> FO { var o: FO; o.color = c.color; o.depth = c.depth; return o; }\\n';\n"
		+ "G.clearPipes = {}; G.clearMod = null; G.clearBuf = null; G.clearF32 = new Float32Array(8); G.clearDyn = new Uint32Array(1); G.clearSlot = 0;\n"
		+ "G.clearRegion = function (tex, depthTex, r, g, b, a, depth, x, y, w, h, mip) {\n"
		+ "  mip = mip || 0; var tw = Math.max(1, tex.width >> mip), th = Math.max(1, tex.height >> mip);\n"
		+ "  if (G.pass) { G.pass.end(); G.pass = null; }\n"
		+ "  var top = Math.max(0, Math.min(th, y));\n"
		+ "  var sx = Math.max(0, Math.min(tw, x)), sw = Math.max(0, Math.min(tw - sx, w)), sh = Math.max(0, Math.min(th - top, h));\n"
		+ "  if (sw === 0 || sh === 0) { return; }\n"
		+ "  if (!G.clearBuf) {\n"
		+ "    G.clearBuf = G.dev.createBuffer({ size: 4096, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST, label: 'region-clear' });\n"
		+ "  }\n"
		+ "  var key = tex.format + '|' + (depthTex ? depthTex.format : '-');\n"
		+ "  var st = G.clearPipes[key];\n"
		+ "  if (!st) {\n"
		+ "    var m = G.clearMod || (G.clearMod = G.dev.createShaderModule({ code: G.clearCode, label: 'region-clear' }));\n"
		+ "    var bgl = G.dev.createBindGroupLayout({ entries: [{ binding: 0, visibility: 3, buffer: { type: 'uniform', hasDynamicOffset: true } }] });\n"
		+ "    var desc = { label: 'region-clear', layout: G.dev.createPipelineLayout({ bindGroupLayouts: [bgl] }),\n"
		+ "      vertex: { module: m, entryPoint: 'vs' }, fragment: { module: m, entryPoint: 'fs', targets: [{ format: tex.format }] }, primitive: { topology: 'triangle-list' } };\n"
		+ "    if (depthTex) { desc.depthStencil = { format: depthTex.format, depthWriteEnabled: true, depthCompare: 'always' }; }\n"
		+ "    var stBg = G.dev.createBindGroup({ layout: bgl, entries: [{ binding: 0, resource: { buffer: G.clearBuf, offset: 0, size: 32 } }] });\n"
		+ "    st = G.clearPipes[key] = { bgl: bgl, bg: stBg, pipeline: G.dev.createRenderPipeline(desc) };\n"
		+ "  }\n"
		+ "  var f = G.clearF32; f[0] = r; f[1] = g; f[2] = b; f[3] = a; f[4] = depth; f[5] = 0; f[6] = 0; f[7] = 0;\n"
		+ "  var bg;\n"
		+ "  if (G.clearSlot < 16) {\n"
		+ "    var off = G.clearSlot * 256;\n"
		+ "    G.clearSlot++;\n"
		+ "    G.dev.queue.writeBuffer(G.clearBuf, off, f);\n"
		+ "    G.clearDyn[0] = off;\n"
		+ "    bg = st.bg;\n"
		+ "  } else {\n"
		+ "    var ub = G.dev.createBuffer({ size: 32, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST, label: 'region-clear' });\n"
		+ "    G.dev.queue.writeBuffer(ub, 0, f);\n"
		+ "    G.trash.push(ub);\n"
		+ "    bg = G.dev.createBindGroup({ layout: st.bgl, entries: [{ binding: 0, resource: { buffer: ub, offset: 0, size: 32 } }] });\n"
		+ "    G.clearDyn[0] = 0;\n"
		+ "  }\n"
		+ "  var d = { colorAttachments: [{ view: G.mipView(tex, mip), loadOp: 'load', storeOp: 'store' }] };\n"
		+ "  if (depthTex) { d.depthStencilAttachment = { view: G.mipView(depthTex, mip), depthLoadOp: 'load', depthStoreOp: 'store' }; }\n"
		+ "  var p = G.encoder().beginRenderPass(d);\n"
		+ "  p.setScissorRect(sx, top, sw, sh); p.setPipeline(st.pipeline); p.setBindGroup(0, bg, G.clearDyn, 0, 1); p.draw(3, 1, 0, 0); p.end();\n"
		+ "};\n"
		+ "G.readTexture = function (tex, mip, x, y, w, h, bpp, fn) {\n"
		+ "  if (G.pass) { G.pass.end(); G.pass = null; }\n"
		+ "  var row = w * bpp, padded = (row + 255) & ~255;\n"
		+ "  var staging = G.dev.createBuffer({ size: padded * h, usage: GPUBufferUsage.MAP_READ | GPUBufferUsage.COPY_DST, label: 'readback' });\n"
		+ "  G.encoder().copyTextureToBuffer({ texture: tex, mipLevel: mip, origin: { x: x, y: y, z: 0 } }, { buffer: staging, bytesPerRow: padded, rowsPerImage: h }, { width: w, height: h, depthOrArrayLayers: 1 });\n"
		+ "  G.reads.push({ staging: staging, row: row, padded: padded, h: h, fn: fn });\n"
		+ "};\n"
		+ "G.finishRead = function (r) {\n"
		+ "  r.staging.mapAsync(GPUMapMode.READ).then(function () {\n"
		+ "    var src = new Int8Array(r.staging.getMappedRange()), out = new Int8Array(r.row * r.h);\n"
		+ "    for (var i = 0; i < r.h; i++) { out.set(src.subarray(i * r.padded, i * r.padded + r.row), i * r.row); }\n"
		+ "    r.staging.unmap(); r.staging.destroy();\n"
		+ "    r.fn(out);\n"
		+ "  }, function (e) { G.errors++; G.lastError = 'readback: ' + e; console.error('[webgpu] readback failed', e); r.fn(new Int8Array(r.row * r.h)); });\n"
		+ "};\n"
		+ "G.blitCode = 'struct VO { @builtin(position) pos: vec4<f32>, @location(0) uv: vec2<f32> };\\n'\n"
		+ "  + '@vertex fn vs(@builtin(vertex_index) i: u32) -> VO { var o: VO; let x = f32(i32(i & 1u) * 4 - 1); let y = f32(i32(i & 2u) * 2 - 1);'\n"
		+ "  + ' o.pos = vec4<f32>(x, y, 0.0, 1.0); o.uv = vec2<f32>((x + 1.0) * 0.5, (y + 1.0) * 0.5); return o; }\\n'\n"
		+ "  + '@group(0) @binding(0) var t: texture_2d<f32>; @group(0) @binding(1) var s: sampler;\\n'\n"
		+ "  + '@fragment fn fs(i: VO) -> @location(0) vec4<f32> { return vec4<f32>(textureSample(t, s, i.uv).rgb, 1.0); }\\n';\n"
		+ "G.blit = function (view) {\n"
		+ "  if (!G.ctx) { return false; }\n"
		+ "  if (G.pass) { G.pass.end(); G.pass = null; }\n"
		+ "  var st = G.blitState;\n"
		+ "  if (!st) {\n"
		+ "    var m = G.dev.createShaderModule({ code: G.blitCode, label: 'present-blit' });\n"
		+ "    var bgl = G.dev.createBindGroupLayout({ entries: [{ binding: 0, visibility: 2, texture: { sampleType: 'float' } }, { binding: 1, visibility: 2, sampler: { type: 'non-filtering' } }] });\n"
		+ "    st = G.blitState = {\n"
		+ "      bgl: bgl,\n"
		+ "      pipeline: G.dev.createRenderPipeline({ label: 'present-blit', layout: G.dev.createPipelineLayout({ bindGroupLayouts: [bgl] }),\n"
		+ "        vertex: { module: m, entryPoint: 'vs' }, fragment: { module: m, entryPoint: 'fs', targets: [{ format: G.fmt }] }, primitive: { topology: 'triangle-list' } }),\n"
		+ "      sampler: G.dev.createSampler({ magFilter: 'nearest', minFilter: 'nearest' }), view: null, group: null };\n"
		+ "  }\n"
		+ "  if (st.view !== view) { st.view = view; st.group = G.dev.createBindGroup({ layout: st.bgl, entries: [{ binding: 0, resource: view }, { binding: 1, resource: st.sampler }] }); }\n"
		+ "  var p = G.encoder().beginRenderPass({ colorAttachments: [{ view: G.ctx.getCurrentTexture().createView(), loadOp: 'clear', storeOp: 'store', clearValue: { r: 0, g: 0, b: 0, a: 1 } }] });\n"
		+ "  p.setPipeline(st.pipeline); p.setBindGroup(0, st.group); p.draw(3, 1, 0, 0); p.end(); G.blits++;\n"
		+ "  return true;\n"
		+ "};\n"
		+ "G.debugStats = function () {\n"
		+ "  var t = G.lastColorTex; if (!t) { return Promise.resolve(null); }\n"
		+ "  return new Promise(function (ok) { G.readTexture(t, 0, 0, 0, t.width, t.height, 4, function (px) {\n"
		+ "    var sum = 0, nz = 0; for (var i = 0; i < px.length; i += 4) { var v = (px[i] & 255) + (px[i + 1] & 255) + (px[i + 2] & 255); sum += v; if (v > 30) { nz++; } }\n"
		+ "    ok({ w: t.width, h: t.height, format: t.format, mean: +(sum / (px.length / 4 * 3)).toFixed(1), nonblack: +(nz / (px.length / 4)).toFixed(3) });\n"
		+ "  }); G.submit(); });\n"
		+ "};\n";
}
