#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Pack the staged 26.3 WASM-GC game into single offline files that open from disk.

Reads what target_teavm_263/javascript-wasm-gc serves (the page scripts, the linked client
engine, the asset shards) and writes self-contained files that run from file:// with no server
and no network (multiplayer still needs a relay or a ws:// server):

  * one XZ archive holds the client runtime + module, assets.bin, the asset index, the object
    pack (sounds, languages) and the small JSON catalogs; a tiny WASM xz decoder unpacks it;
  * the server worker runs the same client module, started via WebWorkerTransport's embedded path;
  * the chunk mesh worker starts from a blob: URL.

Formats (one archive, several containers):

  html   ordinary HTML page
  xhtml  the same page as XHTML (application/xhtml+xml)
  xml    the same XHTML document saved as .xml; browsers render XHTML-namespaced XML and run it
  svg    an SVG document whose foreignObject holds the XHTML page

MHTML is not produced: Chrome and Edge open .mhtml archives with scripts disabled, so a game
cannot run from one.

Sounds: "sound" embeds every sound effect (no music: the 290 MB of music would push a single file
past what a browser will hold as one string); "silent" embeds none.

    python scripts/make_offline.py                                  # all formats, both variants
    python scripts/make_offline.py --formats html svg --variants silent
"""

import argparse
import base64
import gzip
import hashlib
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from minecraft_xz import container as xz_container, html85, unpack_container
from build_xz_decoder import build as build_decoder

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "target_teavm_263", "javascript-wasm-gc")
MC_OBJECTS = os.path.join(os.path.expanduser("~"), "AppData", "Roaming", ".minecraft", "assets", "objects")
DEFAULT_ZIG = os.environ.get("ZIG", "")
MESH_WORKER_SPAWN = 'new Worker(new URL("minecraft-web-mesh-worker.js", location.href))'
PAGE_SCRIPTS = ("minecraft_blobstore.js", "minecraft-web-net.js", "minecraft-web-shell.js",
                "minecraft-web-touch.js", "minecraft-web-mesh.js")
FORMATS = ("html", "xhtml", "xml", "svg")
VARIANTS = {"sound": "sfx", "silent": "none"}
XHTML_NS = "http://www.w3.org/1999/xhtml"
BG = "#ef323d"

def read(path):
    with open(path, "rb") as handle:
        return handle.read()

def text(path):
    return read(path).decode("utf-8")

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def data_uri(path, mime):
    return "data:%s;base64,%s" % (mime, base64.b64encode(read(path)).decode("ascii"))

def object_pack(sounds_mode):
    served = gzip.decompress(read(os.path.join(SRC, "objects.bin.gz")))
    served_map = json.loads(text(os.path.join(SRC, "objects_sha.json")))
    sounds = json.loads(text(os.path.join(SRC, "sounds_index.json")))
    languages = json.loads(text(os.path.join(SRC, "lang_index.json")))
    chunks, sha_map, offset = [], {}, 0
    for sha, (start, length) in served_map.items():
        chunks.append(served[start:start + length])
        sha_map[sha] = [offset, length]
        offset += length
    missing = 0
    if sounds_mode != "none":
        for name, sha in sorted(sounds.items()):
            if sha in sha_map:
                continue
            if sounds_mode == "sfx" and ("/music/" in name or "/records/" in name):
                continue
            path = os.path.join(MC_OBJECTS, sha[:2], sha)
            if not os.path.isfile(path):
                missing += 1
                continue
            data = read(path)
            if hashlib.sha1(data).hexdigest() != sha:
                raise ValueError("object %s does not match its hash" % path)
            chunks.append(data)
            sha_map[sha] = [offset, len(data)]
            offset += len(data)
    if missing:
        print("warning: %d sound objects missing from %s" % (missing, MC_OBJECTS))

    sounds = {name: sha for name, sha in sounds.items() if sha in sha_map}
    languages["files"] = {name: sha for name, sha in languages["files"].items() if sha in sha_map}
    languages["languages"] = {name: info for name, info in languages["languages"].items()
                              if name == "en_us" or name in languages["files"]}
    return b"".join(chunks), sha_map, sounds, languages

XML_PRELUDE = r"""(function () {
	var X = "http://www.w3.org/1999/xhtml";
	var D = Document.prototype;
	if (document.createElement("div").namespaceURI !== X) {
		var createNS = D.createElementNS;
		D.createElement = function (name, options) {
			return createNS.call(this, X, String(name).toLowerCase(), options);
		};
	}
	if (!document.body) {
		Object.defineProperty(document, "body", { configurable: true, get: function () {
			return document.getElementsByTagNameNS(X, "body")[0] || null;
		} });
	}
	if (!document.head) {
		Object.defineProperty(document, "head", { configurable: true, get: function () {
			return document.getElementsByTagNameNS(X, "head")[0] || null;
		} });
	}
})();"""

BOOT = r""""use strict";
(function () {
	// 26.3's compiler emits two forms WebGL2 rejects (same fix as the served index.html).
	var proto = WebGL2RenderingContext.prototype;
	var shaderSource = proto.shaderSource;
	var unrolled = [
		"_frag_output_00[0][0] = coefficients[0];", "_frag_output_00[0][1] = coefficients[1];",
		"_frag_output_00[0][2] = coefficients[2];", "_frag_output_00[0][3] = coefficients[3];",
		"_frag_output_00[1][0] = coefficients[4];", "_frag_output_00[1][1] = coefficients[5];",
		"_frag_output_00[1][2] = coefficients[6];", "_frag_output_00[1][3] = coefficients[7];"
	].join("\n");
	proto.shaderSource = function (shader, source) {
		var t = String(source);
		t = t.split("floatfloat(").join("float(");
		t = t.replace(/_frag_output_00\s*\[\s*attachmentIndex\s*\]\s*\[\s*i_1\s*\]\s*=\s*coefficients\s*\[\s*\(\s*attachmentIndex\s*\*\s*4\s*\)\s*\+\s*i_1\s*\]\s*;/g, unrolled);
		return shaderSource.call(this, shader, t);
	};
})();
(function () {
	var shell = window.MinecraftWeb;
	var stage = shell.bootStage;
	window.addEventListener("error", function (e) { console.error(e.error || e.message); });
	window.addEventListener("unhandledrejection", function (e) { console.error(e.reason); });

	var PHONE_PIXEL_BUDGET = 1150000;
	var forcedScale = parseFloat((/[?&]scale=([0-9.]+)/.exec(location.search) || [])[1]);
	// Same as index.html: laptops with weak integrated GPUs render at CSS resolution on HiDPI screens.
	var weakGpu = null;
	function isWeakGpu() {
		if (weakGpu !== null) { return weakGpu; }
		weakGpu = false;
		try {
			var gl = document.createElement("canvas").getContext("webgl2");
			var ext = gl && gl.getExtension("WEBGL_debug_renderer_info");
			var name = ext ? String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL)) : "";
			weakGpu = /Intel.*\b(U?HD|Iris\(R\) Graphics [56]|GMA)\b|Mesa Intel|Mali|PowerVR|Adreno \(TM\) [1-5]\d\d|SwiftShader|llvmpipe/i.test(name);
			var lose = gl && gl.getExtension("WEBGL_lose_context");
			if (lose) { lose.loseContext(); }
		} catch (e) { /* keep devicePixelRatio */ }
		return weakGpu;
	}
	// The game mirrors its shader pack choice here (ShaderPacks.LS_SELECTED).
	function shaderPackSelected() {
		try { return !!window.localStorage.getItem("minecraft.shaderpack.selected"); } catch (e) { return false; }
	}
	window.__minecraftWebPixelRatio = function (c) {
		var r = window.devicePixelRatio || 1;
		if (forcedScale >= 0.25 && forcedScale <= 3) { return forcedScale; }
		var coarse = !!(window.matchMedia && matchMedia("(pointer: coarse)").matches);
		if (r > 1 && !coarse && isWeakGpu()) { r = 1; }
		if (coarse) {
			r = Math.min(r, 2);
			var css = (c.clientWidth || 1) * (c.clientHeight || 1);
			if (css * r * r > PHONE_PIXEL_BUDGET) { r = Math.max(1, Math.sqrt(PHONE_PIXEL_BUDGET / css)); }
		}
				// Shader packs draw several full-screen passes per frame, so a weak GPU is fill-rate
		// bound: render at half resolution (a quarter of the pixels) while a pack is selected.
		if (shaderPackSelected() && isWeakGpu()) { r = Math.min(r, 0.5); }
return r;
	};
	function fitCanvas() {
		var c = document.getElementById("game_frame");
		if (!c) { return; }
		var r = window.__minecraftWebPixelRatio(c);
		var w = Math.max(1, Math.round(c.clientWidth * r) | 0);
		var h = Math.max(1, Math.round(c.clientHeight * r) | 0);
		if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
		var crisp = w < c.clientWidth ? "pixelated" : "";
		if (c.style.imageRendering !== crisp) { c.style.imageRendering = crisp; }
	}
	window.addEventListener("resize", fitCanvas);
	// Picking or clearing a pack changes the ratio; the engine re-reads it on resize.
	var lastPackSelected = shaderPackSelected();
	setInterval(function () {
		var now = shaderPackSelected();
		if (now !== lastPackSelected) { lastPackSelected = now; window.dispatchEvent(new Event("resize")); }
	}, 1000);
	document.addEventListener("fullscreenchange", fitCanvas);
	if (window.visualViewport) { window.visualViewport.addEventListener("resize", fitCanvas); }
	fitCanvas();

	function payload(id) {
		var el = document.getElementById(id);
		var t = el ? el.textContent : null;
		if (el) { el.parentNode.removeChild(el); }
		return t;
	}
	function b64(s) {
		s = String(s || "").replace(/\s+/g, "");
		var out = new Uint8Array((s.length / 4) * 3 | 0), n = 0, CH = 4 * 65536;
		for (var i = 0; i < s.length; i += CH) {
			var part = atob(s.substr(i, CH));
			for (var j = 0; j < part.length; j++) { out[n++] = part.charCodeAt(j); }
		}
		return out.subarray(0, n);
	}
	function idle() {
		return new Promise(function (r) {
			if (typeof requestAnimationFrame === "function") { requestAnimationFrame(function () { setTimeout(r, 0); }); }
			setTimeout(r, 100); // hidden tabs pause animation frames
		});
	}

	function installObjectShim(bin, map) {
		var files = null;
		var objectUrls = {};
		var textFor = function (url) {
			if (files === null) {
				try { files = JSON.parse(window.__minecraftFiles || "{}"); } catch (e) { files = {}; }
			}
			var name = String(url || "").replace(/^.*\//, "").replace(/[?#].*$/, "");
			return Object.prototype.hasOwnProperty.call(files, name) ? files[name] : null;
		};
		var sliceFor = function (url) {
			var m = /objects\/[0-9a-f]{2}\/([0-9a-f]{40})/.exec(url || "");
			if (!m) { return null; }
			var span = map[m[1]];
			return span ? bin.subarray(span[0], span[0] + span[1]) : null;
		};
		window.__minecraftObjectUrl = function (url) {
			var m = /objects\/[0-9a-f]{2}\/([0-9a-f]{40})/.exec(url || "");
			if (!m) { return url; }
			var sha = m[1];
			if (objectUrls[sha]) { return objectUrls[sha]; }
			var slice = sliceFor(url);
			if (!slice) { return url; }
			var blobUrl = URL.createObjectURL(new Blob([slice], { type: "audio/ogg" }));
			objectUrls[sha] = blobUrl;
			return blobUrl;
		};
		var P = XMLHttpRequest.prototype, open = P.open, send = P.send;
		P.open = function (method, url, async) {
			this.__eagText = textFor(url);
			this.__eagHit = this.__eagText === null ? sliceFor(url) : null;
			this.__eagAsync = async !== false;
			if (this.__eagText !== null || this.__eagHit) { return; }
			return open.apply(this, arguments);
		};
		P.send = function () {
			var self = this, hit = this.__eagHit, text = this.__eagText;
			if (text === null && !hit) { return send.apply(this, arguments); }
			var define = function (key, value) { Object.defineProperty(self, key, { value: value, configurable: true }); };
			var body;
			if (text !== null) { body = text; }
			else {
				var chars = new Array(hit.length);
				for (var i = 0; i < hit.length; i++) { chars[i] = String.fromCharCode(hit[i] & 255); }
				body = chars.join("");
			}
			define("status", 200); define("readyState", 4); define("statusText", "OK");
			define("responseText", body);
			define("response", this.responseType === "arraybuffer" && hit
				? hit.buffer.slice(hit.byteOffset, hit.byteOffset + hit.byteLength) : body);
			var fire = function () {
				if (self.onreadystatechange) { self.onreadystatechange(); }
				if (self.onload) { self.onload(); }
			};
			if (this.__eagAsync) { setTimeout(fire, 0); } else { fire(); }
		};
	}

	// The bundled shader packs go through the game's own pack picker (silent, no toast) once the game
	// is up, so they appear in Options > Video Settings > Shader Packs. Each is added once per browser:
	// one the player deletes stays deleted.
	function seedShaderPacks(bin, list) {
		if (!bin || !list || !list.length || typeof DataTransfer !== "function") { return; }
		var KEY = "minecraft.offline.shaderpacks";
		var done = {};
		try { done = JSON.parse(window.localStorage.getItem(KEY) || "{}") || {}; } catch (e) { done = {}; }
		if (list.every(function (p) { return done[p.file]; })) { return; }
		var tries = 0;
		var wait = function () {
			var input = document.getElementById("minecraft-shaderpack-file");
			if (typeof window.__shaderPackStatus !== "function" || !input) {
				if (++tries < 1200) { setTimeout(wait, 500); }
				return;
			}
			setTimeout(function () {
				var stored = (/(?:^|\n)stored: (.*)$/m.exec(String(window.__shaderPackStatus())) || [])[1] || "";
				var have = stored.split(", ");
				var todo = list.filter(function (p) {
					if (have.indexOf(p.file) >= 0) { done[p.file] = 1; }
					return !done[p.file];
				});
				var next = function () {
					var p = todo.shift();
					if (!p) {
						bin = null;
						try { window.localStorage.setItem(KEY, JSON.stringify(done)); } catch (e) { /* re-adds next time */ }
						return;
					}
					try {
						var dt = new DataTransfer();
						dt.items.add(new File([bin.subarray(p.offset, p.offset + p.size)], p.file, { type: "application/zip" }));
						input.files = dt.files;
						input.dispatchEvent(new Event("change"));
						done[p.file] = 1;
						console.log("[26.3-offline] added shader pack " + p.file);
					} catch (e) { console.error("[26.3-offline] could not add shader pack " + p.file, e); }
					setTimeout(next, 400);
				};
				next();
			}, 3000); // let the saved /config folder load first
		};
		wait();
	}

	async function boot() {
		try {
			// A one-struct-type module validates only with Wasm GC.
			if (typeof WebAssembly !== "object"
				|| !WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 95, 1, 127, 0]))) {
				throw new Error("this browser has no WebAssembly GC: use current Chrome, Edge or Firefox, or Safari 18.2+");
			}
			if (navigator.storage && navigator.storage.persist) { try { navigator.storage.persist().catch(function () {}); } catch (e) {} }
			stage("Unpacking", 3);
			await idle();
			var packed = window.__minecraftHTML85Decode(payload("p-xz"));
			var loader = b64(payload("p-loader"));
			stage("Unpacking", 10);
			await idle();
			var archive = await window.__minecraftXZDecode(packed, loader);
			packed = loader = null;
			var td = new TextDecoder();
			var runtime = td.decode(archive.runtime);
			(0, eval)(runtime);
			if (!window.TeaVM || !TeaVM.wasmGC) { throw new Error("TeaVM WASM-GC runtime did not load"); }
			var meta = JSON.parse(td.decode(archive.meta));
			window.__minecraftFiles = JSON.stringify(meta.files);
			window.__minecraftAssetsIndex = td.decode(archive.index);
			// Own buffers: subarrays would pin the whole decoder memory for the session.
			var assets = archive.assets.slice();
			var objects = archive.objects.slice();
			var wasm = archive.wasm.slice();
			var shaderPacks = archive.shaderpacks ? archive.shaderpacks.slice() : null;
			archive = null;
			window.__minecraftAssetsBinRaw = assets;
			window.__minecraftWorkerAssetsRaw = assets;
			window.__minecraftWorkerAssetsIndex = window.__minecraftAssetsIndex;
			installObjectShim(objects, meta.objects);
			shell.mark("assets");

			stage("Compiling", 40);
			await idle();
			var module = await WebAssembly.compile(wasm, { builtins: ["js-string"] });
			shell.mark("engine");
			window.__minecraftClientModule = module;
			// The server worker runs this same module (the served page shares it the same way);
			// WebWorkerTransport.spawnWithEmbeddedAssets transfers these bytes to it.
			window.__minecraftWorkerServerRuntime = runtime;
			window.__minecraftWorkerServerWasm = wasm;
			window.__minecraftServerModuleUrl = "embedded:";
			var compile = WebAssembly.compile;
			WebAssembly.compile = function (src, opts) {
				return src instanceof WebAssembly.Module ? Promise.resolve(src) : compile.call(WebAssembly, src, opts);
			};
			var before = Promise.resolve(shell.beforeMain ? shell.beforeMain() : null).catch(function () {});
			var teavm = (await Promise.all([TeaVM.wasmGC.load(module), before]))[0];
			if (typeof teavm.exports.main !== "function") { throw new Error("TeaVM WASM-GC module did not export main"); }
			stage("Starting", 100);
			fitCanvas();
			shell.engineStarting();
			teavm.exports.main([]);
			seedShaderPacks(shaderPacks, meta.shaderpacks);
			shaderPacks = null;
		} catch (error) {
			shell.bootFailed(error);
			console.error("[26.3-offline] startup failed", error);
		}
	}
	boot();
})();"""

def shader_pack_bundle(folder):
    if not folder:
        return b"", []
    listing = json.loads(text(os.path.join(folder, "packs.json")))
    blob, spans = bytearray(), []
    for entry in listing:
        data = read(os.path.join(folder, entry["file"]))
        if data[:2] != b"PK":
            raise ValueError("%s is not a zip" % entry["file"])
        spans.append({"file": entry["file"], "offset": len(blob), "size": len(data)})
        blob += data
    return bytes(blob), spans

def build_archive(sounds_mode, xz_preset, xz_extreme, zig, shaderpacks_dir=None):
    started = time.time()
    wasm_dir = os.path.join(SRC, "wasm-gc")
    wasm = read(os.path.join(wasm_dir, "classes263.wasm"))
    runtime = read(os.path.join(wasm_dir, "classes263.wasm-runtime.js"))
    if wasm[:4] != b"\x00asm":
        raise ValueError("classes263.wasm has no WebAssembly header (is a link running?)")
    if b"callInFiber" not in runtime:
        raise ValueError("classes263.wasm-runtime.js is unpatched (patchWasmGCRuntime did not run)")
    index_html = text(os.path.join(SRC, "index.html"))
    for needle in ("floatfloat(", "_frag_output_00", "classes263.wasm-runtime.js", "minecraft-web-mesh.js"):
        if needle not in index_html:
            raise ValueError("served index.html changed (%s missing); update this packager's BOOT" % needle)

    assets = gzip.decompress(read(os.path.join(SRC, "assets.bin.gz")))
    index = gzip.decompress(read(os.path.join(SRC, "assets_index.json.gz")))
    objects, sha_map, sounds, languages = object_pack(sounds_mode)
    version = json.loads(text(os.path.join(SRC, "version.json")))["id"]
    files = {
        "sounds_index.json": json.dumps(sounds, separators=(",", ":")),
        "lang_index.json": json.dumps(languages, separators=(",", ":")),
        "version.json": text(os.path.join(SRC, "version.json")),
        "sounds.json": text(os.path.join(SRC, "sounds.json")),
        "pipelines.json": text(os.path.join(SRC, "pipelines.json")),
    }
    meta = {"version": version, "files": files, "objects": sha_map, "sounds": sounds_mode,
            "wasmSha256": sha256(wasm), "runtimeSha256": sha256(runtime), "assetsSha256": sha256(assets),
            "built": time.strftime("%Y-%m-%dT%H:%M:%S")}
    packs, pack_spans = shader_pack_bundle(shaderpacks_dir)
    if pack_spans:
        meta["shaderpacks"] = pack_spans
    entries = {"meta": json.dumps(meta, separators=(",", ":")).encode(), "index": index,
               "runtime": runtime, "assets": assets, "objects": objects, "wasm": wasm}
    if pack_spans:
        entries["shaderpacks"] = packs
        print("[%s] shader packs: %d, %.1f MB" % (sounds_mode, len(pack_spans), len(packs) / 1e6), flush=True)
    print("[%s] archive: wasm %.1f MB, assets %.1f MB, objects %.1f MB (%d sounds); xz -%d%s ..." % (
        sounds_mode, len(wasm) / 1e6, len(assets) / 1e6, len(objects) / 1e6, len(sounds),
        xz_preset, "e" if xz_extreme else ""), flush=True)
    loader_wasm = build_decoder(zig)
    packed, raw_bytes, packed_bytes = xz_container(entries, xz_preset, xz_extreme, 128)
    unpacked = unpack_container(packed)
    if unpacked["wasm"] != wasm or unpacked["assets"] != assets or unpacked["objects"] != objects \
            or (pack_spans and unpacked["shaderpacks"] != packs):
        raise ValueError("archive round trip failed")
    print("[%s] compressed %.1f MB -> %.1f MB in %.0fs" % (sounds_mode, raw_bytes / 1e6, packed_bytes / 1e6,
                                                          time.time() - started), flush=True)
    return {"version": version, "meta": meta, "xz": html85(packed),
            "loader": base64.b64encode(loader_wasm).decode("ascii")}

def page_parts():
    mesh = text(os.path.join(SRC, "minecraft-web-mesh.js"))
    if mesh.count(MESH_WORKER_SPAWN) != 1:
        raise ValueError("minecraft-web-mesh.js no longer spawns its worker the expected way")
    mesh = mesh.replace(MESH_WORKER_SPAWN,
                        'new Worker(window.__minecraftWebMeshWorkerUrl || new URL("minecraft-web-mesh-worker.js", location.href))')
    scripts = [("minecraft_xz_loader.js", text(os.path.join(REPO, "scripts", "minecraft_xz_loader.js")))]
    scripts.append(("mesh worker url", 'window.__minecraftWebMeshWorkerUrl = URL.createObjectURL(new Blob('
                    '[document.getElementById("mesh-worker-src").textContent], {type: "text/javascript"}));'))
    for name in PAGE_SCRIPTS:
        scripts.append((name, mesh if name == "minecraft-web-mesh.js" else text(os.path.join(SRC, name))))
    for required in ("boot-logo.png", "favicon.png"):
        if not os.path.isfile(os.path.join(SRC, required)):
            raise ValueError("%s missing from %s: stage the site first" % (required, SRC))
    return {
        "css": text(os.path.join(SRC, "minecraft-web.css")),
        "mesh_worker": text(os.path.join(SRC, "minecraft-web-mesh-worker.js")),
        "scripts": scripts,
        "logo": data_uri(os.path.join(SRC, "boot-logo.png"), "image/png"),
        "icon": data_uri(os.path.join(SRC, "favicon.png"), "image/png"),
    }

def html_raw(source, name):
    if re.search(r"</script|<!--", source, re.I):
        raise ValueError("%s cannot be inlined into HTML: contains </script or <!--" % name)
    return source

def cdata(source):
    return "<![CDATA[" + source.replace("]]>", "]]]]><![CDATA[>") + "]]>"

def render(fmt, parts, archive, variant):
    xml = fmt != "html"
    build_tag = "%s offline, engine %s, %s" % (archive["version"], archive["meta"]["wasmSha256"][:12], variant)

    def script(source, name, attrs=""):
        body = cdata("\n" + source + "\n") if xml else "\n" + html_raw(source, name) + "\n"
        return "<script%s>%s</script>" % (attrs, body)

    def data(sid, payload):
        body = cdata(payload) if xml else payload
        if not xml and "</script" in payload:
            raise ValueError("payload %s contains </script" % sid)
        return '<script type="application/octet-stream" id="%s">%s</script>' % (sid, body)

    css = parts["css"]
    if "</style" in css:
        raise ValueError("minecraft-web.css contains </style")
    style = "<style>%s</style>" % (("/*" + cdata("*/\n" + css + "\n/*") + "*/") if xml else "\n" + css + "\n")
    head = [
        '<meta charset="UTF-8" />',
        '<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover" />',
        '<title>Minecraft</title>',
        '<meta name="author" content="chiikabu (github.com/chiikabu)" />',
        '<meta name="theme-color" content="%s" />' % BG,
        '<meta name="minecraft-build" content="%s" />' % build_tag,
        '<link rel="icon" href="%s" />' % parts["icon"],
        style,
    ]
    if xml:
        head.append(script(XML_PRELUDE, "xml prelude"))
    head.append(script(parts["scripts"][0][1], parts["scripts"][0][0]))

    worker = parts["mesh_worker"]
    if not xml and re.search(r"</script|<!--", worker, re.I):
        raise ValueError("minecraft-web-mesh-worker.js contains </script")
    head.append('<script type="text/plain" id="mesh-worker-src">%s</script>' % (cdata(worker) if xml else worker))
    head += [script(source, name) for name, source in parts["scripts"][1:]]
    body = [
        '<canvas id="game_frame" width="854" height="480"></canvas>',
        '<div id="log"></div>',
        '<div id="boot" role="progressbar" aria-label="Loading Minecraft" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">',
        '<img id="logo" src="%s" alt="Mojang Studios" />' % parts["logo"],
        '<div id="bar"><div id="fill"></div></div>',

        '<div id="stage">Loading. If this stays here, open the file with Chrome, Edge or Safari instead of a file viewer.</div>',
        '<div id="joining" hidden="hidden"></div>',
        '<div id="credit"><span>minecraft-web</span> by <a href="https://github.com/chiikabu">chiikabu</a></div>',
        '</div>',
        data("p-loader", archive["loader"]),
        data("p-xz", archive["xz"]),
        script(BOOT, "boot"),
    ]
    html_attrs = 'lang="en" style="width:100%%;height:100%%;margin:0;background:%s;"' % BG
    page = "<head>\n%s\n</head>\n<body>\n%s\n</body>" % ("\n".join(head), "\n".join(body))
    if fmt == "html":
        return "<!DOCTYPE html>\n<html %s>\n%s\n</html>\n" % (html_attrs, page)
    if fmt in ("xhtml", "xml"):
        return ('<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE html>\n<html xmlns="%s" %s>\n%s\n</html>\n'
                % (XHTML_NS, html_attrs, page))

    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<svg xmlns="http://www.w3.org/2000/svg" width="100%%" height="100%%" style="display:block;background:%s;">\n'
            '<title>Minecraft</title>\n'
            '<foreignObject x="0" y="0" width="100%%" height="100%%">\n'
            '<html xmlns="%s" %s>\n%s\n</html>\n'
            '</foreignObject>\n</svg>\n' % (BG, XHTML_NS, html_attrs, page))

def check_xml(path):
    import xml.parsers.expat
    parser = xml.parsers.expat.ParserCreate()
    with open(path, "rb") as handle:
        while True:
            chunk = handle.read(1 << 22)
            parser.Parse(chunk, not chunk)
            if not chunk:
                break

def main():
    global SRC
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--web", help="the staged site folder to pack (default: target_teavm_263/javascript-wasm-gc)")
    parser.add_argument("--out-dir", default=os.path.join(REPO, "dist"))
    parser.add_argument("--formats", nargs="+", choices=FORMATS, default=list(FORMATS))
    parser.add_argument("--variants", nargs="+", choices=sorted(VARIANTS), default=["sound", "silent"])
    parser.add_argument("--xz-preset", type=int, choices=range(0, 10), default=8)
    parser.add_argument("--no-xz-extreme", action="store_true")
    parser.add_argument("--zig", default=DEFAULT_ZIG if os.path.isfile(DEFAULT_ZIG) else "zig")
    parser.add_argument("--shaderpacks", metavar="DIR",
                        help="bundle the shader packs listed in DIR/packs.json; added to the game on first start")
    args = parser.parse_args()
    if args.web:
        SRC = os.path.abspath(args.web)
    os.makedirs(args.out_dir, exist_ok=True)
    parts = page_parts()
    for variant in args.variants:
        archive = build_archive(VARIANTS[variant], args.xz_preset, not args.no_xz_extreme, args.zig,
                                args.shaderpacks)
        for fmt in args.formats:
            out = os.path.join(args.out_dir, "minecraft-%s-offline-%s.%s" % (archive["version"], variant, fmt))
            document = render(fmt, parts, archive, variant)
            tmp = out + ".tmp"
            with open(tmp, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(document)
            if fmt != "html":
                check_xml(tmp)
            os.replace(tmp, out)
            print("wrote %s (%.1f MB)" % (os.path.relpath(out, REPO), os.path.getsize(out) / 1e6), flush=True)
    return 0

if __name__ == "__main__":
    sys.exit(main())
