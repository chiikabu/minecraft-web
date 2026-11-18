// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalChannelRegistry;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.worker.WorkerChannelRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> {
	SocketAddress localAddress;
	ChannelHandler handler;
	Class<? extends Channel> channelClass;

	@SuppressWarnings("unchecked")
	public B group(final EventLoopGroup group) {
		return (B)this;
	}

	@SuppressWarnings("unchecked")
	public B channel(final Class<? extends C> channelClass) {
		this.channelClass = channelClass;
		return (B)this;
	}

	@SuppressWarnings("unchecked")
	public B handler(final ChannelHandler handler) {
		this.handler = handler;
		return (B)this;
	}

	@SuppressWarnings("unchecked")
	public <T> B option(final ChannelOption<T> option, final T value) {
		return (B)this;
	}

	@SuppressWarnings("unchecked")
	public B localAddress(final SocketAddress localAddress) {
		this.localAddress = localAddress;
		return (B)this;
	}

	@SuppressWarnings("unchecked")
	public B localAddress(final InetAddress host, final int port) {
		this.localAddress = new InetSocketAddress(host, port);
		return (B)this;
	}

	@SuppressWarnings("unchecked")
	public B localAddress(final String host, final int port) {
		this.localAddress = InetSocketAddress.createUnresolved(host, port);
		return (B)this;
	}

	public ChannelFuture bind() {
		if (this.channelClass() != LocalServerChannel.class) {
			throw new UnsupportedOperationException("cannot bind " + this.channelClass() + " in browser");
		}
		if (WorkerChannelRegistry.isServerEnabled()) {
			return DefaultChannelPromise.succeeded(WorkerChannelRegistry.bind(this.localAddress, this.childHandler()));
		}
		LocalServerChannel channel = LocalChannelRegistry.bind(this.localAddress, this.childHandler());
		return DefaultChannelPromise.succeeded(channel);
	}

	Class<? extends Channel> channelClass() {
		return this.channelClass == null ? Channel.class : this.channelClass;
	}

	ChannelHandler childHandler() {
		return null;
	}
}
