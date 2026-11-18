// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;

public interface ChannelHandlerContext {
	Channel channel();

	ChannelPipeline pipeline();

	String name();

	ByteBufAllocator alloc();

	ChannelHandlerContext fireChannelRead(Object msg);

	ChannelHandlerContext fireChannelActive();

	ChannelHandlerContext fireChannelInactive();

	ChannelHandlerContext fireExceptionCaught(Throwable cause);

	ChannelHandlerContext read();

	ChannelHandlerContext flush();

	ChannelPromise newPromise();

	ChannelPromise voidPromise();

	ChannelFuture write(Object msg);

	ChannelFuture write(Object msg, ChannelPromise promise);

	ChannelFuture writeAndFlush(Object msg);

	ChannelFuture writeAndFlush(Object msg, ChannelPromise promise);

	ChannelFuture close();
}
