// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

public interface ChannelConfig {
	boolean isAutoRead();

	ChannelConfig setAutoRead(boolean autoRead);

	boolean setOption(ChannelOption<?> option, Object value);
}
