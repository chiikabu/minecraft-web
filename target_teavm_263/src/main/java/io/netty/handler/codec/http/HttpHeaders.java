// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec.http;

public interface HttpHeaders {
	String get(CharSequence name);

	HttpHeaders set(CharSequence name, Object value);
}
