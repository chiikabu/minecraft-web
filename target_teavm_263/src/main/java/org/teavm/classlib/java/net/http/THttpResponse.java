// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net.http;

import java.io.InputStream;
import java.nio.charset.Charset;

public interface THttpResponse<T> {
	interface BodyHandler<T> {
	}

	final class BodyHandlers {
		private BodyHandlers() {
		}

		private static <T> BodyHandler<T> handler() {
			return new BodyHandler<T>() {
			};
		}

		public static BodyHandler<Void> discarding() {
			return handler();
		}

		public static BodyHandler<String> ofString() {
			return handler();
		}

		public static BodyHandler<String> ofString(final Charset charset) {
			return handler();
		}

		public static BodyHandler<InputStream> ofInputStream() {
			return handler();
		}

		public static BodyHandler<byte[]> ofByteArray() {
			return handler();
		}
	}

	int statusCode();

	T body();

	THttpHeaders headers();
}
