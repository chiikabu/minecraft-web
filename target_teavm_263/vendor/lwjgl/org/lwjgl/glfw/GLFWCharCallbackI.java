// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWCharCallbackI {
	void invoke(long window, int codepoint);
}
