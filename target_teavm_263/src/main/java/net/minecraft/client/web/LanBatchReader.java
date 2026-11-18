// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class LanBatchReader {
	private static final byte[] MAGIC_BYTES = { 0x7F, 'T', 'W', 'B', '1' };
	private static final byte[] NOTHING = new byte[0];
	private static final int PROBE = 0;
	private static final int PLAIN = 1;
	private static final int BATCH = 2;

	private int mode = PROBE;
	private byte[] held = NOTHING;
	private int remaining;
	private int lengthSoFar;
	private int lengthShift;

	public static byte[] magic() {
		return MAGIC_BYTES.clone();
	}

	public boolean batching() {
		return this.mode == BATCH;
	}

	public byte[] feed(final byte[] slice) {
		if (this.mode == PLAIN) {
			return slice;
		}
		if (this.mode == BATCH) {
			return this.unwrap(slice, 0);
		}
		byte[] seen = this.held.length == 0 ? slice : concat(this.held, slice);
		int compare = Math.min(seen.length, 1 + MAGIC_BYTES.length);
		for (int i = 0; i < compare; i++) {
			if (seen[i] != (i == 0 ? MAGIC_BYTES.length : MAGIC_BYTES[i - 1])) {
				this.mode = PLAIN;
				this.held = NOTHING;
				return seen;
			}
		}
		if (seen.length < 1 + MAGIC_BYTES.length) {
			this.held = seen;
			return NOTHING;
		}
		this.mode = BATCH;
		this.held = NOTHING;
		return this.unwrap(seen, 1 + MAGIC_BYTES.length);
	}

	private byte[] unwrap(final byte[] data, final int from) {
		int length = data.length - from;
		if (length <= 0) {
			return NOTHING;
		}
		if (this.remaining >= length) {
			this.remaining -= length;
			return from == 0 ? data : Arrays.copyOfRange(data, from, data.length);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(length);
		int at = from;
		while (at < data.length) {
			if (this.remaining > 0) {
				int n = Math.min(this.remaining, data.length - at);
				out.write(data, at, n);
				at += n;
				this.remaining -= n;
			} else {
				int b = data[at++];
				this.lengthSoFar |= (b & 127) << this.lengthShift;
				this.lengthShift += 7;
				if ((b & 128) == 0) {
					this.remaining = this.lengthSoFar;
					this.lengthSoFar = 0;
					this.lengthShift = 0;
				} else if (this.lengthShift >= 21) {
					throw new IllegalStateException("batched relay frame length is wider than 21 bits");
				}
			}
		}
		return out.toByteArray();
	}

	private static byte[] concat(final byte[] a, final byte[] b) {
		byte[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
