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
		int firstIdatOffset = -1, firstIdatLen = 0, totalCompressed = 0;
		ByteArrayOutputStream compressed = null;
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
					if (length > MAX_DECODE_BYTES - totalCompressed) throw invalid("compressed data exceeds decode limit");
					seenData = true;
					totalCompressed += length;
					if (compressed == null) {
						if (firstIdatOffset < 0) {
							firstIdatOffset = body;
							firstIdatLen = length;
						} else {
							compressed = new ByteArrayOutputStream(totalCompressed);
							compressed.write(data, firstIdatOffset, firstIdatLen);
							compressed.write(data, body, length);
						}
					} else {
						compressed.write(data, body, length);
					}
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
			if (compressed == null) {
				raw = WebInflate.zlib(data, firstIdatOffset, firstIdatLen, (int) expected);
			} else {
				raw = WebInflate.zlib(compressed.toByteArray(), (int) expected);
			}
		} catch (RuntimeException failure) {
			throw new IOException("Could not load image: corrupt PNG data", failure);
		}
		if (raw.length != expected) throw invalid("incorrect scanline data length");
		byte[] pixels = new byte[(int) pixelBytes];
		if (interlace == 0 && depth == 8) {
			if (transparency == null && color == 6 && components == 4) {
				int stride = width * 4;
				int cursor = 0, previous = -1;
				for (int y = 0; y < height; y++) {
					int filter = raw[cursor++] & 255, line = cursor;
					unfilter(raw, line, previous, stride, 4, filter);
					System.arraycopy(raw, line, pixels, y * stride, stride);
					previous = line;
					cursor += stride;
				}
				return new PngImage(pixels, width, height, components);
			}
			if (transparency == null && color == 2 && components == 4) {
				int stride = width * 3, outStride = width * 4;
				int cursor = 0, previous = -1;
				for (int y = 0; y < height; y++) {
					int filter = raw[cursor++] & 255, line = cursor;
					unfilter(raw, line, previous, stride, 3, filter);
					int src = line, end = line + stride, dst = y * outStride;
					while (src < end) {
						pixels[dst] = raw[src];
						pixels[dst + 1] = raw[src + 1];
						pixels[dst + 2] = raw[src + 2];
						pixels[dst + 3] = (byte) 255;
						src += 3;
						dst += 4;
					}
					previous = line;
					cursor += stride;
				}
				return new PngImage(pixels, width, height, components);
			}
			if (transparency == null && color == 2 && components == 3) {
				int stride = width * 3;
				int cursor = 0, previous = -1;
				for (int y = 0; y < height; y++) {
					int filter = raw[cursor++] & 255, line = cursor;
					unfilter(raw, line, previous, stride, 3, filter);
					System.arraycopy(raw, line, pixels, y * stride, stride);
					previous = line;
					cursor += stride;
				}
				return new PngImage(pixels, width, height, components);
			}
			if (color == 3 && components == 4) {
				int paletteCount = palette.length / 3;
				byte[] rgbaPal = new byte[paletteCount * 4];
				int trnsLen = transparency != null ? transparency.length : 0;
				for (int i = 0; i < paletteCount; i++) {
					int p3 = i * 3, p4 = i << 2;
					rgbaPal[p4] = palette[p3];
					rgbaPal[p4 + 1] = palette[p3 + 1];
					rgbaPal[p4 + 2] = palette[p3 + 2];
					rgbaPal[p4 + 3] = i < trnsLen ? transparency[i] : (byte) 255;
				}
				int stride = width, outStride = width * 4;
				int cursor = 0, previous = -1;
				for (int y = 0; y < height; y++) {
					int filter = raw[cursor++] & 255, line = cursor;
					unfilter(raw, line, previous, stride, 1, filter);
					int dst = y * outStride;
					for (int x = 0; x < width; x++) {
						int idx = raw[line + x] & 255;
						if (idx >= paletteCount) throw invalid("palette index out of range");
						int p4 = idx << 2;
						pixels[dst] = rgbaPal[p4];
						pixels[dst + 1] = rgbaPal[p4 + 1];
						pixels[dst + 2] = rgbaPal[p4 + 2];
						pixels[dst + 3] = rgbaPal[p4 + 3];
						dst += 4;
					}
					previous = line;
					cursor += stride;
				}
				return new PngImage(pixels, width, height, components);
			}
			if (transparency == null && color == 4 && components == 4) {
				int stride = width * 2, outStride = width * 4;
				int cursor = 0, previous = -1;
				for (int y = 0; y < height; y++) {
					int filter = raw[cursor++] & 255, line = cursor;
					unfilter(raw, line, previous, stride, 2, filter);
					int src = line, end = line + stride, dst = y * outStride;
					while (src < end) {
						byte g = raw[src];
						pixels[dst] = g;
						pixels[dst + 1] = g;
						pixels[dst + 2] = g;
						pixels[dst + 3] = raw[src + 1];
						src += 2;
						dst += 4;
					}
					previous = line;
					cursor += stride;
				}
				return new PngImage(pixels, width, height, components);
			}
		}
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
		switch (filter) {
			case 0:
				return;
			case 1: {
				int end = line + count;
				for (int cur = line + bpp; cur < end; cur++) {
					raw[cur] += raw[cur - bpp];
				}
				return;
			}
			case 2: {
				if (previous < 0) return;
				int end = line + count;
				for (int cur = line, prev = previous; cur < end; cur++, prev++) {
					raw[cur] += raw[prev];
				}
				return;
			}
			case 3: {
				int limit = Math.min(bpp, count);
				int end = line + count;
				if (previous < 0) {
					for (int cur = line + bpp; cur < end; cur++) {
						raw[cur] += (byte) ((raw[cur - bpp] & 255) >> 1);
					}
				} else {
					for (int i = 0; i < limit; i++) {
						raw[line + i] += (byte) ((raw[previous + i] & 255) >> 1);
					}
					for (int cur = line + bpp, prev = previous + bpp; cur < end; cur++, prev++) {
						raw[cur] += (byte) (((raw[cur - bpp] & 255) + (raw[prev] & 255)) >> 1);
					}
				}
				return;
			}
			case 4: {
				int limit = Math.min(bpp, count);
				int end = line + count;
				if (previous < 0) {
					for (int cur = line + bpp; cur < end; cur++) {
						raw[cur] += raw[cur - bpp];
					}
				} else {
					for (int i = 0; i < limit; i++) {
						raw[line + i] += raw[previous + i];
					}
					for (int cur = line + bpp, prev = previous + bpp; cur < end; cur++, prev++) {
						int a = raw[cur - bpp] & 255;
						int b = raw[prev] & 255;
						int c = raw[prev - bpp] & 255;
						int pa = b - c;
						int pb = a - c;
						int pc = pa + pb;
						if (pa < 0) pa = -pa;
						if (pb < 0) pb = -pb;
						if (pc < 0) pc = -pc;
						raw[cur] += (byte) ((pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c));
					}
				}
				return;
			}
			default:
				throw invalid("unknown filter");
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
