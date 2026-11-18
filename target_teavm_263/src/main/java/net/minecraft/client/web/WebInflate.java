// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebInflate {
	private static int calls;
	private static int inBytes;
	private static int outBytes;

	private WebInflate() {
	}

	public static byte[] zlib(final byte[] src) {
		return zlib(src, Integer.MAX_VALUE);
	}

	public static byte[] zlib(final byte[] src, final int maxOutput) {
		if (maxOutput < 0) throw new IllegalArgumentException("Negative inflate output limit");
		return copy(nativeInflate(src, 0, maxOutput), src.length);
	}

	public static byte[] gzip(final byte[] src) {
		return copy(nativeInflate(src, 1, Integer.MAX_VALUE), src.length);
	}

	public static byte[] raw(final byte[] src) {
		return copy(nativeInflate(src, 2, Integer.MAX_VALUE), src.length);
	}

	public static int[] churnCounters() {
		return new int[] { calls, inBytes, outBytes, nativeMicros() };
	}

	@JSBody(script = "return globalThis.__minecraftInflateMicros|0;")
	private static native int nativeMicros();

	private static byte[] copy(final Int8Array src, final int inLen) {
		int n = src.getLength();
		byte[] dst = src.copyToJavaArray().clone();
		calls++;
		inBytes += inLen;
		outBytes += n;
		return dst;
	}

	@JSBody(params = { "src", "mode", "maxOutput" }, script = """
		var t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : 0;
		if (!globalThis.__minecraftInflate) {
			globalThis.__minecraftInflate = (function() {
		function inflateRaw(src, maxOutput) {
			src = asU8(src);
			if (maxOutput === undefined) maxOutput = 0x7fffffff;
			var dest = new Uint8Array(Math.min(maxOutput, Math.max(src.length * 4, 1024)));
			var d = new Data(src, dest, maxOutput);
			var bfinal, btype, res;
			do {
				bfinal = tinf_getbit(d);
				btype = tinf_read_bits(d, 2, 0);
				if (btype === 0) res = tinf_inflate_uncompressed_block(d);
				else if (btype === 1) res = tinf_inflate_block_data(d, sltree, sdtree);
				else if (btype === 2) {
					tinf_decode_trees(d, d.ltree, d.dtree);
					res = tinf_inflate_block_data(d, d.ltree, d.dtree);
				} else {
					throw new Error("inflate: bad block type " + btype);
				}
				if (res !== 0) throw new Error("inflate: data error");
			} while (!bfinal);
			return d.dest.subarray(0, d.destLen);
		}

		function inflateZlib(src, maxOutput) {
			src = asU8(src);
			if (src.length < 6) throw new Error("zlib: truncated");
			var cmf = src[0], flg = src[1];
			if ((cmf & 0x0f) !== 8) throw new Error("zlib: not deflate");
			if (((cmf << 8) + flg) % 31 !== 0) throw new Error("zlib: header check");
			var off = 2;
			if (flg & 0x20) {
				if (src.length < 10) throw new Error("zlib: truncated dict");
				off += 4;
			}
			return inflateRaw(src.subarray(off, src.length - 4), maxOutput);
		}

		function inflateGzip(src, maxOutput) {
			src = asU8(src);
			if (src.length < 18) throw new Error("gzip: truncated");
			if (src[0] !== 0x1f || src[1] !== 0x8b) throw new Error("gzip: magic");
			if (src[2] !== 8) throw new Error("gzip: not deflate");
			var flg = src[3];
			var off = 10;
			if (flg & 4) {
				if (off + 2 > src.length) throw new Error("gzip: truncated extra");
				var xlen = src[off] | (src[off + 1] << 8);
				off += 2 + xlen;
			}
			if (flg & 8) {
				while (off < src.length && src[off] !== 0) off++;
				off++;
			}
			if (flg & 16) {
				while (off < src.length && src[off] !== 0) off++;
				off++;
			}
			if (flg & 2) off += 2;
			if (off + 8 > src.length) throw new Error("gzip: truncated body");
			return inflateRaw(src.subarray(off, src.length - 8), maxOutput);
		}

		function asU8(src) {
			// A TeaVM byte[] arrives as a wrapper object, NOT a typed array: the
			// payload is on .data, and the wrapper has no .length at all (element
			// access compiles to .data[i], length to .data.length). Reading
			// src.length here gave undefined, so new Uint8Array(undefined) made an
			// EMPTY array and every inflate failed -- every PNG and every saved
			// chunk. Unwrap first, then view; only a non-view falls back to a copy.
			var d = src && src.data ? src.data : src;
			if (d instanceof Uint8Array) return d;
			if (ArrayBuffer.isView(d)) return new Uint8Array(d.buffer, d.byteOffset, d.byteLength);
			var n = d.length, out = new Uint8Array(n);
			for (var i = 0; i < n; i++) out[i] = d[i] & 255;
			return out;
		}

		function Tree() {
			this.table = new Uint16Array(16);
			this.trans = new Uint16Array(288);
		}

		function Data(source, dest, maxOutput) {
			this.source = source;
			this.sourceIndex = 0;
			this.tag = 0;
			this.bitcount = 0;
			this.dest = dest;
			this.destLen = 0;
			this.maxOutput = maxOutput;
			this.ltree = new Tree();
			this.dtree = new Tree();
		}

		function grow(d, need) {
			if (need > d.maxOutput - d.destLen) throw new Error("inflate: output limit exceeded");
			if (d.destLen + need <= d.dest.length) return;
			var n = Math.min(d.maxOutput, Math.max(d.dest.length * 2, d.destLen + need));
			var nd = new Uint8Array(n);
			nd.set(d.dest.subarray(0, d.destLen));
			d.dest = nd;
		}

		var sltree = new Tree();
		var sdtree = new Tree();
		var length_bits = new Uint8Array(30);
		var length_base = new Uint16Array(30);
		var dist_bits = new Uint8Array(30);
		var dist_base = new Uint16Array(30);
		var clcidx = new Uint8Array([16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]);
		var code_tree = new Tree();
		var lengths = new Uint8Array(288 + 32);
		var offs = new Uint16Array(16);

		function tinf_build_bits_base(bits, base, delta, first) {
			var i, sum;
			for (i = 0; i < delta; ++i) bits[i] = 0;
			for (i = 0; i < 30 - delta; ++i) bits[i + delta] = (i / delta) | 0;
			for (sum = first, i = 0; i < 30; ++i) {
				base[i] = sum;
				sum += 1 << bits[i];
			}
		}

		function tinf_build_fixed_trees(lt, dt) {
			var i;
			for (i = 0; i < 7; ++i) lt.table[i] = 0;
			lt.table[7] = 24;
			lt.table[8] = 152;
			lt.table[9] = 112;
			for (i = 0; i < 24; ++i) lt.trans[i] = 256 + i;
			for (i = 0; i < 144; ++i) lt.trans[24 + i] = i;
			for (i = 0; i < 8; ++i) lt.trans[24 + 144 + i] = 280 + i;
			for (i = 0; i < 112; ++i) lt.trans[24 + 144 + 8 + i] = 144 + i;
			for (i = 0; i < 5; ++i) dt.table[i] = 0;
			dt.table[5] = 32;
			for (i = 0; i < 32; ++i) dt.trans[i] = i;
		}

		function tinf_build_tree(t, lens, off, num) {
			var i, sum;
			for (i = 0; i < 16; ++i) t.table[i] = 0;
			for (i = 0; i < num; ++i) t.table[lens[off + i]]++;
			t.table[0] = 0;
			for (sum = 0, i = 0; i < 16; ++i) {
				offs[i] = sum;
				sum += t.table[i];
			}
			for (i = 0; i < num; ++i) {
				if (lens[off + i]) t.trans[offs[lens[off + i]]++] = i;
			}
		}

		function tinf_getbit(d) {
			if (!d.bitcount--) {
				d.tag = d.source[d.sourceIndex++];
				d.bitcount = 7;
			}
			var bit = d.tag & 1;
			d.tag >>>= 1;
			return bit;
		}

		function tinf_read_bits(d, num, base) {
			if (!num) return base;
			while (d.bitcount < 24) {
				d.tag |= d.source[d.sourceIndex++] << d.bitcount;
				d.bitcount += 8;
			}
			var val = d.tag & (0xffff >>> (16 - num));
			d.tag >>>= num;
			d.bitcount -= num;
			return val + base;
		}

		function tinf_decode_symbol(d, t) {
			while (d.bitcount < 24) {
				d.tag |= d.source[d.sourceIndex++] << d.bitcount;
				d.bitcount += 8;
			}
			var sum = 0, cur = 0, len = 0, tag = d.tag;
			do {
				cur = 2 * cur + (tag & 1);
				tag >>>= 1;
				++len;
				sum += t.table[len];
				cur -= t.table[len];
			} while (cur >= 0);
			d.tag = tag;
			d.bitcount -= len;
			return t.trans[sum + cur];
		}

		function tinf_decode_trees(d, lt, dt) {
			var hlit = tinf_read_bits(d, 5, 257);
			var hdist = tinf_read_bits(d, 5, 1);
			var hclen = tinf_read_bits(d, 4, 4);
			var i, num, length;
			for (i = 0; i < 19; ++i) lengths[i] = 0;
			for (i = 0; i < hclen; ++i) lengths[clcidx[i]] = tinf_read_bits(d, 3, 0);
			tinf_build_tree(code_tree, lengths, 0, 19);
			for (num = 0; num < hlit + hdist; ) {
				var sym = tinf_decode_symbol(d, code_tree);
				if (sym === 16) {
					var prev = lengths[num - 1];
					for (length = tinf_read_bits(d, 2, 3); length; --length) lengths[num++] = prev;
				} else if (sym === 17) {
					for (length = tinf_read_bits(d, 3, 3); length; --length) lengths[num++] = 0;
				} else if (sym === 18) {
					for (length = tinf_read_bits(d, 7, 11); length; --length) lengths[num++] = 0;
				} else {
					lengths[num++] = sym;
				}
			}
			tinf_build_tree(lt, lengths, 0, hlit);
			tinf_build_tree(dt, lengths, hlit, hdist);
		}

		function tinf_inflate_block_data(d, lt, dt) {
			for (;;) {
				var sym = tinf_decode_symbol(d, lt);
				if (sym === 256) return 0;
				if (sym < 256) {
					grow(d, 1);
					d.dest[d.destLen++] = sym;
				} else {
					sym -= 257;
					var length = tinf_read_bits(d, length_bits[sym], length_base[sym]);
					var dist = tinf_decode_symbol(d, dt);
					var copyOff = d.destLen - tinf_read_bits(d, dist_bits[dist], dist_base[dist]);
					grow(d, length);
					for (var i = 0; i < length; i++) d.dest[d.destLen++] = d.dest[copyOff++];
				}
			}
		}

		function tinf_inflate_uncompressed_block(d) {
			while (d.bitcount >= 8) {
				d.sourceIndex--;
				d.bitcount -= 8;
			}
			var length = d.source[d.sourceIndex] | (d.source[d.sourceIndex + 1] << 8);
			var invlength = d.source[d.sourceIndex + 2] | (d.source[d.sourceIndex + 3] << 8);
			if (length !== (~invlength & 0xffff)) return -3;
			d.sourceIndex += 4;
			grow(d, length);
			for (var i = 0; i < length; i++) d.dest[d.destLen++] = d.source[d.sourceIndex++];
			d.bitcount = 0;
			return 0;
		}

		tinf_build_fixed_trees(sltree, sdtree);
		tinf_build_bits_base(length_bits, length_base, 4, 3);
		tinf_build_bits_base(dist_bits, dist_base, 2, 1);
		length_bits[28] = 0;
		length_base[28] = 258;
				return { raw: inflateRaw, zlib: inflateZlib, gzip: inflateGzip };
			})();
		}
		var f = globalThis.__minecraftInflate;
		var out = mode === 1 ? f.gzip(src, maxOutput) : (mode === 2 ? f.raw(src, maxOutput) : f.zlib(src, maxOutput));
		if (typeof performance !== 'undefined' && performance.now) {
			var dt = performance.now() - t0;
			if (typeof globalThis.__minecraftInflateMicros !== 'number') globalThis.__minecraftInflateMicros = 0;
			globalThis.__minecraftInflateMicros += dt;
		}
		return new Int8Array(out.buffer, out.byteOffset, out.length);
		""")
	private static native Int8Array nativeInflate(byte[] src, int mode, int maxOutput);
}
