#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)

import argparse
import json
import os
import sys

from gen_sound_index import REPO, DEFAULT_ASSETS, newest_index, object_path, stage, download

PREFIX = "minecraft/lang/"
META = "pack.mcmeta"

BUNDLED = "en_us"

def langs_of(objects: dict) -> dict:
    out = {}
    for name, entry in objects.items():
        if name.startswith(PREFIX) and name.endswith(".json"):
            code = name[len(PREFIX):-len(".json")]
            if code != BUNDLED:
                out[code] = entry["hash"]
    return out

def metadata(objects: dict, store: str) -> dict:
    entry = objects.get(META)
    if entry is None:
        raise SystemExit(f"no {META} in the asset index; cannot build the language list")
    path = os.path.join(store, object_path(entry["hash"]))
    if not os.path.exists(path):
        raise SystemExit(f"{META} object missing ({entry['hash']}); re-run with --download")
    with open(path) as f:
        return json.load(f).get("language", {})

def selfcheck() -> int:
    objects = {
        "minecraft/lang/de_de.json": {"hash": "aa" + "0" * 38, "size": 1},
        "minecraft/lang/en_us.json": {"hash": "bb" + "0" * 38, "size": 1},
        "minecraft/sounds/music/game.ogg": {"hash": "cc" + "0" * 38, "size": 1},
        "pack.mcmeta": {"hash": "dd" + "0" * 38, "size": 1},
    }
    got = langs_of(objects)
    assert got == {"de_de": "aa" + "0" * 38}, got
    assert object_path("aa" + "0" * 38).endswith(os.path.join("aa", "aa" + "0" * 38))

    advertised = {c: i for c, i in {"de_de": 1, "fr_fr": 1, "en_us": 1}.items()
                  if c == BUNDLED or c in got}
    assert set(advertised) == {"de_de", "en_us"}, advertised
    print("selfcheck OK (lang entries found, en_us excluded from staging, "
          "unstaged language dropped from the list)")
    return 0

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--selfcheck", action="store_true", help="run the assertions and exit")
    ap.add_argument("--index", help="assets index json (default: newest under --assets/indexes)")
    ap.add_argument("--assets", default=DEFAULT_ASSETS)
    ap.add_argument("--store", help="dir holding objects/<2>/<sha1> (default: --assets)")
    ap.add_argument("--out", default=os.path.join(REPO, "target_teavm_263/javascript"))
    ap.add_argument("--download", action="store_true", help="fetch objects the local store lacks")
    ap.add_argument("--no-stage", action="store_true", help="write the index only, copy nothing")
    args = ap.parse_args()
    if args.selfcheck:
        return selfcheck()

    store = args.store or args.assets
    index_json = args.index or newest_index(args.assets)
    with open(index_json) as f:
        objects = json.load(f)["objects"]
    files = langs_of(objects)
    if not files:
        print(f"no {PREFIX}* entries in {index_json}", file=sys.stderr)
        return 1

    if args.download:
        print(f"downloaded:  {download(files, store)} objects")

    staged_bytes = placed = present = 0
    missing = []
    if not args.no_stage:
        staged_bytes, placed, present, missing = stage(files, store, args.out)
        for code in missing:
            files.pop(code, None)

    languages = metadata(objects, store)
    advertised = {c: v for c, v in languages.items() if c == BUNDLED or c in files}

    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, "lang_index.json")
    with open(path, "w") as f:
        json.dump({"files": files, "languages": advertised}, f, sort_keys=True, separators=(",", ":"))

    print(f"index:      {index_json}")
    print(f"languages:  {len(advertised)} advertised, {len(files)} fetchable + {BUNDLED} bundled")
    if missing:
        print(f"MISSING:    {len(missing)} objects not in the store, dropped from the list "
              f"(e.g. {missing[0]}) — re-run with --download", file=sys.stderr)
    print(f"staged:     {staged_bytes / 1e6:.1f} MB ({placed} placed, {present} already current)")
    print(f"wrote:      {path} ({os.path.getsize(path) / 1e3:.0f} kB)")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
