#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Generate WebGPU WGSL assets from the shipped Mojang GLSL corpus.

This is a build-time translator, never a browser/runtime dependency.

Usage:
  ./gradlew :game263:wgslManifest --offline        # -> game263/build/wgsl_pipelines.json
  python3 scripts/webgpu_generate_wgsl.py --check
  python3 scripts/webgpu_generate_wgsl.py --output target_teavm_263/javascript/assets/minecraft/shaders/wgsl
  python3 scripts/webgpu_generate_wgsl.py --selfcheck

One WGSL module per distinct (shader file, defines) pair that some RenderPipeline actually
uses -- the static pipelines come from the gradle dump, the post-effect passes from
assets/minecraft/post_effect/*.json. manifest.json maps the same key the Java side computes
(WebGpuDevice.pipelineKey: "vs|fs|K=V;...|FLAG;...") to the two module files plus the
bindings each module declares and the vertex input locations, so the browser never parses GLSL.

Conventions baked into the modules (the Java side relies on all three):
  * every uniform block / sampler name has ONE global @binding number (BASE_BINDINGS + the
    rest sorted), so a vertex and fragment module never disagree on a slot;
  * vertex shaders end with gl_Position.y = -gl_Position.y: render targets keep OpenGL's
    bottom-up row order, so sampling them and reading them back stays identical to WebGL2;
    the final canvas blit un-flips. Depth stays 0..1 because DeviceInfo.isZZeroToOne=true.
  * the CloudFaces isamplerBuffer becomes a read-only storage buffer of packed int8s.
"""

import argparse
import importlib.util
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path("target_teavm_263/javascript/assets/minecraft/shaders")

BASE_BINDINGS = {
    "DynamicTransforms": 0,
    "Projection": 2,
    "Fog": 4,
    "Lighting": 6,
    "Sampler0": 8,
    "Sampler1": 10,
    "Sampler2": 12,
    "Sampler3": 14,
    "CloudInfo": 16,
}

SAMPLER_TO_TEXTURE = {
    "sampler2D": "texture2D",
    "samplerCube": "textureCube",
}

TEXTURE_OPS = (
    "texture",
    "textureLod",
    "textureProj",
    "textureSize",
    "texelFetch",
    "textureGrad",
    "textureOffset",
    "textureLodOffset",
    "textureProjOffset",
)

def load_resolver():
    path = pathlib.Path(__file__).with_name("webgpu_shader_audit.py")
    spec = importlib.util.spec_from_file_location("webgpu_shader_audit", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def next_binding(bindings):
    used = set(bindings.values())
    value = 20
    while value in used or value + 1 in used:
        value += 2
    return value

def rewrite_calls(source, name, transform):
    needle = name + "("
    out = []
    pos = 0
    while True:
        start = source.find(needle, pos)
        if start < 0:
            out.append(source[pos:])
            return "".join(out)
        out.append(source[pos:start])
        open_pos = start + len(name)
        depth = 0
        for end in range(open_pos, len(source)):
            char = source[end]
            if char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    break
        else:
            out.append(source[start:])
            return "".join(out)
        out.append(transform(source[start:end + 1]))
        pos = end + 1

def split_sampler_uses(source, bindings):
    sampler_vars = {}

    cloud_binding = bindings.setdefault("CloudFaces", next_binding(bindings))
    source = re.sub(
        r"^\s*uniform\s+isamplerBuffer\s+CloudFaces\s*;\s*$",
        f"layout(std430, set=0, binding={cloud_binding}) readonly buffer "
        "CloudFacesBuffer { int CloudFaces[]; };",
        source,
        flags=re.M,
    )
    if "CloudFaces" in source:

        source = re.sub(
            r"texelFetch\s*\(\s*CloudFaces\s*,\s*([^\)]+)\s*\)\.r",
            r"cloudFaceByte(\1)",
            source,
        )
        source = source.replace(
            "void main()",
            "int cloudFaceByte(int i) { int w = CloudFaces[i >> 2];"
            " int b = (w >> ((i & 3) * 8)) & 255; return (b << 24) >> 24; }\n"
            "void main()",
            1,
        )

    def global_sampler(match):
        sampler_type, name = match.group(1), match.group(2)
        texture_type = SAMPLER_TO_TEXTURE.get(sampler_type)
        if texture_type is None:
            return match.group(0)
        base = bindings.setdefault(name, next_binding(bindings))
        sampler_vars[name] = sampler_type
        return (
            f"layout(set=0, binding={base}) uniform {texture_type} {name}_texture;\n"
            f"layout(set=0, binding={base + 1}) uniform sampler {name}_sampler;"
        )

    source = re.sub(
        r"^\s*uniform\s+([iu]?sampler\w+)\s+(\w+)\s*;\s*$",
        global_sampler,
        source,
        flags=re.M,
    )

    helper_names = {
        match.group(1)
        for match in re.finditer(
            r"\b\w+\s+(\w+)\s*\(\s*[iu]?sampler\w+\s+\w+", source
        )
    }

    def helper_param(match):
        sampler_type, name = match.group(1), match.group(2)
        texture_type = SAMPLER_TO_TEXTURE.get(sampler_type)
        if texture_type is None:
            return match.group(0)
        sampler_vars[name] = sampler_type
        return f"{texture_type} {name}_texture, sampler {name}_sampler"

    source = re.sub(
        r"\b([iu]?sampler\w+)\s+(\w+)(?=\s*[,\)])", helper_param, source
    )

    ops = "|".join(TEXTURE_OPS)
    for name, sampler_type in sampler_vars.items():
        source = re.sub(
            rf"\b({ops})\s*\(\s*{re.escape(name)}\s*,",
            rf"\1({sampler_type}({name}_texture, {name}_sampler),",
            source,
        )

    for helper in helper_names:
        def call(match):
            name = match.group(1)
            if name not in sampler_vars:
                return match.group(0)
            return f"{helper}({name}_texture, {name}_sampler,"

        source = re.sub(
            rf"\b{re.escape(helper)}\s*\(\s*(\w+)\s*,", call, source
        )

    return source

VARYINGS = {
    name: i
    for i, name in enumerate(
        (
            "vertexColor", "texCoord0", "sphericalVertexDistance", "cylindricalVertexDistance",
            "texCoord", "overlayColor", "lightMapColor", "vertexPerFaceColorFront",
            "vertexPerFaceColorBack", "vertexDistance", "texProj0", "scaledCoord",
            "fAnimationProgress",
        )
    )
}

def normalize(source, stage, defines=None, bindings=None):
    bindings = dict(BASE_BINDINGS) if bindings is None else bindings
    source = re.sub(r"^\s*#version\s+\d+.*$", "", source, flags=re.M)
    defines = dict(defines or {})
    if "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE" in source:
        defines.setdefault("RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "")
    define_text = "".join(
        f"#define {name} {value}\n".replace(" \n", "\n")
        for name, value in sorted(defines.items())
    )
    source = "#version 450\n" + define_text + source
    source = source.replace("gl_VertexID", "gl_VertexIndex")
    source = source.replace("gl_InstanceID", "gl_InstanceIndex")

    def block(match):
        name = match.group(1)
        binding = bindings.setdefault(name, next_binding(bindings))
        return f"layout(std140, set=0, binding={binding}) uniform {name}"

    source = re.sub(
        r"layout\s*\(\s*std140\s*\)\s*uniform\s+(\w+)", block, source
    )

    source = source.replace("    uniform float DiscardAlpha;", "    float DiscardAlpha;")
    source = split_sampler_uses(source, bindings)

    if stage == "vert":

        def explicit_lod(call):
            return "textureLod(" + call[len("texture("):-1] + ", 0.0)"

        source = rewrite_calls(source, "texture", explicit_lod)

    source = re.sub(r"^\s*gl_PointSize\s*=.*;\s*$", "", source, flags=re.M)

    source = source.replace(
        "const vec2[] positions = vec2[](", "const vec2 positions[6] = vec2[6]("
    )
    source = source.replace(
        "const vec3[] vertices = vec3[](", "const vec3 vertices[24] = vec3[24]("
    )
    source = source.replace(
        "const vec4[] faceColors = vec4[](", "const vec4 faceColors[6] = vec4[6]("
    )
    source = source.replace(
        "const vec3[] COLORS = vec3[](", "const vec3 COLORS[16] = vec3[16]("
    )
    source = source.replace(
        "vec4 coeffSamples[targetCount];",
        "vec4 coeffSamples[OIT_COEFF_COUNT / 4];",
    )

    source = source.replace(
        "int index = gl_VertexIndex & 7;", "int index = int(gl_VertexIndex % 6u);"
    )
    source = source.replace(
        "int quadVertex = gl_VertexIndex % 4;",
        "int quadVertex = int(gl_VertexIndex % 4u);",
    )
    source = source.replace(
        "int index = (gl_VertexIndex / 4) * 3;",
        "int index = int((gl_VertexIndex / 4u) * 3u);",
    )

    varying = (stage == "vert" and "out") or (stage == "frag" and "in")
    for qualifier in ("in", "out"):
        location = 0
        pattern = re.compile(
            rf"^(\s*)(?:(flat)\s+)?{qualifier}\s+([^;]+?)\s+(\w+)\s*;\s*$",
            re.M,
        )

        def io(match):
            nonlocal location
            indent, flat, shader_type, name = match.groups()
            if qualifier == varying:
                if name not in VARYINGS:
                    raise SystemExit(f"unknown varying {name}; add it to VARYINGS")
                value = VARYINGS[name]
            else:
                value = location
                location += 1
            flat_text = (flat + " ") if flat else ""
            return (
                f"{indent}layout(location={value}) {flat_text}{qualifier} "
                f"{shader_type} {name};"
            )

        source = pattern.sub(io, source)

    if stage == "vert":

        source = source.replace("void main()", "void main_gl()", 1)
        source += "\nvoid main() { main_gl(); gl_Position.y = -gl_Position.y; }\n"

    return source, bindings

def vertex_inputs(normalized):
    return {
        m.group(2): int(m.group(1))
        for m in re.finditer(r"layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s+(?:flat\s+)?in\s+[^;]+?\s+(\w+);", normalized, re.M)
    }

def active_source(source, defined):
    active = [True]
    branches = []
    out = []

    def condition(expr):
        token = re.compile(r"defined\s*\(\s*(\w+)\s*\)|&&|\|\||!|\(|\)|\s+")
        parts = []
        end = 0
        for match in token.finditer(expr):
            if match.start() != end:
                raise ValueError("Unsupported shader preprocessor condition: " + expr)
            if match.group(1) is not None:
                parts.append("True" if match.group(1) in defined else "False")
            else:
                parts.append({"&&": " and ", "||": " or ", "!": " not "}.get(match.group(), match.group()))
            end = match.end()
        if end != len(expr):
            raise ValueError("Unsupported shader preprocessor condition: " + expr)
        return bool(eval("".join(parts), {"__builtins__": {}}, {}))

    for line in source.splitlines(keepends=True):
        directive = re.match(r"\s*#(ifdef|ifndef|if|elif|else|endif)\b\s*(.*)", line)
        if directive is None:
            if active[-1]:
                out.append(line)
            continue
        kind, value = directive.groups()
        if kind in ("ifdef", "ifndef", "if"):
            branch = (value.strip() in defined) if kind == "ifdef" else (
                value.strip() not in defined if kind == "ifndef" else condition(value)
            )
            branches.append([active[-1], branch, False])
            active.append(active[-1] and branch)
        elif kind == "elif":
            if not branches or branches[-1][2]:
                raise ValueError("Unexpected #elif in shader")
            branch = condition(value)
            parent, taken, _ = branches[-1]
            active[-1] = parent and not taken and branch
            branches[-1][1] = taken or branch
        elif kind == "else":
            if not branches or branches[-1][2]:
                raise ValueError("Unexpected #else in shader")
            parent, taken, _ = branches[-1]
            active[-1] = parent and not taken
            branches[-1][1:] = [True, True]
        else:
            if not branches:
                raise ValueError("Unexpected #endif in shader")
            active.pop()
            branches.pop()
    if branches:
        raise ValueError("Unclosed shader preprocessor condition")
    return "".join(out)

WGSL_BINDING = re.compile(
    r"@group\(0\) @binding\((\d+)\)\s*\n\s*var(<uniform>|<storage[^>]*>)? \w+: ([\w<>]+);"
)

def wgsl_bindings(wgsl):
    out = []
    for m in WGSL_BINDING.finditer(wgsl):
        binding, space, ty = int(m.group(1)), m.group(2), m.group(3)
        if space == "<uniform>":
            kind = "uniform"
        elif space and space.startswith("<storage"):
            kind = "storage"
        elif ty == "sampler":
            kind = "sampler"
        elif ty.startswith("texture_cube"):
            kind = "texture_cube"
        else:
            kind = "texture_2d"
        out.append([binding, kind])
    return out

def global_bindings(root, resolver):
    names = set()
    for sub in ("core", "post"):
        paths = set((root / sub).glob("*")) | set(root.parent.parent.glob(f"*/shaders/{sub}/*"))
        for path in sorted(paths):
            if path.suffix not in (".vsh", ".fsh"):
                continue
            text = resolver.resolve(str(path), str(root), source_root=str(root.parent.parent))
            names.update(re.findall(r"layout\s*\(\s*std140\s*\)\s*uniform\s+(\w+)", text))
            names.update(re.findall(r"^\s*uniform\s+[iu]?sampler\w+\s+(\w+)\s*;", text, re.M))
    bindings = dict(BASE_BINDINGS)
    for name in sorted(names):
        if name not in bindings:
            bindings[name] = next_binding(bindings)
    return bindings

def canonical_defines(values, flags):
    return (
        ";".join(f"{k}={values[k]}" for k in sorted(values)),
        ";".join(sorted(flags)),
    )

def pipeline_key(vertex, fragment, values, flags):
    v, f = canonical_defines(values, flags)
    return f"{vertex}|{fragment}|{v}|{f}"

def location_indexes(location_keys):
    aliases = {}
    ambiguous = {}
    for location, keys in sorted(location_keys.items()):
        unique = sorted(keys)
        if len(unique) == 1:
            aliases[location] = unique[0]
        else:
            ambiguous[location] = unique
    return aliases, ambiguous

def shader_path(root, ident):
    ns, _, path = ident.partition(":")
    if not path:
        ns, path = "minecraft", ns
    source_root = root if ns == "minecraft" else root.parent.parent / ns / "shaders"
    return source_root / path

def post_pipeline_location(stem, index):

    return f"minecraft:{stem}/{index}"

def load_pipelines(root, pipelines_json):
    pipelines = json.loads(pathlib.Path(pipelines_json).read_text())
    post_dir = root.parent / "post_effect"
    for cfg in sorted(post_dir.glob("*.json")):
        for i, p in enumerate(json.loads(cfg.read_text())["passes"]):
            pipelines.append({
                "location": post_pipeline_location(cfg.stem, i),
                "vertex": p["vertex_shader"],
                "fragment": p["fragment_shader"],
                "values": {},
                "flags": [],
            })
    return pipelines

def variant_slug(values, flags):
    v, f = canonical_defines(values, flags)
    if not v and not f:
        return ""
    import hashlib
    return "." + hashlib.sha1(f"{v}|{f}".encode()).hexdigest()[:8]

def naga_path(argument):
    candidate = argument or os.environ.get("NAGA") or shutil.which("naga")
    if not candidate:
        raise SystemExit(
            "naga not found; install naga-cli or pass --naga /path/to/naga"
        )
    return pathlib.Path(candidate)

def translate(naga, source, stage, output, relative):
    wgsl = output / relative
    glsl = output / (str(relative) + ".normalized.glsl")
    wgsl.parent.mkdir(parents=True, exist_ok=True)
    glsl.write_text(source)
    process = subprocess.run(
        [str(naga), "--input-kind", "glsl", "--shader-stage", stage, str(glsl), str(wgsl)],
        text=True,
        capture_output=True,
        timeout=30,
    )
    glsl.unlink()
    if process.returncode:
        return (process.stderr or process.stdout).strip()
    return None

def generate(root, output, naga, pipelines_json, emit_locations=False):
    resolver = load_resolver()
    shader_files = [
        path
        for sub in ("core", "post")
        for path in sorted((root / sub).glob("*"))
        if path.suffix in (".vsh", ".fsh")
    ]
    if not shader_files:
        raise ValueError("No vertex or fragment shader sources found: " + str(root))
    bindings = global_bindings(root, resolver)
    pipelines = load_pipelines(root, pipelines_json)

    output.mkdir(parents=True, exist_ok=True)
    for stale in output.rglob("*.wgsl"):
        stale.unlink()
    failures = []
    shaders = {}
    manifest_pipelines = {}
    location_keys = {}
    resolved_cache = {}

    def module(ident, stage, values, flags):
        base = shader_path(root, ident)
        path = base.with_suffix(".vsh" if stage == "vert" else ".fsh")
        relative = pathlib.Path(path.parent.name) / f"{path.stem}.{stage}{variant_slug(values, flags)}.wgsl"
        source_root = root
        if not path.is_relative_to(root):
            source_root = root.parent.parent
            relative = pathlib.Path(path.relative_to(source_root).parts[0]) / relative
        rel = str(relative)
        if rel in shaders or any(f[0] == rel for f in failures):
            return rel
        if path not in resolved_cache:
            resolved_cache[path] = resolver.resolve(str(path), str(root), source_root=str(source_root))
        defines = dict(values)
        defines.update({flag: "" for flag in flags})
        source, _ = normalize(resolved_cache[path], stage, defines, dict(bindings))
        error = translate(naga, source, stage, output, relative)
        if error:
            failures.append((rel, error))
            return rel
        wgsl = (output / relative).read_text()
        entry = {"bindings": wgsl_bindings(wgsl)}
        if stage == "vert":
            raw = path.read_text()
            entry["inputs"] = vertex_inputs(
                active_source(raw, set(defines)) if re.search(r"layout\s*\(\s*location\s*=", raw) else source
            )
        shaders[rel] = entry
        return rel

    for p in pipelines:
        key = pipeline_key(p["vertex"], p["fragment"], p["values"], p["flags"])
        if emit_locations:
            location_keys.setdefault(p["location"], set()).add(key)
        if key in manifest_pipelines:
            continue
        manifest_pipelines[key] = {
            "vert": module(p["vertex"], "vert", p["values"], p["flags"]),
            "frag": module(p["fragment"], "frag", p["values"], p["flags"]),
        }

    if failures:
        for rel, error in failures:
            print(f"FAIL {rel}: {error}")
        raise ValueError("Shader translation failed for " + ", ".join(rel for rel, _ in failures))

    manifest = {"bindings": bindings, "shaders": shaders, "pipelines": manifest_pipelines}
    if emit_locations:
        locations, ambiguous_locations = location_indexes(location_keys)
        manifest["locations"] = locations
        if ambiguous_locations:
            manifest["ambiguous_locations"] = ambiguous_locations
    (output / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True) + "\n")
    print(
        f"WGSL PASS: {len(pipelines)} pipelines -> {len(manifest_pipelines)} distinct keys, "
        f"{len(shaders)} modules, {len(bindings)} global bindings"
    )

def selfcheck(raw_vertex_source=None):
    sample = """#version 330
uniform sampler2D Sampler0;
in vec2 texCoord0;
out vec4 color;
void main() { color = texture(Sampler0, texCoord0); }
"""
    result, bindings = normalize(sample, "frag")
    assert "uniform texture2D Sampler0_texture" in result
    assert "uniform sampler Sampler0_sampler" in result
    assert "texture(sampler2D(Sampler0_texture, Sampler0_sampler), texCoord0)" in result
    assert "layout(location=1) in vec2 texCoord0;" in result
    assert bindings["Sampler0"] == 8
    vert, _ = normalize("#version 330\nin vec3 Position;\nvoid main() { gl_Position = vec4(Position, 1.0); }\n", "vert", {"FLAG": ""})
    assert "#define FLAG\n" in vert and "void main_gl()" in vert and "gl_Position.y = -gl_Position.y" in vert
    assert vertex_inputs(vert) == {"Position": 0}
    assert vertex_inputs("layout(location = 5) in float ChunkVisibility;") == {"ChunkVisibility": 5}
    assert vertex_inputs("layout ( location = 3 ) in ivec2 UV2;") == {"UV2": 3}
    if raw_vertex_source is not None:
        assert vertex_inputs(raw_vertex_source) == {"Position": 0, "Color": 1, "UV0": 2, "UV2": 3}
    conditional = "layout(location = 0) in vec3 Position;\n#ifdef EXTRA\nlayout(location = 5) in float Extra;\n#endif\n"
    assert vertex_inputs(active_source(conditional, set())) == {"Position": 0}
    assert vertex_inputs(active_source(conditional, {"EXTRA"})) == {"Position": 0, "Extra": 5}
    branches = "#ifdef OUTER\n#if defined(A)\nA\n#elif defined(B)\nB\n#else\nC\n#endif\n#endif\n"
    assert active_source(branches, {"OUTER", "B"}) == "B\n"
    assert active_source(branches, {"OUTER"}) == "C\n"
    assert active_source(branches, {"B"}) == ""
    for invalid in ("#if VALUE > 0\nwrong\n#endif\n", "#else\nwrong\n", "#ifdef OPEN\nwrong\n"):
        try:
            active_source(invalid, {"VALUE"})
            assert False, "invalid preprocessor structure must fail closed"
        except ValueError:
            pass
    oit, _ = normalize("#version 330\n#ifndef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE\n#error wrong depth\n#endif\nvoid main() {}", "frag")
    assert "#define RENDERPEARL_DEPTH_IS_ZERO_TO_ONE\n" in oit
    assert "vec4 coeffSamples[OIT_COEFF_COUNT / 4];" in normalize("vec4 coeffSamples[targetCount];", "frag")[0]
    assert wgsl_bindings("@group(0) @binding(4) \nvar<uniform> global: Fog;\n@group(0) @binding(8) \nvar Sampler0_texture: texture_2d<f32>;\n@group(0) @binding(9) \nvar Sampler0_sampler: sampler;\n") == [[4, "uniform"], [8, "texture_2d"], [9, "sampler"]]
    assert pipeline_key("minecraft:core/a", "minecraft:core/b", {"X": "1", "A": "2"}, ["Z", "B"]) == "minecraft:core/a|minecraft:core/b|A=2;X=1|B;Z"
    aliases, ambiguous = location_indexes({"unique": {"key"}, "shared": {"z", "a", "z"}})
    assert aliases == {"unique": "key"}
    assert ambiguous == {"shared": ["a", "z"]}
    assert post_pipeline_location("entity_outline", 3) == "minecraft:entity_outline/3"
    with tempfile.TemporaryDirectory(prefix="wgsl-mod-bindings-") as directory:
        assets = pathlib.Path(directory) / "assets"
        core = assets / "example/shaders/core"
        core.mkdir(parents=True)
        blocks = "layout(std140) uniform ModA { float a; };\nlayout(std140) uniform ModB { float b; };\n"
        (core / "map.vsh").write_text(blocks)
        (core / "map.fsh").write_text("uniform sampler2D ModSampler;\n" + blocks)
        mod_bindings = global_bindings(assets / "minecraft/shaders", load_resolver())
        assert {"ModA", "ModB", "ModSampler"} <= mod_bindings.keys()
        for stage, text in (("vert", blocks), ("frag", "uniform sampler2D ModSampler;\n" + blocks)):
            normalized, _ = normalize(text, stage, bindings=dict(mod_bindings))
            for name in ("ModA", "ModB"):
                assert f"binding={mod_bindings[name]}) uniform {name}" in normalized
        standalone = pathlib.Path(directory) / "standalone/core"
        standalone.mkdir(parents=True)
        (standalone / "map.vsh").write_text(blocks)
        assert "ModA" in global_bindings(standalone.parent, load_resolver())
    nested_uniform, _ = normalize(
        "layout(std140) uniform DiscardAlphaBlock {\n    uniform float DiscardAlpha;\n};\n"
        "uniform sampler2D Sampler0;\n", "frag")
    assert "uniform DiscardAlphaBlock {\n    float DiscardAlpha;\n}" in nested_uniform
    assert "uniform texture2D Sampler0_texture;" in nested_uniform
    comments = "// uniform\n    float alpha;\n    /* uniform\n       float unused; */\n"
    commented_uniform, _ = normalize(
        "layout(std140) uniform AlphaBlock {\n" + comments + "};\n", "frag")
    assert comments in commented_uniform
    commented_block, _ = normalize(
        "// uniform HistoricalBlock {\nuniform sampler2D Sampler0;\n// }\n", "frag")
    assert "uniform texture2D Sampler0_texture;" in commented_block
    assert "uniform sampler Sampler0_sampler;" in commented_block
    print("webgpu_generate_wgsl self-check PASS")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, default=ROOT)
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--naga")
    parser.add_argument("--pipelines", default="game263/build/wgsl_pipelines.json",
                        help="output of ./gradlew :game263:wgslManifest")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--selfcheck", action="store_true")
    args = parser.parse_args()

    if args.selfcheck:
        selfcheck()
        return
    if args.check == bool(args.output):
        parser.error("choose exactly one of --check or --output DIR")

    naga = naga_path(args.naga)
    if args.check:
        with tempfile.TemporaryDirectory(prefix="webgpu-wgsl-") as directory:
            generate(args.root, pathlib.Path(directory), naga, args.pipelines)
    else:
        generate(args.root, args.output, naga, args.pipelines)

if __name__ == "__main__":
    main()
