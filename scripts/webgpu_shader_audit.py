#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)

import os
import re
import sys

DEFAULT_DIR = "target_teavm_263/javascript/assets/minecraft/shaders"

AMBER = {

    r"\b[iu]?samplerBuffer\b": "buffer texture -> WGSL read-only storage buffer (hand-mapped binding)",
    r"\bgl_PointSize\b": "point size is not settable in WGSL; needs a quad or a dropped feature",
    r"\bdFdx\b|\bdFdy\b|\bfwidth\b": "derivative builtin: WGSL has dpdx/dpdy/fwidth, fragment stage only",
}

RED = {
    r"\bimageLoad\b|\bimageStore\b|\blayout\s*\(\s*[^)]*\bimage": "image load/store — no path from a render pipeline",
    r"\batomic[A-Za-z]*\s*\(": "atomics — compute-only in WGSL",
    r"\bbarrier\s*\(": "workgroup barrier — compute-only",
    r"\bgl_ClipDistance\b|\bgl_CullDistance\b": "clip/cull distance — WGSL has no equivalent",
    r"\blayout\s*\(\s*(points|lines|triangles)": "geometry-stage layout — WebGPU has no geometry stage",
    r"\bsubroutine\b": "GLSL subroutines — no WGSL equivalent",
    r"\bgl_FragDepth\b\s*=\s*(?!\s*gl_FragCoord\.z\s*;)[^;]*\bgl_FragCoord\b": "depth write derived from fragcoord — check depth convention by hand",
}

GREEN_MARKERS = {
    r"layout\s*\(\s*std140\s*\)\s*uniform": "std140 UBO -> GPUBufferBinding",
    r"\buniform\s+sampler2D(Array)?\b": "sampler2D -> texture_2d + sampler",
    r"\btexelFetch\s*\(": "texelFetch -> textureLoad",
    r"\bdiscard\b": "discard -> discard",
    r"\bflat\b\s+(in|out)\b": "flat -> @interpolate(flat)",
    r"\bgl_VertexID\b": "gl_VertexID -> @builtin(vertex_index)",
    r"\bgl_InstanceID\b": "gl_InstanceID -> @builtin(instance_index)",
    r"\bgl_FragDepth\b\s*=\s*gl_FragCoord\.z\s*;": "fragment depth passthrough -> @builtin(frag_depth)",
}

IMPORT_RE = re.compile(r"^\s*#(?:moj_import|include)\s+<minecraft:([^>]+)>", re.M)

def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    return re.sub(r"//[^\n]*", " ", src)

def resolve(path, root, seen=None, source_root=None):
    seen = seen if seen is not None else set()
    real = os.path.realpath(path)
    allowed_root = os.path.realpath(source_root or root)
    if os.path.commonpath((real, allowed_root)) != allowed_root:
        raise ValueError("Shader import escapes its source root: " + str(path))
    if real in seen:
        return ""
    seen.add(real)
    with open(path, "r", encoding="utf-8") as handle:
        src = handle.read()

    def sub(match):
        target = os.path.join(root, "include", match.group(1))
        if not os.path.exists(target):
            raise ValueError("Missing shader import: " + match.group(1))
        return resolve(target, root, seen)

    return IMPORT_RE.sub(sub, src)

def classify(src):
    text = strip_comments(src)
    red = [why for pat, why in RED.items() if re.search(pat, text)]
    amber = [why for pat, why in AMBER.items() if re.search(pat, text)]
    green = [why for pat, why in GREEN_MARKERS.items() if re.search(pat, text)]
    level = "RED" if red else ("AMBER" if amber else "GREEN")
    return level, red + amber, green

def verdict(counts):
    if counts["RED"]:
        return "NO-GO", "%d shader(s) have no WGSL form; the shader set is the lane, not the device" % counts["RED"]
    if counts["AMBER"] > 3:
        return "HOLD", "%d hand-mapped shaders dominate; decide the mappings before device code" % counts["AMBER"]
    return "GO", "shaders are a BUILD STEP, not a runtime translator: %d clean, %d hand-mapped" % (
        counts["GREEN"], counts["AMBER"],
    )

def selftest():
    lvl, why, _ = classify("layout(std140) uniform Fog { float d; };\nvoid main(){ discard; }")
    assert lvl == "GREEN", (lvl, why)

    lvl, why, _ = classify("void main(){ imageStore(img, ivec2(0), vec4(1)); }")
    assert lvl == "RED", (lvl, why)

    lvl, why, _ = classify("uniform isamplerBuffer CloudFaces;\nvoid main(){}")
    assert lvl == "AMBER", (lvl, why)

    lvl, _, _ = classify("// imageStore is not used here\nvoid main(){}")
    assert lvl == "GREEN", lvl

    assert classify("void main(){ gl_FragDepth = gl_FragCoord.z; }")[0] == "GREEN"
    assert classify("void main(){ gl_FragDepth = gl_FragCoord.z * 0.5; }")[0] == "RED"

    assert verdict({"RED": 1, "AMBER": 0, "GREEN": 70})[0] == "NO-GO"
    assert verdict({"RED": 0, "AMBER": 9, "GREEN": 60})[0] == "HOLD"
    assert verdict({"RED": 0, "AMBER": 2, "GREEN": 69})[0] == "GO"

    assert IMPORT_RE.search("#moj_import <minecraft:fog.glsl>")
    assert IMPORT_RE.search("#include <minecraft:fog.glsl>")
    print("selftest: 11 checks PASS")

def main():
    if "--selftest" in sys.argv:
        selftest()
        return 0

    root = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DIR
    if not os.path.isdir(root):
        print("no shader dir at %s" % root)
        return 2

    counts = {"GREEN": 0, "AMBER": 0, "RED": 0}
    rows = []
    for sub in ("core", "post"):
        directory = os.path.join(root, sub)
        if not os.path.isdir(directory):
            continue
        for name in sorted(os.listdir(directory)):
            if not name.endswith((".vsh", ".fsh")):
                continue
            path = os.path.join(directory, name)
            level, why, green = classify(resolve(path, root))
            counts[level] += 1
            if level != "GREEN":
                rows.append((level, "%s/%s" % (sub, name), why))

    print("WEBGPU shader audit — %s" % root)
    print("  scanned %d shaders (imports inlined)" % sum(counts.values()))
    print("  GREEN %d   AMBER %d   RED %d" % (counts["GREEN"], counts["AMBER"], counts["RED"]))
    for level, name, why in rows:
        print("  %-5s %-40s %s" % (level, name, "; ".join(why)))
    call, why = verdict(counts)
    print("\nVERDICT: %s — %s" % (call, why))
    return 0

if __name__ == "__main__":
    sys.exit(main())
