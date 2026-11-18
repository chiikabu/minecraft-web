// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

@FunctionalInterface
public interface GLFWPreeditCallbackI {
	void invoke(long window, int preeditCount, long preeditString, int blockCount, long blockSizes, int focusedBlock, int caret);
}
