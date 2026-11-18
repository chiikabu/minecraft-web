// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import java.util.Arrays;

final class SDLEventQueue {
	private static final int MAX_EVENTS = 65535;

	private static int[] type = new int[64];
	private static long[] time = new long[64];
	private static int[] window = new int[64];
	private static int[] a = new int[64];
	private static int[] b = new int[64];
	private static int[] c = new int[64];
	private static int[] d = new int[64];
	private static float[] x = new float[64];
	private static float[] y = new float[64];
	private static float[] xr = new float[64];
	private static float[] yr = new float[64];
	private static String[] str = new String[64];
	private static int head;
	private static int count;
	private static boolean overflowLogged;

	private static ByteBuffer liveString;

	private SDLEventQueue() {
	}

	static boolean isEmpty() {
		return count == 0;
	}

	static int size() {
		return count;
	}

	private static int push(int eventType, int windowId) {
		if (count == MAX_EVENTS) {
			if (!overflowLogged) {
				overflowLogged = true;
				SDLLog.log(SDLLog.SDL_LOG_CATEGORY_INPUT, SDLLog.SDL_LOG_PRIORITY_WARN, "Event queue is full; dropping events");
			}
			return -1;
		}
		if (count == type.length) {
			grow();
		}
		int i = (head + count++) % type.length;
		type[i] = eventType;
		time[i] = SDLTimer.SDL_GetTicksNS();
		window[i] = windowId;
		a[i] = 0;
		b[i] = 0;
		c[i] = 0;
		d[i] = 0;
		x[i] = 0;
		y[i] = 0;
		xr[i] = 0;
		yr[i] = 0;
		str[i] = null;
		return i;
	}

	private static void grow() {
		int n = type.length;
		int[] order = new int[n];
		for (int k = 0; k < n; k++) {
			order[k] = (head + k) % n;
		}
		type = reorder(type, order);
		time = reorder(time, order);
		window = reorder(window, order);
		a = reorder(a, order);
		b = reorder(b, order);
		c = reorder(c, order);
		d = reorder(d, order);
		x = reorder(x, order);
		y = reorder(y, order);
		xr = reorder(xr, order);
		yr = reorder(yr, order);
		String[] s = new String[n * 2];
		for (int k = 0; k < n; k++) {
			s[k] = str[order[k]];
		}
		str = s;
		head = 0;
	}

	private static int[] reorder(int[] src, int[] order) {
		int[] dst = new int[src.length * 2];
		for (int k = 0; k < order.length; k++) {
			dst[k] = src[order[k]];
		}
		return dst;
	}

	private static long[] reorder(long[] src, int[] order) {
		long[] dst = new long[src.length * 2];
		for (int k = 0; k < order.length; k++) {
			dst[k] = src[order[k]];
		}
		return dst;
	}

	private static float[] reorder(float[] src, int[] order) {
		float[] dst = new float[src.length * 2];
		for (int k = 0; k < order.length; k++) {
			dst[k] = src[order[k]];
		}
		return dst;
	}

	static void pushSimple(int eventType) {
		push(eventType, 0);
	}

	static void pushWindow(int eventType, int windowId, int data1, int data2) {
		int i = push(eventType, windowId);
		if (i >= 0) {
			a[i] = data1;
			b[i] = data2;
		}
	}

	static void pushDisplay(int eventType, int displayId, int data1) {
		int i = push(eventType, 0);
		if (i >= 0) {
			a[i] = displayId;
			b[i] = data1;
		}
	}

	static void pushKey(boolean down, int windowId, int scancode, int keycode, int mod, boolean repeat) {
		int i = push(down ? SDLEvents.SDL_EVENT_KEY_DOWN : SDLEvents.SDL_EVENT_KEY_UP, windowId);
		if (i >= 0) {
			a[i] = scancode;
			b[i] = keycode;
			c[i] = mod & 0xFFFF;
			d[i] = (down ? 1 : 0) | (repeat ? 2 : 0);
		}
	}

	static void pushText(int windowId, String text) {
		int i = push(SDLEvents.SDL_EVENT_TEXT_INPUT, windowId);
		if (i >= 0) {
			str[i] = text;
		}
	}

	static void pushEditing(int windowId, String text, int start, int length) {
		int i = push(SDLEvents.SDL_EVENT_TEXT_EDITING, windowId);
		if (i >= 0) {
			str[i] = text;
			a[i] = start;
			b[i] = length;
		}
	}

	static void pushMotion(int windowId, int buttonState, float px, float py, float dx, float dy) {
		int i = push(SDLEvents.SDL_EVENT_MOUSE_MOTION, windowId);
		if (i >= 0) {
			a[i] = buttonState;
			x[i] = px;
			y[i] = py;
			xr[i] = dx;
			yr[i] = dy;
		}
	}

	static void pushButton(boolean down, int windowId, int button, int clicks, float px, float py) {
		int i = push(down ? SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN : SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP, windowId);
		if (i >= 0) {
			a[i] = button;
			b[i] = down ? 1 : 0;
			c[i] = Math.max(0, Math.min(255, clicks));
			x[i] = px;
			y[i] = py;
		}
	}

	static void pushWheel(int windowId, float wx, float wy, float mouseX, float mouseY, int ix, int iy) {
		int i = push(SDLEvents.SDL_EVENT_MOUSE_WHEEL, windowId);
		if (i >= 0) {
			x[i] = wx;
			y[i] = wy;
			xr[i] = mouseX;
			yr[i] = mouseY;
			a[i] = ix;
			b[i] = iy;
		}
	}

	private static void releaseString() {
		if (liveString != null) {
			memFree(liveString);
			liveString = null;
		}
	}

	private static long string(String value) {
		if (value == null) {
			return NULL;
		}
		liveString = memUTF8(value, true);
		return memAddress(liveString);
	}

	static boolean poll(long event) {
		releaseString();
		if (count == 0) {
			return false;
		}
		int i = head;
		head = (head + 1) % type.length;
		count--;
		write(i, event);
		str[i] = null;
		return true;
	}

	static void flush(int minType, int maxType) {
		int n = type.length;
		int kept = 0;
		for (int k = 0; k < count; k++) {
			int src = (head + k) % n;
			int t = type[src];
			if (t >= minType && t <= maxType) {
				str[src] = null;
				continue;
			}
			int dst = (head + kept) % n;
			if (dst != src) {
				type[dst] = type[src];
				time[dst] = time[src];
				window[dst] = window[src];
				a[dst] = a[src];
				b[dst] = b[src];
				c[dst] = c[src];
				d[dst] = d[src];
				x[dst] = x[src];
				y[dst] = y[src];
				xr[dst] = xr[src];
				yr[dst] = yr[src];
				str[dst] = str[src];
				str[src] = null;
			}
			kept++;
		}
		count = kept;
	}

	static boolean has(int minType, int maxType) {
		int n = type.length;
		for (int k = 0; k < count; k++) {
			int t = type[(head + k) % n];
			if (t >= minType && t <= maxType) {
				return true;
			}
		}
		return false;
	}

	private static void write(int i, long e) {
		int t = type[i];
		memSet(e, 0, SDL_Event.SIZEOF);
		memPutInt(e, t);
		memPutLong(e + 8, time[i]);
		if (t >= SDLEvents.SDL_EVENT_DISPLAY_FIRST && t <= SDLEvents.SDL_EVENT_DISPLAY_LAST) {
			memPutInt(e + 16, a[i]);
			memPutInt(e + 20, b[i]);
			return;
		}
		memPutInt(e + 16, window[i]);
		switch (t) {
			case SDLEvents.SDL_EVENT_KEY_DOWN:
			case SDLEvents.SDL_EVENT_KEY_UP:
				memPutInt(e + 24, a[i]);
				memPutInt(e + 28, b[i]);
				memPutShort(e + 32, (short) c[i]);
				memPutShort(e + 34, (short) a[i]);
				memPutByte(e + 36, (byte) (d[i] & 1));
				memPutByte(e + 37, (byte) ((d[i] >> 1) & 1));
				break;
			case SDLEvents.SDL_EVENT_TEXT_INPUT:
				memPutLong(e + 24, string(str[i]));
				break;
			case SDLEvents.SDL_EVENT_TEXT_EDITING:
				memPutLong(e + 24, string(str[i]));
				memPutInt(e + 32, a[i]);
				memPutInt(e + 36, b[i]);
				break;
			case SDLEvents.SDL_EVENT_MOUSE_MOTION:
				memPutInt(e + 20, SDLBrowser.MOUSE_ID);
				memPutInt(e + 24, a[i]);
				memPutFloat(e + 28, x[i]);
				memPutFloat(e + 32, y[i]);
				memPutFloat(e + 36, xr[i]);
				memPutFloat(e + 40, yr[i]);
				break;
			case SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN:
			case SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP:
				memPutInt(e + 20, SDLBrowser.MOUSE_ID);
				memPutByte(e + 24, (byte) a[i]);
				memPutByte(e + 25, (byte) b[i]);
				memPutByte(e + 26, (byte) c[i]);
				memPutFloat(e + 28, x[i]);
				memPutFloat(e + 32, y[i]);
				break;
			case SDLEvents.SDL_EVENT_MOUSE_WHEEL:
				memPutInt(e + 20, SDLBrowser.MOUSE_ID);
				memPutFloat(e + 24, x[i]);
				memPutFloat(e + 28, y[i]);
				memPutInt(e + 32, 0);
				memPutFloat(e + 36, xr[i]);
				memPutFloat(e + 40, yr[i]);
				memPutInt(e + 44, a[i]);
				memPutInt(e + 48, b[i]);
				break;
			default:
				memPutInt(e + 20, a[i]);
				memPutInt(e + 24, b[i]);
				break;
		}
	}

	static void clear() {
		Arrays.fill(str, null);
		head = 0;
		count = 0;
		releaseString();
	}
}
