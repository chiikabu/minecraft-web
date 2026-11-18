// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.io.Closeable;
import java.io.IOException;

public class TFileSystem implements Closeable {
	public static final TFileSystem DEFAULT = new TFileSystem();

	public String getSeparator() {
		return "/";
	}

	public boolean isOpen() {
		return true;
	}

	public boolean isReadOnly() {
		return false;
	}

	public TPath getPath(String first, String... more) {
		return TPath.of(first, more);
	}

	public TWatchService newWatchService() throws IOException {
		return TWatchService.NONE;
	}

	@Override
	public void close() throws IOException {
	}
}
