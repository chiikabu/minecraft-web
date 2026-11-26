// minecraft-web by chiikabu (https://github.com/chiikabu)

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipFile
import org.teavm.gradle.api.OptimizationLevel
import org.teavm.gradle.api.WasmDebugInfoLevel
import org.teavm.gradle.api.WasmDebugInfoLocation

plugins {
	id("java")
	id("org.teavm") version "0.12.1-browserport.1"
}

val teaVMVersion = "0.12.1-browserport.1"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

val hostJavac = javaToolchains.compilerFor {
	languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
	options.release.set(24)
	options.encoding = "UTF-8"
}

repositories {
	mavenLocal()
	mavenCentral()
	maven { url = uri("https://libraries.minecraft.net/") }
}

sourceSets {
	named("main") {
		java.srcDirs("src/main/java", "../game263-teavm/src/main/java", "vendor/joml", "vendor/guava", "vendor/gson", "vendor/lwjgl")
		java.exclude("sun/**")
		java.exclude("javax/**")
	}
}

val compileSunMisc = tasks.register<Exec>("compileSunMisc") {
	val outDir = layout.buildDirectory.dir("sunmisc-classes")
	val hostPkgSources = fileTree("src/main/java") { include("sun/**/*.java", "javax/**/*.java") }
	val classlibJar = configurations.detachedConfiguration(
		dependencies.create("org.teavm:teavm-classlib:$teaVMVersion")
	).apply { isTransitive = false }
	inputs.files(hostPkgSources)
	inputs.files(classlibJar)
	outputs.dir(outDir)
	commandLine(
		listOf(
			hostJavac.get().executablePath.asFile.absolutePath,
			"--release", "8", "-nowarn",
			"-cp", classlibJar.singleFile.absolutePath,
			"-d", outDir.get().asFile.absolutePath
		) + hostPkgSources.files.map { it.absolutePath }
	)
}

val replacedClasslibClasses = listOf(
	"org/teavm/classlib/java/io/TFile",
	"org/teavm/classlib/java/io/TBufferedReader",
	"org/teavm/classlib/java/io/TReader",
	"org/teavm/classlib/java/io/TRandomAccessFile",
	"org/teavm/classlib/java/io/TFilterOutputStream",
	"org/teavm/classlib/java/nio/TByteBuffer",
	"org/teavm/classlib/impl/console/JsConsolePrintStream",
	"org/teavm/classlib/java/util/TFormatter",
	"org/teavm/classlib/java/util/TAbstractMap",
	"org/teavm/classlib/java/util/TCollections",
	"org/teavm/classlib/java/util/TArrayDeque",
	"org/teavm/classlib/java/util/stream/impl/TSimpleStreamSpliterator",
	"org/teavm/classlib/java/util/stream/impl/TSimpleStreamImpl",
	"org/teavm/classlib/java/util/TUUID",
	"org/teavm/classlib/java/util/TDate",
	"org/threeten/bp/Duration",
	"org/teavm/classlib/java/util/TTemplateCollections",
	"org/teavm/classlib/java/lang/TCharacter",
	"org/teavm/classlib/java/lang/TInteger",
	"org/teavm/classlib/java/lang/TLong",
	"org/teavm/classlib/java/lang/TMath",
	"org/teavm/classlib/java/lang/TSystem",
	"org/teavm/classlib/java/lang/TRuntime",
	"org/teavm/classlib/java/util/concurrent/TConcurrentHashMap",
	"org/teavm/classlib/java/util/concurrent/atomic/TAtomicInteger",
	"org/teavm/classlib/java/util/stream/TStreamSupport",
	"org/teavm/classlib/java/lang/TClass",
	"org/teavm/classlib/java/lang/TClassLoader",
	"org/teavm/classlib/java/lang/TStackTraceElement",
	"org/teavm/classlib/java/util/TSpliterators",
	"org/teavm/classlib/java/util/TBase64",
	"org/teavm/classlib/java/lang/annotation/TElementType",
	"org/teavm/classlib/java/util/TServiceLoader",
	"org/teavm/classlib/java/lang/invoke/TMethodHandles",
	"org/teavm/classlib/java/lang/invoke/TMethodHandle",
	"org/teavm/classlib/java/lang/invoke/TMethodType",
	"org/teavm/classlib/java/lang/reflect/TType",
	"org/teavm/classlib/java/lang/TSecurityManager",
	"org/teavm/classlib/java/lang/TThread",
	"org/teavm/classlib/java/lang/ref/TReferenceQueue",
	"org/teavm/classlib/java/security/TSecureRandom",
	"org/teavm/classlib/java/net/TURL",
	"org/teavm/classlib/java/util/TLocale",
	"org/teavm/classlib/java/util/TProperties",
	"org/teavm/classlib/java/util/zip/TDeflater",
	"org/teavm/classlib/java/util/zip/TInflater",
	"org/teavm/classlib/java/util/zip/TZipFile"
)

val gsonSource = configurations.detachedConfiguration(
	dependencies.create("com.google.code.gson:gson:2.14.0")
).apply { isTransitive = false }

val patchGson = tasks.register<Zip>("patchGson") {
	archiveFileName.set("gson-2.14.0-patched.jar")
	inputs.property("patchRev", 5)
	destinationDirectory.set(layout.buildDirectory.dir("patched-gson"))
	entryCompression = ZipEntryCompression.STORED
	from(provider { zipTree(gsonSource.singleFile) }) {
		exclude("com/google/gson/internal/bind/TypeAdapters.class")
		exclude("com/google/gson/internal/bind/TypeAdapters$*.class")
		exclude("com/google/gson/internal/sql/SqlTypesSupport.class")
		exclude("com/google/gson/internal/UnsafeAllocator.class")
		exclude("com/google/gson/internal/UnsafeAllocator$*.class")
		exclude("com/google/gson/internal/sql/SqlTypesSupport$*.class")
		exclude("com/google/gson/internal/reflect/ReflectionHelper.class")
		exclude("com/google/gson/internal/reflect/ReflectionHelper$*.class")
		exclude("com/google/gson/internal/ReflectionAccessFilterHelper.class")
		exclude("com/google/gson/internal/ReflectionAccessFilterHelper$*.class")
	}
}

val guavaSource = configurations.detachedConfiguration(
	dependencies.create("com.google.guava:guava:33.6.0-jre")
).apply { isTransitive = false }

val patchGuava = tasks.register<Zip>("patchGuava") {
	archiveFileName.set("guava-33.6.0-jre-patched.jar")
	inputs.property("patchRev", 2)
	destinationDirectory.set(layout.buildDirectory.dir("patched-guava"))
	entryCompression = ZipEntryCompression.STORED
	from(provider { zipTree(guavaSource.singleFile) }) {
		exclude("com/google/common/util/concurrent/AbstractFutureState*.class")
	}
}

val classlibSource = configurations.detachedConfiguration(
	dependencies.create("org.teavm:teavm-classlib:$teaVMVersion")
).apply { isTransitive = false }

val patchClasslib = tasks.register<Zip>("patchClasslib") {
	archiveFileName.set("teavm-classlib-$teaVMVersion-patched.jar")
	inputs.property("replacedClasses", replacedClasslibClasses)
	destinationDirectory.set(layout.buildDirectory.dir("patched-classlib"))
	entryCompression = ZipEntryCompression.STORED
	from(provider { zipTree(classlibSource.singleFile) }) {
		exclude { entry ->
			replacedClasslibClasses.any { base ->
				entry.path == "$base.class" || (entry.path.startsWith("$base$") && entry.path.endsWith(".class"))
			}
		}
	}
}

val teaVMCoreSource = configurations.detachedConfiguration(
	dependencies.create("org.teavm:teavm-core:$teaVMVersion")
).apply { isTransitive = false }
val teaVMCoreSources = configurations.detachedConfiguration(
	dependencies.create("org.teavm:teavm-core:$teaVMVersion:sources")
).apply { isTransitive = false }

val longRuntimePath = "org/teavm/backend/javascript/long.js"
val fastAnalyzerPath = "org/teavm/dependency/FastDependencyAnalyzer"
val inferencePath = "org/teavm/model/analysis/ClassInference"
val escapePath = "org/teavm/model/analysis/EscapeAnalysis"
val inliningPath = "org/teavm/model/optimization/Inlining"
val loopAnalyzerPath = "org/teavm/model/optimization/LoopInvariantAnalyzer"
val heapPath = "org/teavm/runtime/heap/Heap"
val corePatchClasspath = configurations.detachedConfiguration(
	dependencies.create("org.teavm:teavm-core:$teaVMVersion")
)
val prepareCorePatch = tasks.register("prepareCorePatch") {
	inputs.files(teaVMCoreSources)
	val output = layout.buildDirectory.file("compiler-core-source/$fastAnalyzerPath.java")
	outputs.file(output)
	doLast {
		val source = ZipFile(teaVMCoreSources.singleFile).use { zip ->
			zip.getInputStream(zip.getEntry("$fastAnalyzerPath.java")).bufferedReader().readText()
		}
		val before = "ValueType result = ValueType.parseIfPossible(itemName);"
		val after = "ValueType result = degree > 0 ? ValueType.parseIfPossible(itemName) : null;"
		require(source.contains(after) || source.split(before).size == 2) { "TeaVM fast analyzer changed; review the compiler patch" }
		output.get().asFile.apply {
			parentFile.mkdirs()
			writeText(if (source.contains(after)) source else source.replace(before, after))
		}
	}
}
val prepareInferencePatch = tasks.register("prepareInferencePatch") {
	inputs.files(teaVMCoreSources)
	val output = layout.buildDirectory.file("compiler-inference-source/$inferencePath.java")
	outputs.file(output)
	doLast {
		var source = ZipFile(teaVMCoreSources.singleFile).use { zip ->
			zip.getInputStream(zip.getEntry("$inferencePath.java")).bufferedReader().readText()
		}.replace("com.carrotsearch.hppc", "org.teavm.hppc")
		for ((variable, block) in listOf("thrownTypeName" to "program.basicBlockAt(callSite.block)", "type" to "currentBlock")) {
			val before = "for (String $variable : methodDep.getThrown().getTypes()) {"
			val after = "for (String $variable : $block.getTryCatchBlocks().isEmpty() ? new String[0] : methodDep.getThrown().getTypes()) {"
			if (source.contains(after)) continue
			require(source.split(before).size == 2) { "TeaVM exception inference changed; review the compiler patch" }
			source = source.replace(before, after)
		}
		output.get().asFile.apply {
			parentFile.mkdirs()
			writeText(source)
		}
	}
}
val prepareEscapePatch = tasks.register("prepareEscapePatch") {
	inputs.files(teaVMCoreSources)
	val output = layout.buildDirectory.file("compiler-escape-source/$escapePath.java")
	outputs.file(output)
	doLast {
		val source = ZipFile(teaVMCoreSources.singleFile).use { zip ->
			zip.getInputStream(zip.getEntry("$escapePath.java")).bufferedReader().readText()
		}.replace("com.carrotsearch.hppc", "org.teavm.hppc")
		val before = "for (int successor : graph.outgoingEdges(task.index)) {\n                queue.add(new Task(successor, task.field));\n            }"
		val after = before + "\n            for (int predecessor : graph.incomingEdges(task.index)) {\n                queue.add(new Task(predecessor, task.field));\n            }"
		require(source.contains(after) || source.split(before).size == 2) { "TeaVM escape analysis changed; review the compiler patch" }
		output.get().asFile.apply {
			parentFile.mkdirs()
			writeText(if (source.contains(after)) source else source.replace(before, after))
		}
	}
}
val prepareInliningPatch = tasks.register("prepareInliningPatch") {
	inputs.files(teaVMCoreSources)
	val output = layout.buildDirectory.file("compiler-inlining-source/$inliningPath.java")
	outputs.file(output)
	doLast {
		var source = ZipFile(teaVMCoreSources.singleFile).use { zip ->
			zip.getInputStream(zip.getEntry("$inliningPath.java")).bufferedReader().readText()
		}.replace("com.carrotsearch.hppc", "org.teavm.hppc")
		val field = "private ClassInference classInference;"
		if (source.contains("overflowImplementations")) {
			output.get().asFile.apply { parentFile.mkdirs(); writeText(source) }
			return@doLast
		}
		require(source.split(field).size == 2) { "TeaVM inlining fields changed; review the compiler patch" }
		source = source.replace(field, field + "\n    private Map<MethodReference, Set<MethodReference>> overflowImplementations = new java.util.HashMap<>();")
		val start = source.indexOf("                Set<MethodReference> implementations = new HashSet<>();")
		val end = source.indexOf("                if (implementations.size() == 1) {", start)
		require(start >= 0 && end > start) { "TeaVM inlining resolution changed; review the compiler patch" }
		val replacement = """|                Set<MethodReference> implementations;
|                if (classInference.isOverflow(invoke.getInstance().getIndex())) {
|                    implementations = overflowImplementations.computeIfAbsent(invoke.getMethod(), reference ->
|                            Devirtualization.implementations(hierarchy, dependencyInfo,
|                                    classes.getClassNames().toArray(new String[0]), reference));
|                } else {
|                    implementations = Devirtualization.implementations(hierarchy, dependencyInfo,
|                            classInference.classesOf(invoke.getInstance().getIndex()), invoke.getMethod());
|                }
|
|""".trimMargin()
		output.get().asFile.apply {
			parentFile.mkdirs()
			writeText(source.substring(0, start) + replacement + source.substring(end))
		}
	}
}
val prepareLoopAnalyzerPatch = tasks.register("prepareLoopAnalyzerPatch") {
	inputs.files(teaVMCoreSources)
	val output = layout.buildDirectory.file("compiler-loop-source/$loopAnalyzerPath.java")
	outputs.file(output)
	doLast {
		val source = ZipFile(teaVMCoreSources.singleFile).use { zip ->
			zip.getInputStream(zip.getEntry("$loopAnalyzerPath.java")).bufferedReader().readText()
		}
		val before = "public void visit(CastInstruction insn) {\n        canMove = true;"
		val after = before + "\n        sideEffect = !insn.isWeak();"
		require(source.contains(after) || source.split(before).size == 2) { "TeaVM cast motion changed; review the compiler patch" }
		output.get().asFile.apply {
			parentFile.mkdirs()
			writeText(if (source.contains(after)) source else source.replace(before, after))
		}
	}
}
val prepareHeapPatch = tasks.register("prepareHeapPatch") {
	inputs.files(teaVMCoreSources)
	val output = layout.buildDirectory.file("compiler-heap-source/$heapPath.java")
	outputs.file(output)
	doLast {
		var source = ZipFile(teaVMCoreSources.singleFile).use { zip ->
			zip.getInputStream(zip.getEntry("$heapPath.java")).bufferedReader().readText()
		}
		val replacements = listOf(
			"var last = lastFreeRecord();" to "var last = lastRecord();",
			"var amountToGrow = bytes - (last != null ? HeapRecord.size(last) : 0);" to
				"var amountToGrow = bytes + (HeapRecord.isAllocated(last) ? Structure.sizeOf(HeapRecord.class) : -HeapRecord.size(last));",
			"private static boolean tryExtend(int bytes, HeapNode last)" to
				"private static boolean tryExtend(int bytes, HeapRecord last)",
			"if (last != null) {\n            delete(last);\n            last.size += grownBytes;\n            insert(last);" to
				"if (!HeapRecord.isAllocated(last)) {\n            delete((HeapNode) last);\n            last.size += grownBytes;\n            insert((HeapNode) last);",
			"newEmpty.size = grownBytes - Structure.sizeOf(HeapRecord.class);" to
				"newEmpty.size = grownBytes - Structure.sizeOf(HeapRecord.class);\n            newEmpty.previousSize = HeapRecord.size(last);",
			"private static HeapNode lastFreeRecord()" to "private static HeapRecord lastRecord()",
			"result = HeapRecord.isAllocated(record) ? Address.fromInt(0).toStructure() : record;" to "result = record;",
			"return (HeapNode) result;" to "return result;"
		)
		for ((before, after) in replacements) {
			if (source.contains(after)) continue
			require(source.split(before).size == 2) { "TeaVM heap changed; review growth patch: $before" }
			source = source.replace(before, after)
		}
		output.get().asFile.apply {
			parentFile.mkdirs()
			writeText(source)
		}
	}
}
val asyncFinderPath = "org/teavm/model/util/AsyncMethodFinder"
val prepareAsyncFinderPatch = tasks.register("prepareAsyncFinderPatch") {
	inputs.files(teaVMCoreSources)
	val cutFile = file("async-cut.txt")
	if (cutFile.exists()) inputs.file(cutFile)
	val reportFile = layout.buildDirectory.file("async-report.txt").get().asFile
	val output = layout.buildDirectory.file("compiler-async-source/$asyncFinderPath.java")
	outputs.file(output)
	doLast {
		var source = ZipFile(teaVMCoreSources.singleFile).use { zip ->
			zip.getInputStream(zip.getEntry("$asyncFinderPath.java")).bufferedReader().readText()
		}
		fun swap(before: String, after: String) {
			require(source.split(before).size == 2) { "TeaVM AsyncMethodFinder changed; review the async patch: " + before.take(60) }
			source = source.replace(before, after)
		}
		val reportPath = reportFile.absolutePath.replace("\\", "/")
		val cutPath = cutFile.absolutePath.replace("\\", "/")
		swap("    private boolean hasAsyncMethods;\n", """|    private boolean hasAsyncMethods;
			|    private final Map<MethodReference, MethodReference> web263Parent = new HashMap<>();
			|    private final Map<MethodReference, Integer> web263Via = new HashMap<>();
			|    private final Set<String> web263Cut = web263LoadCut();
			|    private final Map<String, Integer> web263CutHits = new java.util.TreeMap<>();
			|    private final Set<MethodReference> web263CutRefs = new java.util.LinkedHashSet<>();
			|    private int web263ViaSize;
			|    private final Map<MethodReference, List<MethodReference>> web263GatedByCallee = new HashMap<>();
			|    private final Map<MethodReference, Integer> web263GatedTargets = new HashMap<>();
			|    private int web263GatedEdges;
			|
			|    // gate:N stops propagation across call sites with more than N possible targets, unless the
			|    // caller's class name starts with a gate-allow: prefix (thread entry machinery). Lines in async-cut.txt.
			|    private boolean web263Gated(MethodReference callee, MethodReference caller, int targets) {
			|        int limit = Integer.MAX_VALUE;
			|        List<String> allow = new ArrayList<>();
			|        for (String entry : web263Cut) {
			|            if (entry.startsWith("gate:")) {
			|                limit = Integer.parseInt(entry.substring(5).trim());
			|            } else if (entry.startsWith("gate-allow:")) {
			|                allow.add(entry.substring(11).trim());
			|            }
			|        }
			|        if (targets <= limit) {
			|            return false;
			|        }
			|        for (String allowed : allow) {
			|            String owner = caller.getClassName();
			|            if (owner.equals(allowed) || owner.startsWith(allowed + "$")) {
			|                return false;
			|            }
			|        }
			|        web263GatedEdges++;
			|        List<MethodReference> callers = web263GatedByCallee.computeIfAbsent(callee, k -> new ArrayList<>());
			|        if (callers.size() < 6) {
			|            callers.add(caller);
			|        }
			|        web263GatedTargets.merge(callee, targets, Math::max);
			|        return true;
			|    }
			|
			|    private static Set<String> web263LoadCut() {
			|        Set<String> cut = new HashSet<>();
			|        try {
			|            java.nio.file.Path path = java.nio.file.Paths.get("$cutPath");
			|            if (java.nio.file.Files.exists(path)) {
			|                for (String line : java.nio.file.Files.readAllLines(path)) {
			|                    line = line.trim();
			|                    if (!line.isEmpty() && !line.startsWith("#")) {
			|                        cut.add(line);
			|                    }
			|                }
			|            }
			|        } catch (java.io.IOException e) {
			|            throw new RuntimeException(e);
			|        }
			|        return cut;
			|    }
			|
			|    private String web263CutEntry(MethodReference ref) {
			|        if (web263Cut.isEmpty()) {
			|            return null;
			|        }
			|        String full = ref.toString();
			|        if (web263Cut.contains(full)) {
			|            return full;
			|        }
			|        String named = ref.getClassName() + "." + ref.getName();
			|        if (web263Cut.contains(named)) {
			|            return named;
			|        }
			|        String wholeClass = ref.getClassName() + ".*";
			|        return web263Cut.contains(wholeClass) ? wholeClass : null;
			|    }
			|
			|    private void web263Report() {
			|        Map<MethodReference, List<MethodReference>> children = new HashMap<>();
			|        List<MethodReference> seeds = new ArrayList<>();
			|        for (Map.Entry<MethodReference, MethodReference> e : web263Parent.entrySet()) {
			|            if (e.getValue() == null) {
			|                seeds.add(e.getKey());
			|            } else {
			|                children.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
			|            }
			|        }
			|        Map<MethodReference, Integer> size = new HashMap<>();
			|        java.util.ArrayDeque<MethodReference> order = new java.util.ArrayDeque<>();
			|        java.util.ArrayDeque<MethodReference> work = new java.util.ArrayDeque<>(seeds);
			|        while (!work.isEmpty()) {
			|            MethodReference m = work.pop();
			|            order.push(m);
			|            for (MethodReference c : children.getOrDefault(m, List.of())) {
			|                work.push(c);
			|            }
			|        }
			|        for (MethodReference m : order) {
			|            int s = 1;
			|            for (MethodReference c : children.getOrDefault(m, List.of())) {
			|                s += size.getOrDefault(c, 1);
			|            }
			|            size.put(m, s);
			|        }
			|        // Polymorphic entries: an async callee that reached callers through call sites with more than one target.
			|        Map<MethodReference, int[]> poly = new HashMap<>();
			|        int viaPolymorphic = 0;
			|        for (Map.Entry<MethodReference, MethodReference> e : web263Parent.entrySet()) {
			|            Integer via = web263Via.get(e.getKey());
			|            if (e.getValue() != null && via != null && via > 1) {
			|                viaPolymorphic++;
			|                int[] agg = poly.computeIfAbsent(e.getValue(), k -> new int[3]);
			|                agg[0]++;
			|                agg[1] += size.getOrDefault(e.getKey(), 1);
			|                agg[2] = Math.max(agg[2], via);
			|            }
			|        }
			|        StringBuilder out = new StringBuilder();
			|        out.append("async methods: ").append(asyncMethods.size()).append("\n");
			|        out.append("async family methods: ").append(asyncFamilyMethods.size()).append("\n");
			|        out.append("hasAsyncMethods (monitors seed): ").append(hasAsyncMethods).append("\n");
			|        out.append("first reached through a polymorphic call site: ").append(viaPolymorphic).append("\n");
			|        out.append("cut list: ").append(web263Cut).append("\n");
			|        out.append("cut hits: ").append(web263CutHits).append("\n");
			|        out.append("gated edges: ").append(web263GatedEdges).append(" (suspending callee -> caller, through a call site with many targets)\n");
			|        for (Map.Entry<MethodReference, List<MethodReference>> g : web263GatedByCallee.entrySet()) {
			|            out.append("  gated ").append(g.getKey()).append("   targets<=").append(web263GatedTargets.get(g.getKey()))
			|                    .append("   e.g. ").append(g.getValue()).append("\n");
			|        }
			|        // Every caller of a cut method, two levels up: these call sites can no longer suspend.
			|        for (MethodReference cut : web263CutRefs) {
			|            CallGraphNode node = callGraph.getNode(cut);
			|            out.append("\n== callers of cut method ").append(cut).append("\n");
			|            if (node == null) {
			|                continue;
			|            }
			|            for (CallSite site : node.getCallerCallSites()) {
			|                for (CallGraphNode caller : site.getCallers()) {
			|                    out.append("  ").append(caller.getMethod()).append("   targets=").append(site.getCalledMethods().size()).append("\n");
			|                    java.util.Set<String> up = new java.util.TreeSet<>();
			|                    for (CallSite upSite : caller.getCallerCallSites()) {
			|                        for (CallGraphNode upCaller : upSite.getCallers()) {
			|                            up.add(upCaller.getMethod().toString());
			|                        }
			|                    }
			|                    for (String u : up) {
			|                        out.append("      <- ").append(u).append("\n");
			|                    }
			|                }
			|            }
			|        }
			|        out.append("\n== seeds (subtree size)\n");
			|        seeds.sort((a, b) -> size.get(b) - size.get(a));
			|        for (MethodReference s : seeds) {
			|            out.append(size.get(s)).append("  ").append(s).append("\n");
			|        }
			|        out.append("\n== polymorphic entries: async callee, callers it tainted through multi-target sites, their subtree total, max targets\n");
			|        List<MethodReference> polyList = new ArrayList<>(poly.keySet());
			|        polyList.sort((a, b) -> poly.get(b)[1] - poly.get(a)[1]);
			|        for (MethodReference m : polyList.subList(0, Math.min(150, polyList.size()))) {
			|            int[] agg = poly.get(m);
			|            out.append(agg[1]).append("  callers ").append(agg[0]).append("  targets<=").append(agg[2]).append("  ").append(m)
			|                    .append("  <- ").append(web263Chain(m, 6)).append("\n");
			|        }
			|        out.append("\n== largest subtrees\n");
			|        List<MethodReference> all = new ArrayList<>(size.keySet());
			|        all.sort((a, b) -> size.get(b) - size.get(a));
			|        for (MethodReference m : all.subList(0, Math.min(200, all.size()))) {
			|            out.append(size.get(m)).append("  via ").append(web263Via.get(m)).append("  ").append(m)
			|                    .append("  <- ").append(web263Chain(m, 4)).append("\n");
			|        }
			|        try {
			|            java.nio.file.Files.writeString(java.nio.file.Paths.get("$reportPath"), out.toString());
			|        } catch (java.io.IOException e) {
			|            throw new RuntimeException(e);
			|        }
			|    }
			|
			|    private String web263Chain(MethodReference m, int hops) {
			|        StringBuilder chain = new StringBuilder();
			|        MethodReference p = web263Parent.get(m);
			|        for (int i = 0; i < hops && p != null; i++) {
			|            chain.append(i == 0 ? "" : " <- ").append(p);
			|            p = web263Parent.get(p);
			|        }
			|        return chain.toString();
			|    }
			|""".trimMargin())
		swap("""        for (Map.Entry<MethodReference, Boolean> entry : new ArrayList<>(asyncFamilyMethods.entrySet())) {
            if (!entry.getValue()) {
                asyncFamilyMethods.remove(entry.getKey());
            }
        }
    }
""", """        for (Map.Entry<MethodReference, Boolean> entry : new ArrayList<>(asyncFamilyMethods.entrySet())) {
            if (!entry.getValue()) {
                asyncFamilyMethods.remove(entry.getKey());
            }
        }
        web263Report();
    }
""")
		swap("""        if (!asyncMethods.add(methodRef)) {
            return;
        }
        CallGraphNode node = callGraph.getNode(methodRef);""", """        String web263Hit = web263CutEntry(methodRef);
        if (web263Hit != null) {
            web263CutHits.merge(web263Hit, 1, Integer::sum);
            web263CutRefs.add(methodRef);
            return;
        }
        if (stack != null && stack.next != null && web263Gated(stack.next.method, methodRef, web263ViaSize)) {
            return;
        }
        if (!asyncMethods.add(methodRef)) {
            return;
        }
        web263Parent.put(methodRef, stack != null && stack.next != null ? stack.next.method : null);
        web263Via.put(methodRef, web263ViaSize);
        CallGraphNode node = callGraph.getNode(methodRef);""")
		swap("""        for (CallSite callSite : node.getCallerCallSites()) {
            for (CallGraphNode caller : callSite.getCallers()) {
                add(caller.getMethod(), new CallStack(caller.getMethod(), stack));""", """        for (CallSite callSite : node.getCallerCallSites()) {
            int web263Targets = callSite.getCalledMethods().size();
            for (CallGraphNode caller : callSite.getCallers()) {
                web263ViaSize = web263Targets;
                add(caller.getMethod(), new CallStack(caller.getMethod(), stack));""")
		output.get().asFile.apply {
			parentFile.mkdirs()
			writeText(source)
		}
	}
}
val compileCorePatch = tasks.register<JavaCompile>("compileCorePatch") {
	source(prepareCorePatch, prepareInferencePatch, prepareEscapePatch, prepareInliningPatch, prepareLoopAnalyzerPatch, prepareHeapPatch, prepareAsyncFinderPatch)
	classpath = corePatchClasspath
	destinationDirectory.set(layout.buildDirectory.dir("compiler-core-classes"))
	options.release.set(17)
}
val patchTeaVMCore = tasks.register<Zip>("patchTeaVMCore") {
	archiveFileName.set("teavm-core-$teaVMVersion-patched.jar")
	destinationDirectory.set(layout.buildDirectory.dir("patched-teavm-core"))
	entryCompression = ZipEntryCompression.STORED
	from(provider { zipTree(teaVMCoreSource.singleFile) }) {
		exclude(longRuntimePath)
		exclude("$fastAnalyzerPath.class")
		exclude("$inferencePath.class", "$inferencePath\$*.class")
		exclude("$escapePath.class", "$escapePath\$*.class")
		exclude("$inliningPath.class", "$inliningPath\$*.class")
		exclude("$loopAnalyzerPath.class")
		exclude("$heapPath.class")
		exclude("$asyncFinderPath.class", "$asyncFinderPath\$*.class")
	}
	from(compileCorePatch.map { it.destinationDirectory })
	from("compiler-runtime") {
		include(longRuntimePath)
	}
}

val teaVMToolingSource = configurations.detachedConfiguration(
	dependencies.create("org.teavm:teavm-tooling:$teaVMVersion")
).apply { isTransitive = false }
val teaVMToolingClasspath = configurations.detachedConfiguration(
	dependencies.create("org.teavm:teavm-tooling:$teaVMVersion")
)
val compileToolingPatch = tasks.register<Exec>("compileToolingPatch") {
	val out = layout.buildDirectory.dir("tooling-patch-classes")
	inputs.dir("compiler-tooling")
	inputs.files(teaVMToolingClasspath)
	outputs.dir(out)
	doFirst { out.get().asFile.mkdirs() }
	commandLine(listOf(
		hostJavac.get().executablePath.asFile.absolutePath,
		"--release", "17", "-nowarn", "-proc:none", "-cp", teaVMToolingClasspath.asPath,
		"-d", out.get().asFile.absolutePath,
		file("compiler-tooling/org/teavm/tooling/TeaVMTool.java").absolutePath))
	doLast {
		val daemonPath = "org/teavm/tooling/daemon/BuildDaemon.class"
		val bytes = ZipFile(teaVMToolingSource.singleFile).use { zip ->
			zip.getInputStream(zip.getEntry(daemonPath)).readBytes()
		}
		val from = "-XX:+HeapDumpOnOutOfMemoryError".encodeToByteArray()
		val to = "-XX:-HeapDumpOnOutOfMemoryError".encodeToByteArray()
		check(from.size == to.size)
		var hits = 0
		var i = 0
		while (i <= bytes.size - from.size) {
			var match = true
			var j = 0
			while (j < from.size) {
				if (bytes[i + j] != from[j]) {
					match = false
					break
				}
				j++
			}
			if (match) {
				from.indices.forEach { k -> bytes[i + k] = to[k] }
				hits++
				i += from.size
			} else {
				i++
			}
		}
		check(hits >= 1) { "TeaVM BuildDaemon heap-dump flag missing in $daemonPath" }
		out.get().file(daemonPath).asFile.apply {
			parentFile.mkdirs()
			writeBytes(bytes)
		}
	}
}
val patchTeaVMTooling = tasks.register<Zip>("patchTeaVMTooling") {
	archiveFileName.set("teavm-tooling-$teaVMVersion-patched.jar")
	destinationDirectory.set(layout.buildDirectory.dir("patched-teavm-tooling"))
	entryCompression = ZipEntryCompression.STORED
	from(provider { zipTree(teaVMToolingSource.singleFile) }) {
		exclude("org/teavm/tooling/TeaVMTool.class", "org/teavm/tooling/TeaVMTool$*.class",
			"org/teavm/tooling/daemon/BuildDaemon.class")
	}
	from(compileToolingPatch.map { it.outputs.files.singleFile })
	doLast {
		ZipFile(archiveFile.get().asFile).use { zip ->
			val entry = zip.getEntry("org/teavm/tooling/daemon/BuildDaemon.class")
			val bytes = zip.getInputStream(entry).readBytes()
			check(CRC32().apply { update(bytes) }.value == entry.crc) {
				"TeaVM BuildDaemon ZIP checksum mismatch"
			}
		}
	}
}

configurations.configureEach {
	if (name != classlibSource.name) {
		exclude(group = "org.teavm", module = "teavm-classlib")
		exclude(group = "org.slf4j")
		exclude(group = "org.apache.logging.log4j")
		exclude(group = "com.mojang", module = "logging")
		exclude(group = "org.joml")
		if (name != guavaSource.name) {
			exclude(group = "com.google.guava", module = "guava")
		}
		if (name != gsonSource.name) {
			exclude(group = "com.google.code.gson", module = "gson")
		}
	}
}

dependencies {
	"teavm"(files(patchClasslib.map { it.archiveFile }))
	"teavm"("com.jcraft:jzlib:1.1.3")
	"teavm"("org.teavm:teavm-platform:$teaVMVersion")
	"teavm"("org.teavm:teavm-jso-impl:$teaVMVersion")
	"teavm"("org.teavm:teavm-metaprogramming-impl:$teaVMVersion")
}

dependencies {
	teavm(teavm.libs.jso)
	teavm(teavm.libs.jsoApis)
	"teavm"(files(compileSunMisc.map { it.outputs.files.singleFile }))
	compileOnly("org.teavm:teavm-core:$teaVMVersion")
	compileOnly("org.teavm:teavm-interop:$teaVMVersion")
	compileOnly("org.teavm:teavm-platform:$teaVMVersion")
	compileOnly("org.teavm:teavm-jso:$teaVMVersion")
	compileOnly("org.teavm:teavm-jso-impl:$teaVMVersion")
	compileOnly(files(provider { classlibSource.singleFile }))
	compileOnly("org.jetbrains:annotations:24.1.0")
	compileOnly("com.google.j2objc:j2objc-annotations:3.0.0")
	compileOnly("org.checkerframework:checker-qual:3.43.0")
	compileOnly("com.google.errorprone:error_prone_annotations:2.28.0")
	val gameArchive = if (providers.gradleProperty("game263.reconstructedJar").isPresent)
		"game263-reconstructed-flipped.jar" else "game263-flipped.jar"
	implementation(files("../game263/build/libs/$gameArchive") {
		builtBy(":game263:flippedJar")
	})
	implementation(files(patchGson.map { it.archiveFile }))
	implementation(files(patchGuava.map { it.archiveFile }))
	implementation("com.google.guava:failureaccess:1.0.3")
	implementation("com.mojang:brigadier:1.3.11")
	implementation("com.mojang:datafixerupper:10.0.21")
	implementation("com.mojang:logging:1.7.12")
	implementation("commons-codec:commons-codec:1.22.0")
	implementation("commons-io:commons-io:2.20.0")
	implementation("org.apache.commons:commons-lang3:3.20.0")
	implementation("it.unimi.dsi:fastutil:8.5.18")
	implementation("org.jspecify:jspecify:1.0.0")
}

fun buildFlag(name: String, default: Boolean = false) =
	providers.gradleProperty(name).map { it.isEmpty() || it.toBoolean() }.getOrElse(default)

val isRelease = buildFlag("web263.release")

val isGenModule = buildFlag("web263.gen")
val isServerModule = buildFlag("web263.server") || isGenModule

val isObfuscated = isRelease || buildFlag("web263.obfuscate", default = true)

teavm.js {
	obfuscated = isObfuscated
	sourceMap = false
	targetFileName = if (isGenModule) "../classes263_gen.js" else if (isServerModule) "../classes263_server.js" else "../classes263.js"
	optimization = if (isRelease) OptimizationLevel.BALANCED else OptimizationLevel.NONE
	outOfProcess = true
	fastGlobalAnalysis = !buildFlag("web263.precise")
	processMemory = 12288
	entryPointName.set("main")
	mainClass = if (isGenModule) "net.minecraft.client.web.WebGenMain" else if (isServerModule) "net.minecraft.client.web.WebServerMain" else "net.minecraft.client.web.WebMain"
	outputDir = file("javascript")
	debugInformation = true
}

val wasmGCClientFile = "classes263.wasm"
val wasmGCServerFile = "classes263_server.wasm"
val wasmGCGenFile = "classes263_gen.wasm"
val wasmGCTargetFile = if (isGenModule) wasmGCGenFile else if (isServerModule) wasmGCServerFile else wasmGCClientFile
val wasmGCOptimization = if (buildFlag("web263.wasmFull")) OptimizationLevel.AGGRESSIVE else OptimizationLevel.BALANCED
val wasmGCMainClass = if (isGenModule) {
	"net.minecraft.client.web.WebGenMain"
} else if (isServerModule) {
	"net.minecraft.client.web.WebServerMain"
} else {
	"net.minecraft.client.web.WebMain"
}

fun wasmGCArtifactNames(wasmFile: String) = listOf(
	wasmFile,
	"$wasmFile-runtime.js",
	"$wasmFile-deobfuscator.wasm",
	"$wasmFile.teadbg",
)

teavm.wasmGC {
	targetFileName = wasmGCTargetFile
	outputDir = file("javascript-wasm-gc")
	relativePathInOutputDir = "wasm-gc"
	mainClass = wasmGCMainClass
	optimization = wasmGCOptimization
	obfuscated = isObfuscated
	strict = true
	copyRuntime = true
	outOfProcess = true
	fastGlobalAnalysis = !buildFlag("web263.precise")
	processMemory = (findProperty("web263.linkMemoryMb") as String?)?.toInt() ?: (if (buildFlag("web263.precise")) 14336 else 8192)
	debugInformation = true
	debugInfoLocation = WasmDebugInfoLocation.EXTERNAL
	debugInfoLevel = WasmDebugInfoLevel.DEOBFUSCATION
	disassembly = false
	minDirectBuffersSize = 32
	maxDirectBuffersSize = 512
}

val checkCompilerHost = tasks.register("checkCompilerHost") {
	doLast {
		check(JavaVersion.current() == JavaVersion.VERSION_17) {
			"This TeaVM fork requires a JDK 17 host. Set JAVA_HOME to JDK 17 and keep JDK 25 installed for the game toolchain."
		}
	}
}

tasks.withType<org.teavm.gradle.tasks.TeaVMTask>().configureEach {
	val upstream = daemonClasspath.files.filterNot { it.name.startsWith("teavm-core-") || it.name.startsWith("teavm-tooling-") }
	dependsOn(checkCompilerHost, patchTeaVMCore, patchTeaVMTooling)
	daemonClasspath.setFrom(patchTeaVMCore.map { it.archiveFile }, patchTeaVMTooling.map { it.archiveFile }, upstream)
}

tasks.register("checkCompilerRuntime") {
	dependsOn(checkCompilerHost, patchTeaVMCore, patchTeaVMTooling)
	doLast {
		val archive = patchTeaVMCore.get().archiveFile.get().asFile
		val override = file("compiler-runtime/$longRuntimePath").readText()
		val upstreamSource = ZipFile(teaVMCoreSources.singleFile).use { zip ->
			zip.getInputStream(zip.getEntry(longRuntimePath)).bufferedReader().readText()
		}
		fun unchangedRuntimeParts(source: String): Pair<String, String> {
			val start = source.indexOf("let Long_fromNumber")
			val end = source.indexOf("let Long_toNumber")
			if (start < 0 || end <= start) {
				throw GradleException("TeaVM long runtime layout changed")
			}
			return source.substring(0, start) to source.substring(end)
		}
		if (unchangedRuntimeParts(override) != unchangedRuntimeParts(upstreamSource)) {
			throw GradleException("compiler override changes more than Long_fromNumber")
		}
		ZipFile(archive).use { zip ->
			val entries = zip.entries().asSequence().filter { it.name == longRuntimePath }.toList()
			if (entries.size != 1) {
				throw GradleException("patched teavm-core has ${entries.size} copies of $longRuntimePath")
			}
			val source = zip.getInputStream(entries.single()).bufferedReader().readText()
			for (fragment in listOf("Number.isFinite(val)", "val !== val ? Long_ZERO", "9223372036854775807")) {
				if (!source.contains(fragment)) {
					throw GradleException("patched teavm-core long runtime is missing: $fragment")
				}
			}
		}
		for (name in listOf("generateJavaScript", "generateWasmGC")) {
			val compilerTask = tasks.named<org.teavm.gradle.tasks.TeaVMTask>(name).get()
			val coreJars = compilerTask.daemonClasspath.files.filter { it.name.startsWith("teavm-core-") }
			if (coreJars != listOf(archive)) {
				throw GradleException("$name does not use exactly the patched teavm-core: $coreJars")
			}
			val mismatched = compilerTask.daemonClasspath.files.filter {
				it.name.startsWith("teavm-") && !it.name.contains(teaVMVersion)
			}
			if (mismatched.isNotEmpty()) {
				throw GradleException("$name mixes TeaVM versions: $mismatched")
			}
		}
		val jsTask = tasks.named<org.teavm.gradle.tasks.GenerateJavaScriptTask>("generateJavaScript").get()
		val expectedJsFastAnalysis = !buildFlag("web263.precise")
		val expectedJsOptimization = if (isRelease) OptimizationLevel.BALANCED else OptimizationLevel.NONE
		if (jsTask.fastGlobalAnalysis.get() != expectedJsFastAnalysis
				|| jsTask.optimization.get() != expectedJsOptimization
				|| jsTask.obfuscated.get() != isObfuscated) {
			throw GradleException("JavaScript compiler profile does not match the selected build flags")
		}
		val wasmTask = tasks.named<org.teavm.gradle.tasks.GenerateWasmGCTask>("generateWasmGC").get()
		val expectedWasmFastAnalysis = !buildFlag("web263.precise")
		if (wasmTask.fastGlobalAnalysis.get() != expectedWasmFastAnalysis
				|| wasmTask.optimization.get() != wasmGCOptimization
				|| wasmTask.obfuscated.get() != isObfuscated
				|| wasmTask.targetFileName.get() != wasmGCTargetFile
				|| wasmTask.mainClass.get() != wasmGCMainClass) {
			throw GradleException("WasmGC compiler profile lost its size settings")
		}
		println("compiler profiles verified: js fast=${jsTask.fastGlobalAnalysis.get()} "
			+ "optimization=${jsTask.optimization.get()} obfuscated=${jsTask.obfuscated.get()}; "
			+ "wasm-gc fast=${wasmTask.fastGlobalAnalysis.get()} "
			+ "optimization=${wasmTask.optimization.get()} obfuscated=${wasmTask.obfuscated.get()} "
			+ "main=${wasmTask.mainClass.get()} output=${wasmTask.targetFileName.get()}")
	}
}

tasks.named<org.teavm.gradle.tasks.CopyWasmGCRuntimeTask>("copyWasmGCRuntime") {
	obfuscated.set(false)
}

val patchWasmGCRuntime = tasks.register("patchWasmGCRuntime") {
	outputs.upToDateWhen { false }
	doLast {
		val runtimeDir = file(findProperty("web263.wasmRuntimeDir")?.toString() ?: "javascript-wasm-gc/wasm-gc")
		val runtimes = listOf(wasmGCClientFile, wasmGCServerFile, wasmGCGenFile)
			.map { runtimeDir.resolve("$it-runtime.js") }
			.filter { it.isFile }
		val currentRuntime = runtimeDir.resolve("$wasmGCTargetFile-runtime.js")
		if (currentRuntime !in runtimes) {
			throw GradleException("WasmGC runtime is missing: ${currentRuntime.name}")
		}
		for (runtime in runtimes) {
			val original = """
        createWeakRef(value, ref, queue) {
            if (queue !== null) {
""".trimStart()
		val fixed = """
        createWeakRef(value, ref, queue) {
            if (value === null) {
                return null;
            }
            if (queue !== null) {
""".trimStart()
		var source = runtime.readText()
		val firstMatch = source.indexOf(original)
		when {
			source.contains(fixed) -> Unit
			firstMatch != source.lastIndexOf(original) ->
				throw GradleException("TeaVM runtime contains more than one createWeakRef factory")
			firstMatch >= 0 -> source = source.replace(original, fixed)
			else -> throw GradleException("TeaVM runtime createWeakRef factory changed; update its null patch")
		}
		val exportsLine = "            context.exports = exports;\n"
		val exportsPublished = exportsLine + "            globalThis.__teavmMemory = exports[\"teavm.memory\"];\n"
		if (!source.contains(exportsPublished)) {
			if (source.indexOf(exportsLine) < 0 || source.indexOf(exportsLine) != source.lastIndexOf(exportsLine)) {
				throw GradleException("TeaVM runtime exports assignment changed; update the linear-memory publish patch")
			}
			source = source.replace(exportsLine, exportsPublished)
		}
		if (!source.contains("function callInFiber(")) {
			val asyncStart = "function asyncImports(imports) {"
			val asyncIdx = source.indexOf(asyncStart)
			if (asyncIdx < 0) {
				throw GradleException("TeaVM runtime asyncImports changed; update the fiber trampoline patch")
			}
			val helper = """
function callInFiber(fn, args) {
    var enter = globalThis.__minecraftEnterFiber;
    if (typeof enter === "function") {
        globalThis.__minecraftFiberPending = {fn: fn, args: args};
        globalThis.__minecraftFiberPendingRet = undefined;
        enter();
        return globalThis.__minecraftFiberPendingRet;
    }
    return fn.apply(null, args);
}

"""
			source = source.substring(0, asyncIdx) + helper + source.substring(asyncIdx)
			source = source.replace(
				"            return setTimeout(() => {\n                fn(instance);\n            }, dt);",
				"            return setTimeout(() => {\n                callInFiber(fn, [instance]);\n            }, dt);"
			)
			val defineMethodOld = """            cls.prototype[name] = new Function("rethrowJavaAsJs", "fn",
                `return function(${'$'}{paramsAsString}) {\n` +
                `    try {\n` +
                `        return fn(${'$'}{['this', params].join(", ")});\n` +
                `    } catch (e) {\n` +
                `        rethrowJavaAsJs(e);\n` +
                `    }\n` +
                `};`
            )(rethrowJavaAsJs, fn);"""
			val defineMethodNew = """            cls.prototype[name] = new Function("rethrowJavaAsJs", "fn", "callInFiber",
                `return function(${'$'}{paramsAsString}) {\n` +
                `    try {\n` +
                `        return callInFiber(fn, [${'$'}{['this', params].join(", ")}]);\n` +
                `    } catch (e) {\n` +
                `        rethrowJavaAsJs(e);\n` +
                `    }\n` +
                `};`
            )(rethrowJavaAsJs, fn, callInFiber);"""
			if (!source.contains(defineMethodOld)) {
				throw GradleException("TeaVM runtime defineMethod changed; update the fiber trampoline patch")
			}
			source = source.replace(defineMethodOld, defineMethodNew)
			source = source.replace(
				"                result = function() {\n                    return instance[propertyName].apply(instance, arguments);\n                }",
				"                result = function() {\n                    return callInFiber(instance[propertyName].bind(instance), Array.from(arguments));\n                }"
			)
		}
		if (!source.contains("userExports.WasmGC.fiberCall")) {
			val hook = """    if (userExports.WasmGC && typeof userExports.WasmGC.fiberCall === "function") {
        globalThis.__minecraftEnterFiber = userExports.WasmGC.fiberCall;
    }
"""
			val marker = "    return teavm;\n}\n\nasync function compileModule"
			val hookAt = source.indexOf(marker)
			if (hookAt < 0) {
				throw GradleException("TeaVM runtime load() tail changed; update the fiberCall hook")
			}
			source = source.substring(0, hookAt) + hook + source.substring(hookAt)
		}
			runtime.writeText(source)
		}
	}
}

tasks.named("copyWasmGCRuntime") {
	finalizedBy(patchWasmGCRuntime)
}

tasks.named("buildWasmGC") {
	finalizedBy(patchWasmGCRuntime)
}

