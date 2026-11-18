// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.io;

import java.io.IOException;
import java.io.OutputStream;

public class TObjectOutputStream {
	public TObjectOutputStream(OutputStream out) throws IOException {
		throw new IOException("Java serialization unavailable in browser");
	}

	public void writeObject(Object o) throws IOException {
		throw new IOException("Java serialization unavailable in browser");
	}
}
