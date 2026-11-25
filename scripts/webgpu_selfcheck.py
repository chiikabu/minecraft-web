#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""WebGPU 26.3 default-on & hot-path self-check (native Linux: python3 + QuickJS).

Verifies:
1. Java lexical validity (balanced braces/parens/brackets, strings, comments) across
   the 3 WebGPU backend Java files.
2. Default-on & sessionStorage fallback invariants in WebGpuBackend.java.
3. Exact attachment caller contract (passColor/passDepth with view.attachmentHandle())
   plus hot-path optimization structural checks in WebGpuCommandEncoder.java.
4. All @JSBody snippets across the 3 WebGPU files (including WebGpuNatives.HELPER)
   parse cleanly in QuickJS, follow strict ES5 (no arrow functions, const/let, or
   template literals outside strings), and contain no literal '</script', '<!--', or ']]>'.
5. Live QuickJS execution & unit tests of WebGpuNatives.HELPER:
   - G.replay across all 7 opcodes, non-zero byte offset, Int32Array cache reuse,
     WebAssembly memory.grow buffer refresh, and invalid-opcode error handling.
   - G.clearColorAndDepth single-pass clear for matching mips/dims + mismatch fallback.
   - G.clearRegion 16-slot 4096-byte dynamic-offset ring reuse, zero per-call allocs,
     empty-rect early exit, overflow >16 fallback into G.trash, and G.submit cleanup.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

WEBGPU_DIR = ROOT / "target_teavm_263/src/main/java/com/mojang/blaze3d/webgpu"
WEBGPU_JAVA_FILES = [
    WEBGPU_DIR / "WebGpuBackend.java",
    WEBGPU_DIR / "WebGpuCommandEncoder.java",
    WEBGPU_DIR / "WebGpuNatives.java",
]


def find_qjs() -> Path:
    env_qjs = os.environ.get("QJS")
    if env_qjs:
        p = Path(env_qjs)
        if p.exists():
            return p
    which = shutil.which("qjs")
    if which:
        return Path(which)
    return Path("/root/brel/qjs")


def unescape_java_string(raw: str) -> str:
    """Decode Java string literal escape sequences."""
    out = []
    i = 0
    n = len(raw)
    escapes = {
        "n": "\n",
        "r": "\r",
        "t": "\t",
        "b": "\b",
        "f": "\f",
        '"': '"',
        "'": "'",
        "\\": "\\",
    }
    while i < n:
        ch = raw[i]
        if ch == "\\" and i + 1 < n:
            nxt = raw[i + 1]
            if nxt in escapes:
                out.append(escapes[nxt])
                i += 2
                continue
            if nxt == "u" and i + 5 < n:
                out.append(chr(int(raw[i + 2 : i + 6], 16)))
                i += 6
                continue
        out.append(ch)
        i += 1
    return "".join(out)


def check_java_lexical(path: Path) -> None:
    """Verify balanced {}, (), [] and properly terminated strings/chars/comments."""
    src = path.read_text(encoding="utf-8")
    stack: list[tuple[str, int]] = []
    pairs = {"{": "}", "(": ")", "[": "]"}
    closing = {"}": "{", ")": "(", "]": "["}

    i = 0
    n = len(src)
    line = 1
    while i < n:
        ch = src[i]
        if ch == "\n":
            line += 1
            i += 1
            continue
        if ch == "/" and i + 1 < n and src[i + 1] == "/":
            nl = src.find("\n", i + 2)
            i = n if nl < 0 else nl
            continue
        if ch == "/" and i + 1 < n and src[i + 1] == "*":
            end = src.find("*/", i + 2)
            assert end >= 0, f"{path}:{line}: unclosed block comment"
            line += src.count("\n", i, end + 2)
            i = end + 2
            continue
        if src.startswith('"""', i):
            start_line = line
            i += 3
            found = False
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if src.startswith('"""', i):
                    i += 3
                    found = True
                    break
                if src[i] == "\n":
                    line += 1
                i += 1
            assert found, f"{path}:{start_line}: unclosed text block"
            continue
        if ch == '"':
            start_line = line
            i += 1
            found = False
            while i < n:
                c = src[i]
                if c == "\\":
                    i += 2
                    continue
                if c == '"':
                    i += 1
                    found = True
                    break
                assert c != "\n", f"{path}:{start_line}: newline in string literal"
                i += 1
            assert found, f"{path}:{start_line}: unclosed string literal"
            continue
        if ch == "'":
            start_line = line
            i += 1
            found = False
            while i < n:
                c = src[i]
                if c == "\\":
                    i += 2
                    continue
                if c == "'":
                    i += 1
                    found = True
                    break
                assert c != "\n", f"{path}:{start_line}: newline in char literal"
                i += 1
            assert found, f"{path}:{start_line}: unclosed char literal"
            continue
        if ch in pairs:
            stack.append((ch, line))
        elif ch in closing:
            assert stack, f"{path}:{line}: unexpected '{ch}'"
            top, top_line = stack.pop()
            assert top == closing[ch], f"{path}:{line}: '{ch}' closes '{top}' opened at line {top_line}"
        i += 1

    assert not stack, f"{path}: unclosed delimiters: {stack}"
    assert re.search(r"^\s*package\s+[a-zA-Z0-9_.]+;", src, re.M), f"{path}: missing package declaration"


def parse_java_string_expr(src: str, pos: int) -> tuple[str, int]:
    """Parse a Java string expression (concatenated literals or text block) starting at pos."""
    parts: list[str] = []
    n = len(src)
    while pos < n:
        while pos < n:
            if src[pos].isspace():
                pos += 1
            elif src.startswith("//", pos):
                nl = src.find("\n", pos + 2)
                pos = n if nl < 0 else nl + 1
            elif src.startswith("/*", pos):
                end = src.find("*/", pos + 2)
                assert end >= 0
                pos = end + 2
            else:
                break
        if src.startswith('"""', pos):
            pos += 3
            if pos < n and src[pos] == "\r":
                pos += 1
            if pos < n and src[pos] == "\n":
                pos += 1
            start = pos
            while pos < n:
                if src[pos] == "\\":
                    pos += 2
                    continue
                if src.startswith('"""', pos):
                    parts.append(unescape_java_string(src[start:pos]))
                    pos += 3
                    break
                pos += 1
        elif pos < n and src[pos] == '"':
            pos += 1
            start = pos
            while pos < n:
                if src[pos] == "\\":
                    pos += 2
                    continue
                if src[pos] == '"':
                    parts.append(unescape_java_string(src[start:pos]))
                    pos += 1
                    break
                pos += 1
        else:
            break
        save = pos
        while pos < n:
            if src[pos].isspace():
                pos += 1
            elif src.startswith("//", pos):
                nl = src.find("\n", pos + 2)
                pos = n if nl < 0 else nl + 1
            elif src.startswith("/*", pos):
                end = src.find("*/", pos + 2)
                assert end >= 0
                pos = end + 2
            else:
                break
        if pos < n and src[pos] == "+":
            pos += 1
            continue
        pos = save
        break
    return "".join(parts), pos


def extract_helper_js(natives_src: str) -> str:
    m = re.search(r"static\s+final\s+String\s+HELPER\s*=\s*", natives_src)
    assert m, "WebGpuNatives.HELPER not found"
    js, _ = parse_java_string_expr(natives_src, m.end())
    assert js.strip().startswith("var G = window.__mcgpu"), "Extracted HELPER looks wrong"
    return js


def extract_jsbodies(path: Path, const_map: dict[str, str] | None = None) -> list[tuple[int, list[str], str]]:
    """Extract all @JSBody annotations as (line, params, script_code)."""
    src = path.read_text(encoding="utf-8")
    const_map = const_map or {}
    results: list[tuple[int, list[str], str]] = []

    for m in re.finditer(r"@(?:org\.teavm\.jso\.)?JSBody\s*\(", src):
        line = src.count("\n", 0, m.start()) + 1
        pos = m.end()
        depth = 1
        in_str = None
        i = pos
        while i < len(src) and depth > 0:
            if in_str == '"""':
                if src[i] == "\\":
                    i += 2
                    continue
                if src.startswith('"""', i):
                    in_str = None
                    i += 3
                    continue
                i += 1
                continue
            if in_str == '"':
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == '"':
                    in_str = None
                i += 1
                continue
            if src.startswith("//", i):
                nl = src.find("\n", i + 2)
                i = len(src) if nl < 0 else nl + 1
                continue
            if src.startswith("/*", i):
                end = src.find("*/", i + 2)
                i = len(src) if end < 0 else end + 2
                continue
            if src.startswith('"""', i):
                in_str = '"""'
                i += 3
                continue
            if src[i] == '"':
                in_str = '"'
                i += 1
                continue
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
            i += 1
        ann = src[pos : i - 1]

        params: list[str] = []
        pm = re.search(r"params\s*=\s*(\{[^}]*\}|\"[^\"]*\")", ann)
        if pm:
            params = re.findall(r'"([^"]+)"', pm.group(1))

        sm = re.search(r"script\s*=\s*", ann)
        assert sm, f"{path}:{line}: @JSBody missing script="
        rem = ann[sm.end() :].lstrip()
        if rem.startswith('"'):
            script, _ = parse_java_string_expr(ann, sm.end())
        else:
            ident = re.match(r"([A-Za-z_][A-Za-z0-9_]*)", rem)
            assert ident and ident.group(1) in const_map, f"{path}:{line}: unknown @JSBody script ref {rem[:30]!r}"
            script = const_map[ident.group(1)]
        results.append((line, params, script))
    return results


def strip_js_strings_and_comments(js: str) -> str:
    """Strip JS single/double quoted strings and comments for ES5 linting."""
    out = []
    i = 0
    n = len(js)
    while i < n:
        ch = js[i]
        if ch == "/" and i + 1 < n and js[i + 1] == "/":
            nl = js.find("\n", i + 2)
            i = n if nl < 0 else nl
            continue
        if ch == "/" and i + 1 < n and js[i + 1] == "*":
            end = js.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        if ch in ("'", '"'):
            q = ch
            out.append(" ")
            i += 1
            while i < n:
                if js[i] == "\\":
                    i += 2
                    continue
                if js[i] == q:
                    i += 1
                    break
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def check_es5_and_html_safety(label: str, js: str) -> None:
    lower = js.lower()
    for bad in ("</script", "<!--", "]]>"):
        assert bad not in lower, f"{label}: forbidden sequence {bad!r} in JS"
    stripped = strip_js_strings_and_comments(js)
    assert "`" not in stripped, f"{label}: ES6 template literal backtick found"
    assert "=>" not in stripped, f"{label}: ES6 arrow function '=>' found"
    assert not re.search(r"\b(?:const|let)\b", stripped), f"{label}: ES6 const/let found in {stripped!r}"


def run_qjs(script: str) -> str:
    qjs = find_qjs()
    assert qjs.exists(), f"QuickJS binary not found at {qjs}"
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as tf:
        tf.write(script)
        tf_path = tf.name
    try:
        res = subprocess.run([str(qjs), tf_path], capture_output=True, text=True, timeout=15)
        if res.returncode != 0:
            raise AssertionError(f"qjs exited {res.returncode}\nSTDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}")
        return res.stdout
    finally:
        try:
            os.unlink(tf_path)
        except OSError:
            pass


def check_all_jsbodies_in_qjs(helper_js: str) -> int:
    total = 0
    compile_checks = []
    for path in WEBGPU_JAVA_FILES:
        cmap = {"HELPER": helper_js} if path.name == "WebGpuNatives.java" else {}
        bodies = extract_jsbodies(path, cmap)
        for line, params, js in bodies:
            label = f"{path.name}:{line}"
            check_es5_and_html_safety(label, js)
            compile_checks.append((label, ", ".join(params), js))
            total += 1

    lines = ["var parsed = 0;"]
    for label, param_str, js in compile_checks:
        lines.append(
            f"try {{ new Function({json.dumps(param_str)}, {json.dumps(js)}); parsed++; }} "
            f"catch (e) {{ throw new Error({json.dumps(label)} + ': ' + e); }}"
        )
    lines.append("print('JSBODY_PARSE_OK ' + parsed);")
    out = run_qjs("\n".join(lines))
    assert f"JSBODY_PARSE_OK {total}" in out, f"Unexpected qjs output: {out}"
    return total


def test_helper_runtime_in_qjs(helper_js: str) -> str:
    harness = r"""
var window = globalThis;
var location = { search: '' };
var console = {
  errors: [],
  error: function () { this.errors.push(Array.prototype.slice.call(arguments).join(' ')); },
  log: function () {}
};
var GPUBufferUsage = { MAP_READ: 1, MAP_WRITE: 2, COPY_SRC: 4, COPY_DST: 8, INDEX: 16, VERTEX: 32, UNIFORM: 64, STORAGE: 128 };
var GPUMapMode = { READ: 1, WRITE: 2 };

// Install WebGpuNatives.HELPER
""" + helper_js + r"""

function assert(cond, msg) {
  if (!cond) { throw new Error('ASSERT FAILED: ' + msg); }
}

var G = window.__mcgpu;
assert(G && typeof G.replay === 'function', 'G.replay missing');
assert(typeof G.clearColorAndDepth === 'function', 'G.clearColorAndDepth missing');
assert(typeof G.clearRegion === 'function', 'G.clearRegion missing');

// ---- Mock GPUDevice / Queue / Encoder / Pass ----
var createdBuffers = [];
var createdBindGroups = [];
var createdPipelines = [];
var createdShaders = [];
var writeCalls = [];
var submittedCmds = [];
var renderedPasses = [];

function makeMockPass(desc) {
  var ops = [];
  var p = {
    desc: desc,
    ops: ops,
    ended: false,
    setPipeline: function (pl) { ops.push(['pipeline', pl]); },
    setBindGroup: function (idx, bg, dyn, start, len) {
      var dynCopy = null;
      if (dyn !== undefined) {
        if (start !== undefined && len !== undefined) {
          dynCopy = Array.prototype.slice.call(dyn, start, start + len);
        } else {
          dynCopy = Array.prototype.slice.call(dyn);
        }
      }
      if (bg && bg.__bgl && bg.__bgl.__hasDynamicOffset) {
        assert(dynCopy && dynCopy.length === 1, 'dynamic offset required for hasDynamicOffset BGL');
        var off = dynCopy[0];
        assert((off & 255) === 0, 'dynamic offset must be 256-aligned, got ' + off);
        var res = bg.__entries[0].resource;
        var bindOff = res.offset || 0;
        var bindSize = res.size !== undefined ? res.size : (res.buffer.size - bindOff);
        assert(bindOff + off + bindSize <= res.buffer.size,
          'dynamic binding out of bounds: ' + (bindOff + off + bindSize) + ' > ' + res.buffer.size);
      }
      ops.push(['bindGroup', idx, bg, dynCopy]);
    },
    setVertexBuffer: function (slot, buf, off, sz) { ops.push(['vb', slot, buf, off, sz]); },
    setIndexBuffer: function (buf, fmt, off, sz) { ops.push(['ib', buf, fmt, off, sz]); },
    setScissorRect: function (x, y, w, h) { ops.push(['scissor', x, y, w, h]); },
    drawIndexed: function (c, i, f, bv, fi) { ops.push(['drawIndexed', c, i, f, bv, fi]); },
    draw: function (c, i, f, fi) { ops.push(['draw', c, i, f, fi]); },
    end: function () { this.ended = true; }
  };
  renderedPasses.push(p);
  return p;
}

var mockDev = {
  listeners: {},
  addEventListener: function (ev, fn) { this.listeners[ev] = fn; },
  lost: { then: function () {} },
  createCommandEncoder: function () {
    return {
      beginRenderPass: function (d) { return makeMockPass(d); },
      finish: function () { return { kind: 'cmdbuf' }; }
    };
  },
  createShaderModule: function (d) {
    var m = { kind: 'shader', label: d.label, code: d.code, getCompilationInfo: function () { return { then: function () {} }; } };
    createdShaders.push(m);
    return m;
  },
  createBindGroupLayout: function (d) {
    var hasDyn = d.entries.some(function (e) { return e.buffer && e.buffer.hasDynamicOffset; });
    return { kind: 'bgl', entries: d.entries, __hasDynamicOffset: hasDyn };
  },
  createPipelineLayout: function (d) { return { kind: 'pl', d: d }; },
  createRenderPipeline: function (d) {
    var pl = { kind: 'pipeline', label: d.label, d: d };
    createdPipelines.push(pl);
    return pl;
  },
  createBuffer: function (d) {
    var b = {
      kind: 'buffer',
      id: createdBuffers.length + 1,
      size: d.size,
      usage: d.usage,
      label: d.label,
      destroyed: false,
      destroy: function () { this.destroyed = true; }
    };
    createdBuffers.push(b);
    return b;
  },
  createBindGroup: function (d) {
    var bg = { kind: 'bg', id: createdBindGroups.length + 1, __bgl: d.layout, __entries: d.entries };
    createdBindGroups.push(bg);
    return bg;
  },
  queue: {
    writeBuffer: function (buf, off, data) {
      var snap = new Float32Array(data.length);
      snap.set(data);
      writeCalls.push({ buf: buf, off: off, data: snap });
    },
    submit: function (arr) { submittedCmds.push(arr); }
  }
};

function makeTex(w, h, mips, fmt, label) {
  return {
    width: w,
    height: h,
    mipLevelCount: mips,
    format: fmt,
    label: label,
    createView: function (opts) {
      return { tex: label, baseMip: opts.baseMipLevel, mips: opts.mipLevelCount };
    }
  };
}

G.init(mockDev, {}, 'bgra8unorm');

// ---- TEST 1: G.replay all 7 opcodes + memI32 caching + buffer growth refresh ----
var mem1 = new ArrayBuffer(1024);
globalThis.__teavmMemory = { buffer: mem1 };
var view1 = new Int32Array(mem1);

var objPipe = { name: 'pipeA' };
var objBg0 = { name: 'bg0' };
var objBgDyn = { name: 'bgDyn' };
var objVb = { name: 'vb0' };
var objIb = { name: 'ib0' };
var idPipe = G.put(objPipe);
var idBg0 = G.put(objBg0);
var idBgDyn = G.put(objBgDyn);
var idVb = G.put(objVb);
var idIb = G.put(objIb);

// Start at non-zero byte offset 64 (int index 16)
var startInt = 16;
var seq = [
  1, idPipe,
  2, idBg0, 0,
  2, idBgDyn, 2, 256, 512,
  3, 0, idVb, 128, 4096,
  4, idIb, 1, 0, 2048,
  4, idIb, 0, 16, 1024,
  5, 10, 20, 300, 400,
  6, 36, 2, 6, 4, 1,
  7, 12, 3, 2, 5
];
for (var si = 0; si < seq.length; si++) { view1[startInt + si] = seq[si]; }

var replayPass = makeMockPass({ label: 'replay-test' });
G.replay(replayPass, startInt * 4, seq.length);

assert(G.memBuf === mem1, 'G.memBuf should cache mem1');
var cachedI32 = G.memI32;
assert(cachedI32 instanceof Int32Array, 'G.memI32 should be Int32Array');
assert(G.draws === 2, 'G.draws should be 2 after drawIndexed + draw, got ' + G.draws);
assert(replayPass.ops.length === 9, 'expected 9 pass ops, got ' + replayPass.ops.length);
assert(replayPass.ops[0][0] === 'pipeline' && replayPass.ops[0][1] === objPipe, 'op 0 pipeline');
assert(replayPass.ops[1][0] === 'bindGroup' && replayPass.ops[1][2] === objBg0 && replayPass.ops[1][3] === null, 'op 1 bg0');
assert(replayPass.ops[2][0] === 'bindGroup' && replayPass.ops[2][2] === objBgDyn && replayPass.ops[2][3][0] === 256 && replayPass.ops[2][3][1] === 512, 'op 2 bgDyn');
assert(replayPass.ops[3][0] === 'vb' && replayPass.ops[3][1] === 0 && replayPass.ops[3][2] === objVb && replayPass.ops[3][3] === 128 && replayPass.ops[3][4] === 4096, 'op 3 vb');
assert(replayPass.ops[4][0] === 'ib' && replayPass.ops[4][1] === objIb && replayPass.ops[4][2] === 'uint32' && replayPass.ops[4][3] === 0 && replayPass.ops[4][4] === 2048, 'op 4 ib uint32');
assert(replayPass.ops[5][0] === 'ib' && replayPass.ops[5][1] === objIb && replayPass.ops[5][2] === 'uint16' && replayPass.ops[5][3] === 16 && replayPass.ops[5][4] === 1024, 'op 5 ib uint16');
assert(replayPass.ops[6][0] === 'scissor' && replayPass.ops[6][1] === 10 && replayPass.ops[6][2] === 20 && replayPass.ops[6][3] === 300 && replayPass.ops[6][4] === 400, 'op 6 scissor');
assert(replayPass.ops[7][0] === 'drawIndexed' && replayPass.ops[7][1] === 36 && replayPass.ops[7][4] === 4 && replayPass.ops[7][5] === 1, 'op 7 drawIndexed');
assert(replayPass.ops[8][0] === 'draw' && replayPass.ops[8][1] === 12 && replayPass.ops[8][2] === 3 && replayPass.ops[8][3] === 2 && replayPass.ops[8][4] === 5, 'op 8 draw');

// Second replay on SAME ArrayBuffer must reuse G.memI32 (same object identity)
var replayPass2 = makeMockPass({ label: 'replay-reuse' });
G.replay(replayPass2, startInt * 4, 2);
assert(G.memI32 === cachedI32, 'G.memI32 must be reused when memory buffer unchanged');
assert(replayPass2.ops.length === 1 && replayPass2.ops[0][1] === objPipe, 'reuse replay op');

// Simulated wasm memory.grow -> new ArrayBuffer must refresh G.memI32
var mem2 = new ArrayBuffer(2048);
globalThis.__teavmMemory = { buffer: mem2 };
var view2 = new Int32Array(mem2);
view2[4] = 7; view2[5] = 3; view2[6] = 1; view2[7] = 0; view2[8] = 0;
var replayPass3 = makeMockPass({ label: 'replay-grown' });
G.replay(replayPass3, 16, 5);
assert(G.memBuf === mem2 && G.memI32 !== cachedI32, 'G.memI32 must refresh after memory.grow');
assert(replayPass3.ops.length === 1 && replayPass3.ops[0][0] === 'draw' && replayPass3.ops[0][1] === 3, 'grown replay draw');

// Bad opcode throws
view2[10] = 99;
var threw = false;
try { G.replay(replayPass3, 40, 1); } catch (e) { threw = /bad command 99 at 0\/1/.test(String(e)); }
assert(threw, 'bad command should throw with relative index');

// ---- TEST 2: G.clearColorAndDepth (single pass per mip + mismatch fallback) ----
renderedPasses.length = 0;
var colorTex = makeTex(640, 480, 3, 'rgba8unorm', 'color');
var depthTex = makeTex(640, 480, 3, 'depth32float', 'depth');
G.clearColorAndDepth(colorTex, 0.25, 0.5, 0.75, 1.0, depthTex, 0.9);
assert(renderedPasses.length === 3, 'clearColorAndDepth 3 mips should use 3 combined passes, got ' + renderedPasses.length);
for (var m = 0; m < 3; m++) {
  var d = renderedPasses[m].desc;
  assert(renderedPasses[m].ended, 'pass must end');
  assert(d.colorAttachments.length === 1 && d.colorAttachments[0].view.baseMip === m && d.colorAttachments[0].loadOp === 'clear', 'color attachment mip ' + m);
  assert(Math.abs(d.colorAttachments[0].clearValue.r - 0.25) < 1e-6, 'clearColor r');
  assert(d.depthStencilAttachment && d.depthStencilAttachment.view.baseMip === m && d.depthStencilAttachment.depthLoadOp === 'clear', 'depth attachment mip ' + m);
  assert(Math.abs(d.depthStencilAttachment.depthClearValue - 0.9) < 1e-6, 'clearDepth');
}

// Mismatched mipLevelCount -> fallback to separate passes (2 color + 1 depth = 3)
renderedPasses.length = 0;
var color2 = makeTex(640, 480, 2, 'rgba8unorm', 'c2');
var depth1 = makeTex(640, 480, 1, 'depth32float', 'd1');
G.clearColorAndDepth(color2, 1, 0, 0, 1, depth1, 1.0);
assert(renderedPasses.length === 3, 'mismatch fallback should issue 2 color + 1 depth = 3 passes, got ' + renderedPasses.length);
assert(renderedPasses[0].desc.colorAttachments.length === 1 && !renderedPasses[0].desc.depthStencilAttachment, 'fallback color pass 0');
assert(renderedPasses[2].desc.colorAttachments.length === 0 && !!renderedPasses[2].desc.depthStencilAttachment, 'fallback depth pass');

// ---- TEST 3: G.clearRegion ring reuse (16 slots), empty rect, overflow (>16), and G.submit ----
renderedPasses.length = 0;
createdBuffers.length = 0;
createdBindGroups.length = 0;
writeCalls.length = 0;

var atlasC = makeTex(512, 512, 1, 'rgba8unorm', 'atlasC');
var atlasD = makeTex(512, 512, 1, 'depth32float', 'atlasD');

// Empty rect should early-return without consuming a slot or allocating anything
G.clearRegion(atlasC, atlasD, 0, 0, 0, 0, 1.0, 10, 10, 0, 16, 0);
assert(G.clearSlot === 0 && createdBuffers.length === 0 && renderedPasses.length === 0, 'empty clearRegion should be no-op');

// 16 calls in one frame -> 1 buffer (4096 bytes), 1 bind group (hasDynamicOffset), 0 trash
for (var slot = 0; slot < 16; slot++) {
  G.clearRegion(atlasC, atlasD, slot / 16, 0.5, 0.25, 1.0, 0.75, slot * 16, 0, 16, 16, 0);
}
assert(createdBuffers.length === 1, 'expected exactly 1 reusable clearBuf for 16 calls, got ' + createdBuffers.length);
assert(createdBuffers[0].size === 4096, 'clearBuf must be 4096 bytes');
assert(createdBindGroups.length === 1, 'expected exactly 1 reusable bindGroup for 16 calls, got ' + createdBindGroups.length);
assert(createdBindGroups[0].__bgl.__hasDynamicOffset === true, 'clearRegion BGL must set hasDynamicOffset: true');
assert(createdBindGroups[0].__entries[0].resource.size === 32, 'clearRegion binding size must be 32');
assert(G.trash.length === 0, 'no trash during ring slots 0..15');
assert(G.clearSlot === 16, 'clearSlot should be 16');
assert(renderedPasses.length === 16, '16 passes recorded');
for (var slot = 0; slot < 16; slot++) {
  assert(writeCalls[slot].buf === G.clearBuf && writeCalls[slot].off === slot * 256, 'write offset slot ' + slot);
  assert(Math.abs(writeCalls[slot].data[0] - slot / 16) < 1e-6 && Math.abs(writeCalls[slot].data[4] - 0.75) < 1e-6, 'write data slot ' + slot);
  var bgOp = renderedPasses[slot].ops[2];
  assert(bgOp[0] === 'bindGroup' && bgOp[2] === createdBindGroups[0] && bgOp[3][0] === slot * 256, 'pass bindGroup dynamic offset slot ' + slot);
}

// 17th and 18th calls -> overflow fallback allocates 32-byte one-shot buffers pushed to G.trash, dyn offset 0
G.clearRegion(atlasC, atlasD, 0.9, 0.8, 0.7, 0.6, 0.5, 0, 16, 16, 16, 0);
G.clearRegion(atlasC, atlasD, 0.1, 0.2, 0.3, 0.4, 0.5, 16, 16, 16, 16, 0);
assert(createdBuffers.length === 3, '2 overflow calls should create 2 one-shot buffers (total 3), got ' + createdBuffers.length);
assert(createdBuffers[1].size === 32 && createdBuffers[2].size === 32, 'overflow buffers should be 32 bytes');
assert(G.trash.length === 2, 'overflow buffers must be pushed to G.trash');
assert(renderedPasses[16].ops[2][3][0] === 0 && renderedPasses[17].ops[2][3][0] === 0, 'overflow dynamic offset must be 0');

// Submit cleans up trash, dropIds, resets clearSlot to 0
G.dropIds.push(idBg0);
G.submit();
assert(G.frames === 1, 'frames incremented');
assert(G.clearSlot === 0, 'G.clearSlot must reset to 0 on submit');
assert(G.trash.length === 0, 'G.trash emptied on submit');
assert(createdBuffers[1].destroyed && createdBuffers[2].destroyed, 'overflow buffers destroyed on submit');
assert(!createdBuffers[0].destroyed, 'reusable G.clearBuf must NOT be destroyed on submit');
assert(G.reg[idBg0] === null && G.freeIds.indexOf(idBg0) >= 0, 'dropIds recycled on submit');

// Next frame reuses slot 0 with zero new allocations
var bufsBefore = createdBuffers.length;
var bgsBefore = createdBindGroups.length;
G.clearRegion(atlasC, atlasD, 0.3, 0.4, 0.5, 1.0, 1.0, 0, 0, 32, 32, 0);
assert(createdBuffers.length === bufsBefore, 'frame 2 clearRegion allocated a buffer');
assert(createdBindGroups.length === bgsBefore, 'frame 2 clearRegion allocated a bindGroup');
assert(G.clearSlot === 1, 'frame 2 clearSlot should be 1');
assert(writeCalls[writeCalls.length - 1].buf === G.clearBuf && writeCalls[writeCalls.length - 1].off === 0, 'frame 2 reused offset 0');

print('HELPER_RUNTIME_PASS replay=7opcodes+grow clearColorAndDepth=combined+fallback clearRegion=16slot+overflow+submit');
"""
    out = run_qjs(harness)
    assert "HELPER_RUNTIME_PASS" in out, f"Unexpected qjs output: {out}"
    return out.strip()


def check_webgpu_invariants() -> None:
    # 1. WebGpuBackend.java
    backend_src = (WEBGPU_DIR / "WebGpuBackend.java").read_text(encoding="utf-8")
    assert re.search(r"public\s+static\s+final\s+boolean\s+DEFAULT_ON\s*=\s*true\s*;", backend_src), \
        "WebGpuBackend.DEFAULT_ON must be true"
    assert re.search(r"public\s+static\s+final\s+int\s+PROBE_TIMEOUT_MS\s*=\s*2500\s*;", backend_src), \
        "PROBE_TIMEOUT_MS must remain 2500"
    assert "minecraft-web.webgpuFailed" in backend_src, \
        "WebGpuBackend must preserve upstream 'minecraft-web.webgpuFailed' sessionStorage key"

    # 2. WebGpuCommandEncoder.java: attachment contract + hot-path checks
    enc_src = (WEBGPU_DIR / "WebGpuCommandEncoder.java").read_text(encoding="utf-8")
    body = enc_src.split("public RenderPassBackend createRenderPass(", 1)[1].split("public void submitRenderPass()", 1)[0]
    assert body.count("WebGpuNatives.passColor(view.attachmentHandle(),") == 2, \
        f"Expected 2 passColor(view.attachmentHandle(), ...) calls, got {body.count('WebGpuNatives.passColor(view.attachmentHandle(),')}"
    assert body.count("WebGpuNatives.passDepth(view.attachmentHandle(),") == 1, \
        f"Expected 1 passDepth(view.attachmentHandle(), ...) call, got {body.count('WebGpuNatives.passDepth(view.attachmentHandle(),')}"
    assert "new WebGpuRenderPass(" not in body, \
        "createRenderPass must reuse reusablePass instead of allocating new WebGpuRenderPass"
    assert "reusablePass.reset(" in body, "createRenderPass must call reusablePass.reset(...)"
    assert "colorFormatsByLen" in body, "createRenderPass must reuse colorFormatsByLen cache"
    assert "WebGpuNatives.clearColorAndDepth(" in enc_src, \
        "clearColorAndDepthTextures must call WebGpuNatives.clearColorAndDepth"

    submit_body = enc_src.split("public void submitRenderPass()", 1)[1].split("public void clearColorTexture(", 1)[0]
    assert "current.release();" in submit_body, "submitRenderPass must release reusablePass references"

    tm_body = enc_src.split("final class WebGpuTransientMemory ", 1)[1].split("final class WebGpuRenderPass ", 1)[0]
    assert "List<Integer>" not in tm_body, "WebGpuTransientMemory must not box Integer offsets/lastUsed"
    assert "private int[] offsets" in tm_body and "private int[] lastUsed" in tm_body, \
        "WebGpuTransientMemory must use primitive int[]"

    rp_body = enc_src.split("final class WebGpuRenderPass ", 1)[1]
    assert "new int[]{" not in rp_body, "WebGpuRenderPass must not allocate scissor int[]"
    assert "lastBoundGroupId" in rp_body and "lastBoundOffsets" in rp_body, \
        "WebGpuRenderPass must deduplicate CMD_BIND_GROUP"
    assert "hasLastScissor" in rp_body and "lastSx" in rp_body, \
        "WebGpuRenderPass must deduplicate CMD_SCISSOR"
    assert "isUniformAligned(" in rp_body and "alignMask" in rp_body, \
        "WebGpuRenderPass must use bitmask uniform alignment check"
    assert "uniformLen" in rp_body and "new Object[p.uniformNames.length]" not in rp_body, \
        "WebGpuRenderPass must reuse uniformValues array"


def main() -> int:
    for path in WEBGPU_JAVA_FILES:
        check_java_lexical(path)
    print(f"JAVA_LEXICAL_PASS {len(WEBGPU_JAVA_FILES)} WebGPU Java files balanced & valid")

    check_webgpu_invariants()
    print("WEBGPU_INVARIANTS_PASS default-on, attachment contract & hot-path checks")

    natives_src = (WEBGPU_DIR / "WebGpuNatives.java").read_text(encoding="utf-8")
    helper_js = extract_helper_js(natives_src)
    check_es5_and_html_safety("WebGpuNatives.HELPER", helper_js)

    n_bodies = check_all_jsbodies_in_qjs(helper_js)
    print(f"JSBODY_ES5_PASS {n_bodies} @JSBody snippets parsed in QuickJS with strict ES5 & HTML safety")

    rt_msg = test_helper_runtime_in_qjs(helper_js)
    print(rt_msg)

    print("WEBGPU_SELFCHECK_ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
