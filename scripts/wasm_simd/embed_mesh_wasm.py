#!/usr/bin/env python3
"""Compile, link, validate, embed, and verify the WASM-SIMD128 chunk mesher kernel.

Pipeline:
  1. Compiles scripts/wasm_simd/mesh_kernel.c with native Linux clang-21
     (--target=wasm32 -msimd128 -O3 -ffreestanding -fno-builtin -Wall -Wextra -Werror -c).
  2. Links into a standalone .wasm via scripts/wasm_link_single.py
     (--memory-mode export --initial-pages 192) and validates with validate_wasm().
  3. Embeds base64-encoded .wasm between // BEGIN_MESH_WASM_B64 and
     // END_MESH_WASM_B64 in target_teavm_263/wasm-gc/minecraft-web-mesh-worker.js,
     and mirrors byte-for-byte to
     target_teavm_263/javascript-wasm-gc/minecraft-web-mesh-worker.js when present.
  4. Runs comprehensive verification:
     - Offline-bundle safety: zero </script, <!--, or ]]> in minecraft-web-mesh-worker.js
     - Byte-for-byte identity between wasm-gc and javascript-wasm-gc copies (when present)
     - Exact round-trip decode of embedded MESH_WASM_B64 == freshly linked .wasm
     - Live QuickJS (/root/brel/qjs) execution in BOTH:
       (a) JS fallback mode (no WebAssembly)
       (b) Mock-WASM dispatch mode (fake globalThis.WebAssembly with simulated
           memory.grow detachment, DEFS including oversized chunked DEFS, TAGS,
           RESET, JOB wire output + transfer list, timing word stamp, decline &
           overflow handling, init failure / CompileError / flag disable fallback,
           and DEFS capacity-exceeded fallback to JS)
       (c) CommonJS module.exports compatibility check (mesh_kernel_test.mjs API)
"""

from __future__ import annotations

import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent.parent
KERNEL_C = ROOT / "scripts/wasm_simd/mesh_kernel.c"
LINKER_PY = ROOT / "scripts/wasm_link_single.py"
WORKER_WASM_GC = ROOT / "target_teavm_263/wasm-gc/minecraft-web-mesh-worker.js"
WORKER_JS_WASM_GC = ROOT / "target_teavm_263/javascript-wasm-gc/minecraft-web-mesh-worker.js"
QJS_BIN = Path("/root/brel/qjs")

BEGIN_MARKER = "// BEGIN_MESH_WASM_B64"
END_MARKER = "// END_MESH_WASM_B64"

REQUIRED_EXPORTS = [
    "memory",
    "wasm_init",
    "wasm_reset",
    "wasm_job_in_ptr",
    "wasm_job_in_cap",
    "wasm_out_ptr",
    "wasm_out_cap",
    "wasm_read_defs",
    "wasm_read_tags",
    "wasm_run_job",
]


def compile_and_link_wasm(tmp_dir: Path) -> tuple[bytes, dict]:
    """Compile mesh_kernel.c to wasm32-simd128 and link with wasm_link_single.py."""
    obj_path = tmp_dir / "mesh_kernel.o"
    wasm_path = tmp_dir / "mesh_kernel.wasm"

    subprocess.run(
        [
            "clang-21",
            "--target=wasm32",
            "-msimd128",
            "-O3",
            "-ffreestanding",
            "-fno-builtin",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-c",
            str(KERNEL_C),
            "-o",
            str(obj_path),
        ],
        check=True,
    )

    subprocess.run(
        [
            sys.executable,
            str(LINKER_PY),
            str(obj_path),
            "-o",
            str(wasm_path),
            "--memory-mode",
            "export",
            "--initial-pages",
            "192",
            "--inspect",
        ],
        check=True,
    )

    wasm_bytes = wasm_path.read_bytes()

    scripts_dir = str(ROOT / "scripts")
    if scripts_dir not in sys.path:
        sys.path.insert(0, scripts_dir)
    import wasm_link_single  # type: ignore

    info = wasm_link_single.validate_wasm(wasm_bytes)
    if info["imports"]:
        raise AssertionError(f"Expected zero imports in standalone worker .wasm, got {info['imports']}")
    export_names = [name for name, _kind, _idx in info["exports"]]
    missing = [name for name in REQUIRED_EXPORTS if name not in export_names]
    if missing:
        raise AssertionError(f"Missing required exports: {missing} (got {export_names})")
    return wasm_bytes, info


def embed_into_workers(wasm_bytes: bytes) -> tuple[str, int]:
    """Inject MESH_WASM_B64 into wasm-gc worker and mirror to javascript-wasm-gc if present."""
    raw = WORKER_WASM_GC.read_bytes()
    newline = "\r\n" if b"\r\n" in raw else "\n"
    text = raw.decode("utf-8")

    b64 = base64.b64encode(wasm_bytes).decode("ascii")
    pattern = re.compile(
        re.escape(BEGIN_MARKER) + r"\r?\n.*?\r?\n" + re.escape(END_MARKER),
        re.DOTALL,
    )
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        raise RuntimeError(
            f"Expected exactly 1 {BEGIN_MARKER}..{END_MARKER} block in {WORKER_WASM_GC}, found {len(matches)}"
        )

    replacement = f"{BEGIN_MARKER}{newline}var MESH_WASM_B64 = \"{b64}\";{newline}{END_MARKER}"
    updated = text[: matches[0].start()] + replacement + text[matches[0].end() :]
    updated_bytes = updated.encode("utf-8")

    WORKER_WASM_GC.write_bytes(updated_bytes)
    if WORKER_JS_WASM_GC.parent.is_dir():
        WORKER_JS_WASM_GC.write_bytes(updated_bytes)
    return b64, len(updated_bytes)


def verify_static_invariants(expected_wasm: bytes) -> None:
    """Verify offline-bundle safety, optional mirror identity, and exact base64 round-trip."""
    b_wasm_gc = WORKER_WASM_GC.read_bytes()

    if WORKER_JS_WASM_GC.parent.is_dir():
        b_js_wasm_gc = WORKER_JS_WASM_GC.read_bytes()
        if b_wasm_gc != b_js_wasm_gc:
            raise AssertionError(
                f"Mirror mismatch: {WORKER_WASM_GC} ({len(b_wasm_gc)} B) != {WORKER_JS_WASM_GC} ({len(b_js_wasm_gc)} B)"
            )

    text = b_wasm_gc.decode("utf-8")

    if re.search(r"</script", text, re.IGNORECASE):
        raise AssertionError("minecraft-web-mesh-worker.js contains forbidden </script")
    if "<!--" in text:
        raise AssertionError("minecraft-web-mesh-worker.js contains forbidden <!--")
    if "]]>" in text:
        raise AssertionError("minecraft-web-mesh-worker.js contains forbidden ]]>")

    m = re.search(
        re.escape(BEGIN_MARKER) + r'\r?\nvar MESH_WASM_B64 = "([A-Za-z0-9+/=]*)";\r?\n' + re.escape(END_MARKER),
        text,
    )
    if not m:
        raise AssertionError("Could not parse embedded MESH_WASM_B64 declaration")
    decoded = base64.b64decode(m.group(1), validate=True)
    if decoded != expected_wasm:
        raise AssertionError(
            f"Embedded MESH_WASM_B64 decoded to {len(decoded)} B (sha256={hashlib.sha256(decoded).hexdigest()[:16]}), "
            f"expected {len(expected_wasm)} B (sha256={hashlib.sha256(expected_wasm).hexdigest()[:16]})"
        )


def run_qjs_script(tmp_dir: Path, name: str, script: str) -> dict:
    script_path = tmp_dir / name
    script_path.write_text(script, encoding="utf-8")
    proc = subprocess.run(
        [str(QJS_BIN), "--std", str(script_path)],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"QuickJS test {name} exited {proc.returncode}:\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
        )
    lines = [ln for ln in proc.stdout.splitlines() if ln.startswith("QJS_OK=")]
    if not lines:
        raise RuntimeError(f"QuickJS test {name} did not emit QJS_OK:\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}")
    return json.loads(lines[-1][len("QJS_OK=") :])


def verify_qjs_modes(tmp_dir: Path, expected_wasm: bytes) -> dict:
    """Test minecraft-web-mesh-worker.js in QuickJS in JS fallback, mock-WASM, and module.exports modes."""
    wasm_path = tmp_dir / "expected.wasm"
    wasm_path.write_bytes(expected_wasm)

    worker_path_js = json.dumps(str(WORKER_WASM_GC))
    wasm_path_js = json.dumps(str(wasm_path))

    # --- Test A: JS Fallback Mode (no globalThis.WebAssembly) ---
    fallback_driver = f"""
const workerSrc = std.loadFile({worker_path_js});
if (!workerSrc) throw new Error("Failed to read worker");

globalThis.self = globalThis;
globalThis.importScripts = function () {{}};
globalThis.performance = {{ now: function () {{ return Date.now(); }} }};
const posted = [];
globalThis.postMessage = function (msg, transfer) {{
    posted.push({{ msg: msg, transfer: transfer || null }});
}};

(0, eval)(workerSrc);

if (posted.length !== 1 || !posted[0].msg.ready || posted[0].msg.wasm !== false) {{
    throw new Error("Expected JS fallback ready message with wasm=false, got: " + JSON.stringify(posted[0]));
}}
if (!posted[0].msg.tags || !posted[0].msg.caps || !posted[0].msg.capsHalo2) {{
    throw new Error("Missing caps/tags on ready message");
}}

const resetBuf = new ArrayBuffer(16);
new Int32Array(resetBuf).set([3, 1, 0, 16]);
self.onmessage({{ data: resetBuf }});

const nameStr = "minecraft:air";
const defsBuf = new ArrayBuffer(8 + 1 + 47 + nameStr.length);
const ddv = new DataView(defsBuf);
ddv.setInt32(0, 1, true);
ddv.setInt32(4, 1, true);
ddv.setUint8(8, 5);
ddv.setUint16(9, 0, true);
ddv.setInt32(11, 1 | 32, true);
ddv.setInt32(15, 0, true);
ddv.setFloat32(20, 1.0, true);
ddv.setUint16(9 + 45, nameStr.length, true);
for (let i = 0; i < nameStr.length; i++) ddv.setUint8(9 + 47 + i, nameStr.charCodeAt(i));

posted.length = 0;
self.onmessage({{ data: defsBuf }});
if (posted.length !== 1 || !posted[0].msg.names || posted[0].msg.names[0][1] !== "minecraft:air") {{
    throw new Error("JS fallback DEFS did not post expected names");
}}

const tagsBuf = new ArrayBuffer(16);
new Int32Array(tagsBuf).set([4, 0, 0, 0]);
self.onmessage({{ data: tagsBuf }});

const cells = 18 * 18 * 18;
const jobBuf = new ArrayBuffer(64 + cells * 3);
const jh = new Int32Array(jobBuf, 0, 16);
const jhf = new Float32Array(jobBuf, 0, 16);
jh[0] = 2; jh[1] = 42; jh[2] = 1; jh[3] = 0;
jhf[4] = 0.5; jhf[5] = 1.0; jhf[6] = 0.8; jhf[7] = 0.8; jhf[8] = 0.6; jhf[9] = 0.6;
jh[14] = 18;

posted.length = 0;
self.onmessage({{ data: jobBuf }});
if (posted.length !== 1 || !(posted[0].msg instanceof ArrayBuffer)) {{
    throw new Error("JS fallback JOB did not post ArrayBuffer result");
}}
if (!posted[0].transfer || posted[0].transfer.length !== 1 || posted[0].transfer[0] !== posted[0].msg) {{
    throw new Error("JS fallback JOB transfer list mismatch");
}}
const rh = new Int32Array(posted[0].msg, 0, 16);
if (rh[0] !== 2 || rh[1] !== 42 || rh[2] !== 1 || rh[3] !== 0 || rh[4] !== -1 || rh[5] !== 15) {{
    throw new Error("JS fallback JOB header mismatch: " + Array.from(rh).join(","));
}}

print("QJS_OK=" + JSON.stringify({{ mode: "js_fallback", readyWasm: false, jobStatus: rh[3] }}));
"""
    res_fallback = run_qjs_script(tmp_dir, "test_js_fallback.js", fallback_driver)

    # --- Test B: Mock-WASM Dispatch Mode (full ABI, memory.grow detachment, chunked DEFS, declines, fallback) ---
    mock_wasm_driver = f"""
const workerSrc = std.loadFile({worker_path_js});
if (!workerSrc) throw new Error("Failed to read worker");

function readFileBytes(path) {{
    const f = std.open(path, "rb");
    f.seek(0, std.SEEK_END);
    const sz = f.tell();
    f.seek(0, std.SEEK_SET);
    const ab = new ArrayBuffer(sz);
    f.read(ab, 0, sz);
    f.close();
    return new Uint8Array(ab);
}}
const expectedWasm = readFileBytes({wasm_path_js});

function runScenario(opts) {{
    const log = [];
    const posted = [];
    let perfNow = 100.0;
    const IN_PTR = 256;
    const IN_CAP = opts.inCap || 4096;
    const OUT_PTR = IN_PTR + IN_CAP + 256;
    const OUT_CAP = opts.outCap || 4096;
    let memPages = 2;
    let memBuf = new ArrayBuffer(memPages * 65536);
    let growCount = 0;

    function simulateGrow(extraPages) {{
        const oldU8 = new Uint8Array(memBuf);
        memPages += extraPages;
        const nextBuf = new ArrayBuffer(memPages * 65536);
        new Uint8Array(nextBuf).set(oldU8);
        memBuf = nextBuf;
        growCount++;
    }}

    const fakeMemory = {{
        get buffer() {{ return memBuf; }}
    }};

    let capturedWasmBytes = null;
    const defChunksReceived = [];
    let lastTagsBytes = null;

    const fakeWebAssembly = {{
        Module: function (bytes) {{
            if (opts.throwCompileError) {{
                throw new Error("CompileError: SIMD128 not supported");
            }}
            capturedWasmBytes = new Uint8Array(bytes.slice ? bytes.slice(0) : bytes);
            this._bytes = capturedWasmBytes;
        }},
        Instance: function (mod, imports) {{
            if (!(mod instanceof fakeWebAssembly.Module)) throw new Error("Bad Module");
            this.exports = {{
                memory: fakeMemory,
                wasm_init: function () {{
                    log.push(["wasm_init"]);
                    if (opts.failInit) return -1;
                    simulateGrow(2);
                    return 0;
                }},
                wasm_reset: function (ep) {{
                    log.push(["wasm_reset", ep]);
                }},
                wasm_job_in_ptr: function () {{ return IN_PTR; }},
                wasm_job_in_cap: function () {{ return IN_CAP; }},
                wasm_out_ptr: function () {{ return OUT_PTR; }},
                wasm_out_cap: function () {{ return OUT_CAP; }},
                wasm_read_defs: function (ptr, len) {{
                    if (ptr !== IN_PTR) throw new Error("wasm_read_defs wrong ptr: " + ptr);
                    const copy = new Uint8Array(memBuf, ptr, len).slice();
                    defChunksReceived.push(copy);
                    log.push(["wasm_read_defs", ptr, len]);
                    if (opts.growOnDefs) simulateGrow(1);
                    if (opts.defsRc !== undefined) return opts.defsRc;
                    return 0;
                }},
                wasm_read_tags: function (ptr, len) {{
                    if (ptr !== IN_PTR) throw new Error("wasm_read_tags wrong ptr: " + ptr);
                    lastTagsBytes = new Uint8Array(memBuf, ptr, len).slice();
                    log.push(["wasm_read_tags", ptr, len]);
                    return 0;
                }},
                wasm_run_job: function (ptr, len) {{
                    if (ptr !== IN_PTR) throw new Error("wasm_run_job wrong ptr: " + ptr);
                    const inH = new Int32Array(memBuf, ptr, 16);
                    const jobId = inH[1], jobEpoch = inH[2];
                    log.push(["wasm_run_job", ptr, len, jobId, jobEpoch]);
                    perfNow += 1.25;
                    if (opts.throwOnJob) {{
                        throw new Error("RuntimeError: simulated wasm trap");
                    }}
                    if (opts.growOnJob) {{
                        simulateGrow(1);
                    }}
                    if (opts.jobRc !== undefined && opts.jobRc <= 0) {{
                        const oh = new Int32Array(memBuf, OUT_PTR, 16);
                        oh.fill(0);
                        oh[0] = 2; oh[1] = jobId; oh[2] = jobEpoch; oh[3] = 1;
                        return opts.jobRc;
                    }}
                    const outWords = 16 + 2 + 28;
                    const oh = new Int32Array(memBuf, OUT_PTR, outWords);
                    oh.fill(0);
                    oh[0] = 2; oh[1] = jobId; oh[2] = jobEpoch; oh[3] = 0;
                    oh[4] = -1; oh[5] = 15;
                    oh[6] = 28 * 4; oh[7] = 0; oh[8] = 0; oh[9] = 2;
                    oh[16] = 7; oh[17] = 19;
                    for (let k = 0; k < 28; k++) oh[18 + k] = 0x1000 + k;
                    return outWords * 4;
                }}
            }};
        }}
    }};

    const selfObj = {{
        __minecraftWebDisableWasmMesh: !!opts.disableFlag
    }};
    const locObj = {{ search: opts.locationSearch || "" }};
    const perfObj = {{ now: function () {{ return perfNow; }} }};
    const postFn = function (msg, transfer) {{
        posted.push({{ msg: msg, transfer: transfer || null }});
    }};
    const importScriptsFn = function () {{}};

    const factory = new Function(
        "self", "postMessage", "importScripts", "performance", "WebAssembly", "location",
        workerSrc
    );
    factory(selfObj, postFn, importScriptsFn, perfObj, fakeWebAssembly, locObj);

    return {{
        self: selfObj,
        posted: posted,
        log: log,
        capturedWasmBytes: capturedWasmBytes,
        defChunksReceived: defChunksReceived,
        getLastTagsBytes: function () {{ return lastTagsBytes; }},
        getMemBuf: function () {{ return memBuf; }},
        getGrowCount: function () {{ return growCount; }}
    }};
}}

function makeStateRecord(id, flags, need, name) {{
    const buf = new ArrayBuffer(1 + 47 + name.length);
    const dv = new DataView(buf);
    dv.setUint8(0, 5);
    dv.setUint16(1, id, true);
    dv.setInt32(3, flags, true);
    dv.setInt32(7, need, true);
    dv.setFloat32(12, 1.0, true);
    dv.setUint16(1 + 45, name.length, true);
    for (let i = 0; i < name.length; i++) dv.setUint8(1 + 47 + i, name.charCodeAt(i));
    return new Uint8Array(buf);
}}

function makeDefsBlob(epoch, records) {{
    let total = 8;
    for (const r of records) total += r.byteLength;
    const ab = new ArrayBuffer(total);
    const dv = new DataView(ab);
    dv.setInt32(0, 1, true);
    dv.setInt32(4, epoch, true);
    const u8 = new Uint8Array(ab);
    let p = 8;
    for (const r of records) {{ u8.set(r, p); p += r.byteLength; }}
    return ab;
}}

function makeJobBlob(jobId, epoch, extraLen) {{
    const cells = 18 * 18 * 18;
    const ab = new ArrayBuffer(64 + cells * 3 + (extraLen || 0));
    const h = new Int32Array(ab, 0, 16);
    const hf = new Float32Array(ab, 0, 16);
    h[0] = 2; h[1] = jobId; h[2] = epoch; h[3] = 0;
    hf[4] = 0.5; hf[5] = 1.0; hf[6] = 0.8; hf[7] = 0.8; hf[8] = 0.6; hf[9] = 0.6;
    h[13] = extraLen || 0;
    h[14] = 18;
    return ab;
}}

const s1 = runScenario({{ inCap: 32768, outCap: 8192, growOnDefs: true, growOnJob: true }});
if (!s1.posted[0] || !s1.posted[0].msg.ready || s1.posted[0].msg.wasm !== true) {{
    throw new Error("Mock-WASM scenario 1 did not boot with wasm=true");
}}
if (!s1.capturedWasmBytes || s1.capturedWasmBytes.byteLength !== expectedWasm.byteLength) {{
    throw new Error("Decoded MESH_WASM_B64 length mismatch: " +
        (s1.capturedWasmBytes ? s1.capturedWasmBytes.byteLength : -1) + " vs " + expectedWasm.byteLength);
}}
for (let i = 0; i < expectedWasm.byteLength; i++) {{
    if (s1.capturedWasmBytes[i] !== expectedWasm[i]) {{
        throw new Error("Decoded MESH_WASM_B64 byte mismatch at offset " + i);
    }}
}}

const reset1 = new ArrayBuffer(16);
new Int32Array(reset1).set([3, 7, 0, 64]);
s1.self.onmessage({{ data: reset1 }});

s1.posted.length = 0;
s1.self.onmessage({{ data: makeDefsBlob(99, [makeStateRecord(1, 1, 0, "stale:block")]) }});
if (s1.defChunksReceived.length !== 0 || s1.posted.length !== 0) {{
    throw new Error("Stale DEFS was not ignored in WASM mode");
}}

const defs7 = makeDefsBlob(7, [
    makeStateRecord(0, 1 | 32, 0, "minecraft:air"),
    makeStateRecord(1, 2 | 4, 256, "minecraft:stone")
]);
s1.self.onmessage({{ data: defs7 }});
if (s1.defChunksReceived.length !== 1 || s1.defChunksReceived[0].byteLength !== defs7.byteLength) {{
    throw new Error("Valid DEFS not passed to wasm_read_defs");
}}
if (s1.posted.length !== 1 || !s1.posted[0].msg.names || s1.posted[0].msg.names.length !== 2 ||
    s1.posted[0].msg.names[1][1] !== "minecraft:stone" || s1.posted[0].msg.names[1][2] !== 256) {{
    throw new Error("WASM DEFS did not emit expected state names");
}}

const tags1 = new ArrayBuffer(16 + 4 * 8);
const ti = new Int32Array(tags1);
ti[0] = 4; ti[1] = 1; ti[2] = 4; ti[3] = 0;
ti[4] = 0x1000; ti[5] = 0x2000; ti[8] = 0x3000; ti[9] = 0x4000;
s1.self.onmessage({{ data: tags1 }});
if (!s1.getLastTagsBytes() || s1.getLastTagsBytes().byteLength !== tags1.byteLength) {{
    throw new Error("TAGS not forwarded to wasm_read_tags");
}}

s1.posted.length = 0;
const job7 = makeJobBlob(123, 7, 0);
s1.self.onmessage({{ data: job7 }});
if (s1.posted.length !== 1 || !(s1.posted[0].msg instanceof ArrayBuffer)) {{
    throw new Error("WASM JOB did not post ArrayBuffer result");
}}
if (!s1.posted[0].transfer || s1.posted[0].transfer.length !== 1 || s1.posted[0].transfer[0] !== s1.posted[0].msg) {{
    throw new Error("WASM JOB transfer list mismatch");
}}
if (s1.posted[0].msg === s1.getMemBuf()) {{
    throw new Error("WASM JOB transferred linear memory buffer directly instead of slicing!");
}}
const outH = new Int32Array(s1.posted[0].msg);
if (outH.length !== 46 || outH[0] !== 2 || outH[1] !== 123 || outH[2] !== 7 || outH[3] !== 0 ||
    outH[6] !== 112 || outH[9] !== 2 || outH[11] !== 1250 || outH[16] !== 7 || outH[17] !== 19 || outH[45] !== 0x101b) {{
    throw new Error("WASM JOB wire output mismatch: " + Array.from(outH.slice(0, 20)).join(","));
}}

const sChunk = runScenario({{ inCap: 200, outCap: 4096 }});
const rChunk = new ArrayBuffer(16);
new Int32Array(rChunk).set([3, 1, 0, 16]);
sChunk.self.onmessage({{ data: rChunk }});
sChunk.posted.length = 0;
const recs = [];
for (let i = 0; i < 5; i++) recs.push(makeStateRecord(i, 1, i * 16, "minecraft:state_" + i));
const bigDefs = makeDefsBlob(1, recs);
if (bigDefs.byteLength <= 200) throw new Error("Test setup error: bigDefs not > 200");
sChunk.self.onmessage({{ data: bigDefs }});
if (sChunk.defChunksReceived.length < 2) {{
    throw new Error("Oversized DEFS was not chunked: chunks=" + sChunk.defChunksReceived.length);
}}
let totalRecBytes = 0;
for (const c of sChunk.defChunksReceived) {{
    if (c.byteLength > 200) throw new Error("DEFS chunk exceeded inCap: " + c.byteLength);
    const cdv = new DataView(c.buffer, c.byteOffset, 8);
    if (cdv.getInt32(0, true) !== 1 || cdv.getInt32(4, true) !== 1) throw new Error("Bad DEFS chunk header");
    totalRecBytes += c.byteLength - 8;
}}
if (totalRecBytes !== bigDefs.byteLength - 8) {{
    throw new Error("Chunked DEFS record bytes mismatch: " + totalRecBytes + " vs " + (bigDefs.byteLength - 8));
}}
if (sChunk.posted.length !== 1 || !sChunk.posted[0].msg.names || sChunk.posted[0].msg.names.length !== 5) {{
    throw new Error("Chunked DEFS did not emit all 5 state names");
}}

const sDecl = runScenario({{ inCap: 20000, outCap: 4096, jobRc: -5 }});
sDecl.posted.length = 0;
sDecl.self.onmessage({{ data: makeJobBlob(501, 1, 0) }});
if (sDecl.posted.length !== 2 || !sDecl.posted[0].msg.declined || sDecl.posted[0].msg.job !== 501 ||
    !(sDecl.posted[1].msg instanceof ArrayBuffer) || sDecl.posted[1].msg.byteLength !== 64 ||
    new Int32Array(sDecl.posted[1].msg)[3] !== 1) {{
    throw new Error("WASM overflow decline (-5) did not produce expected decline pair");
}}

sDecl.posted.length = 0;
sDecl.self.onmessage({{ data: makeJobBlob(502, 1, 4000) }});
if (sDecl.posted.length !== 2 || !sDecl.posted[0].msg.declined ||
    sDecl.posted[0].msg.declined.indexOf("input") < 0 ||
    new Int32Array(sDecl.posted[1].msg)[1] !== 502 || new Int32Array(sDecl.posted[1].msg)[3] !== 1) {{
    throw new Error("Oversized JOB input did not decline cleanly");
}}

const sTrap = runScenario({{ inCap: 32768, outCap: 4096, throwOnJob: true }});
sTrap.posted.length = 0;
sTrap.self.onmessage({{ data: makeJobBlob(503, 1, 0) }});
if (sTrap.posted.length !== 2 || !sTrap.posted[0].msg.declined ||
    new Int32Array(sTrap.posted[1].msg)[1] !== 503 || new Int32Array(sTrap.posted[1].msg)[3] !== 2) {{
    throw new Error("WASM trap did not produce status=2 error reply");
}}

const sNoSimd = runScenario({{ throwCompileError: true }});
if (sNoSimd.posted[0].msg.wasm !== false) throw new Error("CompileError did not fall back to JS");

const sFailInit = runScenario({{ failInit: true }});
if (sFailInit.posted[0].msg.wasm !== false) throw new Error("wasm_init=-1 did not fall back to JS");

const sFlagOff = runScenario({{ disableFlag: true }});
if (sFlagOff.posted[0].msg.wasm !== false) throw new Error("__minecraftWebDisableWasmMesh did not disable WASM");

const sQueryOff = runScenario({{ locationSearch: "?foo=1&nowasmmesh" }});
if (sQueryOff.posted[0].msg.wasm !== false) throw new Error("?nowasmmesh did not disable WASM");

const sDefsFallback = runScenario({{ inCap: 32768, outCap: 4096, defsRc: -3 }});
if (sDefsFallback.posted[0].msg.wasm !== true) throw new Error("Expected wasm=true before DEFS overflow");
const rf = new ArrayBuffer(16);
new Int32Array(rf).set([3, 1, 0, 16]);
sDefsFallback.self.onmessage({{ data: rf }});
sDefsFallback.posted.length = 0;
sDefsFallback.self.onmessage({{ data: makeDefsBlob(1, [makeStateRecord(0, 1 | 32, 0, "minecraft:air")]) }});
if (sDefsFallback.posted.length !== 1 || sDefsFallback.posted[0].msg.names[0][1] !== "minecraft:air") {{
    throw new Error("DEFS rc=-3 fallback did not replay into JS readDefs");
}}
sDefsFallback.posted.length = 0;
sDefsFallback.self.onmessage({{ data: makeJobBlob(777, 1, 0) }});
if (sDefsFallback.posted.length !== 1 || new Int32Array(sDefsFallback.posted[0].msg)[1] !== 777 ||
    new Int32Array(sDefsFallback.posted[0].msg)[3] !== 0) {{
    throw new Error("JOB after DEFS rc=-3 fallback did not succeed on JS path");
}}

print("QJS_OK=" + JSON.stringify({{
    mode: "mock_wasm",
    decodedBytes: s1.capturedWasmBytes.byteLength,
    growsHandled: s1.getGrowCount(),
    chunkedDefsChunks: sChunk.defChunksReceived.length,
    timingMicros: outH[11]
}}));
"""
    res_mock = run_qjs_script(tmp_dir, "test_mock_wasm.js", mock_wasm_driver)

    # --- Test C: CommonJS module.exports compatibility (no importScripts) ---
    cjs_driver = f"""
const workerSrc = std.loadFile({worker_path_js});
const modObj = {{ exports: {{}} }};
const fn = new Function("module", workerSrc);
fn(modObj);
const k = modObj.exports;
if (typeof k.mthSeed !== "function" || typeof k.initTrig !== "function" || typeof k.fastInvSqrt !== "function") {{
    throw new Error("module.exports missing expected test hooks");
}}
k.initTrig();
const seed = k.mthSeed(10, 20, -30);
if (!Array.isArray(seed) || seed.length !== 2) throw new Error("mthSeed failed");
print("QJS_OK=" + JSON.stringify({{ mode: "cjs_exports", seed: seed }}));
"""
    res_cjs = run_qjs_script(tmp_dir, "test_cjs_exports.js", cjs_driver)

    return {"js_fallback": res_fallback, "mock_wasm": res_mock, "cjs_exports": res_cjs}


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="embed-mesh-wasm-") as tmp:
        tmp_dir = Path(tmp)

        print("[1/4] Compiling & linking scripts/wasm_simd/mesh_kernel.c...")
        wasm_bytes, info = compile_and_link_wasm(tmp_dir)
        print(
            f"  OK: wasm={len(wasm_bytes)} B ({len(wasm_bytes) / 1024:.2f} KiB), "
            f"funcs={info['num_functions']}, data={info['num_data_segments']}, "
            f"imports={len(info['imports'])}, exports={len(info['exports'])}"
        )

        print("[2/4] Embedding MESH_WASM_B64 into minecraft-web-mesh-worker.js...")
        b64, worker_size = embed_into_workers(wasm_bytes)
        print(
            f"  OK: b64={len(b64)} chars ({len(b64) / 1024:.2f} KiB), "
            f"worker={worker_size} B ({worker_size / 1024:.2f} KiB)"
        )

        print("[3/4] Checking static & offline-bundle invariants...")
        verify_static_invariants(wasm_bytes)
        print("  OK: zero </script / <!-- / ]]>, MESH_WASM_B64 exact round-trip")

        print("[4/4] Running QuickJS verification (JS fallback, mock-WASM dispatch, CJS exports)...")
        qjs_res = verify_qjs_modes(tmp_dir, wasm_bytes)
        print(f"  OK: js_fallback={qjs_res['js_fallback']}")
        print(f"  OK: mock_wasm={qjs_res['mock_wasm']}")
        print(f"  OK: cjs_exports={qjs_res['cjs_exports']}")

        print("\n=== EMBED SUMMARY ===")
        print(f"WASM binary : {len(wasm_bytes)} bytes ({len(wasm_bytes) / 1024:.2f} KiB)")
        print(f"Base64 size : {len(b64)} bytes ({len(b64) / 1024:.2f} KiB)")
        print(f"Worker size : {worker_size} bytes ({worker_size / 1024:.2f} KiB)")
        print(f"WASM sha256 : {hashlib.sha256(wasm_bytes).hexdigest()}")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
