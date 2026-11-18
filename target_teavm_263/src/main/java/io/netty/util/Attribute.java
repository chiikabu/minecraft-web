// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

public interface Attribute<T> {
	AttributeKey<T> key();

	T get();

	void set(T value);
}
