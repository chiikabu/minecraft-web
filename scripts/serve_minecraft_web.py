#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Serve a staged web build the way it should be hosted.

  * Compressed engine. classes263.wasm is 140 MB raw, ~31 MB gzipped, less with Brotli; the server
    worker is another 109 MB. Precompressed .br/.gz siblings are made once (--precompress) and sent
    with Content-Encoding to browsers that accept them. This is the biggest load-time win on a
    real connection; use the same files and headers with any CDN.
  * Share links. /play/<server> and /r/<world> return the game page with link-specific preview
    tags ("Join <server> on Minecraft Web"), so posts on X get a proper card. The page itself then
    rewrites the URL and joins the server (minecraft-web-shell.js).
  * /api/* answers 503 hosting_disabled until world hosting launches (hosting/api.py).

    python3 scripts/serve_minecraft_web.py --precompress            # once per build
    python3 scripts/serve_minecraft_web.py --port 8262 [--origin https://minecraft-web.example]

Python's http.server is fine for testing and small deployments; put a CDN in front for more.
"""
import argparse
import email.utils
import gzip
import html
import http.server
import json
import os
import re
import shutil
import socketserver
import subprocess
import sys
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, "target_teavm_263", "javascript-wasm-gc")
COMPRESSIBLE = re.compile(r"\.(wasm|js|json|css|html|txt|teadbg|wgsl)$")
LINK = re.compile(r"^(?P<prefix>/(?:.*/)?)(?P<kind>play|r)/(?P<id>[^/?#]+)/?$")
TYPES = {".wasm": "application/wasm", ".js": "text/javascript", ".json": "application/json",
         ".css": "text/css", ".html": "text/html; charset=utf-8", ".png": "image/png",
         ".gz": "application/gzip", ".zst": "application/zstd", ".bin": "application/octet-stream",
         ".jar": "application/java-archive", ".zip": "application/zip", ".wgsl": "text/plain; charset=utf-8"}
LOCAL_CORS_ORIGIN_RE = re.compile(r"^http://(?:localhost|127\.0\.0\.1|\[::1\])(?::\d{1,5})?$", re.IGNORECASE)

def _zstd_packer():
    try:
        import zstandard
        cctx = zstandard.ZstdCompressor(level=15)
        return cctx.compress
    except ImportError:
        pass
    try:
        from compression import zstd as stdlib_zstd
        return lambda d: stdlib_zstd.compress(d, level=15)
    except ImportError:
        pass
    zstd_bin = shutil.which("zstd")
    if zstd_bin:
        def pack_cli(data):
            proc = subprocess.run([zstd_bin, "-15", "-c"], input=data, capture_output=True, check=True)
            return proc.stdout
        return pack_cli
    return None

def precompress(web):
    try:
        import brotli
    except ImportError:
        brotli = None
    zstd_pack = _zstd_packer()
    for folder, _, files in os.walk(web, followlinks=False):
        for name in files:
            path = os.path.join(folder, name)
            if not COMPRESSIBLE.search(name) or os.path.getsize(path) < 1024:
                continue
            with open(path, "rb") as handle:
                data = handle.read()
            mtime = os.path.getmtime(path)
            for suffix, pack in ((".gz", lambda d: gzip.compress(d, 9)),
                                 (".br", (lambda d: brotli.compress(d, quality=9, lgwin=24)) if brotli else None),
                                 (".zst", zstd_pack)):
                out = path + suffix
                if pack is None or os.path.exists(out) and os.path.getmtime(out) >= mtime:
                    continue
                packed = pack(data)
                with open(out + ".part", "wb") as handle:
                    handle.write(packed)
                os.replace(out + ".part", out)
                print("  %-60s %8.1f MB -> %6.1f MB %s" % (os.path.relpath(path, web), len(data) / 1e6, len(packed) / 1e6, suffix))

def link_meta(page, kind, ident, origin, prefix):
    ident = urllib.parse.unquote(ident)[:200]
    title = ("Join %s on Minecraft Web" % ident) if kind == "play" else "Join this Minecraft Web world"
    description = "Open the link and you're in: Minecraft in your browser, no download."
    url = origin + prefix + kind + "/" + urllib.parse.quote(ident, safe="")
    image = origin + prefix + "minecraft-web-card.png"
    swaps = {
        r'(<meta property="og:title" content=")[^"]*'.encode(): title,
        r'(<meta name="twitter:title" content=")[^"]*'.encode(): title,
        r'(<meta property="og:description" content=")[^"]*'.encode(): description,
        r'(<meta name="twitter:description" content=")[^"]*'.encode(): description,
        r'(<meta property="og:image" content=")[^"]*'.encode(): image,
        r'(<meta name="twitter:image" content=")[^"]*'.encode(): image,
    }
    for pattern, value in swaps.items():
        page = re.sub(pattern, lambda m: m.group(1) + html.escape(value, quote=True).encode(), page, count=1)

    head = b'<head>\n\t<base href="%s" />\n\t<meta property="og:url" content="%s" />' % (
        html.escape(prefix, quote=True).encode(), html.escape(url, quote=True).encode())
    return page.replace(b"<head>", head, 1)

class Handler(http.server.SimpleHTTPRequestHandler):
    origin = None

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=WEB, **kwargs)

    def public_origin(self):
        if self.origin:
            return self.origin
        host = re.sub(r"[^A-Za-z0-9.:\[\]-]", "", self.headers.get("Host", "localhost"))
        return "http://" + host

    def end_headers(self):

        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        super().end_headers()

    def guess_type(self, path):
        return TYPES.get(os.path.splitext(path)[1], super().guess_type(path))

    def _send_mod_cors(self):
        origin = self.headers.get("Origin", "")
        if origin and LOCAL_CORS_ORIGIN_RE.match(origin):
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Mod-Filename")
            self.send_header("Access-Control-Max-Age", "600")

    def do_OPTIONS(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/api/mods" or path.startswith("/api/mods/"):
            self.send_response(204)
            self._send_mod_cors()
            self.send_header("Content-Length", "0")
            self.end_headers()
            return None
        self.send_error(405)
        return None

    def do_HEAD(self):
        path = urllib.parse.urlsplit(self.path).path
        if self.send_encoded(path, head_only=True):
            return None
        if self.send_static_etag(path, head_only=True):
            return None
        return super().do_HEAD()

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == "/api/mods/status":
            return self.json(200, {"available": False, "offlineTranslator": True, "mode": "offline"}, cors=True)
        if path == "/wasm-profiles.json" and not os.path.isfile(self.translate_path(path)):
            return self.json(200, {"profiles": [{"id": "vanilla", "name": "vanilla", "clientWasm": "wasm-gc/classes263.wasm"}]})
        if path.startswith("/api/"):
            return self.json(503, {"error": "hosting_disabled"})
        link = LINK.match(path)
        if link:
            return self.share_page(link)
        if path in ("/", "/index.html"):
            return self.share_page(None)
        if self.send_encoded(path):
            return None
        if self.send_static_etag(path):
            return None
        return super().do_GET()

    def _etag(self, st, encoding=None):
        suffix = ("-" + encoding) if encoding else ""
        return '"%x-%x%s"' % (st.st_mtime_ns, st.st_size, suffix)

    def _if_none_match(self, etag):
        raw = self.headers.get("If-None-Match")
        if not raw:
            return False
        target = etag[2:] if etag.startswith("W/") else etag
        for token in raw.split(","):
            t = token.strip()
            if t == "*":
                return True
            if t.startswith("W/"):
                t = t[2:]
            if t == target:
                return True
        return False

    def _accepted_encodings(self):
        accept = self.headers.get("Accept-Encoding", "")
        out = set()
        for part in accept.split(","):
            segs = [s.strip().lower() for s in part.split(";")]
            enc = segs[0]
            if not enc:
                continue
            q = 1.0
            for s in segs[1:]:
                if s.startswith("q="):
                    try:
                        q = float(s[2:])
                    except ValueError:
                        q = 0.0
            if q > 0:
                out.add(enc)
        return out

    def _stream_file(self, path):
        with open(path, "rb") as handle:
            while True:
                chunk = handle.read(1 << 20)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def send_static_etag(self, path, head_only=False):
        local = self.translate_path(path)
        if not os.path.isfile(local):
            return False
        st = os.stat(local)
        etag = self._etag(st)
        stamp = email.utils.formatdate(st.st_mtime, usegmt=True)
        inm = self.headers.get("If-None-Match")
        if self._if_none_match(etag) or (not inm and self.headers.get("If-Modified-Since") == stamp):
            self.send_response(304)
            self.send_header("ETag", etag)
            self.send_header("Last-Modified", stamp)
            if COMPRESSIBLE.search(local):
                self.send_header("Vary", "Accept-Encoding")
            self.end_headers()
            return True
        self.send_response(200)
        self.send_header("Content-Type", self.guess_type(local))
        self.send_header("Content-Length", str(st.st_size))
        self.send_header("Last-Modified", stamp)
        self.send_header("ETag", etag)
        if COMPRESSIBLE.search(local):
            self.send_header("Vary", "Accept-Encoding")
        self.end_headers()
        if not head_only:
            self._stream_file(local)
        return True

    def json(self, status, data, cors=False):
        body = json.dumps(data).encode()
        self.send_response(status)
        if cors:
            self._send_mod_cors()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def share_page(self, link):
        with open(os.path.join(WEB, "index.html"), "rb") as handle:
            page = handle.read()
        origin = self.public_origin()
        if link:
            page = link_meta(page, link.group("kind"), link.group("id"), origin, link.group("prefix"))
        else:

            page = page.replace(b'content="minecraft-web-card.png"', b'content="%s/minecraft-web-card.png"' % origin.encode())
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(page)))
        self.end_headers()
        self.wfile.write(page)

    def send_encoded(self, path, head_only=False):
        local = self.translate_path(path)
        if not os.path.isfile(local) or not COMPRESSIBLE.search(local):
            return False
        accepted = self._accepted_encodings()
        local_mtime = os.path.getmtime(local)
        for suffix, encoding in ((".zst", "zstd"), (".br", "br"), (".gz", "gzip")):
            packed = local + suffix
            if encoding in accepted and os.path.isfile(packed) and os.path.getmtime(packed) >= local_mtime:
                st = os.stat(packed)
                etag = self._etag(st, encoding)
                stamp = email.utils.formatdate(local_mtime, usegmt=True)
                inm = self.headers.get("If-None-Match")
                if self._if_none_match(etag) or (not inm and self.headers.get("If-Modified-Since") == stamp):
                    self.send_response(304)
                    self.send_header("ETag", etag)
                    self.send_header("Last-Modified", stamp)
                    self.send_header("Vary", "Accept-Encoding")
                    self.end_headers()
                    return True
                self.send_response(200)
                self.send_header("Content-Type", self.guess_type(local))
                self.send_header("Content-Encoding", encoding)
                self.send_header("Vary", "Accept-Encoding")
                self.send_header("Content-Length", str(st.st_size))
                self.send_header("Last-Modified", stamp)
                self.send_header("ETag", etag)
                self.end_headers()
                if not head_only:
                    self._stream_file(packed)
                return True
        return False

    def log_message(self, fmt, *args):
        if "--quiet" not in sys.argv:
            super().log_message(fmt, *args)

class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True

def main():
    global WEB
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--web", default=WEB)
    parser.add_argument("--port", type=int, default=8262)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--origin", help="public origin for preview tags, e.g. https://minecraft-web.example")
    parser.add_argument("--precompress", action="store_true", help="write .gz/.br files, then exit")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()
    WEB = os.path.abspath(args.web)
    if args.precompress:
        precompress(WEB)
        return 0
    Handler.origin = args.origin.rstrip("/") if args.origin else None
    print("Minecraft Web on http://%s:%d/  (share links: /play/<server>)" % (args.bind, args.port))
    Server((args.bind, args.port), Handler).serve_forever()
    return 0

if __name__ == "__main__":
    sys.exit(main())
