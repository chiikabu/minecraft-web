// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalChannelRegistry;
import io.netty.channel.websocket.JsWebSocketTransport;
import io.netty.channel.worker.WorkerChannelRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class Bootstrap extends AbstractBootstrap<Bootstrap, Channel> {
	public ChannelFuture connect(final SocketAddress remoteAddress) {
		if (remoteAddress instanceof InetSocketAddress inet) {
			return JsWebSocketTransport.connect(inet.getHostString(), inet.getPort(), this.handler);
		}
		if (this.channelClass() != LocalChannel.class) {
			throw new UnsupportedOperationException("cannot connect " + this.channelClass() + " in browser");
		}
		if (WorkerChannelRegistry.isClientEnabled()) {
			return DefaultChannelPromise.succeeded(WorkerChannelRegistry.connect(remoteAddress, this.handler));
		}
		LocalChannel channel = LocalChannelRegistry.connect(remoteAddress, this.handler);
		return DefaultChannelPromise.succeeded(channel);
	}

	public ChannelFuture connect(final InetAddress host, final int port) {
		return JsWebSocketTransport.connect(host.getHostName(), port, this.handler);
	}
}
