// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class RelayStore {
	public static final String KEY = "minecraft_web:relays";
	public static final String KEY_CUSTOM = "minecraft_web:relays.custom";
	public static final String KEY_SELECTED = "minecraft_web:relay.selected";
	public static final String AUTO = "";

	public static final String STATUS_OK = "ok";
	public static final String STATUS_FAIL = "fail";
	public static final String STATUS_CHECKING = "checking";

	private static final String PLACEHOLDER_HOST = "relay-";
	private static final String PLACEHOLDER_DOMAIN = ".invalid";

	private RelayStore() {
	}

	public static String builtin(final int n) {
		return "wss://" + PLACEHOLDER_HOST + n + PLACEHOLDER_DOMAIN + "/socket/";
	}

	public static int builtinIndex(final String url) {
		String socket = normalize(url);
		if (!socket.startsWith("wss://")) {
			return 0;
		}
		String host = socket.substring(6, socket.length() - "/socket/".length());
		if (!host.startsWith(PLACEHOLDER_HOST) || !host.endsWith(PLACEHOLDER_DOMAIN)) {
			return 0;
		}
		String digits = host.substring(PLACEHOLDER_HOST.length(), host.length() - PLACEHOLDER_DOMAIN.length());
		if (digits.isEmpty() || digits.length() > 3) {
			return 0;
		}
		for (int i = 0; i < digits.length(); i++) {
			if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
				return 0;
			}
		}
		return Integer.parseInt(digits);
	}

	public static List<String> all(final int builtins, final List<String> customs) {
		List<String> out = new ArrayList<>();
		for (int i = 1; i <= builtins; i++) {
			out.add(builtin(i));
		}
		for (String custom : customs) {
			if (!out.contains(custom)) {
				out.add(custom);
			}
		}
		return out;
	}

	public static String label(final String url, final int builtins, final List<String> customs) {
		int index = all(builtins, customs).indexOf(normalize(url));
		return index < 0 ? "" : "Relay " + (index + 1);
	}

	public static boolean isCustom(final String url, final int builtins, final List<String> customs) {
		String socket = normalize(url);
		int n = builtinIndex(socket);
		return !(n >= 1 && n <= builtins) && customs.contains(socket);
	}

	public static String resolve(final String selected, final int builtins, final List<String> customs,
			final Function<String, String> status) {
		List<String> all = all(builtins, customs);
		String explicit = normalize(selected);
		if (!explicit.isEmpty() && all.contains(explicit)) {
			return explicit;
		}
		for (String relay : all) {
			if (STATUS_OK.equals(status.apply(relay))) {
				return relay;
			}
		}
		for (String relay : all) {
			if (!STATUS_FAIL.equals(status.apply(relay))) {
				return relay;
			}
		}
		return all.isEmpty() ? "" : all.get(0);
	}

	public static List<String> addCustom(final List<String> customs, final String url) {
		String clean = normalize(url);
		if (!clean.isEmpty() && builtinIndex(clean) == 0 && !customs.contains(clean)) {
			customs.add(clean);
		}
		return customs;
	}

	public static List<String> removeCustom(final List<String> customs, final String url) {
		customs.remove(normalize(url));
		return customs;
	}

	public static String[] migrate(final String legacy, final Function<String, String> alias) {
		List<String> customs = new ArrayList<>();
		String selected = AUTO;
		boolean first = true;
		for (String entry : parse(legacy)) {
			String mapped = normalize(alias.apply(entry));
			if (mapped.isEmpty()) {
				mapped = entry;
			}
			boolean bundled = builtinIndex(mapped) > 0;
			if (!bundled && !customs.contains(mapped)) {
				customs.add(mapped);
			}
			if (first) {
				selected = bundled ? AUTO : mapped;
				first = false;
			}
		}
		return new String[] { encode(customs), selected };
	}

	public static List<String> parse(final String raw) {
		List<String> out = new ArrayList<>();
		if (raw != null) {
			for (String line : raw.split("\n")) {
				String url = normalize(line);
				if (!url.isEmpty() && !out.contains(url)) {
					out.add(url);
				}
			}
		}
		return out;
	}

	public static String encode(final List<String> list) {
		return String.join("\n", list);
	}

	public static String normalize(final String url) {
		String s = url == null ? "" : url.trim();
		String socketScheme;
		String rest;
		if (s.startsWith("http://")) {
			socketScheme = "ws://";
			rest = s.substring(7);
		} else if (s.startsWith("https://")) {
			socketScheme = "wss://";
			rest = s.substring(8);
		} else if (s.startsWith("ws://")) {
			socketScheme = "ws://";
			rest = s.substring(5);
		} else if (s.startsWith("wss://")) {
			socketScheme = "wss://";
			rest = s.substring(6);
		} else {
			return "";
		}
		if (rest.indexOf('?') >= 0 || rest.indexOf('#') >= 0) {
			return "";
		}
		int slash = rest.indexOf('/');
		String authority = slash < 0 ? rest : rest.substring(0, slash);
		String path = slash < 0 ? "" : rest.substring(slash);
		if (authority.isEmpty() || authority.indexOf('@') >= 0
				|| !(path.isEmpty() || path.equals("/") || path.equals("/socket") || path.equals("/socket/"))) {
			return "";
		}
		return socketScheme + authority.toLowerCase(java.util.Locale.ROOT) + "/socket/";
	}

	public static String httpBase(final String relay) {
		String socket = normalize(relay);
		if (socket.startsWith("ws://")) {
			return "http://" + socket.substring(5, socket.length() - "/socket/".length());
		}
		if (socket.startsWith("wss://")) {
			return "https://" + socket.substring(6, socket.length() - "/socket/".length());
		}
		return "";
	}
}
