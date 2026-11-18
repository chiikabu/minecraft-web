// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang;

import java.io.InputStream;
import java.io.OutputStream;

public class TProcess {
	public InputStream getInputStream() { throw new UnsupportedOperationException("no processes in the browser"); }
	public InputStream getErrorStream() { throw new UnsupportedOperationException("no processes in the browser"); }
	public OutputStream getOutputStream() { throw new UnsupportedOperationException("no processes in the browser"); }
	public int waitFor() { throw new UnsupportedOperationException("no processes in the browser"); }
	public int exitValue() { throw new UnsupportedOperationException("no processes in the browser"); }
	public void destroy() { }
	public boolean isAlive() { return false; }
}
