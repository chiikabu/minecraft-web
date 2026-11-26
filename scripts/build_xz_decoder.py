#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Build the standalone XZ decoder from source using Zig's bundled C toolchain."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent

def build(zig="zig"):
    library = ROOT / "target_teavm_263/xz-loader/c/xz"
    sources = [ROOT / "scripts/xz_decoder.c", *[library / name for name in
               ("xz_dec_stream.c", "xz_dec_lzma2.c", "xz_crc32.c")]]
    with tempfile.TemporaryDirectory(prefix="xz-decoder-") as temporary:
        output = Path(temporary) / "decoder.wasm"
        subprocess.run([zig, "cc", "-target", "wasm32-wasi", "-mexec-model=reactor",
                        "-O2", "-flto", "-DXZ_DEC_SINGLE", "-I" + str(library), *map(str, sources),
                        "-Wl,--export=decode", "-Wl,--export=malloc",
                        "-Wl,--max-memory=2147483648", "-Wl,--strip-all", "-o", str(output)],
                       check=True, timeout=120)
        return output.read_bytes()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="new decoder Wasm file")
    parser.add_argument("--zig", default="zig")
    args = parser.parse_args()
    try:
        if args.output.exists() or args.output.is_symlink():
            raise ValueError("Output must be a new file")
        data = build(args.zig)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("xb") as stream:
            stream.write(data)
        print(f"XZ_DECODER_BUILT {len(data)} bytes SHA-256 {hashlib.sha256(data).hexdigest()}")
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(2, f"XZ decoder build failed: {error}\n")
