// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

public final class ChannelOption<T> {
	public static final ChannelOption<Boolean> TCP_NODELAY = new ChannelOption<>("TCP_NODELAY");

	private final String name;

	private ChannelOption(final String name) {
		this.name = name;
	}

	public String name() {
		return this.name;
	}

	@Override
	public String toString() {
		return this.name;
	}
}
