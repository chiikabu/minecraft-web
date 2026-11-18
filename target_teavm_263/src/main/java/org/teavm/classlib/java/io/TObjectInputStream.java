// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.io;

import java.io.IOException;
import java.io.InputStream;

public class TObjectInputStream {
	public TObjectInputStream(InputStream in) throws IOException {
		throw new IOException("Java serialization unavailable in browser");
	}

	public Object readObject() throws IOException, ClassNotFoundException {
		throw new IOException("Java serialization unavailable in browser");
	}
}
