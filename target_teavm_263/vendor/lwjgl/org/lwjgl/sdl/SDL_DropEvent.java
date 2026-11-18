// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import org.lwjgl.system.Struct;

public class SDL_DropEvent extends Struct<SDL_DropEvent> {
	public static final int SIZEOF = 48;
	public static final int ALIGNOF = 8;
	public static final int TYPE = 0;
	public static final int RESERVED = 4;
	public static final int TIMESTAMP = 8;
	public static final int WINDOWID = 16;
	public static final int X = 20;
	public static final int Y = 24;
	public static final int SOURCE = 32;
	public static final int DATA = 40;

	SDL_DropEvent(long address) {
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

	public float x() {
		return memGetFloat(address + X);
	}

	public float y() {
		return memGetFloat(address + Y);
	}

	public ByteBuffer source() {
		long pointer = memGetAddress(address + SOURCE);
		return pointer == NULL ? null : memByteBuffer(pointer, strlen(pointer));
	}

	public String sourceString() {
		long pointer = memGetAddress(address + SOURCE);
		return pointer == NULL ? null : memUTF8(pointer);
	}

	public ByteBuffer data() {
		long pointer = memGetAddress(address + DATA);
		return pointer == NULL ? null : memByteBuffer(pointer, strlen(pointer));
	}

	public String dataString() {
		long pointer = memGetAddress(address + DATA);
		return pointer == NULL ? null : memUTF8(pointer);
	}

	public static SDL_DropEvent create(long address) {
		return new SDL_DropEvent(address);
	}

	private static int strlen(long pointer) {
		int length = 0;
		while (memGetByte(pointer + length) != 0) {
			length++;
		}
		return length;
	}
}
