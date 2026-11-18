// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.zip;

public class TCRC32C implements TChecksum {
	private static final int[] TABLE = new int[256];

	static {
		for (int i = 0; i < 256; i++) {
			int crc = i;
			for (int bit = 0; bit < 8; bit++) {
				crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0x82F63B78 : 0);
			}
			TABLE[i] = crc;
		}
	}

	private int crc = 0xFFFFFFFF;

	@Override
	public long getValue() {
		return (~crc) & 0xFFFFFFFFL;
	}

	@Override
	public void reset() {
		crc = 0xFFFFFFFF;
	}

	@Override
	public void update(int val) {
		crc = (crc >>> 8) ^ TABLE[(crc ^ val) & 0xFF];
	}

	public void update(byte[] buf) {
		update(buf, 0, buf.length);
	}

	@Override
	public void update(byte[] buf, int off, int nbytes) {
		if (off < 0 || nbytes < 0 || off > buf.length - nbytes) {
			throw new ArrayIndexOutOfBoundsException();
		}
		for (int i = 0; i < nbytes; i++) {
			crc = (crc >>> 8) ^ TABLE[(crc ^ buf[off + i]) & 0xFF];
		}
	}

	public static void main(String[] args) throws Exception {
		check("", 0x00000000L);
		check("a", 0xC1D04330L);
		check("123456789", 0xE3069283L);
		TCRC32C split = new TCRC32C();
		split.update('a');
		split.update("bc".getBytes("UTF-8"), 0, 2);
		TCRC32C whole = new TCRC32C();
		whole.update("abc".getBytes("UTF-8"), 0, 3);
		if (split.getValue() != whole.getValue()) {
			throw new AssertionError("split update mismatch");
		}
		System.out.println("OK");
	}

	private static void check(String input, long expected) throws Exception {
		TCRC32C c = new TCRC32C();
		byte[] b = input.getBytes("UTF-8");
		c.update(b, 0, b.length);
		if (c.getValue() != expected) {
			throw new AssertionError("\"" + input + "\": expected " + Long.toHexString(expected) + " got "
					+ Long.toHexString(c.getValue()));
		}
	}
}
