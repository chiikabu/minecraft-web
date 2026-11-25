#!/usr/bin/env python3
"""Compile, link, validate, and embed WASM-SIMD128 C kernels.

Uses native Linux clang-21 + scripts/wasm_link_single.py only (zero Windows .exe
invocations). By default compiles scripts/wasm_simd/worldgen_cull_kernels.c in
`--memory-mode import --no-data`, validates the linked .wasm via validate_wasm(),
and updates the inline base64 in:
  - target_teavm_263/wasm-gc/minecraft-web-simd-kernels.js
  (and mirrors to target_teavm_263/javascript-wasm-gc/minecraft-web-simd-kernels.js
   if that staged directory exists).

Pass --mesh (or --mesh-out PATH) to also compile and validate mesh_kernel.c in
`--memory-mode export`.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import tempfile

SCRIPT_DIR = Path(__file__).resolve().parent
SCRIPTS_DIR = SCRIPT_DIR.parent
REPO_ROOT = SCRIPTS_DIR.parent

sys.path.insert(0, str(SCRIPTS_DIR))
import wasm_link_single as wls  # noqa: E402

WORLDGEN_C = SCRIPT_DIR / "worldgen_cull_kernels.c"
MESH_C = SCRIPT_DIR / "mesh_kernel.c"

PRIMARY_JS = REPO_ROOT / "target_teavm_263" / "wasm-gc" / "minecraft-web-simd-kernels.js"
MIRROR_JS = REPO_ROOT / "target_teavm_263" / "javascript-wasm-gc" / "minecraft-web-simd-kernels.js"

EXPECTED_WORLDGEN_EXPORTS = (
    "simd_improved_noise_batch",
    "simd_aquifer_voronoi_batch",
    "simd_beardifier_volume",
    "simd_biome_fiddle_batch",
    "simd_cull_aabb_frustum_fog",
    "simd_sort_translucent_quads",
    "simd_mat4_mul_batch",
    "simd_set_scratch",
)

EXPECTED_MESH_EXPORTS = (
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
    "__heap_base",
)

FORBIDDEN_HTML_XML_RE = re.compile(r"</script|<!--|\]\]>", re.I)


def active_js_targets() -> tuple[Path, ...]:
    if MIRROR_JS.parent.is_dir():
        return (PRIMARY_JS, MIRROR_JS)
    return (PRIMARY_JS,)


def compile_wasm_object(src_c: Path, obj_out: Path, clang: str = "clang-21") -> bytes:
    if not src_c.is_file():
        raise FileNotFoundError(f"C kernel source not found: {src_c}")
    cmd = [
        clang,
        "--target=wasm32",
        "-msimd128",
        "-O3",
        "-ffreestanding",
        "-fno-builtin",
        "-c",
        str(src_c),
        "-o",
        str(obj_out),
    ]
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed (rc={res.returncode}):\n{res.stdout}")
    return obj_out.read_bytes()


def build_worldgen_wasm(clang: str = "clang-21", out_wasm: Path | None = None) -> tuple[bytes, dict]:
    with tempfile.TemporaryDirectory(prefix="build-worldgen-simd-") as tmp:
        obj_path = Path(tmp) / "worldgen_cull_kernels.o"
        obj_bytes = compile_wasm_object(WORLDGEN_C, obj_path, clang=clang)

    wasm_bytes = wls.link_wasm_object(obj_bytes, memory_mode="import", no_data=True)
    info = wls.validate_wasm(wasm_bytes)

    if info["num_data_segments"] != 0:
        raise RuntimeError(f"worldgen_cull_kernels.wasm must have 0 data segments, got {info['num_data_segments']}")
    if ("env", "memory", wls.EXT_MEMORY) not in info["imports"]:
        raise RuntimeError(f"worldgen_cull_kernels.wasm must import env.memory, got {info['imports']}")
    export_names = [name for name, _, _ in info["exports"]]
    missing = [name for name in EXPECTED_WORLDGEN_EXPORTS if name not in export_names]
    if missing:
        raise RuntimeError(f"worldgen_cull_kernels.wasm missing exports: {missing}")

    if out_wasm is not None:
        out_wasm.parent.mkdir(parents=True, exist_ok=True)
        out_wasm.write_bytes(wasm_bytes)

    return wasm_bytes, info


def build_mesh_wasm(clang: str = "clang-21", out_wasm: Path | None = None) -> tuple[bytes, dict]:
    with tempfile.TemporaryDirectory(prefix="build-mesh-simd-") as tmp:
        obj_path = Path(tmp) / "mesh_kernel.o"
        obj_bytes = compile_wasm_object(MESH_C, obj_path, clang=clang)

    wasm_bytes = wls.link_wasm_object(obj_bytes, memory_mode="export")
    info = wls.validate_wasm(wasm_bytes)

    export_names = [name for name, _, _ in info["exports"]]
    missing = [name for name in EXPECTED_MESH_EXPORTS if name not in export_names]
    if missing:
        raise RuntimeError(f"mesh_kernel.wasm missing exports: {missing}")

    if out_wasm is not None:
        out_wasm.parent.mkdir(parents=True, exist_ok=True)
        out_wasm.write_bytes(wasm_bytes)

    return wasm_bytes, info


def _replace_single(text: str, pattern: str, replacement: str, label: str) -> str:
    matches = list(re.finditer(pattern, text))
    if len(matches) != 1:
        raise RuntimeError(
            f"Expected exactly 1 {label} marker in minecraft-web-simd-kernels.js, found {len(matches)}"
        )
    m = matches[0]
    return text[: m.start()] + replacement + text[m.end() :]


def render_updated_js(template_text: str, wasm_bytes: bytes) -> str:
    b64 = base64.b64encode(wasm_bytes).decode("ascii")
    digest = hashlib.sha256(wasm_bytes).hexdigest()
    updated = _replace_single(
        template_text,
        r'var WASM_BYTES = \d+;',
        f"var WASM_BYTES = {len(wasm_bytes)};",
        "WASM_BYTES",
    )
    updated = _replace_single(
        updated,
        r'var WASM_SHA256 = "[0-9a-f]*";',
        f'var WASM_SHA256 = "{digest}";',
        "WASM_SHA256",
    )
    updated = _replace_single(
        updated,
        r'var WASM_B64 = "[A-Za-z0-9+/=]*";',
        f'var WASM_B64 = "{b64}";',
        "WASM_B64",
    )
    updated = updated.replace("\r\n", "\n")
    bad = FORBIDDEN_HTML_XML_RE.search(updated)
    if bad:
        raise RuntimeError(f"minecraft-web-simd-kernels.js contains forbidden HTML/XML token: {bad.group(0)!r}")
    return updated


def verify_embedded_js(js_path: Path, expected_wasm: bytes) -> None:
    content = js_path.read_text(encoding="utf-8")
    bad = FORBIDDEN_HTML_XML_RE.search(content)
    if bad:
        raise RuntimeError(f"{js_path} contains forbidden HTML/XML token: {bad.group(0)!r}")
    m_b64 = re.search(r'var WASM_B64 = "([A-Za-z0-9+/=]*)";', content)
    m_len = re.search(r'var WASM_BYTES = (\d+);', content)
    if not m_b64 or not m_len:
        raise RuntimeError(f"{js_path} missing WASM_B64 or WASM_BYTES")
    decoded = base64.b64decode(m_b64.group(1))
    if decoded != expected_wasm:
        raise RuntimeError(f"{js_path} embedded base64 does not match freshly linked worldgen_cull_kernels.wasm")
    if int(m_len.group(1)) != len(expected_wasm):
        raise RuntimeError(f"{js_path} WASM_BYTES={m_len.group(1)} != {len(expected_wasm)}")
    wls.validate_wasm(decoded)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--clang", default="clang-21", help="Native Linux clang binary (default: clang-21)")
    parser.add_argument("--out-wasm", type=Path, default=None, help="Optional path to also write worldgen_cull_kernels.wasm")
    parser.add_argument("--mesh", action="store_true", help="Also compile, link (export mode), and validate mesh_kernel.c")
    parser.add_argument("--mesh-out", type=Path, default=None, help="Optional path to write mesh_kernel.wasm (implies --mesh)")
    parser.add_argument("--check", action="store_true", help="Verify existing minecraft-web-simd-kernels.js matches freshly built .wasm")
    parser.add_argument("--no-update-js", action="store_true", help="Build and validate .wasm without updating minecraft-web-simd-kernels.js")
    args = parser.parse_args(argv)

    wasm_bytes, info = build_worldgen_wasm(clang=args.clang, out_wasm=args.out_wasm)
    digest = hashlib.sha256(wasm_bytes).hexdigest()
    print(
        f"[worldgen_cull_kernels] OK: {len(wasm_bytes)} bytes, sha256={digest[:12]}, "
        f"data={info['num_data_segments']}, exports={[e[0] for e in info['exports']]}"
    )

    targets = active_js_targets()
    if args.check:
        for target in targets:
            verify_embedded_js(target, wasm_bytes)
        if len(targets) > 1 and targets[0].read_bytes() != targets[1].read_bytes():
            raise RuntimeError("wasm-gc and javascript-wasm-gc minecraft-web-simd-kernels.js are not byte-identical")
        print(f"[check] OK: {len(targets)} minecraft-web-simd-kernels.js copy(ies) match linked .wasm byte-for-byte")
    elif not args.no_update_js:
        if not PRIMARY_JS.is_file():
            raise FileNotFoundError(f"Template JS missing: {PRIMARY_JS}")
        updated = render_updated_js(PRIMARY_JS.read_text(encoding="utf-8"), wasm_bytes)
        encoded = updated.encode("utf-8")
        for target in targets:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(encoded)
            verify_embedded_js(target, wasm_bytes)
        if len(targets) > 1 and targets[0].read_bytes() != targets[1].read_bytes():
            raise RuntimeError("Mirror verification failed: JS targets differ")
        print(
            f"[js] updated {len(targets)} copy(ies) ({len(encoded)} bytes, base64={len(base64.b64encode(wasm_bytes))} chars)"
        )

    if args.mesh or args.mesh_out is not None:
        mesh_bytes, mesh_info = build_mesh_wasm(clang=args.clang, out_wasm=args.mesh_out)
        mesh_digest = hashlib.sha256(mesh_bytes).hexdigest()
        print(
            f"[mesh_kernel] OK: {len(mesh_bytes)} bytes, sha256={mesh_digest[:12]}, "
            f"data={mesh_info['num_data_segments']}, exports={[e[0] for e in mesh_info['exports']]}"
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
