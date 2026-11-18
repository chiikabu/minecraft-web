// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWCursorEnterCallbackI {
	void invoke(long window, boolean entered);
}
