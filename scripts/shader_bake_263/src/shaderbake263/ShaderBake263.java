// minecraft-web by chiikabu (https://github.com/chiikabu)

package shaderbake263;

import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.backend.opengl.GlPipelineRecompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.frontend.shaders.SpvUtil;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderManager;
import org.lwjgl.system.MemoryUtil;

public final class ShaderBake263 {
	public static final int MAGIC = 0x4D435342;
	public static final int VERSION = 1;
	static final int MAX_DECORATION = 47;

	private ShaderBake263() {
	}

	public static void main(String[] args) throws Exception {
		File clientJar = null;
		Path output = null;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--client-jar" -> clientJar = new File(args[++i]);
				case "--output" -> output = Path.of(args[++i]);
				default -> throw new IllegalArgumentException("unknown arg " + args[i]);
			}
		}
		if (clientJar == null || output == null) throw new IllegalArgumentException("usage: --client-jar JAR --output FILE");

		DeviceInfo info = Env.browserDeviceInfo();
		Env.installFakeDevice(info);
		ShaderManager.Configs configs = Env.loadConfigs(clientJar);
		Map<String, RenderPipeline> pipelines = Env.allPipelines(configs);

		PipelineBuilder builder = Env.newPipelineBuilder(info);
		RecordingGlslCompiler recorder = new RecordingGlslCompiler(info.isZZeroToOne(), info.features().shaderDrawParameters());
		Field compilerField = PipelineBuilder.class.getDeclaredField("compiler");
		compilerField.setAccessible(true);
		compilerField.set(builder, recorder);
		boolean drawParams = info.features().shaderDrawParameters();
		GlPipelineRecompiler recompiler = new GlPipelineRecompiler(null, drawParams);

		List<byte[]> blobs = new ArrayList<>();
		Map<String, Integer> blobByContent = new LinkedHashMap<>();
		Map<String, Integer> spvTable = new TreeMap<>();
		Map<String, byte[]> reflTable = new TreeMap<>();
		Map<String, ReflectionData> reflData = new TreeMap<>();
		Map<String, String> glslTable = new TreeMap<>();
		List<String> failed = new ArrayList<>();
		int ok = 0;

		for (Map.Entry<String, RenderPipeline> entry : pipelines.entrySet()) {
			recorder.calls.clear();
			List<PipelineRunner.StageResult> stages;
			try {
				stages = PipelineRunner.run(builder, recompiler, entry.getValue(), configs);
			} catch (Throwable t) {
				Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : t;
				System.err.println("[bake] " + entry.getKey() + " threw " + cause);
				stages = null;
			}
			for (RecordingGlslCompiler.Call call : recorder.calls) {
				String content = hex(fnv1a64(call.spv())) + ":" + call.spv().length;
				Integer blob = blobByContent.get(content);
				if (blob == null) {
					blob = blobs.size();
					blobs.add(call.spv());
					blobByContent.put(content, blob);
				}
				Integer prev = spvTable.putIfAbsent(call.key(), blob);
				if (prev != null && !Arrays.equals(blobs.get(prev), call.spv())) {
					throw new IllegalStateException("non-deterministic compile for " + call.key());
				}
				String mk = moduleKey(fnv1a64(call.spv()), call.type());
				if (!reflTable.containsKey(mk)) {
					ReflectionData rd = reflect(call.spv(), call.type());
					reflData.put(mk, rd);
					reflTable.put(mk, rd.serialized);
				}
			}
			if (stages == null) {
				failed.add(entry.getKey());
				continue;
			}
			if (stages.size() != recorder.calls.size()) throw new IllegalStateException("stage/compile mismatch " + entry.getKey());
			for (int i = 0; i < stages.size(); i++) {
				PipelineRunner.StageResult st = stages.get(i);
				RecordingGlslCompiler.Call call = recorder.calls.get(i);
				ShaderType type = ShaderType.valueOf(st.type());
				if (type != call.type()) throw new IllegalStateException("stage order mismatch " + entry.getKey());
				ReflectionData rd = reflData.get(moduleKey(fnv1a64(call.spv()), type));
				checkOnlyPatchableWordsDiffer(entry.getKey(), call.spv(), st.finalSpv(), rd.patchableWords);
				String mk = moduleKey(fnv1a64(st.finalSpv()), type);
				String prev = glslTable.putIfAbsent(mk, st.glsl());
				if (prev != null && !prev.equals(st.glsl())) throw new IllegalStateException("GLSL differs for same SPIR-V: " + entry.getKey());
			}
			ok++;
		}

		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bos);
		out.writeInt(MAGIC);
		out.writeInt(VERSION);
		out.writeInt((info.isZZeroToOne() ? 1 : 0) | (info.features().shaderDrawParameters() ? 2 : 0)
			| (info.hintsAndWorkarounds().isExplicitDepthRequired() ? 4 : 0) | (drawParams ? 8 : 0));
		out.writeInt(pipelines.size());
		out.writeInt(blobs.size());
		for (byte[] b : blobs) {
			out.writeInt(b.length);
			out.write(b);
		}
		out.writeInt(spvTable.size());
		for (Map.Entry<String, Integer> e : spvTable.entrySet()) {
			writeStr(out, e.getKey());
			out.writeInt(e.getValue());
		}
		out.writeInt(reflTable.size());
		for (Map.Entry<String, byte[]> e : reflTable.entrySet()) {
			writeModuleKey(out, e.getKey());
			out.write(e.getValue());
		}
		out.writeInt(glslTable.size());
		for (Map.Entry<String, String> e : glslTable.entrySet()) {
			writeModuleKey(out, e.getKey());
			writeStr(out, e.getValue());
		}
		out.flush();
		Files.createDirectories(output.toAbsolutePath().getParent());
		Files.write(output, bos.toByteArray());

		System.out.println("[bake] pipelines: " + pipelines.size() + " (ok " + ok + ", failed " + failed.size() + ")");
		System.out.println("[bake] SPV entries: " + spvTable.size() + " (" + blobs.size() + " distinct blobs)");
		System.out.println("[bake] reflection entries: " + reflTable.size());
		System.out.println("[bake] GLSL entries: " + glslTable.size());
		System.out.println("[bake] wrote " + output + " (" + bos.size() + " bytes)");
		for (String f : failed) System.out.println("[bake] FAILED: " + f);
		System.exit(0);
	}

	public static String spvKey(String name, ShaderType type, ShaderDefines defines) {
		StringBuilder sb = new StringBuilder(name).append('|').append(type.name()).append('|');
		boolean first = true;
		for (Map.Entry<String, String> e : new TreeMap<>(defines.values()).entrySet()) {
			if (!first) sb.append(',');
			sb.append(e.getKey()).append('=').append(e.getValue());
			first = false;
		}
		sb.append('|');
		first = true;
		for (String f : new TreeSet<>(defines.flags())) {
			if (!first) sb.append(',');
			sb.append(f);
			first = false;
		}
		return sb.toString();
	}

	public static long fnv1a64(byte[] bytes) {
		long h = 0xcbf29ce484222325L;
		for (byte b : bytes) {
			h ^= (b & 0xFF);
			h *= 0x100000001b3L;
		}
		return h;
	}

	static String moduleKey(long hash, ShaderType type) {
		return hex(hash) + ":" + type.ordinal();
	}

	private static String hex(long v) {
		return String.format("%016x", v);
	}

	private static void writeModuleKey(DataOutputStream out, String key) throws IOException {
		int colon = key.indexOf(':');
		out.writeLong(Long.parseUnsignedLong(key.substring(0, colon), 16));
		out.writeByte(Integer.parseInt(key.substring(colon + 1)));
	}

	static void writeStr(DataOutputStream out, String s) throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		out.writeInt(b.length);
		out.write(b);
	}

	private static void checkOnlyPatchableWordsDiffer(String pipeline, byte[] original, byte[] fin, Set<Integer> patchable) {
		if (original.length != fin.length) throw new IllegalStateException("SPIR-V length changed by PipelineBuilder: " + pipeline);
		for (int w = 0; w < original.length / 4; w++) {
			for (int k = 0; k < 4; k++) {
				if (original[w * 4 + k] != fin[w * 4 + k]) {
					if (!patchable.contains(w)) throw new IllegalStateException("unexpected patched word " + w + " in " + pipeline);
					break;
				}
			}
		}
	}

	static final class ReflectionData {
		byte[] serialized;
		final Set<Integer> patchableWords = new HashSet<>();
	}

	private static ReflectionData reflect(byte[] spvBytes, ShaderType type) throws Exception {
		ByteBuffer buf = MemoryUtil.memAlloc(spvBytes.length);
		buf.put(0, spvBytes);
		SPIRVModule module = new SPIRVModule(buf, type);
		try {
			SpvModule.Reflection r = module.reflect();
			IntBuffer words = module.spv().asIntBuffer();
			ReflectionData rd = new ReflectionData();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bos);
			for (List<SpvModule.Reflection.InterfaceVariable> vars : List.of(r.inputs(), r.outputs())) {
				out.writeInt(vars.size());
				for (SpvModule.Reflection.InterfaceVariable v : vars) {
					writeStr(out, v.name());
					writeType(out, v.type());
					int loc = v.location();
					int off = probe(words, () -> v.location(loc + 0x1234567), () -> v.location(loc));
					out.writeInt(off);
					List<int[]> decs = new ArrayList<>();
					for (int d = 0; d <= MAX_DECORATION; d++) {
						int val = v.decoration(d);
						if (val != 0) decs.add(new int[] {d, val});
					}
					out.writeInt(decs.size());
					for (int[] d : decs) {
						out.writeInt(d[0]);
						out.writeInt(d[1]);
					}
				}
			}
			out.writeInt(SpvUtil.DESCRIPTOR_TYPES.size());
			int total = 0;
			for (int i = 0; i < SpvUtil.DESCRIPTOR_TYPES.size(); i++) {
				int rt = SpvUtil.DESCRIPTOR_TYPES.getInt(i);
				List<SpvModule.Reflection.Descriptor> ds = r.descriptors(rt);
				out.writeInt(rt);
				out.writeInt(ds.size());
				for (SpvModule.Reflection.Descriptor d : ds) {
					if (d.resourceType() != rt) throw new IllegalStateException("resourceType mismatch");
					writeStr(out, d.name());
					writeType(out, d.type());
					int set = d.descriptorSetIndex();
					int binding = d.binding();
					int setOff = probe(words, () -> d.descriptorSetIndex(set + 0x1234567), () -> d.descriptorSetIndex(set));
					int bindOff = probe(words, () -> d.binding(binding + 0x1234567), () -> d.binding(binding));
					rd.patchableWords.add(setOff);
					rd.patchableWords.add(bindOff);
					out.writeInt(setOff);
					out.writeInt(bindOff);
					total++;
				}
			}
			if (total != r.descriptors().size()) throw new IllegalStateException("descriptors() != sum of descriptors(type)");
			out.writeInt(r.pushConstants().size());
			for (SpvModule.Reflection.PushConstant pc : r.pushConstants()) out.writeInt(pc.size());
			out.flush();
			rd.serialized = bos.toByteArray();
			return rd;
		} finally {
			module.close();
		}
	}

	private static int probe(IntBuffer words, Runnable poke, Runnable restore) {
		int[] before = new int[words.limit()];
		words.get(0, before);
		poke.run();
		int found = -1;
		for (int i = 0; i < before.length; i++) {
			if (words.get(i) != before[i]) {
				if (found >= 0) throw new IllegalStateException("setter changed more than one word");
				found = i;
			}
		}
		restore.run();
		for (int i = 0; i < before.length; i++) if (words.get(i) != before[i]) throw new IllegalStateException("restore failed");
		if (found < 0) throw new IllegalStateException("setter changed no word");
		return found;
	}

	private static void writeType(DataOutputStream out, SpvModule.Reflection.Type t) throws IOException {
		out.writeInt(t.baseType());
		out.writeInt(t.dimensions());
		out.writeInt(t.vectorSize());
		int n = t.arrayDimensions();
		out.writeInt(n);
		for (int i = 0; i < n; i++) out.writeInt(t.arrayLength(i));
	}
}
