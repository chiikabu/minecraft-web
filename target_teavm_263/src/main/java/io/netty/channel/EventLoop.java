// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

public interface EventLoop extends EventLoopGroup {
	boolean inEventLoop();
}
