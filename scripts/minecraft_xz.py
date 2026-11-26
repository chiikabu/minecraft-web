#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)

import argparse
import base64
import gzip
import json
import lzma
import os
import re
import struct
import subprocess
import tempfile
import xml.parsers.expat
import zlib

from build_xz_decoder import build as build_decoder

MAGIC = b"MCXZ001\0"
CONTAINER_MAGIC = b"MCXC001\0"
GZIP_CONTAINER_MAGIC = b"MCGC001\0"
MAX_BUFFER_BYTES = 1024 * 1024 * 1024

def html85(data):
    return base64.b85encode(data).decode("ascii").replace("<", ".")

html85_encode = html85

# --- Base122-HTML (+14.29% UTF-8 overhead; zero C1 controls, zero 0x00/\n/\r/"/&/</\) ---
_B122_ILLEGAL = (0x00, 0x0A, 0x0D, 0x22, 0x26, 0x3C, 0x5C)
_B122_ILLEGAL_SET = frozenset(_B122_ILLEGAL)
_B122_RE = re.compile(rb'[\x00\x0a\x0d"&<\\].?', re.DOTALL)
_B122_REPL = {}
for _idx, _b in enumerate(_B122_ILLEGAL):
    _B122_REPL[bytes((_b,))] = chr(0x0100 + (7 << 7) + _b).encode("utf-8")
    for _lo in range(128):
        _B122_REPL[bytes((_b, _lo))] = chr(0x0100 + (_idx << 7) + _lo).encode("utf-8")

def _b122_sub(match, _repl=_B122_REPL):
    return _repl[match.group(0)]

def base122_html_encode(data):
    n = len(data)
    if n == 0:
        return ""
    m = (n * 8 + 6) // 7
    vals = bytearray(m)
    full = (n // 7) * 7
    j = 0
    mv = memoryview(data)
    for b0, b1, b2, b3, b4, b5, b6 in struct.iter_unpack("7B", mv[:full]):
        vals[j] = b0 >> 1
        vals[j + 1] = ((b0 & 1) << 6) | (b1 >> 2)
        vals[j + 2] = ((b1 & 3) << 5) | (b2 >> 3)
        vals[j + 3] = ((b2 & 7) << 4) | (b3 >> 4)
        vals[j + 4] = ((b3 & 15) << 3) | (b4 >> 5)
        vals[j + 5] = ((b4 & 31) << 2) | (b5 >> 6)
        vals[j + 6] = ((b5 & 63) << 1) | (b6 >> 7)
        vals[j + 7] = b6 & 127
        j += 8
    rem = n - full
    if rem:
        acc = 0
        for b in mv[full:]:
            acc = (acc << 8) | b
        acc <<= 7 - rem
        for k in range(rem, -1, -1):
            vals[j] = (acc >> (k * 7)) & 127
            j += 1
    return _B122_RE.sub(_b122_sub, bytes(vals)).decode("utf-8")

def base122_html_decode(text):
    if not text:
        return b""
    out = bytearray()
    bits = 0
    nbits = 0
    n = len(text)
    illegal = _B122_ILLEGAL
    illegal_set = _B122_ILLEGAL_SET
    for idx, ch in enumerate(text):
        c = ord(ch)
        if c < 0x80:
            if c in illegal_set:
                raise ValueError("invalid base122 payload character")
            bits = (bits << 7) | c
            nbits += 7
            if nbits >= 8:
                nbits -= 8
                out.append((bits >> nbits) & 255)
                bits &= (1 << nbits) - 1
        elif 0x0100 <= c <= 0x04FF:
            v = c - 0x0100
            tag = v >> 7
            lo = v & 127
            if tag < 7:
                bits = (bits << 14) | (illegal[tag] << 7) | lo
                nbits += 14
                nbits -= 8
                out.append((bits >> nbits) & 255)
                if nbits >= 8:
                    nbits -= 8
                    out.append((bits >> nbits) & 255)
                bits &= (1 << nbits) - 1
            else:
                if idx != n - 1:
                    raise ValueError("invalid base122 shortened marker position")
                bits = (bits << 7) | lo
                nbits += 7
                if nbits >= 8:
                    nbits -= 8
                    out.append((bits >> nbits) & 255)
                    bits &= (1 << nbits) - 1
        else:
            raise ValueError("invalid base122 payload character")
    if nbits == 7:
        raise ValueError("invalid base122 payload length")
    if nbits > 0 and bits != 0:
        raise ValueError("invalid base122 padding bits")
    return bytes(out)

# --- XML-UTF8-hybrid (+17.77% UTF-8 overhead; XML 1.0 safe, zero < & > C0/C1/DEL/surrogates) ---
_XUTF_ASCII = tuple([0x09] + [c for c in range(0x20, 0x7F) if c not in (0x26, 0x3C, 0x3E)])
assert len(_XUTF_ASCII) == 93
_XUTF_DECODE_ASCII = [-1] * 128
for _i, _c in enumerate(_XUTF_ASCII):
    _XUTF_DECODE_ASCII[_c] = _i
_XUTF_2B = tuple(chr(0x00A0 + i).encode("utf-8") for i in range(29 * 64))

def xml_utf8_encode(data):
    n = len(data)
    if n == 0:
        return ""
    out = bytearray()
    append = out.append
    extend = out.extend
    ascii_tbl = _XUTF_ASCII
    two_tbl = _XUTF_2B
    acc = 0
    nbits = 0
    full = (n // 4) * 4
    mv = memoryview(data)
    for (word,) in struct.iter_unpack(">I", mv[:full]):
        acc = (acc << 32) | word
        nbits += 32
        while nbits >= 20:
            v = (acc >> (nbits - 7)) & 127
            if v < 93:
                append(ascii_tbl[v])
                nbits -= 7
            elif v < 122:
                nbits -= 13
                extend(two_tbl[((v - 93) << 6) | ((acc >> nbits) & 63)])
            else:
                nbits -= 20
                cp = 0x0800 + (((v - 122) << 13) | ((acc >> nbits) & 8191))
                extend((0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63)))
        acc &= (1 << nbits) - 1
    for b in mv[full:]:
        acc = (acc << 8) | b
        nbits += 8
        while nbits >= 20:
            v = (acc >> (nbits - 7)) & 127
            if v < 93:
                append(ascii_tbl[v])
                nbits -= 7
            elif v < 122:
                nbits -= 13
                extend(two_tbl[((v - 93) << 6) | ((acc >> nbits) & 63)])
            else:
                nbits -= 20
                cp = 0x0800 + (((v - 122) << 13) | ((acc >> nbits) & 8191))
                extend((0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63)))
        acc &= (1 << nbits) - 1
    while nbits >= 7:
        v = (acc >> (nbits - 7)) & 127
        if v < 93:
            append(ascii_tbl[v])
            nbits -= 7
            acc &= (1 << nbits) - 1
        elif v < 122 and nbits >= 13:
            nbits -= 13
            extend(two_tbl[((v - 93) << 6) | ((acc >> nbits) & 63)])
            acc &= (1 << nbits) - 1
        else:
            break
    while nbits >= 8:
        nbits -= 8
        cp = 0xC800 + ((acc >> nbits) & 255)
        extend((0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63)))
        acc &= (1 << nbits) - 1
    if nbits > 0:
        cp = 0xC900 + ((nbits - 1) << 7) + acc
        extend((0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63)))
    return out.decode("utf-8")

def xml_utf8_decode(text):
    if not text:
        return b""
    out = bytearray()
    bits = 0
    nbits = 0
    tbl = _XUTF_DECODE_ASCII
    n = len(text)
    for idx, ch in enumerate(text):
        c = ord(ch)
        if c < 128:
            v = tbl[c]
            if v < 0:
                raise ValueError("invalid xml-utf8 payload character")
            bits = (bits << 7) | v
            nbits += 7
            if nbits >= 8:
                nbits -= 8
                out.append((bits >> nbits) & 255)
                bits &= (1 << nbits) - 1
        elif 0x00A0 <= c <= 0x07DF:
            d2 = c - 0x00A0
            v2 = ((93 + (d2 >> 6)) << 6) | (d2 & 63)
            bits = (bits << 13) | v2
            nbits += 13
            nbits -= 8
            out.append((bits >> nbits) & 255)
            if nbits >= 8:
                nbits -= 8
                out.append((bits >> nbits) & 255)
            bits &= (1 << nbits) - 1
        elif 0x0800 <= c <= 0xC7FF:
            d3 = c - 0x0800
            v3 = ((122 + (d3 >> 13)) << 13) | (d3 & 8191)
            bits = (bits << 20) | v3
            nbits += 20
            nbits -= 8
            out.append((bits >> nbits) & 255)
            nbits -= 8
            out.append((bits >> nbits) & 255)
            if nbits >= 8:
                nbits -= 8
                out.append((bits >> nbits) & 255)
            bits &= (1 << nbits) - 1
        elif 0xC800 <= c <= 0xC8FF:
            bits = (bits << 8) | (c - 0xC800)
            nbits += 8
            nbits -= 8
            out.append((bits >> nbits) & 255)
            bits &= (1 << nbits) - 1
        elif 0xC900 <= c <= 0xCC7F:
            dt = c - 0xC900
            k = (dt >> 7) + 1
            vt = dt & 127
            if vt >= (1 << k) or idx != n - 1:
                raise ValueError("invalid xml-utf8 tail character")
            bits = (bits << k) | vt
            nbits += k
            if nbits >= 8:
                nbits -= 8
                out.append((bits >> nbits) & 255)
                bits &= (1 << nbits) - 1
        else:
            raise ValueError("invalid xml-utf8 payload character")
    if nbits != 0:
        raise ValueError("invalid xml-utf8 payload length")
    return bytes(out)

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
for(const [encoded,hex] of vectors.b122) {
  if(Buffer.from(__minecraftBase122Decode(encoded)).toString('hex')!==hex) throw Error('base122 mismatch');
  if(Buffer.from(__minecraftPayloadDecode({textContent:encoded,getAttribute:()=>'b122'})).toString('hex')!==hex) throw Error('payload b122 mismatch');
}
for(const invalid of ['\x00', '\n', '<', '"', '&', '\\', '\x80', '\u0500', 'A', '\u0480A']) {
  let rejected=false;
  try { __minecraftBase122Decode(invalid); } catch(e) { rejected=true; }
  if(!rejected) throw Error('invalid base122 accepted: '+JSON.stringify(invalid));
}
for(const [encoded,hex] of vectors.xutf) {
  if(Buffer.from(__minecraftXmlUtf8Decode(encoded)).toString('hex')!==hex) throw Error('xutf mismatch');
  if(Buffer.from(__minecraftPayloadDecode({textContent:encoded,getAttribute:()=>'xutf'})).toString('hex')!==hex) throw Error('payload xutf mismatch');
}
for(const invalid of ['\x00', '\x08', '\n', '\r', '<', '>', '&', '\x7f', '\x80', '\u009f', '\uD800', 'A', '\uC900A']) {
  let rejected=false;
  try { __minecraftXmlUtf8Decode(invalid); } catch(e) { rejected=true; }
  if(!rejected) throw Error('invalid xutf accepted: '+JSON.stringify(invalid));
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
    with tempfile.NamedTemporaryFile(suffix=".wasm", delete=False) as compiled:
        compiled.write(loader)
        compiled_path = compiled.name
    vectors_path = None
    try:
        samples = [bytes(range(256)) * 31, b"", *(bytes((n + i * 73) & 255 for i in range(n)) for n in range(1, 258)),
                   *(bytes([255]) * n for n in range(1, 9)), *(bytes([0]) * n for n in range(1, 16))]
        b122_vecs = []
        xutf_vecs = []
        for data in samples:
            h = html85(data)
            assert "<" not in h
            assert base64.b85decode(h.replace(".", "<")) == data
            b = base122_html_encode(data)
            assert not any(c in b for c in '\x00\n\r"&<\\')
            assert not any(0x80 <= ord(c) <= 0x9F for c in b)
            assert base122_html_decode(b) == data
            b122_vecs.append((b, data.hex()))
            x = xml_utf8_encode(data)
            assert not any(c in x for c in '<>&\n\r')
            assert not any(ord(c) <= 0x08 or (0x0B <= ord(c) <= 0x1F) or ord(c) == 0x7F or (0x80 <= ord(c) <= 0x9F) for c in x)
            assert xml_utf8_decode(x) == data
            p = xml.parsers.expat.ParserCreate()
            p.Parse(("<r>" + x + "</r>").encode("utf-8"), True)
            xutf_vecs.append((x, data.hex()))
        with tempfile.NamedTemporaryFile("w", suffix=".json", encoding="utf-8", delete=False) as vectors:
            json.dump({"base85": [(html85(data), data.hex()) for data in samples],
                       "b122": b122_vecs,
                       "xutf": xutf_vecs,
                       "valid": [base64.b64encode(data).decode() for data in valid],
                       "invalid": [base64.b64encode(data).decode() for data in invalid],
                       "expected": {name: data.hex() for name, data in expected.items()}}, vectors)
            vectors_path = vectors.name
        subprocess.run(["node", "-e", node_test, browser_loader, compiled_path, vectors_path], check=True, timeout=30)
    finally:
        for p in (compiled_path, vectors_path):
            if p and os.path.exists(p):
                os.unlink(p)
    return 0

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--selfcheck", action="store_true")
    parser.add_argument("--zig", default="zig")
    args = parser.parse_args()
    raise SystemExit(selfcheck(args.zig) if args.selfcheck else parser.error("use --selfcheck"))
