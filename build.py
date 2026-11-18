#!/usr/bin/env python3
# minecraft-web by chiikabu (https://github.com/chiikabu)
"""Build the browser client from your own Minecraft 26.3 installation.

    python build.py all --jdk17 PATH --jdk25 PATH [--minecraft-dir DIR]

Steps (run one by name, or `all`):

    sources    decompile the classes this port changes and apply patches/ to them
    jar        compile the patched classes into build/client-26.3-patched.jar
    compiler   build the patched TeaVM compiler into build/teavm
    link       link the game to WebAssembly (about 20 minutes, 12 GB of free RAM)
    site       assemble the playable folder in site/

Nothing here downloads Minecraft. The jar, its libraries and its assets are read from the
launcher's folder, so start 26.3 once in the official launcher first.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(ROOT, "build")
PATCHES = os.path.join(ROOT, "patches")
sys.path.insert(0, os.path.join(ROOT, "scripts"))

def fail(message):
    sys.exit("build.py: " + message)

def sha(path, kind="sha256"):
    h = hashlib.new(kind)
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()

def text_sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()

def read_text(path):
    with open(path, "rb") as f:
        return f.read().decode("utf-8").replace("\r\n", "\n")

def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)

def run(args, **kwargs):
    print("+ " + " ".join(str(a) for a in args[:6]) + (" ..." if len(args) > 6 else ""), flush=True)
    subprocess.run([str(a) for a in args], check=True, **kwargs)

def default_minecraft_dir():
    home = os.path.expanduser("~")
    if os.name == "nt":
        return os.path.join(os.environ.get("APPDATA", home), ".minecraft")
    if sys.platform == "darwin":
        return os.path.join(home, "Library", "Application Support", "minecraft")
    return os.path.join(home, ".minecraft")

def java(home, tool="java"):
    path = os.path.join(home, "bin", tool + (".exe" if os.name == "nt" else ""))
    if not os.path.isfile(path):
        fail("no %s in %s" % (tool, home))
    return path

class Inputs:

    def __init__(self, args):
        with open(os.path.join(PATCHES, "manifest.json"), encoding="utf-8") as f:
            self.manifest = json.load(f)
        mc = self.manifest["minecraft"]
        self.minecraft_dir = os.path.abspath(args.minecraft_dir or default_minecraft_dir())
        self.jar = os.path.abspath(args.jar or os.path.join(
            self.minecraft_dir, "versions", mc["version"], mc["version"] + ".jar"))
        self.jdk17, self.jdk25 = args.jdk17, args.jdk25
        self.patched_jar = os.path.join(BUILD, "client-%s-patched.jar" % mc["version"])
        self.maven = os.path.join(BUILD, "teavm", "maven")

    def check_jar(self):
        mc = self.manifest["minecraft"]
        if not os.path.isfile(self.jar):
            fail("no client jar at %s (install %s with the official launcher, or pass --jar)"
                 % (self.jar, mc["version"]))
        if os.path.getsize(self.jar) != mc["size"] or sha(self.jar, "sha1") != mc["sha1"]:
            fail("%s is not the official %s client jar (sha1 %s)" % (self.jar, mc["version"], mc["sha1"]))

    def libraries(self):
        base = os.path.join(self.minecraft_dir, "libraries")
        paths = [os.path.join(base, *rel.split("/")) for rel in self.manifest["libraries"]]
        missing = [p for p in paths if not os.path.isfile(p)]
        if missing:
            fail("%d game libraries are missing under %s (first: %s). Start %s once in the launcher."
                 % (len(missing), base, missing[0], self.manifest["minecraft"]["version"]))
        return paths

    def need(self, name):
        home = getattr(self, name)
        if not home:
            fail("pass --%s (or set %s_HOME)" % (name, name.upper()))
        return home

def download(spec, folder):
    path = os.path.join(folder, spec["url"].rsplit("/", 1)[-1])
    if not (os.path.isfile(path) and sha(path) == spec["sha256"]):
        os.makedirs(folder, exist_ok=True)
        print("downloading " + spec["url"], flush=True)
        urllib.request.urlretrieve(spec["url"], path + ".part")
        if sha(path + ".part") != spec["sha256"]:
            fail("checksum mismatch for " + spec["url"])
        os.replace(path + ".part", path)
    return path

def decompile(inputs, out):
    manifest = inputs.manifest
    families = set(manifest["classes"])
    vineflower = download(manifest["decompiler"], os.path.join(BUILD, "tools"))
    subset = os.path.join(BUILD, "subset.jar")
    os.makedirs(BUILD, exist_ok=True)
    with zipfile.ZipFile(inputs.jar) as source, zipfile.ZipFile(subset, "w", zipfile.ZIP_STORED) as target:
        for name in sorted(source.namelist()):
            if name.endswith(".class") and name[:-6].split("$")[0] in families:
                target.writestr(zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)), source.read(name))
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    context = ["-e=" + inputs.jar] + ["-e=" + lib for lib in inputs.libraries()]
    with open(os.path.join(BUILD, "decompile.log"), "w") as log:
        run([java(inputs.need("jdk25")), "-Xmx4g", "-jar", vineflower, *context, subset, out],
            stdout=log, stderr=subprocess.STDOUT)

def step_sources(inputs):
    from source_edits import apply_edits
    inputs.check_jar()
    raw_dir = os.path.join(BUILD, "raw")
    decompile(inputs, raw_dir)
    written = 0
    for family, entry in sorted(inputs.manifest["classes"].items()):
        raw = read_text(os.path.join(raw_dir, *family.split("/")) + ".java")
        if text_sha(raw) != entry["raw"]:
            fail("decompiled %s differs from the text the patches were made against. Use the same "
                 "tools as the manifest (decompiler %s on JDK %s)."
                 % (family, inputs.manifest["decompiler"]["version"], inputs.manifest["jdk"]))
        for output in entry["outputs"]:
            with open(os.path.join(PATCHES, *output["edits"].split("/")), encoding="utf-8") as f:
                result = apply_edits(raw, json.load(f))
            if text_sha(result) != output["sha256"]:
                fail("patch for %s did not produce the expected source" % output["path"])
            write_text(os.path.join(ROOT, *output["path"].split("/")), result)
            written += 1
    print("patched %d sources from %d decompiled classes" % (written, len(inputs.manifest["classes"])))

def step_jar(inputs):
    inputs.check_jar()
    sources = []
    for folder, _, names in os.walk(os.path.join(ROOT, "port263", "java")):
        sources += [os.path.join(folder, name) for name in names if name.endswith(".java")]
    expected = inputs.manifest["jar_sources"]
    if len(sources) != expected:
        fail("port263/java has %d sources, expected %d: run the sources step first" % (len(sources), expected))
    classes = os.path.join(BUILD, "patched-classes")
    shutil.rmtree(classes, ignore_errors=True)
    os.makedirs(classes)
    extra = [download(spec, os.path.join(BUILD, "tools")) for spec in inputs.manifest.get("compile_only", [])]
    classpath = os.pathsep.join([inputs.jar] + inputs.libraries() + extra)
    argfile = os.path.join(BUILD, "javac.args")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("-g\n-nowarn\n-proc:none\n-encoding\nUTF-8\n-d\n\"%s\"\n-cp\n\"%s\"\n"
                % (classes.replace("\\", "/"), classpath.replace("\\", "/")))
        f.writelines('"%s"\n' % path.replace("\\", "/") for path in sorted(sources))
    run([java(inputs.need("jdk25"), "javac"), "@" + argfile])
    replaced = {}
    for folder, _, names in os.walk(classes):
        for name in names:
            path = os.path.join(folder, name)
            replaced[os.path.relpath(path, classes).replace(os.sep, "/")] = path
    with zipfile.ZipFile(inputs.jar) as source, \
            zipfile.ZipFile(inputs.patched_jar + ".part", "w", zipfile.ZIP_DEFLATED, compresslevel=1) as target:
        for info in source.infolist():
            name = info.filename

            if name in replaced or (name.startswith("META-INF/") and name.endswith((".RSA", ".SF", ".DSA", ".EC"))):
                continue
            target.writestr(info, source.read(name))
        for name in sorted(replaced):
            target.write(replaced[name], name)
    os.replace(inputs.patched_jar + ".part", inputs.patched_jar)
    print("wrote %s (%d classes replaced or added)" % (os.path.relpath(inputs.patched_jar, ROOT), len(replaced)))

def step_compiler(inputs):
    recipe = inputs.manifest["compiler"]
    version_jar = os.path.join(inputs.maven, "org", "teavm", "teavm-core", recipe["version"],
                               "teavm-core-%s.jar" % recipe["version"])
    stamp = os.path.join(BUILD, "teavm", "built.txt")
    patch = os.path.join(PATCHES, "compiler.patch")
    if sha(patch) != recipe["patch_sha256"]:
        fail("patches/compiler.patch was altered (check out the repository without line-ending conversion)")
    if os.path.isfile(version_jar) and os.path.isfile(stamp) and open(stamp).read() == recipe["patch_sha256"]:
        print("compiler already built")
        return
    source = os.path.join(BUILD, "teavm", "source")
    shutil.rmtree(os.path.join(BUILD, "teavm"), ignore_errors=True)
    os.makedirs(source)
    env = dict(os.environ, JAVA_HOME=inputs.need("jdk17"))
    git = ["git", "-C", source, "-c", "core.autocrlf=false", "-c", "core.longpaths=true"]
    run(["git", "init", "-q", source])
    run(git + ["fetch", "-q", "--depth=1", recipe["repository"], recipe["commit"]])
    run(git + ["checkout", "-q", "--detach", "FETCH_HEAD"])
    run(git + ["apply", "--whitespace=nowarn", patch])
    wrapper = os.path.join(source, "gradlew.bat" if os.name == "nt" else "gradlew")
    run([wrapper, "-p", source, "--no-daemon", "--console=plain", "-Dmaven.repo.local=" + inputs.maven,
         "-Pteavm.project.version=" + recipe["version"], "-Pteavm.localNodeJS=true",
         "publishToMavenLocal", "-x", "javadoc"], env=env, cwd=source)
    with open(stamp, "w") as f:
        f.write(recipe["patch_sha256"])

def step_link(inputs):
    if not os.path.isfile(inputs.patched_jar):
        fail("run the jar step first")
    env = dict(os.environ, JAVA_HOME=inputs.need("jdk17"), JAVA_TOOL_OPTIONS="-Xss32m")
    wrapper = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    run([wrapper, "--no-daemon", "--console=plain",
         "-Dmaven.repo.local=" + inputs.maven,
         "-Porg.gradle.java.installations.paths=%s,%s" % (inputs.jdk17, inputs.need("jdk25")),
         "-Pgame263.reconstructedJar=" + inputs.patched_jar,
         "-Pweb263.linkMemoryMb=%d" % inputs.link_memory_mb,
         ":target_teavm_263:buildWasmGC"], env=env, cwd=ROOT)
    linked = os.path.join(ROOT, "target_teavm_263", "javascript-wasm-gc", "wasm-gc")
    engine = os.path.join(BUILD, "engine")
    shutil.rmtree(engine, ignore_errors=True)
    os.makedirs(engine)

    run([sys.executable, os.path.join(ROOT, "port263", "split_huge_wasm.py"),
         os.path.join(linked, "classes263.wasm"), os.path.join(engine, "classes263.wasm")])
    shutil.copyfile(os.path.join(linked, "classes263.wasm-runtime.js"),
                    os.path.join(engine, "classes263.wasm-runtime.js"))
    print("engine in " + os.path.relpath(engine, ROOT))

def step_site(inputs):
    inputs.check_jar()
    out = os.path.join(ROOT, "site")
    shutil.rmtree(out, ignore_errors=True)
    run([sys.executable, os.path.join(ROOT, "scripts", "stage_site.py"), "--jar", inputs.jar,
         "--assets", os.path.join(inputs.minecraft_dir, "assets"),
         "--engine", os.path.join(BUILD, "engine"), "--out", out,
         "--java", java(inputs.need("jdk25"))])
    print("done: python scripts/serve_minecraft_web.py --web site")

STEPS = {"sources": step_sources, "jar": step_jar, "compiler": step_compiler, "link": step_link, "site": step_site}

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("step", choices=[*STEPS, "all"])
    parser.add_argument("--jar", help="the official client jar (default: taken from --minecraft-dir)")
    parser.add_argument("--minecraft-dir", help="the launcher's game folder (default: the standard location)")
    parser.add_argument("--jdk17", default=os.environ.get("JDK17_HOME"), help="a JDK 17 home (or JDK17_HOME)")
    parser.add_argument("--jdk25", default=os.environ.get("JDK25_HOME"), help="a JDK 25 home (or JDK25_HOME)")
    parser.add_argument("--link-memory-mb", type=int, default=12288)
    args = parser.parse_args()
    inputs = Inputs(args)
    inputs.link_memory_mb = args.link_memory_mb
    for name in (STEPS if args.step == "all" else [args.step]):
        print("== " + name, flush=True)
        STEPS[name](inputs)

if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        fail("command failed with exit code %d" % error.returncode)
