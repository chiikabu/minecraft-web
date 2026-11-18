// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.ArrayList;
import java.util.List;

public abstract class MessageToMessageEncoder<I> extends ChannelOutboundHandlerAdapter {
	protected abstract void encode(ChannelHandlerContext ctx, I msg, List<Object> out) throws Exception;

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
		List<Object> out = new ArrayList<>(1);
		try {
			this.encode(ctx, (I)msg, out);
		} catch (ClassCastException var6) {
			if (!out.isEmpty()) {
				throw var6;
			}
			ctx.write(msg, promise);
			return;
		}
		if (out.isEmpty()) {
			promise.trySuccess();
			return;
		}
		for (int i = 0; i < out.size() - 1; i++) {
			ctx.write(out.get(i), ctx.newPromise());
		}
		ctx.write(out.get(out.size() - 1), promise);
	}
}
