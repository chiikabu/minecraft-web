#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Stage the playable web folder from a vanilla client jar, the launcher's asset store and a linked engine.

    python scripts/stage_site.py --jar <.minecraft>/versions/26.3/26.3.jar \\
        --assets <.minecraft>/assets --engine <dir with classes263.wasm> --out <new dir>

Everything in the output comes from those three inputs and this repository:

  index.html, minecraft-web*.js, minecraft-web.css,       page layer (target_teavm_263/wasm-gc,
  minecraft_blobstore.js                          scripts/minecraft_blobstore.js)
  boot-logo.png, favicon.png                      drawn from the jar
  wasm-gc/classes263.wasm (+ runtime)             the engine, copied as given; it must already be
                                                  split (port263/split_huge_wasm.py)
  assets.bin.gz, assets_index.json.gz             jar assets, the launcher's title panorama and
                                                  unifont, the shader bake and the WebGPU shaders
  objects.bin.gz, objects_sha.json                English language variants, loaded at boot
  shard_meta.json                                 sha256 of each shard; the page's cache keys
  lang_index.json, sounds_index.json              what objects/ holds
  assets/minecraft/sounds.json                    sound manifest
  objects/<2>/<sha1>                              sounds and languages, fetched one at a time
                                                  (hard links into the launcher store when possible)
  version.json, sounds.json, pipelines.json       not fetched by the page; scripts/make_offline.py
                                                  reads them from a staged folder

Two build steps run the game's own classes on a desktop JVM, so a JDK of the game's Java version
(26.3: Java 25) is needed, plus the launcher's version JSON and libraries. Those are looked for
next to the jar and next to the asset store; --version-json and --libraries name other places.

  * The shader bake (scripts/bake_shaders_263.py). The engine cannot draw without it.
    --shader-bake reuses the baked263.bin of an earlier run and needs no JVM.
  * The pipeline list for the WebGPU shaders, which also need naga (cargo install naga-cli).
    Without naga they are left out: WebGL2, the default, does not use them, and ?webgpu falls
    back to WebGL2.

Nothing is fetched from the network. Sounds or languages the launcher store lacks are counted,
named and left out; --download fetches them (sha1-checked) into the store from the server the
launcher uses.

Serve the result with any static server that sends .gz files as they are (no Content-Encoding):

    python scripts/serve_minecraft_web.py --web <out> --port 8262
"""
import argparse
import gzip
import hashlib
import json
import mmap
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
sys.path.insert(0, str(HERE))
from bake_shaders_263 import library_classpath
from gen_asset_index import object_overlay, wanted
from gen_lang_index import langs_of
from gen_sound_index import download, stage

TEMPLATES = REPO / "target_teavm_263" / "wasm-gc"
PAGE_FILES = ("index.html", "minecraft-web.css", "minecraft-web-net.js", "minecraft-web-shell.js",
              "minecraft-web-touch.js", "minecraft-web-mesh.js", "minecraft-web-mesh-worker.js",
              "minecraft-web-simd-kernels.js",
              "minecraft-web-jvm-translator.js", "minecraft-web-jvm-bridge.js")
ENGINE_FILES = ("classes263.wasm", "classes263.wasm-runtime.js")

ENGINE_DEBUG_FILES = ("classes263.wasm-deobfuscator.wasm", "classes263.wasm.teadbg")
PIPELINE_SOURCE = REPO / "game263" / "src" / "desktop" / "java" / "net" / "minecraft" / "web263" / "WgslManifest.java"
BAKE_ASSET = "assets/minecraft/shaders/baked263.bin"
WGSL_ASSETS = "assets/minecraft/shaders/wgsl/"
SOUNDS = "minecraft/sounds/"
SOUND_MANIFEST = "minecraft/sounds.json"
PACK_META = "pack.mcmeta"

BOOT_PACK_LANGUAGE = re.compile(r"en_[a-z]+")

MAX_FUNCTION_BYTES = 7654321

class StageError(Exception):
    pass

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def compact_json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")

def gz(data):

    return gzip.compress(data, 9, mtime=0)

def megabytes(count):
    return "%.1f MB" % (count / 1e6)

def some(names, limit=4):
    return ", ".join(names[:limit]) + (", ..." if len(names) > limit else "")

def run(command, what, cwd=None):
    result = subprocess.run(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, errors="replace")
    if result.returncode:
        raise StageError("%s failed:\n%s" % (what, result.stdout[-4000:]))
    return result.stdout

def launcher_metadata(args):
    path = args.version_json or args.jar.with_suffix(".json")
    if not path.is_file():
        if args.version_json:
            raise StageError("version JSON not found: %s" % path)
        return None, path
    meta = json.loads(path.read_text(encoding="utf-8"))
    if "inheritsFrom" in meta:
        raise StageError("%s is a mod loader profile; pass the vanilla version JSON" % path)
    return meta, path

def find_asset_index(args, meta):
    indexes = args.assets / "indexes"
    name = args.asset_index or (meta or {}).get("assetIndex", {}).get("id")
    if name:
        path = Path(name) if name.endswith(".json") else indexes / (name + ".json")
        if not path.is_file():
            raise StageError("asset index not found: %s (start this version once in the launcher)" % path)
        return path
    found = sorted(indexes.glob("*.json"))
    if len(found) != 1:
        raise StageError("cannot tell which of the %d asset indexes in %s belongs to this jar; "
                         "pass --asset-index or --version-json" % (len(found), indexes))
    return found[0]

def find_jdk(args, meta, meta_path, needed):
    home = os.environ.get("JAVA_HOME")
    java = shutil.which(args.java or (str(Path(home) / "bin" / "java") if home else "java"))
    if not java:
        return None, "no java found"
    java = Path(java)
    if not java.with_name("javac" + java.suffix).is_file():
        return None, "%s is not a JDK (no javac next to it)" % java
    version = subprocess.run([str(java), "-version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    major = re.search(r'version "(\d+)', version.stdout)
    if not major or int(major.group(1)) < needed:
        return None, "%s is Java %s" % (java, major.group(1) if major else "?")
    if not meta:
        return None, "no launcher version JSON at %s" % meta_path
    if not args.libraries.is_dir():
        return None, "no launcher libraries at %s" % args.libraries
    return java, None

def uleb(data, at):
    value = shift = 0
    while True:
        byte = data[at]
        at += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, at
        shift += 7

def check_engine(folder):
    for name in ENGINE_FILES:
        if not (folder / name).is_file():
            raise StageError("engine file missing: %s" % (folder / name))
    if b"callInFiber" not in (folder / ENGINE_FILES[1]).read_bytes():
        raise StageError("%s is unpatched: the link did not finish (patchWasmGCRuntime)" % ENGINE_FILES[1])
    not_wasm = StageError("%s is not a WebAssembly module (is a link running?)" % ENGINE_FILES[0])
    if (folder / ENGINE_FILES[0]).stat().st_size < 8:
        raise not_wasm
    largest = 0
    with open(folder / ENGINE_FILES[0], "rb") as handle, \
            mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ) as data:
        if data[:8] != b"\0asm\1\0\0\0":
            raise not_wasm
        at = 8
        while at < len(data):
            section = data[at]
            size, at = uleb(data, at + 1)
            if section == 10:
                count, body = uleb(data, at)
                for _ in range(count):
                    length, body = uleb(data, body)
                    largest = max(largest, length)
                    body += length
            at += size
    if largest > MAX_FUNCTION_BYTES:
        raise StageError("%s is not split: its largest function is %d bytes and browsers stop at %d. "
                         "Run port263/split_huge_wasm.py on it first." % (ENGINE_FILES[0], largest, MAX_FUNCTION_BYTES))

def jar_assets(jar, index_path, store):
    entries = sorted((e for e in jar.infolist() if not e.is_dir() and wanted(e.filename)), key=lambda e: e.filename)
    try:
        overlay = object_overlay(jar, str(index_path), str(store / "objects"))
    except (OSError, ValueError) as error:
        raise StageError("the launcher store lacks files the asset bundle needs (title panorama, unifont): %s\n"
                         "Start this version once in the launcher, or re-run with --download." % error)
    files = {e.filename: overlay[e.filename] if e.filename in overlay else jar.read(e) for e in entries}
    files.update((name, overlay[name]) for name in sorted(overlay) if name not in files and wanted(name))
    return files

def bake_shaders(args, java, meta_path, work):
    out = work / "baked263.bin"

    log = run([sys.executable, str(HERE / "bake_shaders_263.py"), "--jar", str(args.jar),
               "--version-json", str(meta_path), "--libraries", str(args.libraries),
               "--java", str(java), "--output", str(out)], "shader bake", cwd=work)
    for line in log.splitlines():
        if line.startswith("[bake] pipelines"):
            print("shader bake: " + line[len("[bake] "):])
    return out.read_bytes()

def pipeline_list(args, java, meta_path, work):
    out = work / "pipelines.json"
    classpath = os.pathsep.join([str(args.jar)] + library_classpath(str(meta_path), str(args.libraries)))
    run([str(java), "-Xmx512m", "--class-path", classpath, str(PIPELINE_SOURCE), str(out)],
        "pipeline list (WgslManifest)", cwd=work)
    return out.read_bytes()

def wgsl_modules(files, pipelines, naga, work):
    from webgpu_generate_wgsl import generate
    shaders = work / "assets" / "minecraft" / "shaders"
    for name, data in files.items():
        if name.startswith(("assets/minecraft/shaders/", "assets/minecraft/post_effect/")):
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    out = shaders / "wgsl"
    renderpearl = any(p.get("backend") == "renderpearl" for p in json.loads(pipelines))
    try:
        generate(shaders, out, Path(naga), work / "pipelines.json", emit_locations=renderpearl)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        raise StageError("WebGPU shader translation failed: %s" % error)
    return {WGSL_ASSETS + path.relative_to(out).as_posix(): path.read_bytes()
            for path in sorted(out.rglob("*")) if path.is_file()}

def bundle(entries):
    index, parts, offset = {}, [], 0
    for name, data in entries:
        index[name] = [offset, len(data)]
        parts.append(data)
        offset += len(data)
    return b"".join(parts), index

def intact(store, objects, name):
    entry = objects.get(name)
    if not entry:
        return None
    digest = entry["hash"]
    path = store / "objects" / digest[:2] / digest
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if len(data) != entry["size"] or hashlib.sha1(data).hexdigest() != digest:
        return None
    return path

def split_present(store, objects, names):
    present, missing = [], []
    for name in sorted(names):
        (present if intact(store, objects, name) else missing).append(name)
    return present, missing

def selected(args, objects):
    codes = {"minecraft/lang/%s.json" % code: code for code in langs_of(objects)
             if args.languages == "all" or BOOT_PACK_LANGUAGE.fullmatch(code)}
    sounds = [name for name in objects if name.startswith(SOUNDS)] if args.sounds == "all" else []
    return codes, sounds

def launcher_content(args, objects, notes):
    store = args.assets
    codes, names = selected(args, objects)
    listing = {}
    pack_meta = intact(store, objects, PACK_META)
    if pack_meta:
        listing = json.loads(pack_meta.read_text(encoding="utf-8")).get("language", {})
    else:
        notes.append("%s is not in the launcher store: English (US) only" % PACK_META)
        codes = {}
    present, missing = split_present(store, objects, codes)
    if missing:
        notes.append("%d languages missing or damaged in the launcher store, left out: %s"
                     % (len(missing), some([codes[name] for name in missing])))
    languages = {codes[name]: objects[name]["hash"] for name in present}
    listing = {code: entry for code, entry in listing.items() if code == "en_us" or code in languages}

    manifest = intact(store, objects, SOUND_MANIFEST)
    if names and not manifest:
        notes.append("%s is not in the launcher store: no sounds" % SOUND_MANIFEST)
        names = []
    present, lost = split_present(store, objects, names)
    if lost:
        notes.append("%d of %d sounds missing or damaged in the launcher store, left out: %s"
                     % (len(lost), len(names), some(lost)))
    sounds = {name: objects[name]["hash"] for name in present}
    if sounds:
        sound_manifest = manifest.read_bytes()
    else:

        events = json.loads(manifest.read_text(encoding="utf-8")) if manifest else {}
        for event in events.values():
            event["sounds"] = []
        sound_manifest = compact_json(events)
    if missing or lost:
        notes.append("what is still left out is damaged in the store: delete those objects there and re-run"
                     if args.download else "--download fetches what the launcher store lacks")
    return languages, listing, sounds, sound_manifest

def page_images(jar, out):
    from io import BytesIO
    from PIL import Image
    studio = Image.open(BytesIO(jar.read("assets/minecraft/textures/gui/title/mojangstudios.png"))).convert("RGBA")
    half = studio.height // 2
    logo = Image.new("RGBA", (studio.width * 2, half), (0, 0, 0, 0))
    logo.paste(studio.crop((0, 0, studio.width, half)), (0, 0))
    logo.paste(studio.crop((0, half, studio.width, studio.height)), (studio.width, 0))
    logo.save(out / "boot-logo.png", optimize=True)
    grass = Image.open(BytesIO(jar.read("assets/minecraft/textures/block/grass_block_side.png"))).convert("RGBA")
    grass.resize((192, 192), Image.NEAREST).save(out / "favicon.png", optimize=True)

def stage_page(args, jar, out):
    sources = [(TEMPLATES / name, name) for name in PAGE_FILES]
    sources.append((HERE / "minecraft_blobstore.js", "minecraft_blobstore.js"))
    for source, name in sources:

        (out / name).write_bytes(source.read_bytes().replace(b"\r\n", b"\n"))
    try:
        page_images(jar, out)
    except ImportError as error:
        raise StageError("the boot logo and favicon need Pillow (pip install pillow): %s" % error)
    engine = out / "wasm-gc"
    engine.mkdir()
    for name in ENGINE_FILES + ENGINE_DEBUG_FILES:
        if (args.engine / name).is_file():
            shutil.copyfile(args.engine / name, engine / name)
    html = (out / "index.html").read_text(encoding="utf-8")
    for needle in ("id=\"credit\"", "__minecraftInstallWebStore"):
        if needle not in html:
            raise StageError("index.html is not the shard launcher (%s missing)" % needle)
    for ref in re.findall(r'(?:src|href)="([^":?#]+)"', html):
        if not (out / ref).is_file():
            raise StageError("index.html loads %s, which was not staged" % ref)

def build(args, out, work):
    with open(REPO / "port_version.json", encoding="utf-8") as handle:
        port_version = json.load(handle)["version"]
    notes = []

    check_engine(args.engine)
    meta, meta_path = launcher_metadata(args)
    index_path = find_asset_index(args, meta)
    objects = json.loads(index_path.read_text(encoding="utf-8"))["objects"]
    store = args.assets

    with zipfile.ZipFile(args.jar) as jar:
        version_json = jar.read("version.json")
        version = json.loads(version_json)
        if version["id"] != port_version:
            raise StageError("this port is for Minecraft %s; the jar is %s" % (port_version, version["id"]))
        client = (meta or {}).get("downloads", {}).get("client", {})
        if client.get("sha1") and hashlib.sha1(args.jar.read_bytes()).hexdigest() != client["sha1"]:
            notes.append("the jar is not the vanilla client that %s names" % meta_path.name)

        if args.download:
            jar_names = {e.filename for e in jar.infolist()}
            codes, names = selected(args, objects)
            names = set(names)
            needed = {name: entry["hash"] for name, entry in objects.items()
                      if name in codes or name in names or name in (PACK_META, SOUND_MANIFEST)
                      or "assets/" + name in jar_names or name.startswith("minecraft/font/")}
            print("downloaded %d objects into %s" % (download(needed, str(store)), store))

        files = jar_assets(jar, index_path, store)
        java, why = find_jdk(args, meta, meta_path, int(version.get("java_version", 0)))
        if args.shader_bake:
            baked = args.shader_bake.read_bytes()
        elif java:
            baked = bake_shaders(args, java, meta_path, work)
        else:
            raise StageError("the shader bake runs the game's shader compiler on a Java %s JDK (%s). "
                             "Pass --java <jdk>/bin/java, or --shader-bake <baked263.bin> from an earlier run."
                             % (version.get("java_version"), why))
        if baked[:4] != b"MCSB":
            raise StageError("the shader bake has the wrong header")
        pipelines = pipeline_list(args, java, meta_path, work) if java else None
        if pipelines and args.naga:
            files.update(wgsl_modules(files, pipelines, args.naga, work))
        elif pipelines:
            notes.append("no WebGPU shaders (naga not found, see --naga); WebGL2, the default, does not use them")
        else:
            notes.append("no pipelines.json and no WebGPU shaders (%s); WebGL2, the default, needs neither" % why)
        files[BAKE_ASSET] = baked
        assets, assets_index = bundle(files.items())
        index_json = compact_json(assets_index)

        languages, listing, sounds, sound_manifest = launcher_content(args, objects, notes)

        merged = ["assets/" + SOUND_MANIFEST] + ["assets/" + name for name in sounds] \
            + ["assets/minecraft/lang/%s.json" % code for code in languages]
        if set(merged) & set(assets_index):
            raise StageError("launcher objects collide with bundled assets: %s"
                             % some(sorted(set(merged) & set(assets_index))))
        packed = sorted(digest for code, digest in languages.items() if BOOT_PACK_LANGUAGE.fullmatch(code))
        objects_bin, objects_map = bundle((digest, (store / "objects" / digest[:2] / digest).read_bytes())
                                          for digest in packed)
        objects_map_json = compact_json(objects_map)

        shard_meta = {
            "v": 1,
            "indexSha256": sha256(index_json),
            "assetsSha256": sha256(assets),
            "objectsSha256": sha256(objects_bin),
            "objectsMapSha256": sha256(objects_map_json),
            "shards": [
                {"key": "index", "url": "assets_index.json.gz", "gzip": True},
                {"key": "assets", "url": "assets.bin.gz", "gzip": True},
                {"key": "objects", "url": "objects.bin.gz", "gzip": True},
                {"key": "objectsMap", "url": "objects_sha.json", "gzip": False},
            ],
        }
        outputs = {
            "assets.bin.gz": gz(assets),
            "assets_index.json.gz": gz(index_json),
            "objects.bin.gz": gz(objects_bin),
            "objects_sha.json": objects_map_json,
            "shard_meta.json": json.dumps(shard_meta, separators=(",", ":")).encode() + b"\n",
            "lang_index.json": compact_json({"files": languages, "languages": listing}),
            "sounds_index.json": compact_json(sounds),
            "assets/minecraft/sounds.json": sound_manifest,

            "version.json": version_json,
            "sounds.json": sound_manifest,
        }
        if pipelines:
            outputs["pipelines.json"] = pipelines
        if args.raw_assets:
            outputs["assets.bin"] = assets
            outputs["assets_index.json"] = index_json
        for name, data in outputs.items():
            path = out / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

        loose = dict(sounds)
        loose.update((code, digest) for code, digest in languages.items() if digest not in objects_map)
        loose_bytes, _, _, gone = stage(loose, str(store), str(out))
        if gone:
            raise StageError("objects disappeared from the launcher store while staging: %s" % some(gone))
        stage_page(args, jar, out)

    print("assets:    %d files, %s (%s gzipped)" % (len(assets_index), megabytes(len(assets)),
                                                   megabytes(len(outputs["assets.bin.gz"]))))
    print("languages: %d (%d in the boot pack, %s)" % (len(languages) + 1, len(packed), megabytes(len(objects_bin))))
    print("sounds:    %d" % len(sounds))
    print("objects/:  %d files, %s" % (len(loose), megabytes(loose_bytes)))
    print("engine:    %s" % megabytes((out / "wasm-gc" / ENGINE_FILES[0]).stat().st_size))
    for note in notes:
        print("NOTE: " + note)

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--jar", type=Path, required=True, help="vanilla client jar")
    parser.add_argument("--assets", type=Path, required=True, help="launcher asset store (indexes/ and objects/)")
    parser.add_argument("--engine", type=Path, required=True,
                        help="folder with the split classes263.wasm and classes263.wasm-runtime.js")
    parser.add_argument("--out", type=Path, required=True, help="output folder; must not exist yet")
    parser.add_argument("--version-json", type=Path, help="launcher version JSON (default: next to the jar)")
    parser.add_argument("--libraries", type=Path, help="launcher libraries folder (default: next to the asset store)")
    parser.add_argument("--asset-index", help="asset index id or file (default: the one the version JSON names)")
    parser.add_argument("--java", help="java of a JDK for the game's Java version (default: JAVA_HOME, then PATH)")
    parser.add_argument("--shader-bake", type=Path, help="baked263.bin from an earlier run; skips the bake")
    parser.add_argument("--naga", default=os.environ.get("NAGA") or shutil.which("naga"),
                        help="naga binary for the WebGPU shaders (default: NAGA, then PATH)")
    parser.add_argument("--sounds", choices=("all", "none"), default="all",
                        help="all: every sound in the launcher store, under objects/; none: a silent build")
    parser.add_argument("--languages", choices=("all", "english"), default="all",
                        help="all: every language in the launcher store; english: the English variants only")
    parser.add_argument("--download", action="store_true",
                        help="fetch objects the launcher store lacks from the launcher's download server")
    parser.add_argument("--raw-assets", action="store_true",
                        help="also write assets.bin and assets_index.json uncompressed")
    args = parser.parse_args()
    for name in ("jar", "assets", "engine", "out", "version_json", "libraries", "shader_bake"):
        if getattr(args, name):
            setattr(args, name, getattr(args, name).resolve())
    args.libraries = args.libraries or args.assets.parent / "libraries"

    out = args.out
    staging = None
    try:
        if not args.jar.is_file():
            raise StageError("client jar not found: %s" % args.jar)
        if not (args.assets / "indexes").is_dir() or not (args.assets / "objects").is_dir():
            raise StageError("%s is not a launcher asset store (no indexes/ and objects/)" % args.assets)
        if out.exists() and (not out.is_dir() or any(out.iterdir())):
            raise StageError("%s already exists; --out must be a new folder" % out)
        out.parent.mkdir(parents=True, exist_ok=True)

        staging = Path(tempfile.mkdtemp(prefix=out.name + ".", suffix=".staging", dir=out.parent))
        with tempfile.TemporaryDirectory(prefix="stage-site-") as work:
            build(args, staging, Path(work))
        if out.exists():
            out.rmdir()
        staging.rename(out)
        staging = None
    except (StageError, OSError, KeyError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        print("stage_site: %s" % error, file=sys.stderr)
        return 1
    finally:
        if staging:
            shutil.rmtree(staging, ignore_errors=True)
    print("staged %s" % out)
    print("serve:  python scripts/serve_minecraft_web.py --web %s --port 8262" % out)
    return 0

if __name__ == "__main__":
    sys.exit(main())
