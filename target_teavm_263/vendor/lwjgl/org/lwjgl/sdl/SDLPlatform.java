// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

public final class SDLPlatform {
	private SDLPlatform() {
	}

	public static String SDL_GetPlatform() {
		return "Web";
	}
}
