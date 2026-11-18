// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

public final class THttpHeaders {
	private THttpHeaders() {
	}

	public Optional<String> firstValue(final String name) {
		return Optional.empty();
	}

	public OptionalLong firstValueAsLong(final String name) {
		return OptionalLong.empty();
	}

	public List<String> allValues(final String name) {
		return List.of();
	}

	public Map<String, List<String>> map() {
		return Map.of();
	}
}
