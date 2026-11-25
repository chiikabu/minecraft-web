// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebWorkerTransport {

	public static final String CH_PACKET = "NET";

	public static final String CH_PACKET_BATCH = "NETB";

	private WebWorkerTransport() {
	}

	public static JSObject startServer(final String moduleUrl, final boolean embedded) {
		if (packagedWorkers()) {
			return startPackagedServer(baseHref(), pageChunkTransportDebugRequested(),
				pageGenWorkersRequested(), pageFixedSeed());
		}
		if (!embedded && sharedModuleAvailable()) {
			return spawnWithSharedModule(sharedModuleBootstrap());
		}
		return embedded ? spawnWithEmbeddedAssets(embeddedBootstrapFor(moduleUrl)) : spawn(bootstrapFor(moduleUrl));
	}

	@JSBody(script = "return !!(globalThis.__minecraftWebSharedModule instanceof WebAssembly.Module) && !/[?&]splitwasm(?:[=&]|$)/.test(location.search || '');")
	private static native boolean sharedModuleAvailable();

	private static String sharedModuleBootstrap() {
		return "self.__minecraftBase = " + quote(baseHref()) + ";\n"
			+ "self.__minecraftEmbeddedWorker = true;\n"
			+ "self.__minecraftChunkTransportDebug = " + pageChunkTransportDebugRequested() + ";\n"
			+ pageFlagsForWorker()
			+ "self.onmessage = function(e) {\n"
			+ "  var d = e.data;\n"
			+ "  if (!d || !d.__minecraftWebSharedBoot) return;\n"
			+ "  self.onmessage = null;\n"
			+ "  self.__minecraftDeobf = !!d.deobf;\n"
			+ "  if (d.assets && d.index) { self.__minecraftAssetsIndex = d.index; self.__minecraftAssetsBinRaw = d.assets; self.__minecraftGenAssets = d.assets; self.__minecraftGenIndex = d.index; }\n"
			+ "  var base = new URL('wasm-gc/', self.__minecraftBase).href;\n"
			+ "  try { importScripts(new URL('minecraft-web-simd-kernels.js', self.__minecraftBase).href); } catch (e) {}\n"
			+ "  importScripts(base + 'classes263.wasm-runtime.js');\n"
			+ "  var fail = function(error) { setTimeout(function() { throw error; }, 0); };\n"
			+ compilePassthroughShim()
			+ "  try {\n"
			+ "    self.__minecraftServerModule = d.module;\n"
			+ "    Promise.resolve(self.TeaVM.wasmGC.load(d.module, {\n"
			+ "      stackDeobfuscator: {\n"
			+ "        enabled: self.__minecraftDeobf === true,\n"
			+ "        infoLocation: 'external',\n"
			+ "        path: base + 'classes263.wasm-deobfuscator.wasm',\n"
			+ "        externalInfoPath: base + 'classes263.wasm.teadbg'\n"
			+ "      }\n"
			+ "    }).then(function(teavm) {\n"
			+ "      try { var s = self.__mcWebSimd || self.__twitSimd; var m = self.__teavmMemory || (teavm && teavm.exports && teavm.exports['teavm.memory']); if (s && m) { s.bind(m); } } catch (e) {}\n"
			+ "      return teavm.exports.main([]);\n"
			+ "    })).catch(fail);\n"
			+ "  } catch (error) { fail(error); }\n"
			+ "};\n";
	}

	@JSBody(params = {"source"}, script =
		"var mod = globalThis.__minecraftWebSharedModule;"
		+ "var raw = globalThis.__minecraftWorkerAssetsRaw;"
		+ "var indexStr = globalThis.__minecraftWorkerAssetsIndex;"
		+ "globalThis.__minecraftWorkerAssetsRaw = undefined;"
		+ "globalThis.__minecraftWorkerAssetsIndex = undefined;"
		+ "var serverAssets = null, serverIndex = null, transfer = [];"
		+ "if (raw && raw.buffer && typeof indexStr === 'string') {"
		+ "  try {"
		+ "    var parsed = JSON.parse(indexStr), outIdx = {}, total = 0, keys = Object.keys(parsed);"
		+ "    for (var i = 0; i < keys.length; i++) {"
		+ "      var k = keys[i];"
		+ "      if (!k.startsWith('assets/minecraft/') || k.startsWith('assets/minecraft/lang/')) { total += parsed[k][1]; }"
		+ "    }"
		+ "    var compact = new Uint8Array(total), pos = 0;"
		+ "    for (var j = 0; j < keys.length; j++) {"
		+ "      var k2 = keys[j];"
		+ "      if (!k2.startsWith('assets/minecraft/') || k2.startsWith('assets/minecraft/lang/')) {"
		+ "        var span = parsed[k2], off = span[0], len = span[1];"
		+ "        compact.set(raw.subarray(off, off + len), pos);"
		+ "        outIdx[k2] = [pos, len];"
		+ "        pos += len;"
		+ "      }"
		+ "    }"
		+ "    serverAssets = compact; serverIndex = outIdx; transfer.push(compact.buffer);"
		+ "  } catch (e) { serverAssets = null; serverIndex = null; }"
		+ "}"
		+ "var blob = new Blob([source], {type: 'text/javascript'});"
		+ "var url = URL.createObjectURL(blob);"
		+ "var w; try { w = new Worker(url); } catch(e) { URL.revokeObjectURL(url); throw e; }"
		+ "w.__minecraftSourceUrl = url;"
		+ "var deobf = /[?&]deobf=1(?:[&]|$)/.test(location.search || '');"
		+ "try { w.postMessage({__minecraftWebSharedBoot: true, module: mod, index: serverIndex, assets: serverAssets, deobf: deobf}, transfer); }"
		+ "catch(e) { w.terminate(); URL.revokeObjectURL(url); throw e; }"
		+ "return w;")
	private static native JSObject spawnWithSharedModule(String source);

	public static JSObject startGeneration() {
		return packagedWorkers() ? startPackagedGeneration() : spawn(genBootstrap());
	}

	@JSBody(script = "return !!globalThis.__minecraftPackagedWorkers;")
	private static native boolean packagedWorkers();

	@JSBody(params = {"base", "debug", "workers", "seed"},
		script = "return globalThis.__minecraftPackagedWorkers.server(base, debug, workers, seed);")
	private static native JSObject startPackagedServer(String base, boolean debug, int workers, String seed);

	@JSBody(script = "return globalThis.__minecraftPackagedWorkers.generation();")
	private static native JSObject startPackagedGeneration();

	@JSBody(script = "return typeof Worker === 'function' && typeof Blob === 'function';")
	public static native boolean supported();

	public static String bootstrapFor(final String moduleUrl) {
		return "self.__minecraftBase = " + quote(baseHref()) + ";\n"
			+ "self.__minecraftChunkTransportDebug = " + pageChunkTransportDebugRequested() + ";\n"
			+ pageFlagsForWorker()
			+ "self.__minecraftServerModuleUrl = " + quote(moduleUrl) + ";\n"
			+ "importScripts(" + quote(moduleUrl) + ");\n"
			+ invokeMain();
	}

	public static String embeddedBootstrapFor(final String moduleUrl) {
		return "self.__minecraftBase = " + quote(baseHref()) + ";\n"
			+ "self.__minecraftEmbeddedWorker = true;\n"
			+ "self.__minecraftChunkTransportDebug = " + pageChunkTransportDebugRequested() + ";\n"
			+ pageFlagsForWorker()
			+ "self.onmessage = function(e) {\n"
			+ "  var d = e.data;\n"
			+ "  if (!d || !d.__minecraftWorkerAssets) return;\n"
			+ "  self.onmessage = null;\n"
			+ "  self.__minecraftAssetsIndex = d.index;\n"
			+ "  self.__minecraftAssetsBinRaw = d.assets;\n"
			+ "  self.__minecraftGenAssets = d.assets; self.__minecraftGenIndex = d.index;\n"
			+ "  self.__minecraftServerRuntimeSource = d.serverRuntime;\n"
			+ "  self.__minecraftGenRuntimeSource = d.genRuntime || null;\n"
			+ "  (0, eval)(d.serverRuntime);\n"
			+ "  if (!self.TeaVM || !self.TeaVM.wasmGC) throw new Error('TeaVM server WASM-GC runtime did not load');\n"
			+ "  var fail = function(error) { setTimeout(function() { throw error; }, 0); };\n"
			+ compilePassthroughShim()
			+ "  try { Promise.resolve(WebAssembly.compile(d.serverWasm, {builtins: ['js-string']}).then(function(module) {\n"
			+ "    self.__minecraftServerModule = module;\n"
			+ "    d.serverWasm = null;\n"
			+ "    return self.TeaVM.wasmGC.load(module, {stackDeobfuscator: {enabled: false}});\n"
			+ "  }).then(function(teavm) {\n"
			+ "    if (typeof teavm.exports.main !== 'function') throw new Error('TeaVM server WASM-GC module did not export main');\n"
			+ "    if (d.genWasm && d.genRuntime) { WebAssembly.compile(d.genWasm, {builtins: ['js-string']}).then(function(m) { self.__minecraftGenModule = m; d.genWasm = null; }).catch(fail); }\n"
			+ "    return teavm.exports.main([]);\n"
			+ "  })).catch(fail); } catch (error) { fail(error); }\n"
			+ "};\n";
	}

	private static String pageFlagsForWorker() {
		return "self.__minecraftGenWorkers = " + pageGenWorkersRequested() + ";\n"
			+ "self.__minecraftFixedSeed = " + quote(pageFixedSeed()) + ";\n"
			+ "self.__minecraftServerDrainMs = " + pageServerDrainMs() + ";\n"
			+ "self.__minecraftQuery = " + quote(pageQuery()) + ";\n"
			+ "self.__minecraftPageCores = " + pageCores() + ";\n";
	}

	@JSBody(script = "if (typeof self !== 'undefined' && typeof self.__minecraftQuery === 'string') return self.__minecraftQuery;"
		+ "try { return String(location.search || ''); } catch (e) { return ''; }")
	private static native String pageQuery();

	@JSBody(script = "if (typeof self !== 'undefined' && typeof self.__minecraftPageCores === 'number') return self.__minecraftPageCores;"
		+ "return (typeof navigator !== 'undefined' && navigator.hardwareConcurrency) || 2;")
	public static native int pageCores();

	public static int queryInt(final String name, final int fallback) {
		String query = pageQuery();
		int at = query.startsWith("?") ? 1 : 0;
		while (at < query.length()) {
			int end = query.indexOf('&', at);
			if (end < 0) {
				end = query.length();
			}
			if (query.startsWith(name + "=", at)) {
				try {
					return Integer.parseInt(query.substring(at + name.length() + 1, end));
				} catch (NumberFormatException e) {
					return fallback;
				}
			}
			at = end + 1;
		}
		return fallback;
	}

	public static String genBootstrap() {
		return "self.__minecraftBase = " + quote(baseUrlOrEmpty()) + ";\n"
			+ "self.__minecraftGenWorker = true;\n"
			+ "self.__minecraftGenWorkers = 0;\n"
			+ "self.__minecraftChunkTransportDebug = false;\n"
			+ "self.onmessage = function(e) {\n"
			+ "  var d = e.data;\n"
			+ "  if (!d || !d.__minecraftGenInit) return;\n"
			+ "  self.onmessage = null;\n"
			+ "  self.__minecraftGenWorkerIndex = d.workerIndex | 0;\n"
			+ "  if (d.assets) { self.__minecraftEmbeddedWorker = true; self.__minecraftAssetsIndex = d.index; self.__minecraftAssetsBinRaw = d.assets; }\n"
			+ "  var fail = function(error) { setTimeout(function() { throw error; }, 0); };\n"
			+ "  try {\n"
			+ "    if (d.module) {\n"
			+ "      if (d.runtime) { (0, eval)(d.runtime); }\n"
			+ "      else { try { importScripts(new URL('minecraft-web-simd-kernels.js', self.__minecraftBase).href); } catch (e) {} importScripts(new URL('wasm-gc/classes263.wasm-runtime.js', self.__minecraftBase).href); }\n"
			+ compilePassthroughShim()
			+ "      Promise.resolve(self.TeaVM.wasmGC.load(d.module, {stackDeobfuscator: {enabled: false}}).then(function(teavm) {\n"
			+ "        try { var s = self.__mcWebSimd || self.__twitSimd; var m = self.__teavmMemory || (teavm && teavm.exports && teavm.exports['teavm.memory']); if (s && m) { s.bind(m); } } catch (e) {}\n"
			+ "        return teavm.exports.main([]);\n"
			+ "      })).catch(fail);\n"
			+ "    } else if (d.moduleUrl) {\n"
			+ "      importScripts(d.moduleUrl);\n"
			+ "      Promise.resolve(main([])).catch(fail);\n"
			+ "    } else { throw new Error('gen worker got neither a module nor a module URL'); }\n"
			+ "  } catch (error) { fail(error); }\n"
			+ "};\n";
	}

	private static String compilePassthroughShim() {
		return "  var compile = WebAssembly.compile;\n"
			+ "  WebAssembly.compile = function(src, opts) { return src instanceof WebAssembly.Module ? Promise.resolve(src) : compile.call(WebAssembly, src, opts); };\n";
	}

	@JSBody(script = "return (typeof self !== 'undefined' && self.__minecraftBase) ? self.__minecraftBase : '';")
	private static native String baseUrlOrEmpty();

	@JSBody(script = "if (typeof self !== 'undefined' && typeof self.__minecraftGenWorkers === 'number') return self.__minecraftGenWorkers;"
		+ "try { var m = /[?&]genworkers=(\\d+)/.exec(location.search); return m ? parseInt(m[1], 10) : -1; } catch (e) { return -1; }")
	public static native int pageGenWorkersRequested();

	@JSBody(script = "if (typeof self !== 'undefined' && typeof self.__minecraftServerDrainMs === 'number') return self.__minecraftServerDrainMs;"
		+ "try { var m = /[?&]serverdrain=(\\d+)/.exec(location.search); if (m) return parseInt(m[1], 10); } catch (e) {}"
		+ "if (typeof window === 'undefined') return -1;"
		+ "return (navigator.hardwareConcurrency || 2) <= 2 ? 25 : 40;")
	public static native int pageServerDrainMs();

	@JSBody(script = "if (typeof self !== 'undefined' && typeof self.__minecraftFixedSeed === 'string') return self.__minecraftFixedSeed;"
		+ "try { var m = /[?&]seed=(-?\\d+)/.exec(location.search); return m ? m[1] : ''; } catch (e) { return ''; }")
	public static native String pageFixedSeed();

	private static String invokeMain() {
		return "  var fail = function(error) { setTimeout(function() { throw error; }, 0); };\n"
			+ "  try { Promise.resolve(main([])).catch(fail); } catch (error) { fail(error); }\n";
	}

	@JSBody(script = "return new URL('.', location.href).href;")
	private static native String baseHref();

	@JSBody(script = "return /(?:^|[?&])chunknetdebug(?:[=&]|$)/.test(location.search || '');")
	private static native boolean pageChunkTransportDebugRequested();

	@JSBody(script = "return typeof self !== 'undefined' && !!self.__minecraftChunkTransportDebug;")
	public static native boolean chunkTransportDebugEnabled();

	public static String resolve(final String path) {
		if (path == null || path.isEmpty() || path.indexOf(':') >= 0 || path.charAt(0) == '/') {
			return path;
		}
		String base = baseUrl();
		return base == null ? path : base + path;
	}

	@JSBody(script = "return typeof self !== 'undefined' && self.__minecraftBase ? self.__minecraftBase : null;")
	private static native String baseUrl();

	@JSBody(params = {"source"}, script =
		"var blob = new Blob([source], {type: 'text/javascript'});"
		+ "var url = URL.createObjectURL(blob);"
		+ "var w; try { w = new Worker(url); } catch(e) { URL.revokeObjectURL(url); throw e; }"
		+ "w.__minecraftSourceUrl = url;"
		+ "return w;")
	public static native JSObject spawn(String source);

	@JSBody(params = {"source"}, script =
		"var raw = globalThis.__minecraftWorkerAssetsRaw || globalThis.__minecraftAssetsBinRaw;"
		+ "var index = globalThis.__minecraftWorkerAssetsIndex || globalThis.__minecraftAssetsIndex;"
		+ "var serverRuntime = globalThis.__minecraftWorkerServerRuntime;"
		+ "var serverWasm = globalThis.__minecraftWorkerServerWasm;"
		+ "if (!raw || !raw.buffer || typeof raw.byteOffset !== 'number'"
		+ "    || typeof raw.byteLength !== 'number' || typeof index === 'undefined'"
		+ "    || typeof serverRuntime !== 'string' || !serverWasm || !serverWasm.buffer) {"
		+ "  throw new Error('embedded worker assets are not ready');"
		+ "}"
		+ "var blob = new Blob([source], {type: 'text/javascript'});"
		+ "var url = URL.createObjectURL(blob);"
		+ "var w; try { w = new Worker(url); } catch(e) { URL.revokeObjectURL(url); throw e; }"
		+ "w.__minecraftSourceUrl = url;"
		+ "var genRuntime = globalThis.__minecraftWorkerGenRuntime, genWasm = globalThis.__minecraftWorkerGenWasm;"
		+ "var transfer = [serverWasm.buffer]; if (genWasm && genWasm.buffer) transfer.push(genWasm.buffer);"
		+ "try { w.postMessage({__minecraftWorkerAssets: true, index: index, assets: raw,"
		+ "  serverRuntime: serverRuntime, serverWasm: serverWasm,"
		+ "  genRuntime: genRuntime || null, genWasm: genWasm || null}, transfer);"
		+ "} catch(e) { w.terminate(); URL.revokeObjectURL(url); throw e; }"
		+ "globalThis.__minecraftWorkerGenRuntime = undefined;"
		+ "globalThis.__minecraftWorkerGenWasm = undefined;"
		+ "globalThis.__minecraftWorkerAssetsRaw = undefined;"
		+ "globalThis.__minecraftWorkerAssetsIndex = undefined;"
		+ "globalThis.__minecraftWorkerServerRuntime = undefined;"
		+ "globalThis.__minecraftWorkerServerWasm = undefined;"
		+ "return w;")
	public static native JSObject spawnWithEmbeddedAssets(String source);

	@JSBody(script = "return typeof (globalThis.__minecraftWorkerAssetsIndex || globalThis.__minecraftAssetsIndex) !== 'undefined'"
		+ " && !!(globalThis.__minecraftWorkerAssetsRaw || globalThis.__minecraftAssetsBinRaw);")
	public static native boolean embeddedAssetsAvailable();

	@JSBody(params = {"js"}, script =
		"return URL.createObjectURL(new Blob([js], {type: 'text/javascript'}));")
	public static native String blobUrl(String js);

	public static void send(final JSObject worker, final String channel, final byte[] data) {
		postTransfer(worker, channel, Int8Array.copyFromJavaArray(data));
	}

	@JSBody(params = {"worker", "ch", "view"}, script =
		"worker.postMessage({ch: ch, dat: view.buffer}, [view.buffer]);")
	private static native void postTransfer(JSObject worker, String ch, Int8Array view);

	public static void receive(final JSObject worker, final WebWorkerInbox inbox) {
		attach(worker, dispatcher(inbox));
	}

	@JSBody(params = {"worker", "handler"}, script =
		"worker.onmessage = function(e) { handler(e.data.ch, e.data.dat); };")
	private static native void attach(JSObject worker, Handler handler);

	@JSBody(params = {"worker"}, script = "worker.terminate();"
		+ "if (worker.__minecraftSourceUrl) { URL.revokeObjectURL(worker.__minecraftSourceUrl); delete worker.__minecraftSourceUrl; }")
	public static native void terminate(JSObject worker);

	public static void onError(final JSObject worker, final Runnable onError) {
		attachError(worker, onError::run);
	}

	@JSBody(params = {"worker", "handler"}, script =
		"worker.onerror = function(e) { if (e && e.preventDefault) { e.preventDefault(); } handler(); };"
		+ "worker.onmessageerror = function() { handler(); };")
	private static native void attachError(JSObject worker, ErrorHandler handler);

	@JSFunctor
	private interface ErrorHandler extends JSObject {
		void run();
	}

	public static void selfSend(final String channel, final byte[] data) {
		selfPostTransfer(channel, Int8Array.copyFromJavaArray(data));
	}

	@JSBody(params = {"ch", "view"}, script =
		"self.postMessage({ch: ch, dat: view.buffer}, [view.buffer]);")
	private static native void selfPostTransfer(String ch, Int8Array view);

	public static void selfReceive(final WebWorkerInbox inbox) {
		selfAttach(dispatcher(inbox));
	}

	@JSBody(params = {"handler"}, script =
		"self.onmessage = function(e) { handler(e.data.ch, e.data.dat); };")
	private static native void selfAttach(Handler handler);

	@JSBody(script = "return typeof window === 'undefined' && typeof self !== 'undefined';")
	public static native boolean inWorker();

	private static Handler dispatcher(final WebWorkerInbox inbox) {
		return (channel, data) -> {
			if (channel != null && data != null) {
				if (CH_PACKET_BATCH.equals(channel)) {
					inbox.offerBatch(CH_PACKET, asInt8(data).copyToJavaArray());
				} else {
					inbox.offer(channel, asInt8(data).copyToJavaArray());
				}
			}
		};
	}

	@JSFunctor
	private interface Handler extends JSObject {
		void message(String channel, JSObject data);
	}

	@JSBody(params = {"data"}, script = "return new Int8Array(data);")
	private static native Int8Array asInt8(JSObject data);

	private static String quote(final String s) {
		StringBuilder out = new StringBuilder(s.length() + 2).append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '"' || c == '\\') {
				out.append('\\');
			}
			out.append(c);
		}
		return out.append('"').toString();
	}
}
