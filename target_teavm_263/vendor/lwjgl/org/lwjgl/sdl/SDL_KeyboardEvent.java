// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import org.lwjgl.system.Struct;

public class SDL_KeyboardEvent extends Struct<SDL_KeyboardEvent> {
	public static final int SIZEOF = 40;
	public static final int ALIGNOF = 8;
	public static final int TYPE = 0;
	public static final int RESERVED = 4;
	public static final int TIMESTAMP = 8;
	public static final int WINDOWID = 16;
	public static final int WHICH = 20;
	public static final int SCANCODE = 24;
	public static final int KEY = 28;
	public static final int MOD = 32;
	public static final int RAW = 34;
	public static final int DOWN = 36;
	public static final int REPEAT = 37;

	SDL_KeyboardEvent(long address) {
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

	public int scancode() {
		return memGetInt(address + SCANCODE);
	}

	public int key() {
		return memGetInt(address + KEY);
	}

	public short mod() {
		return memGetShort(address + MOD);
	}

	public short raw() {
		return memGetShort(address + RAW);
	}

	public boolean down() {
		return memGetByte(address + DOWN) != 0;
	}

	public boolean repeat() {
		return memGetByte(address + REPEAT) != 0;
	}

	public static SDL_KeyboardEvent create(long address) {
		return new SDL_KeyboardEvent(address);
	}
}
