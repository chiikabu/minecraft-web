// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.ArrayList;
import java.util.List;

public abstract class MessageToMessageDecoder<I> extends ChannelInboundHandlerAdapter {
	protected abstract void decode(ChannelHandlerContext ctx, I msg, List<Object> out) throws Exception;

	public boolean acceptInboundMessage(final Object msg) throws Exception {
		return true;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
		if (!this.acceptInboundMessage(msg)) {
			ctx.fireChannelRead(msg);
			return;
		}
		List<Object> out = new ArrayList<>(1);
		try {
			this.decode(ctx, (I)msg, out);
		} catch (ClassCastException var5) {
			if (!out.isEmpty()) {
				throw var5;
			}
			ctx.fireChannelRead(msg);
			return;
		}
		for (Object decoded : out) {
			ctx.fireChannelRead(decoded);
		}
	}
}
