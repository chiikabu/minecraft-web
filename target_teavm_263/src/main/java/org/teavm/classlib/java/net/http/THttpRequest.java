// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net.http;

import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.function.Supplier;
import org.teavm.classlib.java.net.TURI;

public class THttpRequest {
	public interface BodyPublisher {
	}

	public interface Builder {
		Builder GET();

		Builder HEAD();

		Builder POST(BodyPublisher publisher);

		Builder PUT(BodyPublisher publisher);

		Builder DELETE();

		Builder header(String name, String value);

		Builder setHeader(String name, String value);

		Builder timeout(Duration duration);

		Builder uri(TURI uri);

		THttpRequest build();
	}

	public static final class BodyPublishers {
		private static final BodyPublisher EMPTY = new BodyPublisher() {
		};

		private BodyPublishers() {
		}

		public static BodyPublisher noBody() {
			return EMPTY;
		}

		public static BodyPublisher ofString(final String body) {
			return EMPTY;
		}

		public static BodyPublisher ofByteArray(final byte[] body) {
			return EMPTY;
		}

		public static BodyPublisher ofInputStream(final Supplier<? extends InputStream> supplier) {
			return EMPTY;
		}

		public static BodyPublisher fromPublisher(final Flow.Publisher<? extends java.nio.ByteBuffer> publisher, final long contentLength) {
			return EMPTY;
		}
	}

	private final TURI uri;

	private THttpRequest(final TURI uri) {
		this.uri = uri;
	}

	public static Builder newBuilder(final TURI uri) {
		return new Builder() {
			@Override
			public Builder GET() {
				return this;
			}

			@Override
			public Builder HEAD() {
				return this;
			}

			@Override
			public Builder POST(final BodyPublisher publisher) {
				return this;
			}

			@Override
			public Builder PUT(final BodyPublisher publisher) {
				return this;
			}

			@Override
			public Builder DELETE() {
				return this;
			}

			@Override
			public Builder header(final String name, final String value) {
				return this;
			}

			@Override
			public Builder setHeader(final String name, final String value) {
				return this;
			}

			@Override
			public Builder timeout(final Duration duration) {
				return this;
			}

			@Override
			public Builder uri(final TURI other) {
				return this;
			}

			@Override
			public THttpRequest build() {
				return new THttpRequest(uri);
			}
		};
	}

	public TURI uri() {
		return this.uri;
	}
}
