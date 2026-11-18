// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

import java.io.IOException;
import java.util.jar.JarFile;

public abstract class TJarURLConnection extends TURLConnection {
	protected TURL jarFileURL;
	protected TURLConnection jarFileURLConnection;

	protected TJarURLConnection(TURL url) {
		super(url);
	}

	public TURL getJarFileURL() {
		return jarFileURL;
	}

	public JarFile getJarFile() throws IOException {
		throw new IOException("no jar files in the browser");
	}

	public String getEntryName() {
		return null;
	}
}
