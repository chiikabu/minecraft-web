// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;

public class WebSocketChannel extends AbstractChannel {
	public interface Transport {
		void send(byte[] frame);

		void close();
	}

	private final SocketAddress remoteAddress;
	private final ChannelPromise connectPromise = this.newPromise();
	private final Queue<ByteBuf> outbound = new ArrayDeque<>();
	private final boolean rawPipe;
	private Transport transport;
	private boolean opened;

	public WebSocketChannel(final SocketAddress remoteAddress) {
		this(remoteAddress, false);
	}

	public WebSocketChannel(final SocketAddress remoteAddress, final boolean rawPipe) {
		this.remoteAddress = remoteAddress;
		this.rawPipe = rawPipe;
	}

	public static String uriFor(final String host, final int port, final boolean pageIsSecure) {
		String trimmed = host.trim();
		if (trimmed.indexOf("://") >= 0) {
			return trimmed;
		}
		return (pageIsSecure ? "wss://" : "ws://") + trimmed + ":" + port + "/";
	}

	public void attach(final Transport transport) {
		this.transport = transport;
		this.activate();
	}

	public ChannelPromise connectPromise() {
		return this.connectPromise;
	}

	public void onOpen() {
		if (this.opened || !this.isOpen()) {
			return;
		}
		this.opened = true;
		this.connectPromise.trySuccess();
		this.activate();
		this.doFlush();
	}

	public void onBinaryFrame(final byte[] frame) {
		if (!this.isOpen()) {
			return;
		}
		final ByteBuf buf = Unpooled.wrappedBuffer(this.rawPipe ? frame : withLengthPrefix(frame));
		this.eventLoop().execute(() -> {
			if (this.isOpen()) {
				this.pipeline().fireChannelRead(buf);
			} else {
				ReferenceCountUtil.release(buf);
			}
		});
	}

	public void onClosed(final String reason) {
		if (!this.opened) {
			this.failBeforeOpen(reason);
			return;
		}
		this.eventLoop().execute(this::close);
	}

	public void onError(final String message) {
		if (!this.opened) {
			this.failBeforeOpen(message);
			return;
		}
		this.eventLoop().execute(() -> {
			if (this.isOpen()) {
				this.pipeline().fireExceptionCaught(new IOException(message));
				this.close();
			}
		});
	}

	private void failBeforeOpen(final String message) {
		if (!this.isOpen()) {
			return;
		}
		this.opened = true;
		this.connectPromise.tryFailure(new IOException(message));
		this.activate();
		this.pipeline().fireExceptionCaught(new IOException(message));
		this.close();
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
			promise.tryFailure(new IllegalArgumentException("websocket channel writes ByteBufs, got " + msg));
			return;
		}
		this.outbound.add(buf);
		promise.trySuccess();
	}

	@Override
	protected void doFlush() {
		if (!this.opened) {
			return;
		}
		ByteBuf buf;
		while ((buf = this.outbound.poll()) != null) {
			try {
				if (this.isOpen() && this.transport != null) {
					this.transport.send(this.rawPipe ? bytesOf(buf) : stripLengthPrefix(buf));
				}
			} catch (CorruptedFrameException e) {
				this.pipeline().fireExceptionCaught(e);
				this.close();
				return;
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
		this.connectPromise.tryFailure(new ClosedChannelException());
	}

	public static byte[] withLengthPrefix(final byte[] frame) {
		int length = frame.length;
		byte[] out = new byte[varIntSize(length) + length];
		int i = 0;
		while ((length & -128) != 0) {
			out[i++] = (byte)(length & 127 | 128);
			length >>>= 7;
		}
		out[i++] = (byte)length;
		System.arraycopy(frame, 0, out, i, frame.length);
		return out;
	}

	public static byte[] bytesOf(final ByteBuf buf) {
		byte[] all = new byte[buf.readableBytes()];
		buf.getBytes(buf.readerIndex(), all);
		return all;
	}

	public static byte[] stripLengthPrefix(final ByteBuf buf) {
		int available = buf.readableBytes();
		byte[] all = new byte[available];
		buf.getBytes(buf.readerIndex(), all);
		int length = 0;
		int read = 0;
		byte b;
		do {
			if (read == available || read == 3) {
				throw new CorruptedFrameException("packet length is wider than 21 bits");
			}
			b = all[read];
			length |= (b & 127) << read * 7;
			read++;
		} while ((b & 128) != 0);
		if (length != available - read) {
			throw new CorruptedFrameException("declared length " + length + " but " + (available - read) + " bytes follow");
		}
		byte[] frame = new byte[length];
		System.arraycopy(all, read, frame, 0, length);
		return frame;
	}

	private static int varIntSize(final int value) {
		for (int size = 1; size < 5; size++) {
			if ((value & -1 << size * 7) == 0) {
				return size;
			}
		}
		return 5;
	}
}
