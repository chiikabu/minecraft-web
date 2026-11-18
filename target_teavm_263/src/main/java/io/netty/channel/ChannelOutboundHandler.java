// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

public interface ChannelOutboundHandler extends ChannelHandler {
	void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception;

	void flush(ChannelHandlerContext ctx) throws Exception;

	void read(ChannelHandlerContext ctx) throws Exception;
}
