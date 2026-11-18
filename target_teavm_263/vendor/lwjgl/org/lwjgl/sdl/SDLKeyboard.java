// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.system.MemoryUtil;

public final class SDLKeyboard {
	public static final int SDL_TEXTINPUT_TYPE_TEXT = 0;
	public static final int SDL_TEXTINPUT_TYPE_TEXT_NAME = 1;
	public static final int SDL_TEXTINPUT_TYPE_TEXT_EMAIL = 2;
	public static final int SDL_TEXTINPUT_TYPE_TEXT_USERNAME = 3;
	public static final int SDL_TEXTINPUT_TYPE_TEXT_PASSWORD_HIDDEN = 4;
	public static final int SDL_TEXTINPUT_TYPE_TEXT_PASSWORD_VISIBLE = 5;
	public static final int SDL_TEXTINPUT_TYPE_NUMBER = 6;
	public static final int SDL_TEXTINPUT_TYPE_NUMBER_PASSWORD_HIDDEN = 7;
	public static final int SDL_TEXTINPUT_TYPE_NUMBER_PASSWORD_VISIBLE = 8;
	public static final int SDL_CAPITALIZE_NONE = 0;
	public static final int SDL_CAPITALIZE_SENTENCES = 1;
	public static final int SDL_CAPITALIZE_WORDS = 2;
	public static final int SDL_CAPITALIZE_LETTERS = 3;
	public static final String SDL_PROP_TEXTINPUT_TYPE_NUMBER = "SDL.textinput.type";
	public static final String SDL_PROP_TEXTINPUT_CAPITALIZATION_NUMBER = "SDL.textinput.capitalization";
	public static final String SDL_PROP_TEXTINPUT_AUTOCORRECT_BOOLEAN = "SDL.textinput.autocorrect";
	public static final String SDL_PROP_TEXTINPUT_MULTILINE_BOOLEAN = "SDL.textinput.multiline";
	public static final String SDL_PROP_TEXTINPUT_ANDROID_INPUTTYPE_NUMBER = "SDL.textinput.android.inputtype";

	private SDLKeyboard() {
	}

	public static boolean SDL_HasKeyboard() {
		return true;
	}

	public static IntBuffer SDL_GetKeyboards() {
		IntBuffer ids = MemoryUtil.memAllocInt(2);
		ids.put(0, SDLBrowser.KEYBOARD_ID);
		ids.put(1, 0);
		ids.limit(1);
		return ids;
	}

	public static String SDL_GetKeyboardNameForID(int instanceId) {
		return instanceId == SDLBrowser.KEYBOARD_ID ? "Browser keyboard" : null;
	}

	public static long SDL_GetKeyboardFocus() {
		return SDLBrowser.canvasWindowHandle();
	}

	public static ByteBuffer SDL_GetKeyboardState() {
		return SDLBrowser.keyboardState();
	}

	public static void SDL_ResetKeyboard() {
		SDLBrowser.resetKeyboard();
	}

	public static short SDL_GetModState() {
		return (short) SDLBrowser.modState();
	}

	public static void SDL_SetModState(short modstate) {
		SDLBrowser.setModState(modstate & 0xFFFF);
	}

	public static int SDL_GetKeyFromScancode(int scancode, short modstate, boolean key_event) {
		return SDLKeymap.keycode(scancode);
	}

	public static int SDL_GetScancodeFromKey(int key, ShortBuffer modstate) {
		if (modstate != null) {
			modstate.put(modstate.position(), (short) 0);
		}
		return SDLKeymap.scancodeForKey(key);
	}

	public static String SDL_GetScancodeName(int scancode) {
		return SDLKeymap.scancodeName(scancode);
	}

	public static int SDL_GetScancodeFromName(CharSequence name) {
		if (name != null) {
			String value = name.toString();
			for (int scancode = 1; scancode < SDLKeymap.SCANCODE_COUNT; scancode++) {
				if (value.equalsIgnoreCase(SDLKeymap.scancodeName(scancode))) {
					return scancode;
				}
			}
		}
		SDLError.set("Unknown scancode name");
		return 0;
	}

	public static String SDL_GetKeyName(int key) {
		return SDLKeymap.keyName(key);
	}

	public static int SDL_GetKeyFromName(CharSequence name) {
		if (name == null || name.length() == 0) {
			return 0;
		}
		String value = name.toString();
		if (value.codePointCount(0, value.length()) == 1) {
			return Character.toLowerCase(value.codePointAt(0));
		}
		int scancode = SDL_GetScancodeFromName(value);
		return scancode == 0 ? 0 : SDLKeymap.keycode(scancode);
	}

	public static boolean SDL_StartTextInput(long window) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		SDLBrowser.startTextInput();
		return true;
	}

	public static boolean SDL_StartTextInputWithProperties(long window, int props) {
		return SDL_StartTextInput(window);
	}

	public static boolean SDL_TextInputActive(long window) {
		return SDLBrowser.textInputActive();
	}

	public static boolean SDL_StopTextInput(long window) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		SDLBrowser.stopTextInput();
		return true;
	}

	public static boolean SDL_ClearComposition(long window) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetTextInputArea(long window, SDL_Rect.Buffer rect, int cursor) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		if (rect == null) {
			SDLBrowser.setTextInputArea(0, 0, 0, 0, cursor);
		} else {
			SDLBrowser.setTextInputArea(rect.x(), rect.y(), rect.w(), rect.h(), cursor);
		}
		return true;
	}

	public static boolean SDL_HasScreenKeyboardSupport() {
		return false;
	}

	public static boolean SDL_ScreenKeyboardShown(long window) {
		return false;
	}
}
