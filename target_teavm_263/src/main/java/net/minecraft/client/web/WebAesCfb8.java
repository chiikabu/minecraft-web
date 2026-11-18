// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

public final class WebAesCfb8 {
	private static final int[] T0 = new int[256];
	private static final int[] T1 = new int[256];
	private static final int[] T2 = new int[256];
	private static final int[] T3 = new int[256];
	private static final int[] SBOX = new int[256];

	static {
		int[] pow = new int[256];
		int[] log = new int[256];
		int x = 1;
		for (int i = 0; i < 256; i++) {
			pow[i] = x;
			log[x] = i;
			x ^= x << 1 ^ ((x & 0x80) != 0 ? 0x11b : 0);
		}

		SBOX[0] = 0x63;
		for (int i = 1; i < 256; i++) {
			int inv = pow[255 - log[i]];
			int s = inv ^ inv << 1 ^ inv << 2 ^ inv << 3 ^ inv << 4;
			SBOX[i] = (s ^ s >>> 8 ^ 0x63) & 0xff;
		}

		for (int i = 0; i < 256; i++) {
			int s = SBOX[i];
			int s2 = mul2(s);
			int s3 = s2 ^ s;
			T0[i] = s2 << 24 | s << 16 | s << 8 | s3;
			T1[i] = s3 << 24 | s2 << 16 | s << 8 | s;
			T2[i] = s << 24 | s3 << 16 | s2 << 8 | s;
			T3[i] = s << 24 | s << 16 | s3 << 8 | s2;
		}
	}

	private static int mul2(final int b) {
		return (b << 1 ^ ((b & 0x80) != 0 ? 0x1b : 0)) & 0xff;
	}

	private final int[] roundKeys = new int[44];
	private int r0;
	private int r1;
	private int r2;
	private int r3;

	public WebAesCfb8(final byte[] secret) {
		if (secret.length != 16) {
			throw new IllegalArgumentException("AES-128 needs a 16-byte secret, got " + secret.length);
		}

		expandKey(secret);
		this.r0 = word(secret, 0);
		this.r1 = word(secret, 4);
		this.r2 = word(secret, 8);
		this.r3 = word(secret, 12);
	}

	private static int word(final byte[] b, final int off) {
		return (b[off] & 0xff) << 24 | (b[off + 1] & 0xff) << 16 | (b[off + 2] & 0xff) << 8 | b[off + 3] & 0xff;
	}

	private void expandKey(final byte[] key) {
		int[] rk = this.roundKeys;
		for (int i = 0; i < 4; i++) {
			rk[i] = word(key, i * 4);
		}

		int rcon = 1;
		for (int i = 4; i < 44; i++) {
			int t = rk[i - 1];
			if (i % 4 == 0) {
				t = t << 8 | t >>> 24;
				t = SBOX[t >>> 24] << 24 | SBOX[t >>> 16 & 0xff] << 16 | SBOX[t >>> 8 & 0xff] << 8 | SBOX[t & 0xff];
				t ^= rcon << 24;
				rcon = mul2(rcon);
			}

			rk[i] = rk[i - 4] ^ t;
		}
	}

	private int keyStreamByte() {
		int[] rk = this.roundKeys;
		int s0 = this.r0 ^ rk[0];
		int s1 = this.r1 ^ rk[1];
		int s2 = this.r2 ^ rk[2];
		int s3 = this.r3 ^ rk[3];

		for (int round = 1; round < 10; round++) {
			int k = round << 2;
			int t0 = T0[s0 >>> 24] ^ T1[s1 >>> 16 & 0xff] ^ T2[s2 >>> 8 & 0xff] ^ T3[s3 & 0xff] ^ rk[k];
			int t1 = T0[s1 >>> 24] ^ T1[s2 >>> 16 & 0xff] ^ T2[s3 >>> 8 & 0xff] ^ T3[s0 & 0xff] ^ rk[k + 1];
			int t2 = T0[s2 >>> 24] ^ T1[s3 >>> 16 & 0xff] ^ T2[s0 >>> 8 & 0xff] ^ T3[s1 & 0xff] ^ rk[k + 2];
			int t3 = T0[s3 >>> 24] ^ T1[s0 >>> 16 & 0xff] ^ T2[s1 >>> 8 & 0xff] ^ T3[s2 & 0xff] ^ rk[k + 3];
			s0 = t0;
			s1 = t1;
			s2 = t2;
			s3 = t3;
		}

		return (SBOX[s0 >>> 24] ^ rk[40] >>> 24) & 0xff;
	}

	private void advance(final int in) {
		this.r0 = this.r0 << 8 | this.r1 >>> 24;
		this.r1 = this.r1 << 8 | this.r2 >>> 24;
		this.r2 = this.r2 << 8 | this.r3 >>> 24;
		this.r3 = this.r3 << 8 | in & 0xff;
	}

	public void encrypt(final byte[] buf, final int offset, final int length) {
		for (int i = offset; i < offset + length; i++) {
			int c = (buf[i] ^ keyStreamByte()) & 0xff;
			buf[i] = (byte)c;
			advance(c);
		}
	}

	public void decrypt(final byte[] buf, final int offset, final int length) {
		for (int i = offset; i < offset + length; i++) {
			int c = buf[i] & 0xff;
			buf[i] = (byte)(c ^ keyStreamByte());
			advance(c);
		}
	}

	public static byte[] encryptBlockForTest(final byte[] key, final byte[] block) {
		WebAesCfb8 aes = new WebAesCfb8(key);
		aes.r0 = word(block, 0);
		aes.r1 = word(block, 4);
		aes.r2 = word(block, 8);
		aes.r3 = word(block, 12);

		int[] rk = aes.roundKeys;
		int s0 = aes.r0 ^ rk[0];
		int s1 = aes.r1 ^ rk[1];
		int s2 = aes.r2 ^ rk[2];
		int s3 = aes.r3 ^ rk[3];

		for (int round = 1; round < 10; round++) {
			int k = round << 2;
			int t0 = T0[s0 >>> 24] ^ T1[s1 >>> 16 & 0xff] ^ T2[s2 >>> 8 & 0xff] ^ T3[s3 & 0xff] ^ rk[k];
			int t1 = T0[s1 >>> 24] ^ T1[s2 >>> 16 & 0xff] ^ T2[s3 >>> 8 & 0xff] ^ T3[s0 & 0xff] ^ rk[k + 1];
			int t2 = T0[s2 >>> 24] ^ T1[s3 >>> 16 & 0xff] ^ T2[s0 >>> 8 & 0xff] ^ T3[s1 & 0xff] ^ rk[k + 2];
			int t3 = T0[s3 >>> 24] ^ T1[s0 >>> 16 & 0xff] ^ T2[s1 >>> 8 & 0xff] ^ T3[s2 & 0xff] ^ rk[k + 3];
			s0 = t0;
			s1 = t1;
			s2 = t2;
			s3 = t3;
		}

		int o0 = (SBOX[s0 >>> 24] << 24 | SBOX[s1 >>> 16 & 0xff] << 16 | SBOX[s2 >>> 8 & 0xff] << 8 | SBOX[s3 & 0xff]) ^ rk[40];
		int o1 = (SBOX[s1 >>> 24] << 24 | SBOX[s2 >>> 16 & 0xff] << 16 | SBOX[s3 >>> 8 & 0xff] << 8 | SBOX[s0 & 0xff]) ^ rk[41];
		int o2 = (SBOX[s2 >>> 24] << 24 | SBOX[s3 >>> 16 & 0xff] << 16 | SBOX[s0 >>> 8 & 0xff] << 8 | SBOX[s1 & 0xff]) ^ rk[42];
		int o3 = (SBOX[s3 >>> 24] << 24 | SBOX[s0 >>> 16 & 0xff] << 16 | SBOX[s1 >>> 8 & 0xff] << 8 | SBOX[s2 & 0xff]) ^ rk[43];

		byte[] out = new byte[16];
		putWord(out, 0, o0);
		putWord(out, 4, o1);
		putWord(out, 8, o2);
		putWord(out, 12, o3);
		return out;
	}

	private static void putWord(final byte[] b, final int off, final int w) {
		b[off] = (byte)(w >>> 24);
		b[off + 1] = (byte)(w >>> 16);
		b[off + 2] = (byte)(w >>> 8);
		b[off + 3] = (byte)w;
	}
}
