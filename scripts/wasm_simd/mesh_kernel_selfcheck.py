#!/usr/bin/env python3
"""Compile, link, inspect, parity-verify, and benchmark the WASM-SIMD128 chunk mesher kernel.

Verifies:
  1. Freestanding wasm32 + simd128 compilation with clang-21 (-Wall -Wextra -Werror)
     and zero undefined external function imports.
  2. Single-TU linking via scripts/wasm_link_single.py (--memory-mode export) into a
     compact standalone .wasm module, checking all exported ABI symbols and real
     0xFD SIMD128 + i64.mul codegen via llvm-objdump-21.
  3. 100% BYTE-FOR-BYTE parity between the exact same C source (compiled to native
     x86_64 .so using wasm_simd128_native.h) and target_teavm_263/wasm-gc/minecraft-web-mesh-worker.js
     running in /root/brel/qjs across comprehensive synthetic DEFS/TAGS/JOB suites:
       - Flat & Smooth AO lighting (full + partial faces, all 6 directions, MC-138211 corners)
       - 16x16 shape occlusion culling (v128.andnot + v128.any_true)
       - Skip rules 1..5 including IronBarsBlock (rule=5) & MangroveRootsBlock (rule=4)
       - Single, Weighted (pow2 & non-pow2), and MultiPart models + negative world coords
       - Random offsets, custom model seeds, multi-tint & boxed-in tint skip
       - Fluids (still, flowing all quadrants, falling beside sturdy/ice, waterlogged, overlay,
         tint, custom shape occlusion, real face normals on front & back faces)
       - Shader pack tags (kind=4 on & off)
       - VisGraph BFS (sparse, solid, partitioned multi-component visibility)
       - Decline cases (unknown state, stale epoch, halo<20 with AO, dynamic neighbor, missing extra)
  4. Throughput comparison (us/section) between native C/SIMD and QuickJS worker.
"""

from __future__ import annotations

import ctypes
import json
import math
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent.parent
KERNEL_C = ROOT / "scripts/wasm_simd/mesh_kernel.c"
LINKER_PY = ROOT / "scripts/wasm_link_single.py"
JS_WORKER = ROOT / "target_teavm_263/wasm-gc/minecraft-web-mesh-worker.js"
QJS_BIN = Path("/root/brel/qjs")

# Protocol flags matching minecraft-web-mesh-worker.js / Web263MeshOffload.java
F_AIR = 1 << 0
F_SOLID_RENDER = 1 << 1
F_MODEL = 1 << 2
F_EMISSIVE = 1 << 3
F_FORCE_OPAQUE = 1 << 4
F_LIGHT_PERMEABLE = 1 << 5
F_COLLISION_FULL = 1 << 6
F_HAS_OFFSET = 1 << 7
F_CUSTOM_SEED = 1 << 8
F_LEAVES = 1 << 9
F_SOLID = 1 << 10
F_BLOCKS_FLUID_FLOW = 1 << 11
F_ICE = 1 << 12
F_OVERLAY_NEIGHBOUR = 1 << 13
F_FLUID_SOURCE = 1 << 15
F_FLUID_FALLING = 1 << 16
F_FLUID_TINT = 1 << 17
F_DYNAMIC_SHAPE = 1 << 18

EX_OFFSET = 1
EX_SEED = 2

# Direction ordinals: 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST
DIR_QUADS_UNIT = {
    0: [(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)],  # DOWN
    1: [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)],  # UP
    2: [(1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)],  # NORTH
    3: [(0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)],  # SOUTH
    4: [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)],  # WEST
    5: [(1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)],  # EAST
}


# ---------------------------------------------------------------------------
# Binary blob builders (exact match to Web263MeshOffload.java)
# ---------------------------------------------------------------------------


def make_reset_blob(epoch: int, cutout_leaves: int = 0, state_count: int = 64) -> bytes:
    return struct.pack("<iiii", 3, epoch, cutout_leaves, state_count)


def make_tags_blob(on: int, block_tags: list[int], fluid_tags: list[int]) -> bytes:
    assert len(block_tags) == len(fluid_tags)
    n = len(block_tags)
    out = bytearray(struct.pack("<iiii", 4, on, n, 0))
    if on and n > 0:
        out.extend(struct.pack(f"<{n}i", *block_tags))
        out.extend(struct.pack(f"<{n}i", *fluid_tags))
    return bytes(out)


def pack_quad(
    positions: list[tuple[float, float, float]],
    uvs: list[tuple[float, float]],
    direction: int,
    layer: int = 0,
    tint_index: int = -1,
    shade_override: int = -1,
    emission: int = 0,
    sprite_id: int = 0,
) -> bytes:
    assert len(positions) == 4 and len(uvs) == 4
    vals: list[float] = []
    for x, y, z in positions:
        vals.extend((float(x), float(y), float(z)))
    for u, v in uvs:
        vals.extend((float(u), float(v)))
    return struct.pack("<20f", *vals) + struct.pack(
        "<BBbbBBH",
        direction,
        layer,
        tint_index,
        shade_override,
        emission,
        0,
        sprite_id,
    )


def make_box_quad(
    direction: int,
    x0: float,
    y0: float,
    z0: float,
    x1: float,
    y1: float,
    z1: float,
    layer: int = 0,
    tint_index: int = -1,
    shade_override: int = -1,
    emission: int = 0,
    sprite_id: int = 0,
    u0: float = 0.0,
    v0: float = 0.0,
    u1: float = 0.0625,
    v1: float = 0.0625,
) -> bytes:
    verts = []
    for vx, vy, vz in DIR_QUADS_UNIT[direction]:
        verts.append((x1 if vx else x0, y1 if vy else y0, z1 if vz else z0))
    uvs = [(u0, v0), (u0, v1), (u1, v1), (u1, v0)]
    return pack_quad(verts, uvs, direction, layer, tint_index, shade_override, emission, sprite_id)


class DefsBuilder:
    def __init__(self, epoch: int) -> None:
        self.buf = bytearray(struct.pack("<ii", 1, epoch))

    def add_shape(self, sid: int, rows: list[int]) -> None:
        assert len(rows) == 16
        self.buf.extend(struct.pack("<BH16H", 1, sid, *rows))

    def add_part(self, pid: int, ao: int, buckets: list[list[bytes]]) -> None:
        assert len(buckets) == 7
        self.buf.extend(struct.pack("<BHB", 2, pid, ao))
        for b in buckets:
            self.buf.extend(struct.pack("<H", len(b)))
            for q in b:
                assert len(q) == 88
                self.buf.extend(q)

    def add_model_single(self, mid: int, pid: int) -> None:
        self.buf.extend(struct.pack("<BHBH", 3, mid, 0, pid))

    def add_model_weighted(self, mid: int, entries: list[tuple[int, int]]) -> None:
        total = sum(w for w, _ in entries)
        self.buf.extend(struct.pack("<BHBHi", 3, mid, 1, len(entries), total))
        for w, child in entries:
            self.buf.extend(struct.pack("<iH", w, child))

    def add_model_multipart(self, mid: int, children: list[int]) -> None:
        self.buf.extend(struct.pack("<BHBH", 3, mid, 2, len(children)))
        for child in children:
            self.buf.extend(struct.pack("<H", child))

    def add_fluid_model(
        self,
        fid: int,
        layer: int,
        overlay: int,
        tint: int,
        sprites: list[tuple[float, float, float, float, int]],
    ) -> None:
        assert len(sprites) == 3
        self.buf.extend(struct.pack("<BHBBB", 4, fid, layer, overlay, tint))
        for u0, u1, v0, v1, sid in sprites:
            self.buf.extend(struct.pack("<4fH", u0, u1, v0, v1, sid))

    def add_state(
        self,
        sid: int,
        flags: int,
        need: int = 0,
        emission: int = 0,
        shade: float = 1.0,
        skip_rule: int = 0,
        skip_key: int = 0,
        block_id: int = 0,
        occ: tuple[int, int, int, int, int, int] = (0, 0, 0, 0, 0, 0),
        tints: int = 0,
        model: int = 65535,
        fluid_group: int = 0,
        fluid_model: int = 65535,
        amount: int = 0,
        own_height: float = 0.0,
        sturdy: int = 0,
        extra: int = 0,
        name: str = "test:block",
    ) -> None:
        nb = name.encode("utf-8")
        self.buf.extend(
            struct.pack(
                "<BHiiBfBHH6HBHBHBfBBH",
                5,
                sid,
                flags,
                need,
                emission,
                shade,
                skip_rule,
                skip_key,
                block_id,
                *occ,
                tints,
                model,
                fluid_group,
                fluid_model,
                amount,
                own_height,
                sturdy,
                extra,
                len(nb),
            )
        )
        self.buf.extend(nb)

    def build(self) -> bytes:
        return bytes(self.buf)


def make_job_blob(
    job_id: int,
    epoch: int,
    ao: bool,
    halo: int,
    origin: tuple[int, int, int],
    states: list[int],
    lights: list[int],
    extras: list[tuple[int, int, tuple[float, float, float] | None, int | None, list[int]]] | None = None,
    cardinal: tuple[float, float, float, float, float, float] = (0.5, 1.0, 0.8, 0.8, 0.6, 0.6),
) -> bytes:
    cells = halo * halo * halo
    assert len(states) == cells and len(lights) == cells
    ex_buf = bytearray()
    if extras:
        for cell_idx, ex_flags, offset, seed64, colors in extras:
            ex_buf.extend(struct.pack("<HBB", cell_idx, ex_flags, len(colors)))
            if ex_flags & EX_OFFSET:
                assert offset is not None
                ex_buf.extend(struct.pack("<3f", *offset))
            if ex_flags & EX_SEED:
                assert seed64 is not None
                ex_buf.extend(struct.pack("<Q", seed64 & 0xFFFFFFFFFFFFFFFF))
            for col in colors:
                ex_buf.extend(struct.pack("<i", col))
    header = struct.pack(
        "<iiii6fiiiiii",
        2,
        job_id,
        epoch,
        1 if ao else 0,
        *cardinal,
        origin[0],
        origin[1],
        origin[2],
        len(ex_buf),
        halo,
        0,
    )
    assert len(header) == 64
    return header + struct.pack(f"<{cells}H", *states) + bytes(lights) + bytes(ex_buf)


# ---------------------------------------------------------------------------
# Synthetic Palette & World Scenarios
# ---------------------------------------------------------------------------


def build_test_defs(epoch: int) -> bytes:
    d = DefsBuilder(epoch)

    # Custom 16x16 occlusion shapes (id >= 2)
    # 2: bottom half-slab (rows 0..7 full, 8..15 empty)
    d.add_shape(2, [0xFFFF] * 8 + [0x0000] * 8)
    # 3: top half-slab (rows 0..7 empty, 8..15 full)
    d.add_shape(3, [0x0000] * 8 + [0xFFFF] * 8)
    # 4: stair L-shape (bottom full + left 8 columns of top half)
    d.add_shape(4, [0xFFFF] * 8 + [0x00FF] * 8)
    # 5: custom full shape (all 0xFFFF, tests non-id-1 full occlusion)
    d.add_shape(5, [0xFFFF] * 16)

    # Parts
    # Part 0: full solid stone cube (ao=1, layer=0, 6 culled faces)
    d.add_part(0, 1, [[make_box_quad(dir_, 0, 0, 0, 1, 1, 1, layer=0)] for dir_ in range(6)] + [[]])

    # Part 1: grass cube (ao=1, top face tinted tintIndex=0, side/bottom untinted, shade override on north)
    d.add_part(
        1,
        1,
        [
            [make_box_quad(0, 0, 0, 0, 1, 1, 1, layer=0, tint_index=-1)],
            [make_box_quad(1, 0, 0, 0, 1, 1, 1, layer=0, tint_index=0, u0=0.1, v0=0.1, u1=0.2, v1=0.2)],
            [make_box_quad(2, 0, 0, 0, 1, 1, 1, layer=0, tint_index=-1, shade_override=1)],
            [make_box_quad(3, 0, 0, 0, 1, 1, 1, layer=0, tint_index=-1)],
            [make_box_quad(4, 0, 0, 0, 1, 1, 1, layer=0, tint_index=-1)],
            [make_box_quad(5, 0, 0, 0, 1, 1, 1, layer=0, tint_index=-1)],
            [],
        ],
    )

    # Part 2: cutout leaves cube (ao=1, layer=1 cutout, tinted tintIndex=0, animated sprite 3)
    d.add_part(
        2,
        1,
        [[make_box_quad(dir_, 0, 0, 0, 1, 1, 1, layer=1, tint_index=0, sprite_id=3)] for dir_ in range(6)] + [[]],
    )

    # Part 3: translucent glass cube (ao=0, layer=2)
    d.add_part(3, 0, [[make_box_quad(dir_, 0, 0, 0, 1, 1, 1, layer=2)] for dir_ in range(6)] + [[]])

    # Part 4: bottom half-slab (ao=1, partial faces on N/S/W/E + unculled top at y=0.5, full bottom)
    d.add_part(
        4,
        1,
        [
            [make_box_quad(0, 0, 0, 0, 1, 0.5, 1, layer=0)],
            [],
            [make_box_quad(2, 0, 0, 0, 1, 0.5, 1, layer=0)],
            [make_box_quad(3, 0, 0, 0, 1, 0.5, 1, layer=0)],
            [make_box_quad(4, 0, 0, 0, 1, 0.5, 1, layer=0)],
            [make_box_quad(5, 0, 0, 0, 1, 0.5, 1, layer=0)],
            [make_box_quad(1, 0, 0, 0, 1, 0.5, 1, layer=0)],
        ],
    )

    # Part 5: top half-slab (ao=1)
    d.add_part(
        5,
        1,
        [
            [],
            [make_box_quad(1, 0, 0.5, 0, 1, 1, 1, layer=0)],
            [make_box_quad(2, 0, 0.5, 0, 1, 1, 1, layer=0)],
            [make_box_quad(3, 0, 0.5, 0, 1, 1, 1, layer=0)],
            [make_box_quad(4, 0, 0.5, 0, 1, 1, 1, layer=0)],
            [make_box_quad(5, 0, 0.5, 0, 1, 1, 1, layer=0)],
            [make_box_quad(0, 0, 0.5, 0, 1, 1, 1, layer=0)],
        ],
    )

    # Part 6: stair / partial multi-quad box (exercises QFLAG_PARTIAL on all 6 dirs + per-quad emission + sprite 9)
    d.add_part(
        6,
        1,
        [
            [make_box_quad(0, 0.125, 0.0, 0.125, 0.875, 0.5, 0.875, layer=0)],
            [make_box_quad(1, 0.125, 0.5, 0.125, 0.875, 1.0, 0.875, layer=0, emission=11, sprite_id=9)],
            [make_box_quad(2, 0.0, 0.0, 0.0, 1.0, 0.5, 1.0, layer=0), make_box_quad(2, 0.0, 0.5, 0.0, 0.5, 1.0, 1.0, layer=0)],
            [make_box_quad(3, 0.125, 0.125, 0.125, 0.875, 0.875, 1.0, layer=0)],
            [make_box_quad(4, 0.0, 0.125, 0.125, 0.875, 0.875, 0.875, layer=0)],
            [make_box_quad(5, 0.125, 0.125, 0.125, 1.0, 0.875, 0.875, layer=0)],
            [make_box_quad(1, 0.25, 0.25, 0.25, 0.75, 0.75, 0.75, layer=1, tint_index=1, sprite_id=5)],
        ],
    )

    # Part 7: iron bars slim post (culled on all 6 faces, partial bounds, cutout layer 1)
    d.add_part(
        7,
        1,
        [
            [make_box_quad(dir_, 0.4375, 0.0, 0.4375, 0.5625, 1.0, 0.5625, layer=1)]
            for dir_ in range(6)
        ]
        + [[]],
    )

    # Part 8: crossed plant / offset flower with 2 tints (tintIndex 0, 1, and out-of-range 2) + sprite 17
    d.add_part(
        8,
        0,
        [[] for _ in range(6)]
        + [
            [
                make_box_quad(2, 0.15, 0.0, 0.5, 0.85, 0.8, 0.5, layer=1, tint_index=0, sprite_id=17),
                make_box_quad(4, 0.5, 0.0, 0.15, 0.5, 0.8, 0.85, layer=1, tint_index=1, emission=7),
                make_box_quad(1, 0.2, 0.8, 0.2, 0.8, 0.8, 0.8, layer=1, tint_index=2),
            ]
        ],
    )

    # Part 9: alternate variant cube (distinct UVs for weighted model testing)
    d.add_part(
        9,
        1,
        [
            [make_box_quad(dir_, 0, 0, 0, 1, 1, 1, layer=0, u0=0.25, v0=0.25, u1=0.5, v1=0.5)]
            for dir_ in range(6)
        ]
        + [[]],
    )

    # Part 10: third variant cube (distinct UVs + animated sprite 21)
    d.add_part(
        10,
        1,
        [
            [make_box_quad(dir_, 0, 0, 0, 1, 1, 1, layer=0, u0=0.5, v0=0.5, u1=0.75, v1=0.75, sprite_id=21)]
            for dir_ in range(6)
        ]
        + [[]],
    )

    # Models
    d.add_model_single(0, 0)  # stone
    d.add_model_single(1, 1)  # grass
    d.add_model_single(2, 2)  # leaves
    d.add_model_single(3, 3)  # glass
    d.add_model_single(4, 4)  # bottom slab
    d.add_model_single(5, 5)  # top slab
    d.add_model_single(6, 6)  # stair/partial
    d.add_model_single(7, 7)  # iron bars
    d.add_model_single(8, 8)  # flower
    d.add_model_single(9, 9)  # variant B
    d.add_model_single(10, 10)  # variant C
    # Weighted pow2 (total = 4)
    d.add_model_weighted(11, [(1, 0), (2, 9), (1, 10)])
    # Weighted non-pow2 (total = 7)
    d.add_model_weighted(12, [(2, 0), (3, 9), (2, 10)])
    # MultiPart combining single + weighted non-pow2 (tests nextLong + sub-seed reset!)
    d.add_model_multipart(13, [7, 12])

    # Fluid models
    # 0: water (translucent layer=2, overlay=1, tint=1, animated sprites 11, 12, 13)
    d.add_fluid_model(
        0,
        layer=2,
        overlay=1,
        tint=1,
        sprites=[
            (0.0, 0.0625, 0.0, 0.0625, 11),
            (0.0625, 0.1875, 0.0, 0.125, 12),
            (0.1875, 0.25, 0.0, 0.0625, 13),
        ],
    )
    # 1: lava (solid layer=0, overlay=0, tint=0, animated sprites 14, 15, 0)
    d.add_fluid_model(
        1,
        layer=0,
        overlay=0,
        tint=0,
        sprites=[
            (0.25, 0.3125, 0.0, 0.0625, 14),
            (0.3125, 0.4375, 0.0, 0.125, 15),
            (0.25, 0.3125, 0.0, 0.0625, 0),
        ],
    )

    # States
    # 0: air
    d.add_state(0, F_AIR | F_LIGHT_PERMEABLE, shade=1.0, block_id=0, name="minecraft:air")
    # 1: stone (full cube, solid render, shade 0.2)
    d.add_state(
        1,
        F_SOLID_RENDER | F_MODEL | F_COLLISION_FULL | F_SOLID | F_BLOCKS_FLUID_FLOW,
        shade=0.2,
        block_id=1,
        occ=(1, 1, 1, 1, 1, 1),
        model=0,
        sturdy=0x3F,
        name="minecraft:stone",
    )
    # 2: glowstone (emissive=1, emission=15)
    d.add_state(
        2,
        F_SOLID_RENDER | F_MODEL | F_EMISSIVE | F_COLLISION_FULL | F_SOLID | F_BLOCKS_FLUID_FLOW,
        emission=15,
        shade=1.0,
        block_id=2,
        occ=(1, 1, 1, 1, 1, 1),
        model=0,
        sturdy=0x3F,
        name="minecraft:glowstone",
    )
    # 3: grass (tinted=1)
    d.add_state(
        3,
        F_SOLID_RENDER | F_MODEL | F_COLLISION_FULL | F_SOLID | F_BLOCKS_FLUID_FLOW,
        shade=0.2,
        block_id=3,
        occ=(1, 1, 1, 1, 1, 1),
        tints=1,
        model=1,
        sturdy=0x3F,
        name="minecraft:grass_block",
    )
    # 4: leaves (F_LEAVES | F_FORCE_OPAQUE | F_OVERLAY_NEIGHBOUR, skip_rule=2, tints=1)
    d.add_state(
        4,
        F_MODEL | F_FORCE_OPAQUE | F_LEAVES | F_SOLID | F_OVERLAY_NEIGHBOUR | F_LIGHT_PERMEABLE,
        shade=0.8,
        skip_rule=2,
        block_id=4,
        occ=(0, 0, 0, 0, 0, 0),
        tints=1,
        model=2,
        name="minecraft:oak_leaves",
    )
    # 5: glass (skip_rule=1, skip_key=5, F_OVERLAY_NEIGHBOUR, translucent)
    d.add_state(
        5,
        F_MODEL | F_OVERLAY_NEIGHBOUR | F_LIGHT_PERMEABLE | F_SOLID | F_BLOCKS_FLUID_FLOW,
        shade=1.0,
        skip_rule=1,
        skip_key=5,
        block_id=5,
        occ=(0, 0, 0, 0, 0, 0),
        model=3,
        sturdy=0x3F,
        name="minecraft:glass",
    )
    # 6: mangrove roots (skip_rule=4, skip_key=6 -> culls only dir < 2)
    d.add_state(
        6,
        F_MODEL | F_LIGHT_PERMEABLE | F_SOLID,
        shade=0.8,
        skip_rule=4,
        skip_key=6,
        block_id=6,
        occ=(0, 0, 0, 0, 0, 0),
        model=0,
        name="minecraft:mangrove_roots",
    )
    # 7: iron bars variant A (skip_rule=5, block_id=7, skip_key=0b0011)
    d.add_state(
        7,
        F_MODEL | F_LIGHT_PERMEABLE | F_SOLID,
        shade=1.0,
        skip_rule=5,
        skip_key=0b0011,
        block_id=7,
        occ=(0, 0, 0, 0, 0, 0),
        model=7,
        name="minecraft:iron_bars[n=t,s=t]",
    )
    # 8: iron bars variant B (skip_rule=5, same block_id=7, different skip_key=0b1100)
    d.add_state(
        8,
        F_MODEL | F_LIGHT_PERMEABLE | F_SOLID,
        shade=1.0,
        skip_rule=5,
        skip_key=0b1100,
        block_id=7,
        occ=(0, 0, 0, 0, 0, 0),
        model=7,
        name="minecraft:iron_bars[w=t,e=t]",
    )
    # 9: bottom slab (occ: down=1, up=0, sides=shape 2)
    d.add_state(
        9,
        F_MODEL | F_LIGHT_PERMEABLE | F_SOLID | F_BLOCKS_FLUID_FLOW,
        shade=0.8,
        block_id=9,
        occ=(1, 0, 2, 2, 2, 2),
        model=4,
        sturdy=1,  # down sturdy
        name="minecraft:stone_slab[bottom]",
    )
    # 10: top slab (occ: down=0, up=1, sides=shape 3)
    d.add_state(
        10,
        F_MODEL | F_LIGHT_PERMEABLE | F_SOLID | F_BLOCKS_FLUID_FLOW,
        shade=0.8,
        block_id=9,
        occ=(0, 1, 3, 3, 3, 3),
        model=5,
        sturdy=2,  # up sturdy
        name="minecraft:stone_slab[top]",
    )
    # 11: stair/partial block (occ: down=5 custom full, sides=shape 4 L-shape, tints=2)
    d.add_state(
        11,
        F_MODEL | F_LIGHT_PERMEABLE | F_SOLID | F_BLOCKS_FLUID_FLOW,
        shade=0.75,
        block_id=11,
        occ=(5, 0, 4, 4, 4, 4),
        tints=2,
        model=6,
        name="minecraft:oak_stairs",
    )
    # 12: weighted pow2 block
    d.add_state(
        12,
        F_SOLID_RENDER | F_MODEL | F_COLLISION_FULL | F_SOLID | F_BLOCKS_FLUID_FLOW,
        shade=0.2,
        block_id=12,
        occ=(1, 1, 1, 1, 1, 1),
        model=11,
        sturdy=0x3F,
        name="minecraft:weighted_pow2",
    )
    # 13: multipart + custom seed + offset + 2 tints
    d.add_state(
        13,
        F_MODEL | F_HAS_OFFSET | F_CUSTOM_SEED | F_LIGHT_PERMEABLE,
        shade=1.0,
        block_id=13,
        occ=(0, 0, 0, 0, 0, 0),
        tints=2,
        model=13,
        extra=EX_OFFSET | EX_SEED,
        name="minecraft:multipart_seeded",
    )
    # 14: offset flower with 2 tints
    d.add_state(
        14,
        F_MODEL | F_HAS_OFFSET | F_LIGHT_PERMEABLE,
        shade=1.0,
        block_id=14,
        occ=(0, 0, 0, 0, 0, 0),
        tints=2,
        model=8,
        extra=EX_OFFSET,
        name="minecraft:tall_flower",
    )
    # 15: water source (fluid_group=1, fluid_model=0, own_height=8/9)
    d.add_state(
        15,
        F_LIGHT_PERMEABLE | F_FLUID_SOURCE | F_FLUID_TINT,
        shade=1.0,
        skip_rule=3,
        block_id=15,
        fluid_group=1,
        fluid_model=0,
        amount=8,
        own_height=8.0 / 9.0,
        name="minecraft:water[level=0]",
    )
    # 16: water flowing mid (own_height=5/9)
    d.add_state(
        16,
        F_LIGHT_PERMEABLE | F_FLUID_TINT,
        shade=1.0,
        skip_rule=3,
        block_id=15,
        fluid_group=1,
        fluid_model=0,
        amount=5,
        own_height=5.0 / 9.0,
        name="minecraft:water[level=3]",
    )
    # 17: water flowing low (own_height=2/9)
    d.add_state(
        17,
        F_LIGHT_PERMEABLE | F_FLUID_TINT,
        shade=1.0,
        skip_rule=3,
        block_id=15,
        fluid_group=1,
        fluid_model=0,
        amount=2,
        own_height=2.0 / 9.0,
        name="minecraft:water[level=6]",
    )
    # 18: falling water (F_FLUID_FALLING, own_height=8/9)
    d.add_state(
        18,
        F_LIGHT_PERMEABLE | F_FLUID_FALLING | F_FLUID_TINT,
        shade=1.0,
        skip_rule=3,
        block_id=15,
        fluid_group=1,
        fluid_model=0,
        amount=8,
        own_height=8.0 / 9.0,
        name="minecraft:water[level=8]",
    )
    # 19: waterlogged bottom slab (BOTH F_MODEL AND fluid_group=1 + F_FLUID_TINT)
    d.add_state(
        19,
        F_MODEL | F_LIGHT_PERMEABLE | F_SOLID | F_FLUID_SOURCE | F_FLUID_TINT,
        shade=0.8,
        block_id=9,
        occ=(1, 0, 2, 2, 2, 2),
        model=4,
        fluid_group=1,
        fluid_model=0,
        amount=8,
        own_height=8.0 / 9.0,
        sturdy=1,
        name="minecraft:stone_slab[waterlogged=true]",
    )
    # 20: lava source (fluid_group=2, fluid_model=1, emission=15, no tint, no overlay)
    d.add_state(
        20,
        F_LIGHT_PERMEABLE | F_FLUID_SOURCE,
        emission=15,
        shade=1.0,
        skip_rule=3,
        block_id=20,
        fluid_group=2,
        fluid_model=1,
        amount=8,
        own_height=8.0 / 9.0,
        name="minecraft:lava[level=0]",
    )
    # 21: ice block (F_ICE, sturdy, translucent glass-like)
    d.add_state(
        21,
        F_MODEL | F_ICE | F_SOLID | F_BLOCKS_FLUID_FLOW | F_OVERLAY_NEIGHBOUR | F_LIGHT_PERMEABLE,
        shade=1.0,
        skip_rule=1,
        skip_key=21,
        block_id=21,
        occ=(0, 0, 0, 0, 0, 0),
        model=3,
        sturdy=0x3F,
        name="minecraft:ice",
    )
    # 22: dynamic shape block (for decline test when beside falling fluid)
    d.add_state(
        22,
        F_MODEL | F_LIGHT_PERMEABLE | F_DYNAMIC_SHAPE,
        shade=1.0,
        block_id=22,
        occ=(0, 0, 0, 0, 0, 0),
        model=0,
        name="minecraft:bamboo",
    )
    # 23: opaque dark block (shade=0.2, light permeable=0, for VisGraph wall)
    d.add_state(
        23,
        F_SOLID_RENDER | F_MODEL | F_COLLISION_FULL | F_SOLID | F_BLOCKS_FLUID_FLOW,
        shade=0.2,
        block_id=23,
        occ=(1, 1, 1, 1, 1, 1),
        model=0,
        sturdy=0x3F,
        name="minecraft:deepslate",
    )
    # 24: non-power-of-2 weighted block (mthSeed)
    d.add_state(
        24,
        F_SOLID_RENDER | F_MODEL | F_COLLISION_FULL | F_SOLID | F_BLOCKS_FLUID_FLOW,
        shade=0.2,
        block_id=24,
        occ=(1, 1, 1, 1, 1, 1),
        model=12,
        sturdy=0x3F,
        name="minecraft:weighted_nonpow2",
    )
    return d.build()


def build_tags_on() -> bytes:
    # Pack valid ShaderBlockIds bit patterns: bits 11..15 (low 5 bits) + bits 24..31 (high 8 bits)
    n = 32
    blk = [0] * n
    fld = [0] * n
    for sid in range(n):
        idx = (sid * 7 + 3) & 0x1FFF
        blk[sid] = ((idx & 0x1F) << 11) | (((idx >> 5) & 0xFF) << 24)
        fidx = (sid * 11 + 5) & 0x1FFF
        fld[sid] = ((fidx & 0x1F) << 11) | (((fidx >> 5) & 0xFF) << 24)
    return make_tags_blob(1, blk, fld)


def make_complex_section_job(
    job_id: int,
    epoch: int,
    ao: bool,
    halo: int,
    origin: tuple[int, int, int] = (-128, -64, -256),
    partition_vis: bool = False,
) -> bytes:
    pad = (halo - 16) >> 1
    cells = halo * halo * halo
    states = [0] * cells
    lights = [0] * cells

    def idx(x: int, y: int, z: int) -> int:
        return ((y + pad) * halo + (z + pad)) * halo + (x + pad)

    # Populate varied sky/block light across entire halo (including 0, low <= 2, high 15)
    for hy in range(-pad, 16 + pad):
        for hz in range(-pad, 16 + pad):
            for hx in range(-pad, 16 + pad):
                sky = (hx * 3 + hy * 5 + hz * 7) & 15
                blk = (hx * 2 + hy * 3 + hz) & 15
                # Include explicit zero-light cells to hit smoothBlend n==0 and sky==0 branches
                if (hx + hz) % 7 == 0 and hy < 6:
                    sky = 0
                if hx % 5 == 0 and hz % 5 == 0 and hy == 2:
                    sky = 0
                    blk = 0
                lights[idx(hx, hy, hz)] = (sky << 4) | blk

    # Base terrain floor at y=0..2 (stone + weighted + grass)
    for z in range(-pad, 16 + pad):
        for x in range(-pad, 16 + pad):
            states[idx(x, 0, z)] = 1
            states[idx(x, 1, z)] = 12 if ((x + z) & 1) == 0 else 24
            if (x + z) % 3 == 0:
                states[idx(x, 2, z)] = 3  # grass (some boxed in under y=3!)
            else:
                states[idx(x, 2, z)] = 1

    # Add a 3x3 solid stone cap over grass at (2..4, 2, 2..4) so (3, 2, 3) is completely boxed in!
    for z in range(2, 5):
        for x in range(2, 5):
            states[idx(x, 3, z)] = 1
    states[idx(3, 2, 3)] = 3  # boxed-in grass with NO extra record

    # Glowstone light sources
    states[idx(1, 3, 1)] = 2
    states[idx(8, 5, 8)] = 2

    # Leaves cluster (skip_rule=2 + forceOpaque)
    for x in range(1, 4):
        for z in range(6, 9):
            states[idx(x, 4, z)] = 4
            states[idx(x, 5, z)] = 4

    # Glass cluster (skip_rule=1) & mangrove roots (skip_rule=4)
    states[idx(5, 3, 2)] = 5
    states[idx(6, 3, 2)] = 5
    states[idx(5, 4, 2)] = 5
    states[idx(5, 3, 4)] = 6
    states[idx(5, 4, 4)] = 6  # stacked vertically -> culled between y=3 and y=4
    states[idx(6, 3, 4)] = 6  # adjacent horizontally -> NOT culled by rule 4!

    # IronBarsBlock (skip_rule=5):
    states[idx(2, 4, 11)] = 7  # key 0b0011
    states[idx(2, 5, 11)] = 7  # vertical same key -> culled
    states[idx(2, 6, 11)] = 8  # vertical diff key -> NOT culled!
    states[idx(3, 4, 11)] = 8  # horizontal diff key -> CULLED (dir >= 2)!

    # Shape occlusion matrix (slabs & stairs)
    states[idx(8, 3, 2)] = 9   # bottom slab
    states[idx(9, 3, 2)] = 9   # bottom slab next to bottom slab -> culled
    states[idx(10, 3, 2)] = 10 # top slab next to bottom slab -> NOT culled
    states[idx(11, 3, 2)] = 11 # stair L-shape next to top slab & bottom slab
    states[idx(12, 3, 2)] = 9  # bottom slab next to stair L-shape -> bottom half occluded!
    states[idx(11, 4, 2)] = 11 # stacked stair (down=shape 5 custom full)
    states[idx(8, 3, 3)] = 1   # stone next to bottom slab (partial occlusion -> NOT culled)

    # MultiPart + custom seed + random offset + tall flower
    states[idx(4, 3, 8)] = 13
    states[idx(5, 3, 8)] = 13
    states[idx(6, 3, 8)] = 14
    states[idx(7, 3, 8)] = 14

    # Fluid pool & flowing stream in all 4 directions + waterfall + waterlogged + ice + glass + lava
    # Water basin at y=4, x=9..14, z=7..13
    states[idx(11, 4, 10)] = 15  # source
    states[idx(11, 5, 10)] = 15  # stacked source (heightSelf >= 1)
    states[idx(12, 4, 10)] = 16  # flow east (+X)
    states[idx(13, 4, 10)] = 17  # flow east lower
    states[idx(10, 4, 10)] = 16  # flow west (-X)
    states[idx(9, 4, 10)] = 17
    states[idx(11, 4, 11)] = 16  # flow south (+Z)
    states[idx(11, 4, 12)] = 17
    states[idx(11, 4, 9)] = 16   # flow north (-Z)
    states[idx(11, 4, 8)] = 17
    states[idx(12, 4, 11)] = 16  # diagonal flow (+X, +Z)
    states[idx(10, 4, 9)] = 17   # diagonal flow (-X, -Z)
    # Drop-off step (neighbor air with fluid below -> distance = own - (nh - MAX_FLUID_HEIGHT))
    states[idx(14, 3, 10)] = 16
    # Falling water beside sturdy stone and beside ice
    states[idx(13, 5, 12)] = 18
    states[idx(14, 5, 12)] = 1   # sturdy stone next to falling water
    states[idx(13, 5, 13)] = 18
    states[idx(14, 5, 13)] = 21  # ice next to falling water (ignored by fluidSolidFace!)
    # Water next to glass (F_OVERLAY_NEIGHBOUR) & slab (shape 2 fluid occlusion)
    states[idx(10, 4, 11)] = 5   # glass next to water -> overlay sprite & back=false!
    states[idx(10, 4, 12)] = 9   # bottom slab next to water -> partial fluid occlusion
    # Waterlogged bottom slab
    states[idx(12, 4, 9)] = 19
    # Lava pool (group 2, no tint, no overlay)
    states[idx(2, 3, 13)] = 20
    states[idx(3, 3, 13)] = 20
    states[idx(2, 4, 13)] = 1    # solid cap above lava

    if partition_vis:
        # Build a full opaque wall at x=7 across y=0..15, z=0..15 (>= 256 opaque blocks)
        # AND a sealed hollow room at x=10..13, y=9..12, z=2..5 so VisGraph BFS does real multi-component work!
        for y in range(16):
            for z in range(16):
                states[idx(7, y, z)] = 23
        for y in range(9, 13):
            for z in range(2, 6):
                for x in range(10, 14):
                    if x in (10, 13) or y in (9, 12) or z in (2, 5):
                        states[idx(x, y, z)] = 23
                    else:
                        states[idx(x, y, z)] = 0

    # Build extras for all non-boxed-in cells that need tints / offset / seed
    extras = []
    for y in range(16):
        for z in range(16):
            for x in range(16):
                sid = states[idx(x, y, z)]
                if x == 3 and y == 2 and z == 3 and sid == 3:
                    continue  # intentionally boxed in, no extra!
                cell_key = x | (z << 4) | (y << 8)
                if sid == 3:  # grass: 1 tint
                    col = (0xFF << 24) | (((90 + x * 7) & 255) << 16) | (((180 + z * 3) & 255) << 8) | 64
                    extras.append((cell_key, 0, None, None, [ctypes.c_int32(col).value]))
                elif sid == 4:  # leaves: 1 tint
                    col = (0xFF << 24) | (68 << 16) | (165 << 8) | 52
                    extras.append((cell_key, 0, None, None, [ctypes.c_int32(col).value]))
                elif sid == 11:  # stair: 2 tints
                    c0 = ctypes.c_int32((0xFF << 24) | (200 << 16) | (140 << 8) | 90).value
                    c1 = ctypes.c_int32((0xFF << 24) | (90 << 16) | (210 << 8) | 130).value
                    extras.append((cell_key, 0, None, None, [c0, c1]))
                elif sid == 13:  # multipart + offset + custom seed + 2 tints
                    off = (0.125 * (x - 4) - 0.0625, 0.0, -0.1875 + 0.03125 * z)
                    seed64 = 0x9E3779B97F4A7C15 ^ (x * 1315423911 + z * 2654435761)
                    c0 = ctypes.c_int32((0xFF << 24) | (240 << 16) | (80 << 8) | 120).value
                    c1 = ctypes.c_int32((0xFF << 24) | (80 << 16) | (240 << 8) | 180).value
                    extras.append((cell_key, EX_OFFSET | EX_SEED, off, seed64, [c0, c1]))
                elif sid == 14:  # tall flower: offset + 2 tints
                    off = (-0.21875, -0.0625, 0.15625)
                    c0 = ctypes.c_int32((0xFF << 24) | (255 << 16) | (200 << 8) | 40).value
                    c1 = ctypes.c_int32((0xFF << 24) | (40 << 16) | (180 << 8) | 255).value
                    extras.append((cell_key, EX_OFFSET, off, None, [c0, c1]))
                elif sid in (15, 16, 17, 18, 19):  # water / waterlogged: fluid tint
                    col = ctypes.c_int32((0xFF << 24) | (63 << 16) | (118 << 8) | 228).value
                    extras.append((cell_key, 0, None, None, [col]))

    return make_job_blob(job_id, epoch, ao, halo, origin, states, lights, extras)


def make_all_solid_job(job_id: int, epoch: int) -> bytes:
    halo = 18
    cells = halo * halo * halo
    return make_job_blob(job_id, epoch, False, halo, (0, 0, 0), [1] * cells, [0xF0] * cells, [])


def make_sparse_vis_job(job_id: int, epoch: int) -> bytes:
    """Sparse section (< 256 opaque blocks) to exercise visResolve fast-path (vis = -1 / 15)."""
    halo = 20
    pad = 2
    cells = halo * halo * halo
    states = [0] * cells
    lights = [0xF0] * cells

    def idx(x: int, y: int, z: int) -> int:
        return ((y + pad) * halo + (z + pad)) * halo + (x + pad)

    for i in range(6):
        states[idx(2 + i, 4, 3)] = 1
        states[idx(2 + i, 5, 3)] = 9
    states[idx(8, 8, 8)] = 20  # isolated lava block
    return make_job_blob(job_id, epoch, True, halo, (-48, 80, 112), states, lights, [])


def make_fuzz_section_job(job_id: int, epoch: int, seed: int = 0x5EED1234) -> bytes:
    """Deterministic pseudo-random stress section hitting all states, shapes, fluids, AO, tints."""
    halo = 20
    pad = 2
    cells = halo * halo * halo
    states = [0] * cells
    lights = [0] * cells

    def idx(x: int, y: int, z: int) -> int:
        return ((y + pad) * halo + (z + pad)) * halo + (x + pad)

    rng = seed
    def nxt() -> int:
        nonlocal rng
        rng = (rng * 1664525 + 1013904223) & 0xFFFFFFFF
        return rng

    # Exclude 22 (F_DYNAMIC_SHAPE) so falling fluids don't decline
    palette = [0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24]
    for hy in range(-pad, 16 + pad):
        for hz in range(-pad, 16 + pad):
            for hx in range(-pad, 16 + pad):
                r = nxt()
                states[idx(hx, hy, hz)] = palette[r % len(palette)]
                lights[idx(hx, hy, hz)] = (nxt() >> 16) & 0xFF

    extras = []
    for y in range(16):
        for z in range(16):
            for x in range(16):
                sid = states[idx(x, y, z)]
                cell_key = x | (z << 4) | (y << 8)
                if sid in (3, 4):
                    col = ctypes.c_int32(0xFF000000 | (nxt() & 0xFFFFFF)).value
                    extras.append((cell_key, 0, None, None, [col]))
                elif sid == 11:
                    c0 = ctypes.c_int32(0xFF000000 | (nxt() & 0xFFFFFF)).value
                    c1 = ctypes.c_int32(0xFF000000 | (nxt() & 0xFFFFFF)).value
                    extras.append((cell_key, 0, None, None, [c0, c1]))
                elif sid == 13:
                    off = (
                        ((nxt() & 255) - 128) / 512.0,
                        ((nxt() & 255) - 128) / 1024.0,
                        ((nxt() & 255) - 128) / 512.0,
                    )
                    seed64 = ((nxt() << 32) | nxt()) & 0xFFFFFFFFFFFFFFFF
                    c0 = ctypes.c_int32(0xFF000000 | (nxt() & 0xFFFFFF)).value
                    c1 = ctypes.c_int32(0xFF000000 | (nxt() & 0xFFFFFF)).value
                    extras.append((cell_key, EX_OFFSET | EX_SEED, off, seed64, [c0, c1]))
                elif sid == 14:
                    off = (
                        ((nxt() & 255) - 128) / 512.0,
                        0.0,
                        ((nxt() & 255) - 128) / 512.0,
                    )
                    c0 = ctypes.c_int32(0xFF000000 | (nxt() & 0xFFFFFF)).value
                    c1 = ctypes.c_int32(0xFF000000 | (nxt() & 0xFFFFFF)).value
                    extras.append((cell_key, EX_OFFSET, off, None, [c0, c1]))
                elif sid in (15, 16, 17, 18, 19):
                    col = ctypes.c_int32(0xFF000000 | (nxt() & 0xFFFFFF)).value
                    extras.append((cell_key, 0, None, None, [col]))

    return make_job_blob(job_id, epoch, True, halo, (-512, -32, 768), states, lights, extras)


def make_decline_jobs(epoch: int) -> list[tuple[str, bytes]]:
    halo = 20
    pad = 2
    cells = halo * halo * halo

    def idx(x: int, y: int, z: int) -> int:
        return ((y + pad) * halo + (z + pad)) * halo + (x + pad)

    jobs: list[tuple[str, bytes]] = []

    # 1. Unknown block state in center
    s1 = [0] * cells
    s1[idx(4, 4, 4)] = 999
    jobs.append(("unknown_state", make_job_blob(901, epoch, True, halo, (0, 0, 0), s1, [0xF0] * cells)))

    # 2. Stale epoch
    jobs.append(("stale_epoch", make_job_blob(902, epoch + 99, False, 18, (0, 0, 0), [0] * (18**3), [0] * (18**3))))

    # 3. Smooth AO with halo=18 (PAD=1 < 2)
    jobs.append(("ao_halo18", make_job_blob(903, epoch, True, 18, (0, 0, 0), [0] * (18**3), [0] * (18**3))))

    # 4. Falling fluid beside F_DYNAMIC_SHAPE
    s4 = [0] * cells
    s4[idx(5, 5, 5)] = 18  # falling water
    s4[idx(5, 5, 4)] = 22  # bamboo (F_DYNAMIC_SHAPE) to the north
    col = ctypes.c_int32((0xFF << 24) | (63 << 16) | (118 << 8) | 228).value
    jobs.append(
        (
            "falling_beside_dynamic",
            make_job_blob(
                904,
                epoch,
                True,
                halo,
                (0, 0, 0),
                s4,
                [0xF0] * cells,
                [(5 | (5 << 4) | (5 << 8), 0, None, None, [col])],
            ),
        )
    )

    # 5. Missing cell extras for F_HAS_OFFSET block
    s5 = [0] * cells
    s5[idx(3, 3, 3)] = 14
    jobs.append(("missing_extra", make_job_blob(905, epoch, True, halo, (0, 0, 0), s5, [0xF0] * cells, [])))

    return jobs


# ---------------------------------------------------------------------------
# QuickJS Worker Driver
# ---------------------------------------------------------------------------


def run_js_worker_sequence(messages: list[tuple[str, bytes]], bench_job: bytes | None = None, bench_iters: int = 0):
    """Run minecraft-web-mesh-worker.js in QuickJS on an ordered sequence of blobs."""
    with tempfile.TemporaryDirectory(prefix="mesh-qjs-") as tmp:
        tmp_path = Path(tmp)
        manifest = []
        for i, (label, data) in enumerate(messages):
            p = tmp_path / f"msg_{i:03d}.bin"
            p.write_bytes(data)
            manifest.append((label, str(p)))
        bench_path = ""
        if bench_job is not None and bench_iters > 0:
            bp = tmp_path / "bench.bin"
            bp.write_bytes(bench_job)
            bench_path = str(bp)

        driver = f"""
const workerSrc = std.loadFile({str(JS_WORKER)!r});
if (!workerSrc) throw new Error("Failed to read minecraft-web-mesh-worker.js");

globalThis.self = globalThis;
globalThis.importScripts = function () {{}};
globalThis.performance = {{
    now: function () {{
        return Date.now();
    }}
}};
let lastResult = null;
let lastDeclined = null;
globalThis.postMessage = function (msg) {{
    if (msg instanceof ArrayBuffer) {{
        lastResult = msg;
    }} else if (msg && msg.declined) {{
        lastDeclined = String(msg.declined);
    }}
}};

(0, eval)(workerSrc);

function readFileBytes(path) {{
    const f = std.open(path, "rb");
    f.seek(0, std.SEEK_END);
    const sz = f.tell();
    f.seek(0, std.SEEK_SET);
    const ab = new ArrayBuffer(sz);
    f.read(ab, 0, sz);
    f.close();
    return ab;
}}
function writeFileBytes(path, ab) {{
    const f = std.open(path, "wb");
    f.write(ab, 0, ab.byteLength);
    f.close();
}}

const manifest = {json.dumps(manifest)};
const meta = [];
for (let i = 0; i < manifest.length; i++) {{
    const [label, path] = manifest[i];
    const ab = readFileBytes(path);
    lastResult = null;
    lastDeclined = null;
    self.onmessage({{ data: ab }});
    if (lastResult !== null) {{
        const outPath = {json.dumps(str(tmp_path))} + "/out_" + i + ".bin";
        writeFileBytes(outPath, lastResult);
        meta.push({{ idx: i, label, outPath, declined: lastDeclined }});
    }}
}}

let benchUs = 0;
const benchPath = {json.dumps(bench_path)};
const benchIters = {bench_iters};
if (benchPath && benchIters > 0) {{
    const jobBuf = readFileBytes(benchPath);
    // warmup
    for (let w = 0; w < 3; w++) {{
        lastDeclined = null;
        self.onmessage({{ data: jobBuf }});
        if (lastDeclined) throw new Error("Benchmark job declined in JS: " + lastDeclined);
    }}
    const t0 = os.now ? os.now() : (Date.now() * 1000);
    for (let it = 0; it < benchIters; it++) {{
        self.onmessage({{ data: jobBuf }});
    }}
    const t1 = os.now ? os.now() : (Date.now() * 1000);
    benchUs = t1 - t0;
}}

print("QJS_META=" + JSON.stringify({{ meta, benchUs }}));
"""
        driver_path = tmp_path / "driver.js"
        driver_path.write_text(driver, encoding="utf-8")
        proc = subprocess.run(
            [str(QJS_BIN), "--std", str(driver_path)],
            capture_output=True,
            text=True,
            timeout=120,
        )
        if proc.returncode != 0:
            raise RuntimeError(
                f"QuickJS driver exited {proc.returncode}:\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
            )

        meta_line = [ln for ln in proc.stdout.splitlines() if ln.startswith("QJS_META=")]
        if not meta_line:
            raise RuntimeError(f"QuickJS driver failed:\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}")
        parsed = json.loads(meta_line[-1][len("QJS_META=") :])
        outputs: dict[str, tuple[bytes, str | None]] = {}
        for item in parsed["meta"]:
            outputs[item["label"]] = (Path(item["outPath"]).read_bytes(), item["declined"])
        return outputs, float(parsed["benchUs"])


# ---------------------------------------------------------------------------
# Native C/SIMD Wrapper
# ---------------------------------------------------------------------------


class NativeMeshKernel:
    def __init__(self, so_path: Path) -> None:
        self.lib = ctypes.CDLL(str(so_path))
        self.lib.wasm_init.argtypes = []
        self.lib.wasm_init.restype = ctypes.c_int32
        self.lib.wasm_reset.argtypes = [ctypes.c_int32]
        self.lib.wasm_reset.restype = None
        self.lib.wasm_job_in_ptr.argtypes = []
        self.lib.wasm_job_in_ptr.restype = ctypes.c_size_t
        self.lib.wasm_job_in_cap.argtypes = []
        self.lib.wasm_job_in_cap.restype = ctypes.c_int32
        self.lib.wasm_out_ptr.argtypes = []
        self.lib.wasm_out_ptr.restype = ctypes.c_size_t
        self.lib.wasm_out_cap.argtypes = []
        self.lib.wasm_out_cap.restype = ctypes.c_int32
        self.lib.wasm_read_defs.argtypes = [ctypes.c_size_t, ctypes.c_int32]
        self.lib.wasm_read_defs.restype = ctypes.c_int32
        self.lib.wasm_read_tags.argtypes = [ctypes.c_size_t, ctypes.c_int32]
        self.lib.wasm_read_tags.restype = ctypes.c_int32
        self.lib.wasm_run_job.argtypes = [ctypes.c_size_t, ctypes.c_int32]
        self.lib.wasm_run_job.restype = ctypes.c_int32

        rc = self.lib.wasm_init()
        assert rc == 0, f"wasm_init failed: {rc}"
        self.in_ptr = self.lib.wasm_job_in_ptr()
        self.in_cap = self.lib.wasm_job_in_cap()
        self.out_ptr = self.lib.wasm_out_ptr()
        self.out_cap = self.lib.wasm_out_cap()

    def _stage(self, data: bytes) -> int:
        assert len(data) <= self.in_cap, f"blob {len(data)} > in_cap {self.in_cap}"
        ctypes.memmove(self.in_ptr, data, len(data))
        return len(data)

    def reset(self, epoch: int) -> None:
        self.lib.wasm_reset(epoch)

    def read_defs(self, data: bytes) -> int:
        n = self._stage(data)
        return self.lib.wasm_read_defs(self.in_ptr, n)

    def read_tags(self, data: bytes) -> int:
        n = self._stage(data)
        return self.lib.wasm_read_tags(self.in_ptr, n)

    def run_job(self, data: bytes) -> tuple[int, bytes]:
        n = self._stage(data)
        rc = self.lib.wasm_run_job(self.in_ptr, n)
        out_len = rc if rc > 0 else 64
        out_bytes = ctypes.string_at(self.out_ptr, out_len)
        return rc, out_bytes


# ---------------------------------------------------------------------------
# Comparison Helpers
# ---------------------------------------------------------------------------


def zero_timing_word(buf: bytes) -> bytes:
    """Zero header int32 word 11 (host timing micros) before exact byte comparison."""
    if len(buf) < 64:
        return buf
    ba = bytearray(buf)
    ba[44:48] = b"\x00\x00\x00\x00"
    return bytes(ba)


def describe_diff(label: str, js_buf: bytes, c_buf: bytes) -> str:
    jh = struct.unpack_from("<16i", js_buf, 0)
    ch = struct.unpack_from("<16i", c_buf, 0)
    lines = [
        f"MISMATCH in {label}: len(js)={len(js_buf)} vs len(c)={len(c_buf)}",
        f"  js header: {jh}",
        f"  c  header: {ch}",
    ]
    n = min(len(js_buf), len(c_buf))
    for off in range(0, n, 4):
        if js_buf[off : off + 4] != c_buf[off : off + 4]:
            jw = struct.unpack_from("<i", js_buf, off)[0]
            cw = struct.unpack_from("<i", c_buf, off)[0]
            jf = struct.unpack_from("<f", js_buf, off)[0]
            cf = struct.unpack_from("<f", c_buf, off)[0]
            lines.append(
                f"  first diff at byte {off} (word {off // 4}): "
                f"js=0x{jw & 0xFFFFFFFF:08x} ({jf:.6g}) vs c=0x{cw & 0xFFFFFFFF:08x} ({cf:.6g})"
            )
            break
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Step 1: WASM-SIMD128 Compile, Link & Disassembly Check
# ---------------------------------------------------------------------------


def verify_wasm_build(tmp_dir: Path) -> dict[str, int | list[str]]:
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

    # Check symbols: no undefined external functions (only __stack_pointer / table allowed)
    sym_out = subprocess.run(
        ["llvm-readobj-21", "--symbols", str(obj_path)],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    bad_undef = []
    cur_name = ""
    cur_type = ""
    for line in sym_out.splitlines():
        s = line.strip()
        if s.startswith("Name:"):
            cur_name = s.split(":", 1)[1].strip()
        elif s.startswith("Type:"):
            cur_type = s.split(":", 1)[1].strip()
        elif "UNDEFINED" in s and "FUNCTION" in cur_type:
            bad_undef.append(cur_name)
    if bad_undef:
        raise AssertionError(f"Unexpected undefined wasm32 function imports: {bad_undef}")

    # Disassembly check for real 0xFD SIMD128 + i64.mul instructions
    dis = subprocess.run(
        ["llvm-objdump-21", "-d", str(obj_path)],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    simd_count = len(re.findall(r"^\s*[0-9a-f]+:\s+fd\s", dis, re.MULTILINE))
    i64_mul_count = len(re.findall(r"\bi64\.mul\b", dis))
    required_ops = ["v128.andnot", "v128.any_true", "f32x4.mul", "f32x4.floor", "i8x16.swizzle", "i64.mul"]
    missing_ops = [op for op in required_ops if op not in dis]
    if missing_ops:
        raise AssertionError(f"Missing expected wasm32-simd128 instructions: {missing_ops}")

    # Link via scripts/wasm_link_single.py (standalone export mode, 12 MiB initial = 192 pages, growable)
    wasm_bytes = 0
    exports: list[str] = []
    if LINKER_PY.exists():
        link_proc = subprocess.run(
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
            capture_output=True,
            text=True,
            check=True,
        )
        wasm_bytes = wasm_path.stat().st_size
        m = re.search(r"exports=(\[[^\]]*\])", link_proc.stdout)
        if m:
            exports = eval(m.group(1))
        required_exports = [
            "memory",
            "wasm_init",
            "wasm_reset",
            "wasm_job_in_ptr",
            "wasm_out_ptr",
            "wasm_read_defs",
            "wasm_read_tags",
            "wasm_run_job",
        ]
        missing_exp = [e for e in required_exports if e not in exports]
        if missing_exp:
            raise AssertionError(f"Missing required exports in linked .wasm: {missing_exp} (got {exports})")

    return {
        "obj_bytes": obj_path.stat().st_size,
        "wasm_bytes": wasm_bytes,
        "simd_count": simd_count,
        "i64_mul_count": i64_mul_count,
        "exports": exports,
    }


# ---------------------------------------------------------------------------
# Step 2: Native Parity & Speed Benchmark
# ---------------------------------------------------------------------------


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="mesh-kernel-check-") as tmp:
        tmp_dir = Path(tmp)

        print("[1/3] Compiling & linking wasm32-simd128 kernel...")
        wasm_info = verify_wasm_build(tmp_dir)
        print(
            f"  OK: obj={wasm_info['obj_bytes']} B, wasm={wasm_info['wasm_bytes']} B "
            f"({wasm_info['wasm_bytes'] / 1024:.1f} KiB), "
            f"0xFD SIMD ops={wasm_info['simd_count']}, i64.mul={wasm_info['i64_mul_count']}"
        )
        print(f"  Exports: {wasm_info['exports']}")

        print("[2/3] Compiling native x86_64 parity .so (unified wasm_simd128_native.h)...")
        so_path = tmp_dir / "mesh_kernel.so"
        subprocess.run(
            [
                "clang-21",
                "-O3",
                "-fno-math-errno",
                "-ffp-contract=off",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-shared",
                "-fPIC",
                str(KERNEL_C),
                "-o",
                str(so_path),
            ],
            check=True,
        )
        kernel = NativeMeshKernel(so_path)

        # Build test sequence
        epoch1 = 1
        defs1 = build_test_defs(epoch1)
        tags_on = build_tags_on()
        tags_off = make_tags_blob(0, [], [])

        job_flat = make_complex_section_job(101, epoch1, ao=False, halo=18, origin=(-128, -64, -256))
        job_ao = make_complex_section_job(102, epoch1, ao=True, halo=20, origin=(-128, -64, -256))
        job_vis_part = make_complex_section_job(103, epoch1, ao=True, halo=20, origin=(64, 16, -32), partition_vis=True)
        job_all_solid = make_all_solid_job(104, epoch1)
        job_sparse_vis = make_sparse_vis_job(108, epoch1)
        job_tagged_flat = make_complex_section_job(105, epoch1, ao=False, halo=18, origin=(16, 32, 48))
        job_tagged_ao = make_complex_section_job(106, epoch1, ao=True, halo=20, origin=(16, 32, 48), partition_vis=True)
        job_fuzz_tagged = make_fuzz_section_job(109, epoch1, seed=0x5EED1234)
        job_untagged_again = make_complex_section_job(107, epoch1, ao=True, halo=20, origin=(-16, 0, 16))
        decline_jobs = make_decline_jobs(epoch1)

        # Sequence executed identically by BOTH QuickJS minecraft-web-mesh-worker.js AND native C/SIMD
        messages: list[tuple[str, bytes]] = [
            ("reset_1", make_reset_blob(epoch1)),
            ("defs_1", defs1),
            ("defs_stale_ignored", build_test_defs(epoch1 + 5)),
            ("flat_halo18", job_flat),
            ("smooth_ao_halo20", job_ao),
            ("vis_partitioned_ao", job_vis_part),
            ("vis_all_solid", job_all_solid),
            ("vis_sparse_ao", job_sparse_vis),
            ("tags_on", tags_on),
            ("tagged_flat_halo18", job_tagged_flat),
            ("tagged_smooth_ao_halo20", job_tagged_ao),
            ("fuzz_all_features_tagged", job_fuzz_tagged),
            ("tags_off", tags_off),
            ("untagged_after_off", job_untagged_again),
        ]
        for dname, dblob in decline_jobs:
            messages.append((f"decline_{dname}", dblob))

        # Test reset -> new epoch cleans old state
        epoch2 = 2
        defs2 = build_test_defs(epoch2)
        job_bench = make_complex_section_job(201, epoch2, ao=True, halo=20, origin=(64, 16, -32), partition_vis=True)
        messages.append(("reset_2", make_reset_blob(epoch2)))
        messages.append(("defs_2", defs2))
        messages.append(("epoch2_ao", job_bench))

        bench_iters_js = 12
        bench_iters_c = 200
        print("[3/3] Running byte-for-byte parity suite against QuickJS minecraft-web-mesh-worker.js...")
        js_outputs, js_bench_us_total = run_js_worker_sequence(messages, bench_job=job_bench, bench_iters=bench_iters_js)

        total_bytes_verified = 0
        total_quads_verified = 0
        cases_passed = 0

        for label, blob in messages:
            kind = struct.unpack_from("<i", blob, 0)[0]
            if kind == 3:
                ep = struct.unpack_from("<i", blob, 4)[0]
                kernel.reset(ep)
            elif kind == 1:
                rc = kernel.read_defs(blob)
                assert rc == 0, f"wasm_read_defs({label}) failed with {rc}"
            elif kind == 4:
                rc = kernel.read_tags(blob)
                assert rc == 0, f"wasm_read_tags({label}) failed with {rc}"
            elif kind == 2:
                rc, c_out = kernel.run_job(blob)
                js_out, js_declined = js_outputs[label]
                js_norm = zero_timing_word(js_out)
                c_norm = zero_timing_word(c_out)

                if label.startswith("decline_"):
                    assert rc < 0, f"{label}: expected negative decline rc, got {rc}"
                    assert js_declined is not None, f"{label}: expected JS decline"
                else:
                    assert rc > 0, f"{label}: unexpected C decline rc={rc}"
                    assert js_declined is None, f"{label}: unexpected JS decline {js_declined}"

                if js_norm != c_norm:
                    raise AssertionError(describe_diff(label, js_norm, c_norm))

                hdr = struct.unpack_from("<16i", c_norm, 0)
                quads = (hdr[6] + hdr[7] + hdr[8]) // 112
                total_bytes_verified += len(c_norm)
                total_quads_verified += quads
                cases_passed += 1
                print(
                    f"  PASS {label:28s}  bytes={len(c_norm):7d}  quads={quads:4d} "
                    f"(S={hdr[6]//112}, C={hdr[7]//112}, T={hdr[8]//112})  "
                    f"sprites={hdr[9]:2d}  vis=0x{hdr[5]&0xF:x}{hdr[4]&0xFFFFFFFF:08x}  rc={rc}"
                )

        # Native speed benchmark on the exact same job_bench (in epoch 2)
        for _ in range(10):
            rc_w, _ = kernel.run_job(job_bench)
            assert rc_w > 0
        t0 = time.perf_counter()
        for _ in range(bench_iters_c):
            rc_b, _ = kernel.run_job(job_bench)
            assert rc_b > 0
        t1 = time.perf_counter()
        c_us = (t1 - t0) * 1e6 / bench_iters_c
        js_us = js_bench_us_total / bench_iters_js if js_bench_us_total > 0 else 0.0
        speedup = (js_us / c_us) if c_us > 0 else 0.0

        print("\n=== SUMMARY ===")
        print(f"WASM binary size : {wasm_info['wasm_bytes']} bytes ({wasm_info['wasm_bytes'] / 1024:.2f} KiB)")
        print(f"SIMD128 / i64.mul: {wasm_info['simd_count']} x 0xFD SIMD ops, {wasm_info['i64_mul_count']} x i64.mul")
        print(f"Parity verified  : {cases_passed}/{cases_passed} cases, {total_quads_verified} quads, {total_bytes_verified} bytes 100% BYTE-IDENTICAL")
        print(f"Benchmark (AO+Vis+Fluids): C/SIMD = {c_us:.1f} us/section vs QuickJS JS = {js_us:.1f} us/section ({speedup:.1f}x faster)")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
