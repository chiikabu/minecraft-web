#!/usr/bin/env python3
"""Self-check, WASM-SIMD128 audit, numerical parity verification, and benchmark
for scripts/wasm_simd/worldgen_cull_kernels.c.

Uses native Linux clang-21, llvm-objdump-21, llvm-readobj-21, and python3 only.
"""
from __future__ import annotations

import ctypes
import math
import random
import re
import struct
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
C_SRC = Path(__file__).resolve().parent / "worldgen_cull_kernels.c"
LINK_SCRIPT = ROOT / "scripts" / "wasm_link_single.py"

EXPECTED_EXPORTS = [
    "simd_improved_noise_batch",
    "simd_aquifer_voronoi_batch",
    "simd_beardifier_volume",
    "simd_biome_fiddle_batch",
    "simd_cull_aabb_frustum_fog",
    "simd_sort_translucent_quads",
    "simd_mat4_mul_batch",
]

# Expected SIMD128 instruction families per kernel in wasm32 disassembly
PER_KERNEL_SIMD_EXPECT = {
    "simd_improved_noise_batch": ("f64x2.",),
    "simd_aquifer_voronoi_batch": ("i32x4.",),
    "simd_beardifier_volume": ("f32x4.sqrt", "f32x4.", "f64x2.", "i32x4."),
    "simd_biome_fiddle_batch": ("f64x2.", "i64x2."),
    "simd_cull_aabb_frustum_fog": ("f32x4.", "i32x4.bitmask"),
    "simd_sort_translucent_quads": ("f32x4.", "v128.store"),
    "simd_mat4_mul_batch": ("f32x4.mul", "f32x4.add"),
}


# ---------------------------------------------------------------------------
# Minimal WASM binary parser & zero-relocation standalone linker check
# ---------------------------------------------------------------------------

def read_uleb(buf: bytes, pos: int) -> tuple[int, int]:
    val = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        val |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return val, pos
        shift += 7


def write_uleb(val: int) -> bytes:
    out = bytearray()
    while True:
        b = val & 0x7F
        val >>= 7
        if val:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def parse_wasm_sections(data: bytes) -> list[tuple[int, str, bytes]]:
    assert data[:8] == b"\x00asm\x01\x00\x00\x00", "Invalid WASM magic/version"
    pos = 8
    sections = []
    while pos < len(data):
        sec_id = data[pos]
        pos += 1
        sec_len, pos = read_uleb(data, pos)
        body = data[pos : pos + sec_len]
        pos += sec_len
        name = ""
        if sec_id == 0:
            nlen, npos = read_uleb(body, 0)
            name = body[npos : npos + nlen].decode("utf-8", errors="replace")
        sections.append((sec_id, name, body))
    return sections


def parse_imports(body: bytes) -> list[tuple[str, str, int]]:
    cnt, pos = read_uleb(body, 0)
    out = []
    for _ in range(cnt):
        mlen, pos = read_uleb(body, pos)
        mod = body[pos : pos + mlen].decode("utf-8")
        pos += mlen
        nlen, pos = read_uleb(body, pos)
        field = body[pos : pos + nlen].decode("utf-8")
        pos += nlen
        kind = body[pos]
        pos += 1
        if kind == 0:  # func
            _, pos = read_uleb(body, pos)
        elif kind == 1:  # table
            pos += 1  # elemtype
            flags = body[pos]
            pos += 1
            _, pos = read_uleb(body, pos)
            if flags & 1:
                _, pos = read_uleb(body, pos)
        elif kind == 2:  # memory
            flags = body[pos]
            pos += 1
            _, pos = read_uleb(body, pos)
            if flags & 1:
                _, pos = read_uleb(body, pos)
        elif kind == 3:  # global
            pos += 2  # valtype + mutability
        elif kind == 4:  # tag
            pos += 1
            _, pos = read_uleb(body, pos)
        else:
            raise ValueError(f"Unknown import kind {kind}")
        out.append((mod, field, kind))
    return out


def parse_exports(body: bytes) -> list[tuple[str, int, int]]:
    cnt, pos = read_uleb(body, 0)
    out = []
    for _ in range(cnt):
        nlen, pos = read_uleb(body, pos)
        name = body[pos : pos + nlen].decode("utf-8")
        pos += nlen
        kind = body[pos]
        pos += 1
        idx, pos = read_uleb(body, pos)
        out.append((name, kind, idx))
    return out


def link_zero_reloc_obj_to_wasm(obj_bytes: bytes) -> bytes:
    """Link a zero-relocation, zero-data wasm32 .o into an instantiable .wasm
    importing `env.memory` (matches `wasm_link_single.py --memory-mode import`)."""
    sections = parse_wasm_sections(obj_bytes)
    out = bytearray(b"\x00asm\x01\x00\x00\x00")
    for sec_id, name, body in sections:
        if sec_id == 0:
            # Strip relocatable-object custom sections (linking, producers, target_features)
            assert not name.startswith("reloc."), f"Unexpected relocation section: {name}"
            continue
        if sec_id == 2:
            # Emit minimal single import: (import "env" "memory" (memory 1))
            imp_body = bytearray()
            imp_body += write_uleb(1)  # 1 import
            imp_body += write_uleb(3) + b"env"
            imp_body += write_uleb(6) + b"memory"
            imp_body.append(0x02)  # memory import
            imp_body.append(0x00)  # limits: min only (no max -> compatible with any TeaVM memory)
            imp_body += write_uleb(1)  # min 1 page
            out.append(2)
            out += write_uleb(len(imp_body))
            out += imp_body
            continue
        out.append(sec_id)
        out += write_uleb(len(body))
        out += body
    return bytes(out)


def verify_wasm_build(tmp: Path) -> tuple[int, dict[str, int]]:
    obj_path = tmp / "worldgen_cull_kernels.o"
    cmd = [
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
        str(C_SRC),
        "-o",
        str(obj_path),
    ]
    subprocess.run(cmd, check=True)
    obj_bytes = obj_path.read_bytes()
    sections = parse_wasm_sections(obj_bytes)

    sec_ids = [sid for sid, _, _ in sections]
    custom_names = [name for sid, name, _ in sections if sid == 0]
    assert 11 not in sec_ids, "WASM object must have ZERO DATA (0x0B) sections!"
    assert 12 not in sec_ids, "WASM object must have ZERO DATACOUNT (0x0C) sections!"
    assert 6 not in sec_ids, "WASM object must have ZERO GLOBAL (0x06) sections!"

    # Check imports & exports
    imports = []
    exports = []
    for sid, _, body in sections:
        if sid == 2:
            imports = parse_imports(body)
        elif sid == 7:
            exports = parse_exports(body)

    func_imports = [imp for imp in imports if imp[2] == 0]
    global_imports = [imp for imp in imports if imp[2] == 3]
    assert not func_imports, f"Unexpected external function imports (libc/libm leak): {func_imports}"
    for mod, field, _ in global_imports:
        assert field == "__stack_pointer", f"Unexpected mutable global import: {mod}.{field}"

    export_names = [name for name, kind, _ in exports if kind == 0]
    assert export_names == EXPECTED_EXPORTS, f"Export mismatch: {export_names} != {EXPECTED_EXPORTS}"

    # Link via scripts/wasm_link_single.py if present, else verify standalone zero-reloc link
    linked_wasm_path = tmp / "worldgen_cull_kernels.wasm"
    if LINK_SCRIPT.is_file():
        link_res = subprocess.run(
            [
                sys.executable,
                str(LINK_SCRIPT),
                str(obj_path),
                "-o",
                str(linked_wasm_path),
                "--memory-mode",
                "import",
                "--no-data",
                "--inspect",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        wasm_bytes = linked_wasm_path.read_bytes()
        link_mode = "wasm_link_single.py --memory-mode import --no-data"
        if link_res.stdout.strip():
            for ln in link_res.stdout.strip().splitlines():
                print(f"  [linker] {ln}")
    else:
        wasm_bytes = link_zero_reloc_obj_to_wasm(obj_bytes)
        linked_wasm_path.write_bytes(wasm_bytes)
        link_mode = "standalone zero-reloc .o -> .wasm (wasm_link_single.py not yet present)"

    # Audit final linked .wasm
    wasm_sections = parse_wasm_sections(wasm_bytes)
    wasm_sec_ids = [sid for sid, _, _ in wasm_sections]
    assert 11 not in wasm_sec_ids, "Linked .wasm must have ZERO DATA sections!"
    assert 12 not in wasm_sec_ids, "Linked .wasm must have ZERO DATACOUNT sections!"
    wasm_imports = []
    wasm_exports = []
    for sid, _, body in wasm_sections:
        if sid == 2:
            wasm_imports = parse_imports(body)
        elif sid == 7:
            wasm_exports = parse_exports(body)
    assert any(m == "env" and f == "memory" and k == 2 for m, f, k in wasm_imports), (
        f"Linked .wasm must import env.memory, got {wasm_imports}"
    )
    wasm_func_exports = [n for n, k, _ in wasm_exports if k == 0]
    allowed_extra = {"simd_set_scratch"}
    assert set(EXPECTED_EXPORTS).issubset(set(wasm_func_exports)), (
        f"Missing expected exports in linked .wasm: {set(EXPECTED_EXPORTS) - set(wasm_func_exports)}"
    )
    assert set(wasm_func_exports) - set(EXPECTED_EXPORTS) <= allowed_extra, (
        f"Unexpected extra exports in linked .wasm: {set(wasm_func_exports) - set(EXPECTED_EXPORTS)}"
    )

    # Verify llvm-objdump-21 -d disassembly on BOTH .o and linked .wasm
    dis_obj = subprocess.run(
        ["llvm-objdump-21", "-d", str(obj_path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    dis_wasm = subprocess.run(
        ["llvm-objdump-21", "-d", str(linked_wasm_path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    assert "f32x4." in dis_wasm and "f64x2." in dis_wasm and "i32x4." in dis_wasm, (
        "Linked .wasm disassembly missing expected SIMD128 instruction families!"
    )

    # Split disassembly by function and verify each kernel uses SIMD128
    fn_blocks: dict[str, str] = {}
    cur_fn = None
    buf_lines: list[str] = []
    for line in dis_obj.splitlines():
        m = re.match(r"^[0-9a-f]+\s+<([^>]+)>:", line)
        if m:
            if cur_fn is not None:
                fn_blocks[cur_fn] = "\n".join(buf_lines)
            cur_fn = m.group(1)
            buf_lines = []
        elif cur_fn is not None:
            buf_lines.append(line)
    if cur_fn is not None:
        fn_blocks[cur_fn] = "\n".join(buf_lines)

    for fn, reqs in PER_KERNEL_SIMD_EXPECT.items():
        assert fn in fn_blocks, f"Missing function {fn} in llvm-objdump-21 disassembly!"
        body_txt = fn_blocks[fn]
        for token in reqs:
            assert token in body_txt, f"Kernel {fn} missing expected SIMD128 instruction '{token}'!"
        assert "\tcall\t" not in body_txt and "\tcall " not in body_txt, (
            f"Kernel {fn} contains unexpected external/internal call instruction!"
        )

    simd_counts = {
        "f32x4": len(re.findall(r"\bf32x4\.[a-z0-9_]+", dis_obj)),
        "f64x2": len(re.findall(r"\bf64x2\.[a-z0-9_]+", dis_obj)),
        "i32x4": len(re.findall(r"\bi32x4\.[a-z0-9_]+", dis_obj)),
        "i64x2": len(re.findall(r"\bi64x2\.[a-z0-9_]+", dis_obj)),
        "v128": len(re.findall(r"\bv128\.[a-z0-9_]+", dis_obj)),
    }
    print(f"[WASM-SIMD128 AUDIT] PASS ({link_mode})")
    print(f"  .o size: {len(obj_bytes):,} B | linked .wasm size: {len(wasm_bytes):,} B")
    print(f"  data segments: 0 | mutable globals: {len(global_imports)} | external func imports: 0 | custom sections in .o: {custom_names}")
    print(f"  SIMD128 instruction counts: {simd_counts}")
    return len(wasm_bytes), simd_counts


# ---------------------------------------------------------------------------
# Pure-Python Minecraft 26.3 Reference Implementations (exact math)
# ---------------------------------------------------------------------------

def f32(x: float) -> float:
    return struct.unpack("<f", struct.pack("<f", x))[0]


def i32_wrap(x: int) -> int:
    x &= 0xFFFFFFFF
    return x - 0x100000000 if x >= 0x80000000 else x


def u64_wrap(x: int) -> int:
    return x & 0xFFFFFFFFFFFFFFFF


# Exact SimplexNoise.GRADIENT from reference/minecraft-26.2/.../SimplexNoise.java
SIMPLEX_GRADIENT = (
    (1, 1, 0), (-1, 1, 0), (1, -1, 0), (-1, -1, 0),
    (1, 0, 1), (-1, 0, 1), (1, 0, -1), (-1, 0, -1),
    (0, 1, 1), (0, -1, 1), (0, 1, -1), (0, -1, -1),
    (1, 1, 0), (0, -1, 1), (-1, 1, 0), (0, -1, -1),
)


def ref_improved_noise_point(perm: bytes, xo: float, yo: float, zo: float, x: float, y: float, z: float) -> float:
    px = x + xo
    py = y + yo
    pz = z + zo
    xf = math.floor(px)
    yf = math.floor(py)
    zf = math.floor(pz)
    xr = px - xf
    yr = py - yf
    zr = pz - zf

    p = lambda i: perm[i & 0xFF]
    x0 = p(xf)
    x1 = p(xf + 1)
    xy00 = p(x0 + yf)
    xy01 = p(x0 + yf + 1)
    xy10 = p(x1 + yf)
    xy11 = p(x1 + yf + 1)

    def gdot(h: int, dx: float, dy: float, dz: float) -> float:
        g = SIMPLEX_GRADIENT[h & 15]
        return g[0] * dx + g[1] * dy + g[2] * dz

    d000 = gdot(p(xy00 + zf), xr, yr, zr)
    d100 = gdot(p(xy10 + zf), xr - 1.0, yr, zr)
    d010 = gdot(p(xy01 + zf), xr, yr - 1.0, zr)
    d110 = gdot(p(xy11 + zf), xr - 1.0, yr - 1.0, zr)
    d001 = gdot(p(xy00 + zf + 1), xr, yr, zr - 1.0)
    d101 = gdot(p(xy10 + zf + 1), xr - 1.0, yr, zr - 1.0)
    d011 = gdot(p(xy01 + zf + 1), xr, yr - 1.0, zr - 1.0)
    d111 = gdot(p(xy11 + zf + 1), xr - 1.0, yr - 1.0, zr - 1.0)

    smooth = lambda t: t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
    lerp = lambda a, p0, p1: p0 + a * (p1 - p0)
    xa, ya, za = smooth(xr), smooth(yr), smooth(zr)
    ly0 = lerp(ya, lerp(xa, d000, d100), lerp(xa, d010, d110))
    ly1 = lerp(ya, lerp(xa, d001, d101), lerp(xa, d011, d111))
    return lerp(za, ly0, ly1)


def ref_aquifer_voronoi(cells: list[tuple[int, int, int]], queries: list[tuple[int, int, int]]):
    out_idx = []
    out_dist = []
    INT_MAX = 0x7FFFFFFF
    for qx, qy, qz in queries:
        d1 = d2 = d3 = d4 = INT_MAX
        i1 = i2 = i3 = i4 = 0
        for idx, (cx, cy, cz) in enumerate(cells):
            dx = i32_wrap(cx - qx)
            dy = i32_wrap(cy - qy)
            dz = i32_wrap(cz - qz)
            nd = i32_wrap(i32_wrap(dx * dx) + i32_wrap(dy * dy) + i32_wrap(dz * dz))
            if d1 >= nd:
                i4, i3, i2, i1 = i3, i2, i1, idx
                d4, d3, d2, d1 = d3, d2, d1, nd
            elif d2 >= nd:
                i4, i3, i2 = i3, i2, idx
                d4, d3, d2 = d3, d2, nd
            elif d3 >= nd:
                i4, i3 = i3, idx
                d4, d3 = d3, nd
            elif d4 >= nd:
                i4 = idx
                d4 = nd
        out_idx.extend((i1, i2, i3, i4))
        out_dist.extend((d1, d2, d3, d4))
    return out_idx, out_dist


def build_beard_kernel() -> list[float]:
    """Exact Beardifier.BEARD_KERNEL (24x24x24 = 13824 floats)."""
    kernel = [0.0] * 13824
    for zi in range(24):
        dz = zi - 12
        for xi in range(24):
            dx = xi - 12
            for yi in range(24):
                dy = (yi - 12) + 0.5
                dist_sq = dx * dx + dy * dy + dz * dz
                kernel[zi * 24 * 24 + xi * 24 + yi] = f32(math.exp(-dist_sq / 16.0))
    return kernel


def ref_mth_fast_inv_sqrt(x: float) -> float:
    xhalf = 0.5 * x
    i = struct.unpack("<q", struct.pack("<d", x))[0]
    i = 6910469410427058090 - (i >> 1)
    y = struct.unpack("<d", struct.pack("<q", i))[0]
    return y * (1.5 - xhalf * y * y)


def ref_beardifier_volume(rigids, junctions, beard_kernel, min_x, min_y, min_z, nx, ny, nz) -> list[float]:
    out = [0.0] * (nx * ny * nz)

    def bury(dx: float, dy: float, dz: float) -> float:
        dsq = f32(f32(dx * dx) + f32(dy * dy) + f32(dz * dz))
        if dsq >= 36.0:
            return 0.0
        return f32(1.0 - f32(f32(math.sqrt(dsq)) / 6.0))

    def beard(dx: int, dy: int, dz: int, y_to_ground: int) -> float:
        xi, yi, zi = dx + 12, dy + 12, dz + 12
        if 0 <= xi < 24 and 0 <= yi < 24 and 0 <= zi < 24:
            dy_off = f32(y_to_ground + 0.5)
            dsq = f32(f32(f32(dx) * f32(dx)) + f32(dy_off * dy_off) + f32(f32(dz) * f32(dz)))
            inv = f32(ref_mth_fast_inv_sqrt(f32(dsq / 2.0)))
            val = f32(f32(f32(-dy_off) * inv) / 2.0)
            return f32(val * beard_kernel[zi * 576 + xi * 24 + yi])
        return 0.0

    for iz in range(nz):
        bz = min_z + iz
        for ix in range(nx):
            bx = min_x + ix
            for iy in range(ny):
                by = min_y + iy
                nv = 0.0
                for bmin_x, bmin_y, bmin_z, bmax_x, bmax_y, bmax_z, gdelta, adj in rigids:
                    dx = max(0, max(bmin_x - bx, bx - bmax_x))
                    dz = max(0, max(bmin_z - bz, bz - bmax_z))
                    gy = bmin_y + gdelta
                    dy_to_ground = by - gy
                    if adj == 0:
                        continue
                    elif adj == 1:  # BURY
                        nv = f32(nv + bury(f32(dx), f32(dy_to_ground / 2.0), f32(dz)))
                    elif adj == 2:  # BEARD_THIN
                        nv = f32(nv + f32(beard(dx, dy_to_ground, dz, dy_to_ground) * f32(0.8)))
                    elif adj == 3:  # BEARD_BOX
                        dy_box = max(0, max(gy - by, by - bmax_y))
                        nv = f32(nv + f32(beard(dx, dy_box, dz, dy_to_ground) * f32(0.8)))
                    elif adj == 4:  # ENCAPSULATE
                        dy_enc = max(0, max(bmin_y - by, by - bmax_y))
                        nv = f32(nv + f32(bury(f32(dx / 2.0), f32(dy_enc / 2.0), f32(dz / 2.0)) * f32(0.8)))
                for jx, jy, jz in junctions:
                    dx = bx - jx
                    dy = by - jy
                    dz = bz - jz
                    nv = f32(nv + f32(beard(dx, dy, dz, dy) * f32(0.4)))
                out[(iz * nx + ix) * ny + iy] = nv
    return out


def ref_biome_fiddle_batch(seed: int, coords: list[tuple[int, int, int]]) -> list[int]:
    MASK64 = 0xFFFFFFFFFFFFFFFF
    MULT = 6364136223846793005
    INC = 1442695040888963407

    def lcg_next(rval: int, c: int) -> int:
        rval = (rval * ((rval * MULT + INC) & MASK64)) & MASK64
        return (rval + (c & MASK64)) & MASK64

    def get_fiddle(rval: int) -> float:
        # Signed right shift by 24 then floorMod 1024 == unsigned (rval >> 24) & 1023
        uniform = ((rval >> 24) & 1023) / 1024.0
        return (uniform - 0.5) * 0.9

    seed_u = seed & MASK64
    out = []
    for bx, by, bz in coords:
        ax, ay, az = bx - 2, by - 2, bz - 2
        px, py, pz = ax >> 2, ay >> 2, az >> 2
        fx, fy, fz = (ax & 3) / 4.0, (ay & 3) / 4.0, (az & 3) / 4.0
        min_i = 0
        min_d = float("inf")
        for i in range(8):
            xe = (i & 4) == 0
            ye = (i & 2) == 0
            ze = (i & 1) == 0
            cx = px if xe else px + 1
            cy = py if ye else py + 1
            cz = pz if ze else pz + 1
            dx = fx if xe else fx - 1.0
            dy = fy if ye else fy - 1.0
            dz = fz if ze else fz - 1.0
            r = lcg_next(seed_u, cx)
            r = lcg_next(r, cy)
            r = lcg_next(r, cz)
            r = lcg_next(r, cx)
            r = lcg_next(r, cy)
            r = lcg_next(r, cz)
            fid_x = get_fiddle(r)
            r = lcg_next(r, seed_u)
            fid_y = get_fiddle(r)
            r = lcg_next(r, seed_u)
            fid_z = get_fiddle(r)
            d = (dz + fid_z) ** 2 + (dy + fid_y) ** 2 + (dx + fid_x) ** 2
            if min_d > d:
                min_d = d
                min_i = i
        out.extend((
            px if (min_i & 4) == 0 else px + 1,
            py if (min_i & 2) == 0 else py + 1,
            pz if (min_i & 1) == 0 else pz + 1,
        ))
    return out


def ref_cull_aabb_frustum_fog(planes, cam_x, cam_y, cam_z, sph_sq, cyl_sq, mins, maxs) -> list[int]:
    count = len(mins[0])
    words = [0] * ((count + 31) // 32)
    for i in range(count):
        x1 = f32(mins[0][i] - cam_x)
        y1 = f32(mins[1][i] - cam_y)
        z1 = f32(mins[2][i] - cam_z)
        x2 = f32(maxs[0][i] - cam_x)
        y2 = f32(maxs[1][i] - cam_y)
        z2 = f32(maxs[2][i] - cam_z)
        vis = True
        for p in range(6):
            nx, ny, nz, d = planes[p * 4 : p * 4 + 4]
            px = x1 if nx < 0.0 else x2
            py = y1 if ny < 0.0 else y2
            pz = z1 if nz < 0.0 else z2
            dot = f32(f32(f32(nx * px) + f32(ny * py)) + f32(f32(nz * pz) + d))
            if not (dot >= 0.0):
                vis = False
                break
        if vis and (sph_sq > 0.0 or cyl_sq > 0.0):
            dx = max(0.0, max(x1, f32(-x2)))
            dy = max(0.0, max(y1, f32(-y2)))
            dz = max(0.0, max(z1, f32(-z2)))
            hsq = f32(f32(dx * dx) + f32(dz * dz))
            dysq = f32(dy * dy)
            if sph_sq > 0.0 and not (f32(hsq + dysq) <= sph_sq):
                vis = False
            if cyl_sq > 0.0 and not (max(hsq, dysq) <= cyl_sq):
                vis = False
        if vis:
            words[i >> 5] |= 1 << (i & 31)
    return words


def ref_sort_translucent_quads(verts, quad_count, cam_x, cam_y, cam_z):
    dists = []
    for q in range(quad_count):
        b = q * 28
        cx = f32(f32(verts[b + 0] + verts[b + 14]) * 0.5)
        cy = f32(f32(verts[b + 1] + verts[b + 15]) * 0.5)
        cz = f32(f32(verts[b + 2] + verts[b + 16]) * 0.5)
        dx = f32(cx - cam_x)
        dy = f32(cy - cam_y)
        dz = f32(cz - cam_z)
        dists.append(f32(f32(f32(dx * dx) + f32(dy * dy)) + f32(dz * dz)))
    # Python timsort is stable; sorting by (-dist, q) matches Floats.compare(keys[o2], keys[o1])
    order = sorted(range(quad_count), key=lambda q: (-dists[q], q))
    indices = []
    for q in order:
        base = q * 4
        indices.extend((base + 0, base + 1, base + 2, base + 2, base + 3, base + 0))
    return dists, order, indices


def ref_mat4_mul_batch(a, b, count) -> list[float]:
    out = [0.0] * (count * 16)
    for m in range(count):
        off = m * 16
        for col in range(4):
            b0 = b[off + col * 4 + 0]
            b1 = b[off + col * 4 + 1]
            b2 = b[off + col * 4 + 2]
            b3 = b[off + col * 4 + 3]
            for row in range(4):
                s = f32(f32(a[off + 0 + row] * b0) + f32(a[off + 4 + row] * b1))
                s = f32(s + f32(a[off + 8 + row] * b2))
                s = f32(s + f32(a[off + 12 + row] * b3))
                out[off + col * 4 + row] = s
    return out


# ---------------------------------------------------------------------------
# Native x86_64 .so compilation, ctypes bindings, parity tests & benchmarks
# ---------------------------------------------------------------------------

def build_and_load_native(tmp: Path) -> ctypes.CDLL:
    so_path = tmp / "libworldgen_cull_kernels.so"
    subprocess.run(
        [
            "clang-21",
            "-O3",
            "-shared",
            "-fPIC",
            "-Wall",
            "-Wextra",
            "-Werror",
            str(C_SRC),
            "-o",
            str(so_path),
        ],
        check=True,
    )
    lib = ctypes.CDLL(str(so_path))

    c_f32_p = ctypes.POINTER(ctypes.c_float)
    c_i32_p = ctypes.POINTER(ctypes.c_int32)
    c_u32_p = ctypes.POINTER(ctypes.c_uint32)
    c_u8_p = ctypes.POINTER(ctypes.c_uint8)

    lib.simd_improved_noise_batch.argtypes = [
        c_u8_p,
        ctypes.c_double, ctypes.c_double, ctypes.c_double,
        ctypes.c_double, ctypes.c_double, ctypes.c_double,
        ctypes.c_double, ctypes.c_double, ctypes.c_double,
        ctypes.c_int, ctypes.c_int, ctypes.c_int,
        ctypes.c_double, ctypes.c_double,
        c_f32_p, ctypes.c_int,
    ]
    lib.simd_aquifer_voronoi_batch.argtypes = [
        c_i32_p, ctypes.c_int, c_i32_p, ctypes.c_int, c_i32_p, c_i32_p,
    ]
    lib.simd_beardifier_volume.argtypes = [
        c_f32_p, ctypes.c_int, c_f32_p, ctypes.c_int, c_f32_p,
        ctypes.c_int, ctypes.c_int, ctypes.c_int,
        ctypes.c_int, ctypes.c_int, ctypes.c_int,
        c_f32_p,
    ]
    lib.simd_biome_fiddle_batch.argtypes = [
        ctypes.c_int64, c_i32_p, ctypes.c_int, c_i32_p,
    ]
    lib.simd_cull_aabb_frustum_fog.argtypes = [
        c_f32_p,
        ctypes.c_float, ctypes.c_float, ctypes.c_float,
        ctypes.c_float, ctypes.c_float,
        c_f32_p, c_f32_p, c_f32_p, c_f32_p, c_f32_p, c_f32_p,
        ctypes.c_int, c_u32_p,
    ]
    lib.simd_sort_translucent_quads.argtypes = [
        c_f32_p, ctypes.c_int,
        ctypes.c_float, ctypes.c_float, ctypes.c_float,
        c_f32_p, c_u32_p, c_u32_p,
    ]
    lib.simd_mat4_mul_batch.argtypes = [
        c_f32_p, c_f32_p, c_f32_p, ctypes.c_int,
    ]
    return lib


def float_ulp_diff(a: float, b: float) -> int:
    if a == b:
        return 0
    ia = struct.unpack("<i", struct.pack("<f", a))[0]
    ib = struct.unpack("<i", struct.pack("<f", b))[0]
    if ia < 0:
        ia = 0x80000000 - ia
    if ib < 0:
        ib = 0x80000000 - ib
    return abs(ia - ib)


def run_parity_tests(lib: ctypes.CDLL) -> None:
    rng = random.Random(2632026)

    # 1. simd_improved_noise_batch (3D odd/even + accumulate + 2D ny==1 slice)
    perm_list = list(range(256))
    rng.shuffle(perm_list)
    perm_bytes = bytes(perm_list)
    perm_arr = (ctypes.c_uint8 * 256)(*perm_list)

    max_ulp_noise = 0
    max_abs_noise = 0.0
    for nx, ny, nz in ((7, 19, 5), (8, 16, 4), (13, 1, 7), (16, 1, 8)):
        n_total = nx * ny * nz
        out_arr = (ctypes.c_float * n_total)()
        ref = [0.0] * n_total
        for oct_idx, (freq, amp, accum) in enumerate([(0.0625, 1.0, 0), (0.125, 0.5, 1), (0.25, 0.25, 1)]):
            xo, yo, zo = rng.uniform(0, 256), rng.uniform(0, 256), rng.uniform(0, 256)
            x0, y0, z0 = -47.25 + oct_idx, -64.5, 19.75
            dx, dy, dz = 1.0, 1.0, 1.0
            lib.simd_improved_noise_batch(
                perm_arr, xo, yo, zo, x0, y0, z0, dx, dy, dz, nx, ny, nz, freq, amp, out_arr, accum
            )
            for iz in range(nz):
                for ix in range(nx):
                    for iy in range(ny):
                        val = f32(
                            ref_improved_noise_point(
                                perm_bytes, xo, yo, zo,
                                (x0 + ix * dx) * freq,
                                (y0 + iy * dy) * freq,
                                (z0 + iz * dz) * freq,
                            ) * amp
                        )
                        k = (iz * nx + ix) * ny + iy
                        ref[k] = f32(ref[k] + val) if accum else val
        for k in range(n_total):
            ulp = float_ulp_diff(out_arr[k], ref[k])
            max_ulp_noise = max(max_ulp_noise, ulp)
            max_abs_noise = max(max_abs_noise, abs(out_arr[k] - ref[k]))
            assert ulp <= 1, f"ImprovedNoise mismatch at {k}: got={out_arr[k]} ref={ref[k]} ulp={ulp}"
    print(f"[PARITY 1/7] simd_improved_noise_batch   PASS (max_ulp={max_ulp_noise}, max_abs={max_abs_noise:.2e})")

    # 2. simd_aquifer_voronoi_batch (12-cell fast path + ties + general cellCount)
    for cell_count in (12, 1, 3, 7, 15, 29):
        cells = [(rng.randint(-40, 40), rng.randint(-40, 40), rng.randint(-40, 40)) for _ in range(cell_count)]
        # Inject deliberate distance ties around origin
        if cell_count >= 6:
            cells[1] = (3, 4, 0)
            cells[4] = (-3, 4, 0)
            cells[5] = (0, 3, -4)
        queries = [(0, 0, 0)] + [(rng.randint(-30, 30), rng.randint(-30, 30), rng.randint(-30, 30)) for _ in range(63)]
        c_flat = [v for t in cells for v in t]
        q_flat = [v for t in queries for v in t]
        c_arr = (ctypes.c_int32 * len(c_flat))(*c_flat)
        q_arr = (ctypes.c_int32 * len(q_flat))(*q_flat)
        out_i = (ctypes.c_int32 * (len(queries) * 4))()
        out_d = (ctypes.c_int32 * (len(queries) * 4))()
        lib.simd_aquifer_voronoi_batch(c_arr, cell_count, q_arr, len(queries), out_i, out_d)
        ref_i, ref_d = ref_aquifer_voronoi(cells, queries)
        assert list(out_i) == ref_i, f"Aquifer idx mismatch (cellCount={cell_count})"
        assert list(out_d) == ref_d, f"Aquifer dist mismatch (cellCount={cell_count})"
    print("[PARITY 2/7] simd_aquifer_voronoi_batch  PASS (bit-exact across 12-cell fast path, ties & tails)")

    # 3. simd_beardifier_volume (all 5 TerrainAdjustment modes + JigsawJunctions)
    beard_kernel = build_beard_kernel()
    bk_arr = (ctypes.c_float * 13824)(*beard_kernel)
    rigids = [
        # (minX, minY, minZ, maxX, maxY, maxZ, groundDelta, adjOrdinal)
        (-2, 58, -3, 3, 66, 2, 1, 0),    # NONE
        (4, 60, 1, 9, 68, 6, 2, 1),      # BURY
        (-5, 55, 2, 1, 63, 7, 1, 2),     # BEARD_THIN
        (1, 56, -4, 6, 65, 1, 2, 3),     # BEARD_BOX
        (-3, 62, -2, 4, 70, 5, 0, 4),    # ENCAPSULATE
    ]
    junctions = [
        (0, 61, 0),
        (5, 64, 4),
        (-4, 59, -1),
    ]
    r_flat = [float(v) for r in rigids for v in r]
    j_flat = [float(v) for j in junctions for v in j]
    r_arr = (ctypes.c_float * len(r_flat))(*r_flat)
    j_arr = (ctypes.c_float * len(j_flat))(*j_flat)
    min_x, min_y, min_z = -8, 48, -7
    nx, ny, nz = 17, 35, 15
    out_b = (ctypes.c_float * (nx * ny * nz))()
    lib.simd_beardifier_volume(
        r_arr, len(rigids), j_arr, len(junctions), bk_arr, min_x, min_y, min_z, nx, ny, nz, out_b
    )
    ref_b = ref_beardifier_volume(rigids, junctions, beard_kernel, min_x, min_y, min_z, nx, ny, nz)
    max_ulp_b = 0
    max_abs_b = 0.0
    nonzero_b = 0
    for k in range(nx * ny * nz):
        if ref_b[k] != 0.0:
            nonzero_b += 1
        ulp = float_ulp_diff(out_b[k], ref_b[k])
        max_ulp_b = max(max_ulp_b, ulp)
        max_abs_b = max(max_abs_b, abs(out_b[k] - ref_b[k]))
        assert ulp <= 1, f"Beardifier mismatch at {k}: got={out_b[k]} ref={ref_b[k]} ulp={ulp}"
    assert nonzero_b > 1000
    print(f"[PARITY 3/7] simd_beardifier_volume      PASS (max_ulp={max_ulp_b}, max_abs={max_abs_b:.2e}, nonzero={nonzero_b})")

    # 4. simd_biome_fiddle_batch (multiple signed 64-bit seeds + negative/boundary coords)
    coords = [
        (0, 0, 0), (1, 1, 1), (2, 2, 2), (3, 3, 3), (-1, -1, -1), (-2, -2, -2), (-3, -3, -3), (-4, -4, -4),
    ] + [(rng.randint(-5000, 5000), rng.randint(-64, 320), rng.randint(-5000, 5000)) for _ in range(512)]
    c_flat = [v for t in coords for v in t]
    c_arr = (ctypes.c_int32 * len(c_flat))(*c_flat)
    out_q = (ctypes.c_int32 * len(c_flat))()
    for seed in (-7842319481234567891, 0, -1, 0x123456789ABCDEF0, 0x7FFFFFFFFFFFFFFF):
        lib.simd_biome_fiddle_batch(seed, c_arr, len(coords), out_q)
        ref_q = ref_biome_fiddle_batch(seed, coords)
        assert list(out_q) == ref_q, f"BiomeManager fiddle mismatch for seed={seed}"
    print(f"[PARITY 4/7] simd_biome_fiddle_batch     PASS (bit-exact across 5 seeds x {len(coords)} coords)")

    # 5. simd_cull_aabb_frustum_fog (real perspective frustum + sph/cyl fog + odd counts)
    # Construct a realistic 6-plane frustum (looking down -Z, ~90 deg FOV, near=0.05, far=256)
    s2 = f32(math.sqrt(0.5))
    planes = [
        f32(s2),  f32(0.0), f32(-s2), f32(0.0),    # left:   x - z >= 0
        f32(-s2), f32(0.0), f32(-s2), f32(0.0),    # right: -x - z >= 0
        f32(0.0), f32(s2),  f32(-s2), f32(0.0),    # bottom: y - z >= 0
        f32(0.0), f32(-s2), f32(-s2), f32(0.0),    # top:   -y - z >= 0
        f32(0.0), f32(0.0), f32(-1.0), f32(-0.05), # near:  -z >= 0.05
        f32(0.0), f32(0.0), f32(1.0), f32(256.0),  # far:    z >= -256
    ]
    planes_arr = (ctypes.c_float * 24)(*planes)
    cam_x, cam_y, cam_z = f32(12.5), f32(64.25), f32(-33.75)
    for count in (1, 3, 4, 31, 32, 33, 37, 259):
        min_xs, min_ys, min_zs = [], [], []
        max_xs, max_ys, max_zs = [], [], []
        for _ in range(count):
            x0 = f32(cam_x + rng.uniform(-220.0, 220.0))
            y0 = f32(cam_y + rng.uniform(-160.0, 160.0))
            z0 = f32(cam_z + rng.uniform(-300.0, 60.0))
            min_xs.append(x0); min_ys.append(y0); min_zs.append(z0)
            max_xs.append(f32(x0 + 16.0)); max_ys.append(f32(y0 + 16.0)); max_zs.append(f32(z0 + 16.0))
        a_minx = (ctypes.c_float * count)(*min_xs)
        a_miny = (ctypes.c_float * count)(*min_ys)
        a_minz = (ctypes.c_float * count)(*min_zs)
        a_maxx = (ctypes.c_float * count)(*max_xs)
        a_maxy = (ctypes.c_float * count)(*max_ys)
        a_maxz = (ctypes.c_float * count)(*max_zs)
        n_words = (count + 31) // 32
        for sph_sq, cyl_sq in ((0.0, 0.0), (140.0**2, 0.0), (0.0, 120.0**2), (160.0**2, 130.0**2)):
            out_m = (ctypes.c_uint32 * n_words)(*([0xDEADBEEF] * n_words))
            lib.simd_cull_aabb_frustum_fog(
                planes_arr, cam_x, cam_y, cam_z, f32(sph_sq), f32(cyl_sq),
                a_minx, a_miny, a_minz, a_maxx, a_maxy, a_maxz, count, out_m
            )
            ref_m = ref_cull_aabb_frustum_fog(
                planes, cam_x, cam_y, cam_z, f32(sph_sq), f32(cyl_sq),
                (min_xs, min_ys, min_zs), (max_xs, max_ys, max_zs)
            )
            assert list(out_m) == ref_m, f"Cull mask mismatch count={count} sph={sph_sq} cyl={cyl_sq}"
    print("[PARITY 5/7] simd_cull_aabb_frustum_fog  PASS (bit-exact across 6 planes, sph/cyl fog & odd tails)")

    # 6. simd_sort_translucent_quads (insertion + heapsort paths + heavy ties)
    for qcount in (1, 5, 17, 32, 33, 257, 1025):
        verts = [0.0] * (qcount * 28)
        for q in range(qcount):
            # Include quantized coords to create many exact centroid distance ties
            if q % 5 == 0:
                x0, y0, z0 = float((q % 7) - 3), 62.0, float(((q // 5) % 7) - 3)
            else:
                x0, y0, z0 = rng.uniform(-16, 16), rng.uniform(-16, 16), rng.uniform(-16, 16)
            x2, y2, z2 = x0 + 1.0, y0 + (0.0 if q % 3 == 0 else 1.0), z0 + 1.0
            b = q * 28
            verts[b + 0], verts[b + 1], verts[b + 2] = f32(x0), f32(y0), f32(z0)
            verts[b + 14], verts[b + 15], verts[b + 16] = f32(x2), f32(y2), f32(z2)
        v_arr = (ctypes.c_float * len(verts))(*verts)
        s_dist = (ctypes.c_float * qcount)()
        s_ord = (ctypes.c_uint32 * qcount)()
        out_idx = (ctypes.c_uint32 * (qcount * 6))()
        cx, cy, cz = f32(2.25), f32(63.5), f32(-1.75)
        lib.simd_sort_translucent_quads(v_arr, qcount, cx, cy, cz, s_dist, s_ord, out_idx)
        ref_d, ref_o, ref_idx = ref_sort_translucent_quads(verts, qcount, cx, cy, cz)
        for q in range(qcount):
            assert float_ulp_diff(s_dist[q], ref_d[q]) == 0
        assert list(s_ord) == ref_o, f"Quad sort order mismatch (qcount={qcount})"
        assert list(out_idx) == ref_idx, f"Quad sort index mismatch (qcount={qcount})"
    print("[PARITY 6/7] simd_sort_translucent_quads PASS (bit-exact stable order & 6-index expansion, N=1..1025)")

    # 7. simd_mat4_mul_batch (out-of-place + in-place aliasing out==a & out==b)
    mcount = 64
    a_list = [f32(rng.uniform(-10.0, 10.0)) for _ in range(mcount * 16)]
    b_list = [f32(rng.uniform(-10.0, 10.0)) for _ in range(mcount * 16)]
    a_arr = (ctypes.c_float * (mcount * 16))(*a_list)
    b_arr = (ctypes.c_float * (mcount * 16))(*b_list)
    o_arr = (ctypes.c_float * (mcount * 16))()
    lib.simd_mat4_mul_batch(a_arr, b_arr, o_arr, mcount)
    ref_m4 = ref_mat4_mul_batch(a_list, b_list, mcount)
    max_ulp_m4 = 0
    for k in range(mcount * 16):
        ulp = float_ulp_diff(o_arr[k], ref_m4[k])
        max_ulp_m4 = max(max_ulp_m4, ulp)
        assert ulp == 0, f"Mat4 mismatch at {k}: got={o_arr[k]} ref={ref_m4[k]} ulp={ulp}"
    # Aliasing check: out == a and out == b
    a_alias = (ctypes.c_float * (mcount * 16))(*a_list)
    lib.simd_mat4_mul_batch(a_alias, b_arr, a_alias, mcount)
    assert list(a_alias) == ref_m4, "Mat4 in-place aliasing (out==a) failed!"
    b_alias = (ctypes.c_float * (mcount * 16))(*b_list)
    lib.simd_mat4_mul_batch(a_arr, b_alias, b_alias, mcount)
    assert list(b_alias) == ref_m4, "Mat4 in-place aliasing (out==b) failed!"
    print(f"[PARITY 7/7] simd_mat4_mul_batch         PASS (max_ulp={max_ulp_m4}, in-place aliasing verified)")


def run_benchmarks(lib: ctypes.CDLL) -> None:
    rng = random.Random(42)
    print("\n[THROUGHPUT BENCHMARKS - Native x86_64 C/SIMD]")

    # 1. ImprovedNoise: full 16x384x16 chunk volume = 98,304 samples
    perm_arr = (ctypes.c_uint8 * 256)(*range(256))
    n_noise = 16 * 384 * 16
    out_n = (ctypes.c_float * n_noise)()
    iters = 20
    t0 = time.perf_counter()
    for _ in range(iters):
        lib.simd_improved_noise_batch(
            perm_arr, 13.7, 42.3, 91.1, 0.0, -64.0, 0.0, 1.0, 1.0, 1.0, 16, 384, 16, 0.0625, 1.0, out_n, 0
        )
    dt = time.perf_counter() - t0
    print(f"  1. simd_improved_noise_batch   (16x384x16): {dt / iters * 1e3:6.3f} ms/chunk  ({n_noise * iters / dt / 1e6:6.1f} M samples/s)")

    # 2. Aquifer Voronoi: 12-cell stencil x 32,768 blocks
    cells12 = (ctypes.c_int32 * 36)(*[rng.randint(-20, 20) for _ in range(36)])
    n_aq = 32768
    q_aq = (ctypes.c_int32 * (n_aq * 3))(*[rng.randint(-16, 16) for _ in range(n_aq * 3)])
    oi = (ctypes.c_int32 * (n_aq * 4))()
    od = (ctypes.c_int32 * (n_aq * 4))()
    iters = 50
    t0 = time.perf_counter()
    for _ in range(iters):
        lib.simd_aquifer_voronoi_batch(cells12, 12, q_aq, n_aq, oi, od)
    dt = time.perf_counter() - t0
    print(f"  2. simd_aquifer_voronoi_batch  (32K x 12c): {dt / iters * 1e3:6.3f} ms/batch  ({n_aq * iters / dt / 1e6:6.1f} M blocks/s)")

    # 3. Beardifier: 16x64x16 volume, 8 rigids + 4 junctions
    bk_arr = (ctypes.c_float * 13824)(*build_beard_kernel())
    r_flat = []
    for i in range(8):
        r_flat.extend([float(i), 56.0, float(i), float(i + 6), 66.0, float(i + 6), 1.0, float((i % 4) + 1)])
    j_flat = [4.0, 60.0, 4.0, 10.0, 62.0, 8.0, 6.0, 58.0, 12.0, 12.0, 64.0, 4.0]
    r_arr = (ctypes.c_float * len(r_flat))(*r_flat)
    j_arr = (ctypes.c_float * len(j_flat))(*j_flat)
    n_beard = 16 * 64 * 16
    out_b = (ctypes.c_float * n_beard)()
    iters = 50
    t0 = time.perf_counter()
    for _ in range(iters):
        lib.simd_beardifier_volume(r_arr, 8, j_arr, 4, bk_arr, 0, 40, 0, 16, 64, 16, out_b)
    dt = time.perf_counter() - t0
    print(f"  3. simd_beardifier_volume      (16x64x16) : {dt / iters * 1e3:6.3f} ms/vol    ({n_beard * iters / dt / 1e6:6.1f} M voxels/s)")

    # 4. BiomeManager fiddle: 16,384 block queries
    n_bio = 16384
    c_bio = (ctypes.c_int32 * (n_bio * 3))(*[rng.randint(-1000, 1000) for _ in range(n_bio * 3)])
    o_bio = (ctypes.c_int32 * (n_bio * 3))()
    iters = 30
    t0 = time.perf_counter()
    for _ in range(iters):
        lib.simd_biome_fiddle_batch(-1234567890123456789, c_bio, n_bio, o_bio)
    dt = time.perf_counter() - t0
    print(f"  4. simd_biome_fiddle_batch     (16K blks) : {dt / iters * 1e3:6.3f} ms/batch  ({n_bio * iters / dt / 1e6:6.1f} M blocks/s)")

    # 5. Frustum + Fog cull: 4,096 chunk sections
    n_cull = 4096
    s2 = f32(math.sqrt(0.5))
    planes_arr = (ctypes.c_float * 24)(
        s2, 0, -s2, 0, -s2, 0, -s2, 0, 0, s2, -s2, 0, 0, -s2, -s2, 0, 0, 0, -1, -0.05, 0, 0, 1, 256
    )
    minx = (ctypes.c_float * n_cull)(*[rng.uniform(-200, 200) for _ in range(n_cull)])
    miny = (ctypes.c_float * n_cull)(*[rng.uniform(-64, 256) for _ in range(n_cull)])
    minz = (ctypes.c_float * n_cull)(*[rng.uniform(-256, 64) for _ in range(n_cull)])
    maxx = (ctypes.c_float * n_cull)(*[minx[i] + 16.0 for i in range(n_cull)])
    maxy = (ctypes.c_float * n_cull)(*[miny[i] + 16.0 for i in range(n_cull)])
    maxz = (ctypes.c_float * n_cull)(*[minz[i] + 16.0 for i in range(n_cull)])
    mask = (ctypes.c_uint32 * (n_cull // 32))()
    iters = 2000
    t0 = time.perf_counter()
    for _ in range(iters):
        lib.simd_cull_aabb_frustum_fog(planes_arr, 0.0, 64.0, 0.0, 192.0**2, 160.0**2, minx, miny, minz, maxx, maxy, maxz, n_cull, mask)
    dt = time.perf_counter() - t0
    print(f"  5. simd_cull_aabb_frustum_fog  (4K boxes) : {dt / iters * 1e6:6.1f} us/frame  ({n_cull * iters / dt / 1e6:6.1f} M boxes/s)")

    # 6. Translucent quad sort: 4,096 quads
    n_q = 4096
    verts = (ctypes.c_float * (n_q * 28))(*[rng.uniform(-16, 16) for _ in range(n_q * 28)])
    sd = (ctypes.c_float * n_q)()
    so = (ctypes.c_uint32 * n_q)()
    oi_q = (ctypes.c_uint32 * (n_q * 6))()
    iters = 50
    t0 = time.perf_counter()
    for _ in range(iters):
        lib.simd_sort_translucent_quads(verts, n_q, 8.0, 8.0, 8.0, sd, so, oi_q)
    dt = time.perf_counter() - t0
    print(f"  6. simd_sort_translucent_quads (4K quads) : {dt / iters * 1e3:6.3f} ms/sort   ({n_q * iters / dt / 1e6:6.2f} M quads/s)")

    # 7. Mat4 batch multiply: 4,096 transforms
    n_m = 4096
    ma = (ctypes.c_float * (n_m * 16))(*[rng.uniform(-2, 2) for _ in range(n_m * 16)])
    mb = (ctypes.c_float * (n_m * 16))(*[rng.uniform(-2, 2) for _ in range(n_m * 16)])
    mo = (ctypes.c_float * (n_m * 16))()
    iters = 1000
    t0 = time.perf_counter()
    for _ in range(iters):
        lib.simd_mat4_mul_batch(ma, mb, mo, n_m)
    dt = time.perf_counter() - t0
    print(f"  7. simd_mat4_mul_batch         (4K mat4s) : {dt / iters * 1e6:6.1f} us/batch  ({n_m * iters / dt / 1e6:6.1f} M mat4/s)")


def main() -> int:
    assert C_SRC.is_file(), f"Missing {C_SRC}"
    with tempfile.TemporaryDirectory(prefix="simd-kernels-check-") as tmpdir:
        tmp = Path(tmpdir)
        wasm_size, _ = verify_wasm_build(tmp)
        lib = build_and_load_native(tmp)
        run_parity_tests(lib)
        run_benchmarks(lib)
    print(f"\nALL_7_WASM_SIMD_KERNELS_PASS wasm_bytes={wasm_size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
