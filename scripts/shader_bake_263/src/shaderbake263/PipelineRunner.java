// minecraft-web by chiikabu (https://github.com/chiikabu)

package shaderbake263;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlPipelineRecompiler;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PipelineRunner {
	private PipelineRunner() {
	}

	public record StageResult(String name, String type, byte[] finalSpv, String glsl) {
	}

	private static final Method GENERATE;

	static {
		try {
			GENERATE = PipelineBuilder.class.getDeclaredMethod("generateBackendCreateInfo",
				RenderPipeline.class, ShaderSource.class, ReferenceArrayList.class, Object2IntOpenHashMap.class);
			GENERATE.setAccessible(true);
		} catch (NoSuchMethodException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	public static List<StageResult> run(PipelineBuilder builder, GlPipelineRecompiler recompiler, RenderPipeline pipeline, ShaderSource source)
		throws ReflectiveOperationException {
		ReferenceArrayList<BackendRenderPipeline.CreateInfo.Shader> shaders = new ReferenceArrayList<>();
		BackendRenderPipeline.CreateInfo info = (BackendRenderPipeline.CreateInfo) GENERATE.invoke(
			builder, pipeline, source, shaders, new Object2IntOpenHashMap<String>());
		if (info == null) return null;
		try {
			Map<BackendRenderPipeline.CreateInfo.Shader, String> glsl = recompiler.decompileShaders(info);
			if (glsl == null) return null;
			List<StageResult> out = new ArrayList<>();
			for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
				ByteBuffer spv = shader.module().spv();
				byte[] bytes = new byte[spv.limit()];
				for (int i = 0; i < bytes.length; i++) bytes[i] = spv.get(i);
				out.add(new StageResult(shader.name(), shader.module().type().name(), bytes, glsl.get(shader)));
			}
			return out;
		} finally {
			for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) shader.module().close();
		}
	}
}
