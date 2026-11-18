// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SDLSurface {
	public static final int SDL_SURFACE_PREALLOCATED = 1;
	public static final int SDL_SURFACE_LOCK_NEEDED = 2;
	public static final int SDL_SURFACE_LOCKED = 4;
	public static final int SDL_SURFACE_SIMD_ALIGNED = 8;
	public static final int SDL_SCALEMODE_INVALID = -1;
	public static final int SDL_SCALEMODE_NEAREST = 0;
	public static final int SDL_SCALEMODE_LINEAR = 1;
	public static final int SDL_SCALEMODE_PIXELART = 2;
	public static final int SDL_FLIP_NONE = 0;
	public static final int SDL_FLIP_HORIZONTAL = 1;
	public static final int SDL_FLIP_VERTICAL = 2;
	public static final int SDL_FLIP_HORIZONTAL_AND_VERTICAL = 3;
	public static final String SDL_PROP_SURFACE_SDR_WHITE_POINT_FLOAT = "SDL.surface.SDR_white_point";
	public static final String SDL_PROP_SURFACE_HDR_HEADROOM_FLOAT = "SDL.surface.HDR_headroom";
	public static final String SDL_PROP_SURFACE_TONEMAP_OPERATOR_STRING = "SDL.surface.tonemap";
	public static final String SDL_PROP_SURFACE_HOTSPOT_X_NUMBER = "SDL.surface.hotspot.x";
	public static final String SDL_PROP_SURFACE_HOTSPOT_Y_NUMBER = "SDL.surface.hotspot.y";
	public static final String SDL_PROP_SURFACE_ROTATION_FLOAT = "SDL.surface.rotation";

	private static final Map<Long, List<Long>> alternates = new HashMap<>();
	private static final Map<Long, Long> ownedPixels = new HashMap<>();

	private SDLSurface() {
	}

	private static SDL_Surface newSurface(int width, int height, int format, long pixels, int pitch, int flags) {
		long s = nmemCalloc(1, SDL_Surface.SIZEOF);
		memPutInt(s + SDL_Surface.FLAGS, flags);
		memPutInt(s + SDL_Surface.FORMAT, format);
		memPutInt(s + SDL_Surface.W, width);
		memPutInt(s + SDL_Surface.H, height);
		memPutInt(s + SDL_Surface.PITCH, pitch);
		memPutLong(s + SDL_Surface.PIXELS, pixels);
		memPutInt(s + SDL_Surface.REFCOUNT, 1);
		return SDL_Surface.create(s);
	}

	public static SDL_Surface SDL_CreateSurface(int width, int height, int format) {
		if (width < 0 || height < 0) {
			SDLError.set("Parameter 'width'/'height' is invalid");
			return null;
		}
		int bytes = Math.max(1, SDLPixels.SDL_BYTESPERPIXEL(format));
		int pitch = width * bytes;
		long pixels = nmemCalloc(1, (long) pitch * height);
		SDL_Surface surface = newSurface(width, height, format, pixels, pitch, 0);
		ownedPixels.put(surface.address(), pixels);
		return surface;
	}

	public static SDL_Surface SDL_CreateSurfaceFrom(int width, int height, int format, ByteBuffer pixels, int pitch) {
		if (width < 0 || height < 0) {
			SDLError.set("Parameter 'width'/'height' is invalid");
			return null;
		}
		return newSurface(width, height, format, pixels == null ? NULL : memAddress(pixels), pitch,
			SDL_SURFACE_PREALLOCATED);
	}

	public static void SDL_DestroySurface(SDL_Surface surface) {
		if (surface == null) {
			return;
		}
		long s = surface.address();
		int refs = memGetInt(s + SDL_Surface.REFCOUNT) - 1;
		memPutInt(s + SDL_Surface.REFCOUNT, refs);
		if (refs > 0) {
			return;
		}
		SDL_RemoveSurfaceAlternateImages(surface);
		Long pixels = ownedPixels.remove(s);
		if (pixels != null) {
			nmemFree(pixels);
		}
		nmemFree(s);
	}

	public static boolean SDL_AddSurfaceAlternateImage(SDL_Surface surface, SDL_Surface image) {
		if (surface == null || image == null) {
			return SDLError.SDL_SetError("Parameter 'surface'/'image' is invalid");
		}
		image.refcount(image.refcount() + 1);
		alternates.computeIfAbsent(surface.address(), key -> new ArrayList<>()).add(image.address());
		return true;
	}

	public static boolean SDL_SurfaceHasAlternateImages(SDL_Surface surface) {
		return surface != null && alternates.containsKey(surface.address());
	}

	public static void SDL_RemoveSurfaceAlternateImages(SDL_Surface surface) {
		if (surface == null) {
			return;
		}
		List<Long> images = alternates.remove(surface.address());
		if (images != null) {
			for (long image : images) {
				SDL_DestroySurface(SDL_Surface.create(image));
			}
		}
	}

	public static boolean SDL_LockSurface(SDL_Surface surface) {
		return surface != null;
	}

	public static void SDL_UnlockSurface(SDL_Surface surface) {
	}

	public static boolean SDL_MUSTLOCK(SDL_Surface surface) {
		return false;
	}
}
