// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.buffer;

import io.netty.util.ByteProcessor;
import io.netty.util.IllegalReferenceCountException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;

public class ByteBuf implements io.netty.util.ReferenceCounted, Comparable<ByteBuf> {
	private static final byte[] EMPTY = new byte[0];
	private static final int CALC_THRESHOLD = 1048576 * 4;

	private final ByteBuf root;
	private final ByteBuf parent;
	private byte[] array;
	private final int offset;

	private int cap;
	private final int maxCapacity;
	private final boolean growable;

	private int readerIndex;
	private int writerIndex;
	private int markedReaderIndex;
	private int markedWriterIndex;
	private int refCnt = 1;

	public ByteBuf() {
		this(new byte[256], true, Integer.MAX_VALUE);
	}

	ByteBuf(final byte[] array) {
		this(array, false, array.length);
		this.writerIndex = array.length;
	}

	ByteBuf(final byte[] array, final boolean growable, final int maxCapacity) {
		this.root = this;
		this.parent = null;
		this.array = array;
		this.offset = 0;
		this.cap = array.length;
		this.growable = growable;
		this.maxCapacity = maxCapacity;
	}

	private ByteBuf(final ByteBuf parent, final int offset, final int length) {
		this.root = parent.root;
		this.parent = parent;
		this.array = null;
		this.offset = offset;
		this.cap = length;
		this.growable = false;
		this.maxCapacity = length;
	}

	private byte[] mem() {
		return this.root.array;
	}

	public ByteBufAllocator alloc() {
		return ByteBufAllocator.DEFAULT;
	}

	public boolean hasArray() {
		return true;
	}

	public byte[] array() {
		return this.mem();
	}

	public int arrayOffset() {
		return this.offset;
	}

	public boolean hasMemoryAddress() {
		return false;
	}

	public long memoryAddress() {
		throw new UnsupportedOperationException("no direct memory in the browser shim");
	}

	public boolean isDirect() {
		return false;
	}

	public boolean isReadOnly() {
		return false;
	}

	public ByteBuf asReadOnly() {
		return this.duplicate();
	}

	public boolean isContiguous() {
		return true;
	}

	public ByteBuf asByteBuf() {
		return this;
	}

	public ByteBuf unwrap() {
		return this.parent;
	}

	public int capacity() {
		return this.cap;
	}

	public ByteBuf capacity(final int newCapacity) {
		if (newCapacity < 0 || newCapacity > this.maxCapacity) {
			throw new IllegalArgumentException("newCapacity: " + newCapacity + " (expected: 0-" + this.maxCapacity + ')');
		}
		if (this.root != this) {
			throw new UnsupportedOperationException("derived buffer");
		}
		if (newCapacity != this.array.length) {
			this.array = Arrays.copyOf(this.array, newCapacity);
		}
		this.cap = newCapacity;
		if (this.readerIndex > newCapacity) {
			this.readerIndex = newCapacity;
		}
		if (this.writerIndex > newCapacity) {
			this.writerIndex = newCapacity;
		}
		return this;
	}

	public int maxCapacity() {
		return this.maxCapacity;
	}

	public ByteOrder order() {
		return ByteOrder.BIG_ENDIAN;
	}

	public ByteBuf order(final ByteOrder endianness) {
		if (endianness == ByteOrder.BIG_ENDIAN) {
			return this;
		}
		throw new UnsupportedOperationException("little-endian views are not implemented; use the *LE accessors");
	}

	public int readerIndex() {
		return this.readerIndex;
	}

	public ByteBuf readerIndex(final int readerIndex) {
		if (readerIndex < 0 || readerIndex > this.writerIndex) {
			throw new IndexOutOfBoundsException("readerIndex: " + readerIndex + " (expected: 0 <= readerIndex <= writerIndex(" + this.writerIndex + "))");
		}
		this.readerIndex = readerIndex;
		return this;
	}

	public int writerIndex() {
		return this.writerIndex;
	}

	public ByteBuf writerIndex(final int writerIndex) {
		if (writerIndex < this.readerIndex || writerIndex > this.cap) {
			throw new IndexOutOfBoundsException("writerIndex: " + writerIndex + " (expected: readerIndex(" + this.readerIndex + ") <= writerIndex <= capacity(" + this.cap + "))");
		}
		this.writerIndex = writerIndex;
		return this;
	}

	public ByteBuf setIndex(final int readerIndex, final int writerIndex) {
		if (readerIndex < 0 || readerIndex > writerIndex || writerIndex > this.cap) {
			throw new IndexOutOfBoundsException("readerIndex: " + readerIndex + ", writerIndex: " + writerIndex + " (expected: 0 <= readerIndex <= writerIndex <= capacity(" + this.cap + "))");
		}
		this.readerIndex = readerIndex;
		this.writerIndex = writerIndex;
		return this;
	}

	public int readableBytes() {
		return this.writerIndex - this.readerIndex;
	}

	public int writableBytes() {
		return this.cap - this.writerIndex;
	}

	public int maxWritableBytes() {
		return this.maxCapacity - this.writerIndex;
	}

	public int maxFastWritableBytes() {
		return this.writableBytes();
	}

	public boolean isReadable() {
		return this.writerIndex > this.readerIndex;
	}

	public boolean isReadable(final int size) {
		return this.writerIndex - this.readerIndex >= size;
	}

	public boolean isWritable() {
		return this.cap > this.writerIndex;
	}

	public boolean isWritable(final int size) {
		return this.cap - this.writerIndex >= size;
	}

	public ByteBuf clear() {
		this.readerIndex = 0;
		this.writerIndex = 0;
		return this;
	}

	public ByteBuf markReaderIndex() {
		this.markedReaderIndex = this.readerIndex;
		return this;
	}

	public ByteBuf resetReaderIndex() {
		return this.readerIndex(this.markedReaderIndex);
	}

	public ByteBuf markWriterIndex() {
		this.markedWriterIndex = this.writerIndex;
		return this;
	}

	public ByteBuf resetWriterIndex() {
		return this.writerIndex(this.markedWriterIndex);
	}

	public ByteBuf discardReadBytes() {
		if (this.readerIndex == 0) {
			return this;
		}
		if (this.readerIndex != this.writerIndex) {
			System.arraycopy(this.mem(), this.offset + this.readerIndex, this.mem(), this.offset, this.writerIndex - this.readerIndex);
			this.writerIndex -= this.readerIndex;
		} else {
			this.writerIndex = 0;
		}
		this.markedReaderIndex = Math.max(this.markedReaderIndex - this.readerIndex, 0);
		this.markedWriterIndex = Math.max(this.markedWriterIndex - this.readerIndex, 0);
		this.readerIndex = 0;
		return this;
	}

	public ByteBuf discardSomeReadBytes() {
		if (this.readerIndex > 0 && (this.readerIndex == this.writerIndex || this.readerIndex >= this.cap >>> 1)) {
			return this.discardReadBytes();
		}
		return this;
	}

	public ByteBuf ensureWritable(final int minWritableBytes) {
		if (minWritableBytes < 0) {
			throw new IllegalArgumentException("minWritableBytes: " + minWritableBytes);
		}
		if (minWritableBytes <= this.writableBytes()) {
			return this;
		}
		if (!this.growable || minWritableBytes > this.maxCapacity - this.writerIndex) {
			throw new IndexOutOfBoundsException(
				"writerIndex(" + this.writerIndex + ") + " + minWritableBytes + " exceeds capacity(" + this.cap + ")"
			);
		}
		this.capacity(this.calculateNewCapacity(this.writerIndex + minWritableBytes));
		return this;
	}

	public int ensureWritable(final int minWritableBytes, final boolean force) {
		if (minWritableBytes <= this.writableBytes()) {
			return 0;
		}
		if (!this.growable || minWritableBytes > this.maxCapacity - this.writerIndex) {
			if (!force || !this.growable || this.cap == this.maxCapacity) {
				return 1;
			}
			this.capacity(this.maxCapacity);
			return 3;
		}
		this.capacity(this.calculateNewCapacity(this.writerIndex + minWritableBytes));
		return 2;
	}

	private int calculateNewCapacity(final int minNewCapacity) {
		if (minNewCapacity > CALC_THRESHOLD) {
			int newCapacity = minNewCapacity / CALC_THRESHOLD * CALC_THRESHOLD;
			return newCapacity > this.maxCapacity - CALC_THRESHOLD ? this.maxCapacity : newCapacity + CALC_THRESHOLD;
		}
		int newCapacity = 64;
		while (newCapacity < minNewCapacity) {
			newCapacity <<= 1;
		}
		return Math.min(newCapacity, this.maxCapacity);
	}

	@Override
	public int refCnt() {
		return this.root == this ? this.refCnt : this.root.refCnt();
	}

	@Override
	public ByteBuf retain() {
		return this.retain(1);
	}

	@Override
	public ByteBuf retain(final int increment) {
		if (increment <= 0) {
			throw new IllegalArgumentException("increment: " + increment + " (expected: > 0)");
		}
		if (this.root != this) {
			this.root.retain(increment);
			return this;
		}
		if (this.refCnt <= 0) {
			throw new IllegalReferenceCountException(0, increment);
		}
		this.refCnt += increment;
		return this;
	}

	@Override
	public ByteBuf touch() {
		return this;
	}

	@Override
	public ByteBuf touch(final Object hint) {
		return this;
	}

	@Override
	public boolean release() {
		return this.release(1);
	}

	@Override
	public boolean release(final int decrement) {
		if (decrement <= 0) {
			throw new IllegalArgumentException("decrement: " + decrement + " (expected: > 0)");
		}
		if (this.root != this) {
			return this.root.release(decrement);
		}
		if (decrement > this.refCnt) {
			throw new IllegalReferenceCountException(this.refCnt, -decrement);
		}
		this.refCnt -= decrement;
		if (this.refCnt > 0) {
			return false;
		}
		this.array = EMPTY;
		this.cap = 0;
		this.readerIndex = 0;
		this.writerIndex = 0;
		return true;
	}

	private void checkIndex(final int index, final int length) {
		if (index < 0 || length < 0 || index > this.cap - length) {
			throw new IndexOutOfBoundsException("index: " + index + ", length: " + length + " (expected: range(0, " + this.cap + "))");
		}
	}

	private int at(final int index) {
		return this.offset + index;
	}

	public boolean getBoolean(final int index) {
		return this.getByte(index) != 0;
	}

	public byte getByte(final int index) {
		this.checkIndex(index, 1);
		return this.mem()[this.at(index)];
	}

	public short getUnsignedByte(final int index) {
		return (short)(this.getByte(index) & 255);
	}

	public short getShort(final int index) {
		this.checkIndex(index, 2);
		byte[] a = this.mem();
		int i = this.at(index);
		return (short)((a[i] & 255) << 8 | a[i + 1] & 255);
	}

	public short getShortLE(final int index) {
		this.checkIndex(index, 2);
		byte[] a = this.mem();
		int i = this.at(index);
		return (short)(a[i] & 255 | (a[i + 1] & 255) << 8);
	}

	public int getUnsignedShort(final int index) {
		return this.getShort(index) & 65535;
	}

	public int getUnsignedShortLE(final int index) {
		return this.getShortLE(index) & 65535;
	}

	public int getMedium(final int index) {
		int value = this.getUnsignedMedium(index);
		return (value & 0x800000) != 0 ? value | 0xFF000000 : value;
	}

	public int getMediumLE(final int index) {
		int value = this.getUnsignedMediumLE(index);
		return (value & 0x800000) != 0 ? value | 0xFF000000 : value;
	}

	public int getUnsignedMedium(final int index) {
		this.checkIndex(index, 3);
		byte[] a = this.mem();
		int i = this.at(index);
		return (a[i] & 255) << 16 | (a[i + 1] & 255) << 8 | a[i + 2] & 255;
	}

	public int getUnsignedMediumLE(final int index) {
		this.checkIndex(index, 3);
		byte[] a = this.mem();
		int i = this.at(index);
		return a[i] & 255 | (a[i + 1] & 255) << 8 | (a[i + 2] & 255) << 16;
	}

	public int getInt(final int index) {
		this.checkIndex(index, 4);
		byte[] a = this.mem();
		int i = this.at(index);
		return (a[i] & 255) << 24 | (a[i + 1] & 255) << 16 | (a[i + 2] & 255) << 8 | a[i + 3] & 255;
	}

	public int getIntLE(final int index) {
		this.checkIndex(index, 4);
		byte[] a = this.mem();
		int i = this.at(index);
		return a[i] & 255 | (a[i + 1] & 255) << 8 | (a[i + 2] & 255) << 16 | (a[i + 3] & 255) << 24;
	}

	public long getUnsignedInt(final int index) {
		return this.getInt(index) & 4294967295L;
	}

	public long getUnsignedIntLE(final int index) {
		return this.getIntLE(index) & 4294967295L;
	}

	public long getLong(final int index) {
		return ((long)this.getInt(index) & 4294967295L) << 32 | this.getInt(index + 4) & 4294967295L;
	}

	public long getLongLE(final int index) {
		return this.getIntLE(index) & 4294967295L | ((long)this.getIntLE(index + 4) & 4294967295L) << 32;
	}

	public char getChar(final int index) {
		return (char)this.getShort(index);
	}

	public float getFloat(final int index) {
		return Float.intBitsToFloat(this.getInt(index));
	}

	public float getFloatLE(final int index) {
		return Float.intBitsToFloat(this.getIntLE(index));
	}

	public double getDouble(final int index) {
		return Double.longBitsToDouble(this.getLong(index));
	}

	public double getDoubleLE(final int index) {
		return Double.longBitsToDouble(this.getLongLE(index));
	}

	public ByteBuf getBytes(final int index, final byte[] dst) {
		return this.getBytes(index, dst, 0, dst.length);
	}

	public ByteBuf getBytes(final int index, final byte[] dst, final int dstIndex, final int length) {
		this.checkIndex(index, length);
		System.arraycopy(this.mem(), this.at(index), dst, dstIndex, length);
		return this;
	}

	public ByteBuf getBytes(final int index, final ByteBuf dst) {
		return this.getBytes(index, dst, dst.writableBytes());
	}

	public ByteBuf getBytes(final int index, final ByteBuf dst, final int length) {
		this.getBytes(index, dst, dst.writerIndex(), length);
		dst.writerIndex(dst.writerIndex() + length);
		return this;
	}

	public ByteBuf getBytes(final int index, final ByteBuf dst, final int dstIndex, final int length) {
		this.checkIndex(index, length);
		dst.setBytes(dstIndex, this.mem(), this.at(index), length);
		return this;
	}

	public ByteBuf getBytes(final int index, final ByteBuffer dst) {
		int length = dst.remaining();
		this.checkIndex(index, length);
		dst.put(this.mem(), this.at(index), length);
		return this;
	}

	public ByteBuf getBytes(final int index, final OutputStream out, final int length) throws IOException {
		this.checkIndex(index, length);
		out.write(this.mem(), this.at(index), length);
		return this;
	}

	public int getBytes(final int index, final java.nio.channels.GatheringByteChannel out, final int length) throws IOException {
		return out.write(this.internalNioBuffer(index, length));
	}

	public int getBytes(final int index, final java.nio.channels.FileChannel out, final long position, final int length) throws IOException {
		return out.write(this.internalNioBuffer(index, length), position);
	}

	public CharSequence getCharSequence(final int index, final int length, final Charset charset) {
		this.checkIndex(index, length);
		return new String(this.mem(), this.at(index), length, charset);
	}

	public ByteBuf setBoolean(final int index, final boolean value) {
		return this.setByte(index, value ? 1 : 0);
	}

	public ByteBuf setByte(final int index, final int value) {
		this.checkIndex(index, 1);
		this.mem()[this.at(index)] = (byte)value;
		return this;
	}

	public ByteBuf setShort(final int index, final int value) {
		this.checkIndex(index, 2);
		byte[] a = this.mem();
		int i = this.at(index);
		a[i] = (byte)(value >>> 8);
		a[i + 1] = (byte)value;
		return this;
	}

	public ByteBuf setShortLE(final int index, final int value) {
		this.checkIndex(index, 2);
		byte[] a = this.mem();
		int i = this.at(index);
		a[i] = (byte)value;
		a[i + 1] = (byte)(value >>> 8);
		return this;
	}

	public ByteBuf setChar(final int index, final int value) {
		return this.setShort(index, value);
	}

	public ByteBuf setMedium(final int index, final int value) {
		this.checkIndex(index, 3);
		byte[] a = this.mem();
		int i = this.at(index);
		a[i] = (byte)(value >>> 16);
		a[i + 1] = (byte)(value >>> 8);
		a[i + 2] = (byte)value;
		return this;
	}

	public ByteBuf setMediumLE(final int index, final int value) {
		this.checkIndex(index, 3);
		byte[] a = this.mem();
		int i = this.at(index);
		a[i] = (byte)value;
		a[i + 1] = (byte)(value >>> 8);
		a[i + 2] = (byte)(value >>> 16);
		return this;
	}

	public ByteBuf setInt(final int index, final int value) {
		this.checkIndex(index, 4);
		byte[] a = this.mem();
		int i = this.at(index);
		a[i] = (byte)(value >>> 24);
		a[i + 1] = (byte)(value >>> 16);
		a[i + 2] = (byte)(value >>> 8);
		a[i + 3] = (byte)value;
		return this;
	}

	public ByteBuf setIntLE(final int index, final int value) {
		this.checkIndex(index, 4);
		byte[] a = this.mem();
		int i = this.at(index);
		a[i] = (byte)value;
		a[i + 1] = (byte)(value >>> 8);
		a[i + 2] = (byte)(value >>> 16);
		a[i + 3] = (byte)(value >>> 24);
		return this;
	}

	public ByteBuf setLong(final int index, final long value) {
		this.setInt(index, (int)(value >>> 32));
		this.setInt(index + 4, (int)value);
		return this;
	}

	public ByteBuf setLongLE(final int index, final long value) {
		this.setIntLE(index, (int)value);
		this.setIntLE(index + 4, (int)(value >>> 32));
		return this;
	}

	public ByteBuf setFloat(final int index, final float value) {
		return this.setInt(index, Float.floatToRawIntBits(value));
	}

	public ByteBuf setFloatLE(final int index, final float value) {
		return this.setIntLE(index, Float.floatToRawIntBits(value));
	}

	public ByteBuf setDouble(final int index, final double value) {
		return this.setLong(index, Double.doubleToRawLongBits(value));
	}

	public ByteBuf setDoubleLE(final int index, final double value) {
		return this.setLongLE(index, Double.doubleToRawLongBits(value));
	}

	public ByteBuf setBytes(final int index, final byte[] src) {
		return this.setBytes(index, src, 0, src.length);
	}

	public ByteBuf setBytes(final int index, final byte[] src, final int srcIndex, final int length) {
		this.checkIndex(index, length);
		System.arraycopy(src, srcIndex, this.mem(), this.at(index), length);
		return this;
	}

	public ByteBuf setBytes(final int index, final ByteBuf src) {
		return this.setBytes(index, src, src.readableBytes());
	}

	public ByteBuf setBytes(final int index, final ByteBuf src, final int length) {
		this.setBytes(index, src, src.readerIndex(), length);
		src.readerIndex(src.readerIndex() + length);
		return this;
	}

	public ByteBuf setBytes(final int index, final ByteBuf src, final int srcIndex, final int length) {
		this.checkIndex(index, length);
		src.getBytes(srcIndex, this.mem(), this.at(index), length);
		return this;
	}

	public ByteBuf setBytes(final int index, final ByteBuffer src) {
		int length = src.remaining();
		this.checkIndex(index, length);
		src.get(this.mem(), this.at(index), length);
		return this;
	}

	public int setBytes(final int index, final InputStream in, final int length) throws IOException {
		this.checkIndex(index, length);
		int read = 0;
		while (read < length) {
			int n = in.read(this.mem(), this.at(index) + read, length - read);
			if (n < 0) {
				break;
			}
			read += n;
		}
		return read == 0 && length > 0 ? -1 : read;
	}

	public int setBytes(final int index, final java.nio.channels.ScatteringByteChannel in, final int length) throws IOException {
		return in.read(this.internalNioBuffer(index, length));
	}

	public int setBytes(final int index, final java.nio.channels.FileChannel in, final long position, final int length) throws IOException {
		return in.read(this.internalNioBuffer(index, length), position);
	}

	public int setCharSequence(final int index, final CharSequence seq, final Charset charset) {
		byte[] bytes = seq.toString().getBytes(charset);
		this.setBytes(index, bytes);
		return bytes.length;
	}

	public ByteBuf setZero(final int index, final int length) {
		this.checkIndex(index, length);
		Arrays.fill(this.mem(), this.at(index), this.at(index) + length, (byte)0);
		return this;
	}

	private void checkReadable(final int length) {
		if (length < 0 || this.readerIndex + length > this.writerIndex) {
			throw new IndexOutOfBoundsException(
				"readerIndex(" + this.readerIndex + ") + " + length + " exceeds writerIndex(" + this.writerIndex + ")"
			);
		}
	}

	public boolean readBoolean() {
		return this.readByte() != 0;
	}

	public byte readByte() {
		this.checkReadable(1);
		return this.mem()[this.at(this.readerIndex++)];
	}

	public short readUnsignedByte() {
		return (short)(this.readByte() & 255);
	}

	public short readShort() {
		this.checkReadable(2);
		short value = this.getShort(this.readerIndex);
		this.readerIndex += 2;
		return value;
	}

	public short readShortLE() {
		this.checkReadable(2);
		short value = this.getShortLE(this.readerIndex);
		this.readerIndex += 2;
		return value;
	}

	public int readUnsignedShort() {
		return this.readShort() & 65535;
	}

	public int readUnsignedShortLE() {
		return this.readShortLE() & 65535;
	}

	public int readMedium() {
		int value = this.readUnsignedMedium();
		return (value & 0x800000) != 0 ? value | 0xFF000000 : value;
	}

	public int readMediumLE() {
		int value = this.readUnsignedMediumLE();
		return (value & 0x800000) != 0 ? value | 0xFF000000 : value;
	}

	public int readUnsignedMedium() {
		this.checkReadable(3);
		int value = this.getUnsignedMedium(this.readerIndex);
		this.readerIndex += 3;
		return value;
	}

	public int readUnsignedMediumLE() {
		this.checkReadable(3);
		int value = this.getUnsignedMediumLE(this.readerIndex);
		this.readerIndex += 3;
		return value;
	}

	public int readInt() {
		this.checkReadable(4);
		int value = this.getInt(this.readerIndex);
		this.readerIndex += 4;
		return value;
	}

	public int readIntLE() {
		this.checkReadable(4);
		int value = this.getIntLE(this.readerIndex);
		this.readerIndex += 4;
		return value;
	}

	public long readUnsignedInt() {
		return this.readInt() & 4294967295L;
	}

	public long readUnsignedIntLE() {
		return this.readIntLE() & 4294967295L;
	}

	public long readLong() {
		this.checkReadable(8);
		long value = this.getLong(this.readerIndex);
		this.readerIndex += 8;
		return value;
	}

	public long readLongLE() {
		this.checkReadable(8);
		long value = this.getLongLE(this.readerIndex);
		this.readerIndex += 8;
		return value;
	}

	public char readChar() {
		return (char)this.readShort();
	}

	public float readFloat() {
		return Float.intBitsToFloat(this.readInt());
	}

	public float readFloatLE() {
		return Float.intBitsToFloat(this.readIntLE());
	}

	public double readDouble() {
		return Double.longBitsToDouble(this.readLong());
	}

	public double readDoubleLE() {
		return Double.longBitsToDouble(this.readLongLE());
	}

	public ByteBuf readBytes(final byte[] dst) {
		return this.readBytes(dst, 0, dst.length);
	}

	public ByteBuf readBytes(final byte[] dst, final int dstIndex, final int length) {
		this.checkReadable(length);
		this.getBytes(this.readerIndex, dst, dstIndex, length);
		this.readerIndex += length;
		return this;
	}

	public ByteBuf readBytes(final int length) {
		this.checkReadable(length);
		ByteBuf copy = this.copy(this.readerIndex, length);
		this.readerIndex += length;
		return copy;
	}

	public ByteBuf readBytes(final ByteBuf dst) {
		return this.readBytes(dst, dst.writableBytes());
	}

	public ByteBuf readBytes(final ByteBuf dst, final int length) {
		this.readBytes(dst, dst.writerIndex(), length);
		dst.writerIndex(dst.writerIndex() + length);
		return this;
	}

	public ByteBuf readBytes(final ByteBuf dst, final int dstIndex, final int length) {
		this.checkReadable(length);
		this.getBytes(this.readerIndex, dst, dstIndex, length);
		this.readerIndex += length;
		return this;
	}

	public ByteBuf readBytes(final ByteBuffer dst) {
		int length = dst.remaining();
		this.checkReadable(length);
		this.getBytes(this.readerIndex, dst);
		this.readerIndex += length;
		return this;
	}

	public ByteBuf readBytes(final OutputStream out, final int length) throws IOException {
		this.checkReadable(length);
		this.getBytes(this.readerIndex, out, length);
		this.readerIndex += length;
		return this;
	}

	public int readBytes(final java.nio.channels.GatheringByteChannel out, final int length) throws IOException {
		this.checkReadable(length);
		int written = this.getBytes(this.readerIndex, out, length);
		this.readerIndex += written;
		return written;
	}

	public int readBytes(final java.nio.channels.FileChannel out, final long position, final int length) throws IOException {
		this.checkReadable(length);
		int written = this.getBytes(this.readerIndex, out, position, length);
		this.readerIndex += written;
		return written;
	}

	public CharSequence readCharSequence(final int length, final Charset charset) {
		this.checkReadable(length);
		CharSequence seq = this.getCharSequence(this.readerIndex, length, charset);
		this.readerIndex += length;
		return seq;
	}

	public String readString(final int length, final Charset charset) {
		return this.readCharSequence(length, charset).toString();
	}

	public ByteBuf readSlice(final int length) {
		this.checkReadable(length);
		ByteBuf slice = this.slice(this.readerIndex, length);
		this.readerIndex += length;
		return slice;
	}

	public ByteBuf readRetainedSlice(final int length) {
		this.checkReadable(length);
		ByteBuf slice = this.retainedSlice(this.readerIndex, length);
		this.readerIndex += length;
		return slice;
	}

	public ByteBuf skipBytes(final int length) {
		this.checkReadable(length);
		this.readerIndex += length;
		return this;
	}

	public ByteBuf writeBoolean(final boolean value) {
		return this.writeByte(value ? 1 : 0);
	}

	public ByteBuf writeByte(final int value) {
		this.ensureWritable(1);
		this.mem()[this.at(this.writerIndex++)] = (byte)value;
		return this;
	}

	public ByteBuf writeShort(final int value) {
		this.ensureWritable(2);
		this.setShort(this.writerIndex, value);
		this.writerIndex += 2;
		return this;
	}

	public ByteBuf writeShortLE(final int value) {
		this.ensureWritable(2);
		this.setShortLE(this.writerIndex, value);
		this.writerIndex += 2;
		return this;
	}

	public ByteBuf writeChar(final int value) {
		return this.writeShort(value);
	}

	public ByteBuf writeMedium(final int value) {
		this.ensureWritable(3);
		this.setMedium(this.writerIndex, value);
		this.writerIndex += 3;
		return this;
	}

	public ByteBuf writeMediumLE(final int value) {
		this.ensureWritable(3);
		this.setMediumLE(this.writerIndex, value);
		this.writerIndex += 3;
		return this;
	}

	public ByteBuf writeInt(final int value) {
		this.ensureWritable(4);
		this.setInt(this.writerIndex, value);
		this.writerIndex += 4;
		return this;
	}

	public ByteBuf writeIntLE(final int value) {
		this.ensureWritable(4);
		this.setIntLE(this.writerIndex, value);
		this.writerIndex += 4;
		return this;
	}

	public ByteBuf writeLong(final long value) {
		this.ensureWritable(8);
		this.setLong(this.writerIndex, value);
		this.writerIndex += 8;
		return this;
	}

	public ByteBuf writeLongLE(final long value) {
		this.ensureWritable(8);
		this.setLongLE(this.writerIndex, value);
		this.writerIndex += 8;
		return this;
	}

	public ByteBuf writeFloat(final float value) {
		return this.writeInt(Float.floatToRawIntBits(value));
	}

	public ByteBuf writeFloatLE(final float value) {
		return this.writeIntLE(Float.floatToRawIntBits(value));
	}

	public ByteBuf writeDouble(final double value) {
		return this.writeLong(Double.doubleToRawLongBits(value));
	}

	public ByteBuf writeDoubleLE(final double value) {
		return this.writeLongLE(Double.doubleToRawLongBits(value));
	}

	public ByteBuf writeBytes(final byte[] src) {
		return this.writeBytes(src, 0, src.length);
	}

	public ByteBuf writeBytes(final byte[] src, final int srcIndex, final int length) {
		this.ensureWritable(length);
		this.setBytes(this.writerIndex, src, srcIndex, length);
		this.writerIndex += length;
		return this;
	}

	public ByteBuf writeBytes(final ByteBuf src) {
		return this.writeBytes(src, src.readableBytes());
	}

	public ByteBuf writeBytes(final ByteBuf src, final int length) {
		this.writeBytes(src, src.readerIndex(), length);
		src.readerIndex(src.readerIndex() + length);
		return this;
	}

	public ByteBuf writeBytes(final ByteBuf src, final int srcIndex, final int length) {
		this.ensureWritable(length);
		this.setBytes(this.writerIndex, src, srcIndex, length);
		this.writerIndex += length;
		return this;
	}

	public ByteBuf writeBytes(final ByteBuffer src) {
		int length = src.remaining();
		this.ensureWritable(length);
		this.setBytes(this.writerIndex, src);
		this.writerIndex += length;
		return this;
	}

	public int writeBytes(final InputStream in, final int length) throws IOException {
		this.ensureWritable(length);
		int written = this.setBytes(this.writerIndex, in, length);
		if (written > 0) {
			this.writerIndex += written;
		}
		return written;
	}

	public int writeBytes(final java.nio.channels.ScatteringByteChannel in, final int length) throws IOException {
		this.ensureWritable(length);
		int written = this.setBytes(this.writerIndex, in, length);
		if (written > 0) {
			this.writerIndex += written;
		}
		return written;
	}

	public int writeBytes(final java.nio.channels.FileChannel in, final long position, final int length) throws IOException {
		this.ensureWritable(length);
		int written = this.setBytes(this.writerIndex, in, position, length);
		if (written > 0) {
			this.writerIndex += written;
		}
		return written;
	}

	public int writeCharSequence(final CharSequence seq, final Charset charset) {
		byte[] bytes = seq.toString().getBytes(charset);
		this.writeBytes(bytes);
		return bytes.length;
	}

	public ByteBuf writeZero(final int length) {
		this.ensureWritable(length);
		this.setZero(this.writerIndex, length);
		this.writerIndex += length;
		return this;
	}

	public ByteBuf slice() {
		return this.slice(this.readerIndex, this.readableBytes());
	}

	public ByteBuf slice(final int index, final int length) {
		this.checkIndex(index, length);
		ByteBuf slice = new ByteBuf(this, this.at(index), length);
		slice.writerIndex = length;
		return slice;
	}

	public ByteBuf retainedSlice() {
		this.retain();
		return this.slice();
	}

	public ByteBuf retainedSlice(final int index, final int length) {
		this.retain();
		return this.slice(index, length);
	}

	public ByteBuf duplicate() {
		ByteBuf dup = new ByteBuf(this, this.offset, this.cap);
		dup.readerIndex = this.readerIndex;
		dup.writerIndex = this.writerIndex;
		return dup;
	}

	public ByteBuf retainedDuplicate() {
		this.retain();
		return this.duplicate();
	}

	public ByteBuf copy() {
		return this.copy(this.readerIndex, this.readableBytes());
	}

	public ByteBuf copy(final int index, final int length) {
		this.checkIndex(index, length);
		byte[] copy = new byte[length];
		System.arraycopy(this.mem(), this.at(index), copy, 0, length);
		ByteBuf out = new ByteBuf(copy, true, Integer.MAX_VALUE);
		out.writerIndex = length;
		return out;
	}

	public int nioBufferCount() {
		return 1;
	}

	public ByteBuffer nioBuffer() {
		return this.nioBuffer(this.readerIndex, this.readableBytes());
	}

	public ByteBuffer nioBuffer(final int index, final int length) {
		this.checkIndex(index, length);
		return ByteBuffer.wrap(this.mem(), this.at(index), length).slice();
	}

	public ByteBuffer internalNioBuffer(final int index, final int length) {
		return this.nioBuffer(index, length);
	}

	public ByteBuffer[] nioBuffers() {
		return new ByteBuffer[] { this.nioBuffer() };
	}

	public ByteBuffer[] nioBuffers(final int index, final int length) {
		return new ByteBuffer[] { this.nioBuffer(index, length) };
	}

	public int indexOf(final int fromIndex, final int toIndex, final byte value) {
		if (fromIndex <= toIndex) {
			int from = Math.max(fromIndex, 0);
			if (from >= toIndex) {
				return -1;
			}
			byte[] a = this.mem();
			for (int i = from; i < toIndex; i++) {
				if (a[this.at(i)] == value) {
					return i;
				}
			}
			return -1;
		}
		int from = Math.min(fromIndex, this.cap);
		if (from < 0 || toIndex >= this.cap) {
			return -1;
		}
		byte[] a = this.mem();
		for (int i = from - 1; i >= toIndex; i--) {
			if (a[this.at(i)] == value) {
				return i;
			}
		}
		return -1;
	}

	public int bytesBefore(final byte value) {
		return this.bytesBefore(this.readerIndex, this.readableBytes(), value);
	}

	public int bytesBefore(final int length, final byte value) {
		this.checkReadable(length);
		return this.bytesBefore(this.readerIndex, length, value);
	}

	public int bytesBefore(final int index, final int length, final byte value) {
		int found = this.indexOf(index, index + length, value);
		return found < 0 ? -1 : found - index;
	}

	public int forEachByte(final ByteProcessor processor) {
		return this.forEachByte(this.readerIndex, this.readableBytes(), processor);
	}

	public int forEachByte(final int index, final int length, final ByteProcessor processor) {
		this.checkIndex(index, length);
		byte[] a = this.mem();
		for (int i = index; i < index + length; i++) {
			if (!process(processor, a[this.at(i)])) {
				return i;
			}
		}
		return -1;
	}

	public int forEachByteDesc(final ByteProcessor processor) {
		return this.forEachByteDesc(this.readerIndex, this.readableBytes(), processor);
	}

	public int forEachByteDesc(final int index, final int length, final ByteProcessor processor) {
		this.checkIndex(index, length);
		byte[] a = this.mem();
		for (int i = index + length - 1; i >= index; i--) {
			if (!process(processor, a[this.at(i)])) {
				return i;
			}
		}
		return -1;
	}

	private static boolean process(final ByteProcessor processor, final byte value) {
		try {
			return processor.process(value);
		} catch (RuntimeException var3) {
			throw var3;
		} catch (Exception var4) {
			throw new RuntimeException(var4);
		}
	}

	@Override
	public String toString() {
		return "ByteBuf(ridx: " + this.readerIndex + ", widx: " + this.writerIndex + ", cap: " + this.cap + ")";
	}

	public String toString(final Charset charset) {
		return this.toString(this.readerIndex, this.readableBytes(), charset);
	}

	public String toString(final int index, final int length, final Charset charset) {
		this.checkIndex(index, length);
		return new String(this.mem(), this.at(index), length, charset);
	}

	@Override
	public boolean equals(final Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ByteBuf that) || this.readableBytes() != that.readableBytes()) {
			return false;
		}
		int length = this.readableBytes();
		for (int i = 0; i < length; i++) {
			if (this.getByte(this.readerIndex + i) != that.getByte(that.readerIndex() + i)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		int length = this.readableBytes();
		int intCount = length >>> 2;
		int byteCount = length & 3;
		int hash = 1;
		int index = this.readerIndex;
		for (int i = intCount; i > 0; i--) {
			hash = 31 * hash + this.getInt(index);
			index += 4;
		}
		for (int i = byteCount; i > 0; i--) {
			hash = 31 * hash + this.getByte(index++);
		}
		return hash == 0 ? 1 : hash;
	}

	@Override
	public int compareTo(final ByteBuf that) {
		int aLen = this.readableBytes();
		int bLen = that.readableBytes();
		int minLen = Math.min(aLen, bLen);
		for (int i = 0; i < minLen; i++) {
			int diff = (this.getByte(this.readerIndex + i) & 255) - (that.getByte(that.readerIndex() + i) & 255);
			if (diff != 0) {
				return diff;
			}
		}
		return aLen - bLen;
	}
}
