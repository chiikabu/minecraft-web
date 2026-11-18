// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.ArrayList;
import java.util.List;

public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {
	private ByteBuf cumulation;

	protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

	protected void handlerRemoved0(final ChannelHandlerContext ctx) throws Exception {
	}

	@Override
	public final void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
		if (this.cumulation != null) {
			this.cumulation.release();
			this.cumulation = null;
		}
		this.handlerRemoved0(ctx);
	}

	@Override
	public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
		if (!(msg instanceof ByteBuf in)) {
			ctx.fireChannelRead(msg);
			return;
		}
		List<Object> out = new ArrayList<>(1);
		final ByteBuf buf;
		final boolean decodingIn;
		if (this.cumulation == null) {
			buf = in;
			decodingIn = true;
		} else {
			this.cumulation.writeBytes(in);
			in.release();
			buf = this.cumulation;
			decodingIn = false;
		}
		try {
			while (buf.isReadable()) {
				int readableBefore = buf.readableBytes();
				int producedBefore = out.size();
				this.decode(ctx, buf, out);
				if (buf.readableBytes() == readableBefore && out.size() == producedBefore) {
					break;
				}
			}
		} finally {
			if (buf.isReadable()) {
				if (decodingIn) {
					this.cumulation = Unpooled.buffer(buf.readableBytes());
					this.cumulation.writeBytes(buf);
				} else {
					this.cumulation.discardReadBytes();
				}
			} else if (this.cumulation != null) {
				this.cumulation.release();
				this.cumulation = null;
			}
			if (decodingIn) {
				in.release();
			}
			for (Object decoded : out) {
				ctx.fireChannelRead(decoded);
			}
		}
	}
}
