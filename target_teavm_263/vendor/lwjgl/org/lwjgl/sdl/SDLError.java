// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

public final class SDLError {
	private static String error = "";

	private SDLError() {
	}

	static void set(String message) {
		error = message == null ? "" : message;
	}

	public static boolean SDL_SetError(CharSequence fmt) {
		set(fmt == null ? "" : fmt.toString());
		return false;
	}

	public static boolean SDL_OutOfMemory() {
		set("Out of memory");
		return false;
	}

	public static String SDL_GetError() {
		return error;
	}

	public static boolean SDL_ClearError() {
		error = "";
		return true;
	}
}
