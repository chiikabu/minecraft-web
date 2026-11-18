// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

public final class TDatagramPacket {
	private byte[] buffer;
	private int offset;
	private int length;
	private TInetAddress address;
	private int port;

	public TDatagramPacket(final byte[] buffer, final int length) {
		this(buffer, 0, length);
	}

	public TDatagramPacket(final byte[] buffer, final int offset, final int length) {
		this.buffer = buffer;
		this.offset = offset;
		this.length = length;
	}

	public TDatagramPacket(final byte[] buffer, final int length, final TInetAddress address, final int port) {
		this(buffer, 0, length);
		this.address = address;
		this.port = port;
	}

	public TDatagramPacket(final byte[] buffer, final int length, final TSocketAddress address) {
		this(buffer, 0, length);
		setSocketAddress(address);
	}

	public byte[] getData() {
		return this.buffer;
	}

	public void setData(final byte[] buffer) {
		this.buffer = buffer;
		this.offset = 0;
		this.length = buffer.length;
	}

	public int getOffset() {
		return this.offset;
	}

	public int getLength() {
		return this.length;
	}

	public void setLength(final int length) {
		this.length = length;
	}

	public TInetAddress getAddress() {
		return this.address;
	}

	public void setAddress(final TInetAddress address) {
		this.address = address;
	}

	public int getPort() {
		return this.port;
	}

	public void setPort(final int port) {
		this.port = port;
	}

	public TSocketAddress getSocketAddress() {
		return new TInetSocketAddress(this.address, this.port);
	}

	public void setSocketAddress(final TSocketAddress address) {
		TInetSocketAddress socketAddress = (TInetSocketAddress) address;
		this.address = socketAddress.getAddress();
		this.port = socketAddress.getPort();
	}
}
