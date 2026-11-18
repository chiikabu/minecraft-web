#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)

import argparse
import base64
import gzip
import json
import lzma
import os
import struct
import subprocess
import tempfile
import zlib

from build_xz_decoder import build as build_decoder

MAGIC = b"MCXZ001\0"
CONTAINER_MAGIC = b"MCXC001\0"
GZIP_CONTAINER_MAGIC = b"MCGC001\0"
MAX_BUFFER_BYTES = 1024 * 1024 * 1024

def html85(data):
    return base64.b85encode(data).decode("ascii").replace("<", ".")

def archive(entries):
    ordered = list(entries.items())
    names = []
    header_bytes = 12
    for name, data in ordered:
        encoded = name.encode("utf-8")
        if len(encoded) > 65535:
            raise ValueError("archive entry name is too long: " + name)
        names.append(encoded)
        header_bytes += 12 + len(encoded)
        if len(data) > 0xFFFFFFFF:
            raise ValueError("archive entry exceeds 4 GiB: " + name)

    if header_bytes + sum(len(data) for _, data in ordered) > MAX_BUFFER_BYTES:
        raise ValueError("archive exceeds the 1 GiB decoder buffer limit")
    out = bytearray(MAGIC + struct.pack("<I", len(ordered)))
    offset = header_bytes
    for (name, data), encoded in zip(ordered, names):
        out += struct.pack("<HHII", len(encoded), 0, offset, len(data))
        out += encoded
        offset += len(data)
    for _, data in ordered:
        out += data
    return bytes(out)

def unpack_archive(data):
    if len(data) < 12 or data[:8] != MAGIC:
        raise ValueError("invalid MCXZ archive magic")
    count = struct.unpack_from("<I", data, 8)[0]
    if count > (len(data) - 12) // 12:
        raise ValueError("invalid MCXZ archive count")
    cursor = 12
    entries = {}
    spans = []
    for _ in range(count):
        if cursor + 12 > len(data):
            raise ValueError("truncated MCXZ archive record")
        name_len, reserved, offset, length = struct.unpack_from("<HHII", data, cursor)
        cursor += 12
        if reserved or offset + length > len(data) or cursor + name_len > len(data):
            raise ValueError("invalid MCXZ archive record")
        name = data[cursor:cursor + name_len].decode("utf-8")
        cursor += name_len
        if name in entries:
            raise ValueError("duplicate MCXZ archive name")
        entries[name] = data[offset:offset + length]
        spans.append((offset, length))
    for offset, length in spans:
        if offset != cursor:
            raise ValueError("overlapping or noncontiguous MCXZ payload")
        cursor += length
    if cursor != len(data):
        raise ValueError("trailing MCXZ archive bytes")
    return entries

def compress(data, preset=8, extreme=True, dictionary_mib=None):
    if not 0 <= preset <= 9:
        raise ValueError("XZ preset must be 0..9")
    flags = preset | (lzma.PRESET_EXTREME if extreme else 0)
    options = {"id": lzma.FILTER_LZMA2, "preset": flags, "pb": 0}
    if dictionary_mib is not None:
        if not 1 <= dictionary_mib <= 128:
            raise ValueError("XZ dictionary must be 1..128 MiB")
        options["dict_size"] = dictionary_mib * 1024 * 1024
    return lzma.compress(data, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC32,
                         filters=[options])

def container(entries, preset=8, extreme=True, dictionary_mib=None, compression="xz"):
    if compression not in ("xz", "gzip"):
        raise ValueError("compression must be xz or gzip")
    raw = archive(entries)
    magic = GZIP_CONTAINER_MAGIC if compression == "gzip" else CONTAINER_MAGIC
    packed = gzip.compress(raw, compresslevel=9, mtime=0) if compression == "gzip" else compress(raw, preset, extreme, dictionary_mib)
    if len(raw) + len(packed) > MAX_BUFFER_BYTES:
        raise ValueError("compressed and decoded data exceed the 1 GiB decoder buffer limit")
    return magic + struct.pack("<II", len(raw), len(packed)) + packed, len(raw), len(packed)

def unpack_container(data):
    if len(data) < 16 or data[:8] not in (CONTAINER_MAGIC, GZIP_CONTAINER_MAGIC):
        raise ValueError("invalid compressed container header")
    raw_size, packed_size = struct.unpack_from("<II", data, 8)
    if raw_size < 12 or packed_size != len(data) - 16 or raw_size + packed_size > MAX_BUFFER_BYTES:
        raise ValueError("invalid compressed container sizes")
    decoder = zlib.decompressobj(31) if data[:8] == GZIP_CONTAINER_MAGIC else lzma.LZMADecompressor(memlimit=MAX_BUFFER_BYTES)
    raw = decoder.decompress(data[16:], max_length=raw_size + 1)
    if len(raw) != raw_size or not decoder.eof or decoder.unused_data:
        raise ValueError("compressed stream length differs from its container")
    return unpack_archive(raw)

def selfcheck(zig="zig"):
    expected = {
        "hello.txt": "Hello, 世界".encode(),
        "empty.bin": b"",
        "binary.bin": bytes(range(256)) * 31,
        "__proto__": b"ordinary entry",
        "../literal-name": b"opaque archive name, not a filesystem path",
    }
    valid = [container(expected, preset)[0] for preset in (0, 8, 9)]
    valid.append(container(expected, dictionary_mib=128)[0])
    valid.append(container(expected, compression="gzip")[0])
    for dictionary_mib in (0, 129):
        try:
            compress(b"", dictionary_mib=dictionary_mib)
        except ValueError:
            pass
        else:
            raise AssertionError("invalid dictionary size accepted")
    invalid = []
    for good in (valid[0], valid[-1]):
        invalid.extend(good[:n] for n in (0, 7, 15, 16, len(good) - 1))
        invalid.append(good + b"extra")
        for offset in (0, 8, 12, 32):
            changed = bytearray(good); changed[offset] ^= 1; invalid.append(bytes(changed))
        for raw_size in (MAX_BUFFER_BYTES, len(archive(expected)) - 1, len(archive(expected)) + 1):
            changed = bytearray(good)
            struct.pack_into("<I", changed, 8, raw_size)
            invalid.append(bytes(changed))
    malformed = [b"bad", archive({}) + b"extra"]
    for offset, value in ((8, 255), (12, 255), (14, 1), (16, 0), (24, 255), (37, ord("a"))):
        changed = bytearray(archive({"a": b"x", "b": b"y"}))
        changed[offset] = value
        malformed.append(bytes(changed))
    for raw in malformed:
        for magic, packed in ((CONTAINER_MAGIC, compress(raw)),
                              (GZIP_CONTAINER_MAGIC, gzip.compress(raw, mtime=0))):
            invalid.append(magic + struct.pack("<II", len(raw), len(packed)) + packed)
    for tail in (b"extra", gzip.compress(b"", mtime=0), gzip.compress(b"x", mtime=0)):
        changed = bytearray(valid[-1] + tail)
        struct.pack_into("<I", changed, 12, len(changed) - 16)
        invalid.append(bytes(changed))
    changed = bytearray(valid[-1]); changed[-8] ^= 1; invalid.append(bytes(changed))
    for data in valid:
        assert unpack_container(data) == expected
    for data in invalid:
        try:
            unpack_container(data)
        except (ValueError, lzma.LZMAError, zlib.error):
            pass
        else:
            raise AssertionError("invalid container accepted")

    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    browser_loader = os.path.join(repo, "scripts", "minecraft_xz_loader.js")
    loader = build_decoder(zig)
    assert loader == build_decoder(zig), "decoder build is not reproducible"
    node_test = r"""
const fs=require('fs'),vm=require('vm');
vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
const vectors=JSON.parse(fs.readFileSync(process.argv[3],'utf8'));
const loader=fs.readFileSync(process.argv[2]);
if(WebAssembly.Module.imports(new WebAssembly.Module(loader)).length) throw Error('decoder has host imports');
for(const [encoded,hex] of vectors.base85) {
  for(const text of [encoded, '\n'+(encoded.match(/.{1,17}/g)||[]).join(' \r\n')+'\t']) {
    if(Buffer.from(__minecraftHTML85Decode(text)).toString('hex')!==hex) throw Error('base85 mismatch');
  }
}
for(const invalid of ['0', '000000', '/', '<0', 'é0', '~~~~~']) {
  let rejected=false;
  try { __minecraftHTML85Decode(invalid); } catch(e) { rejected=true; }
  if(!rejected) throw Error('invalid base85 accepted: '+invalid);
}
(async()=>{
 const gzip=Buffer.from(vectors.valid.at(-1),'base64');
 const native=globalThis.DecompressionStream;
 globalThis.DecompressionStream=undefined;
 let unavailable=false;
 try { await __minecraftXZDecode(gzip); } catch(e) { unavailable=/DecompressionStream unavailable/.test(e.message); }
 globalThis.DecompressionStream=native;
 if(!unavailable) throw Error('missing native decoder was not reported');
 for(const text of vectors.valid) {
  const bytes=Buffer.from(text,'base64');
  const a=await __minecraftXZDecode(bytes,bytes.subarray(0,8).toString()==='MCGC001\0'?undefined:loader);
  if(Object.getPrototypeOf(a)!==null) throw Error('archive has an inherited prototype');
  for(const [name,hex] of Object.entries(vectors.expected)) {
   if(Buffer.from(a[name]).toString('hex')!==hex) throw Error('archive bytes differ: '+name);
  }
 }
 for(const text of vectors.invalid) {
  let rejected=false;
  try { await __minecraftXZDecode(Buffer.from(text,'base64'),loader); } catch(e) { rejected=true; }
  if(!rejected) throw Error('invalid container accepted');
 }
 console.log('XZ_GZIP_SOURCE_PASS exact bytes; malformed streams/archives rejected; bounded output; prototype/opaque names safe; no host imports');
})().catch(e=>{console.error(e);process.exitCode=1});
"""
    with tempfile.NamedTemporaryFile(suffix=".wasm") as compiled:
        compiled.write(loader); compiled.flush()
        samples = [bytes(range(256)) * 31, b"", *(bytes((n + i * 73) & 255 for i in range(n)) for n in range(1, 257)),
                   *(bytes([255]) * n for n in range(1, 9))]
        with tempfile.NamedTemporaryFile("w", suffix=".json") as vectors:
            json.dump({"base85": [(html85(data), data.hex()) for data in samples],
                       "valid": [base64.b64encode(data).decode() for data in valid],
                       "invalid": [base64.b64encode(data).decode() for data in invalid],
                       "expected": {name: data.hex() for name, data in expected.items()}}, vectors)
            vectors.flush()
            for data in samples:
                assert "<" not in html85(data)
                assert base64.b85decode(html85(data).replace(".", "<")) == data
            subprocess.run(["node", "-e", node_test, browser_loader, compiled.name, vectors.name], check=True, timeout=30)
    return 0

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--selfcheck", action="store_true")
    parser.add_argument("--zig", default="zig")
    args = parser.parse_args()
    raise SystemExit(selfcheck(args.zig) if args.selfcheck else parser.error("use --selfcheck"))
