// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.websocket;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import java.net.InetSocketAddress;
import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.websocket.WebSocket;

public final class JsWebSocketTransport implements WebSocketChannel.Transport {
	private static final int CONNECT_TIMEOUT_MS = 15000;

	private final WebSocket socket;

	private JsWebSocketTransport(final WebSocket socket) {
		this.socket = socket;
	}

	public static ChannelFuture connect(final String host, final int port, final ChannelHandler initializer) {
		if (host.trim().indexOf("://") < 0) {
			if (JsDirectTcpTransport.available()) {
				return JsDirectTcpTransport.connect(host.trim(), port, initializer);
			}
			return JsWispTransport.connect(host.trim(), port, initializer);
		}
		WebSocketChannel channel = new WebSocketChannel(InetSocketAddress.createUnresolved(host, port));
		String uri = WebSocketChannel.uriFor(host, port, pageIsSecure());
		if (initializer != null) {
			channel.pipeline().addLast(initializer);
		}

		WebSocket socket;
		try {
			socket = new WebSocket(uri, "socket-v1");
		} catch (Throwable e) {
			channel.onError("cannot open " + uri + ": " + e);
			return channel.connectPromise();
		}

		socket.setBinaryType("arraybuffer");
		channel.attach(new JsWebSocketTransport(socket));

		final int connectTimeout = Window.setTimeout(
			() -> channel.onError("timed out connecting to " + uri), CONNECT_TIMEOUT_MS);

		socket.onOpen(event -> {
			Window.clearTimeout(connectTimeout);
			channel.onOpen();
		});
		socket.onMessage(event -> {
			if (isArrayBuffer(event.getData())) {
				channel.onBinaryFrame(new Int8Array(event.getDataAsArray()).copyToJavaArray());
			}
		});
		socket.onClose(event -> {
			Window.clearTimeout(connectTimeout);
			channel.onClosed("websocket closed (" + event.getCode() + ")");
		});
		socket.onError(event -> {
			Window.clearTimeout(connectTimeout);
			channel.onError("websocket error connecting to " + uri);
		});
		return channel.connectPromise();
	}

	@Override
	public void send(final byte[] frame) {
		this.socket.send(Int8Array.copyFromJavaArray(frame));
	}

	@Override
	public void close() {
		try {
			this.socket.close();
		} catch (Throwable e) {
		}
	}

	@JSBody(script = "try { return String(globalThis.location && globalThis.location.protocol) === 'https:'; }"
		+ " catch (e) { return false; }")
	private static native boolean pageIsSecure();

	@JSBody(params = { "data" }, script = "return data instanceof ArrayBuffer;")
	private static native boolean isArrayBuffer(org.teavm.jso.JSObject data);
}
