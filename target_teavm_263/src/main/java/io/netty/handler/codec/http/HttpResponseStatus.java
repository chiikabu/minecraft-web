// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec.http;

public final class HttpResponseStatus {
	public static final HttpResponseStatus SWITCHING_PROTOCOLS = new HttpResponseStatus(101);
	public static final HttpResponseStatus UNAUTHORIZED = new HttpResponseStatus(401);

	private final int code;

	private HttpResponseStatus(final int code) {
		this.code = code;
	}

	public int code() {
		return this.code;
	}
}
