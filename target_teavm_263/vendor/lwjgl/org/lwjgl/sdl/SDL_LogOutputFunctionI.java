// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

@FunctionalInterface
public interface SDL_LogOutputFunctionI {
	void invoke(long userdata, int category, int priority, long message);
}
