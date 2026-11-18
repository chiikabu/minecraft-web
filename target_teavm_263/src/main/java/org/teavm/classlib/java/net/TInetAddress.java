// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

public class TInetAddress {
	private static final byte[] NO_ADDRESS = new byte[4];

	private final String host;
	private final byte[] address;

	TInetAddress(final String host) {
		this(host, NO_ADDRESS);
	}

	TInetAddress(final String host, final byte[] address) {
		this.host = host;
		this.address = address;
	}

	public static TInetAddress getByName(final String host) throws TUnknownHostException {
		return new TInetAddress(host);
	}

	public static TInetAddress[] getAllByName(final String host) throws TUnknownHostException {
		return new TInetAddress[] { getByName(host) };
	}

	public static TInetAddress getByAddress(final byte[] address) throws TUnknownHostException {
		return getByAddress(null, address);
	}

	public static TInetAddress getByAddress(final String host, final byte[] address) throws TUnknownHostException {
		String name = host != null ? host : format(address);
		if (address.length == 4) {
			return new TInet4Address(name, address);
		} else if (address.length == 16) {
			return new TInet6Address(name, address, 0, null);
		} else {
			throw new TUnknownHostException("addr is of illegal length");
		}
	}

	public static TInetAddress getLocalHost() throws TUnknownHostException {
		return getLoopbackAddress();
	}

	public static TInetAddress getLoopbackAddress() {
		return new TInet4Address("127.0.0.1", new byte[] { 127, 0, 0, 1 });
	}

	static String format(final byte[] address) {
		StringBuilder builder = new StringBuilder();
		if (address.length == 4) {
			for (int i = 0; i < 4; i++) {
				if (i > 0) {
					builder.append('.');
				}

				builder.append(address[i] & 255);
			}
		} else {
			for (int i = 0; i < address.length; i += 2) {
				if (i > 0) {
					builder.append(':');
				}

				builder.append(Integer.toHexString((address[i] & 255) << 8 | address[i + 1] & 255));
			}
		}

		return builder.toString();
	}

	public String getHostAddress() {
		return this.host;
	}

	public String getHostName() {
		return this.host;
	}

	public String getCanonicalHostName() {
		return this.host;
	}

	public byte[] getAddress() {
		return this.address;
	}

	public boolean isAnyLocalAddress() {
		return false;
	}

	public boolean isLoopbackAddress() {
		return this.address.length == 4 && this.address[0] == 127;
	}

	@Override
	public boolean equals(final Object other) {
		return other instanceof TInetAddress that && this.host.equals(that.host);
	}

	@Override
	public int hashCode() {
		return this.host.hashCode();
	}

	@Override
	public String toString() {
		return this.host + "/" + this.host;
	}
}
