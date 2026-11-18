// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.handler.codec.http;

public interface HttpResponse extends HttpMessage {
	HttpResponseStatus status();
}
