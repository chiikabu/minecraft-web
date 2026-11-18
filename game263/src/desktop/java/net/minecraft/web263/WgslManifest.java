// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.web263;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;

public final class WgslManifest {
	private WgslManifest() {
	}

	public static void main(String[] args) throws Exception {
		List<Pipeline> pipelines = new ArrayList<>();
		for (Object pipeline : staticPipelines()) {
			pipelines.add(readPipeline(pipeline));
		}
		pipelines.sort((a, b) -> {
			int location = a.location().compareTo(b.location());
			return location != 0 || !a.renderPearl() || !b.renderPearl()
				? location : variantKey(a).compareTo(variantKey(b));
		});
		StringBuilder out = new StringBuilder("[\n");
		for (int i = 0; i < pipelines.size(); i++) {
			Pipeline p = pipelines.get(i);
			Map<String, String> values = new TreeMap<>(p.defines().values());
			TreeSet<String> flags = new TreeSet<>(p.defines().flags());
			out.append("  {\"location\": ").append(q(p.location()))
				.append(", \"vertex\": ").append(q(p.vertex()))
				.append(", \"fragment\": ").append(q(p.fragment()))
				.append(", \"values\": {");
			boolean first = true;
			for (Map.Entry<String, String> e : values.entrySet()) {
				out.append(first ? "" : ", ").append(q(e.getKey())).append(": ").append(q(e.getValue()));
				first = false;
			}
			out.append("}, \"flags\": [");
			first = true;
			for (String f : flags) {
				out.append(first ? "" : ", ").append(q(f));
				first = false;
			}
			out.append("]");
			if (p.renderPearl()) out.append(", \"backend\": \"renderpearl\"");
			out.append("}").append(i + 1 < pipelines.size() ? ",\n" : "\n");
		}
		out.append("]\n");
		Files.writeString(Path.of(args[0]), out.toString(), StandardCharsets.UTF_8);
		System.out.println("wgsl manifest: " + pipelines.size() + " static pipelines -> " + args[0]);
	}

	private record Pipeline(String location, String vertex, String fragment, ShaderDefines defines, boolean renderPearl) {}

	private static Collection<?> staticPipelines() throws ReflectiveOperationException {
		try {
			return (Collection<?>) RenderPipelines.class.getMethod("getStaticPipelines").invoke(null);
		} catch (NoSuchMethodException legacy) {
			List<Object> pipelines = new ArrayList<>();
			Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			addAll(pipelines, seen, (Collection<?>) RenderPipelines.class.getMethod("requiredPipelines").invoke(null));
			addAll(pipelines, seen, (Collection<?>) RenderPipelines.class.getMethod("optionalPipelines").invoke(null));
			for (var field : RenderPipelines.class.getFields()) {
				if (Modifier.isStatic(field.getModifiers()) && field.getType().getSimpleName().equals("RenderPipeline")) {
					Object pipeline = field.get(null);
					if (pipeline != null && seen.add(pipeline)) pipelines.add(pipeline);
				}
			}
			return pipelines;
		}
	}

	private static void addAll(List<Object> output, Set<Object> seen, Collection<?> pipelines) {
		for (Object pipeline : pipelines) {
			if (seen.add(pipeline)) output.add(pipeline);
		}
	}

	private static String variantKey(Pipeline pipeline) {
		return pipeline.vertex() + "\0" + pipeline.fragment() + "\0"
			+ new TreeMap<>(pipeline.defines().values()) + "\0" + new TreeSet<>(pipeline.defines().flags());
	}

	private static Pipeline readPipeline(Object pipeline) throws ReflectiveOperationException {
		String location = call(pipeline, "getLocation").toString();
		ShaderDefines defines = (ShaderDefines) call(pipeline, "getShaderDefines");
		try {
			Map<?, ?> shaders = (Map<?, ?>) call(pipeline, "getShaders");
			if (shaders.size() != 2) throw new IllegalArgumentException("Unsupported shader stages: " + location);
			return new Pipeline(location, shader(shaders, "VERTEX"), shader(shaders, "FRAGMENT"), defines, true);
		} catch (NoSuchMethodException legacy) {
			return new Pipeline(location, call(pipeline, "getVertexShader").toString(),
				call(pipeline, "getFragmentShader").toString(), defines, false);
		}
	}

	private static String shader(Map<?, ?> shaders, String stage) {
		return shaders.entrySet().stream()
			.filter(e -> e.getKey() instanceof Enum<?> key && key.name().equals(stage))
			.map(e -> e.getValue().toString()).findFirst().orElseThrow();
	}

	private static Object call(Object target, String method) throws ReflectiveOperationException {
		return target.getClass().getMethod(method).invoke(target);
	}

	private static String q(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
