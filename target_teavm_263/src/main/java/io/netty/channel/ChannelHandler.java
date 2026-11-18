// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.channel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface ChannelHandler {
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	@interface Sharable {
	}

	default void handlerAdded(ChannelHandlerContext ctx) throws Exception {
	}

	default void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
	}
}
