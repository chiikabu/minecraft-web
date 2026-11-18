// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.commons.compress.archivers.tar;

import java.io.IOException;
import java.io.OutputStream;

public class TarArchiveOutputStream extends OutputStream {
	public TarArchiveOutputStream(OutputStream out) {
		throw new UnsupportedOperationException("tar archives are not available in the browser");
	}

	public void setLongFileMode(int mode) {
	}

	public long getBytesWritten() {
		return 0L;
	}

	public void putArchiveEntry(TarArchiveEntry entry) throws IOException {
		throw new IOException("tar archives are not available in the browser");
	}

	public void closeArchiveEntry() throws IOException {
		throw new IOException("tar archives are not available in the browser");
	}

	public void finish() throws IOException {
		throw new IOException("tar archives are not available in the browser");
	}

	@Override
	public void write(int b) throws IOException {
		throw new IOException("tar archives are not available in the browser");
	}
}
