// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

public interface ReferenceCounted {
	int refCnt();

	ReferenceCounted retain();

	ReferenceCounted retain(int increment);

	ReferenceCounted touch();

	ReferenceCounted touch(Object hint);

	boolean release();

	boolean release(int decrement);
}
