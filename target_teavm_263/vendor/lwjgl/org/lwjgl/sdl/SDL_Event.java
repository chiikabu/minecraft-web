// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;
import org.lwjgl.system.Struct;

public class SDL_Event extends Struct<SDL_Event> implements NativeResource {
	public static final int SIZEOF = 128;
	public static final int ALIGNOF = 8;
	public static final int TYPE = 0;

	private SDL_CommonEvent common;
	private SDL_DisplayEvent display;
	private SDL_WindowEvent window;
	private SDL_KeyboardEvent key;
	private SDL_TextEditingEvent edit;
	private SDL_TextInputEvent text;
	private SDL_MouseMotionEvent motion;
	private SDL_MouseButtonEvent button;
	private SDL_MouseWheelEvent wheel;
	private SDL_DropEvent drop;

	public SDL_Event(ByteBuffer container) {
		super(memAddress(container), container);
	}

	SDL_Event(long address, ByteBuffer container) {
		super(address, container);
	}

	public int sizeof() {
		return SIZEOF;
	}

	public int type() {
		return memGetInt(address + TYPE);
	}

	public SDL_Event type(int value) {
		memPutInt(address + TYPE, value);
		return this;
	}

	public SDL_CommonEvent common() {
		return common != null ? common : (common = new SDL_CommonEvent(address));
	}

	public SDL_DisplayEvent display() {
		return display != null ? display : (display = new SDL_DisplayEvent(address));
	}

	public SDL_WindowEvent window() {
		return window != null ? window : (window = new SDL_WindowEvent(address));
	}

	public SDL_KeyboardEvent key() {
		return key != null ? key : (key = new SDL_KeyboardEvent(address));
	}

	public SDL_TextEditingEvent edit() {
		return edit != null ? edit : (edit = new SDL_TextEditingEvent(address));
	}

	public SDL_TextInputEvent text() {
		return text != null ? text : (text = new SDL_TextInputEvent(address));
	}

	public SDL_MouseMotionEvent motion() {
		return motion != null ? motion : (motion = new SDL_MouseMotionEvent(address));
	}

	public SDL_MouseButtonEvent button() {
		return button != null ? button : (button = new SDL_MouseButtonEvent(address));
	}

	public SDL_MouseWheelEvent wheel() {
		return wheel != null ? wheel : (wheel = new SDL_MouseWheelEvent(address));
	}

	public SDL_DropEvent drop() {
		return drop != null ? drop : (drop = new SDL_DropEvent(address));
	}

	@Override
	public void free() {
		nmemFree(address);
	}

	public static SDL_Event malloc() {
		return new SDL_Event(nmemAlloc(SIZEOF), null);
	}

	public static SDL_Event calloc() {
		return new SDL_Event(nmemCalloc(1, SIZEOF), null);
	}

	public static SDL_Event create(long address) {
		return new SDL_Event(address, null);
	}

	public static SDL_Event createSafe(long address) {
		return address == NULL ? null : new SDL_Event(address, null);
	}

	public static SDL_Event malloc(MemoryStack stack) {
		return new SDL_Event(stack.nmalloc(ALIGNOF, SIZEOF), null);
	}

	public static SDL_Event calloc(MemoryStack stack) {
		return new SDL_Event(stack.ncalloc(ALIGNOF, 1, SIZEOF), null);
	}
}
