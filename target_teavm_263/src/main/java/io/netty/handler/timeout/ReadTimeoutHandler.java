// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.timeout;

import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.concurrent.TimeUnit;

public class ReadTimeoutHandler extends ChannelInboundHandlerAdapter {
	public ReadTimeoutHandler(final int timeoutSeconds) {
	}

	public ReadTimeoutHandler(final long timeout, final TimeUnit unit) {
	}
}
