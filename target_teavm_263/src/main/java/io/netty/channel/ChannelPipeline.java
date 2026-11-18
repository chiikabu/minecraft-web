// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

public interface ChannelPipeline {
	Channel channel();

	ChannelPipeline addFirst(String name, ChannelHandler handler);

	ChannelPipeline addLast(String name, ChannelHandler handler);

	ChannelPipeline addLast(ChannelHandler... handlers);

	ChannelPipeline addBefore(String baseName, String name, ChannelHandler handler);

	ChannelPipeline addAfter(String baseName, String name, ChannelHandler handler);

	ChannelHandler get(String name);

	<T extends ChannelHandler> T get(Class<T> handlerType);

	ChannelHandler remove(String name);

	ChannelPipeline remove(ChannelHandler handler);

	ChannelHandler replace(String oldName, String newName, ChannelHandler newHandler);

	ChannelHandlerContext context(String name);

	ChannelHandlerContext firstContext();

	ChannelPipeline fireChannelActive();

	ChannelPipeline fireChannelInactive();

	ChannelPipeline fireChannelRead(Object msg);

	ChannelPipeline fireExceptionCaught(Throwable cause);

	ChannelPipeline read();

	ChannelPipeline flush();

	ChannelFuture write(Object msg, ChannelPromise promise);

	ChannelFuture writeAndFlush(Object msg, ChannelPromise promise);
}
