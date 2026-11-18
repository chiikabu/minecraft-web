// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

public final class SDLTimer {
	public static final int SDL_MS_PER_SECOND = 1000;
	public static final int SDL_US_PER_SECOND = 1000000;
	public static final long SDL_NS_PER_SECOND = 1000000000L;
	public static final int SDL_NS_PER_MS = 1000000;
	public static final int SDL_NS_PER_US = 1000;

	private static final long START = System.nanoTime();

	private SDLTimer() {
	}

	public static long SDL_GetTicksNS() {
		return System.nanoTime() - START;
	}

	public static long SDL_GetTicks() {
		return SDL_GetTicksNS() / 1_000_000L;
	}

	public static long SDL_GetPerformanceCounter() {
		return System.nanoTime();
	}

	public static long SDL_GetPerformanceFrequency() {
		return 1_000_000_000L;
	}

	public static void SDL_Delay(int ms) {
	}

	public static void SDL_DelayNS(long ns) {
	}

	public static long SDL_SECONDS_TO_NS(long s) {
		return s * 1_000_000_000L;
	}

	public static long SDL_NS_TO_SECONDS(long ns) {
		return ns / 1_000_000_000L;
	}

	public static long SDL_MS_TO_NS(long ms) {
		return ms * 1_000_000L;
	}

	public static long SDL_NS_TO_MS(long ns) {
		return ns / 1_000_000L;
	}

	public static long SDL_US_TO_NS(long us) {
		return us * 1_000L;
	}

	public static long SDL_NS_TO_US(long ns) {
		return ns / 1_000L;
	}
}
