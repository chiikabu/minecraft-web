// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.util.Locale;

public final class SDLMisc {
	private SDLMisc() {
	}

	public static boolean SDL_OpenURL(CharSequence url) {
		if (url == null) {
			return SDLError.SDL_SetError("Parameter 'url' is invalid");
		}
		String value = url.toString();
		String lower = value.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("mailto:")) {
			return SDLError.SDL_SetError("Opening '" + value + "' is not supported in the browser");
		}
		if (!SDLBrowser.openWindow(value)) {
			return SDLError.SDL_SetError("The browser blocked opening a new window");
		}
		return true;
	}
}
