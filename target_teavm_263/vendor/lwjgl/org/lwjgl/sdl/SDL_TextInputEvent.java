// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import org.lwjgl.system.Struct;

public class SDL_TextInputEvent extends Struct<SDL_TextInputEvent> {
	public static final int SIZEOF = 32;
	public static final int ALIGNOF = 8;
	public static final int TYPE = 0;
	public static final int RESERVED = 4;
	public static final int TIMESTAMP = 8;
	public static final int WINDOWID = 16;
	public static final int TEXT = 24;

	SDL_TextInputEvent(long address) {
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

	public ByteBuffer text() {
		long pointer = memGetAddress(address + TEXT);
		return pointer == NULL ? null : memByteBuffer(pointer, strlen(pointer));
	}

	public String textString() {
		long pointer = memGetAddress(address + TEXT);
		return pointer == NULL ? null : memUTF8(pointer);
	}

	public static SDL_TextInputEvent create(long address) {
		return new SDL_TextInputEvent(address);
	}

	private static int strlen(long pointer) {
		int length = 0;
		while (memGetByte(pointer + length) != 0) {
			length++;
		}
		return length;
	}
}
