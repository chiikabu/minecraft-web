// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;
import org.lwjgl.system.Struct;

public class SDL_MessageBoxData extends Struct<SDL_MessageBoxData> implements NativeResource {
	public static final int SIZEOF = 56;
	public static final int ALIGNOF = 8;
	public static final int FLAGS = 0;
	public static final int WINDOW = 8;
	public static final int TITLE = 16;
	public static final int MESSAGE = 24;
	public static final int NUMBUTTONS = 32;
	public static final int BUTTONS = 40;
	public static final int COLORSCHEME = 48;

	public SDL_MessageBoxData(ByteBuffer container) {
		super(memAddress(container), container);
	}

	SDL_MessageBoxData(long address, ByteBuffer container) {
		super(address, container);
	}

	public int sizeof() {
		return SIZEOF;
	}

	public int flags() {
		return memGetInt(address + FLAGS);
	}

	public long window() {
		return memGetAddress(address + WINDOW);
	}

	public String titleString() {
		long title = memGetAddress(address + TITLE);
		return title == NULL ? null : memUTF8(title);
	}

	public String messageString() {
		long message = memGetAddress(address + MESSAGE);
		return message == NULL ? null : memUTF8(message);
	}

	public int numbuttons() {
		return memGetInt(address + NUMBUTTONS);
	}

	public SDL_MessageBoxButtonData.Buffer buttons() {
		long buttons = memGetAddress(address + BUTTONS);
		return buttons == NULL ? null : SDL_MessageBoxButtonData.create(buttons, numbuttons());
	}

	public SDL_MessageBoxData flags(int value) {
		memPutInt(address + FLAGS, value);
		return this;
	}

	public SDL_MessageBoxData window(long value) {
		memPutLong(address + WINDOW, value);
		return this;
	}

	public SDL_MessageBoxData title(ByteBuffer value) {
		memPutLong(address + TITLE, value == null ? NULL : memAddress(value));
		return this;
	}

	public SDL_MessageBoxData message(ByteBuffer value) {
		memPutLong(address + MESSAGE, value == null ? NULL : memAddress(value));
		return this;
	}

	public SDL_MessageBoxData buttons(SDL_MessageBoxButtonData.Buffer value) {
		memPutLong(address + BUTTONS, value == null ? NULL : value.address());
		memPutInt(address + NUMBUTTONS, value == null ? 0 : value.remaining());
		return this;
	}

	@Override
	public void free() {
		nmemFree(address);
	}

	public static SDL_MessageBoxData malloc() {
		return new SDL_MessageBoxData(nmemAlloc(SIZEOF), null);
	}

	public static SDL_MessageBoxData calloc() {
		return new SDL_MessageBoxData(nmemCalloc(1, SIZEOF), null);
	}

	public static SDL_MessageBoxData create(long address) {
		return new SDL_MessageBoxData(address, null);
	}

	public static SDL_MessageBoxData malloc(MemoryStack stack) {
		return new SDL_MessageBoxData(stack.nmalloc(ALIGNOF, SIZEOF), null);
	}

	public static SDL_MessageBoxData calloc(MemoryStack stack) {
		return new SDL_MessageBoxData(stack.ncalloc(ALIGNOF, 1, SIZEOF), null);
	}
}
