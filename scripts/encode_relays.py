#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Write the built-in relay table into target_teavm_263/wasm-gc/minecraft-web-net.js, encoded.

The engine only knows built-in relays as "Relay 1", "Relay 2", ... (placeholders); the page holds
their real hosts, XOR-encoded with a fresh random key on every run so no host appears as text in
the shipped page or in a search of it. Order matters: the first line is Relay 1.

    python scripts/encode_relays.py wss://relay-a.example wss://relay-b.example:8443
    python scripts/encode_relays.py --file relays.local.txt      # one per line, # comments allowed
    python scripts/encode_relays.py --list                         # decode and print the current table

Keep the plain list out of git (relays.local.txt is ignored). Encoding is obfuscation, not secrecy:
the browser still connects to the real host, which the network tab shows while it is connected.
"""
import argparse
import base64
import os
import re
import secrets
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "target_teavm_263", "wasm-gc", "minecraft-web-net.js")
BLOCK = re.compile(r"(// @@RELAYS-BEGIN[^\n]*\n)(.*?)(\t// @@RELAYS-END)", re.S)

def canonical(entry):
    s = entry.strip()
    if not s or s.startswith("#"):
        return None
    if "://" not in s:
        s = "wss://" + s
    m = re.match(r"^(https?|wss?)://([^/?#@\s]+)/?(socket/?)?$", s, re.I)
    if not m:
        raise SystemExit("not a relay address: %r (expected host, https://host or wss://host/socket/)" % entry)
    scheme = {"http": "ws", "https": "wss"}.get(m.group(1).lower(), m.group(1).lower())
    host = m.group(2).lower()
    if scheme == "ws" and not re.match(r"^(localhost|127\.|\[::1\])", host):
        raise SystemExit("refusing cleartext ws:// for a remote relay: %s" % entry)
    return "%s://%s" % (scheme, host)

def encode(bases):
    key = secrets.token_bytes(16)
    table = []
    for i, text in enumerate(bases):
        raw = text.encode("utf-8")
        out = bytes(b ^ key[(j + i * 7) % len(key)] ^ ((j * 29 + 11) & 255) for j, b in enumerate(raw))
        table.append(base64.b64encode(out).decode("ascii"))
    return base64.b64encode(key).decode("ascii"), table

def decode(key_b64, table):
    key = base64.b64decode(key_b64) if key_b64 else b"\0"
    out = []
    for i, item in enumerate(table):
        raw = base64.b64decode(item)
        out.append(bytes(b ^ key[(j + i * 7) % len(key)] ^ ((j * 29 + 11) & 255) for j, b in enumerate(raw)).decode("utf-8"))
    return out

def current(source):
    block = BLOCK.search(source).group(2)
    key = re.search(r'var K = "([^"]*)";', block).group(1)
    table = re.findall(r'"([A-Za-z0-9+/=]+)"', re.search(r"var T = \[(.*?)\];", block, re.S).group(1))
    return key, table

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("relays", nargs="*", help="relay addresses in order (Relay 1 first)")
    parser.add_argument("--file", help="read addresses from this file, one per line")
    parser.add_argument("--list", action="store_true", help="print the decoded current table and exit")
    parser.add_argument("--target", default=TARGET)
    args = parser.parse_args()
    source = open(args.target, encoding="utf-8").read()
    if args.list:
        for n, text in enumerate(decode(*current(source)), 1):
            print("Relay %d  %s" % (n, text))
        return 0
    entries = list(args.relays)
    if args.file:
        entries += open(args.file, encoding="utf-8").read().splitlines()
    bases = []
    for entry in entries:
        c = canonical(entry)
        if c and c not in bases:
            bases.append(c)
    if not bases:
        raise SystemExit("no relays given")
    key, table = encode(bases)
    assert decode(key, table) == bases
    body = '\tvar K = "%s";\n\tvar T = [%s];\n' % (key, ", ".join('"%s"' % t for t in table))
    source = BLOCK.sub(lambda m: m.group(1) + body + m.group(3), source, count=1)
    with open(args.target, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(source)
    print("wrote %d built-in relay(s) to %s" % (len(bases), os.path.relpath(args.target, ROOT)))
    return 0

if __name__ == "__main__":
    sys.exit(main())
