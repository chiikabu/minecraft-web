// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

public final class SDLStdinc {
	public static final float SDL_FLT_EPSILON = 1.1920929E-7f;
	public static final int SDL_INVALID_UNICODE_CODEPOINT = 65533;

	private SDLStdinc() {
	}

	public static ByteBuffer SDL_malloc(long size) {
		return MemoryUtil.memByteBuffer(MemoryUtil.nmemAlloc(size), (int) size);
	}

	public static ByteBuffer SDL_calloc(long nmemb, long size) {
		return MemoryUtil.memByteBuffer(MemoryUtil.nmemCalloc(nmemb, size), (int) (nmemb * size));
	}

	public static void SDL_free(ByteBuffer mem) {
		MemoryUtil.memFree(mem);
	}

	public static void SDL_free(IntBuffer mem) {
		MemoryUtil.memFree(mem);
	}

	public static void SDL_free(LongBuffer mem) {
		MemoryUtil.memFree(mem);
	}

	public static void SDL_free(PointerBuffer mem) {
		if (mem != null) {
			MemoryUtil.nmemFree(mem.address0());
		}
	}

	public static void nSDL_free(long mem) {
		MemoryUtil.nmemFree(mem);
	}

	public static int SDL_FOURCC(byte a, byte b, byte c, byte d) {
		return (a & 0xFF) | (b & 0xFF) << 8 | (c & 0xFF) << 16 | (d & 0xFF) << 24;
	}
}
