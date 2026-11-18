// minecraft-web by chiikabu (https://github.com/chiikabu)

package shaderbake263;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.serialization.JsonOps;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.StrictJsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

public final class Env {
	private Env() {
	}

	public static DeviceInfo browserDeviceInfo() {
		return new DeviceInfo(
			"WebKit WebGL", "WebKit", "OpenGL ES 3.0 (WebGL 2.0)", false, "OpenGL", 1.0F,
			new DeviceLimits(1, 256, 4096, Long.MAX_VALUE, 0, 8, 0),
			new DeviceFeatures(true, false, false, true, false, false, false, false),
			Set.of(),
			new HintsAndWorkarounds(false, false, false, false),
			DeviceType.INTEGRATED);
	}

	public static void installFakeDevice(DeviceInfo info) throws ReflectiveOperationException {
		Field f = RenderSystem.class.getDeclaredField("DEVICE");
		f.setAccessible(true);
		f.set(null, proxy(GpuDevice.class, info));
	}

	public static GpuDeviceBackend fakeBackend(DeviceInfo info) {
		return proxy(GpuDeviceBackend.class, info);
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, DeviceInfo info) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
			switch (m.getName()) {
				case "getDeviceInfo": return info;
				case "toString": return "Fake" + type.getSimpleName();
				case "hashCode": return System.identityHashCode(p);
				case "equals": return p == a[0];
				default: throw new UnsupportedOperationException("fake device: " + m);
			}
		});
	}

	public static ShaderManager.Configs loadConfigs(File clientJar) throws ReflectiveOperationException {
		MultiPackResourceManager manager = resourceManager(clientJar);
		Method m = ShaderManager.class.getDeclaredMethod("loadConfigs", net.minecraft.server.packs.resources.ResourceManager.class);
		m.setAccessible(true);
		return (ShaderManager.Configs) m.invoke(null, manager);
	}

	public static MultiPackResourceManager resourceManager(File clientJar) {
		PackLocationInfo info = new PackLocationInfo("vanilla", Component.literal("vanilla"), PackSource.BUILT_IN, Optional.empty());
		PackResources pack = (PackResources) new FilePackResources.FileResourcesSupplier(clientJar).openMetadata(info);
		return new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(pack));
	}

	public static ShaderSource lightShaderSource(ResourceManager manager) {
		return new ShaderSource() {
			@Override
			public String getShader(Identifier id, ShaderType type) {
				try {
					return manager.getResourceOrThrow(type.idConverter().idToFile(id)).readAllAsString();
				} catch (IOException e) {
					return null;
				}
			}

			@Override
			public ShaderSource.CachedIncludeSource getInclude(Identifier id) {
				throw new IllegalStateException("include requested in twin path: " + id);
			}

			@Override
			public void close() {
			}
		};
	}

	public static Map<Identifier, PostChainConfig> loadPostChains(ResourceManager manager) throws IOException {
		FileToIdConverter converter = FileToIdConverter.json("post_effect");
		Map<Identifier, PostChainConfig> out = new TreeMap<>();
		for (Map.Entry<Identifier, Resource> e : converter.listMatchingResources(manager).entrySet()) {
			try (Reader reader = e.getValue().openAsReader()) {
				JsonElement json = StrictJsonParser.parse(reader);
				out.put(converter.fileToId(e.getKey()), PostChainConfig.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(JsonSyntaxException::new));
			}
		}
		return out;
	}

	public static Map<String, RenderPipeline> allPipelines(ShaderManager.Configs configs) throws ReflectiveOperationException {
		return allPipelines(configs.postChains());
	}

	public static Map<String, RenderPipeline> allPipelines(Map<Identifier, PostChainConfig> postChains) throws ReflectiveOperationException {
		Map<String, RenderPipeline> out = new TreeMap<>();
		List<RenderPipeline> statics = staticPipelines();
		Map<String, Integer> seenLocations = new java.util.HashMap<>();
		for (RenderPipeline p : statics) {
			String loc = p.getLocation().toString();
			int n = seenLocations.merge(loc, 1, Integer::sum);
			out.put(n == 1 ? loc : loc + "#" + n, p);
		}
		for (Map.Entry<Identifier, PostChainConfig> chain : new TreeMap<>(postChains).entrySet()) {
			List<PostChainConfig.Pass> passes = chain.getValue().passes();
			for (int i = 0; i < passes.size(); i++) {
				RenderPipeline p = postPassPipeline(passes.get(i), chain.getKey().withSuffix("/" + i));
				out.put("post_effect:" + p.getLocation(), p);
			}
		}
		return out;
	}

	public static RenderPipeline postPassPipeline(PostChainConfig.Pass config, Identifier id) {
		RenderPipeline.Builder pipelineBuilder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withFragmentShader(config.fragmentShaderId())
			.withVertexShader(config.vertexShaderId())
			.withLocation(id);
		BindGroupLayout.Builder bindGroupLayoutBuilder = BindGroupLayout.builder();
		for (PostChainConfig.Input input : config.inputs()) {
			bindGroupLayoutBuilder.withUniform(input.samplerName() + "Sampler", UniformType.COMBINED_IMAGE_SAMPLER);
		}
		bindGroupLayoutBuilder.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
		for (String uniformGroupName : config.uniforms().keySet()) {
			bindGroupLayoutBuilder.withUniform(uniformGroupName, UniformType.UNIFORM_BUFFER);
		}
		pipelineBuilder.withBindGroupLayout(bindGroupLayoutBuilder.build());
		pipelineBuilder.withColorTargetState(ColorTargetState.DEFAULT);
		return pipelineBuilder.build();
	}

	public static List<RenderPipeline> staticPipelines() throws ReflectiveOperationException {
		List<RenderPipeline> out = new ArrayList<>();
		Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		addAll(out, seen, RenderPipelines.requiredPipelines());
		addAll(out, seen, RenderPipelines.optionalPipelines());
		for (Field field : RenderPipelines.class.getDeclaredFields()) {
			if (!Modifier.isStatic(field.getModifiers())) continue;
			field.setAccessible(true);
			Object v = field.get(null);
			if (v instanceof RenderPipeline p) {
				if (seen.add(p)) out.add(p);
			} else if (v instanceof net.minecraft.client.renderer.oit.OitPipelineSet set) {
				addAll(out, seen, List.of(set.depthBoundsPipeline(), set.transmittancePipeline(), set.accumulatePipeline()));
			}
		}
		return out;
	}

	private static void addAll(List<RenderPipeline> out, Set<Object> seen, Collection<RenderPipeline> ps) {
		for (RenderPipeline p : ps) if (seen.add(p)) out.add(p);
	}

	public static PipelineBuilder newPipelineBuilder(DeviceInfo info) {
		return new PipelineBuilder(fakeBackend(info));
	}
}
