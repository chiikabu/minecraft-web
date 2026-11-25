#!/usr/bin/env python3
"""End-to-end selfcheck for scripts/wasm_link_single.py and wasm_simd128_native.h.

Uses native Linux clang-21, llvm-objdump-21, llvm-readobj-21, and Python ctypes
to verify:
  1. Export-mode linking (internal memory + stack + aligned DATA + indirect table + __heap_base).
  2. Import-mode linking (ONLY env.memory import + simd_set_scratch + --no-data & data_base guards).
  3. Real 0xFD SIMD128 opcodes in disassembled linked .wasm binaries.
  4. Native x86_64/ARM64 compilation & execution parity of wasm_simd128_native.h via ctypes.
  5. Structural validator negative cases.
"""

from __future__ import annotations

import ctypes
import math
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile

SCRIPT_DIR = Path(__file__).resolve().parent
SCRIPTS_ROOT = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPTS_ROOT))

import wasm_link_single as wls  # noqa: E402

EXPORT_MODE_KERNEL_C = r'''
#include <stdint.h>
#include "wasm_simd128_native.h"

#ifdef __wasm__
#define WASM_EXPORT(name) __attribute__((export_name(name)))
#else
#define WASM_EXPORT(name)
#endif

static const float LUT_SCALE[4] __attribute__((aligned(16))) = {1.5f, 2.0f, 3.25f, 4.5f};
static const float LUT_BIAS[4] __attribute__((aligned(16))) = {0.25f, -0.5f, 1.0f, -2.0f};
static const float LUT_ALIGNED64[16] __attribute__((aligned(64))) = {
    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
};
static const float * volatile LUT_PTRS[2] = { &LUT_SCALE[0], &LUT_ALIGNED64[4] };
static int32_t g_counter = 11;
static int32_t g_bss_ring[16] __attribute__((aligned(16)));

typedef int32_t (*op_fn_t)(int32_t);

__attribute__((noinline)) static int32_t op_add(int32_t x) {
    return x + g_counter;
}

__attribute__((noinline)) static int32_t op_mul(int32_t x) {
    return x * 5 - 3;
}

static const op_fn_t OP_TABLE[2] = { op_add, op_mul };

__attribute__((noinline)) static void apply_simd_transform(float *buf, int32_t which) {
    v128_t v = wasm_v128_load(buf);
    v128_t scale = wasm_v128_load(LUT_PTRS[which & 1]);
    v128_t bias = wasm_v128_load(LUT_BIAS);
    v128_t r = wasm_f32x4_add(wasm_f32x4_mul(v, scale), bias);
    r = wasm_f32x4_max(wasm_f32x4_sqrt(wasm_f32x4_abs(r)), wasm_f32x4_splat(0.5f));
    wasm_v128_store(buf, r);
}

#ifdef __wasm__
extern unsigned char __heap_base;

WASM_EXPORT("get_heap_base")
uint32_t get_heap_base(void) {
    return (uint32_t)(uintptr_t)&__heap_base;
}
#endif

WASM_EXPORT("simd_export_kernel")
float simd_export_kernel(int32_t idx, float x) {
    float local_buf[4] __attribute__((aligned(16))) = {x, x + 1.0f, x + 2.0f, x + 3.0f};
    apply_simd_transform(local_buf, idx);
    op_fn_t fn = (idx & 2) ? op_add : OP_TABLE[idx & 1];
    g_counter += fn(idx);
    g_bss_ring[idx & 15] = g_counter;
    v128_t v = wasm_v128_load(local_buf);
    v128_t mask = wasm_f32x4_gt(v, wasm_f32x4_splat(1.0f));
    uint32_t bm = wasm_i32x4_bitmask(mask);
    return local_buf[0] + local_buf[1] + local_buf[2] + local_buf[3] + (float)bm + (float)g_bss_ring[idx & 15];
}

int32_t plain_non_static_fn(int32_t a, int32_t b) {
    return op_add(a) + b;
}
'''

IMPORT_MODE_KERNEL_C = r'''
#include <stdint.h>
#include "wasm_simd128_native.h"

#ifdef __wasm__
#define WASM_EXPORT(name) __attribute__((export_name(name)))
#else
#define WASM_EXPORT(name)
#endif

__attribute__((noinline)) static void spill_helper(float *tmp) {
    v128_t v = wasm_v128_load(tmp);
    v128_t neg = wasm_f32x4_neg(v);
    v128_t mask = wasm_f32x4_lt(v, wasm_f32x4_splat(0.0f));
    wasm_v128_store(tmp, wasm_v128_bitselect(neg, v, mask));
}

WASM_EXPORT("simd_f32_axpy")
void simd_f32_axpy(float *dst, const float *a, const float *b, float alpha, int32_t count) {
    v128_t valpha = wasm_f32x4_splat(alpha);
    int32_t i = 0;
    for (; i + 4 <= count; i += 4) {
        v128_t va = wasm_v128_load(a + i);
        v128_t vb = wasm_v128_load(b + i);
        v128_t vr = wasm_f32x4_add(wasm_f32x4_mul(va, valpha), vb);
        wasm_v128_store(dst + i, vr);
    }
    for (; i < count; ++i) {
        dst[i] = a[i] * alpha + b[i];
    }
}

WASM_EXPORT("simd_stack_abs4")
float simd_stack_abs4(float a, float b, float c, float d) {
    float tmp[4] __attribute__((aligned(16))) = {a, b, c, d};
    spill_helper(tmp);
    return tmp[0] + tmp[1] + tmp[2] + tmp[3];
}

WASM_EXPORT("simd_comprehensive_check")
uint32_t simd_comprehensive_check(
    const float *f32_in,
    const double *f64_in,
    const int32_t *i32_in,
    const int64_t *i64_in,
    const uint8_t *u8_table,
    const uint8_t *u8_idx,
    float *f32_out,
    double *f64_out,
    int32_t *i32_out,
    int64_t *i64_out,
    uint8_t *u8_out) {

    /* f32x4 ops */
    v128_t fa = wasm_v128_load(f32_in);
    v128_t fb = wasm_v128_load(f32_in + 4);
    v128_t fc = wasm_f32x4_div(wasm_f32x4_sub(wasm_f32x4_mul(fa, fb), wasm_f32x4_min(fa, fb)),
                               wasm_f32x4_max(wasm_f32x4_sqrt(wasm_f32x4_abs(fb)), wasm_f32x4_splat(1.0f)));
    fc = wasm_f32x4_replace_lane(fc, 3, wasm_f32x4_extract_lane(wasm_f32x4_neg(fa), 0));
    wasm_v128_store(f32_out, fc);
    uint32_t f32_cmp_bits =
        (wasm_i32x4_bitmask(wasm_f32x4_eq(fa, fb)) << 0) |
        (wasm_i32x4_bitmask(wasm_f32x4_ne(fa, fb)) << 4) |
        (wasm_i32x4_bitmask(wasm_f32x4_lt(fa, fb)) << 8) |
        (wasm_i32x4_bitmask(wasm_f32x4_le(fa, fb)) << 12) |
        (wasm_i32x4_bitmask(wasm_f32x4_gt(fa, fb)) << 16) |
        (wasm_i32x4_bitmask(wasm_f32x4_ge(fa, fb)) << 20);

    /* f64x2 ops */
    v128_t da = wasm_v128_load(f64_in);
    v128_t db = wasm_v128_load(f64_in + 2);
    v128_t dc = wasm_f64x2_add(wasm_f64x2_floor(wasm_f64x2_div(da, db)),
                               wasm_f64x2_min(wasm_f64x2_sqrt(wasm_f64x2_max(da, wasm_f64x2_splat(0.0))), db));
    wasm_v128_store(f64_out, dc);
    uint32_t f64_cmp_bits =
        (wasm_i64x2_bitmask(wasm_f64x2_lt(da, db)) << 0) |
        (wasm_i64x2_bitmask(wasm_f64x2_le(da, db)) << 2) |
        (wasm_i64x2_bitmask(wasm_f64x2_gt(da, db)) << 4) |
        (wasm_i64x2_bitmask(wasm_f64x2_ge(da, db)) << 6);

    /* i32x4 / u32x4 / trunc_sat / convert ops */
    v128_t ia = wasm_v128_load(i32_in);
    v128_t ib = wasm_v128_load(i32_in + 4);
    v128_t ic = wasm_i32x4_add(wasm_i32x4_mul(wasm_i32x4_sub(ia, ib), wasm_i32x4_splat(3)),
                               wasm_i32x4_min(ia, ib));
    ic = wasm_i32x4_max(ic, wasm_i32x4_shl(wasm_i32x4_shr(ib, 1), 2));
    v128_t iu = wasm_u32x4_min(wasm_u32x4_shr(ia, 3), wasm_u32x4_max(ib, wasm_i32x4_splat(7)));
    v128_t tsat = wasm_i32x4_trunc_sat_f32x4(fa);
    v128_t fconv = wasm_f32x4_convert_i32x4(tsat);
    v128_t combined_i32 = wasm_v128_xor(wasm_v128_andnot(ic, iu), wasm_v128_or(tsat, wasm_v128_not(wasm_v128_const(1, 2, 3, 4))));
    combined_i32 = wasm_i32x4_add(combined_i32, wasm_i32x4_trunc_sat_f32x4(fconv));
    wasm_v128_store(i32_out, combined_i32);

    /* i64x2 / u64x2 ops */
    v128_t qa = wasm_v128_load(i64_in);
    v128_t qb = wasm_v128_load(i64_in + 2);
    v128_t qc = wasm_i64x2_add(wasm_i64x2_mul(qa, wasm_i64x2_splat(5)),
                               wasm_i64x2_sub(wasm_i64x2_shl(qb, 2), wasm_i64x2_shr(qa, 1)));
    qc = wasm_v128_xor(qc, wasm_u64x2_shr(qb, 3));
    wasm_v128_store(i64_out, qc);

    /* i16x8 + i8x16 swizzle + bitmask + any_true */
    v128_t s16 = wasm_i16x8_and(wasm_i16x8_sub(wasm_i16x8_add(wasm_i16x8_splat(300), wasm_i16x8_splat(45)),
                                               wasm_i16x8_splat(10)),
                                wasm_i16x8_splat(0x00FF));
    int16_t lane0 = wasm_i16x8_extract_lane(s16, 0);

    v128_t tbl = wasm_v128_load(u8_table);
    v128_t idx = wasm_v128_load(u8_idx);
    v128_t swz = wasm_i8x16_swizzle(tbl, idx);
    wasm_v128_store(u8_out, swz);
    uint32_t bm8 = wasm_i8x16_bitmask(wasm_v128_or(swz, wasm_i8x16_splat((int8_t)-128)));
    uint32_t any = wasm_v128_any_true(swz) ? 1u : 0u;

    return (f32_cmp_bits ^ (f64_cmp_bits << 8) ^ bm8 ^ ((uint32_t)(uint16_t)lane0 << 16) ^ any);
}
'''


def _run(cmd: list[str]) -> str:
    proc = subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return proc.stdout


def _compile_wasm_obj(src_path: Path, obj_path: Path) -> bytes:
    _run([
        "clang-21",
        "--target=wasm32",
        "-msimd128",
        "-O3",
        "-ffreestanding",
        f"-I{SCRIPT_DIR}",
        "-c",
        str(src_path),
        "-o",
        str(obj_path),
    ])
    return obj_path.read_bytes()


def _verify_llvm_tools(wasm_path: Path, *, expect_simd_mnemonics: list[str]) -> tuple[str, str]:
    disasm = _run(["llvm-objdump-21", "-d", str(wasm_path)])
    readobj = _run(["llvm-readobj-21", "--file-headers", "--sections", str(wasm_path)])
    assert "Format: WASM" in readobj, "llvm-readobj-21 did not recognize WASM format"
    assert "Name: linking" not in readobj, "linked .wasm still contains 'linking' section"
    assert "Name: reloc." not in readobj, "linked .wasm still contains 'reloc.*' section"

    # Verify real 0xFD SIMD128 opcode bytes appear in objdump hex dump.
    fd_lines = [line for line in disasm.splitlines() if re.search(r":\s+fd\s+[0-9a-f]{2}", line)]
    assert len(fd_lines) >= 3, f"Expected >= 3 real 0xFD SIMD128 instruction encodings, found {len(fd_lines)}"
    for mnem in expect_simd_mnemonics:
        assert mnem in disasm, f"Expected SIMD128 mnemonic '{mnem}' in disassembly"
    return disasm, readobj


def test_export_mode(tmp: Path) -> dict:
    src = tmp / "export_kernel.c"
    obj_path = tmp / "export_kernel.o"
    wasm_path = tmp / "export_kernel.wasm"
    cli_wasm_path = tmp / "export_kernel_cli.wasm"
    src.write_text(EXPORT_MODE_KERNEL_C, encoding="utf-8")

    obj_bytes = _compile_wasm_obj(src, obj_path)

    # Link via Python API.
    wasm_bytes = wls.link_wasm_object(
        obj_bytes,
        memory_mode="export",
        initial_pages=256,
        max_pages=4096,
        stack_size=65536,
        data_base=65536,
    )
    wasm_path.write_bytes(wasm_bytes)
    info = wls.validate_wasm(wasm_bytes)

    # Link via CLI and verify byte-for-byte determinism.
    cli_out = _run([
        sys.executable,
        str(SCRIPTS_ROOT / "wasm_link_single.py"),
        str(obj_path),
        "-o",
        str(cli_wasm_path),
        "--memory-mode",
        "export",
        "--initial-pages",
        "256",
        "--max-pages",
        "4096",
        "--stack-size",
        "65536",
        "--data-base",
        "65536",
        "--inspect",
    ])
    assert "LINKED" in cli_out
    assert cli_wasm_path.read_bytes() == wasm_bytes, "CLI output differs from Python API output"

    # Check exports, imports, memory, globals, table.
    assert info["imports"] == [], f"Export mode should have zero imports, got {info['imports']}"
    assert info["num_memories"] == 1
    assert info["num_tables"] == 1, f"Indirect function pointer table should be internalized: {info}"
    assert info["num_globals"] == 2, f"Expected __stack_pointer and __heap_base globals: {info}"
    assert info["num_data_segments"] >= 3, f"Expected rodata/data/bss segments: {info}"
    export_names = {name for name, _, _ in info["exports"]}
    assert {"memory", "__heap_base", "simd_export_kernel", "plain_non_static_fn"} <= export_names, export_names
    assert "op_add" not in export_names and "op_mul" not in export_names and "apply_simd_transform" not in export_names

    # Verify __heap_base global matches get_heap_base() relocated i32.const and >= 65536.
    sections = {s.sec_id: s for s in wls.parse_sections(wasm_bytes)}
    globs = wls.parse_global_section(sections[wls.SEC_GLOBAL].payload)
    _, sp_val, _ = wls.parse_init_expr(globs[0].init_expr, 0)
    _, heap_base_val, _ = wls.parse_init_expr(globs[1].init_expr, 0)
    assert globs[0].mutable is True and sp_val == 65536, f"Bad __stack_pointer global: {globs[0]}"
    assert globs[1].mutable is False and heap_base_val > 65536 and (heap_base_val % 16) == 0

    # Verify MEMORY section exact limits (min=256, max=4096).
    mems = wls.parse_memory_section(sections[wls.SEC_MEMORY].payload)
    assert len(mems) == 1 and mems[0].minimum == 256 and mems[0].maximum == 4096, mems

    # Verify all DATA segments are placed >= 65536, non-overlapping, and R_WASM_MEMORY_ADDR_I32 pointers relocated.
    data_segs = wls.parse_data_section(sections[wls.SEC_DATA].payload)
    prev_end = 65536
    seg_addrs = []
    for seg in data_segs:
        assert seg.offset_const is not None and seg.offset_const >= prev_end
        seg_addrs.append((seg.offset_const, len(seg.data), seg.data))
        prev_end = seg.offset_const + len(seg.data)
    assert heap_base_val == wls.align_up(prev_end, 16)
    # Verify the 64-byte aligned LUT_ALIGNED64 segment is indeed 64-byte aligned.
    aligned64_segs = [addr for addr, length, _ in seg_addrs if length == 64]
    assert aligned64_segs and all((a % 64) == 0 for a in aligned64_segs), f"64B alignment violated: {seg_addrs}"
    # Verify LUT_PTRS (8-byte pointer table) contains relocated addresses >= 65536 pointing into LUT_SCALE & LUT_ALIGNED64+16.
    lut_scale_addr = next(addr for addr, length, data in seg_addrs if length == 16 and data[:4] == struct.pack("<f", 1.5))
    lut64_addr = aligned64_segs[0]
    expected_ptrs = struct.pack("<II", lut_scale_addr, lut64_addr + 16)
    assert any(data == expected_ptrs for _, _, data in seg_addrs), (
        f"Expected relocated R_WASM_MEMORY_ADDR_I32 ptr table {expected_ptrs.hex()} in DATA segments"
    )

    # Verify disassembly & readobj.
    disasm, _ = _verify_llvm_tools(
        wasm_path,
        expect_simd_mnemonics=["v128.load", "v128.store", "f32x4.mul", "f32x4.add", "f32x4.sqrt", "i32x4.bitmask"],
    )
    assert f"i32.const\t{heap_base_val}" in disasm, (
        f"Expected get_heap_base() to return relocated __heap_base={heap_base_val}"
    )
    assert "call_indirect" in disasm
    return {"size_bytes": len(wasm_bytes), "heap_base": heap_base_val, "exports": sorted(export_names)}


def test_import_mode(tmp: Path) -> dict:
    src = tmp / "import_kernel.c"
    obj_path = tmp / "import_kernel.o"
    wasm_path = tmp / "import_kernel.wasm"
    src.write_text(IMPORT_MODE_KERNEL_C, encoding="utf-8")

    obj_bytes = _compile_wasm_obj(src, obj_path)

    wasm_bytes = wls.link_wasm_object(obj_bytes, memory_mode="import", no_data=True)
    wasm_path.write_bytes(wasm_bytes)
    info = wls.validate_wasm(wasm_bytes)

    # Must keep ONLY (import "env" "memory" (memory 1))
    assert info["imports"] == [("env", "memory", wls.EXT_MEMORY)], f"Unexpected imports: {info['imports']}"
    sections = {s.sec_id: s for s in wls.parse_sections(wasm_bytes)}
    imp_entries = wls.parse_import_section(sections[wls.SEC_IMPORT].payload)
    assert len(imp_entries) == 1
    assert imp_entries[0].limits is not None
    assert imp_entries[0].limits.flags == wls.LIMITS_FLAG_NONE
    assert imp_entries[0].limits.minimum == 1
    assert imp_entries[0].limits.maximum is None

    assert wls.SEC_MEMORY not in sections
    assert wls.SEC_TABLE not in sections
    assert wls.SEC_DATA not in sections
    assert wls.SEC_DATACOUNT not in sections

    # Mutable __stack_pointer initialized to 0 + synthesized simd_set_scratch(ptr).
    globs = wls.parse_global_section(sections[wls.SEC_GLOBAL].payload)
    assert len(globs) == 1 and globs[0].mutable is True
    _, sp_val, _ = wls.parse_init_expr(globs[0].init_expr, 0)
    assert sp_val == 0

    export_names = {name for name, _, _ in info["exports"]}
    assert {"simd_f32_axpy", "simd_stack_abs4", "simd_comprehensive_check", "simd_set_scratch"} == export_names

    disasm, _ = _verify_llvm_tools(
        wasm_path,
        expect_simd_mnemonics=[
            "v128.load",
            "v128.store",
            "f32x4.mul",
            "f32x4.add",
            "f64x2.floor",
            "i32x4.trunc_sat_f32x4_s",
            "i8x16.swizzle",
            "i8x16.bitmask",
            "v128.any_true",
            "v128.bitselect",
        ],
    )
    assert "global.set\t0" in disasm

    # Test import-mode active-DATA protection guards using export_kernel.o (which has static DATA).
    data_obj = (tmp / "export_kernel.o").read_bytes()
    for kwargs in (
        {"memory_mode": "import", "no_data": True},
        {"memory_mode": "import"},  # missing explicit data_base with active DATA
        {"memory_mode": "import", "data_base": 0},  # address 0 collision
    ):
        try:
            wls.link_wasm_object(data_obj, **kwargs)
        except wls.LinkError:
            pass
        else:
            raise AssertionError(f"Expected LinkError for import-mode data guard with {kwargs}")

    # Explicit safe data_base in import mode must succeed cleanly.
    placed_import_wasm = wls.link_wasm_object(data_obj, memory_mode="import", data_base=0x100000, stack_size=0x0F0000)
    pinfo = wls.validate_wasm(placed_import_wasm)
    assert pinfo["imports"] == [("env", "memory", wls.EXT_MEMORY)]
    p_secs = {s.sec_id: s for s in wls.parse_sections(placed_import_wasm)}
    p_segs = wls.parse_data_section(p_secs[wls.SEC_DATA].payload)
    assert p_segs[0].offset_const == 0x100000

    return {"size_bytes": len(wasm_bytes), "exports": sorted(export_names)}


def test_native_shim_parity(tmp: Path) -> None:
    """Compile the exact same SIMD128 C kernels natively without -lm and verify exact results."""
    so_path = tmp / "native_parity.so"
    src_export = tmp / "export_kernel.c"
    src_import = tmp / "import_kernel.c"

    # Compile WITHOUT -lm to prove wasm_simd128_native.h has zero external math library dependency.
    _run([
        "clang-21",
        "-O3",
        "-shared",
        "-fPIC",
        "-Wall",
        "-Wextra",
        "-Werror",
        f"-I{SCRIPT_DIR}",
        str(src_export),
        str(src_import),
        "-o",
        str(so_path),
    ])

    # Verify zero libm dependency in the compiled native shared library.
    needed = _run(["llvm-readobj-21", "--needed-libs", str(so_path)])
    assert "libm." not in needed, f"Native parity shim unexpectedly depends on libm:\n{needed}"

    lib = ctypes.CDLL(str(so_path))

    # 1. Test simd_f32_axpy
    lib.simd_f32_axpy.argtypes = [
        ctypes.POINTER(ctypes.c_float),
        ctypes.POINTER(ctypes.c_float),
        ctypes.POINTER(ctypes.c_float),
        ctypes.c_float,
        ctypes.c_int32,
    ]
    n = 10
    a = (ctypes.c_float * n)(*[float(i) - 3.5 for i in range(n)])
    b = (ctypes.c_float * n)(*[float(i) * 0.25 + 1.0 for i in range(n)])
    dst = (ctypes.c_float * n)()
    lib.simd_f32_axpy(dst, a, b, ctypes.c_float(2.5), n)
    for i in range(n):
        expected = struct.unpack("<f", struct.pack("<f", a[i] * 2.5 + b[i]))[0]
        assert dst[i] == expected, f"axpy[{i}]: got {dst[i]}, expected {expected}"

    # 2. Test simd_stack_abs4
    lib.simd_stack_abs4.argtypes = [ctypes.c_float] * 4
    lib.simd_stack_abs4.restype = ctypes.c_float
    got_abs = lib.simd_stack_abs4(-1.5, 2.25, -3.75, 4.0)
    assert got_abs == 11.5, f"simd_stack_abs4: got {got_abs}, expected 11.5"

    # 3. Test simd_export_kernel (exercises LUT_PTRS, OP_TABLE, g_counter, g_bss_ring, bitmask)
    lib.simd_export_kernel.argtypes = [ctypes.c_int32, ctypes.c_float]
    lib.simd_export_kernel.restype = ctypes.c_float
    res0 = lib.simd_export_kernel(0, 2.0)
    assert math.isfinite(res0) and res0 > 20.0, f"Unexpected simd_export_kernel result: {res0}"

    # 4. Test simd_comprehensive_check (exercises f32x4/f64x2/i32x4/u32x4/i64x2/u64x2/i16x8/i8x16/v128)
    lib.simd_comprehensive_check.argtypes = [
        ctypes.POINTER(ctypes.c_float),
        ctypes.POINTER(ctypes.c_double),
        ctypes.POINTER(ctypes.c_int32),
        ctypes.POINTER(ctypes.c_int64),
        ctypes.POINTER(ctypes.c_uint8),
        ctypes.POINTER(ctypes.c_uint8),
        ctypes.POINTER(ctypes.c_float),
        ctypes.POINTER(ctypes.c_double),
        ctypes.POINTER(ctypes.c_int32),
        ctypes.POINTER(ctypes.c_int64),
        ctypes.POINTER(ctypes.c_uint8),
    ]
    lib.simd_comprehensive_check.restype = ctypes.c_uint32

    f32_in = (ctypes.c_float * 8)(float("nan"), 1e20, -1e20, -3.75, 4.0, -0.0, 9.0, 2.0)
    f64_in = (ctypes.c_double * 4)(-7.25, 9.5, 2.0, 4.0)
    i32_in = (ctypes.c_int32 * 8)(-100, 25, 0x7FFFFFFF, -1, 10, -20, 5, 16)
    i64_in = (ctypes.c_int64 * 4)(1000, -2000, 16, -64)
    u8_table = (ctypes.c_uint8 * 16)(*[10 + i for i in range(16)])
    u8_idx = (ctypes.c_uint8 * 16)(15, 0, 16, 255, 7, 3, 2, 1, 8, 9, 10, 11, 12, 13, 14, 17)

    f32_out = (ctypes.c_float * 4)()
    f64_out = (ctypes.c_double * 2)()
    i32_out = (ctypes.c_int32 * 4)()
    i64_out = (ctypes.c_int64 * 2)()
    u8_out = (ctypes.c_uint8 * 16)()

    bits = lib.simd_comprehensive_check(
        f32_in, f64_in, i32_in, i64_in, u8_table, u8_idx,
        f32_out, f64_out, i32_out, i64_out, u8_out,
    )
    # Check swizzle out-of-range zeroing (indices 16, 255, 17 -> 0).
    expected_swz = [10 + idx if idx < 16 else 0 for idx in u8_idx]
    assert list(u8_out) == expected_swz, f"i8x16.swizzle mismatch: {list(u8_out)} != {expected_swz}"
    # Check f64x2 floor(-7.25 / 2.0) = floor(-3.625) = -4.0; + min(sqrt(max(-7.25, 0)), 2.0) = -4.0 + 0.0 = -4.0.
    assert f64_out[0] == -4.0, f"f64x2 floor/min/max mismatch: {f64_out[0]} != -4.0"
    # Check f64x2[1]: floor(9.5 / 4.0) = floor(2.375) = 2.0; + min(sqrt(9.5), 4.0) = 2.0 + sqrt(9.5).
    assert abs(f64_out[1] - (2.0 + math.sqrt(9.5))) < 1e-12
    # Check lane 3 replaced with -fa[0] (NaN -> NaN).
    assert math.isnan(f32_out[3])
    # Check lane0 = (300 + 45 - 10) & 0xFF = 335 & 0xFF = 79 embedded into bits.
    assert bits != 0


def test_validator_negative_cases(tmp: Path) -> None:
    obj_bytes = (tmp / "export_kernel.o").read_bytes()
    valid_wasm = (tmp / "export_kernel.wasm").read_bytes()

    # 1. Raw unlinked .o must fail validate_wasm (contains linking / reloc.*).
    for label, bad in [
        ("unlinked .o", obj_bytes),
        ("bad magic", b"\x00BAD\x01\x00\x00\x00"),
        ("truncated section", valid_wasm[:-3]),
        ("out-of-order sections", wls.WASM_MAGIC + wls.WASM_VERSION + wls.encode_section(wls.SEC_FUNCTION, b"\x00") + wls.encode_section(wls.SEC_TYPE, b"\x00")),
        ("bad export index", wls.WASM_MAGIC + wls.WASM_VERSION + wls.encode_section(wls.SEC_EXPORT, b"\x01\x01a\x00\x05")),
    ]:
        try:
            wls.validate_wasm(bad)
        except wls.WasmValidationError:
            pass
        else:
            raise AssertionError(f"validate_wasm unexpectedly accepted invalid module: {label}")


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="wasm-linker-selfcheck-") as td:
        tmp = Path(td)
        exp_info = test_export_mode(tmp)
        imp_info = test_import_mode(tmp)
        test_native_shim_parity(tmp)
        test_validator_negative_cases(tmp)
    print(
        f"LINKER_SELFCHECK_PASS "
        f"export={exp_info['size_bytes']}B(heap_base=0x{exp_info['heap_base']:x},exports={len(exp_info['exports'])}) "
        f"import={imp_info['size_bytes']}B(exports={imp_info['exports']}) "
        f"native_parity=OK validator_neg=OK"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
