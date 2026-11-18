// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.io.Closeable;

public interface TWatchService extends Closeable {
	TWatchService NONE = new TWatchService() {
		@Override
		public TWatchKey poll() {
			return null;
		}

		@Override
		public TWatchKey take() throws InterruptedException {
			throw new InterruptedException("nothing is watched in the browser");
		}

		@Override
		public void close() {
		}
	};

	TWatchKey poll();

	TWatchKey take() throws InterruptedException;
}
