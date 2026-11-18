// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

public interface AttributeMap {
	<T> Attribute<T> attr(AttributeKey<T> key);

	<T> boolean hasAttr(AttributeKey<T> key);
}
