// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWWindowFocusCallbackI {
	void invoke(long window, boolean focused);
}
