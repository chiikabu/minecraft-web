// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.jpountz.lz4;

import java.io.IOException;
import java.io.InputStream;

public class LZ4BlockInputStream extends InputStream {
	public LZ4BlockInputStream(InputStream in) throws IOException {
		throw new IOException("lz4 region file compression is not available in the browser");
	}

	@Override
	public int read() throws IOException {
		throw new IOException("lz4 region file compression is not available in the browser");
	}
}
