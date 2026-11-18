// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net.http;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class THttpClient implements AutoCloseable {
	public interface Builder {
		Builder connectTimeout(Duration duration);

		Builder executor(Executor executor);

		THttpClient build();
	}

	public static Builder newBuilder() {
		return new Builder() {
			@Override
			public Builder connectTimeout(final Duration duration) {
				return this;
			}

			@Override
			public Builder executor(final Executor executor) {
				return this;
			}

			@Override
			public THttpClient build() {
				return new THttpClient();
			}
		};
	}

	public static THttpClient newHttpClient() {
		return new THttpClient();
	}

	public <T> THttpResponse<T> send(final THttpRequest request, final THttpResponse.BodyHandler<T> handler) throws IOException {
		throw new IOException("HTTP unavailable in browser");
	}

	public <T> CompletableFuture<THttpResponse<T>> sendAsync(final THttpRequest request, final THttpResponse.BodyHandler<T> handler) {
		CompletableFuture<THttpResponse<T>> future = new CompletableFuture<>();
		future.completeExceptionally(new IOException("HTTP unavailable in browser"));
		return future;
	}

	@Override
	public void close() {
	}
}
