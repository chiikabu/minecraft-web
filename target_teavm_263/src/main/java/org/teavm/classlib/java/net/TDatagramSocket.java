// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

import java.io.Closeable;
import java.io.IOException;

public class TDatagramSocket implements Closeable {
	private boolean closed;

	public TDatagramSocket() throws IOException {
	}

	public TDatagramSocket(final int port) throws IOException {
	}

	public TDatagramSocket(final int port, final TInetAddress bindAddress) throws IOException {
	}

	public void send(final TDatagramPacket packet) throws IOException {
		throw new IOException("UDP unavailable in browser");
	}

	public void receive(final TDatagramPacket packet) throws IOException {
		throw new IOException("UDP unavailable in browser");
	}

	public void setSoTimeout(final int timeout) throws IOException {
	}

	public int getLocalPort() {
		return -1;
	}

	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		this.closed = true;
	}
}
