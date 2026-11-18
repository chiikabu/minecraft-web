// minecraft-web by chiikabu (https://github.com/chiikabu)

package shaderbake263;

import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.opengl.GlPipelineRecompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

public final class SelfCheck263 {
	private SelfCheck263() {
	}

	public static void main(String[] args) throws Exception {
		String jar = null, harness = null, twins = null, cp = null;
		int limit = 0;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--client-jar" -> jar = args[++i];
				case "--harness" -> harness = args[++i];
				case "--twins" -> twins = args[++i];
				case "--classpath" -> cp = args[++i];
				case "--limit" -> limit = Integer.parseInt(args[++i]);
				default -> throw new IllegalArgumentException(args[i]);
			}
		}
		Map<String, String> nativeGlsl = Worker.run(jar, true, limit);

		List<URL> urls = new ArrayList<>();
		urls.add(new File(twins).toURI().toURL());
		urls.add(new File(harness).toURI().toURL());
		for (String p : cp.split(File.pathSeparator)) urls.add(new File(p).toURI().toURL());
		Map<String, String> twinGlsl;
		try (URLClassLoader twinLoader = new URLClassLoader("twins", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
			Class<?> glsl = twinLoader.loadClass("com.mojang.renderpearl.frontend.shaders.GlslCompiler");
			if (glsl.getProtectionDomain().getCodeSource().getLocation().toString().endsWith(".jar")) {
				throw new IllegalStateException("twin GlslCompiler not shadowing the jar");
			}
			Class<?> worker = twinLoader.loadClass(Worker.class.getName());
			Method run = worker.getMethod("run", String.class, boolean.class, int.class);
			@SuppressWarnings("unchecked")
			Map<String, String> result = (Map<String, String>) run.invoke(null, jar, false, limit);
			twinGlsl = result;
		}

		int pipelines = (int) nativeGlsl.keySet().stream().map(k -> k.substring(0, k.lastIndexOf('|'))).distinct().count();
		int mismatches = 0;
		for (Map.Entry<String, String> e : nativeGlsl.entrySet()) {
			String twin = twinGlsl.get(e.getKey());
			if (twin == null) {
				System.out.println("[selfcheck] MISSING in twin path: " + e.getKey());
				mismatches++;
			} else if (!twin.equals(e.getValue())) {
				System.out.println("[selfcheck] GLSL DIFFERS: " + e.getKey());
				mismatches++;
			}
		}
		for (String k : twinGlsl.keySet()) {
			if (!nativeGlsl.containsKey(k)) {
				System.out.println("[selfcheck] extra in twin path: " + k);
				mismatches++;
			}
		}
		System.out.println("[selfcheck] compared " + nativeGlsl.size() + " stages across " + pipelines + " pipelines: "
			+ (mismatches == 0 ? "OK, twin GLSL identical to native" : mismatches + " MISMATCHES"));
		System.exit(mismatches == 0 && pipelines >= 3 ? 0 : 1);
	}

	public static final class Worker {
		private Worker() {
		}

		public static Map<String, String> run(String jar, boolean nativeSide, int limit) throws Exception {
			DeviceInfo info = Env.browserDeviceInfo();
			Env.installFakeDevice(info);
			MultiPackResourceManager manager = Env.resourceManager(new File(jar));
			ShaderSource source;
			Map<String, RenderPipeline> pipelines;
			if (nativeSide) {
				var configs = Env.loadConfigs(new File(jar));
				source = configs;
				pipelines = Env.allPipelines(configs);
			} else {
				source = Env.lightShaderSource(manager);
				pipelines = Env.allPipelines(Env.loadPostChains(manager));
			}
			PipelineBuilder builder = Env.newPipelineBuilder(info);
			GlPipelineRecompiler recompiler = new GlPipelineRecompiler(null, info.features().shaderDrawParameters());
			Map<String, String> out = new LinkedHashMap<>();
			int n = 0;
			for (Map.Entry<String, RenderPipeline> e : pipelines.entrySet()) {
				if (limit > 0 && n++ >= limit) break;
				List<PipelineRunner.StageResult> stages = PipelineRunner.run(builder, recompiler, e.getValue(), source);
				if (stages == null) {
					out.put(e.getKey() + "|FAILED", "");
					continue;
				}
				for (PipelineRunner.StageResult st : stages) out.put(e.getKey() + "|" + st.type(), st.glsl());
			}
			return out;
		}
	}
}
