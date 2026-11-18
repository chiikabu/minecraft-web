// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.lwjgl.glfw.BrowserTouch;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.KeyboardEvent;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.events.WheelEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;

public final class SDLBrowser {
	static final int MOUSE_ID = 1;
	static final int KEYBOARD_ID = 1;
	static final int DISPLAY_ID = 1;
	private static final int DEFAULT_WIDTH = 854;
	private static final int DEFAULT_HEIGHT = 480;
	private static final long HANDLE_BASE = 0x5D1_0000L;

	static final int KMOD_LSHIFT = 0x0001;
	static final int KMOD_RSHIFT = 0x0002;
	static final int KMOD_LCTRL = 0x0040;
	static final int KMOD_RCTRL = 0x0080;
	static final int KMOD_LALT = 0x0100;
	static final int KMOD_RALT = 0x0200;
	static final int KMOD_LGUI = 0x0400;
	static final int KMOD_RGUI = 0x0800;
	static final int KMOD_NUM = 0x1000;
	static final int KMOD_CAPS = 0x2000;
	static final int KMOD_SHIFT = KMOD_LSHIFT | KMOD_RSHIFT;
	static final int KMOD_CTRL = KMOD_LCTRL | KMOD_RCTRL;
	static final int KMOD_ALT = KMOD_LALT | KMOD_RALT;
	static final int KMOD_GUI = KMOD_LGUI | KMOD_RGUI;

	private SDLBrowser() {
	}

	private static final class Win {
		final int id;
		final long handle;
		final long createFlags;
		String title;
		boolean mouseGrab;

		Win(int id, long createFlags, String title) {
			this.id = id;
			this.handle = HANDLE_BASE + ((long) id << 4);
			this.createFlags = createFlags;
			this.title = title;
		}
	}

	private static final ArrayList<Win> windows = new ArrayList<>();
	private static int nextWindowId = 1;
	private static Win canvasWindow;

	static long createWindow(String title, long flags) {
		ensureInstalled();
		Win win = new Win(nextWindowId++, flags, title == null ? "" : title);
		windows.add(win);
		if ((flags & SDLVideo.SDL_WINDOW_HIDDEN) == 0 || canvasWindow == null) {
			canvasWindow = win;
		}
		if (win == canvasWindow && (flags & SDLVideo.SDL_WINDOW_HIDDEN) == 0) {
			if (dom && title != null && !title.isEmpty()) {
				setDocumentTitle(title);
			}
			if (canvas != null) {
				syncCanvasBackingStore(canvas, highPixelDensity);
			}
			recordGeometry();
		}
		return win.handle;
	}

	static void destroyWindow(long handle) {
		Win win = find(handle);
		if (win == null) {
			return;
		}
		windows.remove(win);
		if (win == canvasWindow) {
			canvasWindow = null;
			for (int i = windows.size() - 1; i >= 0 && canvasWindow == null; i--) {
				if ((windows.get(i).createFlags & SDLVideo.SDL_WINDOW_HIDDEN) == 0) {
					canvasWindow = windows.get(i);
				}
			}
			if (canvasWindow == null && !windows.isEmpty()) {
				canvasWindow = windows.get(windows.size() - 1);
			}
		}
	}

	private static Win find(long handle) {
		for (int i = 0; i < windows.size(); i++) {
			Win win = windows.get(i);
			if (win.handle == handle) {
				return win;
			}
		}
		return null;
	}

	static boolean valid(long handle) {
		if (find(handle) != null) {
			return true;
		}
		SDLError.set("Parameter 'window' is invalid");
		return false;
	}

	static int windowId(long handle) {
		Win win = find(handle);
		return win == null ? 0 : win.id;
	}

	static long windowFromId(int id) {
		for (int i = 0; i < windows.size(); i++) {
			if (windows.get(i).id == id) {
				return windows.get(i).handle;
			}
		}
		return 0L;
	}

	private static int canvasWindowId() {
		return canvasWindow == null ? 0 : canvasWindow.id;
	}

	static boolean isCanvasWindow(long handle) {
		return canvasWindow != null && canvasWindow.handle == handle;
	}

	static long canvasWindowHandle() {
		return canvasWindow == null ? 0L : canvasWindow.handle;
	}

	static long windowFlags(long handle) {
		Win win = find(handle);
		if (win == null) {
			return 0L;
		}
		long flags = win.createFlags & ~(SDLVideo.SDL_WINDOW_FULLSCREEN | SDLVideo.SDL_WINDOW_MINIMIZED
			| SDLVideo.SDL_WINDOW_OCCLUDED | SDLVideo.SDL_WINDOW_INPUT_FOCUS | SDLVideo.SDL_WINDOW_MOUSE_FOCUS
			| SDLVideo.SDL_WINDOW_MOUSE_RELATIVE_MODE | SDLVideo.SDL_WINDOW_MOUSE_GRABBED);
		if (win.mouseGrab) {
			flags |= SDLVideo.SDL_WINDOW_MOUSE_GRABBED;
		}
		if (win == canvasWindow && (win.createFlags & SDLVideo.SDL_WINDOW_HIDDEN) == 0) {
			if (isFullscreen()) {
				flags |= SDLVideo.SDL_WINDOW_FULLSCREEN;
			}
			if (focused) {
				flags |= SDLVideo.SDL_WINDOW_INPUT_FOCUS;
			}
			if (mouseInside) {
				flags |= SDLVideo.SDL_WINDOW_MOUSE_FOCUS;
			}
			if (relativeMode) {
				flags |= SDLVideo.SDL_WINDOW_MOUSE_RELATIVE_MODE;
			}
			if (pageHidden) {
				flags |= SDLVideo.SDL_WINDOW_MINIMIZED | SDLVideo.SDL_WINDOW_OCCLUDED;
			}
		}
		return flags;
	}

	static void setTitle(long handle, String title) {
		Win win = find(handle);
		if (win != null) {
			win.title = title;
			if (win == canvasWindow && dom) {
				setDocumentTitle(title);
			}
		}
	}

	static String title(long handle) {
		Win win = find(handle);
		return win == null ? "" : win.title;
	}

	static void setMouseGrab(long handle, boolean grab) {
		Win win = find(handle);
		if (win != null) {
			win.mouseGrab = grab;
		}
	}

	static boolean mouseGrab(long handle) {
		Win win = find(handle);
		return win != null && win.mouseGrab;
	}

	static void requestSize(long handle, int width, int height) {
		if (isCanvasWindow(handle) && (width != cssWidth() || height != cssHeight())) {
			SDLEventQueue.pushWindow(SDLEvents.SDL_EVENT_WINDOW_RESIZED, canvasWindowId(), cssWidth(), cssHeight());
		}
	}

	private static HTMLCanvasElement canvas;
	private static boolean dom;
	private static boolean installed;
	private static boolean active;
	private static boolean highPixelDensity = true;
	private static JSObject touchControls;

	private static boolean focused = true;
	private static boolean mouseInside;
	private static boolean pageHidden;

	private static int reportedWidth;
	private static int reportedHeight;
	private static int reportedPixelWidth;
	private static int reportedPixelHeight;
	private static float reportedDensity = 1.0F;

	static int cssWidth() {
		return canvas == null ? DEFAULT_WIDTH : Math.max(1, canvas.getClientWidth());
	}

	static int cssHeight() {
		return canvas == null ? DEFAULT_HEIGHT : Math.max(1, canvas.getClientHeight());
	}

	static int pixelWidth() {
		return canvas == null ? DEFAULT_WIDTH : Math.max(1, canvas.getWidth());
	}

	static int pixelHeight() {
		return canvas == null ? DEFAULT_HEIGHT : Math.max(1, canvas.getHeight());
	}

	static float pixelDensity() {
		return (float) pixelWidth() / cssWidth();
	}

	static int screenWidth() {
		return canvas == null ? DEFAULT_WIDTH : Math.max(1, screenWidth0());
	}

	static int screenHeight() {
		return canvas == null ? DEFAULT_HEIGHT : Math.max(1, screenHeight0());
	}

	static float screenDensity() {
		return canvas == null ? 1.0F : (float) Math.max(1.0E-3, devicePixelRatio());
	}

	static float refreshRate() {
		return 60.0F;
	}

	private static void recordGeometry() {
		reportedWidth = cssWidth();
		reportedHeight = cssHeight();
		reportedPixelWidth = pixelWidth();
		reportedPixelHeight = pixelHeight();
		reportedDensity = pixelDensity();
	}

	private static void checkResize() {
		if (canvas == null) {
			return;
		}
		syncCanvasBackingStore(canvas, highPixelDensity);
		int width = cssWidth();
		int height = cssHeight();
		int pixelWidth = pixelWidth();
		int pixelHeight = pixelHeight();
		float density = pixelDensity();
		int id = canvasWindowId();
		if (!active || id == 0) {
			recordGeometry();
			return;
		}
		if (width != reportedWidth || height != reportedHeight) {
			SDLEventQueue.pushWindow(SDLEvents.SDL_EVENT_WINDOW_RESIZED, id, width, height);
		}
		if (pixelWidth != reportedPixelWidth || pixelHeight != reportedPixelHeight) {
			SDLEventQueue.pushWindow(SDLEvents.SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED, id, pixelWidth, pixelHeight);
		}
		if (density != reportedDensity) {
			SDLEventQueue.pushWindow(SDLEvents.SDL_EVENT_WINDOW_DISPLAY_SCALE_CHANGED, id, 0, 0);
		}
		recordGeometry();
		mouseX = clamp(mouseX, width);
		mouseY = clamp(mouseY, height);
	}

	public static HTMLCanvasElement replaceCanvas() {
		HTMLCanvasElement old = canvas != null ? canvas : findCanvas();
		if (old == null) {
			return null;
		}
		HTMLCanvasElement fresh = cloneAndReplace(old);
		canvas = fresh;
		if (installed) {
			installCanvasListeners(fresh);
		}
		System.out.println("[sdl-web] canvas element replaced");
		return fresh;
	}

	public static void setHighPixelDensity(boolean highDensity) {
		highPixelDensity = highDensity;
		checkResize();
	}

	public static HTMLCanvasElement canvas() {
		return canvas;
	}

	static void init() {
		ensureInstalled();
		if (touchControls == null && canvas != null) {
			touchControls = BrowserTouch.install(canvas, SDLBrowser::onTouchInput);
			BrowserTouch.mode(touchControls, relativeMode);
		}
		active = true;
		recordGeometry();
	}

	static void quit() {
		active = false;
		relativeMode = false;
		if (pointerLocked) {
			exitPointerLock();
		}
		if (touchControls != null) {
			BrowserTouch.destroy(touchControls);
			touchControls = null;
		}
		SDLEventQueue.clear();
		java.util.Arrays.fill(keyDown, (byte) 0);
		java.util.Arrays.fill(touchDown, (byte) 0);
		for (int i = 0; i < 512; i++) {
			keyboardState.put(i, (byte) 0);
		}
		buttonMask = 0;
		textInputActive = false;
	}

	static boolean isActive() {
		return active;
	}

	private static HTMLCanvasElement findCanvas() {
		HTMLDocument document = org.teavm.jso.browser.Window.current().getDocument();
		return (HTMLCanvasElement) document.getElementById("game_frame");
	}

	private static boolean documentListenersInstalled;

	private static void ensureInstalled() {
		if (installed) {
			return;
		}
		installed = true;
		dom = hasDocument();
		if (!dom) {
			System.out.println("[sdl-web] no document; running headless at " + DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT);
			return;
		}
		if (canvas == null) {
			canvas = findCanvas();
		}
		if (canvas == null) {
			System.out.println("[sdl-web] no #game_frame canvas; running headless at " + DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT);
			return;
		}
		syncCanvasBackingStore(canvas, highPixelDensity);
		if (!documentListenersInstalled) {
			documentListenersInstalled = true;
			installDocumentListeners(org.teavm.jso.browser.Window.current().getDocument());
		}
		installCanvasListeners(canvas);
		focused = documentHasFocus();
		pageHidden = documentHidden();
		documentFullscreen = isDocumentFullscreen();
		recordGeometry();
		requestLayoutMap();
	}

	private static void installDocumentListeners(HTMLDocument document) {
		document.addEventListener("keydown", (EventListener<KeyboardEvent>) SDLBrowser::onKeyDown);
		document.addEventListener("keyup", (EventListener<KeyboardEvent>) SDLBrowser::onKeyUp);
		document.addEventListener("paste", (EventListener<Event>) SDLBrowser::onPaste);
		document.addEventListener("mouseup", (EventListener<MouseEvent>) SDLBrowser::onMouseUp);
		document.addEventListener("pointerlockchange", event -> onPointerLockChange());
		document.addEventListener("fullscreenchange", event -> onFullscreenChange());
		document.addEventListener("visibilitychange", event -> onVisibilityChange());
		org.teavm.jso.browser.Window.current().addEventListener("blur", event -> onFocus(false));
		org.teavm.jso.browser.Window.current().addEventListener("focus", event -> onFocus(true));
		org.teavm.jso.browser.Window.current().addEventListener("resize", event -> checkResize());
	}

	private static void installCanvasListeners(HTMLCanvasElement target) {
		BrowserTouch.destroy(touchControls);
		touchControls = BrowserTouch.install(target, SDLBrowser::onTouchInput);
		BrowserTouch.mode(touchControls, relativeMode);
		target.addEventListener("mousemove", (EventListener<MouseEvent>) SDLBrowser::onMouseMove);
		target.addEventListener("mousedown", (EventListener<MouseEvent>) SDLBrowser::onMouseDown);
		target.addEventListener("wheel", (EventListener<WheelEvent>) SDLBrowser::onWheel);
		target.addEventListener("contextmenu", event -> event.preventDefault());
		target.addEventListener("auxclick", event -> event.preventDefault());
		target.addEventListener("mouseenter", event -> onMouseEnter(true));
		target.addEventListener("mouseleave", event -> onMouseEnter(false));
		target.addEventListener("click", event -> {
			if (active && relativeMode && !pointerLocked) {
				requestPointerLock(target);
			}
		});
		observeResize(target, SDLBrowser::checkResize);
	}

	private static final byte[] keyDown = new byte[SDLKeymap.SCANCODE_COUNT];
	private static final byte[] touchDown = new byte[SDLKeymap.SCANCODE_COUNT];
	private static final ByteBuffer keyboardState = ByteBuffer.allocate(SDLKeymap.SCANCODE_COUNT);
	private static boolean shiftHeld;
	private static boolean ctrlHeld;
	private static boolean altHeld;
	private static boolean metaHeld;
	private static boolean capsLock;
	private static boolean numLock;
	private static int modOverride = -1;
	private static int pendingPaste = -1;
	private static int pendingPasteMods;
	private static boolean textInputActive;
	private static int textAreaX;
	private static int textAreaY;
	private static int textAreaW;
	private static int textAreaH;
	private static int textAreaCursor;

	static ByteBuffer keyboardState() {
		return keyboardState;
	}

	private static void setKey(byte[] source, int scancode, boolean down) {
		if (scancode <= 0 || scancode >= SDLKeymap.SCANCODE_COUNT) {
			return;
		}
		source[scancode] = (byte) (down ? 1 : 0);
		keyboardState.put(scancode, (byte) (keyDown[scancode] | touchDown[scancode]));
	}

	private static boolean held(int scancode) {
		return (keyDown[scancode] | touchDown[scancode]) != 0;
	}

	static int modState() {
		if (modOverride >= 0) {
			return modOverride;
		}
		int mods = 0;
		mods |= side(SDLKeymap.SC_LSHIFT, SDLKeymap.SC_RSHIFT, shiftHeld, KMOD_LSHIFT, KMOD_RSHIFT);
		mods |= side(SDLKeymap.SC_LCTRL, SDLKeymap.SC_RCTRL, ctrlHeld, KMOD_LCTRL, KMOD_RCTRL);
		mods |= side(SDLKeymap.SC_LALT, SDLKeymap.SC_RALT, altHeld, KMOD_LALT, KMOD_RALT);
		mods |= side(SDLKeymap.SC_LGUI, SDLKeymap.SC_RGUI, metaHeld, KMOD_LGUI, KMOD_RGUI);
		if (capsLock) {
			mods |= KMOD_CAPS;
		}
		if (numLock) {
			mods |= KMOD_NUM;
		}
		return mods;
	}

	private static int side(int left, int right, boolean domHeld, int leftBit, int rightBit) {
		int mods = (held(left) ? leftBit : 0) | (held(right) ? rightBit : 0);
		return mods == 0 && domHeld ? leftBit : mods;
	}

	static void setModState(int mods) {
		modOverride = mods;
	}

	private static void syncModifiers(boolean shift, boolean ctrl, boolean alt, boolean meta) {
		modOverride = -1;
		shiftHeld = shift;
		ctrlHeld = ctrl;
		altHeld = alt;
		metaHeld = meta;
		if (!shift) {
			releaseStale(SDLKeymap.SC_LSHIFT);
			releaseStale(SDLKeymap.SC_RSHIFT);
		}
		if (!ctrl) {
			releaseStale(SDLKeymap.SC_LCTRL);
			releaseStale(SDLKeymap.SC_RCTRL);
		}
		if (!alt) {
			releaseStale(SDLKeymap.SC_LALT);
			releaseStale(SDLKeymap.SC_RALT);
		}
		if (!meta) {
			releaseStale(SDLKeymap.SC_LGUI);
			releaseStale(SDLKeymap.SC_RGUI);
		}
	}

	private static void releaseStale(int scancode) {
		if (keyDown[scancode] != 0) {
			setKey(keyDown, scancode, false);
			if (touchDown[scancode] == 0) {
				SDLEventQueue.pushKey(false, canvasWindowId(), scancode, SDLKeymap.keycode(scancode), modState(), false);
			}
		}
	}

	private static void onKeyDown(KeyboardEvent event) {
		if (!active) {
			return;
		}
		int scancode = SDLKeymap.scancode(event.getCode());
		boolean shift = event.isShiftKey();
		boolean ctrl = event.isCtrlKey();
		boolean alt = event.isAltKey();
		boolean meta = event.isMetaKey();
		boolean altGraph = event.getModifierState("AltGraph");
		capsLock = event.getModifierState("CapsLock");
		numLock = event.getModifierState("NumLock");
		syncModifiers(shift, ctrl, alt, meta);
		String printed = event.getKey();
		if (!shift && !ctrl && !alt && !meta && !altGraph && SDLKeymap.learn(scancode, printed)) {
			SDLEventQueue.pushSimple(SDLEvents.SDL_EVENT_KEYMAP_CHANGED);
		}
		setKey(keyDown, scancode, true);
		int mods = modState();
		int keycode = scancode != 0 ? SDLKeymap.keycode(scancode) : singleCodepoint(printed);
		boolean shortcut = ctrl || meta;
		if (shortcut && !alt && keycode == 'v') {
			pendingPaste = scancode;
			pendingPasteMods = mods;
			return;
		}
		if (swallows(event, scancode)) {
			event.preventDefault();
		}
		SDLEventQueue.pushKey(true, canvasWindowId(), scancode, keycode, mods, event.isRepeat());
		if (textInputActive && (!shortcut || altGraph) && !event.isComposing()) {
			int codepoint = singleCodepoint(printed);
			if (codepoint >= 32 && codepoint != 127) {
				SDLEventQueue.pushText(canvasWindowId(), printed);
			}
		}
	}

	private static void onKeyUp(KeyboardEvent event) {
		if (!active) {
			return;
		}
		int scancode = SDLKeymap.scancode(event.getCode());
		capsLock = event.getModifierState("CapsLock");
		numLock = event.getModifierState("NumLock");
		boolean wasDown = scancode > 0 && keyDown[scancode] != 0;
		setKey(keyDown, scancode, false);
		syncModifiers(event.isShiftKey(), event.isCtrlKey(), event.isAltKey(), event.isMetaKey());
		if (scancode == pendingPaste) {
			pendingPaste = -1;
		}
		if (swallows(event, scancode)) {
			event.preventDefault();
		}
		if ((wasDown || scancode == 0) && (scancode == 0 || touchDown[scancode] == 0)) {
			SDLEventQueue.pushKey(false, canvasWindowId(), scancode, SDLKeymap.keycode(scancode), modState(), false);
		}
	}

	private static void onPaste(Event event) {
		clipboard = readPasteText(event);
		if (!active || pendingPaste < 0) {
			return;
		}
		int scancode = pendingPaste;
		pendingPaste = -1;
		boolean shortcut = (pendingPasteMods & (KMOD_CTRL | KMOD_GUI)) != 0;
		boolean extra = (pendingPasteMods & (KMOD_SHIFT | KMOD_ALT)) != 0;
		if (!clipboard.isEmpty() && shortcut && !extra) {
			SDLEventQueue.pushKey(true, canvasWindowId(), scancode, SDLKeymap.keycode(scancode), pendingPasteMods, false);
		}
	}

	private static boolean swallows(KeyboardEvent event, int scancode) {
		if (event.isCtrlKey() || event.isMetaKey() || event.isAltKey()) {
			return false;
		}
		return scancode == SDLKeymap.SC_SPACE || scancode == SDLKeymap.SC_TAB || scancode == SDLKeymap.SC_BACKSPACE
			|| scancode == SDLKeymap.SC_APOSTROPHE || scancode == SDLKeymap.SC_SLASH
			|| (scancode >= SDLKeymap.SC_HOME && scancode <= SDLKeymap.SC_UP && scancode != SDLKeymap.SC_DELETE)
			|| (scancode >= SDLKeymap.SC_F1 && scancode <= SDLKeymap.SC_F12);
	}

	private static int singleCodepoint(String text) {
		if (text == null || text.isEmpty() || text.codePointCount(0, text.length()) != 1) {
			return 0;
		}
		return text.codePointAt(0);
	}

	static void startTextInput() {
		textInputActive = true;
	}

	static void stopTextInput() {
		textInputActive = false;
	}

	static boolean textInputActive() {
		return textInputActive;
	}

	static void setTextInputArea(int x, int y, int w, int h, int cursor) {
		textAreaX = x;
		textAreaY = y;
		textAreaW = w;
		textAreaH = h;
		textAreaCursor = cursor;
	}

	static void resetKeyboard() {
		for (int scancode = 1; scancode < SDLKeymap.SCANCODE_COUNT; scancode++) {
			if (held(scancode)) {
				keyDown[scancode] = 0;
				touchDown[scancode] = 0;
				keyboardState.put(scancode, (byte) 0);
				SDLEventQueue.pushKey(false, canvasWindowId(), scancode, SDLKeymap.keycode(scancode), modState(), false);
			}
		}
		pendingPaste = -1;
		shiftHeld = ctrlHeld = altHeld = metaHeld = false;
	}

	private static void requestLayoutMap() {
		readLayoutMap((code, key) -> {
			int scancode = SDLKeymap.scancode(code);
			if (SDLKeymap.learn(scancode, key) && active) {
				SDLEventQueue.pushSimple(SDLEvents.SDL_EVENT_KEYMAP_CHANGED);
			}
		});
	}

	@JSFunctor
	private interface LayoutEntry extends JSObject {
		void entry(String code, String key);
	}

	@JSBody(params = {"sink"}, script = "try { if (navigator.keyboard && navigator.keyboard.getLayoutMap) {"
		+ " navigator.keyboard.getLayoutMap().then(function (map) { map.forEach(function (key, code) { sink(code, key); }); },"
		+ " function () {}); } } catch (e) {}")
	private static native void readLayoutMap(LayoutEntry sink);

	private static float mouseX;
	private static float mouseY;
	private static int buttonMask;
	private static boolean relativeMode;
	private static boolean pointerLocked;
	private static double wheelRemainderX;
	private static double wheelRemainderY;
	private static long currentCursor = 1L;
	private static boolean cursorVisible = true;

	private static float clamp(float value, int extent) {
		return value < 0 ? 0 : (value > extent ? extent : value);
	}

	static int mouseState(FloatBuffer x, FloatBuffer y) {
		if (x != null) {
			x.put(x.position(), mouseX);
		}
		if (y != null) {
			y.put(y.position(), mouseY);
		}
		return buttonMask;
	}

	static void warpMouse(float x, float y) {
		mouseX = x;
		mouseY = y;
	}

	static boolean relativeMode() {
		return relativeMode;
	}

	static void setRelativeMode(boolean enabled) {
		relativeMode = enabled;
		if (touchControls != null) {
			BrowserTouch.mode(touchControls, enabled);
		}
		if (canvas == null) {
			return;
		}
		if (enabled) {
			if (!pointerLocked) {
				requestPointerLock(canvas);
			}
		} else if (pointerLocked) {
			exitPointerLock();
		}
		applyCursor();
	}

	private static void syncMouseModifiers(MouseEvent event) {
		syncModifiers(event.getShiftKey(), event.getCtrlKey(), event.getAltKey(), event.getMetaKey());
	}

	private static final float SPIKE_FLOOR = 250f;
	private static final float SPIKE_RATIO = 8f;
	private static final float SPIKE_BASE = 10f;
	private static int skipLockedMoves;
	private static float recentMotion;
	private static boolean droppedLast;

	static boolean isMotionSpike(float dx, float dy) {
		if (skipLockedMoves > 0) {
			skipLockedMoves--;
			return true;
		}
		float magnitude = Math.max(Math.abs(dx), Math.abs(dy));
		if (!droppedLast && magnitude > SPIKE_FLOOR && magnitude > SPIKE_RATIO * (recentMotion + SPIKE_BASE)) {
			droppedLast = true;
			return true;
		}
		droppedLast = false;
		recentMotion = recentMotion * 0.7f + magnitude * 0.3f;
		return false;
	}

	private static void onMouseMove(MouseEvent event) {
		if (!active) {
			return;
		}
		float dx = (float) event.getMovementX();
		float dy = (float) event.getMovementY();
		if (pointerLocked && isMotionSpike(dx, dy)) {
			return;
		}
		if (pointerLocked) {
			mouseX = clamp(mouseX + dx, cssWidth());
			mouseY = clamp(mouseY + dy, cssHeight());
		} else {
			mouseX = event.getOffsetX();
			mouseY = event.getOffsetY();
		}
		SDLEventQueue.pushMotion(canvasWindowId(), buttonMask, mouseX, mouseY, dx, dy);
	}

	private static int sdlButton(int domButton) {
		return domButton >= 0 && domButton <= 4 ? domButton + 1 : 0;
	}

	private static void onMouseDown(MouseEvent event) {
		if (!active) {
			return;
		}
		event.preventDefault();
		syncMouseModifiers(event);
		int button = sdlButton(event.getButton());
		if (button == 0) {
			return;
		}
		if (!pointerLocked) {
			mouseX = event.getOffsetX();
			mouseY = event.getOffsetY();
		}
		buttonMask |= 1 << (button - 1);
		SDLEventQueue.pushButton(true, canvasWindowId(), button, Math.max(1, event.getDetail()), mouseX, mouseY);
	}

	private static void onMouseUp(MouseEvent event) {
		if (!active) {
			return;
		}
		int button = sdlButton(event.getButton());
		int bit = button == 0 ? 0 : 1 << (button - 1);
		if ((buttonMask & bit) == 0) {
			return;
		}
		boolean onCanvas = isTarget(event, canvas);
		if (onCanvas) {
			event.preventDefault();
			if (!pointerLocked) {
				mouseX = event.getOffsetX();
				mouseY = event.getOffsetY();
			}
		}
		syncMouseModifiers(event);
		buttonMask &= ~bit;
		SDLEventQueue.pushButton(false, canvasWindowId(), button, Math.max(1, event.getDetail()), mouseX, mouseY);
	}

	private static void onWheel(WheelEvent event) {
		event.preventDefault();
		if (!active) {
			return;
		}
		double unit = event.getDeltaMode() == 0 ? 100.0 : (event.getDeltaMode() == 1 ? 3.0 : 1.0);
		double x = event.getDeltaX() / unit;
		double y = -event.getDeltaY() / unit;
		wheelRemainderX += x;
		wheelRemainderY += y;
		int ix = (int) wheelRemainderX;
		int iy = (int) wheelRemainderY;
		wheelRemainderX -= ix;
		wheelRemainderY -= iy;
		SDLEventQueue.pushWheel(canvasWindowId(), (float) x, (float) y, mouseX, mouseY, ix, iy);
	}

	private static void onMouseEnter(boolean entered) {
		mouseInside = entered;
		if (active && canvasWindowId() != 0) {
			SDLEventQueue.pushWindow(entered ? SDLEvents.SDL_EVENT_WINDOW_MOUSE_ENTER : SDLEvents.SDL_EVENT_WINDOW_MOUSE_LEAVE,
				canvasWindowId(), 0, 0);
		}
	}

	private static void onPointerLockChange() {
		boolean wasLocked = pointerLocked;
		pointerLocked = canvas != null && isPointerLocked(canvas);
		if (pointerLocked && !wasLocked) {
			skipLockedMoves = 1;
			recentMotion = 0f;
			droppedLast = false;
		}
	}

	private static void onTouchInput(int kind, double x, double y) {
		if (!active) {
			return;
		}
		int id = canvasWindowId();
		if (kind == 0) {
			int scancode = SDLKeymap.scancodeForGlfwKey((int) x);
			if (scancode == 0) {
				return;
			}
			boolean down = y != 0;
			setKey(touchDown, scancode, down);
			if (keyDown[scancode] == 0) {
				SDLEventQueue.pushKey(down, id, scancode, SDLKeymap.keycode(scancode), modState(), false);
			}
		} else if (kind == 1) {
			int button = (int) x == 1 ? 3 : 1;
			boolean down = y != 0;
			int bit = 1 << (button - 1);
			buttonMask = down ? buttonMask | bit : buttonMask & ~bit;
			SDLEventQueue.pushButton(down, id, button, 1, mouseX, mouseY);
		} else if (kind == 4) {
			SDLEventQueue.pushText(id, new String(Character.toChars((int) x)));
		} else {
			float oldX = mouseX;
			float oldY = mouseY;
			if (kind == 3) {
				mouseX = clamp(mouseX + (float) x, cssWidth());
				mouseY = clamp(mouseY + (float) y, cssHeight());
				SDLEventQueue.pushMotion(id, buttonMask, mouseX, mouseY, (float) x, (float) y);
			} else {
				mouseX = (float) x;
				mouseY = (float) y;
				SDLEventQueue.pushMotion(id, buttonMask, mouseX, mouseY, mouseX - oldX, mouseY - oldY);
			}
		}
	}

	private static final String[] CSS_CURSORS = {
		"default", "text", "wait", "crosshair", "progress", "nwse-resize", "nesw-resize", "ew-resize",
		"ns-resize", "move", "not-allowed", "pointer", "nw-resize", "n-resize", "ne-resize", "e-resize",
		"se-resize", "s-resize", "sw-resize", "w-resize",
	};

	static long systemCursor(int id) {
		if (id < 0 || id >= CSS_CURSORS.length) {
			SDLError.set("Parameter 'id' is invalid");
			return 0L;
		}
		return id + 1L;
	}

	static void setCursor(long cursor) {
		if (cursor != 0L) {
			currentCursor = cursor;
		}
		applyCursor();
	}

	static long cursor() {
		return currentCursor;
	}

	static void showCursor(boolean visible) {
		cursorVisible = visible;
		applyCursor();
	}

	static boolean cursorVisible() {
		return cursorVisible;
	}

	private static void applyCursor() {
		int index = (int) (currentCursor - 1);
		String css = !cursorVisible ? "none"
			: (index >= 0 && index < CSS_CURSORS.length ? CSS_CURSORS[index] : "default");
		if (canvas != null) {
			setCanvasCursor(canvas, css);
		}
	}

	private static boolean fullscreenWanted;
	private static boolean fullscreenPending;
	private static boolean documentFullscreen;

	private static void onFocus(boolean gained) {
		focused = gained;
		if (!active) {
			return;
		}
		if (!gained) {
			resetKeyboard();
			for (int button = 1; button <= 5; button++) {
				int bit = 1 << (button - 1);
				if ((buttonMask & bit) != 0) {
					SDLEventQueue.pushButton(false, canvasWindowId(), button, 1, mouseX, mouseY);
				}
			}
			buttonMask = 0;
		}
		if (canvasWindowId() != 0) {
			SDLEventQueue.pushWindow(gained ? SDLEvents.SDL_EVENT_WINDOW_FOCUS_GAINED : SDLEvents.SDL_EVENT_WINDOW_FOCUS_LOST,
				canvasWindowId(), 0, 0);
		}
	}

	private static void onVisibilityChange() {
		boolean hidden = documentHidden();
		if (hidden == pageHidden) {
			return;
		}
		pageHidden = hidden;
		if (active && canvasWindowId() != 0) {
			SDLEventQueue.pushWindow(hidden ? SDLEvents.SDL_EVENT_WINDOW_MINIMIZED : SDLEvents.SDL_EVENT_WINDOW_RESTORED,
				canvasWindowId(), 0, 0);
		}
	}

	static boolean isFullscreen() {
		return documentFullscreen || (fullscreenPending && fullscreenWanted);
	}

	static boolean setFullscreen(boolean fullscreen) {
		fullscreenWanted = fullscreen;
		if (canvas == null) {
			fullscreenPending = false;
			if (fullscreen) {
				SDLError.set("Fullscreen is not available without a canvas");
				return false;
			}
			return true;
		}
		if (fullscreen) {
			if (documentFullscreen) {
				return true;
			}
			if (!requestFullscreen(canvas, SDLBrowser::onFullscreenRefused)) {
				fullscreenWanted = false;
				SDLError.set("The Fullscreen API is not available in this browser");
				return false;
			}
			fullscreenPending = true;
			return true;
		}
		fullscreenPending = false;
		if (documentFullscreen) {
			exitFullscreen();
		}
		return true;
	}

	private static void onFullscreenRefused(String reason) {
		if (!fullscreenPending) {
			return;
		}
		fullscreenPending = false;
		fullscreenWanted = false;
		SDLLog.log(SDLLog.SDL_LOG_CATEGORY_VIDEO, SDLLog.SDL_LOG_PRIORITY_WARN, "Fullscreen request refused: " + reason);
		if (active && canvasWindowId() != 0) {
			SDLEventQueue.pushWindow(SDLEvents.SDL_EVENT_WINDOW_LEAVE_FULLSCREEN, canvasWindowId(), 0, 0);
		}
	}

	private static void onFullscreenChange() {
		documentFullscreen = isDocumentFullscreen();
		fullscreenPending = false;
		if (documentFullscreen) {
			lockKeyboard();
		} else {
			fullscreenWanted = false;
			unlockKeyboard();
		}
		if (active && canvasWindowId() != 0) {
			SDLEventQueue.pushWindow(documentFullscreen ? SDLEvents.SDL_EVENT_WINDOW_ENTER_FULLSCREEN
				: SDLEvents.SDL_EVENT_WINDOW_LEAVE_FULLSCREEN, canvasWindowId(), 0, 0);
		}
		checkResize();
	}

	private static String clipboard = "";

	static String clipboard() {
		return clipboard;
	}

	static boolean setClipboard(String text) {
		clipboard = text == null ? "" : text;
		if (dom) {
			writeSystemClipboard(clipboard);
		}
		return true;
	}

	private static boolean unloadArmed;

	public static void setUnloadPrompt(boolean armed) {
		if (armed != unloadArmed && hasDocument()) {
			unloadArmed = armed;
			armUnloadPrompt(armed);
		}
	}

	@JSFunctor
	interface Callback extends JSObject {
		void run();
	}

	@JSFunctor
	interface Refusal extends JSObject {
		void refused(String reason);
	}

	@JSBody(script = "return typeof document !== 'undefined';")
	private static native boolean hasDocument();

	@JSBody(script = "return typeof document.hasFocus === 'function' ? document.hasFocus() : true;")
	private static native boolean documentHasFocus();

	@JSBody(script = "return !!document.hidden;")
	private static native boolean documentHidden();

	@JSBody(script = "return (window.screen && screen.width) | 0;")
	private static native int screenWidth0();

	@JSBody(script = "return (window.screen && screen.height) | 0;")
	private static native int screenHeight0();

	@JSBody(script = "return window.devicePixelRatio || 1;")
	private static native double devicePixelRatio();

	@JSBody(params = {"event", "target"}, script = "return !!target && event.target === target;")
	private static native boolean isTarget(Event event, HTMLCanvasElement target);

	@JSBody(params = {"old"}, script = "var c = old.cloneNode(false); c.width = old.width; c.height = old.height;"
		+ " old.parentNode.replaceChild(c, old); return c;")
	public static native HTMLCanvasElement cloneAndReplace(HTMLCanvasElement old);

	@JSBody(params = {"canvas", "callback"}, script = "if (!canvas) return;"
		+ " if (typeof ResizeObserver === 'function') { new ResizeObserver(function () { callback(); }).observe(canvas); }"
		+ " if (window.matchMedia && !window.__sdlDprWatch) {"
		+ "  window.__sdlDprWatch = true;"
		+ "  var watch = function () {"
		+ "   var mq = matchMedia('(resolution: ' + (window.devicePixelRatio || 1) + 'dppx)');"
		+ "   var onChange = function () { mq.removeEventListener('change', onChange); callback(); watch(); };"
		+ "   if (mq.addEventListener) mq.addEventListener('change', onChange);"
		+ "  };"
		+ "  watch();"
		+ " }")
	private static native void observeResize(HTMLCanvasElement canvas, Callback callback);

	@JSBody(params = {"title"}, script = "document.title = title;")
	public static native void setDocumentTitle(String title);

	@JSBody(params = {"canvas"}, script = "if (canvas && canvas.isConnected && canvas.requestPointerLock) { try {"
		+ " var plain = function () { try { var q = canvas.requestPointerLock(); if (q && q.catch) { q.catch(function () {}); } } catch (e) {} };"
		+ " var p = canvas.requestPointerLock({ unadjustedMovement: true });"
		+ " if (p && p.catch) { p.catch(function (e) { if (e && e.name === 'NotSupportedError') { plain(); } }); } } catch (e) {} }")
	public static native void requestPointerLock(HTMLCanvasElement canvas);

	@JSBody(script = "if (document.exitPointerLock) { try { document.exitPointerLock(); } catch (e) {} }")
	public static native void exitPointerLock();

	@JSBody(params = {"canvas"}, script = "return !!canvas && document.pointerLockElement === canvas;")
	public static native boolean isPointerLocked(HTMLCanvasElement canvas);

	@JSBody(params = {"canvas", "refused"}, script = "if (!canvas || !canvas.requestFullscreen) return false;"
		+ " try { var p = canvas.requestFullscreen();"
		+ "  if (p && p.then) { p.then(null, function (e) { refused(String(e && e.message || e)); }); } }"
		+ " catch (e) { refused(String(e && e.message || e)); }"
		+ " return true;")
	private static native boolean requestFullscreen(HTMLCanvasElement canvas, Refusal refused);

	public static void requestFullscreen(HTMLCanvasElement canvas) {
		requestFullscreen(canvas, reason -> { });
	}

	@JSBody(script = "if (document.fullscreenElement && document.exitFullscreen) { try {"
		+ " var p = document.exitFullscreen(); if (p && p.catch) { p.catch(function () {}); } } catch (e) {} }")
	public static native void exitFullscreen();

	@JSBody(script = "return !!document.fullscreenElement;")
	public static native boolean isDocumentFullscreen();

	@JSBody(params = {"canvas", "highDensity"}, script = "if (canvas) { var r = highDensity ? (window.devicePixelRatio || 1) : 1;"
		+ " if (highDensity && typeof window.__minecraftWebPixelRatio === 'function') { r = window.__minecraftWebPixelRatio(canvas) || r; }"
		+ " else if (r > 2 && window.matchMedia && matchMedia('(pointer: coarse)').matches) { r = 2; }"
		+ " var w = Math.round(canvas.clientWidth * r) | 0, h = Math.round(canvas.clientHeight * r) | 0;"
		+ " if (w > 0 && h > 0 && (canvas.width !== w || canvas.height !== h)) { canvas.width = w; canvas.height = h; } }")
	public static native void syncCanvasBackingStore(HTMLCanvasElement canvas, boolean highDensity);

	@JSBody(script = "var p = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';"
		+ " return /mac/i.test(p) && !(navigator.maxTouchPoints > 1);")
	public static native boolean isMacHost();

	@JSBody(params = {"canvas", "cursor"}, script = "if (canvas) { canvas.style.cursor = cursor; }")
	public static native void setCanvasCursor(HTMLCanvasElement canvas, String cursor);

	@JSBody(params = {"event"}, script = "return (event.clipboardData && event.clipboardData.getData('text/plain')) || '';")
	public static native String readPasteText(Event event);

	@JSBody(params = {"armed"}, script = "window.__minecraftUnloadArmed = !!armed;"
		+ "if (!window.__minecraftUnloadHooked) {"
		+ "  window.__minecraftUnloadHooked = true;"
		+ "  window.addEventListener('beforeunload', function (e) {"
		+ "    if (window.__minecraftUnloadArmed) { e.preventDefault(); e.returnValue = ''; }"
		+ "  });"
		+ "}")
	public static native void armUnloadPrompt(boolean armed);

	@JSBody(script = "try { if (navigator.keyboard && navigator.keyboard.lock) {"
		+ "  var p = navigator.keyboard.lock(); if (p && p.catch) { p.catch(function () {}); }"
		+ "} } catch (e) {}")
	public static native void lockKeyboard();

	@JSBody(script = "try { if (navigator.keyboard && navigator.keyboard.unlock) { navigator.keyboard.unlock(); } } catch (e) {}")
	public static native void unlockKeyboard();

	@JSBody(params = {"text"}, script = "if (!navigator.clipboard) {"
		+ "  console.info('[sdl-web] no navigator.clipboard (insecure origin?); system clipboard not written');"
		+ "  return;"
		+ "}"
		+ "try { navigator.clipboard.writeText(text).catch(function (e) {"
		+ "  console.info('[sdl-web] clipboard write refused: ' + e);"
		+ "}); } catch (e) { console.info('[sdl-web] clipboard write threw: ' + e); }")
	public static native void writeSystemClipboard(String text);

	@JSBody(params = {"url"}, script = "try { var w = window.open(url, '_blank'); if (w) { try { w.opener = null; } catch (e) {} return true; } }"
		+ " catch (e) {} return false;")
	static native boolean openWindow(String url);

	@JSBody(params = {"error", "title", "message"}, script = "if (error) { console.error('[' + title + '] ' + message); }"
		+ " else { console.warn('[' + title + '] ' + message); }")
	static native void consoleMessage(boolean error, String title, String message);
}
