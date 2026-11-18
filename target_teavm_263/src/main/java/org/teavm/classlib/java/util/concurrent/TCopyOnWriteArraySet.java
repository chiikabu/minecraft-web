// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.Collection;
import java.util.LinkedHashSet;

public class TCopyOnWriteArraySet<E> extends LinkedHashSet<E> {
	public TCopyOnWriteArraySet() {
	}

	public TCopyOnWriteArraySet(Collection<? extends E> c) {
		super(c);
	}
}
