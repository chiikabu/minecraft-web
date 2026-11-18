// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.commons.compress.archivers.tar;

import java.io.IOException;
import java.io.InputStream;

public class TarArchiveInputStream extends InputStream {
	public TarArchiveInputStream(InputStream in) {
		throw new UnsupportedOperationException("tar archives are not available in the browser");
	}

	public TarArchiveEntry getNextTarEntry() throws IOException {
		throw new IOException("tar archives are not available in the browser");
	}

	@Override
	public int read() throws IOException {
		throw new IOException("tar archives are not available in the browser");
	}
}
