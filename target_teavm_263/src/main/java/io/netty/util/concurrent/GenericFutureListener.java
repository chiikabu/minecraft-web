// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util.concurrent;

public interface GenericFutureListener<F extends Future<?>> {
	void operationComplete(F future) throws Exception;
}
