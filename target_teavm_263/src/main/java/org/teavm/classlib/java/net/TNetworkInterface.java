// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

public final class TNetworkInterface {
	private final String name;

	private TNetworkInterface(final String name) {
		this.name = name;
	}

	public static TNetworkInterface getByName(final String name) {
		return null;
	}

	public String getName() {
		return this.name;
	}
}
