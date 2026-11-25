#!/usr/bin/env python3
"""End-to-end verification suite for the client-side JVM -> JS mod translator & Fabric/MC bridge.

Checks performed:
  1. File presence, optional sync with target_teavm_263/javascript-wasm-gc/ when staged,
     inline HTML/XML safety (no </script, <!--, or ]]>), and full JS lexical/delimiter
     balance plus minecraft-web.css and WebMods.java @JSBody ES5 checks.
  2. Packaging & staging integration across index.html, scripts/make_offline.py,
     and scripts/stage_site.py — including a live in-memory dry-run of
     make_offline.render() across html, xhtml, xml, and svg plus expat XML check.
  3. Pure-Python Java 21 (classfile major 65) bytecode assembler generating a
     spec-compliant DEFLATE-compressed synthetic Fabric .jar in memory with:
       - fabric.mod.json (ClientModInitializer entrypoint + mixin config)
       - selfcheck.mixins.json
       - Real binary .class files exercising loops, BigInt 64-bit longs, try/catch,
         invokedynamic StringConcatFactory, invokedynamic LambdaMetafactory,
         Gson (JsonParser + POJO toJson/fromJson), Minecraft.getInstance(),
         LocalPlayer, KeyBindingHelper, ClientTickEvents, HudRenderCallback, and
         @Mixin / @ModifyReturnValue / @Inject RuntimeVisibleAnnotations.
     Plus an independent JVMS binary parser verifying every emitted .class file.
  4. Live JavaScript execution via any native Linux JS engine (qjs, node, bun, deno,
     d8, gjs, jsc — strictly avoiding Windows /mnt/*.exe interop), executing
     minecraft-web-jvm-translator.js + minecraft-web-jvm-bridge.js directly against the
     compiled binary Fabric .jar to prove live end-to-end JVM -> JS execution!
"""

import argparse
import base64
import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.parsers.expat
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
WASM_GC_DIR = REPO / "target_teavm_263" / "wasm-gc"
JS_WASM_GC_DIR = REPO / "target_teavm_263" / "javascript-wasm-gc"
MODS_JAVA_DIR = REPO / "target_teavm_263" / "src" / "main" / "java" / "net" / "minecraft" / "client" / "web" / "mods"
SCRIPTS_DIR = REPO / "scripts"

TRANSLATOR_FILE = "minecraft-web-jvm-translator.js"
BRIDGE_FILE = "minecraft-web-jvm-bridge.js"
SHELL_FILE = "minecraft-web-shell.js"
CSS_FILE = "minecraft-web.css"

# Keywords after which `/` introduces a regex literal rather than division.
REGEX_PRECEDING_KEYWORDS = frozenset({
    "return", "case", "throw", "in", "instanceof", "typeof", "void",
    "delete", "new", "do", "else", "yield", "await", "of",
})
CONTROL_PAREN_KEYWORDS = frozenset({"if", "while", "for", "with"})


# ---------------------------------------------------------------------------
# 1. JS lexical / delimiter / HTML-safety validator
# ---------------------------------------------------------------------------

def check_js_syntax_and_safety(source: str, filename: str) -> dict:
    """Scan JS source for HTML-inline hazards and balanced strings/comments/delimiters."""
    issues = []

    m_script = re.search(r"</script", source, re.I)
    if m_script:
        line = source.count("\n", 0, m_script.start()) + 1
        issues.append(f"{filename}:{line}: contains forbidden '</script' sequence (breaks offline HTML inlining)")
    m_html_comment = re.search(r"<!--", source)
    if m_html_comment:
        line = source.count("\n", 0, m_html_comment.start()) + 1
        issues.append(f"{filename}:{line}: contains forbidden '<!--' sequence (breaks offline HTML inlining)")
    m_cdata_end = re.search(r"\]\]>", source)
    if m_cdata_end:
        line = source.count("\n", 0, m_cdata_end.start()) + 1
        issues.append(f"{filename}:{line}: contains forbidden ']]>' sequence (breaks offline XML/XHTML/SVG CDATA)")

    n = len(source)
    i = 0
    line = 1
    col = 1

    stack = []
    prev_kind = "start"
    prev_word = ""
    in_template = False

    def advance(ch: str):
        nonlocal line, col
        if ch == "\n":
            line += 1
            col = 1
        else:
            col += 1

    def can_start_regex() -> bool:
        if prev_kind in ("start", "punct", "close_control", "close_brace"):
            return True
        if prev_kind == "keyword" and prev_word in REGEX_PRECEDING_KEYWORDS:
            return True
        return False

    pairs = {"(": ")", "[": "]", "{": "}"}
    closers = {")": "(", "]": "[", "}": "{"}

    while i < n:
        ch = source[i]

        if in_template:
            if ch == "\\":
                advance(ch)
                i += 1
                if i < n:
                    advance(source[i])
                    i += 1
                continue
            if ch == "`":
                in_template = False
                prev_kind = "close_expr"
                prev_word = ""
                advance(ch)
                i += 1
                continue
            if ch == "$" and i + 1 < n and source[i + 1] == "{":
                stack.append(("{", line, col, "template_expr"))
                in_template = False
                prev_kind = "punct"
                prev_word = ""
                advance(ch)
                advance("{")
                i += 2
                continue
            advance(ch)
            i += 1
            continue

        if ch in " \t\r\n\f\v":
            advance(ch)
            i += 1
            continue

        if ch == "/" and i + 1 < n and source[i + 1] == "/":
            i += 2
            col += 2
            while i < n and source[i] != "\n":
                i += 1
                col += 1
            continue

        if ch == "/" and i + 1 < n and source[i + 1] == "*":
            start_line, start_col = line, col
            i += 2
            col += 2
            closed = False
            while i + 1 < n:
                if source[i] == "*" and source[i + 1] == "/":
                    i += 2
                    col += 2
                    closed = True
                    break
                advance(source[i])
                i += 1
            if not closed:
                issues.append(f"{filename}:{start_line}:{start_col}: unclosed block comment")
                break
            continue

        if ch in ("'", '"'):
            quote = ch
            start_line, start_col = line, col
            advance(ch)
            i += 1
            closed = False
            while i < n:
                c = source[i]
                if c == "\\":
                    advance(c)
                    i += 1
                    if i < n:
                        advance(source[i])
                        i += 1
                    continue
                if c == "\n":
                    issues.append(f"{filename}:{start_line}:{start_col}: unescaped newline in {quote} string")
                    break
                if c == quote:
                    advance(c)
                    i += 1
                    closed = True
                    break
                advance(c)
                i += 1
            if not closed and not issues:
                issues.append(f"{filename}:{start_line}:{start_col}: unclosed {quote} string")
            prev_kind = "close_expr"
            prev_word = ""
            continue

        if ch == "`":
            in_template = True
            advance(ch)
            i += 1
            continue

        if ch == "/" and can_start_regex():
            start_line, start_col = line, col
            advance(ch)
            i += 1
            in_class = False
            closed = False
            while i < n:
                c = source[i]
                if c == "\\":
                    advance(c)
                    i += 1
                    if i < n:
                        advance(source[i])
                        i += 1
                    continue
                if c == "\n":
                    issues.append(f"{filename}:{start_line}:{start_col}: unclosed regex literal")
                    break
                if c == "[":
                    in_class = True
                elif c == "]" and in_class:
                    in_class = False
                elif c == "/" and not in_class:
                    advance(c)
                    i += 1
                    while i < n and source[i].isalpha():
                        advance(source[i])
                        i += 1
                    closed = True
                    break
                advance(c)
                i += 1
            if not closed and not issues:
                issues.append(f"{filename}:{start_line}:{start_col}: unclosed regex literal")
            prev_kind = "close_expr"
            prev_word = ""
            continue

        if ch in pairs:
            meta = ""
            if ch == "(" and prev_kind == "keyword" and prev_word in CONTROL_PAREN_KEYWORDS:
                meta = "control_paren"
            stack.append((ch, line, col, meta))
            prev_kind = "punct"
            prev_word = ""
            advance(ch)
            i += 1
            continue

        if ch in closers:
            if not stack:
                issues.append(f"{filename}:{line}:{col}: unexpected closing '{ch}'")
                break
            open_ch, open_line, open_col, meta = stack.pop()
            if open_ch != closers[ch]:
                issues.append(
                    f"{filename}:{line}:{col}: mismatched '{ch}' closing '{open_ch}' opened at {open_line}:{open_col}"
                )
                break
            advance(ch)
            i += 1
            if meta == "template_expr":
                in_template = True
                prev_kind = "punct"
                prev_word = ""
            elif meta == "control_paren":
                prev_kind = "close_control"
                prev_word = ""
            elif ch == "}":
                prev_kind = "close_brace"
                prev_word = ""
            else:
                prev_kind = "close_expr"
                prev_word = ""
            continue

        if ch.isalpha() or ch in ("_", "$"):
            start = i
            while i < n and (source[i].isalnum() or source[i] in ("_", "$")):
                advance(source[i])
                i += 1
            word = source[start:i]
            if word in REGEX_PRECEDING_KEYWORDS or word in CONTROL_PAREN_KEYWORDS:
                prev_kind = "keyword"
            else:
                prev_kind = "ident"
            prev_word = word
            continue

        if ch.isdigit():
            while i < n and (source[i].isalnum() or source[i] in (".", "_")):
                advance(source[i])
                i += 1
            prev_kind = "number"
            prev_word = ""
            continue

        if ch in ("+", "-") and i + 1 < n and source[i + 1] == ch:
            advance(ch)
            advance(ch)
            i += 2
            prev_kind = "close_expr" if prev_kind in ("ident", "close_expr") else "punct"
            prev_word = ""
            continue

        prev_kind = "punct"
        prev_word = ch
        advance(ch)
        i += 1

    if in_template:
        issues.append(f"{filename}: unclosed template literal at EOF")
    for open_ch, open_line, open_col, _ in stack:
        issues.append(f"{filename}:{open_line}:{open_col}: unclosed '{open_ch}' at EOF")

    return {
        "file": filename,
        "bytes": len(source.encode("utf-8")),
        "lines": source.count("\n") + 1,
        "issues": issues,
    }


# ---------------------------------------------------------------------------
# 2. Pure-Python Java 21 (major 65) binary .class assembler & Fabric JAR builder
# ---------------------------------------------------------------------------

class CodeBuilder:
    """Minimal JVMS bytecode assembler with label patching and exception table."""

    def __init__(self, cb: "JvmClassBuilder"):
        self.cb = cb
        self.buf = bytearray()
        self.labels = {}
        self.fixups = []
        self.exc_table = []

    def pc(self) -> int:
        return len(self.buf)

    def label(self, name: str):
        self.labels[name] = len(self.buf)

    def emit(self, *ops: int):
        self.buf.extend(ops)

    def u1(self, v: int):
        self.buf.append(v & 0xFF)

    def s1(self, v: int):
        self.buf.extend(struct.pack(">b", v))

    def u2(self, v: int):
        self.buf.extend(struct.pack(">H", v))

    def s2(self, v: int):
        self.buf.extend(struct.pack(">h", v))

    def branch(self, opcode: int, target_label: str):
        op_pc = len(self.buf)
        self.buf.append(opcode)
        patch_pos = len(self.buf)
        self.buf.extend(b"\x00\x00")
        self.fixups.append((patch_pos, op_pc, target_label))

    def ldc(self, cp_idx: int):
        if cp_idx <= 255:
            self.emit(0x12, cp_idx)
        else:
            self.emit(0x13)
            self.u2(cp_idx)

    def ldc2_w(self, cp_idx: int):
        self.emit(0x14)
        self.u2(cp_idx)

    def getstatic(self, owner: str, name: str, desc: str):
        self.emit(0xB2)
        self.u2(self.cb.cp_fieldref(owner, name, desc))

    def putstatic(self, owner: str, name: str, desc: str):
        self.emit(0xB3)
        self.u2(self.cb.cp_fieldref(owner, name, desc))

    def getfield(self, owner: str, name: str, desc: str):
        self.emit(0xB4)
        self.u2(self.cb.cp_fieldref(owner, name, desc))

    def putfield(self, owner: str, name: str, desc: str):
        self.emit(0xB5)
        self.u2(self.cb.cp_fieldref(owner, name, desc))

    def invokevirtual(self, owner: str, name: str, desc: str):
        self.emit(0xB6)
        self.u2(self.cb.cp_methodref(owner, name, desc))

    def invokespecial(self, owner: str, name: str, desc: str):
        self.emit(0xB7)
        self.u2(self.cb.cp_methodref(owner, name, desc))

    def invokestatic(self, owner: str, name: str, desc: str):
        self.emit(0xB8)
        self.u2(self.cb.cp_methodref(owner, name, desc))

    def invokeinterface(self, owner: str, name: str, desc: str, arg_slots: int):
        self.emit(0xB9)
        self.u2(self.cb.cp_iface_methodref(owner, name, desc))
        self.u1(arg_slots)
        self.u1(0)

    def invokedynamic(self, bsm_idx: int, name: str, desc: str):
        self.emit(0xBA)
        self.u2(self.cb.cp_invokedynamic(bsm_idx, name, desc))
        self.u1(0)
        self.u1(0)

    def new_obj(self, cls: str):
        self.emit(0xBB)
        self.u2(self.cb.cp_class(cls))

    def checkcast(self, cls: str):
        self.emit(0xC0)
        self.u2(self.cb.cp_class(cls))

    def catch(self, start_lbl: str, end_lbl: str, handler_lbl: str, catch_cls: str = None):
        self.exc_table.append((start_lbl, end_lbl, handler_lbl, catch_cls))

    def build_attribute(self, max_stack: int, max_locals: int) -> bytes:
        code = bytearray(self.buf)
        for patch_pos, op_pc, lbl in self.fixups:
            if lbl not in self.labels:
                raise KeyError(f"Unresolved branch label: {lbl}")
            rel = self.labels[lbl] - op_pc
            struct.pack_into(">h", code, patch_pos, rel)
        out = bytearray()
        out.extend(struct.pack(">HHI", max_stack, max_locals, len(code)))
        out.extend(code)
        out.extend(struct.pack(">H", len(self.exc_table)))
        for s_lbl, e_lbl, h_lbl, catch_cls in self.exc_table:
            c_idx = self.cb.cp_class(catch_cls) if catch_cls else 0
            out.extend(struct.pack(">HHHH", self.labels[s_lbl], self.labels[e_lbl], self.labels[h_lbl], c_idx))
        out.extend(struct.pack(">H", 0))
        return bytes(out)


class JvmClassBuilder:
    """Emit a spec-compliant Java 21 (major 65) binary .class file."""

    ACC_PUBLIC = 0x0001
    ACC_PRIVATE = 0x0002
    ACC_STATIC = 0x0008
    ACC_FINAL = 0x0010
    ACC_SUPER = 0x0020
    ACC_SYNTHETIC = 0x1000

    REF_invokeVirtual = 5
    REF_invokeStatic = 6
    REF_invokeSpecial = 7
    REF_newInvokeSpecial = 8
    REF_invokeInterface = 9

    def __init__(self, this_class: str, super_class: str = "java/lang/Object",
                 interfaces=None, access_flags: int = 0x0021, major: int = 65):
        self.major = major
        self.access_flags = access_flags
        self._cp = []
        self._cp_map = {}
        self.this_idx = self.cp_class(this_class)
        self.super_idx = self.cp_class(super_class) if super_class else 0
        self.iface_idxs = [self.cp_class(i) for i in (interfaces or [])]
        self.fields = []
        self.methods = []
        self.bootstrap_methods = []
        self.class_annotations = []

    def _add_cp(self, key, raw: bytes, wide: bool = False) -> int:
        if key in self._cp_map:
            return self._cp_map[key]
        idx = len(self._cp) + 1
        self._cp.append(raw)
        if wide:
            self._cp.append(None)  # JVMS 4.4.5: Long and Double consume 2 slots
        self._cp_map[key] = idx
        return idx

    def cp_utf8(self, s: str) -> int:
        b = s.encode("utf-8")
        return self._add_cp(("utf8", s), struct.pack(">BH", 1, len(b)) + b)

    def cp_int(self, v: int) -> int:
        return self._add_cp(("int", v), struct.pack(">Bi", 3, v))

    def cp_float(self, v: float) -> int:
        return self._add_cp(("float", v), struct.pack(">Bf", 4, v))

    def cp_long(self, v: int) -> int:
        return self._add_cp(("long", v), struct.pack(">Bq", 5, v), wide=True)

    def cp_double(self, v: float) -> int:
        return self._add_cp(("double", v), struct.pack(">Bd", 6, v), wide=True)

    def cp_class(self, internal_name: str) -> int:
        ni = self.cp_utf8(internal_name)
        return self._add_cp(("class", internal_name), struct.pack(">BH", 7, ni))

    def cp_string(self, s: str) -> int:
        ui = self.cp_utf8(s)
        return self._add_cp(("string", s), struct.pack(">BH", 8, ui))

    def cp_nat(self, name: str, desc: str) -> int:
        ni = self.cp_utf8(name)
        di = self.cp_utf8(desc)
        return self._add_cp(("nat", name, desc), struct.pack(">BHH", 12, ni, di))

    def cp_fieldref(self, owner: str, name: str, desc: str) -> int:
        ci = self.cp_class(owner)
        nti = self.cp_nat(name, desc)
        return self._add_cp(("field", owner, name, desc), struct.pack(">BHH", 9, ci, nti))

    def cp_methodref(self, owner: str, name: str, desc: str) -> int:
        ci = self.cp_class(owner)
        nti = self.cp_nat(name, desc)
        return self._add_cp(("method", owner, name, desc), struct.pack(">BHH", 10, ci, nti))

    def cp_iface_methodref(self, owner: str, name: str, desc: str) -> int:
        ci = self.cp_class(owner)
        nti = self.cp_nat(name, desc)
        return self._add_cp(("imethod", owner, name, desc), struct.pack(">BHH", 11, ci, nti))

    def cp_method_handle(self, ref_kind: int, ref_idx: int) -> int:
        return self._add_cp(("mh", ref_kind, ref_idx), struct.pack(">BBH", 15, ref_kind, ref_idx))

    def cp_method_type(self, desc: str) -> int:
        di = self.cp_utf8(desc)
        return self._add_cp(("mt", desc), struct.pack(">BH", 16, di))

    def cp_invokedynamic(self, bsm_idx: int, name: str, desc: str) -> int:
        nti = self.cp_nat(name, desc)
        return self._add_cp(("indy", bsm_idx, name, desc), struct.pack(">BHH", 18, bsm_idx, nti))

    def add_bootstrap_method(self, bsm_handle_idx: int, arg_idxs: list) -> int:
        idx = len(self.bootstrap_methods)
        self.bootstrap_methods.append((bsm_handle_idx, list(arg_idxs)))
        return idx

    def _encode_elem_value(self, ev) -> bytes:
        tag, val = ev
        if tag == "s":
            return b"s" + struct.pack(">H", self.cp_utf8(val))
        if tag == "c":
            return b"c" + struct.pack(">H", self.cp_utf8(val))
        if tag == "I":
            return b"I" + struct.pack(">H", self.cp_int(val))
        if tag == "Z":
            return b"Z" + struct.pack(">H", self.cp_int(1 if val else 0))
        if tag == "F":
            return b"F" + struct.pack(">H", self.cp_float(val))
        if tag == "[":
            out = bytearray(b"[" + struct.pack(">H", len(val)))
            for item in val:
                out.extend(self._encode_elem_value(item))
            return bytes(out)
        if tag == "@":
            return b"@" + self._encode_annotation(val[0], val[1])
        raise ValueError(f"Unsupported annotation element tag: {tag}")

    def _encode_annotation(self, desc: str, pairs: list) -> bytes:
        out = bytearray(struct.pack(">HH", self.cp_utf8(desc), len(pairs)))
        for k, ev in pairs:
            out.extend(struct.pack(">H", self.cp_utf8(k)))
            out.extend(self._encode_elem_value(ev))
        return bytes(out)

    def _encode_rva(self, anns: list) -> tuple:
        name_idx = self.cp_utf8("RuntimeVisibleAnnotations")
        body = bytearray(struct.pack(">H", len(anns)))
        for desc, pairs in anns:
            body.extend(self._encode_annotation(desc, pairs))
        return (name_idx, bytes(body))

    def add_class_annotation(self, desc: str, pairs: list):
        self.class_annotations.append((desc, pairs))

    def add_field(self, access: int, name: str, desc: str):
        self.fields.append((access, self.cp_utf8(name), self.cp_utf8(desc), []))

    def add_method(self, access: int, name: str, desc: str, code_builder: CodeBuilder,
                   max_stack: int = 12, max_locals: int = 12, annotations=None):
        attrs = []
        if code_builder is not None:
            attrs.append((self.cp_utf8("Code"), code_builder.build_attribute(max_stack, max_locals)))
        if annotations:
            attrs.append(self._encode_rva(annotations))
        self.methods.append((access, self.cp_utf8(name), self.cp_utf8(desc), attrs))

    def add_default_init(self, super_class: str = "java/lang/Object"):
        c = CodeBuilder(self)
        c.emit(0x2A)  # aload_0
        c.invokespecial(super_class, "<init>", "()V")
        c.emit(0xB1)  # return
        self.add_method(self.ACC_PUBLIC, "<init>", "()V", c, max_stack=2, max_locals=1)

    def to_bytes(self) -> bytes:
        class_attrs = []
        if self.bootstrap_methods:
            bm_name = self.cp_utf8("BootstrapMethods")
            bm_body = bytearray(struct.pack(">H", len(self.bootstrap_methods)))
            for mh_idx, args in self.bootstrap_methods:
                bm_body.extend(struct.pack(">HH", mh_idx, len(args)))
                for a in args:
                    bm_body.extend(struct.pack(">H", a))
            class_attrs.append((bm_name, bytes(bm_body)))
        if self.class_annotations:
            class_attrs.append(self._encode_rva(self.class_annotations))

        out = bytearray()
        out.extend(struct.pack(">IHH", 0xCAFEBABE, 0, self.major))
        out.extend(struct.pack(">H", len(self._cp) + 1))
        for entry in self._cp:
            if entry is not None:
                out.extend(entry)
        out.extend(struct.pack(">HHH", self.access_flags, self.this_idx, self.super_idx))
        out.extend(struct.pack(">H", len(self.iface_idxs)))
        for ii in self.iface_idxs:
            out.extend(struct.pack(">H", ii))

        out.extend(struct.pack(">H", len(self.fields)))
        for acc, ni, di, attrs in self.fields:
            out.extend(struct.pack(">HHHH", acc, ni, di, len(attrs)))
            for an, ab in attrs:
                out.extend(struct.pack(">HI", an, len(ab)))
                out.extend(ab)

        out.extend(struct.pack(">H", len(self.methods)))
        for acc, ni, di, attrs in self.methods:
            out.extend(struct.pack(">HHHH", acc, ni, di, len(attrs)))
            for an, ab in attrs:
                out.extend(struct.pack(">HI", an, len(ab)))
                out.extend(ab)

        out.extend(struct.pack(">H", len(class_attrs)))
        for an, ab in class_attrs:
            out.extend(struct.pack(">HI", an, len(ab)))
            out.extend(ab)

        return bytes(out)


def verify_classfile_binary(data: bytes, expected_name: str) -> dict:
    """Independent JVMS binary classfile parser validating structural integrity."""
    pos = 0

    def u1():
        nonlocal pos
        v = data[pos]
        pos += 1
        return v

    def u2():
        nonlocal pos
        v = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        return v

    def u4():
        nonlocal pos
        v = struct.unpack_from(">I", data, pos)[0]
        pos += 4
        return v

    magic = u4()
    assert magic == 0xCAFEBABE, f"Bad magic: {hex(magic)}"
    minor = u2()
    major = u2()
    assert major == 65, f"Expected Java 21 (major 65), got {major}"
    cp_count = u2()
    cp = [None] * cp_count
    idx = 1
    while idx < cp_count:
        tag = u1()
        if tag == 1:
            ln = u2()
            cp[idx] = ("Utf8", data[pos:pos + ln].decode("utf-8"))
            pos += ln
        elif tag in (3, 4):
            pos += 4
            cp[idx] = ("Num4",)
        elif tag in (5, 6):
            pos += 8
            cp[idx] = ("Num8",)
            idx += 1
        elif tag in (7, 8, 16):
            cp[idx] = (tag, u2())
        elif tag in (9, 10, 11, 12, 18):
            cp[idx] = (tag, u2(), u2())
        elif tag == 15:
            cp[idx] = (15, u1(), u2())
        else:
            raise AssertionError(f"Unexpected CP tag {tag} at slot {idx}")
        idx += 1

    u2()
    this_cls = u2()
    u2()
    this_name = cp[cp[this_cls][1]][1]
    assert this_name == expected_name, f"Class name mismatch: {this_name} != {expected_name}"
    ifaces_count = u2()
    for _ in range(ifaces_count):
        u2()

    def skip_attrs():
        nonlocal pos
        ac = u2()
        names = []
        for _ in range(ac):
            an = cp[u2()][1]
            al = u4()
            pos_end = pos + al
            assert pos_end <= len(data), f"Truncated attribute {an}"
            names.append(an)
            pos = pos_end
        return names

    fc = u2()
    for _ in range(fc):
        u2(); u2(); u2()
        skip_attrs()
    mc = u2()
    method_names = []
    for _ in range(mc):
        u2()
        mn = cp[u2()][1]
        u2()
        skip_attrs()
        method_names.append(mn)
    cls_attrs = skip_attrs()
    assert pos == len(data), f"Trailing bytes in {expected_name}: {len(data) - pos}"
    return {
        "class": this_name,
        "major": major,
        "minor": minor,
        "cp_count": cp_count,
        "fields": fc,
        "methods": method_names,
        "class_attrs": cls_attrs,
        "bytes": len(data),
    }


def build_synthetic_fabric_jar() -> tuple:
    """Build a real synthetic Java 21 Fabric mod JAR exercising every JVM + Fabric feature."""
    classes = {}

    # --- 1. Simple POJO for Gson test: com/example/selfcheck/ModConfig ---
    cfg = JvmClassBuilder("com/example/selfcheck/ModConfig")
    cfg.add_field(cfg.ACC_PUBLIC, "label", "Ljava/lang/String;")
    cfg.add_field(cfg.ACC_PUBLIC, "zoomFactor", "F")
    cfg.add_default_init()
    classes["com/example/selfcheck/ModConfig.class"] = cfg.to_bytes()

    # --- 2. Main ClientModInitializer: com/example/selfcheck/SelfCheckClientMod ---
    mod_cls = "com/example/selfcheck/SelfCheckClientMod"
    m = JvmClassBuilder(mod_cls, interfaces=["net/fabricmc/api/ClientModInitializer"])
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "initialized", "Z")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "loopSum", "I")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "longResult", "J")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "catchResult", "I")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "concatResult", "Ljava/lang/String;")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "gsonJson", "Ljava/lang/String;")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "gsonParserLabel", "Ljava/lang/String;")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "gsonPojoLabel", "Ljava/lang/String;")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "tickCount", "I")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "hudCount", "I")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "keyPressedCount", "I")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "lastPlayerX", "D")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "lastPlayerHealth", "F")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "lastHudText", "Ljava/lang/String;")
    m.add_field(m.ACC_PUBLIC | m.ACC_STATIC, "zoomKey", "Lnet/minecraft/client/KeyMapping;")
    m.add_default_init()

    # Bootstrap Method 0: StringConcatFactory.makeConcatWithConstants("Mod=\x01 sum=\x01")
    scf_ref = m.cp_methodref(
        "java/lang/invoke/StringConcatFactory",
        "makeConcatWithConstants",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
    )
    scf_mh = m.cp_method_handle(m.REF_invokeStatic, scf_ref)
    bsm_concat = m.add_bootstrap_method(scf_mh, [m.cp_string("Mod=\x01 sum=\x01")])

    # Bootstrap Method 1: LambdaMetafactory for ClientTickEvents$EndTick
    lmf_ref = m.cp_methodref(
        "java/lang/invoke/LambdaMetafactory",
        "metafactory",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
        "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
        "Ljava/lang/invoke/CallSite;",
    )
    lmf_mh = m.cp_method_handle(m.REF_invokeStatic, lmf_ref)
    tick_target_ref = m.cp_methodref(mod_cls, "onClientTick", "(Lnet/minecraft/client/Minecraft;)V")
    tick_target_mh = m.cp_method_handle(m.REF_invokeStatic, tick_target_ref)
    tick_mt = m.cp_method_type("(Lnet/minecraft/client/Minecraft;)V")
    bsm_tick_lambda = m.add_bootstrap_method(lmf_mh, [tick_mt, tick_target_mh, tick_mt])

    # Bootstrap Method 2: LambdaMetafactory for HudRenderCallback
    hud_desc = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"
    hud_target_ref = m.cp_methodref(mod_cls, "onHudRender", hud_desc)
    hud_target_mh = m.cp_method_handle(m.REF_invokeStatic, hud_target_ref)
    hud_mt = m.cp_method_type(hud_desc)
    bsm_hud_lambda = m.add_bootstrap_method(lmf_mh, [hud_mt, hud_target_mh, hud_mt])

    # Helper method: public static int testTryCatch() -> 42
    tc_c = CodeBuilder(m)
    tc_c.label("try_start")
    tc_c.new_obj("java/lang/RuntimeException")
    tc_c.emit(0x59)
    tc_c.ldc(m.cp_string("selfcheck-trap"))
    tc_c.invokespecial("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V")
    tc_c.emit(0xBF)  # athrow
    tc_c.label("try_end")
    tc_c.label("catch_handler")
    tc_c.emit(0x57)
    tc_c.emit(0x10, 42)
    tc_c.emit(0xAC)  # ireturn
    tc_c.catch("try_start", "try_end", "catch_handler", "java/lang/RuntimeException")
    m.add_method(m.ACC_PUBLIC | m.ACC_STATIC, "testTryCatch", "()I", tc_c, max_stack=4, max_locals=2)

    # Helper method: public static void testTryCatchExitSafety()
    # Catches first RuntimeException, then throws a second one AFTER the try block.
    # Verifies epc is updated/cleared after exiting the try range (must NOT infinite-loop!).
    tce_c = CodeBuilder(m)
    tce_c.label("t1_start")
    tce_c.new_obj("java/lang/RuntimeException")
    tce_c.emit(0x59)
    tce_c.ldc(m.cp_string("first"))
    tce_c.invokespecial("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V")
    tce_c.emit(0xBF)
    tce_c.label("t1_end")
    tce_c.label("t1_catch")
    tce_c.emit(0x57)
    tce_c.new_obj("java/lang/IllegalStateException")
    tce_c.emit(0x59)
    tce_c.ldc(m.cp_string("escaped-after-catch"))
    tce_c.invokespecial("java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V")
    tce_c.emit(0xBF)
    tce_c.catch("t1_start", "t1_end", "t1_catch", "java/lang/RuntimeException")
    m.add_method(m.ACC_PUBLIC | m.ACC_STATIC, "testTryCatchExitSafety", "()V", tce_c, max_stack=4, max_locals=2)

    # Helper method: public static String testGsonPojo(String json)
    gp_c = CodeBuilder(m)
    gp_c.new_obj("com/google/gson/Gson")
    gp_c.emit(0x59)
    gp_c.invokespecial("com/google/gson/Gson", "<init>", "()V")
    gp_c.emit(0x2A)  # aload_0 (json)
    gp_c.ldc(m.cp_class("com/example/selfcheck/ModConfig"))
    gp_c.invokevirtual("com/google/gson/Gson", "fromJson", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;")
    gp_c.checkcast("com/example/selfcheck/ModConfig")
    gp_c.getfield("com/example/selfcheck/ModConfig", "label", "Ljava/lang/String;")
    gp_c.emit(0xB0)  # areturn
    m.add_method(m.ACC_PUBLIC | m.ACC_STATIC, "testGsonPojo", "(Ljava/lang/String;)Ljava/lang/String;",
                 gp_c, max_stack=6, max_locals=2)

    # Benchmark helper 1: public static int benchMix(int a, int b) -> ((a * 31) ^ (b + 17)) + (a >>> 3)
    bm_c = CodeBuilder(m)
    bm_c.emit(0x1A)          # iload_0 (a)
    bm_c.emit(0x10, 31)      # bipush 31
    bm_c.emit(0x68)          # imul
    bm_c.emit(0x1B)          # iload_1 (b)
    bm_c.emit(0x10, 17)      # bipush 17
    bm_c.emit(0x60)          # iadd
    bm_c.emit(0x82)          # ixor
    bm_c.emit(0x1A)          # iload_0 (a)
    bm_c.emit(0x06)          # iconst_3
    bm_c.emit(0x7C)          # iushr
    bm_c.emit(0x60)          # iadd
    bm_c.emit(0xAC)          # ireturn
    m.add_method(m.ACC_PUBLIC | m.ACC_STATIC, "benchMix", "(II)I", bm_c, max_stack=4, max_locals=2)

    # Benchmark helper 2: public static int benchHotLoop(int n)
    #   int acc = 1; for (int i = 0; i < n; i++) acc = benchMix(acc, i); return acc;
    bl_c = CodeBuilder(m)
    bl_c.emit(0x04, 0x3C)    # iconst_1; istore_1 (acc = 1)
    bl_c.emit(0x03, 0x3D)    # iconst_0; istore_2 (i = 0)
    bl_c.label("bench_head")
    bl_c.emit(0x1C, 0x1A)    # iload_2 (i); iload_0 (n)
    bl_c.branch(0xA2, "bench_end")  # if_icmpge bench_end
    bl_c.emit(0x1B, 0x1C)    # iload_1 (acc); iload_2 (i)
    bl_c.invokestatic(mod_cls, "benchMix", "(II)I")
    bl_c.emit(0x3C)          # istore_1 (acc)
    bl_c.emit(0x84, 2, 1)    # iinc 2, 1
    bl_c.branch(0xA7, "bench_head")
    bl_c.label("bench_end")
    bl_c.emit(0x1B, 0xAC)    # iload_1; ireturn
    m.add_method(m.ACC_PUBLIC | m.ACC_STATIC, "benchHotLoop", "(I)I", bl_c, max_stack=4, max_locals=3)

    # Method: onInitializeClient()V
    init_c = CodeBuilder(m)
    init_c.emit(0x04)
    init_c.putstatic(mod_cls, "initialized", "Z")

    # Loop: sum 1..10 = 55
    init_c.emit(0x03, 0x3C)
    init_c.emit(0x04, 0x3D)
    init_c.label("loop_head")
    init_c.emit(0x1C, 0x10, 10)
    init_c.branch(0xA3, "loop_end")
    init_c.emit(0x1B, 0x1C, 0x60, 0x3C)
    init_c.emit(0x84, 2, 1)
    init_c.branch(0xA7, "loop_head")
    init_c.label("loop_end")
    init_c.emit(0x1B)
    init_c.putstatic(mod_cls, "loopSum", "I")

    # 64-bit BigInt long math: (1_000_000_000_000L + 234_567_890_123L) * 2L - 100L = 2_469_135_780_146L
    init_c.ldc2_w(m.cp_long(1_000_000_000_000))
    init_c.ldc2_w(m.cp_long(234_567_890_123))
    init_c.emit(0x61)
    init_c.ldc2_w(m.cp_long(2))
    init_c.emit(0x69)
    init_c.ldc2_w(m.cp_long(100))
    init_c.emit(0x65)
    init_c.putstatic(mod_cls, "longResult", "J")

    # try/catch helper
    init_c.invokestatic(mod_cls, "testTryCatch", "()I")
    init_c.putstatic(mod_cls, "catchResult", "I")

    # invokedynamic StringConcatFactory
    init_c.ldc(m.cp_string("selfcheck_mod"))
    init_c.getstatic(mod_cls, "loopSum", "I")
    init_c.invokedynamic(bsm_concat, "makeConcatWithConstants", "(Ljava/lang/String;I)Ljava/lang/String;")
    init_c.putstatic(mod_cls, "concatResult", "Ljava/lang/String;")

    # Gson: toJson(ModConfig) + JsonParser.parseString(json).getAsJsonObject().get("label").getAsString()
    init_c.new_obj("com/google/gson/Gson")
    init_c.emit(0x59)
    init_c.invokespecial("com/google/gson/Gson", "<init>", "()V")
    init_c.emit(0x4E)  # astore_3

    init_c.new_obj("com/example/selfcheck/ModConfig")
    init_c.emit(0x59)
    init_c.invokespecial("com/example/selfcheck/ModConfig", "<init>", "()V")
    init_c.emit(0x59)
    init_c.ldc(m.cp_string("turbo-zoom"))
    init_c.putfield("com/example/selfcheck/ModConfig", "label", "Ljava/lang/String;")
    init_c.emit(0x59)
    init_c.ldc(m.cp_float(0.25))
    init_c.putfield("com/example/selfcheck/ModConfig", "zoomFactor", "F")
    init_c.emit(0x3A, 4)

    init_c.emit(0x2D)
    init_c.emit(0x19, 4)
    init_c.invokevirtual("com/google/gson/Gson", "toJson", "(Ljava/lang/Object;)Ljava/lang/String;")
    init_c.emit(0x59)
    init_c.putstatic(mod_cls, "gsonJson", "Ljava/lang/String;")
    init_c.invokestatic("com/google/gson/JsonParser", "parseString", "(Ljava/lang/String;)Lcom/google/gson/JsonElement;")
    init_c.invokevirtual("com/google/gson/JsonElement", "getAsJsonObject", "()Lcom/google/gson/JsonObject;")
    init_c.ldc(m.cp_string("label"))
    init_c.invokevirtual("com/google/gson/JsonObject", "get", "(Ljava/lang/String;)Lcom/google/gson/JsonElement;")
    init_c.invokevirtual("com/google/gson/JsonElement", "getAsString", "()Ljava/lang/String;")
    init_c.putstatic(mod_cls, "gsonParserLabel", "Ljava/lang/String;")

    # KeyBindingHelper.registerKeyBinding(new KeyMapping("key.selfcheck.zoom", 67, "category.selfcheck"))
    init_c.new_obj("net/minecraft/client/KeyMapping")
    init_c.emit(0x59)
    init_c.ldc(m.cp_string("key.selfcheck.zoom"))
    init_c.emit(0x10, 67)
    init_c.ldc(m.cp_string("category.selfcheck"))
    init_c.invokespecial("net/minecraft/client/KeyMapping", "<init>", "(Ljava/lang/String;ILjava/lang/String;)V")
    init_c.invokestatic(
        "net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper",
        "registerKeyBinding",
        "(Lnet/minecraft/client/KeyMapping;)Lnet/minecraft/client/KeyMapping;",
    )
    init_c.putstatic(mod_cls, "zoomKey", "Lnet/minecraft/client/KeyMapping;")

    # ClientTickEvents.END_CLIENT_TICK.register(SelfCheckClientMod::onClientTick)
    init_c.getstatic(
        "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents",
        "END_CLIENT_TICK",
        "Lnet/fabricmc/fabric/api/event/Event;",
    )
    init_c.invokedynamic(
        bsm_tick_lambda,
        "onEndTick",
        "()Lnet/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$EndTick;",
    )
    init_c.invokevirtual("net/fabricmc/fabric/api/event/Event", "register", "(Ljava/lang/Object;)V")

    # HudRenderCallback.EVENT.register(SelfCheckClientMod::onHudRender)
    init_c.getstatic(
        "net/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback",
        "EVENT",
        "Lnet/fabricmc/fabric/api/event/Event;",
    )
    init_c.invokedynamic(
        bsm_hud_lambda,
        "onHudRender",
        "()Lnet/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback;",
    )
    init_c.invokevirtual("net/fabricmc/fabric/api/event/Event", "register", "(Ljava/lang/Object;)V")
    init_c.emit(0xB1)
    m.add_method(m.ACC_PUBLIC, "onInitializeClient", "()V", init_c, max_stack=10, max_locals=8)

    # Method: public static void onClientTick(Minecraft client)
    tick_c = CodeBuilder(m)
    tick_c.getstatic(mod_cls, "tickCount", "I")
    tick_c.emit(0x04, 0x60)
    tick_c.putstatic(mod_cls, "tickCount", "I")

    tick_c.invokestatic("net/minecraft/client/Minecraft", "getInstance", "()Lnet/minecraft/client/Minecraft;")
    tick_c.emit(0x4C)
    tick_c.emit(0x2B)
    tick_c.branch(0xC6, "no_player")
    tick_c.emit(0x2B)
    tick_c.getfield("net/minecraft/client/Minecraft", "player", "Lnet/minecraft/client/player/LocalPlayer;")
    tick_c.emit(0x4D)
    tick_c.emit(0x2C)
    tick_c.branch(0xC6, "no_player")
    tick_c.emit(0x2C)
    tick_c.invokevirtual("net/minecraft/client/player/LocalPlayer", "getX", "()D")
    tick_c.putstatic(mod_cls, "lastPlayerX", "D")
    tick_c.emit(0x2C)
    tick_c.invokevirtual("net/minecraft/client/player/LocalPlayer", "getHealth", "()F")
    tick_c.putstatic(mod_cls, "lastPlayerHealth", "F")
    tick_c.label("no_player")

    tick_c.getstatic(mod_cls, "zoomKey", "Lnet/minecraft/client/KeyMapping;")
    tick_c.branch(0xC6, "tick_done")
    tick_c.getstatic(mod_cls, "zoomKey", "Lnet/minecraft/client/KeyMapping;")
    tick_c.invokevirtual("net/minecraft/client/KeyMapping", "isDown", "()Z")
    tick_c.branch(0x99, "tick_done")
    tick_c.getstatic(mod_cls, "keyPressedCount", "I")
    tick_c.emit(0x04, 0x60)
    tick_c.putstatic(mod_cls, "keyPressedCount", "I")
    tick_c.label("tick_done")
    tick_c.emit(0xB1)
    m.add_method(m.ACC_PUBLIC | m.ACC_STATIC, "onClientTick", "(Lnet/minecraft/client/Minecraft;)V",
                 tick_c, max_stack=6, max_locals=4)

    # Method: public static void onHudRender(GuiGraphics g, DeltaTracker deltaTracker)
    hud_c = CodeBuilder(m)
    hud_c.getstatic(mod_cls, "hudCount", "I")
    hud_c.emit(0x04, 0x60)
    hud_c.putstatic(mod_cls, "hudCount", "I")

    hud_c.ldc(m.cp_string("HUD"))
    hud_c.getstatic(mod_cls, "tickCount", "I")
    hud_c.invokedynamic(bsm_concat, "makeConcatWithConstants", "(Ljava/lang/String;I)Ljava/lang/String;")
    hud_c.emit(0x59)
    hud_c.putstatic(mod_cls, "lastHudText", "Ljava/lang/String;")
    hud_c.emit(0x4D)

    hud_c.emit(0x2A)
    hud_c.branch(0xC6, "hud_done")
    hud_c.emit(0x2A)
    hud_c.emit(0x01)
    hud_c.emit(0x2C)
    hud_c.emit(0x10, 8)
    hud_c.emit(0x10, 8)
    hud_c.ldc(m.cp_int(0xFFFFFF))
    hud_c.invokevirtual(
        "net/minecraft/client/gui/GuiGraphics",
        "drawString",
        "(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I",
    )
    hud_c.emit(0x57)
    hud_c.label("hud_done")
    hud_c.emit(0xB1)
    m.add_method(m.ACC_PUBLIC | m.ACC_STATIC, "onHudRender", hud_desc, hud_c, max_stack=8, max_locals=4)

    classes[f"{mod_cls}.class"] = m.to_bytes()

    # --- 3. @Mixin with @ModifyReturnValue (GameRenderer.getFov -> fovModifier) ---
    fov_cls = "com/example/selfcheck/mixin/FovModifierMixin"
    fm = JvmClassBuilder(fov_cls)
    fm.add_class_annotation(
        "Lorg/spongepowered/asm/mixin/Mixin;",
        [("value", ("[", [("c", "Lnet/minecraft/client/renderer/GameRenderer;")]))],
    )
    fm.add_default_init()
    fov_c = CodeBuilder(fm)
    fov_c.emit(0x23)  # fload_1 (original)
    fov_c.ldc(fm.cp_float(0.5))
    fov_c.emit(0x6A)  # fmul
    fov_c.emit(0xAE)  # freturn
    fm.add_method(
        fm.ACC_PUBLIC,
        "modifyFov",
        "(F)F",
        fov_c,
        max_stack=4,
        max_locals=3,
        annotations=[(
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            [
                ("method", ("[", [("s", "getFov")])),
                ("at", ("[", [("@", ("Lorg/spongepowered/asm/mixin/injection/At;", [("value", ("s", "RETURN"))]))])),
            ],
        )],
    )
    classes[f"{fov_cls}.class"] = fm.to_bytes()

    # --- 4. @Mixin with @Inject (Minecraft.tick @ TAIL -> tickEnd) ---
    inj_cls = "com/example/selfcheck/mixin/MinecraftTickInjectMixin"
    im = JvmClassBuilder(inj_cls)
    im.add_class_annotation(
        "Lorg/spongepowered/asm/mixin/Mixin;",
        [("targets", ("[", [("s", "net.minecraft.client.Minecraft")]))],
    )
    im.add_field(im.ACC_PUBLIC | im.ACC_STATIC, "injectHits", "I")
    im.add_default_init()
    inj_c = CodeBuilder(im)
    inj_c.getstatic(inj_cls, "injectHits", "I")
    inj_c.emit(0x04, 0x60)
    inj_c.putstatic(inj_cls, "injectHits", "I")
    inj_c.emit(0xB1)
    im.add_method(
        im.ACC_PUBLIC,
        "onMinecraftTick",
        "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",
        inj_c,
        max_stack=4,
        max_locals=3,
        annotations=[(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            [
                ("method", ("[", [("s", "tick")])),
                ("at", ("[", [("@", ("Lorg/spongepowered/asm/mixin/injection/At;", [("value", ("s", "TAIL"))]))])),
            ],
        )],
    )
    classes[f"{inj_cls}.class"] = im.to_bytes()

    parsed = []
    for path, raw in classes.items():
        parsed.append(verify_classfile_binary(raw, path[:-6]))

    fabric_mod_json = {
        "schemaVersion": 1,
        "id": "selfcheck_mod",
        "version": "1.0.0",
        "name": "SelfCheck Mod",
        "description": "Synthetic Java 21 Fabric mod built by jvm_translator_selfcheck.py",
        "environment": "client",
        "entrypoints": {
            "client": ["com.example.selfcheck.SelfCheckClientMod"],
        },
        "mixins": ["selfcheck.mixins.json"],
    }
    mixins_json = {
        "required": True,
        "package": "com.example.selfcheck.mixin",
        "compatibilityLevel": "JAVA_21",
        "client": ["FovModifierMixin", "MinecraftTickInjectMixin"],
        "injectors": {"defaultRequire": 1},
    }

    jar_buf = io.BytesIO()
    with zipfile.ZipFile(jar_buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("fabric.mod.json", json.dumps(fabric_mod_json, indent=2))
        zf.writestr("selfcheck.mixins.json", json.dumps(mixins_json, indent=2))
        for path, raw in classes.items():
            zf.writestr(path, raw)
    return jar_buf.getvalue(), parsed


# ---------------------------------------------------------------------------
# 3. Wiring, packaging, CSS/Java checks & make_offline dry-run
# ---------------------------------------------------------------------------

def check_wiring_and_packaging() -> list:
    report = []

    idx_a = (WASM_GC_DIR / "index.html").read_bytes()
    idx_b_path = JS_WASM_GC_DIR / "index.html"
    if idx_b_path.is_file():
        idx_b = idx_b_path.read_bytes()
        assert idx_a == idx_b, "target_teavm_263/wasm-gc/index.html and javascript-wasm-gc/index.html differ!"
        sync_note = "synced"
    else:
        sync_note = "template"
    idx_text = idx_a.decode("utf-8")

    t_tag = f'<script src="{TRANSLATOR_FILE}"></script>'
    b_tag = f'<script src="{BRIDGE_FILE}"></script>'
    assert t_tag in idx_text, f"index.html missing {t_tag}"
    assert b_tag in idx_text, f"index.html missing {b_tag}"
    assert idx_text.index(t_tag) < idx_text.index(b_tag), (
        f"index.html must load {TRANSLATOR_FILE} before {BRIDGE_FILE}"
    )
    assert idx_text.index(f'<script src="{SHELL_FILE}"></script>') < idx_text.index(t_tag), (
        f"index.html should load {SHELL_FILE} before {TRANSLATOR_FILE}"
    )
    report.append(f"index.html {sync_note} ({len(idx_a)} bytes) with ordered translator+bridge <script> tags")

    sys.path.insert(0, str(SCRIPTS_DIR))
    import stage_site  # noqa: E402
    assert TRANSLATOR_FILE in stage_site.PAGE_FILES, "stage_site.PAGE_FILES missing translator"
    assert BRIDGE_FILE in stage_site.PAGE_FILES, "stage_site.PAGE_FILES missing bridge"
    staged_names = set(stage_site.PAGE_FILES) | {
        "minecraft_blobstore.js", "boot-logo.png", "favicon.png",
        "wasm-gc/classes263.wasm-runtime.js",
    }
    for ref in re.findall(r'(?:src|href)="([^":?#]+)"', idx_text):
        assert ref in staged_names, f"index.html references '{ref}' which stage_site.py does not stage"
    for needle in ('id="credit"', "__minecraftInstallWebStore"):
        assert needle in idx_text, f"stage_site.py needle missing from index.html: {needle}"
    report.append("stage_site.py PAGE_FILES + all index.html src/href references verified")

    import make_offline  # noqa: E402
    assert TRANSLATOR_FILE in make_offline.PAGE_SCRIPTS, "make_offline.PAGE_SCRIPTS missing translator"
    assert BRIDGE_FILE in make_offline.PAGE_SCRIPTS, "make_offline.PAGE_SCRIPTS missing bridge"
    assert make_offline.PAGE_SCRIPTS.index(TRANSLATOR_FILE) < make_offline.PAGE_SCRIPTS.index(BRIDGE_FILE), (
        "make_offline.PAGE_SCRIPTS must order translator before bridge"
    )
    for needle in ("floatfloat(", "_frag_output_00", "classes263.wasm-runtime.js", "minecraft-web-mesh.js",
                   TRANSLATOR_FILE, BRIDGE_FILE):
        assert needle in idx_text, f"make_offline.py needle missing from index.html: {needle}"
    report.append(f"make_offline.py PAGE_SCRIPTS ({len(make_offline.PAGE_SCRIPTS)} entries) + index needles verified")

    return report


def check_css_and_java_mod_sources() -> list:
    report = []

    css_path = WASM_GC_DIR / CSS_FILE
    assert css_path.is_file(), f"Missing {css_path}"
    css_src = css_path.read_text(encoding="utf-8")
    assert "</style" not in css_src.lower(), f"{CSS_FILE} contains forbidden '</style'"
    assert "]]>" not in css_src, f"{CSS_FILE} contains forbidden ']]>'"
    for selector in ("#eagler-drop-overlay", "#eagler-mod-build-toast", "#eagler-mod-hud-canvas"):
        assert selector in css_src, f"{CSS_FILE} missing Minecraft mod UI selector: {selector}"
    report.append(f"{CSS_FILE}: offline-safe with drop overlay, build toast, and HUD canvas rules")

    for jname in ("ModJarInspector.java", "WebMods.java", "ModsScreen.java"):
        jp = MODS_JAVA_DIR / jname
        assert jp.is_file(), f"Missing {jp}"

    wm_src = (MODS_JAVA_DIR / "WebMods.java").read_text(encoding="utf-8")
    if "Camera.webFovModifier" in wm_src:
        cam_patch = (REPO / "patches" / "port263" / "net" / "minecraft" / "client" / "Camera.json").read_text(encoding="utf-8")
        assert "webFovModifier" in cam_patch, "WebMods.java references Camera.webFovModifier without Camera.json patch"
    assert "WebLifecycleMsg.safePackName" not in wm_src, "WebMods.java must not call package-private WebLifecycleMsg.safePackName"
    assert not re.search(r"</script", wm_src, re.I), "WebMods.java contains forbidden '</script'"
    assert "<!--" not in wm_src, "WebMods.java contains forbidden '<!--'"
    assert "]]>" not in wm_src, "WebMods.java contains forbidden ']]>'"

    # Extract every @JSBody script string from WebMods.java and check ES5 + zero statement-position IIFEs
    jsbody_blocks = []
    for m in re.finditer(r"@JSBody\s*\((.*?)\)\s*private\s+static\s+native", wm_src, re.S):
        ann = m.group(1)
        sm = re.search(r"script\s*=\s*(.*)", ann, re.S)
        assert sm, "Malformed @JSBody annotation in WebMods.java"
        literals = re.findall(r'"((?:[^"\\]|\\.)*)"', sm.group(1))
        decoded = ""
        for lit in literals:
            decoded += json.loads('"' + lit + '"')
        jsbody_blocks.append(decoded)
        assert not re.search(r"(?:^|[;{}])\s*\(\s*function\s*\(", decoded), (
            f"Statement-position IIFE in WebMods.java @JSBody: {decoded[:120]}"
        )
        assert not re.search(r"\b(?:const|let)\s+[A-Za-z_$]", decoded), (
            f"Non-ES5 const/let in WebMods.java @JSBody: {decoded[:120]}"
        )
        assert "=>" not in decoded, f"Non-ES5 arrow function in WebMods.java @JSBody: {decoded[:120]}"
        assert "`" not in decoded, f"Non-ES5 template literal in WebMods.java @JSBody: {decoded[:120]}"
    assert len(jsbody_blocks) >= 25, f"Expected >=25 @JSBody methods in WebMods.java, got {len(jsbody_blocks)}"

    engine = find_native_js_engine()
    if engine is not None:
        kind, bin_path = engine
        check_js = (
            "var bodies = " + json.dumps(jsbody_blocks) + ";\n"
            "for (var i = 0; i < bodies.length; i++) { new Function(bodies[i]); }\n"
            "console.log('JSBODY_ES5_OK:' + bodies.length);\n"
        )
        with tempfile.NamedTemporaryFile("w", suffix=".js", encoding="utf-8", delete=False) as tf:
            tf.write(check_js)
            tf_path = tf.name
        try:
            cmd = [bin_path, "--std", tf_path] if kind == "qjs" else (
                [bin_path, "run", tf_path] if kind == "deno" else [bin_path, tf_path]
            )
            proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=15)
            assert proc.returncode == 0 and f"JSBODY_ES5_OK:{len(jsbody_blocks)}" in proc.stdout, (
                f"WebMods.java @JSBody syntax check failed:\n{proc.stdout}"
            )
        finally:
            try:
                os.unlink(tf_path)
            except OSError:
                pass
    report.append(
        f"Java mod sources verified (ModJarInspector, WebMods with {len(jsbody_blocks)} ES5 @JSBody blocks, ModsScreen)"
    )
    return report


def check_js_files_and_offline_dry_run(skip_sync_check: bool = False) -> list:
    report = []
    for fname in (TRANSLATOR_FILE, BRIDGE_FILE):
        p_a = WASM_GC_DIR / fname
        p_b = JS_WASM_GC_DIR / fname
        assert p_a.is_file(), f"Missing {p_a}"
        ba = p_a.read_bytes()
        if not skip_sync_check and p_b.is_file():
            bb = p_b.read_bytes()
            assert ba == bb, f"{fname} differs between wasm-gc/ and javascript-wasm-gc/"
            sync_label = "synced"
        else:
            sync_label = "template"
        src = ba.decode("utf-8")
        res = check_js_syntax_and_safety(src, fname)
        assert not res["issues"], "JS issues:\n  " + "\n  ".join(res["issues"])
        report.append(
            f"{fname}: {sync_label} ({res['bytes']} bytes, {res['lines']} lines), HTML/XML-safe, delimiters balanced"
        )

    t_src = (WASM_GC_DIR / TRANSLATOR_FILE).read_text(encoding="utf-8")
    b_src = (WASM_GC_DIR / BRIDGE_FILE).read_text(encoding="utf-8")
    for sym in ("EaglerJvmTranslator", "MinecraftWebJvmTranslator", "unzipJar", "parseClass",
                "createRuntime", "getSharedRuntime", "loadJar", "unloadMod", "getModReport",
                "listTranslatedMods", "dispatchHook", "hasHook"):
        assert sym in t_src, f"{TRANSLATOR_FILE} missing locked API symbol: {sym}"
    for sym in ("EaglerJvmBridge", "MinecraftWebJvmBridge", "install", "attachMixins", "renderHudOverlay"):
        assert sym in b_src, f"{BRIDGE_FILE} missing locked API symbol: {sym}"

    sys.path.insert(0, str(SCRIPTS_DIR))
    import make_offline  # noqa: E402
    if (JS_WASM_GC_DIR / "boot-logo.png").is_file() and TRANSLATOR_FILE in make_offline.PAGE_SCRIPTS:
        parts = make_offline.page_parts()
        script_names = [name for name, _ in parts["scripts"]]
        assert TRANSLATOR_FILE in script_names and BRIDGE_FILE in script_names
    else:
        mesh_src = (WASM_GC_DIR / "minecraft-web-mesh.js").read_text(encoding="utf-8")
        mesh_src = mesh_src.replace(
            make_offline.MESH_WORKER_SPAWN,
            'new Worker(window.__minecraftWebMeshWorkerUrl || new URL("minecraft-web-mesh-worker.js", location.href))',
        )
        scripts = [
            ("minecraft_xz_loader.js", (SCRIPTS_DIR / "minecraft_xz_loader.js").read_text(encoding="utf-8")),
            ("mesh worker url", 'window.__minecraftWebMeshWorkerUrl = URL.createObjectURL(new Blob('
             '[document.getElementById("mesh-worker-src").textContent], {type: "text/javascript"}));'),
            ("minecraft_blobstore.js", (SCRIPTS_DIR / "minecraft_blobstore.js").read_text(encoding="utf-8")),
            ("minecraft-web-net.js", (WASM_GC_DIR / "minecraft-web-net.js").read_text(encoding="utf-8")),
            ("minecraft-web-shell.js", (WASM_GC_DIR / "minecraft-web-shell.js").read_text(encoding="utf-8")),
            (TRANSLATOR_FILE, t_src),
            (BRIDGE_FILE, b_src),
            ("minecraft-web-touch.js", (WASM_GC_DIR / "minecraft-web-touch.js").read_text(encoding="utf-8")),
            ("minecraft-web-mesh.js", mesh_src),
        ]
        parts = {
            "css": (WASM_GC_DIR / CSS_FILE).read_text(encoding="utf-8"),
            "mesh_worker": (WASM_GC_DIR / "minecraft-web-mesh-worker.js").read_text(encoding="utf-8"),
            "scripts": scripts,
            "logo": "data:image/png;base64,iVBORw0KGgo=",
            "icon": "data:image/png;base64,iVBORw0KGgo=",
        }

    dummy_archive = {
        "version": "26.3-selfcheck",
        "meta": {"wasmSha256": "0" * 64},
        "xz": "DUMMYXZ",
        "loader": "QUFB",
    }
    for fmt in make_offline.FORMATS:
        doc = make_offline.render(fmt, parts, dummy_archive, "silent")
        assert "EaglerJvmTranslator" in doc and "EaglerJvmBridge" in doc, (
            f"Offline {fmt} output missing translator/bridge code"
        )
        assert "#eagler-drop-overlay" in doc and "#eagler-mod-build-toast" in doc and "#eagler-mod-hud-canvas" in doc, (
            f"Offline {fmt} output missing Minecraft mod UI CSS rules"
        )
        if fmt != "html":
            parser = xml.parsers.expat.ParserCreate()
            parser.Parse(doc.encode("utf-8"), True)
    report.append("make_offline.py dry-run passed across all 4 formats (html, xhtml, xml, svg + expat XML check)")
    return report


# ---------------------------------------------------------------------------
# 4. Native Linux JS engine discovery & live end-to-end execution
# ---------------------------------------------------------------------------

def is_native_linux_binary(path: str) -> bool:
    """Strictly reject Windows /mnt/* paths and .exe binaries (WSL interop hangs)."""
    if not path:
        return False
    low = path.lower()
    if low.endswith(".exe") or low.endswith(".cmd") or low.endswith(".bat"):
        return False
    real = os.path.realpath(path)
    if real.startswith("/mnt/") or path.startswith("/mnt/"):
        return False
    if not os.path.isfile(real) or not os.access(real, os.X_OK):
        return False
    try:
        with open(real, "rb") as f:
            hdr = f.read(4)
        return hdr == b"\x7fELF" or hdr[:2] == b"#!"
    except OSError:
        return False


def find_native_js_engine():
    """Return (kind, path) for an available native Linux JS runtime, or None."""
    candidates = [
        ("node", "node"),
        ("node", "nodejs"),
        ("bun", "bun"),
        ("deno", "deno"),
        ("qjs", "qjs"),
        ("d8", "d8"),
        ("gjs", "gjs"),
        ("jsc", "jsc"),
    ]
    linux_path = os.pathsep.join(
        p for p in os.environ.get("PATH", "").split(os.pathsep)
        if p and not p.startswith("/mnt/")
    )
    for kind, name in candidates:
        found = shutil.which(name, path=linux_path)
        if found and is_native_linux_binary(found):
            return (kind, found)
    for extra in ("/usr/bin/qjs", "/usr/local/bin/qjs", "/root/brel/qjs"):
        if is_native_linux_binary(extra):
            return ("qjs", extra)
    return None


def build_live_js_harness(translator_path: Path, bridge_path: Path, jar_bytes: bytes, engine_kind: str) -> str:
    """Build a self-contained JS test harness runnable under qjs, node, bun, deno, d8, etc."""
    jar_b64 = base64.b64encode(jar_bytes).decode("ascii")
    t_path_js = json.dumps(str(translator_path))
    b_path_js = json.dumps(str(bridge_path))
    return f"""
// Auto-generated live verification harness for minecraft-web-jvm-translator.js + minecraft-web-jvm-bridge.js
(function () {{
  "use strict";
  var g = typeof globalThis !== "undefined" ? globalThis : this;
  if (typeof g.window === "undefined") g.window = g;
  if (typeof g.self === "undefined") g.self = g;
  if (typeof g.console === "undefined") {{
    g.console = {{
      log: function () {{ if (typeof print === "function") print(Array.prototype.join.call(arguments, " ")); }},
      info: function () {{ if (typeof print === "function") print(Array.prototype.join.call(arguments, " ")); }},
      warn: function () {{ if (typeof print === "function") print("WARN: " + Array.prototype.join.call(arguments, " ")); }},
      error: function () {{ if (typeof print === "function") print("ERROR: " + Array.prototype.join.call(arguments, " ")); }},
      debug: function () {{}}
    }};
  }}
  if (typeof g.performance === "undefined") {{
    var t0 = Date.now();
    g.performance = {{ now: function () {{ return Date.now() - t0; }} }};
  }}
  if (typeof g.TextDecoder === "undefined") {{
    g.TextDecoder = function () {{}};
    g.TextDecoder.prototype.decode = function (bytes) {{
      if (!bytes) return "";
      var u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes.buffer || bytes, bytes.byteOffset || 0, bytes.byteLength || bytes.length || 0);
      var out = "", i = 0, n = u8.length;
      while (i < n) {{
        var b0 = u8[i++];
        if (b0 < 0x80) {{ out += String.fromCharCode(b0); }}
        else if ((b0 & 0xE0) === 0xC0 && i < n) {{
          out += String.fromCharCode(((b0 & 0x1F) << 6) | (u8[i++] & 0x3F));
        }} else if ((b0 & 0xF0) === 0xE0 && i + 1 < n) {{
          out += String.fromCharCode(((b0 & 0x0F) << 12) | ((u8[i++] & 0x3F) << 6) | (u8[i++] & 0x3F));
        }} else if ((b0 & 0xF8) === 0xF0 && i + 2 < n) {{
          var cp = (((b0 & 0x07) << 18) | ((u8[i++] & 0x3F) << 12) | ((u8[i++] & 0x3F) << 6) | (u8[i++] & 0x3F)) - 0x10000;
          out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
        }}
      }}
      return out;
    }};
  }}
  if (typeof g.TextEncoder === "undefined") {{
    g.TextEncoder = function () {{}};
    g.TextEncoder.prototype.encode = function (str) {{
      var arr = [];
      for (var i = 0; i < str.length; i++) {{
        var c = str.charCodeAt(i);
        if (c < 0x80) arr.push(c);
        else if (c < 0x800) arr.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
        else arr.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
      }}
      return new Uint8Array(arr);
    }};
  }}
  if (typeof g.atob === "undefined") {{
    var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    g.atob = function (s) {{
      s = String(s).replace(/=+$/, "");
      var out = "";
      for (var i = 0; i < s.length; i += 4) {{
        var a = B64.indexOf(s[i]), b = B64.indexOf(s[i+1]);
        var c = i + 2 < s.length ? B64.indexOf(s[i+2]) : -1;
        var d = i + 3 < s.length ? B64.indexOf(s[i+3]) : -1;
        out += String.fromCharCode((a << 2) | (b >> 4));
        if (c >= 0) out += String.fromCharCode(((b & 15) << 4) | (c >> 2));
        if (d >= 0) out += String.fromCharCode(((c & 3) << 6) | d);
      }}
      return out;
    }};
  }}

  var winListeners = {{}};
  g.addEventListener = function (type, fn) {{
    if (!winListeners[type]) winListeners[type] = [];
    winListeners[type].push(fn);
  }};
  g.removeEventListener = function () {{}};
  function fireWinEvent(type, ev) {{
    var list = winListeners[type] || [];
    for (var i = 0; i < list.length; i++) list[i](ev);
  }}

  var drawnTexts = [];
  function makeMockCanvas(id) {{
    var ctx2d = {{
      fillStyle: "#ffffff",
      font: "10px monospace",
      textBaseline: "top",
      imageSmoothingEnabled: false,
      save: function () {{}},
      restore: function () {{}},
      scale: function () {{}},
      translate: function () {{}},
      clearRect: function () {{}},
      fillRect: function () {{}},
      fillText: function (txt, x, y) {{ drawnTexts.push({{ text: String(txt), x: x, y: y }}); }},
      measureText: function (txt) {{ return {{ width: String(txt).length * 6 }}; }},
      createLinearGradient: function () {{ return {{ addColorStop: function () {{}} }}; }}
    }};
    return {{
      id: id || "",
      width: 854, height: 480, clientWidth: 854, clientHeight: 480, style: {{}},
      getContext: function () {{ return ctx2d; }},
      getBoundingClientRect: function () {{ return {{ left: 0, top: 0, width: 854, height: 480 }}; }}
    }};
  }}

  var domById = {{ game_frame: makeMockCanvas("game_frame") }};
  g.devicePixelRatio = 1;
  g.innerWidth = 854;
  g.innerHeight = 480;
  g.document = {{
    body: {{
      appendChild: function (el) {{ if (el && el.id) domById[el.id] = el; return el; }},
      removeChild: function (el) {{ if (el && el.id && domById[el.id] === el) delete domById[el.id]; }},
      contains: function (el) {{ return !!(el && el.id && domById[el.id] === el); }}
    }},
    getElementById: function (id) {{ return domById[id] || null; }},
    createElement: function () {{ return makeMockCanvas(""); }},
    addEventListener: function () {{}}
  }};

  var tickHandlers = [];
  var frameHandlers = [];
  var currentFov = 70;
  var currentGamma = 0.5;
  var mockState = {{
    frameSeq: 1, inWorld: true, tick: 1, fov: 70, gamma: 0.5, renderDistance: 8, fps: 60,
    player: {{
      name: "SelfCheckPlayer", x: 123.5, y: 64.0, z: -42.25,
      vx: 0, vy: 0, vz: 0, yaw: 90.0, pitch: 0.0,
      health: 18.5, maxHealth: 20.0, food: 20, armor: 0,
      onGround: true, sprinting: false, crouching: false
    }},
    world: {{
      dimension: "minecraft:overworld", gameTime: 1200, clockTime: 6000,
      raining: false, thundering: false
    }}
  }};
  g.EaglerMods = {{
    _liveState: mockState,
    getState: function () {{ return mockState; }},
    getFov: function () {{ return currentFov; }},
    setFov: function (v) {{ currentFov = v; mockState.fov = v; }},
    getGamma: function () {{ return currentGamma; }},
    setGamma: function (v) {{ currentGamma = v; mockState.gamma = v; }},
    onTick: function (fn) {{ tickHandlers.push(fn); }},
    onFrame: function (fn) {{ frameHandlers.push(fn); }},
    onScreenChange: function () {{}},
    onWorldJoin: function () {{}},
    onWorldLeave: function () {{}},
    chat: function () {{}},
    actionBar: function () {{}},
    sendChat: function () {{}}
  }};

  function readFileUtf8(p) {{
    if (typeof std !== "undefined" && typeof std.loadFile === "function") return std.loadFile(p);
    if (typeof require === "function") return require("fs").readFileSync(p, "utf8");
    if (typeof Deno !== "undefined") return Deno.readTextFileSync(p);
    if (typeof read === "function") return read(p);
    throw new Error("No file reader available in JS host");
  }}

  (0, eval)(readFileUtf8({t_path_js}));
  (0, eval)(readFileUtf8({b_path_js}));

  var passed = [];
  var failed = [];
  function check(name, cond, detail) {{
    if (cond) {{
      passed.push(name);
      console.log("  [PASS] " + name + (detail ? " (" + detail + ")" : ""));
    }} else {{
      failed.push(name + ": " + detail);
      console.log("  [FAIL] " + name + ": " + detail);
    }}
  }}

  var T = g.EaglerJvmTranslator;
  var B = g.EaglerJvmBridge;
  check("globals_present",
    !!(T && typeof T.loadJar === "function" && B && typeof B.install === "function" &&
       typeof B.attachMixins === "function" && typeof B.renderHudOverlay === "function" &&
       g.MinecraftWebJvmTranslator === T && g.MinecraftWebJvmBridge === B),
    "EaglerJvmTranslator/MinecraftWebJvmTranslator + EaglerJvmBridge/MinecraftWebJvmBridge");

  var binStr = g.atob({json.dumps(jar_b64)});
  var jarBytes = new Uint8Array(binStr.length);
  for (var i = 0; i < binStr.length; i++) jarBytes[i] = binStr.charCodeAt(i);

  var report = T.loadJar("selfcheck_mod.jar", jarBytes);
  check("deflate_jar_load",
    !!(report && report.ok && report.classesLoaded === 4 && report.entrypointsRun.length === 1 && report.errors.length === 0),
    "classes=" + (report && report.classesLoaded) + " eps=" + JSON.stringify(report && report.entrypointsRun) + " errs=" + JSON.stringify(report && report.errors));

  var rt = T.getSharedRuntime();
  var modCls = "com/example/selfcheck/SelfCheckClientMod";

  check("onInitializeClient_loop",
    Boolean(rt.getStatic(modCls, "initialized")) === true && rt.getStatic(modCls, "loopSum") === 55,
    "loopSum=" + rt.getStatic(modCls, "loopSum"));

  var longVal = rt.getStatic(modCls, "longResult");
  check("bigint_64bit_longs",
    longVal === 2469135780146n || String(longVal) === "2469135780146",
    "longResult=" + typeof longVal + ":" + String(longVal));

  check("try_catch_basic",
    rt.getStatic(modCls, "catchResult") === 42,
    "catchResult=" + rt.getStatic(modCls, "catchResult"));

  check("invokedynamic_string_concat",
    String(rt.getStatic(modCls, "concatResult")) === "Mod=selfcheck_mod sum=55",
    "concatResult=" + JSON.stringify(rt.getStatic(modCls, "concatResult")));

  var gsonJson = rt.getStatic(modCls, "gsonJson");
  check("gson_toJson_and_JsonParser",
    typeof gsonJson === "string" && gsonJson.indexOf("turbo-zoom") >= 0 &&
    String(rt.getStatic(modCls, "gsonParserLabel")) === "turbo-zoom",
    "gsonJson=" + gsonJson + " parsed=" + rt.getStatic(modCls, "gsonParserLabel"));

  // Test Gson.fromJson(json, ModConfig.class) POJO deserialization
  var pojoOk = false, pojoDetail = "";
  try {{
    var lbl = rt.invokeStatic(modCls, "testGsonPojo", "(Ljava/lang/String;)Ljava/lang/String;", [gsonJson]);
    pojoOk = String(lbl) === "turbo-zoom";
    pojoDetail = "label=" + JSON.stringify(lbl);
  }} catch (e) {{
    pojoDetail = (e && (e.__cls || e.message)) ? ((e.__cls || "") + " " + (e.detailMessage || e.message || "")) : String(e);
  }}
  check("gson_pojo_fromJson", pojoOk, pojoDetail);

  // Test try/catch exit safety (exception thrown AFTER a try/catch must NOT re-enter catch handler)
  var exitSafeOk = false, exitSafeDetail = "";
  var origWrap = rt.wrapException;
  var wrapHits = 0;
  rt.wrapException = function (ex) {{
    if (++wrapHits > 10) {{
      var loopErr = new Error("Stale epc catch-handler infinite loop detected (>10 catches)");
      loopErr.__jvmStepLimit = true;
      throw loopErr;
    }}
    return origWrap.call(this, ex);
  }};
  try {{
    rt.invokeStatic(modCls, "testTryCatchExitSafety", "()V", []);
    exitSafeDetail = "did not throw IllegalStateException";
  }} catch (e) {{
    var isStepLimit = !!(e && e.__jvmStepLimit);
    var clsName = (e && (e.__cls || e.__class)) || "";
    exitSafeOk = !isStepLimit && clsName === "java/lang/IllegalStateException";
    exitSafeDetail = isStepLimit ? "stale epc after try/catch caused infinite catch re-entry (" + wrapHits + " catches)" : (clsName || String(e));
  }} finally {{
    rt.wrapException = origWrap;
  }}
  check("try_catch_exit_safety", exitSafeOk, exitSafeDetail);

  var zk = rt.getStatic(modCls, "zoomKey");
  check("keybinding_helper_register", !!(zk && zk._keyCode === 67), "keyCode=" + (zk && zk._keyCode));

  // Fire KeyC keydown + live EaglerMods tick + HUD frame
  fireWinEvent("keydown", {{ code: "KeyC", key: "c", keyCode: 67, repeat: false }});
  for (var ti = 0; ti < tickHandlers.length; ti++) tickHandlers[ti](mockState, 1);

  check("client_tick_lambda",
    rt.getStatic(modCls, "tickCount") === 1,
    "tickCount=" + rt.getStatic(modCls, "tickCount"));
  check("minecraft_and_localplayer",
    rt.getStatic(modCls, "lastPlayerX") === 123.5 && Math.abs(rt.getStatic(modCls, "lastPlayerHealth") - 18.5) < 0.01,
    "x=" + rt.getStatic(modCls, "lastPlayerX") + " health=" + rt.getStatic(modCls, "lastPlayerHealth"));
  check("keymapping_isDown",
    rt.getStatic(modCls, "keyPressedCount") === 1 && B.isKeyDown(67) === true,
    "keyPressedCount=" + rt.getStatic(modCls, "keyPressedCount"));

  if (frameHandlers.length > 0) {{
    for (var fi = 0; fi < frameHandlers.length; fi++) frameHandlers[fi]({{ frame: 1 }});
  }} else {{
    B.renderHudOverlay({{ rt: rt, tickDelta: 0.5 }});
  }}
  var hudDrawn = drawnTexts.some(function (d) {{ return d.text.indexOf("Mod=HUD sum=1") >= 0; }});
  check("hud_render_lambda_and_drawString",
    rt.getStatic(modCls, "hudCount") >= 1 && hudDrawn,
    "hudCount=" + rt.getStatic(modCls, "hudCount") + " texts=" + JSON.stringify(drawnTexts.map(function (d) {{ return d.text; }})));

  // Check @Mixin @ModifyReturnValue FOV modifier (70 * 0.5 = 35)
  check("mixin_report_count",
    report.mixinsAttached >= 2,
    "report.mixinsAttached=" + report.mixinsAttached);
  check("mixin_fov_modifier",
    currentFov === 35,
    "currentFov=" + currentFov + " (expected 35)");
  var injHits = rt.getStatic("com/example/selfcheck/mixin/MinecraftTickInjectMixin", "injectHits");
  check("mixin_minecraft_tick_inject",
    injHits >= 1,
    "injectHits=" + injHits);

  // Check unloadMod
  var unloaded = T.unloadMod("selfcheck_mod") === true && T.listTranslatedMods().length === 0;
  check("mod_unload", unloaded, "remaining=" + T.listTranslatedMods().length);

  var summary = {{ engine: {json.dumps(engine_kind)}, passed: passed.length, failed: failed.length, failures: failed }};
  if (failed.length === 0) {{
    console.log("LIVE_SELFCHECK_PASS:" + JSON.stringify(summary));
  }} else {{
    console.log("LIVE_SELFCHECK_FAIL:" + JSON.stringify(summary));
    if (typeof std !== "undefined" && std.exit) std.exit(1);
    if (typeof process !== "undefined" && process.exit) process.exit(1);
    throw new Error("Live selfcheck failures: " + failed.join("; "));
  }}
}})();
"""


def _i32(v: int) -> int:
    v &= 0xFFFFFFFF
    return v - 0x100000000 if v >= 0x80000000 else v


def _expected_bench_hot_loop(n: int) -> int:
    acc = 1
    for i in range(n):
        a31 = _i32(acc * 31)
        b17 = _i32(i + 17)
        x = _i32(a31 ^ b17)
        s = (acc & 0xFFFFFFFF) >> 3
        acc = _i32(x + s)
    return acc


def build_live_js_bench_harness(translator_path: Path, bridge_path: Path, jar_bytes: bytes, engine_kind: str) -> str:
    """Build Section 5: Live QuickJS Performance Benchmark running on the compiled Java 21 Fabric .jar."""
    jar_b64 = base64.b64encode(jar_bytes).decode("ascii")
    t_path_js = json.dumps(str(translator_path))
    b_path_js = json.dumps(str(bridge_path))
    hot_inner = 500
    hot_outer = 200
    expected_hot = _expected_bench_hot_loop(hot_inner)
    return f"""
// Auto-generated Section 5 performance benchmark harness for minecraft-web-jvm-translator.js + minecraft-web-jvm-bridge.js
(function () {{
  "use strict";
  var g = typeof globalThis !== "undefined" ? globalThis : this;
  if (typeof g.window === "undefined") g.window = g;
  if (typeof g.self === "undefined") g.self = g;
  if (typeof g.console === "undefined") {{
    g.console = {{
      log: function () {{ if (typeof print === "function") print(Array.prototype.join.call(arguments, " ")); }},
      info: function () {{ if (typeof print === "function") print(Array.prototype.join.call(arguments, " ")); }},
      warn: function () {{ if (typeof print === "function") print("WARN: " + Array.prototype.join.call(arguments, " ")); }},
      error: function () {{ if (typeof print === "function") print("ERROR: " + Array.prototype.join.call(arguments, " ")); }},
      debug: function () {{}}
    }};
  }}
  var nowMs = (typeof g.performance !== "undefined" && typeof g.performance.now === "function")
    ? function () {{ return g.performance.now(); }}
    : ((typeof os !== "undefined" && typeof os.now === "function")
      ? function () {{ return os.now(); }}
      : function () {{ return Date.now(); }});
  if (typeof g.performance === "undefined") {{
    var t0 = nowMs();
    g.performance = {{ now: function () {{ return nowMs() - t0; }} }};
  }}
  if (typeof g.TextDecoder === "undefined") {{
    g.TextDecoder = function () {{}};
    g.TextDecoder.prototype.decode = function (bytes) {{
      if (!bytes) return "";
      var u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes.buffer || bytes, bytes.byteOffset || 0, bytes.byteLength || bytes.length || 0);
      var out = "", i = 0, n = u8.length;
      while (i < n) {{
        var b0 = u8[i++];
        if (b0 < 0x80) {{ out += String.fromCharCode(b0); }}
        else if ((b0 & 0xE0) === 0xC0 && i < n) {{
          out += String.fromCharCode(((b0 & 0x1F) << 6) | (u8[i++] & 0x3F));
        }} else if ((b0 & 0xF0) === 0xE0 && i + 1 < n) {{
          out += String.fromCharCode(((b0 & 0x0F) << 12) | ((u8[i++] & 0x3F) << 6) | (u8[i++] & 0x3F));
        }} else if ((b0 & 0xF8) === 0xF0 && i + 2 < n) {{
          var cp = (((b0 & 0x07) << 18) | ((u8[i++] & 0x3F) << 12) | ((u8[i++] & 0x3F) << 6) | (u8[i++] & 0x3F)) - 0x10000;
          out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
        }}
      }}
      return out;
    }};
  }}
  if (typeof g.TextEncoder === "undefined") {{
    g.TextEncoder = function () {{}};
    g.TextEncoder.prototype.encode = function (str) {{
      var arr = [];
      for (var i = 0; i < str.length; i++) {{
        var c = str.charCodeAt(i);
        if (c < 0x80) arr.push(c);
        else if (c < 0x800) arr.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
        else arr.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
      }}
      return new Uint8Array(arr);
    }};
  }}
  if (typeof g.atob === "undefined") {{
    var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    g.atob = function (s) {{
      s = String(s).replace(/=+$/, "");
      var out = "";
      for (var i = 0; i < s.length; i += 4) {{
        var a = B64.indexOf(s[i]), b = B64.indexOf(s[i+1]);
        var c = i + 2 < s.length ? B64.indexOf(s[i+2]) : -1;
        var d = i + 3 < s.length ? B64.indexOf(s[i+3]) : -1;
        out += String.fromCharCode((a << 2) | (b >> 4));
        if (c >= 0) out += String.fromCharCode(((b & 15) << 4) | (c >> 2));
        if (d >= 0) out += String.fromCharCode(((c & 3) << 6) | d);
      }}
      return out;
    }};
  }}

  var winListeners = {{}};
  g.addEventListener = function (type, fn) {{
    if (!winListeners[type]) winListeners[type] = [];
    winListeners[type].push(fn);
  }};
  g.removeEventListener = function () {{}};
  function fireWinEvent(type, ev) {{
    var list = winListeners[type] || [];
    for (var i = 0; i < list.length; i++) list[i](ev);
  }}

  var canvasStats = {{ clearRects: 0, fillRects: 0, fillTexts: 0 }};
  function makeBenchCanvas(id) {{
    var ctx2d = {{
      fillStyle: "#ffffff",
      font: "10px monospace",
      textBaseline: "top",
      textAlign: "left",
      imageSmoothingEnabled: false,
      save: function () {{}},
      restore: function () {{}},
      scale: function () {{}},
      translate: function () {{}},
      clearRect: function () {{ canvasStats.clearRects++; }},
      fillRect: function () {{ canvasStats.fillRects++; }},
      fillText: function () {{ canvasStats.fillTexts++; }},
      measureText: function (txt) {{ return {{ width: String(txt).length * 6 }}; }},
      createLinearGradient: function () {{ return {{ addColorStop: function () {{}} }}; }}
    }};
    return {{
      id: id || "",
      width: 854, height: 480, clientWidth: 854, clientHeight: 480, style: {{}},
      getContext: function () {{ return ctx2d; }},
      getBoundingClientRect: function () {{ return {{ left: 0, top: 0, width: 854, height: 480 }}; }}
    }};
  }}

  var domById = {{ game_frame: makeBenchCanvas("game_frame") }};
  g.devicePixelRatio = 1;
  g.innerWidth = 854;
  g.innerHeight = 480;
  g.document = {{
    body: {{
      appendChild: function (el) {{ if (el && el.id) domById[el.id] = el; return el; }},
      removeChild: function (el) {{ if (el && el.id && domById[el.id] === el) delete domById[el.id]; }},
      contains: function (el) {{ return !!(el && el.id && domById[el.id] === el); }}
    }},
    getElementById: function (id) {{ return domById[id] || null; }},
    createElement: function () {{ return makeBenchCanvas(""); }},
    addEventListener: function () {{}}
  }};

  var currentFov = 70;
  var currentGamma = 0.5;
  var liveState = {{
    frameSeq: 1, inWorld: true, paused: false, tick: 1, frame: 1,
    fov: 70, gamma: 0.5, renderDistance: 8, fps: 60,
    guiScale: 2, guiWidth: 427, guiHeight: 240, windowWidth: 854, windowHeight: 480,
    screen: null, screenName: "", screenTitle: "", username: "BenchPlayer", profile: "vanilla",
    player: {{
      name: "BenchPlayer", x: 123.5, y: 64.0, z: -42.25,
      vx: 0, vy: 0, vz: 0, yaw: 90.0, pitch: 0.0,
      health: 18.5, maxHealth: 20.0, absorption: 0.0,
      food: 20, saturation: 5.0, exhaustion: 0.0, armor: 0,
      xpLevel: 12, xpProgress: 0.5,
      onGround: true, sprinting: false, sneaking: false, crouching: false,
      inWater: false, underWater: false, fallFlying: false, inVehicle: false,
      gameMode: "survival",
      mainHand: "minecraft:air", mainHandCount: 0, mainHandDamage: 0, mainHandMaxDamage: 0,
      offHand: "minecraft:air", offHandCount: 0, offHandDamage: 0, offHandMaxDamage: 0
    }},
    world: {{
      dimension: "minecraft:overworld", gameTime: 1200, dayTime: 6000, clockTime: 6000,
      raining: false, thundering: false
    }}
  }};
  g.EaglerMods = {{
    _liveState: liveState,
    _livePlayer: liveState.player,
    _liveWorld: liveState.world,
    getState: function () {{ return liveState; }},
    getFov: function () {{ return currentFov; }},
    setFov: function (v) {{ currentFov = v; liveState.fov = v; }},
    getGamma: function () {{ return currentGamma; }},
    setGamma: function (v) {{ currentGamma = v; liveState.gamma = v; }},
    onTick: function () {{}},
    onFrame: function () {{}},
    onScreenChange: function () {{}},
    onWorldJoin: function () {{}},
    onWorldLeave: function () {{}},
    chat: function () {{}},
    actionBar: function () {{}},
    sendChat: function () {{}}
  }};

  function readFileUtf8(p) {{
    if (typeof std !== "undefined" && typeof std.loadFile === "function") return std.loadFile(p);
    if (typeof require === "function") return require("fs").readFileSync(p, "utf8");
    if (typeof Deno !== "undefined") return Deno.readTextFileSync(p);
    if (typeof read === "function") return read(p);
    throw new Error("No file reader available in JS host");
  }}

  (0, eval)(readFileUtf8({t_path_js}));
  (0, eval)(readFileUtf8({b_path_js}));

  var T = g.EaglerJvmTranslator;
  var B = g.EaglerJvmBridge;
  var binStr = g.atob({json.dumps(jar_b64)});
  var jarBytes = new Uint8Array(binStr.length);
  for (var i = 0; i < binStr.length; i++) jarBytes[i] = binStr.charCodeAt(i);

  var tLoad0 = nowMs();
  var report = T.loadJar("selfcheck_mod.jar", jarBytes);
  var loadMs = nowMs() - tLoad0;
  if (!report || !report.ok) {{
    throw new Error("Benchmark JAR load failed: " + JSON.stringify(report));
  }}

  var rt = T.getSharedRuntime();
  var modCls = "com/example/selfcheck/SelfCheckClientMod";
  var injCls = "com/example/selfcheck/mixin/MinecraftTickInjectMixin";
  fireWinEvent("keydown", {{ code: "KeyC", key: "c", keyCode: 67, repeat: false }});

  var passed = [];
  var failed = [];
  var metrics = {{ jarLoadMs: Number(loadMs.toFixed(2)), hasHookApi: typeof T.hasHook === "function" }};
  function fmt(n, d) {{ return Number(n).toFixed(d); }}
  function benchCheck(name, ok, detail) {{
    if (ok) {{
      passed.push(name);
      console.log("  [BENCH PASS] " + name + ": " + detail);
    }} else {{
      failed.push(name + ": " + detail);
      console.log("  [BENCH FAIL] " + name + ": " + detail);
    }}
  }}

  // 1. Hot arithmetic / loop throughput (compiled JVM for-loop + static method calls)
  var HOT_OUTER = {hot_outer};
  var HOT_INNER = {hot_inner};
  var EXPECTED_HOT = {expected_hot};
  var hotArg = [HOT_INNER];
  // Warmup
  for (var w1 = 0; w1 < 5; w1++) rt.invokeStatic(modCls, "benchHotLoop", "(I)I", hotArg);
  var hotRes = 0;
  var tHot0 = nowMs();
  for (var h = 0; h < HOT_OUTER; h++) {{
    hotRes = rt.invokeStatic(modCls, "benchHotLoop", "(I)I", hotArg);
  }}
  var hotMs = nowMs() - tHot0;
  var totalInnerCalls = HOT_OUTER * HOT_INNER;
  var hotCallsPerSec = hotMs > 0 ? Math.round(totalInnerCalls / (hotMs / 1000)) : totalInnerCalls;
  var hotUsPerCall = (hotMs * 1000) / totalInnerCalls;
  metrics.hotLoop = {{
    outerCalls: HOT_OUTER, innerIters: HOT_INNER, totalCalls: totalInnerCalls,
    ms: Number(hotMs.toFixed(2)), usPerCall: Number(hotUsPerCall.toFixed(3)), callsPerSec: hotCallsPerSec
  }};
  benchCheck("hot_arithmetic_loop",
    hotRes === EXPECTED_HOT,
    fmt(hotMs, 2) + " ms total for " + totalInnerCalls + " JVM calls (" + fmt(hotUsPerCall, 3) + " us/call, " + hotCallsPerSec + " calls/sec, checksum=" + hotRes + ")");

  // 2. 10,000 simulated Minecraft ticks (tickStart + tickEnd + @Mixin @Inject + ClientTickEvents + LocalPlayer + KeyMapping)
  var TICKS = 10000;
  var tickCtx = {{ tick: 0, hasPlayer: true, state: liveState, cancelled: false }};
  // Warmup
  for (var wt = 0; wt < 20; wt++) {{
    tickCtx.tick = wt;
    T.dispatchHook("tickStart", tickCtx);
    T.dispatchHook("tickEnd", tickCtx);
  }}
  var ticksBefore = rt.getStatic(modCls, "tickCount");
  var injBefore = rt.getStatic(injCls, "injectHits");
  var keysBefore = rt.getStatic(modCls, "keyPressedCount");
  var tTick0 = nowMs();
  for (var tk = 1; tk <= TICKS; tk++) {{
    liveState.tick = tk;
    liveState.frameSeq++;
    liveState.player.x = 123.5 + (tk & 15);
    tickCtx.tick = tk;
    T.dispatchHook("tickStart", tickCtx);
    T.dispatchHook("tickEnd", tickCtx);
  }}
  var tickMs = nowMs() - tTick0;
  var tickDelta = rt.getStatic(modCls, "tickCount") - ticksBefore;
  var injDelta = rt.getStatic(injCls, "injectHits") - injBefore;
  var keysDelta = rt.getStatic(modCls, "keyPressedCount") - keysBefore;
  var usPerTick = (tickMs * 1000) / TICKS;
  var tickBudgetPct = ((tickMs / TICKS) / 50.0) * 100.0;
  metrics.ticks10k = {{
    count: TICKS, ms: Number(tickMs.toFixed(2)), usPerTick: Number(usPerTick.toFixed(2)),
    budgetPct: Number(tickBudgetPct.toFixed(3)), tickDelta: tickDelta, injDelta: injDelta, keysDelta: keysDelta
  }};
  benchCheck("ticks_10k_throughput",
    tickDelta === TICKS && injDelta === TICKS && keysDelta === TICKS && tickMs < 500,
    fmt(tickMs, 2) + " ms / 10,000 ticks (" + fmt(usPerTick, 2) + " us/tick, " + fmt(tickBudgetPct, 3) + "% of 50ms budget; limit <500ms; hits=" + tickDelta + "/" + injDelta + "/" + keysDelta + ")");

  // 3. 1,000 simulated HUD frames (hudRender + HudRenderCallback + GuiGraphics.drawString + dirty-skip check)
  var HUD_FRAMES = 1000;
  var hudCtx = {{ rt: rt, tickDelta: 0.5, partialTick: 0.5, guiWidth: 427, guiHeight: 240, guiScale: 2, inWorld: true, screenOpen: false, paused: false }};
  // Initial render (must draw once)
  canvasStats.clearRects = 0; canvasStats.fillRects = 0; canvasStats.fillTexts = 0;
  B.renderHudOverlay(hudCtx);
  var initDraws = canvasStats.fillTexts;
  // Reset counters and run 1,000 identical steady frames (text unchanged -> canvas redraws should be skipped!)
  canvasStats.clearRects = 0; canvasStats.fillRects = 0; canvasStats.fillTexts = 0;
  var hudBefore = rt.getStatic(modCls, "hudCount");
  var tHud0 = nowMs();
  for (var hf = 0; hf < HUD_FRAMES; hf++) {{
    B.renderHudOverlay(hudCtx);
  }}
  var hudMs = nowMs() - tHud0;
  var hudDelta = rt.getStatic(modCls, "hudCount") - hudBefore;
  var steadyClearRects = canvasStats.clearRects;
  var steadyFillTexts = canvasStats.fillTexts;
  // Mutate tickCount once and render 1 dirty frame to verify canvas DOES redraw on change
  rt.putStatic(modCls, "tickCount", rt.getStatic(modCls, "tickCount") + 1);
  canvasStats.clearRects = 0; canvasStats.fillTexts = 0;
  B.renderHudOverlay(hudCtx);
  var dirtyFillTexts = canvasStats.fillTexts;
  var usPerHud = (hudMs * 1000) / HUD_FRAMES;
  var steadySkipped = steadyClearRects === 0 && steadyFillTexts === 0;
  metrics.hud1k = {{
    frames: HUD_FRAMES, ms: Number(hudMs.toFixed(2)), usPerFrame: Number(usPerHud.toFixed(2)),
    hudDelta: hudDelta, initDraws: initDraws, steadyClearRects: steadyClearRects,
    steadyFillTexts: steadyFillTexts, dirtyFillTexts: dirtyFillTexts, steadySkipped: steadySkipped
  }};
  benchCheck("hud_1k_frames_and_dirty_skip",
    initDraws > 0 && hudDelta === HUD_FRAMES && dirtyFillTexts > 0 && steadySkipped && hudMs < 250,
    fmt(hudMs, 2) + " ms / 1,000 frames (" + fmt(usPerHud, 2) + " us/frame; limit <250ms; steadyClearRects=" + steadyClearRects + ", steadyFillTexts=" + steadyFillTexts + ", dirtyFillTexts=" + dirtyFillTexts + ")");

  // 4. 10,000 @Mixin @ModifyReturnValue FOV calls (fovModifier)
  var FOV_CALLS = 10000;
  var fovCtx = {{ value: 70.0, returnValue: undefined, cancelled: false }};
  // Warmup
  for (var wf = 0; wf < 20; wf++) {{
    fovCtx.value = 70.0; fovCtx.returnValue = undefined;
    T.dispatchHook("fovModifier", fovCtx);
  }}
  var lastFov = 0;
  var tFov0 = nowMs();
  for (var fc = 0; fc < FOV_CALLS; fc++) {{
    fovCtx.value = 70.0;
    fovCtx.returnValue = undefined;
    fovCtx.cancelled = false;
    var fovOut = T.dispatchHook("fovModifier", fovCtx);
    lastFov = (typeof fovCtx.returnValue === "number") ? fovCtx.returnValue : (typeof fovOut === "number" ? fovOut : fovCtx.value);
  }}
  var fovMs = nowMs() - tFov0;
  var usPerFov = (fovMs * 1000) / FOV_CALLS;
  metrics.fov10k = {{
    calls: FOV_CALLS, ms: Number(fovMs.toFixed(2)), usPerCall: Number(usPerFov.toFixed(2)), lastFov: lastFov
  }};
  benchCheck("mixin_fov_10k_calls",
    Math.abs(lastFov - 35.0) < 0.01 && fovMs < 200,
    fmt(fovMs, 2) + " ms / 10,000 calls (" + fmt(usPerFov, 2) + " us/call; limit <200ms; fov=" + lastFov + ")");

  T.unloadMod("selfcheck_mod");

  var summary = {{ engine: {json.dumps(engine_kind)}, passed: passed.length, failed: failed.length, metrics: metrics, failures: failed }};
  if (failed.length === 0) {{
    console.log("LIVE_BENCH_PASS:" + JSON.stringify(summary));
  }} else {{
    console.log("LIVE_BENCH_FAIL:" + JSON.stringify(summary));
    if (typeof std !== "undefined" && std.exit) std.exit(1);
    if (typeof process !== "undefined" && process.exit) process.exit(1);
    throw new Error("Live benchmark failures: " + failed.join("; "));
  }}
}})();
"""


def run_live_js_benchmarks(jar_bytes: bytes) -> tuple:
    engine = find_native_js_engine()
    if engine is None:
        return None, "No native Linux JS engine found (skipped live JS benchmarks)"
    kind, bin_path = engine
    harness = build_live_js_bench_harness(WASM_GC_DIR / TRANSLATOR_FILE, WASM_GC_DIR / BRIDGE_FILE, jar_bytes, kind)
    with tempfile.NamedTemporaryFile("w", suffix=".js", encoding="utf-8", delete=False) as tf:
        tf.write(harness)
        harness_path = tf.name
    try:
        if kind == "qjs":
            cmd = [bin_path, "--std", harness_path]
        elif kind == "deno":
            cmd = [bin_path, "run", "--allow-read", harness_path]
        else:
            cmd = [bin_path, harness_path]
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=30)
        return (kind, bin_path, proc.returncode, proc.stdout), None
    finally:
        try:
            os.unlink(harness_path)
        except OSError:
            pass


def run_live_js_tests(jar_bytes: bytes) -> tuple:
    engine = find_native_js_engine()
    if engine is None:
        return None, "No native Linux JS engine found (skipped live JS execution; run on host Node/browser)"
    kind, bin_path = engine
    harness = build_live_js_harness(WASM_GC_DIR / TRANSLATOR_FILE, WASM_GC_DIR / BRIDGE_FILE, jar_bytes, kind)
    with tempfile.NamedTemporaryFile("w", suffix=".js", encoding="utf-8", delete=False) as tf:
        tf.write(harness)
        harness_path = tf.name
    try:
        if kind == "qjs":
            cmd = [bin_path, "--std", harness_path]
        elif kind == "deno":
            cmd = [bin_path, "run", "--allow-read", harness_path]
        else:
            cmd = [bin_path, harness_path]
        proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=15)
        return (kind, bin_path, proc.returncode, proc.stdout), None
    finally:
        try:
            os.unlink(harness_path)
        except OSError:
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wiring-only", action="store_true",
                        help="Only verify HTML/packager/staging wiring and synthetic JAR builder")
    parser.add_argument("--skip-wiring-check", action="store_true",
                        help="Skip index.html/stage_site.py/make_offline.py wiring assertions")
    parser.add_argument("--skip-sync-check", action="store_true",
                        help="Skip javascript-wasm-gc/ sync check")
    parser.add_argument("--emit-jar", type=Path,
                        help="Optional path to write the synthetic Java 21 Fabric .jar fixture")
    parser.add_argument("--emit-js-harness", type=Path,
                        help="Optional path to write the standalone JS verification harness")
    args = parser.parse_args()

    if not args.skip_wiring_check:
        print("=== 1. HTML / Packager / Staging Wiring Checks ===")
        for line in check_wiring_and_packaging():
            print("  [PASS]", line)
    else:
        print("=== 1. HTML / Packager / Staging Wiring Checks (SKIPPED via --skip-wiring-check) ===")

    print("=== 2. Synthetic Java 21 Binary Fabric .jar Fixture (DEFLATE) ===")
    jar_bytes, parsed_classes = build_synthetic_fabric_jar()
    for info in parsed_classes:
        print(
            f"  [PASS] {info['class']}.class (Java 21 major={info['major']}, "
            f"cp={info['cp_count']}, fields={info['fields']}, methods={len(info['methods'])}, "
            f"attrs={info['class_attrs']}, {info['bytes']} B)"
        )
    print(f"  [PASS] Synthetic Fabric JAR assembled in memory: {len(jar_bytes)} bytes, sha256={hashlib.sha256(jar_bytes).hexdigest()[:12]}")
    if args.emit_jar:
        args.emit_jar.write_bytes(jar_bytes)
        print(f"  [INFO] Wrote fixture JAR to {args.emit_jar}")

    if args.wiring_only:
        print("JVM_TRANSLATOR_WIRING_PASS")
        return 0

    print("=== 3. Translator, Bridge, CSS, Java Sources & make_offline.py 4-Format Dry Run ===")
    for line in check_css_and_java_mod_sources():
        print("  [PASS]", line)
    for line in check_js_files_and_offline_dry_run(skip_sync_check=args.skip_sync_check):
        print("  [PASS]", line)

    if args.emit_js_harness:
        harness = build_live_js_harness(WASM_GC_DIR / TRANSLATOR_FILE, WASM_GC_DIR / BRIDGE_FILE, jar_bytes, "host")
        args.emit_js_harness.write_text(harness, encoding="utf-8")
        print(f"  [INFO] Wrote standalone JS harness to {args.emit_js_harness}")

    print("=== 4. Live Native Linux JS Runtime Execution ===")
    result, skip_reason = run_live_js_tests(jar_bytes)
    if skip_reason:
        print("  [SKIP]", skip_reason)
    else:
        kind, bin_path, rc, out = result
        print(f"  [INFO] Engine: {kind} ({bin_path}), exit={rc}")
        for ln in out.splitlines():
            print("   ", ln)
        assert rc == 0, f"Live JS execution failed with exit code {rc}"
        assert "LIVE_SELFCHECK_PASS:" in out, "Live JS execution did not emit LIVE_SELFCHECK_PASS marker"
        print("  [PASS] Live end-to-end JVM -> JS execution succeeded")

    print("=== 5. Live QuickJS Performance Benchmark ===")
    bench_result, bench_skip = run_live_js_benchmarks(jar_bytes)
    if bench_skip:
        print("  [SKIP]", bench_skip)
    else:
        b_kind, b_path, b_rc, b_out = bench_result
        print(f"  [INFO] Engine: {b_kind} ({b_path}), exit={b_rc}")
        for ln in b_out.splitlines():
            print("   ", ln)
        assert b_rc == 0, f"Live JS performance benchmark failed with exit code {b_rc}"
        assert "LIVE_BENCH_PASS:" in b_out, "Live JS benchmark did not emit LIVE_BENCH_PASS marker"
        print("  [PASS] Live QuickJS performance benchmarks succeeded")

    print("JVM_TRANSLATOR_SELFCHECK_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
