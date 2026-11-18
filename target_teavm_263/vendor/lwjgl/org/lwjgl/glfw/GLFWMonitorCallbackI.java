// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWMonitorCallbackI {
	void invoke(long monitor, int event);
}
