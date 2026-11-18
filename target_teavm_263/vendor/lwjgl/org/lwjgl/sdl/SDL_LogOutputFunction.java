// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

public abstract class SDL_LogOutputFunction implements SDL_LogOutputFunctionI {
	public static SDL_LogOutputFunction create(SDL_LogOutputFunctionI instance) {
		if (instance instanceof SDL_LogOutputFunction function) {
			return function;
		}
		return new SDL_LogOutputFunction() {
			@Override
			public void invoke(long userdata, int category, int priority, long message) {
				instance.invoke(userdata, category, priority, message);
			}
		};
	}

	public void free() {
	}

	public long address() {
		return 0L;
	}
}
