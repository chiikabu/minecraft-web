// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class TSocket implements Closeable {
	private boolean closed;

	public TSocket() {
	}

	public TSocket(final String host, final int port) throws IOException {
		throw new IOException("TCP sockets unavailable in browser");
	}

	public void connect(final TSocketAddress address) throws IOException {
		throw new IOException("TCP sockets unavailable in browser");
	}

	public void connect(final TSocketAddress address, final int timeout) throws IOException {
		throw new IOException("TCP sockets unavailable in browser");
	}

	public InputStream getInputStream() throws IOException {
		throw new IOException("TCP sockets unavailable in browser");
	}

	public OutputStream getOutputStream() throws IOException {
		throw new IOException("TCP sockets unavailable in browser");
	}

	public TInetAddress getInetAddress() {
		return null;
	}

	public int getPort() {
		return -1;
	}

	public boolean isClosed() {
		return this.closed;
	}

	public boolean isConnected() {
		return false;
	}

	public void setSoTimeout(final int timeout) throws IOException {
	}

	@Override
	public void close() {
		this.closed = true;
	}
}
