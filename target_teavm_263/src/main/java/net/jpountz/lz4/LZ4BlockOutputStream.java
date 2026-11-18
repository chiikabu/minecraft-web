// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.jpountz.lz4;

import java.io.IOException;
import java.io.OutputStream;

public class LZ4BlockOutputStream extends OutputStream {
	public LZ4BlockOutputStream(OutputStream out) throws IOException {
		throw new IOException("lz4 region file compression is not available in the browser");
	}

	@Override
	public void write(int b) throws IOException {
		throw new IOException("lz4 region file compression is not available in the browser");
	}
}
