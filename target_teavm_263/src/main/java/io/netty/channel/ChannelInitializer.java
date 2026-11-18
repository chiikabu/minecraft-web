// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

public abstract class ChannelInitializer<C extends Channel> extends ChannelInboundHandlerAdapter {
	protected abstract void initChannel(C channel) throws Exception;

	@Override
	@SuppressWarnings("unchecked")
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		try {
			this.initChannel((C)ctx.channel());
		} finally {
			ctx.pipeline().remove(this);
		}
	}
}
