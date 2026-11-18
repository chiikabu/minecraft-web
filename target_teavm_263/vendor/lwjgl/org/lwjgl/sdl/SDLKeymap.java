// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.util.HashMap;
import java.util.Map;

final class SDLKeymap {
	static final int SCANCODE_COUNT = 512;
	static final int SCANCODE_MASK = 1 << 30;

	static final int SC_A = 4;
	static final int SC_Z = 29;
	static final int SC_1 = 30;
	static final int SC_0 = 39;
	static final int SC_RETURN = 40;
	static final int SC_ESCAPE = 41;
	static final int SC_BACKSPACE = 42;
	static final int SC_TAB = 43;
	static final int SC_SPACE = 44;
	static final int SC_APOSTROPHE = 52;
	static final int SC_SLASH = 56;
	static final int SC_CAPSLOCK = 57;
	static final int SC_F1 = 58;
	static final int SC_F12 = 69;
	static final int SC_HOME = 74;
	static final int SC_PAGEUP = 75;
	static final int SC_DELETE = 76;
	static final int SC_END = 77;
	static final int SC_PAGEDOWN = 78;
	static final int SC_RIGHT = 79;
	static final int SC_LEFT = 80;
	static final int SC_DOWN = 81;
	static final int SC_UP = 82;
	static final int SC_LCTRL = 224;
	static final int SC_LSHIFT = 225;
	static final int SC_LALT = 226;
	static final int SC_LGUI = 227;
	static final int SC_RCTRL = 228;
	static final int SC_RSHIFT = 229;
	static final int SC_RALT = 230;
	static final int SC_RGUI = 231;

	private static final Map<String, Integer> CODES = buildCodes();
	private static final String[] NAMES = buildNames();
	private static final int[] layout = new int[SCANCODE_COUNT];

	private SDLKeymap() {
	}

	static int scancode(String domCode) {
		if (domCode == null) {
			return 0;
		}
		Integer scancode = CODES.get(domCode);
		return scancode == null ? 0 : scancode;
	}

	static int keycode(int scancode) {
		if (scancode <= 0 || scancode >= SCANCODE_COUNT) {
			return 0;
		}
		int learned = layout[scancode];
		return learned != 0 ? learned : defaultKeycode(scancode);
	}

	static boolean learn(int scancode, String printed) {
		if (scancode <= 0 || scancode >= SCANCODE_COUNT || printed == null || printed.isEmpty()
				|| printed.codePointCount(0, printed.length()) != 1) {
			return false;
		}
		int codepoint = Character.toLowerCase(printed.codePointAt(0));
		if (codepoint < 32 || codepoint == 127) {
			return false;
		}
		if (scancode >= SC_A && scancode <= SC_Z) {
			if (!(codepoint >= 'a' && codepoint <= 'z')) {
				codepoint = 0;
			}
		} else if (scancode >= SC_1 && scancode <= SC_0) {
			if (!(codepoint >= '0' && codepoint <= '9')) {
				codepoint = 0;
			}
		} else if (!hasDefaultPrintable(scancode)) {
			return false;
		}
		if (codepoint == defaultKeycode(scancode)) {
			codepoint = 0;
		}
		if (layout[scancode] == codepoint) {
			return false;
		}
		layout[scancode] = codepoint;
		return true;
	}

	private static boolean hasDefaultPrintable(int scancode) {
		return (scancode >= 45 && scancode <= 56) || scancode == 100;
	}

	static int defaultKeycode(int scancode) {
		if (scancode >= SC_A && scancode <= SC_Z) {
			return 'a' + scancode - SC_A;
		}
		if (scancode >= SC_1 && scancode < SC_0) {
			return '1' + scancode - SC_1;
		}
		switch (scancode) {
			case SC_0: return '0';
			case SC_RETURN: return '\r';
			case SC_ESCAPE: return 0x1B;
			case SC_BACKSPACE: return 0x08;
			case SC_TAB: return '\t';
			case SC_SPACE: return ' ';
			case 45: return '-';
			case 46: return '=';
			case 47: return '[';
			case 48: return ']';
			case 49: return '\\';
			case 50: return '#';
			case 51: return ';';
			case 52: return '\'';
			case 53: return '`';
			case 54: return ',';
			case 55: return '.';
			case 56: return '/';
			case SC_DELETE: return 0x7F;
			case 100: return '\\';
			default: return scancode == 0 ? 0 : scancode | SCANCODE_MASK;
		}
	}

	static int scancodeForKey(int keycode) {
		if ((keycode & SCANCODE_MASK) != 0) {
			int scancode = keycode & ~SCANCODE_MASK;
			return scancode < SCANCODE_COUNT ? scancode : 0;
		}
		for (int scancode = 1; scancode < SCANCODE_COUNT; scancode++) {
			if (keycode(scancode) == keycode) {
				return scancode;
			}
		}
		return 0;
	}

	static String scancodeName(int scancode) {
		if (scancode <= 0 || scancode >= NAMES.length || NAMES[scancode] == null) {
			return "";
		}
		return NAMES[scancode];
	}

	static String keyName(int keycode) {
		switch (keycode) {
			case 0: return "";
			case 0x08: return "Backspace";
			case '\t': return "Tab";
			case '\r': return "Return";
			case 0x1B: return "Escape";
			case ' ': return "Space";
			case 0x7F: return "Delete";
			default:
				break;
		}
		if ((keycode & SCANCODE_MASK) != 0) {
			return scancodeName(keycode & ~SCANCODE_MASK & (SCANCODE_COUNT - 1));
		}
		if (keycode < 32 || keycode > Character.MAX_CODE_POINT) {
			return "";
		}
		return new String(Character.toChars(Character.toUpperCase(keycode)));
	}

	private static Map<String, Integer> buildCodes() {
		Map<String, Integer> m = new HashMap<>(256);
		for (char c = 'A'; c <= 'Z'; c++) {
			m.put("Key" + c, SC_A + (c - 'A'));
		}
		for (int d = 1; d <= 9; d++) {
			m.put("Digit" + d, SC_1 + d - 1);
			m.put("Numpad" + d, 89 + d - 1);
		}
		m.put("Digit0", SC_0);
		m.put("Numpad0", 98);
		for (int f = 1; f <= 12; f++) {
			m.put("F" + f, SC_F1 + f - 1);
		}
		for (int f = 13; f <= 24; f++) {
			m.put("F" + f, 104 + f - 13);
		}
		m.put("Enter", SC_RETURN);
		m.put("Escape", SC_ESCAPE);
		m.put("Backspace", SC_BACKSPACE);
		m.put("Tab", SC_TAB);
		m.put("Space", SC_SPACE);
		m.put("Minus", 45);
		m.put("Equal", 46);
		m.put("BracketLeft", 47);
		m.put("BracketRight", 48);
		m.put("Backslash", 49);
		m.put("Semicolon", 51);
		m.put("Quote", SC_APOSTROPHE);
		m.put("Backquote", 53);
		m.put("Comma", 54);
		m.put("Period", 55);
		m.put("Slash", SC_SLASH);
		m.put("CapsLock", SC_CAPSLOCK);
		m.put("PrintScreen", 70);
		m.put("ScrollLock", 71);
		m.put("Pause", 72);
		m.put("Insert", 73);
		m.put("Home", SC_HOME);
		m.put("PageUp", SC_PAGEUP);
		m.put("Delete", SC_DELETE);
		m.put("End", SC_END);
		m.put("PageDown", SC_PAGEDOWN);
		m.put("ArrowRight", SC_RIGHT);
		m.put("ArrowLeft", SC_LEFT);
		m.put("ArrowDown", SC_DOWN);
		m.put("ArrowUp", SC_UP);
		m.put("NumLock", 83);
		m.put("NumpadDivide", 84);
		m.put("NumpadMultiply", 85);
		m.put("NumpadSubtract", 86);
		m.put("NumpadAdd", 87);
		m.put("NumpadEnter", 88);
		m.put("NumpadDecimal", 99);
		m.put("IntlBackslash", 100);
		m.put("ContextMenu", 101);
		m.put("Power", 102);
		m.put("NumpadEqual", 103);
		m.put("Help", 117);
		m.put("AudioVolumeMute", 127);
		m.put("VolumeMute", 127);
		m.put("AudioVolumeUp", 128);
		m.put("VolumeUp", 128);
		m.put("AudioVolumeDown", 129);
		m.put("VolumeDown", 129);
		m.put("NumpadComma", 133);
		m.put("IntlRo", 135);
		m.put("KanaMode", 136);
		m.put("IntlYen", 137);
		m.put("Convert", 138);
		m.put("NonConvert", 139);
		m.put("Lang1", 144);
		m.put("Lang2", 145);
		m.put("ControlLeft", SC_LCTRL);
		m.put("ShiftLeft", SC_LSHIFT);
		m.put("AltLeft", SC_LALT);
		m.put("MetaLeft", SC_LGUI);
		m.put("OSLeft", SC_LGUI);
		m.put("ControlRight", SC_RCTRL);
		m.put("ShiftRight", SC_RSHIFT);
		m.put("AltRight", SC_RALT);
		m.put("MetaRight", SC_RGUI);
		m.put("OSRight", SC_RGUI);
		return m;
	}

	private static String[] buildNames() {
		String[] n = new String[SCANCODE_COUNT];
		for (int i = 0; i < 26; i++) {
			n[SC_A + i] = String.valueOf((char) ('A' + i));
		}
		for (int i = 0; i < 9; i++) {
			n[SC_1 + i] = String.valueOf((char) ('1' + i));
			n[89 + i] = "Keypad " + (char) ('1' + i);
		}
		n[SC_0] = "0";
		n[98] = "Keypad 0";
		for (int f = 1; f <= 12; f++) {
			n[SC_F1 + f - 1] = "F" + f;
		}
		for (int f = 13; f <= 24; f++) {
			n[104 + f - 13] = "F" + f;
		}
		String[][] rest = {
			{"40", "Return"}, {"41", "Escape"}, {"42", "Backspace"}, {"43", "Tab"}, {"44", "Space"},
			{"45", "-"}, {"46", "="}, {"47", "["}, {"48", "]"}, {"49", "\\"}, {"50", "#"}, {"51", ";"},
			{"52", "'"}, {"53", "`"}, {"54", ","}, {"55", "."}, {"56", "/"}, {"57", "CapsLock"},
			{"70", "PrintScreen"}, {"71", "ScrollLock"}, {"72", "Pause"}, {"73", "Insert"}, {"74", "Home"},
			{"75", "PageUp"}, {"76", "Delete"}, {"77", "End"}, {"78", "PageDown"}, {"79", "Right"},
			{"80", "Left"}, {"81", "Down"}, {"82", "Up"}, {"83", "Numlock"}, {"84", "Keypad /"},
			{"85", "Keypad *"}, {"86", "Keypad -"}, {"87", "Keypad +"}, {"88", "Keypad Enter"},
			{"99", "Keypad ."}, {"100", "NonUSBackslash"}, {"101", "Application"}, {"102", "Power"},
			{"103", "Keypad ="}, {"117", "Help"}, {"127", "Mute"}, {"128", "VolumeUp"},
			{"129", "VolumeDown"}, {"133", "Keypad ,"}, {"135", "International 1"},
			{"136", "International 2"}, {"137", "International 3"}, {"138", "International 4"},
			{"139", "International 5"}, {"144", "Language 1"}, {"145", "Language 2"},
			{"224", "Left Ctrl"}, {"225", "Left Shift"}, {"226", "Left Alt"}, {"227", "Left GUI"},
			{"228", "Right Ctrl"}, {"229", "Right Shift"}, {"230", "Right Alt"}, {"231", "Right GUI"},
		};
		for (String[] entry : rest) {
			n[Integer.parseInt(entry[0])] = entry[1];
		}
		return n;
	}

	static int scancodeForGlfwKey(int glfwKey) {
		if (glfwKey >= 'A' && glfwKey <= 'Z') {
			return SC_A + glfwKey - 'A';
		}
		if (glfwKey >= '1' && glfwKey <= '9') {
			return SC_1 + glfwKey - '1';
		}
		switch (glfwKey) {
			case '0': return SC_0;
			case 32: return SC_SPACE;
			case 256: return SC_ESCAPE;
			case 257: return SC_RETURN;
			case 258: return SC_TAB;
			case 259: return SC_BACKSPACE;
			case 340: return SC_LSHIFT;
			case 341: return SC_LCTRL;
			case 342: return SC_LALT;
			default: return 0;
		}
	}
}
