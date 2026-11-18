// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class LanGate {
	public static final String MARK = "\0MCWLAN\0";
	public static final String CAPS_MARK = "\0MCWCAP\0";
	public static final String CAP_BATCH = "b";
	public static final String NEED_PASSWORD = "This world needs a password";
	public static final String WRONG_PASSWORD = "Wrong password for this world";
	private static final int MAX_HOST = 255;
	private static final int INTENT_STATUS = 1;

	private LanGate() {
	}

	public static String hash(final String password) {
		if (password == null || password.isEmpty()) {
			return "";
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(("mcw-lan\0" + password).getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder(32);
			for (int i = 0; i < 16; i++) {
				out.append(Character.forDigit((digest[i] >> 4) & 15, 16)).append(Character.forDigit(digest[i] & 15, 16));
			}
			return out.toString();
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	public static byte[] tagHandshake(final byte[] packet, final String passwordHash) {
		return tagHandshake(packet, passwordHash, "");
	}

	public static byte[] tagHandshake(final byte[] packet, final String passwordHash, final String caps) {
		Handshake h = Handshake.parse(packet);
		boolean hasHash = passwordHash != null && !passwordHash.isEmpty();
		boolean hasCaps = caps != null && !caps.isEmpty();
		if (h == null || !hasHash && !hasCaps) {
			return packet;
		}
		String host = stripTag(h.host);
		String password = hasHash ? MARK + passwordHash : "";
		String tagged = host + (hasCaps ? CAPS_MARK + caps : "") + password;
		if (tagged.length() > MAX_HOST) {
			tagged = host + password;
			if (!hasHash || tagged.length() > MAX_HOST) {
				return packet;
			}
		}
		return h.withHost(tagged).encode();
	}

	public static byte[] tagFramedHandshake(final byte[] framed, final String passwordHash) {
		return tagFramedHandshake(framed, passwordHash, "");
	}

	public static byte[] tagFramedHandshake(final byte[] framed, final String passwordHash, final String caps) {
		if ((passwordHash == null || passwordHash.isEmpty()) && (caps == null || caps.isEmpty())) {
			return framed;
		}
		try {
			int[] at = { 0 };
			int length = readVarInt(framed, at);
			if (length <= 0 || at[0] + length != framed.length) {
				return framed;
			}
			byte[] packet = java.util.Arrays.copyOfRange(framed, at[0], framed.length);
			byte[] tagged = tagHandshake(packet, passwordHash, caps);
			if (tagged == packet) {
				return framed;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			writeVarInt(out, tagged.length);
			out.write(tagged, 0, tagged.length);
			return out.toByteArray();
		} catch (RuntimeException e) {
			return framed;
		}
	}

	public static final class Decision {
		public final byte[] forward;
		public final byte[] reply;
		public final String caps;

		private Decision(final byte[] forward, final byte[] reply, final String caps) {
			this.forward = forward;
			this.reply = reply;
			this.caps = caps;
		}
	}

	public static Decision check(final byte[] packet, final String requiredHash) {
		Handshake h = Handshake.parse(packet);
		if (h == null) {
			return new Decision(packet, null, "");
		}
		int mark = h.host.indexOf(MARK);
		String given = mark < 0 ? "" : h.host.substring(mark + MARK.length());
		String front = mark < 0 ? h.host : h.host.substring(0, mark);
		int capsAt = front.indexOf(CAPS_MARK);
		String caps = capsAt < 0 ? "" : front.substring(capsAt + CAPS_MARK.length());
		String host = capsAt < 0 ? front : front.substring(0, capsAt);
		byte[] clean = host.length() == h.host.length() ? packet : h.withHost(host).encode();
		if (requiredHash == null || requiredHash.isEmpty() || h.intent == INTENT_STATUS || requiredHash.equals(given)) {
			return new Decision(clean, null, caps);
		}
		return new Decision(null, loginDisconnect(given.isEmpty() ? NEED_PASSWORD : WRONG_PASSWORD), "");
	}

	static byte[] loginDisconnect(final String text) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeVarInt(out, 0);
		writeString(out, "{\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
		return out.toByteArray();
	}

	static String stripTag(final String host) {
		int mark = host.indexOf(MARK);
		String front = mark < 0 ? host : host.substring(0, mark);
		int caps = front.indexOf(CAPS_MARK);
		return caps < 0 ? front : front.substring(0, caps);
	}

	static final class Handshake {
		final int protocol;
		final String host;
		final int port;
		final int intent;

		Handshake(final int protocol, final String host, final int port, final int intent) {
			this.protocol = protocol;
			this.host = host;
			this.port = port;
			this.intent = intent;
		}

		Handshake withHost(final String newHost) {
			return new Handshake(this.protocol, newHost, this.port, this.intent);
		}

		static Handshake parse(final byte[] packet) {
			try {
				int[] at = { 0 };
				if (readVarInt(packet, at) != 0) {
					return null;
				}
				int protocol = readVarInt(packet, at);
				int length = readVarInt(packet, at);
				if (length < 0 || length > MAX_HOST * 4 || at[0] + length + 2 > packet.length) {
					return null;
				}
				String host = new String(packet, at[0], length, StandardCharsets.UTF_8);
				at[0] += length;
				int port = (packet[at[0]] & 255) << 8 | packet[at[0] + 1] & 255;
				at[0] += 2;
				int intent = readVarInt(packet, at);
				if (at[0] != packet.length || intent < 1 || intent > 3) {
					return null;
				}
				return new Handshake(protocol, host, port, intent);
			} catch (RuntimeException e) {
				return null;
			}
		}

		byte[] encode() {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			writeVarInt(out, 0);
			writeVarInt(out, this.protocol);
			writeString(out, this.host);
			out.write(this.port >> 8 & 255);
			out.write(this.port & 255);
			writeVarInt(out, this.intent);
			return out.toByteArray();
		}
	}

	private static int readVarInt(final byte[] data, final int[] at) {
		int value = 0;
		for (int shift = 0; shift < 35; shift += 7) {
			if (at[0] >= data.length) {
				throw new IndexOutOfBoundsException();
			}
			int b = data[at[0]++] & 255;
			value |= (b & 127) << shift;
			if ((b & 128) == 0) {
				return value;
			}
		}
		throw new IllegalArgumentException("varint too long");
	}

	private static void writeVarInt(final ByteArrayOutputStream out, int value) {
		while ((value & ~127) != 0) {
			out.write(value & 127 | 128);
			value >>>= 7;
		}
		out.write(value);
	}

	private static void writeString(final ByteArrayOutputStream out, final String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, bytes.length);
		out.write(bytes, 0, bytes.length);
	}
}
