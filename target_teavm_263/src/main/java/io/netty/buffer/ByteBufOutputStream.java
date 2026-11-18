// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.buffer;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ByteBufOutputStream extends OutputStream implements DataOutput {
	private final ByteBuf buffer;
	private final int startIndex;
	private final DataOutputStream utf8out = new DataOutputStream(this);

	public ByteBufOutputStream(final ByteBuf buffer) {
		this.buffer = buffer;
		this.startIndex = buffer.writerIndex();
	}

	public int writtenBytes() {
		return this.buffer.writerIndex() - this.startIndex;
	}

	public ByteBuf buffer() {
		return this.buffer;
	}

	@Override
	public void write(final byte[] b, final int off, final int len) {
		if (len != 0) {
			this.buffer.writeBytes(b, off, len);
		}
	}

	@Override
	public void write(final byte[] b) {
		this.buffer.writeBytes(b);
	}

	@Override
	public void write(final int b) {
		this.buffer.writeByte(b);
	}

	@Override
	public void writeBoolean(final boolean v) {
		this.buffer.writeBoolean(v);
	}

	@Override
	public void writeByte(final int v) {
		this.buffer.writeByte(v);
	}

	@Override
	public void writeBytes(final String s) {
		int length = s.length();
		for (int i = 0; i < length; i++) {
			this.buffer.writeByte((byte)s.charAt(i));
		}
	}

	@Override
	public void writeChar(final int v) {
		this.buffer.writeChar(v);
	}

	@Override
	public void writeChars(final String s) {
		int length = s.length();
		for (int i = 0; i < length; i++) {
			this.buffer.writeChar(s.charAt(i));
		}
	}

	@Override
	public void writeDouble(final double v) {
		this.buffer.writeDouble(v);
	}

	@Override
	public void writeFloat(final float v) {
		this.buffer.writeFloat(v);
	}

	@Override
	public void writeInt(final int v) {
		this.buffer.writeInt(v);
	}

	@Override
	public void writeLong(final long v) {
		this.buffer.writeLong(v);
	}

	@Override
	public void writeShort(final int v) {
		this.buffer.writeShort((short)v);
	}

	@Override
	public void writeUTF(final String s) throws IOException {
		this.utf8out.writeUTF(s);
	}

	public void writeAscii(final CharSequence s) {
		this.buffer.writeBytes(s.toString().getBytes(StandardCharsets.US_ASCII));
	}
}
