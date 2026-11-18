// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.lwjgl.system.MemoryUtil;

public final class SDLLog {
	public static final int SDL_LOG_CATEGORY_APPLICATION = 0;
	public static final int SDL_LOG_CATEGORY_ERROR = 1;
	public static final int SDL_LOG_CATEGORY_ASSERT = 2;
	public static final int SDL_LOG_CATEGORY_SYSTEM = 3;
	public static final int SDL_LOG_CATEGORY_AUDIO = 4;
	public static final int SDL_LOG_CATEGORY_VIDEO = 5;
	public static final int SDL_LOG_CATEGORY_RENDER = 6;
	public static final int SDL_LOG_CATEGORY_INPUT = 7;
	public static final int SDL_LOG_CATEGORY_TEST = 8;
	public static final int SDL_LOG_CATEGORY_GPU = 9;
	public static final int SDL_LOG_CATEGORY_RESERVED2 = 10;
	public static final int SDL_LOG_CATEGORY_RESERVED3 = 11;
	public static final int SDL_LOG_CATEGORY_RESERVED4 = 12;
	public static final int SDL_LOG_CATEGORY_RESERVED5 = 13;
	public static final int SDL_LOG_CATEGORY_RESERVED6 = 14;
	public static final int SDL_LOG_CATEGORY_RESERVED7 = 15;
	public static final int SDL_LOG_CATEGORY_RESERVED8 = 16;
	public static final int SDL_LOG_CATEGORY_RESERVED9 = 17;
	public static final int SDL_LOG_CATEGORY_RESERVED10 = 18;
	public static final int SDL_LOG_CATEGORY_CUSTOM = 19;
	public static final int SDL_LOG_PRIORITY_INVALID = 0;
	public static final int SDL_LOG_PRIORITY_TRACE = 1;
	public static final int SDL_LOG_PRIORITY_VERBOSE = 2;
	public static final int SDL_LOG_PRIORITY_DEBUG = 3;
	public static final int SDL_LOG_PRIORITY_INFO = 4;
	public static final int SDL_LOG_PRIORITY_WARN = 5;
	public static final int SDL_LOG_PRIORITY_ERROR = 6;
	public static final int SDL_LOG_PRIORITY_CRITICAL = 7;
	public static final int SDL_LOG_PRIORITY_COUNT = 8;

	private static final int CATEGORIES = 32;
	private static final int[] priorities = new int[CATEGORIES];
	private static SDL_LogOutputFunctionI output;
	private static long outputUserdata;

	static {
		SDL_ResetLogPriorities();
	}

	private SDLLog() {
	}

	public static void SDL_SetLogPriorities(int priority) {
		Arrays.fill(priorities, priority);
	}

	public static void SDL_SetLogPriority(int category, int priority) {
		if (category >= 0 && category < CATEGORIES) {
			priorities[category] = priority;
		}
	}

	public static int SDL_GetLogPriority(int category) {
		return category >= 0 && category < CATEGORIES ? priorities[category] : SDL_LOG_PRIORITY_ERROR;
	}

	public static void SDL_ResetLogPriorities() {
		Arrays.fill(priorities, SDL_LOG_PRIORITY_ERROR);
		priorities[SDL_LOG_CATEGORY_APPLICATION] = SDL_LOG_PRIORITY_INFO;
		priorities[SDL_LOG_CATEGORY_ASSERT] = SDL_LOG_PRIORITY_WARN;
		priorities[SDL_LOG_CATEGORY_TEST] = SDL_LOG_PRIORITY_VERBOSE;
	}

	public static void SDL_SetLogOutputFunction(SDL_LogOutputFunctionI callback, long userdata) {
		output = callback;
		outputUserdata = userdata;
	}

	public static void SDL_Log(CharSequence fmt) {
		log(SDL_LOG_CATEGORY_APPLICATION, SDL_LOG_PRIORITY_INFO, fmt == null ? "" : fmt.toString());
	}

	static void log(int category, int priority, String message) {
		if (priority < SDL_GetLogPriority(category)) {
			return;
		}
		SDL_LogOutputFunctionI callback = output;
		if (callback == null) {
			System.out.println("[sdl-web] " + message);
			return;
		}
		ByteBuffer text = MemoryUtil.memUTF8(message, true);
		try {
			callback.invoke(outputUserdata, category, priority, MemoryUtil.memAddress(text));
		} finally {
			MemoryUtil.memFree(text);
		}
	}
}
