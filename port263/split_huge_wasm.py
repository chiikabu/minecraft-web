# minecraft-web by chiikabu (https://github.com/chiikabu)
import os
import sys

LIMIT = 7_000_000
CHUNK = 512 * 1024
VOID = None

VALTYPES = {0x7F, 0x7E, 0x7D, 0x7C, 0x7B, 0x70, 0x6F, 0x6E, 0x6D, 0x6C, 0x6B, 0x6A, 0x73, 0x72, 0x71}
STORES = set(range(0x36, 0x3F))

def uleb(data, i):
    n = shift = 0
    while True:
        b = data[i]
        i += 1
        n |= (b & 0x7F) << shift
        if b < 128:
            return n, i
        shift += 7

def sleb(data, i):
    n = shift = 0
    while True:
        b = data[i]
        i += 1
        n |= (b & 0x7F) << shift
        shift += 7
        if b < 128:
            if shift < 64 and (b & 0x40):
                n |= (~0 << shift)
            return n, i

def encode_uleb(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)

def read_valtype(data, i):
    b = data[i]
    if b in (0x63, 0x64):
        _, i = sleb(data, i + 1)
        return i
    if b in VALTYPES:
        return i + 1
    raise ValueError("bad valtype %#x at %d" % (b, i))

def read_fieldtype(data, i):
    if data[i] in (0x78, 0x77):
        return i + 2
    return read_valtype(data, i) + 1

def read_blocktype(data, i, funcs):
    b = data[i]
    if b == 0x40:
        return i + 1, 0, 0
    if b in (0x63, 0x64) or b in VALTYPES:
        return read_valtype(data, i), 0, 1
    idx, i2 = sleb(data, i)
    if idx < 0:
        raise ValueError("block type %d" % idx)
    return i2, funcs[idx][0], funcs[idx][1]

def parse_types(data, start, end):
    i = start
    groups_expected, i = uleb(data, i)
    funcs = {}
    structs = {}
    idx = 0
    for _group in range(groups_expected):
        if i >= end:
            raise ValueError("type section ended early at group %d" % _group)
        group = 1
        if data[i] == 0x4E:
            group, i = uleb(data, i + 1)
        for _ in range(group):
            if data[i] in (0x50, 0x4F):
                n, i = uleb(data, i + 1)
                for _s in range(n):
                    _, i = uleb(data, i)
            kind = data[i]
            i += 1
            if kind == 0x60:
                np, i = uleb(data, i)
                for _p in range(np):
                    i = read_valtype(data, i)
                nr, i = uleb(data, i)
                for _r in range(nr):
                    i = read_valtype(data, i)
                funcs[idx] = (np, nr)
            elif kind == 0x5F:
                nf, i = uleb(data, i)
                for _f in range(nf):
                    i = read_fieldtype(data, i)
                structs[idx] = nf
            elif kind == 0x5E:
                i = read_fieldtype(data, i)
            else:
                raise ValueError("type kind %#x at %d (type %d)" % (kind, i - 1, idx))
            idx += 1
    if i != end:
        raise ValueError("type section leftover %d after %d types" % (end - i, idx))
    return funcs, structs

def parse_name(data, i):
    n, i = uleb(data, i)
    return i + n

def parse_limits(data, i):
    flags = data[i]
    i += 1
    _, i = uleb(data, i)
    if flags & 1:
        _, i = uleb(data, i)
    return i

def parse_imports(data, start, end):
    i = start
    n, i = uleb(data, i)
    funcs = []
    for _ in range(n):
        i = parse_name(data, i)
        i = parse_name(data, i)
        kind = data[i]
        i += 1
        if kind == 0x00:
            ty, i = uleb(data, i)
            funcs.append(ty)
        elif kind == 0x01:
            i = read_valtype(data, i)
            i = parse_limits(data, i)
        elif kind == 0x02:
            i = parse_limits(data, i)
        elif kind == 0x03:
            i = read_valtype(data, i)
            i += 1
        elif kind == 0x04:
            _, i = uleb(data, i)
        else:
            raise ValueError("import kind %#x" % kind)
    if i != end:
        raise ValueError("import leftover %d" % (end - i))
    return funcs

def parse_function_types(data, start, end):
    i = start
    n, i = uleb(data, i)
    types = []
    for _ in range(n):
        ty, i = uleb(data, i)
        types.append(ty)
    if i != end:
        raise ValueError("function leftover %d" % (end - i))
    return types

def sections_of(data):
    i = 8
    out = []
    while i < len(data):
        sid = data[i]
        i += 1
        slen, i = uleb(data, i)
        out.append((sid, i, slen))
        i += slen
    if i != len(data):
        raise ValueError("trailing %d" % (len(data) - i))
    return out

def memarg(data, i):
    _, i = uleb(data, i)
    _, i = uleb(data, i)
    return i

def prefix_fc(data, i):
    sub, i = uleb(data, i)
    if sub == 0:
        _, i = uleb(data, i)
        return i + 1, (3, 0)
    if sub == 1:
        _, i = uleb(data, i)
        return i, (0, 0)
    if sub == 2:
        return i + 2, (3, 0)
    if sub == 3:
        return i + 1, (3, 0)
    if sub == 4:
        _, i = uleb(data, i)
        _, i = uleb(data, i)
        return i, (3, 0)
    if sub == 5:
        _, i = uleb(data, i)
        return i, (0, 0)
    if sub == 6:
        _, i = uleb(data, i)
        _, i = uleb(data, i)
        return i, (3, 0)
    if sub == 7:
        _, i = uleb(data, i)
        return i, (2, 1)
    if sub == 8:
        _, i = uleb(data, i)
        return i, (0, 1)
    if sub == 9:
        _, i = uleb(data, i)
        return i, (3, 0)
    if sub in (10, 11):
        return i, (1, 1)
    raise ValueError("fc sub %#x" % sub)

def prefix_fb(data, i, structs):
    sub, i = uleb(data, i)
    if sub in (0, 1):
        ty, i = uleb(data, i)
        return i, ((structs[ty], 1) if sub == 0 else (0, 1))
    if sub in (2, 3, 4):
        _, i = uleb(data, i)
        _, i = uleb(data, i)
        return i, (1, 1)
    if sub == 5:
        _, i = uleb(data, i)
        _, i = uleb(data, i)
        return i, (2, 0)
    if sub == 6:
        _, i = uleb(data, i)
        return i, (2, 1)
    if sub == 7:
        _, i = uleb(data, i)
        return i, (1, 1)
    if sub == 8:
        _, i = uleb(data, i)
        n, i = uleb(data, i)
        return i, (n, 1)
    if sub in (9, 10):
        _, i = uleb(data, i)
        _, i = uleb(data, i)
        return i, (2, 1)
    if sub in (11, 12, 13):
        _, i = uleb(data, i)
        return i, (2, 1)
    if sub == 14:
        _, i = uleb(data, i)
        return i, (3, 0)
    if sub == 15:
        return i, (1, 1)
    if sub == 16:
        _, i = uleb(data, i)
        return i, (4, 0)
    if sub == 17:
        _, i = uleb(data, i)
        _, i = uleb(data, i)
        return i, (5, 0)
    if sub in (18, 19):
        _, i = uleb(data, i)
        _, i = uleb(data, i)
        return i, (3, 0)
    if sub in (20, 21, 22, 23):
        _, i = sleb(data, i)
        return i, (1, 1)
    if sub in (24, 25):
        i += 1
        _, i = uleb(data, i)
        _, i = sleb(data, i)
        _, i = sleb(data, i)
        return i, (1, 1)
    if sub in (26, 27, 28, 29, 30):
        return i, (1, 1)
    raise ValueError("fb sub %#x" % sub)

FIXED_POP_PUSH = {}
for op in [0x45, 0x50] + list(range(0x67, 0x6A)) + list(range(0x79, 0x7C)) + list(range(0x8B, 0x92)) \
        + list(range(0x99, 0xA0)) + list(range(0xA7, 0xC5)) + [0xD1, 0x22]:
    FIXED_POP_PUSH[op] = (1, 1)
for op in list(range(0x46, 0x50)) + list(range(0x51, 0x67)) + list(range(0x6A, 0x79)) + list(range(0x7C, 0x8B)) \
        + list(range(0x92, 0x99)) + list(range(0xA0, 0xA7)):
    FIXED_POP_PUSH[op] = (2, 1)
for op in (0x41, 0x42, 0x43, 0x44, 0x23, 0x20, 0xD0, 0xD2):
    FIXED_POP_PUSH[op] = (0, 1)
for op in (0x1A, 0x21, 0x24):
    FIXED_POP_PUSH[op] = (1, 0)
FIXED_POP_PUSH[0x1B] = (3, 1)
FIXED_POP_PUSH[0x25] = (1, 1)
FIXED_POP_PUSH[0x26] = (2, 0)
FIXED_POP_PUSH[0x3F] = (0, 1)
FIXED_POP_PUSH[0x40] = (1, 1)
FIXED_POP_PUSH[0x01] = (0, 0)

def skip_simple(data, i, op):
    if op in (0x41, 0x42, 0xD0):
        _, i = sleb(data, i)
    elif op == 0x43:
        i += 4
    elif op == 0x44:
        i += 8
    elif op in (0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x3F, 0x40, 0xD2):
        _, i = uleb(data, i)
    return i

class Walker:
    def __init__(self, data, funcs, structs, func_type_of):
        self.data = data
        self.funcs = funcs
        self.structs = structs
        self.func_type_of = func_type_of

    def walk(self, start, end):
        data = self.data
        i = start
        depth = 0
        ctrl = []
        unreachable = False
        splits = []
        while i < end:
            op_at = i
            op = data[i]
            i += 1
            if op == 0x0B:
                if not ctrl:
                    if i != end:
                        raise ValueError("end early at %d expected %d" % (i, end))
                    return splits, depth
                entry, params, results = ctrl.pop()
                depth = entry + results
                unreachable = False
            elif op == 0x05:
                entry, params, results = ctrl[-1]
                depth = entry
                unreachable = False
            elif op in (0x02, 0x03, 0x04):
                i, params, results = read_blocktype(data, i, self.funcs)
                if not unreachable:
                    if op == 0x04:
                        depth -= 1
                    depth -= params
                ctrl.append((depth, params, results))
            elif op in (0x0C, 0x0D):
                _, i = uleb(data, i)
                if op == 0x0D and not unreachable:
                    depth -= 1
                if op == 0x0C:
                    unreachable = True
            elif op == 0x0E:
                n, i = uleb(data, i)
                for _ in range(n + 1):
                    _, i = uleb(data, i)
                if not unreachable:
                    depth -= 1
                unreachable = True
            elif op == 0x0F:
                unreachable = True
            elif op in (0x10, 0x12):
                fn, i = uleb(data, i)
                params, results = self.func_type_of(fn)
                if not unreachable:
                    depth = depth - params + results
                if op == 0x12:
                    unreachable = True
            elif op in (0x11, 0x13, 0x14, 0x15):
                ty, i = uleb(data, i)
                _, i = uleb(data, i)
                params, results = self.funcs[ty]
                if not unreachable:
                    depth = depth - params - 1 + results
                if op in (0x13, 0x15):
                    unreachable = True
            elif op == 0x1C:
                n, i = uleb(data, i)
                for _ in range(n):
                    i = read_valtype(data, i)
                if not unreachable:
                    depth = depth - (1 + max(n, 1)) + max(n, 1)
            elif op == 0xFC:
                i, (pop, push) = prefix_fc(data, i)
                if not unreachable:
                    depth = depth - pop + push
            elif op == 0xFB:
                i, (pop, push) = prefix_fb(data, i, self.structs)
                if not unreachable:
                    depth = depth - pop + push
            elif op == 0x00:
                unreachable = True
            elif op in FIXED_POP_PUSH:
                i = skip_simple(data, i, op)
                if not unreachable:
                    pop, push = FIXED_POP_PUSH[op]
                    depth = depth - pop + push
            elif 0x28 <= op <= 0x3E:
                i = memarg(data, i)
                if not unreachable:
                    depth += -2 if op in STORES else 0
            else:
                raise ValueError("opcode %#x at %d" % (op, op_at))
            if depth < 0 and not unreachable:
                raise ValueError("underflow %d at %d op %#x" % (depth, op_at, op))
            if not ctrl and not unreachable and depth == 0 and i < end and data[i] != 0x0B:
                splits.append(i)
        raise ValueError("ran off function")

def locate_body(data, code_start, code_index):
    i = code_start
    count, i = uleb(data, i)
    for fn in range(count):
        size, i = uleb(data, i)
        if fn == code_index:
            return i, i + size
        i += size
    raise ValueError("missing function %d" % code_index)

def load(path):
    with open(path, "rb") as f:
        data = f.read()
    secs = sections_of(data)
    by_id = {}
    for sid, start, slen in secs:
        by_id.setdefault(sid, (start, slen))
    def span(sid):
        start, slen = by_id[sid]
        return start, start + slen

    funcs, structs = parse_types(data, *span(1))
    if funcs.get(0) != (0, 0):
        raise ValueError("type 0 is %s, expected void" % (funcs.get(0),))
    imported = parse_imports(data, *span(2))
    defined = parse_function_types(data, *span(3))

    def func_type_of(fn):
        if fn < len(imported):
            return funcs[imported[fn]]
        return funcs[defined[fn - len(imported)]]

    return data, secs, by_id, funcs, structs, imported, defined, func_type_of

def oversized(data, by_id, limit=LIMIT):
    start, slen = by_id[10]
    i = start
    end = start + slen
    count, i = uleb(data, i)
    found = []
    for fn in range(count):
        size, i = uleb(data, i)
        if size > limit:
            found.append(fn)
        i += size
    if i != end:
        raise ValueError("code walk %d != %d" % (i, end))
    return found

def plan(data, by_id, funcs, structs, func_type_of, code_index):
    body, body_end = locate_body(data, by_id[10][0], code_index)
    groups, code = uleb(data, body)
    if groups != 0:
        raise ValueError("function %d has %d local groups; splitter expects none" % (code_index, groups))
    walker = Walker(data, funcs, structs, func_type_of)
    splits, depth = walker.walk(code, body_end)
    if depth != 0:
        raise ValueError("function %d ends at stack depth %d" % (code_index, depth))
    if data[body_end - 1] != 0x0B:
        raise ValueError("function %d does not end with end" % code_index)
    points = [code] + splits + [body_end - 1]
    spans = [(a, b) for a, b in zip(points, points[1:]) if b > a]
    groups = []
    cur = []
    size = 0
    for span in spans:
        n = span[1] - span[0]
        if n > LIMIT:
            raise ValueError("unsplittable instruction span %d in function %d" % (n, code_index))
        if cur and size + n > CHUNK:
            groups.append(cur)
            cur = [span]
            size = n
        else:
            cur.append(span)
            size += n
    if cur:
        groups.append(cur)
    return body, body_end, groups

def encode_body(code_bytes):
    body = b"\x00" + code_bytes + b"\x0b"
    return encode_uleb(len(body)) + body

def rewrite(path, out_path):
    data, secs, by_id, funcs, structs, imported, defined, func_type_of = load(path)
    targets = oversized(data, by_id)
    if not targets:
        print(path, "nothing over", LIMIT)
        return False
    print(path, "splitting", targets)
    plans = {}
    helpers = []
    base = len(imported) + len(defined)
    for code_index in targets:
        body, body_end, groups = plan(data, by_id, funcs, structs, func_type_of, code_index)
        calls = bytearray()
        for group in groups:
            chunk = b"".join(data[a:b] for a, b in group)
            fn_index = base + len(helpers)
            helpers.append(chunk)
            calls.extend(b"\x10" + encode_uleb(fn_index))
        plans[code_index] = (body, body_end, bytes(calls) + b"\x0b")
        print("  fn", code_index, "helpers", len(groups), "callstub", len(calls))

    new_defined = defined + [0] * len(helpers)
    func_payload = encode_uleb(len(new_defined)) + b"".join(encode_uleb(t) for t in new_defined)

    cstart, clen = by_id[10]
    i = cstart
    count, i = uleb(data, i)
    code_payload = bytearray(encode_uleb(count + len(helpers)))
    for fn in range(count):
        size, nxt = uleb(data, i)
        raw_end = nxt + size
        if fn in plans:
            body, body_end, replacement = plans[fn]
            if body != nxt or body_end != raw_end:
                raise ValueError("body mismatch")
            body_bytes = b"\x00" + replacement
            code_payload.extend(encode_uleb(len(body_bytes)))
            code_payload.extend(body_bytes)
        else:
            code_payload.extend(data[i:raw_end])
        i = raw_end
    for chunk in helpers:
        code_payload.extend(encode_body(chunk))

    replaced = {3: func_payload, 10: code_payload}
    out = bytearray(data[:8])
    for sid, start, slen in secs:
        payload = replaced.get(sid, data[start:start + slen])
        out.append(sid)
        out.extend(encode_uleb(len(payload)))
        out.extend(payload)
    with open(out_path, "wb") as f:
        f.write(out)
    print("wrote", out_path, "bytes", len(out), "helpers", len(helpers))
    return True

def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: split_huge_wasm.py IN OUT")
    rewrite(sys.argv[1], sys.argv[2])

if __name__ == "__main__":
    main()
