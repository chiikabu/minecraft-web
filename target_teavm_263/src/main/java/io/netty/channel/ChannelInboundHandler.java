// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

public interface ChannelInboundHandler extends ChannelHandler {
	void channelActive(ChannelHandlerContext ctx) throws Exception;

	void channelInactive(ChannelHandlerContext ctx) throws Exception;

	void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception;

	void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception;
}
