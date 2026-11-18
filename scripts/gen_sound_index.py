#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)

import argparse
import glob
import hashlib
import json
import os
import shutil
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

CDN = "https://resources.download.minecraft.net/"

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_ASSETS = os.path.join(REPO, "game263/run/assets")
PREFIX = "minecraft/sounds/"

MANIFEST = "minecraft/sounds.json"

def newest_index(assets_dir: str) -> str:
    candidates = glob.glob(os.path.join(assets_dir, "indexes", "*.json"))
    if not candidates:
        raise SystemExit(f"no assets index under {assets_dir}/indexes")
    return max(candidates, key=lambda p: os.path.getmtime(p))

def object_path(sha1: str) -> str:
    return os.path.join("objects", sha1[:2], sha1)

def sounds_of(index_json: str) -> dict:
    with open(index_json) as f:
        objects = json.load(f)["objects"]
    return {k: v["hash"] for k, v in objects.items() if k.startswith(PREFIX)}

def download(sounds: dict, store: str) -> int:
    todo = [
        (sha1, os.path.join(store, object_path(sha1)))
        for sha1 in dict.fromkeys(sounds.values())
        if not os.path.exists(os.path.join(store, object_path(sha1)))
    ]
    if not todo:
        return 0

    def fetch(job):
        sha1, dst = job
        with urllib.request.urlopen(CDN + sha1[:2] + "/" + sha1, timeout=30) as r:
            data = r.read()
        if hashlib.sha1(data).hexdigest() != sha1:
            print(f"sha1 mismatch, dropped: {sha1}", file=sys.stderr)
            return 0
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst + ".part", "wb") as f:
            f.write(data)
        os.replace(dst + ".part", dst)
        return 1

    print(f"downloading: {len(todo)} objects from {CDN}")
    with ThreadPoolExecutor(max_workers=8) as pool:
        return sum(pool.map(fetch, todo))

def stage_manifest(sha1: str, store: str, out: str) -> None:
    src = os.path.join(store, object_path(sha1))
    dst = os.path.join(out, "assets", MANIFEST)
    if not os.path.exists(src):
        print(f"MISSING {MANIFEST} ({sha1}) — re-run with --download; "
              "without it SoundManager registers no sound events", file=sys.stderr)
        return
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)
    print(f"manifest:   assets/{MANIFEST} ({os.path.getsize(dst) / 1e3:.0f} kB)")

def stage(sounds: dict, store: str, out: str) -> tuple:
    total = placed = present = 0
    missing = []
    for path, sha1 in sounds.items():
        rel = object_path(sha1)
        src = os.path.join(store, rel)
        if not os.path.exists(src):
            missing.append(path)
            continue
        size = os.path.getsize(src)
        total += size
        dst = os.path.join(out, rel)
        if os.path.exists(dst) and os.path.getsize(dst) == size:
            present += 1
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        try:
            if os.path.exists(dst):
                os.remove(dst)
            os.link(src, dst)
        except OSError:
            shutil.copyfile(src, dst)
        placed += 1
    return total, placed, present, missing

def verify(sounds: dict, store: str, out: str, samples: int = 5) -> None:
    picks = sorted(sounds)[:: max(1, len(sounds) // samples)][:samples]
    for path in picks:
        rel = object_path(sounds[path])
        with open(os.path.join(store, rel), "rb") as f:
            want = f.read()
        with open(os.path.join(out, rel), "rb") as f:
            got = f.read()
        assert got == want, f"staged object != store object for {path}"
    print(f"verify:     {len(picks)} staged objects byte-match the store")

def check_merge(sounds: dict, assets_index: str) -> None:
    with open(assets_index) as f:
        asset_keys = list(json.load(f))
    sound_keys = ["assets/" + MANIFEST] + ["assets/" + k for k in sounds]
    dupes = set(asset_keys) & set(sound_keys)
    assert not dupes, f"sound paths already in the asset bundle: {sorted(dupes)[:5]}"
    merged = sorted(asset_keys + sound_keys)
    assert all(a < b for a, b in zip(merged, merged[1:])), "merged index is not strictly sorted"

    assert min(sound_keys) == "assets/" + MANIFEST, "manifest does not sort first"
    print(f"merge:      {len(sound_keys)} sound keys splice into {len(asset_keys)} asset keys, sorted and disjoint")

def selfcheck() -> int:
    assert object_path("1058cf72714c48b154e7883cb9d7ec918e0816b3") == os.path.join(
        "objects", "10", "1058cf72714c48b154e7883cb9d7ec918e0816b3"
    )
    fake = {
        "objects": {
            "minecraft/sounds/ambient/cave/cave1.ogg": {"hash": "aa" + "0" * 38, "size": 1},
            "minecraft/sounds/music/game.ogg": {"hash": "bb" + "0" * 38, "size": 2},
            "icons/icon_16x16.png": {"hash": "cc" + "0" * 38, "size": 3},
            "minecraft/lang/de_de.json": {"hash": "dd" + "0" * 38, "size": 4},
        }
    }
    import tempfile

    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        json.dump(fake, f)
        tmp = f.name
    got = sounds_of(tmp)
    os.remove(tmp)
    assert set(got) == {
        "minecraft/sounds/ambient/cave/cave1.ogg",
        "minecraft/sounds/music/game.ogg",
    }, got
    assert got["minecraft/sounds/music/game.ogg"] == "bb" + "0" * 38
    print("selfcheck OK (2 sounds kept, icons + lang dropped, url derivation exact)")
    return 0

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selfcheck", action="store_true", help="run the assertions and exit")
    ap.add_argument("--index", help="assets index json (default: newest under --assets/indexes)")
    ap.add_argument("--assets", default=DEFAULT_ASSETS)
    ap.add_argument("--store", help="dir holding objects/<2>/<sha1> (default: --assets); "
                                    "--download writes here, so point it at a writable cache")
    ap.add_argument("--out", default=os.path.join(REPO, "target_teavm_263/javascript"))
    ap.add_argument("--assets-index", help="assets_index.json to check the merge against "
                                           "(default: <out>/assets_index.json, skipped if absent)")
    ap.add_argument("--no-stage", action="store_true", help="write the index only, copy nothing")
    ap.add_argument("--download", action="store_true", help="fetch objects the local store lacks")
    ap.add_argument("--only", nargs="+", metavar="PREFIX",
                    help="stage/download only sounds under these prefixes (the index stays whole)")
    args = ap.parse_args()
    if args.selfcheck:
        return selfcheck()
    store = args.store or args.assets

    index_json = args.index or newest_index(args.assets)
    with open(index_json) as f:
        all_objects = {k: v["hash"] for k, v in json.load(f)["objects"].items()}
    sounds = {k: v for k, v in all_objects.items() if k.startswith(PREFIX)}
    if not sounds:
        print(f"no {PREFIX}* entries in {index_json}", file=sys.stderr)
        return 1

    wanted = sounds
    if args.only:
        keep = tuple(p if p.startswith(PREFIX) else PREFIX + p for p in args.only)
        wanted = {k: v for k, v in sounds.items() if k.startswith(keep)}
        print(f"--only:     {len(wanted)} of {len(sounds)} sounds selected for staging")

    index_path = os.path.join(args.out, "sounds_index.json")
    os.makedirs(args.out, exist_ok=True)
    with open(index_path, "w") as f:
        json.dump(sounds, f, sort_keys=True, separators=(",", ":"))

    print(f"index:      {index_json}")
    print(f"sounds:     {len(sounds)}")
    print(f"index size: {os.path.getsize(index_path) / 1e6:.2f} MB  ({index_path})")
    assets_index = args.assets_index or os.path.join(args.out, "assets_index.json")
    if os.path.exists(assets_index):
        check_merge(sounds, assets_index)
    else:
        print(f"merge:      skipped, no {assets_index} (run scripts/gen_asset_index.py first)")
    if args.no_stage:
        return 0

    manifest = {MANIFEST: all_objects[MANIFEST]}
    if args.download:
        print(f"fetched:    {download({**wanted, **manifest}, store)} objects")
    stage_manifest(manifest[MANIFEST], store, args.out)
    total, placed, present, missing = stage(wanted, store, args.out)
    print(f"objects:    {placed} staged, {present} already current, {len(missing)} missing")
    print(f"audio size: {total / 1e6:.2f} MB  ({os.path.join(args.out, 'objects')})")
    if missing:
        print(f"MISSING from the object store (first 5): {missing[:5]}", file=sys.stderr)
        print("re-run with --download to fetch them", file=sys.stderr)
    staged = {k: v for k, v in wanted.items() if k not in set(missing)}
    if staged:
        verify(staged, store, args.out)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
