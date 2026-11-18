// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.nio.IntBuffer;

public final class SDLMessageBox {
	public static final int SDL_MESSAGEBOX_ERROR = 16;
	public static final int SDL_MESSAGEBOX_WARNING = 32;
	public static final int SDL_MESSAGEBOX_INFORMATION = 64;
	public static final int SDL_MESSAGEBOX_BUTTONS_LEFT_TO_RIGHT = 128;
	public static final int SDL_MESSAGEBOX_BUTTONS_RIGHT_TO_LEFT = 256;
	public static final int SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT = 1;
	public static final int SDL_MESSAGEBOX_BUTTON_ESCAPEKEY_DEFAULT = 2;
	public static final int SDL_MESSAGEBOX_COLOR_BACKGROUND = 0;
	public static final int SDL_MESSAGEBOX_COLOR_TEXT = 1;
	public static final int SDL_MESSAGEBOX_COLOR_BUTTON_BORDER = 2;
	public static final int SDL_MESSAGEBOX_COLOR_BUTTON_BACKGROUND = 3;
	public static final int SDL_MESSAGEBOX_COLOR_BUTTON_SELECTED = 4;
	public static final int SDL_MESSAGEBOX_COLOR_COUNT = 5;

	private SDLMessageBox() {
	}

	public static boolean SDL_ShowSimpleMessageBox(int flags, CharSequence title, CharSequence message, long window) {
		show(flags, title == null ? "" : title.toString(), message == null ? "" : message.toString());
		return true;
	}

	public static boolean SDL_ShowMessageBox(SDL_MessageBoxData messageboxdata, IntBuffer buttonid) {
		if (messageboxdata == null) {
			return SDLError.SDL_SetError("Parameter 'messageboxdata' is invalid");
		}
		String title = messageboxdata.titleString();
		String message = messageboxdata.messageString();
		show(messageboxdata.flags(), title == null ? "" : title, message == null ? "" : message);
		int choice = -1;
		SDL_MessageBoxButtonData.Buffer buttons = messageboxdata.buttons();
		if (buttons != null && buttons.remaining() > 0) {
			choice = buttons.get(0).buttonID();
			for (int i = 0; i < buttons.remaining(); i++) {
				SDL_MessageBoxButtonData button = buttons.get(i);
				if ((button.flags() & SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT) != 0) {
					choice = button.buttonID();
					break;
				}
			}
		}
		if (buttonid != null) {
			buttonid.put(buttonid.position(), choice);
		}
		return true;
	}

	private static void show(int flags, String title, String message) {
		boolean error = (flags & SDL_MESSAGEBOX_ERROR) != 0;
		System.err.println("[sdl-web] message box '" + title + "': " + message);
		SDLBrowser.consoleMessage(error, title, message);
	}
}
