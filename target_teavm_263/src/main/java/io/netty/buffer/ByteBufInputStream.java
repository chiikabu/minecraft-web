// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.buffer;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public class ByteBufInputStream extends InputStream implements DataInput {
	private final ByteBuf buffer;
	private final int startIndex;
	private final int endIndex;
	private final boolean releaseOnClose;
	private boolean closed;

	public ByteBufInputStream(final ByteBuf buffer) {
		this(buffer, buffer.readableBytes(), false);
	}

	public ByteBufInputStream(final ByteBuf buffer, final int length) {
		this(buffer, length, false);
	}

	public ByteBufInputStream(final ByteBuf buffer, final int length, final boolean releaseOnClose) {
		if (length < 0) {
			if (releaseOnClose) {
				buffer.release();
			}
			throw new IllegalArgumentException("length: " + length);
		}
		if (length > buffer.readableBytes()) {
			if (releaseOnClose) {
				buffer.release();
			}
			throw new IndexOutOfBoundsException("length(" + length + ") > readableBytes(" + buffer.readableBytes() + ')');
		}
		this.buffer = buffer;
		this.releaseOnClose = releaseOnClose;
		this.startIndex = buffer.readerIndex();
		this.endIndex = this.startIndex + length;
		buffer.markReaderIndex();
	}

	public int readBytes() {
		return this.buffer.readerIndex() - this.startIndex;
	}

	@Override
	public void close() throws IOException {
		try {
			super.close();
		} finally {
			if (this.releaseOnClose && !this.closed) {
				this.closed = true;
				this.buffer.release();
			}
		}
	}

	@Override
	public int available() {
		return this.endIndex - this.buffer.readerIndex();
	}

	@Override
	public void mark(final int readlimit) {
		this.buffer.markReaderIndex();
	}

	@Override
	public boolean markSupported() {
		return true;
	}

	@Override
	public void reset() {
		this.buffer.resetReaderIndex();
	}

	@Override
	public int read() {
		return this.available() <= 0 ? -1 : this.buffer.readByte() & 255;
	}

	@Override
	public int read(final byte[] b, final int off, final int len) {
		int available = this.available();
		if (available == 0) {
			return len == 0 ? 0 : -1;
		}
		int count = Math.min(available, len);
		this.buffer.readBytes(b, off, count);
		return count;
	}

	@Override
	public long skip(final long n) {
		return this.skipBytes((int)Math.min(n, Integer.MAX_VALUE));
	}

	@Override
	public int skipBytes(final int n) {
		int count = Math.min(this.available(), n);
		if (count <= 0) {
			return 0;
		}
		this.buffer.skipBytes(count);
		return count;
	}

	private void checkAvailable(final int length) throws IOException {
		if (length < 0) {
			throw new IndexOutOfBoundsException("length: " + length);
		}
		if (length > this.available()) {
			throw new EOFException();
		}
	}

	@Override
	public void readFully(final byte[] b) throws IOException {
		this.readFully(b, 0, b.length);
	}

	@Override
	public void readFully(final byte[] b, final int off, final int len) throws IOException {
		this.checkAvailable(len);
		this.buffer.readBytes(b, off, len);
	}

	@Override
	public boolean readBoolean() throws IOException {
		return this.readByte() != 0;
	}

	@Override
	public byte readByte() throws IOException {
		if (this.available() <= 0) {
			throw new EOFException();
		}
		return this.buffer.readByte();
	}

	@Override
	public int readUnsignedByte() throws IOException {
		return this.readByte() & 255;
	}

	@Override
	public short readShort() throws IOException {
		this.checkAvailable(2);
		return this.buffer.readShort();
	}

	@Override
	public int readUnsignedShort() throws IOException {
		return this.readShort() & 65535;
	}

	@Override
	public char readChar() throws IOException {
		return (char)this.readShort();
	}

	@Override
	public int readInt() throws IOException {
		this.checkAvailable(4);
		return this.buffer.readInt();
	}

	@Override
	public long readLong() throws IOException {
		this.checkAvailable(8);
		return this.buffer.readLong();
	}

	@Override
	public float readFloat() throws IOException {
		return Float.intBitsToFloat(this.readInt());
	}

	@Override
	public double readDouble() throws IOException {
		return Double.longBitsToDouble(this.readLong());
	}

	@Override
	public String readLine() throws IOException {
		int available = this.available();
		if (available == 0) {
			return null;
		}

		StringBuilder line = new StringBuilder();
		while (this.available() > 0) {
			int c = this.buffer.readUnsignedByte();
			if (c == '\n') {
				break;
			}
			if (c == '\r') {
				if (this.available() > 0 && (char)this.buffer.getUnsignedByte(this.buffer.readerIndex()) == '\n') {
					this.buffer.skipBytes(1);
				}
				break;
			}
			line.append((char)c);
		}
		return line.toString();
	}

	@Override
	public String readUTF() throws IOException {
		return DataInputStream.readUTF(this);
	}
}
