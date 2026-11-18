# minecraft-web by chiikabu (https://github.com/chiikabu)
import difflib
from itertools import accumulate
import re

def make_edits(before, after):
    old_lines, new_lines = before.splitlines(True), after.splitlines(True)
    offsets = [0, *accumulate(map(len, old_lines))]
    edits = []

    old_start = new_start = 0
    for old_end, new_end, size in difflib.SequenceMatcher(
            None, old_lines, new_lines, autojunk=False).get_matching_blocks():
        if size == 1:
            continue
        original = re.findall(r"\w+|\s+|[^\w\s]", "".join(old_lines[old_start:old_end]))
        replacement = re.findall(r"\w+|\s+|[^\w\s]", "".join(new_lines[new_start:new_end]))
        positions = [0, *accumulate(map(len, original))]
        for operation, start, end, first, last in difflib.SequenceMatcher(
                None, original, replacement, autojunk=False).get_opcodes():
            if operation != "equal":
                edits.append({"start": offsets[old_start] + positions[start],
                              "delete": positions[end] - positions[start],
                              "insert": "".join(replacement[first:last])})
        old_start, new_start = old_end + size, new_end + size
    if before and any(len(edit["insert"]) >= 32 for edit in edits):
        original = re.findall(r"\w+|\s+|[^\w\s]", before)
        source_offsets = [0, *accumulate(map(len, original))]
        matcher = difflib.SequenceMatcher(None, [], original, autojunk=False)
        for edit in edits:
            insert = edit["insert"]
            if len(insert) < 32:
                continue
            tokens = re.findall(r"\w+|\s+|[^\w\s]", insert)
            positions = [0, *accumulate(map(len, tokens))]
            matcher.set_seq1(tokens)
            pieces, cursor = [], 0
            for new_start, old_start, size in matcher.get_matching_blocks():
                start, end = positions[new_start], positions[new_start + size]

                if end - start < 32:
                    continue
                if start > cursor:
                    pieces.append(insert[cursor:start])
                pieces.append({"copy": source_offsets[old_start], "length": end - start})
                cursor = end
            if pieces:
                if cursor < len(insert):
                    pieces.append(insert[cursor:])
                edit["insert"] = pieces
    return edits

def inserted_text(source, value):
    if isinstance(value, str):
        return value
    if not isinstance(value, list) or not value:
        raise ValueError("Insertion must be text or a nonempty list of text/source ranges")
    pieces = []
    for piece in value:
        if isinstance(piece, str):
            pieces.append(piece)
            continue
        if not isinstance(piece, dict) or set(piece) != {"copy", "length"}:
            raise ValueError("Invalid source-copy fields")
        start, length = piece["copy"], piece["length"]
        if (type(start) is not int or type(length) is not int or start < 0
                or length <= 0 or start + length > len(source)):
            raise ValueError("Source-copy range is outside the original source")
        pieces.append(source[start:start + length])
    return "".join(pieces)

def apply_edits(source, edits):
    if not isinstance(edits, list):
        raise ValueError("Source edits must be a list")
    cursor, previous = 0, -1
    output = []
    for edit in edits:
        if not isinstance(edit, dict) or set(edit) != {"start", "delete", "insert"}:
            raise ValueError("Invalid source edit fields")
        start, count = edit["start"], edit["delete"]
        if (type(start) is not int or type(count) is not int or start <= previous
                or start < cursor or count < 0 or start + count > len(source)):
            raise ValueError("Invalid, overlapping, or unordered source edit")
        insert = inserted_text(source, edit["insert"])
        if count == 0 and not insert:
            raise ValueError("Empty source edit")
        output.extend((source[cursor:start], insert))
        cursor, previous = start + count, start
    output.append(source[cursor:])
    return "".join(output)

def selfcheck():
    import random
    pairs = [("", "new"), ("removed", ""), ("same", "same"), ("a😀b", "aé😀c"),
             ("old\nunchanged\nlast\n", "new\nunchanged\nfirst\nlast\n"),
             ("alpha beta", "beta alpha"), ("a\r\nb\r\n", "a\r\nc\r\n")]
    for before, after in pairs:
        assert apply_edits(before, make_edits(before, after)) == after
    randomizer = random.Random(147)
    for _ in range(300):
        before = "".join(randomizer.choices("ab é😀\r\n{}()", k=80))
        start, end = sorted(randomizer.sample(range(len(before) + 1), 2))
        after = before[:start] + "".join(randomizer.choices("abc😀\n ", k=12)) + before[end:]
        assert apply_edits(before, make_edits(before, after)) == after
    before = "class Kept { int unchangedName = 1; }\n// deleted private contents\n"
    after = "class Kept { int unchangedName = 2; }\n"
    edits = make_edits(before, after)
    assert apply_edits(before, edits) == after
    assert "".join(edit["insert"] for edit in edits) == "2"
    body = "   consume(privateMarker, originalValue, originalFlags);\r\n" * 3
    before = "prefix é😀\r\n" + body + "suffix\r\n"
    after = before + "if (enabled) {\r\n" + body + "}\r\n"
    moved = make_edits(before, after)
    assert apply_edits(before, moved) == after
    assert "privateMarker" not in str(moved), "Copied source leaked into the edit payload"
    body = ("void source() {\n    Object first = lookupInput(original);\n"
            "    consumeOriginalValue(first, original);\n}\n")
    for repetitions in (1, 20):
        before = body * repetitions
        after = before + body.replace("source()", "moved()").replace("    ", "\t")
        moved = make_edits(before, after)
        assert apply_edits(before, moved) == after
        literals = "".join(part for edit in moved for part in
                           (edit["insert"] if isinstance(edit["insert"], list) else [edit["insert"]])
                           if isinstance(part, str))
        assert "lookupInput(original)" not in literals, "Reindented source leaked into literals"
        assert "consumeOriginalValue" not in literals, "Short copied statement leaked into literals"
    assert apply_edits("012345", [{"start": 0, "delete": 6,
                                  "insert": [{"copy": 4, "length": 2}, ":", {"copy": 0, "length": 2}]}]) == "45:01"
    assert inserted_text("é😀A", [{"copy": 1, "length": 2}]) == "😀A"
    for _ in range(50):
        before = " ".join("".join(randomizer.choices("abcdef😀é", k=6)) for _ in range(90))
        start, end = sorted(randomizer.sample(range(len(before) + 1), 2))
        after = before + "duplicate(" + before[start:end] + ")"
        assert apply_edits(before, make_edits(before, after)) == after
    valid = {"start": 0, "delete": 1, "insert": "x"}
    invalid = [None, [{}], [dict(valid, start=-1)], [dict(valid, start=True)],
               [dict(valid, delete=-1)], [dict(valid, delete=99)], [dict(valid, insert=1)],
               [dict(valid, extra=1)], [dict(valid, delete=0, insert="")],
               [valid, valid], [dict(valid, delete=2), dict(valid, start=1)],
               *[[dict(valid, insert=value)] for value in (
                   [], [None], [[]], [{"copy": 0}], [{"copy": 0, "length": 1, "extra": 1}],
                   [{"copy": -1, "length": 1}], [{"copy": True, "length": 1}],
                   [{"copy": 0, "length": False}], [{"copy": 0, "length": 0}],
                   [{"copy": 0, "length": -1}], [{"copy": 0, "length": 99}])]]
    for edits in invalid:
        try:
            apply_edits("source", edits)
        except ValueError:
            continue
        raise AssertionError("Invalid edits were accepted")
    print("SOURCE_EDITS_PASS: exact Unicode/CRLF replay; copied bodies referenced, not stored; malformed edits/ranges rejected")

if __name__ == "__main__":
    selfcheck()
