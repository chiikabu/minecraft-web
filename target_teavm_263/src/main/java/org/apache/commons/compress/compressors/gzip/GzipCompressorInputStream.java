// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.commons.compress.compressors.gzip;

import java.io.IOException;
import java.io.InputStream;

public class GzipCompressorInputStream extends InputStream {
	public GzipCompressorInputStream(InputStream in) {
		throw new UnsupportedOperationException("commons-compress gzip is not available in the browser");
	}

	@Override
	public int read() throws IOException {
		throw new IOException("commons-compress gzip is not available in the browser");
	}
}
