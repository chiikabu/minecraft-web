// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWMouseButtonCallbackI {
	void invoke(long window, int button, int action, int mods);
}
