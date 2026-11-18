// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import org.lwjgl.system.Struct;

public class SDL_PixelFormatDetails extends Struct<SDL_PixelFormatDetails> {
	public static final int SIZEOF = 32;
	public static final int ALIGNOF = 4;
	public static final int FORMAT = 0;
	public static final int BITS_PER_PIXEL = 4;
	public static final int BYTES_PER_PIXEL = 5;
	public static final int PADDING = 6;
	public static final int RMASK = 8;
	public static final int GMASK = 12;
	public static final int BMASK = 16;
	public static final int AMASK = 20;
	public static final int RBITS = 24;
	public static final int GBITS = 25;
	public static final int BBITS = 26;
	public static final int ABITS = 27;
	public static final int RSHIFT = 28;
	public static final int GSHIFT = 29;
	public static final int BSHIFT = 30;
	public static final int ASHIFT = 31;

	public SDL_PixelFormatDetails(ByteBuffer container) {
		super(memAddress(container), container);
	}

	SDL_PixelFormatDetails(long address, ByteBuffer container) {
		super(address, container);
	}

	public int sizeof() {
		return SIZEOF;
	}

	public int format() {
		return memGetInt(address + FORMAT);
	}

	public byte bits_per_pixel() {
		return memGetByte(address + BITS_PER_PIXEL);
	}

	public byte bytes_per_pixel() {
		return memGetByte(address + BYTES_PER_PIXEL);
	}

	public int Rmask() {
		return memGetInt(address + RMASK);
	}

	public int Gmask() {
		return memGetInt(address + GMASK);
	}

	public int Bmask() {
		return memGetInt(address + BMASK);
	}

	public int Amask() {
		return memGetInt(address + AMASK);
	}

	public byte Rbits() {
		return memGetByte(address + RBITS);
	}

	public byte Gbits() {
		return memGetByte(address + GBITS);
	}

	public byte Bbits() {
		return memGetByte(address + BBITS);
	}

	public byte Abits() {
		return memGetByte(address + ABITS);
	}

	public byte Rshift() {
		return memGetByte(address + RSHIFT);
	}

	public byte Gshift() {
		return memGetByte(address + GSHIFT);
	}

	public byte Bshift() {
		return memGetByte(address + BSHIFT);
	}

	public byte Ashift() {
		return memGetByte(address + ASHIFT);
	}

	public static SDL_PixelFormatDetails create(long address) {
		return new SDL_PixelFormatDetails(address, null);
	}

	public static SDL_PixelFormatDetails createSafe(long address) {
		return address == NULL ? null : new SDL_PixelFormatDetails(address, null);
	}
}
