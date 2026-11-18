// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec.http;

import io.netty.buffer.ByteBuf;

public class DefaultFullHttpResponse implements HttpResponse {
	private final HttpVersion version;
	private final HttpResponseStatus status;
	private final ByteBuf content;
	private final HttpHeaders headers = new DefaultHttpHeaders();

	public DefaultFullHttpResponse(final HttpVersion version, final HttpResponseStatus status, final ByteBuf content) {
		this.version = version;
		this.status = status;
		this.content = content;
	}

	@Override
	public HttpHeaders headers() {
		return this.headers;
	}

	@Override
	public HttpResponseStatus status() {
		return this.status;
	}
}
