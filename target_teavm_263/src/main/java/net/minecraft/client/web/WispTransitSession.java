// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

public final class WispTransitSession {
	public interface Socket {
		void send(byte[] frame);

		void close();
	}

	public interface Sink {
		void data(byte[] mcBytes);

		void closed(int reason);
	}

	private final Socket socket;
	private final Sink sink;
	private final int streamId;
	private final String host;
	private final int port;
	private boolean closed;

	public WispTransitSession(final Socket socket, final Sink sink, final int streamId,
			final String host, final int port) {
		this.socket = socket;
		this.sink = sink;
		this.streamId = streamId;
		this.host = host;
		this.port = port;
	}

	public void start() {
		this.socket.send(WispCodec.connect(this.streamId, this.host, this.port));
	}

	public void send(final byte[] mcBytes) {
		if (!this.closed) {
			this.socket.send(WispCodec.data(this.streamId, mcBytes));
		}
	}

	public void onFrame(final byte[] frame) {
		if (this.closed) {
			return;
		}
		switch (WispCodec.type(frame)) {
			case WispCodec.TYPE_DATA -> {
				if (WispCodec.streamId(frame) == this.streamId) {
					this.sink.data(WispCodec.payload(frame));
				}
			}
			case WispCodec.TYPE_CLOSE -> {
				if (WispCodec.streamId(frame) == this.streamId) {
					this.closed = true;
					this.sink.closed(WispCodec.closeReason(frame));
				}
			}
			default -> {
			}
		}
	}

	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.socket.send(WispCodec.close(this.streamId, 0));
		}
		this.socket.close();
	}
}
