// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib;

import java.net.Proxy;

public abstract class HttpDiscoveryService implements DiscoveryService {
	private final Proxy proxy;

	protected HttpDiscoveryService(Proxy proxy) {
		this.proxy = proxy;
	}

	public Proxy getProxy() {
		return proxy;
	}
}
