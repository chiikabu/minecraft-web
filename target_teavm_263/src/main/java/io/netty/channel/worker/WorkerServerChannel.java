// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.worker;

import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ServerChannel;
import io.netty.channel.local.LocalAddress;
import java.net.SocketAddress;

public class WorkerServerChannel extends AbstractChannel implements ServerChannel {
	private final SocketAddress localAddress;
	private final ChannelHandler childHandler;
	private final WorkerChannel.Transport transport;
	private WorkerChannel accepted;

	WorkerServerChannel(final SocketAddress localAddress, final ChannelHandler childHandler, final WorkerChannel.Transport transport) {
		this.localAddress = localAddress;
		this.childHandler = childHandler;
		this.transport = transport;
	}

	public WorkerChannel accepted() {
		if (this.accepted == null) {
			WorkerChannel channel = new WorkerChannel(this.localAddress, new LocalAddress("worker:client"), this.transport);
			if (this.childHandler != null) {
				channel.pipeline().addLast(this.childHandler);
			}
			this.accepted = channel;
			channel.activate();
		}
		return this.accepted;
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
		WorkerChannel child = this.accepted;
		this.accepted = null;
		if (child != null) {
			child.close();
		}
	}
}
