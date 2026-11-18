// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

public abstract class MessageToByteEncoder<I> extends ChannelOutboundHandlerAdapter {
	protected MessageToByteEncoder() {
	}

	protected MessageToByteEncoder(final Class<? extends I> outboundMessageType) {
	}

	protected MessageToByteEncoder(final boolean preferDirect) {
	}

	protected abstract void encode(ChannelHandlerContext ctx, I msg, ByteBuf out) throws Exception;

	public boolean acceptOutboundMessage(final Object msg) throws Exception {
		return true;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) throws Exception {
		if (!this.acceptOutboundMessage(msg)) {
			ctx.write(msg, promise);
			return;
		}
		ByteBuf out = ctx.alloc().buffer();
		try {
			this.encode(ctx, (I)msg, out);
		} catch (RuntimeException var6) {
			if (out.readableBytes() != 0) {
				throw var6;
			}
			out.release();
			ctx.write(msg, promise);
			return;
		}
		ctx.write(out, promise);
	}
}
