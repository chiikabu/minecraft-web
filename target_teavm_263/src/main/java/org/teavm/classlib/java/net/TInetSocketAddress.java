// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

public class TInetSocketAddress extends TSocketAddress {
	private final TInetAddress address;
	private final String hostname;
	private final int port;
	private final boolean unresolved;

	public TInetSocketAddress(final int port) {
		this("0.0.0.0", port);
	}

	public TInetSocketAddress(final String hostname, final int port) {
		this(new TInetAddress(hostname), hostname, port, false);
	}

	public TInetSocketAddress(final TInetAddress address, final int port) {
		this(address, address == null ? "0.0.0.0" : address.getHostName(), port, false);
	}

	private TInetSocketAddress(final TInetAddress address, final String hostname, final int port, final boolean unresolved) {
		this.address = address;
		this.hostname = hostname;
		this.port = port;
		this.unresolved = unresolved;
	}

	public static TInetSocketAddress createUnresolved(final String host, final int port) {
		return new TInetSocketAddress(null, host, port, true);
	}

	public TInetAddress getAddress() {
		return this.address;
	}

	public String getHostName() {
		return this.hostname;
	}

	public String getHostString() {
		return this.hostname;
	}

	public int getPort() {
		return this.port;
	}

	public boolean isUnresolved() {
		return this.unresolved;
	}

	@Override
	public boolean equals(final Object other) {
		if (!(other instanceof TInetSocketAddress that)) {
			return false;
		}

		return this.port == that.port && this.hostname.equals(that.hostname);
	}

	@Override
	public int hashCode() {
		return this.hostname.hashCode() * 31 + this.port;
	}

	@Override
	public String toString() {
		return this.hostname + "/" + this.hostname + ":" + this.port;
	}
}
