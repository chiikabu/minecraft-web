// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWFramebufferSizeCallbackI {
	void invoke(long window, int width, int height);
}
