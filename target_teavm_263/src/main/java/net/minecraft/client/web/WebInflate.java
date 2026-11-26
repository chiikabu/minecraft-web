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
		return copy(nativeInflate(src, 0, src.length, 0, maxOutput), src.length);
	}

	public static byte[] zlib(final byte[] src, final int offset, final int length, final int maxOutput) {
		if (maxOutput < 0) throw new IllegalArgumentException("Negative inflate output limit");
		if (offset < 0 || length < 0 || offset > src.length - length) {
			throw new IndexOutOfBoundsException("Invalid inflate slice");
		}
		return copy(nativeInflate(src, offset, length, 0, maxOutput), length);
	}

	public static byte[] gzip(final byte[] src) {
		return copy(nativeInflate(src, 0, src.length, 1, Integer.MAX_VALUE), src.length);
	}

	public static byte[] raw(final byte[] src) {
		return copy(nativeInflate(src, 0, src.length, 2, Integer.MAX_VALUE), src.length);
	}

	public static int[] churnCounters() {
		return new int[] { calls, inBytes, outBytes, nativeMicros() };
	}

	@JSBody(script = "return globalThis.__minecraftInflateMicros|0;")
	private static native int nativeMicros();

	private static byte[] copy(final Int8Array src, final int inLen) {
		int n = src.getLength();
		byte[] dst = src.copyToJavaArray();
		calls++;
		inBytes += inLen;
		outBytes += n;
		return dst;
	}

	@JSBody(params = { "src", "offset", "length", "mode", "maxOutput" }, script = """
		var t0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : 0;
		if (!globalThis.__minecraftInflate) {
			globalThis.__minecraftInflate = (function() {
		function inflateRaw(src, maxOutput) {
			src = asU8(src);
			if (maxOutput === undefined) maxOutput = 0x7fffffff;
			var d = state;
			d.source = src;
			d.sourceIndex = 0;
			d.tag = 0;
			d.bitcount = 0;
			d.dest = scratch;
			d.destLen = 0;
			d.maxOutput = maxOutput;
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
			var out = d.dest.subarray(0, d.destLen);
			d.source = null;
			d.dest = null;
			return out;
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
			this.fast = new Int32Array(512);
			this.fastMask = 511;
		}

		var dltree = new Tree();
		var ddtree = new Tree();
		var scratch = new Uint8Array(65536);
		var state = {
			source: null,
			sourceIndex: 0,
			tag: 0,
			bitcount: 0,
			dest: null,
			destLen: 0,
			maxOutput: 0,
			ltree: dltree,
			dtree: ddtree
		};

		function grow(d, need) {
			if (need > d.maxOutput - d.destLen) throw new Error("inflate: output limit exceeded");
			if (d.destLen + need <= d.dest.length) return;
			var n = Math.min(d.maxOutput, Math.max(d.dest.length * 2, d.destLen + need));
			var nd = new Uint8Array(n);
			nd.set(d.dest.subarray(0, d.destLen));
			d.dest = nd;
			if (n <= 4194304 && n > scratch.length) {
				scratch = nd;
			}
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
		var rev9 = new Uint16Array(512);
		for (var ri = 0; ri < 512; ++ri) {
			var rv = ri, ro = 0;
			for (var rb = 0; rb < 9; ++rb) {
				ro = (ro << 1) | (rv & 1);
				rv >>>= 1;
			}
			rev9[ri] = ro;
		}

		function tinf_build_bits_base(bits, base, delta, first) {
			var i, sum;
			for (i = 0; i < delta; ++i) bits[i] = 0;
			for (i = 0; i < 30 - delta; ++i) bits[i + delta] = (i / delta) | 0;
			for (sum = first, i = 0; i < 30; ++i) {
				base[i] = sum;
				sum += 1 << bits[i];
			}
		}

		function tinf_build_fast(t, maxLen) {
			var fast = t.fast, table = t.table, trans = t.trans;
			var fbits = maxLen > 9 ? 9 : (maxLen < 1 ? 1 : maxLen);
			var fsize = 1 << fbits;
			t.fastMask = fsize - 1;
			var j, len, count, i, code = 0, idx = 0;
			if (maxLen > 9 || maxLen === 0) {
				for (j = 0; j < fsize; ++j) fast[j] = 0;
			}
			for (len = 1; len <= fbits; ++len) {
				count = table[len];
				var step = 1 << len;
				var shift = 9 - len;
				for (i = 0; i < count; ++i) {
					var entry = (trans[idx++] << 4) | len;
					var fill = rev9[code & 511] >>> shift;
					for (; fill < fsize; fill += step) {
						fast[fill] = entry;
					}
					++code;
				}
				code <<= 1;
			}
			if (maxLen <= 9 && maxLen > 0 && code < (fsize << 1)) {
				for (j = 0; j < fsize; ++j) fast[j] = 0;
				code = 0;
				idx = 0;
				for (len = 1; len <= fbits; ++len) {
					count = table[len];
					var step2 = 1 << len;
					var shift2 = 9 - len;
					for (i = 0; i < count; ++i) {
						var entry2 = (trans[idx++] << 4) | len;
						var fill2 = rev9[code & 511] >>> shift2;
						for (; fill2 < fsize; fill2 += step2) {
							fast[fill2] = entry2;
						}
						++code;
					}
					code <<= 1;
				}
			}
		}

		function tinf_build_fixed_trees(lt, dt) {
			var i;
			for (i = 0; i < 16; ++i) lt.table[i] = 0;
			lt.table[7] = 24;
			lt.table[8] = 152;
			lt.table[9] = 112;
			for (i = 0; i < 24; ++i) lt.trans[i] = 256 + i;
			for (i = 0; i < 144; ++i) lt.trans[24 + i] = i;
			for (i = 0; i < 8; ++i) lt.trans[24 + 144 + i] = 280 + i;
			for (i = 0; i < 112; ++i) lt.trans[24 + 144 + 8 + i] = 144 + i;
			for (i = 0; i < 16; ++i) dt.table[i] = 0;
			dt.table[5] = 32;
			for (i = 0; i < 32; ++i) dt.trans[i] = i;
			tinf_build_fast(lt, 9);
			tinf_build_fast(dt, 5);
		}

		function tinf_build_tree(t, lens, off, num) {
			var i, sum, maxLen = 0, l;
			var table = t.table, trans = t.trans;
			for (i = 0; i < 16; ++i) table[i] = 0;
			for (i = 0; i < num; ++i) {
				l = lens[off + i];
				if (l > maxLen) maxLen = l;
				table[l]++;
			}
			table[0] = 0;
			for (sum = 0, i = 0; i < 16; ++i) {
				offs[i] = sum;
				sum += table[i];
			}
			for (i = 0; i < num; ++i) {
				l = lens[off + i];
				if (l) trans[offs[l]++] = i;
			}
			tinf_build_fast(t, maxLen);
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
			if (d.bitcount < num) {
				while (d.bitcount < 24) {
					d.tag |= d.source[d.sourceIndex++] << d.bitcount;
					d.bitcount += 8;
				}
			}
			var val = d.tag & ((1 << num) - 1);
			d.tag >>>= num;
			d.bitcount -= num;
			return val + base;
		}

		function tinf_decode_symbol_slow(d, t) {
			var sum = 0, cur = 0, len = 0, tag = d.tag, table = t.table;
			do {
				cur = 2 * cur + (tag & 1);
				tag >>>= 1;
				++len;
				if (len > 15) throw new Error("inflate: bad code");
				sum += table[len];
				cur -= table[len];
			} while (cur >= 0);
			d.tag = tag;
			d.bitcount -= len;
			return t.trans[sum + cur];
		}

		function tinf_decode_symbol(d, t) {
			if (d.bitcount < 15) {
				while (d.bitcount < 24) {
					d.tag |= d.source[d.sourceIndex++] << d.bitcount;
					d.bitcount += 8;
				}
			}
			var entry = t.fast[d.tag & t.fastMask];
			if (entry) {
				var bits = entry & 15;
				d.tag >>>= bits;
				d.bitcount -= bits;
				return entry >>> 4;
			}
			return tinf_decode_symbol_slow(d, t);
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
			var src = d.source;
			var srcIdx = d.sourceIndex;
			var tag = d.tag;
			var bitcount = d.bitcount;
			var dest = d.dest;
			var destLen = d.destLen;
			var maxOut = d.maxOutput;
			var destCap = dest.length < maxOut ? dest.length : maxOut;
			var ltFast = lt.fast;
			var ltMask = lt.fastMask;
			var dtFast = dt.fast;
			var dtMask = dt.fastMask;
			var sym, entry, bits, length, dist, copyOff, i, num, fillVal;
			for (;;) {
				if (bitcount < 15) {
					tag |= (src[srcIdx++] << bitcount) | (src[srcIdx++] << (bitcount + 8));
					bitcount += 16;
				}
				entry = ltFast[tag & ltMask];
				if (entry) {
					bits = entry & 15;
					tag >>>= bits;
					bitcount -= bits;
					sym = entry >>> 4;
				} else {
					d.sourceIndex = srcIdx;
					d.tag = tag;
					d.bitcount = bitcount;
					sym = tinf_decode_symbol_slow(d, lt);
					srcIdx = d.sourceIndex;
					tag = d.tag;
					bitcount = d.bitcount;
				}
				if (sym < 256) {
					if (destLen >= destCap) {
						d.destLen = destLen;
						grow(d, 1);
						dest = d.dest;
						destCap = dest.length < maxOut ? dest.length : maxOut;
					}
					dest[destLen++] = sym;
				} else if (sym === 256) {
					d.sourceIndex = srcIdx;
					d.tag = tag;
					d.bitcount = bitcount;
					d.destLen = destLen;
					return 0;
				} else {
					sym -= 257;
					if (sym > 28) return -3;
					num = length_bits[sym];
					if (num) {
						if (bitcount < num) {
							tag |= (src[srcIdx++] << bitcount) | (src[srcIdx++] << (bitcount + 8));
							bitcount += 16;
						}
						length = (tag & ((1 << num) - 1)) + length_base[sym];
						tag >>>= num;
						bitcount -= num;
					} else {
						length = length_base[sym];
					}
					if (bitcount < 15) {
						tag |= (src[srcIdx++] << bitcount) | (src[srcIdx++] << (bitcount + 8));
						bitcount += 16;
					}
					entry = dtFast[tag & dtMask];
					if (entry) {
						bits = entry & 15;
						tag >>>= bits;
						bitcount -= bits;
						dist = entry >>> 4;
					} else {
						d.sourceIndex = srcIdx;
						d.tag = tag;
						d.bitcount = bitcount;
						dist = tinf_decode_symbol_slow(d, dt);
						srcIdx = d.sourceIndex;
						tag = d.tag;
						bitcount = d.bitcount;
					}
					if (dist > 29) return -3;
					num = dist_bits[dist];
					if (num) {
						if (bitcount < num) {
							tag |= (src[srcIdx++] << bitcount) | (src[srcIdx++] << (bitcount + 8));
							bitcount += 16;
						}
						dist = (tag & ((1 << num) - 1)) + dist_base[dist];
						tag >>>= num;
						bitcount -= num;
					} else {
						dist = dist_base[dist];
					}
					copyOff = destLen - dist;
					if (copyOff < 0) return -3;
					if (destLen + length > destCap) {
						d.destLen = destLen;
						grow(d, length);
						dest = d.dest;
						destCap = dest.length < maxOut ? dest.length : maxOut;
					}
					if (dist >= length && length >= 32) {
						dest.copyWithin(destLen, copyOff, copyOff + length);
						destLen += length;
					} else if (dist === 1) {
						fillVal = dest[copyOff];
						if (length >= 32) {
							dest.fill(fillVal, destLen, destLen + length);
							destLen += length;
						} else {
							for (i = 0; i < length; i++) dest[destLen++] = fillVal;
						}
					} else {
						for (i = 0; i < length; i++) dest[destLen++] = dest[copyOff++];
					}
				}
			}
		}

		function tinf_inflate_uncompressed_block(d) {
			while (d.bitcount >= 8) {
				d.sourceIndex--;
				d.bitcount -= 8;
			}
			if (d.sourceIndex + 4 > d.source.length) return -3;
			var length = d.source[d.sourceIndex] | (d.source[d.sourceIndex + 1] << 8);
			var invlength = d.source[d.sourceIndex + 2] | (d.source[d.sourceIndex + 3] << 8);
			if (length !== (~invlength & 0xffff)) return -3;
			d.sourceIndex += 4;
			if (d.sourceIndex + length > d.source.length) return -3;
			if (length > 0) {
				grow(d, length);
				d.dest.set(d.source.subarray(d.sourceIndex, d.sourceIndex + length), d.destLen);
				d.destLen += length;
				d.sourceIndex += length;
			}
			d.bitcount = 0;
			d.tag = 0;
			return 0;
		}

		tinf_build_fixed_trees(sltree, sdtree);
		tinf_build_bits_base(length_bits, length_base, 4, 3);
		tinf_build_bits_base(dist_bits, dist_base, 2, 1);
		length_bits[28] = 0;
		length_base[28] = 258;
				return { asU8: asU8, raw: inflateRaw, zlib: inflateZlib, gzip: inflateGzip };
			})();
		}
		var f = globalThis.__minecraftInflate;
		var u8 = f.asU8(src);
		if (offset !== 0 || length !== u8.length) {
			u8 = u8.subarray(offset, offset + length);
		}
		var out = mode === 1 ? f.gzip(u8, maxOutput) : (mode === 2 ? f.raw(u8, maxOutput) : f.zlib(u8, maxOutput));
		if (typeof performance !== 'undefined' && performance.now) {
			var dt = performance.now() - t0;
			if (typeof globalThis.__minecraftInflateMicros !== 'number') globalThis.__minecraftInflateMicros = 0;
			globalThis.__minecraftInflateMicros += dt;
		}
		return new Int8Array(out.buffer, out.byteOffset, out.length);
		""")
	private static native Int8Array nativeInflate(byte[] src, int offset, int length, int mode, int maxOutput);
}
