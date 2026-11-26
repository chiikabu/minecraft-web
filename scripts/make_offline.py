#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Pack the staged 26.3 WASM-GC game into single offline files that open from disk.

Reads what target_teavm_263/javascript-wasm-gc serves (the page scripts, the linked client
engine, the asset shards) and writes self-contained files that run from file:// with no server
and no network (multiplayer still needs a relay or a ws:// server):

  * two parallel XZ archives hold (1) the client WASM module (`p-wasm`) and (2) assets.bin,
    the asset index, the object pack (sounds, languages), small JSON catalogs, the TeaVM
    runtime, and large non-boot page scripts (`p-rest`); a tiny WASM xz decoder unpacks both
    in parallel across 2 inline Web Workers while overlapping WebAssembly.compile;
  * `html` uses Base122-HTML (+14.29% UTF-8 overhead); `xhtml`, `xml`, and `svg` use
    XML-UTF8-hybrid (+17.77% UTF-8 overhead, strictly XML 1.0 & expat-safe);
  * the server worker runs the same client module, started via WebWorkerTransport's embedded
    or shared-module path;
  * the chunk mesh worker starts from a blob: URL unpacked from `p-rest`.

Formats (one archive pair, several containers):

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
import concurrent.futures
import gzip
import hashlib
import io
import json
import os
import re
import struct
import sys
import time
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from minecraft_xz import (
    base122_html_decode,
    base122_html_encode,
    container as xz_container,
    html85,
    unpack_container,
    xml_utf8_decode,
    xml_utf8_encode,
)
from build_xz_decoder import build as build_decoder
from ogvp_v4 import encode_ogg_pack

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "target_teavm_263", "javascript-wasm-gc")
MC_OBJECTS = os.path.join(os.path.expanduser("~"), "AppData", "Roaming", ".minecraft", "assets", "objects")
DEFAULT_ZIG = os.environ.get("ZIG", "")
MESH_WORKER_SPAWN = 'new Worker(new URL("minecraft-web-mesh-worker.js", location.href))'
PAGE_SCRIPTS = (
    "minecraft_blobstore.js",
    "minecraft-web-net.js",
    "minecraft-web-shell.js",
    "minecraft-web-touch.js",
    "minecraft-web-mesh.js",
)
PACKED_SCRIPTS = (
    ("js_translator", "minecraft-web-jvm-translator.js"),
    ("js_bridge", "minecraft-web-jvm-bridge.js"),
    ("js_modrinth", "minecraft-web-modrinth.js"),
    ("js_mesh_worker", "minecraft-web-mesh-worker.js"),
    ("js_simd_kernels", "minecraft-web-simd-kernels.js"),
)
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

def _opt_nbt(data):
    if len(data) < 2 or data[:2] != b"\x1f\x8b":
        return data, False
    try:
        raw = gzip.decompress(data)
        co = zlib.compressobj(0, zlib.DEFLATED, 31)
        repacked = co.compress(raw) + co.flush()
        return repacked, True
    except Exception:
        return data, False

def _opt_zip(data):
    if len(data) < 4 or data[:2] != b"PK":
        return data, False
    try:
        src_buf = io.BytesIO(data)
        dst_buf = io.BytesIO()
        with zipfile.ZipFile(src_buf, "r") as zin, zipfile.ZipFile(dst_buf, "w", compression=zipfile.ZIP_STORED) as zout:
            for info in zin.infolist():
                payload = zin.read(info.filename)
                out_info = zipfile.ZipInfo(filename=info.filename, date_time=(1980, 1, 1, 0, 0, 0))
                out_info.compress_type = zipfile.ZIP_STORED
                out_info.external_attr = info.external_attr
                zout.writestr(out_info, payload)
        return dst_buf.getvalue(), True
    except Exception:
        return data, False

def _opt_png(data):
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n":
        return data, False
    try:
        pos = 8
        n = len(data)
        chunks = []
        idat_indices = []
        while pos + 12 <= n:
            clen = struct.unpack_from(">I", data, pos)[0]
            ctype = data[pos + 4:pos + 8]
            end = pos + 12 + clen
            if end > n:
                return data, False
            if ctype == b"IDAT":
                idat_indices.append(len(chunks))
            chunks.append((ctype, data[pos + 8:pos + 8 + clen], data[pos:end]))
            pos = end
        if pos != n or not idat_indices:
            return data, False
        first_idat, last_idat = idat_indices[0], idat_indices[-1]
        if last_idat - first_idat + 1 != len(idat_indices):
            return data, False
        idat_zlib = b"".join(chunks[i][1] for i in idat_indices)
        raw_scanlines = zlib.decompress(idat_zlib)
        repacked_idat = zlib.compress(raw_scanlines, level=0)
        crc = zlib.crc32(b"IDAT" + repacked_idat) & 0xFFFFFFFF
        new_idat = struct.pack(">I", len(repacked_idat)) + b"IDAT" + repacked_idat + struct.pack(">I", crc)
        parts = [b"\x89PNG\r\n\x1a\n"]
        for c in chunks[:first_idat]:
            parts.append(c[2])
        parts.append(new_idat)
        for c in chunks[last_idat + 1:]:
            parts.append(c[2])
        return b"".join(parts), True
    except Exception:
        return data, False

def _opt_json(data):
    try:
        parsed = json.loads(data.decode("utf-8"))
        minified = json.dumps(parsed, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        if len(minified) < len(data):
            return minified, True
        return data, False
    except Exception:
        return data, False

def optimize_assets_bundle(assets_bytes, index_bytes):
    """Losslessly pre-unpack gzipped .nbt, .zip, PNG IDAT zlib streams, and minify .json inside assets.bin."""
    index = json.loads(index_bytes.decode("utf-8"))
    out_chunks = []
    new_index = {}
    cursor = 0
    counts = {"nbt": 0, "zip": 0, "png": 0, "json": 0}
    for path, (off, length) in sorted(index.items(), key=lambda kv: (kv[1][0], kv[0])):
        data = assets_bytes[off:off + length]
        low = path.lower()
        if low.endswith(".nbt"):
            data, ok = _opt_nbt(data)
            if ok:
                counts["nbt"] += 1
        elif low.endswith(".zip"):
            data, ok = _opt_zip(data)
            if ok:
                counts["zip"] += 1
        elif low.endswith(".png"):
            data, ok = _opt_png(data)
            if ok:
                counts["png"] += 1
        elif low.endswith(".json"):
            data, ok = _opt_json(data)
            if ok:
                counts["json"] += 1
        new_index[path] = [cursor, len(data)]
        out_chunks.append(data)
        cursor += len(data)
    new_index_bytes = json.dumps(new_index, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return b"".join(out_chunks), new_index_bytes, counts

def object_pack(sounds_mode, use_ogvp=True, zig="zig"):
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
    ogvp_paths = {}
    if sounds_mode != "none":
        for name, sha in sorted(sounds.items()):
            if sha in sha_map or sha in ogvp_paths:
                continue
            if sounds_mode == "sfx" and ("/music/" in name or "/records/" in name):
                continue
            path = os.path.join(SRC, "objects", sha[:2], sha)
            if not os.path.isfile(path):
                path = os.path.join(MC_OBJECTS, sha[:2], sha)
            if not os.path.isfile(path):
                missing += 1
                continue
            data = read(path)
            if hashlib.sha1(data).hexdigest() != sha:
                raise ValueError("object %s does not match its hash" % path)
            if use_ogvp and len(data) >= 64 and data[:4] == b"OggS":
                ogvp_paths[sha] = path
            else:
                chunks.append(data)
                sha_map[sha] = [offset, len(data)]
                offset += len(data)
    if missing:
        print("warning: %d sound objects missing from %s" % (missing, MC_OBJECTS))

    ogvp_meta, ogvp_audio, ogvp_wasm = b"", b"", b""
    if ogvp_paths:
        ogvp_meta, ogvp_audio, ogvp_wasm = encode_ogg_pack(ogvp_paths, zig=zig)

    present = set(sha_map) | set(ogvp_paths)
    sounds = {name: sha for name, sha in sounds.items() if sha in present}
    languages["files"] = {name: sha for name, sha in languages["files"].items() if sha in sha_map}
    languages["languages"] = {name: info for name, info in languages["languages"].items()
                              if name == "en_us" or name in languages["files"]}
    return b"".join(chunks), sha_map, sounds, languages, ogvp_meta, ogvp_audio, ogvp_wasm

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
	var earlyGpuWanted = true;
	try {
		var sp = new URLSearchParams(location.search);
		if ((sp.has("webgl") && sp.get("webgl") !== "0") || sp.get("webgpu") === "0") { earlyGpuWanted = false; }
		if (sessionStorage.getItem("minecraft-web.webgpuFailed")) { earlyGpuWanted = false; }
		if (!sp.has("webgpu") && localStorage.getItem("minecraft.shaderpack.selected")) { earlyGpuWanted = false; }
	} catch (e) {}
	if (earlyGpuWanted && typeof navigator !== "undefined" && navigator.gpu && navigator.gpu.requestAdapter && !window.__minecraftWebGpuState) {
		window.__minecraftWebGpuState = "probing";
		var gpuT0 = performance.now();
		window.__minecraftEarlyWebGpu = navigator.gpu.requestAdapter({ powerPreference: "high-performance" }).then(function (adapter) {
			if (window.__minecraftWebGpuState !== "probing") { return null; }
			if (!adapter) { window.__minecraftWebGpuState = "no adapter"; return null; }
			var limits = {};
			for (var k in adapter.limits) { limits[k] = adapter.limits[k]; }
			window.__minecraftWebGpuLimits = limits;
			window.__minecraftWebGpuAdapterInfo = adapter.info || {};
			if (adapter.isFallbackAdapter) { window.__minecraftWebGpuState = "software fallback adapter"; return null; }
			var info = window.__minecraftWebGpuAdapterInfo;
			var gpuDesc = [info.vendor, info.architecture, info.device, info.description].filter(Boolean).join(" ");
			var isDiscreteRe = /NVIDIA|GeForce|RTX|GTX|Quadro|Radeon\s*(RX|Pro)|Arc\s*A\d|Apple\s*M/i;
			var isIntegratedRe = /Intel|UHD|HD Graphics|Iris|Radeon\(TM\) Graphics|Vega|Mali|Adreno/i;
			var forceWebGpu = false;
			try { var sp2 = new URLSearchParams(location.search); forceWebGpu = sp2.get("webgpu") === "1"; } catch (e) {}
			if (!forceWebGpu && gpuDesc && isIntegratedRe.test(gpuDesc) && !isDiscreteRe.test(gpuDesc)) {
				try {
					var probeCanvas = document.createElement("canvas");
					var probeGl = probeCanvas.getContext("webgl2", { powerPreference: "high-performance" });
					var dbg = probeGl && probeGl.getExtension("WEBGL_debug_renderer_info");
					var glName = dbg ? String(probeGl.getParameter(dbg.UNMASKED_RENDERER_WEBGL)) : "";
					var loseP = probeGl && probeGl.getExtension("WEBGL_lose_context");
					if (loseP) { loseP.loseContext(); }
					if (glName && isDiscreteRe.test(glName)) {
						console.info("[26.3-gpu] WebGPU selected integrated GPU (" + gpuDesc + ") while discrete GPU (" + glName + ") is available via WebGL2; preferring discrete GPU");
						window.__minecraftWebGpuState = "discrete-webgl2-preferred: " + glName;
						return null;
					}
				} catch (e) {}
			}
			var desc = {};
			var reqFeatures = [];
			var wantFeatures = ["indirect-first-instance", "timestamp-query", "float32-filterable", "depth-clip-control", "bgra8unorm-storage"];
			if (adapter.features && typeof adapter.features.has === "function") {
				for (var fi = 0; fi < wantFeatures.length; fi++) {
					if (adapter.features.has(wantFeatures[fi])) { reqFeatures.push(wantFeatures[fi]); }
				}
			}
			if (reqFeatures.length) { desc.requiredFeatures = reqFeatures; }
			var reqLimits = {}, hasLimits = false;
			var desired = {
				maxBufferSize: 536870912, maxStorageBufferBindingSize: 268435456,
				maxTextureDimension2D: 16384, maxUniformBufferBindingSize: 262144,
				maxComputeWorkgroupStorageSize: 32768, maxComputeInvocationsPerWorkgroup: 1024,
				maxColorAttachmentBytesPerSample: 64
			};
			if (adapter.limits) {
				for (var lk in desired) {
					var av = adapter.limits[lk];
					if (typeof av === "number" && isFinite(av) && av > 0) {
						reqLimits[lk] = Math.min(desired[lk], av);
						hasLimits = true;
					}
				}
			}
			if (hasLimits) { desc.requiredLimits = reqLimits; }
			return adapter.requestDevice(desc).catch(function () { return adapter.requestDevice(); }).then(function (device) {
				if (window.__minecraftWebGpuState !== "probing") { try { device.destroy(); } catch (e) {} return null; }
				window.__minecraftWebGpuDevice = device;
				window.__minecraftWebGpuState = "ready";
				window.__minecraftWebGpuProbeMs = Math.round(performance.now() - gpuT0);
				device.lost.then(function (info) { window.__minecraftWebGpuState = "lost: " + info.reason; });
				return { adapter: adapter, device: device, info: window.__minecraftWebGpuAdapterInfo };
			});
		}).catch(function (e) {
			if (window.__minecraftWebGpuState === "probing") { window.__minecraftWebGpuState = "error: " + e; }
			return null;
		});
	} else {
		window.__minecraftEarlyWebGpu = null;
	}
})();
(function () {
	if (typeof WebGL2RenderingContext !== "undefined") {
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
			if (t.indexOf("floatfloat(") >= 0) { t = t.split("floatfloat(").join("float("); }
			if (t.indexOf("_frag_output_00") >= 0) {
				t = t.replace(/_frag_output_00\s*\[\s*attachmentIndex\s*\]\s*\[\s*i_1\s*\]\s*=\s*coefficients\s*\[\s*\(\s*attachmentIndex\s*\*\s*4\s*\)\s*\+\s*i_1\s*\]\s*;/g, unrolled);
			}
			return shaderSource.call(this, shader, t);
		};
	}
})();
(function () {
	var shell = window.MinecraftWeb;
	var stage = shell.bootStage;
	window.addEventListener("error", function (e) { console.error(e.error || e.message); });
	window.addEventListener("unhandledrejection", function (e) { console.error(e.reason); });

	var PHONE_PIXEL_BUDGET = 1150000;
	var WEAK_GPU_PIXEL_BUDGET = 921600;
	var WEAK_GPU_RE = /Intel.*\b(U?HD|Iris\(R\) Graphics [56]|GMA)\b|Intel\(R\) Graphics\b|Mesa Intel|Mali|PowerVR|Adreno \(TM\) ([1-5]\d\d|61\d)|Panfrost|Freedreno|Turnip|virgl|V3D|AMD.*(Stoney|Radeon\(TM\) R[2-5]|Vega [1-3]|610M)|SwiftShader|llvmpipe/i;
	var forcedScale = parseFloat((/[?&]scale=([0-9.]+)/.exec(location.search) || [])[1]);
	// Same as index.html: laptops with weak integrated GPUs stay within WEAK_GPU_PIXEL_BUDGET (~720p)
	// and avoid HiDPI over-rendering. Avoid blocking cold unpack with a throwaway WebGL2 context.
	var weakGpu = null;
	var glProbeReady = false;
	function isWeakGpu() {
		if (weakGpu !== null) { return weakGpu; }
		try {
			if (typeof navigator !== "undefined" && (/\bCrOS\b/.test(navigator.userAgent || "") || (navigator.deviceMemory > 0 && navigator.deviceMemory <= 4))) {
				weakGpu = true;
				return weakGpu;
			}
			var gpuInfo = window.__minecraftWebGpuAdapterInfo;
			if (gpuInfo) {
				var desc = [gpuInfo.vendor, gpuInfo.architecture, gpuInfo.device, gpuInfo.description].filter(Boolean).join(" ");
				if (desc) {
					weakGpu = WEAK_GPU_RE.test(desc);
					return weakGpu;
				}
			}
			if (!glProbeReady) { return false; }
			weakGpu = false;
			var gl = document.createElement("canvas").getContext("webgl2", { powerPreference: "high-performance" });
			var ext = gl && gl.getExtension("WEBGL_debug_renderer_info");
			var name = ext ? String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL)) : "";
			weakGpu = WEAK_GPU_RE.test(name);
			var lose = gl && gl.getExtension("WEBGL_lose_context");
			if (lose) { lose.loseContext(); }
		} catch (e) { weakGpu = false; }
		return !!weakGpu;
	}
	window.__minecraftWebWeakGpu = isWeakGpu;
	// The game mirrors its shader pack choice here (ShaderPacks.LS_SELECTED).
	function shaderPackSelected() {
		try { return !!window.localStorage.getItem("minecraft.shaderpack.selected"); } catch (e) { return false; }
	}
	window.__minecraftWebPixelRatio = function (c) {
		var r = window.devicePixelRatio || 1;
		if (forcedScale >= 0.25 && forcedScale <= 3) { return forcedScale; }
		var coarse = !!(window.matchMedia && matchMedia("(pointer: coarse)").matches);
		var weak = isWeakGpu();
		var css = (c.clientWidth || 1) * (c.clientHeight || 1);
		if (coarse) {
			r = Math.min(r, 2);
			if (css * r * r > PHONE_PIXEL_BUDGET) { r = Math.max(1, Math.sqrt(PHONE_PIXEL_BUDGET / css)); }
		}
		if (weak) {
			if (!coarse && r > 1) { r = 1; }
			if (css * r * r > WEAK_GPU_PIXEL_BUDGET) {
				r = Math.min(r, Math.max(0.65, Math.sqrt(WEAK_GPU_PIXEL_BUDGET / css)));
			}
		}
		// Shader packs draw several full-screen passes per frame, so a weak GPU is fill-rate
		// bound: render at half resolution (a quarter of the pixels) while a pack is selected.
		if (shaderPackSelected() && weak) { r = Math.min(r, 0.5); }
		return r;
	};
	function fitCanvas() {
		var c = document.getElementById("game_frame");
		if (!c) { return; }
		var r = window.__minecraftWebPixelRatio(c);
		var w = Math.max(1, Math.round(c.clientWidth * r) | 0);
		var h = Math.max(1, Math.round(c.clientHeight * r) | 0);
		if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
		var crisp = r <= 0.55 ? "pixelated" : "";
		if (c.style.imageRendering !== crisp) { c.style.imageRendering = crisp; }
	}
	window.addEventListener("resize", fitCanvas);
	// Picking or clearing a pack changes the ratio; the engine re-reads it on resize.
	function onShaderPackChange(e) {
		if (e && e.type === "storage" && e.key && e.key !== "minecraft.shaderpack.selected") { return; }
		fitCanvas();
		window.dispatchEvent(new Event("resize"));
	}
	window.addEventListener("minecraft-web:shaderpack", onShaderPackChange);
	window.addEventListener("storage", onShaderPackChange);
	document.addEventListener("fullscreenchange", fitCanvas);
	if (window.visualViewport) { window.visualViewport.addEventListener("resize", fitCanvas); }
	fitCanvas();

	function payload(id) {
		var el = document.getElementById(id);
		var t = el ? el.textContent : null;
		if (el && el.parentNode) { el.parentNode.removeChild(el); }
		return t;
	}
	function removeEl(el) {
		if (el && el.parentNode) { el.parentNode.removeChild(el); }
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
	function getCacheKey() {
		var metaEl = document.querySelector('meta[name="minecraft-cache-key"]');
		if (metaEl && metaEl.content) { return metaEl.content; }
		var buildEl = document.querySelector('meta[name="minecraft-build"]');
		return buildEl && buildEl.content ? buildEl.content : "";
	}
	function isWasmMagic(b) {
		return b && b.length >= 8 && b[0] === 0 && b[1] === 97 && b[2] === 115 && b[3] === 109;
	}

	async function createOgvpDecoder(wasmBytes, metaBytes, audioBytes) {
		if (!wasmBytes || !metaBytes || !audioBytes || !wasmBytes.length || !metaBytes.length) { return null; }
		var loaded = await WebAssembly.instantiate(wasmBytes, {});
		var ex = loaded.instance.exports;
		if (typeof ex._initialize === "function") { ex._initialize(); }
		var dv = new DataView(metaBytes.buffer, metaBytes.byteOffset, metaBytes.byteLength);
		var magic = "";
		for (var mi = 0; mi < 8; mi++) { magic += String.fromCharCode(metaBytes[mi]); }
		if (magic !== "OGVP4M01") { throw new Error("Invalid OGVP4M01 header"); }
		var numSetups = dv.getUint32(8, true);
		var numEntries = dv.getUint32(12, true);
		var priorSize = dv.getUint32(16, true);
		var off = 20;
		for (var sid = 0; sid < numSetups; sid++) {
			var il = dv.getUint16(off, true), sl = dv.getUint16(off + 2, true);
			off += 4;
			var idB = metaBytes.subarray(off, off + il); off += il;
			var suB = metaBytes.subarray(off, off + sl); off += sl;
			var prB = metaBytes.subarray(off, off + priorSize); off += priorSize;
			var pId = ex.malloc(il) >>> 0, pSu = ex.malloc(sl) >>> 0, pPr = ex.malloc(priorSize) >>> 0;
			var mem = new Uint8Array(ex.memory.buffer);
			mem.set(idB, pId); mem.set(suB, pSu); mem.set(prB, pPr);
			var rc = ex.ogvp_init_setup(sid, pId, il, pSu, sl, pPr);
			ex.free(pId); ex.free(pSu); ex.free(pPr);
			if (rc !== 0) { throw new Error("OGVP setup init failed: " + rc); }
		}
		var HEX = "0123456789abcdef";
		var entries = Object.create(null);
		for (var ei = 0; ei < numEntries; ei++) {
			var sha = "";
			for (var hi = 0; hi < 20; hi++) {
				var b = metaBytes[off + hi];
				sha += HEX[(b >>> 4) & 15] + HEX[b & 15];
			}
			off += 20;
			var origLen = dv.getUint32(off, true); off += 4;
			var aOff = dv.getUint32(off, true); off += 4;
			var aLen = dv.getUint32(off, true); off += 4;
			var mLen = dv.getUint32(off, true); off += 4;
			entries[sha] = [origLen, aOff, aLen, off, mLen];
			off += mLen;
		}
		var pM = 0, capM = 0, pA = 0, capA = 0, pO = 0, capO = 0;
		return {
			count: numEntries,
			decode: function (sha) {
				var e = entries[sha];
				if (!e) { return null; }
				var origLen = e[0], aOff = e[1], aLen = e[2], mOff = e[3], mLen = e[4];
				var needO = origLen + 4096;
				if (mLen > capM) { if (pM) { ex.free(pM); } capM = Math.max(4096, mLen); pM = ex.malloc(capM) >>> 0; }
				if (aLen > capA) { if (pA) { ex.free(pA); } capA = Math.max(65536, aLen); pA = ex.malloc(capA) >>> 0; }
				if (needO > capO) { if (pO) { ex.free(pO); } capO = Math.max(131072, needO); pO = ex.malloc(capO) >>> 0; }
				var mem = new Uint8Array(ex.memory.buffer);
				mem.set(metaBytes.subarray(mOff, mOff + mLen), pM);
				mem.set(audioBytes.subarray(aOff, aOff + aLen), pA);
				var outLen = ex.ogvp_decode_split(pM, mLen, pA, aLen, pO, capO);
				if (outLen !== origLen) {
					console.error("[26.3-offline] OGVP decode error for " + sha + ": " + outLen + " vs " + origLen);
					return null;
				}
				mem = new Uint8Array(ex.memory.buffer);
				return mem.slice(pO, pO + outLen);
			}
		};
	}

	function installObjectShim(bin, map, ogvp) {
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
			var sha = m[1];
			var span = map[sha];
			if (span) { return bin.subarray(span[0], span[0] + span[1]); }
			return ogvp ? ogvp.decode(sha) : null;
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
			var isArrayBuffer = this.responseType === "arraybuffer" && hit;
			var body = "";
			if (text !== null) { body = text; }
			else if (!isArrayBuffer) {
				var chars = new Array(hit.length);
				for (var i = 0; i < hit.length; i++) { chars[i] = String.fromCharCode(hit[i] & 255); }
				body = chars.join("");
			}
			define("status", 200); define("readyState", 4); define("statusText", "OK");
			if (!isArrayBuffer) { define("responseText", body); }
			define("response", isArrayBuffer
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

			var wasmEl = document.getElementById("p-wasm");
			var restEl = document.getElementById("p-rest");
			var td = new TextDecoder();
			var wasm = null;
			var rest = null;
			var compilePromise = null;

			if (wasmEl && restEl) {
				var cacheKey = getCacheKey();
				var skipCache = /[?&]nocache=1(?:[&]|$)/.test(location.search || "");
				var offlineCache = (!skipCache && cacheKey && window.__minecraftInstallOfflineCache)
					? window.__minecraftInstallOfflineCache() : null;
				var wasmKey = cacheKey + ":wasm";
				var restKey = cacheKey + ":rest";
				var warmHit = false;

				if (offlineCache) {
					try {
						var cachedWasmP = Promise.resolve(offlineCache.get(wasmKey)).catch(function () { return null; }).then(function (w) {
							if (isWasmMagic(w)) {
								compilePromise = WebAssembly.compile(w, { builtins: ["js-string"] });
								return w;
							}
							return null;
						});
						var cachedRestP = Promise.resolve(offlineCache.get(restKey)).catch(function () { return null; });
						var cachedPair = await Promise.all([cachedWasmP, cachedRestP]);
						if (cachedPair[0] && cachedPair[1] && cachedPair[1].length) {
							rest = window.__minecraftArchiveUnpack(cachedPair[1]);
							wasm = cachedPair[0];
							warmHit = true;
							removeEl(wasmEl);
							removeEl(restEl);
							removeEl(document.getElementById("p-loader"));
							stage("Compiling", 40);
							console.info("[26.3-offline] warm-boot hit from IndexedDB cache");
						} else if (compilePromise) {
							compilePromise.catch(function () {});
							compilePromise = null;
						}
					} catch (cacheErr) {
						if (compilePromise) { compilePromise.catch(function () {}); }
						warmHit = false;
						wasm = null;
						rest = null;
						compilePromise = null;
					}
				}

				if (!warmHit) {
					stage("Unpacking", 10);
					var loaderB64 = payload("p-loader");
					var unpack = window.__minecraftParallelUnpack(wasmEl, restEl, loaderB64, function (kind, data, rawRestBytes) {
						if (kind === "wasm") {
							wasm = data;
							compilePromise = WebAssembly.compile(wasm, { builtins: ["js-string"] });
							stage("Compiling", 35);
							if (offlineCache) {
								try { Promise.resolve(offlineCache.put(wasmKey, wasm)).catch(function () {}); } catch (e) {}
							}
						} else if (kind === "rest") {
							if (offlineCache && rawRestBytes) {
								try {
									Promise.resolve(offlineCache.put(restKey, rawRestBytes)).then(function () {
										if (window.__minecraftPruneStore) {
											return window.__minecraftPruneStore(offlineCache, [wasmKey, restKey]);
										}
									}).catch(function () {});
								} catch (e) {}
							}
						}
					});
					var unpacked = await unpack;
					wasm = unpacked.wasm;
					rest = unpacked.rest;
					if (!compilePromise) {
						compilePromise = WebAssembly.compile(wasm, { builtins: ["js-string"] });
					}
				}
			} else {
				var xzEl = document.getElementById("p-xz");
				var packed = window.__minecraftPayloadDecode(xzEl);
				removeEl(xzEl);
				var loader = b64(payload("p-loader"));
				stage("Unpacking", 10);
				await idle();
				var singleArchive = await window.__minecraftXZDecode(packed, loader);
				packed = loader = null;
				wasm = singleArchive.wasm.slice();
				compilePromise = WebAssembly.compile(wasm, { builtins: ["js-string"] });
				rest = singleArchive;
			}

			var simdSrc = "";
			if (rest.js_mesh_worker && rest.js_mesh_worker.length) {
				window.__minecraftWebMeshWorkerUrl = URL.createObjectURL(
					new Blob([rest.js_mesh_worker], { type: "text/javascript" })
				);
			}
			if (rest.js_simd_kernels && rest.js_simd_kernels.length) {
				simdSrc = td.decode(rest.js_simd_kernels);
				window.__minecraftWebSimdKernelsText = simdSrc;
				(0, eval)(simdSrc);
			} else {
				var simdEl = document.getElementById("simd-kernels-src");
				if (simdEl) { simdSrc = simdEl.textContent; }
			}
			var runtime = td.decode(rest.runtime);
			(0, eval)(runtime);
			if (!window.TeaVM || !TeaVM.wasmGC) { throw new Error("TeaVM WASM-GC runtime did not load"); }
			if (rest.js_translator && rest.js_translator.length) { (0, eval)(td.decode(rest.js_translator)); }
			if (rest.js_bridge && rest.js_bridge.length) { (0, eval)(td.decode(rest.js_bridge)); }
			if (rest.js_modrinth && rest.js_modrinth.length) { (0, eval)(td.decode(rest.js_modrinth)); }

			var meta = JSON.parse(td.decode(rest.meta));
			window.__minecraftFiles = JSON.stringify(meta.files);
			window.__minecraftAssetsIndex = td.decode(rest.index);
			// Own buffers: subarrays would pin the whole archive buffer for the session.
			var assets = rest.assets.slice();
			var objects = rest.objects.slice();
			var shaderPacks = rest.shaderpacks ? rest.shaderpacks.slice() : null;
			var ogvpWasm = rest.ogvp_wasm ? rest.ogvp_wasm.slice() : null;
			var ogvpMeta = rest.ogvp_meta ? rest.ogvp_meta.slice() : null;
			rest = null;
			var ogvp = null;
			var sfxEl = document.getElementById("p-sfx");
			if (sfxEl && ogvpWasm && ogvpMeta) {
				var sfxT0 = performance.now();
				var ogvpAudio = window.__minecraftPayloadDecode(sfxEl);
				removeEl(sfxEl);
				ogvp = await createOgvpDecoder(ogvpWasm, ogvpMeta, ogvpAudio);
				ogvpWasm = null;
				console.info("[26.3-offline] OGVP-V4 lazy audio decoder ready (" + (ogvp ? ogvp.count : 0) + " sounds, " + Math.round(performance.now() - sfxT0) + "ms)");
			} else if (sfxEl) {
				removeEl(sfxEl);
			}
			window.__minecraftAssetsBinRaw = assets;
			window.__minecraftWorkerAssetsRaw = assets;
			window.__minecraftWorkerAssetsIndex = window.__minecraftAssetsIndex;
			installObjectShim(objects, meta.objects, ogvp);
			shell.mark("assets");

			stage("Compiling", 45);
			var module = await compilePromise;
			shell.mark("engine");
			window.__minecraftWebSharedModule = module;
			window.__minecraftClientModule = module;
			// The server worker runs this same module (the served page shares it the same way);
			// WebWorkerTransport can reuse __minecraftWebSharedModule or transfer __minecraftWorkerServerWasm.
			// Prepend the offline WASM-SIMD128 kernels and auto-bind hook so server and gen workers get self.__mcWebSimd too.
			var simdBindHook = "\n;(function(){var G=typeof globalThis!=='undefined'?globalThis:self;"
				+ "var T=G.TeaVM&&G.TeaVM.wasmGC;if(T&&T.load&&!T.__mcWebSimdHook){T.__mcWebSimdHook=1;"
				+ "var origLoad=T.load;T.load=function(){return Promise.resolve(origLoad.apply(this,arguments)).then(function(inst){"
				+ "try{var s=G.__mcWebSimd||G.__twitSimd;var m=G.__teavmMemory||(inst&&inst.exports&&inst.exports['teavm.memory']);"
				+ "if(s&&m){s.bind(m);}}catch(e){}return inst;});};}})();";
			window.__minecraftWorkerServerRuntime = simdSrc ? (simdSrc + "\n" + runtime + simdBindHook) : runtime;
			window.__minecraftWorkerServerWasm = wasm;
			window.__minecraftServerModuleUrl = "embedded:";
			var compile = WebAssembly.compile;
			WebAssembly.compile = function (src, opts) {
				return src instanceof WebAssembly.Module ? Promise.resolve(src) : compile.call(WebAssembly, src, opts);
			};
			var before = Promise.resolve(shell.beforeMain ? shell.beforeMain() : null).catch(function () {});
			var teavm = (await Promise.all([TeaVM.wasmGC.load(module), before]))[0];
			if (typeof teavm.exports.main !== "function") { throw new Error("TeaVM WASM-GC module did not export main"); }
			var simd = window.__mcWebSimd || window.__twitSimd;
			if (simd) {
				var simdMem = window.__teavmMemory || teavm.exports["teavm.memory"];
				if (simdMem) { simd.bind(simdMem); }
			}
			glProbeReady = true;
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

def _check_index_html():
    index_html = text(os.path.join(SRC, "index.html"))
    for needle in ("floatfloat(", "_frag_output_00", "classes263.wasm-runtime.js", "minecraft-web-mesh.js",
                   "minecraft-web-simd-kernels.js",
                   "minecraft-web-jvm-translator.js", "minecraft-web-jvm-bridge.js",
                   "minecraft-web-modrinth.js", "WEAK_GPU_PIXEL_BUDGET"):
        if needle not in index_html:
            raise ValueError("served index.html changed (%s missing); update this packager's BOOT" % needle)

def _extract_script_payload_with_enc(doc, script_id):
    pattern = r'<script\s+([^>]*\bid="%s"[^>]*)>(.*?)</script>' % re.escape(script_id)
    match = re.search(pattern, doc, re.S)
    if not match:
        return None, None
    attrs, body = match.group(1), match.group(2)
    enc_match = re.search(r'\bdata-enc="([^"]+)"', attrs)
    enc = enc_match.group(1) if enc_match else "h85"
    if body.startswith("<![CDATA[") and body.endswith("]]>"):
        body = body[9:-3].replace("]]]]><![CDATA[>", "]]>")
    elif script_id == "p-loader" or enc == "h85":
        body = body.strip()
    return body, enc

def _extract_script_payload(doc, script_id):
    body, _ = _extract_script_payload_with_enc(doc, script_id)
    if body is None:
        raise ValueError("script#%s not found in source offline bundle" % script_id)
    return body

def _decode_payload_text(body, enc):
    if enc == "b122":
        return base122_html_decode(body)
    if enc == "xutf":
        return xml_utf8_decode(body)
    if enc == "h85":
        cleaned = re.sub(r"[\t\n\f\r ]+", "", body)
        return base64.b85decode(cleaned.replace(".", "<"))
    raise ValueError("unknown payload encoding: %s" % enc)

def _check_container_header(packed, label):
    if len(packed) < 16 or packed[:8] not in (b"MCXC001\x00", b"MCGC001\x00"):
        raise ValueError("%s has invalid compressed container magic" % label)
    raw_size, packed_size = struct.unpack_from("<II", packed, 8)
    if raw_size < 12 or packed_size != len(packed) - 16 or raw_size + packed_size > 1024 * 1024 * 1024:
        raise ValueError("%s failed container size check (raw=%d, packed=%d, len=%d)" % (
            label, raw_size, packed_size, len(packed)))
    return raw_size, packed_size

def load_prebuilt_decoder(path):
    """Load the compiled XZ decoder WASM from a .wasm file or an existing offline HTML/XML/SVG bundle."""
    raw = read(path)
    if raw[:4] == b"\x00asm":
        return raw
    doc = raw.decode("utf-8")
    loader_b64 = _extract_script_payload(doc, "p-loader")
    wasm = base64.b64decode(loader_b64)
    if wasm[:4] != b"\x00asm":
        raise ValueError("prebuilt decoder in %s does not start with WebAssembly magic" % path)
    return wasm

def load_archive_from_bundle(path, verify_archive=False):
    """Reuse the verified XZ archive(s) + WASM XZ decoder from an existing offline bundle (<2s rebuild)."""
    started = time.time()
    _check_index_html()
    doc = text(path)
    loader_b64 = _extract_script_payload(doc, "p-loader")
    loader_wasm = base64.b64decode(loader_b64)
    if loader_wasm[:4] != b"\x00asm":
        raise ValueError("p-loader in %s is not a valid WebAssembly module" % path)

    version = json.loads(text(os.path.join(SRC, "version.json")))["id"]
    wasm_sha = ""
    inferred_variant = "silent"
    meta_match = re.search(r'<meta\s+name="minecraft-build"\s+content="([^"]+)"', doc)
    if meta_match:
        build_match = re.match(r'^(\S+)\s+offline,\s+engine\s+([0-9a-f]+),\s+(\S+)$', meta_match.group(1).strip())
        if build_match:
            version = build_match.group(1)
            wasm_sha = build_match.group(2)
            inferred_variant = build_match.group(3)
    cache_match = re.search(r'<meta\s+name="minecraft-cache-key"\s+content="([0-9a-f]+)(?:_[0-9a-f]+)?_([^"_]+)"', doc)
    if cache_match:
        if len(cache_match.group(1)) >= len(wasm_sha):
            wasm_sha = cache_match.group(1)
        inferred_variant = cache_match.group(2)

    wasm_body, wasm_enc = _extract_script_payload_with_enc(doc, "p-wasm")
    rest_body, rest_enc = _extract_script_payload_with_enc(doc, "p-rest")
    if wasm_body is not None and rest_body is not None:
        wasm_packed = _decode_payload_text(wasm_body, wasm_enc)
        rest_packed = _decode_payload_text(rest_body, rest_enc)
        w_raw, _ = _check_container_header(wasm_packed, "p-wasm in " + path)
        r_raw, _ = _check_container_header(rest_packed, "p-rest in " + path)
        meta = {"version": version, "wasmSha256": wasm_sha}
        if verify_archive:
            unpack_container(wasm_packed)
            rest_entries = unpack_container(rest_packed)
            meta = json.loads(rest_entries["meta"].decode("utf-8"))
            version = meta.get("version", version)
            wasm_sha = meta.get("wasmSha256", wasm_sha)
        else:
            wasm_path = os.path.join(SRC, "wasm-gc", "classes263.wasm")
            if os.path.isfile(wasm_path):
                cur_sha = sha256(read(wasm_path))
                if wasm_sha and not cur_sha.startswith(wasm_sha):
                    print("warning: staged classes263.wasm (%s) differs from reused archive (%s)" % (
                        cur_sha[:12], wasm_sha[:12]), flush=True)
                elif not wasm_sha:
                    meta["wasmSha256"] = cur_sha
        print("[reuse] loaded 2-stream archive from %s (loader %d B, wasm %.1f MB + rest %.1f MB -> %.1f MB raw) in %.2fs" % (
            os.path.relpath(path, REPO), len(loader_wasm), len(wasm_packed) / 1e6, len(rest_packed) / 1e6,
            (w_raw + r_raw) / 1e6, time.time() - started), flush=True)
        out = {"version": version, "meta": meta, "wasm_packed": wasm_packed, "rest_packed": rest_packed,
               "loader": loader_b64, "variant": inferred_variant}
        if wasm_enc in ("b122", "xutf"):
            out["wasm_" + wasm_enc] = wasm_body
        if rest_enc in ("b122", "xutf"):
            out["rest_" + rest_enc] = rest_body
        sfx_body, sfx_enc = _extract_script_payload_with_enc(doc, "p-sfx")
        if sfx_body is not None:
            out["sfx_packed"] = _decode_payload_text(sfx_body, sfx_enc)
            if sfx_enc in ("b122", "xutf"):
                out["sfx_" + sfx_enc] = sfx_body
        return out

    # Legacy single-stream bundle reuse
    xz_body, xz_enc = _extract_script_payload_with_enc(doc, "p-xz")
    if xz_body is None:
        raise ValueError("neither p-wasm/p-rest nor p-xz found in %s" % path)
    packed_bytes = _decode_payload_text(xz_body, xz_enc)
    raw_size, _ = _check_container_header(packed_bytes, "p-xz in " + path)
    meta = {"version": version, "wasmSha256": wasm_sha}
    if verify_archive:
        entries = unpack_container(packed_bytes)
        meta = json.loads(entries["meta"].decode("utf-8"))
        version = meta.get("version", version)
        wasm_sha = meta.get("wasmSha256", wasm_sha)
    print("[reuse] loaded legacy archive from %s (loader %d B, container %.1f MB -> %.1f MB raw) in %.2fs" % (
        os.path.relpath(path, REPO), len(loader_wasm), len(packed_bytes) / 1e6, raw_size / 1e6,
        time.time() - started), flush=True)
    return {"version": version, "meta": meta, "xz": xz_body, "xz_enc": xz_enc, "loader": loader_b64,
            "variant": inferred_variant}

def build_archive(sounds_mode, xz_preset, xz_extreme, zig, shaderpacks_dir=None, prebuilt_decoder=None, use_ogvp=True):
    started = time.time()
    wasm_dir = os.path.join(SRC, "wasm-gc")
    wasm = read(os.path.join(wasm_dir, "classes263.wasm"))
    runtime = read(os.path.join(wasm_dir, "classes263.wasm-runtime.js"))
    if wasm[:4] != b"\x00asm":
        raise ValueError("classes263.wasm has no WebAssembly header (is a link running?)")
    if b"callInFiber" not in runtime:
        raise ValueError("classes263.wasm-runtime.js is unpatched (patchWasmGCRuntime did not run)")
    _check_index_html()

    raw_assets = gzip.decompress(read(os.path.join(SRC, "assets.bin.gz")))
    raw_index = gzip.decompress(read(os.path.join(SRC, "assets_index.json.gz")))
    assets, index, opt_counts = optimize_assets_bundle(raw_assets, raw_index)
    print("[%s] OPT pre-unpack: assets %.1f MB -> %.1f MB (nbt=%d, zip=%d, png=%d, json=%d)" % (
        sounds_mode, len(raw_assets) / 1e6, len(assets) / 1e6,
        opt_counts["nbt"], opt_counts["zip"], opt_counts["png"], opt_counts["json"]), flush=True)

    objects, sha_map, sounds, languages, ogvp_meta, ogvp_audio, ogvp_wasm = object_pack(
        sounds_mode, use_ogvp=use_ogvp, zig=zig)
    version = json.loads(text(os.path.join(SRC, "version.json")))["id"]
    files = {
        "sounds_index.json": json.dumps(sounds, separators=(",", ":")),
        "lang_index.json": json.dumps(languages, separators=(",", ":")),
        "version.json": text(os.path.join(SRC, "version.json")),
        "sounds.json": "{}" if sounds_mode == "none" else text(os.path.join(SRC, "sounds.json")),
        "pipelines.json": text(os.path.join(SRC, "pipelines.json")),
    }
    meta = {"version": version, "files": files, "objects": sha_map, "sounds": sounds_mode,
            "wasmSha256": sha256(wasm), "runtimeSha256": sha256(runtime), "assetsSha256": sha256(assets),
            "built": time.strftime("%Y-%m-%dT%H:%M:%S")}
    if ogvp_audio:
        meta["ogvp"] = "v4"
        meta["sfxSha256"] = sha256(ogvp_audio)
    packs, pack_spans = shader_pack_bundle(shaderpacks_dir)
    if pack_spans:
        meta["shaderpacks"] = pack_spans

    wasm_entries = {"wasm": wasm}
    rest_entries = {
        "meta": json.dumps(meta, separators=(",", ":")).encode("utf-8"),
        "index": index,
        "runtime": runtime,
        "assets": assets,
        "objects": objects,
    }
    if ogvp_meta and ogvp_wasm:
        rest_entries["ogvp_wasm"] = ogvp_wasm
        rest_entries["ogvp_meta"] = ogvp_meta
    for entry_key, filename in PACKED_SCRIPTS:
        rest_entries[entry_key] = read(os.path.join(SRC, filename))
    if pack_spans:
        rest_entries["shaderpacks"] = packs
        print("[%s] shader packs: %d, %.1f MB" % (sounds_mode, len(pack_spans), len(packs) / 1e6), flush=True)

    print("[%s] 2-stream archive: wasm %.1f MB | rest: assets %.1f MB, objects %.1f MB (%d sounds%s), %d packed JS; xz -%d%s ..." % (
        sounds_mode, len(wasm) / 1e6, len(assets) / 1e6, len(objects) / 1e6, len(sounds),
        (", OGVP-V4 %.2f MB raw audio" % (len(ogvp_audio) / 1e6)) if ogvp_audio else "",
        len(PACKED_SCRIPTS), xz_preset, "e" if xz_extreme else ""), flush=True)
    loader_wasm = load_prebuilt_decoder(prebuilt_decoder) if prebuilt_decoder else build_decoder(zig)

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        f_wasm = pool.submit(xz_container, wasm_entries, xz_preset, xz_extreme, 128)
        f_rest = pool.submit(xz_container, rest_entries, xz_preset, xz_extreme, 128)
        wasm_packed, wasm_raw_b, wasm_packed_b = f_wasm.result()
        rest_packed, rest_raw_b, rest_packed_b = f_rest.result()

    unpacked_wasm = unpack_container(wasm_packed)
    unpacked_rest = unpack_container(rest_packed)
    if unpacked_wasm["wasm"] != wasm or unpacked_rest["assets"] != assets or unpacked_rest["objects"] != objects \
            or (pack_spans and unpacked_rest["shaderpacks"] != packs) \
            or (ogvp_meta and (unpacked_rest["ogvp_meta"] != ogvp_meta or unpacked_rest["ogvp_wasm"] != ogvp_wasm)):
        raise ValueError("archive round trip failed")
    for entry_key, _ in PACKED_SCRIPTS:
        if unpacked_rest[entry_key] != rest_entries[entry_key]:
            raise ValueError("packed script round trip failed: " + entry_key)

    print("[%s] compressed %.1f MB -> %.1f MB (wasm %.1f MB + rest %.1f MB%s) in %.0fs" % (
        sounds_mode, (wasm_raw_b + rest_raw_b) / 1e6,
        (wasm_packed_b + rest_packed_b + len(ogvp_audio)) / 1e6,
        wasm_packed_b / 1e6, rest_packed_b / 1e6,
        (" + ogvp-v4 %.1f MB" % (len(ogvp_audio) / 1e6)) if ogvp_audio else "",
        time.time() - started), flush=True)
    res = {
        "version": version,
        "meta": meta,
        "wasm_packed": wasm_packed,
        "rest_packed": rest_packed,
        "loader": base64.b64encode(loader_wasm).decode("ascii"),
    }
    if ogvp_audio:
        res["sfx_packed"] = ogvp_audio
    return res

def page_parts():
    mesh = text(os.path.join(SRC, "minecraft-web-mesh.js"))
    if mesh.count(MESH_WORKER_SPAWN) != 1:
        raise ValueError("minecraft-web-mesh.js no longer spawns its worker the expected way")
    mesh = mesh.replace(MESH_WORKER_SPAWN,
                        'new Worker(window.__minecraftWebMeshWorkerUrl || new URL("minecraft-web-mesh-worker.js", location.href))')
    scripts = [("minecraft_xz_loader.js", text(os.path.join(REPO, "scripts", "minecraft_xz_loader.js")))]
    for name in PAGE_SCRIPTS:
        scripts.append((name, mesh if name == "minecraft-web-mesh.js" else text(os.path.join(SRC, name))))
    for required in ("boot-logo.png", "favicon.png"):
        if not os.path.isfile(os.path.join(SRC, required)):
            raise ValueError("%s missing from %s: stage the site first" % (required, SRC))
    return {
        "css": text(os.path.join(SRC, "minecraft-web.css")),
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

def _encode_stream(archive, key, fmt):
    enc = "b122" if fmt == "html" else "xutf"
    cache_field = "%s_%s" % (key, enc)
    if cache_field in archive:
        return archive[cache_field], enc
    raw = archive[key + "_packed"]
    encoded = base122_html_encode(raw) if enc == "b122" else xml_utf8_encode(raw)
    archive[cache_field] = encoded
    return encoded, enc

def render(fmt, parts, archive, variant):
    xml = fmt != "html"
    wasm_sha = archive["meta"]["wasmSha256"]
    rest_sha = sha256(archive["rest_packed"])[:16] if "rest_packed" in archive else ""
    build_tag = "%s offline, engine %s, %s" % (archive["version"], wasm_sha[:12], variant)
    cache_key = "%s_%s_%s" % (wasm_sha[:32], rest_sha, variant) if rest_sha else "%s_%s" % (wasm_sha, variant)

    def script(source, name, attrs=""):
        body = cdata("\n" + source + "\n") if xml else "\n" + html_raw(source, name) + "\n"
        return "<script%s>%s</script>" % (attrs, body)

    def data(sid, payload, enc=None):
        if re.search(r"</script|<!--", payload, re.I):
            raise ValueError("payload %s contains </script or <!--" % sid)
        enc_attr = (' data-enc="%s"' % enc) if enc else ""
        if xml and enc == "xutf":
            if "<" in payload or "&" in payload or "]]>" in payload:
                raise ValueError("xutf payload %s contains forbidden XML sequence" % sid)
            body = payload
        else:
            body = cdata(payload) if xml else payload
        return '<script type="application/octet-stream" id="%s"%s>%s</script>' % (sid, enc_attr, body)

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
        '<meta name="minecraft-cache-key" content="%s" />' % cache_key,
        '<link rel="icon" href="%s" />' % parts["icon"],
        style,
    ]
    if xml:
        head.append(script(XML_PRELUDE, "xml prelude"))
    for name, source in parts["scripts"]:
        head.append(script(source, name))

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
    ]
    if "wasm_packed" in archive and "rest_packed" in archive:
        wasm_text, wasm_enc = _encode_stream(archive, "wasm", fmt)
        rest_text, rest_enc = _encode_stream(archive, "rest", fmt)
        body.append(data("p-wasm", wasm_text, wasm_enc))
        body.append(data("p-rest", rest_text, rest_enc))
        if archive.get("sfx_packed"):
            sfx_text, sfx_enc = _encode_stream(archive, "sfx", fmt)
            body.append(data("p-sfx", sfx_text, sfx_enc))
    else:
        body.append(data("p-xz", archive["xz"], archive.get("xz_enc", "h85")))
    body.append(script(BOOT, "boot"))

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
    parser.add_argument("--out", metavar="FILE",
                        help="write a single output file to this exact path (requires one format and one variant)")
    parser.add_argument("--formats", nargs="+", choices=FORMATS, default=list(FORMATS))
    parser.add_argument("--variants", nargs="+", choices=sorted(VARIANTS), default=None)
    parser.add_argument("--xz-preset", type=int, choices=range(0, 10), default=8)
    parser.add_argument("--no-xz-extreme", action="store_true")
    parser.add_argument("--no-ogvp", action="store_true",
                        help="disable OGVP-V4 lossless Vorbis bitstream re-coding for sound variants")
    parser.add_argument("--zig", default=DEFAULT_ZIG if os.path.isfile(DEFAULT_ZIG) else "zig")
    parser.add_argument("--prebuilt-decoder", metavar="PATH",
                        help="use a prebuilt XZ decoder .wasm or extract p-loader from an existing offline bundle instead of running zig")
    parser.add_argument("--reuse-archive-from", metavar="BUNDLE",
                        help="fast mode: reuse the XZ archive and decoder from an existing offline bundle while re-inlining the latest page scripts/CSS/BOOT")
    parser.add_argument("--verify-archive", action="store_true",
                        help="with --reuse-archive-from, fully decompress and verify the reused XZ archive before writing")
    parser.add_argument("--shaderpacks", metavar="DIR",
                        help="bundle the shader packs listed in DIR/packs.json; defaults to build/shaderpacks if present")
    parser.add_argument("--no-shaderpacks", action="store_true",
                        help="do not bundle any default shader packs even if build/shaderpacks/packs.json exists")
    args = parser.parse_args()
    if not args.shaderpacks and not args.no_shaderpacks:
        default_sp = os.path.join(REPO, "build", "shaderpacks")
        if os.path.isfile(os.path.join(default_sp, "packs.json")):
            args.shaderpacks = default_sp
    if args.web:
        SRC = os.path.abspath(args.web)
    parts = page_parts()

    if args.reuse_archive_from:
        if args.variants is not None and len(args.variants) != 1:
            parser.error("--reuse-archive-from holds one archive; pass at most one --variants value")
        if args.out and len(args.formats) != 1:
            parser.error("--out requires exactly one format and one variant")
        archive = load_archive_from_bundle(args.reuse_archive_from, verify_archive=args.verify_archive)
        variants = args.variants if args.variants is not None else [archive.get("variant") or "silent"]
        archive_iter = [(variants[0], archive)]
    else:
        variants = args.variants if args.variants is not None else ["sound", "silent"]
        if args.out and (len(variants) != 1 or len(args.formats) != 1):
            parser.error("--out requires exactly one format and one variant")
        archive_iter = (
            (variant, build_archive(VARIANTS[variant], args.xz_preset, not args.no_xz_extreme, args.zig,
                                    args.shaderpacks, prebuilt_decoder=args.prebuilt_decoder,
                                    use_ogvp=(not args.no_ogvp and not args.prebuilt_decoder)))
            for variant in variants
        )

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    else:
        os.makedirs(args.out_dir, exist_ok=True)

    for variant, archive in archive_iter:
        for fmt in args.formats:
            out = os.path.abspath(args.out) if args.out else os.path.join(
                args.out_dir, "minecraft-%s-offline-%s.%s" % (archive["version"], variant, fmt))
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
