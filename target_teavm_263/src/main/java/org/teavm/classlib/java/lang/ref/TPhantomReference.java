// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.ref;

public class TPhantomReference<T> extends TReference<T> {
	public TPhantomReference(T value, TReferenceQueue<? super T> queue) {
	}

	@Override
	public T get() {
		return null;
	}
}
