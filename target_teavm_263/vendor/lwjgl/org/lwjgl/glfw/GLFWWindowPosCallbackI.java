// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWWindowPosCallbackI {
	void invoke(long window, int xpos, int ypos);
}
