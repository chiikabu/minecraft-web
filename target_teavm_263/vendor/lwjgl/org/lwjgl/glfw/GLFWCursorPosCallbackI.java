// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWCursorPosCallbackI {
	void invoke(long window, double xpos, double ypos);
}
