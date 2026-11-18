// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.ArrayDeque;
import org.teavm.jso.typedarrays.Int8Array;

public final class JsWispHost {
	public interface Listener {
		void state(byte state, String address, int port, String message);
	}

	public static int deflateBytes;

	private final int streamId;
	private final WispHostSession session;
	private final Listener listener;
	private final ArrayDeque<byte[]> inbound = new ArrayDeque<>();
	private boolean closed;

	public JsWispHost(final String socketUrl, final WispHostSession.PeerPipeline pipeline,
			final int port, final String motd, final boolean announce, final String passwordHash, final Listener listener) {
		this.streamId = WebRelayRpc.allocateStreamId();
		this.listener = listener;
		this.session = new WispHostSession(new WispHostSession.Socket() {
			@Override
			public void send(final byte[] frame) {
				WebRelayRpc.sendBinary(JsWispHost.this.streamId, frame);
			}

			@Override
			public void sendPeer(final int peerStreamId, final byte[] buffer, final int offset, final int length,
					final boolean batched, final int[] large, final int largeCount) {
				byte[] exact = buffer;
				if (offset != WispCodec.HEADER_BYTES || buffer.length != offset + length) {
					exact = new byte[WispCodec.HEADER_BYTES + length];
					System.arraycopy(buffer, offset, exact, WispCodec.HEADER_BYTES, length);
				}
				int firstLarge = largeCount > 0 && deflateBytes > 0 ? large[0] - offset + WispCodec.HEADER_BYTES : -1;
				WebRelayRpc.sendLan(JsWispHost.this.streamId, peerStreamId, Int8Array.copyFromJavaArray(exact), batched,
					firstLarge, deflateBytes);
			}

			@Override
			public void closePeer(final int peerStreamId) {
				WebRelayRpc.sendLanOrdered(JsWispHost.this.streamId, peerStreamId,
					Int8Array.copyFromJavaArray(WispCodec.close(peerStreamId, 0)));
			}

			@Override
			public void close() {
				WebRelayRpc.forgetBinaryStream(JsWispHost.this.streamId);
			}
		}, pipeline, port, motd, announce);
		this.session.setPasswordHash(passwordHash);

		listener.state(WebLifecycleMsg.HOST_OPENING, "", -1, "");
		WebRelayRpc.registerBinaryStreamAt(socketUrl, this.streamId, new WebRelayRpc.BinaryHandler() {
			@Override
			public void event(final int kind, final Int8Array data, final String reason) {
				JsWispHost.this.onEvent(kind, data, reason);
			}
		});
	}

	public void setPasswordHash(final String passwordHash) {
		this.session.setPasswordHash(passwordHash);
	}

	public WispHostSession session() {
		return this.session;
	}

	private void onEvent(final int kind, final Int8Array data, final String reason) {
		if (this.closed) {
			return;
		}
		switch (kind) {
			case WebRelayRpc.BINARY_OPENED -> {
				try {
					this.session.start();
				} catch (RuntimeException e) {
					this.fail("invalid relay host frame: " + e.getMessage());
				}
			}
			case WebRelayRpc.BINARY_FRAME -> {
				this.inbound.add(data.copyToJavaArray());
				WebServerMain.wake();
			}
			case WebRelayRpc.BINARY_CLOSED -> {
				this.closed = true;
				this.inbound.clear();
				this.listener.state(WebLifecycleMsg.HOST_ERROR, "", -1,
					reason == null || reason.isEmpty() ? "relay socket closed" : reason);
			}
			default -> {
			}
		}
	}

	public void drain() {
		byte[] frame;
		while (!this.closed && (frame = this.inbound.poll()) != null) {
			try {
				int type = WispCodec.type(frame);
				this.session.onFrame(frame);
				if (type == WispCodec.TYPE_HOST_OPENED) {
					this.listener.state(WebLifecycleMsg.HOST_BOUND, this.session.hostedAddress(),
						this.session.hostedPort(), "");
				} else if (type == WispCodec.TYPE_HOST_ERROR) {
					this.closed = true;
					this.listener.state(WebLifecycleMsg.HOST_ERROR, "", -1,
						hostError(this.session.errorReason()));
				}
			} catch (RuntimeException e) {
				this.fail("invalid relay host frame: " + e.getMessage());
			}
		}
	}

	private void fail(final String message) {
		this.closed = true;
		this.inbound.clear();
		WebRelayRpc.forgetBinaryStream(this.streamId);
		this.listener.state(WebLifecycleMsg.HOST_ERROR, "", -1, message);
	}

	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.inbound.clear();
		this.session.close();
		this.listener.state(WebLifecycleMsg.HOST_IDLE, "", -1, "");
	}

	private static String hostError(final int reason) {
		return switch (reason) {
			case 1 -> "relay port is already in use";
			case 2 -> "relay hosting is disabled";
			case 3 -> "relay has no routable LAN interface";
			default -> "relay refused hosting (reason " + reason + ")";
		};
	}
}
