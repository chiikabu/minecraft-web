#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Build and run the 100% SHA-256 bit-exact OGVP-V4 lossless Ogg-Vorbis codec.

Compiles:
  * scripts/ogvp_v4_wasm.c  -> zero-import ~37 KB WASM32 lazy per-sound decoder
  * scripts/ogvp_v4_encode.c -> native CLI encoder + mandatory 100% SHA-256 verifier
"""

import argparse
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(SCRIPT_DIR)
BUILD_DIR = os.path.join(REPO, "build", "ogvp_v4")

CORE_H = os.path.join(SCRIPT_DIR, "ogvp_v4_core.h")
WASM_C = os.path.join(SCRIPT_DIR, "ogvp_v4_wasm.c")
ENCODE_C = os.path.join(SCRIPT_DIR, "ogvp_v4_encode.c")


def _src_hash(*paths):
    h = hashlib.sha256()
    for p in paths:
        with open(p, "rb") as f:
            h.update(f.read())
    return h.hexdigest()[:16]


def build_wasm_decoder(zig="zig"):
    os.makedirs(BUILD_DIR, exist_ok=True)
    tag = _src_hash(CORE_H, WASM_C)
    cached = os.path.join(BUILD_DIR, "ogvp_v4_decoder_%s.wasm" % tag)
    if os.path.isfile(cached):
        with open(cached, "rb") as f:
            data = f.read()
        if data[:4] == b"\x00asm":
            return data
    with tempfile.TemporaryDirectory(prefix="ogvp-wasm-") as tmp:
        out = os.path.join(tmp, "ogvp_v4_decoder.wasm")
        subprocess.run(
            [
                zig, "cc",
                "-target", "wasm32-wasi", "-mexec-model=reactor",
                "-O2", "-flto",
                "-I" + SCRIPT_DIR, WASM_C,
                "-Wl,--export=ogvp_prior_size",
                "-Wl,--export=ogvp_init_setup",
                "-Wl,--export=ogvp_decode_split",
                "-Wl,--export=malloc",
                "-Wl,--export=free",
                "-Wl,--max-memory=268435456",
                "-Wl,--strip-all",
                "-o", out,
            ],
            check=True,
            timeout=120,
        )
        with open(out, "rb") as f:
            data = f.read()
    with open(cached, "wb") as f:
        f.write(data)
    return data


def build_native_encoder(zig="zig"):
    os.makedirs(BUILD_DIR, exist_ok=True)
    tag = _src_hash(CORE_H, ENCODE_C)
    ext = ".exe" if os.name == "nt" else ""
    cached = os.path.join(BUILD_DIR, "ogvp_v4_encode_%s%s" % (tag, ext))
    if os.path.isfile(cached):
        return cached
    tmp_out = cached + ".tmp" + ext
    subprocess.run(
        [
            zig, "cc",
            "-O3", "-I" + SCRIPT_DIR, ENCODE_C,
            "-o", tmp_out,
        ],
        check=True,
        timeout=120,
    )
    os.replace(tmp_out, cached)
    return cached


def encode_ogg_pack(sha_to_path, zig="zig"):
    """Encode {sha1: ogg_file_path} into (meta_bytes, audio_bytes, wasm_bytes).

    Every single file is 100.000% SHA-256 verified by the native encoder.
    """
    if not sha_to_path:
        return b"", b"", b""
    wasm_bytes = build_wasm_decoder(zig)
    encoder_exe = build_native_encoder(zig)
    with tempfile.TemporaryDirectory(prefix="ogvp-enc-") as tmp:
        manifest_path = os.path.join(tmp, "manifest.tsv")
        meta_path = os.path.join(tmp, "ogvp_meta.bin")
        audio_path = os.path.join(tmp, "ogvp_audio.bin")
        with open(manifest_path, "w", encoding="utf-8", newline="\n") as mf:
            for sha, fpath in sorted(sha_to_path.items()):
                mf.write("%s\t%s\n" % (sha, fpath))
        subprocess.run([encoder_exe, manifest_path, meta_path, audio_path], check=True, timeout=600)
        with open(meta_path, "rb") as f:
            meta_bytes = f.read()
        with open(audio_path, "rb") as f:
            audio_bytes = f.read()
    if meta_bytes[:8] != b"OGVP4M01":
        raise ValueError("Invalid OGVP4M01 metadata header")
    return meta_bytes, audio_bytes, wasm_bytes


def selfcheck(zig="zig"):
    """Compile encoder + WASM decoder, encode sample .ogg files, and verify 100% SHA-256 bit-exact in Node V8 WASM."""
    site_objs = os.path.join(REPO, "site", "objects")
    sounds_idx = os.path.join(REPO, "site", "sounds_index.json")
    if not os.path.isfile(sounds_idx):
        print("OGVP_SELFCHECK_SKIP: site/sounds_index.json not staged; checking compiler builds only")
        w = build_wasm_decoder(zig)
        e = build_native_encoder(zig)
        print("OGVP_BUILD_OK: wasm=%d bytes, encoder=%s" % (len(w), os.path.basename(e)))
        return 0

    import json
    sounds = json.load(open(sounds_idx, encoding="utf-8"))
    sample = {}
    for name, sha in sorted(sounds.items()):
        if "/music/" in name or "/records/" in name:
            continue
        p = os.path.join(site_objs, sha[:2], sha)
        if os.path.isfile(p):
            sample[sha] = p
            if len(sample) >= 24:
                break
    # Also include 1 music file (including ballad_of_the_cats if present, which has a continued Ogg page!)
    for name, sha in sorted(sounds.items()):
        if "ballad_of_the_cats" in name or "note/harp.ogg" in name:
            p = os.path.join(site_objs, sha[:2], sha)
            if os.path.isfile(p):
                sample[sha] = p

    meta_bytes, audio_bytes, wasm_bytes = encode_ogg_pack(sample, zig=zig)
    with tempfile.TemporaryDirectory(prefix="ogvp-check-") as tmp:
        mp = os.path.join(tmp, "meta.bin")
        ap = os.path.join(tmp, "audio.bin")
        wp = os.path.join(tmp, "dec.wasm")
        sp = os.path.join(tmp, "sample.json")
        open(mp, "wb").write(meta_bytes)
        open(ap, "wb").write(audio_bytes)
        open(wp, "wb").write(wasm_bytes)
        open(sp, "w", encoding="utf-8").write(json.dumps(sample))

        node_js = r"""
const fs = require('fs'), crypto = require('crypto');
const [mp, ap, wp, sp] = process.argv.slice(1);
const meta = fs.readFileSync(mp);
const audio = fs.readFileSync(ap);
const wasm = fs.readFileSync(wp);
const sample = JSON.parse(fs.readFileSync(sp, 'utf8'));

const mod = new WebAssembly.Module(wasm);
const imports = WebAssembly.Module.imports(mod);
if (imports.length !== 0) throw new Error('Expected 0 WASM imports, got ' + imports.length);
const inst = new WebAssembly.Instance(mod, {});
const ex = inst.exports;
if (typeof ex._initialize === 'function') ex._initialize();

if (meta.subarray(0, 8).toString('ascii') !== 'OGVP4M01') throw new Error('Bad magic');
const numSetups = meta.readUInt32LE(8);
const numEntries = meta.readUInt32LE(12);
const priorSize = meta.readUInt32LE(16);
if (priorSize !== ex.ogvp_prior_size()) throw new Error('Prior size mismatch');
let off = 20;
for (let sid = 0; sid < numSetups; sid++) {
  const il = meta.readUInt16LE(off), sl = meta.readUInt16LE(off + 2);
  off += 4;
  const id = meta.subarray(off, off + il); off += il;
  const su = meta.subarray(off, off + sl); off += sl;
  const pr = meta.subarray(off, off + priorSize); off += priorSize;
  const pId = ex.malloc(il) >>> 0, pSu = ex.malloc(sl) >>> 0, pPr = ex.malloc(priorSize) >>> 0;
  let mem = new Uint8Array(ex.memory.buffer);
  mem.set(id, pId); mem.set(su, pSu); mem.set(pr, pPr);
  const rc = ex.ogvp_init_setup(sid, pId, il, pSu, sl, pPr);
  ex.free(pId); ex.free(pSu); ex.free(pPr);
  if (rc !== 0) throw new Error('ogvp_init_setup failed: ' + rc);
}
let ok = 0;
for (let i = 0; i < numEntries; i++) {
  const sha = meta.subarray(off, off + 20).toString('hex'); off += 20;
  const origLen = meta.readUInt32LE(off); off += 4;
  const aOff = meta.readUInt32LE(off); off += 4;
  const aLen = meta.readUInt32LE(off); off += 4;
  const mLen = meta.readUInt32LE(off); off += 4;
  const recMeta = meta.subarray(off, off + mLen); off += mLen;
  const recAudio = audio.subarray(aOff, aOff + aLen);
  const pM = ex.malloc(mLen) >>> 0;
  const pA = ex.malloc(aLen) >>> 0;
  const pO = ex.malloc(origLen + 4096) >>> 0;
  let mem = new Uint8Array(ex.memory.buffer);
  mem.set(recMeta, pM); mem.set(recAudio, pA);
  const outLen = ex.ogvp_decode_split(pM, mLen, pA, aLen, pO, origLen + 4096);
  mem = new Uint8Array(ex.memory.buffer);
  const decoded = Buffer.from(mem.subarray(pO, pO + outLen));
  ex.free(pM); ex.free(pA); ex.free(pO);
  if (outLen !== origLen) throw new Error('Length mismatch for ' + sha + ': ' + outLen + ' != ' + origLen);
  const orig = fs.readFileSync(sample[sha]);
  const h1 = crypto.createHash('sha256').update(orig).digest('hex');
  const h2 = crypto.createHash('sha256').update(decoded).digest('hex');
  if (h1 !== h2) throw new Error('SHA-256 mismatch for ' + sha);
  ok++;
}
console.log('OGVP_V4_SELFCHECK_PASS: ' + ok + '/' + numEntries + ' files 100% SHA-256 bit-exact in V8 WASM (imports=0, wasm=' + wasm.length + ' B)');
"""
        subprocess.run(["node", "-e", node_js, mp, ap, wp, sp], check=True, timeout=60)
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selfcheck", action="store_true")
    parser.add_argument("--zig", default=os.environ.get("ZIG", "zig"))
    args = parser.parse_args()
    raise SystemExit(selfcheck(args.zig) if args.selfcheck else parser.error("use --selfcheck"))
