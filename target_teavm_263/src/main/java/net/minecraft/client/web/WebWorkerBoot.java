// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.nio.charset.StandardCharsets;

public final class WebWorkerBoot {
	public static final String CHANNEL = "BOOT";

	public static final int IGNORE = 0;
	public static final int ACCEPT_READY = 1;
	public static final int ACCEPT_FAILED = 2;

	private static final byte READY = 1;
	private static final byte FAILED = 2;
	private static final byte TIMEOUT = 3;

	private boolean waiting;

	public void begin() {
		this.waiting = true;
	}

	public void reset() {
		this.waiting = false;
	}

	public boolean waiting() {
		return this.waiting;
	}

	public int receive(final byte[] message) {
		if (!this.waiting || message == null || message.length < 1) {
			return IGNORE;
		}
		if (message[0] == READY) {
			this.waiting = false;
			return ACCEPT_READY;
		}
		if (message[0] == FAILED || message[0] == TIMEOUT) {
			this.waiting = false;
			return ACCEPT_FAILED;
		}
		return IGNORE;
	}

	public static int opOf(final byte[] message) {
		return message != null && message.length >= 1 ? message[0] : -1;
	}

	public static byte[] ready() {
		return new byte[] { READY };
	}

	public static byte[] failed() {
		return new byte[] { FAILED };
	}

	public static byte[] failed(final String detail) {
		if (detail == null || detail.isEmpty()) {
			return failed();
		}
		byte[] text = detail.getBytes(StandardCharsets.UTF_8);
		int length = Math.min(text.length, 511);
		byte[] message = new byte[length + 1];
		message[0] = FAILED;
		System.arraycopy(text, 0, message, 1, length);
		return message;
	}

	public static String failureDetail(final byte[] message) {
		return isFailed(message) && message.length > 1
			? new String(message, 1, message.length - 1, StandardCharsets.UTF_8)
			: "";
	}

	public static byte[] timeout() {
		return new byte[] { TIMEOUT };
	}

	public static boolean isFailed(final byte[] message) {
		return opOf(message) == FAILED;
	}
}
