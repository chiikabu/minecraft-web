// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

public class TInet6Address extends TInetAddress {
	private final int scopeId;
	private final TNetworkInterface scopedInterface;

	TInet6Address(final String host, final byte[] address, final int scopeId, final TNetworkInterface scopedInterface) {
		super(host, address);
		this.scopeId = scopeId;
		this.scopedInterface = scopedInterface;
	}

	public static TInet6Address getByAddress(final String host, final byte[] address, final int scopeId) {
		return new TInet6Address(host != null ? host : TInetAddress.format(address), address, scopeId, null);
	}

	public static TInet6Address getByAddress(final String host, final byte[] address, final TNetworkInterface scopedInterface) {
		return new TInet6Address(host != null ? host : TInetAddress.format(address), address, 0, scopedInterface);
	}

	public int getScopeId() {
		return this.scopeId;
	}

	public TNetworkInterface getScopedInterface() {
		return this.scopedInterface;
	}

	public boolean isIPv4CompatibleAddress() {
		byte[] address = this.getAddress();
		for (int i = 0; i < 12; i++) {
			if (address[i] != 0) {
				return false;
			}
		}

		return true;
	}
}
