// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.bootstrap;

import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;

public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {
	private ChannelHandler childHandler;

	@Override
	public ServerBootstrap group(final EventLoopGroup group) {
		return this;
	}

	public ServerBootstrap group(final EventLoopGroup parentGroup, final EventLoopGroup childGroup) {
		return this;
	}

	public ServerBootstrap childHandler(final ChannelHandler childHandler) {
		this.childHandler = childHandler;
		return this;
	}

	@Override
	ChannelHandler childHandler() {
		return this.childHandler;
	}
}
