// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.Comparator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public class TConcurrentSkipListMap<K, V> extends TreeMap<K, V> {
	public TConcurrentSkipListMap() {
	}

	public TConcurrentSkipListMap(Comparator<? super K> comparator) {
		super(comparator);
	}

	public TConcurrentSkipListMap(Map<? extends K, ? extends V> source) {
		super(source);
	}

	public TConcurrentSkipListMap(SortedMap<K, ? extends V> source) {
		super(source);
	}
}
