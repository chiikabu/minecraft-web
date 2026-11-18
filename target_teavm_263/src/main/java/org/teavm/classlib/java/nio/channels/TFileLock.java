// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.channels;

import java.io.IOException;
import org.teavm.classlib.java.nio.file.TVfs;

public class TFileLock implements AutoCloseable {
	private final String key;
	private boolean valid = true;

	TFileLock(String key) {
		this.key = key;
	}

	public boolean isValid() {
		return valid;
	}

	public void release() throws IOException {
		if (valid) {
			valid = false;
			TVfs.unlock(key);
		}
	}

	@Override
	public void close() throws IOException {
		release();
	}
}
