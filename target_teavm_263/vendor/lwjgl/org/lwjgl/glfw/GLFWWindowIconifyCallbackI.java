// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWWindowIconifyCallbackI {
	void invoke(long window, boolean iconified);
}
