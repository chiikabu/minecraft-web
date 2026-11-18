// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

public final class Callbacks {
	private Callbacks() {
	}

	public static void glfwFreeCallbacks(long window) {
		GLFW.freeCallbacks(window);
	}
}
