// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

public final class RelayTransport {
	public static final String KEY = "minecraft_web:relay.transport";
	public static final String AUTO = "auto";
	public static final String WEBRTC = "webrtc";
	public static final String WEBSOCKET = "websocket";

	private RelayTransport() {
	}

	public static String normalize(final String value) {
		return WEBRTC.equals(value) || WEBSOCKET.equals(value) ? value : AUTO;
	}

	public static String next(final String value) {
		return switch (normalize(value)) {
			case AUTO -> WEBRTC;
			case WEBRTC -> WEBSOCKET;
			default -> AUTO;
		};
	}

	public static String label(final String value) {
		return switch (normalize(value)) {
			case WEBRTC -> "Method 1";
			case WEBSOCKET -> "Method 2";
			default -> "Auto";
		};
	}
}
