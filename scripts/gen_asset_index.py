#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)

import argparse
import hashlib
import io
import json
import os
import random
import re
import shutil
import sys
import tempfile
import zipfile

EXCLUDED_PREFIXES = (
    "assets/minecraft/sounds/",
    "assets/minecraft/resourcepacks/",
    "assets/minecraft/textures/gui/realms/",
)

EXCLUDED_EXACT = (
    "assets/minecraft/textures/gui/title/realms.png",
)

KEPT_LANG = ("en_us.json", "deprecated.json")

KEPT_ROOT_FILES = ("version.json",)

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def default_paths():
    with open(os.path.join(REPO, "port_version.json"), encoding="utf-8") as source:
        port = json.load(source)
    return (os.path.join(REPO, port["client_jar"]),
            os.path.join(REPO, port["asset_index_dir"], port["asset_index_id"] + ".json"),
            os.path.join(REPO, port["asset_object_dir"]))

def _refs(node):
    if isinstance(node, str):

        if "/" in node and "." in node.rsplit("/", 1)[1]:
            yield node
    elif isinstance(node, dict):
        for v in node.values():
            yield from _refs(v)
    elif isinstance(node, list):
        for v in node:
            yield from _refs(v)

def _refs_satisfied(blob, available, needed=None):
    try:
        doc = json.loads(blob)
    except (ValueError, UnicodeDecodeError) as error:
        raise ValueError("Invalid asset manifest JSON") from error
    for ref in _refs(doc):
        ns, _, path = ref.rpartition(":")
        name = "assets/%s/%s" % (ns or "minecraft", path)
        if name not in available:
            print(f"REFUSED overlay: manifest references {name}, which is not available")
            return False
        if needed is not None:
            needed.add(name)
    return True

def safe_name(name):
    if (not isinstance(name, str) or "\\" in name or ":" in name or "\0" in name
            or any(part in {"", ".", ".."} for part in name.split("/"))):
        raise ValueError("Unsafe asset path: " + repr(name))
    return name

def wanted(name: str) -> bool:
    safe_name(name)
    if name in KEPT_ROOT_FILES:
        return True
    if not (name.startswith("assets/") or name.startswith("data/")):
        return False
    if name.startswith(EXCLUDED_PREFIXES) or name in EXCLUDED_EXACT:
        return False
    if "/lang/" in name and name.rsplit("/", 1)[-1] not in KEPT_LANG:
        return False
    return True

def _index_objects(index_path, object_dir) -> dict:
    out = {}
    with open(index_path) as source:
        objects = json.load(source)["objects"]
    if not isinstance(objects, dict):
        raise ValueError("Expected an objects map in the asset index")
    for asset_path, meta in objects.items():
        safe_name(asset_path)
        digest = meta["hash"]
        if not re.fullmatch(r"[0-9a-f]{40}", digest):
            raise ValueError("Invalid asset object hash: " + asset_path)
        if not isinstance(meta["size"], int) or meta["size"] < 0:
            raise ValueError("Invalid asset object size: " + asset_path)
        blob = os.path.join(object_dir, digest[:2], digest)
        out["assets/" + asset_path] = (meta["size"], blob)
    return out

def object_bytes(record):
    size, path = record
    with open(path, "rb") as source:
        data = source.read()
    if len(data) != size or hashlib.sha1(data).hexdigest() != os.path.basename(path):
        raise ValueError("Corrupt asset object: " + str(path))
    return data

def object_overlay(jar: zipfile.ZipFile, index_path, object_dir) -> dict:
    objects = _index_objects(index_path, object_dir)
    if not objects:
        return {}
    sizes = {e.filename: e.file_size for e in jar.infolist()}

    available = {name for name in sizes if not name.endswith("/") and wanted(name)}
    available.update(name for name, (_, path) in objects.items() if os.path.isfile(path) and wanted(name))
    overlay = {}
    needed = set()
    for name, (size, blob) in objects.items():
        if name not in sizes or not wanted(name):
            continue
        data = object_bytes((size, blob))
        if data == jar.read(name):
            continue
        if name.endswith(".json") and not _refs_satisfied(data, available, needed):
            raise ValueError("Asset manifest has unavailable references: " + name)
        overlay[name] = data

    for name in needed:
        if name not in overlay and name in objects:
            overlay[name] = object_bytes(objects[name])

    return overlay

def _unifont_selfcheck() -> None:
    tmp = tempfile.mkdtemp(prefix="unifont-selfcheck-")
    try:
        idx_dir = os.path.join(tmp, "indexes")
        obj_dir = os.path.join(tmp, "objects")
        os.makedirs(idx_dir)
        os.makedirs(obj_dir)

        stub = b'{"providers":[]}'
        real = json.dumps({"providers": [
            {"type": "unihex", "hex_file": "minecraft:font/unifont.zip", "size_overrides": []},
            {"type": "unihex", "hex_file": "minecraft:font/unifont_jp.zip", "size_overrides": []},
        ]}).encode()
        def make_font_zip(filename):
            out = io.BytesIO()
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as font:
                font.writestr(filename, b"0041:00000000000000000000000000000000\n"
                                        b"4E00:00000000000000000000000000000000\n")
            return out.getvalue()

        zip_a = make_font_zip("unifont.hex")
        zip_b = make_font_zip("unifont_jp.hex")

        def put(data):
            h = hashlib.sha1(data).hexdigest()
            d = os.path.join(obj_dir, h[:2])
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, h), "wb") as f:
                f.write(data)
            return h

        objects = {
            "minecraft/font/include/unifont.json": {"hash": put(real), "size": len(real)},
            "minecraft/font/unifont.zip": {"hash": put(zip_a), "size": len(zip_a)},
            "minecraft/font/unifont_jp.zip": {"hash": put(zip_b), "size": len(zip_b)},
        }
        index_path = os.path.join(idx_dir, "26.3.json")
        with open(index_path, "w") as f:
            json.dump({"objects": objects}, f)

        jar_path = os.path.join(tmp, "fake.jar")
        with zipfile.ZipFile(jar_path, "w") as jar:
            jar.writestr("assets/minecraft/font/include/unifont.json", stub)

        with zipfile.ZipFile(jar_path) as jar:
            overlay = object_overlay(jar, index_path, obj_dir)
        m = "assets/minecraft/font/include/unifont.json"
        assert overlay.get(m) == real, "unifont manifest stub was not overlaid with the real one"
        for font_name, source in (("unifont.zip", zip_a), ("unifont_jp.zip", zip_b)):
            shipped = overlay.get("assets/minecraft/font/" + font_name)
            assert shipped == source, font_name + " not pulled into overlay whole"

        h_a = objects["minecraft/font/unifont.zip"]["hash"]
        os.remove(os.path.join(obj_dir, h_a[:2], h_a))
        try:
            with zipfile.ZipFile(jar_path) as jar:
                object_overlay(jar, index_path, obj_dir)
        except ValueError as error:
            assert "unavailable references" in str(error)
        else:
            raise AssertionError("missing font data accepted")
        print("unifont overlay OK  (real manifest + 2 zips pulled in; missing font data rejected)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

def selfcheck() -> int:
    keep = [
        "assets/minecraft/textures/gui/title/minecraft.png",
        "assets/minecraft/lang/en_us.json",
        "assets/minecraft/lang/deprecated.json",
        "assets/minecraft/font/default.json",
        "data/minecraft/tags/item/axes.json",
        "assets/.mcassetsroot",

        "version.json",
    ]
    drop = [
        "assets/minecraft/lang/de_de.json",
        "assets/minecraft/sounds/ambient/cave/cave1.ogg",
        "assets/minecraft/resourcepacks/programmer_art.zip",
        "assets/minecraft/textures/gui/realms/configure_icon.png",
        "assets/minecraft/textures/gui/title/realms.png",
        "net/minecraft/client/Minecraft.class",
        "pack.png",
    ]
    for n in keep:
        assert wanted(n), f"should be indexed: {n}"
    for n in drop:
        assert not wanted(n), f"should be excluded: {n}"

    overlaid = 0
    defaults = default_paths() if os.path.isfile(os.path.join(REPO, "port_version.json")) else None
    if defaults and all(os.path.exists(path) for path in defaults):
        jar_path, index_path, object_dir = defaults
        with zipfile.ZipFile(jar_path) as jar:
            overlay = object_overlay(jar, index_path, object_dir)
        for face in range(6):
            name = f"assets/minecraft/textures/gui/title/background/panorama_{face}.png"
            assert name in overlay, f"panorama face not overlaid: {name}"
            assert len(overlay[name]) > 10000, f"panorama face still a stub: {name}"
        overlaid = len(overlay)
        print(f"overlay OK  ({overlaid} jar placeholders replaced by indexed objects)")

    _unifont_selfcheck()

    print(f"selfcheck OK ({len(keep)} kept, {len(drop)} dropped)")
    return 0

def verify(out: str, index: dict, samples: int = 20) -> None:
    paths = random.sample(sorted(index), min(samples, len(index)))
    with open(os.path.join(out, "assets.bin"), "rb") as bundle:
        for path in paths:
            offset, length = index[path]
            bundle.seek(offset)
            got = bundle.read(length)
            with open(os.path.join(out, path), "rb") as f:
                want = f.read()
            assert got == want, f"bundle slice != file for {path}"
    print(f"verify:     {len(paths)} random slices byte-match the extracted tree")

EXTRA_DIRS = ("assets/minecraft/shaders/wgsl",)

def extra_files(out_dir: str) -> dict:
    found = {}
    for rel in EXTRA_DIRS:
        root = os.path.join(out_dir, rel)
        for dirpath, _, files in os.walk(root):
            for f in files:
                path = os.path.join(dirpath, f)
                name = os.path.relpath(path, out_dir).replace(os.sep, "/")
                with open(path, "rb") as fh:
                    found[name] = fh.read()
    return found

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selfcheck", action="store_true", help="run the filter assertions and exit")
    ap.add_argument("--jar")
    ap.add_argument("--index", help="the selected version's asset index JSON")
    ap.add_argument("--objects", help="content-addressed asset objects directory")
    ap.add_argument("--out", default=os.path.join(REPO, "target_teavm_263/javascript"))
    args = ap.parse_args()
    if args.selfcheck:
        return selfcheck()
    if not all((args.jar, args.index, args.objects)):
        jar, index, objects = default_paths()
        args.jar, args.index, args.objects = args.jar or jar, args.index or index, args.objects or objects

    index = {}
    written = skipped = 0
    biggest = []
    bundle_path = os.path.join(args.out, "assets.bin")

    with zipfile.ZipFile(args.jar) as jar, open(bundle_path, "wb") as bundle:
        overlay = object_overlay(jar, args.index, args.objects)
        jar_names = {e.filename for e in jar.infolist()}
        for name in sorted(overlay):
            was = jar.getinfo(name).file_size if name in jar_names else 0
            print(f"overlay:    {name} {was} B -> {len(overlay[name])} B")

        entries = sorted((e for e in jar.infolist() if not e.is_dir() and wanted(e.filename)),
                         key=lambda e: e.filename)
        for entry in entries:
            data = overlay.get(entry.filename) or jar.read(entry)
            index[entry.filename] = [bundle.tell(), len(data)]
            bundle.write(data)
            biggest.append((len(data), entry.filename))

            dest = os.path.join(args.out, entry.filename)
            if os.path.exists(dest) and os.path.getsize(dest) == len(data):
                skipped += 1
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as dst:
                dst.write(data)
            written += 1

        for name in sorted(overlay):
            if name in index or not wanted(name):
                continue
            data = overlay[name]
            index[name] = [bundle.tell(), len(data)]
            bundle.write(data)
            biggest.append((len(data), name))
            dest = os.path.join(args.out, name)
            if os.path.exists(dest) and os.path.getsize(dest) == len(data):
                skipped += 1
                continue
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, "wb") as dst:
                dst.write(data)
            written += 1

        for name, data in sorted(extra_files(args.out).items()):
            index[name] = [bundle.tell(), len(data)]
            bundle.write(data)
            biggest.append((len(data), name))

    index_path = os.path.join(args.out, "assets_index.json")
    with open(index_path, "w") as f:
        json.dump(index, f, sort_keys=True, separators=(",", ":"))

    total = sum(length for _, length in index.values())
    print(f"files:      {len(index)} ({written} extracted, {skipped} already current)")
    print(f"asset size: {total / 1e6:.2f} MB")
    print(f"bundle:     {os.path.getsize(bundle_path) / 1e6:.2f} MB  ({bundle_path})")
    print(f"index size: {os.path.getsize(index_path) / 1e6:.2f} MB  ({index_path})")
    verify(args.out, index)
    if total > 40e6:
        print("OVER the 40MB budget - biggest offenders:", file=sys.stderr)
        for size, name in sorted(biggest, reverse=True)[:20]:
            print(f"  {size / 1e6:7.2f} MB  {name}", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
