#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Cryptographically-verified symbolic JSON edit composer and verifier.

Composes a desired transformation (m_before -> m_after) onto an existing
patches/**/*.json edit list E1 over an unknown Mojang decompiled source `raw`,
producing E_combined such that:

    apply_edits(raw, E_combined) == m_after

without ever needing `raw` on disk and without leaking >= 32-char Mojang
source slices as string literals in JSON.

Mathematical proof of exactness (double-sentinel invariance):
    Every byte of `raw` not deleted by E1 (plus every byte referenced by a
    {"copy": c, "length": l} range in E1) is uniquely determined by (E1, m_before).
    Every remaining byte of `raw` is filled with two completely disjoint sentinel
    patterns to form `raw_synth_1` and `raw_synth_2`.  If:
        apply_edits(raw_synth_1, E1) == m_before
        apply_edits(raw_synth_2, E1) == m_before
        apply_edits(raw_synth_1, E_combined) == m_after
        apply_edits(raw_synth_2, E_combined) == m_after
    then E_combined is mathematically invariant to every unknown byte of `raw`,
    and therefore MUST produce exact `m_after` (matching sha256(m_after)) on the
    true Vineflower-decompiled `raw`.
"""

import difflib
import hashlib
import json
import os
import random
import re
import subprocess
import sys
from itertools import accumulate

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(SCRIPT_DIR)
PATCHES_DIR = os.path.join(ROOT, "patches")
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from source_edits import apply_edits, inserted_text, make_edits, selfcheck as source_edits_selfcheck

COPY_MIN_LEN = 32
TOKEN_RE = re.compile(r"\w+|\s+|[^\w\s]")


def text_sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def ins_len(value) -> int:
    if isinstance(value, str):
        return len(value)
    if not isinstance(value, list) or not value:
        raise ValueError("Invalid insert value")
    total = 0
    for piece in value:
        if isinstance(piece, str):
            total += len(piece)
        elif isinstance(piece, dict) and set(piece) == {"copy", "length"}:
            total += piece["length"]
        else:
            raise ValueError(f"Invalid insert piece: {piece!r}")
    return total


def parse_e1_segments(m_before: str, e1: list):
    """Partition m_before into RAW and EDIT segments over raw coordinates.

    Returns (raw_len, segments, e1_entries, known_raw) where:
      - raw_len: exact length of unknown `raw`
      - segments: ordered list covering [0, len(m_before)]
      - e1_entries: ordered list of edit descriptors corresponding to e1[0..k-1]
      - known_raw: list[str | None] of length raw_len with every known char of `raw`
    """
    delta = sum(ins_len(ed["insert"]) - ed["delete"] for ed in e1)
    raw_len = len(m_before) - delta
    if raw_len < 0:
        raise ValueError(f"Negative implied raw_len={raw_len}")

    known_raw = [None] * raw_len
    segments = []
    e1_entries = []
    r_cur = 0
    m_cur = 0
    prev_start = -1

    for idx, ed in enumerate(e1):
        rs = ed["start"]
        rd = ed["delete"]
        ins = ed["insert"]
        if rs <= prev_start or rs < r_cur or rd < 0 or rs + rd > raw_len:
            raise ValueError(f"Invalid E1 edit at index {idx}: {ed!r} (raw_len={raw_len})")
        prev_start = rs

        if rs > r_cur:
            keep = rs - r_cur
            seg = {
                "kind": "RAW",
                "r_start": r_cur,
                "r_end": rs,
                "m_start": m_cur,
                "m_end": m_cur + keep,
            }
            segments.append(seg)
            for k in range(keep):
                known_raw[r_cur + k] = m_before[m_cur + k]
            m_cur += keep

        ilen = ins_len(ins)
        pieces = []
        pm = m_cur
        raw_pieces = [ins] if isinstance(ins, str) else ins
        for p in raw_pieces:
            if isinstance(p, str):
                if p:
                    if m_before[pm : pm + len(p)] != p:
                        raise ValueError(
                            f"E1 literal mismatch at edit {idx}, m={pm}: "
                            f"expected {p[:40]!r}, got {m_before[pm:pm+len(p)][:40]!r}"
                        )
                    pieces.append(("LIT", pm, pm + len(p), p))
                    pm += len(p)
            else:
                c, l = p["copy"], p["length"]
                if c < 0 or l <= 0 or c + l > raw_len:
                    raise ValueError(f"E1 copy out of bounds at edit {idx}: {p!r}")
                slice_text = m_before[pm : pm + l]
                for k in range(l):
                    existing = known_raw[c + k]
                    ch = slice_text[k]
                    if existing is not None and existing != ch:
                        raise ValueError(
                            f"E1 copy conflict at raw[{c+k}]: {existing!r} vs {ch!r}"
                        )
                    known_raw[c + k] = ch
                pieces.append(("COPY", pm, pm + l, (c, l)))
                pm += l
        if pm != m_cur + ilen:
            raise ValueError(f"Piece length mismatch at edit {idx}")

        entry = {
            "kind": "EDIT",
            "e_idx": idx,
            "raw_edit": ed,
            "r_start": rs,
            "r_end": rs + rd,
            "m_start": m_cur,
            "m_end": m_cur + ilen,
            "pieces": pieces,
        }
        segments.append(entry)
        e1_entries.append(entry)
        m_cur += ilen
        r_cur = rs + rd

    if r_cur < raw_len:
        keep = raw_len - r_cur
        segments.append({
            "kind": "RAW",
            "r_start": r_cur,
            "r_end": raw_len,
            "m_start": m_cur,
            "m_end": m_cur + keep,
        })
        for k in range(keep):
            known_raw[r_cur + k] = m_before[m_cur + k]
        m_cur += keep

    if m_cur != len(m_before):
        raise ValueError(f"Segment partition length mismatch: {m_cur} != {len(m_before)}")

    return raw_len, segments, e1_entries, known_raw


def _build_known_raw_index(known_raw: list):
    """Build a tokenized index of known_raw for DMCA copy-ref compression.

    Unknown positions are replaced by '\\x00' sentinels so SequenceMatcher
    never matches across unknown bytes (assuming inserted Java text has no NUL).
    """
    sentinel = "\x00"
    raw_str = "".join(c if c is not None else sentinel for c in known_raw)
    tokens = TOKEN_RE.findall(raw_str)
    offsets = [0, *accumulate(map(len, tokens))]
    return raw_str, tokens, offsets


def _compress_literal_to_pieces(lit: str, matcher, source_offsets: list, known_raw: list, known_raw_str: str) -> list:
    if len(lit) < COPY_MIN_LEN or "\x00" in lit:
        return [("LIT", lit)] if lit else []
    tokens = TOKEN_RE.findall(lit)
    positions = [0, *accumulate(map(len, tokens))]
    matcher.set_seq1(tokens)
    stage1 = []
    cursor = 0
    for new_start, old_start, size in matcher.get_matching_blocks():
        start, end = positions[new_start], positions[new_start + size]
        length = end - start
        if length < COPY_MIN_LEN:
            continue
        r_off = source_offsets[old_start]
        if known_raw_str[r_off : r_off + length] != lit[start:end]:
            continue
        if start > cursor:
            stage1.append(("LIT", lit[cursor:start]))
        stage1.append(("COPY", r_off, length))
        cursor = end
    if not stage1:
        stage1 = [("LIT", lit)]
    elif cursor < len(lit):
        stage1.append(("LIT", lit[cursor:]))

    # Second pass: exact greedy substring search over known_raw_str on every remaining
    # literal piece >= COPY_MIN_LEN so out-of-order / moved blocks are also replaced by
    # {"copy", "length"} references (0 verbatim >= 32-char raw substrings in JSON).
    out = []
    for item in stage1:
        if item[0] != "LIT" or len(item[1]) < COPY_MIN_LEN:
            out.append(item)
            continue
        s = item[1]
        slen = len(s)
        klen = len(known_raw_str)
        cur = 0
        i = 0
        while i <= slen - COPY_MIN_LEN:
            needle = s[i : i + COPY_MIN_LEN]
            pos = known_raw_str.find(needle)
            if pos == -1:
                i += 1
                continue
            # Pick the longest match starting at i across occurrences of needle.
            best_pos = pos
            best_len = COPY_MIN_LEN
            search_from = pos
            checks = 0
            while search_from != -1 and checks < 16:
                checks += 1
                l = COPY_MIN_LEN
                max_l = min(slen - i, klen - search_from)
                while l < max_l and s[i + l] == known_raw_str[search_from + l]:
                    l += 1
                if l > best_len:
                    best_len = l
                    best_pos = search_from
                search_from = known_raw_str.find(needle, search_from + 1)
            # Extend left into unconsumed literal region if possible? No, i advances greedily from left.
            assert "\x00" not in known_raw_str[best_pos : best_pos + best_len]
            if i > cur:
                out.append(("LIT", s[cur:i]))
            out.append(("COPY", best_pos, best_len))
            cur = i + best_len
            i = cur
        if cur < slen:
            out.append(("LIT", s[cur:]))
    return out


def _coalesce_pieces(pieces: list) -> list:
    merged = []
    for item in pieces:
        kind = item[0]
        if kind == "LIT":
            text = item[1]
            if not text:
                continue
            if merged and merged[-1][0] == "LIT":
                merged[-1] = ("LIT", merged[-1][1] + text)
            else:
                merged.append(("LIT", text))
        elif kind == "COPY":
            c, l = item[1], item[2]
            if l <= 0:
                continue
            if merged and merged[-1][0] == "COPY" and merged[-1][1] + merged[-1][2] == c:
                merged[-1] = ("COPY", merged[-1][1], merged[-1][2] + l)
            else:
                merged.append(("COPY", c, l))
        else:
            raise ValueError(f"Unknown piece kind: {item!r}")
    return merged


def _format_insert(pieces: list):
    if not pieces:
        return ""
    if len(pieces) == 1 and pieces[0][0] == "LIT":
        return pieces[0][1]
    out = []
    for item in pieces:
        if item[0] == "LIT":
            out.append(item[1])
        else:
            out.append({"copy": item[1], "length": item[2]})
    return out


def _slice_m_before_to_pieces(segments: list, m_before: str, a: int, b: int) -> list:
    """Represent m_before[a:b] as RAW/COPY/LIT pieces from E1's partition."""
    if a >= b:
        return []
    out = []
    for seg in segments:
        if seg["m_end"] <= a:
            continue
        if seg["m_start"] >= b:
            break
        oa = max(a, seg["m_start"])
        ob = min(b, seg["m_end"])
        if oa >= ob:
            continue
        if seg["kind"] == "RAW":
            r_off = seg["r_start"] + (oa - seg["m_start"])
            out.append(("RAW", r_off, ob - oa))
        else:
            for pkind, pm_s, pm_e, pdata in seg["pieces"]:
                if pm_e <= oa:
                    continue
                if pm_s >= ob:
                    break
                pa = max(oa, pm_s)
                pb = min(ob, pm_e)
                if pa >= pb:
                    continue
                if pkind == "COPY":
                    c, _ = pdata
                    out.append(("COPY", c + (pa - pm_s), pb - pa))
                else:
                    out.append(("LIT", pdata[pa - pm_s : pb - pm_s]))
    return out


def _m_point_to_raw_in_segments(segments: list, m: int, raw_len: int) -> int:
    """Map an m_before coordinate strictly outside any E1 edit to its exact raw offset."""
    for seg in segments:
        if seg["kind"] == "RAW" and seg["m_start"] <= m <= seg["m_end"]:
            return seg["r_start"] + (m - seg["m_start"])
    if m == 0:
        return 0
    if m == raw_len and not segments:
        return raw_len
    raise ValueError(f"Could not map m={m} to RAW segment")


def compose_edits(e1: list, m_before: str, m_after: str) -> list:
    """Symbolically compose (m_before -> m_after) onto existing E1 over unknown raw.

    Preserves all untouched E1 edits verbatim, merges only overlapping/touching edits,
    preserves E1 copy references on surviving slices, compresses >= 32-char copied
    raw spans in newly inserted text into {"copy", "length"} references, and verifies
    double-sentinel invariance before returning.
    """
    raw_len, segments, e1_entries, known_raw = parse_e1_segments(m_before, e1)
    if m_before == m_after:
        return [dict(ed) for ed in e1]

    # Step 1: Compute minimal edits E2 from m_before -> m_after (expanded to plain strings).
    e2_raw = make_edits(m_before, m_after)
    e2_list = []
    for ed in e2_raw:
        ms = ed["start"]
        me = ms + ed["delete"]
        ins = inserted_text(m_before, ed["insert"])
        e2_list.append((ms, me, ins))

    if not e2_list:
        return [dict(ed) for ed in e1]

    # Step 2: Cluster E2 edits and any E1 edits whose closed m-intervals intersect them.
    # Untouched E1 edits remain completely separate and unmodified.
    clusters = []
    for j, (ms, me, ins) in enumerate(e2_list):
        clusters.append({
            "m_start": ms,
            "m_end": me,
            "e2_indices": [j],
            "e1_indices": [],
        })

    # Merge any touching/overlapping E2 clusters first.
    merged_clusters = []
    for cl in clusters:
        if merged_clusters and merged_clusters[-1]["m_end"] >= cl["m_start"]:
            prev = merged_clusters[-1]
            prev["m_end"] = max(prev["m_end"], cl["m_end"])
            prev["e2_indices"].extend(cl["e2_indices"])
        else:
            merged_clusters.append(cl)
    clusters = merged_clusters

    # Iteratively absorb all E1 edits whose closed m-interval intersects an active cluster,
    # merging clusters whenever an absorbed E1 edit bridges two clusters.
    used_e1 = set()
    changed = True
    while changed:
        changed = False
        for i, entry in enumerate(e1_entries):
            if i in used_e1:
                continue
            es, ee = entry["m_start"], entry["m_end"]
            hit = [ci for ci, cl in enumerate(clusters) if cl["m_end"] >= es and cl["m_start"] <= ee]
            if not hit:
                continue
            used_e1.add(i)
            changed = True
            first = clusters[hit[0]]
            first["m_start"] = min(first["m_start"], es)
            first["m_end"] = max(first["m_end"], ee)
            first["e1_indices"].append(i)
            for ci in reversed(hit[1:]):
                other = clusters.pop(ci)
                first["m_start"] = min(first["m_start"], other["m_start"])
                first["m_end"] = max(first["m_end"], other["m_end"])
                first["e1_indices"].extend(other["e1_indices"])
                first["e2_indices"].extend(other["e2_indices"])
        # Also merge any two clusters that now touch in m_before space.
        clusters.sort(key=lambda c: (c["m_start"], c["m_end"]))
        re_merged = []
        for cl in clusters:
            if re_merged and re_merged[-1]["m_end"] >= cl["m_start"]:
                prev = re_merged[-1]
                prev["m_end"] = max(prev["m_end"], cl["m_end"])
                prev["e1_indices"].extend(cl["e1_indices"])
                prev["e2_indices"].extend(cl["e2_indices"])
                changed = True
            else:
                re_merged.append(cl)
        clusters = re_merged

    # Build known_raw SequenceMatcher lazily only if any literal piece >= COPY_MIN_LEN exists.
    matcher_state = None

    def get_matcher():
        nonlocal matcher_state
        if matcher_state is None:
            raw_str, tokens, offsets = _build_known_raw_index(known_raw)
            matcher = difflib.SequenceMatcher(None, [], tokens, autojunk=False)
            matcher_state = (matcher, offsets, raw_str)
        return matcher_state

    # Step 3: Materialize each active cluster into a raw edit, plus all untouched E1 edits.
    output_edits = []

    for i, entry in enumerate(e1_entries):
        if i not in used_e1:
            output_edits.append(dict(entry["raw_edit"]))

    for cl in clusters:
        cl["e1_indices"].sort()
        cl["e2_indices"].sort()
        cm_s, cm_e = cl["m_start"], cl["m_end"]

        # Map [cm_s, cm_e] -> [cr_s, cr_e] in raw coordinates.
        start_e1s = [e1_entries[i] for i in cl["e1_indices"] if e1_entries[i]["m_start"] == cm_s]
        if start_e1s:
            cr_s = min(e["r_start"] for e in start_e1s)
        else:
            cr_s = _m_point_to_raw_in_segments(segments, cm_s, raw_len)

        end_e1s = [e1_entries[i] for i in cl["e1_indices"] if e1_entries[i]["m_end"] == cm_e]
        if end_e1s:
            cr_e = max(e["r_end"] for e in end_e1s)
        else:
            cr_e = _m_point_to_raw_in_segments(segments, cm_e, raw_len)

        # Reconstruct exact replacement piece list across [cm_s, cm_e].
        raw_pieces = []
        cur = cm_s
        for j in cl["e2_indices"]:
            ms, me, ins = e2_list[j]
            if ms > cur:
                raw_pieces.extend(_slice_m_before_to_pieces(segments, m_before, cur, ms))
            if ins:
                raw_pieces.append(("LIT", ins))
            cur = me
        if cur < cm_e:
            raw_pieces.extend(_slice_m_before_to_pieces(segments, m_before, cur, cm_e))

        # Trim leading/trailing pieces that exactly match raw[cr_s:cr_e] in place.
        while raw_pieces and cr_s < cr_e:
            first = raw_pieces[0]
            if first[0] in ("RAW", "COPY") and first[1] == cr_s:
                take = min(first[2], cr_e - cr_s)
                cr_s += take
                if take == first[2]:
                    raw_pieces.pop(0)
                else:
                    raw_pieces[0] = (first[0], first[1] + take, first[2] - take)
            elif first[0] == "LIT" and first[1]:
                s = first[1]
                k = 0
                max_k = min(len(s), cr_e - cr_s)
                while k < max_k and known_raw[cr_s + k] is not None and known_raw[cr_s + k] == s[k]:
                    k += 1
                if k == 0:
                    break
                cr_s += k
                if k == len(s):
                    raw_pieces.pop(0)
                else:
                    raw_pieces[0] = ("LIT", s[k:])
            else:
                break

        while raw_pieces and cr_s < cr_e:
            last = raw_pieces[-1]
            if last[0] in ("RAW", "COPY") and last[1] + last[2] == cr_e:
                take = min(last[2], cr_e - cr_s)
                cr_e -= take
                if take == last[2]:
                    raw_pieces.pop()
                else:
                    raw_pieces[-1] = (last[0], last[1], last[2] - take)
            elif last[0] == "LIT" and last[1]:
                s = last[1]
                k = 0
                max_k = min(len(s), cr_e - cr_s)
                while k < max_k and known_raw[cr_e - 1 - k] is not None and known_raw[cr_e - 1 - k] == s[len(s) - 1 - k]:
                    k += 1
                if k == 0:
                    break
                cr_e -= k
                if k == len(s):
                    raw_pieces.pop()
                else:
                    raw_pieces[-1] = ("LIT", s[: len(s) - k])
            else:
                break

        del_len = cr_e - cr_s
        if del_len == 0 and not raw_pieces:
            continue

        # Convert RAW pieces: >= COPY_MIN_LEN stay COPY; shorter become LIT so adjacent literals coalesce.
        norm = []
        for item in raw_pieces:
            if item[0] == "RAW":
                r_off, length = item[1], item[2]
                if length >= COPY_MIN_LEN:
                    norm.append(("COPY", r_off, length))
                else:
                    norm.append(("LIT", "".join(known_raw[r_off : r_off + length])))
            else:
                norm.append(item)
        norm = _coalesce_pieces(norm)

        # Compress any >= COPY_MIN_LEN literal slices matching known_raw into {"copy", "length"}.
        compressed = []
        for item in norm:
            if item[0] == "LIT" and len(item[1]) >= COPY_MIN_LEN and raw_len > 0:
                matcher, offsets, raw_str = get_matcher()
                compressed.extend(_compress_literal_to_pieces(item[1], matcher, offsets, known_raw, raw_str))
            else:
                compressed.append(item)
        compressed = _coalesce_pieces(compressed)

        output_edits.append({
            "start": cr_s,
            "delete": del_len,
            "insert": _format_insert(compressed),
        })

    output_edits.sort(key=lambda e: e["start"])

    # Mandatory double-sentinel invariance proof before returning.
    verify_composition(e1, output_edits, m_before, m_after)
    return output_edits


def build_sentinel_raws(known_raw: list):
    """Build two completely disjoint synthetic raw strings agreeing on all known_raw positions."""
    s1_pool = ("\x01", "\x02")
    s2_pool = ("\x03", "\x04", "\x05")
    raw1 = "".join(c if c is not None else s1_pool[i % 2] for i, c in enumerate(known_raw))
    raw2 = "".join(c if c is not None else s2_pool[i % 3] for i, c in enumerate(known_raw))
    return raw1, raw2


def verify_composition(e1: list, e_combined: list, m_before: str, m_after: str) -> dict:
    """Mathematically prove apply_edits(raw, e_combined) == m_after for ALL raw matching (e1, m_before)."""
    raw_len, _, _, known_raw = parse_e1_segments(m_before, e1)
    raw1, raw2 = build_sentinel_raws(known_raw)

    r1_before = apply_edits(raw1, e1)
    r2_before = apply_edits(raw2, e1)
    if r1_before != m_before or r2_before != m_before:
        raise AssertionError("E1 failed baseline replay on synthetic raw")

    r1_after = apply_edits(raw1, e_combined)
    r2_after = apply_edits(raw2, e_combined)
    if r1_after != m_after:
        raise AssertionError(f"E_combined failed sentinel-1 proof (len {len(r1_after)} vs {len(m_after)})")
    if r2_after != m_after:
        raise AssertionError(f"E_combined failed sentinel-2 proof (not invariant to unknown raw bytes!)")

    # Verify JSON round-trip and strict apply_edits acceptance.
    roundtripped = json.loads(json.dumps(e_combined))
    if apply_edits(raw1, roundtripped) != m_after or apply_edits(raw2, roundtripped) != m_after:
        raise AssertionError("E_combined failed JSON round-trip proof")

    return {
        "raw_len": raw_len,
        "unknown_raw_chars": sum(1 for c in known_raw if c is None),
        "e1_count": len(e1),
        "e_combined_count": len(e_combined),
        "sha256_before": text_sha(m_before),
        "sha256_after": text_sha(m_after),
    }


def fuzz_test(trials: int = 500):
    rng = random.Random(2632026)

    # 1. Targeted corner cases.
    corner_cases = [
        # (raw, m1, m2)
        ("abcdef", "abcdef", "abXYef"),
        ("abcdef", "aXYZef", "abcdef"),  # complete revert!
        ("abcdef", "af", "aINSERTf"),  # insert at pure deletion point
        ("abcdef", "abXcdYef", "abX123cdYef"),  # touch E1 boundary
        ("abcdef", "abXcdYef", "abZZZef"),  # span across 2 E1 edits
        ("abcdef", "XabcdefY", "WabcdefZ"),  # edit at start=0 and end=len
        ("", "hello", "world"),
        ("hello", "", "new"),
        ("hello", "", ""),
        ("a😀b\r\ncé", "a😀X\r\ncé", "a😀XY\r\ncZ"),
    ]
    for raw, m1, m2 in corner_cases:
        e1 = make_edits(raw, m1)
        assert apply_edits(raw, e1) == m1
        ec = compose_edits(e1, m1, m2)
        assert apply_edits(raw, ec) == m2

    # 2. DMCA copy-reference preservation and compression test.
    mojang_block = (
        "      for (int idx = 0; idx < secretVanillaArray.length; idx++) {\n"
        "         secretVanillaAccumulator += secretVanillaArray[idx] * 31;\n"
        "      }\n"
    )
    raw = "class Vanilla {\n   void f() {\n" + mojang_block + "   }\n}\n"
    # E1 wraps mojang_block with an if-guard (using a copy ref or untouched gap).
    m1 = "class Vanilla {\n   void f() {\n      if (enabled) {\n" + mojang_block + "      }\n   }\n}\n"
    e1 = make_edits(raw, m1)
    assert apply_edits(raw, e1) == m1
    # Now M2 moves/re-indents mojang_block again inside an else branch.
    m2 = (
        "class Vanilla {\n   void f() {\n      if (enabled) {\n"
        + mojang_block
        + "      } else {\n"
        + mojang_block
        + "      }\n   }\n}\n"
    )
    ec = compose_edits(e1, m1, m2)
    assert apply_edits(raw, ec) == m2
    literals = "".join(
        part
        for ed in ec
        for part in (ed["insert"] if isinstance(ed["insert"], list) else [ed["insert"]])
        if isinstance(part, str)
    )
    assert "secretVanillaAccumulator" not in literals, f"DMCA copy leak into JSON literals: {ec!r}"

    # 3. 500 randomized multi-edit composition fuzz trials.
    alphabet = "abcde é😀\r\n{}();=+"
    for t in range(trials):
        n = rng.randint(10, 140)
        raw = "".join(rng.choices(alphabet, k=n))
        # Build m1 by applying 0..5 random mutations to raw (including copies of raw blocks).
        m1 = raw
        for _ in range(rng.randint(0, 5)):
            if not m1:
                m1 = "".join(rng.choices(alphabet, k=rng.randint(1, 10)))
                continue
            a, b = sorted(rng.sample(range(len(m1) + 1), 2))
            if rng.random() < 0.25 and len(raw) >= 40:
                ca = rng.randint(0, len(raw) - 36)
                ins = raw[ca : ca + rng.randint(32, min(50, len(raw) - ca))]
            else:
                ins = "".join(rng.choices(alphabet, k=rng.randint(0, 15)))
            m1 = m1[:a] + ins + m1[b:]
        e1 = make_edits(raw, m1)
        assert apply_edits(raw, e1) == m1

        # Build m2 by applying 1..5 random mutations to m1 (including copies & reverts).
        m2 = m1
        for _ in range(rng.randint(1, 5)):
            if rng.random() < 0.15:
                m2 = raw  # test full/partial revert
                continue
            if not m2:
                m2 = "".join(rng.choices(alphabet, k=rng.randint(1, 10)))
                continue
            a, b = sorted(rng.sample(range(len(m2) + 1), 2))
            if rng.random() < 0.25 and len(m1) >= 40:
                ca = rng.randint(0, len(m1) - 36)
                ins = m1[ca : ca + rng.randint(32, min(50, len(m1) - ca))]
            else:
                ins = "".join(rng.choices(alphabet, k=rng.randint(0, 18)))
            m2 = m2[:a] + ins + m2[b:]

        ec = compose_edits(e1, m1, m2)
        actual = apply_edits(raw, ec)
        assert actual == m2, f"Fuzz trial {t} failed!"

    print(f"COMPOSE_EDITS_FUZZ_PASS: {len(corner_cases)} corner cases + DMCA copy-ref check + {trials} random trials passed")


def verify_manifest_and_patches():
    manifest_path = os.path.join(PATCHES_DIR, "manifest.json")
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)

    assert "classes" in manifest and isinstance(manifest["classes"], dict)
    total_outputs = 0
    total_edits = 0
    bad_branding = []

    for cls, entry in sorted(manifest["classes"].items()):
        assert isinstance(entry.get("raw"), str) and len(entry["raw"]) == 64
        outputs = entry.get("outputs", [])
        assert isinstance(outputs, list) and outputs
        for out in outputs:
            total_outputs += 1
            assert isinstance(out.get("sha256"), str) and len(out["sha256"]) == 64
            edit_path = os.path.join(PATCHES_DIR, *out["edits"].split("/"))
            if not os.path.isfile(edit_path):
                raise AssertionError(f"Missing patch file: {out['edits']}")
            with open(edit_path, encoding="utf-8") as ef:
                edits = json.load(ef)
            assert isinstance(edits, list), f"{out['edits']} is not a JSON list"
            cursor, prev = 0, -1
            for ed in edits:
                assert isinstance(ed, dict) and set(ed) == {"start", "delete", "insert"}, f"Bad edit in {out['edits']}: {ed}"
                s, d, ins = ed["start"], ed["delete"], ed["insert"]
                assert type(s) is int and type(d) is int and s > prev and s >= cursor and d >= 0, (
                    f"Ordering/overlap error in {out['edits']}: prev={prev} cursor={cursor} edit={ed}"
                )
                lits = [ins] if isinstance(ins, str) else [p for p in ins if isinstance(p, str)]
                if isinstance(ins, list):
                    assert ins, f"Empty insert list in {out['edits']}"
                    for p in ins:
                        if not isinstance(p, str):
                            assert isinstance(p, dict) and set(p) == {"copy", "length"}
                            assert type(p["copy"]) is int and type(p["length"]) is int
                            assert p["copy"] >= 0 and p["length"] > 0
                assert d > 0 or ins_len(ins) > 0, f"Empty edit in {out['edits']}"
                joined = "".join(lits)
                for forbidden in ("web262", "Web262", "WEB262", "twitcraft", "Twitcraft", "TWITCRAFT"):
                    if forbidden in joined:
                        bad_branding.append((out["edits"], forbidden))
                cursor = s + d
                prev = s
                total_edits += 1

    if bad_branding:
        raise AssertionError(f"Forbidden branding found in patches: {bad_branding[:10]}")

    # Verify double-sentinel invariance for every patch modified relative to git HEAD
    # (proves no modified patch reads any unknown raw byte deleted by upstream E1).
    modified_checked = verify_modified_patches_double_sentinel(manifest)

    print(
        f"MANIFEST_VERIFY_PASS: {len(manifest['classes'])} classes, {total_outputs} patch outputs, "
        f"{total_edits} edits structurally valid, {modified_checked} modified patches double-sentinel proven, "
        f"0 forbidden branding tokens"
    )


def _max_raw_index(edits: list) -> int:
    hi = 0
    for ed in edits:
        hi = max(hi, ed["start"] + ed["delete"])
        ins = ed["insert"]
        if isinstance(ins, list):
            for p in ins:
                if isinstance(p, dict):
                    hi = max(hi, p["copy"] + p["length"])
    return hi


def _deleted_unknown_positions(e_old: list, raw_len: int) -> set:
    """Return raw indices deleted by e_old and never copied by e_old."""
    deleted = set()
    copied = set()
    for ed in e_old:
        s, d = ed["start"], ed["delete"]
        for k in range(s, s + d):
            deleted.add(k)
        ins = ed["insert"]
        if isinstance(ins, list):
            for p in ins:
                if isinstance(p, dict):
                    c, l = p["copy"], p["length"]
                    for k in range(c, c + l):
                        copied.add(k)
    return deleted - copied


def verify_modified_patches_double_sentinel(manifest: dict, base_rev: str = "HEAD") -> int:
    """For every patch modified vs git base_rev, prove double-sentinel invariance & schema."""
    r = subprocess.run(
        ["git", "-C", ROOT, "diff", "--name-only", base_rev, "--", "patches/"],
        capture_output=True,
        text=True,
    )
    if r.returncode != 0:
        return 0
    changed = [line.strip() for line in r.stdout.splitlines() if line.strip().endswith(".json") and line.strip() != "patches/manifest.json"]
    if not changed:
        return 0

    head_manifest_proc = subprocess.run(
        ["git", "-C", ROOT, "show", f"{base_rev}:patches/manifest.json"],
        capture_output=True,
        text=True,
    )
    head_manifest = json.loads(head_manifest_proc.stdout) if head_manifest_proc.returncode == 0 else None

    checked = 0
    for rel in sorted(changed):
        edits_rel = rel[len("patches/"):]
        old_proc = subprocess.run(
            ["git", "-C", ROOT, "show", f"{base_rev}:{rel}"],
            capture_output=True,
            text=True,
        )
        if old_proc.returncode != 0:
            continue
        e_old = json.loads(old_proc.stdout)
        with open(os.path.join(ROOT, rel), encoding="utf-8") as ef:
            e_new = json.load(ef)

        raw_len = max(_max_raw_index(e_old), _max_raw_index(e_new)) + 64
        unknown = _deleted_unknown_positions(e_old, raw_len)
        base_chars = [chr(65 + (i % 26)) for i in range(raw_len)]
        raw1 = "".join("\x01" if i in unknown else base_chars[i] for i in range(raw_len))
        raw2 = "".join("\x02" if i in unknown else base_chars[i] for i in range(raw_len))

        out1_old = apply_edits(raw1, e_old)
        out2_old = apply_edits(raw2, e_old)
        assert out1_old == out2_old and "\x01" not in out1_old, f"Baseline E1 sentinel check failed for {edits_rel}"

        out1_new = apply_edits(raw1, e_new)
        out2_new = apply_edits(raw2, e_new)
        if out1_new != out2_new or "\x01" in out1_new or "\x02" in out2_new:
            raise AssertionError(f"DOUBLE-SENTINEL INVARIANCE FAILED for {edits_rel}: new patch reads unknown deleted raw bytes!")

        # Also verify manifest sha256 actually changed whenever the edit list changed.
        if head_manifest is not None and e_new != e_old:
            for cls, ent in manifest["classes"].items():
                for out in ent["outputs"]:
                    if out["edits"] == edits_rel:
                        old_outs = [o for o in head_manifest["classes"][cls]["outputs"] if o["edits"] == edits_rel]
                        assert old_outs and old_outs[0]["sha256"] != out["sha256"], (
                            f"Manifest sha256 was not updated for modified patch {edits_rel}"
                        )
        checked += 1

    if checked:
        print(f"DOUBLE_SENTINEL_PROOF_PASS: {checked}/{len(changed)} modified patches invariant to all unknown deleted raw bytes")
    return checked


def main():
    source_edits_selfcheck()
    fuzz_test(500)
    verify_manifest_and_patches()


if __name__ == "__main__":
    main()
