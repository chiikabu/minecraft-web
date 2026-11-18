// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.local;

import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ServerChannel;
import java.net.SocketAddress;

public class LocalServerChannel extends AbstractChannel implements ServerChannel {
	private LocalAddress localAddress;
	private ChannelHandler childHandler;

	void bind(final SocketAddress address, final ChannelHandler childHandler) {
		this.childHandler = childHandler;
		this.localAddress = LocalChannelRegistry.register(this, address);
		this.activate();
	}

	LocalChannel accept(final LocalChannel client) {
		LocalChannel serverSide = new LocalChannel();
		LocalChannel.connect(client, serverSide, this.localAddress);
		if (this.childHandler != null) {
			serverSide.pipeline().addLast(this.childHandler);
		}
		return serverSide;
	}

	@Override
	public SocketAddress localAddress() {
		return this.localAddress;
	}

	@Override
	public SocketAddress remoteAddress() {
		return null;
	}

	@Override
	protected void doWrite(final Object msg, final ChannelPromise promise) {
		promise.tryFailure(new UnsupportedOperationException("cannot write to a server channel"));
	}

	@Override
	protected void doFlush() {
	}

	@Override
	protected void doClose() {
		if (this.localAddress != null) {
			LocalChannelRegistry.unregister(this.localAddress);
		}
	}
}
