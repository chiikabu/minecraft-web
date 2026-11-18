// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec.http;

import java.util.HashMap;
import java.util.Map;

public class DefaultHttpHeaders implements HttpHeaders {
	private final Map<String, String> values = new HashMap<>();

	@Override
	public String get(final CharSequence name) {
		return this.values.get(String.valueOf(name).toLowerCase());
	}

	@Override
	public HttpHeaders set(final CharSequence name, final Object value) {
		this.values.put(String.valueOf(name).toLowerCase(), String.valueOf(value));
		return this;
	}
}
