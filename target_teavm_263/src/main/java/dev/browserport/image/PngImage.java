// minecraft-web by chiikabu (https://github.com/chiikabu)

package dev.browserport.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import net.minecraft.client.web.WebInflate;

public final class PngImage {
	private static final int MAX_DECODE_BYTES = 256 * 1024 * 1024;
	private static final int[][] PASSES = {
		{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4},
		{0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}, {0, 0, 1, 1}
	};
	public final byte[] pixels;
	public final int width;
	public final int height;
	public final int components;

	private PngImage(byte[] pixels, int width, int height, int components) {
		this.pixels = pixels;
		this.width = width;
		this.height = height;
		this.components = components;
	}

	public static PngImage decode(byte[] data, int requestedComponents) throws IOException {
		if (requestedComponents < 0 || requestedComponents > 4) throw new IllegalArgumentException("Invalid component count");
		if (data.length < 8 || readInt(data, 0) != 0x89504e47 || readInt(data, 4) != 0x0d0a1a0a) {
			throw invalid("bad signature");
		}
		int width = 0, height = 0, depth = 0, color = -1, channels = 0, interlace = 0;
		byte[] palette = null, transparency = null;
		boolean seenData = false, endedData = false, ended = false;
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		CRC32 crc = new CRC32();
		for (int offset = 8; offset < data.length;) {
			if (data.length - offset < 12) throw invalid("truncated chunk");
			int length = readInt(data, offset), type = readInt(data, offset + 4), body = offset + 8;
			if (length < 0 || length > data.length - body - 4) throw invalid("truncated chunk");
			crc.reset();
			crc.update(data, offset + 4, length + 4);
			if ((int) crc.getValue() != readInt(data, body + length)) throw invalid("chunk checksum");
			if (color < 0 && type != 0x49484452) throw invalid("IHDR must be first");
			if (seenData && type != 0x49444154) endedData = true;
			switch (type) {
				case 0x49484452:
					if (color >= 0 || length != 13) throw invalid("duplicate or invalid IHDR");
					width = readInt(data, body);
					height = readInt(data, body + 4);
					depth = data[body + 8] & 255;
					color = data[body + 9] & 255;
					interlace = data[body + 12] & 255;
					channels = switch (color) { case 0, 3 -> 1; case 2 -> 3; case 4 -> 2; case 6 -> 4; default -> 0; };
					if (channels == 0 || !(depth == 8 || depth == 16 && color != 3
							|| (depth == 1 || depth == 2 || depth == 4) && (color == 0 || color == 3))) {
						throw invalid("invalid color type or bit depth");
					}
					if (data[body + 10] != 0 || data[body + 11] != 0 || interlace > 1) throw invalid("unsupported encoding method");
					if (width <= 0 || height <= 0 || (long) width * height > MAX_DECODE_BYTES / 4) throw invalid("dimensions exceed decode limit");
					break;
				case 0x504c5445:
					if (palette != null || transparency != null || seenData || color == 0 || color == 4
							|| length == 0 || length % 3 != 0 || length > 768
							|| color == 3 && length / 3 > 1 << depth) throw invalid("invalid palette");
					palette = Arrays.copyOfRange(data, body, body + length);
					break;
				case 0x74524e53:
					if (transparency != null || seenData || !(color == 0 && length == 2 || color == 2 && length == 6
							|| color == 3 && palette != null && length > 0 && length <= palette.length / 3)) throw invalid("invalid transparency");
					transparency = Arrays.copyOfRange(data, body, body + length);
					break;
				case 0x49444154:
					if (endedData || color == 3 && palette == null) throw invalid("invalid IDAT order");
					if (length > MAX_DECODE_BYTES - compressed.size()) throw invalid("compressed data exceeds decode limit");
					seenData = true;
					compressed.write(data, body, length);
					break;
				case 0x49454e44:
					if (length != 0 || !seenData) throw invalid("invalid IEND");
					ended = true;
					break;
				default:
					if ((type & 0x20000000) == 0) throw invalid("unknown critical chunk");
			}
			if (ended) break;
			offset = body + length + 4;
		}
		if (!ended) throw invalid("missing IEND");
		int components = requestedComponents != 0 ? requestedComponents : color == 3 ? (transparency == null ? 3 : 4)
			: channels + (transparency != null ? 1 : 0);
		long expected = 0;
		int firstPass = interlace == 0 ? 7 : 0, lastPass = interlace == 0 ? 8 : 7;
		for (int pass = firstPass; pass < lastPass; pass++) {
			int[] p = PASSES[pass];
			int w = passSize(width, p[0], p[2]), h = passSize(height, p[1], p[3]);
			if (w != 0 && h != 0) expected += (((long) w * channels * depth + 7) / 8 + 1) * h;
		}
		long pixelBytes = (long) width * height * components;
		if (expected + pixelBytes > MAX_DECODE_BYTES) throw invalid("image exceeds decode limit");
		byte[] raw;
		try {
			raw = WebInflate.zlib(compressed.toByteArray(), (int) expected);
		} catch (RuntimeException failure) {
			throw new IOException("Could not load image: corrupt PNG data", failure);
		}
		if (raw.length != expected) throw invalid("incorrect scanline data length");
		byte[] pixels = new byte[(int) pixelBytes];
		int cursor = 0, bpp = Math.max(1, channels * depth / 8), shift = depth == 16 ? 8 : 0;
		int maximum = depth == 16 ? 65535 : 255;
		for (int pass = firstPass; pass < lastPass; pass++) {
			int[] p = PASSES[pass];
			int w = passSize(width, p[0], p[2]), h = passSize(height, p[1], p[3]);
			if (w == 0 || h == 0) continue;
			int stride = (w * channels * depth + 7) / 8;
			int previous = -1;
			for (int y = 0; y < h; y++) {
				int filter = raw[cursor++] & 255, line = cursor;
				unfilter(raw, line, previous, stride, bpp, filter);
				for (int x = 0; x < w; x++) {
					int index = x * channels;
					int r = sample(raw, line, index, depth), g = r, b = r, a = maximum;
					if (color == 3) {
						if (r >= palette.length / 3) throw invalid("palette index out of range");
						a = transparency != null && r < transparency.length ? transparency[r] & 255 : 255;
						g = palette[r * 3 + 1] & 255;
						b = palette[r * 3 + 2] & 255;
						r = palette[r * 3] & 255;
					} else if (color == 0 || color == 4) {
						if (color == 4) a = sample(raw, line, index + 1, depth);
						else if (transparency != null && r == readShort(transparency, 0)) a = 0;
						if (depth < 8) r *= 255 / ((1 << depth) - 1);
						g = b = r;
					} else {
						g = sample(raw, line, index + 1, depth);
						b = sample(raw, line, index + 2, depth);
						if (color == 6) a = sample(raw, line, index + 3, depth);
						else if (transparency != null && r == readShort(transparency, 0)
								&& g == readShort(transparency, 2) && b == readShort(transparency, 4)) a = 0;
					}
					int out = ((p[1] + y * p[3]) * width + p[0] + x * p[2]) * components;
					if (components < 3) {
						pixels[out++] = (byte) ((r * 77 + g * 150 + b * 29 >> 8) >> shift);
					} else {
						pixels[out++] = (byte) (r >> shift);
						pixels[out++] = (byte) (g >> shift);
						pixels[out++] = (byte) (b >> shift);
					}
					if (components == 2 || components == 4) pixels[out] = (byte) (a >> shift);
				}
				previous = line;
				cursor += stride;
			}
		}
		return new PngImage(pixels, width, height, components);
	}

	private static void unfilter(byte[] raw, int line, int previous, int count, int bpp, int filter) throws IOException {
		if (filter > 4) throw invalid("unknown filter");
		if (filter == 0) return;
		for (int i = 0; i < count; i++) {
			int a = i >= bpp ? raw[line + i - bpp] & 255 : 0;
			int b = previous >= 0 ? raw[previous + i] & 255 : 0;
			int predictor;
			switch (filter) {
				case 1: predictor = a; break;
				case 2: predictor = b; break;
				case 3: predictor = (a + b) >> 1; break;
				default:
					int c = previous >= 0 && i >= bpp ? raw[previous + i - bpp] & 255 : 0;
					int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
					predictor = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
			}
			raw[line + i] += (byte) predictor;
		}
	}

	private static int passSize(int size, int start, int step) {
		return size <= start ? 0 : (size - start - 1) / step + 1;
	}

	private static int sample(byte[] raw, int line, int index, int depth) {
		if (depth == 16) return readShort(raw, line + index * 2);
		if (depth == 8) return raw[line + index] & 255;
		int bit = index * depth;
		return (raw[line + (bit >> 3)] >> (8 - depth - (bit & 7))) & ((1 << depth) - 1);
	}

	private static int readShort(byte[] data, int offset) {
		return (data[offset] & 255) << 8 | data[offset + 1] & 255;
	}

	private static int readInt(byte[] data, int offset) {
		return readShort(data, offset) << 16 | readShort(data, offset + 2);
	}

	private static IOException invalid(String reason) {
		return new IOException("Could not load image: PNG " + reason);
	}
}
