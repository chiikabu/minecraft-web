// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.nio.charset.StandardCharsets;

public final class WispCodec {
	public static final int TYPE_CONNECT = 0x01;
	public static final int TYPE_DATA = 0x02;
	public static final int TYPE_CONTINUE = 0x03;
	public static final int TYPE_CLOSE = 0x04;
	public static final int TYPE_HOST_OPEN = 0x10;
	public static final int TYPE_HOST_OPENED = 0x11;
	public static final int TYPE_HOST_ERROR = 0x12;
	public static final int TYPE_PEER_OPEN = 0x13;
	public static final int TYPE_HOST_CLOSE = 0x14;

	public static final int CONTROL_STREAM = 0;

	public static final int FIRST_PEER_STREAM_ID = 0x80000000;

	public static final boolean PEER_STREAMS_START_WITH_CREDIT = true;

	public static final int HEADER_BYTES = 5;

	private WispCodec() {
	}

	public static boolean isPeerStream(final int streamId) {
		return (streamId ^ Integer.MIN_VALUE) >= (FIRST_PEER_STREAM_ID ^ Integer.MIN_VALUE);
	}

	public static int type(final byte[] frame) {
		requireHeader(frame);
		return frame[0] & 0xff;
	}

	public static int streamId(final byte[] frame) {
		requireHeader(frame);
		return u32(frame, 1);
	}

	public static byte[] payload(final byte[] frame) {
		requireHeader(frame);
		byte[] out = new byte[frame.length - HEADER_BYTES];
		System.arraycopy(frame, HEADER_BYTES, out, 0, out.length);
		return out;
	}

	public static byte[] frame(final int type, final int streamId, final byte[] payload) {
		byte[] out = new byte[HEADER_BYTES + payload.length];
		out[0] = (byte)type;
		putU32(out, 1, streamId);
		System.arraycopy(payload, 0, out, HEADER_BYTES, payload.length);
		return out;
	}

	public static byte[] connect(final int streamId, final String host, final int port) {
		if (isPeerStream(streamId)) {
			throw new IllegalArgumentException("transit stream id must be below the peer range: " + streamId);
		}
		byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
		if (hostBytes.length < 1 || hostBytes.length > 255) {
			throw new IllegalArgumentException("host length out of range (1..255): " + hostBytes.length);
		}
		byte[] payload = new byte[3 + hostBytes.length];
		payload[0] = 1;
		putU16(payload, 1, port);
		System.arraycopy(hostBytes, 0, payload, 3, hostBytes.length);
		return frame(TYPE_CONNECT, streamId, payload);
	}

	public static byte[] data(final int streamId, final byte[] packet) {
		return frame(TYPE_DATA, streamId, packet);
	}

	public static void dataHeader(final byte[] frame, final int streamId) {
		requireHeader(frame);
		frame[0] = (byte)TYPE_DATA;
		putU32(frame, 1, streamId);
	}

	public static byte[] close(final int streamId, final int reason) {
		return frame(TYPE_CLOSE, streamId, new byte[] { (byte)reason });
	}

	public static byte[] hostOpen(final int port, final String motd, final boolean announce) {
		byte[] motdBytes = motd.getBytes(StandardCharsets.UTF_8);
		if (motdBytes.length > 0xffff) {
			throw new IllegalArgumentException("motd too long: " + motdBytes.length);
		}

		byte[] payload = new byte[2 + 2 + motdBytes.length + 1];
		putU16(payload, 0, port);
		putU16(payload, 2, motdBytes.length);
		System.arraycopy(motdBytes, 0, payload, 4, motdBytes.length);
		payload[payload.length - 1] = (byte)(announce ? 1 : 0);
		return frame(TYPE_HOST_OPEN, CONTROL_STREAM, payload);
	}

	public static byte[] hostClose() {
		return frame(TYPE_HOST_CLOSE, CONTROL_STREAM, new byte[0]);
	}

	public static int hostOpenedPort(final byte[] frame) {
		expect(frame, TYPE_HOST_OPENED);
		return u16(frame, HEADER_BYTES);
	}

	public static String hostOpenedAddress(final byte[] frame) {
		expect(frame, TYPE_HOST_OPENED);
		int length = frame[HEADER_BYTES + 2] & 0xff;
		return string(frame, HEADER_BYTES + 3, length);
	}

	public static int hostErrorReason(final byte[] frame) {
		expect(frame, TYPE_HOST_ERROR);
		return frame[HEADER_BYTES] & 0xff;
	}

	public static int peerOpenStreamId(final byte[] frame) {
		expect(frame, TYPE_PEER_OPEN);
		int fromHeader = streamId(frame);
		int fromPayload = u32(frame, HEADER_BYTES);
		if (fromHeader != fromPayload) {
			throw new IllegalArgumentException(
				"PEER_OPEN stream id mismatch: header " + fromHeader + " vs payload " + fromPayload);
		}
		if (!isPeerStream(fromHeader)) {
			throw new IllegalArgumentException("PEER_OPEN id " + fromHeader + " is not in the peer range");
		}
		return fromHeader;
	}

	public static String peerOpenAddress(final byte[] frame) {
		expect(frame, TYPE_PEER_OPEN);
		int length = frame[HEADER_BYTES + 4] & 0xff;
		return string(frame, HEADER_BYTES + 5, length);
	}

	public static int closeReason(final byte[] frame) {
		expect(frame, TYPE_CLOSE);
		return frame[HEADER_BYTES] & 0xff;
	}

	public static final int CLOSE_BAD_INFO = 0x41;

	private static void requireHeader(final byte[] frame) {
		if (frame.length < HEADER_BYTES) {
			throw new IllegalArgumentException("wisp frame shorter than its header: " + frame.length);
		}
	}

	private static void expect(final byte[] frame, final int type) {
		int actual = type(frame);
		if (actual != type) {
			throw new IllegalArgumentException("expected wisp type 0x" + Integer.toHexString(type)
				+ " but got 0x" + Integer.toHexString(actual));
		}
	}

	private static String string(final byte[] frame, final int offset, final int length) {
		if (offset + length > frame.length) {
			throw new IllegalArgumentException("string runs past the frame");
		}
		return new String(frame, offset, length, StandardCharsets.UTF_8);
	}

	private static int u16(final byte[] b, final int off) {
		return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8;
	}

	private static int u32(final byte[] b, final int off) {
		return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
	}

	private static void putU16(final byte[] b, final int off, final int v) {
		b[off] = (byte)v;
		b[off + 1] = (byte)(v >>> 8);
	}

	private static void putU32(final byte[] b, final int off, final int v) {
		b[off] = (byte)v;
		b[off + 1] = (byte)(v >>> 8);
		b[off + 2] = (byte)(v >>> 16);
		b[off + 3] = (byte)(v >>> 24);
	}
}
