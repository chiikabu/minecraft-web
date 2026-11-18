// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.websocket;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import java.net.InetSocketAddress;
import net.minecraft.client.web.WebRelayAuth;
import net.minecraft.client.web.WebRelayRpc;
import net.minecraft.client.web.WispTransitSession;
import org.teavm.jso.browser.Window;
import org.teavm.jso.typedarrays.Int8Array;

public final class JsWispTransport implements WebSocketChannel.Transport {
	private static final int CONNECT_TIMEOUT_MS = 15000;

	private final WispTransitSession session;
	private String passwordHash;
	private String caps;

	private JsWispTransport(final WispTransitSession session, final String passwordHash, final String caps) {
		this.session = session;
		this.passwordHash = passwordHash;
		this.caps = caps;
	}

	private static boolean lanWorld(final String host) {
		return net.minecraft.client.web.LanServers.LOOPBACK.equals(host);
	}

	@org.teavm.jso.JSBody(script = "try { return !/[?&]lanbatch=0(?:&|$)/.test(location.search || ''); } catch (e) { return true; }")
	private static native boolean batchingWanted();

	public static ChannelFuture connect(final String host, final int port, final ChannelHandler initializer) {
		WebSocketChannel channel = new WebSocketChannel(InetSocketAddress.createUnresolved(host, port), true);
		String uri = WebRelayAuth.relaySocketUrl();
		int streamId = WebRelayRpc.allocateStreamId();
		if (initializer != null) {
			channel.pipeline().addLast(initializer);
		}

		final net.minecraft.client.web.LanBatchReader batches =
			lanWorld(host) && batchingWanted() ? new net.minecraft.client.web.LanBatchReader() : null;
		WispTransitSession session = new WispTransitSession(
			new WispTransitSession.Socket() {
				@Override
				public void send(final byte[] frame) {
					WebRelayRpc.sendBinary(streamId, frame);
				}

				@Override
				public void close() {
					WebRelayRpc.forgetBinaryStream(streamId);
				}
			},
			new WispTransitSession.Sink() {
				@Override
				public void data(final byte[] mcBytes) {
					if (batches == null) {
						channel.onBinaryFrame(mcBytes);
						return;
					}
					byte[] stream;
					try {
						stream = batches.feed(mcBytes);
					} catch (IllegalStateException e) {
						WebRelayRpc.forgetBinaryStream(streamId);
						channel.onError("relay tunnel to " + host + ":" + port + " is corrupt: " + e.getMessage());
						return;
					}
					if (stream.length > 0) {
						channel.onBinaryFrame(stream);
					}
				}

				@Override
				public void closed(final int reason) {
					WebRelayRpc.forgetBinaryStream(streamId);
					channel.onClosed("relay closed the tunnel to " + host + ":" + port + " (reason " + reason + ")");
				}
			},
			streamId, host, port);
		channel.attach(new JsWispTransport(session, net.minecraft.client.web.LanServers.takePassword(host, port),
			batches != null ? net.minecraft.client.web.LanGate.CAP_BATCH : ""));

		final int connectTimeout = Window.setTimeout(
			() -> {
				WebRelayRpc.forgetBinaryStream(streamId);
				channel.onError("timed out opening relay tunnel " + uri);
			}, CONNECT_TIMEOUT_MS);

		WebRelayRpc.registerBinaryStream(streamId, new WebRelayRpc.BinaryHandler() {
			@Override
			public void event(final int kind, final Int8Array data, final String reason) {
				switch (kind) {
					case WebRelayRpc.BINARY_OPENED -> {
						Window.clearTimeout(connectTimeout);
						session.start();
						channel.onOpen();
					}
					case WebRelayRpc.BINARY_FRAME -> session.onFrame(data.copyToJavaArray());
					case WebRelayRpc.BINARY_CLOSED -> {
						Window.clearTimeout(connectTimeout);
						channel.onClosed(reason);
					}
					default -> {
					}
				}
			}
		});
		return channel.connectPromise();
	}

	@Override
	public void send(final byte[] frame) {
		if (!this.passwordHash.isEmpty() || !this.caps.isEmpty()) {
			byte[] tagged = net.minecraft.client.web.LanGate.tagFramedHandshake(frame, this.passwordHash, this.caps);
			this.passwordHash = "";
			this.caps = "";
			this.session.send(tagged);
			return;
		}
		this.session.send(frame);
	}

	@Override
	public void close() {
		this.session.close();
	}

}
