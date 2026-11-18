// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

import java.io.IOException;

public class TMulticastSocket extends TDatagramSocket {
	public TMulticastSocket() throws IOException {
	}

	public TMulticastSocket(final int port) throws IOException {
	}

	public void joinGroup(final TInetAddress group) throws IOException {
		throw new IOException("multicast unavailable in browser");
	}

	public void leaveGroup(final TInetAddress group) throws IOException {
		throw new IOException("multicast unavailable in browser");
	}

	public void setTimeToLive(final int ttl) throws IOException {
	}
}
