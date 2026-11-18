// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;
import org.lwjgl.system.Struct;

public class SDL_DisplayMode extends Struct<SDL_DisplayMode> implements NativeResource {
	public static final int SIZEOF = 40;
	public static final int ALIGNOF = 8;
	public static final int DISPLAYID = 0;
	public static final int FORMAT = 4;
	public static final int W = 8;
	public static final int H = 12;
	public static final int PIXEL_DENSITY = 16;
	public static final int REFRESH_RATE = 20;
	public static final int REFRESH_RATE_NUMERATOR = 24;
	public static final int REFRESH_RATE_DENOMINATOR = 28;
	public static final int INTERNAL = 32;

	public SDL_DisplayMode(ByteBuffer container) {
		super(memAddress(container), container);
	}

	SDL_DisplayMode(long address, ByteBuffer container) {
		super(address, container);
	}

	public int sizeof() {
		return SIZEOF;
	}

	public int displayID() {
		return memGetInt(address + DISPLAYID);
	}

	public int format() {
		return memGetInt(address + FORMAT);
	}

	public int w() {
		return memGetInt(address + W);
	}

	public int h() {
		return memGetInt(address + H);
	}

	public float pixel_density() {
		return memGetFloat(address + PIXEL_DENSITY);
	}

	public float refresh_rate() {
		return memGetFloat(address + REFRESH_RATE);
	}

	public int refresh_rate_numerator() {
		return memGetInt(address + REFRESH_RATE_NUMERATOR);
	}

	public int refresh_rate_denominator() {
		return memGetInt(address + REFRESH_RATE_DENOMINATOR);
	}

	public SDL_DisplayMode displayID(int value) {
		memPutInt(address + DISPLAYID, value);
		return this;
	}

	public SDL_DisplayMode format(int value) {
		memPutInt(address + FORMAT, value);
		return this;
	}

	public SDL_DisplayMode w(int value) {
		memPutInt(address + W, value);
		return this;
	}

	public SDL_DisplayMode h(int value) {
		memPutInt(address + H, value);
		return this;
	}

	public SDL_DisplayMode pixel_density(float value) {
		memPutFloat(address + PIXEL_DENSITY, value);
		return this;
	}

	public SDL_DisplayMode refresh_rate(float value) {
		memPutFloat(address + REFRESH_RATE, value);
		return this;
	}

	public SDL_DisplayMode refresh_rate_numerator(int value) {
		memPutInt(address + REFRESH_RATE_NUMERATOR, value);
		return this;
	}

	public SDL_DisplayMode refresh_rate_denominator(int value) {
		memPutInt(address + REFRESH_RATE_DENOMINATOR, value);
		return this;
	}

	public SDL_DisplayMode set(SDL_DisplayMode src) {
		memCopy(src.address(), address, SIZEOF);
		return this;
	}

	@Override
	public void free() {
		nmemFree(address);
	}

	public static SDL_DisplayMode malloc() {
		return new SDL_DisplayMode(nmemAlloc(SIZEOF), null);
	}

	public static SDL_DisplayMode calloc() {
		return new SDL_DisplayMode(nmemCalloc(1, SIZEOF), null);
	}

	public static SDL_DisplayMode create(long address) {
		return new SDL_DisplayMode(address, null);
	}

	public static SDL_DisplayMode createSafe(long address) {
		return address == NULL ? null : new SDL_DisplayMode(address, null);
	}

	public static SDL_DisplayMode malloc(MemoryStack stack) {
		return new SDL_DisplayMode(stack.nmalloc(ALIGNOF, SIZEOF), null);
	}

	public static SDL_DisplayMode calloc(MemoryStack stack) {
		return new SDL_DisplayMode(stack.ncalloc(ALIGNOF, 1, SIZEOF), null);
	}
}
