// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.ajax.XMLHttpRequest;

public final class WebShaderSource implements ShaderSource {
	private final Map<String, String> cache = new HashMap<>();

	@Override
	public @Nullable String getShader(Identifier id, ShaderType type) {
		String ext = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
		return fetch("assets/" + id.getNamespace() + "/shaders/" + id.getPath() + ext);
	}

	@Override
	public ShaderSource.@Nullable CachedIncludeSource getInclude(Identifier id) {
		return null;
	}

	@Override
	public void close() {
	}

	private String fetch(String url) {
		if (cache.containsKey(url)) {
			return cache.get(url);
		}
		byte[] bundled = WebPackResources.rawAsset(url);
		String result = bundled != null ? new String(bundled, StandardCharsets.UTF_8) : fetchOverHttp(url);
		cache.put(url, result);
		return result;
	}

	@Nullable
	private static String fetchOverHttp(String url) {
		XMLHttpRequest xhr = WebPackResources.getSync(url, false);
		return xhr == null ? null : xhr.getResponseText();
	}
}
