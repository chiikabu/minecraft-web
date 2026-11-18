// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

public final class SDLClipboard {
	private SDLClipboard() {
	}

	public static boolean SDL_SetClipboardText(CharSequence text) {
		return SDLBrowser.setClipboard(text == null ? "" : text.toString());
	}

	public static String SDL_GetClipboardText() {
		return SDLBrowser.clipboard();
	}

	public static boolean SDL_HasClipboardText() {
		return !SDLBrowser.clipboard().isEmpty();
	}

	public static boolean SDL_SetPrimarySelectionText(CharSequence text) {
		return true;
	}

	public static String SDL_GetPrimarySelectionText() {
		return "";
	}

	public static boolean SDL_HasPrimarySelectionText() {
		return false;
	}

	public static boolean SDL_ClearClipboardData() {
		return SDLBrowser.setClipboard("");
	}
}
