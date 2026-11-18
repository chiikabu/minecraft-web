// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWScrollCallbackI {
	void invoke(long window, double xoffset, double yoffset);
}
