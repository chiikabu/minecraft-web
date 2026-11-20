# minecraft-web

By [chiikabu](https://github.com/chiikabu).

Minecraft: Java Edition 26.3 in a browser tab, compiled to WebAssembly. Singleplayer, LAN and
server play, resource packs, shader packs, touch controls.

It runs in Chrome, Edge, Brave, Opera and Safari. Firefox cannot load it yet: it runs out of
memory compiling the game.

There is no Minecraft code and there are no Minecraft assets in this repository. It holds the
browser platform layer, the build tooling and a set of patches. To get something playable you
need your own copy of the game: the build reads the 26.3 client, its libraries and its assets
from the official launcher's folder on your machine and compiles the site from them.

## You need

- Minecraft: Java Edition, with 26.3 started once from the official launcher
- JDK 17 and JDK 25 (Temurin is what this was built with)
- Python 3.10+ with Pillow (`pip install pillow`), Git and Node.js
- 16 GB of RAM; the link step alone takes about 12

## Build

```sh
python build.py all --jdk17 /path/to/jdk-17 --jdk25 /path/to/jdk-25
```

The launcher folder is found automatically; pass `--minecraft-dir` if yours is somewhere else.
A full build takes around half an hour, most of it in the link step. When it finishes:

```sh
python scripts/serve_minecraft_web.py --web site
```

and open http://127.0.0.1:8262.

The steps can be run one at a time (`sources`, `jar`, `compiler`, `link`, `site`); `python
build.py -h` says what each does. `python scripts/make_offline.py --web site`
packs a finished build into a single HTML file and needs [Zig](https://ziglang.org) on the path.

## How it works

`build.py` decompiles the couple of hundred game classes this port changes, straight from your
jar, and applies the edit lists in `patches/` to them. An edit list is only offsets and inserted
text, so it is useless without the jar it was made for. The patched classes are compiled back
into a copy of the jar, and a patched [TeaVM](https://teavm.org) links that together with the
platform code in `target_teavm_263/` (WebGL 2 renderer backend, audio, input, storage, networking
over WebSockets) into one WebAssembly module.

Multiplayer goes through a WebSocket relay because a page cannot open TCP sockets. None is
configured here: add your own in the in-game relay list, or bake one in with
`scripts/encode_relays.py`.

## Please don't

Don't host your build for other people. It contains Mojang's game, which you are not allowed to
redistribute. This project is not affiliated with Mojang or Microsoft.

## Credits

Made by chiikabu ([github.com/chiikabu](https://github.com/chiikabu)).

The credit is on the loading screen, on the title screen and behind the Credits button, and it is
part of the build: values the game needs at start-up are decoded with it, so a build with the
credit edited out does not start. If you fork this, leave it in.

## License

Apache-2.0 for the code in this repository. Third-party notices are in `NOTICE`.
