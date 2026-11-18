// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.system;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import org.lwjgl.PointerBuffer;
import org.teavm.classlib.PlatformDetector;
import org.teavm.interop.Address;
import org.teavm.runtime.heap.Heap;

public final class MemoryUtil {
	public static final long NULL = 0L;

	public static final boolean LINEAR = PlatformDetector.isWebAssemblyGC();
	private static final long FOREIGN_BASE = 1L << 40;

	private static final class Block {
		final byte[] array;
		final long base;
		final int size;
		final boolean foreign;
		java.util.List<Buffer> views;

		Block(byte[] array, long base, int size, boolean foreign) {
			this.array = array;
			this.base = base;
			this.size = size;
			this.foreign = foreign;
		}
	}

	private static long nextAddress = LINEAR ? FOREIGN_BASE : 0x100000L;
	private static final IdentityHashMap<byte[], Block> byArray = new IdentityHashMap<>();
	private static final IdentityHashMap<Buffer, long[]> typed = new IdentityHashMap<>();

	private static long[] blockBase = new long[256];
	private static Block[] blockAt = new Block[256];
	private static int blockCount;
	private static Block lastResolved;

	private static final HashMap<Long, Integer> linearSizes = new HashMap<>();
	private static long linearLiveBytes;
	private static final HashMap<Long, Long> alignedBases = new HashMap<>();

	private MemoryUtil() {
	}

	private static boolean linear(long address) {
		return LINEAR && address < FOREIGN_BASE;
	}

	private static Address at(long address) {
		return Address.fromInt((int) address);
	}

	private static void indexInsert(long base, Block block) {
		if (blockCount == blockBase.length) {
			blockBase = java.util.Arrays.copyOf(blockBase, blockCount * 2);
			blockAt = java.util.Arrays.copyOf(blockAt, blockCount * 2);
		}
		assert blockCount == 0 || base > blockBase[blockCount - 1] : "fake-heap index insert out of order";
		blockBase[blockCount] = base;
		blockAt[blockCount] = block;
		blockCount++;
	}

	private static int indexFloor(long address) {
		int lo = 0;
		int hi = blockCount - 1;
		int found = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (blockBase[mid] <= address) {
				found = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return found;
	}

	private static Block newBlock(int size) {
		long base = nextAddress;
		nextAddress = (base + size + 15L) & ~15L;
		byte[] array = new byte[size];
		Block block = new Block(array, base, size, false);
		indexInsert(base, block);
		byArray.put(array, block);
		churnBlocksMinted++;
		return block;
	}

	public static boolean legacyChurn;
	static Object churnSink;

	private static Block resolve(long address) {
		if (legacyChurn) {
			churnSink = Long.valueOf(address);
		}
		Block cached = lastResolved;
		if (cached != null && address >= cached.base && address - cached.base < cached.size) {
			churnResolveHits++;
			return cached;
		}
		churnResolveMisses++;
		int i = indexFloor(address);
		Block block = i < 0 ? null : blockAt[i];
		if (block == null || address - block.base >= block.size) {
			throw new IllegalStateException("MemoryUtil shim: address 0x" + Long.toHexString(address) + " is not backed by any live allocation");
		}
		lastResolved = block;
		return block;
	}

	private static Block resolveRange(long address, int width) {
		Block block = resolve(address);
		int offset = (int) (address - block.base);
		if (width < 0 || width > block.size - offset) {
			throw new IllegalStateException("MemoryUtil shim: " + width + "-byte access at 0x" + Long.toHexString(address) + " overruns its " + block.size + "-byte allocation");
		}
		return block;
	}

	private static Block registerArray(byte[] array) {
		Block block = byArray.get(array);
		if (block == null) {
			long base = nextAddress;
			nextAddress = (base + array.length + 15L) & ~15L;
			block = new Block(array, base, array.length, true);
			indexInsert(base, block);
			byArray.put(array, block);
			churnForeignRegistered++;
		}
		return block;
	}

	static int churnResolveHits;
	static int churnResolveMisses;
	static int churnBlocksMinted;
	static int churnForeignRegistered;
	static int churnViewsMinted;

	public static int[] churnCounters() {
		return new int[] { churnResolveHits, churnResolveMisses, churnBlocksMinted, churnForeignRegistered, churnViewsMinted };
	}

	public static long[] debugStats() {
		long blocks = linearSizes.size();
		long bytes = linearLiveBytes;
		long foreign = 0;
		long foreignBytes = 0;
		for (int i = 0; i < blockCount; i++) {
			Block block = blockAt[i];
			blocks++;
			bytes += block.size;
			if (block.foreign) {
				foreign++;
				foreignBytes += block.size;
			}
		}
		return new long[] { blocks, bytes, foreign, foreignBytes, typed.size(), nextAddress };
	}

	public static long[] debugTopBlocks(int n) {
		long[] sizes = new long[blockCount + linearSizes.size()];
		int k = 0;
		for (int i = 0; i < blockCount; i++) {
			Block block = blockAt[i];
			sizes[k++] = ((long) block.size << 1) | (block.foreign ? 1L : 0L);
		}
		for (Integer size : linearSizes.values()) {
			sizes[k++] = (long) size << 1;
		}
		java.util.Arrays.sort(sizes);
		int take = Math.min(n, sizes.length);
		long[] out = new long[take];
		for (int j = 0; j < take; j++) {
			out[j] = sizes[sizes.length - 1 - j];
		}
		return out;
	}

	private static long addressOfTyped(Buffer buffer, int elementSize) {
		long[] rec = typed.get(buffer);
		if (rec == null) {
			throw new IllegalStateException("MemoryUtil shim: " + buffer.getClass().getSimpleName() + " was not allocated through this shim (MemoryUtil/MemoryStack/PointerBuffer), or its allocation has since been freed; cannot resolve its address");
		}
		return rec[0] + (long) buffer.position() * elementSize;
	}

	public static long memAddress(ByteBuffer buffer) {
		if (buffer.hasArray()) {
			Block block = registerArray(buffer.array());
			return block.base + buffer.arrayOffset() + buffer.position();
		}
		if (LINEAR) {
			long a = LinearMemory.address(buffer);
			if (a >= 0) {
				return a;
			}
		}
		return addressOfTyped(buffer, 1);
	}

	public static long memAddress(ByteBuffer buffer, int position) {
		return memAddress(buffer) - buffer.position() + position;
	}

	public static long memLinearAddress(ByteBuffer buffer) {
		return LINEAR && !buffer.hasArray() ? LinearMemory.address(buffer) : -1L;
	}

	public static long memAddress(IntBuffer buffer) {
		if (LINEAR) {
			long a = LinearMemory.address(buffer);
			if (a >= 0) {
				return a;
			}
		}
		return addressOfTyped(buffer, 4);
	}

	public static long memAddress(LongBuffer buffer) {
		if (LINEAR) {
			long a = LinearMemory.address(buffer);
			if (a >= 0) {
				return a;
			}
		}
		return addressOfTyped(buffer, 8);
	}

	public static long memAddress(PointerBuffer buffer) {
		return buffer.address();
	}

	public static long memAddress(CustomBuffer buffer) {
		return buffer.address();
	}

	public static ByteBuffer memByteBuffer(long address, int capacity) {
		churnViewsMinted++;
		if (linear(address)) {
			return LinearMemory.wrap((int) address, capacity);
		}
		Block block = resolveRange(address, capacity);
		int offset = (int) (address - block.base);
		return ((ByteBuffer) ByteBuffer.wrap(block.array, offset, capacity).slice()).order(ByteOrder.LITTLE_ENDIAN);
	}

	public static IntBuffer memIntBuffer(long address, int capacity) {
		IntBuffer view = memByteBuffer(address, capacity * 4).asIntBuffer();
		if (!linear(address)) {
			trackTyped(address, view, 4);
		}
		return view;
	}

	public static ByteBuffer memByteBuffer(IntBuffer buffer) {
		return memByteBuffer(memAddress(buffer), buffer.remaining() * 4);
	}

	public static LongBuffer memLongBuffer(long address, int capacity) {
		LongBuffer view = memByteBuffer(address, capacity * 8).asLongBuffer();
		if (!linear(address)) {
			trackTyped(address, view, 8);
		}
		return view;
	}

	public static PointerBuffer memPointerBuffer(long address, int capacity) {
		if (capacity < 0 || capacity > Integer.MAX_VALUE / Long.BYTES) throw new IllegalArgumentException("Invalid pointer capacity");
		return new PointerBuffer(memLongBuffer(address, capacity));
	}

	private static void trackTyped(long address, Buffer view, int elementSize) {
		typed.put(view, new long[] {address, elementSize});
		Block block = resolve(address);
		if (block.views == null) {
			block.views = new java.util.ArrayList<>(4);
		}
		block.views.add(view);
	}

	public static ByteBuffer sliceWatch;
	public static int sliceLo, sliceHi;

	public static ByteBuffer memSlice(ByteBuffer buffer, int offset, int length) {
		if (offset < 0 || length < 0 || offset > buffer.remaining() - length) {
			throw new IndexOutOfBoundsException("MemoryUtil: slice outside remaining buffer");
		}
		ByteBuffer duplicate = buffer.duplicate();
		int base = buffer.position();
		((Buffer) duplicate).position(base + offset);
		((Buffer) duplicate).limit(base + offset + length);
		if (buffer == sliceWatch) {
			sliceLo = Math.min(sliceLo, base + offset);
			sliceHi = Math.max(sliceHi, base + offset + length);
		}
		return ((ByteBuffer) duplicate.slice()).order(ByteOrder.LITTLE_ENDIAN);
	}

	public static void memCopy(long src, long dst, long bytes) {
		int length = (int) bytes;
		if (length <= 0) {
			return;
		}
		boolean ls = linear(src);
		boolean ld = linear(dst);
		if (ls && ld) {
			Address.moveMemoryBlock(at(src), at(dst), length);
		} else if (ls) {
			Block dstBlock = resolveRange(dst, length);
			copyOut(src, dstBlock.array, (int) (dst - dstBlock.base), length);
		} else if (ld) {
			Block srcBlock = resolveRange(src, length);
			copyIn(srcBlock.array, (int) (src - srcBlock.base), dst, length);
		} else {
			Block srcBlock = resolveRange(src, length);
			Block dstBlock = resolveRange(dst, length);
			System.arraycopy(srcBlock.array, (int) (src - srcBlock.base), dstBlock.array, (int) (dst - dstBlock.base), length);
		}
	}

	public static void memCopy(ByteBuffer src, ByteBuffer dst) {
		int length = src.remaining();
		if (length > dst.remaining()) {
			throw new java.nio.BufferOverflowException();
		}
		if (dst.isReadOnly()) {
			throw new java.nio.ReadOnlyBufferException();
		}
		int srcPosition = src.position();
		int dstPosition = dst.position();
		if (src.hasArray() && dst.hasArray()) {
			System.arraycopy(src.array(), src.arrayOffset() + srcPosition, dst.array(), dst.arrayOffset() + dstPosition, length);
			return;
		}
		if (LINEAR) {
			long s = src.hasArray() ? -1L : LinearMemory.address(src);
			long d = dst.hasArray() ? -1L : LinearMemory.address(dst);
			if (s >= 0 && d >= 0) {
				Address.moveMemoryBlock(at(s), at(d), length);
				return;
			}
			if (s >= 0 && dst.hasArray()) {
				copyOut(s, dst.array(), dst.arrayOffset() + dstPosition, length);
				return;
			}
			if (d >= 0 && src.hasArray()) {
				copyIn(src.array(), src.arrayOffset() + srcPosition, d, length);
				return;
			}
		}
		for (int i = 0; i < length; i++) {
			dst.put(dstPosition + i, src.get(srcPosition + i));
		}
	}

	public static void copyOut(long src, byte[] dst, int dstOff, int length) {
		Address a = at(src);
		for (int i = 0; i < length; i++) {
			dst[dstOff + i] = a.add(i).getByte();
		}
	}

	public static void copyIn(byte[] src, int srcOff, long dst, int length) {
		Address a = at(dst);
		for (int i = 0; i < length; i++) {
			a.add(i).putByte(src[srcOff + i]);
		}
	}

	public static void memSet(ByteBuffer buffer, int value) {
		int remaining = buffer.remaining();
		if (remaining != 0) memSet(memAddress(buffer), value, remaining);
	}

	public static void memSet(long ptr, int value, long bytes) {
		int length = (int) bytes;
		if (linear(ptr)) {
			Address.fill(at(ptr), (byte) value, length);
			return;
		}
		Block block = resolveRange(ptr, length);
		int offset = (int) (ptr - block.base);
		java.util.Arrays.fill(block.array, offset, offset + length, (byte) value);
	}

	public static byte memGetByte(long address) {
		if (linear(address)) {
			return at(address).getByte();
		}
		Block block = resolveRange(address, 1);
		return block.array[(int) (address - block.base)];
	}

	public static void memPutByte(long address, byte value) {
		if (linear(address)) {
			at(address).putByte(value);
			return;
		}
		Block block = resolveRange(address, 1);
		block.array[(int) (address - block.base)] = value;
	}

	public static void memPutShort(long address, short value) {
		if (linear(address)) {
			at(address).putShort(value);
			return;
		}
		Block block = resolveRange(address, 2);
		int off = (int) (address - block.base);
		byte[] a = block.array;
		a[off] = (byte) value;
		a[off + 1] = (byte) (value >> 8);
	}

	public static short memGetShort(long address) {
		if (linear(address)) {
			return at(address).getShort();
		}
		Block block = resolveRange(address, 2);
		int off = (int) (address - block.base);
		return (short) ((block.array[off] & 0xFF) | (block.array[off + 1] << 8));
	}

	public static int memGetInt(long address) {
		if (linear(address)) {
			return at(address).getInt();
		}
		Block block = resolveRange(address, 4);
		int off = (int) (address - block.base);
		byte[] a = block.array;
		return (a[off] & 0xFF) | ((a[off + 1] & 0xFF) << 8) | ((a[off + 2] & 0xFF) << 16) | ((a[off + 3] & 0xFF) << 24);
	}

	public static long memGetAddress(long address) {
		return memGetLong(address);
	}

	public static long memGetLong(long address) {
		if (linear(address)) {
			return at(address).getLong();
		}
		Block block = resolveRange(address, 8);
		int off = (int) (address - block.base);
		long value = 0;
		for (int i = 0; i < 8; i++) value |= (block.array[off + i] & 0xffL) << (i * 8);
		return value;
	}

	public static void memPutLong(long address, long value) {
		if (linear(address)) {
			at(address).putLong(value);
			return;
		}
		Block block = resolveRange(address, 8);
		int off = (int) (address - block.base);
		for (int i = 0; i < 8; i++) block.array[off + i] = (byte) (value >>> (i * 8));
	}

	public static void memPutInt(long address, int value) {
		if (linear(address)) {
			at(address).putInt(value);
			return;
		}
		Block block = resolveRange(address, 4);
		int off = (int) (address - block.base);
		byte[] a = block.array;
		a[off] = (byte) value;
		a[off + 1] = (byte) (value >> 8);
		a[off + 2] = (byte) (value >> 16);
		a[off + 3] = (byte) (value >> 24);
	}

	public static float memGetFloat(long address) {
		return Float.intBitsToFloat(memGetInt(address));
	}

	public static void memPutFloat(long address, float value) {
		if (linear(address)) {
			at(address).putFloat(value);
			return;
		}
		memPutInt(address, Float.floatToRawIntBits(value));
	}

	public static void memPutBlockVertex(long address, int x, int y, int z, int abgr, int u, int v, int light) {
		if (linear(address)) {
			Address a = at(address);
			a.putInt(x);
			a.add(4).putInt(y);
			a.add(8).putInt(z);
			a.add(12).putInt(abgr);
			a.add(16).putInt(u);
			a.add(20).putInt(v);
			a.add(24).putInt(light);
			return;
		}
		Block block = resolveRange(address, 28);
		int off = (int) (address - block.base);
		byte[] a = block.array;
		putIntLE(a, off, x);
		putIntLE(a, off + 4, y);
		putIntLE(a, off + 8, z);
		putIntLE(a, off + 12, abgr);
		putIntLE(a, off + 16, u);
		putIntLE(a, off + 20, v);
		putIntLE(a, off + 24, light);
	}

	public static void memPutEntityVertex(long address, int x, int y, int z, int abgr, int u, int v, int overlay, int light, int normal) {
		if (linear(address)) {
			Address a = at(address);
			a.putInt(x);
			a.add(4).putInt(y);
			a.add(8).putInt(z);
			a.add(12).putInt(abgr);
			a.add(16).putInt(u);
			a.add(20).putInt(v);
			a.add(24).putInt(overlay);
			a.add(28).putInt(light);
			a.add(32).putInt(normal);
			return;
		}
		Block block = resolveRange(address, 36);
		int off = (int) (address - block.base);
		byte[] a = block.array;
		putIntLE(a, off, x);
		putIntLE(a, off + 4, y);
		putIntLE(a, off + 8, z);
		putIntLE(a, off + 12, abgr);
		putIntLE(a, off + 16, u);
		putIntLE(a, off + 20, v);
		putIntLE(a, off + 24, overlay);
		putIntLE(a, off + 28, light);
		putIntLE(a, off + 32, normal);
	}

	private static void putIntLE(byte[] a, int off, int value) {
		a[off] = (byte) value;
		a[off + 1] = (byte) (value >> 8);
		a[off + 2] = (byte) (value >> 16);
		a[off + 3] = (byte) (value >> 24);
	}

	public static ByteBuffer memUTF8(CharSequence text) {
		return memUTF8(text, true);
	}

	public static ByteBuffer memUTF8(CharSequence text, boolean nullTerminated) {
		byte[] bytes;
		if (text instanceof String string) {
			StringBuilder repaired = null;
			for (int i = 0; i < string.length(); i++) {
				char c = string.charAt(i);
				if (Character.isHighSurrogate(c) && i + 1 < string.length()
						&& Character.isLowSurrogate(string.charAt(i + 1))) {
					i++;
				} else if (Character.isSurrogate(c)) {
					if (repaired == null) repaired = new StringBuilder(string);
					repaired.setCharAt(i, '?');
				}
			}
			bytes = (repaired == null ? string : repaired.toString()).getBytes(StandardCharsets.UTF_8);
		} else {
			int length = text.length();
			long size = length;
			for (int i = 0; i < length; i++) {
				char c = text.charAt(i);
				if (c >= 0x800) {
					size += 2;
					if (Character.isHighSurrogate(c)) i++;
				} else if (c >= 0x80) size++;
				if (size + (nullTerminated ? 1 : 0) > Integer.MAX_VALUE) throw new java.nio.BufferOverflowException();
			}
			bytes = new byte[(int) size];
			int output = 0;
			for (int i = 0; i < length; i++) {
				int c = text.charAt(i);
				if (c < 0x80) {
					bytes[output++] = (byte) c;
				} else if (c < 0x800) {
					bytes[output++] = (byte) (0xc0 | c >> 6);
					bytes[output++] = (byte) (0x80 | c & 0x3f);
				} else if (Character.isHighSurrogate((char) c)) {
					if (i + 1 == length) throw new StringIndexOutOfBoundsException(i + 1);
					c = ((c - 0xd800) << 10) + (text.charAt(++i) - 0xdc00) + 0x10000;
					bytes[output++] = (byte) (0xf0 | c >> 18);
					bytes[output++] = (byte) (0x80 | c >> 12 & 0x3f);
					bytes[output++] = (byte) (0x80 | c >> 6 & 0x3f);
					bytes[output++] = (byte) (0x80 | c & 0x3f);
				} else {
					bytes[output++] = (byte) (0xe0 | c >> 12);
					bytes[output++] = (byte) (0x80 | c >> 6 & 0x3f);
					bytes[output++] = (byte) (0x80 | c & 0x3f);
				}
			}
		}
		if (nullTerminated && bytes.length == Integer.MAX_VALUE) throw new java.nio.BufferOverflowException();
		ByteBuffer buffer = memAlloc(bytes.length + (nullTerminated ? 1 : 0));
		buffer.put(bytes);
		if (nullTerminated) buffer.put((byte) 0);
		return buffer.flip();
	}

	public static String memUTF8(long address) {
		if (linear(address)) {
			Address a = at(address);
			int n = 0;
			while (a.add(n).getByte() != 0) {
				n++;
			}
			byte[] bytes = new byte[n];
			copyOut(address, bytes, 0, n);
			return new String(bytes, StandardCharsets.UTF_8);
		}
		Block block = resolve(address);
		int start = (int) (address - block.base);
		int end = start;
		while (end < block.array.length && block.array[end] != 0) {
			end++;
		}
		return new String(block.array, start, end - start, StandardCharsets.UTF_8);
	}

	public static ByteBuffer memAlloc(int size) {
		return memByteBuffer(nmemAlloc(size), size);
	}

	public static ByteBuffer memCalloc(int num, int size) {
		return memByteBuffer(nmemCalloc(num, size), num * size);
	}

	public static IntBuffer memAllocInt(int capacity) {
		return memIntBuffer(nmemAlloc((long) capacity * 4), capacity);
	}

	public static ByteBuffer memRealloc(ByteBuffer buffer, int size) {
		if (buffer == null) {
			return memAlloc(size);
		}
		return memByteBuffer(nmemRealloc(memAddress(buffer, 0), size), size);
	}

	private static void free(long address) {
		if (linear(address)) {
			Integer size = linearSizes.remove(address);
			if (size != null) {
				linearLiveBytes -= size;
				Heap.release(at(address));
			}
			return;
		}
		int i = indexFloor(address);
		if (i < 0 || blockBase[i] != address) {
			return;
		}
		Block block = blockAt[i];
		System.arraycopy(blockBase, i + 1, blockBase, i, blockCount - i - 1);
		System.arraycopy(blockAt, i + 1, blockAt, i, blockCount - i - 1);
		blockCount--;
		blockAt[blockCount] = null;
		byArray.remove(block.array);
		if (block.views != null) {
			for (int v = 0; v < block.views.size(); v++) {
				typed.remove(block.views.get(v));
			}
			block.views = null;
		}
		if (lastResolved == block) {
			lastResolved = null;
		}
	}

	public static void memFree(ByteBuffer buffer) {
		memFree((Buffer) buffer);
	}

	public static void memFree(IntBuffer buffer) {
		memFree((Buffer) buffer);
	}

	public static void memFree(CustomBuffer buffer) {
		if (buffer instanceof NativeResource resource) {
			resource.free();
		}
	}

	public static void memFree(Buffer buffer) {
		if (buffer == null) {
			return;
		}
		if (buffer instanceof ByteBuffer && ((ByteBuffer) buffer).hasArray()) {
			Block block = byArray.get(((ByteBuffer) buffer).array());
			if (block != null) {
				free(block.base);
			}
			return;
		}
		if (LINEAR) {
			long a = -1L;
			if (buffer instanceof ByteBuffer) {
				a = LinearMemory.base((ByteBuffer) buffer);
			} else if (buffer instanceof IntBuffer) {
				a = LinearMemory.address((IntBuffer) buffer) - (long) buffer.position() * 4L;
			} else if (buffer instanceof LongBuffer) {
				a = LinearMemory.address((LongBuffer) buffer) - (long) buffer.position() * 8L;
			}
			if (a >= 0) {
				free(a);
				return;
			}
		}
		long[] rec = typed.remove(buffer);
		if (rec != null) {
			free(rec[0]);
		}
	}

	private static int allocationSize(long size) {
		if (size < 0 || size > Integer.MAX_VALUE
				|| LINEAR && size > Integer.MAX_VALUE - Address.sizeOf() + 1L) {
			throw new IllegalArgumentException("MemoryUtil: allocation size out of range: " + size);
		}
		return (int) Math.max(1L, size);
	}

	public static long nmemAlloc(long size) {
		int n = allocationSize(size);
		if (LINEAR) {
			Address a = Heap.alloc(n);
			if (a == null) {
				throw new OutOfMemoryError("MemoryUtil: linear memory exhausted allocating " + n + " bytes");
			}
			long ptr = a.toInt() & 0xFFFFFFFFL;
			linearSizes.put(ptr, n);
			linearLiveBytes += n;
			churnBlocksMinted++;
			return ptr;
		}
		return newBlock(n).base;
	}

	public static long nmemCalloc(long num, long size) {
		if (num < 0 || size < 0 || num != 0 && size > Integer.MAX_VALUE / num) {
			throw new IllegalArgumentException("MemoryUtil: allocation size out of range: " + num + " * " + size);
		}
		long bytes = num * size;
		long ptr = nmemAlloc(bytes);
		if (LINEAR) {
			Address.fillZero(at(ptr), (int) Math.max(1L, bytes));
		}
		return ptr;
	}

	public static long nmemAlignedAlloc(long alignment, long size) {
		if (alignment <= 0 || alignment > Integer.MAX_VALUE || (alignment & (alignment - 1)) != 0) {
			throw new IllegalArgumentException("MemoryUtil: invalid alignment: " + alignment);
		}
		long raw = nmemAlloc((long) allocationSize(size) + alignment - 1);
		long aligned = (raw + alignment - 1) & -alignment;
		alignedBases.put(aligned, raw);
		return aligned;
	}

	public static void nmemAlignedFree(long address) {
		Long raw = alignedBases.remove(address);
		if (raw != null) nmemFree(raw);
	}

	public static long nmemRealloc(long ptr, long size) {
		return ALLOCATOR.realloc(ptr, size);
	}

	public static void nmemFree(long address) {
		if (address != NULL) {
			free(address);
		}
	}

	public interface MemoryAllocator {
		long malloc(long size);

		long calloc(long num, long size);

		long realloc(long ptr, long size);

		void free(long ptr);
	}

	private static final MemoryAllocator ALLOCATOR = new MemoryAllocator() {
		@Override
		public long malloc(long size) {
			return nmemAlloc(size);
		}

		@Override
		public long calloc(long num, long size) {
			return nmemCalloc(num, size);
		}

		@Override
		public long realloc(long ptr, long size) {
			int n = allocationSize(size);
			if (alignedBases.containsKey(ptr)) {
				throw new IllegalArgumentException("MemoryUtil: aligned allocations require nmemAlignedFree");
			}
			if (ptr == NULL) {
				return nmemAlloc(size);
			}
			if (linear(ptr)) {
				Integer old = linearSizes.get(ptr);
				if (old == null) {
					throw new IllegalStateException("MemoryUtil shim: realloc of 0x" + Long.toHexString(ptr) + ", which is not a live allocation");
				}
				long fresh = nmemAlloc(size);
				Address.moveMemoryBlock(at(ptr), at(fresh), (int) Math.min(old, size));
				free(ptr);
				return fresh;
			}
			Block oldBlock = resolve(ptr);
			if (ptr != oldBlock.base) {
				throw new IllegalStateException("MemoryUtil shim: realloc address 0x" + Long.toHexString(ptr) + " is not an allocation base");
			}
			Block newBlock = newBlock(n);
			System.arraycopy(oldBlock.array, 0, newBlock.array, 0, (int) Math.min(oldBlock.size, size));
			free(oldBlock.base);
			return newBlock.base;
		}

		@Override
		public void free(long ptr) {
			nmemFree(ptr);
		}
	};

	public static MemoryAllocator getAllocator(boolean forceMalloc) {
		return ALLOCATOR;
	}
}
