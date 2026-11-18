// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AttributeKey<T> {
	private static final ConcurrentMap<String, AttributeKey<?>> NAMES = new ConcurrentHashMap<>();
	private final String name;

	private AttributeKey(final String name) {
		this.name = name;
	}

	@SuppressWarnings("unchecked")
	public static <T> AttributeKey<T> valueOf(final String name) {
		return (AttributeKey<T>) NAMES.computeIfAbsent(name, AttributeKey::new);
	}

	public String name() {
		return this.name;
	}

	@Override
	public String toString() {
		return this.name;
	}
}
