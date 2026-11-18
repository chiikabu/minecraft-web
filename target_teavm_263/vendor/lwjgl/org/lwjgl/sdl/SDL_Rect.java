// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;

public class SDL_Rect extends Struct<SDL_Rect> implements NativeResource {
	public static final int SIZEOF = 16;
	public static final int ALIGNOF = 4;
	public static final int X = 0;
	public static final int Y = 4;
	public static final int W = 8;
	public static final int H = 12;

	public SDL_Rect(ByteBuffer container) {
		super(memAddress(container), container);
	}

	SDL_Rect(long address, ByteBuffer container) {
		super(address, container);
	}

	public int sizeof() {
		return SIZEOF;
	}

	public int x() {
		return memGetInt(address + X);
	}

	public int y() {
		return memGetInt(address + Y);
	}

	public int w() {
		return memGetInt(address + W);
	}

	public int h() {
		return memGetInt(address + H);
	}

	public SDL_Rect x(int value) {
		memPutInt(address + X, value);
		return this;
	}

	public SDL_Rect y(int value) {
		memPutInt(address + Y, value);
		return this;
	}

	public SDL_Rect w(int value) {
		memPutInt(address + W, value);
		return this;
	}

	public SDL_Rect h(int value) {
		memPutInt(address + H, value);
		return this;
	}

	public SDL_Rect set(int x, int y, int w, int h) {
		return x(x).y(y).w(w).h(h);
	}

	public SDL_Rect set(SDL_Rect src) {
		memCopy(src.address(), address, SIZEOF);
		return this;
	}

	@Override
	public void free() {
		nmemFree(address);
	}

	public static SDL_Rect malloc() {
		return new SDL_Rect(nmemAlloc(SIZEOF), null);
	}

	public static SDL_Rect calloc() {
		return new SDL_Rect(nmemCalloc(1, SIZEOF), null);
	}

	public static SDL_Rect create(long address) {
		return new SDL_Rect(address, null);
	}

	public static SDL_Rect createSafe(long address) {
		return address == NULL ? null : new SDL_Rect(address, null);
	}

	public static SDL_Rect malloc(MemoryStack stack) {
		return new SDL_Rect(stack.nmalloc(ALIGNOF, SIZEOF), null);
	}

	public static SDL_Rect calloc(MemoryStack stack) {
		return new SDL_Rect(stack.ncalloc(ALIGNOF, 1, SIZEOF), null);
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

	public static class Buffer extends StructBuffer<SDL_Rect, Buffer> {
		public Buffer(long address, int capacity) {
			super(address, capacity);
		}

		@Override
		public int sizeof() {
			return SIZEOF;
		}

		@Override
		protected SDL_Rect newStruct(long address) {
			return new SDL_Rect(address, null);
		}

		public int x() {
			return memGetInt(address() + X);
		}

		public int y() {
			return memGetInt(address() + Y);
		}

		public int w() {
			return memGetInt(address() + W);
		}

		public int h() {
			return memGetInt(address() + H);
		}

		public Buffer x(int value) {
			memPutInt(address() + X, value);
			return this;
		}

		public Buffer y(int value) {
			memPutInt(address() + Y, value);
			return this;
		}

		public Buffer w(int value) {
			memPutInt(address() + W, value);
			return this;
		}

		public Buffer h(int value) {
			memPutInt(address() + H, value);
			return this;
		}
	}
}
