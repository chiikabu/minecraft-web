// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLBrowser;
import org.lwjgl.system.SharedLibrary;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Uint8Array;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.KeyboardEvent;
import org.teavm.jso.dom.events.MouseEvent;
import org.teavm.jso.dom.events.WheelEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;

public final class GLFW {
	public static final int GLFW_PLATFORM_NULL = 393221;
	private static final long WINDOW_HANDLE = 0x57494e444fL;
	private static final long MONITOR_HANDLE = 0x4d4f4e49L;
	private static final int CURSOR = 208897;
	private static final int CURSOR_DISABLED = 212995;
	private static final int RAW_MOUSE_MOTION = 208901;
	private static final int IME = 208903;
	private static final int DEFAULT_WIDTH = 854;
	private static final int DEFAULT_HEIGHT = 480;

	private static HTMLCanvasElement canvas;
	private static boolean highPixelDensity = true;
	private static JSObject touchControls;
	private static boolean initialized;
	private static long initNanos;

	private static long windowMonitor;
	private static int cursorMode = 212993;
	private static boolean pointerLocked;
	private static double cursorX;
	private static double cursorY;
	private static String clipboard = "";
	private static int lastMods;
	private static final boolean[] KEY_STATE = new boolean[400];
	private static final boolean[] TOUCH_KEY_STATE = new boolean[400];

	private static GLFWErrorCallbackI errorCallback;
	private static GLFWMonitorCallbackI monitorCallback;
	private static GLFWKeyCallbackI keyCallback;
	private static GLFWCharCallbackI charCallback;
	private static GLFWPreeditCallbackI preeditCallback;
	private static GLFWIMEStatusCallbackI imeStatusCallback;
	private static GLFWCursorPosCallbackI cursorPosCallback;
	private static GLFWMouseButtonCallbackI mouseButtonCallback;
	private static GLFWScrollCallbackI scrollCallback;
	private static GLFWDropCallbackI dropCallback;
	private static GLFWWindowCloseCallbackI windowCloseCallback;
	private static GLFWWindowPosCallbackI windowPosCallback;
	private static GLFWWindowSizeCallbackI windowSizeCallback;
	private static GLFWWindowFocusCallbackI windowFocusCallback;
	private static GLFWCursorEnterCallbackI cursorEnterCallback;
	private static GLFWWindowIconifyCallbackI windowIconifyCallback;
	private static GLFWFramebufferSizeCallbackI framebufferSizeCallback;

	private GLFW() {
	}

	public static boolean glfwInit() {
		if (initialized) {
			return true;
		}
		initNanos = System.nanoTime();
		HTMLDocument document = org.teavm.jso.browser.Window.current().getDocument();
		canvas = (HTMLCanvasElement)document.getElementById("game_frame");
		if (canvas == null) {
			System.out.println("[glfw-web] no #game_frame canvas; running headless at " + DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT);
		} else {
			syncCanvasBackingStore(canvas);
			installListeners(document);
		}
		initialized = true;
		return true;
	}

	public static void glfwTerminate() {
		BrowserTouch.destroy(touchControls);
		touchControls = null;
	}

	public static void glfwInitHint(int hint, int value) {
	}

	public static int glfwGetPlatform() {
		return GLFW_PLATFORM_NULL;
	}

	public static boolean glfwPlatformSupported(int platform) {
		return false;
	}

	public static double glfwGetTime() {
		return (System.nanoTime() - initNanos) / 1.0E9;
	}

	public static SharedLibrary getLibrary() {
		return null;
	}

	public static int glfwGetError(PointerBuffer description) {
		return 0;
	}

	public static GLFWErrorCallback glfwSetErrorCallback(GLFWErrorCallbackI callback) {
		GLFWErrorCallbackI previous = errorCallback;
		errorCallback = callback;
		return previous instanceof GLFWErrorCallback ? (GLFWErrorCallback)previous : null;
	}

	public static long glfwGetPrimaryMonitor() {
		return MONITOR_HANDLE;
	}

	public static PointerBuffer glfwGetMonitors() {
		LongBuffer handles = LongBuffer.allocate(1);
		handles.put(0, MONITOR_HANDLE);
		return new PointerBuffer(handles);
	}

	public static String glfwGetMonitorName(long monitor) {
		return "Canvas";
	}

	public static void glfwGetMonitorPos(long monitor, int[] x, int[] y) {
		x[0] = 0;
		y[0] = 0;
	}

	public static GLFWVidMode glfwGetVideoMode(long monitor) {
		return new GLFWVidMode(canvasWidth(), canvasHeight(), 8, 8, 8, 60);
	}

	public static GLFWVidMode.Buffer glfwGetVideoModes(long monitor) {
		return new GLFWVidMode.Buffer(new GLFWVidMode[]{glfwGetVideoMode(monitor)});
	}

	public static GLFWMonitorCallback glfwSetMonitorCallback(GLFWMonitorCallbackI callback) {
		monitorCallback = callback;
		return null;
	}

	public static void glfwDefaultWindowHints() {
	}

	public static void glfwWindowHint(int hint, int value) {
	}

	public static long glfwCreateWindow(int width, int height, CharSequence title, long monitor, long share) {
		glfwInit();
		windowMonitor = monitor;
		setDocumentTitle(title.toString());
		return WINDOW_HANDLE;
	}

	public static void glfwDestroyWindow(long window) {
		freeCallbacks(window);
	}

	public static boolean glfwWindowShouldClose(long window) {
		return false;
	}

	public static void glfwShowWindow(long window) {
	}

	public static void glfwSetWindowTitle(long window, CharSequence title) {
		setDocumentTitle(title.toString());
	}

	public static void glfwSetWindowIcon(long window, GLFWImage.Buffer icons) {
	}

	public static void glfwSetWindowSizeLimits(long window, int minWidth, int minHeight, int maxWidth, int maxHeight) {
	}

	public static void glfwGetWindowPos(long window, int[] x, int[] y) {
		x[0] = 0;
		y[0] = 0;
	}

	public static void glfwGetFramebufferSize(long window, int[] width, int[] height) {
		width[0] = canvasWidth();
		height[0] = canvasHeight();
	}

	public static long glfwGetWindowMonitor(long window) {
		return windowMonitor;
	}

	public static void glfwSetWindowMonitor(long window, long monitor, int x, int y, int width, int height, int refreshRate) {
		windowMonitor = monitor;
		if (canvas == null) {
			return;
		}
		if (monitor != 0L) {
			requestFullscreen(canvas);
		} else {
			exitFullscreen();
		}
	}

	public static void glfwPollEvents() {
	}

	public static int glfwGetInputMode(long window, int mode) {
		return mode == CURSOR ? cursorMode : 0;
	}

	public static void glfwSetInputMode(long window, int mode, int value) {
		if (mode != CURSOR) {
			return;
		}
		cursorMode = value;
		BrowserTouch.mode(touchControls, value == CURSOR_DISABLED);
		if (value == CURSOR_DISABLED) {
			requestPointerLock(canvas);
		} else {
			exitPointerLock();
		}
	}

	public static boolean glfwRawMouseMotionSupported() {
		return false;
	}

	public static boolean glfwJoystickPresent(int joystick) {
		return browserGamepad(joystick) != null;
	}

	public static boolean glfwJoystickIsGamepad(int joystick) {
		return standardGamepad(browserGamepad(joystick));
	}

	public static String glfwGetJoystickName(int joystick) {
		return gamepadName(browserGamepad(joystick));
	}

	public static String glfwGetGamepadName(int joystick) {
		JSObject pad = browserGamepad(joystick);
		return standardGamepad(pad) ? gamepadName(pad) : null;
	}

	public static String glfwGetJoystickGUID(int joystick) {
		return null;
	}

	public static boolean glfwGetGamepadState(int joystick, GLFWGamepadState state) {
		return state.read(browserGamepad(joystick));
	}

	public static FloatBuffer glfwGetJoystickAxes(int joystick) {
		Float32Array values = joystickAxes(browserGamepad(joystick));
		if (values == null) return null;
		FloatBuffer buffer = FloatBuffer.allocate(values.getLength());
		for (int i = 0; i < values.getLength(); i++) buffer.put(values.get(i));
		return buffer.flip();
	}

	public static ByteBuffer glfwGetJoystickButtons(int joystick) {
		Uint8Array values = joystickButtons(browserGamepad(joystick));
		if (values == null) return null;
		ByteBuffer buffer = ByteBuffer.allocate(values.getLength());
		for (int i = 0; i < values.getLength(); i++) buffer.put((byte) values.get(i));
		return buffer.flip();
	}

	public static ByteBuffer glfwGetJoystickHats(int joystick) {
		return browserGamepad(joystick) == null ? null : ByteBuffer.allocate(0);
	}

	@JSBody(params = {"axesPad"}, script = """
		if (!axesPad || !axesPad.connected) return null;
		return Float32Array.from(axesPad.axes, value => Number.isFinite(value) ? Math.max(-1, Math.min(1, value)) : 0);
		""")
	private static native Float32Array joystickAxes(JSObject axesPad);

	@JSBody(params = {"buttonsPad"}, script = """
		if (!buttonsPad || !buttonsPad.connected) return null;
		return Uint8Array.from(buttonsPad.buttons, button => button.pressed ? 1 : 0);
		""")
	private static native Uint8Array joystickButtons(JSObject buttonsPad);

	@JSBody(params = {"joystick"}, script = """
		if (joystick < 0 || joystick > 15 || !navigator.getGamepads) return null;
		try {
			var pad = navigator.getGamepads()[joystick];
			return pad && pad.connected ? pad : null;
		} catch (error) {
			if (error.name === 'SecurityError') return null;
			throw error;
		}
		""")
	private static native JSObject browserGamepad(int joystick);

	@JSBody(params = {"pad"}, script = "return !!pad && pad.mapping === 'standard';")
	private static native boolean standardGamepad(JSObject pad);

	@JSBody(params = {"pad"}, script = "return pad ? pad.id : null;")
	private static native String gamepadName(JSObject pad);

	public static int glfwGetKey(long window, int key) {
		return key >= 0 && key < KEY_STATE.length && (KEY_STATE[key] || TOUCH_KEY_STATE[key]) ? 1 : 0;
	}

	public static String glfwGetKeyName(int key, int scancode) {
		if (key >= 'A' && key <= 'Z') {
			return String.valueOf((char)(key - 'A' + 'a'));
		}
		if (key >= '0' && key <= '9') {
			return String.valueOf((char)key);
		}
		if (key >= 320 && key <= 329) {
			return String.valueOf((char)('0' + key - 320));
		}
		switch (key) {
			case 39: return "'";
			case 44: return ",";
			case 45: return "-";
			case 46: return ".";
			case 47: return "/";
			case 59: return ";";
			case 61: return "=";
			case 91: return "[";
			case 92: return "\\";
			case 93: return "]";
			case 96: return "`";
			case 330: return ".";
			case 331: return "/";
			case 332: return "*";
			case 333: return "-";
			case 334: return "+";
			case 336: return "=";
			default: return null;
		}
	}

	public static void glfwSetCursorPos(long window, double x, double y) {
		cursorX = x;
		cursorY = y;
	}

	public static long glfwCreateStandardCursor(int shape) {
		return shape;
	}

	public static void glfwSetCursor(long window, long cursor) {
		setCanvasCursor(canvas, cssCursor((int)cursor));
	}

	public static void glfwSetPreeditCursorRectangle(long window, int x, int y, int width, int height) {
	}

	public static String glfwGetClipboardString(long window) {
		return clipboard;
	}

	public static void glfwSetClipboardString(long window, ByteBuffer string) {
		StringBuilder sb = new StringBuilder();
		for (int i = string.position(); i < string.limit(); i++) {
			byte b = string.get(i);
			if (b == 0) {
				break;
			}
			sb.append((char)(b & 0xFF));
		}
		clipboard = decodeUtf8(sb.toString());
		writeSystemClipboard(clipboard);
	}

	public static GLFWKeyCallback glfwSetKeyCallback(long window, GLFWKeyCallbackI callback) {
		keyCallback = callback;
		return null;
	}

	public static GLFWCharCallback glfwSetCharCallback(long window, GLFWCharCallbackI callback) {
		charCallback = callback;
		return null;
	}

	public static GLFWPreeditCallback glfwSetPreeditCallback(long window, GLFWPreeditCallbackI callback) {
		preeditCallback = callback;
		return null;
	}

	public static GLFWIMEStatusCallback glfwSetIMEStatusCallback(long window, GLFWIMEStatusCallbackI callback) {
		imeStatusCallback = callback;
		return null;
	}

	public static GLFWCursorPosCallback glfwSetCursorPosCallback(long window, GLFWCursorPosCallbackI callback) {
		cursorPosCallback = callback;
		return null;
	}

	public static GLFWMouseButtonCallback glfwSetMouseButtonCallback(long window, GLFWMouseButtonCallbackI callback) {
		mouseButtonCallback = callback;
		return null;
	}

	public static GLFWScrollCallback glfwSetScrollCallback(long window, GLFWScrollCallbackI callback) {
		scrollCallback = callback;
		return null;
	}

	public static GLFWDropCallback glfwSetDropCallback(long window, GLFWDropCallbackI callback) {
		dropCallback = callback;
		return null;
	}

	public static GLFWWindowCloseCallback glfwSetWindowCloseCallback(long window, GLFWWindowCloseCallbackI callback) {
		windowCloseCallback = callback;
		return null;
	}

	public static GLFWWindowPosCallback glfwSetWindowPosCallback(long window, GLFWWindowPosCallbackI callback) {
		windowPosCallback = callback;
		return null;
	}

	public static GLFWWindowSizeCallback glfwSetWindowSizeCallback(long window, GLFWWindowSizeCallbackI callback) {
		windowSizeCallback = callback;
		return null;
	}

	public static GLFWWindowFocusCallback glfwSetWindowFocusCallback(long window, GLFWWindowFocusCallbackI callback) {
		windowFocusCallback = callback;
		return null;
	}

	public static GLFWCursorEnterCallback glfwSetCursorEnterCallback(long window, GLFWCursorEnterCallbackI callback) {
		cursorEnterCallback = callback;
		return null;
	}

	public static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI callback) {
		windowIconifyCallback = callback;
		return null;
	}

	public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(long window, GLFWFramebufferSizeCallbackI callback) {
		framebufferSizeCallback = callback;
		return null;
	}

	static void freeCallbacks(long window) {
		keyCallback = null;
		charCallback = null;
		preeditCallback = null;
		imeStatusCallback = null;
		cursorPosCallback = null;
		mouseButtonCallback = null;
		scrollCallback = null;
		dropCallback = null;
		windowCloseCallback = null;
		windowPosCallback = null;
		windowSizeCallback = null;
		windowFocusCallback = null;
		cursorEnterCallback = null;
		windowIconifyCallback = null;
		framebufferSizeCallback = null;
	}

	private static int canvasWidth() {
		return canvas == null ? DEFAULT_WIDTH : Math.max(1, canvas.getWidth());
	}

	private static int canvasHeight() {
		return canvas == null ? DEFAULT_HEIGHT : Math.max(1, canvas.getHeight());
	}

	public static HTMLCanvasElement replaceCanvas() {
		if (canvas == null) {
			return null;
		}
		HTMLCanvasElement fresh = cloneAndReplace(canvas);
		canvas = fresh;
		if (initialized) {
			installCanvasListeners(fresh);
		}
		System.out.println("[glfw-web] canvas element replaced for the WebGL2 fallback");
		return fresh;
	}

	private static HTMLCanvasElement cloneAndReplace(HTMLCanvasElement old) {
		return SDLBrowser.cloneAndReplace(old);
	}

	private static void installListeners(HTMLDocument document) {
		document.addEventListener("keydown", (EventListener<KeyboardEvent>)GLFW::onKeyDown);
		document.addEventListener("keyup", (EventListener<KeyboardEvent>)GLFW::onKeyUp);
		document.addEventListener("paste", (EventListener<Event>)GLFW::onPaste);
		installCanvasListeners(canvas);
		document.addEventListener("pointerlockchange", event -> pointerLocked = isPointerLocked(canvas));
		document.addEventListener("fullscreenchange", event -> onFullscreenChange());
		org.teavm.jso.browser.Window.current().addEventListener("blur", event -> invokeFocus(false));
		org.teavm.jso.browser.Window.current().addEventListener("focus", event -> invokeFocus(true));
		org.teavm.jso.browser.Window.current().addEventListener("resize", event -> onResize());
	}

	private static void installCanvasListeners(HTMLCanvasElement canvas) {
		BrowserTouch.destroy(touchControls);
		touchControls = BrowserTouch.install(canvas, GLFW::onTouchInput);
		BrowserTouch.mode(touchControls, cursorMode == CURSOR_DISABLED);
		canvas.addEventListener("mousemove", (EventListener<MouseEvent>)GLFW::onMouseMove);
		canvas.addEventListener("mousedown", (EventListener<MouseEvent>)event -> onMouseButton(event, 1));
		canvas.addEventListener("mouseup", (EventListener<MouseEvent>)event -> onMouseButton(event, 0));
		canvas.addEventListener("wheel", (EventListener<WheelEvent>)GLFW::onWheel);
		canvas.addEventListener("contextmenu", event -> event.preventDefault());
		canvas.addEventListener("auxclick", event -> event.preventDefault());
		canvas.addEventListener("mouseenter", event -> invokeCursorEnter(true));
		canvas.addEventListener("mouseleave", event -> invokeCursorEnter(false));
		canvas.addEventListener("click", event -> {
			if (cursorMode == CURSOR_DISABLED && !pointerLocked) {
				requestPointerLock(canvas);
			}
		});
	}

	private static void onFullscreenChange() {
		if (isDocumentFullscreen()) {
			lockKeyboard();
		} else {
			windowMonitor = 0L;
			unlockKeyboard();
		}
		syncCanvasBackingStore(canvas);
		onResize();
	}

	private static void onResize() {
		syncCanvasBackingStore(canvas);
		if (windowSizeCallback != null) {
			windowSizeCallback.invoke(WINDOW_HANDLE, clientWidth(), clientHeight());
		}
		if (framebufferSizeCallback != null) {
			framebufferSizeCallback.invoke(WINDOW_HANDLE, canvasWidth(), canvasHeight());
		}
	}

	private static int clientWidth() {
		return canvas == null ? DEFAULT_WIDTH : Math.max(1, canvas.getClientWidth());
	}

	private static int clientHeight() {
		return canvas == null ? DEFAULT_HEIGHT : Math.max(1, canvas.getClientHeight());
	}

	private static void invokeFocus(boolean focused) {
		if (windowFocusCallback != null) {
			windowFocusCallback.invoke(WINDOW_HANDLE, focused);
		}
	}

	private static void invokeCursorEnter(boolean entered) {
		if (cursorEnterCallback != null) {
			cursorEnterCallback.invoke(WINDOW_HANDLE, entered);
		}
	}

	private static void onKeyDown(KeyboardEvent event) {
		int key = glfwKey(event.getCode());
		if (key >= 0 && key < KEY_STATE.length) {
			KEY_STATE[key] = true;
		}
		lastMods = mods(event);
		if (key == 86 && (event.isCtrlKey() || event.isMetaKey()) && !event.isAltKey()) {
			return;
		}
		if (swallows(event, key)) {
			event.preventDefault();
		}
		if (keyCallback != null) {
			keyCallback.invoke(WINDOW_HANDLE, key, 0, event.isRepeat() ? 2 : 1, mods(event));
		}
		if (charCallback != null && !event.isCtrlKey() && !event.isMetaKey()) {
			String text = event.getKey();
			if (text != null && !text.isEmpty() && text.codePointCount(0, text.length()) == 1 && text.codePointAt(0) >= 32) {
				charCallback.invoke(WINDOW_HANDLE, text.codePointAt(0));
			}
		}
	}

	private static void onKeyUp(KeyboardEvent event) {
		int key = glfwKey(event.getCode());
		if (key >= 0 && key < KEY_STATE.length) {
			KEY_STATE[key] = false;
		}
		lastMods = mods(event);
		if (swallows(event, key)) {
			event.preventDefault();
		}
		if (keyCallback != null && (key < 0 || key >= TOUCH_KEY_STATE.length || !TOUCH_KEY_STATE[key])) {
			keyCallback.invoke(WINDOW_HANDLE, key, 0, 0, mods(event));
		}
	}

	private static void onPaste(Event event) {
		clipboard = readPasteText(event);
		if (!clipboard.isEmpty() && keyCallback != null && (lastMods & (2 | 8)) != 0 && (lastMods & (1 | 4)) == 0) {
			keyCallback.invoke(WINDOW_HANDLE, 86, 0, 1, lastMods);
		}
	}

	private static boolean swallows(KeyboardEvent event, int key) {
		if (event.isCtrlKey() || event.isMetaKey() || event.isAltKey()) {
			return false;
		}
		return key == 32 || key == 258 || key == 259 || key == 39 || key == 47
			|| (key >= 262 && key <= 269)
			|| (key >= 290 && key <= 301);
	}

	private static int mods(KeyboardEvent event) {
		int mods = 0;
		if (event.isShiftKey()) {
			mods |= 1;
		}
		if (event.isCtrlKey()) {
			mods |= 2;
		}
		if (event.isAltKey()) {
			mods |= 4;
		}
		if (event.isMetaKey()) {
			mods |= 8;
		}
		if (event.getModifierState("CapsLock")) {
			mods |= 16;
		}
		if (event.getModifierState("NumLock")) {
			mods |= 32;
		}
		return mods;
	}

	private static int mods(MouseEvent event) {
		int mods = 0;
		if (event.getShiftKey()) {
			mods |= 1;
		}
		if (event.getCtrlKey()) {
			mods |= 2;
		}
		if (event.getAltKey()) {
			mods |= 4;
		}
		if (event.getMetaKey()) {
			mods |= 8;
		}
		return mods;
	}

	private static void onTouchInput(int kind, double x, double y) {
		if (kind == 0) {
			int key = (int)x;
			TOUCH_KEY_STATE[key] = y != 0;
			if (keyCallback != null && !KEY_STATE[key]) keyCallback.invoke(WINDOW_HANDLE, key, 0, (int)y, 0);
		} else if (kind == 1) {
			if (mouseButtonCallback != null) mouseButtonCallback.invoke(WINDOW_HANDLE, (int)x, (int)y, 0);
		} else if (kind == 4) {
			if (charCallback != null) charCallback.invoke(WINDOW_HANDLE, (int)x);
		} else {
			cursorX = kind == 3 ? cursorX + x : x;
			cursorY = kind == 3 ? cursorY + y : y;
			if (cursorPosCallback != null) cursorPosCallback.invoke(WINDOW_HANDLE, cursorX, cursorY);
		}
	}

	private static void onMouseMove(MouseEvent event) {
		if (pointerLocked) {
			cursorX += event.getMovementX();
			cursorY += event.getMovementY();
		} else {
			cursorX = event.getOffsetX();
			cursorY = event.getOffsetY();
		}
		if (cursorPosCallback != null) {
			cursorPosCallback.invoke(WINDOW_HANDLE, cursorX, cursorY);
		}
	}

	private static void onMouseButton(MouseEvent event, int action) {
		event.preventDefault();
		if (mouseButtonCallback != null) {
			mouseButtonCallback.invoke(WINDOW_HANDLE, glfwButton(event.getButton()), action, mods(event));
		}
	}

	private static int glfwButton(int domButton) {
		if (domButton == 1) {
			return 2;
		}
		return domButton == 2 ? 1 : domButton;
	}

	private static void onWheel(WheelEvent event) {
		event.preventDefault();
		if (scrollCallback == null) {
			return;
		}
		double unit = event.getDeltaMode() == 0 ? 100.0 : (event.getDeltaMode() == 1 ? 3.0 : 1.0);
		scrollCallback.invoke(WINDOW_HANDLE, -event.getDeltaX() / unit, -event.getDeltaY() / unit);
	}

	private static String cssCursor(int shape) {
		switch (shape) {
			case 221186:
				return "text";
			case 221187:
				return "crosshair";
			case 221188:
				return "pointer";
			case 221189:
				return "ew-resize";
			case 221190:
				return "ns-resize";
			case 221193:
				return "move";
			case 221194:
				return "not-allowed";
			default:
				return "default";
		}
	}

	private static final Map<String, Integer> KEY_CODES = buildKeyCodes();

	private static int glfwKey(String domCode) {
		if (domCode == null) {
			return -1;
		}
		Integer key = KEY_CODES.get(domCode);
		return key == null ? -1 : key;
	}

	private static Map<String, Integer> buildKeyCodes() {
		Map<String, Integer> m = new HashMap<>(256);
		for (char c = 'A'; c <= 'Z'; c++) {
			m.put("Key" + c, (int)c);
		}
		for (int d = 0; d <= 9; d++) {
			m.put("Digit" + d, 48 + d);
			m.put("Numpad" + d, 320 + d);
		}
		for (int f = 1; f <= 25; f++) {
			m.put("F" + f, 289 + f);
		}
		m.put("Space", 32);
		m.put("Quote", 39);
		m.put("Comma", 44);
		m.put("Minus", 45);
		m.put("Period", 46);
		m.put("Slash", 47);
		m.put("Semicolon", 59);
		m.put("Equal", 61);
		m.put("BracketLeft", 91);
		m.put("Backslash", 92);
		m.put("BracketRight", 93);
		m.put("Backquote", 96);
		m.put("IntlBackslash", 161);
		m.put("IntlRo", 162);
		m.put("IntlYen", 162);
		m.put("Escape", 256);
		m.put("Enter", 257);
		m.put("Tab", 258);
		m.put("Backspace", 259);
		m.put("Insert", 260);
		m.put("Delete", 261);
		m.put("ArrowRight", 262);
		m.put("ArrowLeft", 263);
		m.put("ArrowDown", 264);
		m.put("ArrowUp", 265);
		m.put("PageUp", 266);
		m.put("PageDown", 267);
		m.put("Home", 268);
		m.put("End", 269);
		m.put("CapsLock", 280);
		m.put("ScrollLock", 281);
		m.put("NumLock", 282);
		m.put("PrintScreen", 283);
		m.put("Pause", 284);
		m.put("NumpadDecimal", 330);
		m.put("NumpadDivide", 331);
		m.put("NumpadMultiply", 332);
		m.put("NumpadSubtract", 333);
		m.put("NumpadAdd", 334);
		m.put("NumpadEnter", 335);
		m.put("NumpadEqual", 336);
		m.put("ShiftLeft", 340);
		m.put("ControlLeft", 341);
		m.put("AltLeft", 342);
		m.put("MetaLeft", 343);
		m.put("OSLeft", 343);
		m.put("ShiftRight", 344);
		m.put("ControlRight", 345);
		m.put("AltRight", 346);
		m.put("MetaRight", 347);
		m.put("OSRight", 347);
		m.put("ContextMenu", 348);
		return m;
	}

	private static void setDocumentTitle(String title) {
		SDLBrowser.setDocumentTitle(title);
	}

	private static void requestPointerLock(HTMLCanvasElement canvas) {
		SDLBrowser.requestPointerLock(canvas);
	}

	private static void exitPointerLock() {
		SDLBrowser.exitPointerLock();
	}

	private static boolean isPointerLocked(HTMLCanvasElement canvas) {
		return SDLBrowser.isPointerLocked(canvas);
	}

	private static void requestFullscreen(HTMLCanvasElement canvas) {
		SDLBrowser.requestFullscreen(canvas);
	}

	private static void exitFullscreen() {
		SDLBrowser.exitFullscreen();
	}

	private static boolean isDocumentFullscreen() {
		return SDLBrowser.isDocumentFullscreen();
	}

	private static void syncCanvasBackingStore(HTMLCanvasElement canvas) {
		SDLBrowser.syncCanvasBackingStore(canvas, highPixelDensity);
	}

	public static void setWindowPixelDensity(boolean highDensity) {
		highPixelDensity = highDensity;
		syncCanvasBackingStore(canvas);
	}

	public static boolean isMacHost() {
		return SDLBrowser.isMacHost();
	}

	private static void setCanvasCursor(HTMLCanvasElement canvas, String cursor) {
		SDLBrowser.setCanvasCursor(canvas, cursor);
	}

	private static String readPasteText(org.teavm.jso.dom.events.Event event) {
		return SDLBrowser.readPasteText(event);
	}

	public static void setUnloadPrompt(boolean armed) {
		SDLBrowser.setUnloadPrompt(armed);
	}

	private static void lockKeyboard() {
		SDLBrowser.lockKeyboard();
	}

	private static void unlockKeyboard() {
		SDLBrowser.unlockKeyboard();
	}

	private static void writeSystemClipboard(String text) {
		SDLBrowser.writeSystemClipboard(text);
	}

	@JSBody(params = {"latin1"}, script = "try { return decodeURIComponent(escape(latin1)); } catch (e) { return latin1; }")
	private static native String decodeUtf8(String latin1);
}
