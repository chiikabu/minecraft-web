// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.util.List;

public interface TWatchKey {
	TWatchKey NONE = new TWatchKey() {
		@Override
		public boolean isValid() {
			return true;
		}

		@Override
		public List<TWatchEvent<?>> pollEvents() {
			return List.of();
		}

		@Override
		public boolean reset() {
			return true;
		}

		@Override
		public void cancel() {
		}

		@Override
		public TWatchable watchable() {
			return null;
		}
	};

	boolean isValid();

	List<TWatchEvent<?>> pollEvents();

	boolean reset();

	void cancel();

	TWatchable watchable();
}
