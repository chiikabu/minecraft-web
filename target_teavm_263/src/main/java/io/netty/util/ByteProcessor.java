// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

public interface ByteProcessor {
	boolean process(byte value) throws Exception;
}
