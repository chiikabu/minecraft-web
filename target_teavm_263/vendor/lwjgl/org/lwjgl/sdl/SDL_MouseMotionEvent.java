// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import org.lwjgl.system.Struct;

public class SDL_MouseMotionEvent extends Struct<SDL_MouseMotionEvent> {
	public static final int SIZEOF = 48;
	public static final int ALIGNOF = 8;
	public static final int TYPE = 0;
	public static final int RESERVED = 4;
	public static final int TIMESTAMP = 8;
	public static final int WINDOWID = 16;
	public static final int WHICH = 20;
	public static final int STATE = 24;
	public static final int X = 28;
	public static final int Y = 32;
	public static final int XREL = 36;
	public static final int YREL = 40;

	SDL_MouseMotionEvent(long address) {
		super(address, null);
	}

	public int sizeof() {
		return SIZEOF;
	}

	public int type() {
		return memGetInt(address + TYPE);
	}

	public long timestamp() {
		return memGetLong(address + TIMESTAMP);
	}

	public int windowID() {
		return memGetInt(address + WINDOWID);
	}

	public int which() {
		return memGetInt(address + WHICH);
	}

	public int state() {
		return memGetInt(address + STATE);
	}

	public float x() {
		return memGetFloat(address + X);
	}

	public float y() {
		return memGetFloat(address + Y);
	}

	public float xrel() {
		return memGetFloat(address + XREL);
	}

	public float yrel() {
		return memGetFloat(address + YREL);
	}

	public static SDL_MouseMotionEvent create(long address) {
		return new SDL_MouseMotionEvent(address);
	}
}
