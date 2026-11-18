#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Pre-bake Minecraft 26.3's native shader steps for the browser build.

26.3 compiles every render pipeline GLSL -> SPIR-V (ShaderC), reflects it (SPIRV-Cross) and
decompiles it back to GLSL 330 (SPIRV-Cross). None of that can run in the browser, so this runs
the real 26.3 classes on the desktop JVM with the real LWJGL natives, for every static pipeline
and every post-effect pass, and writes one binary table (format documented in
scripts/shader_bake_263/src/shaderbake263/ShaderBake263.java). The browser twins of
GlslCompiler / SPIRVModule / GlPipelineRecompiler replay it from
assets/minecraft/shaders/baked263.bin.

  python scripts/bake_shaders_263.py --jar .../versions/26.3/26.3.jar \
      --version-json .../versions/26.3/26.3.json --libraries .../.minecraft/libraries \
      --java .../jdk-25/bin/java.exe --output port263/baked263.bin [--self-check]

--self-check additionally compiles the browser twins (target_teavm_263) against the client jar
and checks, in a second class loader, that the twin path reproduces the native GLSL.
"""
import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
BAKE_DIR = HERE / "shader_bake_263"
REPO = HERE.parent
TWIN_ROOT = REPO / "target_teavm_263" / "src" / "main" / "java"
TWIN_FILES = [
    "com/mojang/renderpearl/frontend/shaders/BakedShaders.java",
    "com/mojang/renderpearl/frontend/shaders/GlslCompiler.java",
    "com/mojang/renderpearl/frontend/shaders/SPIRVModule.java",
    "com/mojang/renderpearl/frontend/shaders/SpvUtil.java",
    "com/mojang/renderpearl/backend/opengl/GlPipelineRecompiler.java",
]

def host_os():
    s = platform.system().lower()
    return "windows" if s.startswith("win") else ("osx" if s == "darwin" else "linux")

def host_arch_suffix():
    m = platform.machine().lower()
    if m in ("arm64", "aarch64"):
        return "-arm64"
    if m in ("x86", "i386", "i686"):
        return "-x86"
    return ""

def rules_allow(rules, os_name):
    if not rules:
        return True
    allowed = False
    for rule in rules:
        os_rule = rule.get("os")
        if os_rule is None or os_rule.get("name") == os_name:
            allowed = rule.get("action") == "allow"
    return allowed

def library_classpath(version_json, libraries):
    data = json.loads(Path(version_json).read_text(encoding="utf-8"))
    os_name, arch = host_os(), host_arch_suffix()
    out = []
    for lib in data.get("libraries", []):
        if not rules_allow(lib.get("rules"), os_name):
            continue
        artifact = lib.get("downloads", {}).get("artifact")
        if not artifact:
            continue
        parts = lib["name"].split(":")
        classifier = parts[3] if len(parts) > 3 else ""
        if classifier.startswith("natives-") and classifier != f"natives-{os_name.replace('osx', 'macos')}{arch}":
            continue
        path = Path(libraries) / artifact["path"]
        if not path.is_file():
            sys.exit(f"missing library {path}")
        out.append(str(path))
    return out

def javac_for(java):
    java = Path(java)
    name = "javac.exe" if java.suffix.lower() == ".exe" else "javac"
    javac = java.with_name(name)
    if not javac.exists():
        sys.exit(f"no javac next to {java}")
    return str(javac)

def compile_java(javac, classpath, sources, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = [javac, "--release", "25", "-nowarn", "-proc:none", "-encoding", "UTF-8",
           "-cp", os.pathsep.join(classpath), "-d", str(out_dir)] + [str(s) for s in sources]
    subprocess.run(cmd, check=True)

def unsigned_copy(jar, out):
    with zipfile.ZipFile(jar) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for item in src.infolist():
            name = item.filename.upper()
            if name.startswith("META-INF/") and name.endswith((".SF", ".RSA", ".DSA", ".EC")):
                continue
            dst.writestr(item, src.read(item.filename))
    return out

def run(cmd):
    result = subprocess.run(cmd)
    if result.returncode != 0:
        sys.exit(f"{Path(cmd[0]).name} exited with {result.returncode}")

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--version-json", required=True)
    ap.add_argument("--libraries", required=True)
    ap.add_argument("--java", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--self-check", action="store_true", help="also verify the browser twins against the native path")
    ap.add_argument("--self-check-pipelines", type=int, default=0, help="limit self-check to N pipelines (0 = all)")
    args = ap.parse_args()

    libs = library_classpath(args.version_json, args.libraries)
    cp = [str(Path(args.jar))] + libs
    javac = javac_for(args.java)
    work = Path(tempfile.mkdtemp(prefix="shader_bake_263_"))
    try:
        harness = work / "harness"
        compile_java(javac, cp, sorted((BAKE_DIR / "src").rglob("*.java")), harness)
        java_cmd = [args.java, "-Xss4m", "--enable-native-access=ALL-UNNAMED", "-Dorg.lwjgl.util.NoChecks=false"]
        run(java_cmd + ["-cp", os.pathsep.join([str(harness)] + cp),
                        "shaderbake263.ShaderBake263", "--client-jar", args.jar, "--output", args.output])
        if args.self_check:
            twins = work / "twins"
            sources = [TWIN_ROOT / f for f in TWIN_FILES] + sorted((BAKE_DIR / "stub").rglob("*.java"))
            compile_java(javac, cp, sources, twins)
            twin_cp = [str(unsigned_copy(args.jar, work / "client-unsigned.jar"))] + libs
            run(java_cmd + ["-cp", os.pathsep.join([str(harness)] + cp),
                            "-Dshaderbake.bin=" + str(Path(args.output).resolve()),
                            "shaderbake263.SelfCheck263",
                            "--client-jar", args.jar, "--harness", str(harness), "--twins", str(twins),
                            "--classpath", os.pathsep.join(twin_cp),
                            "--limit", str(args.self_check_pipelines)])
    finally:
        shutil.rmtree(work, ignore_errors=True)

if __name__ == "__main__":
    main()
