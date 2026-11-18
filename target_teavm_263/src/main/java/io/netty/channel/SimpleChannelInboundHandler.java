// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

public abstract class SimpleChannelInboundHandler<I> extends ChannelInboundHandlerAdapter {
	protected SimpleChannelInboundHandler() {
	}

	protected SimpleChannelInboundHandler(boolean autoRelease) {
	}

	protected abstract void channelRead0(ChannelHandlerContext ctx, I msg) throws Exception;

	@Override
	@SuppressWarnings("unchecked")
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		channelRead0(ctx, (I)msg);
	}
}
