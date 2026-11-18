// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.local;

import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandler;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;

public final class LocalChannelRegistry {
	private static final Map<LocalAddress, LocalServerChannel> BOUND = new HashMap<>();
	private static int nextId;

	private LocalChannelRegistry() {
	}

	public static LocalServerChannel bind(final SocketAddress localAddress, final ChannelHandler childHandler) {
		LocalServerChannel channel = new LocalServerChannel();
		channel.bind(localAddress, childHandler);
		return channel;
	}

	public static LocalChannel connect(final SocketAddress remoteAddress, final ChannelHandler clientHandler) {
		LocalServerChannel server = get(remoteAddress);
		if (server == null) {
			throw new ChannelException("connection refused: " + remoteAddress);
		}
		LocalChannel client = new LocalChannel();
		LocalChannel serverSide = server.accept(client);
		if (clientHandler != null) {
			client.pipeline().addLast(clientHandler);
		}
		serverSide.activate();
		client.activate();
		return client;
	}

	static LocalAddress register(final LocalServerChannel channel, final SocketAddress localAddress) {
		LocalAddress address = localAddress == null || localAddress == LocalAddress.ANY
			? new LocalAddress("ephemeral-" + nextId++)
			: (LocalAddress)localAddress;
		if (BOUND.containsKey(address)) {
			throw new ChannelException("address already in use: " + address);
		}
		BOUND.put(address, channel);
		return address;
	}

	static void unregister(final LocalAddress address) {
		BOUND.remove(address);
	}

	static LocalServerChannel get(final SocketAddress address) {
		return address instanceof LocalAddress local ? BOUND.get(local) : null;
	}
}
