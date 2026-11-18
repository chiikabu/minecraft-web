// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.LinkedHashMap;
import java.util.Map;

final class LruByteCache<K, V> {
	interface Sizer<V> {
		long sizeOf(V value);
	}

	private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(16, 0.75F, true);
	private final long capBytes;
	private final Sizer<V> sizer;
	private long bytes;
	private int evictions;

	LruByteCache(final long capBytes, final Sizer<V> sizer) {
		this.capBytes = capBytes;
		this.sizer = sizer;
	}

	V get(final K key) {
		return this.entries.get(key);
	}

	void put(final K key, final V value) {
		V old = this.entries.put(key, value);
		if (old != null) {
			this.bytes -= this.sizer.sizeOf(old);
		}
		this.bytes += this.sizer.sizeOf(value);
		this.evictDown();
	}

	private void evictDown() {
		java.util.Iterator<Map.Entry<K, V>> it = this.entries.entrySet().iterator();
		while (this.bytes > this.capBytes && this.entries.size() > 1 && it.hasNext()) {
			Map.Entry<K, V> eldest = it.next();
			this.bytes -= this.sizer.sizeOf(eldest.getValue());
			it.remove();
			this.evictions++;
		}
	}

	void clear() {
		this.entries.clear();
		this.bytes = 0L;
	}

	Map<K, V> asMap() {
		return this.entries;
	}

	int size() {
		return this.entries.size();
	}

	long bytes() {
		return this.bytes;
	}

	int evictions() {
		return this.evictions;
	}
}
