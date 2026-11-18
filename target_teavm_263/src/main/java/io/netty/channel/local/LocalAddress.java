// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel.local;

import java.net.SocketAddress;

public final class LocalAddress extends SocketAddress implements Comparable<LocalAddress> {
	public static final LocalAddress ANY = new LocalAddress("ANY");

	private final String id;

	public LocalAddress(final String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}

	@Override
	public int compareTo(final LocalAddress other) {
		return this.id.compareTo(other.id);
	}

	@Override
	public boolean equals(final Object other) {
		return other instanceof LocalAddress address && this.id.equals(address.id);
	}

	@Override
	public int hashCode() {
		return this.id.hashCode();
	}

	@Override
	public String toString() {
		return "local:" + this.id;
	}
}
