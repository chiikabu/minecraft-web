// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.util.tinyfd;

public final class TinyFileDialogs {
	private TinyFileDialogs() {
	}

	public static int tinyfd_messageBox(CharSequence title, CharSequence message, CharSequence dialogType, CharSequence iconType, int defaultButton) {
		System.out.println("[glfw-web] messageBox(" + iconType + ") " + title + ": " + message);
		return 1;
	}

	public static String tinyfd_getGlobalChar(CharSequence name) {
		return null;
	}
}
