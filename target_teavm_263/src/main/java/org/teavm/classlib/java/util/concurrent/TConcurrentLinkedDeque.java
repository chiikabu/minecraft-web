// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;

public class TConcurrentLinkedDeque<E> extends ArrayDeque<E> {
	public TConcurrentLinkedDeque() {
	}

	public TConcurrentLinkedDeque(Collection<? extends E> source) {
		super(source);
	}
}
