#!/usr/bin/env python3
"""Pure-Python single-TU WebAssembly SIMD128 relocatable object (.o) linker.

Links a single relocatable wasm32 object file produced by:
    clang-21 --target=wasm32 -msimd128 -O3 -ffreestanding -c foo.c -o foo.o
directly into a clean, browser-ready .wasm module without wasm-ld or zig.

Supports two memory modes:
  - "export" (default): internal linear memory exported as "memory", internal
    mutable i32 __stack_pointer initialized to stack_size, active DATA segments
    placed starting at data_base (16-byte aligned), and optional immutable i32
    __heap_base global export.
  - "import": keeps ONLY (import "env" "memory" (memory 1)) for zero-copy
    sharing with TeaVM's WebAssembly.Memory, configurable mutable
    __stack_pointer global (default 0) with synthesized simd_set_scratch(ptr)
    setter, and strict active-DATA collision / --no-data enforcement.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import math
from pathlib import Path
import struct
import sys
from typing import Any

# ---------------------------------------------------------------------------
# WebAssembly binary format constants (aligned with LLVM 21 BinaryFormat/Wasm.h)
# ---------------------------------------------------------------------------

WASM_MAGIC = b"\x00asm"
WASM_VERSION = b"\x01\x00\x00\x00"

SEC_CUSTOM = 0
SEC_TYPE = 1
SEC_IMPORT = 2
SEC_FUNCTION = 3
SEC_TABLE = 4
SEC_MEMORY = 5
SEC_GLOBAL = 6
SEC_EXPORT = 7
SEC_START = 8
SEC_ELEM = 9
SEC_CODE = 10
SEC_DATA = 11
SEC_DATACOUNT = 12
SEC_TAG = 13

SECTION_NAMES = {
    SEC_CUSTOM: "CUSTOM",
    SEC_TYPE: "TYPE",
    SEC_IMPORT: "IMPORT",
    SEC_FUNCTION: "FUNCTION",
    SEC_TABLE: "TABLE",
    SEC_MEMORY: "MEMORY",
    SEC_GLOBAL: "GLOBAL",
    SEC_EXPORT: "EXPORT",
    SEC_START: "START",
    SEC_ELEM: "ELEM",
    SEC_CODE: "CODE",
    SEC_DATA: "DATA",
    SEC_DATACOUNT: "DATACOUNT",
    SEC_TAG: "TAG",
}

# Canonical order per WebAssembly 2.0 specification (DATACOUNT precedes CODE/DATA,
# TAG precedes GLOBAL).
CANONICAL_SECTION_ORDER = {
    SEC_TYPE: 1,
    SEC_IMPORT: 2,
    SEC_FUNCTION: 3,
    SEC_TABLE: 4,
    SEC_MEMORY: 5,
    SEC_TAG: 6,
    SEC_GLOBAL: 7,
    SEC_EXPORT: 8,
    SEC_START: 9,
    SEC_ELEM: 10,
    SEC_DATACOUNT: 11,
    SEC_CODE: 12,
    SEC_DATA: 13,
}

EXT_FUNC = 0
EXT_TABLE = 1
EXT_MEMORY = 2
EXT_GLOBAL = 3
EXT_TAG = 4

VALTYPE_I32 = 0x7F
VALTYPE_I64 = 0x7E
VALTYPE_F32 = 0x7D
VALTYPE_F64 = 0x7C
VALTYPE_V128 = 0x7B
VALTYPE_FUNCREF = 0x70
VALTYPE_EXTERNREF = 0x6F
VALTYPE_EXNREF = 0x69
VALID_VALTYPES = {
    VALTYPE_I32,
    VALTYPE_I64,
    VALTYPE_F32,
    VALTYPE_F64,
    VALTYPE_V128,
    VALTYPE_FUNCREF,
    VALTYPE_EXTERNREF,
    VALTYPE_EXNREF,
    0x73,  # nullfuncref
    0x72,  # nullexternref
    0x74,  # nullexnref
    0x71,  # nullref
    0x6E,  # anyref
    0x6D,  # eqref
    0x6C,  # i31ref
    0x6B,  # structref
    0x6A,  # arrayref
}

LIMITS_FLAG_NONE = 0x00
LIMITS_FLAG_HAS_MAX = 0x01
LIMITS_FLAG_IS_SHARED = 0x02
LIMITS_FLAG_IS_64 = 0x04
LIMITS_FLAG_HAS_PAGE_SIZE = 0x08

DATA_SEG_IS_PASSIVE = 0x01
DATA_SEG_HAS_MEMINDEX = 0x02

# Linking custom section subsections (version 2)
WASM_SEGMENT_INFO = 5
WASM_INIT_FUNCS = 6
WASM_COMDAT_INFO = 7
WASM_SYMBOL_TABLE = 8

# Symbol kinds
SYM_FUNCTION = 0
SYM_DATA = 1
SYM_GLOBAL = 2
SYM_SECTION = 3
SYM_TAG = 4
SYM_TABLE = 5

# Symbol flags
SYM_BINDING_MASK = 0x03
SYM_BINDING_GLOBAL = 0x00
SYM_BINDING_WEAK = 0x01
SYM_BINDING_LOCAL = 0x02
SYM_VISIBILITY_MASK = 0x0C
SYM_VISIBILITY_DEFAULT = 0x00
SYM_VISIBILITY_HIDDEN = 0x04
SYM_UNDEFINED = 0x10
SYM_EXPORTED = 0x20
SYM_EXPLICIT_NAME = 0x40
SYM_NO_STRIP = 0x80
SYM_TLS = 0x100
SYM_ABSOLUTE = 0x200

# Relocation types (LLVM 21 BinaryFormat/WasmRelocs.def)
R_WASM_FUNCTION_INDEX_LEB = 0
R_WASM_TABLE_INDEX_SLEB = 1
R_WASM_TABLE_INDEX_I32 = 2
R_WASM_MEMORY_ADDR_LEB = 3
R_WASM_MEMORY_ADDR_SLEB = 4
R_WASM_MEMORY_ADDR_I32 = 5
R_WASM_TYPE_INDEX_LEB = 6
R_WASM_GLOBAL_INDEX_LEB = 7
R_WASM_FUNCTION_OFFSET_I32 = 8
R_WASM_SECTION_OFFSET_I32 = 9
R_WASM_TAG_INDEX_LEB = 10
R_WASM_MEMORY_ADDR_REL_SLEB = 11
R_WASM_TABLE_INDEX_REL_SLEB = 12
R_WASM_GLOBAL_INDEX_I32 = 13
R_WASM_MEMORY_ADDR_LEB64 = 14
R_WASM_MEMORY_ADDR_SLEB64 = 15
R_WASM_MEMORY_ADDR_I64 = 16
R_WASM_MEMORY_ADDR_REL_SLEB64 = 17
R_WASM_TABLE_INDEX_SLEB64 = 18
R_WASM_TABLE_INDEX_I64 = 19
R_WASM_TABLE_NUMBER_LEB = 20
R_WASM_MEMORY_ADDR_TLS_SLEB = 21
R_WASM_FUNCTION_OFFSET_I64 = 22
R_WASM_MEMORY_ADDR_LOCREL_I32 = 23
R_WASM_TABLE_INDEX_REL_SLEB64 = 24
R_WASM_MEMORY_ADDR_TLS_SLEB64 = 25
R_WASM_FUNCTION_INDEX_I32 = 26

RELOC_HAS_ADDEND = {
    R_WASM_MEMORY_ADDR_LEB,
    R_WASM_MEMORY_ADDR_SLEB,
    R_WASM_MEMORY_ADDR_I32,
    R_WASM_FUNCTION_OFFSET_I32,
    R_WASM_SECTION_OFFSET_I32,
    R_WASM_MEMORY_ADDR_REL_SLEB,
    R_WASM_MEMORY_ADDR_LEB64,
    R_WASM_MEMORY_ADDR_SLEB64,
    R_WASM_MEMORY_ADDR_I64,
    R_WASM_MEMORY_ADDR_REL_SLEB64,
    R_WASM_MEMORY_ADDR_TLS_SLEB,
    R_WASM_FUNCTION_OFFSET_I64,
    R_WASM_MEMORY_ADDR_LOCREL_I32,
    R_WASM_MEMORY_ADDR_TLS_SLEB64,
}

TABLE_INDEX_RELOCS = {
    R_WASM_TABLE_INDEX_SLEB,
    R_WASM_TABLE_INDEX_I32,
    R_WASM_TABLE_INDEX_REL_SLEB,
    R_WASM_TABLE_INDEX_SLEB64,
    R_WASM_TABLE_INDEX_I64,
    R_WASM_TABLE_INDEX_REL_SLEB64,
}

TABLE_USING_RELOCS = TABLE_INDEX_RELOCS | {R_WASM_TABLE_NUMBER_LEB}

WASM_PAGE_SIZE = 65536


class LinkError(RuntimeError):
    """Raised when a relocatable Wasm object cannot be linked."""


class WasmValidationError(ValueError):
    """Raised when structural Wasm validation fails."""


# ---------------------------------------------------------------------------
# LEB128 & low-level binary codecs
# ---------------------------------------------------------------------------


def read_uleb(buf: bytes | bytearray | memoryview, pos: int, max_bits: int = 32) -> tuple[int, int]:
    """Decode an unsigned LEB128 integer with strict bounds and bit-width checks."""
    max_bytes = (max_bits + 6) // 7
    result = 0
    shift = 0
    n = len(buf)
    for i in range(max_bytes):
        if pos >= n:
            raise WasmValidationError(f"Truncated unsigned LEB128 at offset {pos}")
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            if i == max_bytes - 1:
                rem_bits = max_bits - shift
                if (b & 0x7F) >= (1 << rem_bits):
                    raise WasmValidationError(
                        f"Unsigned LEB128 overflow in final byte 0x{b:02x} at offset {pos - 1}"
                    )
            return result, pos
        shift += 7
    raise WasmValidationError(f"Unsigned LEB128 exceeds {max_bytes} bytes near offset {pos}")


def read_sleb(buf: bytes | bytearray | memoryview, pos: int, max_bits: int = 32) -> tuple[int, int]:
    """Decode a signed LEB128 integer with strict bounds and sign-bit checks."""
    max_bytes = (max_bits + 6) // 7
    result = 0
    shift = 0
    n = len(buf)
    for i in range(max_bytes):
        if pos >= n:
            raise WasmValidationError(f"Truncated signed LEB128 at offset {pos}")
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if (b & 0x80) == 0:
            if i == max_bytes - 1:
                rem_bits = max_bits - (shift - 7)
                sign_bit = 1 << (rem_bits - 1)
                unused_mask = 0x7F ^ ((1 << rem_bits) - 1)
                if b & sign_bit:
                    if (b & unused_mask) != unused_mask:
                        raise WasmValidationError(
                            f"Signed LEB128 invalid negative sign-extension byte 0x{b:02x} at offset {pos - 1}"
                        )
                else:
                    if (b & unused_mask) != 0:
                        raise WasmValidationError(
                            f"Signed LEB128 invalid positive sign-extension byte 0x{b:02x} at offset {pos - 1}"
                        )
            if b & 0x40:
                result |= -(1 << shift)
            return result, pos
    raise WasmValidationError(f"Signed LEB128 exceeds {max_bytes} bytes near offset {pos}")


def encode_uleb(val: int) -> bytes:
    if val < 0:
        raise ValueError(f"encode_uleb requires non-negative value, got {val}")
    out = bytearray()
    while True:
        b = val & 0x7F
        val >>= 7
        if val:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def encode_sleb(val: int) -> bytes:
    out = bytearray()
    while True:
        b = val & 0x7F
        val >>= 7
        if (val == 0 and (b & 0x40) == 0) or (val == -1 and (b & 0x40) != 0):
            out.append(b)
            return bytes(out)
        out.append(b | 0x80)


def encode_uleb_padded(val: int, width: int = 5) -> bytes:
    """Encode an unsigned integer as a fixed-width padded LEB128 (5 bytes for u32, 10 for u64)."""
    bits = 32 if width == 5 else 64
    mask = (1 << bits) - 1
    u = val & mask
    out = bytearray(width)
    for i in range(width):
        b = u & 0x7F
        u >>= 7
        if i < width - 1:
            b |= 0x80
        out[i] = b
    return bytes(out)


def encode_sleb_padded(val: int, width: int = 5) -> bytes:
    """Encode a signed integer as a fixed-width padded LEB128 (5 bytes for i32, 10 for i64)."""
    bits = 32 if width == 5 else 64
    half = 1 << (bits - 1)
    mask = (1 << bits) - 1
    s = ((val + half) & mask) - half
    out = bytearray(width)
    for i in range(width):
        b = s & 0x7F
        s >>= 7
        if i < width - 1:
            b |= 0x80
        out[i] = b
    return bytes(out)


def read_str(buf: bytes | bytearray | memoryview, pos: int) -> tuple[str, int]:
    length, pos = read_uleb(buf, pos, 32)
    end = pos + length
    if end > len(buf):
        raise WasmValidationError(f"Truncated UTF-8 string of length {length} at offset {pos}")
    try:
        text = bytes(buf[pos:end]).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise WasmValidationError(f"Invalid UTF-8 string at offset {pos}: {exc}") from exc
    return text, end


def encode_str(text: str) -> bytes:
    raw = text.encode("utf-8")
    return encode_uleb(len(raw)) + raw


def align_up(value: int, alignment: int) -> int:
    if alignment <= 1:
        return value
    return (value + alignment - 1) & ~(alignment - 1)


def encode_section(sec_id: int, payload: bytes) -> bytes:
    return bytes([sec_id]) + encode_uleb(len(payload)) + payload


def encode_custom_section(name: str, payload: bytes) -> bytes:
    return encode_section(SEC_CUSTOM, encode_str(name) + payload)


# ---------------------------------------------------------------------------
# Structured section dataclasses
# ---------------------------------------------------------------------------


@dataclass
class RawSection:
    index: int
    sec_id: int
    payload: bytes
    custom_name: str | None = None
    custom_payload: bytes | None = None


@dataclass
class Limits:
    flags: int
    minimum: int
    maximum: int | None = None
    page_size: int | None = None

    def encode(self) -> bytes:
        out = bytearray([self.flags])
        out += encode_uleb(self.minimum)
        if self.flags & LIMITS_FLAG_HAS_MAX:
            assert self.maximum is not None
            out += encode_uleb(self.maximum)
        if self.flags & LIMITS_FLAG_HAS_PAGE_SIZE:
            assert self.page_size is not None
            out += encode_uleb(self.page_size)
        return bytes(out)


@dataclass
class ImportEntry:
    module: str
    field: str
    kind: int
    # Kind-specific payload:
    type_index: int | None = None
    table_elem_type: int | None = None
    limits: Limits | None = None
    global_valtype: int | None = None
    global_mutable: bool | None = None
    tag_attr: int | None = None
    tag_type_index: int | None = None

    def encode(self) -> bytes:
        out = bytearray()
        out += encode_str(self.module)
        out += encode_str(self.field)
        out.append(self.kind)
        if self.kind == EXT_FUNC:
            assert self.type_index is not None
            out += encode_uleb(self.type_index)
        elif self.kind == EXT_TABLE:
            assert self.table_elem_type is not None and self.limits is not None
            out.append(self.table_elem_type)
            out += self.limits.encode()
        elif self.kind == EXT_MEMORY:
            assert self.limits is not None
            out += self.limits.encode()
        elif self.kind == EXT_GLOBAL:
            assert self.global_valtype is not None and self.global_mutable is not None
            out.append(self.global_valtype)
            out.append(1 if self.global_mutable else 0)
        elif self.kind == EXT_TAG:
            assert self.tag_attr is not None and self.tag_type_index is not None
            out.append(self.tag_attr)
            out += encode_uleb(self.tag_type_index)
        else:
            raise LinkError(f"Unknown import kind {self.kind}")
        return bytes(out)


@dataclass
class GlobalEntry:
    valtype: int
    mutable: bool
    init_expr: bytes

    def encode(self) -> bytes:
        return bytes([self.valtype, 1 if self.mutable else 0]) + self.init_expr


@dataclass
class ExportEntry:
    name: str
    kind: int
    index: int

    def encode(self) -> bytes:
        return encode_str(self.name) + bytes([self.kind]) + encode_uleb(self.index)


@dataclass
class ElemSegment:
    flags: int
    table_index: int
    offset_expr: bytes | None
    offset_const: int | None
    elem_kind_or_type: int | None
    functions: list[int] = field(default_factory=list)
    raw_exprs: list[bytes] = field(default_factory=list)


@dataclass
class DataSegment:
    flags: int
    mem_index: int
    offset_expr: bytes | None
    offset_const: int | None
    payload_start: int  # offset of raw data bytes within original DATA section payload
    payload_end: int
    data: bytes
    name: str = ""
    p2align: int = 0
    linking_flags: int = 0
    placed_addr: int = 0

    @property
    def is_passive(self) -> bool:
        return bool(self.flags & DATA_SEG_IS_PASSIVE)


@dataclass
class SymbolInfo:
    index: int
    kind: int
    flags: int
    name: str
    element_index: int | None = None
    segment: int | None = None
    offset: int | None = None
    size: int | None = None
    section_index: int | None = None
    import_module: str | None = None
    import_field: str | None = None

    @property
    def is_undefined(self) -> bool:
        return bool(self.flags & SYM_UNDEFINED)

    @property
    def is_local(self) -> bool:
        return (self.flags & SYM_BINDING_MASK) == SYM_BINDING_LOCAL

    @property
    def is_exported(self) -> bool:
        return bool(self.flags & SYM_EXPORTED)


@dataclass
class Relocation:
    rtype: int
    offset: int
    index: int
    addend: int = 0


# ---------------------------------------------------------------------------
# Section parsers (shared between linker and structural validator)
# ---------------------------------------------------------------------------


def parse_limits(buf: bytes | bytearray | memoryview, pos: int) -> tuple[Limits, int]:
    if pos >= len(buf):
        raise WasmValidationError(f"Truncated limits at offset {pos}")
    flags = buf[pos]
    pos += 1
    if flags & ~(LIMITS_FLAG_HAS_MAX | LIMITS_FLAG_IS_SHARED | LIMITS_FLAG_IS_64 | LIMITS_FLAG_HAS_PAGE_SIZE):
        raise WasmValidationError(f"Invalid limits flags 0x{flags:02x} at offset {pos - 1}")
    bits = 64 if (flags & LIMITS_FLAG_IS_64) else 32
    minimum, pos = read_uleb(buf, pos, bits)
    maximum = None
    if flags & LIMITS_FLAG_HAS_MAX:
        maximum, pos = read_uleb(buf, pos, bits)
        if maximum < minimum:
            raise WasmValidationError(f"Limits maximum ({maximum}) < minimum ({minimum})")
    page_size = None
    if flags & LIMITS_FLAG_HAS_PAGE_SIZE:
        page_size, pos = read_uleb(buf, pos, 32)
    return Limits(flags=flags, minimum=minimum, maximum=maximum, page_size=page_size), pos


def parse_valtype(buf: bytes | bytearray | memoryview, pos: int) -> tuple[bytes, int]:
    if pos >= len(buf):
        raise WasmValidationError(f"Truncated valtype at offset {pos}")
    b = buf[pos]
    if b in VALID_VALTYPES:
        return bytes([b]), pos + 1
    if b in (0x63, 0x64):  # (ref null? ht)
        _, next_pos = read_sleb(buf, pos + 1, 33)
        return bytes(buf[pos:next_pos]), next_pos
    raise WasmValidationError(f"Invalid valtype 0x{b:02x} at offset {pos}")


def parse_init_expr(buf: bytes | bytearray | memoryview, pos: int) -> tuple[bytes, int | None, int]:
    """Parse a constant initializer expression ending in 0x0b (end).

    Returns (raw_expr_bytes, i32_const_value_or_None, next_pos).
    """
    start = pos
    n = len(buf)
    i32_val: int | None = None
    op_count = 0
    while True:
        if pos >= n:
            raise WasmValidationError(f"Unterminated init_expr starting at offset {start}")
        op = buf[pos]
        pos += 1
        if op == 0x0B:  # end
            if op_count != 1:
                i32_val = None
            return bytes(buf[start:pos]), i32_val, pos
        op_count += 1
        if op == 0x41:  # i32.const
            val, pos = read_sleb(buf, pos, 32)
            i32_val = val
        elif op == 0x42:  # i64.const
            _, pos = read_sleb(buf, pos, 64)
            i32_val = None
        elif op == 0x43:  # f32.const
            if pos + 4 > n:
                raise WasmValidationError("Truncated f32.const in init_expr")
            pos += 4
            i32_val = None
        elif op == 0x44:  # f64.const
            if pos + 8 > n:
                raise WasmValidationError("Truncated f64.const in init_expr")
            pos += 8
            i32_val = None
        elif op == 0x23:  # global.get
            _, pos = read_uleb(buf, pos, 32)
            i32_val = None
        elif op == 0xD0:  # ref.null
            _, pos = read_sleb(buf, pos, 33)
            i32_val = None
        elif op == 0xD2:  # ref.func
            _, pos = read_uleb(buf, pos, 32)
            i32_val = None
        elif op in (0x6A, 0x6B, 0x6C, 0x7C, 0x7D, 0x7E):  # extended-const i32/i64 add/sub/mul
            i32_val = None
        elif op == 0xFD:  # SIMD prefix (v128.const = 0x0c)
            sub, pos = read_uleb(buf, pos, 32)
            if sub != 0x0C:
                raise WasmValidationError(f"Unsupported SIMD op 0xfd {sub} in init_expr")
            if pos + 16 > n:
                raise WasmValidationError("Truncated v128.const in init_expr")
            pos += 16
            i32_val = None
        else:
            raise WasmValidationError(f"Unsupported opcode 0x{op:02x} in init_expr at offset {pos - 1}")


def parse_sections(wasm_bytes: bytes) -> list[RawSection]:
    if len(wasm_bytes) < 8 or wasm_bytes[:4] != WASM_MAGIC:
        raise WasmValidationError("Invalid Wasm magic header (expected \\x00asm)")
    if wasm_bytes[4:8] != WASM_VERSION:
        raise WasmValidationError(f"Unsupported Wasm version {wasm_bytes[4:8].hex()} (expected 01000000)")
    pos = 8
    n = len(wasm_bytes)
    sections: list[RawSection] = []
    idx = 0
    while pos < n:
        sec_id = wasm_bytes[pos]
        pos += 1
        if sec_id > SEC_TAG:
            raise WasmValidationError(f"Unknown Wasm section id {sec_id} at offset {pos - 1}")
        sec_size, pos = read_uleb(wasm_bytes, pos, 32)
        end = pos + sec_size
        if end > n:
            raise WasmValidationError(
                f"Section {sec_id} claims size {sec_size} bytes, exceeding EOF by {end - n} bytes"
            )
        payload = wasm_bytes[pos:end]
        pos = end
        custom_name = None
        custom_payload = None
        if sec_id == SEC_CUSTOM:
            custom_name, name_end = read_str(payload, 0)
            custom_payload = payload[name_end:]
        sections.append(
            RawSection(
                index=idx,
                sec_id=sec_id,
                payload=payload,
                custom_name=custom_name,
                custom_payload=custom_payload,
            )
        )
        idx += 1
    return sections


def parse_type_section(payload: bytes) -> list[bytes]:
    """Return list of raw type entries (normally 0x60 + params + results)."""
    count, pos = read_uleb(payload, 0, 32)
    entries: list[bytes] = []
    for _ in range(count):
        start = pos
        if pos >= len(payload):
            raise WasmValidationError("Truncated TYPE section entry")
        form = payload[pos]
        pos += 1
        if form != 0x60:
            raise WasmValidationError(f"Unsupported TYPE form 0x{form:02x} (expected 0x60 func)")
        n_params, pos = read_uleb(payload, pos, 32)
        for _ in range(n_params):
            _, pos = parse_valtype(payload, pos)
        n_results, pos = read_uleb(payload, pos, 32)
        for _ in range(n_results):
            _, pos = parse_valtype(payload, pos)
        entries.append(payload[start:pos])
    if pos != len(payload):
        raise WasmValidationError(f"Trailing {len(payload) - pos} bytes in TYPE section")
    return entries


def parse_import_section(payload: bytes) -> list[ImportEntry]:
    count, pos = read_uleb(payload, 0, 32)
    imports: list[ImportEntry] = []
    for _ in range(count):
        module, pos = read_str(payload, pos)
        field_name, pos = read_str(payload, pos)
        if pos >= len(payload):
            raise WasmValidationError("Truncated IMPORT descriptor")
        kind = payload[pos]
        pos += 1
        if kind == EXT_FUNC:
            tidx, pos = read_uleb(payload, pos, 32)
            imports.append(ImportEntry(module=module, field=field_name, kind=kind, type_index=tidx))
        elif kind == EXT_TABLE:
            if pos >= len(payload):
                raise WasmValidationError("Truncated TABLE import")
            elem_type = payload[pos]
            pos += 1
            limits, pos = parse_limits(payload, pos)
            imports.append(
                ImportEntry(module=module, field=field_name, kind=kind, table_elem_type=elem_type, limits=limits)
            )
        elif kind == EXT_MEMORY:
            limits, pos = parse_limits(payload, pos)
            imports.append(ImportEntry(module=module, field=field_name, kind=kind, limits=limits))
        elif kind == EXT_GLOBAL:
            if pos + 2 > len(payload):
                raise WasmValidationError("Truncated GLOBAL import")
            vt = payload[pos]
            mut = payload[pos + 1]
            if vt not in VALID_VALTYPES:
                raise WasmValidationError(f"Invalid global valtype 0x{vt:02x}")
            if mut not in (0, 1):
                raise WasmValidationError(f"Invalid global mutability {mut}")
            pos += 2
            imports.append(
                ImportEntry(
                    module=module,
                    field=field_name,
                    kind=kind,
                    global_valtype=vt,
                    global_mutable=bool(mut),
                )
            )
        elif kind == EXT_TAG:
            if pos >= len(payload):
                raise WasmValidationError("Truncated TAG import")
            attr = payload[pos]
            pos += 1
            tidx, pos = read_uleb(payload, pos, 32)
            imports.append(
                ImportEntry(module=module, field=field_name, kind=kind, tag_attr=attr, tag_type_index=tidx)
            )
        else:
            raise WasmValidationError(f"Unknown import kind {kind}")
    if pos != len(payload):
        raise WasmValidationError(f"Trailing {len(payload) - pos} bytes in IMPORT section")
    return imports


def parse_function_section(payload: bytes) -> list[int]:
    count, pos = read_uleb(payload, 0, 32)
    out: list[int] = []
    for _ in range(count):
        tidx, pos = read_uleb(payload, pos, 32)
        out.append(tidx)
    if pos != len(payload):
        raise WasmValidationError(f"Trailing {len(payload) - pos} bytes in FUNCTION section")
    return out


def parse_table_section(payload: bytes) -> list[tuple[int, Limits]]:
    count, pos = read_uleb(payload, 0, 32)
    tables: list[tuple[int, Limits]] = []
    for _ in range(count):
        if pos >= len(payload):
            raise WasmValidationError("Truncated TABLE section")
        elem_type = payload[pos]
        pos += 1
        limits, pos = parse_limits(payload, pos)
        tables.append((elem_type, limits))
    if pos != len(payload):
        raise WasmValidationError(f"Trailing {len(payload) - pos} bytes in TABLE section")
    return tables


def parse_memory_section(payload: bytes) -> list[Limits]:
    count, pos = read_uleb(payload, 0, 32)
    mems: list[Limits] = []
    for _ in range(count):
        lim, pos = parse_limits(payload, pos)
        mems.append(lim)
    if pos != len(payload):
        raise WasmValidationError(f"Trailing {len(payload) - pos} bytes in MEMORY section")
    return mems


def parse_global_section(payload: bytes) -> list[GlobalEntry]:
    count, pos = read_uleb(payload, 0, 32)
    globs: list[GlobalEntry] = []
    for _ in range(count):
        if pos + 2 > len(payload):
            raise WasmValidationError("Truncated GLOBAL entry")
        vt = payload[pos]
        mut = payload[pos + 1]
        if vt not in VALID_VALTYPES:
            raise WasmValidationError(f"Invalid global valtype 0x{vt:02x}")
        if mut not in (0, 1):
            raise WasmValidationError(f"Invalid global mutability {mut}")
        pos += 2
        expr, _, pos = parse_init_expr(payload, pos)
        globs.append(GlobalEntry(valtype=vt, mutable=bool(mut), init_expr=expr))
    if pos != len(payload):
        raise WasmValidationError(f"Trailing {len(payload) - pos} bytes in GLOBAL section")
    return globs


def parse_export_section(payload: bytes) -> list[ExportEntry]:
    count, pos = read_uleb(payload, 0, 32)
    exports: list[ExportEntry] = []
    for _ in range(count):
        name, pos = read_str(payload, pos)
        if pos >= len(payload):
            raise WasmValidationError("Truncated EXPORT entry")
        kind = payload[pos]
        pos += 1
        if kind > EXT_TAG:
            raise WasmValidationError(f"Unknown export kind {kind}")
        index, pos = read_uleb(payload, pos, 32)
        exports.append(ExportEntry(name=name, kind=kind, index=index))
    if pos != len(payload):
        raise WasmValidationError(f"Trailing {len(payload) - pos} bytes in EXPORT section")
    return exports


def parse_elem_section(payload: bytes) -> list[ElemSegment]:
    count, pos = read_uleb(payload, 0, 32)
    segs: list[ElemSegment] = []
    for _ in range(count):
        flags, pos = read_uleb(payload, pos, 32)
        if flags > 7:
            raise WasmValidationError(f"Unsupported ELEM segment flags {flags}")
        is_passive = bool(flags & 1)
        has_table_idx = (not is_passive) and bool(flags & 2)
        has_exprs = bool(flags & 4)
        table_idx = 0
        offset_expr = None
        offset_const = None
        if not is_passive:
            if has_table_idx:
                table_idx, pos = read_uleb(payload, pos, 32)
            offset_expr, offset_const, pos = parse_init_expr(payload, pos)
        elem_kind_or_type = None
        if is_passive or has_table_idx:
            if pos >= len(payload):
                raise WasmValidationError("Truncated ELEM kind/type")
            elem_kind_or_type = payload[pos]
            pos += 1
        vec_count, pos = read_uleb(payload, pos, 32)
        funcs: list[int] = []
        exprs: list[bytes] = []
        if has_exprs:
            for _ in range(vec_count):
                raw_expr, _, pos = parse_init_expr(payload, pos)
                exprs.append(raw_expr)
        else:
            for _ in range(vec_count):
                fidx, pos = read_uleb(payload, pos, 32)
                funcs.append(fidx)
        segs.append(
            ElemSegment(
                flags=flags,
                table_index=table_idx,
                offset_expr=offset_expr,
                offset_const=offset_const,
                elem_kind_or_type=elem_kind_or_type,
                functions=funcs,
                raw_exprs=exprs,
            )
        )
    if pos != len(payload):
        raise WasmValidationError(f"Trailing {len(payload) - pos} bytes in ELEM section")
    return segs


def encode_elem_section(segs: list[ElemSegment]) -> bytes:
    out = bytearray(encode_uleb(len(segs)))
    for seg in segs:
        out += encode_uleb(seg.flags)
        is_passive = bool(seg.flags & 1)
        has_table_idx = (not is_passive) and bool(seg.flags & 2)
        has_exprs = bool(seg.flags & 4)
        if not is_passive:
            if has_table_idx:
                out += encode_uleb(seg.table_index)
            assert seg.offset_expr is not None
            out += seg.offset_expr
        if is_passive or has_table_idx:
            assert seg.elem_kind_or_type is not None
            out.append(seg.elem_kind_or_type)
        if has_exprs:
            out += encode_uleb(len(seg.raw_exprs))
            for expr in seg.raw_exprs:
                out += expr
        else:
            out += encode_uleb(len(seg.functions))
            for fidx in seg.functions:
                out += encode_uleb(fidx)
    return bytes(out)


def parse_code_section_offsets(payload: bytes) -> tuple[int, int, list[tuple[int, int]]]:
    """Return (count, bodies_start_pos, [(body_entry_offset, body_end_offset), ...])."""
    count, pos = read_uleb(payload, 0, 32)
    bodies_start = pos
    spans: list[tuple[int, int]] = []
    for i in range(count):
        entry_start = pos
        body_size, body_start = read_uleb(payload, pos, 32)
        body_end = body_start + body_size
        if body_end > len(payload):
            raise WasmValidationError(f"CODE body #{i} overflows section end ({body_end} > {len(payload)})")
        # Validate locals declarations and trailing 0x0b end opcode.
        lpos = body_start
        local_decl_count, lpos = read_uleb(payload, lpos, 32)
        for _ in range(local_decl_count):
            _, lpos = read_uleb(payload, lpos, 32)
            _, lpos = parse_valtype(payload, lpos)
        if lpos > body_end:
            raise WasmValidationError(f"CODE body #{i} locals exceed body size")
        if body_size == 0 or payload[body_end - 1] != 0x0B:
            raise WasmValidationError(f"CODE body #{i} does not end with 0x0b (end)")
        spans.append((entry_start, body_end))
        pos = body_end
    if pos != len(payload):
        raise WasmValidationError(f"Trailing {len(payload) - pos} bytes in CODE section")
    return count, bodies_start, spans


def parse_data_section(payload: bytes) -> list[DataSegment]:
    count, pos = read_uleb(payload, 0, 32)
    segs: list[DataSegment] = []
    for _ in range(count):
        flags, pos = read_uleb(payload, pos, 32)
        if flags not in (0, 1, 2):
            raise WasmValidationError(f"Unsupported DATA segment flags {flags}")
        mem_idx = 0
        offset_expr = None
        offset_const = None
        if flags == 0:
            offset_expr, offset_const, pos = parse_init_expr(payload, pos)
        elif flags == 2:
            mem_idx, pos = read_uleb(payload, pos, 32)
            offset_expr, offset_const, pos = parse_init_expr(payload, pos)
        seg_size, pos = read_uleb(payload, pos, 32)
        end = pos + seg_size
        if end > len(payload):
            raise WasmValidationError(f"DATA segment overflows section end ({end} > {len(payload)})")
        segs.append(
            DataSegment(
                flags=flags,
                mem_index=mem_idx,
                offset_expr=offset_expr,
                offset_const=offset_const,
                payload_start=pos,
                payload_end=end,
                data=payload[pos:end],
            )
        )
        pos = end
    if pos != len(payload):
        raise WasmValidationError(f"Trailing {len(payload) - pos} bytes in DATA section")
    return segs


def parse_linking_section(
    payload: bytes,
    imports_by_kind: dict[int, list[ImportEntry]],
    sections: list[RawSection],
) -> tuple[list[SymbolInfo], list[tuple[str, int, int]], list[tuple[int, int]]]:
    """Parse the custom 'linking' section (version 2).

    Returns (symbols, segment_infos[(name, p2align, flags)], init_funcs[(priority, sym_idx)]).
    """
    version, pos = read_uleb(payload, 0, 32)
    if version != 2:
        raise LinkError(f"Unsupported linking section version {version} (expected 2)")
    symbols: list[SymbolInfo] = []
    seg_infos: list[tuple[str, int, int]] = []
    init_funcs: list[tuple[int, int]] = []
    while pos < len(payload):
        sub_type = payload[pos]
        pos += 1
        sub_size, pos = read_uleb(payload, pos, 32)
        sub = payload[pos : pos + sub_size]
        pos += sub_size
        if sub_type == WASM_SEGMENT_INFO:
            cnt, q = read_uleb(sub, 0, 32)
            for _ in range(cnt):
                name, q = read_str(sub, q)
                p2align, q = read_uleb(sub, q, 32)
                sflags, q = read_uleb(sub, q, 32)
                seg_infos.append((name, p2align, sflags))
        elif sub_type == WASM_INIT_FUNCS:
            cnt, q = read_uleb(sub, 0, 32)
            for _ in range(cnt):
                prio, q = read_uleb(sub, q, 32)
                sym_idx, q = read_uleb(sub, q, 32)
                init_funcs.append((prio, sym_idx))
        elif sub_type == WASM_SYMBOL_TABLE:
            cnt, q = read_uleb(sub, 0, 32)
            for sym_idx in range(cnt):
                kind = sub[q]
                q += 1
                flags, q = read_uleb(sub, q, 32)
                is_undef = bool(flags & SYM_UNDEFINED)
                is_explicit = bool(flags & SYM_EXPLICIT_NAME)
                if kind in (SYM_FUNCTION, SYM_GLOBAL, SYM_TAG, SYM_TABLE):
                    elem_idx, q = read_uleb(sub, q, 32)
                    imp_mod = None
                    imp_field = None
                    ext_kind = {
                        SYM_FUNCTION: EXT_FUNC,
                        SYM_GLOBAL: EXT_GLOBAL,
                        SYM_TAG: EXT_TAG,
                        SYM_TABLE: EXT_TABLE,
                    }[kind]
                    if is_undef:
                        imp_list = imports_by_kind.get(ext_kind, [])
                        if elem_idx < len(imp_list):
                            imp_mod = imp_list[elem_idx].module
                            imp_field = imp_list[elem_idx].field
                    if (not is_undef) or is_explicit:
                        name, q = read_str(sub, q)
                    else:
                        name = imp_field or f"<undef_{kind}_{elem_idx}>"
                    symbols.append(
                        SymbolInfo(
                            index=sym_idx,
                            kind=kind,
                            flags=flags,
                            name=name,
                            element_index=elem_idx,
                            import_module=imp_mod,
                            import_field=imp_field,
                        )
                    )
                elif kind == SYM_DATA:
                    name, q = read_str(sub, q)
                    seg = None
                    off = None
                    sz = None
                    if not is_undef:
                        seg, q = read_uleb(sub, q, 32)
                        off, q = read_uleb(sub, q, 32)
                        sz, q = read_uleb(sub, q, 32)
                    symbols.append(
                        SymbolInfo(
                            index=sym_idx,
                            kind=kind,
                            flags=flags,
                            name=name,
                            segment=seg,
                            offset=off,
                            size=sz,
                        )
                    )
                elif kind == SYM_SECTION:
                    sec_idx, q = read_uleb(sub, q, 32)
                    sec_name = ""
                    if sec_idx < len(sections):
                        sec_name = sections[sec_idx].custom_name or SECTION_NAMES.get(sections[sec_idx].sec_id, "")
                    symbols.append(
                        SymbolInfo(
                            index=sym_idx,
                            kind=kind,
                            flags=flags,
                            name=sec_name,
                            section_index=sec_idx,
                        )
                    )
                else:
                    raise LinkError(f"Unknown linking symbol kind {kind}")
        # Ignore WASM_COMDAT_INFO and any unknown linking subsections.
    return symbols, seg_infos, init_funcs


def parse_reloc_section(payload: bytes) -> tuple[int, list[Relocation]]:
    target_sec_idx, pos = read_uleb(payload, 0, 32)
    count, pos = read_uleb(payload, pos, 32)
    relocs: list[Relocation] = []
    for _ in range(count):
        rtype = payload[pos]
        pos += 1
        offset, pos = read_uleb(payload, pos, 32)
        index, pos = read_uleb(payload, pos, 32)
        addend = 0
        if rtype in RELOC_HAS_ADDEND:
            addend, pos = read_sleb(payload, pos, 64)
        relocs.append(Relocation(rtype=rtype, offset=offset, index=index, addend=addend))
    if pos != len(payload):
        raise LinkError(f"Trailing {len(payload) - pos} bytes in reloc section")
    return target_sec_idx, relocs


# ---------------------------------------------------------------------------
# Structural Wasm validator
# ---------------------------------------------------------------------------


def validate_wasm(wasm_bytes: bytes) -> dict[str, Any]:
    """Validate structural integrity of a linked .wasm binary.

    Verifies:
      - Magic header and version 1
      - Exact section sizes and EOF consumption
      - Canonical section order (no duplicates or out-of-order standard sections)
      - Zero 'linking' or 'reloc.*' custom sections remaining
      - Valid LEB128 encodings and section internals
      - Valid export / import / function / global / memory / table / elem / data indices
    Returns a summary dict on success; raises WasmValidationError on any failure.
    """
    sections = parse_sections(wasm_bytes)

    last_order = 0
    seen_std_ids: set[int] = set()
    section_map: dict[int, RawSection] = {}
    custom_names: list[str] = []

    for sec in sections:
        if sec.sec_id == SEC_CUSTOM:
            assert sec.custom_name is not None
            custom_names.append(sec.custom_name)
            if sec.custom_name == "linking" or sec.custom_name.startswith("reloc."):
                raise WasmValidationError(
                    f"Unlinked custom section '{sec.custom_name}' must not exist in linked .wasm"
                )
        else:
            order = CANONICAL_SECTION_ORDER.get(sec.sec_id)
            if order is None:
                raise WasmValidationError(f"Unknown standard section id {sec.sec_id}")
            if sec.sec_id in seen_std_ids:
                raise WasmValidationError(f"Duplicate standard section {SECTION_NAMES[sec.sec_id]}")
            if order <= last_order:
                raise WasmValidationError(
                    f"Section {SECTION_NAMES[sec.sec_id]} (id={sec.sec_id}) out of canonical order"
                )
            seen_std_ids.add(sec.sec_id)
            last_order = order
            section_map[sec.sec_id] = sec

    types = parse_type_section(section_map[SEC_TYPE].payload) if SEC_TYPE in section_map else []
    imports = parse_import_section(section_map[SEC_IMPORT].payload) if SEC_IMPORT in section_map else []
    func_types = parse_function_section(section_map[SEC_FUNCTION].payload) if SEC_FUNCTION in section_map else []
    def_tables = parse_table_section(section_map[SEC_TABLE].payload) if SEC_TABLE in section_map else []
    def_mems = parse_memory_section(section_map[SEC_MEMORY].payload) if SEC_MEMORY in section_map else []
    def_globals = parse_global_section(section_map[SEC_GLOBAL].payload) if SEC_GLOBAL in section_map else []
    exports = parse_export_section(section_map[SEC_EXPORT].payload) if SEC_EXPORT in section_map else []
    elems = parse_elem_section(section_map[SEC_ELEM].payload) if SEC_ELEM in section_map else []

    # Validate import type indices and count index spaces.
    n_imp_funcs = 0
    n_imp_tables = 0
    n_imp_mems = 0
    n_imp_globals = 0
    n_imp_tags = 0
    for imp in imports:
        if imp.kind == EXT_FUNC:
            assert imp.type_index is not None
            if imp.type_index >= len(types):
                raise WasmValidationError(f"Import func {imp.module}.{imp.field} type idx {imp.type_index} out of range")
            n_imp_funcs += 1
        elif imp.kind == EXT_TABLE:
            n_imp_tables += 1
        elif imp.kind == EXT_MEMORY:
            n_imp_mems += 1
        elif imp.kind == EXT_GLOBAL:
            n_imp_globals += 1
        elif imp.kind == EXT_TAG:
            assert imp.tag_type_index is not None
            if imp.tag_type_index >= len(types):
                raise WasmValidationError(f"Import tag type idx {imp.tag_type_index} out of range")
            n_imp_tags += 1

    # Validate TAG section if present.
    n_def_tags = 0
    if SEC_TAG in section_map:
        tp = section_map[SEC_TAG].payload
        n_def_tags, tpos = read_uleb(tp, 0, 32)
        for _ in range(n_def_tags):
            if tpos >= len(tp):
                raise WasmValidationError("Truncated TAG section")
            tpos += 1  # attr
            tidx, tpos = read_uleb(tp, tpos, 32)
            if tidx >= len(types):
                raise WasmValidationError(f"TAG type index {tidx} out of range")
        if tpos != len(tp):
            raise WasmValidationError("Trailing bytes in TAG section")

    for i, tidx in enumerate(func_types):
        if tidx >= len(types):
            raise WasmValidationError(f"FUNCTION #{i} type index {tidx} >= num_types ({len(types)})")

    total_funcs = n_imp_funcs + len(func_types)
    total_tables = n_imp_tables + len(def_tables)
    total_mems = n_imp_mems + len(def_mems)
    total_globals = n_imp_globals + len(def_globals)
    total_tags = n_imp_tags + n_def_tags

    if total_mems > 1:
        raise WasmValidationError(f"Module defines/imports {total_mems} memories (expected <= 1)")
    for lim in def_mems:
        if not (lim.flags & LIMITS_FLAG_IS_64):
            if lim.minimum > 65536 or (lim.maximum is not None and lim.maximum > 65536):
                raise WasmValidationError(f"Memory32 limits exceed 65536 pages: min={lim.minimum}, max={lim.maximum}")

    # Validate exports.
    seen_export_names: set[str] = set()
    bounds = {
        EXT_FUNC: total_funcs,
        EXT_TABLE: total_tables,
        EXT_MEMORY: total_mems,
        EXT_GLOBAL: total_globals,
        EXT_TAG: total_tags,
    }
    for exp in exports:
        if exp.name in seen_export_names:
            raise WasmValidationError(f"Duplicate export name '{exp.name}'")
        seen_export_names.add(exp.name)
        limit = bounds[exp.kind]
        if exp.index >= limit:
            raise WasmValidationError(
                f"Export '{exp.name}' (kind={exp.kind}) index {exp.index} out of bounds (limit={limit})"
            )

    # Validate START.
    if SEC_START in section_map:
        sp = section_map[SEC_START].payload
        sidx, spos = read_uleb(sp, 0, 32)
        if spos != len(sp):
            raise WasmValidationError("Trailing bytes in START section")
        if sidx >= total_funcs:
            raise WasmValidationError(f"START function index {sidx} >= total_funcs ({total_funcs})")

    # Validate ELEM.
    for seg in elems:
        if not (seg.flags & 1) and seg.table_index >= total_tables:
            raise WasmValidationError(f"ELEM segment table index {seg.table_index} >= total_tables ({total_tables})")
        for fidx in seg.functions:
            if fidx >= total_funcs:
                raise WasmValidationError(f"ELEM function index {fidx} >= total_funcs ({total_funcs})")

    # Validate DATACOUNT.
    datacount: int | None = None
    if SEC_DATACOUNT in section_map:
        dp = section_map[SEC_DATACOUNT].payload
        datacount, dpos = read_uleb(dp, 0, 32)
        if dpos != len(dp):
            raise WasmValidationError("Trailing bytes in DATACOUNT section")

    # Validate CODE.
    code_count = 0
    if SEC_CODE in section_map:
        code_count, _, _ = parse_code_section_offsets(section_map[SEC_CODE].payload)
    if code_count != len(func_types):
        raise WasmValidationError(
            f"CODE body count ({code_count}) != FUNCTION declaration count ({len(func_types)})"
        )

    # Validate DATA.
    data_segs = parse_data_section(section_map[SEC_DATA].payload) if SEC_DATA in section_map else []
    if datacount is not None and datacount != len(data_segs):
        raise WasmValidationError(f"DATACOUNT ({datacount}) != DATA segment count ({len(data_segs)})")
    for seg in data_segs:
        if not seg.is_passive and seg.mem_index >= total_mems:
            raise WasmValidationError(f"Active DATA segment references memory {seg.mem_index}, but total_mems={total_mems}")

    return {
        "size_bytes": len(wasm_bytes),
        "sections": [SECTION_NAMES[s.sec_id] if s.sec_id != 0 else f"CUSTOM({s.custom_name})" for s in sections],
        "num_types": len(types),
        "imports": [(i.module, i.field, i.kind) for i in imports],
        "num_functions": total_funcs,
        "num_defined_functions": len(func_types),
        "num_tables": total_tables,
        "num_memories": total_mems,
        "num_globals": total_globals,
        "num_data_segments": len(data_segs),
        "exports": [(e.name, e.kind, e.index) for e in exports],
        "custom_sections": custom_names,
    }


# ---------------------------------------------------------------------------
# Single-TU Linker
# ---------------------------------------------------------------------------


def _patch_reloc_bytes(buf: bytearray, offset: int, patch: bytes, label: str) -> None:
    end = offset + len(patch)
    if offset < 0 or end > len(buf):
        raise LinkError(f"Relocation {label} at offset 0x{offset:x} (+{len(patch)}B) out of bounds ({len(buf)}B)")
    buf[offset:end] = patch


def link_wasm_object(
    obj_bytes: bytes,
    *,
    memory_mode: str = "export",
    initial_pages: int | None = None,
    max_pages: int | None = None,
    stack_size: int | None = None,
    data_base: int | None = None,
    no_data: bool = False,
    export_heap_base: bool = True,
    export_stack_pointer: bool = False,
    synthesize_set_scratch: bool | None = None,
    keep_target_features: bool = False,
    allow_unresolved_imports: bool = False,
    import_memory_module: str = "env",
    import_memory_name: str = "memory",
) -> bytes:
    """Link a single relocatable clang-21 wasm32 .o binary into a standalone .wasm module."""
    if memory_mode not in ("export", "import"):
        raise ValueError(f"memory_mode must be 'export' or 'import', got {memory_mode!r}")

    # Resolve mode-dependent defaults.
    if memory_mode == "export":
        eff_initial_pages = 256 if initial_pages is None else initial_pages
        eff_max_pages = 4096 if max_pages is None else max_pages
        eff_stack_size = 65536 if stack_size is None else stack_size
        eff_data_base = 65536 if data_base is None else data_base
        eff_synth_scratch = False if synthesize_set_scratch is None else synthesize_set_scratch
    else:
        eff_initial_pages = 1 if initial_pages is None else initial_pages
        eff_max_pages = max_pages  # None => no max limit (matches any TeaVM WebAssembly.Memory)
        eff_stack_size = 0 if stack_size is None else stack_size
        eff_data_base = data_base  # None => reject active DATA unless explicitly placed
        eff_synth_scratch = True if synthesize_set_scratch is None else synthesize_set_scratch

    if eff_initial_pages < 0:
        raise ValueError(f"initial_pages must be >= 0, got {eff_initial_pages}")
    if eff_max_pages is not None and eff_max_pages < eff_initial_pages:
        raise ValueError(f"max_pages ({eff_max_pages}) < initial_pages ({eff_initial_pages})")
    if eff_stack_size < 0:
        raise ValueError(f"stack_size must be >= 0, got {eff_stack_size}")

    sections = parse_sections(obj_bytes)
    by_id: dict[int, RawSection] = {}
    linking_sec: RawSection | None = None
    target_features_sec: RawSection | None = None
    reloc_sections: dict[int, list[Relocation]] = {}

    for sec in sections:
        if sec.sec_id == SEC_CUSTOM:
            assert sec.custom_name is not None and sec.custom_payload is not None
            if sec.custom_name == "linking":
                linking_sec = sec
            elif sec.custom_name == "target_features":
                target_features_sec = sec
            elif sec.custom_name.startswith("reloc."):
                target_idx, relocs = parse_reloc_section(sec.custom_payload)
                reloc_sections[target_idx] = relocs
        else:
            by_id[sec.sec_id] = sec

    if linking_sec is None or linking_sec.custom_payload is None:
        raise LinkError("Input is not a relocatable Wasm object (missing 'linking' custom section)")

    # Parse all standard sections present in the object file.
    types = parse_type_section(by_id[SEC_TYPE].payload) if SEC_TYPE in by_id else []
    imports = parse_import_section(by_id[SEC_IMPORT].payload) if SEC_IMPORT in by_id else []
    func_type_indices = parse_function_section(by_id[SEC_FUNCTION].payload) if SEC_FUNCTION in by_id else []
    orig_tables = parse_table_section(by_id[SEC_TABLE].payload) if SEC_TABLE in by_id else []
    orig_mems = parse_memory_section(by_id[SEC_MEMORY].payload) if SEC_MEMORY in by_id else []
    orig_globals = parse_global_section(by_id[SEC_GLOBAL].payload) if SEC_GLOBAL in by_id else []
    orig_exports = parse_export_section(by_id[SEC_EXPORT].payload) if SEC_EXPORT in by_id else []
    orig_elems = parse_elem_section(by_id[SEC_ELEM].payload) if SEC_ELEM in by_id else []
    data_segments = parse_data_section(by_id[SEC_DATA].payload) if SEC_DATA in by_id else []

    orig_tags_raw: bytes | None = by_id[SEC_TAG].payload if SEC_TAG in by_id else None
    orig_start_idx: int | None = None
    if SEC_START in by_id:
        orig_start_idx, _ = read_uleb(by_id[SEC_START].payload, 0, 32)

    orig_code_payload = by_id[SEC_CODE].payload if SEC_CODE in by_id else b"\x00"
    orig_code_count, orig_code_bodies_start, orig_code_spans = parse_code_section_offsets(orig_code_payload)
    if orig_code_count != len(func_type_indices):
        raise LinkError(
            f"Object CODE count ({orig_code_count}) != FUNCTION count ({len(func_type_indices)})"
        )

    # Group imports by external kind so linking symbols can resolve undefined import names.
    imports_by_kind: dict[int, list[ImportEntry]] = {
        EXT_FUNC: [],
        EXT_TABLE: [],
        EXT_MEMORY: [],
        EXT_GLOBAL: [],
        EXT_TAG: [],
    }
    for imp in imports:
        imports_by_kind[imp.kind].append(imp)

    symbols, seg_infos, init_funcs = parse_linking_section(linking_sec.custom_payload, imports_by_kind, sections)
    if init_funcs:
        raise LinkError(
            f"Static constructors (WASM_INIT_FUNCS count={len(init_funcs)}) are not supported in single-TU freestanding kernels"
        )

    # Attach WASM_SEGMENT_INFO metadata to DATA segments.
    for i, (sname, p2align, sflags) in enumerate(seg_infos):
        if i < len(data_segments):
            data_segments[i].name = sname
            data_segments[i].p2align = p2align
            data_segments[i].linking_flags = sflags

    code_relocs = reloc_sections.get(by_id[SEC_CODE].index, []) if SEC_CODE in by_id else []
    data_relocs = reloc_sections.get(by_id[SEC_DATA].index, []) if SEC_DATA in by_id else []
    all_relocs = code_relocs + data_relocs

    # Determine whether the indirect function table is actually needed.
    needs_indirect_table = any(len(seg.functions) > 0 or len(seg.raw_exprs) > 0 for seg in orig_elems) or any(
        r.rtype in TABLE_USING_RELOCS for r in all_relocs
    )

    # Classify imports and build index remapping tables.
    out_imports: list[ImportEntry] = []
    unresolved_imports: list[str] = []

    func_index_map: dict[int, int] = {}
    table_index_map: dict[int, int] = {}
    global_index_map: dict[int, int] = {}
    tag_index_map: dict[int, int] = {}

    old_sp_global_idx: int | None = None
    old_indirect_table_idx: int | None = None
    indirect_table_min = 0
    saw_memory_import = False

    n_kept_imp_funcs = 0
    n_kept_imp_tables = 0
    n_kept_imp_globals = 0
    n_kept_imp_tags = 0

    kind_counters = {EXT_FUNC: 0, EXT_TABLE: 0, EXT_MEMORY: 0, EXT_GLOBAL: 0, EXT_TAG: 0}
    for imp in imports:
        old_idx = kind_counters[imp.kind]
        kind_counters[imp.kind] += 1

        if imp.kind == EXT_MEMORY:
            saw_memory_import = True
            if memory_mode == "import":
                flags = LIMITS_FLAG_HAS_MAX if eff_max_pages is not None else LIMITS_FLAG_NONE
                out_imports.append(
                    ImportEntry(
                        module=import_memory_module,
                        field=import_memory_name,
                        kind=EXT_MEMORY,
                        limits=Limits(flags=flags, minimum=eff_initial_pages, maximum=eff_max_pages),
                    )
                )
            # In export mode, memory import is dropped and replaced with an internal MEMORY section.
            continue

        if imp.kind == EXT_TABLE and imp.module == "env" and imp.field == "__indirect_function_table":
            old_indirect_table_idx = old_idx
            if imp.limits is not None:
                indirect_table_min = max(indirect_table_min, imp.limits.minimum)
            # Dropped from IMPORT either way: omitted if unused, internalized to TABLE if used.
            continue

        if imp.kind == EXT_GLOBAL and imp.module == "env" and imp.field == "__stack_pointer":
            old_sp_global_idx = old_idx
            # Internalized into GLOBAL section below.
            continue

        # Any other import is an unresolved external dependency.
        unresolved_imports.append(f"{imp.module}.{imp.field} (kind={imp.kind})")
        if imp.kind == EXT_FUNC:
            func_index_map[old_idx] = n_kept_imp_funcs
            n_kept_imp_funcs += 1
        elif imp.kind == EXT_TABLE:
            table_index_map[old_idx] = n_kept_imp_tables
            n_kept_imp_tables += 1
        elif imp.kind == EXT_GLOBAL:
            global_index_map[old_idx] = n_kept_imp_globals
            n_kept_imp_globals += 1
        elif imp.kind == EXT_TAG:
            tag_index_map[old_idx] = n_kept_imp_tags
            n_kept_imp_tags += 1
        out_imports.append(imp)

    if unresolved_imports and not allow_unresolved_imports:
        raise LinkError(
            "Unresolved external imports in single-TU object: "
            + ", ".join(unresolved_imports)
            + " (provide freestanding/static implementations, or pass allow_unresolved_imports=True)"
        )

    if memory_mode == "import" and not saw_memory_import and not orig_mems:
        # Ensure imported memory is always present in import mode.
        flags = LIMITS_FLAG_HAS_MAX if eff_max_pages is not None else LIMITS_FLAG_NONE
        out_imports.append(
            ImportEntry(
                module=import_memory_module,
                field=import_memory_name,
                kind=EXT_MEMORY,
                limits=Limits(flags=flags, minimum=eff_initial_pages, maximum=eff_max_pages),
            )
        )

    # Map defined functions, tables, globals, tags.
    orig_num_imp_funcs = kind_counters[EXT_FUNC]
    for i in range(len(func_type_indices)):
        func_index_map[orig_num_imp_funcs + i] = n_kept_imp_funcs + i

    orig_num_imp_tables = kind_counters[EXT_TABLE]
    out_tables: list[tuple[int, Limits]] = list(orig_tables)
    for i in range(len(orig_tables)):
        table_index_map[orig_num_imp_tables + i] = n_kept_imp_tables + i

    orig_num_imp_globals = kind_counters[EXT_GLOBAL]
    out_globals: list[GlobalEntry] = list(orig_globals)
    for i in range(len(orig_globals)):
        global_index_map[orig_num_imp_globals + i] = n_kept_imp_globals + i

    orig_num_imp_tags = kind_counters[EXT_TAG]
    # Note: TAG definitions rarely exist in C kernels; preserve indices if present.
    if orig_tags_raw is not None:
        n_def_tags, _ = read_uleb(orig_tags_raw, 0, 32)
        for i in range(n_def_tags):
            tag_index_map[orig_num_imp_tags + i] = n_kept_imp_tags + i

    # ------------------------------------------------------------------
    # Data segment placement & validation
    # ------------------------------------------------------------------
    active_data_segs = [s for s in data_segments if not s.is_passive]
    if active_data_segs:
        if no_data:
            desc = ", ".join(f"{s.name or f'seg#{i}'} ({len(s.data)}B)" for i, s in enumerate(active_data_segs))
            raise LinkError(f"Active DATA segments forbidden (--no-data): {desc}")
        if memory_mode == "import" and eff_data_base is None:
            desc = ", ".join(f"{s.name or f'seg#{i}'} ({len(s.data)}B)" for i, s in enumerate(active_data_segs))
            raise LinkError(
                f"Active DATA segments present in memory_mode='import' without explicit data_base: {desc}. "
                "Eliminate static data (for zero-copy TeaVM sharing) or pass --data-base=<addr> above TeaVM's heap."
            )

    placed_base = 0 if eff_data_base is None else align_up(eff_data_base, 16)
    if memory_mode == "import" and active_data_segs and placed_base == 0:
        raise LinkError("data_base=0 in memory_mode='import' would overwrite address 0 / TeaVM memory header")

    cursor = placed_base
    placed_ranges: list[tuple[int, int, str]] = []
    for i, seg in enumerate(data_segments):
        if seg.is_passive:
            seg.placed_addr = 0
            continue
        seg_align = 1 << seg.p2align if seg.p2align > 0 else 1
        cursor = align_up(cursor, seg_align)
        seg.placed_addr = cursor
        end_addr = cursor + len(seg.data)
        placed_ranges.append((cursor, end_addr, seg.name or f"seg#{i}"))
        cursor = end_addr

    # Double-check zero overlap among active segments.
    for a, b in zip(placed_ranges, placed_ranges[1:]):
        if a[1] > b[0]:
            raise LinkError(f"Overlapping active DATA segments: {a[2]} [{a[0]},{a[1]}) and {b[2]} [{b[0]},{b[1]})")

    data_end = cursor
    if memory_mode == "export":
        heap_base = align_up(max(data_end, eff_stack_size, placed_base), 16)
        min_pages_needed = max(1, math.ceil(heap_base / WASM_PAGE_SIZE)) if heap_base > 0 else 1
        if eff_initial_pages < min_pages_needed:
            eff_initial_pages = min_pages_needed
        if eff_max_pages is not None and eff_initial_pages > eff_max_pages:
            raise LinkError(
                f"Placed static data + stack requires {eff_initial_pages} pages (heap_base=0x{heap_base:x}), "
                f"exceeding max_pages={eff_max_pages}"
            )
    else:
        heap_base = align_up(data_end, 16)

    # ------------------------------------------------------------------
    # Internalize __stack_pointer and optional __heap_base globals
    # ------------------------------------------------------------------
    # Check whether any existing export or non-local defined function already provides simd_set_scratch.
    existing_export_names = {e.name for e in orig_exports}
    for sym in symbols:
        if sym.kind == SYM_FUNCTION and not sym.is_undefined and not sym.is_local:
            existing_export_names.add(sym.name)
    add_set_scratch = eff_synth_scratch and ("simd_set_scratch" not in existing_export_names)

    need_sp_global = (
        (old_sp_global_idx is not None)
        or (memory_mode == "export")
        or add_set_scratch
        or export_stack_pointer
    )
    sp_global_idx: int | None = None
    if need_sp_global:
        sp_global_idx = n_kept_imp_globals + len(out_globals)
        out_globals.append(
            GlobalEntry(
                valtype=VALTYPE_I32,
                mutable=True,
                init_expr=b"\x41" + encode_sleb(eff_stack_size) + b"\x0b",
            )
        )
        if old_sp_global_idx is not None:
            global_index_map[old_sp_global_idx] = sp_global_idx

    heap_base_global_idx: int | None = None
    if memory_mode == "export" and export_heap_base:
        heap_base_global_idx = n_kept_imp_globals + len(out_globals)
        out_globals.append(
            GlobalEntry(
                valtype=VALTYPE_I32,
                mutable=False,
                init_expr=b"\x41" + encode_sleb(heap_base) + b"\x0b",
            )
        )

    # ------------------------------------------------------------------
    # Indirect function table & ELEM remapping
    # ------------------------------------------------------------------
    out_elems: list[ElemSegment] = []
    func_to_table_slot: dict[int, int] = {}
    indirect_table_out_idx: int | None = None

    if needs_indirect_table:
        indirect_table_out_idx = n_kept_imp_tables + len(out_tables)
        if old_indirect_table_idx is not None:
            table_index_map[old_indirect_table_idx] = indirect_table_out_idx

        # Remap existing ELEM segments and record table slots.
        next_free_slot = 1  # Slot 0 is reserved for NULL function pointer.
        for seg in orig_elems:
            new_table_idx = table_index_map.get(seg.table_index, indirect_table_out_idx)
            remapped_funcs = [func_index_map[f] for f in seg.functions]
            if not (seg.flags & 1) and seg.offset_const is not None and not (seg.flags & 4):
                for i, old_fidx in enumerate(seg.functions):
                    slot = seg.offset_const + i
                    func_to_table_slot.setdefault(old_fidx, slot)
                    next_free_slot = max(next_free_slot, slot + 1)
            out_elems.append(
                ElemSegment(
                    flags=seg.flags,
                    table_index=new_table_idx,
                    offset_expr=seg.offset_expr,
                    offset_const=seg.offset_const,
                    elem_kind_or_type=seg.elem_kind_or_type,
                    functions=remapped_funcs,
                    raw_exprs=list(seg.raw_exprs),
                )
            )

        # Ensure every function referenced by a TABLE_INDEX relocation has a table slot.
        extra_funcs: list[int] = []
        extra_start_slot = next_free_slot
        for r in all_relocs:
            if r.rtype in TABLE_INDEX_RELOCS:
                sym = symbols[r.index]
                if sym.kind != SYM_FUNCTION or sym.element_index is None:
                    raise LinkError(f"TABLE_INDEX relocation refers to non-function symbol {sym.name}")
                if sym.element_index not in func_to_table_slot:
                    func_to_table_slot[sym.element_index] = next_free_slot
                    extra_funcs.append(func_index_map[sym.element_index])
                    next_free_slot += 1
        if extra_funcs:
            out_elems.append(
                ElemSegment(
                    flags=0,
                    table_index=indirect_table_out_idx,
                    offset_expr=b"\x41" + encode_sleb(extra_start_slot) + b"\x0b",
                    offset_const=extra_start_slot,
                    elem_kind_or_type=None,
                    functions=extra_funcs,
                )
            )

        table_size = max(next_free_slot, indirect_table_min)
        out_tables.append(
            (
                VALTYPE_FUNCREF,
                Limits(flags=LIMITS_FLAG_HAS_MAX, minimum=table_size, maximum=table_size),
            )
        )

    # ------------------------------------------------------------------
    # Prepare synthetic simd_set_scratch(i32) -> () if enabled
    # ------------------------------------------------------------------
    out_types = list(types)
    out_func_types = list(func_type_indices)
    set_scratch_func_idx: int | None = None
    if add_set_scratch:
        assert sp_global_idx is not None
        sig_i32_void = b"\x60\x01\x7f\x00"
        if sig_i32_void in out_types:
            scratch_type_idx = out_types.index(sig_i32_void)
        else:
            scratch_type_idx = len(out_types)
            out_types.append(sig_i32_void)
        set_scratch_func_idx = n_kept_imp_funcs + len(out_func_types)
        out_func_types.append(scratch_type_idx)

    # Compute CODE section count-prefix delta for R_WASM_FUNCTION_OFFSET_I32.
    final_code_count = len(out_func_types)
    code_count_delta = len(encode_uleb(final_code_count)) - orig_code_bodies_start

    # Map old function index -> final byte offset of function body entry in output CODE section.
    func_code_offsets: dict[int, int] = {}
    for i, (entry_start, _) in enumerate(orig_code_spans):
        func_code_offsets[orig_num_imp_funcs + i] = entry_start + code_count_delta

    # ------------------------------------------------------------------
    # Relocation resolver & applicator
    # ------------------------------------------------------------------
    linker_defined_data_addrs = {
        "__heap_base": heap_base,
        "__data_end": data_end,
        "__global_base": placed_base,
        "__memory_base": 0,
        "__stack_high": eff_stack_size,
        "__stack_low": 0,
        "__dso_handle": placed_base,
    }

    def resolve_data_addr(sym: SymbolInfo) -> int:
        if sym.kind != SYM_DATA:
            raise LinkError(f"Expected DATA symbol for memory relocation, got {sym.name} (kind={sym.kind})")
        if sym.is_undefined:
            if sym.name in linker_defined_data_addrs:
                return linker_defined_data_addrs[sym.name]
            raise LinkError(f"Unresolved undefined DATA symbol '{sym.name}'")
        assert sym.segment is not None and sym.offset is not None
        if sym.segment >= len(data_segments):
            raise LinkError(f"DATA symbol '{sym.name}' references out-of-range segment {sym.segment}")
        return data_segments[sym.segment].placed_addr + sym.offset

    def apply_relocs(target_buf: bytearray, relocs: list[Relocation], is_data: bool) -> None:
        # Build quick lookup from DATA payload offset -> placed vaddr (for LOCREL_I32).
        for r in relocs:
            rt = r.rtype
            if rt == R_WASM_TYPE_INDEX_LEB:
                # Per Linking.md, R_WASM_TYPE_INDEX_LEB uses a direct TYPE index, not symbol index!
                if r.index >= len(types):
                    raise LinkError(f"R_WASM_TYPE_INDEX_LEB index {r.index} >= num_types ({len(types)})")
                _patch_reloc_bytes(target_buf, r.offset, encode_uleb_padded(r.index, 5), "R_WASM_TYPE_INDEX_LEB")
                continue

            if r.index >= len(symbols):
                raise LinkError(f"Relocation type {rt} symbol index {r.index} out of range ({len(symbols)})")
            sym = symbols[r.index]

            if rt == R_WASM_FUNCTION_INDEX_LEB:
                assert sym.element_index is not None
                if sym.element_index not in func_index_map:
                    raise LinkError(f"Unresolved function symbol '{sym.name}'")
                _patch_reloc_bytes(
                    target_buf, r.offset, encode_uleb_padded(func_index_map[sym.element_index], 5), "R_WASM_FUNCTION_INDEX_LEB"
                )
            elif rt == R_WASM_FUNCTION_INDEX_I32:
                assert sym.element_index is not None
                _patch_reloc_bytes(
                    target_buf,
                    r.offset,
                    struct.pack("<I", func_index_map[sym.element_index] & 0xFFFFFFFF),
                    "R_WASM_FUNCTION_INDEX_I32",
                )
            elif rt in (R_WASM_TABLE_INDEX_SLEB, R_WASM_TABLE_INDEX_REL_SLEB):
                assert sym.element_index is not None
                slot = func_to_table_slot[sym.element_index]
                _patch_reloc_bytes(target_buf, r.offset, encode_sleb_padded(slot, 5), f"R_WASM_TABLE_INDEX({rt})")
            elif rt in (R_WASM_TABLE_INDEX_SLEB64, R_WASM_TABLE_INDEX_REL_SLEB64):
                assert sym.element_index is not None
                slot = func_to_table_slot[sym.element_index]
                _patch_reloc_bytes(target_buf, r.offset, encode_sleb_padded(slot, 10), f"R_WASM_TABLE_INDEX64({rt})")
            elif rt == R_WASM_TABLE_INDEX_I32:
                assert sym.element_index is not None
                slot = func_to_table_slot[sym.element_index]
                _patch_reloc_bytes(target_buf, r.offset, struct.pack("<I", slot & 0xFFFFFFFF), "R_WASM_TABLE_INDEX_I32")
            elif rt == R_WASM_TABLE_INDEX_I64:
                assert sym.element_index is not None
                slot = func_to_table_slot[sym.element_index]
                _patch_reloc_bytes(target_buf, r.offset, struct.pack("<Q", slot & 0xFFFFFFFFFFFFFFFF), "R_WASM_TABLE_INDEX_I64")
            elif rt == R_WASM_MEMORY_ADDR_LEB:
                addr = resolve_data_addr(sym) + r.addend
                if addr < 0 or addr > 0xFFFFFFFF:
                    raise LinkError(f"R_WASM_MEMORY_ADDR_LEB address {addr} out of u32 range for '{sym.name}'")
                _patch_reloc_bytes(target_buf, r.offset, encode_uleb_padded(addr, 5), "R_WASM_MEMORY_ADDR_LEB")
            elif rt in (R_WASM_MEMORY_ADDR_SLEB, R_WASM_MEMORY_ADDR_REL_SLEB, R_WASM_MEMORY_ADDR_TLS_SLEB):
                addr = resolve_data_addr(sym) + r.addend
                _patch_reloc_bytes(target_buf, r.offset, encode_sleb_padded(addr, 5), f"R_WASM_MEMORY_ADDR_SLEB({rt})")
            elif rt == R_WASM_MEMORY_ADDR_I32:
                addr = resolve_data_addr(sym) + r.addend
                _patch_reloc_bytes(target_buf, r.offset, struct.pack("<I", addr & 0xFFFFFFFF), "R_WASM_MEMORY_ADDR_I32")
            elif rt == R_WASM_MEMORY_ADDR_LEB64:
                addr = resolve_data_addr(sym) + r.addend
                if addr < 0:
                    raise LinkError(f"R_WASM_MEMORY_ADDR_LEB64 address {addr} < 0 for '{sym.name}'")
                _patch_reloc_bytes(target_buf, r.offset, encode_uleb_padded(addr, 10), "R_WASM_MEMORY_ADDR_LEB64")
            elif rt in (R_WASM_MEMORY_ADDR_SLEB64, R_WASM_MEMORY_ADDR_REL_SLEB64, R_WASM_MEMORY_ADDR_TLS_SLEB64):
                addr = resolve_data_addr(sym) + r.addend
                _patch_reloc_bytes(target_buf, r.offset, encode_sleb_padded(addr, 10), f"R_WASM_MEMORY_ADDR_SLEB64({rt})")
            elif rt == R_WASM_MEMORY_ADDR_I64:
                addr = resolve_data_addr(sym) + r.addend
                _patch_reloc_bytes(target_buf, r.offset, struct.pack("<Q", addr & 0xFFFFFFFFFFFFFFFF), "R_WASM_MEMORY_ADDR_I64")
            elif rt == R_WASM_MEMORY_ADDR_LOCREL_I32:
                addr = resolve_data_addr(sym) + r.addend
                loc_vaddr = r.offset
                if is_data:
                    for seg in data_segments:
                        if seg.payload_start <= r.offset < seg.payload_end:
                            loc_vaddr = seg.placed_addr + (r.offset - seg.payload_start)
                            break
                _patch_reloc_bytes(
                    target_buf, r.offset, struct.pack("<i", addr - loc_vaddr), "R_WASM_MEMORY_ADDR_LOCREL_I32"
                )
            elif rt == R_WASM_GLOBAL_INDEX_LEB:
                assert sym.element_index is not None
                if sym.element_index not in global_index_map:
                    raise LinkError(f"Unresolved global symbol '{sym.name}'")
                _patch_reloc_bytes(
                    target_buf, r.offset, encode_uleb_padded(global_index_map[sym.element_index], 5), "R_WASM_GLOBAL_INDEX_LEB"
                )
            elif rt == R_WASM_GLOBAL_INDEX_I32:
                assert sym.element_index is not None
                _patch_reloc_bytes(
                    target_buf,
                    r.offset,
                    struct.pack("<I", global_index_map[sym.element_index] & 0xFFFFFFFF),
                    "R_WASM_GLOBAL_INDEX_I32",
                )
            elif rt == R_WASM_FUNCTION_OFFSET_I32:
                assert sym.element_index is not None
                if sym.element_index not in func_code_offsets:
                    raise LinkError(f"R_WASM_FUNCTION_OFFSET_I32 refers to non-defined function '{sym.name}'")
                val = func_code_offsets[sym.element_index] + r.addend
                _patch_reloc_bytes(target_buf, r.offset, struct.pack("<I", val & 0xFFFFFFFF), "R_WASM_FUNCTION_OFFSET_I32")
            elif rt == R_WASM_FUNCTION_OFFSET_I64:
                assert sym.element_index is not None
                val = func_code_offsets[sym.element_index] + r.addend
                _patch_reloc_bytes(
                    target_buf, r.offset, struct.pack("<Q", val & 0xFFFFFFFFFFFFFFFF), "R_WASM_FUNCTION_OFFSET_I64"
                )
            elif rt == R_WASM_SECTION_OFFSET_I32:
                val = r.addend
                _patch_reloc_bytes(target_buf, r.offset, struct.pack("<I", val & 0xFFFFFFFF), "R_WASM_SECTION_OFFSET_I32")
            elif rt == R_WASM_TAG_INDEX_LEB:
                assert sym.element_index is not None
                _patch_reloc_bytes(
                    target_buf, r.offset, encode_uleb_padded(tag_index_map[sym.element_index], 5), "R_WASM_TAG_INDEX_LEB"
                )
            elif rt == R_WASM_TABLE_NUMBER_LEB:
                assert sym.element_index is not None
                if sym.element_index not in table_index_map:
                    raise LinkError(f"Unresolved table symbol '{sym.name}'")
                _patch_reloc_bytes(
                    target_buf, r.offset, encode_uleb_padded(table_index_map[sym.element_index], 5), "R_WASM_TABLE_NUMBER_LEB"
                )
            else:
                raise LinkError(f"Unsupported relocation type {rt} at offset 0x{r.offset:x}")

    # Apply relocations to CODE and DATA while original section offsets are intact.
    code_buf = bytearray(orig_code_payload)
    if code_relocs:
        apply_relocs(code_buf, code_relocs, is_data=False)

    if data_segments:
        data_raw_buf = bytearray(by_id[SEC_DATA].payload)
        if data_relocs:
            apply_relocs(data_raw_buf, data_relocs, is_data=True)
        for seg in data_segments:
            seg.data = bytes(data_raw_buf[seg.payload_start : seg.payload_end])

    # ------------------------------------------------------------------
    # Assemble CODE section (including optional simd_set_scratch body)
    # ------------------------------------------------------------------
    relocated_bodies = bytes(code_buf[orig_code_bodies_start:])
    if add_set_scratch:
        assert sp_global_idx is not None
        # (func (param i32) (global.set $__stack_pointer (local.get 0)))
        scratch_body = b"\x00\x20\x00\x24" + encode_uleb(sp_global_idx) + b"\x0b"
        relocated_bodies += encode_uleb(len(scratch_body)) + scratch_body
    out_code_payload = encode_uleb(final_code_count) + relocated_bodies if final_code_count > 0 else b""

    # ------------------------------------------------------------------
    # Assemble EXPORT section
    # ------------------------------------------------------------------
    out_exports: list[ExportEntry] = []
    exported_names: set[str] = set()
    exported_func_indices: set[int] = set()

    def add_export(name: str, kind: int, index: int) -> None:
        if name in exported_names:
            return
        exported_names.add(name)
        out_exports.append(ExportEntry(name=name, kind=kind, index=index))
        if kind == EXT_FUNC:
            exported_func_indices.add(index)

    if memory_mode == "export":
        add_export("memory", EXT_MEMORY, 0)

    # 1. Explicit exports from object's EXPORT section (e.g. __attribute__((export_name("...")))).
    for exp in orig_exports:
        if exp.kind == EXT_FUNC:
            if exp.index in func_index_map:
                add_export(exp.name, EXT_FUNC, func_index_map[exp.index])
        elif exp.kind == EXT_GLOBAL:
            if exp.index in global_index_map:
                add_export(exp.name, EXT_GLOBAL, global_index_map[exp.index])
        elif exp.kind == EXT_TABLE:
            if exp.index in table_index_map:
                add_export(exp.name, EXT_TABLE, table_index_map[exp.index])
        elif exp.kind == EXT_MEMORY:
            if memory_mode == "export":
                add_export(exp.name, EXT_MEMORY, 0)
        elif exp.kind == EXT_TAG:
            if exp.index in tag_index_map:
                add_export(exp.name, EXT_TAG, tag_index_map[exp.index])

    # 2. Non-local defined functions from linking symbol table that did not already have an explicit export_name.
    for sym in symbols:
        if sym.kind == SYM_FUNCTION and not sym.is_undefined and not sym.is_local:
            assert sym.element_index is not None
            new_fidx = func_index_map[sym.element_index]
            if new_fidx in exported_func_indices:
                continue
            if sym.name.startswith("__wasm_") and not sym.is_exported:
                continue
            add_export(sym.name, EXT_FUNC, new_fidx)

    # 3. Synthesized simd_set_scratch and optional global exports.
    if set_scratch_func_idx is not None:
        add_export("simd_set_scratch", EXT_FUNC, set_scratch_func_idx)
    if heap_base_global_idx is not None:
        add_export("__heap_base", EXT_GLOBAL, heap_base_global_idx)
    if export_stack_pointer and sp_global_idx is not None:
        add_export("__stack_pointer", EXT_GLOBAL, sp_global_idx)

    # ------------------------------------------------------------------
    # Serialize final .wasm binary in canonical section order
    # ------------------------------------------------------------------
    out = bytearray(WASM_MAGIC + WASM_VERSION)

    # 1. TYPE
    if out_types:
        payload = bytearray(encode_uleb(len(out_types)))
        for entry in out_types:
            payload += entry
        out += encode_section(SEC_TYPE, bytes(payload))

    # 2. IMPORT
    if out_imports:
        payload = bytearray(encode_uleb(len(out_imports)))
        for imp in out_imports:
            payload += imp.encode()
        out += encode_section(SEC_IMPORT, bytes(payload))

    # 3. FUNCTION
    if out_func_types:
        payload = bytearray(encode_uleb(len(out_func_types)))
        for tidx in out_func_types:
            payload += encode_uleb(tidx)
        out += encode_section(SEC_FUNCTION, bytes(payload))

    # 4. TABLE
    if out_tables:
        payload = bytearray(encode_uleb(len(out_tables)))
        for elem_type, lim in out_tables:
            payload.append(elem_type)
            payload += lim.encode()
        out += encode_section(SEC_TABLE, bytes(payload))

    # 5. MEMORY
    if memory_mode == "export":
        mem_flags = LIMITS_FLAG_HAS_MAX if eff_max_pages is not None else LIMITS_FLAG_NONE
        mem_lim = Limits(flags=mem_flags, minimum=eff_initial_pages, maximum=eff_max_pages)
        out += encode_section(SEC_MEMORY, b"\x01" + mem_lim.encode())
    elif orig_mems:
        payload = bytearray(encode_uleb(len(orig_mems)))
        for lim in orig_mems:
            payload += lim.encode()
        out += encode_section(SEC_MEMORY, bytes(payload))

    # 13. TAG (canonical position between MEMORY and GLOBAL)
    if orig_tags_raw is not None:
        out += encode_section(SEC_TAG, orig_tags_raw)

    # 6. GLOBAL
    if out_globals:
        payload = bytearray(encode_uleb(len(out_globals)))
        for g in out_globals:
            payload += g.encode()
        out += encode_section(SEC_GLOBAL, bytes(payload))

    # 7. EXPORT
    if out_exports:
        payload = bytearray(encode_uleb(len(out_exports)))
        for exp in out_exports:
            payload += exp.encode()
        out += encode_section(SEC_EXPORT, bytes(payload))

    # 8. START
    if orig_start_idx is not None:
        out += encode_section(SEC_START, encode_uleb(func_index_map[orig_start_idx]))

    # 9. ELEM
    if out_elems:
        out += encode_section(SEC_ELEM, encode_elem_section(out_elems))

    # 12. DATACOUNT (must precede CODE per Wasm spec)
    if data_segments:
        out += encode_section(SEC_DATACOUNT, encode_uleb(len(data_segments)))

    # 10. CODE
    if out_code_payload:
        out += encode_section(SEC_CODE, out_code_payload)

    # 11. DATA
    if data_segments:
        payload = bytearray(encode_uleb(len(data_segments)))
        for seg in data_segments:
            if seg.flags == 0:
                payload.append(0)
                payload += b"\x41" + encode_sleb(seg.placed_addr) + b"\x0b"
            elif seg.flags == 1:
                payload.append(1)
            elif seg.flags == 2:
                payload.append(2)
                payload += encode_uleb(seg.mem_index)
                payload += b"\x41" + encode_sleb(seg.placed_addr) + b"\x0b"
            else:
                raise LinkError(f"Unsupported DATA segment flags {seg.flags}")
            payload += encode_uleb(len(seg.data))
            payload += seg.data
        out += encode_section(SEC_DATA, bytes(payload))

    # Optional target_features custom section
    if keep_target_features and target_features_sec is not None and target_features_sec.custom_payload is not None:
        out += encode_custom_section("target_features", target_features_sec.custom_payload)

    result = bytes(out)
    validate_wasm(result)
    return result


# ---------------------------------------------------------------------------
# CLI entrypoint
# ---------------------------------------------------------------------------


def _parse_int(text: str) -> int:
    return int(text, 0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("input", type=Path, help="Input clang-21 wasm32 relocatable .o file")
    parser.add_argument("-o", "--output", type=Path, required=True, help="Output linked .wasm file")
    parser.add_argument(
        "--memory-mode",
        choices=("export", "import"),
        default="export",
        help="Memory mode: 'export' (standalone worker memory) or 'import' (shared TeaVM env.memory)",
    )
    parser.add_argument("--initial-pages", type=_parse_int, default=None, help="Initial memory pages (64KiB each)")
    parser.add_argument("--max-pages", type=_parse_int, default=None, help="Maximum memory pages (64KiB each)")
    parser.add_argument("--stack-size", type=_parse_int, default=None, help="Initial __stack_pointer value in bytes")
    parser.add_argument("--data-base", type=_parse_int, default=None, help="Base address for active DATA segments")
    parser.add_argument("--no-data", action="store_true", help="Fail if any active DATA segments exist")
    parser.add_argument("--no-heap-base", action="store_true", help="Do not export __heap_base global in export mode")
    parser.add_argument("--export-stack-pointer", action="store_true", help="Export mutable __stack_pointer global")
    parser.add_argument("--no-set-scratch", action="store_true", help="Do not synthesize simd_set_scratch in import mode")
    parser.add_argument("--keep-target-features", action="store_true", help="Preserve target_features custom section")
    parser.add_argument(
        "--allow-unresolved-imports", action="store_true", help="Keep non-memory/non-table external imports"
    )
    parser.add_argument("--inspect", action="store_true", help="Print validated module summary to stdout")
    args = parser.parse_args(argv)

    try:
        obj_bytes = args.input.read_bytes()
        wasm_bytes = link_wasm_object(
            obj_bytes,
            memory_mode=args.memory_mode,
            initial_pages=args.initial_pages,
            max_pages=args.max_pages,
            stack_size=args.stack_size,
            data_base=args.data_base,
            no_data=args.no_data,
            export_heap_base=not args.no_heap_base,
            export_stack_pointer=args.export_stack_pointer,
            synthesize_set_scratch=False if args.no_set_scratch else None,
            keep_target_features=args.keep_target_features,
            allow_unresolved_imports=args.allow_unresolved_imports,
        )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(wasm_bytes)
        if args.inspect:
            info = validate_wasm(wasm_bytes)
            print(
                f"LINKED {args.output} ({info['size_bytes']} bytes, mode={args.memory_mode}, "
                f"funcs={info['num_functions']}, data={info['num_data_segments']}, "
                f"exports={[e[0] for e in info['exports']]})"
            )
    except (OSError, ValueError, LinkError, WasmValidationError) as exc:
        print(f"wasm_link_single: error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
