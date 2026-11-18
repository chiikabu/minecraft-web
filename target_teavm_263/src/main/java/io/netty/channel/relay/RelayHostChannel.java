// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.websocket.WebSocketChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.ReferenceCountUtil;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;

public class RelayHostChannel extends AbstractChannel {
	public static final int MAX_FLUSH_BYTES = 256 * 1024;

	public static int largePacketBytes = 2048;

	private static final int[] NO_LARGE = new int[0];

	public interface Transport {
		void send(byte[] packet);

		void close();

		default int headroom() {
			return 0;
		}

		default void sendFlush(final byte[] buffer, final int offset, final int length, final int[] large, final int largeCount) {
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
				byte[] packet = new byte[size];
				System.arraycopy(buffer, at, packet, 0, size);
				at += size;
				this.send(packet);
			}
		}
	}

	private final SocketAddress remoteAddress;
	private final Transport transport;
	private final Queue<ByteBuf> outbound = new ArrayDeque<>();
	private boolean compressing;
	private int plainQueued;
	private int packetsOut;
	private int bytesOut;

	public RelayHostChannel(final SocketAddress remoteAddress, final Transport transport) {
		this.remoteAddress = remoteAddress;
		this.transport = transport;
	}

	public void onData(final byte[] payload) {
		if (!this.isOpen()) {
			return;
		}
		this.pipeline().fireChannelRead(Unpooled.wrappedBuffer(WebSocketChannel.withLengthPrefix(payload)));
	}

	public int packetsOut() {
		return this.packetsOut;
	}

	public int bytesOut() {
		return this.bytesOut;
	}

	@Override
	public SocketAddress localAddress() {
		return null;
	}

	@Override
	public SocketAddress remoteAddress() {
		return this.remoteAddress;
	}

	@Override
	protected void doWrite(final Object msg, final ChannelPromise promise) {
		if (!this.isOpen()) {
			ReferenceCountUtil.release(msg);
			promise.tryFailure(new ClosedChannelException());
			return;
		}
		if (!(msg instanceof ByteBuf buf)) {
			ReferenceCountUtil.release(msg);
			promise.tryFailure(new IllegalArgumentException("relay host channel writes ByteBufs, got " + msg));
			return;
		}
		if (!this.compressing) {
			if (this.pipeline().get("compress") != null) {
				this.compressing = true;
			} else {
				this.plainQueued++;
			}
		}
		this.outbound.add(buf);
		promise.trySuccess();
	}

	@Override
	protected void doFlush() {
		while (!this.outbound.isEmpty()) {
			if (!this.isOpen() || this.transport == null) {
				this.discardOutbound();
				return;
			}
			int total = 0;
			int count = 0;
			for (ByteBuf queued : this.outbound) {
				int size = queued.readableBytes();
				if (count > 0 && total + size > MAX_FLUSH_BYTES) {
					break;
				}
				total += size;
				count++;
			}
			int headroom = this.transport.headroom();
			byte[] buffer = new byte[headroom + total];
			int[] large = NO_LARGE;
			int largeCount = 0;
			int at = headroom;
			for (int i = 0; i < count; i++) {
				ByteBuf buf = this.outbound.poll();
				boolean plain = this.plainQueued > 0;
				if (plain) {
					this.plainQueued--;
				}
				try {
					int size = buf.readableBytes();
					buf.getBytes(buf.readerIndex(), buffer, at, size);
					int length = 0;
					int read = 0;
					byte b;
					do {
						if (read == size || read == 3) {
							throw new CorruptedFrameException("packet length is wider than 21 bits");
						}
						b = buffer[at + read];
						length |= (b & 127) << read * 7;
						read++;
					} while ((b & 128) != 0);
					if (length != size - read) {
						throw new CorruptedFrameException("declared length " + length + " but " + (size - read) + " bytes follow");
					}
					if (!plain && length > largePacketBytes && buffer[at + read] == 0) {
						if (largeCount == large.length) {
							large = java.util.Arrays.copyOf(large, Math.max(4, largeCount * 2));
						}
						large[largeCount++] = at;
					}
					at += size;
				} catch (CorruptedFrameException e) {
					ReferenceCountUtil.release(buf);
					this.pipeline().fireExceptionCaught(e);
					this.close();
					return;
				}
				ReferenceCountUtil.release(buf);
			}
			this.packetsOut += count;
			this.bytesOut += total;
			this.transport.sendFlush(buffer, headroom, total, large, largeCount);
		}
	}

	private void discardOutbound() {
		ByteBuf buf;
		while ((buf = this.outbound.poll()) != null) {
			ReferenceCountUtil.release(buf);
		}
		this.plainQueued = 0;
	}

	@Override
	protected void doClose() {
		this.discardOutbound();
		if (this.transport != null) {
			this.transport.close();
		}
	}
}
