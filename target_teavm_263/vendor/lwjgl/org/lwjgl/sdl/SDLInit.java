// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.util.HashMap;
import java.util.Map;

public final class SDLInit {
	public static final int SDL_INIT_AUDIO = 16;
	public static final int SDL_INIT_VIDEO = 32;
	public static final int SDL_INIT_JOYSTICK = 512;
	public static final int SDL_INIT_HAPTIC = 4096;
	public static final int SDL_INIT_GAMEPAD = 8192;
	public static final int SDL_INIT_EVENTS = 16384;
	public static final int SDL_INIT_SENSOR = 32768;
	public static final int SDL_INIT_CAMERA = 65536;
	public static final int SDL_APP_CONTINUE = 0;
	public static final int SDL_APP_SUCCESS = 1;
	public static final int SDL_APP_FAILURE = 2;
	public static final String SDL_PROP_APP_METADATA_NAME_STRING = "SDL.app.metadata.name";
	public static final String SDL_PROP_APP_METADATA_VERSION_STRING = "SDL.app.metadata.version";
	public static final String SDL_PROP_APP_METADATA_IDENTIFIER_STRING = "SDL.app.metadata.identifier";
	public static final String SDL_PROP_APP_METADATA_CREATOR_STRING = "SDL.app.metadata.creator";
	public static final String SDL_PROP_APP_METADATA_COPYRIGHT_STRING = "SDL.app.metadata.copyright";
	public static final String SDL_PROP_APP_METADATA_URL_STRING = "SDL.app.metadata.url";
	public static final String SDL_PROP_APP_METADATA_TYPE_STRING = "SDL.app.metadata.type";

	private static final Map<String, String> metadata = new HashMap<>();
	private static int initialized;

	private SDLInit() {
	}

	public static boolean SDL_Init(int flags) {
		return SDL_InitSubSystem(flags);
	}

	public static boolean SDL_InitSubSystem(int flags) {
		if ((flags & (SDL_INIT_VIDEO | SDL_INIT_EVENTS)) != 0) {
			SDLBrowser.init();
			flags |= SDL_INIT_EVENTS;
		}
		initialized |= flags;
		return true;
	}

	public static void SDL_QuitSubSystem(int flags) {
		initialized &= ~flags;
		if ((initialized & (SDL_INIT_VIDEO | SDL_INIT_EVENTS)) == 0 && SDLBrowser.isActive()) {
			SDLBrowser.quit();
		}
	}

	public static int SDL_WasInit(int flags) {
		return flags == 0 ? initialized : initialized & flags;
	}

	public static void SDL_Quit() {
		initialized = 0;
		SDLBrowser.quit();
	}

	public static boolean SDL_IsMainThread() {
		return true;
	}

	public static boolean SDL_SetAppMetadata(CharSequence appname, CharSequence appversion, CharSequence appidentifier) {
		SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_NAME_STRING, appname);
		SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_VERSION_STRING, appversion);
		SDL_SetAppMetadataProperty(SDL_PROP_APP_METADATA_IDENTIFIER_STRING, appidentifier);
		return true;
	}

	public static boolean SDL_SetAppMetadataProperty(CharSequence name, CharSequence value) {
		if (name == null) {
			return SDLError.SDL_SetError("Parameter 'name' is invalid");
		}
		if (value == null) {
			metadata.remove(name.toString());
		} else {
			metadata.put(name.toString(), value.toString());
		}
		return true;
	}

	public static String SDL_GetAppMetadataProperty(CharSequence name) {
		return name == null ? null : metadata.get(name.toString());
	}
}
