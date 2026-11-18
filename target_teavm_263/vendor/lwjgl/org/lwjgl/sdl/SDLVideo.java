// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;

public final class SDLVideo {
	public static final String SDL_PROP_GLOBAL_VIDEO_WAYLAND_WL_DISPLAY_POINTER = "SDL.video.wayland.wl_display";
	public static final int SDL_SYSTEM_THEME_UNKNOWN = 0;
	public static final int SDL_SYSTEM_THEME_LIGHT = 1;
	public static final int SDL_SYSTEM_THEME_DARK = 2;
	public static final int SDL_ORIENTATION_UNKNOWN = 0;
	public static final int SDL_ORIENTATION_LANDSCAPE = 1;
	public static final int SDL_ORIENTATION_LANDSCAPE_FLIPPED = 2;
	public static final int SDL_ORIENTATION_PORTRAIT = 3;
	public static final int SDL_ORIENTATION_PORTRAIT_FLIPPED = 4;
	public static final long SDL_WINDOW_FULLSCREEN = 1L;
	public static final long SDL_WINDOW_OPENGL = 2L;
	public static final long SDL_WINDOW_OCCLUDED = 4L;
	public static final long SDL_WINDOW_HIDDEN = 8L;
	public static final long SDL_WINDOW_BORDERLESS = 16L;
	public static final long SDL_WINDOW_RESIZABLE = 32L;
	public static final long SDL_WINDOW_MINIMIZED = 64L;
	public static final long SDL_WINDOW_MAXIMIZED = 128L;
	public static final long SDL_WINDOW_MOUSE_GRABBED = 256L;
	public static final long SDL_WINDOW_INPUT_FOCUS = 512L;
	public static final long SDL_WINDOW_MOUSE_FOCUS = 1024L;
	public static final long SDL_WINDOW_EXTERNAL = 2048L;
	public static final long SDL_WINDOW_MODAL = 4096L;
	public static final long SDL_WINDOW_HIGH_PIXEL_DENSITY = 8192L;
	public static final long SDL_WINDOW_MOUSE_CAPTURE = 16384L;
	public static final long SDL_WINDOW_MOUSE_RELATIVE_MODE = 32768L;
	public static final long SDL_WINDOW_ALWAYS_ON_TOP = 65536L;
	public static final long SDL_WINDOW_UTILITY = 131072L;
	public static final long SDL_WINDOW_TOOLTIP = 262144L;
	public static final long SDL_WINDOW_POPUP_MENU = 524288L;
	public static final long SDL_WINDOW_KEYBOARD_GRABBED = 1048576L;
	public static final long SDL_WINDOW_FILL_DOCUMENT = 2097152L;
	public static final long SDL_WINDOW_VULKAN = 268435456L;
	public static final long SDL_WINDOW_METAL = 536870912L;
	public static final long SDL_WINDOW_TRANSPARENT = 1073741824L;
	public static final long SDL_WINDOW_NOT_FOCUSABLE = 2147483648L;
	public static final int SDL_WINDOWPOS_UNDEFINED_MASK = 536805376;
	public static final int SDL_WINDOWPOS_CENTERED_MASK = 805240832;
	public static final int SDL_FLASH_CANCEL = 0;
	public static final int SDL_FLASH_BRIEFLY = 1;
	public static final int SDL_FLASH_UNTIL_FOCUSED = 2;
	public static final int SDL_PROGRESS_STATE_INVALID = -1;
	public static final int SDL_PROGRESS_STATE_NONE = 0;
	public static final int SDL_PROGRESS_STATE_INDETERMINATE = 1;
	public static final int SDL_PROGRESS_STATE_NORMAL = 2;
	public static final int SDL_PROGRESS_STATE_PAUSED = 3;
	public static final int SDL_PROGRESS_STATE_ERROR = 4;
	public static final int SDL_GL_RED_SIZE = 0;
	public static final int SDL_GL_GREEN_SIZE = 1;
	public static final int SDL_GL_BLUE_SIZE = 2;
	public static final int SDL_GL_ALPHA_SIZE = 3;
	public static final int SDL_GL_BUFFER_SIZE = 4;
	public static final int SDL_GL_DOUBLEBUFFER = 5;
	public static final int SDL_GL_DEPTH_SIZE = 6;
	public static final int SDL_GL_STENCIL_SIZE = 7;
	public static final int SDL_GL_ACCUM_RED_SIZE = 8;
	public static final int SDL_GL_ACCUM_GREEN_SIZE = 9;
	public static final int SDL_GL_ACCUM_BLUE_SIZE = 10;
	public static final int SDL_GL_ACCUM_ALPHA_SIZE = 11;
	public static final int SDL_GL_STEREO = 12;
	public static final int SDL_GL_MULTISAMPLEBUFFERS = 13;
	public static final int SDL_GL_MULTISAMPLESAMPLES = 14;
	public static final int SDL_GL_ACCELERATED_VISUAL = 15;
	public static final int SDL_GL_RETAINED_BACKING = 16;
	public static final int SDL_GL_CONTEXT_MAJOR_VERSION = 17;
	public static final int SDL_GL_CONTEXT_MINOR_VERSION = 18;
	public static final int SDL_GL_CONTEXT_FLAGS = 19;
	public static final int SDL_GL_CONTEXT_PROFILE_MASK = 20;
	public static final int SDL_GL_SHARE_WITH_CURRENT_CONTEXT = 21;
	public static final int SDL_GL_FRAMEBUFFER_SRGB_CAPABLE = 22;
	public static final int SDL_GL_CONTEXT_RELEASE_BEHAVIOR = 23;
	public static final int SDL_GL_CONTEXT_RESET_NOTIFICATION = 24;
	public static final int SDL_GL_CONTEXT_NO_ERROR = 25;
	public static final int SDL_GL_FLOATBUFFERS = 26;
	public static final int SDL_GL_EGL_PLATFORM = 27;
	public static final int SDL_GL_CONTEXT_PROFILE_CORE = 1;
	public static final int SDL_GL_CONTEXT_PROFILE_COMPATIBILITY = 2;
	public static final int SDL_GL_CONTEXT_PROFILE_ES = 4;
	public static final int SDL_GL_CONTEXT_DEBUG_FLAG = 1;
	public static final int SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG = 2;
	public static final int SDL_GL_CONTEXT_ROBUST_ACCESS_FLAG = 4;
	public static final int SDL_GL_CONTEXT_RESET_ISOLATION_FLAG = 8;
	public static final int SDL_GL_CONTEXT_RELEASE_BEHAVIOR_NONE = 0;
	public static final int SDL_GL_CONTEXT_RELEASE_BEHAVIOR_FLUSH = 1;
	public static final int SDL_GL_CONTEXT_RESET_NO_NOTIFICATION = 0;
	public static final int SDL_GL_CONTEXT_RESET_LOSE_CONTEXT = 1;
	public static final String SDL_PROP_DISPLAY_HDR_ENABLED_BOOLEAN = "SDL.display.HDR_enabled";
	public static final String SDL_PROP_DISPLAY_KMSDRM_PANEL_ORIENTATION_NUMBER = "SDL.display.KMSDRM.panel_orientation";
	public static final String SDL_PROP_DISPLAY_WAYLAND_WL_OUTPUT_POINTER = "SDL.display.wayland.wl_output";
	public static final String SDL_PROP_DISPLAY_WINDOWS_HMONITOR_POINTER = "SDL.display.windows.hmonitor";
	public static final String SDL_PROP_WINDOW_CREATE_ALWAYS_ON_TOP_BOOLEAN = "SDL.window.create.always_on_top";
	public static final String SDL_PROP_WINDOW_CREATE_BORDERLESS_BOOLEAN = "SDL.window.create.borderless";
	public static final String SDL_PROP_WINDOW_CREATE_CONSTRAIN_POPUP_BOOLEAN = "SDL.window.create.constrain_popup";
	public static final String SDL_PROP_WINDOW_CREATE_FOCUSABLE_BOOLEAN = "SDL.window.create.focusable";
	public static final String SDL_PROP_WINDOW_CREATE_EXTERNAL_GRAPHICS_CONTEXT_BOOLEAN = "SDL.window.create.external_graphics_context";
	public static final String SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER = "SDL.window.create.flags";
	public static final String SDL_PROP_WINDOW_CREATE_FULLSCREEN_BOOLEAN = "SDL.window.create.fullscreen";
	public static final String SDL_PROP_WINDOW_CREATE_HEIGHT_NUMBER = "SDL.window.create.height";
	public static final String SDL_PROP_WINDOW_CREATE_HIDDEN_BOOLEAN = "SDL.window.create.hidden";
	public static final String SDL_PROP_WINDOW_CREATE_HIGH_PIXEL_DENSITY_BOOLEAN = "SDL.window.create.high_pixel_density";
	public static final String SDL_PROP_WINDOW_CREATE_MAXIMIZED_BOOLEAN = "SDL.window.create.maximized";
	public static final String SDL_PROP_WINDOW_CREATE_MENU_BOOLEAN = "SDL.window.create.menu";
	public static final String SDL_PROP_WINDOW_CREATE_METAL_BOOLEAN = "SDL.window.create.metal";
	public static final String SDL_PROP_WINDOW_CREATE_MINIMIZED_BOOLEAN = "SDL.window.create.minimized";
	public static final String SDL_PROP_WINDOW_CREATE_MODAL_BOOLEAN = "SDL.window.create.modal";
	public static final String SDL_PROP_WINDOW_CREATE_MOUSE_GRABBED_BOOLEAN = "SDL.window.create.mouse_grabbed";
	public static final String SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN = "SDL.window.create.opengl";
	public static final String SDL_PROP_WINDOW_CREATE_PARENT_POINTER = "SDL.window.create.parent";
	public static final String SDL_PROP_WINDOW_CREATE_RESIZABLE_BOOLEAN = "SDL.window.create.resizable";
	public static final String SDL_PROP_WINDOW_CREATE_TITLE_STRING = "SDL.window.create.title";
	public static final String SDL_PROP_WINDOW_CREATE_TRANSPARENT_BOOLEAN = "SDL.window.create.transparent";
	public static final String SDL_PROP_WINDOW_CREATE_TOOLTIP_BOOLEAN = "SDL.window.create.tooltip";
	public static final String SDL_PROP_WINDOW_CREATE_UTILITY_BOOLEAN = "SDL.window.create.utility";
	public static final String SDL_PROP_WINDOW_CREATE_VULKAN_BOOLEAN = "SDL.window.create.vulkan";
	public static final String SDL_PROP_WINDOW_CREATE_WIDTH_NUMBER = "SDL.window.create.width";
	public static final String SDL_PROP_WINDOW_CREATE_X_NUMBER = "SDL.window.create.x";
	public static final String SDL_PROP_WINDOW_CREATE_Y_NUMBER = "SDL.window.create.y";
	public static final String SDL_PROP_WINDOW_CREATE_COCOA_WINDOW_POINTER = "SDL.window.create.cocoa.window";
	public static final String SDL_PROP_WINDOW_CREATE_COCOA_VIEW_POINTER = "SDL.window.create.cocoa.view";
	public static final String SDL_PROP_WINDOW_CREATE_WINDOWSCENE_POINTER = "SDL.window.create.uikit.windowscene";
	public static final String SDL_PROP_WINDOW_CREATE_WAYLAND_SURFACE_ROLE_CUSTOM_BOOLEAN = "SDL.window.create.wayland.surface_role_custom";
	public static final String SDL_PROP_WINDOW_CREATE_WAYLAND_CREATE_EGL_WINDOW_BOOLEAN = "SDL.window.create.wayland.create_egl_window";
	public static final String SDL_PROP_WINDOW_CREATE_WAYLAND_WL_SURFACE_POINTER = "SDL.window.create.wayland.wl_surface";
	public static final String SDL_PROP_WINDOW_CREATE_WIN32_HWND_POINTER = "SDL.window.create.win32.hwnd";
	public static final String SDL_PROP_WINDOW_CREATE_WIN32_PIXEL_FORMAT_HWND_POINTER = "SDL.window.create.win32.pixel_format_hwnd";
	public static final String SDL_PROP_WINDOW_CREATE_X11_WINDOW_NUMBER = "SDL.window.create.x11.window";
	public static final String SDL_PROP_WINDOW_CREATE_EMSCRIPTEN_CANVAS_ID_STRING = "SDL.window.create.emscripten.canvas_id";
	public static final String SDL_PROP_WINDOW_CREATE_EMSCRIPTEN_KEYBOARD_ELEMENT_STRING = "SDL.window.create.emscripten.keyboard_element";
	public static final String SDL_PROP_WINDOW_SHAPE_POINTER = "SDL.window.shape";
	public static final String SDL_PROP_WINDOW_HDR_ENABLED_BOOLEAN = "SDL.window.HDR_enabled";
	public static final String SDL_PROP_WINDOW_SDR_WHITE_LEVEL_FLOAT = "SDL.window.SDR_white_level";
	public static final String SDL_PROP_WINDOW_HDR_HEADROOM_FLOAT = "SDL.window.HDR_headroom";
	public static final String SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER = "SDL.window.android.window";
	public static final String SDL_PROP_WINDOW_ANDROID_SURFACE_POINTER = "SDL.window.android.surface";
	public static final String SDL_PROP_WINDOW_UIKIT_WINDOW_POINTER = "SDL.window.uikit.window";
	public static final String SDL_PROP_WINDOW_UIKIT_METAL_VIEW_TAG_NUMBER = "SDL.window.uikit.metal_view_tag";
	public static final String SDL_PROP_WINDOW_UIKIT_OPENGL_FRAMEBUFFER_NUMBER = "SDL.window.uikit.opengl.framebuffer";
	public static final String SDL_PROP_WINDOW_UIKIT_OPENGL_RENDERBUFFER_NUMBER = "SDL.window.uikit.opengl.renderbuffer";
	public static final String SDL_PROP_WINDOW_UIKIT_OPENGL_RESOLVE_FRAMEBUFFER_NUMBER = "SDL.window.uikit.opengl.resolve_framebuffer";
	public static final String SDL_PROP_WINDOW_KMSDRM_DEVICE_INDEX_NUMBER = "SDL.window.kmsdrm.dev_index";
	public static final String SDL_PROP_WINDOW_KMSDRM_DRM_FD_NUMBER = "SDL.window.kmsdrm.drm_fd";
	public static final String SDL_PROP_WINDOW_KMSDRM_GBM_DEVICE_POINTER = "SDL.window.kmsdrm.gbm_dev";
	public static final String SDL_PROP_WINDOW_COCOA_WINDOW_POINTER = "SDL.window.cocoa.window";
	public static final String SDL_PROP_WINDOW_COCOA_METAL_VIEW_TAG_NUMBER = "SDL.window.cocoa.metal_view_tag";
	public static final String SDL_PROP_WINDOW_OPENVR_OVERLAY_ID_NUMBER = "SDL.window.openvr.overlay_id";
	public static final String SDL_PROP_WINDOW_VIVANTE_DISPLAY_POINTER = "SDL.window.vivante.display";
	public static final String SDL_PROP_WINDOW_VIVANTE_WINDOW_POINTER = "SDL.window.vivante.window";
	public static final String SDL_PROP_WINDOW_VIVANTE_SURFACE_POINTER = "SDL.window.vivante.surface";
	public static final String SDL_PROP_WINDOW_WIN32_HWND_POINTER = "SDL.window.win32.hwnd";
	public static final String SDL_PROP_WINDOW_WIN32_HDC_POINTER = "SDL.window.win32.hdc";
	public static final String SDL_PROP_WINDOW_WIN32_INSTANCE_POINTER = "SDL.window.win32.instance";
	public static final String SDL_PROP_WINDOW_WAYLAND_DISPLAY_POINTER = "SDL.window.wayland.display";
	public static final String SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER = "SDL.window.wayland.surface";
	public static final String SDL_PROP_WINDOW_WAYLAND_VIEWPORT_POINTER = "SDL.window.wayland.viewport";
	public static final String SDL_PROP_WINDOW_WAYLAND_EGL_WINDOW_POINTER = "SDL.window.wayland.egl_window";
	public static final String SDL_PROP_WINDOW_WAYLAND_XDG_SURFACE_POINTER = "SDL.window.wayland.xdg_surface";
	public static final String SDL_PROP_WINDOW_WAYLAND_XDG_TOPLEVEL_POINTER = "SDL.window.wayland.xdg_toplevel";
	public static final String SDL_PROP_WINDOW_WAYLAND_XDG_TOPLEVEL_EXPORT_HANDLE_STRING = "SDL.window.wayland.xdg_toplevel_export_handle";
	public static final String SDL_PROP_WINDOW_WAYLAND_XDG_POPUP_POINTER = "SDL.window.wayland.xdg_popup";
	public static final String SDL_PROP_WINDOW_WAYLAND_XDG_POSITIONER_POINTER = "SDL.window.wayland.xdg_positioner";
	public static final String SDL_PROP_WINDOW_X11_DISPLAY_POINTER = "SDL.window.x11.display";
	public static final String SDL_PROP_WINDOW_X11_SCREEN_NUMBER = "SDL.window.x11.screen";
	public static final String SDL_PROP_WINDOW_X11_WINDOW_NUMBER = "SDL.window.x11.window";
	public static final String SDL_PROP_WINDOW_EMSCRIPTEN_CANVAS_ID_STRING = "SDL.window.emscripten.canvas_id";
	public static final String SDL_PROP_WINDOW_EMSCRIPTEN_KEYBOARD_ELEMENT_STRING = "SDL.window.emscripten.keyboard_element";
	public static final int SDL_WINDOW_SURFACE_VSYNC_DISABLED = 0;
	public static final int SDL_WINDOW_SURFACE_VSYNC_ADAPTIVE = -1;
	public static final int SDL_HITTEST_NORMAL = 0;
	public static final int SDL_HITTEST_DRAGGABLE = 1;
	public static final int SDL_HITTEST_RESIZE_TOPLEFT = 2;
	public static final int SDL_HITTEST_RESIZE_TOP = 3;
	public static final int SDL_HITTEST_RESIZE_TOPRIGHT = 4;
	public static final int SDL_HITTEST_RESIZE_RIGHT = 5;
	public static final int SDL_HITTEST_RESIZE_BOTTOMRIGHT = 6;
	public static final int SDL_HITTEST_RESIZE_BOTTOM = 7;
	public static final int SDL_HITTEST_RESIZE_BOTTOMLEFT = 8;
	public static final int SDL_HITTEST_RESIZE_LEFT = 9;
	public static final int SDL_WINDOWPOS_UNDEFINED = SDL_WINDOWPOS_UNDEFINED_MASK;
	public static final int SDL_WINDOWPOS_CENTERED = SDL_WINDOWPOS_CENTERED_MASK;

	private static final String DRIVER = "browser";
	private static final long GL_CONTEXT = 0x6C0_0001L;

	private static long desktopMode;
	private static long exclusiveMode;
	private static boolean exclusiveModeSet;
	private static final int[] glAttributes = new int[64];
	private static long currentGlWindow;
	private static long currentGlContext;
	private static int swapInterval;

	static {
		SDL_GL_ResetAttributes();
	}

	private SDLVideo() {
	}

	public static int SDL_GetNumVideoDrivers() {
		return 1;
	}

	public static String SDL_GetVideoDriver(int index) {
		return index == 0 ? DRIVER : null;
	}

	public static String SDL_GetCurrentVideoDriver() {
		return DRIVER;
	}

	public static int SDL_GetSystemTheme() {
		return SDL_SYSTEM_THEME_UNKNOWN;
	}

	private static boolean validDisplay(int displayID) {
		if (displayID == SDLBrowser.DISPLAY_ID) {
			return true;
		}
		SDLError.set("Invalid display");
		return false;
	}

	public static IntBuffer SDL_GetDisplays() {
		IntBuffer ids = memAllocInt(2);
		ids.put(0, SDLBrowser.DISPLAY_ID);
		ids.put(1, 0);
		ids.limit(1);
		return ids;
	}

	public static int SDL_GetPrimaryDisplay() {
		return SDLBrowser.DISPLAY_ID;
	}

	public static String SDL_GetDisplayName(int displayID) {
		return validDisplay(displayID) ? "Browser screen" : null;
	}

	public static boolean SDL_GetDisplayBounds(int displayID, SDL_Rect rect) {
		if (!validDisplay(displayID)) {
			return false;
		}
		rect.set(0, 0, SDLBrowser.screenWidth(), SDLBrowser.screenHeight());
		return true;
	}

	public static boolean SDL_GetDisplayUsableBounds(int displayID, SDL_Rect rect) {
		return SDL_GetDisplayBounds(displayID, rect);
	}

	public static float SDL_GetDisplayContentScale(int displayID) {
		return validDisplay(displayID) ? SDLBrowser.screenDensity() : 0.0F;
	}

	private static void fillMode(long mode) {
		SDL_DisplayMode.create(mode)
			.displayID(SDLBrowser.DISPLAY_ID)
			.format(SDLPixels.SDL_PIXELFORMAT_XRGB8888)
			.w(SDLBrowser.screenWidth())
			.h(SDLBrowser.screenHeight())
			.pixel_density(SDLBrowser.screenDensity())
			.refresh_rate(SDLBrowser.refreshRate())
			.refresh_rate_numerator((int) SDLBrowser.refreshRate())
			.refresh_rate_denominator(1);
		memPutLong(mode + SDL_DisplayMode.INTERNAL, NULL);
	}

	public static PointerBuffer SDL_GetFullscreenDisplayModes(int displayID) {
		if (!validDisplay(displayID)) {
			return null;
		}
		long block = nmemCalloc(1, 2 * 8 + SDL_DisplayMode.SIZEOF);
		long mode = block + 16;
		fillMode(mode);
		memPutLong(block, mode);
		memPutLong(block + 8, NULL);
		return memPointerBuffer(block, 1);
	}

	public static boolean SDL_GetClosestFullscreenDisplayMode(int displayID, int w, int h, float refresh_rate,
			boolean include_high_density_modes, SDL_DisplayMode closest) {
		if (!validDisplay(displayID)) {
			return false;
		}
		fillMode(closest.address());
		return true;
	}

	private static SDL_DisplayMode desktopMode() {
		if (desktopMode == NULL) {
			desktopMode = nmemCalloc(1, SDL_DisplayMode.SIZEOF);
		}
		fillMode(desktopMode);
		return SDL_DisplayMode.create(desktopMode);
	}

	public static SDL_DisplayMode SDL_GetDesktopDisplayMode(int displayID) {
		return validDisplay(displayID) ? desktopMode() : null;
	}

	public static SDL_DisplayMode SDL_GetCurrentDisplayMode(int displayID) {
		return validDisplay(displayID) ? desktopMode() : null;
	}

	public static int SDL_GetDisplayForWindow(long window) {
		return SDLBrowser.valid(window) ? SDLBrowser.DISPLAY_ID : 0;
	}

	public static int SDL_GetDisplayForRect(SDL_Rect rect) {
		return SDLBrowser.DISPLAY_ID;
	}

	public static long SDL_CreateWindow(CharSequence title, int w, int h, long flags) {
		return SDLBrowser.createWindow(title == null ? null : title.toString(), flags);
	}

	public static void SDL_DestroyWindow(long window) {
		if (window != NULL && SDLBrowser.isCanvasWindow(window) && SDLBrowser.relativeMode()) {
			SDLBrowser.setRelativeMode(false);
		}
		SDLBrowser.destroyWindow(window);
		if (currentGlWindow == window) {
			currentGlWindow = NULL;
		}
	}

	public static int SDL_GetWindowID(long window) {
		return SDLBrowser.valid(window) ? SDLBrowser.windowId(window) : 0;
	}

	public static long SDL_GetWindowFromID(int id) {
		return SDLBrowser.windowFromId(id);
	}

	public static long SDL_GetWindowFlags(long window) {
		return SDLBrowser.windowFlags(window);
	}

	public static float SDL_GetWindowPixelDensity(long window) {
		return SDLBrowser.valid(window) ? SDLBrowser.pixelDensity() : 0.0F;
	}

	public static float SDL_GetWindowDisplayScale(long window) {
		return SDLBrowser.valid(window) ? SDLBrowser.pixelDensity() : 0.0F;
	}

	public static boolean SDL_SetWindowFullscreenMode(long window, SDL_DisplayMode mode) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		if (mode == null) {
			exclusiveModeSet = false;
		} else {
			if (exclusiveMode == NULL) {
				exclusiveMode = nmemCalloc(1, SDL_DisplayMode.SIZEOF);
			}
			memCopy(mode.address(), exclusiveMode, SDL_DisplayMode.SIZEOF);
			exclusiveModeSet = true;
		}
		return true;
	}

	public static SDL_DisplayMode SDL_GetWindowFullscreenMode(long window) {
		return exclusiveModeSet && SDLBrowser.valid(window) ? SDL_DisplayMode.create(exclusiveMode) : null;
	}

	public static int SDL_GetWindowPixelFormat(long window) {
		return SDLPixels.SDL_PIXELFORMAT_XRGB8888;
	}

	public static boolean SDL_SetWindowTitle(long window, CharSequence title) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		SDLBrowser.setTitle(window, title == null ? "" : title.toString());
		return true;
	}

	public static String SDL_GetWindowTitle(long window) {
		return SDLBrowser.title(window);
	}

	public static boolean SDL_SetWindowIcon(long window, SDL_Surface icon) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowPosition(long window, int x, int y) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_GetWindowPosition(long window, IntBuffer x, IntBuffer y) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		put(x, 0);
		put(y, 0);
		return true;
	}

	public static boolean SDL_SetWindowSize(long window, int w, int h) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		SDLBrowser.requestSize(window, w, h);
		return true;
	}

	public static boolean SDL_GetWindowSize(long window, IntBuffer w, IntBuffer h) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		put(w, SDLBrowser.cssWidth());
		put(h, SDLBrowser.cssHeight());
		return true;
	}

	public static boolean SDL_GetWindowSafeArea(long window, SDL_Rect rect) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		rect.set(0, 0, SDLBrowser.cssWidth(), SDLBrowser.cssHeight());
		return true;
	}

	public static boolean SDL_GetWindowSizeInPixels(long window, IntBuffer w, IntBuffer h) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		put(w, SDLBrowser.pixelWidth());
		put(h, SDLBrowser.pixelHeight());
		return true;
	}

	public static boolean SDL_GetWindowBordersSize(long window, IntBuffer top, IntBuffer left, IntBuffer bottom, IntBuffer right) {
		put(top, 0);
		put(left, 0);
		put(bottom, 0);
		put(right, 0);
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowMinimumSize(long window, int min_w, int min_h) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_GetWindowMinimumSize(long window, IntBuffer w, IntBuffer h) {
		put(w, 0);
		put(h, 0);
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowMaximumSize(long window, int max_w, int max_h) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_GetWindowMaximumSize(long window, IntBuffer w, IntBuffer h) {
		put(w, 0);
		put(h, 0);
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowAspectRatio(long window, float min_aspect, float max_aspect) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_GetWindowAspectRatio(long window, FloatBuffer min_aspect, FloatBuffer max_aspect) {
		if (min_aspect != null) {
			min_aspect.put(min_aspect.position(), 0.0F);
		}
		if (max_aspect != null) {
			max_aspect.put(max_aspect.position(), 0.0F);
		}
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowBordered(long window, boolean bordered) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowResizable(long window, boolean resizable) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowAlwaysOnTop(long window, boolean on_top) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowFillDocument(long window, boolean fill) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_ShowWindow(long window) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_HideWindow(long window) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_RaiseWindow(long window) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_MaximizeWindow(long window) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_MinimizeWindow(long window) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_RestoreWindow(long window) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowFullscreen(long window, boolean fullscreen) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		return !SDLBrowser.isCanvasWindow(window) || SDLBrowser.setFullscreen(fullscreen);
	}

	public static boolean SDL_SyncWindow(long window) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowKeyboardGrab(long window, boolean grabbed) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_SetWindowMouseGrab(long window, boolean grabbed) {
		if (!SDLBrowser.valid(window)) {
			return false;
		}
		SDLBrowser.setMouseGrab(window, grabbed);
		return true;
	}

	public static boolean SDL_GetWindowKeyboardGrab(long window) {
		return false;
	}

	public static boolean SDL_GetWindowMouseGrab(long window) {
		return SDLBrowser.mouseGrab(window);
	}

	public static long SDL_GetGrabbedWindow() {
		long canvas = SDLBrowser.canvasWindowHandle();
		return canvas != NULL && SDLBrowser.mouseGrab(canvas) ? canvas : NULL;
	}

	public static boolean SDL_SetWindowOpacity(long window, float opacity) {
		return SDLBrowser.valid(window);
	}

	public static float SDL_GetWindowOpacity(long window) {
		return SDLBrowser.valid(window) ? 1.0F : -1.0F;
	}

	public static boolean SDL_FlashWindow(long window, int operation) {
		return SDLBrowser.valid(window);
	}

	public static boolean SDL_ScreenSaverEnabled() {
		return true;
	}

	public static boolean SDL_EnableScreenSaver() {
		return true;
	}

	public static boolean SDL_DisableScreenSaver() {
		return true;
	}

	public static boolean SDL_GL_LoadLibrary(CharSequence path) {
		return true;
	}

	public static long SDL_GL_GetProcAddress(CharSequence proc) {
		if (proc == null) {
			return NULL;
		}
		return 0x6C1_0000_0000L | (proc.toString().hashCode() & 0xFFFF_FFFFL);
	}

	public static void SDL_GL_UnloadLibrary() {
	}

	public static boolean SDL_GL_ExtensionSupported(CharSequence extension) {
		return false;
	}

	public static void SDL_GL_ResetAttributes() {
		java.util.Arrays.fill(glAttributes, 0);
		glAttributes[SDL_GL_RED_SIZE] = 8;
		glAttributes[SDL_GL_GREEN_SIZE] = 8;
		glAttributes[SDL_GL_BLUE_SIZE] = 8;
		glAttributes[SDL_GL_DEPTH_SIZE] = 24;
		glAttributes[SDL_GL_STENCIL_SIZE] = 8;
		glAttributes[SDL_GL_DOUBLEBUFFER] = 1;
		glAttributes[SDL_GL_ACCELERATED_VISUAL] = 1;
		glAttributes[SDL_GL_CONTEXT_MAJOR_VERSION] = 3;
		glAttributes[SDL_GL_CONTEXT_MINOR_VERSION] = 3;
		glAttributes[SDL_GL_CONTEXT_PROFILE_MASK] = SDL_GL_CONTEXT_PROFILE_ES;
	}

	public static boolean SDL_GL_SetAttribute(int attr, int value) {
		if (attr < 0 || attr >= glAttributes.length) {
			return SDLError.SDL_SetError("Unknown OpenGL attribute");
		}
		glAttributes[attr] = value;
		return true;
	}

	public static boolean SDL_GL_GetAttribute(int attr, IntBuffer value) {
		if (attr < 0 || attr >= glAttributes.length) {
			return SDLError.SDL_SetError("Unknown OpenGL attribute");
		}
		put(value, glAttributes[attr]);
		return true;
	}

	public static long SDL_GL_CreateContext(long window) {
		if (!SDLBrowser.valid(window)) {
			return NULL;
		}
		currentGlWindow = window;
		currentGlContext = GL_CONTEXT;
		return GL_CONTEXT;
	}

	public static boolean SDL_GL_MakeCurrent(long window, long context) {
		currentGlWindow = window;
		currentGlContext = context;
		return true;
	}

	public static long SDL_GL_GetCurrentWindow() {
		return currentGlWindow;
	}

	public static long SDL_GL_GetCurrentContext() {
		return currentGlContext;
	}

	public static boolean SDL_GL_SetSwapInterval(int interval) {
		swapInterval = interval;
		return true;
	}

	public static boolean SDL_GL_GetSwapInterval(IntBuffer interval) {
		put(interval, swapInterval);
		return true;
	}

	public static boolean SDL_GL_SwapWindow(long window) {
		return true;
	}

	public static boolean SDL_GL_DestroyContext(long context) {
		if (currentGlContext == context) {
			currentGlContext = NULL;
		}
		return true;
	}

	public static int SDL_WINDOWPOS_UNDEFINED_DISPLAY(int X) {
		return SDL_WINDOWPOS_UNDEFINED_MASK | X;
	}

	public static boolean SDL_WINDOWPOS_ISUNDEFINED(int X) {
		return (X & 0xFFFF0000) == SDL_WINDOWPOS_UNDEFINED_MASK;
	}

	public static int SDL_WINDOWPOS_CENTERED_DISPLAY(int X) {
		return SDL_WINDOWPOS_CENTERED_MASK | X;
	}

	public static boolean SDL_WINDOWPOS_ISCENTERED(int X) {
		return (X & 0xFFFF0000) == SDL_WINDOWPOS_CENTERED_MASK;
	}

	private static void put(IntBuffer buffer, int value) {
		if (buffer != null) {
			buffer.put(buffer.position(), value);
		}
	}
}
