// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import org.lwjgl.system.Struct;

public class SDL_DisplayEvent extends Struct<SDL_DisplayEvent> {
	public static final int SIZEOF = 32;
	public static final int ALIGNOF = 8;
	public static final int TYPE = 0;
	public static final int RESERVED = 4;
	public static final int TIMESTAMP = 8;
	public static final int DISPLAYID = 16;
	public static final int DATA1 = 20;
	public static final int DATA2 = 24;

	SDL_DisplayEvent(long address) {
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

	public int displayID() {
		return memGetInt(address + DISPLAYID);
	}

	public int data1() {
		return memGetInt(address + DATA1);
	}

	public int data2() {
		return memGetInt(address + DATA2);
	}

	public static SDL_DisplayEvent create(long address) {
		return new SDL_DisplayEvent(address);
	}
}
