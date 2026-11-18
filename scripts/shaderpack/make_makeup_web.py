# minecraft-web by chiikabu (https://github.com/chiikabu)
import os
import re
import sys
import zipfile

args = sys.argv[1:]
SKIP = set()
OVERRIDES = []
positional = []
i = 0
while i < len(args):
    if args[i] == "--skip":
        SKIP.update(args[i + 1].split(","))
        i += 2
    elif args[i] == "--set":
        name, value = args[i + 1].split("=", 1)
        OVERRIDES.append((name, {"ON": True, "OFF": False}.get(value, value)))
        i += 2
    else:
        positional.append(args[i])
        i += 1
SRC = positional[0]
OUT = positional[1] if len(positional) > 1 else "build/shaderpacks/MakeUp-UltraFast-Web.zip"

DEFAULTS = [
    ("AA_TYPE", 0),
    ("AO", 0),
    ("V_CLOUDS", 0),
    ("VOL_LIGHT", 0),
    ("REFLECTION_SLIDER", 2),
    ("WATER_ABSORPTION", 0.04),
    ("SHADOW_QTY_SLIDER", 2),
    ("SHADOW_DISTANCE_SLIDER", 0),
    ("BLOOM", True),
    ("CLOUD_REFLECTION", False),
    ("MATERIAL_GLOSS", False),
    ("COLORED_SHADOW", False),
]

PROFILE = ("profile.web=                SHADOW_CASTING SHADOW_SMOOTH AO_SMOOTH SHADOW_QTY_SLIDER=2 SHADOW_DISTANCE_SLIDER=0 REFLECTION_SLIDER=2"
           " !DOF DOF_STRENGTH=0.09 AO=0 AOSTEPS=5 AO_STRENGTH=0.70 AA_TYPE=0 !MOTION_BLUR REFRACTION=1 SUN_REFLECTION=1"
           " WAVING=1 V_CLOUDS=0 CLOUD_STEPS_AVG=10 CLOUD_VOL_STYLE=0 BLOOM_SAMPLES=3.0 BLOOM BLOOM_FAST CHROMA_ABER=0"
           " VOL_LIGHT=0 !CLOUD_REFLECTION WATER_ABSORPTION=0.04 COLOR_SCHEME=1 !VANILLA_WATER WATER_COLOR_SOURCE=0"
           " !COLORED_SHADOW MOTION_BLUR_SAMPLES=4 !MATERIAL_GLOSS\n")

LANG = ("\nprofile.web=Web\nprofile.web.comment=Fast defaults for browsers and Chromebooks.\n"
        "option.BLOOM_FAST=Fast bloom\n"
        "option.BLOOM_FAST.comment=Web fork: bloom is gathered in the composite pass from the mipmaps the exposure"
        " reading already builds (8 taps, no extra pass or buffer). Off: MakeUp's original two-pass bloom.\n"
        "option.SHADOW_SMOOTH=Smooth shadow edges\n"
        "option.SHADOW_SMOOTH.comment=Web fork: soft shadows use a fixed 4-tap pattern, so their edges show no"
        " stipple when TAA is off. Off: MakeUp's original dithered taps (meant for TAA).\n"
        "option.AO_SMOOTH=Smooth AO\n"
        "option.AO_SMOOTH.comment=Web fork: ambient occlusion with a fixed sample pattern, so it shows no grain"
        " when TAA is off. Off: MakeUp's original dithered pattern (meant for TAA).\n")

GROUPS = {}

GROUPS["passes"] = (
    "shaders.properties: composite1 is not run unless DOF or the original (non-fast) bloom is on, and"
    " composite2 is not run unless TAA or motion blur is on. With those effects off the two passes"
    " only copied the image, each a full-screen draw.",
    [("shaders/shaders.properties",
      "program.shadow.enabled=SHADOW_CASTING\n",
      "# Web fork: composite1 and composite2 only copy the image when their effects are off\n"
      "#if defined BLOOM && !defined BLOOM_FAST\n"
      "#else\n"
      "program.composite1.enabled=DOF\n"
      "program.world0/composite1.enabled=DOF\n"
      "program.world1/composite1.enabled=DOF\n"
      "program.world-1/composite1.enabled=DOF\n"
      "#endif\n"
      "#if AA_TYPE == 0\n"
      "program.composite2.enabled=MOTION_BLUR\n"
      "program.world0/composite2.enabled=MOTION_BLUR\n"
      "program.world1/composite2.enabled=MOTION_BLUR\n"
      "program.world-1/composite2.enabled=MOTION_BLUR\n"
      "#endif\n"
      "program.shadow.enabled=SHADOW_CASTING\n")])

GROUPS["mips"] = (
    "common/composite_fragment.glsl, common/composite_vertex.glsl, lib/water.glsl: the exposure"
    " reading samples the mipmaps of gaux1 (the copy of the scene that deferred makes for reflections)"
    " instead of colortex1. colortex1 is written by up to five passes per frame and the browser"
    " runtime rebuilds a mipmapped buffer's mipmaps after every write; gaux1 is written once. The"
    " water shader reads gaux1 at mip level 0 explicitly.",
    [("shaders/common/composite_fragment.glsl",
      "const bool colortex1MipmapEnabled = true;\n",
      "// Web fork: the exposure reading (vertex shader) and the fast bloom use gaux1's mipmaps\n"
      "const bool gaux1MipmapEnabled = true;\n"),
     ("shaders/common/composite_vertex.glsl",
      "uniform sampler2D colortex1;\n",
      "uniform sampler2D gaux1;  // Web fork: was colortex1\n"),
     ("shaders/common/composite_vertex.glsl",
      "    vec3 exposure_col = texture2DLod(colortex1, vec2(0.5), mipmap_level).rgb;\n"
      "    exposure_col += texture2DLod(colortex1, vec2(0.25), mipmap_level).rgb;\n"
      "    exposure_col += texture2DLod(colortex1, vec2(0.75), mipmap_level).rgb;\n"
      "    exposure_col += texture2DLod(colortex1, vec2(0.25, 0.75), mipmap_level).rgb;\n"
      "    exposure_col += texture2DLod(colortex1, vec2(0.75, 0.25), mipmap_level).rgb;\n",
      "    vec3 exposure_col = texture2DLod(gaux1, vec2(0.5), mipmap_level).rgb;\n"
      "    exposure_col += texture2DLod(gaux1, vec2(0.25), mipmap_level).rgb;\n"
      "    exposure_col += texture2DLod(gaux1, vec2(0.75), mipmap_level).rgb;\n"
      "    exposure_col += texture2DLod(gaux1, vec2(0.25, 0.75), mipmap_level).rgb;\n"
      "    exposure_col += texture2DLod(gaux1, vec2(0.75, 0.25), mipmap_level).rgb;\n"),
     ("shaders/lib/water.glsl",
      "    return mix(texture2D(gaux1, pos.xy).rgb, color, water_absortion);\n",
      "    return mix(texture2DLod(gaux1, pos.xy, 0.0).rgb, color, water_absortion);\n"),
     ("shaders/lib/water.glsl",
      "    return vec4(texture2D(gaux1, pos.xy).rgb, border);\n",
      "    return vec4(texture2DLod(gaux1, pos.xy, 0.0).rgb, border);\n"),
     ("shaders/lib/water.glsl",
      "    return vec4(texture2D(gaux1, pos.xy, 0.0).rgb, border);\n",
      "    return vec4(texture2DLod(gaux1, pos.xy, 0.0).rgb, border);\n")])

GROUPS["shadow_skip"] = (
    "common/solid_blocks_fragment.glsl: the four soft-shadow taps are skipped on pixels that get no"
    " direct light anyway (faces turned away from the sun or moon, places the sky does not reach,"
    " and beyond the shadow distance). The image is the same. Not used with MATERIAL_GLOSS, which"
    " also multiplies its highlight by the shadow.",
    [("shaders/common/solid_blocks_fragment.glsl",
      "            float shadowValue = get_shadow(shadow_real_pos, dither);\n"
      "            shadowValue = mix(shadowValue, 1.0, shadowDiffuse);\n",
      "            // Web fork: no shadow taps where there is no direct light to shadow\n"
      "            float shadowValue = 1.0;\n"
      "            #if defined MATERIAL_GLOSS\n"
      "                shadowValue = get_shadow(shadow_real_pos, dither);\n"
      "            #else\n"
      "                if(directLightStrength > 0.0 && shadowDiffuse < 1.0) {\n"
      "                    shadowValue = get_shadow(shadow_real_pos, dither);\n"
      "                }\n"
      "            #endif\n"
      "            shadowValue = mix(shadowValue, 1.0, shadowDiffuse);\n")])

GROUPS["godray_skip"] = (
    "common/composite_fragment.glsl: depth-based godrays (VOL_LIGHT 1) take their four depth taps"
    " only on pixels where the rays show (looking towards the sun or moon, no rain). Elsewhere the"
    " effect costs nothing. Godrays stay off by default.",
    [("shaders/common/composite_fragment.glsl",
      "            float volumetricLight = ssGodrays(dither);\n",
      "            float volumetricLight = 1.0;  // Web fork: sampled below, only where it shows\n"),
     ("shaders/common/composite_fragment.glsl",
      "                pow(clamp(volumetricIntensity, 0.0, 1.0), volumetricDayMixer) * 0.5 * abs(dayNightMix * 2.0 - 1.0);\n",
      "                pow(clamp(volumetricIntensity, 0.0, 1.0), volumetricDayMixer) * 0.5 * abs(dayNightMix * 2.0 - 1.0);\n"
      "            if(volumetricIntensity * (1.0 - rainStrength) > 0.002) {  // Web fork\n"
      "                volumetricLight = ssGodrays(dither);\n"
      "            }\n")])

GROUPS["ao_skip"] = (
    "common/deferred_fragment.glsl: SSAO (AO 1, off by default) takes no depth taps on sky and far"
    " pixels, where its distance fade has already removed it.",
    [("shaders/common/deferred_fragment.glsl",
      "        float final_ao = mix(dbao(dither), 1.0, ao_att);\n",
      "        float final_ao = 1.0;\n"
      "        if(ao_att < 0.995) {  // Web fork: nothing to darken on sky and far pixels\n"
      "            final_ao = mix(dbao(dither), 1.0, ao_att);\n"
      "        }\n")])

GROUPS["ao_smooth"] = (
    "lib/config.glsl, lib/ao.glsl: new option AO_SMOOTH (Ambient occlusion screen, on by default)."
    " SSAO samples a fixed pattern instead of a dithered one. MakeUp relies on TAA to average the"
    " dither away; with TAA off it stays on screen as grain. AO_SMOOTH off gives the original.",
    [("shaders/lib/config.glsl",
      "#define AA_TYPE ",
      "#define AO_SMOOTH // Web fork: SSAO with a fixed sample pattern, for use without TAA (no grain). Off: the original dithered pattern.\n"
      "#ifdef AO_SMOOTH\n"
      "    // Don't remove\n"
      "#endif\n"
      "#define AA_TYPE "),
     ("shaders/lib/ao.glsl",
      "        ditherSample = (i + dither);\n",
      "        #ifdef AO_SMOOTH\n"
      "            ditherSample = (i + 0.5);  // Web fork: fixed pattern\n"
      "        #else\n"
      "            ditherSample = (i + dither);\n"
      "        #endif\n"),
     ("shaders/shaders.properties",
      "screen.AO_SCREEN = AO AO_STRENGTH AOSTEPS\n",
      "screen.AO_SCREEN = AO AO_STRENGTH AOSTEPS AO_SMOOTH\n")])

GROUPS["shadow_smooth"] = (
    "lib/config.glsl, lib/shadow_frag.glsl: new option SHADOW_SMOOTH (Shadows screen, on by default)."
    " Soft shadows take their four taps on a fixed rotated pattern instead of a dithered one, so"
    " shadow edges are smooth instead of stippled when TAA is off. Same number of taps."
    " SHADOW_SMOOTH off gives the original.",
    [("shaders/lib/config.glsl",
      "#define SHADOW_BLUR ",
      "#define SHADOW_SMOOTH // Web fork: soft shadows with a fixed 4-tap pattern, for use without TAA (no stipple on shadow edges). Off: the original dithered taps.\n"
      "#ifdef SHADOW_SMOOTH\n"
      "    // Don't remove\n"
      "#endif\n"
      "#define SHADOW_BLUR "),
     ("shaders/lib/shadow_frag.glsl",
      "    #elif SHADOW_TYPE == 1  // Soft\n"
      "        float current_radius = dither;\n",
      "    #elif SHADOW_TYPE == 1 && defined SHADOW_SMOOTH  // Web fork: soft, fixed rotated pattern\n"
      "        vec2 webRadius = vec2(SHADOW_BLUR * 0.5) / shadowMapResolution;\n"
      "        float webDepth = the_shadow_pos.z - 0.00006;\n"
      "        shadow_sample = shadow2D(shadowtex1, vec3(the_shadow_pos.xy + webRadius * vec2(1.0, 0.4), webDepth)).r;\n"
      "        shadow_sample += shadow2D(shadowtex1, vec3(the_shadow_pos.xy + webRadius * vec2(-0.4, 1.0), webDepth)).r;\n"
      "        shadow_sample += shadow2D(shadowtex1, vec3(the_shadow_pos.xy + webRadius * vec2(-1.0, -0.4), webDepth)).r;\n"
      "        shadow_sample += shadow2D(shadowtex1, vec3(the_shadow_pos.xy + webRadius * vec2(0.4, -1.0), webDepth)).r;\n"
      "        shadow_sample *= 0.25;\n"
      "    #elif SHADOW_TYPE == 1  // Soft\n"
      "        float current_radius = dither;\n"),
     ("shaders/shaders.properties",
      "screen.SHADOWS_SCREEN = SHADOW_CASTING COLORED_SHADOW SHADOW_TYPE SHADOW_BLUR ",
      "screen.SHADOWS_SCREEN = SHADOW_CASTING COLORED_SHADOW SHADOW_TYPE SHADOW_BLUR SHADOW_SMOOTH ")])

GROUPS["vertex_matrices"] = (
    "src/position_vertex.glsl, src/position_vertex_water.glsl, common/shadow_vertex.glsl: vertex"
    " positions are computed as projection * (modelView * vertex). The browser runtime expands"
    " gl_ModelViewProjectionMatrix to a product of two matrix uniforms, which is a matrix-matrix"
    " multiply per vertex; the shadow program also went through both shadow matrices and their"
    " inverses (four matrix-matrix products per vertex) to arrive at the same position. Terrain is"
    " drawn twice with shadows on, so this is the largest cost on devices that are slow at vertices.",
    [("shaders/src/position_vertex.glsl",
      "gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n",
      "gl_Position = gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex);  // Web fork: no matrix-matrix product per vertex\n"),
     ("shaders/src/position_vertex.glsl",
      "                vec4 waveOffsetClip = gl_ModelViewProjectionMatrix * vec4(waveOffsetWorld, 0.0);\n",
      "                vec4 waveOffsetClip = gl_ProjectionMatrix * (gl_ModelViewMatrix * vec4(waveOffsetWorld, 0.0));  // Web fork\n"),
     ("shaders/src/position_vertex_water.glsl",
      "gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n",
      "gl_Position = gl_ProjectionMatrix * viewSpacePos4D;  // Web fork: no matrix-matrix product per vertex\n"),
     ("shaders/common/shadow_vertex.glsl",
      "    vec4 position = shadowModelViewInverse * shadowProjectionInverse * gl_ModelViewProjectionMatrix * gl_Vertex;\n"
      "    gl_Position = shadowProjection * shadowModelView * position;\n",
      "    // Web fork: in the shadow pass the fixed-function matrices are the shadow matrices, so the\n"
      "    // round trip through their inverses gave this same position.\n"
      "    gl_Position = gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex);\n")])

BLOOM_FAST_FN = """#if defined BLOOM && defined BLOOM_FAST
    // Web fork: fast bloom. Eight fixed taps, on two rings, of the mipmaps of gaux1 (the scene
    // before translucents), which the exposure reading needs anyway. The bright-pass is applied
    // per tap. No bloom buffer, no extra pass and no dither noise.
    uniform sampler2D gaux1;
    uniform float softLod;
    uniform float aspectRatioInverse;

    vec3 webBloomTap(vec2 coords, float lod) {
        vec3 c = texture2DLod(gaux1, coords, lod).rgb;
        return c * smoothstep(0.85, 1.0, luma(c * exposure));
    }

    vec3 webBloom() {
        vec2 a = vec2(aspectRatioInverse, 1.0) * 0.02;
        vec2 b = vec2(aspectRatioInverse, 1.0) * 0.055;
        float lod2 = softLod + 1.0;
        vec3 sum = webBloomTap(texcoord + a, softLod);
        sum += webBloomTap(texcoord - a, softLod);
        sum += webBloomTap(texcoord + vec2(a.x, -a.y), softLod);
        sum += webBloomTap(texcoord + vec2(-a.x, a.y), softLod);
        sum += webBloomTap(texcoord + vec2(b.x, 0.0), lod2);
        sum += webBloomTap(texcoord - vec2(b.x, 0.0), lod2);
        sum += webBloomTap(texcoord + vec2(0.0, b.y), lod2);
        sum += webBloomTap(texcoord - vec2(0.0, b.y), lod2);
        return sum * 0.0625;
    }
#endif

// MAIN FUNCTION ------------------
"""

GROUPS["bloom_fast"] = (
    "lib/config.glsl, common/composite_fragment.glsl, common/composite1_fragment.glsl: new option"
    " BLOOM_FAST (Post processing screen, on by default). Bloom is added in the composite pass from"
    " eight taps of gaux1's mipmaps, instead of a bright-pass buffer (colortex0) with its own"
    " mipmaps plus twelve dithered taps in composite1. It costs no pass and no buffer. Bloom comes"
    " from the scene before water and other translucents, and under water or in lava it fades with"
    " the fog of the pixel it lands on. BLOOM_FAST off gives the original bloom.",
    [("shaders/lib/config.glsl",
      "#define BLOOM_SAMPLES ",
      "#define BLOOM_FAST // Web fork: bloom in the composite pass from 8 taps of mipmaps that already exist. Off: the original two-pass bloom.\n"
      "#ifdef BLOOM_FAST\n"
      "    // Don't remove\n"
      "#endif\n"
      "#define BLOOM_SAMPLES "),
     ("shaders/common/composite_fragment.glsl",
      "// MAIN FUNCTION ------------------\n",
      BLOOM_FAST_FN),
     ("shaders/common/composite_fragment.glsl",
      "    #ifdef BLOOM\n"
      "        // Bloom source\n",
      "    #if defined BLOOM && defined BLOOM_FAST\n"
      "        // the taps are of the scene before this pass's fog, so fade them like the pixel itself\n"
      "        float webBloomKeep = 1.0;\n"
      "        if(isEyeInWater == 1) {\n"
      "            webBloomKeep = clamp(pow(1.001 - linearDepth, 5.0 + (4.0 * WATER_ABSORPTION)), 0.0, 1.0);\n"
      "        } else if(isEyeInWater == 2) {\n"
      "            webBloomKeep = 1.0 - clamp(sqrt(linearDepth * far * 0.125), 0.0, 1.0);\n"
      "        }\n"
      "        if(blindness > .01) {\n"
      "            webBloomKeep = 0.0;\n"
      "        }\n"
      "        blockColor.rgb += webBloom() * webBloomKeep;\n"
      "        blockColor = clamp(blockColor, vec4(0.0), vec4(vec3(50.0), 1.0));\n"
      "        /* DRAWBUFFERS:16 */\n"
      "        gl_FragData[0] = blockColor;\n"
      "        gl_FragData[1] = vec4(exposure, 0.0, 0.0, 0.0);\n"
      "    #elif defined BLOOM\n"
      "        // Bloom source\n"),
     ("shaders/common/composite1_fragment.glsl",
      "#ifdef BLOOM\n    const bool colortex0MipmapEnabled = true;\n",
      "#if defined BLOOM && !defined BLOOM_FAST\n    const bool colortex0MipmapEnabled = true;\n"),
     ("shaders/common/composite1_fragment.glsl",
      "#ifdef BLOOM\n    uniform float softLod;\n",
      "#if defined BLOOM && !defined BLOOM_FAST\n    uniform float softLod;\n"),
     ("shaders/common/composite1_fragment.glsl",
      "#ifdef BLOOM\n    #include \"/lib/bloom.glsl\"\n",
      "#if defined BLOOM && !defined BLOOM_FAST\n    #include \"/lib/bloom.glsl\"\n"),
     ("shaders/common/composite1_fragment.glsl",
      "    #ifdef BLOOM\n        vec3 bloom = mipmap_bloom(colortex0, texcoord, dither);\n",
      "    #if defined BLOOM && !defined BLOOM_FAST\n        vec3 bloom = mipmap_bloom(colortex0, texcoord, dither);\n"),
     ("shaders/shaders.properties",
      " BLOOM BLOOM_SAMPLES CHROMA_ABER ",
      " BLOOM BLOOM_SAMPLES BLOOM_FAST <empty> CHROMA_ABER ")])

NOTICE_HEAD = """MakeUp UltraFast Web

A fork of "MakeUp - UltraFast" by Javier Garduno (XorDev contributions as credited in the files),
licensed under the GNU Lesser General Public License v3.0 like the original:
https://www.gnu.org/licenses/lgpl-3.0.html . Original pack: https://modrinth.com/shader/makeup-ultra-fast

Changes in this fork (made by scripts/shaderpack/make_makeup_web.py in the minecraft-web sources).
Every changed line of GLSL carries a "Web fork" comment.

1. Defaults in lib/config.glsl, a "Web" profile in shaders.properties (also the default selection)
   and its name in lang/en_US.lang. Defaults: no TAA, no SSAO, no volumetric clouds, no godrays,
   no material gloss, no coloured shadows, shortest shadow distance with a 600 px map; fast bloom and smooth
   shadow edges on. Water: ray-marched reflections and clearer water (absorption 0.04). Every
   effect can be turned on or off in the shader options.
"""

def set_define(text, name, value):
    if isinstance(value, bool):
        on = re.compile(r"(?m)^(\s*)#define " + name + r"\b")
        off = re.compile(r"(?m)^(\s*)//\s*#define " + name + r"\b")
        if value:
            text, n = off.subn(r"\1#define " + name, text, count=1)
            return text, n + len(on.findall(text))
        text, n = on.subn(r"\1// #define " + name, text, count=1)
        return text, n + len(off.findall(text))
    pattern = re.compile(r"(?m)^(\s*#define " + name + r" )[-0-9.]+")
    return pattern.subn(r"\g<1>" + str(value), text, count=1)

with zipfile.ZipFile(SRC) as zi:
    names = zi.namelist()
    root = next((n[: n.index("shaders/")] for n in names if "shaders/" in n), "")
    files = {}
    for info in zi.infolist():
        if not info.filename.endswith("/"):
            files[info.filename[len(root):]] = zi.read(info.filename)

def edit(rel, fn):
    if rel not in files:
        sys.exit("missing in the source zip: " + rel)

    files[rel] = fn(files[rel].decode("utf-8")).encode("utf-8")

notice = NOTICE_HEAD
number = 2
for group, (description, patches) in GROUPS.items():
    if group in SKIP:
        continue
    for rel, old, new in patches:
        def apply(text, rel=rel, old=old, new=new):
            if "\r\n" in text:
                old, new = old.replace("\n", "\r\n"), new.replace("\n", "\r\n")
            if text.count(old) != 1:
                sys.exit("%s: patch '%s' expects its text once, found %d: %r" % (rel, group, text.count(old), old[:70]))
            return text.replace(old, new, 1)
        edit(rel, apply)
    wrapped = []
    line = "%d. " % number
    indent = " " * len(line)
    for word in description.split():
        if len(line) + len(word) > 98:
            wrapped.append(line.rstrip())
            line = indent
        line += word + " "
    wrapped.append(line.rstrip())
    notice += "\n" + "\n".join(wrapped) + "\n"
    number += 1

def defaults(text):
    for name, value in DEFAULTS + OVERRIDES:
        if isinstance(value, str):
            value = float(value) if "." in value else int(value)
        text, n = set_define(text, name, value)
        if n == 0:
            sys.exit("config.glsl: no #define " + name)
    return text

def profile(text):
    anchor = "profile.no_effects="
    if anchor not in text:
        sys.exit("no profiles in shaders.properties")
    line = PROFILE
    for group, option in (("bloom_fast", " BLOOM_FAST"), ("shadow_smooth", " SHADOW_SMOOTH"), ("ao_smooth", " AO_SMOOTH")):
        if group in SKIP:
            line = line.replace(option, "", 1)
    return text.replace(anchor, line + anchor, 1)

edit("shaders/lib/config.glsl", defaults)
edit("shaders/shaders.properties", profile)
edit("shaders/lang/en_US.lang", lambda text: text + LANG)
files["shaders/WEB_FORK.txt"] = notice.encode("utf-8")

os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zo:
    for rel in sorted(files):
        entry = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o644 << 16
        zo.writestr(entry, files[rel], compresslevel=9)
print("wrote", OUT, "files", len(files), "groups", ",".join(g for g in GROUPS if g not in SKIP))
