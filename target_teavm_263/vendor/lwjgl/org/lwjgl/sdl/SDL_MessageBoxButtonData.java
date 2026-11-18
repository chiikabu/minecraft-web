// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;

public class SDL_MessageBoxButtonData extends Struct<SDL_MessageBoxButtonData> implements NativeResource {
	public static final int SIZEOF = 16;
	public static final int ALIGNOF = 8;
	public static final int FLAGS = 0;
	public static final int BUTTONID = 4;
	public static final int TEXT = 8;

	public SDL_MessageBoxButtonData(ByteBuffer container) {
		super(memAddress(container), container);
	}

	SDL_MessageBoxButtonData(long address, ByteBuffer container) {
		super(address, container);
	}

	public int sizeof() {
		return SIZEOF;
	}

	public int flags() {
		return memGetInt(address + FLAGS);
	}

	public int buttonID() {
		return memGetInt(address + BUTTONID);
	}

	public String textString() {
		long text = memGetAddress(address + TEXT);
		return text == NULL ? null : memUTF8(text);
	}

	public SDL_MessageBoxButtonData flags(int value) {
		memPutInt(address + FLAGS, value);
		return this;
	}

	public SDL_MessageBoxButtonData buttonID(int value) {
		memPutInt(address + BUTTONID, value);
		return this;
	}

	public SDL_MessageBoxButtonData text(ByteBuffer value) {
		memPutLong(address + TEXT, memAddress(value));
		return this;
	}

	@Override
	public void free() {
		nmemFree(address);
	}

	public static SDL_MessageBoxButtonData malloc() {
		return new SDL_MessageBoxButtonData(nmemAlloc(SIZEOF), null);
	}

	public static SDL_MessageBoxButtonData calloc() {
		return new SDL_MessageBoxButtonData(nmemCalloc(1, SIZEOF), null);
	}

	public static SDL_MessageBoxButtonData create(long address) {
		return new SDL_MessageBoxButtonData(address, null);
	}

	public static SDL_MessageBoxButtonData malloc(MemoryStack stack) {
		return new SDL_MessageBoxButtonData(stack.nmalloc(ALIGNOF, SIZEOF), null);
	}

	public static SDL_MessageBoxButtonData calloc(MemoryStack stack) {
		return new SDL_MessageBoxButtonData(stack.ncalloc(ALIGNOF, 1, SIZEOF), null);
	}

	public static Buffer malloc(int capacity) {
		return new Buffer(nmemAlloc((long) capacity * SIZEOF), capacity);
	}

	public static Buffer calloc(int capacity) {
		return new Buffer(nmemCalloc(capacity, SIZEOF), capacity);
	}

	public static Buffer create(long address, int capacity) {
		return new Buffer(address, capacity);
	}

	public static Buffer malloc(int capacity, MemoryStack stack) {
		return new Buffer(stack.nmalloc(ALIGNOF, capacity * SIZEOF), capacity);
	}

	public static Buffer calloc(int capacity, MemoryStack stack) {
		return new Buffer(stack.ncalloc(ALIGNOF, capacity, SIZEOF), capacity);
	}

	public static class Buffer extends StructBuffer<SDL_MessageBoxButtonData, Buffer> {
		public Buffer(long address, int capacity) {
			super(address, capacity);
		}

		@Override
		public int sizeof() {
			return SIZEOF;
		}

		@Override
		protected SDL_MessageBoxButtonData newStruct(long address) {
			return new SDL_MessageBoxButtonData(address, null);
		}
	}
}
