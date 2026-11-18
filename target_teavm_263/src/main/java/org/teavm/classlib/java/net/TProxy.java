// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class TProxy {
	public enum Type {
		DIRECT,
		HTTP,
		SOCKS
	}

	public static final TProxy NO_PROXY = new TProxy(Type.DIRECT);

	private final Type type;
	private final SocketAddress address;

	private TProxy(Type type) {
		this.type = type;
		this.address = null;
	}

	public TProxy(Type type, SocketAddress address) {
		if (type == Type.DIRECT || !(address instanceof InetSocketAddress)) {
			throw new IllegalArgumentException("Invalid proxy type or address");
		}
		this.type = type;
		this.address = address;
	}

	public SocketAddress address() { return address; }

	@Override
	public final boolean equals(Object other) {
		return other instanceof TProxy proxy && type() == proxy.type()
				&& java.util.Objects.equals(address(), proxy.address());
	}

	@Override
	public final int hashCode() { return type().hashCode() + java.util.Objects.hashCode(address()); }

	public Type type() {
		return type;
	}

	@Override
	public String toString() {
		return type() == Type.DIRECT ? "DIRECT" : type() + " @ " + address();
	}
}
