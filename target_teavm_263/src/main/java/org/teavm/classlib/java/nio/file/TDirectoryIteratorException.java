// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.io.IOException;
import java.util.ConcurrentModificationException;

public class TDirectoryIteratorException extends ConcurrentModificationException {
	public TDirectoryIteratorException(final IOException cause) {
		super(cause);
	}

	@Override
	public IOException getCause() {
		return (IOException) super.getCause();
	}
}
