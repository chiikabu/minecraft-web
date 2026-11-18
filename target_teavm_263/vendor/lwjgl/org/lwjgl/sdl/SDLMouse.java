// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryUtil;

public final class SDLMouse {
	public static final int SDL_SYSTEM_CURSOR_DEFAULT = 0;
	public static final int SDL_SYSTEM_CURSOR_TEXT = 1;
	public static final int SDL_SYSTEM_CURSOR_WAIT = 2;
	public static final int SDL_SYSTEM_CURSOR_CROSSHAIR = 3;
	public static final int SDL_SYSTEM_CURSOR_PROGRESS = 4;
	public static final int SDL_SYSTEM_CURSOR_NWSE_RESIZE = 5;
	public static final int SDL_SYSTEM_CURSOR_NESW_RESIZE = 6;
	public static final int SDL_SYSTEM_CURSOR_EW_RESIZE = 7;
	public static final int SDL_SYSTEM_CURSOR_NS_RESIZE = 8;
	public static final int SDL_SYSTEM_CURSOR_MOVE = 9;
	public static final int SDL_SYSTEM_CURSOR_NOT_ALLOWED = 10;
	public static final int SDL_SYSTEM_CURSOR_POINTER = 11;
	public static final int SDL_SYSTEM_CURSOR_NW_RESIZE = 12;
	public static final int SDL_SYSTEM_CURSOR_N_RESIZE = 13;
	public static final int SDL_SYSTEM_CURSOR_NE_RESIZE = 14;
	public static final int SDL_SYSTEM_CURSOR_E_RESIZE = 15;
	public static final int SDL_SYSTEM_CURSOR_SE_RESIZE = 16;
	public static final int SDL_SYSTEM_CURSOR_S_RESIZE = 17;
	public static final int SDL_SYSTEM_CURSOR_SW_RESIZE = 18;
	public static final int SDL_SYSTEM_CURSOR_W_RESIZE = 19;
	public static final int SDL_SYSTEM_CURSOR_COUNT = 20;
	public static final int SDL_MOUSEWHEEL_NORMAL = 0;
	public static final int SDL_MOUSEWHEEL_FLIPPED = 1;
	public static final int SDL_BUTTON_LEFT = 1;
	public static final int SDL_BUTTON_MIDDLE = 2;
	public static final int SDL_BUTTON_RIGHT = 3;
	public static final int SDL_BUTTON_X1 = 4;
	public static final int SDL_BUTTON_X2 = 5;
	public static final int SDL_BUTTON_LMASK = 1 << (SDL_BUTTON_LEFT - 1);
	public static final int SDL_BUTTON_MMASK = 1 << (SDL_BUTTON_MIDDLE - 1);
	public static final int SDL_BUTTON_RMASK = 1 << (SDL_BUTTON_RIGHT - 1);
	public static final int SDL_BUTTON_X1MASK = 1 << (SDL_BUTTON_X1 - 1);
	public static final int SDL_BUTTON_X2MASK = 1 << (SDL_BUTTON_X2 - 1);

	private SDLMouse() {
	}

	public static boolean SDL_HasMouse() {
		return true;
	}

	public static IntBuffer SDL_GetMice() {
		IntBuffer ids = MemoryUtil.memAllocInt(2);
		ids.put(0, SDLBrowser.MOUSE_ID);
		ids.put(1, 0);
		ids.limit(1);
		return ids;
	}

	public static String SDL_GetMouseNameForID(int instanceId) {
		return instanceId == SDLBrowser.MOUSE_ID ? "Browser mouse" : null;
	}

	public static long SDL_GetMouseFocus() {
		return SDLBrowser.canvasWindowHandle();
	}

	public static int SDL_GetMouseState(FloatBuffer x, FloatBuffer y) {
		return SDLBrowser.mouseState(x, y);
	}

	public static int SDL_GetGlobalMouseState(FloatBuffer x, FloatBuffer y) {
		return SDLBrowser.mouseState(x, y);
	}

	public static int SDL_GetRelativeMouseState(FloatBuffer x, FloatBuffer y) {
		int buttons = SDLBrowser.mouseState(null, null);
		if (x != null) {
			x.put(x.position(), 0.0F);
		}
		if (y != null) {
			y.put(y.position(), 0.0F);
		}
		return buttons;
	}

	public static void SDL_WarpMouseInWindow(long window, float x, float y) {
		SDLBrowser.warpMouse(x, y);
	}

	public static boolean SDL_WarpMouseGlobal(float x, float y) {
		SDLBrowser.warpMouse(x, y);
		return true;
	}

	public static boolean SDL_SetWindowRelativeMouseMode(long window, boolean enabled) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		if (SDLBrowser.isCanvasWindow(window)) {
			SDLBrowser.setRelativeMode(enabled);
		}
		return true;
	}

	public static boolean SDL_GetWindowRelativeMouseMode(long window) {
		return SDLBrowser.isCanvasWindow(window) && SDLBrowser.relativeMode();
	}

	public static boolean SDL_CaptureMouse(boolean enabled) {
		return true;
	}

	public static long SDL_CreateSystemCursor(int id) {
		return SDLBrowser.systemCursor(id);
	}

	public static boolean SDL_SetCursor(long cursor) {
		SDLBrowser.setCursor(cursor);
		return true;
	}

	public static long SDL_GetCursor() {
		return SDLBrowser.cursor();
	}

	public static long SDL_GetDefaultCursor() {
		return SDLBrowser.systemCursor(SDL_SYSTEM_CURSOR_DEFAULT);
	}

	public static void SDL_DestroyCursor(long cursor) {
	}

	public static boolean SDL_ShowCursor() {
		SDLBrowser.showCursor(true);
		return true;
	}

	public static boolean SDL_HideCursor() {
		SDLBrowser.showCursor(false);
		return true;
	}

	public static boolean SDL_CursorVisible() {
		return SDLBrowser.cursorVisible();
	}
}
