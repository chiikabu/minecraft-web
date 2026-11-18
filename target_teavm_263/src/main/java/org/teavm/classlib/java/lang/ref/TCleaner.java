// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.ref;

import org.teavm.classlib.java.util.concurrent.TThreadFactory;

public final class TCleaner {
	private TCleaner() {
	}

	public static TCleaner create() {
		return new TCleaner();
	}

	public static TCleaner create(TThreadFactory threadFactory) {
		return new TCleaner();
	}

	public Cleanable register(Object obj, Runnable action) {
		return () -> {
		};
	}

	public interface Cleanable {
		void clean();
	}
}
