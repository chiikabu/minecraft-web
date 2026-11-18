// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public final class LanServers {
	public static final String LOCKED_SUFFIX = " [password]";
	public static final String LOOPBACK = "127.0.0.1";
	private static final int REPOLL_MS = 3000;
	private static final Map<String, String> PENDING = new HashMap<>();
	private static List<World> worlds = new ArrayList<>();
	private static int generation;
	private static boolean polled;

	private LanServers() {
	}

	public static final class World {
		public final String name;
		public final String address;
		public final boolean locked;

		World(final String announced, final int port) {
			boolean locked = announced.endsWith(LOCKED_SUFFIX);
			this.name = locked ? announced.substring(0, announced.length() - LOCKED_SUFFIX.length()) : announced;
			this.address = address(port);
			this.locked = locked;
		}
	}

	public static String address(final int port) {
		return LOOPBACK + ":" + port;
	}

	public static List<World> worlds() {
		return worlds;
	}

	public static boolean polled() {
		return polled;
	}

	public static void startPolling() {
		polled = false;
		poll(++generation);
	}

	public static void stopPolling() {
		generation++;
	}

	private static void poll(final int gen) {
		if (gen != generation) {
			return;
		}
		if (WebRelayAuth.relaySocketUrl().isEmpty()) {
			polled = true;
			worlds = new ArrayList<>();
			return;
		}
		WebRelayRpc.post("/lan", "{}", (status, body) -> {
			if (gen != generation) {
				return;
			}
			List<World> found = new ArrayList<>();
			if (status == 200) {
				parse(body, (name, port) -> {
					if (port > 0 && port <= 65535) {
						found.add(new World(name, port));
					}
				});
			}
			worlds = found;
			polled = true;
			setTimeoutJs(() -> poll(gen), REPOLL_MS);
		});
	}

	public static void usePassword(final String address, final String password) {
		String hash = LanGate.hash(password);
		if (hash.isEmpty()) {
			PENDING.remove(key(address));
		} else {
			PENDING.put(key(address), hash);
		}
	}

	public static String takePassword(final String host, final int port) {
		String hash = PENDING.remove(key(host + ":" + port));
		return hash == null ? "" : hash;
	}

	private static String key(final String address) {
		return address.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private static final String ALPHABET = "ACDEFGHJKMNPRTUVWXY34679";
	private static final int LENGTH = 6;
	private static final int BITS = 27;
	private static final long MASK = (1L << BITS) - 1;
	private static final long MULTIPLY = 0x2F5A3B1L;
	private static final long INVERSE = inverse(MULTIPLY);
	private static final long ADD = 0x4C1D7E2L;

	public static String encodeCode(final int relay, final int port) {
		if (port <= 0 || port > 65535 || relay < 0 || relay > 15) {
			return "";
		}
		long value = ((long)relay << 23 | (long)port << 7 | check(relay, port)) & MASK;
		long scrambled = (value * MULTIPLY + ADD) & MASK;
		char[] out = new char[LENGTH];
		for (int i = LENGTH - 1; i >= 0; i--) {
			out[i] = ALPHABET.charAt((int)(scrambled % ALPHABET.length()));
			scrambled /= ALPHABET.length();
		}
		return new String(out);
	}

	public static int[] decodeCode(final String code) {
		String clean = code == null ? "" : code.toUpperCase(java.util.Locale.ROOT).replace("-", "").replace(" ", "");
		if (clean.length() != LENGTH) {
			return null;
		}
		long scrambled = 0;
		for (int i = 0; i < LENGTH; i++) {
			int digit = ALPHABET.indexOf(clean.charAt(i));
			if (digit < 0) {
				return null;
			}
			scrambled = scrambled * ALPHABET.length() + digit;
		}
		if (scrambled > MASK) {
			return null;
		}
		long value = ((scrambled - ADD) * INVERSE) & MASK;
		int relay = (int)(value >> 23) & 15;
		int port = (int)(value >> 7) & 0xffff;
		if (port == 0 || (value & 127) != check(relay, port)) {
			return null;
		}
		return new int[] { relay, port };
	}

	private static int check(final int relay, final int port) {
		return (relay * 37 + (port >> 8) * 11 + (port & 255) * 13 + 0x2b) & 127;
	}

	private static long inverse(final long odd) {
		long x = odd;
		for (int i = 0; i < 5; i++) {
			x = (x * (2 - odd * x)) & MASK;
		}
		return x;
	}

	@JSFunctor
	private interface Sink extends JSObject {
		void add(String name, int port);
	}

	@JSFunctor
	private interface Task extends JSObject {
		void run();
	}

	@JSBody(params = { "cb", "ms" }, script = "setTimeout(function () { cb(); }, ms);")
	private static native void setTimeoutJs(Task cb, int ms);

	@JSBody(params = { "body", "sink" }, script = ""
		+ "try { var j = JSON.parse(body); var s = (j && j.servers) || [];"
		+ "  for (var i = 0; i < s.length; i++) { var e = s[i];"
		+ "    if (e && e.port != null) { sink(String(e.name == null ? '' : e.name), e.port | 0); } }"
		+ "} catch (e) {}")
	private static native void parse(String body, Sink sink);
}
