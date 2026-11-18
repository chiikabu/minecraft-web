// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.AttributeMap;
import java.net.SocketAddress;

public interface Channel extends AttributeMap {
	SocketAddress remoteAddress();

	SocketAddress localAddress();

	ChannelPipeline pipeline();

	ChannelConfig config();

	EventLoop eventLoop();

	ByteBufAllocator alloc();

	boolean isOpen();

	boolean isActive();

	ChannelFuture close();

	ChannelFuture closeFuture();

	Channel read();

	Channel flush();

	ChannelPromise newPromise();

	ChannelPromise voidPromise();

	ChannelFuture write(Object msg);

	ChannelFuture write(Object msg, ChannelPromise promise);

	ChannelFuture writeAndFlush(Object msg);

	ChannelFuture writeAndFlush(Object msg, ChannelPromise promise);
}
