// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import io.netty.channel.Channel;
import io.netty.channel.relay.RelayHostChannel;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class WispHostSession {
	public interface Socket {
		void send(byte[] frame);

		void close();

		default void sendPeer(final int streamId, final byte[] buffer, final int offset, final int length,
				final boolean batched, final int[] large, final int largeCount) {
			if (batched) {
				byte[] frame = buffer;
				if (offset != WispCodec.HEADER_BYTES || buffer.length != offset + length) {
					frame = new byte[WispCodec.HEADER_BYTES + length];
					System.arraycopy(buffer, offset, frame, WispCodec.HEADER_BYTES, length);
				}
				WispCodec.dataHeader(frame, streamId);
				this.send(frame);
				return;
			}
			int at = offset;
			int end = offset + length;
			while (at < end) {
				int size = 0;
				int shift = 0;
				byte b;
				do {
					b = buffer[at++];
					size |= (b & 127) << shift;
					shift += 7;
				} while ((b & 128) != 0);
				byte[] frame = new byte[WispCodec.HEADER_BYTES + size];
				System.arraycopy(buffer, at, frame, WispCodec.HEADER_BYTES, size);
				WispCodec.dataHeader(frame, streamId);
				at += size;
				this.send(frame);
			}
		}

		default void closePeer(final int streamId) {
			this.send(WispCodec.close(streamId, 0));
		}
	}

	public interface PeerPipeline {
		void init(Channel peerChannel);
	}

	public static boolean batchFrames = true;

	private final Socket socket;
	private final PeerPipeline pipeline;
	private final int port;
	private final String motd;
	private final boolean announce;
	private final Map<Integer, RelayHostChannel> peers = new HashMap<>();
	private final Map<Integer, PeerTransport> transports = new HashMap<>();
	private final java.util.Set<Integer> admitted = new java.util.HashSet<>();
	private String passwordHash = "";

	private int hostedPort = -1;
	private String hostedAddress;
	private int errorReason;
	private boolean closed;
	private int packetsGone;
	private int bytesGone;

	public WispHostSession(final Socket socket, final PeerPipeline pipeline,
			final int port, final String motd, final boolean announce) {
		this.socket = socket;
		this.pipeline = pipeline;
		this.port = port;
		this.motd = motd;
		this.announce = announce;
	}

	public void setPasswordHash(final String hash) {
		this.passwordHash = hash == null ? "" : hash;
	}

	public void start() {
		this.socket.send(WispCodec.hostOpen(this.port, this.motd, this.announce));
	}

	public String hostedAddress() {
		return this.hostedAddress;
	}

	public int hostedPort() {
		return this.hostedPort;
	}

	public int errorReason() {
		return this.errorReason;
	}

	public int peerCount() {
		return this.peers.size();
	}

	public int batchedPeers() {
		int n = 0;
		for (PeerTransport transport : this.transports.values()) {
			if (transport.batched) {
				n++;
			}
		}
		return n;
	}

	public int packetsOut() {
		int n = this.packetsGone;
		for (RelayHostChannel channel : this.peers.values()) {
			n += channel.packetsOut();
		}
		return n;
	}

	public int bytesOut() {
		int n = this.bytesGone;
		for (RelayHostChannel channel : this.peers.values()) {
			n += channel.bytesOut();
		}
		return n;
	}

	public void onFrame(final byte[] frame) {
		switch (WispCodec.type(frame)) {
			case WispCodec.TYPE_HOST_OPENED -> {
				this.hostedPort = WispCodec.hostOpenedPort(frame);
				this.hostedAddress = WispCodec.hostOpenedAddress(frame);
			}
			case WispCodec.TYPE_HOST_ERROR -> {
				this.errorReason = WispCodec.hostErrorReason(frame);
				this.teardown(false);
			}
			case WispCodec.TYPE_PEER_OPEN -> {
				final int id = WispCodec.peerOpenStreamId(frame);
				final SocketAddress guest = new GuestAddress(WispCodec.peerOpenAddress(frame));
				final PeerTransport transport = new PeerTransport(id);
				final RelayHostChannel channel = new RelayHostChannel(guest, transport);
				this.pipeline.init(channel);
				channel.activate();
				this.peers.put(id, channel);
				this.transports.put(id, transport);
			}
			case WispCodec.TYPE_DATA -> {
				final int id = WispCodec.streamId(frame);
				if (WispCodec.isPeerStream(id)) {
					final RelayHostChannel channel = this.peers.get(id);
					if (channel == null) {
						return;
					}
					if (this.admitted.contains(id)) {
						channel.onData(WispCodec.payload(frame));
						return;
					}
					final LanGate.Decision decision = LanGate.check(WispCodec.payload(frame), this.passwordHash);
					if (decision.forward == null) {
						this.log("refused guest " + channel.remoteAddress() + ": no or wrong world password");
						this.socket.send(WispCodec.data(id, decision.reply));
						this.forget(id);
						channel.close();
						return;
					}
					this.admitted.add(id);
					if (batchFrames && decision.caps.contains(LanGate.CAP_BATCH)) {
						PeerTransport transport = this.transports.get(id);
						transport.batched = true;
						this.socket.send(WispCodec.data(id, LanBatchReader.magic()));
					}
					channel.onData(decision.forward);
				}
			}
			case WispCodec.TYPE_CLOSE -> {
				final int id = WispCodec.streamId(frame);
				if (WispCodec.isPeerStream(id)) {
					final RelayHostChannel channel = this.forget(id);
					if (channel != null) {
						this.logPeerClose(channel.remoteAddress(), WispCodec.closeReason(frame));
						channel.close();
					}
				}
			}
			default -> {
			}
		}
	}

	private RelayHostChannel forget(final int id) {
		RelayHostChannel channel = this.peers.remove(id);
		this.transports.remove(id);
		this.admitted.remove(id);
		if (channel != null) {
			this.packetsGone += channel.packetsOut();
			this.bytesGone += channel.bytesOut();
		}
		return channel;
	}

	public void close() {
		this.socket.send(WispCodec.hostClose());
		this.teardown(false);
	}

	private void teardown(final boolean sendHostClose) {
		if (this.closed) {
			return;
		}
		this.closed = true;
		if (sendHostClose) {
			this.socket.send(WispCodec.hostClose());
		}
		for (final RelayHostChannel channel : new ArrayList<>(this.peers.values())) {
			channel.close();
		}
		this.peers.clear();
		this.transports.clear();
		this.admitted.clear();
		this.socket.close();
	}

	protected void logPeerClose(final SocketAddress guest, final int reason) {
		if (reason == WispCodec.CLOSE_BAD_INFO) {
			this.log("relay dropped guest " + guest + ": hosted LAN is offline-mode only, but this guest"
				+ " negotiated encryption — the relay closed it rather than forward corruption");
		} else {
			this.log("relay closed guest " + guest + " (reason " + reason + ")");
		}
	}

	protected void log(final String message) {
		org.slf4j.LoggerFactory.getLogger("26.3-relay").info(message);
	}

	private final class PeerTransport implements RelayHostChannel.Transport {
		private final int streamId;
		private boolean batched;

		PeerTransport(final int streamId) {
			this.streamId = streamId;
		}

		@Override
		public void send(final byte[] packet) {
			WispHostSession.this.socket.send(WispCodec.data(this.streamId, packet));
		}

		@Override
		public int headroom() {
			return WispCodec.HEADER_BYTES;
		}

		@Override
		public void sendFlush(final byte[] buffer, final int offset, final int length, final int[] large, final int largeCount) {
			WispHostSession.this.socket.sendPeer(this.streamId, buffer, offset, length, this.batched, large, largeCount);
		}

		@Override
		public void close() {
			WispHostSession.this.socket.closePeer(this.streamId);
		}
	}

	private static final class GuestAddress extends SocketAddress {
		private final String display;

		GuestAddress(final String display) {
			this.display = display;
		}

		@Override
		public String toString() {
			return this.display;
		}
	}
}
