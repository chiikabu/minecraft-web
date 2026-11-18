// minecraft-web by chiikabu (https://github.com/chiikabu)

package shaderbake263;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.ShaderDefines;

public final class RecordingGlslCompiler extends GlslCompiler {
	public record Call(String key, ShaderType type, byte[] spv) {
	}

	public final List<Call> calls = new ArrayList<>();

	public RecordingGlslCompiler(boolean isZeroToOne, boolean shaderDrawParameters) {
		super(isZeroToOne, shaderDrawParameters);
	}

	@Override
	public SpvModule compileToSpv(String name, String source, ShaderType type, ShaderDefines defines, ShaderSource shaderSource)
		throws ShaderCompileException {
		SpvModule module = super.compileToSpv(name, source, type, defines, shaderSource);
		ByteBuffer spv = module.spv();
		byte[] bytes = new byte[spv.limit()];
		for (int i = 0; i < bytes.length; i++) bytes[i] = spv.get(i);
		this.calls.add(new Call(ShaderBake263.spvKey(name, type, defines), type, bytes));
		return module;
	}
}
