// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

import java.io.Closeable;
import java.io.IOException;

public class TServerSocket implements Closeable {
	public TServerSocket() throws IOException {
		throw new IOException("listening sockets unavailable in browser");
	}

	public TServerSocket(final int port) throws IOException {
		throw new IOException("listening sockets unavailable in browser");
	}

	public TServerSocket(final int port, final int backlog) throws IOException {
		throw new IOException("listening sockets unavailable in browser");
	}

	public TServerSocket(final int port, final int backlog, final TInetAddress bindAddress) throws IOException {
		throw new IOException("listening sockets unavailable in browser");
	}

	public TSocket accept() throws IOException {
		throw new IOException("listening sockets unavailable in browser");
	}

	public void setSoTimeout(final int timeout) throws IOException {
	}

	public int getLocalPort() {
		return -1;
	}

	public boolean isClosed() {
		return true;
	}

	@Override
	public void close() {
	}
}
