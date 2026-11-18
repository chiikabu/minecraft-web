// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class TMessageDigest {

	private final String algorithm;
	private final Engine engine;

	private TMessageDigest(String algorithm, Engine engine) {
		this.algorithm = algorithm;
		this.engine = engine;
	}

	public static TMessageDigest getInstance(String algorithm) throws NoSuchAlgorithmException {
		if (algorithm == null) {
			throw new NoSuchAlgorithmException("null");
		}
		switch (algorithm.toUpperCase(Locale.ROOT)) {
			case "MD5":
				return new TMessageDigest("MD5", new MD5());
			case "SHA-1":
			case "SHA1":
				return new TMessageDigest("SHA-1", new SHA1());
			case "SHA-256":
			case "SHA256":
				return new TMessageDigest("SHA-256", new SHA256());
			default:
				throw new NoSuchAlgorithmException(algorithm + " MessageDigest not available");
		}
	}

	public static TMessageDigest getInstance(String algorithm, String provider) throws NoSuchAlgorithmException {
		return getInstance(algorithm);
	}

	public void update(byte input) {
		engine.update(input);
	}

	public void update(byte[] input) {
		engine.update(input, 0, input.length);
	}

	public void update(byte[] input, int offset, int len) {
		if (offset < 0 || len < 0 || offset + len > input.length) {
			throw new ArrayIndexOutOfBoundsException();
		}
		engine.update(input, offset, len);
	}

	public void update(java.nio.ByteBuffer input) {
		int len = input.remaining();
		if (len <= 0) {
			return;
		}
		byte[] chunk = new byte[len];
		input.get(chunk);
		engine.update(chunk, 0, len);
	}

	public static boolean isEqual(byte[] a, byte[] b) {
		if (a == b) {
			return true;
		}
		if (a == null || b == null) {
			return false;
		}
		int lenA = a.length;
		int lenB = b.length;
		if (lenB == 0) {
			return lenA == 0;
		}
		int result = lenA - lenB;
		for (int i = 0; i < lenA; i++) {
			result |= a[i] ^ b[i % lenB];
		}
		return result == 0;
	}

	public byte[] digest() {
		byte[] out = engine.digest();
		engine.reset();
		return out;
	}

	public byte[] digest(byte[] input) {
		update(input);
		return digest();
	}

	public void reset() {
		engine.reset();
	}

	public String getAlgorithm() {
		return algorithm;
	}

	public int getDigestLength() {
		return engine.digestLength;
	}

	@Override
	public String toString() {
		return algorithm + " Message Digest";
	}

	private abstract static class Engine {
		final int digestLength;
		private final byte[] wordBuf = new byte[4];
		private int wordBufOff;
		private long byteCount;

		Engine(int digestLength) {
			this.digestLength = digestLength;
		}

		void update(byte in) {
			wordBuf[wordBufOff++] = in;
			if (wordBufOff == 4) {
				processWord(wordBuf, 0);
				wordBufOff = 0;
			}
			byteCount++;
		}

		void update(byte[] in, int off, int len) {
			while (wordBufOff != 0 && len > 0) {
				update(in[off]);
				off++;
				len--;
			}
			while (len > 4) {
				processWord(in, off);
				off += 4;
				len -= 4;
				byteCount += 4;
			}
			while (len > 0) {
				update(in[off]);
				off++;
				len--;
			}
		}

		byte[] digest() {
			long bitLength = byteCount << 3;
			update((byte) 0x80);
			while (wordBufOff != 0) {
				update((byte) 0);
			}
			processLength(bitLength);
			processBlock();
			byte[] out = new byte[digestLength];
			writeState(out);
			return out;
		}

		void reset() {
			byteCount = 0;
			wordBufOff = 0;
			wordBuf[0] = wordBuf[1] = wordBuf[2] = wordBuf[3] = 0;
		}

		abstract void processWord(byte[] in, int inOff);

		abstract void processLength(long bitLength);

		abstract void processBlock();

		abstract void writeState(byte[] out);
	}

	private static final class MD5 extends Engine {
		private int h1 = 0x67452301, h2 = 0xefcdab89, h3 = 0x98badcfe, h4 = 0x10325476;
		private final int[] x = new int[16];
		private int xOff;

		MD5() {
			super(16);
		}

		@Override
		void processWord(byte[] in, int inOff) {
			x[xOff++] = (in[inOff] & 0xff) | ((in[inOff + 1] & 0xff) << 8) | ((in[inOff + 2] & 0xff) << 16)
					| ((in[inOff + 3] & 0xff) << 24);
			if (xOff == 16) {
				processBlock();
			}
		}

		@Override
		void processLength(long bitLength) {
			if (xOff > 14) {
				processBlock();
			}
			x[14] = (int) bitLength;
			x[15] = (int) (bitLength >>> 32);
		}

		@Override
		void writeState(byte[] out) {
			unpack(h1, out, 0);
			unpack(h2, out, 4);
			unpack(h3, out, 8);
			unpack(h4, out, 12);
		}

		private static void unpack(int word, byte[] out, int off) {
			out[off] = (byte) word;
			out[off + 1] = (byte) (word >>> 8);
			out[off + 2] = (byte) (word >>> 16);
			out[off + 3] = (byte) (word >>> 24);
		}

		private static int rotl(int v, int n) {
			return (v << n) | (v >>> (32 - n));
		}

		private static int f(int u, int v, int w) {
			return (u & v) | (~u & w);
		}

		private static int g(int u, int v, int w) {
			return (u & w) | (v & ~w);
		}

		private static int h(int u, int v, int w) {
			return u ^ v ^ w;
		}

		private static int k(int u, int v, int w) {
			return v ^ (u | ~w);
		}

		@Override
		void processBlock() {
			int a = h1, b = h2, c = h3, d = h4;

			a = rotl(a + f(b, c, d) + x[0] + 0xd76aa478, 7) + b;
			d = rotl(d + f(a, b, c) + x[1] + 0xe8c7b756, 12) + a;
			c = rotl(c + f(d, a, b) + x[2] + 0x242070db, 17) + d;
			b = rotl(b + f(c, d, a) + x[3] + 0xc1bdceee, 22) + c;
			a = rotl(a + f(b, c, d) + x[4] + 0xf57c0faf, 7) + b;
			d = rotl(d + f(a, b, c) + x[5] + 0x4787c62a, 12) + a;
			c = rotl(c + f(d, a, b) + x[6] + 0xa8304613, 17) + d;
			b = rotl(b + f(c, d, a) + x[7] + 0xfd469501, 22) + c;
			a = rotl(a + f(b, c, d) + x[8] + 0x698098d8, 7) + b;
			d = rotl(d + f(a, b, c) + x[9] + 0x8b44f7af, 12) + a;
			c = rotl(c + f(d, a, b) + x[10] + 0xffff5bb1, 17) + d;
			b = rotl(b + f(c, d, a) + x[11] + 0x895cd7be, 22) + c;
			a = rotl(a + f(b, c, d) + x[12] + 0x6b901122, 7) + b;
			d = rotl(d + f(a, b, c) + x[13] + 0xfd987193, 12) + a;
			c = rotl(c + f(d, a, b) + x[14] + 0xa679438e, 17) + d;
			b = rotl(b + f(c, d, a) + x[15] + 0x49b40821, 22) + c;

			a = rotl(a + g(b, c, d) + x[1] + 0xf61e2562, 5) + b;
			d = rotl(d + g(a, b, c) + x[6] + 0xc040b340, 9) + a;
			c = rotl(c + g(d, a, b) + x[11] + 0x265e5a51, 14) + d;
			b = rotl(b + g(c, d, a) + x[0] + 0xe9b6c7aa, 20) + c;
			a = rotl(a + g(b, c, d) + x[5] + 0xd62f105d, 5) + b;
			d = rotl(d + g(a, b, c) + x[10] + 0x02441453, 9) + a;
			c = rotl(c + g(d, a, b) + x[15] + 0xd8a1e681, 14) + d;
			b = rotl(b + g(c, d, a) + x[4] + 0xe7d3fbc8, 20) + c;
			a = rotl(a + g(b, c, d) + x[9] + 0x21e1cde6, 5) + b;
			d = rotl(d + g(a, b, c) + x[14] + 0xc33707d6, 9) + a;
			c = rotl(c + g(d, a, b) + x[3] + 0xf4d50d87, 14) + d;
			b = rotl(b + g(c, d, a) + x[8] + 0x455a14ed, 20) + c;
			a = rotl(a + g(b, c, d) + x[13] + 0xa9e3e905, 5) + b;
			d = rotl(d + g(a, b, c) + x[2] + 0xfcefa3f8, 9) + a;
			c = rotl(c + g(d, a, b) + x[7] + 0x676f02d9, 14) + d;
			b = rotl(b + g(c, d, a) + x[12] + 0x8d2a4c8a, 20) + c;

			a = rotl(a + h(b, c, d) + x[5] + 0xfffa3942, 4) + b;
			d = rotl(d + h(a, b, c) + x[8] + 0x8771f681, 11) + a;
			c = rotl(c + h(d, a, b) + x[11] + 0x6d9d6122, 16) + d;
			b = rotl(b + h(c, d, a) + x[14] + 0xfde5380c, 23) + c;
			a = rotl(a + h(b, c, d) + x[1] + 0xa4beea44, 4) + b;
			d = rotl(d + h(a, b, c) + x[4] + 0x4bdecfa9, 11) + a;
			c = rotl(c + h(d, a, b) + x[7] + 0xf6bb4b60, 16) + d;
			b = rotl(b + h(c, d, a) + x[10] + 0xbebfbc70, 23) + c;
			a = rotl(a + h(b, c, d) + x[13] + 0x289b7ec6, 4) + b;
			d = rotl(d + h(a, b, c) + x[0] + 0xeaa127fa, 11) + a;
			c = rotl(c + h(d, a, b) + x[3] + 0xd4ef3085, 16) + d;
			b = rotl(b + h(c, d, a) + x[6] + 0x04881d05, 23) + c;
			a = rotl(a + h(b, c, d) + x[9] + 0xd9d4d039, 4) + b;
			d = rotl(d + h(a, b, c) + x[12] + 0xe6db99e5, 11) + a;
			c = rotl(c + h(d, a, b) + x[15] + 0x1fa27cf8, 16) + d;
			b = rotl(b + h(c, d, a) + x[2] + 0xc4ac5665, 23) + c;

			a = rotl(a + k(b, c, d) + x[0] + 0xf4292244, 6) + b;
			d = rotl(d + k(a, b, c) + x[7] + 0x432aff97, 10) + a;
			c = rotl(c + k(d, a, b) + x[14] + 0xab9423a7, 15) + d;
			b = rotl(b + k(c, d, a) + x[5] + 0xfc93a039, 21) + c;
			a = rotl(a + k(b, c, d) + x[12] + 0x655b59c3, 6) + b;
			d = rotl(d + k(a, b, c) + x[3] + 0x8f0ccc92, 10) + a;
			c = rotl(c + k(d, a, b) + x[10] + 0xffeff47d, 15) + d;
			b = rotl(b + k(c, d, a) + x[1] + 0x85845dd1, 21) + c;
			a = rotl(a + k(b, c, d) + x[8] + 0x6fa87e4f, 6) + b;
			d = rotl(d + k(a, b, c) + x[15] + 0xfe2ce6e0, 10) + a;
			c = rotl(c + k(d, a, b) + x[6] + 0xa3014314, 15) + d;
			b = rotl(b + k(c, d, a) + x[13] + 0x4e0811a1, 21) + c;
			a = rotl(a + k(b, c, d) + x[4] + 0xf7537e82, 6) + b;
			d = rotl(d + k(a, b, c) + x[11] + 0xbd3af235, 10) + a;
			c = rotl(c + k(d, a, b) + x[2] + 0x2ad7d2bb, 15) + d;
			b = rotl(b + k(c, d, a) + x[9] + 0xeb86d391, 21) + c;

			h1 += a;
			h2 += b;
			h3 += c;
			h4 += d;
			xOff = 0;
			for (int i = 0; i != x.length; i++) {
				x[i] = 0;
			}
		}

		@Override
		void reset() {
			super.reset();
			h1 = 0x67452301;
			h2 = 0xefcdab89;
			h3 = 0x98badcfe;
			h4 = 0x10325476;
			xOff = 0;
			for (int i = 0; i != x.length; i++) {
				x[i] = 0;
			}
		}
	}

	private static final class SHA1 extends Engine {
		private int h1 = 0x67452301, h2 = 0xefcdab89, h3 = 0x98badcfe, h4 = 0x10325476, h5 = 0xc3d2e1f0;
		private final int[] x = new int[80];
		private int xOff;

		SHA1() {
			super(20);
		}

		@Override
		void processWord(byte[] in, int inOff) {
			x[xOff++] = ((in[inOff] & 0xff) << 24) | ((in[inOff + 1] & 0xff) << 16) | ((in[inOff + 2] & 0xff) << 8)
					| (in[inOff + 3] & 0xff);
			if (xOff == 16) {
				processBlock();
			}
		}

		@Override
		void processLength(long bitLength) {
			if (xOff > 14) {
				processBlock();
			}
			x[14] = (int) (bitLength >>> 32);
			x[15] = (int) bitLength;
		}

		@Override
		void writeState(byte[] out) {
			pack(h1, out, 0);
			pack(h2, out, 4);
			pack(h3, out, 8);
			pack(h4, out, 12);
			pack(h5, out, 16);
		}

		private static void pack(int word, byte[] out, int off) {
			out[off] = (byte) (word >>> 24);
			out[off + 1] = (byte) (word >>> 16);
			out[off + 2] = (byte) (word >>> 8);
			out[off + 3] = (byte) word;
		}

		private static int rotl(int v, int n) {
			return (v << n) | (v >>> (32 - n));
		}

		@Override
		void processBlock() {
			for (int i = 16; i <= 79; i++) {
				x[i] = rotl(x[i - 3] ^ x[i - 8] ^ x[i - 14] ^ x[i - 16], 1);
			}

			int a = h1, b = h2, c = h3, d = h4, e = h5;

			for (int j = 0; j <= 19; j++) {
				int t = rotl(a, 5) + ((b & c) | (~b & d)) + e + x[j] + 0x5a827999;
				e = d;
				d = c;
				c = rotl(b, 30);
				b = a;
				a = t;
			}
			for (int j = 20; j <= 39; j++) {
				int t = rotl(a, 5) + (b ^ c ^ d) + e + x[j] + 0x6ed9eba1;
				e = d;
				d = c;
				c = rotl(b, 30);
				b = a;
				a = t;
			}
			for (int j = 40; j <= 59; j++) {
				int t = rotl(a, 5) + ((b & c) | (b & d) | (c & d)) + e + x[j] + 0x8f1bbcdc;
				e = d;
				d = c;
				c = rotl(b, 30);
				b = a;
				a = t;
			}
			for (int j = 60; j <= 79; j++) {
				int t = rotl(a, 5) + (b ^ c ^ d) + e + x[j] + 0xca62c1d6;
				e = d;
				d = c;
				c = rotl(b, 30);
				b = a;
				a = t;
			}

			h1 += a;
			h2 += b;
			h3 += c;
			h4 += d;
			h5 += e;
			xOff = 0;
			for (int i = 0; i != x.length; i++) {
				x[i] = 0;
			}
		}

		@Override
		void reset() {
			super.reset();
			h1 = 0x67452301;
			h2 = 0xefcdab89;
			h3 = 0x98badcfe;
			h4 = 0x10325476;
			h5 = 0xc3d2e1f0;
			xOff = 0;
			for (int i = 0; i != x.length; i++) {
				x[i] = 0;
			}
		}
	}

	private static final class SHA256 extends Engine {
		private static final int[] K = { 0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
				0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
				0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa,
				0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
				0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb,
				0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624,
				0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
				0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb,
				0xbef9a3f7, 0xc67178f2 };

		private int h1 = 0x6a09e667, h2 = 0xbb67ae85, h3 = 0x3c6ef372, h4 = 0xa54ff53a;
		private int h5 = 0x510e527f, h6 = 0x9b05688c, h7 = 0x1f83d9ab, h8 = 0x5be0cd19;
		private final int[] x = new int[64];
		private int xOff;

		SHA256() {
			super(32);
		}

		@Override
		void processWord(byte[] in, int inOff) {
			x[xOff] = ((in[inOff] & 0xff) << 24) | ((in[inOff + 1] & 0xff) << 16) | ((in[inOff + 2] & 0xff) << 8)
					| (in[inOff + 3] & 0xff);
			if (++xOff == 16) {
				processBlock();
			}
		}

		@Override
		void processLength(long bitLength) {
			if (xOff > 14) {
				processBlock();
			}
			x[14] = (int) (bitLength >>> 32);
			x[15] = (int) bitLength;
		}

		@Override
		void writeState(byte[] out) {
			pack(h1, out, 0);
			pack(h2, out, 4);
			pack(h3, out, 8);
			pack(h4, out, 12);
			pack(h5, out, 16);
			pack(h6, out, 20);
			pack(h7, out, 24);
			pack(h8, out, 28);
		}

		private static void pack(int word, byte[] out, int off) {
			out[off] = (byte) (word >>> 24);
			out[off + 1] = (byte) (word >>> 16);
			out[off + 2] = (byte) (word >>> 8);
			out[off + 3] = (byte) word;
		}

		private static int ch(int x, int y, int z) {
			return (x & y) ^ (~x & z);
		}

		private static int maj(int x, int y, int z) {
			return (x & y) | (z & (x ^ y));
		}

		private static int bsig0(int x) {
			return ((x >>> 2) | (x << 30)) ^ ((x >>> 13) | (x << 19)) ^ ((x >>> 22) | (x << 10));
		}

		private static int bsig1(int x) {
			return ((x >>> 6) | (x << 26)) ^ ((x >>> 11) | (x << 21)) ^ ((x >>> 25) | (x << 7));
		}

		private static int ssig0(int x) {
			return ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
		}

		private static int ssig1(int x) {
			return ((x >>> 17) | (x << 15)) ^ ((x >>> 19) | (x << 13)) ^ (x >>> 10);
		}

		@Override
		void processBlock() {
			for (int t = 16; t <= 63; t++) {
				x[t] = ssig1(x[t - 2]) + x[t - 7] + ssig0(x[t - 15]) + x[t - 16];
			}

			int a = h1, b = h2, c = h3, d = h4, e = h5, f = h6, g = h7, h = h8;

			for (int t = 0; t < 64; t++) {
				int t1 = h + bsig1(e) + ch(e, f, g) + K[t] + x[t];
				int t2 = bsig0(a) + maj(a, b, c);
				h = g;
				g = f;
				f = e;
				e = d + t1;
				d = c;
				c = b;
				b = a;
				a = t1 + t2;
			}

			h1 += a;
			h2 += b;
			h3 += c;
			h4 += d;
			h5 += e;
			h6 += f;
			h7 += g;
			h8 += h;
			xOff = 0;
			for (int i = 0; i != x.length; i++) {
				x[i] = 0;
			}
		}

		@Override
		void reset() {
			super.reset();
			h1 = 0x6a09e667;
			h2 = 0xbb67ae85;
			h3 = 0x3c6ef372;
			h4 = 0xa54ff53a;
			h5 = 0x510e527f;
			h6 = 0x9b05688c;
			h7 = 0x1f83d9ab;
			h8 = 0x5be0cd19;
			xOff = 0;
			for (int i = 0; i != x.length; i++) {
				x[i] = 0;
			}
		}
	}

	public static void main(String[] args) throws Exception {
		check("MD5", "", "d41d8cd98f00b204e9800998ecf8427e");
		check("SHA-1", "abc", "a9993e364706816aba3e25717850c26c9cd0d89d");
		check("SHA-256", "abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assert getInstance("md5").getDigestLength() == 16;
		assert getInstance("sha1").getDigestLength() == 20;
		assert getInstance("sha256").getDigestLength() == 32;
		TMessageDigest split = getInstance("SHA-256");
		split.update("a".getBytes("UTF-8"));
		split.update("bc".getBytes("UTF-8"));
		assert toHex(split.digest()).equals(toHex(getInstance("SHA-256").digest("abc".getBytes("UTF-8"))));
		try {
			getInstance("NOPE");
			throw new AssertionError("expected NoSuchAlgorithmException");
		} catch (NoSuchAlgorithmException expected) {
		}
		System.out.println("OK");
	}

	private static void check(String algorithm, String input, String expectedHex) throws Exception {
		byte[] digest = getInstance(algorithm).digest(input.getBytes("UTF-8"));
		String actual = toHex(digest);
		if (!actual.equals(expectedHex)) {
			throw new AssertionError(algorithm + ": expected " + expectedHex + " got " + actual);
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xf, 16));
			sb.append(Character.forDigit(b & 0xf, 16));
		}
		return sb.toString();
	}
}
