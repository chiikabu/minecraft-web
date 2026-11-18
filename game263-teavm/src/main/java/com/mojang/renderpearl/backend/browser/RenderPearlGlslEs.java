// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.renderpearl.backend.browser;

import com.mojang.blaze3d.webgl.GlslEs;

public final class RenderPearlGlslEs {
	private RenderPearlGlslEs() {
	}

	public static String translate(final String source, final boolean fragmentShader) {
		return GlslEs.translate(source, fragmentShader);
	}
}
