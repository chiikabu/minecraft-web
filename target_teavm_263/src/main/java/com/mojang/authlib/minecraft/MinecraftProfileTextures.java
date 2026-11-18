// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.minecraft;

import com.mojang.authlib.SignatureState;

public record MinecraftProfileTextures(MinecraftProfileTexture skin, MinecraftProfileTexture cape,
		MinecraftProfileTexture elytra, SignatureState signatureState) {
	public static final MinecraftProfileTextures EMPTY = new MinecraftProfileTextures(null, null, null,
			SignatureState.SIGNED);
}
