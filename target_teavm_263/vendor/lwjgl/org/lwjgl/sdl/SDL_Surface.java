// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import org.lwjgl.system.Struct;

public class SDL_Surface extends Struct<SDL_Surface> {
	public static final int SIZEOF = 48;
	public static final int ALIGNOF = 8;
	public static final int FLAGS = 0;
	public static final int FORMAT = 4;
	public static final int W = 8;
	public static final int H = 12;
	public static final int PITCH = 16;
	public static final int PIXELS = 24;
	public static final int REFCOUNT = 32;
	public static final int RESERVED = 40;

	public SDL_Surface(ByteBuffer container) {
		super(memAddress(container), container);
	}

	SDL_Surface(long address, ByteBuffer container) {
		super(address, container);
	}

	public int sizeof() {
		return SIZEOF;
	}

	public int flags() {
		return memGetInt(address + FLAGS);
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

	public int pitch() {
		return memGetInt(address + PITCH);
	}

	public ByteBuffer pixels() {
		long pixels = memGetAddress(address + PIXELS);
		return pixels == NULL ? null : memByteBuffer(pixels, pitch() * h());
	}

	public int refcount() {
		return memGetInt(address + REFCOUNT);
	}

	public SDL_Surface refcount(int value) {
		memPutInt(address + REFCOUNT, value);
		return this;
	}

	public static SDL_Surface create(long address) {
		return new SDL_Surface(address, null);
	}

	public static SDL_Surface createSafe(long address) {
		return address == NULL ? null : new SDL_Surface(address, null);
	}
}
