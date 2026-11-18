// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import org.lwjgl.system.Struct;

public class SDL_MouseWheelEvent extends Struct<SDL_MouseWheelEvent> {
	public static final int SIZEOF = 56;
	public static final int ALIGNOF = 8;
	public static final int TYPE = 0;
	public static final int RESERVED = 4;
	public static final int TIMESTAMP = 8;
	public static final int WINDOWID = 16;
	public static final int WHICH = 20;
	public static final int X = 24;
	public static final int Y = 28;
	public static final int DIRECTION = 32;
	public static final int MOUSE_X = 36;
	public static final int MOUSE_Y = 40;
	public static final int INTEGER_X = 44;
	public static final int INTEGER_Y = 48;

	SDL_MouseWheelEvent(long address) {
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

	public float x() {
		return memGetFloat(address + X);
	}

	public float y() {
		return memGetFloat(address + Y);
	}

	public int direction() {
		return memGetInt(address + DIRECTION);
	}

	public float mouse_x() {
		return memGetFloat(address + MOUSE_X);
	}

	public float mouse_y() {
		return memGetFloat(address + MOUSE_Y);
	}

	public int integer_x() {
		return memGetInt(address + INTEGER_X);
	}

	public int integer_y() {
		return memGetInt(address + INTEGER_Y);
	}

	public static SDL_MouseWheelEvent create(long address) {
		return new SDL_MouseWheelEvent(address);
	}
}
