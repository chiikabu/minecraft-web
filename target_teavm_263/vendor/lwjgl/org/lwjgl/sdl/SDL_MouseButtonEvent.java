// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import org.lwjgl.system.Struct;

public class SDL_MouseButtonEvent extends Struct<SDL_MouseButtonEvent> {
	public static final int SIZEOF = 40;
	public static final int ALIGNOF = 8;
	public static final int TYPE = 0;
	public static final int RESERVED = 4;
	public static final int TIMESTAMP = 8;
	public static final int WINDOWID = 16;
	public static final int WHICH = 20;
	public static final int BUTTON = 24;
	public static final int DOWN = 25;
	public static final int CLICKS = 26;
	public static final int X = 28;
	public static final int Y = 32;

	SDL_MouseButtonEvent(long address) {
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

	public byte button() {
		return memGetByte(address + BUTTON);
	}

	public boolean down() {
		return memGetByte(address + DOWN) != 0;
	}

	public byte clicks() {
		return memGetByte(address + CLICKS);
	}

	public float x() {
		return memGetFloat(address + X);
	}

	public float y() {
		return memGetFloat(address + Y);
	}

	public static SDL_MouseButtonEvent create(long address) {
		return new SDL_MouseButtonEvent(address);
	}
}
