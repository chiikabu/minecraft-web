// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.worker;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import net.minecraft.network.HiddenByteBuf;

public class WorkerChannel extends AbstractChannel {
	public interface Transport {
		void send(byte[] packet);

		default void sendBatch(final byte[] frame) {
			int at = 0;
			while (at + 4 <= frame.length) {
				int length = (frame[at] & 255) << 24 | (frame[at + 1] & 255) << 16 | (frame[at + 2] & 255) << 8 | frame[at + 3] & 255;
				at += 4;
				byte[] packet = new byte[length];
				System.arraycopy(frame, at, packet, 0, length);
				at += length;
				this.send(packet);
			}
		}

		void close();
	}

	private final SocketAddress localAddress;
	private final SocketAddress remoteAddress;
	private final Transport transport;
	private final Queue<ByteBuf> outbound = new ArrayDeque<>();

	public WorkerChannel(final SocketAddress localAddress, final SocketAddress remoteAddress, final Transport transport) {
		this.localAddress = localAddress;
		this.remoteAddress = remoteAddress;
		this.transport = transport;
	}

	public void onPacket(final byte[] packet) {
		if (!this.isOpen()) {
			return;
		}
		final ByteBuf buf = Unpooled.wrappedBuffer(packet);
		this.pipeline().fireChannelRead(buf);
	}

	@Override
	public SocketAddress localAddress() {
		return this.localAddress;
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
		if (!(HiddenByteBuf.unpack(msg) instanceof ByteBuf buf)) {
			ReferenceCountUtil.release(msg);
			promise.tryFailure(new IllegalArgumentException("worker channel writes ByteBufs, got " + msg));
			return;
		}
		this.outbound.add(buf);
		promise.trySuccess();
	}

	@Override
	protected void doFlush() {
		if (this.outbound.size() > 1 && this.isOpen() && this.transport != null) {
			int total = 0;
			for (ByteBuf queued : this.outbound) {
				total += 4 + queued.readableBytes();
			}
			byte[] frame = new byte[total];
			int at = 0;
			ByteBuf queued;
			while ((queued = this.outbound.poll()) != null) {
				try {
					int length = queued.readableBytes();
					frame[at] = (byte)(length >>> 24);
					frame[at + 1] = (byte)(length >>> 16);
					frame[at + 2] = (byte)(length >>> 8);
					frame[at + 3] = (byte)length;
					queued.getBytes(queued.readerIndex(), frame, at + 4, length);
					at += 4 + length;
				} finally {
					ReferenceCountUtil.release(queued);
				}
			}
			this.transport.sendBatch(frame);
			return;
		}
		ByteBuf buf;
		while ((buf = this.outbound.poll()) != null) {
			try {
				if (this.isOpen() && this.transport != null) {
					this.transport.send(toBytes(buf));
				}
			} finally {
				ReferenceCountUtil.release(buf);
			}
		}
	}

	@Override
	protected void doClose() {
		ByteBuf buf;
		while ((buf = this.outbound.poll()) != null) {
			ReferenceCountUtil.release(buf);
		}
		if (this.transport != null) {
			this.transport.close();
		}
	}

	private static byte[] toBytes(final ByteBuf buf) {
		byte[] bytes = new byte[buf.readableBytes()];
		buf.getBytes(buf.readerIndex(), bytes);
		return bytes;
	}
}
