// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.worker;

import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.local.LocalAddress;
import java.net.SocketAddress;

public final class WorkerChannelRegistry {
	private static final LocalAddress CLIENT = new LocalAddress("worker:client");

	private static WorkerChannel.Transport clientTransport;
	private static WorkerChannel.Transport serverTransport;
	private static WorkerChannel client;
	private static WorkerServerChannel server;

	private WorkerChannelRegistry() {
	}

	public static void enableClient(final WorkerChannel.Transport transport) {
		clientTransport = transport;
	}

	public static void enableServer(final WorkerChannel.Transport transport) {
		serverTransport = transport;
	}

	public static boolean isClientEnabled() {
		return clientTransport != null;
	}

	public static boolean isServerEnabled() {
		return serverTransport != null;
	}

	public static void reset() {
		WorkerChannel oldClient = client;
		WorkerServerChannel oldServer = server;
		clientTransport = null;
		serverTransport = null;
		client = null;
		server = null;
		if (oldClient != null) {
			oldClient.close();
		}
		if (oldServer != null) {
			oldServer.close();
		}
	}

	public static WorkerChannel connect(final SocketAddress remoteAddress, final ChannelHandler clientHandler) {
		if (clientTransport == null) {
			throw new ChannelException("no worker transport installed on this realm");
		}
		WorkerChannel channel = new WorkerChannel(CLIENT, remoteAddress, clientTransport);
		if (clientHandler != null) {
			channel.pipeline().addLast(clientHandler);
		}
		client = channel;
		channel.activate();
		return channel;
	}

	public static WorkerServerChannel bind(final SocketAddress localAddress, final ChannelHandler childHandler) {
		if (serverTransport == null) {
			throw new ChannelException("no worker transport installed on this realm");
		}
		SocketAddress address = localAddress == null || localAddress == LocalAddress.ANY
			? new LocalAddress("worker:server")
			: localAddress;
		server = new WorkerServerChannel(address, childHandler, serverTransport);
		server.activate();
		return server;
	}

	public static void deliver(final byte[] packet) {
		if (client != null) {
			client.onPacket(packet);
		} else if (server != null) {
			server.accepted().onPacket(packet);
		}
	}
}
