// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.websocket;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import java.net.InetSocketAddress;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class JsDirectTcpTransport implements WebSocketChannel.Transport {

	private final JSObject socket;

	private JsDirectTcpTransport(final JSObject socket) {
		this.socket = socket;
	}

	@JSBody(script = "return !!globalThis.__minecraftDirectTcp && globalThis.__minecraftDirectTcp.available();")
	public static native boolean available();

	public static ChannelFuture connect(final String host, final int port, final ChannelHandler initializer) {
		WebSocketChannel channel = new WebSocketChannel(InetSocketAddress.createUnresolved(host, port), true);
		if (initializer != null) {
			channel.pipeline().addLast(initializer);
		}
		JSObject socket = open(host, port, (kind, data, reason) -> {
			switch (kind) {
				case 0 -> channel.onOpen();
				case 1 -> channel.onBinaryFrame(data.copyToJavaArray());
				case 2 -> channel.onClosed(reason);
				default -> channel.onError(reason);
			}
		});
		channel.attach(new JsDirectTcpTransport(socket));
		return channel.connectPromise();
	}

	@Override
	public void send(final byte[] frame) {
		send(this.socket, Int8Array.copyFromJavaArray(frame));
	}

	@Override
	public void close() {
		close(this.socket);
	}

	@JSFunctor
	private interface Events extends JSObject {
		void event(int kind, Int8Array data, String reason);
	}

	@JSBody(params = { "host", "port", "event" },
		script = "return globalThis.__minecraftDirectTcp.open(host, port, event);")
	private static native JSObject open(String host, int port, Events event);

	@JSBody(params = { "socket", "bytes" }, script = "socket.send(bytes);")
	private static native void send(JSObject socket, Int8Array bytes);

	@JSBody(params = { "socket" }, script = "socket.close();")
	private static native void close(JSObject socket);
}
