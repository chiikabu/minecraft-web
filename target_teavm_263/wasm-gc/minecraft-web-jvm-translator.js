/*
 * minecraft-web-jvm-translator.js
 *
 * 100% offline, zero-dependency, in-browser JVM .jar / .class parser,
 * bytecode-to-JavaScript basic-block compiler, invokedynamic runtime,
 * and Fabric mod loader for minecraft-web / Minecraft 26.3 WASM-GC.
 *
 * Safe for single-file HTML/XHTML/XML/SVG offline inlining.
 */
(function (globalScope) {
	"use strict";

	// =========================================================================
	// 1. PURE-JS RFC 1951 RAW DEFLATE INFLATE & SYNCHRONOUS ZIP/JAR EXTRACTOR
	// =========================================================================

	const CL_ORDER = new Uint8Array([
		16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
	]);

	const LEN_BASE = new Uint16Array([
		3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
		35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
	]);

	const LEN_EXTRA = new Uint8Array([
		0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
		3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
	]);

	const DIST_BASE = new Uint16Array([
		1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
		257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193,
		12289, 16385, 24577
	]);

	const DIST_EXTRA = new Uint8Array([
		0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
		7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
	]);

	const FAST_BITS = 9;
	const FAST_SIZE = 1 << FAST_BITS;
	const FAST_MASK = FAST_SIZE - 1;

	function buildHuffman(lengths, start, count) {
		const counts = new Int32Array(16);
		let maxBits = 0;
		for (let i = 0; i < count; i++) {
			const len = lengths[start + i];
			if (len > 0) {
				counts[len]++;
				if (len > maxBits) maxBits = len;
			}
		}
		const offsets = new Int32Array(16);
		let total = 0;
		for (let len = 1; len <= 15; len++) {
			offsets[len] = total;
			total += counts[len];
		}
		const symbols = new Int32Array(total);
		for (let i = 0; i < count; i++) {
			const len = lengths[start + i];
			if (len > 0) {
				symbols[offsets[len]++] = i;
			}
		}
		const fast = new Int32Array(FAST_SIZE);
		let code = 0;
		let idx = 0;
		for (let len = 1; len <= maxBits; len++) {
			const cnt = counts[len];
			if (len <= FAST_BITS) {
				const step = 1 << len;
				for (let k = 0; k < cnt; k++) {
					const sym = symbols[idx++];
					let rev = 0;
					let c = code;
					for (let b = 0; b < len; b++) {
						rev = (rev << 1) | (c & 1);
						c >>>= 1;
					}
					const entry = (sym << 4) | len;
					for (let fill = rev; fill < FAST_SIZE; fill += step) {
						fast[fill] = entry;
					}
					code++;
				}
			} else {
				idx += cnt;
				code += cnt;
			}
			code <<= 1;
		}
		return { counts, symbols, fast, maxBits };
	}

	const FIXED_LIT_TREE = (function () {
		const lens = new Uint8Array(288);
		lens.fill(8, 0, 144);
		lens.fill(9, 144, 256);
		lens.fill(7, 256, 280);
		lens.fill(8, 280, 288);
		return buildHuffman(lens, 0, 288);
	})();

	const FIXED_DIST_TREE = (function () {
		const lens = new Uint8Array(32);
		lens.fill(5);
		return buildHuffman(lens, 0, 32);
	})();

	function inflateRaw(src, expectedSize) {
		const srcLen = src.length;
		let bytePos = 0;
		let bitBuf = 0;
		let bitCount = 0;

		let outCap = expectedSize > 0 ? expectedSize : Math.max(srcLen * 2, 1024);
		let out = new Uint8Array(outCap);
		let outPos = 0;

		function ensureOut(extra) {
			const needed = outPos + extra;
			if (needed <= outCap) return;
			let nextCap = outCap;
			while (nextCap < needed) {
				nextCap = nextCap < 65536 ? nextCap * 2 : (nextCap + (nextCap >>> 1));
			}
			const next = new Uint8Array(nextCap);
			next.set(out.subarray(0, outPos));
			out = next;
			outCap = nextCap;
		}

		function readBits(n) {
			if (n === 0) return 0;
			while (bitCount < n) {
				if (bytePos >= srcLen) {
					throw new Error("Unexpected end of DEFLATE stream");
				}
				bitBuf |= src[bytePos++] << bitCount;
				bitCount += 8;
			}
			const val = bitBuf & ((1 << n) - 1);
			bitBuf >>>= n;
			bitCount -= n;
			return val;
		}

		function decodeSym(huff) {
			while (bitCount < 15 && bytePos < srcLen) {
				bitBuf |= src[bytePos++] << bitCount;
				bitCount += 8;
			}
			const f = huff.fast[bitBuf & FAST_MASK];
			if (f !== 0) {
				const flen = f & 15;
				if (flen <= bitCount) {
					bitBuf >>>= flen;
					bitCount -= flen;
					return f >>> 4;
				}
			}
			let code = 0;
			let first = 0;
			let index = 0;
			const maxBits = huff.maxBits;
			const counts = huff.counts;
			const symbols = huff.symbols;
			for (let len = 1; len <= maxBits; len++) {
				if (bitCount === 0) {
					if (bytePos >= srcLen) {
						throw new Error("Truncated DEFLATE Huffman code");
					}
					bitBuf = src[bytePos++];
					bitCount = 8;
				}
				code |= (bitBuf & 1);
				bitBuf >>>= 1;
				bitCount--;
				const cnt = counts[len];
				if (code - cnt < first) {
					return symbols[index + (code - first)];
				}
				index += cnt;
				first = (first + cnt) << 1;
				code <<= 1;
			}
			throw new Error("Invalid DEFLATE Huffman code");
		}

		let bfinal = 0;
		while (!bfinal) {
			bfinal = readBits(1);
			const btype = readBits(2);
			if (btype === 0) {
				// Stored uncompressed block: align to byte boundary & put back unread bytes
				const drop = bitCount & 7;
				bitBuf >>>= drop;
				bitCount -= drop;
				const unreadBytes = bitCount >>> 3;
				bytePos -= unreadBytes;
				bitBuf = 0;
				bitCount = 0;
				if (bytePos + 4 > srcLen) {
					throw new Error("Truncated DEFLATE stored block header");
				}
				const len = src[bytePos] | (src[bytePos + 1] << 8);
				const nlen = src[bytePos + 2] | (src[bytePos + 3] << 8);
				bytePos += 4;
				if ((len ^ 0xFFFF) !== nlen) {
					throw new Error("Corrupt DEFLATE stored block length");
				}
				if (bytePos + len > srcLen) {
					throw new Error("Truncated DEFLATE stored block payload");
				}
				ensureOut(len);
				out.set(src.subarray(bytePos, bytePos + len), outPos);
				outPos += len;
				bytePos += len;
			} else if (btype === 1 || btype === 2) {
				let litTree;
				let distTree;
				if (btype === 1) {
					litTree = FIXED_LIT_TREE;
					distTree = FIXED_DIST_TREE;
				} else {
					const hlit = readBits(5) + 257;
					const hdist = readBits(5) + 1;
					const hclen = readBits(4) + 4;
					const clLens = new Uint8Array(19);
					for (let i = 0; i < hclen; i++) {
						clLens[CL_ORDER[i]] = readBits(3);
					}
					const clTree = buildHuffman(clLens, 0, 19);
					const totalLens = hlit + hdist;
					const lengths = new Uint8Array(totalLens);
					let i = 0;
					while (i < totalLens) {
						const sym = decodeSym(clTree);
						if (sym < 16) {
							lengths[i++] = sym;
						} else if (sym === 16) {
							if (i === 0) throw new Error("Invalid DEFLATE repeat code");
							const rep = 3 + readBits(2);
							const prev = lengths[i - 1];
							for (let r = 0; r < rep && i < totalLens; r++) lengths[i++] = prev;
						} else if (sym === 17) {
							const rep = 3 + readBits(3);
							for (let r = 0; r < rep && i < totalLens; r++) lengths[i++] = 0;
						} else if (sym === 18) {
							const rep = 11 + readBits(7);
							for (let r = 0; r < rep && i < totalLens; r++) lengths[i++] = 0;
						} else {
							throw new Error("Invalid DEFLATE code length symbol: " + sym);
						}
					}
					litTree = buildHuffman(lengths, 0, hlit);
					distTree = buildHuffman(lengths, hlit, hdist);
				}

				while (true) {
					const sym = decodeSym(litTree);
					if (sym < 256) {
						if (outPos >= outCap) ensureOut(1);
						out[outPos++] = sym;
					} else if (sym === 256) {
						break;
					} else {
						const lenIdx = sym - 257;
						if (lenIdx < 0 || lenIdx >= 29) {
							throw new Error("Invalid DEFLATE length symbol: " + sym);
						}
						const length = LEN_BASE[lenIdx] + readBits(LEN_EXTRA[lenIdx]);
						const distSym = decodeSym(distTree);
						if (distSym < 0 || distSym >= 30) {
							throw new Error("Invalid DEFLATE distance symbol: " + distSym);
						}
						const dist = DIST_BASE[distSym] + readBits(DIST_EXTRA[distSym]);
						if (dist > outPos) {
							throw new Error("Invalid DEFLATE back-reference distance");
						}
						ensureOut(length);
						let srcIdx = outPos - dist;
						for (let k = 0; k < length; k++) {
							out[outPos++] = out[srcIdx++];
						}
					}
				}
			} else {
				throw new Error("Invalid DEFLATE block type: " + btype);
			}
		}

		return outPos === out.length ? out : out.slice(0, outPos);
	}

	const utf8Decoder = typeof TextDecoder !== "undefined" ? new TextDecoder("utf-8") : null;

	function decodeUtf8Bytes(bytes) {
		if (utf8Decoder) {
			return utf8Decoder.decode(bytes);
		}
		let out = "";
		let i = 0;
		const len = bytes.length;
		while (i < len) {
			const b0 = bytes[i++];
			if (b0 < 0x80) {
				out += String.fromCharCode(b0);
			} else if ((b0 & 0xE0) === 0xC0 && i < len) {
				const b1 = bytes[i++];
				out += String.fromCharCode(((b0 & 0x1F) << 6) | (b1 & 0x3F));
			} else if ((b0 & 0xF0) === 0xE0 && i + 1 < len) {
				const b1 = bytes[i++];
				const b2 = bytes[i++];
				out += String.fromCharCode(((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F));
			} else if ((b0 & 0xF8) === 0xF0 && i + 2 < len) {
				const b1 = bytes[i++];
				const b2 = bytes[i++];
				const b3 = bytes[i++];
				const cp = (((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)) - 0x10000;
				out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
			} else {
				out += "\uFFFD";
			}
		}
		return out;
	}

	function toUint8Array(input) {
		if (input instanceof Uint8Array) return input;
		if (input instanceof ArrayBuffer) return new Uint8Array(input);
		if (ArrayBuffer.isView(input)) {
			return new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
		}
		throw new Error("Expected Uint8Array or ArrayBuffer");
	}

	function readU16LE(b, o) {
		return b[o] | (b[o + 1] << 8);
	}

	function readU32LE(b, o) {
		return (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0;
	}

	function readU64LE(b, o) {
		const lo = readU32LE(b, o);
		const hi = readU32LE(b, o + 4);
		return hi * 4294967296 + lo;
	}

	function unzipJar(input) {
		const bytes = toUint8Array(input);
		const len = bytes.length;
		const entries = new Map();
		if (len < 22) {
			throw new Error("Invalid ZIP/JAR archive (too short)");
		}

		// Locate End of Central Directory (0x06054b50)
		const minEocd = Math.max(0, len - 65557);
		let eocd = -1;
		for (let pos = len - 22; pos >= minEocd; pos--) {
			if (bytes[pos] === 0x50 && bytes[pos + 1] === 0x4B && bytes[pos + 2] === 0x05 && bytes[pos + 3] === 0x06) {
				const commentLen = readU16LE(bytes, pos + 20);
				if (pos + 22 + commentLen === len) {
					eocd = pos;
					break;
				}
				if (eocd === -1 && pos + 22 + commentLen <= len) {
					eocd = pos;
				}
			}
		}
		if (eocd === -1) {
			throw new Error("Invalid ZIP/JAR archive (EOCD record not found)");
		}

		let totalEntries = readU16LE(bytes, eocd + 10);
		let cdSize = readU32LE(bytes, eocd + 12);
		let cdOffset = readU32LE(bytes, eocd + 16);

		// Support ZIP64 EOCD if needed
		if ((totalEntries === 0xFFFF || cdSize === 0xFFFFFFFF || cdOffset === 0xFFFFFFFF) && eocd >= 20) {
			const loc = eocd - 20;
			if (readU32LE(bytes, loc) === 0x07064B50) {
				const z64Off = readU64LE(bytes, loc + 8);
				if (z64Off + 56 <= len && readU32LE(bytes, z64Off) === 0x06064B50) {
					totalEntries = readU64LE(bytes, z64Off + 32);
					cdSize = readU64LE(bytes, z64Off + 40);
					cdOffset = readU64LE(bytes, z64Off + 48);
				}
			}
		}

		let ptr = cdOffset;
		const cdEnd = Math.min(len, cdOffset + cdSize);
		for (let idx = 0; idx < totalEntries && ptr + 46 <= cdEnd; idx++) {
			if (readU32LE(bytes, ptr) !== 0x02014B50) {
				break;
			}
			const method = readU16LE(bytes, ptr + 10);
			let compSize = readU32LE(bytes, ptr + 20);
			let uncompSize = readU32LE(bytes, ptr + 24);
			const nameLen = readU16LE(bytes, ptr + 28);
			const extraLen = readU16LE(bytes, ptr + 30);
			const commentLen = readU16LE(bytes, ptr + 32);
			let localOff = readU32LE(bytes, ptr + 42);

			const nameStart = ptr + 46;
			const extraStart = nameStart + nameLen;
			const nextPtr = extraStart + extraLen + commentLen;

			if (compSize === 0xFFFFFFFF || uncompSize === 0xFFFFFFFF || localOff === 0xFFFFFFFF) {
				let ex = extraStart;
				const exEnd = Math.min(len, extraStart + extraLen);
				while (ex + 4 <= exEnd) {
					const hdrId = readU16LE(bytes, ex);
					const hdrSize = readU16LE(bytes, ex + 2);
					let fieldPos = ex + 4;
					if (hdrId === 0x0001) {
						if (uncompSize === 0xFFFFFFFF && fieldPos + 8 <= ex + 4 + hdrSize) {
							uncompSize = readU64LE(bytes, fieldPos);
							fieldPos += 8;
						}
						if (compSize === 0xFFFFFFFF && fieldPos + 8 <= ex + 4 + hdrSize) {
							compSize = readU64LE(bytes, fieldPos);
							fieldPos += 8;
						}
						if (localOff === 0xFFFFFFFF && fieldPos + 8 <= ex + 4 + hdrSize) {
							localOff = readU64LE(bytes, fieldPos);
						}
						break;
					}
					ex += 4 + hdrSize;
				}
			}

			let entryName = decodeUtf8Bytes(bytes.subarray(nameStart, nameStart + nameLen));
			entryName = entryName.replace(/\\/g, "/").replace(/^\/+/, "");
			ptr = nextPtr;

			if (!entryName || entryName.endsWith("/")) {
				continue;
			}
			if (localOff + 30 > len || readU32LE(bytes, localOff) !== 0x04034B50) {
				continue;
			}
			const localNameLen = readU16LE(bytes, localOff + 26);
			const localExtraLen = readU16LE(bytes, localOff + 28);
			const dataStart = localOff + 30 + localNameLen + localExtraLen;
			const dataEnd = dataStart + compSize;
			if (dataEnd > len) {
				continue;
			}

			if (method === 0) {
				entries.set(entryName, bytes.slice(dataStart, dataEnd));
			} else if (method === 8) {
				entries.set(entryName, inflateRaw(bytes.subarray(dataStart, dataEnd), uncompSize));
			}
		}

		return entries;
	}

	// =========================================================================
	// 2. JVM .CLASS FILE PARSER (Java 8..25, major 45..69+, ALL CP tags 1..20)
	// =========================================================================

	function decodeModifiedUtf8(bytes, offset, length) {
		const end = offset + length;
		let out = "";
		let i = offset;
		while (i < end) {
			const b1 = bytes[i++];
			if ((b1 & 0x80) === 0) {
				out += String.fromCharCode(b1);
			} else if ((b1 & 0xE0) === 0xC0) {
				const b2 = i < end ? bytes[i++] : 0;
				out += String.fromCharCode(((b1 & 0x1F) << 6) | (b2 & 0x3F));
			} else if ((b1 & 0xF0) === 0xE0) {
				const b2 = i < end ? bytes[i++] : 0;
				const b3 = i < end ? bytes[i++] : 0;
				out += String.fromCharCode(((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F));
			} else if ((b1 & 0xF8) === 0xF0 && i + 2 < end) {
				const b2 = bytes[i++];
				const b3 = bytes[i++];
				const b4 = bytes[i++];
				const cp = (((b1 & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (b4 & 0x3F)) - 0x10000;
				out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
			} else {
				out += String.fromCharCode(b1);
			}
		}
		return out;
	}

	function descToInternalName(desc) {
		if (!desc) return desc;
		if (desc.length > 2 && desc.charCodeAt(0) === 76 && desc.charCodeAt(desc.length - 1) === 59) {
			return desc.slice(1, -1);
		}
		return desc;
	}

	function parseClass(input) {
		const bytes = toUint8Array(input);
		const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
		let pos = 0;

		function u1() { return bytes[pos++]; }
		function u2() { const v = dv.getUint16(pos, false); pos += 2; return v; }
		function u4() { const v = dv.getUint32(pos, false); pos += 4; return v; }
		function i4() { const v = dv.getInt32(pos, false); pos += 4; return v; }
		function f4() { const v = Math.fround(dv.getFloat32(pos, false)); pos += 4; return v; }
		function f8() { const v = dv.getFloat64(pos, false); pos += 8; return v; }
		function i8() {
			const hi = dv.getInt32(pos, false);
			const lo = dv.getUint32(pos + 4, false);
			pos += 8;
			return (BigInt(hi) << 32n) | BigInt(lo);
		}

		const magic = u4();
		if (magic !== 0xCAFEBABE) {
			throw new Error("Invalid class file magic: 0x" + magic.toString(16));
		}

		const minorVersion = u2();
		const majorVersion = u2();
		if (majorVersion < 45 || majorVersion > 75) {
			throw new Error("Unsupported class file major version: " + majorVersion);
		}

		const cpCount = u2();
		const cp = new Array(cpCount);
		cp[0] = null;

		for (let i = 1; i < cpCount; i++) {
			const tag = u1();
			switch (tag) {
				case 1: { // Utf8
					const strLen = u2();
					const value = decodeModifiedUtf8(bytes, pos, strLen);
					pos += strLen;
					cp[i] = { tag: 1, value };
					break;
				}
				case 3: // Integer
					cp[i] = { tag: 3, value: i4() };
					break;
				case 4: // Float
					cp[i] = { tag: 4, value: f4() };
					break;
				case 5: // Long (2 slots)
					cp[i] = { tag: 5, value: i8() };
					cp[++i] = null;
					break;
				case 6: // Double (2 slots)
					cp[i] = { tag: 6, value: f8() };
					cp[++i] = null;
					break;
				case 7: // Class
					cp[i] = { tag: 7, nameIndex: u2(), name: null };
					break;
				case 8: // String
					cp[i] = { tag: 8, stringIndex: u2(), value: null };
					break;
				case 9: // Fieldref
				case 10: // Methodref
				case 11: // InterfaceMethodref
					cp[i] = { tag, classIndex: u2(), nameAndTypeIndex: u2(), owner: null, name: null, descriptor: null };
					break;
				case 12: // NameAndType
					cp[i] = { tag: 12, nameIndex: u2(), descriptorIndex: u2(), name: null, descriptor: null };
					break;
				case 15: // MethodHandle
					cp[i] = { tag: 15, referenceKind: u1(), referenceIndex: u2(), ref: null, owner: null, name: null, descriptor: null };
					break;
				case 16: // MethodType
					cp[i] = { tag: 16, descriptorIndex: u2(), descriptor: null };
					break;
				case 17: // Dynamic
				case 18: // InvokeDynamic
					cp[i] = { tag, bootstrapMethodAttrIndex: u2(), nameAndTypeIndex: u2(), name: null, descriptor: null };
					break;
				case 19: // Module
				case 20: // Package
					cp[i] = { tag, nameIndex: u2(), name: null };
					break;
				default:
					throw new Error("Unknown constant pool tag " + tag + " at index " + i);
			}
		}

		function cpUtf8(idx) {
			const e = cp[idx];
			return e && e.tag === 1 ? e.value : "";
		}

		// Resolve constant pool cross-references in dependency order
		for (let i = 1; i < cpCount; i++) {
			const e = cp[i];
			if (!e) continue;
			if (e.tag === 7 || e.tag === 19 || e.tag === 20) {
				e.name = cpUtf8(e.nameIndex);
			} else if (e.tag === 8) {
				e.value = cpUtf8(e.stringIndex);
			} else if (e.tag === 12) {
				e.name = cpUtf8(e.nameIndex);
				e.descriptor = cpUtf8(e.descriptorIndex);
			} else if (e.tag === 16) {
				e.descriptor = cpUtf8(e.descriptorIndex);
			}
		}
		for (let i = 1; i < cpCount; i++) {
			const e = cp[i];
			if (!e) continue;
			if (e.tag === 9 || e.tag === 10 || e.tag === 11) {
				const clsE = cp[e.classIndex];
				const natE = cp[e.nameAndTypeIndex];
				e.owner = clsE ? clsE.name : "";
				e.name = natE ? natE.name : "";
				e.descriptor = natE ? natE.descriptor : "";
			} else if (e.tag === 17 || e.tag === 18) {
				const natE = cp[e.nameAndTypeIndex];
				e.name = natE ? natE.name : "";
				e.descriptor = natE ? natE.descriptor : "";
			}
		}
		for (let i = 1; i < cpCount; i++) {
			const e = cp[i];
			if (e && e.tag === 15) {
				const ref = cp[e.referenceIndex];
				e.ref = ref || null;
				if (ref) {
					e.owner = ref.owner || "";
					e.name = ref.name || "";
					e.descriptor = ref.descriptor || "";
				}
			}
		}

		const accessFlags = u2();
		const thisClassIdx = u2();
		const superClassIdx = u2();
		const name = cp[thisClassIdx] ? cp[thisClassIdx].name : "";
		const superName = superClassIdx !== 0 && cp[superClassIdx] ? cp[superClassIdx].name : null;

		const ifaceCount = u2();
		const interfaces = new Array(ifaceCount);
		for (let i = 0; i < ifaceCount; i++) {
			const idx = u2();
			interfaces[i] = cp[idx] ? cp[idx].name : "";
		}

		function readElementValue() {
			const tag = String.fromCharCode(u1());
			switch (tag) {
				case "B":
				case "I":
				case "S":
				case "F":
				case "D":
				case "J": {
					const idx = u2();
					return cp[idx] ? cp[idx].value : 0;
				}
				case "Z": {
					const idx = u2();
					return cp[idx] ? cp[idx].value !== 0 : false;
				}
				case "C": {
					const idx = u2();
					return cp[idx] ? String.fromCharCode(cp[idx].value & 0xFFFF) : "\0";
				}
				case "s": {
					const idx = u2();
					return cpUtf8(idx);
				}
				case "e": {
					const typeIdx = u2();
					const constIdx = u2();
					return {
						enumType: cpUtf8(typeIdx),
						constName: cpUtf8(constIdx)
					};
				}
				case "c": {
					const idx = u2();
					const rawDesc = cpUtf8(idx);
					return {
						className: descToInternalName(rawDesc),
						descriptor: rawDesc
					};
				}
				case "@":
					return readAnnotation(true);
				case "[": {
					const arrLen = u2();
					const arr = new Array(arrLen);
					for (let k = 0; k < arrLen; k++) {
						arr[k] = readElementValue();
					}
					return arr;
				}
				default:
					throw new Error("Unknown annotation element_value tag: " + tag);
			}
		}

		function readAnnotation(visible) {
			const typeDesc = cpUtf8(u2());
			const numPairs = u2();
			const values = Object.create(null);
			for (let k = 0; k < numPairs; k++) {
				const elemName = cpUtf8(u2());
				values[elemName] = readElementValue();
			}
			return {
				type: typeDesc,
				internalName: descToInternalName(typeDesc),
				visible: !!visible,
				values
			};
		}

		function readAttributes(ownerKind) {
			const count = u2();
			const result = {
				annotations: [],
				constantValue: undefined,
				code: null,
				bootstrapMethods: null,
				sourceFile: null
			};
			for (let i = 0; i < count; i++) {
				const attrName = cpUtf8(u2());
				const attrLen = u4();
				const attrEnd = pos + attrLen;
				if (attrName === "RuntimeVisibleAnnotations" || attrName === "RuntimeInvisibleAnnotations") {
					const visible = attrName === "RuntimeVisibleAnnotations";
					const numAnn = u2();
					for (let a = 0; a < numAnn; a++) {
						result.annotations.push(readAnnotation(visible));
					}
				} else if (attrName === "ConstantValue" && ownerKind === "field") {
					const idx = u2();
					const entry = cp[idx];
					result.constantValue = entry ? entry.value : undefined;
				} else if (attrName === "Code" && ownerKind === "method") {
					const maxStack = u2();
					const maxLocals = u2();
					const codeLength = u4();
					const codeBytes = bytes.slice(pos, pos + codeLength);
					pos += codeLength;
					const exTableLen = u2();
					const exceptionTable = new Array(exTableLen);
					for (let e = 0; e < exTableLen; e++) {
						const startPc = u2();
						const endPc = u2();
						const handlerPc = u2();
						const catchIdx = u2();
						const catchType = catchIdx === 0 ? null : (cp[catchIdx] ? cp[catchIdx].name : null);
						exceptionTable[e] = { startPc, endPc, handlerPc, catchType };
					}
					// Skip Code sub-attributes (LineNumberTable, StackMapTable, etc.)
					const subAttrCount = u2();
					for (let s = 0; s < subAttrCount; s++) {
						u2();
						const sLen = u4();
						pos += sLen;
					}
					result.code = { maxStack, maxLocals, bytes: codeBytes, exceptionTable };
				} else if (attrName === "BootstrapMethods" && ownerKind === "class") {
					const numBsm = u2();
					const list = new Array(numBsm);
					for (let b = 0; b < numBsm; b++) {
						const mrefIdx = u2();
						const numArgs = u2();
						const bArgs = new Array(numArgs);
						for (let a = 0; a < numArgs; a++) {
							bArgs[a] = cp[u2()] || null;
						}
						list[b] = {
							methodRef: cp[mrefIdx] || null,
							args: bArgs
						};
					}
					result.bootstrapMethods = list;
				} else if (attrName === "SourceFile" && ownerKind === "class") {
					result.sourceFile = cpUtf8(u2());
				}
				pos = attrEnd;
			}
			return result;
		}

		const fieldCount = u2();
		const fields = new Array(fieldCount);
		for (let i = 0; i < fieldCount; i++) {
			const fFlags = u2();
			const fName = cpUtf8(u2());
			const fDesc = cpUtf8(u2());
			const attrs = readAttributes("field");
			fields[i] = {
				name: fName,
				descriptor: fDesc,
				accessFlags: fFlags,
				constantValue: attrs.constantValue,
				annotations: attrs.annotations
			};
		}

		const methodCount = u2();
		const methods = new Array(methodCount);
		for (let i = 0; i < methodCount; i++) {
			const mFlags = u2();
			const mName = cpUtf8(u2());
			const mDesc = cpUtf8(u2());
			const attrs = readAttributes("method");
			methods[i] = {
				name: mName,
				descriptor: mDesc,
				accessFlags: mFlags,
				code: attrs.code,
				annotations: attrs.annotations
			};
		}

		const classAttrs = readAttributes("class");

		return {
			minorVersion,
			majorVersion,
			accessFlags,
			name,
			superName,
			interfaces,
			cp,
			fields,
			methods,
			bootstrapMethods: classAttrs.bootstrapMethods || [],
			annotations: classAttrs.annotations,
			sourceFile: classAttrs.sourceFile
		};
	}

	// =========================================================================
	// 3. DESCRIPTOR PARSING & NUMERIC / TYPE HELPERS
	// =========================================================================

	const METHOD_DESC_CACHE = new Map();

	function parseMethodDescriptor(desc) {
		let cached = METHOD_DESC_CACHE.get(desc);
		if (cached) return cached;
		const params = [];
		let paramSlots = 0;
		let i = 0;
		const len = desc.length;
		if (i < len && desc.charCodeAt(i) === 40) i++; // '('
		while (i < len && desc.charCodeAt(i) !== 41) { // ')'
			const start = i;
			while (i < len && desc.charCodeAt(i) === 91) i++; // '['
			if (i < len && desc.charCodeAt(i) === 76) { // 'L'
				const semi = desc.indexOf(";", i);
				i = semi >= 0 ? semi + 1 : len;
			} else {
				i++;
			}
			const p = desc.slice(start, i);
			params.push(p);
			paramSlots += (p === "J" || p === "D") ? 2 : 1;
		}
		if (i < len && desc.charCodeAt(i) === 41) i++;
		const ret = desc.slice(i) || "V";
		cached = { params, ret, paramSlots };
		METHOD_DESC_CACHE.set(desc, cached);
		return cached;
	}

	function defaultValueForDesc(desc) {
		if (!desc || desc === "V") return undefined;
		const ch = desc.charCodeAt(0);
		if (ch === 74) return 0n; // 'J'
		if (ch === 76 || ch === 91) return null; // 'L' or '['
		return 0;
	}

	function isCat2Desc(desc) {
		return desc === "J" || desc === "D";
	}

	const LONG_MAX = 9223372036854775807n;
	const LONG_MIN = -9223372036854775808n;

	function f2i(x) {
		if (Number.isNaN(x)) return 0;
		if (x >= 2147483647) return 2147483647;
		if (x <= -2147483648) return -2147483648;
		return Math.trunc(x) | 0;
	}

	function f2l(x) {
		if (Number.isNaN(x)) return 0n;
		if (x >= 9223372036854775808) return LONG_MAX;
		if (x <= -9223372036854775808) return LONG_MIN;
		return BigInt(Math.trunc(x));
	}

	function javaStringHashCode(str) {
		let h = 0;
		for (let i = 0; i < str.length; i++) {
			h = (Math.imul(31, h) + str.charCodeAt(i)) | 0;
		}
		return h;
	}

	function formatJavaFloat(v) {
		if (Number.isNaN(v)) return "NaN";
		if (v === Infinity) return "Infinity";
		if (v === -Infinity) return "-Infinity";
		if (Object.is(v, -0)) return "-0.0";
		if (Number.isInteger(v) && Math.abs(v) < 1e7) return v.toFixed(1);
		return String(v);
	}

	function isReservedFieldKey(k) {
		return k === "__proto__" || k === "constructor" || k === "prototype"
			|| k === "__cls" || k === "__fields" || k === "__samMethodMap"
			|| k === "__jsCallable" || k === "__interfaces" || k === "__isStub"
			|| k === "__ctorRan" || k === "toString" || k === "valueOf";
	}

	const EMPTY_ARGS = Object.freeze([]);
	const STEP_LIMIT = 2000000;

	const ACC_STATIC = 0x0008;
	const ACC_NATIVE = 0x0100;
	const ACC_INTERFACE = 0x0200;
	const ACC_ABSTRACT = 0x0400;

	// =========================================================================
	// 4. BYTECODE COMPILER (SCALAR LOCALS, STRAIGHT-LINE, IC, INTRINSICS)
	// =========================================================================

	const INT_BOX_CACHE = new Array(256);
	for (let _icIdx = -128; _icIdx <= 127; _icIdx++) {
		INT_BOX_CACHE[_icIdx + 128] = { __cls: "java/lang/Integer", _numValue: _icIdx, value: _icIdx };
	}

	const STACK_POOL_DEPTH = 32;
	const STACK_POOL_SIZE = 64;
	const STACK_POOL = new Array(STACK_POOL_DEPTH);
	for (let _spIdx = 0; _spIdx < STACK_POOL_DEPTH; _spIdx++) {
		STACK_POOL[_spIdx] = new Array(STACK_POOL_SIZE);
	}

	function NOOP_INVOKER() { return undefined; }

	function trackIC(rt, ic) {
		if (!ic._inList) {
			ic._inList = true;
			rt._ics.push(ic);
		}
	}

	function makeStaticInvoker(found, nArgs) {
		const fn = found.fn;
		if (fn.__isCompiled) {
			if (nArgs <= 4 && fn.__direct) return fn.__direct;
			return fn;
		}
		const owner = found.owner;
		switch (nArgs) {
			case 0: return function (_r) { return fn.call(owner); };
			case 1: return function (_r, a0) { return fn.call(owner, a0); };
			case 2: return function (_r, a0, a1) { return fn.call(owner, a0, a1); };
			case 3: return function (_r, a0, a1, a2) { return fn.call(owner, a0, a1, a2); };
			case 4: return function (_r, a0, a1, a2, a3) { return fn.call(owner, a0, a1, a2, a3); };
			default: return function (_r, arr) { return fn.apply(owner, arr); };
		}
	}

	function makeInstanceInvoker(found, nArgs) {
		const fn = found.fn;
		if (fn.__isCompiled) {
			if (nArgs <= 3 && fn.__direct) return fn.__direct;
			return function (r, obj, arr) {
				const len = arr.length;
				const ca = new Array(len + 1);
				ca[0] = obj;
				for (let i = 0; i < len; i++) ca[i + 1] = arr[i];
				return fn(r, ca);
			};
		}
		switch (nArgs) {
			case 0: return function (_r, o) { return fn.call(o); };
			case 1: return function (_r, o, a0) { return fn.call(o, a0); };
			case 2: return function (_r, o, a0, a1) { return fn.call(o, a0, a1); };
			case 3: return function (_r, o, a0, a1, a2) { return fn.call(o, a0, a1, a2); };
			default: return function (_r, o, arr) { return fn.apply(o, arr); };
		}
	}

	function makeNativeCtorInvoker(ctor, nArgs) {
		switch (nArgs) {
			case 0: return function (_r, o) { if (!o.__ctorRan) { ctor.call(o); o.__ctorRan = true; } };
			case 1: return function (_r, o, a0) { ctor.call(o, a0); o.__ctorRan = true; };
			case 2: return function (_r, o, a0, a1) { ctor.call(o, a0, a1); o.__ctorRan = true; };
			case 3: return function (_r, o, a0, a1, a2) { ctor.call(o, a0, a1, a2); o.__ctorRan = true; };
			default: return function (_r, o, arr) { ctor.apply(o, arr); o.__ctorRan = true; };
		}
	}

	function matchStaticIntrinsic(canonOwner, canonName, desc, args) {
		if (canonOwner === "java/lang/Math" || canonOwner === "java/lang/StrictMath") {
			const a0 = args[0], a1 = args[1], a2 = args[2];
			if (canonName === "abs") {
				if (desc === "(I)I") return "(" + a0 + " < 0 ? (-" + a0 + " | 0) : (" + a0 + " | 0))";
				if (desc === "(J)J") return "(" + a0 + " < 0n ? BigInt.asIntN(64, -" + a0 + ") : " + a0 + ")";
				if (desc === "(F)F") return "Math.fround(Math.abs(" + a0 + "))";
				if (desc === "(D)D") return "Math.abs(" + a0 + ")";
			} else if (canonName === "min") {
				if (desc === "(II)I" || desc === "(JJ)J") return "(" + a0 + " < " + a1 + " ? " + a0 + " : " + a1 + ")";
				if (desc === "(FF)F") return "Math.fround(Math.min(" + a0 + ", " + a1 + "))";
				if (desc === "(DD)D") return "Math.min(" + a0 + ", " + a1 + ")";
			} else if (canonName === "max") {
				if (desc === "(II)I" || desc === "(JJ)J") return "(" + a0 + " > " + a1 + " ? " + a0 + " : " + a1 + ")";
				if (desc === "(FF)F") return "Math.fround(Math.max(" + a0 + ", " + a1 + "))";
				if (desc === "(DD)D") return "Math.max(" + a0 + ", " + a1 + ")";
			} else if (canonName === "sin" && desc === "(D)D") {
				return "Math.sin(" + a0 + ")";
			} else if (canonName === "cos" && desc === "(D)D") {
				return "Math.cos(" + a0 + ")";
			} else if (canonName === "tan" && desc === "(D)D") {
				return "Math.tan(" + a0 + ")";
			} else if (canonName === "sqrt" && desc === "(D)D") {
				return "Math.sqrt(" + a0 + ")";
			} else if (canonName === "floor" && desc === "(D)D") {
				return "Math.floor(" + a0 + ")";
			} else if (canonName === "ceil" && desc === "(D)D") {
				return "Math.ceil(" + a0 + ")";
			} else if (canonName === "round") {
				if (desc === "(F)I") return "(Number.isNaN(" + a0 + ") ? 0 : H.f2i(Math.floor(" + a0 + " + 0.5)))";
				if (desc === "(D)J") return "(Number.isNaN(" + a0 + ") ? 0n : H.f2l(Math.floor(" + a0 + " + 0.5)))";
			} else if (canonName === "pow" && desc === "(DD)D") {
				return "Math.pow(" + a0 + ", " + a1 + ")";
			} else if (canonName === "atan2" && desc === "(DD)D") {
				return "Math.atan2(" + a0 + ", " + a1 + ")";
			} else if (canonName === "toRadians" && desc === "(D)D") {
				return "(" + a0 + " * 0.017453292519943295)";
			} else if (canonName === "toDegrees" && desc === "(D)D") {
				return "(" + a0 + " * 57.29577951308232)";
			} else if (canonName === "clamp") {
				if (desc === "(III)I") return "((" + a0 + " < " + a1 + " ? " + a1 + " : (" + a0 + " > " + a2 + " ? " + a2 + " : " + a0 + ")) | 0)";
				if (desc === "(JJJ)J") return "(" + a0 + " < " + a1 + " ? " + a1 + " : (" + a0 + " > " + a2 + " ? " + a2 + " : " + a0 + "))";
				if (desc === "(FFF)F") return "Math.fround(" + a0 + " < " + a1 + " ? " + a1 + " : (" + a0 + " > " + a2 + " ? " + a2 + " : " + a0 + "))";
				if (desc === "(DDD)D") return "(" + a0 + " < " + a1 + " ? " + a1 + " : (" + a0 + " > " + a2 + " ? " + a2 + " : " + a0 + "))";
			}
			return null;
		}
		if (canonOwner === "net/minecraft/util/Mth") {
			const a0 = args[0], a1 = args[1], a2 = args[2];
			if (canonName === "clamp") {
				if (desc === "(III)I") return "((" + a0 + " < " + a1 + " ? " + a1 + " : (" + a0 + " > " + a2 + " ? " + a2 + " : " + a0 + ")) | 0)";
				if (desc === "(JJJ)J") return "(" + a0 + " < " + a1 + " ? " + a1 + " : (" + a0 + " > " + a2 + " ? " + a2 + " : " + a0 + "))";
				if (desc === "(FFF)F") return "Math.fround(" + a0 + " < " + a1 + " ? " + a1 + " : (" + a0 + " > " + a2 + " ? " + a2 + " : " + a0 + "))";
				if (desc === "(DDD)D") return "(" + a0 + " < " + a1 + " ? " + a1 + " : (" + a0 + " > " + a2 + " ? " + a2 + " : " + a0 + "))";
			} else if (canonName === "lerp") {
				if (desc === "(FFF)F") return "Math.fround(" + a1 + " + " + a0 + " * (" + a2 + " - " + a1 + "))";
				if (desc === "(DDD)D") return "(" + a1 + " + " + a0 + " * (" + a2 + " - " + a1 + "))";
			} else if (canonName === "floor") {
				if (desc === "(F)I" || desc === "(D)I") return "(Math.floor(" + a0 + ") | 0)";
			} else if (canonName === "ceil") {
				if (desc === "(F)I" || desc === "(D)I") return "(Math.ceil(" + a0 + ") | 0)";
			} else if (canonName === "abs") {
				if (desc === "(I)I") return "(" + a0 + " < 0 ? (-" + a0 + " | 0) : (" + a0 + " | 0))";
				if (desc === "(F)F") return "Math.fround(Math.abs(" + a0 + "))";
				if (desc === "(D)D") return "Math.abs(" + a0 + ")";
			} else if (canonName === "sin" && desc === "(F)F") {
				return "Math.fround(Math.sin(" + a0 + "))";
			} else if (canonName === "cos" && desc === "(F)F") {
				return "Math.fround(Math.cos(" + a0 + "))";
			} else if (canonName === "sqrt") {
				if (desc === "(F)F") return "Math.fround(Math.sqrt(" + a0 + "))";
				if (desc === "(D)D") return "Math.sqrt(" + a0 + ")";
			} else if (canonName === "wrapDegrees") {
				if (desc === "(F)F") return "Math.fround(H.wrapDeg(" + a0 + "))";
				if (desc === "(D)D") return "H.wrapDeg(" + a0 + ")";
				if (desc === "(I)I") return "(Math.trunc(H.wrapDeg(" + a0 + ")) | 0)";
			}
			return null;
		}
		if (canonName === "valueOf") {
			const a0 = args[0];
			if (canonOwner === "java/lang/Integer" && desc === "(I)Ljava/lang/Integer;") {
				return "H.boxInt(" + a0 + ")";
			}
			if (canonOwner === "java/lang/Float" && desc === "(F)Ljava/lang/Float;") {
				return "H.boxFloat(" + a0 + ")";
			}
			if (canonOwner === "java/lang/Double" && desc === "(D)Ljava/lang/Double;") {
				return "H.boxDouble(" + a0 + ")";
			}
			if (canonOwner === "java/lang/Boolean" && desc === "(Z)Ljava/lang/Boolean;") {
				return "(" + a0 + " ? rt._boolTrue : rt._boolFalse)";
			}
		}
		return null;
	}

	function matchUnboxIntrinsic(canonOwner, name, desc) {
		if (name === "intValue" && desc === "()I" && (canonOwner === "java/lang/Integer" || canonOwner === "java/lang/Number")) {
			return { cls: "java/lang/Integer", expr: function (o) { return "(" + o + ".value | 0)"; } };
		}
		if (name === "floatValue" && desc === "()F" && (canonOwner === "java/lang/Float" || canonOwner === "java/lang/Number")) {
			return { cls: "java/lang/Float", expr: function (o) { return "Math.fround(" + o + ".value)"; } };
		}
		if (name === "doubleValue" && desc === "()D" && (canonOwner === "java/lang/Double" || canonOwner === "java/lang/Number")) {
			return { cls: "java/lang/Double", expr: function (o) { return "Number(" + o + ".value)"; } };
		}
		if (name === "booleanValue" && desc === "()Z" && canonOwner === "java/lang/Boolean") {
			return { cls: "java/lang/Boolean", expr: function (o) { return "(" + o + "._boolValue !== undefined ? (" + o + "._boolValue ? 1 : 0) : (" + o + ".value ? 1 : 0))"; } };
		}
		return null;
	}

	function compileJvmMethod(rt, cls, method) {
		const isStatic = (method.accessFlags & ACC_STATIC) !== 0;
		if (!method.code) {
			if ((method.accessFlags & ACC_NATIVE) !== 0) {
				const retDefault = defaultValueForDesc(parseMethodDescriptor(method.descriptor).ret);
				return function nativeUnimplementedStub() {
					return retDefault;
				};
			}
			if ((method.accessFlags & ACC_ABSTRACT) !== 0) {
				return function abstractMethodTrap() {
					throw rt.makeException("java/lang/AbstractMethodError", cls.name + "." + method.name + method.descriptor);
				};
			}
			return function emptyMethod() { return undefined; };
		}

		const code = method.code;
		const b = code.bytes;
		const len = b.length;
		const cp = cls.cp;
		const exTable = code.exceptionTable || [];
		const hasExTable = exTable.length > 0;
		const mDesc = parseMethodDescriptor(method.descriptor);
		const totalArgs = mDesc.params.length + (isStatic ? 0 : 1);
		const useDirectArgs = totalArgs <= 4;

		function s1(p) { const v = b[p]; return v > 127 ? v - 256 : v; }
		function u2(p) { return (b[p] << 8) | b[p + 1]; }
		function s2(p) { const v = (b[p] << 8) | b[p + 1]; return v > 32767 ? v - 65536 : v; }
		function s4(p) { return (b[p] << 24) | (b[p + 1] << 16) | (b[p + 2] << 8) | b[p + 3]; }

		// Pass 1: Decode instructions, leaders, branch presence, and max referenced local slot
		const insns = [];
		const leaders = new Set([0]);
		let hasBranches = false;
		let maxLocalRef = Math.max(code.maxLocals || 0, mDesc.paramSlots + (isStatic ? 0 : 1), 1) - 1;
		for (let i = 0; i < exTable.length; i++) {
			leaders.add(exTable[i].startPc);
			leaders.add(exTable[i].endPc);
			leaders.add(exTable[i].handlerPc);
		}

		let p = 0;
		while (p < len) {
			const pc = p;
			const op = b[p++];
			const insn = { pc, op, nextPc: p };

			switch (op) {
				case 0x10: // bipush
					insn.v = s1(p);
					p += 1;
					break;
				case 0x11: // sipush
					insn.v = s2(p);
					p += 2;
					break;
				case 0x12: // ldc
				case 0xBC: // newarray
					insn.idx = b[p++];
					break;
				case 0x15: case 0x16: case 0x17: case 0x18: case 0x19: // xload
				case 0x36: case 0x37: case 0x38: case 0x39: case 0x3A: // xstore
					insn.idx = b[p++];
					if (insn.idx > maxLocalRef) maxLocalRef = insn.idx;
					break;
				case 0xA9: // ret
					insn.idx = b[p++];
					if (insn.idx > maxLocalRef) maxLocalRef = insn.idx;
					hasBranches = true;
					break;
				case 0x13: case 0x14: // ldc_w, ldc2_w
				case 0xB2: case 0xB3: case 0xB4: case 0xB5: // fields
				case 0xB6: case 0xB7: case 0xB8: // invokes
				case 0xBB: case 0xBD: case 0xC0: case 0xC1: // new, anewarray, checkcast, instanceof
					insn.idx = u2(p);
					p += 2;
					break;
				case 0x84: // iinc
					insn.idx = b[p];
					insn.delta = s1(p + 1);
					if (insn.idx > maxLocalRef) maxLocalRef = insn.idx;
					p += 2;
					break;
				case 0x99: case 0x9A: case 0x9B: case 0x9C: case 0x9D: case 0x9E:
				case 0x9F: case 0xA0: case 0xA1: case 0xA2: case 0xA3: case 0xA4:
				case 0xA5: case 0xA6: case 0xA7: case 0xC6: case 0xC7: { // 2-byte branch
					const target = pc + s2(p);
					p += 2;
					insn.target = target;
					leaders.add(target);
					hasBranches = true;
					break;
				}
				case 0xA8: { // jsr
					const target = pc + s2(p);
					p += 2;
					insn.target = target;
					leaders.add(target);
					leaders.add(p);
					hasBranches = true;
					break;
				}
				case 0xC8: { // goto_w
					const target = pc + s4(p);
					p += 4;
					insn.target = target;
					leaders.add(target);
					hasBranches = true;
					break;
				}
				case 0xC9: { // jsr_w
					const target = pc + s4(p);
					p += 4;
					insn.target = target;
					leaders.add(target);
					leaders.add(p);
					hasBranches = true;
					break;
				}
				case 0xAA: { // tableswitch
					p = (pc + 4) & ~3;
					const defTarget = pc + s4(p); p += 4;
					const low = s4(p); p += 4;
					const high = s4(p); p += 4;
					const count = high - low + 1;
					const targets = new Int32Array(count);
					leaders.add(defTarget);
					for (let k = 0; k < count; k++) {
						const t = pc + s4(p); p += 4;
						targets[k] = t;
						leaders.add(t);
					}
					insn.defTarget = defTarget;
					insn.low = low;
					insn.high = high;
					insn.targets = targets;
					hasBranches = true;
					break;
				}
				case 0xAB: { // lookupswitch
					p = (pc + 4) & ~3;
					const defTarget = pc + s4(p); p += 4;
					const npairs = s4(p); p += 4;
					const pairs = new Array(npairs);
					leaders.add(defTarget);
					for (let k = 0; k < npairs; k++) {
						const match = s4(p); p += 4;
						const t = pc + s4(p); p += 4;
						pairs[k] = [match, t];
						leaders.add(t);
					}
					insn.defTarget = defTarget;
					insn.pairs = pairs;
					hasBranches = true;
					break;
				}
				case 0xB9: // invokeinterface
					insn.idx = u2(p);
					p += 4;
					break;
				case 0xBA: // invokedynamic
					insn.idx = u2(p);
					p += 4;
					break;
				case 0xC4: { // wide
					const subOp = b[p++];
					insn.subOp = subOp;
					insn.idx = u2(p);
					if (insn.idx > maxLocalRef) maxLocalRef = insn.idx;
					p += 2;
					if (subOp === 0x84) {
						insn.delta = s2(p);
						p += 2;
					} else if (subOp === 0xA9) {
						hasBranches = true;
					}
					break;
				}
				case 0xC5: // multianewarray
					insn.idx = u2(p);
					insn.dims = b[p + 2];
					p += 3;
					break;
				default:
					if ((op >= 0x1A && op <= 0x2D) || (op >= 0x3B && op <= 0x4E)) {
						const immSlot = (op >= 0x1A && op <= 0x2D) ? ((op - 0x1A) & 3) : ((op - 0x3B) & 3);
						if (immSlot > maxLocalRef) maxLocalRef = immSlot;
					}
					break;
			}
			insn.nextPc = p;
			insns.push(insn);
		}

		function inTryRange(pc) {
			for (let i = 0; i < exTable.length; i++) {
				if (pc >= exTable[i].startPc && pc < exTable[i].endPc) return true;
			}
			return false;
		}

		const maxStack = Math.max(code.maxStack || 0, 4) + 4;
		const maxLocals = maxLocalRef + 1;
		const useScalarLocals = maxLocals <= 32;
		const isStraightLine = leaders.size <= 1 && !hasExTable && !hasBranches;

		function loc(idx) {
			return useScalarLocals ? ("l" + idx) : ("L[" + idx + "]");
		}

		function emitParamBindings(outLines) {
			if (!useDirectArgs) {
				outLines.push("const _a = args || H.EMPTY_ARGS;");
			}
			let slot = 0;
			let argIdx = 0;
			function argExpr(i) {
				return useDirectArgs ? ("a" + i) : ("_a[" + i + "]");
			}
			if (!isStatic) {
				const ae = argExpr(0);
				outLines.push(loc(0) + " = " + ae + " === undefined ? null : " + ae + ";");
				slot = 1;
				argIdx = 1;
			}
			for (let i = 0; i < mDesc.params.length; i++) {
				const pd = mDesc.params[i];
				const ch = pd.charCodeAt(0);
				const expr = argExpr(argIdx);
				const dst = loc(slot);
				if (ch === 74) { // J
					outLines.push(dst + " = typeof " + expr + " === 'bigint' ? BigInt.asIntN(64, " + expr + ") : BigInt(Math.trunc(" + expr + " || 0));");
					slot += 2;
				} else if (ch === 68) { // D
					outLines.push(dst + " = Number(" + expr + " || 0);");
					slot += 2;
				} else if (ch === 70) { // F
					outLines.push(dst + " = Math.fround(" + expr + " || 0);");
					slot += 1;
				} else if (ch === 90) { // Z
					outLines.push(dst + " = " + expr + " ? 1 : 0;");
					slot += 1;
				} else if (ch === 66) { // B
					outLines.push(dst + " = ((" + expr + " | 0) << 24) >> 24;");
					slot += 1;
				} else if (ch === 83) { // S
					outLines.push(dst + " = ((" + expr + " | 0) << 16) >> 16;");
					slot += 1;
				} else if (ch === 67) { // C
					outLines.push(dst + " = typeof " + expr + " === 'string' ? " + expr + ".charCodeAt(0) : (" + expr + " & 65535);");
					slot += 1;
				} else if (ch === 73) { // I
					outLines.push(dst + " = " + expr + " | 0;");
					slot += 1;
				} else { // L or [
					outLines.push(dst + " = " + expr + " === undefined ? null : " + expr + ";");
					slot += 1;
				}
				argIdx++;
			}
		}

		function emitLocalDecls(outLines) {
			if (useScalarLocals) {
				const decls = new Array(maxLocals);
				for (let i = 0; i < maxLocals; i++) decls[i] = "l" + i + " = 0";
				outLines.push("let " + decls.join(", ") + ";");
			} else {
				outLines.push("const L = new Array(" + maxLocals + ").fill(0);");
			}
		}

		function emitBody(scalarStackMode) {
			const C = [];
			function addConst(obj) {
				const id = C.length;
				C.push(obj);
				return id;
			}

			const usePooledStack = !scalarStackMode && maxStack <= STACK_POOL_SIZE;
			const exTableId = hasExTable ? addConst(exTable) : -1;
			const bodyLines = [];
			let curSp = 0;
			let peakSp = 0;
			let hasBackwardEdge = false;

			function bumpPeak() {
				if (curSp > peakSp) peakSp = curSp;
			}

			function emitRet(valExpr, isCat2Pop) {
				if (scalarStackMode) {
					if (valExpr === null) {
						bodyLines.push("return undefined;");
					} else if (isCat2Pop) {
						const r = "s" + (curSp - 1);
						curSp -= 2;
						bodyLines.push("return " + r + ";");
					} else {
						curSp--;
						bodyLines.push("return s" + curSp + ";");
					}
					return;
				}
				if (usePooledStack) {
					if (valExpr === null) {
						bodyLines.push("H.sd = _sd; return undefined;");
					} else if (isCat2Pop) {
						bodyLines.push("t0 = S[--sp]; sp--; H.sd = _sd; return t0;");
					} else {
						bodyLines.push("t0 = S[--sp]; H.sd = _sd; return t0;");
					}
				} else {
					if (valExpr === null) {
						bodyLines.push("return undefined;");
					} else if (isCat2Pop) {
						bodyLines.push("t0 = S[--sp]; sp--; return t0;");
					} else {
						bodyLines.push("return S[--sp];");
					}
				}
			}

			function emitPush1(expr) {
				if (scalarStackMode) {
					const dst = "s" + curSp;
					curSp++;
					bumpPeak();
					bodyLines.push(dst + " = " + expr + ";");
				} else {
					bodyLines.push("S[sp++] = " + expr + ";");
				}
			}

			function emitPush2(expr) {
				if (scalarStackMode) {
					const dst0 = "s" + curSp;
					const dst1 = "s" + (curSp + 1);
					curSp += 2;
					bumpPeak();
					bodyLines.push("t0 = " + expr + "; " + dst0 + " = 0; " + dst1 + " = t0;");
				} else {
					bodyLines.push("t0 = " + expr + "; S[sp++] = 0; S[sp++] = t0;");
				}
			}

			function emitStoreRetExpr(retDesc, expr) {
				if (retDesc === "V") {
					return expr + ";";
				}
				if (scalarStackMode) {
					if (isCat2Desc(retDesc)) {
						const dst0 = "s" + curSp;
						const dst1 = "s" + (curSp + 1);
						curSp += 2;
						bumpPeak();
						return "t0 = " + expr + "; " + dst0 + " = 0; " + dst1 + " = t0;";
					}
					const dst = "s" + curSp;
					curSp++;
					bumpPeak();
					return dst + " = " + expr + ";";
				}
				if (isCat2Desc(retDesc)) {
					return "t0 = " + expr + "; S[sp++] = 0; S[sp++] = t0;";
				}
				return "S[sp++] = " + expr + ";";
			}

			function popInvokeArgs(params, hasRecv) {
				const n = params.length;
				if (scalarStackMode) {
					const argNames = new Array(n);
					for (let k = n - 1; k >= 0; k--) {
						if (isCat2Desc(params[k])) {
							argNames[k] = "s" + (curSp - 1);
							curSp -= 2;
						} else {
							curSp--;
							argNames[k] = "s" + curSp;
						}
					}
					let recvName = null;
					if (hasRecv) {
						curSp--;
						recvName = "s" + curSp;
					}
					return { setup: "", recv: recvName, args: argNames };
				}
				const parts = [];
				const argNames = new Array(n);
				for (let k = n - 1; k >= 0; k--) {
					const vName = "_p" + k;
					argNames[k] = vName;
					if (isCat2Desc(params[k])) {
						parts.push("const " + vName + " = S[--sp]; sp--;");
					} else {
						parts.push("const " + vName + " = S[--sp];");
					}
				}
				let recvName = null;
				if (hasRecv) {
					parts.push("const _o = S[--sp];");
					recvName = "_o";
				}
				return { setup: parts.join(" "), recv: recvName, args: argNames };
			}

			function emitBin1(fn) {
				if (scalarStackMode) {
					const bVar = "s" + (--curSp);
					const aVar = "s" + (curSp - 1);
					bodyLines.push(aVar + " = " + fn(aVar, bVar) + ";");
				} else {
					bodyLines.push("t1 = S[--sp]; S[sp - 1] = " + fn("S[sp - 1]", "t1") + ";");
				}
			}

			function emitBin2(fn) {
				if (scalarStackMode) {
					const bVar = "s" + (curSp - 1);
					curSp -= 2;
					const aVar = "s" + (curSp - 1);
					bodyLines.push(aVar + " = " + fn(aVar, bVar) + ";");
				} else {
					bodyLines.push("t1 = S[--sp]; sp--; S[sp - 1] = " + fn("S[sp - 1]", "t1") + ";");
				}
			}

			function emitUnaryTop(fn) {
				if (scalarStackMode) {
					const aVar = "s" + (curSp - 1);
					bodyLines.push(aVar + " = " + fn(aVar) + ";");
				} else {
					bodyLines.push("S[sp - 1] = " + fn("S[sp - 1]") + ";");
				}
			}

			function emitJump(curPc, targetPc) {
				if (targetPc <= curPc) {
					hasBackwardEdge = true;
					return "if (++steps > " + STEP_LIMIT + ") throw H.stepLimitError(); pc = " + targetPc + "; continue;";
				}
				return "pc = " + targetPc + "; continue;";
			}

			let prevInTry = false;
			for (let i = 0; i < insns.length; i++) {
				if (scalarStackMode && (curSp < 0 || peakSp > 32)) {
					return null; // Abort scalar-stack attempt and fall back cleanly
				}
				const insn = insns[i];
				const pc = insn.pc;
				const curInTry = hasExTable && inTryRange(pc);
				if (!scalarStackMode) {
					if (leaders.has(pc)) {
						bodyLines.push("case " + pc + ":");
						if (hasExTable) {
							bodyLines.push("epc = " + (curInTry ? pc : -1) + ";");
						}
					} else if (hasExTable && prevInTry && !curInTry) {
						bodyLines.push("epc = -1;");
					}
				}
				prevInTry = curInTry;
				const trackEx = curInTry;
				const op = insn.op;

				switch (op) {
					case 0x00: break; // nop
					case 0x01: emitPush1("null"); break;
					case 0x02: emitPush1("-1"); break;
					case 0x03: emitPush1("0"); break;
					case 0x04: emitPush1("1"); break;
					case 0x05: emitPush1("2"); break;
					case 0x06: emitPush1("3"); break;
					case 0x07: emitPush1("4"); break;
					case 0x08: emitPush1("5"); break;
					case 0x09: emitPush2("0n"); break;
					case 0x0A: emitPush2("1n"); break;
					case 0x0B: emitPush1("0"); break;
					case 0x0C: emitPush1("1"); break;
					case 0x0D: emitPush1("2"); break;
					case 0x0E: emitPush2("0"); break;
					case 0x0F: emitPush2("1"); break;
					case 0x10: // bipush
					case 0x11: // sipush
						emitPush1(String(insn.v));
						break;
					case 0x12: // ldc
					case 0x13: // ldc_w
					case 0x14: { // ldc2_w
						const entry = cp[insn.idx];
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (!entry) {
							emitPush1("null");
						} else if (entry.tag === 3 || entry.tag === 4) {
							const cid = addConst(entry.value);
							emitPush1("C[" + cid + "]");
						} else if (entry.tag === 5 || entry.tag === 6) {
							const cid = addConst(entry.value);
							emitPush2("C[" + cid + "]");
						} else if (entry.tag === 8) {
							const cid = addConst(entry.value);
							emitPush1("C[" + cid + "]");
						} else if (entry.tag === 7) {
							const cid = addConst(entry.name);
							emitPush1("rt.getClassObject(C[" + cid + "])");
						} else if (entry.tag === 15) {
							const cid = addConst(entry);
							emitPush1("rt._resolveMethodHandle(C[" + cid + "])");
						} else if (entry.tag === 16) {
							const cid = addConst(entry.descriptor);
							emitPush1("rt._resolveMethodType(C[" + cid + "])");
						} else if (entry.tag === 17) {
							const cid = addConst({ cls, entry, resolved: false, value: null });
							if (op === 0x14 || isCat2Desc(entry.descriptor)) {
								emitPush2("rt._resolveCondy(C[" + cid + "])");
							} else {
								emitPush1("rt._resolveCondy(C[" + cid + "])");
							}
						} else {
							emitPush1("null");
						}
						break;
					}
					// Loads
					case 0x15: case 0x17: case 0x19:
						emitPush1(loc(insn.idx));
						break;
					case 0x16: case 0x18:
						emitPush2(loc(insn.idx));
						break;
					case 0x1A: case 0x22: case 0x2A: emitPush1(loc(0)); break;
					case 0x1B: case 0x23: case 0x2B: emitPush1(loc(1)); break;
					case 0x1C: case 0x24: case 0x2C: emitPush1(loc(2)); break;
					case 0x1D: case 0x25: case 0x2D: emitPush1(loc(3)); break;
					case 0x1E: case 0x26: emitPush2(loc(0)); break;
					case 0x1F: case 0x27: emitPush2(loc(1)); break;
					case 0x20: case 0x28: emitPush2(loc(2)); break;
					case 0x21: case 0x29: emitPush2(loc(3)); break;

					// Array loads
					case 0x2E: case 0x30: case 0x32: case 0x33: case 0x34: case 0x35: {
						const hName = op === 0x2E ? "iaload" : op === 0x30 ? "faload" : op === 0x32 ? "aaload" : op === 0x33 ? "baload" : op === 0x34 ? "caload" : "saload";
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const iVar = "s" + (--curSp);
							const aVar = "s" + (--curSp);
							emitPush1("H." + hName + "(rt, " + aVar + ", " + iVar + ")");
						} else {
							bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; S[sp++] = H." + hName + "(rt, t0, t1);");
						}
						break;
					}
					case 0x2F: case 0x31: {
						const hName = op === 0x2F ? "laload" : "daload";
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const iVar = "s" + (--curSp);
							const aVar = "s" + (--curSp);
							emitPush2("H." + hName + "(rt, " + aVar + ", " + iVar + ")");
						} else {
							bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; S[sp++] = 0; S[sp++] = H." + hName + "(rt, t0, t1);");
						}
						break;
					}

					// Stores
					case 0x36: case 0x38: case 0x3A:
						if (scalarStackMode) {
							bodyLines.push(loc(insn.idx) + " = s" + (--curSp) + ";");
						} else {
							bodyLines.push(loc(insn.idx) + " = S[--sp];");
						}
						break;
					case 0x37: case 0x39:
						if (scalarStackMode) {
							const vVar = "s" + (curSp - 1);
							curSp -= 2;
							bodyLines.push(loc(insn.idx) + " = " + vVar + ";");
						} else {
							bodyLines.push(loc(insn.idx) + " = S[--sp]; sp--;");
						}
						break;
					case 0x3B: case 0x43: case 0x4B:
					case 0x3C: case 0x44: case 0x4C:
					case 0x3D: case 0x45: case 0x4D:
					case 0x3E: case 0x46: case 0x4E: {
						const slotIdx = (op - 0x3B) & 3;
						if (scalarStackMode) {
							bodyLines.push(loc(slotIdx) + " = s" + (--curSp) + ";");
						} else {
							bodyLines.push(loc(slotIdx) + " = S[--sp];");
						}
						break;
					}
					case 0x3F: case 0x47:
					case 0x40: case 0x48:
					case 0x41: case 0x49:
					case 0x42: case 0x4A: {
						const slotIdx = (op - 0x3F) & 3;
						if (scalarStackMode) {
							const vVar = "s" + (curSp - 1);
							curSp -= 2;
							bodyLines.push(loc(slotIdx) + " = " + vVar + ";");
						} else {
							bodyLines.push(loc(slotIdx) + " = S[--sp]; sp--;");
						}
						break;
					}

					// Array stores
					case 0x4F: case 0x51: case 0x53: case 0x54: case 0x55: case 0x56: {
						const hName = op === 0x4F ? "iastore" : op === 0x51 ? "fastore" : op === 0x53 ? "aastore" : op === 0x54 ? "bastore" : op === 0x55 ? "castore" : "sastore";
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const vVar = "s" + (--curSp);
							const iVar = "s" + (--curSp);
							const aVar = "s" + (--curSp);
							bodyLines.push("H." + hName + "(rt, " + aVar + ", " + iVar + ", " + vVar + ");");
						} else {
							bodyLines.push("t2 = S[--sp]; t1 = S[--sp]; t0 = S[--sp]; H." + hName + "(rt, t0, t1, t2);");
						}
						break;
					}
					case 0x50: case 0x52: {
						const hName = op === 0x50 ? "lastore" : "dastore";
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const vVar = "s" + (curSp - 1);
							curSp -= 2;
							const iVar = "s" + (--curSp);
							const aVar = "s" + (--curSp);
							bodyLines.push("H." + hName + "(rt, " + aVar + ", " + iVar + ", " + vVar + ");");
						} else {
							bodyLines.push("t2 = S[--sp]; sp--; t1 = S[--sp]; t0 = S[--sp]; H." + hName + "(rt, t0, t1, t2);");
						}
						break;
					}

					// Stack manipulation (exact 2-slot cat-2 model)
					case 0x57:
						if (scalarStackMode) curSp--;
						else bodyLines.push("sp--;");
						break;
					case 0x58:
						if (scalarStackMode) curSp -= 2;
						else bodyLines.push("sp -= 2;");
						break;
					case 0x59:
						if (scalarStackMode) {
							bodyLines.push("s" + curSp + " = s" + (curSp - 1) + ";");
							curSp++;
							bumpPeak();
						} else {
							bodyLines.push("S[sp] = S[sp - 1]; sp++;");
						}
						break;
					case 0x5A:
						if (scalarStackMode) {
							const a = curSp - 1, bIdx = curSp - 2;
							bodyLines.push("t0 = s" + a + "; s" + curSp + " = t0; s" + a + " = s" + bIdx + "; s" + bIdx + " = t0;");
							curSp++;
							bumpPeak();
						} else {
							bodyLines.push("t0 = S[sp - 1]; t1 = S[sp - 2]; S[sp - 2] = t0; S[sp - 1] = t1; S[sp] = t0; sp++;");
						}
						break;
					case 0x5B:
						if (scalarStackMode) {
							const a = curSp - 1, bIdx = curSp - 2, cIdx = curSp - 3;
							bodyLines.push("t0 = s" + a + "; s" + curSp + " = t0; s" + a + " = s" + bIdx + "; s" + bIdx + " = s" + cIdx + "; s" + cIdx + " = t0;");
							curSp++;
							bumpPeak();
						} else {
							bodyLines.push("t0 = S[sp - 1]; t1 = S[sp - 2]; t2 = S[sp - 3]; S[sp - 3] = t0; S[sp - 2] = t2; S[sp - 1] = t1; S[sp] = t0; sp++;");
						}
						break;
					case 0x5C:
						if (scalarStackMode) {
							bodyLines.push("s" + curSp + " = s" + (curSp - 2) + "; s" + (curSp + 1) + " = s" + (curSp - 1) + ";");
							curSp += 2;
							bumpPeak();
						} else {
							bodyLines.push("t0 = S[sp - 1]; t1 = S[sp - 2]; S[sp] = t1; S[sp + 1] = t0; sp += 2;");
						}
						break;
					case 0x5D:
						if (scalarStackMode) {
							const a = curSp - 1, bIdx = curSp - 2, cIdx = curSp - 3;
							bodyLines.push("t0 = s" + a + "; t1 = s" + bIdx + "; s" + (curSp + 1) + " = t0; s" + curSp + " = t1; s" + a + " = s" + cIdx + "; s" + bIdx + " = t0; s" + cIdx + " = t1;");
							curSp += 2;
							bumpPeak();
						} else {
							bodyLines.push("t0 = S[sp - 1]; t1 = S[sp - 2]; t2 = S[sp - 3]; S[sp - 3] = t1; S[sp - 2] = t0; S[sp - 1] = t2; S[sp] = t1; S[sp + 1] = t0; sp += 2;");
						}
						break;
					case 0x5E:
						if (scalarStackMode) {
							const a = curSp - 1, bIdx = curSp - 2, cIdx = curSp - 3, dIdx = curSp - 4;
							bodyLines.push("t0 = s" + a + "; t1 = s" + bIdx + "; s" + (curSp + 1) + " = t0; s" + curSp + " = t1; s" + a + " = s" + cIdx + "; s" + bIdx + " = s" + dIdx + "; s" + cIdx + " = t0; s" + dIdx + " = t1;");
							curSp += 2;
							bumpPeak();
						} else {
							bodyLines.push("t0 = S[sp - 1]; t1 = S[sp - 2]; t2 = S[sp - 3]; t3 = S[sp - 4]; S[sp - 4] = t1; S[sp - 3] = t0; S[sp - 2] = t3; S[sp - 1] = t2; S[sp] = t1; S[sp + 1] = t0; sp += 2;");
						}
						break;
					case 0x5F:
						if (scalarStackMode) {
							const a = curSp - 1, bIdx = curSp - 2;
							bodyLines.push("t0 = s" + a + "; s" + a + " = s" + bIdx + "; s" + bIdx + " = t0;");
						} else {
							bodyLines.push("t0 = S[sp - 1]; S[sp - 1] = S[sp - 2]; S[sp - 2] = t0;");
						}
						break;

					// Arithmetic
					case 0x60: emitBin1(function (a, b) { return "(" + a + " + " + b + ") | 0"; }); break;
					case 0x61: emitBin2(function (a, b) { return "BigInt.asIntN(64, " + a + " + " + b + ")"; }); break;
					case 0x62: emitBin1(function (a, b) { return "Math.fround(" + a + " + " + b + ")"; }); break;
					case 0x63: emitBin2(function (a, b) { return a + " + " + b; }); break;

					case 0x64: emitBin1(function (a, b) { return "(" + a + " - " + b + ") | 0"; }); break;
					case 0x65: emitBin2(function (a, b) { return "BigInt.asIntN(64, " + a + " - " + b + ")"; }); break;
					case 0x66: emitBin1(function (a, b) { return "Math.fround(" + a + " - " + b + ")"; }); break;
					case 0x67: emitBin2(function (a, b) { return a + " - " + b; }); break;

					case 0x68: emitBin1(function (a, b) { return "Math.imul(" + a + ", " + b + ")"; }); break;
					case 0x69: emitBin2(function (a, b) { return "BigInt.asIntN(64, " + a + " * " + b + ")"; }); break;
					case 0x6A: emitBin1(function (a, b) { return "Math.fround(" + a + " * " + b + ")"; }); break;
					case 0x6B: emitBin2(function (a, b) { return a + " * " + b; }); break;

					case 0x6C:
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const bVar = "s" + (--curSp);
							const aVar = "s" + (curSp - 1);
							bodyLines.push("if (" + bVar + " === 0) throw rt.makeException('java/lang/ArithmeticException', '/ by zero'); " + aVar + " = (" + aVar + " / " + bVar + ") | 0;");
						} else {
							bodyLines.push("t1 = S[--sp]; if (t1 === 0) throw rt.makeException('java/lang/ArithmeticException', '/ by zero'); S[sp - 1] = (S[sp - 1] / t1) | 0;");
						}
						break;
					case 0x6D:
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const bVar = "s" + (curSp - 1);
							curSp -= 2;
							const aVar = "s" + (curSp - 1);
							bodyLines.push("if (" + bVar + " === 0n) throw rt.makeException('java/lang/ArithmeticException', '/ by zero'); " + aVar + " = BigInt.asIntN(64, " + aVar + " / " + bVar + ");");
						} else {
							bodyLines.push("t1 = S[--sp]; sp--; if (t1 === 0n) throw rt.makeException('java/lang/ArithmeticException', '/ by zero'); S[sp - 1] = BigInt.asIntN(64, S[sp - 1] / t1);");
						}
						break;
					case 0x6E: emitBin1(function (a, b) { return "Math.fround(" + a + " / " + b + ")"; }); break;
					case 0x6F: emitBin2(function (a, b) { return a + " / " + b; }); break;

					case 0x70:
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const bVar = "s" + (--curSp);
							const aVar = "s" + (curSp - 1);
							bodyLines.push("if (" + bVar + " === 0) throw rt.makeException('java/lang/ArithmeticException', '/ by zero'); " + aVar + " = (" + aVar + " % " + bVar + ") | 0;");
						} else {
							bodyLines.push("t1 = S[--sp]; if (t1 === 0) throw rt.makeException('java/lang/ArithmeticException', '/ by zero'); S[sp - 1] = (S[sp - 1] % t1) | 0;");
						}
						break;
					case 0x71:
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const bVar = "s" + (curSp - 1);
							curSp -= 2;
							const aVar = "s" + (curSp - 1);
							bodyLines.push("if (" + bVar + " === 0n) throw rt.makeException('java/lang/ArithmeticException', '/ by zero'); " + aVar + " = " + aVar + " % " + bVar + ";");
						} else {
							bodyLines.push("t1 = S[--sp]; sp--; if (t1 === 0n) throw rt.makeException('java/lang/ArithmeticException', '/ by zero'); S[sp - 1] = S[sp - 1] % t1;");
						}
						break;
					case 0x72: emitBin1(function (a, b) { return "Math.fround(" + a + " % " + b + ")"; }); break;
					case 0x73: emitBin2(function (a, b) { return a + " % " + b; }); break;

					case 0x74: emitUnaryTop(function (a) { return "(-" + a + ") | 0"; }); break;
					case 0x75: emitUnaryTop(function (a) { return "BigInt.asIntN(64, -" + a + ")"; }); break;
					case 0x76: emitUnaryTop(function (a) { return "Math.fround(-" + a + ")"; }); break;
					case 0x77: emitUnaryTop(function (a) { return "-" + a; }); break;

					// Shifts & bitwise
					case 0x78: emitBin1(function (a, b) { return a + " << (" + b + " & 31)"; }); break;
					case 0x79:
						if (scalarStackMode) {
							const bVar = "s" + (--curSp);
							const aVar = "s" + (curSp - 1);
							bodyLines.push(aVar + " = BigInt.asIntN(64, " + aVar + " << BigInt(" + bVar + " & 63));");
						} else {
							bodyLines.push("t1 = BigInt(S[--sp] & 63); S[sp - 1] = BigInt.asIntN(64, S[sp - 1] << t1);");
						}
						break;
					case 0x7A: emitBin1(function (a, b) { return a + " >> (" + b + " & 31)"; }); break;
					case 0x7B:
						if (scalarStackMode) {
							const bVar = "s" + (--curSp);
							const aVar = "s" + (curSp - 1);
							bodyLines.push(aVar + " = " + aVar + " >> BigInt(" + bVar + " & 63);");
						} else {
							bodyLines.push("t1 = BigInt(S[--sp] & 63); S[sp - 1] = S[sp - 1] >> t1;");
						}
						break;
					case 0x7C: emitBin1(function (a, b) { return "(" + a + " >>> (" + b + " & 31)) | 0"; }); break;
					case 0x7D:
						if (scalarStackMode) {
							const bVar = "s" + (--curSp);
							const aVar = "s" + (curSp - 1);
							bodyLines.push(aVar + " = BigInt.asIntN(64, BigInt.asUintN(64, " + aVar + ") >> BigInt(" + bVar + " & 63));");
						} else {
							bodyLines.push("t1 = BigInt(S[--sp] & 63); S[sp - 1] = BigInt.asIntN(64, BigInt.asUintN(64, S[sp - 1]) >> t1);");
						}
						break;

					case 0x7E: emitBin1(function (a, b) { return a + " & " + b; }); break;
					case 0x7F: emitBin2(function (a, b) { return a + " & " + b; }); break;
					case 0x80: emitBin1(function (a, b) { return a + " | " + b; }); break;
					case 0x81: emitBin2(function (a, b) { return a + " | " + b; }); break;
					case 0x82: emitBin1(function (a, b) { return a + " ^ " + b; }); break;
					case 0x83: emitBin2(function (a, b) { return a + " ^ " + b; }); break;

					case 0x84: // iinc
						bodyLines.push(loc(insn.idx) + " = (" + loc(insn.idx) + " + (" + insn.delta + ")) | 0;");
						break;

					// Conversions
					case 0x85: // i2l
						if (scalarStackMode) { const a = "s" + (--curSp); emitPush2("BigInt(" + a + " | 0)"); }
						else bodyLines.push("t0 = S[--sp]; S[sp++] = 0; S[sp++] = BigInt(t0 | 0);");
						break;
					case 0x86: emitUnaryTop(function (a) { return "Math.fround(" + a + ")"; }); break;
					case 0x87: // i2d
						if (scalarStackMode) { const a = "s" + (--curSp); emitPush2(a); }
						else bodyLines.push("t0 = S[--sp]; S[sp++] = 0; S[sp++] = t0;");
						break;
					case 0x88: // l2i
						if (scalarStackMode) { const a = "s" + (curSp - 1); curSp -= 2; emitPush1("Number(BigInt.asIntN(32, " + a + "))"); }
						else bodyLines.push("t0 = S[--sp]; sp--; S[sp++] = Number(BigInt.asIntN(32, t0));");
						break;
					case 0x89: // l2f
						if (scalarStackMode) { const a = "s" + (curSp - 1); curSp -= 2; emitPush1("Math.fround(Number(" + a + "))"); }
						else bodyLines.push("t0 = S[--sp]; sp--; S[sp++] = Math.fround(Number(t0));");
						break;
					case 0x8A: emitUnaryTop(function (a) { return "Number(" + a + ")"; }); break;
					case 0x8B: emitUnaryTop(function (a) { return "H.f2i(" + a + ")"; }); break;
					case 0x8C: // f2l
						if (scalarStackMode) { const a = "s" + (--curSp); emitPush2("H.f2l(" + a + ")"); }
						else bodyLines.push("t0 = S[--sp]; S[sp++] = 0; S[sp++] = H.f2l(t0);");
						break;
					case 0x8D: // f2d
						if (scalarStackMode) { const a = "s" + (--curSp); emitPush2(a); }
						else bodyLines.push("t0 = S[--sp]; S[sp++] = 0; S[sp++] = t0;");
						break;
					case 0x8E: // d2i
						if (scalarStackMode) { const a = "s" + (curSp - 1); curSp -= 2; emitPush1("H.f2i(" + a + ")"); }
						else bodyLines.push("t0 = S[--sp]; sp--; S[sp++] = H.f2i(t0);");
						break;
					case 0x8F: emitUnaryTop(function (a) { return "H.f2l(" + a + ")"; }); break;
					case 0x90: // d2f
						if (scalarStackMode) { const a = "s" + (curSp - 1); curSp -= 2; emitPush1("Math.fround(" + a + ")"); }
						else bodyLines.push("t0 = S[--sp]; sp--; S[sp++] = Math.fround(t0);");
						break;
					case 0x91: emitUnaryTop(function (a) { return "(" + a + " << 24) >> 24"; }); break;
					case 0x92: emitUnaryTop(function (a) { return a + " & 65535"; }); break;
					case 0x93: emitUnaryTop(function (a) { return "(" + a + " << 16) >> 16"; }); break;

					// Comparisons
					case 0x94:
						if (scalarStackMode) { const bVar = "s" + (curSp - 1); curSp -= 2; const aVar = "s" + (curSp - 1); curSp -= 2; emitPush1(aVar + " > " + bVar + " ? 1 : (" + aVar + " < " + bVar + " ? -1 : 0)"); }
						else bodyLines.push("t1 = S[--sp]; sp--; t0 = S[--sp]; sp--; S[sp++] = t0 > t1 ? 1 : (t0 < t1 ? -1 : 0);");
						break;
					case 0x95:
						if (scalarStackMode) { const bVar = "s" + (--curSp); const aVar = "s" + (--curSp); emitPush1(aVar + " > " + bVar + " ? 1 : (" + aVar + " < " + bVar + " ? -1 : (" + aVar + " === " + bVar + " ? 0 : -1))"); }
						else bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; S[sp++] = t0 > t1 ? 1 : (t0 < t1 ? -1 : (t0 === t1 ? 0 : -1));");
						break;
					case 0x96:
						if (scalarStackMode) { const bVar = "s" + (--curSp); const aVar = "s" + (--curSp); emitPush1(aVar + " > " + bVar + " ? 1 : (" + aVar + " < " + bVar + " ? -1 : (" + aVar + " === " + bVar + " ? 0 : 1))"); }
						else bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; S[sp++] = t0 > t1 ? 1 : (t0 < t1 ? -1 : (t0 === t1 ? 0 : 1));");
						break;
					case 0x97:
						if (scalarStackMode) { const bVar = "s" + (curSp - 1); curSp -= 2; const aVar = "s" + (curSp - 1); curSp -= 2; emitPush1(aVar + " > " + bVar + " ? 1 : (" + aVar + " < " + bVar + " ? -1 : (" + aVar + " === " + bVar + " ? 0 : -1))"); }
						else bodyLines.push("t1 = S[--sp]; sp--; t0 = S[--sp]; sp--; S[sp++] = t0 > t1 ? 1 : (t0 < t1 ? -1 : (t0 === t1 ? 0 : -1));");
						break;
					case 0x98:
						if (scalarStackMode) { const bVar = "s" + (curSp - 1); curSp -= 2; const aVar = "s" + (curSp - 1); curSp -= 2; emitPush1(aVar + " > " + bVar + " ? 1 : (" + aVar + " < " + bVar + " ? -1 : (" + aVar + " === " + bVar + " ? 0 : 1))"); }
						else bodyLines.push("t1 = S[--sp]; sp--; t0 = S[--sp]; sp--; S[sp++] = t0 > t1 ? 1 : (t0 < t1 ? -1 : (t0 === t1 ? 0 : 1));");
						break;

					// Branches (backward-edge-only step guard!)
					case 0x99: bodyLines.push("if (S[--sp] === 0) { " + emitJump(pc, insn.target) + " }"); break;
					case 0x9A: bodyLines.push("if (S[--sp] !== 0) { " + emitJump(pc, insn.target) + " }"); break;
					case 0x9B: bodyLines.push("if (S[--sp] < 0) { " + emitJump(pc, insn.target) + " }"); break;
					case 0x9C: bodyLines.push("if (S[--sp] >= 0) { " + emitJump(pc, insn.target) + " }"); break;
					case 0x9D: bodyLines.push("if (S[--sp] > 0) { " + emitJump(pc, insn.target) + " }"); break;
					case 0x9E: bodyLines.push("if (S[--sp] <= 0) { " + emitJump(pc, insn.target) + " }"); break;

					case 0x9F: bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; if (t0 === t1) { " + emitJump(pc, insn.target) + " }"); break;
					case 0xA0: bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; if (t0 !== t1) { " + emitJump(pc, insn.target) + " }"); break;
					case 0xA1: bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; if (t0 < t1) { " + emitJump(pc, insn.target) + " }"); break;
					case 0xA2: bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; if (t0 >= t1) { " + emitJump(pc, insn.target) + " }"); break;
					case 0xA3: bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; if (t0 > t1) { " + emitJump(pc, insn.target) + " }"); break;
					case 0xA4: bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; if (t0 <= t1) { " + emitJump(pc, insn.target) + " }"); break;
					case 0xA5: bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; if (t0 === t1) { " + emitJump(pc, insn.target) + " }"); break;
					case 0xA6: bodyLines.push("t1 = S[--sp]; t0 = S[--sp]; if (t0 !== t1) { " + emitJump(pc, insn.target) + " }"); break;
					case 0xA7:
					case 0xC8:
						bodyLines.push(emitJump(pc, insn.target));
						break;
					case 0xA8:
					case 0xC9:
						bodyLines.push("S[sp++] = " + insn.nextPc + "; " + emitJump(pc, insn.target));
						break;
					case 0xA9:
						hasBackwardEdge = true;
						bodyLines.push("if (++steps > " + STEP_LIMIT + ") throw H.stepLimitError(); pc = " + loc(insn.idx) + "; continue;");
						break;
					case 0xAA: { // tableswitch
						const tid = addConst(insn.targets);
						let anyBack = insn.defTarget <= pc;
						if (!anyBack) {
							for (let k = 0; k < insn.targets.length; k++) {
								if (insn.targets[k] <= pc) { anyBack = true; break; }
							}
						}
						if (anyBack) {
							hasBackwardEdge = true;
							bodyLines.push("{ const _k = S[--sp]; pc = (_k >= " + insn.low + " && _k <= " + insn.high + ") ? C[" + tid + "][_k - " + insn.low + "] : " + insn.defTarget + "; if (pc <= " + pc + " && ++steps > " + STEP_LIMIT + ") throw H.stepLimitError(); continue; }");
						} else {
							bodyLines.push("{ const _k = S[--sp]; pc = (_k >= " + insn.low + " && _k <= " + insn.high + ") ? C[" + tid + "][_k - " + insn.low + "] : " + insn.defTarget + "; continue; }");
						}
						break;
					}
					case 0xAB: { // lookupswitch
						const sw = ["switch (S[--sp]) {"];
						let anyBack = insn.defTarget <= pc;
						for (let k = 0; k < insn.pairs.length; k++) {
							const tgt = insn.pairs[k][1];
							if (tgt <= pc) anyBack = true;
							sw.push("case " + insn.pairs[k][0] + ": pc = " + tgt + "; break;");
						}
						sw.push("default: pc = " + insn.defTarget + "; }");
						if (anyBack) {
							hasBackwardEdge = true;
							sw.push("if (pc <= " + pc + " && ++steps > " + STEP_LIMIT + ") throw H.stepLimitError();");
						}
						sw.push("continue;");
						bodyLines.push(sw.join(" "));
						break;
					}

					// Returns
					case 0xAC: case 0xAE: case 0xB0:
						emitRet("1", false);
						if (scalarStackMode) i = insns.length;
						break;
					case 0xAD: case 0xAF:
						emitRet("2", true);
						if (scalarStackMode) i = insns.length;
						break;
					case 0xB1:
						emitRet(null, false);
						if (scalarStackMode) i = insns.length;
						break;

					// Fields (with per-callsite ICs!)
					case 0xB2: { // getstatic IC
						const ref = cp[insn.idx];
						const cid = addConst({ o: ref.owner, n: ref.name, d: ref.descriptor, sf: null, fn: null, _inList: false });
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (isCat2Desc(ref.descriptor)) {
							if (scalarStackMode) {
								const dst0 = "s" + curSp, dst1 = "s" + (curSp + 1);
								curSp += 2; bumpPeak();
								bodyLines.push("{ const _ic = C[" + cid + "]; t0 = _ic.sf ? _ic.sf[_ic.fn] : H.icGetStatic(rt, _ic); " + dst0 + " = 0; " + dst1 + " = t0; }");
							} else {
								bodyLines.push("{ const _ic = C[" + cid + "]; t0 = _ic.sf ? _ic.sf[_ic.fn] : H.icGetStatic(rt, _ic); S[sp++] = 0; S[sp++] = t0; }");
							}
						} else {
							if (scalarStackMode) {
								const dst = "s" + curSp; curSp++; bumpPeak();
								bodyLines.push("{ const _ic = C[" + cid + "]; " + dst + " = _ic.sf ? _ic.sf[_ic.fn] : H.icGetStatic(rt, _ic); }");
							} else {
								bodyLines.push("{ const _ic = C[" + cid + "]; S[sp++] = _ic.sf ? _ic.sf[_ic.fn] : H.icGetStatic(rt, _ic); }");
							}
						}
						break;
					}
					case 0xB3: { // putstatic IC
						const ref = cp[insn.idx];
						const cid = addConst({ o: ref.owner, n: ref.name, d: ref.descriptor, sf: null, fn: null, _inList: false });
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (isCat2Desc(ref.descriptor)) {
							if (scalarStackMode) {
								const vVar = "s" + (curSp - 1); curSp -= 2;
								bodyLines.push("{ const _ic = C[" + cid + "]; if (_ic.sf) { _ic.sf[_ic.fn] = " + vVar + "; } else { H.icPutStatic(rt, _ic, " + vVar + "); } }");
							} else {
								bodyLines.push("{ const _ic = C[" + cid + "]; t0 = S[--sp]; sp--; if (_ic.sf) { _ic.sf[_ic.fn] = t0; } else { H.icPutStatic(rt, _ic, t0); } }");
							}
						} else {
							if (scalarStackMode) {
								const vVar = "s" + (--curSp);
								bodyLines.push("{ const _ic = C[" + cid + "]; if (_ic.sf) { _ic.sf[_ic.fn] = " + vVar + "; } else { H.icPutStatic(rt, _ic, " + vVar + "); } }");
							} else {
								bodyLines.push("{ const _ic = C[" + cid + "]; t0 = S[--sp]; if (_ic.sf) { _ic.sf[_ic.fn] = t0; } else { H.icPutStatic(rt, _ic, t0); } }");
							}
						}
						break;
					}
					case 0xB4: { // getfield IC + fast direct property read
						const ref = cp[insn.idx];
						const cid = addConst({ o: ref.owner, n: ref.name, d: ref.descriptor, def: defaultValueForDesc(ref.descriptor), cls: "", fn: "", mode: 0, get: null, _inList: false });
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (isCat2Desc(ref.descriptor)) {
							if (scalarStackMode) {
								const oVar = "s" + (--curSp);
								const dst0 = "s" + curSp, dst1 = "s" + (curSp + 1);
								curSp += 2; bumpPeak();
								bodyLines.push("{ const _ic = C[" + cid + "]; t1 = " + oVar + "; t0 = (t1 != null && t1.__cls === _ic.cls && _ic.mode === 1) ? ((t2 = t1[_ic.fn]) !== undefined ? t2 : _ic.def) : H.icGetField(rt, _ic, t1); " + dst0 + " = 0; " + dst1 + " = t0; }");
							} else {
								bodyLines.push("{ const _ic = C[" + cid + "]; t1 = S[--sp]; t0 = (t1 != null && t1.__cls === _ic.cls && _ic.mode === 1) ? ((t2 = t1[_ic.fn]) !== undefined ? t2 : _ic.def) : H.icGetField(rt, _ic, t1); S[sp++] = 0; S[sp++] = t0; }");
							}
						} else {
							if (scalarStackMode) {
								const oVar = "s" + (--curSp);
								const dst = "s" + curSp; curSp++; bumpPeak();
								bodyLines.push("{ const _ic = C[" + cid + "]; t0 = " + oVar + "; " + dst + " = (t0 != null && t0.__cls === _ic.cls && _ic.mode === 1) ? ((t1 = t0[_ic.fn]) !== undefined ? t1 : _ic.def) : H.icGetField(rt, _ic, t0); }");
							} else {
								bodyLines.push("{ const _ic = C[" + cid + "]; t0 = S[--sp]; S[sp++] = (t0 != null && t0.__cls === _ic.cls && _ic.mode === 1) ? ((t1 = t0[_ic.fn]) !== undefined ? t1 : _ic.def) : H.icGetField(rt, _ic, t0); }");
							}
						}
						break;
					}
					case 0xB5: { // putfield IC + fast direct property write
						const ref = cp[insn.idx];
						const cid = addConst({ o: ref.owner, n: ref.name, d: ref.descriptor, cls: "", fn: "", mode: 0, set: null, _inList: false });
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (isCat2Desc(ref.descriptor)) {
							if (scalarStackMode) {
								const vVar = "s" + (curSp - 1); curSp -= 2;
								const oVar = "s" + (--curSp);
								bodyLines.push("{ const _ic = C[" + cid + "]; if (" + oVar + " != null && " + oVar + ".__cls === _ic.cls && _ic.mode === 1) { " + oVar + "[_ic.fn] = " + vVar + "; } else { H.icPutField(rt, _ic, " + oVar + ", " + vVar + "); } }");
							} else {
								bodyLines.push("{ const _ic = C[" + cid + "]; t1 = S[--sp]; sp--; t0 = S[--sp]; if (t0 != null && t0.__cls === _ic.cls && _ic.mode === 1) { t0[_ic.fn] = t1; } else { H.icPutField(rt, _ic, t0, t1); } }");
							}
						} else {
							if (scalarStackMode) {
								const vVar = "s" + (--curSp);
								const oVar = "s" + (--curSp);
								bodyLines.push("{ const _ic = C[" + cid + "]; if (" + oVar + " != null && " + oVar + ".__cls === _ic.cls && _ic.mode === 1) { " + oVar + "[_ic.fn] = " + vVar + "; } else { H.icPutField(rt, _ic, " + oVar + ", " + vVar + "); } }");
							} else {
								bodyLines.push("{ const _ic = C[" + cid + "]; t1 = S[--sp]; t0 = S[--sp]; if (t0 != null && t0.__cls === _ic.cls && _ic.mode === 1) { t0[_ic.fn] = t1; } else { H.icPutField(rt, _ic, t0, t1); } }");
							}
						}
						break;
					}

					// Invocations (with monomorphic ICs + zero-alloc positional args + inlined intrinsics!)
					case 0xB6: // invokevirtual
					case 0xB9: { // invokeinterface
						const ref = cp[insn.idx];
						const pd = parseMethodDescriptor(ref.descriptor);
						const n = pd.params.length;
						const ia = popInvokeArgs(pd.params, true);
						const canonOwner = rt.resolveClassName(ref.owner);
						const icObj = {
							o: ref.owner,
							n: ref.name,
							d: ref.descriptor,
							k: ref.name + ref.descriptor,
							ck: null,
							cls: "",
							isStr: false,
							fn: null,
							nArgs: n,
							isIface: op === 0xB9,
							_inList: false
						};
						const cid = addConst(icObj);
						if (trackEx) bodyLines.push("epc = " + pc + ";");

						const unbox = op === 0xB6 ? matchUnboxIntrinsic(canonOwner, ref.name, ref.descriptor) : null;
						if (unbox) {
							const o = ia.recv;
							const expr = "(" + o + " != null && " + o + ".__cls === '" + unbox.cls + "') ? " + unbox.expr(o) + " : H.icVirt0(rt, C[" + cid + "], " + o + ")";
							if (ia.setup) {
								bodyLines.push("{ " + ia.setup + " " + emitStoreRetExpr(pd.ret, expr) + " }");
							} else {
								bodyLines.push(emitStoreRetExpr(pd.ret, expr));
							}
							break;
						}

						const o = ia.recv;
						let callExpr;
						const guard = "(" + o + " != null && ((" + o + ".__cls === _ic.cls && !" + o + ".__samMethodMap) || (typeof " + o + " === 'string' && _ic.isStr)))";
						if (n <= 3) {
							const argList = n === 0 ? "" : (", " + ia.args.join(", "));
							callExpr = guard + " ? _ic.fn(rt, " + o + argList + ") : H.icVirt" + n + "(rt, _ic, " + o + argList + ")";
							bodyLines.push("{ " + (ia.setup ? ia.setup + " " : "") + "const _ic = C[" + cid + "]; " + emitStoreRetExpr(pd.ret, callExpr) + " }");
						} else {
							const caExpr = "[" + ia.args.join(",") + "]";
							callExpr = guard + " ? _ic.fn(rt, " + o + ", _ca) : H.icVirtN(rt, _ic, " + o + ", _ca)";
							bodyLines.push("{ " + (ia.setup ? ia.setup + " " : "") + "const _ca = " + caExpr + "; const _ic = C[" + cid + "]; " + emitStoreRetExpr(pd.ret, callExpr) + " }");
						}
						break;
					}
					case 0xB7: { // invokespecial IC
						const ref = cp[insn.idx];
						const pd = parseMethodDescriptor(ref.descriptor);
						const n = pd.params.length;
						const ia = popInvokeArgs(pd.params, true);
						if (trackEx) bodyLines.push("epc = " + pc + ";");

						// Fast-path java/lang/Object.<init>()V no-op constructor
						if (ref.owner === "java/lang/Object" && ref.name === "<init>" && ref.descriptor === "()V") {
							const o = ia.recv;
							if (ia.setup) {
								bodyLines.push("{ " + ia.setup + " if (" + o + " == null) throw rt.makeException('java/lang/NullPointerException', 'Cannot invokeSpecial java/lang/Object.<init>()V on null'); }");
							} else {
								bodyLines.push("if (" + o + " == null) throw rt.makeException('java/lang/NullPointerException', 'Cannot invokeSpecial java/lang/Object.<init>()V on null');");
							}
							break;
						}

						const cid = addConst({
							o: ref.owner,
							n: ref.name,
							d: ref.descriptor,
							k: ref.name + ref.descriptor,
							fn: null,
							nArgs: n,
							_inList: false
						});
						const o = ia.recv;
						if (n <= 3) {
							const argList = n === 0 ? "" : (", " + ia.args.join(", "));
							const callExpr = "(" + o + " != null && _ic.fn) ? _ic.fn(rt, " + o + argList + ") : H.icSpecial" + n + "(rt, _ic, " + o + argList + ")";
							bodyLines.push("{ " + (ia.setup ? ia.setup + " " : "") + "const _ic = C[" + cid + "]; " + emitStoreRetExpr(pd.ret, callExpr) + " }");
						} else {
							const caExpr = "[" + ia.args.join(",") + "]";
							const callExpr = "(" + o + " != null && _ic.fn) ? _ic.fn(rt, " + o + ", _ca) : H.icSpecialN(rt, _ic, " + o + ", _ca)";
							bodyLines.push("{ " + (ia.setup ? ia.setup + " " : "") + "const _ca = " + caExpr + "; const _ic = C[" + cid + "]; " + emitStoreRetExpr(pd.ret, callExpr) + " }");
						}
						break;
					}
					case 0xB8: { // invokestatic IC + compile-time inlined intrinsics
						const ref = cp[insn.idx];
						const pd = parseMethodDescriptor(ref.descriptor);
						const n = pd.params.length;
						const ia = popInvokeArgs(pd.params, false);
						const canonOwner = rt.resolveClassName(ref.owner);
						const canonName = rt.resolveMemberAlias(canonOwner, ref.name);

						const intrExpr = matchStaticIntrinsic(canonOwner, canonName, ref.descriptor, ia.args);
						if (intrExpr !== null) {
							if (ia.setup) {
								bodyLines.push("{ " + ia.setup + " " + emitStoreRetExpr(pd.ret, intrExpr) + " }");
							} else {
								bodyLines.push(emitStoreRetExpr(pd.ret, intrExpr));
							}
							break;
						}

						const cid = addConst({
							o: ref.owner,
							n: ref.name,
							d: ref.descriptor,
							k: ref.name + ref.descriptor,
							fn: null,
							nArgs: n,
							_inList: false
						});
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (n <= 4) {
							const argList = n === 0 ? "" : (", " + ia.args.join(", "));
							const callExpr = "_ic.fn ? _ic.fn(rt" + argList + ") : H.icStatic" + n + "(rt, _ic" + argList + ")";
							bodyLines.push("{ " + (ia.setup ? ia.setup + " " : "") + "const _ic = C[" + cid + "]; " + emitStoreRetExpr(pd.ret, callExpr) + " }");
						} else {
							const caExpr = "[" + ia.args.join(",") + "]";
							const callExpr = "_ic.fn ? _ic.fn(rt, _ca) : H.icStaticN(rt, _ic, _ca)";
							bodyLines.push("{ " + (ia.setup ? ia.setup + " " : "") + "const _ca = " + caExpr + "; const _ic = C[" + cid + "]; " + emitStoreRetExpr(pd.ret, callExpr) + " }");
						}
						break;
					}
					case 0xBA: { // invokedynamic
						const indy = cp[insn.idx];
						const pd = parseMethodDescriptor(indy.descriptor);
						const siteObj = {
							cls,
							indy,
							parsedDesc: pd,
							cachedTarget: null,
							_concatPlan: null
						};
						const cid = addConst(siteObj);
						const ia = popInvokeArgs(pd.params, false);
						const arrExpr = pd.params.length === 0 ? "H.EMPTY_ARGS" : ("[" + ia.args.join(",") + "]");
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (ia.setup) {
							bodyLines.push("{ " + ia.setup + " " + emitStoreRetExpr(pd.ret, "rt._invokeDynamic(C[" + cid + "], " + arrExpr + ")") + " }");
						} else {
							bodyLines.push(emitStoreRetExpr(pd.ret, "rt._invokeDynamic(C[" + cid + "], " + arrExpr + ")"));
						}
						break;
					}

					// Object & array creation, checks, exceptions, monitors
					case 0xBB: { // new IC
						const cRef = cp[insn.idx];
						const cid = addConst({ o: cRef.name, alloc: null, _inList: false });
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const dst = "s" + curSp; curSp++; bumpPeak();
							bodyLines.push("{ const _ic = C[" + cid + "]; " + dst + " = _ic.alloc ? _ic.alloc() : H.icNew(rt, _ic); }");
						} else {
							bodyLines.push("{ const _ic = C[" + cid + "]; S[sp++] = _ic.alloc ? _ic.alloc() : H.icNew(rt, _ic); }");
						}
						break;
					}
					case 0xBC: // newarray
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const cnt = "s" + (--curSp);
							emitPush1("rt._newPrimitiveArray(" + insn.idx + ", " + cnt + ")");
						} else {
							bodyLines.push("t0 = S[--sp]; S[sp++] = rt._newPrimitiveArray(" + insn.idx + ", t0);");
						}
						break;
					case 0xBD: { // anewarray
						const cRef = cp[insn.idx];
						const cid = addConst(cRef.name);
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const cnt = "s" + (--curSp);
							emitPush1("rt._newObjectArray(C[" + cid + "], " + cnt + ")");
						} else {
							bodyLines.push("t0 = S[--sp]; S[sp++] = rt._newObjectArray(C[" + cid + "], t0);");
						}
						break;
					}
					case 0xBE: // arraylength
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const aVar = "s" + (--curSp);
							const dst = "s" + curSp; curSp++; bumpPeak();
							bodyLines.push("if (" + aVar + " == null) throw rt.makeException('java/lang/NullPointerException', 'arraylength on null'); " + dst + " = " + aVar + ".length | 0;");
						} else {
							bodyLines.push("t0 = S[--sp]; if (t0 == null) throw rt.makeException('java/lang/NullPointerException', 'arraylength on null'); S[sp++] = t0.length | 0;");
						}
						break;
					case 0xBF: // athrow
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const eVar = "s" + (--curSp);
							bodyLines.push("if (" + eVar + " == null) throw rt.makeException('java/lang/NullPointerException', 'athrow null'); throw " + eVar + ";");
							i = insns.length;
						} else {
							bodyLines.push("t0 = S[--sp]; if (t0 == null) throw rt.makeException('java/lang/NullPointerException', 'athrow null'); throw t0;");
						}
						break;
					case 0xC0: { // checkcast
						const cRef = cp[insn.idx];
						const cid = addConst(cRef.name);
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const topVar = "s" + (curSp - 1);
							bodyLines.push("if (" + topVar + " != null && !rt.isInstanceOf(" + topVar + ", C[" + cid + "])) throw rt.makeException('java/lang/ClassCastException', rt._getClassName(" + topVar + ") + ' cannot be cast to ' + C[" + cid + "]);");
						} else {
							bodyLines.push("t0 = S[sp - 1]; if (t0 != null && !rt.isInstanceOf(t0, C[" + cid + "])) throw rt.makeException('java/lang/ClassCastException', rt._getClassName(t0) + ' cannot be cast to ' + C[" + cid + "]);");
						}
						break;
					}
					case 0xC1: { // instanceof
						const cRef = cp[insn.idx];
						const cid = addConst(cRef.name);
						if (scalarStackMode) {
							const oVar = "s" + (--curSp);
							emitPush1("rt.isInstanceOf(" + oVar + ", C[" + cid + "]) ? 1 : 0");
						} else {
							bodyLines.push("t0 = S[--sp]; S[sp++] = rt.isInstanceOf(t0, C[" + cid + "]) ? 1 : 0;");
						}
						break;
					}
					case 0xC2: // monitorenter
					case 0xC3: // monitorexit
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const mVar = "s" + (--curSp);
							bodyLines.push("if (" + mVar + " == null) throw rt.makeException('java/lang/NullPointerException', 'monitor on null');");
						} else {
							bodyLines.push("t0 = S[--sp]; if (t0 == null) throw rt.makeException('java/lang/NullPointerException', 'monitor on null');");
						}
						break;
					case 0xC4: { // wide
						const sub = insn.subOp;
						if (sub === 0x15 || sub === 0x17 || sub === 0x19) {
							emitPush1(loc(insn.idx));
						} else if (sub === 0x16 || sub === 0x18) {
							emitPush2(loc(insn.idx));
						} else if (sub === 0x36 || sub === 0x38 || sub === 0x3A) {
							if (scalarStackMode) bodyLines.push(loc(insn.idx) + " = s" + (--curSp) + ";");
							else bodyLines.push(loc(insn.idx) + " = S[--sp];");
						} else if (sub === 0x37 || sub === 0x39) {
							if (scalarStackMode) { const vVar = "s" + (curSp - 1); curSp -= 2; bodyLines.push(loc(insn.idx) + " = " + vVar + ";"); }
							else bodyLines.push(loc(insn.idx) + " = S[--sp]; sp--;");
						} else if (sub === 0x84) {
							bodyLines.push(loc(insn.idx) + " = (" + loc(insn.idx) + " + (" + insn.delta + ")) | 0;");
						} else if (sub === 0xA9) {
							hasBackwardEdge = true;
							bodyLines.push("if (++steps > " + STEP_LIMIT + ") throw H.stepLimitError(); pc = " + loc(insn.idx) + "; continue;");
						}
						break;
					}
					case 0xC5: { // multianewarray
						const cRef = cp[insn.idx];
						const cid = addConst(cRef.name);
						const d = insn.dims;
						if (trackEx) bodyLines.push("epc = " + pc + ";");
						if (scalarStackMode) {
							const dVars = new Array(d);
							for (let k = d - 1; k >= 0; k--) dVars[k] = "s" + (--curSp);
							emitPush1("rt.createMultiArray(C[" + cid + "], [" + dVars.join(",") + "], 0)");
						} else {
							const dParts = ["{ const _dims = new Array(" + d + ");"];
							for (let k = d - 1; k >= 0; k--) {
								dParts.push("_dims[" + k + "] = S[--sp];");
							}
							dParts.push("S[sp++] = rt.createMultiArray(C[" + cid + "], _dims, 0); }");
							bodyLines.push(dParts.join(" "));
						}
						break;
					}
					case 0xC6: bodyLines.push("if (S[--sp] == null) { " + emitJump(pc, insn.target) + " }"); break;
					case 0xC7: bodyLines.push("if (S[--sp] != null) { " + emitJump(pc, insn.target) + " }"); break;
					default:
						throw new Error("Unsupported JVM opcode 0x" + op.toString(16) + " at pc=" + pc + " in " + cls.name + "." + method.name);
				}
			}

			if (scalarStackMode && (curSp < 0 || peakSp > 32)) {
				return null;
			}

			// Assemble final prologue + body + epilogue
			const out = [];
			const hoistCount = Math.min(C.length, 24);
			if (hoistCount > 0) {
				const cDecls = new Array(hoistCount);
				for (let ci = 0; ci < hoistCount; ci++) cDecls[ci] = "_c" + ci + " = C[" + ci + "]";
				out.push("const " + cDecls.join(", ") + ";");
			}
			const cRefRe = hoistCount > 0 ? /C\[(\d+)\]/g : null;
			function replaceHoistedC(line) {
				if (!cRefRe) return line;
				return line.replace(cRefRe, function (m, idxStr) {
					const idx = Number(idxStr);
					return idx < hoistCount ? ("_c" + idx) : m;
				});
			}

			if (scalarStackMode) {
				emitLocalDecls(out);
				if (peakSp > 0) {
					const sDecls = new Array(peakSp);
					for (let s = 0; s < peakSp; s++) sDecls[s] = "s" + s;
					out.push("let " + sDecls.join(", ") + ", t0, t1, t2;");
				} else {
					out.push("let t0, t1, t2;");
				}
				emitParamBindings(out);
				for (let k = 0; k < bodyLines.length; k++) out.push(replaceHoistedC(bodyLines[k]));
				out.push("return undefined;");
			} else {
				if (usePooledStack) {
					out.push("const _sd = H.sd;");
					out.push("const S = _sd < " + STACK_POOL_DEPTH + " ? H.SP[_sd] : new Array(" + maxStack + ");");
					out.push("if (_sd < " + STACK_POOL_DEPTH + ") H.sd = _sd + 1;");
				} else {
					if (hasExTable) out.push("const _sd = H.sd;");
					out.push("const S = new Array(" + maxStack + ");");
				}
				emitLocalDecls(out);
				const hasBackwardHandler = hasExTable && exTable.some(function (he) { return he.handlerPc < he.endPc; });
				const needSteps = hasBackwardEdge || hasBackwardHandler;
				out.push("let sp = 0, pc = 0" + (needSteps ? ", steps = 0" : "") + ", t0, t1, t2, t3;");
				if (hasExTable) {
					out.push("let epc = -1;");
					out.push("const EX = " + (exTableId < hoistCount ? ("_c" + exTableId) : ("C[" + exTableId + "]")) + ";");
				}
				emitParamBindings(out);
				out.push("while (true) {");
				if (hasExTable) {
					out.push("try {");
				}
				out.push("switch (pc) {");
				for (let k = 0; k < bodyLines.length; k++) out.push(replaceHoistedC(bodyLines[k]));
				out.push(usePooledStack ? "default: H.sd = _sd; return undefined;" : "default: return undefined;");
				out.push("}");
				out.push(usePooledStack ? "H.sd = _sd; return undefined;" : "return undefined;");
				if (hasExTable) {
					out.push("} catch (_ex) {");
					out.push("if (_ex && _ex.__jvmStepLimit) { H.sd = _sd; throw _ex; }");
					out.push("const _jex = rt.wrapException(_ex);");
					out.push("let _handled = false;");
					out.push("for (let _ei = 0; _ei < EX.length; _ei++) {");
					out.push("const _he = EX[_ei];");
					out.push("if (epc >= _he.startPc && epc < _he.endPc && (_he.catchType === null || rt.isInstanceOf(_jex, _he.catchType))) {");
					if (hasBackwardHandler) {
						out.push("if (_he.handlerPc <= epc && ++steps > " + STEP_LIMIT + ") { H.sd = _sd; throw H.stepLimitError(); }");
					}
					out.push("sp = 0; S[sp++] = _jex; pc = _he.handlerPc; _handled = true; break;");
					out.push("}");
					out.push("}");
					if (usePooledStack) {
						out.push("if (_handled) { if (_sd < " + STACK_POOL_DEPTH + ") H.sd = _sd + 1; continue; }");
					} else {
						out.push("if (_handled) { H.sd = _sd; continue; }");
					}
					out.push("H.sd = _sd; throw _ex;");
					out.push("}");
				}
				out.push("}");
			}
			return { C, body: out.join("\n") };
		}

		let compiled = null;
		if (isStraightLine && maxStack <= 32) {
			compiled = emitBody(true);
		}
		if (!compiled) {
			compiled = emitBody(false);
		}

		const C = compiled.C;
		const body = compiled.body;
		let boundFn;
		if (useDirectArgs) {
			const directFactory = new Function("H", "C", "return function compiledDirect(rt, a0, a1, a2, a3) {\n" + body + "\n};");
			const directFn = directFactory(COMPILED_HELPERS, C);
			if (totalArgs === 0) {
				boundFn = function compiledJvmFunction0(runtimeArg) {
					const savedSd = COMPILED_HELPERS.sd;
					try { return directFn(runtimeArg || rt); }
					catch (e) { COMPILED_HELPERS.sd = savedSd; throw e; }
				};
			} else if (totalArgs === 1) {
				boundFn = function compiledJvmFunction1(runtimeArg, callArgs) {
					const a = callArgs || EMPTY_ARGS;
					const savedSd = COMPILED_HELPERS.sd;
					try { return directFn(runtimeArg || rt, a[0]); }
					catch (e) { COMPILED_HELPERS.sd = savedSd; throw e; }
				};
			} else if (totalArgs === 2) {
				boundFn = function compiledJvmFunction2(runtimeArg, callArgs) {
					const a = callArgs || EMPTY_ARGS;
					const savedSd = COMPILED_HELPERS.sd;
					try { return directFn(runtimeArg || rt, a[0], a[1]); }
					catch (e) { COMPILED_HELPERS.sd = savedSd; throw e; }
				};
			} else if (totalArgs === 3) {
				boundFn = function compiledJvmFunction3(runtimeArg, callArgs) {
					const a = callArgs || EMPTY_ARGS;
					const savedSd = COMPILED_HELPERS.sd;
					try { return directFn(runtimeArg || rt, a[0], a[1], a[2]); }
					catch (e) { COMPILED_HELPERS.sd = savedSd; throw e; }
				};
			} else {
				boundFn = function compiledJvmFunction4(runtimeArg, callArgs) {
					const a = callArgs || EMPTY_ARGS;
					const savedSd = COMPILED_HELPERS.sd;
					try { return directFn(runtimeArg || rt, a[0], a[1], a[2], a[3]); }
					catch (e) { COMPILED_HELPERS.sd = savedSd; throw e; }
				};
			}
			boundFn.__direct = directFn;
		} else {
			const arrayFactory = new Function("H", "C", "return function compiledArgs(rt, args) {\n" + body + "\n};");
			const arrayFn = arrayFactory(COMPILED_HELPERS, C);
			boundFn = function compiledJvmFunctionN(runtimeArg, callArgs) {
				const savedSd = COMPILED_HELPERS.sd;
				try { return arrayFn(runtimeArg || rt, callArgs); }
				catch (e) { COMPILED_HELPERS.sd = savedSd; throw e; }
			};
			boundFn.__direct = null;
		}
		boundFn.__isCompiled = true;
		boundFn.__clsName = cls.name;
		boundFn.__methodName = method.name;
		boundFn.__descriptor = method.descriptor;
		rt.compiledMethodCount++;
		return boundFn;
	}

	function checkArrayAccess(rt, arr, idx) {
		if (arr == null) {
			throw rt.makeException("java/lang/NullPointerException", "Array access on null");
		}
		const i = idx | 0;
		if (i < 0 || i >= arr.length) {
			throw rt.makeException("java/lang/ArrayIndexOutOfBoundsException", "Index " + i + " out of bounds for length " + arr.length);
		}
		return i;
	}

	function resolveIcStatic(rt, ic) {
		const cls = rt.loadClass(ic.o);
		const rName = rt.resolveMemberAlias(ic.o, ic.n);
		const found = rt._lookupMethodHierarchy(cls, rName, ic.d, false, rName === ic.n ? ic.k : null)
			|| (rName !== ic.n ? rt._lookupMethodHierarchy(cls, ic.n, ic.d, false, ic.k) : null);
		if (found) {
			const invoker = makeStaticInvoker(found, ic.nArgs);
			if (cls.initState === 2) {
				ic.fn = invoker;
				trackIC(rt, ic);
			}
			return invoker;
		}
		if (cls.isStub || cls.isNative) {
			return null;
		}
		throw rt.makeException("java/lang/NoSuchMethodError", cls.name + "." + rName + ic.d);
	}

	function resolveIcSpecial(rt, ic, obj) {
		if (obj == null) {
			throw rt.makeException("java/lang/NullPointerException", "Cannot invokeSpecial " + ic.o + "." + ic.n + ic.d + " on null");
		}
		const cls = rt._resolveClassNoInit(ic.o);
		const rName = rt.resolveMemberAlias(ic.o, ic.n);
		let found;
		if (rName === "<init>") {
			found = rt._getCompiledOrNativeMethod(cls, "<init>", ic.d, null, ic.n === "<init>" ? ic.k : null);
		} else {
			found = rt._lookupMethodHierarchy(cls, rName, ic.d, cls.isInterface, rName === ic.n ? ic.k : null)
				|| (rName !== ic.n ? rt._lookupMethodHierarchy(cls, ic.n, ic.d, cls.isInterface, ic.k) : null);
		}
		if (found) {
			const invoker = makeInstanceInvoker(found, ic.nArgs);
			ic.fn = invoker;
			trackIC(rt, ic);
			return invoker;
		}
		if (rName === "<init>" && cls.isNative) {
			const invoker = cls.ctor ? makeNativeCtorInvoker(cls.ctor, ic.nArgs) : NOOP_INVOKER;
			ic.fn = invoker;
			trackIC(rt, ic);
			return invoker;
		}
		if (cls.isStub || cls.isNative) {
			return null;
		}
		throw rt.makeException("java/lang/NoSuchMethodError", cls.name + "." + rName + ic.d);
	}

	function resolveIcVirt(rt, ic, obj) {
		if (obj == null) {
			throw rt.makeException(
				"java/lang/NullPointerException",
				ic.isIface ? ("Cannot invokeInterface " + ic.o + "." + ic.n + ic.d + " on null") : ("Cannot invoke " + ic.n + ic.d + " on null")
			);
		}
		const clsName = rt._getClassName(obj);
		const cls = rt._resolveClassNoInit(clsName);
		const rName = ic.o ? rt.resolveMemberAlias(ic.o, ic.n) : rt.resolveMemberAlias(clsName, ic.n);
		let found = rt._lookupMethodHierarchy(cls, rName, ic.d, true, rName === ic.n ? ic.k : null)
			|| (rName !== ic.n ? rt._lookupMethodHierarchy(cls, ic.n, ic.d, true, ic.k) : null);
		if (!found && ic.isIface) {
			found = rt._lookupMethodHierarchy(rt._resolveClassNoInit(ic.o), rName, ic.d, true, rName === ic.n ? ic.k : null);
		}
		if (found) {
			const invoker = makeInstanceInvoker(found, ic.nArgs);
			if (typeof obj === "string") {
				ic.cls = "java/lang/String";
				ic.isStr = true;
				ic.fn = invoker;
				trackIC(rt, ic);
			} else if (typeof obj.__cls === "string" && obj.__cls.length > 0 && !obj.__samMethodMap) {
				ic.cls = obj.__cls;
				ic.isStr = cls.name === "java/lang/String";
				ic.fn = invoker;
				trackIC(rt, ic);
			}
			return { kind: 1, invoker };
		}
		if (typeof obj === "object" && typeof obj[rName] === "function") {
			return { kind: 2, jsFn: obj[rName] };
		}
		if (cls.isStub || cls.isNative || (ic.o && rt._resolveClassNoInit(ic.o).isStub)) {
			return { kind: 3, val: rt._stubReturn(obj, cls.name, rName, ic.d) };
		}
		throw rt.makeException(
			ic.isIface ? "java/lang/AbstractMethodError" : "java/lang/NoSuchMethodError",
			(ic.isIface ? ic.o : cls.name) + "." + rName + ic.d
		);
	}

	function lookupLambdaFn(rt, ic, obj) {
		const map = obj.__samMethodMap;
		let fn = map[ic.k];
		if (fn) return fn;
		if (ic.ck === null) {
			const cd = rt.canonicalizeDescriptor(ic.d);
			ic.ck = cd === ic.d ? "" : (ic.n + cd);
		}
		if (ic.ck) {
			fn = map[ic.ck];
			if (fn) return fn;
		}
		return map[ic.n] || null;
	}

	const COMPILED_HELPERS = {
		EMPTY_ARGS,
		SP: STACK_POOL,
		sd: 0,
		f2i,
		f2l,
		stepLimitError() {
			const e = new Error("JVM step limit exceeded");
			e.__jvmStepLimit = true;
			return e;
		},
		wrapDeg(deg) {
			let d = deg % 360.0;
			if (d >= 180.0) d -= 360.0;
			if (d < -180.0) d += 360.0;
			return d;
		},
		boxInt(v) {
			const i = v | 0;
			return (i >= -128 && i <= 127) ? INT_BOX_CACHE[i + 128] : { __cls: "java/lang/Integer", _numValue: i, value: i };
		},
		boxFloat(v) {
			const f = Math.fround(v);
			return { __cls: "java/lang/Float", _numValue: f, value: f };
		},
		boxDouble(v) {
			const d = Number(v);
			return { __cls: "java/lang/Double", _numValue: d, value: d };
		},
		icStatic0(rt, ic) {
			const inv = resolveIcStatic(rt, ic);
			return inv ? inv(rt) : rt._stubReturn(null, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icStatic1(rt, ic, a0) {
			const inv = resolveIcStatic(rt, ic);
			return inv ? inv(rt, a0) : rt._stubReturn(null, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icStatic2(rt, ic, a0, a1) {
			const inv = resolveIcStatic(rt, ic);
			return inv ? inv(rt, a0, a1) : rt._stubReturn(null, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icStatic3(rt, ic, a0, a1, a2) {
			const inv = resolveIcStatic(rt, ic);
			return inv ? inv(rt, a0, a1, a2) : rt._stubReturn(null, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icStatic4(rt, ic, a0, a1, a2, a3) {
			const inv = resolveIcStatic(rt, ic);
			return inv ? inv(rt, a0, a1, a2, a3) : rt._stubReturn(null, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icStaticN(rt, ic, arr) {
			const inv = resolveIcStatic(rt, ic);
			return inv ? inv(rt, arr) : rt._stubReturn(null, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icSpecial0(rt, ic, obj) {
			const inv = resolveIcSpecial(rt, ic, obj);
			return inv ? inv(rt, obj) : rt._stubReturn(obj, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icSpecial1(rt, ic, obj, a0) {
			const inv = resolveIcSpecial(rt, ic, obj);
			return inv ? inv(rt, obj, a0) : rt._stubReturn(obj, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icSpecial2(rt, ic, obj, a0, a1) {
			const inv = resolveIcSpecial(rt, ic, obj);
			return inv ? inv(rt, obj, a0, a1) : rt._stubReturn(obj, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icSpecial3(rt, ic, obj, a0, a1, a2) {
			const inv = resolveIcSpecial(rt, ic, obj);
			return inv ? inv(rt, obj, a0, a1, a2) : rt._stubReturn(obj, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icSpecialN(rt, ic, obj, arr) {
			const inv = resolveIcSpecial(rt, ic, obj);
			return inv ? inv(rt, obj, arr) : rt._stubReturn(obj, rt.resolveClassName(ic.o), rt.resolveMemberAlias(ic.o, ic.n), ic.d);
		},
		icVirt0(rt, ic, obj) {
			if (obj != null && obj.__samMethodMap) {
				const lfn = lookupLambdaFn(rt, ic, obj);
				if (lfn) return lfn.call(obj);
			}
			const r = resolveIcVirt(rt, ic, obj);
			if (r.kind === 1) return r.invoker(rt, obj);
			if (r.kind === 2) return r.jsFn.call(obj);
			return r.val;
		},
		icVirt1(rt, ic, obj, a0) {
			if (obj != null && obj.__samMethodMap) {
				const lfn = lookupLambdaFn(rt, ic, obj);
				if (lfn) return lfn.call(obj, a0);
			}
			const r = resolveIcVirt(rt, ic, obj);
			if (r.kind === 1) return r.invoker(rt, obj, a0);
			if (r.kind === 2) return r.jsFn.call(obj, a0);
			return r.val;
		},
		icVirt2(rt, ic, obj, a0, a1) {
			if (obj != null && obj.__samMethodMap) {
				const lfn = lookupLambdaFn(rt, ic, obj);
				if (lfn) return lfn.call(obj, a0, a1);
			}
			const r = resolveIcVirt(rt, ic, obj);
			if (r.kind === 1) return r.invoker(rt, obj, a0, a1);
			if (r.kind === 2) return r.jsFn.call(obj, a0, a1);
			return r.val;
		},
		icVirt3(rt, ic, obj, a0, a1, a2) {
			if (obj != null && obj.__samMethodMap) {
				const lfn = lookupLambdaFn(rt, ic, obj);
				if (lfn) return lfn.call(obj, a0, a1, a2);
			}
			const r = resolveIcVirt(rt, ic, obj);
			if (r.kind === 1) return r.invoker(rt, obj, a0, a1, a2);
			if (r.kind === 2) return r.jsFn.call(obj, a0, a1, a2);
			return r.val;
		},
		icVirtN(rt, ic, obj, arr) {
			if (obj != null && obj.__samMethodMap) {
				const lfn = lookupLambdaFn(rt, ic, obj);
				if (lfn) return lfn.apply(obj, arr);
			}
			const r = resolveIcVirt(rt, ic, obj);
			if (r.kind === 1) return r.invoker(rt, obj, arr);
			if (r.kind === 2) return r.jsFn.apply(obj, arr);
			return r.val;
		},
		icGetStatic(rt, ic) {
			const cls = rt.loadClass(ic.o);
			const rName = rt.resolveMemberAlias(ic.o, ic.n);
			let cur = cls;
			let depth = 0;
			while (cur && depth++ < 64) {
				if (Object.prototype.hasOwnProperty.call(cur.staticFields, rName)) {
					if (cls.initState === 2 && cur.initState === 2) {
						ic.sf = cur.staticFields;
						ic.fn = rName;
						trackIC(rt, ic);
					}
					return cur.staticFields[rName];
				}
				if (rName !== ic.n && Object.prototype.hasOwnProperty.call(cur.staticFields, ic.n)) {
					if (cls.initState === 2 && cur.initState === 2) {
						ic.sf = cur.staticFields;
						ic.fn = ic.n;
						trackIC(rt, ic);
					}
					return cur.staticFields[ic.n];
				}
				if (cur.interfaces && cur.interfaces.length > 0) {
					for (let i = 0; i < cur.interfaces.length; i++) {
						const icls = rt.loadClass(cur.interfaces[i]);
						if (Object.prototype.hasOwnProperty.call(icls.staticFields, rName)) {
							if (cls.initState === 2 && icls.initState === 2) {
								ic.sf = icls.staticFields;
								ic.fn = rName;
								trackIC(rt, ic);
							}
							return icls.staticFields[rName];
						}
						if (rName !== ic.n && Object.prototype.hasOwnProperty.call(icls.staticFields, ic.n)) {
							if (cls.initState === 2 && icls.initState === 2) {
								ic.sf = icls.staticFields;
								ic.fn = ic.n;
								trackIC(rt, ic);
							}
							return icls.staticFields[ic.n];
						}
					}
				}
				cur = cur.superName ? rt.loadClass(cur.superName) : null;
			}
			return defaultValueForDesc(ic.d);
		},
		icPutStatic(rt, ic, val) {
			const cls = rt.loadClass(ic.o);
			const rName = rt.resolveMemberAlias(ic.o, ic.n);
			let cur = cls;
			let depth = 0;
			while (cur && depth++ < 64) {
				if ((cur.staticFieldSet && cur.staticFieldSet.has(rName)) || Object.prototype.hasOwnProperty.call(cur.staticFields, rName)) {
					cur.staticFields[rName] = val;
					if (cls.initState === 2 && cur.initState === 2) {
						ic.sf = cur.staticFields;
						ic.fn = rName;
						trackIC(rt, ic);
					}
					return;
				}
				cur = cur.superName ? rt.loadClass(cur.superName) : null;
			}
			cls.staticFields[rName] = val;
			if (cls.initState === 2) {
				ic.sf = cls.staticFields;
				ic.fn = rName;
				trackIC(rt, ic);
			}
		},
		icGetField(rt, ic, obj) {
			if (obj == null) {
				throw rt.makeException("java/lang/NullPointerException", "Cannot read field " + ic.n + " on null");
			}
			if (obj.__cls === ic.cls && ic.mode === 2 && ic.get) {
				return ic.get.call(obj, rt);
			}
			const clsName = rt._getClassName(obj);
			const rName = rt.resolveMemberAlias(ic.o || clsName, ic.n);
			const cls = rt._resolveClassNoInit(clsName);
			const hasTraps = rt._hasFieldTraps(cls);
			if (hasTraps) {
				const nf = rt._findNativeFieldDef(cls, rName) || (rName !== ic.n ? rt._findNativeFieldDef(cls, ic.n) : null);
				if (nf && typeof nf === "object" && typeof nf.get === "function") {
					if (typeof obj.__cls === "string" && obj.__cls.length > 0) {
						ic.cls = obj.__cls;
						ic.get = nf.get;
						ic.mode = 2;
						trackIC(rt, ic);
					}
					return nf.get.call(obj, rt);
				}
			}
			if (obj.__fields && Object.prototype.hasOwnProperty.call(obj.__fields, rName)) {
				return obj.__fields[rName];
			}
			if (!isReservedFieldKey(rName) && typeof obj === "object" && rName in obj && typeof obj[rName] !== "function") {
				if (!hasTraps && typeof obj.__cls === "string" && obj.__cls.length > 0 && (!cls.isNative || !(rName in cls.methods))) {
					ic.cls = obj.__cls;
					ic.fn = rName;
					ic.mode = 1;
					trackIC(rt, ic);
				}
				return obj[rName];
			}
			if (rName !== ic.n) {
				if (obj.__fields && Object.prototype.hasOwnProperty.call(obj.__fields, ic.n)) {
					return obj.__fields[ic.n];
				}
				if (!isReservedFieldKey(ic.n) && typeof obj === "object" && ic.n in obj && typeof obj[ic.n] !== "function") {
					if (!hasTraps && typeof obj.__cls === "string" && obj.__cls.length > 0 && (!cls.isNative || !(ic.n in cls.methods))) {
						ic.cls = obj.__cls;
						ic.fn = ic.n;
						ic.mode = 1;
						trackIC(rt, ic);
					}
					return obj[ic.n];
				}
			}
			return ic.def;
		},
		icPutField(rt, ic, obj, val) {
			if (obj == null) {
				throw rt.makeException("java/lang/NullPointerException", "Cannot write field " + ic.n + " on null");
			}
			if (obj.__cls === ic.cls && ic.mode === 2 && ic.set) {
				ic.set.call(obj, val, rt);
				return;
			}
			const clsName = rt._getClassName(obj);
			const rName = rt.resolveMemberAlias(ic.o || clsName, ic.n);
			const cls = rt._resolveClassNoInit(clsName);
			const hasTraps = rt._hasFieldTraps(cls);
			if (hasTraps) {
				const nf = rt._findNativeFieldDef(cls, rName) || (rName !== ic.n ? rt._findNativeFieldDef(cls, ic.n) : null);
				if (nf && typeof nf === "object" && typeof nf.set === "function") {
					if (typeof obj.__cls === "string" && obj.__cls.length > 0) {
						ic.cls = obj.__cls;
						ic.set = nf.set;
						ic.mode = 2;
						trackIC(rt, ic);
					}
					nf.set.call(obj, val, rt);
					return;
				}
			}
			if (isReservedFieldKey(rName) || typeof obj[rName] === "function") {
				if (!obj.__fields) obj.__fields = Object.create(null);
				obj.__fields[rName] = val;
			} else {
				obj[rName] = val;
				if (!hasTraps && typeof obj.__cls === "string" && obj.__cls.length > 0 && (!cls.isNative || !(rName in cls.methods))) {
					ic.cls = obj.__cls;
					ic.fn = rName;
					ic.mode = 1;
					trackIC(rt, ic);
				}
			}
		},
		icNew(rt, ic) {
			const cls = rt.loadClass(ic.o);
			const alloc = rt._getAllocFn(cls);
			if (cls.initState === 2) {
				ic.alloc = alloc;
				trackIC(rt, ic);
			}
			return alloc();
		},
		iaload(rt, arr, idx) { return arr[checkArrayAccess(rt, arr, idx)] | 0; },
		laload(rt, arr, idx) { const v = arr[checkArrayAccess(rt, arr, idx)]; return typeof v === "bigint" ? v : BigInt(v || 0); },
		faload(rt, arr, idx) { return Math.fround(arr[checkArrayAccess(rt, arr, idx)]); },
		daload(rt, arr, idx) { return Number(arr[checkArrayAccess(rt, arr, idx)]); },
		aaload(rt, arr, idx) { const v = arr[checkArrayAccess(rt, arr, idx)]; return v === undefined ? null : v; },
		baload(rt, arr, idx) {
			const v = arr[checkArrayAccess(rt, arr, idx)];
			if (typeof v === "boolean") return v ? 1 : 0;
			return arr.__cls === "[Z" ? (v ? 1 : 0) : ((v << 24) >> 24);
		},
		caload(rt, arr, idx) {
			const v = arr[checkArrayAccess(rt, arr, idx)];
			return typeof v === "string" ? v.charCodeAt(0) : (v & 0xFFFF);
		},
		saload(rt, arr, idx) { return (arr[checkArrayAccess(rt, arr, idx)] << 16) >> 16; },
		iastore(rt, arr, idx, val) { arr[checkArrayAccess(rt, arr, idx)] = val | 0; },
		lastore(rt, arr, idx, val) { arr[checkArrayAccess(rt, arr, idx)] = BigInt.asIntN(64, val); },
		fastore(rt, arr, idx, val) { arr[checkArrayAccess(rt, arr, idx)] = Math.fround(val); },
		dastore(rt, arr, idx, val) { arr[checkArrayAccess(rt, arr, idx)] = Number(val); },
		aastore(rt, arr, idx, val) { arr[checkArrayAccess(rt, arr, idx)] = val === undefined ? null : val; },
		bastore(rt, arr, idx, val) {
			const i = checkArrayAccess(rt, arr, idx);
			arr[i] = arr.__cls === "[Z" ? (val & 1) : ((val << 24) >> 24);
		},
		castore(rt, arr, idx, val) { arr[checkArrayAccess(rt, arr, idx)] = val & 0xFFFF; },
		sastore(rt, arr, idx, val) { arr[checkArrayAccess(rt, arr, idx)] = (val << 16) >> 16; }
	};

	// =========================================================================
	// 5. CORE BUILT-IN JDK NATIVE CLASSES (OVERRIDABLE BY EaglerJvmBridge)
	// =========================================================================

	const PRIMITIVE_BOX_CLASS = {
		Z: "java/lang/Boolean",
		B: "java/lang/Byte",
		C: "java/lang/Character",
		S: "java/lang/Short",
		I: "java/lang/Integer",
		J: "java/lang/Long",
		F: "java/lang/Float",
		D: "java/lang/Double"
	};

	const BOX_TO_PRIMITIVE = {
		"java/lang/Boolean": "Z",
		"java/lang/Byte": "B",
		"java/lang/Character": "C",
		"java/lang/Short": "S",
		"java/lang/Integer": "I",
		"java/lang/Long": "J",
		"java/lang/Float": "F",
		"java/lang/Double": "D"
	};

	function installCoreJdkClasses(rt) {
		function strOf(v) {
			return rt.asJsString(v);
		}

		function makeBoxed(clsName, val) {
			return { __cls: clsName, value: val };
		}

		function makeListObj(items) {
			const obj = rt.newInstance("java/util/ArrayList");
			obj._items = items ? items.slice() : [];
			return obj;
		}

		function makeSetObj(items) {
			const obj = rt.newInstance("java/util/LinkedHashSet");
			obj._items = [];
			if (items) {
				for (let i = 0; i < items.length; i++) {
					rt.invokeVirtual(obj, "add", "(Ljava/lang/Object;)Z", [items[i]]);
				}
			}
			return obj;
		}

		function makeMapObj(entries) {
			const obj = rt.newInstance("java/util/LinkedHashMap");
			obj._entries = [];
			if (entries) {
				for (let i = 0; i < entries.length; i++) {
					rt.invokeVirtual(obj, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", [entries[i][0], entries[i][1]]);
				}
			}
			return obj;
		}

		function jvmEquals(a, b) {
			if (a === b) return true;
			if (a == null || b == null) return false;
			if (typeof a === "string" || (a && a.__cls === "java/lang/String")) {
				if (typeof b === "string" || (b && b.__cls === "java/lang/String")) {
					return strOf(a) === strOf(b);
				}
				return false;
			}
			if (typeof a === "object") {
				return !!rt.invokeVirtual(a, "equals", "(Ljava/lang/Object;)Z", [b]);
			}
			return false;
		}

		function jvmHashCode(v) {
			if (v == null) return 0;
			if (typeof v === "string") return javaStringHashCode(v);
			if (typeof v === "number") return v | 0;
			if (typeof v === "bigint") return Number(BigInt.asIntN(32, v ^ (v >> 32n)));
			if (typeof v === "boolean") return v ? 1231 : 1237;
			if (typeof v === "object") {
				return rt.invokeVirtual(v, "hashCode", "()I", []) | 0;
			}
			return 0;
		}

		// Marker interfaces
		for (const iface of [
			"java/io/Serializable",
			"java/lang/Cloneable",
			"java/lang/Comparable",
			"java/lang/CharSequence",
			"java/lang/Iterable",
			"java/lang/Runnable",
			"java/lang/AutoCloseable",
			"java/io/Closeable",
			"java/util/Collection",
			"java/util/Comparator",
			"java/util/function/Supplier",
			"java/util/function/Consumer",
			"java/util/function/BiConsumer",
			"java/util/function/Function",
			"java/util/function/BiFunction",
			"java/util/function/Predicate",
			"java/util/function/BiPredicate",
			"java/util/function/UnaryOperator",
			"java/util/function/BinaryOperator",
			"java/util/function/IntSupplier",
			"java/util/function/IntConsumer",
			"java/util/function/IntFunction",
			"java/util/function/IntPredicate",
			"java/util/function/BooleanSupplier"
		]) {
			rt.registerNativeClass(iface, { superName: "java/lang/Object", isInterface: true, methods: {} });
		}

		// java/lang/Object
		rt.registerNativeClass("java/lang/Object", {
			superName: null,
			methods: {
				"<init>()V": function () {},
				"getClass()Ljava/lang/Class;": function () {
					return rt.getClassObject(rt._getClassName(this));
				},
				"hashCode()I": function () {
					return rt._identityHash(this);
				},
				"equals(Ljava/lang/Object;)Z": function (other) {
					return this === other ? 1 : 0;
				},
				"toString()Ljava/lang/String;": function () {
					const cn = rt._getClassName(this).replace(/\//g, ".");
					const h = (rt._identityHash(this) >>> 0).toString(16);
					return cn + "@" + h;
				},
				"clone()Ljava/lang/Object;": function () {
					if (Array.isArray(this)) {
						const c = this.slice();
						c.__cls = this.__cls;
						c.__elemType = this.__elemType;
						return c;
					}
					if (ArrayBuffer.isView(this)) {
						const c = new this.constructor(this);
						c.__cls = this.__cls;
						c.__elemType = this.__elemType;
						return c;
					}
					const copy = Object.assign(Object.create(Object.getPrototypeOf(this)), this);
					if (this.__fields) copy.__fields = Object.assign(Object.create(null), this.__fields);
					return copy;
				},
				"notify()V": function () {},
				"notifyAll()V": function () {},
				"wait()V": function () {},
				"wait(J)V": function () {},
				"wait(JI)V": function () {}
			}
		});

		// java/lang/Class
		rt.registerNativeClass("java/lang/Class", {
			superName: "java/lang/Object",
			interfaces: ["java/io/Serializable"],
			methods: {
				"getName()Ljava/lang/String;": function () {
					return (this._internalName || "java/lang/Object").replace(/\//g, ".");
				},
				"getSimpleName()Ljava/lang/String;": function () {
					const n = this._internalName || "";
					const slash = n.lastIndexOf("/");
					const base = slash >= 0 ? n.slice(slash + 1) : n;
					const dollar = base.lastIndexOf("$");
					return dollar >= 0 ? base.slice(dollar + 1) : base;
				},
				"getCanonicalName()Ljava/lang/String;": function () {
					return (this._internalName || "java/lang/Object").replace(/\//g, ".").replace(/\$/g, ".");
				},
				"getTypeName()Ljava/lang/String;": function () {
					return (this._internalName || "java/lang/Object").replace(/\//g, ".");
				},
				"isInstance(Ljava/lang/Object;)Z": function (obj) {
					return rt.isInstanceOf(obj, this._internalName) ? 1 : 0;
				},
				"isAssignableFrom(Ljava/lang/Class;)Z": function (other) {
					if (!other) throw rt.makeException("java/lang/NullPointerException", null);
					return rt._isAssignableFrom(other._internalName, this._internalName) ? 1 : 0;
				},
				"isInterface()Z": function () {
					const c = rt._resolveClassNoInit(this._internalName);
					return c && c.isInterface ? 1 : 0;
				},
				"isArray()Z": function () {
					return this._internalName && this._internalName[0] === "[" ? 1 : 0;
				},
				"isPrimitive()Z": function () {
					return this._isPrimitive ? 1 : 0;
				},
				"getSuperclass()Ljava/lang/Class;": function () {
					const c = rt._resolveClassNoInit(this._internalName);
					return c && c.superName ? rt.getClassObject(c.superName) : null;
				},
				"cast(Ljava/lang/Object;)Ljava/lang/Object;": function (obj) {
					if (obj != null && !rt.isInstanceOf(obj, this._internalName)) {
						throw rt.makeException("java/lang/ClassCastException", "Cannot cast to " + this._internalName);
					}
					return obj;
				},
				"desiredAssertionStatus()Z": function () { return 0; },
				"forName(Ljava/lang/String;)Ljava/lang/Class;": function (name) {
					const internal = strOf(name).replace(/\./g, "/");
					rt.loadClass(internal);
					return rt.getClassObject(internal);
				},
				"forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;": function (name, init) {
					const internal = strOf(name).replace(/\./g, "/");
					if (init) rt.loadClass(internal);
					return rt.getClassObject(internal);
				},
				"toString()Ljava/lang/String;": function () {
					return (this._isPrimitive ? "" : "class ") + (this._internalName || "").replace(/\//g, ".");
				},
				"equals(Ljava/lang/Object;)Z": function (o) {
					return (o && o.__cls === "java/lang/Class" && o._internalName === this._internalName) ? 1 : 0;
				},
				"hashCode()I": function () {
					return javaStringHashCode(this._internalName || "");
				}
			}
		});

		// java/lang/String
		rt.registerNativeClass("java/lang/String", {
			superName: "java/lang/Object",
			interfaces: ["java/io/Serializable", "java/lang/Comparable", "java/lang/CharSequence"],
			methods: {
				"<init>()V": function () { this.__value = ""; },
				"<init>(Ljava/lang/String;)V": function (s) { this.__value = strOf(s); },
				"<init>([C)V": function (arr) {
					let out = "";
					if (arr) for (let i = 0; i < arr.length; i++) out += String.fromCharCode(arr[i] & 0xFFFF);
					this.__value = out;
				},
				"<init>([CII)V": function (arr, off, count) {
					let out = "";
					if (arr) for (let i = 0; i < count; i++) out += String.fromCharCode(arr[off + i] & 0xFFFF);
					this.__value = out;
				},
				"<init>([B)V": function (arr) {
					this.__value = arr ? decodeUtf8Bytes(new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength)) : "";
				},
				"<init>([BII)V": function (arr, off, count) {
					this.__value = arr ? decodeUtf8Bytes(new Uint8Array(arr.buffer, arr.byteOffset + off, count)) : "";
				},
				"<init>([BLjava/nio/charset/Charset;)V": function (arr) {
					this.__value = arr ? decodeUtf8Bytes(new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength)) : "";
				},
				"<init>([BLjava/lang/String;)V": function (arr) {
					this.__value = arr ? decodeUtf8Bytes(new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength)) : "";
				},
				"<init>(Ljava/lang/StringBuilder;)V": function (sb) {
					this.__value = sb ? String(sb._buf || "") : "";
				},
				"length()I": function () { return strOf(this).length | 0; },
				"isEmpty()Z": function () { return strOf(this).length === 0 ? 1 : 0; },
				"isBlank()Z": function () { return strOf(this).trim().length === 0 ? 1 : 0; },
				"charAt(I)C": function (i) {
					const s = strOf(this);
					if (i < 0 || i >= s.length) throw rt.makeException("java/lang/IndexOutOfBoundsException", "String index: " + i);
					return s.charCodeAt(i);
				},
				"codePointAt(I)I": function (i) { return strOf(this).codePointAt(i) | 0; },
				"equals(Ljava/lang/Object;)Z": function (o) {
					if (o == null) return 0;
					if (typeof o === "string" || o.__cls === "java/lang/String") {
						return strOf(this) === strOf(o) ? 1 : 0;
					}
					return 0;
				},
				"equalsIgnoreCase(Ljava/lang/String;)Z": function (o) {
					return o != null && strOf(this).toLowerCase() === strOf(o).toLowerCase() ? 1 : 0;
				},
				"compareTo(Ljava/lang/String;)I": function (o) {
					const a = strOf(this), b = strOf(o);
					const n = Math.min(a.length, b.length);
					for (let i = 0; i < n; i++) {
						const d = a.charCodeAt(i) - b.charCodeAt(i);
						if (d !== 0) return d;
					}
					return (a.length - b.length) | 0;
				},
				"compareTo(Ljava/lang/Object;)I": function (o) {
					return rt.invokeVirtual(this, "compareTo", "(Ljava/lang/String;)I", [o]);
				},
				"compareToIgnoreCase(Ljava/lang/String;)I": function (o) {
					return rt.invokeVirtual(strOf(this).toLowerCase(), "compareTo", "(Ljava/lang/String;)I", [strOf(o).toLowerCase()]);
				},
				"hashCode()I": function () { return javaStringHashCode(strOf(this)); },
				"indexOf(I)I": function (ch) { return strOf(this).indexOf(String.fromCharCode(ch)) | 0; },
				"indexOf(II)I": function (ch, from) { return strOf(this).indexOf(String.fromCharCode(ch), from) | 0; },
				"indexOf(Ljava/lang/String;)I": function (sub) { return strOf(this).indexOf(strOf(sub)) | 0; },
				"indexOf(Ljava/lang/String;I)I": function (sub, from) { return strOf(this).indexOf(strOf(sub), from) | 0; },
				"lastIndexOf(I)I": function (ch) { return strOf(this).lastIndexOf(String.fromCharCode(ch)) | 0; },
				"lastIndexOf(II)I": function (ch, from) { return strOf(this).lastIndexOf(String.fromCharCode(ch), from) | 0; },
				"lastIndexOf(Ljava/lang/String;)I": function (sub) { return strOf(this).lastIndexOf(strOf(sub)) | 0; },
				"lastIndexOf(Ljava/lang/String;I)I": function (sub, from) { return strOf(this).lastIndexOf(strOf(sub), from) | 0; },
				"substring(I)Ljava/lang/String;": function (begin) { return strOf(this).slice(begin); },
				"substring(II)Ljava/lang/String;": function (begin, end) { return strOf(this).slice(begin, end); },
				"subSequence(II)Ljava/lang/CharSequence;": function (begin, end) { return strOf(this).slice(begin, end); },
				"concat(Ljava/lang/String;)Ljava/lang/String;": function (o) { return strOf(this) + strOf(o); },
				"replace(CC)Ljava/lang/String;": function (oldC, newC) {
					return strOf(this).split(String.fromCharCode(oldC)).join(String.fromCharCode(newC));
				},
				"replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;": function (a, b) {
					return strOf(this).split(strOf(a)).join(strOf(b));
				},
				"replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;": function (re, rep) {
					return strOf(this).replace(new RegExp(strOf(re), "g"), strOf(rep));
				},
				"replaceFirst(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;": function (re, rep) {
					return strOf(this).replace(new RegExp(strOf(re)), strOf(rep));
				},
				"matches(Ljava/lang/String;)Z": function (re) {
					return new RegExp("^(?:" + strOf(re) + ")$").test(strOf(this)) ? 1 : 0;
				},
				"contains(Ljava/lang/CharSequence;)Z": function (sub) {
					return strOf(this).indexOf(strOf(sub)) >= 0 ? 1 : 0;
				},
				"split(Ljava/lang/String;)[Ljava/lang/String;": function (re) {
					const parts = strOf(this).split(new RegExp(strOf(re)));
					while (parts.length > 0 && parts[parts.length - 1] === "") parts.pop();
					parts.__cls = "[Ljava/lang/String;";
					parts.__elemType = "java/lang/String";
					return parts;
				},
				"split(Ljava/lang/String;I)[Ljava/lang/String;": function (re, limit) {
					const parts = strOf(this).split(new RegExp(strOf(re)));
					if (limit > 0 && parts.length > limit) {
						const tail = parts.slice(limit - 1).join("");
						parts.length = limit - 1;
						parts.push(tail);
					} else if (limit === 0) {
						while (parts.length > 0 && parts[parts.length - 1] === "") parts.pop();
					}
					parts.__cls = "[Ljava/lang/String;";
					parts.__elemType = "java/lang/String";
					return parts;
				},
				"startsWith(Ljava/lang/String;)Z": function (p) { return strOf(this).startsWith(strOf(p)) ? 1 : 0; },
				"startsWith(Ljava/lang/String;I)Z": function (p, off) { return strOf(this).startsWith(strOf(p), off) ? 1 : 0; },
				"endsWith(Ljava/lang/String;)Z": function (s) { return strOf(this).endsWith(strOf(s)) ? 1 : 0; },
				"toLowerCase()Ljava/lang/String;": function () { return strOf(this).toLowerCase(); },
				"toLowerCase(Ljava/util/Locale;)Ljava/lang/String;": function () { return strOf(this).toLowerCase(); },
				"toUpperCase()Ljava/lang/String;": function () { return strOf(this).toUpperCase(); },
				"toUpperCase(Ljava/util/Locale;)Ljava/lang/String;": function () { return strOf(this).toUpperCase(); },
				"trim()Ljava/lang/String;": function () { return strOf(this).trim(); },
				"strip()Ljava/lang/String;": function () { return strOf(this).trim(); },
				"stripLeading()Ljava/lang/String;": function () { return strOf(this).trimStart(); },
				"stripTrailing()Ljava/lang/String;": function () { return strOf(this).trimEnd(); },
				"repeat(I)Ljava/lang/String;": function (n) { return strOf(this).repeat(Math.max(0, n | 0)); },
				"toCharArray()[C": function () {
					const s = strOf(this);
					const arr = new Uint16Array(s.length);
					for (let i = 0; i < s.length; i++) arr[i] = s.charCodeAt(i);
					arr.__cls = "[C";
					arr.__elemType = "C";
					return arr;
				},
				"getBytes()[B": function () {
					const s = strOf(this);
					const enc = typeof TextEncoder !== "undefined" ? new TextEncoder().encode(s) : new Uint8Array(s.length);
					const arr = new Int8Array(enc.length);
					for (let i = 0; i < enc.length; i++) arr[i] = (enc[i] << 24) >> 24;
					arr.__cls = "[B";
					arr.__elemType = "B";
					return arr;
				},
				"getBytes(Ljava/nio/charset/Charset;)[B": function () {
					return rt.invokeVirtual(this, "getBytes", "()[B", []);
				},
				"toString()Ljava/lang/String;": function () { return strOf(this); },
				"intern()Ljava/lang/String;": function () { return strOf(this); },
				"valueOf(Ljava/lang/Object;)Ljava/lang/String;": function (v) { return rt.jvmToString(v, "Ljava/lang/Object;"); },
				"valueOf(Z)Ljava/lang/String;": function (v) { return v ? "true" : "false"; },
				"valueOf(C)Ljava/lang/String;": function (v) { return String.fromCharCode(v & 0xFFFF); },
				"valueOf(I)Ljava/lang/String;": function (v) { return String(v | 0); },
				"valueOf(J)Ljava/lang/String;": function (v) { return String(v); },
				"valueOf(F)Ljava/lang/String;": function (v) { return formatJavaFloat(v); },
				"valueOf(D)Ljava/lang/String;": function (v) { return formatJavaFloat(v); },
				"valueOf([C)Ljava/lang/String;": function (arr) {
					let out = "";
					if (arr) for (let i = 0; i < arr.length; i++) out += String.fromCharCode(arr[i] & 0xFFFF);
					return out;
				},
				"valueOf([CII)Ljava/lang/String;": function (arr, off, count) {
					let out = "";
					if (arr) for (let i = 0; i < count; i++) out += String.fromCharCode(arr[off + i] & 0xFFFF);
					return out;
				},
				"format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;": function (fmt, args) {
					return formatJavaPrintf(rt, strOf(fmt), args || []);
				},
				"format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;": function (_loc, fmt, args) {
					return formatJavaPrintf(rt, strOf(fmt), args || []);
				},
				"formatted([Ljava/lang/Object;)Ljava/lang/String;": function (args) {
					return formatJavaPrintf(rt, strOf(this), args || []);
				},
				"join(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Ljava/lang/String;": function (delim, elems) {
					const d = strOf(delim);
					if (!elems) return "";
					const out = [];
					for (let i = 0; i < elems.length; i++) out.push(rt.jvmToString(elems[i], "Ljava/lang/Object;"));
					return out.join(d);
				}
			}
		});

		// java/lang/StringBuilder & java/lang/StringBuffer
		const sbMethods = {
			"<init>()V": function () { this._buf = ""; },
			"<init>(I)V": function () { this._buf = ""; },
			"<init>(Ljava/lang/String;)V": function (s) { this._buf = s == null ? "" : strOf(s); },
			"<init>(Ljava/lang/CharSequence;)V": function (s) { this._buf = s == null ? "" : rt.jvmToString(s, "Ljava/lang/Object;"); },
			"append(Ljava/lang/String;)Ljava/lang/StringBuilder;": function (s) { this._buf = (this._buf || "") + (s == null ? "null" : strOf(s)); return this; },
			"append(Ljava/lang/Object;)Ljava/lang/StringBuilder;": function (o) { this._buf = (this._buf || "") + rt.jvmToString(o, "Ljava/lang/Object;"); return this; },
			"append(Ljava/lang/CharSequence;)Ljava/lang/StringBuilder;": function (o) { this._buf = (this._buf || "") + (o == null ? "null" : rt.jvmToString(o, "Ljava/lang/Object;")); return this; },
			"append(Ljava/lang/CharSequence;II)Ljava/lang/StringBuilder;": function (o, s, e) { this._buf = (this._buf || "") + (o == null ? "null" : rt.jvmToString(o, "Ljava/lang/Object;").slice(s, e)); return this; },
			"append(I)Ljava/lang/StringBuilder;": function (v) { this._buf = (this._buf || "") + String(v | 0); return this; },
			"append(J)Ljava/lang/StringBuilder;": function (v) { this._buf = (this._buf || "") + String(v); return this; },
			"append(F)Ljava/lang/StringBuilder;": function (v) { this._buf = (this._buf || "") + formatJavaFloat(v); return this; },
			"append(D)Ljava/lang/StringBuilder;": function (v) { this._buf = (this._buf || "") + formatJavaFloat(v); return this; },
			"append(Z)Ljava/lang/StringBuilder;": function (v) { this._buf = (this._buf || "") + (v ? "true" : "false"); return this; },
			"append(C)Ljava/lang/StringBuilder;": function (v) { this._buf = (this._buf || "") + String.fromCharCode(v & 0xFFFF); return this; },
			"append([C)Ljava/lang/StringBuilder;": function (arr) {
				let s = "";
				if (arr) for (let i = 0; i < arr.length; i++) s += String.fromCharCode(arr[i] & 0xFFFF);
				this._buf = (this._buf || "") + s;
				return this;
			},
			"append([CII)Ljava/lang/StringBuilder;": function (arr, off, count) {
				let s = "";
				if (arr) for (let i = 0; i < count; i++) s += String.fromCharCode(arr[off + i] & 0xFFFF);
				this._buf = (this._buf || "") + s;
				return this;
			},
			"append": function (v) { this._buf = (this._buf || "") + rt.jvmToString(v, "Ljava/lang/Object;"); return this; },
			"insert": function (idx, v) {
				const s = this._buf || "";
				this._buf = s.slice(0, idx) + rt.jvmToString(v, "Ljava/lang/Object;") + s.slice(idx);
				return this;
			},
			"delete": function (start, end) {
				const s = this._buf || "";
				this._buf = s.slice(0, start) + s.slice(end);
				return this;
			},
			"deleteCharAt": function (idx) {
				const s = this._buf || "";
				this._buf = s.slice(0, idx) + s.slice(idx + 1);
				return this;
			},
			"reverse": function () {
				this._buf = (this._buf || "").split("").reverse().join("");
				return this;
			},
			"length()I": function () { return (this._buf || "").length | 0; },
			"isEmpty()Z": function () { return (this._buf || "").length === 0 ? 1 : 0; },
			"setLength(I)V": function (n) {
				const s = this._buf || "";
				this._buf = n <= s.length ? s.slice(0, n) : s + "\0".repeat(n - s.length);
			},
			"charAt(I)C": function (i) { return (this._buf || "").charCodeAt(i); },
			"setCharAt(IC)V": function (i, c) {
				const s = this._buf || "";
				this._buf = s.slice(0, i) + String.fromCharCode(c & 0xFFFF) + s.slice(i + 1);
			},
			"substring(I)Ljava/lang/String;": function (a) { return (this._buf || "").slice(a); },
			"substring(II)Ljava/lang/String;": function (a, b) { return (this._buf || "").slice(a, b); },
			"indexOf(Ljava/lang/String;)I": function (sub) { return (this._buf || "").indexOf(strOf(sub)) | 0; },
			"toString()Ljava/lang/String;": function () { return this._buf || ""; }
		};
		rt.registerNativeClass("java/lang/StringBuilder", {
			superName: "java/lang/Object",
			interfaces: ["java/lang/CharSequence", "java/io/Serializable"],
			ctor: function () { this._buf = ""; },
			methods: sbMethods
		});
		const sbufMethods = Object.create(null);
		for (const k of Object.keys(sbMethods)) {
			sbufMethods[k.replace(/Ljava\/lang\/StringBuilder;/g, "Ljava/lang/StringBuffer;")] = sbMethods[k];
		}
		rt.registerNativeClass("java/lang/StringBuffer", {
			superName: "java/lang/Object",
			interfaces: ["java/lang/CharSequence", "java/io/Serializable"],
			ctor: function () { this._buf = ""; },
			methods: sbufMethods
		});

		// java/lang/Throwable and standard exception hierarchy
		const throwableCtors = {
			"<init>()V": function () { this.detailMessage = null; this.cause = null; },
			"<init>(Ljava/lang/String;)V": function (msg) { this.detailMessage = msg == null ? null : strOf(msg); this.cause = null; },
			"<init>(Ljava/lang/String;Ljava/lang/Throwable;)V": function (msg, cause) { this.detailMessage = msg == null ? null : strOf(msg); this.cause = cause || null; },
			"<init>(Ljava/lang/Throwable;)V": function (cause) { this.detailMessage = cause ? rt.jvmToString(cause, "Ljava/lang/Object;") : null; this.cause = cause || null; }
		};
		rt.registerNativeClass("java/lang/Throwable", {
			superName: "java/lang/Object",
			interfaces: ["java/io/Serializable"],
			methods: Object.assign({}, throwableCtors, {
				"getMessage()Ljava/lang/String;": function () { return this.detailMessage != null ? this.detailMessage : null; },
				"getLocalizedMessage()Ljava/lang/String;": function () { return this.detailMessage != null ? this.detailMessage : null; },
				"getCause()Ljava/lang/Throwable;": function () { return this.cause || null; },
				"initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;": function (c) { this.cause = c; return this; },
				"fillInStackTrace()Ljava/lang/Throwable;": function () { return this; },
				"getStackTrace()[Ljava/lang/StackTraceElement;": function () { const a = []; a.__cls = "[Ljava/lang/StackTraceElement;"; return a; },
				"printStackTrace()V": function () { console.error("[JVM]", rt.invokeVirtual(this, "toString", "()Ljava/lang/String;", [])); },
				"toString()Ljava/lang/String;": function () {
					const cn = (this.__cls || "java/lang/Throwable").replace(/\//g, ".");
					return this.detailMessage != null ? (cn + ": " + this.detailMessage) : cn;
				}
			})
		});

		const exHierarchy = [
			["java/lang/Error", "java/lang/Throwable"],
			["java/lang/LinkageError", "java/lang/Error"],
			["java/lang/NoClassDefFoundError", "java/lang/LinkageError"],
			["java/lang/IncompatibleClassChangeError", "java/lang/LinkageError"],
			["java/lang/AbstractMethodError", "java/lang/IncompatibleClassChangeError"],
			["java/lang/NoSuchMethodError", "java/lang/IncompatibleClassChangeError"],
			["java/lang/NoSuchFieldError", "java/lang/IncompatibleClassChangeError"],
			["java/lang/ExceptionInInitializerError", "java/lang/LinkageError"],
			["java/lang/AssertionError", "java/lang/Error"],
			["java/lang/Exception", "java/lang/Throwable"],
			["java/io/IOException", "java/lang/Exception"],
			["java/lang/ReflectiveOperationException", "java/lang/Exception"],
			["java/lang/ClassNotFoundException", "java/lang/ReflectiveOperationException"],
			["java/lang/RuntimeException", "java/lang/Exception"],
			["java/lang/NullPointerException", "java/lang/RuntimeException"],
			["java/lang/ArithmeticException", "java/lang/RuntimeException"],
			["java/lang/ClassCastException", "java/lang/RuntimeException"],
			["java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException"],
			["java/lang/ArrayIndexOutOfBoundsException", "java/lang/IndexOutOfBoundsException"],
			["java/lang/StringIndexOutOfBoundsException", "java/lang/IndexOutOfBoundsException"],
			["java/lang/IllegalArgumentException", "java/lang/RuntimeException"],
			["java/lang/NumberFormatException", "java/lang/IllegalArgumentException"],
			["java/lang/IllegalStateException", "java/lang/RuntimeException"],
			["java/lang/UnsupportedOperationException", "java/lang/RuntimeException"],
			["java/lang/NegativeArraySizeException", "java/lang/RuntimeException"],
			["java/util/NoSuchElementException", "java/lang/RuntimeException"]
		];
		for (let i = 0; i < exHierarchy.length; i++) {
			rt.registerNativeClass(exHierarchy[i][0], { superName: exHierarchy[i][1], methods: Object.assign({}, throwableCtors) });
		}

		// java/lang/Enum & java/lang/Record
		rt.registerNativeClass("java/lang/Enum", {
			superName: "java/lang/Object",
			interfaces: ["java/lang/Comparable", "java/io/Serializable"],
			methods: {
				"<init>(Ljava/lang/String;I)V": function (name, ordinal) {
					this._enumName = strOf(name);
					this._ordinal = ordinal | 0;
				},
				"name()Ljava/lang/String;": function () { return this._enumName || ""; },
				"ordinal()I": function () { return this._ordinal | 0; },
				"toString()Ljava/lang/String;": function () { return this._enumName || ""; },
				"equals(Ljava/lang/Object;)Z": function (o) { return this === o ? 1 : 0; },
				"hashCode()I": function () { return rt._identityHash(this); },
				"compareTo(Ljava/lang/Enum;)I": function (o) { return ((this._ordinal | 0) - (o._ordinal | 0)) | 0; },
				"compareTo(Ljava/lang/Object;)I": function (o) { return ((this._ordinal | 0) - (o._ordinal | 0)) | 0; },
				"getDeclaringClass()Ljava/lang/Class;": function () { return rt.getClassObject(this.__cls); },
				"valueOf(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;": function (enumClsObj, name) {
					const cn = enumClsObj && enumClsObj._internalName;
					const target = strOf(name);
					const c = rt.loadClass(cn);
					if (c && c.staticFields && c.staticFields[target]) return c.staticFields[target];
					throw rt.makeException("java/lang/IllegalArgumentException", "No enum constant " + cn + "." + target);
				}
			}
		});

		rt.registerNativeClass("java/lang/Record", {
			superName: "java/lang/Object",
			methods: { "<init>()V": function () {} }
		});

		// java/lang/Number & boxed primitives
		rt.registerNativeClass("java/lang/Number", {
			superName: "java/lang/Object",
			interfaces: ["java/io/Serializable"],
			methods: {
				"<init>()V": function () {},
				"intValue()I": function () { return typeof this.value === "bigint" ? Number(BigInt.asIntN(32, this.value)) : (this.value | 0); },
				"longValue()J": function () { return typeof this.value === "bigint" ? this.value : f2l(Number(this.value || 0)); },
				"floatValue()F": function () { return Math.fround(Number(this.value || 0)); },
				"doubleValue()D": function () { return Number(this.value || 0); },
				"byteValue()B": function () { return ((rt.invokeVirtual(this, "intValue", "()I", []) << 24) >> 24); },
				"shortValue()S": function () { return ((rt.invokeVirtual(this, "intValue", "()I", []) << 16) >> 16); }
			}
		});

		const BOOL_TRUE = { __cls: "java/lang/Boolean", _boolValue: true, value: 1 };
		const BOOL_FALSE = { __cls: "java/lang/Boolean", _boolValue: false, value: 0 };
		rt._boolTrue = BOOL_TRUE;
		rt._boolFalse = BOOL_FALSE;
		rt.registerNativeClass("java/lang/Boolean", {
			superName: "java/lang/Object",
			interfaces: ["java/io/Serializable", "java/lang/Comparable"],
			staticFields: { TRUE: BOOL_TRUE, FALSE: BOOL_FALSE, TYPE: rt._makePrimitiveClass("Z") },
			methods: {
				"<init>(Z)V": function (v) { this._boolValue = !!v; this.value = v ? 1 : 0; },
				"<init>(Ljava/lang/String;)V": function (s) { const b = strOf(s).toLowerCase() === "true"; this._boolValue = b; this.value = b ? 1 : 0; },
				"valueOf(Z)Ljava/lang/Boolean;": function (v) { return v ? rt._boolTrue : rt._boolFalse; },
				"valueOf(Ljava/lang/String;)Ljava/lang/Boolean;": function (s) { return strOf(s).toLowerCase() === "true" ? rt._boolTrue : rt._boolFalse; },
				"parseBoolean(Ljava/lang/String;)Z": function (s) { return s != null && strOf(s).toLowerCase() === "true" ? 1 : 0; },
				"booleanValue()Z": function () { return this.value ? 1 : 0; },
				"toString()Ljava/lang/String;": function () { return this.value ? "true" : "false"; },
				"toString(Z)Ljava/lang/String;": function (v) { return v ? "true" : "false"; },
				"hashCode()I": function () { return this.value ? 1231 : 1237; },
				"hashCode(Z)I": function (v) { return v ? 1231 : 1237; },
				"equals(Ljava/lang/Object;)Z": function (o) { return (o && o.__cls === "java/lang/Boolean" && Boolean(o.value) === Boolean(this.value)) ? 1 : 0; },
				"compare(ZZ)I": function (a, b) { return (a ? 1 : 0) - (b ? 1 : 0); },
				"compareTo(Ljava/lang/Boolean;)I": function (o) { return (this.value ? 1 : 0) - (o.value ? 1 : 0); },
				"compareTo(Ljava/lang/Object;)I": function (o) { return (this.value ? 1 : 0) - (o.value ? 1 : 0); }
			}
		});

		rt.registerNativeClass("java/lang/Byte", {
			superName: "java/lang/Number",
			interfaces: ["java/lang/Comparable"],
			staticFields: { MIN_VALUE: -128, MAX_VALUE: 127, TYPE: rt._makePrimitiveClass("B") },
			methods: {
				"<init>(B)V": function (v) { this.value = (v << 24) >> 24; },
				"valueOf(B)Ljava/lang/Byte;": function (v) { return makeBoxed("java/lang/Byte", (v << 24) >> 24); },
				"valueOf(Ljava/lang/String;)Ljava/lang/Byte;": function (s) { return makeBoxed("java/lang/Byte", (parseInt(strOf(s), 10) << 24) >> 24); },
				"parseByte(Ljava/lang/String;)B": function (s) { const n = parseInt(strOf(s), 10); if (Number.isNaN(n)) throw rt.makeException("java/lang/NumberFormatException", s); return (n << 24) >> 24; },
				"toUnsignedInt(B)I": function (v) { return v & 0xFF; },
				"toString()Ljava/lang/String;": function () { return String(this.value | 0); },
				"toString(B)Ljava/lang/String;": function (v) { return String((v << 24) >> 24); },
				"hashCode()I": function () { return this.value | 0; },
				"equals(Ljava/lang/Object;)Z": function (o) { return (o && o.__cls === "java/lang/Byte" && o.value === this.value) ? 1 : 0; }
			}
		});

		rt.registerNativeClass("java/lang/Short", {
			superName: "java/lang/Number",
			interfaces: ["java/lang/Comparable"],
			staticFields: { MIN_VALUE: -32768, MAX_VALUE: 32767, TYPE: rt._makePrimitiveClass("S") },
			methods: {
				"<init>(S)V": function (v) { this.value = (v << 16) >> 16; },
				"valueOf(S)Ljava/lang/Short;": function (v) { return makeBoxed("java/lang/Short", (v << 16) >> 16); },
				"parseShort(Ljava/lang/String;)S": function (s) { const n = parseInt(strOf(s), 10); if (Number.isNaN(n)) throw rt.makeException("java/lang/NumberFormatException", s); return (n << 16) >> 16; },
				"toString()Ljava/lang/String;": function () { return String(this.value | 0); },
				"toString(S)Ljava/lang/String;": function (v) { return String((v << 16) >> 16); },
				"hashCode()I": function () { return this.value | 0; },
				"equals(Ljava/lang/Object;)Z": function (o) { return (o && o.__cls === "java/lang/Short" && o.value === this.value) ? 1 : 0; }
			}
		});

		rt.registerNativeClass("java/lang/Character", {
			superName: "java/lang/Object",
			interfaces: ["java/io/Serializable", "java/lang/Comparable"],
			staticFields: { MIN_VALUE: 0, MAX_VALUE: 65535, TYPE: rt._makePrimitiveClass("C") },
			methods: {
				"<init>(C)V": function (v) { this.value = v & 0xFFFF; },
				"valueOf(C)Ljava/lang/Character;": function (v) { return makeBoxed("java/lang/Character", v & 0xFFFF); },
				"charValue()C": function () { return this.value & 0xFFFF; },
				"toString()Ljava/lang/String;": function () { return String.fromCharCode(this.value & 0xFFFF); },
				"toString(C)Ljava/lang/String;": function (v) { return String.fromCharCode(v & 0xFFFF); },
				"isDigit(C)Z": function (c) { return (c >= 48 && c <= 57) ? 1 : 0; },
				"isLetter(C)Z": function (c) { return /[a-zA-Z]/.test(String.fromCharCode(c)) ? 1 : 0; },
				"isLetterOrDigit(C)Z": function (c) { return /[a-zA-Z0-9]/.test(String.fromCharCode(c)) ? 1 : 0; },
				"isWhitespace(C)Z": function (c) { return /\s/.test(String.fromCharCode(c)) ? 1 : 0; },
				"toLowerCase(C)C": function (c) { return String.fromCharCode(c).toLowerCase().charCodeAt(0); },
				"toUpperCase(C)C": function (c) { return String.fromCharCode(c).toUpperCase().charCodeAt(0); },
				"hashCode()I": function () { return this.value | 0; },
				"equals(Ljava/lang/Object;)Z": function (o) { return (o && o.__cls === "java/lang/Character" && o.value === this.value) ? 1 : 0; }
			}
		});

		rt.registerNativeClass("java/lang/Integer", {
			superName: "java/lang/Number",
			interfaces: ["java/lang/Comparable"],
			staticFields: { MIN_VALUE: -2147483648, MAX_VALUE: 2147483647, SIZE: 32, BYTES: 4, TYPE: rt._makePrimitiveClass("I") },
			methods: {
				"<init>(I)V": function (v) { const i = v | 0; this._numValue = i; this.value = i; },
				"<init>(Ljava/lang/String;)V": function (s) { const i = parseInt(strOf(s), 10) | 0; this._numValue = i; this.value = i; },
				"valueOf(I)Ljava/lang/Integer;": function (v) { return COMPILED_HELPERS.boxInt(v); },
				"valueOf(Ljava/lang/String;)Ljava/lang/Integer;": function (s) { return COMPILED_HELPERS.boxInt(rt.invokeStatic("java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", [s])); },
				"valueOf(Ljava/lang/String;I)Ljava/lang/Integer;": function (s, r) { return COMPILED_HELPERS.boxInt(rt.invokeStatic("java/lang/Integer", "parseInt", "(Ljava/lang/String;I)I", [s, r])); },
				"parseInt(Ljava/lang/String;)I": function (s) {
					const t = strOf(s).trim();
					if (!/^[+-]?\d+$/.test(t)) throw rt.makeException("java/lang/NumberFormatException", "For input string: " + t);
					return parseInt(t, 10) | 0;
				},
				"parseInt(Ljava/lang/String;I)I": function (s, r) {
					const n = parseInt(strOf(s).trim(), r | 0);
					if (Number.isNaN(n)) throw rt.makeException("java/lang/NumberFormatException", "For input string: " + s);
					return n | 0;
				},
				"toString()Ljava/lang/String;": function () { return String(this.value | 0); },
				"toString(I)Ljava/lang/String;": function (v) { return String(v | 0); },
				"toString(II)Ljava/lang/String;": function (v, r) { return (v | 0).toString(r | 0); },
				"toHexString(I)Ljava/lang/String;": function (v) { return (v >>> 0).toString(16); },
				"toBinaryString(I)Ljava/lang/String;": function (v) { return (v >>> 0).toString(2); },
				"numberOfLeadingZeros(I)I": function (v) { return Math.clz32(v | 0); },
				"numberOfTrailingZeros(I)I": function (v) { v |= 0; if (v === 0) return 32; return 31 - Math.clz32(v & -v); },
				"bitCount(I)I": function (v) { v -= (v >>> 1) & 0x55555555; v = (v & 0x33333333) + ((v >>> 2) & 0x33333333); return (((v + (v >>> 4)) & 0x0F0F0F0F) * 0x01010101) >>> 24; },
				"highestOneBit(I)I": function (v) { v |= 0; return v === 0 ? 0 : (1 << (31 - Math.clz32(v))); },
				"rotateLeft(II)I": function (v, d) { d &= 31; return ((v << d) | (v >>> (32 - d))) | 0; },
				"rotateRight(II)I": function (v, d) { d &= 31; return ((v >>> d) | (v << (32 - d))) | 0; },
				"compare(II)I": function (a, b) { return a < b ? -1 : (a > b ? 1 : 0); },
				"compareTo(Ljava/lang/Integer;)I": function (o) { return this.value < o.value ? -1 : (this.value > o.value ? 1 : 0); },
				"compareTo(Ljava/lang/Object;)I": function (o) { return this.value < o.value ? -1 : (this.value > o.value ? 1 : 0); },
				"max(II)I": function (a, b) { return a > b ? a : b; },
				"min(II)I": function (a, b) { return a < b ? a : b; },
				"sum(II)I": function (a, b) { return (a + b) | 0; },
				"hashCode()I": function () { return this.value | 0; },
				"hashCode(I)I": function (v) { return v | 0; },
				"equals(Ljava/lang/Object;)Z": function (o) { return (o && o.__cls === "java/lang/Integer" && o.value === this.value) ? 1 : 0; }
			}
		});

		rt.registerNativeClass("java/lang/Long", {
			superName: "java/lang/Number",
			interfaces: ["java/lang/Comparable"],
			staticFields: { MIN_VALUE: LONG_MIN, MAX_VALUE: LONG_MAX, SIZE: 64, BYTES: 8, TYPE: rt._makePrimitiveClass("J") },
			methods: {
				"<init>(J)V": function (v) { this.value = BigInt.asIntN(64, v); },
				"valueOf(J)Ljava/lang/Long;": function (v) { return makeBoxed("java/lang/Long", BigInt.asIntN(64, v)); },
				"valueOf(Ljava/lang/String;)Ljava/lang/Long;": function (s) { return makeBoxed("java/lang/Long", BigInt.asIntN(64, BigInt(strOf(s).trim()))); },
				"parseLong(Ljava/lang/String;)J": function (s) {
					try { return BigInt.asIntN(64, BigInt(strOf(s).trim())); }
					catch (_e) { throw rt.makeException("java/lang/NumberFormatException", "For input string: " + s); }
				},
				"parseLong(Ljava/lang/String;I)J": function (s, r) {
					const n = parseInt(strOf(s).trim(), r | 0);
					if (Number.isNaN(n)) throw rt.makeException("java/lang/NumberFormatException", "For input string: " + s);
					return BigInt.asIntN(64, BigInt(n));
				},
				"toString()Ljava/lang/String;": function () { return String(this.value); },
				"toString(J)Ljava/lang/String;": function (v) { return String(v); },
				"toHexString(J)Ljava/lang/String;": function (v) { return BigInt.asUintN(64, v).toString(16); },
				"compare(JJ)I": function (a, b) { return a < b ? -1 : (a > b ? 1 : 0); },
				"compareTo(Ljava/lang/Long;)I": function (o) { return this.value < o.value ? -1 : (this.value > o.value ? 1 : 0); },
				"compareTo(Ljava/lang/Object;)I": function (o) { return this.value < o.value ? -1 : (this.value > o.value ? 1 : 0); },
				"max(JJ)J": function (a, b) { return a > b ? a : b; },
				"min(JJ)J": function (a, b) { return a < b ? a : b; },
				"sum(JJ)J": function (a, b) { return BigInt.asIntN(64, a + b); },
				"hashCode()I": function () { return Number(BigInt.asIntN(32, this.value ^ (BigInt.asUintN(64, this.value) >> 32n))); },
				"hashCode(J)I": function (v) { return Number(BigInt.asIntN(32, v ^ (BigInt.asUintN(64, v) >> 32n))); },
				"equals(Ljava/lang/Object;)Z": function (o) { return (o && o.__cls === "java/lang/Long" && o.value === this.value) ? 1 : 0; }
			}
		});

		const f32Buf = new ArrayBuffer(8);
		const f32View = new DataView(f32Buf);

		rt.registerNativeClass("java/lang/Float", {
			superName: "java/lang/Number",
			interfaces: ["java/lang/Comparable"],
			staticFields: { POSITIVE_INFINITY: Infinity, NEGATIVE_INFINITY: -Infinity, NaN: NaN, MAX_VALUE: 3.4028235e38, MIN_VALUE: 1.4e-45, TYPE: rt._makePrimitiveClass("F") },
			methods: {
				"<init>(F)V": function (v) { this.value = Math.fround(v); },
				"valueOf(F)Ljava/lang/Float;": function (v) { return makeBoxed("java/lang/Float", Math.fround(v)); },
				"valueOf(Ljava/lang/String;)Ljava/lang/Float;": function (s) { return makeBoxed("java/lang/Float", Math.fround(parseFloat(strOf(s)))); },
				"parseFloat(Ljava/lang/String;)F": function (s) {
					const n = parseFloat(strOf(s).trim());
					if (Number.isNaN(n) && strOf(s).trim() !== "NaN") throw rt.makeException("java/lang/NumberFormatException", s);
					return Math.fround(n);
				},
				"isNaN(F)Z": function (v) { return Number.isNaN(v) ? 1 : 0; },
				"isNaN()Z": function () { return Number.isNaN(this.value) ? 1 : 0; },
				"isInfinite(F)Z": function (v) { return (!Number.isFinite(v) && !Number.isNaN(v)) ? 1 : 0; },
				"isFinite(F)Z": function (v) { return Number.isFinite(v) ? 1 : 0; },
				"floatToIntBits(F)I": function (v) { if (Number.isNaN(v)) return 0x7FC00000; f32View.setFloat32(0, v, false); return f32View.getInt32(0, false); },
				"floatToRawIntBits(F)I": function (v) { f32View.setFloat32(0, v, false); return f32View.getInt32(0, false); },
				"intBitsToFloat(I)F": function (b) { f32View.setInt32(0, b | 0, false); return Math.fround(f32View.getFloat32(0, false)); },
				"compare(FF)I": function (a, b) { if (Number.isNaN(a)) return Number.isNaN(b) ? 0 : 1; if (Number.isNaN(b)) return -1; return a < b ? -1 : (a > b ? 1 : 0); },
				"compareTo(Ljava/lang/Float;)I": function (o) { return rt.invokeStatic("java/lang/Float", "compare", "(FF)I", [this.value, o.value]); },
				"compareTo(Ljava/lang/Object;)I": function (o) { return rt.invokeStatic("java/lang/Float", "compare", "(FF)I", [this.value, o.value]); },
				"toString()Ljava/lang/String;": function () { return formatJavaFloat(this.value); },
				"toString(F)Ljava/lang/String;": function (v) { return formatJavaFloat(v); },
				"hashCode()I": function () { return rt.invokeStatic("java/lang/Float", "floatToIntBits", "(F)I", [this.value]); },
				"hashCode(F)I": function (v) { return rt.invokeStatic("java/lang/Float", "floatToIntBits", "(F)I", [v]); },
				"equals(Ljava/lang/Object;)Z": function (o) { return (o && o.__cls === "java/lang/Float" && (o.value === this.value || (Number.isNaN(o.value) && Number.isNaN(this.value)))) ? 1 : 0; }
			}
		});

		rt.registerNativeClass("java/lang/Double", {
			superName: "java/lang/Number",
			interfaces: ["java/lang/Comparable"],
			staticFields: { POSITIVE_INFINITY: Infinity, NEGATIVE_INFINITY: -Infinity, NaN: NaN, MAX_VALUE: Number.MAX_VALUE, MIN_VALUE: Number.MIN_VALUE, TYPE: rt._makePrimitiveClass("D") },
			methods: {
				"<init>(D)V": function (v) { this.value = Number(v); },
				"valueOf(D)Ljava/lang/Double;": function (v) { return makeBoxed("java/lang/Double", Number(v)); },
				"valueOf(Ljava/lang/String;)Ljava/lang/Double;": function (s) { return makeBoxed("java/lang/Double", parseFloat(strOf(s))); },
				"parseDouble(Ljava/lang/String;)D": function (s) {
					const n = parseFloat(strOf(s).trim());
					if (Number.isNaN(n) && strOf(s).trim() !== "NaN") throw rt.makeException("java/lang/NumberFormatException", s);
					return n;
				},
				"isNaN(D)Z": function (v) { return Number.isNaN(v) ? 1 : 0; },
				"isNaN()Z": function () { return Number.isNaN(this.value) ? 1 : 0; },
				"isInfinite(D)Z": function (v) { return (!Number.isFinite(v) && !Number.isNaN(v)) ? 1 : 0; },
				"isFinite(D)Z": function (v) { return Number.isFinite(v) ? 1 : 0; },
				"doubleToLongBits(D)J": function (v) {
					if (Number.isNaN(v)) return 0x7FF8000000000000n;
					f32View.setFloat64(0, v, false);
					return (BigInt(f32View.getInt32(0, false)) << 32n) | BigInt(f32View.getUint32(4, false));
				},
				"doubleToRawLongBits(D)J": function (v) {
					f32View.setFloat64(0, v, false);
					return (BigInt(f32View.getInt32(0, false)) << 32n) | BigInt(f32View.getUint32(4, false));
				},
				"longBitsToDouble(J)D": function (bits) {
					f32View.setInt32(0, Number(BigInt.asIntN(32, bits >> 32n)), false);
					f32View.setUint32(4, Number(BigInt.asUintN(32, bits)), false);
					return f32View.getFloat64(0, false);
				},
				"compare(DD)I": function (a, b) { if (Number.isNaN(a)) return Number.isNaN(b) ? 0 : 1; if (Number.isNaN(b)) return -1; return a < b ? -1 : (a > b ? 1 : 0); },
				"compareTo(Ljava/lang/Double;)I": function (o) { return rt.invokeStatic("java/lang/Double", "compare", "(DD)I", [this.value, o.value]); },
				"compareTo(Ljava/lang/Object;)I": function (o) { return rt.invokeStatic("java/lang/Double", "compare", "(DD)I", [this.value, o.value]); },
				"toString()Ljava/lang/String;": function () { return formatJavaFloat(this.value); },
				"toString(D)Ljava/lang/String;": function (v) { return formatJavaFloat(v); },
				"hashCode()I": function () { const b = rt.invokeStatic("java/lang/Double", "doubleToLongBits", "(D)J", [this.value]); return Number(BigInt.asIntN(32, b ^ (BigInt.asUintN(64, b) >> 32n))); },
				"hashCode(D)I": function (v) { const b = rt.invokeStatic("java/lang/Double", "doubleToLongBits", "(D)J", [v]); return Number(BigInt.asIntN(32, b ^ (BigInt.asUintN(64, b) >> 32n))); },
				"equals(Ljava/lang/Object;)Z": function (o) { return (o && o.__cls === "java/lang/Double" && (o.value === this.value || (Number.isNaN(o.value) && Number.isNaN(this.value)))) ? 1 : 0; }
			}
		});

		// java/lang/Math & StrictMath
		const mathMethods = {
			"abs(I)I": function (a) { return a < 0 ? (-a | 0) : a; },
			"abs(J)J": function (a) { return a < 0n ? BigInt.asIntN(64, -a) : a; },
			"abs(F)F": function (a) { return Math.fround(Math.abs(a)); },
			"abs(D)D": function (a) { return Math.abs(a); },
			"min(II)I": function (a, b) { return a < b ? a : b; },
			"min(JJ)J": function (a, b) { return a < b ? a : b; },
			"min(FF)F": function (a, b) { return Math.fround(Math.min(a, b)); },
			"min(DD)D": function (a, b) { return Math.min(a, b); },
			"max(II)I": function (a, b) { return a > b ? a : b; },
			"max(JJ)J": function (a, b) { return a > b ? a : b; },
			"max(FF)F": function (a, b) { return Math.fround(Math.max(a, b)); },
			"max(DD)D": function (a, b) { return Math.max(a, b); },
			"clamp(JII)I": function (v, lo, hi) { const n = Number(v); return Math.min(hi, Math.max(lo, n)) | 0; },
			"clamp(JJJ)J": function (v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); },
			"clamp(FFF)F": function (v, lo, hi) { return Math.fround(Math.min(hi, Math.max(lo, v))); },
			"clamp(DDD)D": function (v, lo, hi) { return Math.min(hi, Math.max(lo, v)); },
			"floor(D)D": function (a) { return Math.floor(a); },
			"ceil(D)D": function (a) { return Math.ceil(a); },
			"round(F)I": function (a) { return Number.isNaN(a) ? 0 : f2i(Math.floor(a + 0.5)); },
			"round(D)J": function (a) { return Number.isNaN(a) ? 0n : f2l(Math.floor(a + 0.5)); },
			"rint(D)D": function (a) { return Math.round(a); },
			"sqrt(D)D": function (a) { return Math.sqrt(a); },
			"cbrt(D)D": function (a) { return Math.cbrt(a); },
			"pow(DD)D": function (a, b) { return Math.pow(a, b); },
			"exp(D)D": function (a) { return Math.exp(a); },
			"log(D)D": function (a) { return Math.log(a); },
			"log10(D)D": function (a) { return Math.log10(a); },
			"log1p(D)D": function (a) { return Math.log1p(a); },
			"expm1(D)D": function (a) { return Math.expm1(a); },
			"sin(D)D": function (a) { return Math.sin(a); },
			"cos(D)D": function (a) { return Math.cos(a); },
			"tan(D)D": function (a) { return Math.tan(a); },
			"asin(D)D": function (a) { return Math.asin(a); },
			"acos(D)D": function (a) { return Math.acos(a); },
			"atan(D)D": function (a) { return Math.atan(a); },
			"atan2(DD)D": function (y, x) { return Math.atan2(y, x); },
			"sinh(D)D": function (a) { return Math.sinh(a); },
			"cosh(D)D": function (a) { return Math.cosh(a); },
			"tanh(D)D": function (a) { return Math.tanh(a); },
			"hypot(DD)D": function (a, b) { return Math.hypot(a, b); },
			"signum(F)F": function (a) { return Number.isNaN(a) ? NaN : Math.fround(Math.sign(a)); },
			"signum(D)D": function (a) { return Number.isNaN(a) ? NaN : Math.sign(a); },
			"copySign(FF)F": function (m, s) { return Math.fround(s < 0 || Object.is(s, -0) ? -Math.abs(m) : Math.abs(m)); },
			"copySign(DD)D": function (m, s) { return s < 0 || Object.is(s, -0) ? -Math.abs(m) : Math.abs(m); },
			"toRadians(D)D": function (deg) { return deg * (Math.PI / 180); },
			"toDegrees(D)D": function (rad) { return rad * (180 / Math.PI); },
			"random()D": function () { return Math.random(); },
			"floorDiv(II)I": function (x, y) { if (y === 0) throw rt.makeException("java/lang/ArithmeticException", "/ by zero"); return Math.floor(x / y) | 0; },
			"floorMod(II)I": function (x, y) { if (y === 0) throw rt.makeException("java/lang/ArithmeticException", "/ by zero"); return (((x % y) + y) % y) | 0; },
			"floorDiv(JJ)J": function (x, y) { if (y === 0n) throw rt.makeException("java/lang/ArithmeticException", "/ by zero"); let r = x / y; if ((x ^ y) < 0n && r * y !== x) r -= 1n; return r; },
			"floorMod(JJ)J": function (x, y) { if (y === 0n) throw rt.makeException("java/lang/ArithmeticException", "/ by zero"); let r = x % y; if ((x ^ y) < 0n && r !== 0n) r += y; return r; },
			"fma(DDD)D": function (a, b, c) { return a * b + c; },
			"fma(FFF)F": function (a, b, c) { return Math.fround(a * b + c); }
		};
		rt.registerNativeClass("java/lang/Math", { superName: "java/lang/Object", staticFields: { PI: Math.PI, E: Math.E }, methods: mathMethods });
		rt.registerNativeClass("java/lang/StrictMath", { superName: "java/lang/Object", staticFields: { PI: Math.PI, E: Math.E }, methods: Object.assign({}, mathMethods) });

		// java/io/PrintStream & java/lang/System
		rt.registerNativeClass("java/io/PrintStream", {
			superName: "java/lang/Object",
			methods: {
				"println": function (v) {
					const fn = this._isErr ? console.warn : console.log;
					fn("[JVM]", arguments.length === 0 ? "" : rt.jvmToString(v, "Ljava/lang/Object;"));
				},
				"print": function (v) {
					const fn = this._isErr ? console.warn : console.log;
					fn("[JVM]", arguments.length === 0 ? "" : rt.jvmToString(v, "Ljava/lang/Object;"));
				},
				"printf": function (fmt, args) {
					const fn = this._isErr ? console.warn : console.log;
					fn("[JVM]", formatJavaPrintf(rt, strOf(fmt), args || []));
					return this;
				},
				"flush()V": function () {},
				"close()V": function () {}
			}
		});

		const sysOut = { __cls: "java/io/PrintStream", _isErr: false };
		const sysErr = { __cls: "java/io/PrintStream", _isErr: true };
		rt.registerNativeClass("java/lang/System", {
			superName: "java/lang/Object",
			staticFields: { out: sysOut, err: sysErr },
			methods: {
				"currentTimeMillis()J": function () { return BigInt(Date.now()); },
				"nanoTime()J": function () {
					const ms = typeof performance !== "undefined" ? performance.now() : Date.now();
					return BigInt(Math.round(ms * 1000000));
				},
				"arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V": function (src, srcPos, dst, dstPos, length) {
					if (src == null || dst == null) throw rt.makeException("java/lang/NullPointerException", "arraycopy null");
					const len = length | 0;
					if (len <= 0) return;
					if (src === dst && dstPos > srcPos) {
						for (let i = len - 1; i >= 0; i--) dst[dstPos + i] = src[srcPos + i];
					} else {
						for (let i = 0; i < len; i++) dst[dstPos + i] = src[srcPos + i];
					}
				},
				"identityHashCode(Ljava/lang/Object;)I": function (o) { return rt._identityHash(o); },
				"lineSeparator()Ljava/lang/String;": function () { return "\n"; },
				"getProperty(Ljava/lang/String;)Ljava/lang/String;": function (k) {
					const key = strOf(k);
					if (key === "os.name") return "Linux";
					if (key === "java.version") return "21.0.0";
					if (key === "file.separator") return "/";
					if (key === "line.separator") return "\n";
					return null;
				},
				"getProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;": function (k, def) {
					const v = rt.invokeStatic("java/lang/System", "getProperty", "(Ljava/lang/String;)Ljava/lang/String;", [k]);
					return v != null ? v : def;
				},
				"getenv(Ljava/lang/String;)Ljava/lang/String;": function () { return null; },
				"gc()V": function () {}
			}
		});

		// java/util/Objects
		rt.registerNativeClass("java/util/Objects", {
			superName: "java/lang/Object",
			methods: {
				"equals(Ljava/lang/Object;Ljava/lang/Object;)Z": function (a, b) { return jvmEquals(a, b) ? 1 : 0; },
				"deepEquals(Ljava/lang/Object;Ljava/lang/Object;)Z": function (a, b) { return jvmEquals(a, b) ? 1 : 0; },
				"hashCode(Ljava/lang/Object;)I": function (o) { return jvmHashCode(o); },
				"hash([Ljava/lang/Object;)I": function (arr) {
					if (!arr) return 0;
					let h = 1;
					for (let i = 0; i < arr.length; i++) h = (Math.imul(31, h) + jvmHashCode(arr[i])) | 0;
					return h;
				},
				"toString(Ljava/lang/Object;)Ljava/lang/String;": function (o) { return rt.jvmToString(o, "Ljava/lang/Object;"); },
				"toString(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;": function (o, def) { return o == null ? def : rt.jvmToString(o, "Ljava/lang/Object;"); },
				"requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;": function (o) { if (o == null) throw rt.makeException("java/lang/NullPointerException", null); return o; },
				"requireNonNull(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;": function (o, msg) { if (o == null) throw rt.makeException("java/lang/NullPointerException", strOf(msg)); return o; },
				"requireNonNull(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/lang/Object;": function (o, sup) {
					if (o == null) {
						const m = sup ? rt.invokeInterface(sup, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", []) : null;
						throw rt.makeException("java/lang/NullPointerException", m == null ? null : strOf(m));
					}
					return o;
				},
				"requireNonNullElse(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;": function (a, b) { return a != null ? a : b; },
				"isNull(Ljava/lang/Object;)Z": function (o) { return o == null ? 1 : 0; },
				"nonNull(Ljava/lang/Object;)Z": function (o) { return o != null ? 1 : 0; }
			}
		});

		// java/util/Arrays
		rt.registerNativeClass("java/util/Arrays", {
			superName: "java/lang/Object",
			methods: {
				"copyOf": function (arr, newLen) {
					if (!arr) throw rt.makeException("java/lang/NullPointerException", null);
					const n = Math.max(0, newLen | 0);
					if (ArrayBuffer.isView(arr)) {
						const out = new arr.constructor(n);
						out.set(arr.subarray(0, Math.min(arr.length, n)));
						out.__cls = arr.__cls;
						out.__elemType = arr.__elemType;
						return out;
					}
					const out = new Array(n).fill(null);
					for (let i = 0; i < Math.min(arr.length, n); i++) out[i] = arr[i];
					out.__cls = arr.__cls || "[Ljava/lang/Object;";
					out.__elemType = arr.__elemType;
					return out;
				},
				"copyOfRange": function (arr, from, to) {
					const n = Math.max(0, (to | 0) - (from | 0));
					if (ArrayBuffer.isView(arr)) {
						const out = new arr.constructor(n);
						for (let i = 0; i < n && from + i < arr.length; i++) out[i] = arr[from + i];
						out.__cls = arr.__cls;
						out.__elemType = arr.__elemType;
						return out;
					}
					const out = new Array(n).fill(null);
					for (let i = 0; i < n && from + i < arr.length; i++) out[i] = arr[from + i];
					out.__cls = arr.__cls || "[Ljava/lang/Object;";
					out.__elemType = arr.__elemType;
					return out;
				},
				"fill": function (arr, a, b, c) {
					if (!arr) throw rt.makeException("java/lang/NullPointerException", null);
					if (arguments.length === 2) {
						arr.fill(a);
					} else {
						for (let i = a | 0; i < (b | 0); i++) arr[i] = c;
					}
				},
				"equals": function (a, b) {
					if (a === b) return 1;
					if (!a || !b || a.length !== b.length) return 0;
					for (let i = 0; i < a.length; i++) {
						if (!jvmEquals(a[i], b[i])) return 0;
					}
					return 1;
				},
				"hashCode": function (arr) {
					if (!arr) return 0;
					let h = 1;
					for (let i = 0; i < arr.length; i++) h = (Math.imul(31, h) + jvmHashCode(arr[i])) | 0;
					return h;
				},
				"toString": function (arr) {
					if (!arr) return "null";
					const parts = [];
					for (let i = 0; i < arr.length; i++) parts.push(rt.jvmToString(arr[i], "Ljava/lang/Object;"));
					return "[" + parts.join(", ") + "]";
				},
				"deepToString([Ljava/lang/Object;)Ljava/lang/String;": function (arr) {
					return rt.invokeStatic("java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;", [arr]);
				},
				"asList([Ljava/lang/Object;)Ljava/util/List;": function (arr) {
					return makeListObj(arr ? Array.from(arr) : []);
				},
				"sort": function (arr) {
					if (!arr) return;
					if (ArrayBuffer.isView(arr)) {
						arr.sort();
					} else {
						arr.sort(function (x, y) {
							if (x === y) return 0;
							if (x == null) return -1;
							if (y == null) return 1;
							if (typeof x === "string" && typeof y === "string") return x < y ? -1 : 1;
							return rt.invokeVirtual(x, "compareTo", "(Ljava/lang/Object;)I", [y]) | 0;
						});
					}
				}
			}
		});

		// java/util/Iterator
		rt.registerNativeClass("java/util/Iterator", {
			superName: "java/lang/Object",
			isInterface: true,
			methods: {
				"hasNext()Z": function () { return this._idx < this._items.length ? 1 : 0; },
				"next()Ljava/lang/Object;": function () {
					if (this._idx >= this._items.length) throw rt.makeException("java/util/NoSuchElementException", null);
					return this._items[this._idx++];
				},
				"remove()V": function () {
					if (this._idx > 0 && this._backing) {
						this._backing.splice(--this._idx, 1);
					}
				}
			}
		});

		function makeIterator(items, backing) {
			return { __cls: "java/util/Iterator", _items: items, _backing: backing || null, _idx: 0 };
		}

		// java/util/List & ArrayList & LinkedList
		const listMethods = {
			"<init>()V": function () { this._items = []; },
			"<init>(I)V": function () { this._items = []; },
			"<init>(Ljava/util/Collection;)V": function (col) {
				this._items = [];
				if (col) rt.invokeVirtual(this, "addAll", "(Ljava/util/Collection;)Z", [col]);
			},
			"size()I": function () { return (this._items || []).length | 0; },
			"isEmpty()Z": function () { return (this._items || []).length === 0 ? 1 : 0; },
			"add(Ljava/lang/Object;)Z": function (v) { if (!this._items) this._items = []; this._items.push(v); return 1; },
			"add(ILjava/lang/Object;)V": function (i, v) { if (!this._items) this._items = []; this._items.splice(i | 0, 0, v); },
			"addAll(Ljava/util/Collection;)Z": function (col) {
				if (!this._items) this._items = [];
				if (!col) return 0;
				if (col._items) {
					for (let i = 0; i < col._items.length; i++) this._items.push(col._items[i]);
					return col._items.length > 0 ? 1 : 0;
				}
				const it = rt.invokeInterface(col, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", []);
				let changed = 0;
				while (rt.invokeInterface(it, "java/util/Iterator", "hasNext", "()Z", [])) {
					this._items.push(rt.invokeInterface(it, "java/util/Iterator", "next", "()Ljava/lang/Object;", []));
					changed = 1;
				}
				return changed;
			},
			"get(I)Ljava/lang/Object;": function (i) {
				const arr = this._items || [];
				if (i < 0 || i >= arr.length) throw rt.makeException("java/lang/IndexOutOfBoundsException", "Index: " + i);
				return arr[i];
			},
			"set(ILjava/lang/Object;)Ljava/lang/Object;": function (i, v) {
				const arr = this._items || [];
				const prev = arr[i];
				arr[i] = v;
				return prev === undefined ? null : prev;
			},
			"remove(I)Ljava/lang/Object;": function (i) {
				const arr = this._items || [];
				return arr.splice(i | 0, 1)[0];
			},
			"remove(Ljava/lang/Object;)Z": function (o) {
				const arr = this._items || [];
				for (let i = 0; i < arr.length; i++) {
					if (jvmEquals(arr[i], o)) { arr.splice(i, 1); return 1; }
				}
				return 0;
			},
			"contains(Ljava/lang/Object;)Z": function (o) {
				const arr = this._items || [];
				for (let i = 0; i < arr.length; i++) if (jvmEquals(arr[i], o)) return 1;
				return 0;
			},
			"indexOf(Ljava/lang/Object;)I": function (o) {
				const arr = this._items || [];
				for (let i = 0; i < arr.length; i++) if (jvmEquals(arr[i], o)) return i;
				return -1;
			},
			"clear()V": function () { this._items = []; },
			"iterator()Ljava/util/Iterator;": function () { const a = this._items || []; return makeIterator(a.slice(), a); },
			"forEach(Ljava/util/function/Consumer;)V": function (fn) {
				const arr = this._items || [];
				for (let i = 0; i < arr.length; i++) {
					rt.invokeInterface(fn, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", [arr[i]]);
				}
			},
			"toArray()[Ljava/lang/Object;": function () {
				const out = (this._items || []).slice();
				out.__cls = "[Ljava/lang/Object;";
				return out;
			},
			"toArray([Ljava/lang/Object;)[Ljava/lang/Object;": function (target) {
				const src = this._items || [];
				const out = src.slice();
				out.__cls = (target && target.__cls) || "[Ljava/lang/Object;";
				return out;
			},
			"toString()Ljava/lang/String;": function () {
				const arr = this._items || [];
				return "[" + arr.map(function (x) { return rt.jvmToString(x, "Ljava/lang/Object;"); }).join(", ") + "]";
			},
			"equals(Ljava/lang/Object;)Z": function (o) {
				if (this === o) return 1;
				if (!o || !o._items || (this._items || []).length !== o._items.length) return 0;
				const a = this._items || [];
				for (let i = 0; i < a.length; i++) if (!jvmEquals(a[i], o._items[i])) return 0;
				return 1;
			},
			"of": function () {
				if (arguments.length === 1 && Array.isArray(arguments[0])) return makeListObj(arguments[0]);
				return makeListObj(Array.prototype.slice.call(arguments));
			},
			"copyOf(Ljava/util/Collection;)Ljava/util/List;": function (col) {
				const out = makeListObj([]);
				rt.invokeVirtual(out, "addAll", "(Ljava/util/Collection;)Z", [col]);
				return out;
			}
		};
		rt.registerNativeClass("java/util/List", { superName: "java/lang/Object", isInterface: true, interfaces: ["java/util/Collection", "java/lang/Iterable"], methods: listMethods });
		rt.registerNativeClass("java/util/ArrayList", { superName: "java/lang/Object", interfaces: ["java/util/List", "java/util/Collection", "java/lang/Iterable"], ctor: function () { this._items = []; }, methods: Object.assign({}, listMethods) });
		rt.registerNativeClass("java/util/LinkedList", { superName: "java/lang/Object", interfaces: ["java/util/List", "java/util/Collection", "java/lang/Iterable"], ctor: function () { this._items = []; }, methods: Object.assign({}, listMethods) });

		// java/util/Set & HashSet & LinkedHashSet
		const setMethods = {
			"<init>()V": function () { this._items = []; },
			"<init>(I)V": function () { this._items = []; },
			"<init>(Ljava/util/Collection;)V": function (col) {
				this._items = [];
				if (col) rt.invokeVirtual(this, "addAll", "(Ljava/util/Collection;)Z", [col]);
			},
			"size()I": function () { return (this._items || []).length | 0; },
			"isEmpty()Z": function () { return (this._items || []).length === 0 ? 1 : 0; },
			"contains(Ljava/lang/Object;)Z": function (o) {
				const arr = this._items || [];
				for (let i = 0; i < arr.length; i++) if (jvmEquals(arr[i], o)) return 1;
				return 0;
			},
			"add(Ljava/lang/Object;)Z": function (o) {
				if (!this._items) this._items = [];
				for (let i = 0; i < this._items.length; i++) if (jvmEquals(this._items[i], o)) return 0;
				this._items.push(o);
				return 1;
			},
			"addAll(Ljava/util/Collection;)Z": listMethods["addAll(Ljava/util/Collection;)Z"],
			"remove(Ljava/lang/Object;)Z": listMethods["remove(Ljava/lang/Object;)Z"],
			"clear()V": function () { this._items = []; },
			"iterator()Ljava/util/Iterator;": function () { const a = this._items || []; return makeIterator(a.slice(), a); },
			"forEach(Ljava/util/function/Consumer;)V": listMethods["forEach(Ljava/util/function/Consumer;)V"],
			"toArray()[Ljava/lang/Object;": listMethods["toArray()[Ljava/lang/Object;"],
			"toArray([Ljava/lang/Object;)[Ljava/lang/Object;": listMethods["toArray([Ljava/lang/Object;)[Ljava/lang/Object;"],
			"toString()Ljava/lang/String;": listMethods["toString()Ljava/lang/String;"],
			"of": function () {
				if (arguments.length === 1 && Array.isArray(arguments[0])) return makeSetObj(arguments[0]);
				return makeSetObj(Array.prototype.slice.call(arguments));
			}
		};
		rt.registerNativeClass("java/util/Set", { superName: "java/lang/Object", isInterface: true, interfaces: ["java/util/Collection", "java/lang/Iterable"], methods: setMethods });
		rt.registerNativeClass("java/util/HashSet", { superName: "java/lang/Object", interfaces: ["java/util/Set", "java/util/Collection", "java/lang/Iterable"], ctor: function () { this._items = []; }, methods: Object.assign({}, setMethods) });
		rt.registerNativeClass("java/util/LinkedHashSet", { superName: "java/lang/Object", interfaces: ["java/util/Set", "java/util/Collection", "java/lang/Iterable"], ctor: function () { this._items = []; }, methods: Object.assign({}, setMethods) });

		// java/util/Map$Entry & Map & HashMap & LinkedHashMap & ConcurrentHashMap
		rt.registerNativeClass("java/util/Map$Entry", {
			superName: "java/lang/Object",
			isInterface: true,
			methods: {
				"getKey()Ljava/lang/Object;": function () { return this._key; },
				"getValue()Ljava/lang/Object;": function () { return this._val; },
				"setValue(Ljava/lang/Object;)Ljava/lang/Object;": function (v) { const old = this._val; this._val = v; return old; }
			}
		});

		const mapMethods = {
			"<init>()V": function () { this._entries = []; },
			"<init>(I)V": function () { this._entries = []; },
			"<init>(Ljava/util/Map;)V": function (m) {
				this._entries = [];
				if (m && m._entries) {
					for (let i = 0; i < m._entries.length; i++) this._entries.push([m._entries[i][0], m._entries[i][1]]);
				}
			},
			"size()I": function () { return (this._entries || []).length | 0; },
			"isEmpty()Z": function () { return (this._entries || []).length === 0 ? 1 : 0; },
			"put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;": function (k, v) {
				if (!this._entries) this._entries = [];
				for (let i = 0; i < this._entries.length; i++) {
					if (jvmEquals(this._entries[i][0], k)) {
						const old = this._entries[i][1];
						this._entries[i][1] = v;
						return old;
					}
				}
				this._entries.push([k, v]);
				return null;
			},
			"putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;": function (k, v) {
				if (!this._entries) this._entries = [];
				for (let i = 0; i < this._entries.length; i++) {
					if (jvmEquals(this._entries[i][0], k)) return this._entries[i][1];
				}
				this._entries.push([k, v]);
				return null;
			},
			"get(Ljava/lang/Object;)Ljava/lang/Object;": function (k) {
				const arr = this._entries || [];
				for (let i = 0; i < arr.length; i++) if (jvmEquals(arr[i][0], k)) return arr[i][1];
				return null;
			},
			"getOrDefault(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;": function (k, def) {
				const arr = this._entries || [];
				for (let i = 0; i < arr.length; i++) if (jvmEquals(arr[i][0], k)) return arr[i][1];
				return def;
			},
			"computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;": function (k, fn) {
				const arr = this._entries || (this._entries = []);
				for (let i = 0; i < arr.length; i++) if (jvmEquals(arr[i][0], k)) return arr[i][1];
				const v = rt.invokeInterface(fn, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [k]);
				if (v != null) arr.push([k, v]);
				return v;
			},
			"containsKey(Ljava/lang/Object;)Z": function (k) {
				const arr = this._entries || [];
				for (let i = 0; i < arr.length; i++) if (jvmEquals(arr[i][0], k)) return 1;
				return 0;
			},
			"containsValue(Ljava/lang/Object;)Z": function (v) {
				const arr = this._entries || [];
				for (let i = 0; i < arr.length; i++) if (jvmEquals(arr[i][1], v)) return 1;
				return 0;
			},
			"remove(Ljava/lang/Object;)Ljava/lang/Object;": function (k) {
				const arr = this._entries || [];
				for (let i = 0; i < arr.length; i++) {
					if (jvmEquals(arr[i][0], k)) return arr.splice(i, 1)[0][1];
				}
				return null;
			},
			"clear()V": function () { this._entries = []; },
			"keySet()Ljava/util/Set;": function () {
				return makeSetObj((this._entries || []).map(function (e) { return e[0]; }));
			},
			"values()Ljava/util/Collection;": function () {
				return makeListObj((this._entries || []).map(function (e) { return e[1]; }));
			},
			"entrySet()Ljava/util/Set;": function () {
				return makeSetObj((this._entries || []).map(function (e) {
					return { __cls: "java/util/Map$Entry", _key: e[0], _val: e[1] };
				}));
			},
			"forEach(Ljava/util/function/BiConsumer;)V": function (fn) {
				const arr = this._entries || [];
				for (let i = 0; i < arr.length; i++) {
					rt.invokeInterface(fn, "java/util/function/BiConsumer", "accept", "(Ljava/lang/Object;Ljava/lang/Object;)V", [arr[i][0], arr[i][1]]);
				}
			},
			"toString()Ljava/lang/String;": function () {
				const arr = this._entries || [];
				return "{" + arr.map(function (e) {
					return rt.jvmToString(e[0], "Ljava/lang/Object;") + "=" + rt.jvmToString(e[1], "Ljava/lang/Object;");
				}).join(", ") + "}";
			},
			"entry(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map$Entry;": function (k, v) {
				return { __cls: "java/util/Map$Entry", _key: k, _val: v };
			},
			"of": function () {
				const entries = [];
				for (let i = 0; i + 1 < arguments.length; i += 2) entries.push([arguments[i], arguments[i + 1]]);
				return makeMapObj(entries);
			}
		};
		rt.registerNativeClass("java/util/Map", { superName: "java/lang/Object", isInterface: true, methods: mapMethods });
		rt.registerNativeClass("java/util/HashMap", { superName: "java/lang/Object", interfaces: ["java/util/Map"], ctor: function () { this._entries = []; }, methods: Object.assign({}, mapMethods) });
		rt.registerNativeClass("java/util/LinkedHashMap", { superName: "java/lang/Object", interfaces: ["java/util/Map"], ctor: function () { this._entries = []; }, methods: Object.assign({}, mapMethods) });
		rt.registerNativeClass("java/util/concurrent/ConcurrentHashMap", { superName: "java/lang/Object", interfaces: ["java/util/Map"], ctor: function () { this._entries = []; }, methods: Object.assign({}, mapMethods) });

		// java/util/Optional
		const OPT_EMPTY = { __cls: "java/util/Optional", _value: null };
		rt.registerNativeClass("java/util/Optional", {
			superName: "java/lang/Object",
			methods: {
				"empty()Ljava/util/Optional;": function () { return OPT_EMPTY; },
				"of(Ljava/lang/Object;)Ljava/util/Optional;": function (v) {
					if (v == null) throw rt.makeException("java/lang/NullPointerException", null);
					return { __cls: "java/util/Optional", _value: v };
				},
				"ofNullable(Ljava/lang/Object;)Ljava/util/Optional;": function (v) {
					return v == null ? OPT_EMPTY : { __cls: "java/util/Optional", _value: v };
				},
				"isPresent()Z": function () { return this._value != null ? 1 : 0; },
				"isEmpty()Z": function () { return this._value == null ? 1 : 0; },
				"get()Ljava/lang/Object;": function () {
					if (this._value == null) throw rt.makeException("java/util/NoSuchElementException", "No value present");
					return this._value;
				},
				"orElse(Ljava/lang/Object;)Ljava/lang/Object;": function (other) { return this._value != null ? this._value : other; },
				"orElseGet(Ljava/util/function/Supplier;)Ljava/lang/Object;": function (sup) {
					return this._value != null ? this._value : rt.invokeInterface(sup, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", []);
				},
				"ifPresent(Ljava/util/function/Consumer;)V": function (fn) {
					if (this._value != null) rt.invokeInterface(fn, "java/util/function/Consumer", "accept", "(Ljava/lang/Object;)V", [this._value]);
				},
				"map(Ljava/util/function/Function;)Ljava/util/Optional;": function (fn) {
					if (this._value == null) return OPT_EMPTY;
					const mapped = rt.invokeInterface(fn, "java/util/function/Function", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [this._value]);
					return mapped == null ? OPT_EMPTY : { __cls: "java/util/Optional", _value: mapped };
				},
				"filter(Ljava/util/function/Predicate;)Ljava/util/Optional;": function (pred) {
					if (this._value == null) return OPT_EMPTY;
					return rt.invokeInterface(pred, "java/util/function/Predicate", "test", "(Ljava/lang/Object;)Z", [this._value]) ? this : OPT_EMPTY;
				}
			}
		});

		// java/util/Collections
		rt.registerNativeClass("java/util/Collections", {
			superName: "java/lang/Object",
			methods: {
				"emptyList()Ljava/util/List;": function () { return makeListObj([]); },
				"emptySet()Ljava/util/Set;": function () { return makeSetObj([]); },
				"emptyMap()Ljava/util/Map;": function () { return makeMapObj([]); },
				"singletonList(Ljava/lang/Object;)Ljava/util/List;": function (v) { return makeListObj([v]); },
				"singleton(Ljava/lang/Object;)Ljava/util/Set;": function (v) { return makeSetObj([v]); },
				"singletonMap(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;": function (k, v) { return makeMapObj([[k, v]]); },
				"unmodifiableList(Ljava/util/List;)Ljava/util/List;": function (l) { return l; },
				"unmodifiableSet(Ljava/util/Set;)Ljava/util/Set;": function (s) { return s; },
				"unmodifiableMap(Ljava/util/Map;)Ljava/util/Map;": function (m) { return m; }
			}
		});

		// java/util/Random
		rt.registerNativeClass("java/util/Random", {
			superName: "java/lang/Object",
			methods: {
				"<init>()V": function () {},
				"<init>(J)V": function () {},
				"nextInt()I": function () { return (Math.random() * 4294967296) | 0; },
				"nextInt(I)I": function (bound) { return Math.floor(Math.random() * (bound | 0)) | 0; },
				"nextFloat()F": function () { return Math.fround(Math.random()); },
				"nextDouble()D": function () { return Math.random(); },
				"nextBoolean()Z": function () { return Math.random() < 0.5 ? 1 : 0; },
				"nextLong()J": function () { return BigInt(Math.floor(Math.random() * Number.MAX_SAFE_INTEGER)); }
			}
		});

		// java/util/UUID
		rt.registerNativeClass("java/util/UUID", {
			superName: "java/lang/Object",
			interfaces: ["java/io/Serializable", "java/lang/Comparable"],
			methods: {
				"<init>(JJ)V": function (msb, lsb) {
					this._msb = BigInt.asIntN(64, msb);
					this._lsb = BigInt.asIntN(64, lsb);
				},
				"randomUUID()Ljava/util/UUID;": function () {
					const hex = function (n) {
						let s = "";
						for (let i = 0; i < n; i++) s += ((Math.random() * 16) | 0).toString(16);
						return s;
					};
					const str = hex(8) + "-" + hex(4) + "-4" + hex(3) + "-" + (8 + ((Math.random() * 4) | 0)).toString(16) + hex(3) + "-" + hex(12);
					return rt.invokeStatic("java/util/UUID", "fromString", "(Ljava/lang/String;)Ljava/util/UUID;", [str]);
				},
				"fromString(Ljava/lang/String;)Ljava/util/UUID;": function (s) {
					const clean = strOf(s).replace(/-/g, "");
					const msb = BigInt.asIntN(64, BigInt("0x" + clean.slice(0, 16)));
					const lsb = BigInt.asIntN(64, BigInt("0x" + clean.slice(16, 32)));
					return { __cls: "java/util/UUID", _msb: msb, _lsb: lsb, _str: strOf(s) };
				},
				"getMostSignificantBits()J": function () { return this._msb || 0n; },
				"getLeastSignificantBits()J": function () { return this._lsb || 0n; },
				"toString()Ljava/lang/String;": function () {
					if (this._str) return this._str;
					const h1 = BigInt.asUintN(64, this._msb || 0n).toString(16).padStart(16, "0");
					const h2 = BigInt.asUintN(64, this._lsb || 0n).toString(16).padStart(16, "0");
					return h1.slice(0, 8) + "-" + h1.slice(8, 12) + "-" + h1.slice(12, 16) + "-" + h2.slice(0, 4) + "-" + h2.slice(4, 16);
				},
				"equals(Ljava/lang/Object;)Z": function (o) {
					return (o && o.__cls === "java/util/UUID" && o._msb === this._msb && o._lsb === this._lsb) ? 1 : 0;
				},
				"hashCode()I": function () {
					const x = (this._msb || 0n) ^ (this._lsb || 0n);
					return Number(BigInt.asIntN(32, x ^ (BigInt.asUintN(64, x) >> 32n)));
				}
			}
		});
	}

	function formatJavaPrintf(rt, fmt, args) {
		let idx = 0;
		return fmt.replace(/%(?:(\d+)\$)?([-#+ 0,(]*)(?:\d+)?(?:\.(\d+))?([%sSdDxXoOfFeEgGbBcCn])/g, function (_m, pos, _flags, prec, spec) {
			if (spec === "%") return "%";
			if (spec === "n") return "\n";
			const aIdx = pos ? (parseInt(pos, 10) - 1) : idx++;
			const val = args[aIdx];
			if (spec === "s" || spec === "S") {
				const s = rt.jvmToString(val, "Ljava/lang/Object;");
				return spec === "S" ? s.toUpperCase() : s;
			}
			if (spec === "d" || spec === "x" || spec === "X" || spec === "o") {
				const num = val && typeof val === "object" && "value" in val ? val.value : val;
				const base = spec === "d" ? 10 : (spec === "o" ? 8 : 16);
				const str = typeof num === "bigint" ? num.toString(base) : (num | 0).toString(base);
				return spec === "X" ? str.toUpperCase() : str;
			}
			if (spec === "f" || spec === "F" || spec === "e" || spec === "E" || spec === "g" || spec === "G") {
				const num = Number(val && typeof val === "object" && "value" in val ? val.value : val);
				const p = prec !== undefined ? parseInt(prec, 10) : 6;
				return spec === "f" || spec === "F" ? num.toFixed(p) : num.toPrecision(Math.max(1, p));
			}
			if (spec === "b" || spec === "B") {
				const b = val != null && val !== false && val !== 0 && !(val.__cls === "java/lang/Boolean" && !val.value);
				return spec === "B" ? (b ? "TRUE" : "FALSE") : (b ? "true" : "false");
			}
			if (spec === "c" || spec === "C") {
				const ch = typeof val === "number" ? String.fromCharCode(val & 0xFFFF) : String(val);
				return spec === "C" ? ch.toUpperCase() : ch;
			}
			return rt.jvmToString(val, "Ljava/lang/Object;");
		});
	}

	// =========================================================================
	// 6. JVM RUNTIME (createRuntime)
	// =========================================================================

	function createRuntime() {
		const nativeClasses = new Map();
		const definedClasses = new Map();
		const stubClasses = new Map();
		const loggedStubs = new Set();
		const aliases = new Map();
		const memberAliases = new Map(); // owner -> Map(fromName -> toName)
		const resolvedNameCache = new Map();
		const classObjects = new Map();
		const hooks = new Map(); // hookName -> Array<{ modId, fn }>
		const identityHashes = new WeakMap();
		const activeICs = [];
		let cacheEpoch = 1;
		let nextIdentityHash = 1;

		function invalidateCaches() {
			cacheEpoch++;
			resolvedNameCache.clear();
			if (activeICs.length > 0) {
				for (let i = 0; i < activeICs.length; i++) {
					const ic = activeICs[i];
					ic.fn = null;
					ic.cls = "";
					ic.isStr = false;
					ic.sf = null;
					ic.alloc = null;
					ic.mode = 0;
					ic.get = null;
					ic.set = null;
					ic.ck = null;
					ic._inList = false;
				}
				activeICs.length = 0;
			}
		}

		const rt = {
			nativeClasses,
			definedClasses,
			stubClasses,
			hooks,
			_ics: activeICs,
			_boolTrue: null,
			_boolFalse: null,
			compiledMethodCount: 0,
			currentModId: null,
			__bridgeInstalled: false,

			parseMethodDescriptor,

			_identityHash(obj) {
				if (obj == null) return 0;
				if (typeof obj === "string") return javaStringHashCode(obj);
				if (typeof obj !== "object" && typeof obj !== "function") return Number(obj) | 0;
				let h = identityHashes.get(obj);
				if (h === undefined) {
					h = (Math.imul(nextIdentityHash++, 2654435761)) | 0;
					if (h === 0) h = 1;
					identityHashes.set(obj, h);
				}
				return h;
			},

			_makePrimitiveClass(primDesc) {
				const existing = classObjects.get(primDesc);
				if (existing) return existing;
				const obj = { __cls: "java/lang/Class", _internalName: primDesc, _isPrimitive: true };
				classObjects.set(primDesc, obj);
				return obj;
			},

			getClassObject(internalName) {
				const canon = this.resolveClassName(internalName);
				let obj = classObjects.get(canon);
				if (!obj) {
					obj = { __cls: "java/lang/Class", _internalName: canon, _isPrimitive: false };
					classObjects.set(canon, obj);
				}
				return obj;
			},

			registerAlias(fromName, toName) {
				const from = String(fromName).replace(/\./g, "/");
				const to = String(toName).replace(/\./g, "/");
				if (from && to && from !== to) {
					aliases.set(from, to);
					invalidateCaches();
				}
			},

			registerMemberAlias(owner, fromName, toName) {
				const ownKey = owner === "*" ? "*" : String(owner).replace(/\./g, "/");
				let map = memberAliases.get(ownKey);
				if (!map) {
					map = new Map();
					memberAliases.set(ownKey, map);
				}
				map.set(fromName, toName);
				invalidateCaches();
			},

			resolveClassName(name) {
				if (!name) return "";
				const cached = resolvedNameCache.get(name);
				if (cached !== undefined) return cached;
				let cur = name.indexOf(".") >= 0 ? String(name).replace(/\./g, "/") : String(name);
				if (cur.charCodeAt(0) === 91) { // array descriptor e.g. [Lnet/minecraft/class_310;
					let dims = 0;
					while (dims < cur.length && cur.charCodeAt(dims) === 91) dims++;
					if (cur.charCodeAt(dims) === 76 && cur.charCodeAt(cur.length - 1) === 59) {
						const elem = this.resolveClassName(cur.slice(dims + 1, -1));
						const res = cur.slice(0, dims) + "L" + elem + ";";
						resolvedNameCache.set(name, res);
						return res;
					}
					resolvedNameCache.set(name, cur);
					return cur;
				}
				for (let depth = 0; depth < 16; depth++) {
					const next = aliases.get(cur);
					if (!next || next === cur) break;
					cur = next;
				}
				resolvedNameCache.set(name, cur);
				return cur;
			},

			resolveMemberAlias(owner, memberName) {
				if (!memberName || memberAliases.size === 0) return memberName;
				if (owner) {
					const m1 = memberAliases.get(owner);
					if (m1) {
						const h1 = m1.get(memberName);
						if (h1 !== undefined) return h1;
					}
					const canonOwner = this.resolveClassName(owner);
					if (canonOwner !== owner) {
						const m2 = memberAliases.get(canonOwner);
						if (m2) {
							const h2 = m2.get(memberName);
							if (h2 !== undefined) return h2;
						}
					}
				}
				const wild = memberAliases.get("*");
				if (wild) {
					const hw = wild.get(memberName);
					if (hw !== undefined) return hw;
				}
				return memberName;
			},

			canonicalizeDescriptor(desc) {
				if (!desc || desc.indexOf("L") === -1) return desc;
				const self = this;
				return desc.replace(/L([^;]+);/g, function (_m, cn) {
					return "L" + self.resolveClassName(cn) + ";";
				});
			},

			registerNativeClass(internalName, def) {
				const name = String(internalName).replace(/\./g, "/");
				const d = def || {};
				let existing = nativeClasses.get(name);
				if (existing && !d.replace) {
					if (d.superName !== undefined) existing.superName = d.superName ? String(d.superName).replace(/\./g, "/") : null;
					if (d.interfaces) {
						for (let i = 0; i < d.interfaces.length; i++) {
							const ifn = String(d.interfaces[i]).replace(/\./g, "/");
							if (existing.interfaces.indexOf(ifn) === -1) existing.interfaces.push(ifn);
						}
					}
					if (d.isInterface !== undefined) existing.isInterface = !!d.isInterface;
					if (d.ctor) existing.ctor = d.ctor;
					if (d.staticFields) copyOwnPropsWithDescriptors(existing.staticFields, d.staticFields);
					if (d.fields) Object.assign(existing.fields, d.fields);
					if (d.methods) Object.assign(existing.methods, d.methods);
				} else {
					const staticFields = Object.create(null);
					if (d.staticFields) copyOwnPropsWithDescriptors(staticFields, d.staticFields);
					const fields = Object.create(null);
					if (d.fields) Object.assign(fields, d.fields);
					const methods = Object.create(null);
					if (d.methods) Object.assign(methods, d.methods);
					existing = {
						name,
						superName: d.superName === undefined ? (name === "java/lang/Object" ? null : "java/lang/Object") : (d.superName ? String(d.superName).replace(/\./g, "/") : null),
						interfaces: (d.interfaces || []).map(function (x) { return String(x).replace(/\./g, "/"); }),
						isInterface: !!d.isInterface,
						isNative: true,
						isStub: false,
						initState: 2,
						staticFields,
						fields,
						methods,
						ctor: typeof d.ctor === "function" ? d.ctor : null,
						_vcache: Object.create(null),
						_vcacheEpoch: 0,
						_allocFn: null,
						_allocEpoch: 0,
						_hasFieldTraps: false,
						_trapsEpoch: 0
					};
					nativeClasses.set(name, existing);
				}
				if (name === "java/lang/Boolean" && existing.staticFields) {
					if (existing.staticFields.TRUE) this._boolTrue = existing.staticFields.TRUE;
					if (existing.staticFields.FALSE) this._boolFalse = existing.staticFields.FALSE;
				}
				stubClasses.delete(name);
				if (Array.isArray(d.aliases)) {
					for (let i = 0; i < d.aliases.length; i++) {
						this.registerAlias(d.aliases[i], name);
					}
				}
				invalidateCaches();
				return existing;
			},

			defineClass(parsedClass) {
				if (!parsedClass || !parsedClass.name) {
					throw new Error("Invalid parsed ClassFile");
				}
				const name = parsedClass.name;
				// Never let bundled JiJ copies overwrite registered native bridge classes
				if (nativeClasses.has(name)) {
					return nativeClasses.get(name);
				}

				const staticFields = Object.create(null);
				const instanceFieldDefs = [];
				const staticFieldSet = new Set();
				for (let i = 0; i < parsedClass.fields.length; i++) {
					const f = parsedClass.fields[i];
					if ((f.accessFlags & ACC_STATIC) !== 0) {
						staticFieldSet.add(f.name);
						staticFields[f.name] = f.constantValue !== undefined ? f.constantValue : defaultValueForDesc(f.descriptor);
					} else {
						instanceFieldDefs.push(f);
					}
				}

				const methods = Object.create(null);
				const methodDefs = Object.create(null);
				for (let i = 0; i < parsedClass.methods.length; i++) {
					const m = parsedClass.methods[i];
					const key = m.name + m.descriptor;
					methodDefs[key] = m;
				}

				const clsObj = {
					name,
					superName: parsedClass.superName,
					interfaces: parsedClass.interfaces ? parsedClass.interfaces.slice() : [],
					isInterface: (parsedClass.accessFlags & ACC_INTERFACE) !== 0,
					isNative: false,
					isStub: false,
					initState: 0,
					initError: null,
					parsed: parsedClass,
					cp: parsedClass.cp,
					bootstrapMethods: parsedClass.bootstrapMethods || [],
					staticFields,
					staticFieldSet,
					instanceFieldDefs,
					fields: Object.create(null),
					methods,
					methodDefs,
					ctor: null,
					_vcache: Object.create(null),
					_vcacheEpoch: 0,
					_allocFn: null,
					_allocEpoch: 0,
					_hasFieldTraps: false,
					_trapsEpoch: 0
				};

				definedClasses.set(name, clsObj);
				stubClasses.delete(name);
				invalidateCaches();
				return clsObj;
			},

			_resolveClassNoInit(internalName) {
				const canon = this.resolveClassName(internalName);
				if (!canon) return this._getOrCreateStub("java/lang/Object");
				let cls = nativeClasses.get(canon);
				if (cls) return cls;
				cls = definedClasses.get(canon);
				if (cls) return cls;
				if (canon.charCodeAt(0) === 91) {
					let arrCls = stubClasses.get(canon);
					if (!arrCls) {
						arrCls = {
							name: canon,
							superName: "java/lang/Object",
							interfaces: ["java/lang/Cloneable", "java/io/Serializable"],
							isArray: true,
							isNative: true,
							isStub: false,
							initState: 2,
							staticFields: Object.create(null),
							fields: Object.create(null),
							methods: Object.create(null),
							ctor: null
						};
						stubClasses.set(canon, arrCls);
					}
					return arrCls;
				}
				return this._getOrCreateStub(canon);
			},

			_getOrCreateStub(canonName) {
				let stub = stubClasses.get(canonName);
				if (!stub) {
					stub = {
						name: canonName,
						superName: "java/lang/Object",
						interfaces: [],
						isNative: true,
						isStub: true,
						initState: 2,
						staticFields: Object.create(null),
						fields: Object.create(null),
						methods: Object.create(null),
						ctor: null
					};
					stubClasses.set(canonName, stub);
					if (!loggedStubs.has(canonName)) {
						loggedStubs.add(canonName);
						if (typeof console !== "undefined" && console.warn) {
							console.warn("[EaglerJvmTranslator] Soft-stubbing unknown class:", canonName);
						}
					}
				}
				return stub;
			},

			_ensureInitialized(cls) {
				if (!cls || cls.initState === 2 || cls.initState === 1 || cls.initState === 3) {
					return;
				}
				cls.initState = 1;
				const savedSd = COMPILED_HELPERS.sd;
				try {
					if (cls.superName) {
						const sup = this._resolveClassNoInit(cls.superName);
						if (sup && sup !== cls) {
							this._ensureInitialized(sup);
						}
					}
					const clinitDef = cls.methodDefs && cls.methodDefs["<clinit>()V"];
					if (clinitDef) {
						let fn = cls.methods["<clinit>()V"];
						if (!fn) {
							fn = this.compileMethod(cls, clinitDef);
							cls.methods["<clinit>()V"] = fn;
						}
						fn(this, EMPTY_ARGS);
					}
					cls.initState = 2;
				} catch (err) {
					COMPILED_HELPERS.sd = savedSd;
					cls.initState = 3;
					cls.initError = err;
					throw err;
				}
			},

			loadClass(internalName) {
				const cls = this._resolveClassNoInit(internalName);
				if (cls.initState === 0) {
					this._ensureInitialized(cls);
				}
				return cls;
			},

			compileMethod(cls, method) {
				return compileJvmMethod(this, cls, method);
			},

			_getCompiledOrNativeMethod(cls, name, desc, canonDesc, preKey) {
				if (!cls) return null;
				const rName = this.resolveMemberAlias(cls.name, name);
				const methods = cls.methods;
				const methodDefs = cls.methodDefs;
				const k0 = (preKey && rName === name) ? preKey : (rName + desc);

				// 1. Fast exact descriptor hit (zero array allocation!)
				if (methods && Object.prototype.hasOwnProperty.call(methods, k0)) {
					return { fn: methods[k0], owner: cls };
				}
				if (methodDefs && Object.prototype.hasOwnProperty.call(methodDefs, k0)) {
					const compiled = this.compileMethod(cls, methodDefs[k0]);
					methods[k0] = compiled;
					return { fn: compiled, owner: cls };
				}

				if (rName !== name) {
					const kRaw = preKey || (name + desc);
					if (methods && Object.prototype.hasOwnProperty.call(methods, kRaw)) {
						return { fn: methods[kRaw], owner: cls };
					}
					if (methodDefs && Object.prototype.hasOwnProperty.call(methodDefs, kRaw)) {
						const compiled = this.compileMethod(cls, methodDefs[kRaw]);
						methods[kRaw] = compiled;
						return { fn: compiled, owner: cls };
					}
				}

				// 2. Lazy canonicalized descriptor fallback (only if descriptor contains 'L')
				const cd = canonDesc !== null ? canonDesc : (desc.indexOf("L") >= 0 ? this.canonicalizeDescriptor(desc) : desc);
				if (cd !== desc) {
					const k1 = rName + cd;
					if (methods && Object.prototype.hasOwnProperty.call(methods, k1)) {
						return { fn: methods[k1], owner: cls };
					}
					if (methodDefs && Object.prototype.hasOwnProperty.call(methodDefs, k1)) {
						const compiled = this.compileMethod(cls, methodDefs[k1]);
						methods[k1] = compiled;
						return { fn: compiled, owner: cls };
					}
					if (rName !== name) {
						const k2 = name + cd;
						if (methods && Object.prototype.hasOwnProperty.call(methods, k2)) {
							return { fn: methods[k2], owner: cls };
						}
						if (methodDefs && Object.prototype.hasOwnProperty.call(methodDefs, k2)) {
							const compiled = this.compileMethod(cls, methodDefs[k2]);
							methods[k2] = compiled;
							return { fn: compiled, owner: cls };
						}
					}
				}

				// 3. Native name-only fallback
				if (cls.isNative && methods) {
					if (Object.prototype.hasOwnProperty.call(methods, rName)) {
						return { fn: methods[rName], owner: cls };
					}
					if (rName !== name && Object.prototype.hasOwnProperty.call(methods, name)) {
						return { fn: methods[name], owner: cls };
					}
				}
				return null;
			},

			_lookupMethodHierarchy(startCls, name, desc, searchInterfaces, preKey) {
				if (!startCls) return null;
				const cacheKey = preKey || (name + desc);
				if (searchInterfaces) {
					if (startCls._vcacheEpoch === cacheEpoch && startCls._vcache) {
						const cached = startCls._vcache[cacheKey];
						if (cached !== undefined) return cached;
					}
				} else if (startCls._scacheEpoch === cacheEpoch && startCls._scache) {
					const cached = startCls._scache[cacheKey];
					if (cached !== undefined) return cached;
				}
				let canonDesc = null;
				let cur = startCls;
				let depth = 0;
				while (cur && depth++ < 64) {
					const hit = this._getCompiledOrNativeMethod(cur, name, desc, canonDesc, cacheKey);
					if (hit) {
						if (searchInterfaces) {
							if (startCls._vcacheEpoch !== cacheEpoch || !startCls._vcache) {
								startCls._vcache = Object.create(null);
								startCls._vcacheEpoch = cacheEpoch;
							}
							startCls._vcache[cacheKey] = hit;
						} else {
							if (startCls._scacheEpoch !== cacheEpoch || !startCls._scache) {
								startCls._scache = Object.create(null);
								startCls._scacheEpoch = cacheEpoch;
							}
							startCls._scache[cacheKey] = hit;
						}
						return hit;
					}
					cur = cur.superName ? this._resolveClassNoInit(cur.superName) : null;
				}
				if (searchInterfaces) {
					const queue = [];
					const ifaceVisited = new Set();
					let c = startCls;
					let d2 = 0;
					while (c && d2++ < 64) {
						if (c.interfaces) {
							for (let i = 0; i < c.interfaces.length; i++) queue.push(c.interfaces[i]);
						}
						c = c.superName ? this._resolveClassNoInit(c.superName) : null;
					}
					while (queue.length > 0) {
						const iname = queue.shift();
						const icls = this._resolveClassNoInit(iname);
						if (!icls || ifaceVisited.has(icls.name)) continue;
						ifaceVisited.add(icls.name);
						const hit = this._getCompiledOrNativeMethod(icls, name, desc, canonDesc, cacheKey);
						if (hit) {
							if (startCls._vcacheEpoch !== cacheEpoch || !startCls._vcache) {
								startCls._vcache = Object.create(null);
								startCls._vcacheEpoch = cacheEpoch;
							}
							startCls._vcache[cacheKey] = hit;
							return hit;
						}
						if (icls.interfaces) {
							for (let i = 0; i < icls.interfaces.length; i++) queue.push(icls.interfaces[i]);
						}
					}
				}
				return null;
			},

			_callFound(found, receiver, args) {
				const fn = found.fn;
				const savedSd = COMPILED_HELPERS.sd;
				try {
					if (fn.__isCompiled) {
						if (fn.__direct) {
							if (receiver !== undefined) {
								const n = args.length;
								if (n === 0) return fn.__direct(this, receiver);
								if (n === 1) return fn.__direct(this, receiver, args[0]);
								if (n === 2) return fn.__direct(this, receiver, args[0], args[1]);
								if (n === 3) return fn.__direct(this, receiver, args[0], args[1], args[2]);
							} else {
								const n = args.length;
								if (n === 0) return fn.__direct(this);
								if (n === 1) return fn.__direct(this, args[0]);
								if (n === 2) return fn.__direct(this, args[0], args[1]);
								if (n === 3) return fn.__direct(this, args[0], args[1], args[2]);
								if (n === 4) return fn.__direct(this, args[0], args[1], args[2], args[3]);
							}
						}
						if (receiver !== undefined) {
							const callArgs = new Array(args.length + 1);
							callArgs[0] = receiver;
							for (let i = 0; i < args.length; i++) callArgs[i + 1] = args[i];
							return fn(this, callArgs);
						}
						return fn(this, args);
					}
					return fn.apply(receiver !== undefined ? receiver : found.owner, args);
				} catch (err) {
					COMPILED_HELPERS.sd = savedSd;
					throw err;
				}
			},

			_stubReturn(receiver, clsName, name, desc) {
				if (name === "<init>") return undefined;
				if (name === "toString" && desc === "()Ljava/lang/String;") return "[Stub:" + clsName + "]";
				if (name === "hashCode" && desc === "()I") return this._identityHash(receiver);
				const ret = parseMethodDescriptor(desc).ret;
				if (ret === "V") return undefined;
				const ch = ret.charCodeAt(0);
				if (ch === 74) return 0n; // J
				if (ch === 91) return []; // array
				if (ch !== 76) return 0; // primitive
				const retClsName = this.resolveClassName(descToInternalName(ret));
				if (retClsName === "java/lang/String") return "";
				if (retClsName === "java/util/Optional") return this.invokeStatic("java/util/Optional", "empty", "()Ljava/util/Optional;", EMPTY_ARGS);
				if (receiver && retClsName === clsName) return receiver; // fluent builder stub
				if (!nativeClasses.has(retClsName) && !definedClasses.has(retClsName)) {
					return this.newInstance(retClsName);
				}
				return null;
			},

			_newForBytecode(internalName) {
				const cls = this.loadClass(internalName);
				return this._getAllocFn(cls)();
			},

			newInstance(internalName, initArgs) {
				const cls = this.loadClass(internalName);
				const obj = this._getAllocFn(cls)();
				if (Array.isArray(initArgs)) {
					this.invokeSpecial(obj, cls.name, "<init>", "()V", initArgs);
				}
				return obj;
			},

			_getAllocFn(cls) {
				if (cls._allocFn && cls._allocEpoch === cacheEpoch) {
					return cls._allocFn;
				}
				const chain = [];
				const seen = new Set();
				let cur = cls;
				while (cur && !seen.has(cur.name)) {
					seen.add(cur.name);
					chain.push(cur);
					cur = cur.superName ? this._resolveClassNoInit(cur.superName) : null;
				}
				const plainMap = new Map();
				const reservedMap = new Map();
				const dynFields = [];
				for (let i = chain.length - 1; i >= 0; i--) {
					const c = chain[i];
					if (c.instanceFieldDefs) {
						for (let k = 0; k < c.instanceFieldDefs.length; k++) {
							const fd = c.instanceFieldDefs[k];
							const defVal = defaultValueForDesc(fd.descriptor);
							if (isReservedFieldKey(fd.name)) {
								reservedMap.set(fd.name, defVal);
							} else {
								plainMap.set(fd.name, defVal);
							}
						}
					}
					if (c.isNative && c.fields) {
						const fKeys = Object.keys(c.fields);
						for (let k = 0; k < fKeys.length; k++) {
							const fk = fKeys[k];
							const fv = c.fields[fk];
							if (fv && typeof fv === "object" && (typeof fv.get === "function" || typeof fv.set === "function")) {
								continue;
							}
							if (typeof fv === "function") {
								dynFields.push([fk, fv, isReservedFieldKey(fk)]);
							} else if (isReservedFieldKey(fk)) {
								reservedMap.set(fk, fv);
							} else {
								plainMap.set(fk, fv);
							}
						}
					}
				}
				const hasCtor = !!(cls.isNative && cls.ctor && cls.ctor.length === 0);
				const ctorFn = hasCtor ? cls.ctor : null;
				const clsName = cls.name;
				const isStub = !!cls.isStub;

				let canUseLiteral = !isStub && reservedMap.size === 0 && dynFields.length === 0 && plainMap.size <= 32;
				let props = null;
				if (canUseLiteral) {
					props = ['"__cls":' + JSON.stringify(clsName)];
					for (const [k, v] of plainMap.entries()) {
						let lit;
						if (v === null) lit = "null";
						else if (v === 0) lit = "0";
						else if (v === 0n) lit = "0n";
						else if (typeof v === "number" && Number.isFinite(v)) lit = String(v);
						else if (typeof v === "boolean") lit = v ? "true" : "false";
						else if (typeof v === "string") lit = JSON.stringify(v);
						else { canUseLiteral = false; break; }
						props.push(JSON.stringify(k) + ":" + lit);
					}
				}

				let allocFn;
				if (canUseLiteral) {
					if (hasCtor) {
						const rawAlloc = new Function("ctor", "return function () { const o = {" + props.join(",") + "}; ctor.call(o); o.__ctorRan = true; return o; };");
						allocFn = rawAlloc(ctorFn);
					} else {
						allocFn = new Function("return {" + props.join(",") + "};");
					}
				} else {
					const plainEntries = Array.from(plainMap.entries());
					const reservedEntries = Array.from(reservedMap.entries());
					const self = this;
					allocFn = function () {
						const obj = { __cls: clsName };
						if (isStub) obj.__isStub = true;
						for (let i = 0; i < plainEntries.length; i++) {
							obj[plainEntries[i][0]] = plainEntries[i][1];
						}
						if (reservedEntries.length > 0) {
							const rf = Object.create(null);
							for (let i = 0; i < reservedEntries.length; i++) {
								rf[reservedEntries[i][0]] = reservedEntries[i][1];
							}
							obj.__fields = rf;
						}
						for (let i = 0; i < dynFields.length; i++) {
							const df = dynFields[i];
							const val = df[1].call(obj, self);
							if (df[2]) {
								if (!obj.__fields) obj.__fields = Object.create(null);
								obj.__fields[df[0]] = val;
							} else {
								obj[df[0]] = val;
							}
						}
						if (ctorFn) {
							ctorFn.call(obj);
							obj.__ctorRan = true;
						}
						return obj;
					};
				}

				cls._allocFn = allocFn;
				cls._allocEpoch = cacheEpoch;
				return allocFn;
			},

			_allocInstance(cls) {
				return this._getAllocFn(cls)();
			},

			_hasFieldTraps(cls) {
				if (!cls) return false;
				if (cls._trapsEpoch === cacheEpoch) return cls._hasFieldTraps;
				let has = false;
				let cur = cls;
				let depth = 0;
				while (cur && depth++ < 64) {
					if (cur.isNative && cur.fields) {
						const ks = Object.keys(cur.fields);
						for (let i = 0; i < ks.length; i++) {
							const fv = cur.fields[ks[i]];
							if (fv && typeof fv === "object" && (typeof fv.get === "function" || typeof fv.set === "function")) {
								has = true;
								break;
							}
						}
						if (has) break;
					}
					cur = cur.superName ? this._resolveClassNoInit(cur.superName) : null;
				}
				cls._hasFieldTraps = has;
				cls._trapsEpoch = cacheEpoch;
				return has;
			},

			_newPrimitiveArray(atype, count) {
				const n = count | 0;
				if (n < 0) throw this.makeException("java/lang/NegativeArraySizeException", String(n));
				let arr;
				switch (atype) {
					case 4: arr = new Uint8Array(n); arr.__cls = "[Z"; arr.__elemType = "Z"; break;
					case 5: arr = new Uint16Array(n); arr.__cls = "[C"; arr.__elemType = "C"; break;
					case 6: arr = new Float32Array(n); arr.__cls = "[F"; arr.__elemType = "F"; break;
					case 7: arr = new Float64Array(n); arr.__cls = "[D"; arr.__elemType = "D"; break;
					case 8: arr = new Int8Array(n); arr.__cls = "[B"; arr.__elemType = "B"; break;
					case 9: arr = new Int16Array(n); arr.__cls = "[S"; arr.__elemType = "S"; break;
					case 10: arr = new Int32Array(n); arr.__cls = "[I"; arr.__elemType = "I"; break;
					case 11:
						arr = typeof BigInt64Array !== "undefined" ? new BigInt64Array(n) : new Array(n).fill(0n);
						arr.__cls = "[J";
						arr.__elemType = "J";
						break;
					default:
						throw new Error("Invalid newarray atype: " + atype);
				}
				return arr;
			},

			_newObjectArray(elemName, count) {
				const n = count | 0;
				if (n < 0) throw this.makeException("java/lang/NegativeArraySizeException", String(n));
				const canonElem = this.resolveClassName(elemName);
				const arr = new Array(n).fill(null);
				arr.__cls = canonElem.charCodeAt(0) === 91 ? ("[" + canonElem) : ("[L" + canonElem + ";");
				arr.__elemType = canonElem;
				return arr;
			},

			createArray(descriptor, count) {
				if (descriptor === "[Z") return this._newPrimitiveArray(4, count);
				if (descriptor === "[C") return this._newPrimitiveArray(5, count);
				if (descriptor === "[F") return this._newPrimitiveArray(6, count);
				if (descriptor === "[D") return this._newPrimitiveArray(7, count);
				if (descriptor === "[B") return this._newPrimitiveArray(8, count);
				if (descriptor === "[S") return this._newPrimitiveArray(9, count);
				if (descriptor === "[I") return this._newPrimitiveArray(10, count);
				if (descriptor === "[J") return this._newPrimitiveArray(11, count);
				const inner = descriptor.charCodeAt(0) === 91 ? descriptor.slice(1) : descriptor;
				return this._newObjectArray(descToInternalName(inner), count);
			},

			createMultiArray(arrayDesc, dims, dimIdx) {
				const n = dims[dimIdx] | 0;
				if (n < 0) throw this.makeException("java/lang/NegativeArraySizeException", String(n));
				if (dimIdx === dims.length - 1) {
					return this.createArray(arrayDesc, n);
				}
				const subDesc = arrayDesc.slice(1);
				const arr = new Array(n);
				arr.__cls = this.resolveClassName(arrayDesc);
				arr.__elemType = descToInternalName(subDesc);
				for (let i = 0; i < n; i++) {
					arr[i] = this.createMultiArray(subDesc, dims, dimIdx + 1);
				}
				return arr;
			},

			getStatic(owner, name, desc) {
				const cls = this.loadClass(owner);
				const rName = this.resolveMemberAlias(owner, name);
				let cur = cls;
				let depth = 0;
				while (cur && depth++ < 64) {
					if (Object.prototype.hasOwnProperty.call(cur.staticFields, rName)) return cur.staticFields[rName];
					if (rName !== name && Object.prototype.hasOwnProperty.call(cur.staticFields, name)) return cur.staticFields[name];
					// Check interfaces for static constants
					if (cur.interfaces && cur.interfaces.length > 0) {
						for (let i = 0; i < cur.interfaces.length; i++) {
							const icls = this.loadClass(cur.interfaces[i]);
							if (Object.prototype.hasOwnProperty.call(icls.staticFields, rName)) return icls.staticFields[rName];
							if (rName !== name && Object.prototype.hasOwnProperty.call(icls.staticFields, name)) return icls.staticFields[name];
						}
					}
					cur = cur.superName ? this.loadClass(cur.superName) : null;
				}
				return defaultValueForDesc(desc);
			},

			putStatic(owner, name, desc, val) {
				if (arguments.length === 3) {
					val = desc;
					desc = null;
				}
				const cls = this.loadClass(owner);
				const rName = this.resolveMemberAlias(owner, name);
				let cur = cls;
				let depth = 0;
				while (cur && depth++ < 64) {
					if ((cur.staticFieldSet && cur.staticFieldSet.has(rName)) || Object.prototype.hasOwnProperty.call(cur.staticFields, rName)) {
						cur.staticFields[rName] = val;
						return;
					}
					cur = cur.superName ? this.loadClass(cur.superName) : null;
				}
				cls.staticFields[rName] = val;
			},

			_findNativeFieldDef(cls, name) {
				let cur = cls;
				let depth = 0;
				while (cur && depth++ < 64) {
					if (cur.isNative && cur.fields && Object.prototype.hasOwnProperty.call(cur.fields, name)) {
						return cur.fields[name];
					}
					cur = cur.superName ? this._resolveClassNoInit(cur.superName) : null;
				}
				return null;
			},

			getField(obj, owner, name, desc) {
				if (obj == null) {
					throw this.makeException("java/lang/NullPointerException", "Cannot read field " + name + " on null");
				}
				const clsName = this._getClassName(obj);
				const rName = this.resolveMemberAlias(owner || clsName, name);
				const cls = this._resolveClassNoInit(clsName);
				if (this._hasFieldTraps(cls)) {
					const nf = this._findNativeFieldDef(cls, rName) || (rName !== name ? this._findNativeFieldDef(cls, name) : null);
					if (nf && typeof nf === "object" && typeof nf.get === "function") {
						return nf.get.call(obj, this);
					}
				}
				if (obj.__fields && Object.prototype.hasOwnProperty.call(obj.__fields, rName)) {
					return obj.__fields[rName];
				}
				if (!isReservedFieldKey(rName) && typeof obj === "object" && rName in obj && typeof obj[rName] !== "function") {
					return obj[rName];
				}
				if (rName !== name) {
					if (obj.__fields && Object.prototype.hasOwnProperty.call(obj.__fields, name)) {
						return obj.__fields[name];
					}
					if (!isReservedFieldKey(name) && typeof obj === "object" && name in obj && typeof obj[name] !== "function") {
						return obj[name];
					}
				}
				return defaultValueForDesc(desc);
			},

			putField(obj, owner, name, desc, val) {
				if (arguments.length === 4) {
					val = desc;
					desc = null;
				}
				if (obj == null) {
					throw this.makeException("java/lang/NullPointerException", "Cannot write field " + name + " on null");
				}
				const clsName = this._getClassName(obj);
				const rName = this.resolveMemberAlias(owner || clsName, name);
				const cls = this._resolveClassNoInit(clsName);
				if (this._hasFieldTraps(cls)) {
					const nf = this._findNativeFieldDef(cls, rName) || (rName !== name ? this._findNativeFieldDef(cls, name) : null);
					if (nf && typeof nf === "object" && typeof nf.set === "function") {
						nf.set.call(obj, val, this);
						return;
					}
				}
				if (isReservedFieldKey(rName) || typeof obj[rName] === "function") {
					if (!obj.__fields) obj.__fields = Object.create(null);
					obj.__fields[rName] = val;
				} else {
					obj[rName] = val;
				}
			},

			_invokeLambdaProxy(obj, name, desc, args) {
				const map = obj.__samMethodMap;
				if (!map) return null;
				const canonDesc = this.canonicalizeDescriptor(desc);
				const fn = map[name + desc] || map[name + canonDesc] || map[name];
				if (fn) {
					return { hit: true, value: fn.apply(obj, args || EMPTY_ARGS) };
				}
				return null;
			},

			invokeVirtual(obj, name, desc, args, declaredOwner) {
				if (obj == null) {
					throw this.makeException("java/lang/NullPointerException", "Cannot invoke " + name + desc + " on null");
				}
				const a = args || EMPTY_ARGS;
				if (obj.__samMethodMap) {
					const lr = this._invokeLambdaProxy(obj, name, desc, a);
					if (lr) return lr.value;
				}
				const clsName = this._getClassName(obj);
				const cls = this._resolveClassNoInit(clsName);
				const rName = declaredOwner ? this.resolveMemberAlias(declaredOwner, name) : this.resolveMemberAlias(clsName, name);
				const found = this._lookupMethodHierarchy(cls, rName, desc, true)
					|| (rName !== name ? this._lookupMethodHierarchy(cls, name, desc, true) : null);
				if (found) {
					return this._callFound(found, obj, a);
				}
				// Plain JS object method fallback (for native bridge helper objects)
				if (typeof obj === "object" && typeof obj[rName] === "function") {
					return obj[rName].apply(obj, a);
				}
				if (cls.isStub || (declaredOwner && this._resolveClassNoInit(declaredOwner).isStub)) {
					return this._stubReturn(obj, cls.name, rName, desc);
				}
				// Graceful fallback for unimplemented optional methods on native bridge classes
				if (cls.isNative) {
					return this._stubReturn(obj, cls.name, rName, desc);
				}
				throw this.makeException("java/lang/NoSuchMethodError", cls.name + "." + rName + desc);
			},

			invokeSpecial(obj, owner, name, desc, args) {
				if (obj == null) {
					throw this.makeException("java/lang/NullPointerException", "Cannot invokeSpecial " + owner + "." + name + desc + " on null");
				}
				const a = args || EMPTY_ARGS;
				const cls = this._resolveClassNoInit(owner);
				const rName = this.resolveMemberAlias(owner, name);
				// Constructors (<init>) are never inherited from superclasses in the JVM
				let found;
				if (rName === "<init>") {
					found = this._getCompiledOrNativeMethod(cls, "<init>", desc, null, null);
				} else {
					found = this._lookupMethodHierarchy(cls, rName, desc, cls.isInterface)
						|| (rName !== name ? this._lookupMethodHierarchy(cls, name, desc, cls.isInterface) : null);
				}
				if (found) {
					return this._callFound(found, obj, a);
				}
				if (rName === "<init>" && cls.isNative) {
					if (cls.ctor) {
						if (a.length > 0 || !obj.__ctorRan) {
							cls.ctor.apply(obj, a);
							obj.__ctorRan = true;
						}
					}
					return undefined;
				}
				if (cls.isStub || cls.isNative) {
					return this._stubReturn(obj, cls.name, rName, desc);
				}
				throw this.makeException("java/lang/NoSuchMethodError", cls.name + "." + rName + desc);
			},

			invokeStatic(owner, name, desc, args) {
				const a = args || EMPTY_ARGS;
				const cls = this.loadClass(owner);
				const rName = this.resolveMemberAlias(owner, name);
				const found = this._lookupMethodHierarchy(cls, rName, desc, false)
					|| (rName !== name ? this._lookupMethodHierarchy(cls, name, desc, false) : null);
				if (found) {
					return this._callFound(found, undefined, a);
				}
				if (cls.isStub || cls.isNative) {
					return this._stubReturn(null, cls.name, rName, desc);
				}
				throw this.makeException("java/lang/NoSuchMethodError", cls.name + "." + rName + desc);
			},

			invokeInterface(obj, owner, name, desc, args) {
				if (obj == null) {
					throw this.makeException("java/lang/NullPointerException", "Cannot invokeInterface " + owner + "." + name + desc + " on null");
				}
				const a = args || EMPTY_ARGS;
				if (obj.__samMethodMap) {
					const lr = this._invokeLambdaProxy(obj, name, desc, a);
					if (lr) return lr.value;
				}
				const clsName = this._getClassName(obj);
				const cls = this._resolveClassNoInit(clsName);
				const rName = this.resolveMemberAlias(owner, name);
				const found = this._lookupMethodHierarchy(cls, rName, desc, true)
					|| (rName !== name ? this._lookupMethodHierarchy(cls, name, desc, true) : null)
					|| this._lookupMethodHierarchy(this._resolveClassNoInit(owner), rName, desc, true);
				if (found) {
					return this._callFound(found, obj, a);
				}
				if (typeof obj === "object" && typeof obj[rName] === "function") {
					return obj[rName].apply(obj, a);
				}
				if (cls.isStub || cls.isNative || this._resolveClassNoInit(owner).isStub) {
					return this._stubReturn(obj, cls.name, rName, desc);
				}
				throw this.makeException("java/lang/AbstractMethodError", owner + "." + rName + desc);
			},

			_getClassName(obj) {
				if (obj == null) return "java/lang/Object";
				if (typeof obj === "string") return "java/lang/String";
				if (typeof obj === "object" && obj.__cls) return this.resolveClassName(obj.__cls);
				if (Array.isArray(obj)) return "[Ljava/lang/Object;";
				if (obj instanceof Int32Array) return "[I";
				if (obj instanceof Int8Array) return "[B";
				if (obj instanceof Uint8Array) return "[Z";
				if (obj instanceof Uint16Array) return "[C";
				if (obj instanceof Int16Array) return "[S";
				if (obj instanceof Float32Array) return "[F";
				if (obj instanceof Float64Array) return "[D";
				if (typeof BigInt64Array !== "undefined" && obj instanceof BigInt64Array) return "[J";
				if (obj instanceof Error) return "java/lang/RuntimeException";
				return "java/lang/Object";
			},

			_isAssignableFrom(srcName, targetName) {
				const src = this.resolveClassName(srcName);
				const tgt = this.resolveClassName(targetName);
				if (src === tgt) return true;
				if (tgt === "java/lang/Object") return true;
				if (src.charCodeAt(0) === 91) {
					if (tgt === "java/lang/Cloneable" || tgt === "java/io/Serializable") return true;
					if (tgt.charCodeAt(0) !== 91) return false;
					const se = src.slice(1);
					const te = tgt.slice(1);
					if (se === te) return true;
					if ((se.charCodeAt(0) === 76 || se.charCodeAt(0) === 91) && (te.charCodeAt(0) === 76 || te.charCodeAt(0) === 91)) {
						return this._isAssignableFrom(descToInternalName(se), descToInternalName(te));
					}
					return false;
				}
				const queue = [src];
				const visited = new Set();
				while (queue.length > 0) {
					const curName = this.resolveClassName(queue.shift());
					if (!curName || visited.has(curName)) continue;
					visited.add(curName);
					if (curName === tgt) return true;
					const cls = this._resolveClassNoInit(curName);
					if (!cls) continue;
					if (cls.superName) queue.push(cls.superName);
					if (cls.interfaces) {
						for (let i = 0; i < cls.interfaces.length; i++) queue.push(cls.interfaces[i]);
					}
				}
				return false;
			},

			isInstanceOf(obj, targetName) {
				if (obj == null) return false;
				const tgt = this.resolveClassName(targetName);
				if (tgt === "java/lang/Object") return true;
				if (obj.__interfaces) {
					for (let i = 0; i < obj.__interfaces.length; i++) {
						if (this._isAssignableFrom(obj.__interfaces[i], tgt)) return true;
					}
				}
				const src = this._getClassName(obj);
				return this._isAssignableFrom(src, tgt);
			},

			makeException(clsName, message) {
				const obj = this.newInstance(clsName);
				obj.detailMessage = message != null ? String(message) : null;
				obj.cause = null;
				return obj;
			},

			wrapException(err) {
				if (err && typeof err === "object" && err.__cls && this.isInstanceOf(err, "java/lang/Throwable")) {
					return err;
				}
				const msg = err && err.message ? String(err.message) : String(err);
				const jex = this.makeException("java/lang/RuntimeException", msg);
				jex.__jsError = err;
				return jex;
			},

			asJsString(v) {
				if (v == null) return "";
				if (typeof v === "string") return v;
				if (typeof v === "object" && v.__cls === "java/lang/String") return String(v.__value || "");
				return String(v);
			},

			jvmToString(val, desc) {
				if (desc === "Z") return val ? "true" : "false";
				if (desc === "C") return typeof val === "string" ? val : String.fromCharCode((val | 0) & 0xFFFF);
				if (desc === "I" || desc === "B" || desc === "S") return String(val | 0);
				if (desc === "J") return String(val);
				if (desc === "F" || desc === "D") return formatJavaFloat(val);
				if (val == null) return "null";
				if (typeof val === "string") return val;
				if (typeof val === "number") return String(val);
				if (typeof val === "bigint") return String(val);
				if (typeof val === "boolean") return val ? "true" : "false";
				if (val.__cls === "java/lang/String") return String(val.__value || "");
				try {
					const s = this.invokeVirtual(val, "toString", "()Ljava/lang/String;", EMPTY_ARGS);
					return s == null ? "null" : this.asJsString(s);
				} catch (_e) {
					return String(val);
				}
			},

			_adaptValue(val, fromDesc, toDesc) {
				if (!fromDesc || !toDesc || fromDesc === toDesc || toDesc === "V") return val;
				const fCh = fromDesc.charCodeAt(0);
				const tCh = toDesc.charCodeAt(0);
				const fromPrim = fCh !== 76 && fCh !== 91;
				const toPrim = tCh !== 76 && tCh !== 91;

				if (fromPrim && !toPrim) {
					// Autobox primitive -> wrapper
					const boxCls = PRIMITIVE_BOX_CLASS[fromDesc];
					if (boxCls) {
						return this.invokeStatic(boxCls, "valueOf", "(" + fromDesc + ")L" + boxCls + ";", [val]);
					}
					return val;
				}
				if (!fromPrim && toPrim) {
					// Unbox wrapper -> primitive
					if (val == null) return defaultValueForDesc(toDesc);
					if (typeof val === "object" && "value" in val) {
						const v = val.value;
						if (toDesc === "J") return typeof v === "bigint" ? v : BigInt(Math.trunc(Number(v)));
						if (toDesc === "D") return Number(v);
						if (toDesc === "F") return Math.fround(Number(v));
						if (toDesc === "Z") return v ? 1 : 0;
						return typeof v === "bigint" ? Number(BigInt.asIntN(32, v)) : (v | 0);
					}
					if (toDesc === "J") return typeof val === "bigint" ? val : BigInt(Math.trunc(Number(val)));
					if (toDesc === "D") return Number(val);
					if (toDesc === "F") return Math.fround(Number(val));
					if (toDesc === "Z") return val ? 1 : 0;
					return Number(val) | 0;
				}
				if (fromPrim && toPrim) {
					// Primitive widening
					if (toDesc === "J") return typeof val === "bigint" ? val : BigInt(val | 0);
					if (toDesc === "D") return typeof val === "bigint" ? Number(val) : Number(val);
					if (toDesc === "F") return Math.fround(typeof val === "bigint" ? Number(val) : Number(val));
					return typeof val === "bigint" ? Number(BigInt.asIntN(32, val)) : (val | 0);
				}
				return val;
			},

			_resolveMethodHandle(mhEntry) {
				return { __cls: "java/lang/invoke/MethodHandle", _mh: mhEntry };
			},

			_resolveMethodType(desc) {
				return { __cls: "java/lang/invoke/MethodType", _descriptor: desc };
			},

			_resolveCondy(site) {
				if (site.resolved) return site.value;
				const cls = site.cls;
				const entry = site.entry;
				const bsm = cls.bootstrapMethods && cls.bootstrapMethods[entry.bootstrapMethodAttrIndex];
				let val = null;
				if (bsm && bsm.methodRef) {
					const bo = bsm.methodRef.owner;
					const bn = bsm.methodRef.name;
					if (bo === "java/lang/invoke/ConstantBootstraps") {
						if (bn === "nullConstant") val = null;
						else if (bn === "primitiveClass") val = this._makePrimitiveClass(entry.name);
						else if (bn === "enumConstant") {
							const enumCls = descToInternalName(entry.descriptor);
							val = this.getStatic(enumCls, entry.name, entry.descriptor);
						} else if (bn === "getStaticFinal") {
							const fOwner = bsm.args[0] ? bsm.args[0].name : cls.name;
							val = this.getStatic(fOwner, entry.name, entry.descriptor);
						} else if (bn === "invoke" && bsm.args[0] && bsm.args[0].tag === 15) {
							const mh = bsm.args[0];
							const invokeArgs = bsm.args.slice(1).map(function (a) { return a ? (a.value !== undefined ? a.value : a) : null; });
							val = this._invokeMethodHandle(mh, invokeArgs, null, entry.descriptor);
						}
					}
				}
				site.resolved = true;
				site.value = val;
				return val;
			},

			_invokeMethodHandle(mh, allArgs, callParamDescs, expectedRetDesc) {
				const kind = mh.referenceKind;
				const implDesc = parseMethodDescriptor(mh.descriptor);
				const isInstanceInvoke = kind === 5 || kind === 7 || kind === 9;
				const self = this;

				function adaptArgs(rawArgs, startIdx) {
					const out = new Array(implDesc.params.length);
					for (let i = 0; i < implDesc.params.length; i++) {
						const raw = rawArgs[startIdx + i];
						const fromDesc = callParamDescs ? callParamDescs[startIdx + i] : null;
						out[i] = self._adaptValue(raw, fromDesc, implDesc.params[i]);
					}
					return out;
				}

				let res;
				if (kind === 1) { // REF_getField
					res = this.getField(allArgs[0], mh.owner, mh.name, mh.descriptor);
				} else if (kind === 2) { // REF_getStatic
					res = this.getStatic(mh.owner, mh.name, mh.descriptor);
				} else if (kind === 3) { // REF_putField
					this.putField(allArgs[0], mh.owner, mh.name, mh.descriptor, allArgs[1]);
					return undefined;
				} else if (kind === 4) { // REF_putStatic
					this.putStatic(mh.owner, mh.name, mh.descriptor, allArgs[0]);
					return undefined;
				} else if (kind === 5) { // REF_invokeVirtual
					res = this.invokeVirtual(allArgs[0], mh.name, mh.descriptor, adaptArgs(allArgs, 1), mh.owner);
				} else if (kind === 6) { // REF_invokeStatic
					res = this.invokeStatic(mh.owner, mh.name, mh.descriptor, adaptArgs(allArgs, 0));
				} else if (kind === 7) { // REF_invokeSpecial
					res = this.invokeSpecial(allArgs[0], mh.owner, mh.name, mh.descriptor, adaptArgs(allArgs, 1));
				} else if (kind === 8) { // REF_newInvokeSpecial
					const inst = this.newInstance(mh.owner);
					this.invokeSpecial(inst, mh.owner, "<init>", mh.descriptor, adaptArgs(allArgs, 0));
					res = inst;
				} else if (kind === 9) { // REF_invokeInterface
					res = this.invokeInterface(allArgs[0], mh.owner, mh.name, mh.descriptor, adaptArgs(allArgs, 1));
				} else {
					throw new Error("Unsupported MethodHandle referenceKind: " + kind);
				}

				const actualRet = kind === 8 ? ("L" + mh.owner + ";") : (kind === 1 || kind === 2 ? mh.descriptor : implDesc.ret);
				if (expectedRetDesc && expectedRetDesc !== "V") {
					return this._adaptValue(res, actualRet, expectedRetDesc);
				}
				return expectedRetDesc === "V" ? undefined : res;
			},

			_invokeDynamic(site, args) {
				const cls = site.cls;
				const indy = site.indy;
				const bsm = cls.bootstrapMethods && cls.bootstrapMethods[indy.bootstrapMethodAttrIndex];
				if (!bsm || !bsm.methodRef) {
					throw new Error("Missing BootstrapMethod #" + indy.bootstrapMethodAttrIndex + " in " + cls.name);
				}
				const bOwner = bsm.methodRef.owner;
				const bName = bsm.methodRef.name;

				// 1. LambdaMetafactory.metafactory & altMetafactory
				if (bOwner === "java/lang/invoke/LambdaMetafactory" && (bName === "metafactory" || bName === "altMetafactory")) {
					if (args.length === 0 && site.cachedTarget) {
						return site.cachedTarget;
					}
					const samType = bsm.args[0];
					const implMh = bsm.args[1];
					const instType = bsm.args[2] || samType;
					const samIface = this.resolveClassName(descToInternalName(site.parsedDesc.ret));
					const extraIfaces = [samIface];
					const bridgeDescs = [];

					if (bName === "altMetafactory" && bsm.args.length > 3) {
						const flags = bsm.args[3] ? (bsm.args[3].value | 0) : 0;
						let aIdx = 4;
						if ((flags & 1) !== 0) extraIfaces.push("java/io/Serializable");
						if ((flags & 2) !== 0 && aIdx < bsm.args.length) {
							const mCount = bsm.args[aIdx++].value | 0;
							for (let m = 0; m < mCount && aIdx < bsm.args.length; m++) {
								const mc = bsm.args[aIdx++];
								if (mc && mc.name) extraIfaces.push(this.resolveClassName(mc.name));
							}
						}
						if ((flags & 4) !== 0 && aIdx < bsm.args.length) {
							const bCount = bsm.args[aIdx++].value | 0;
							for (let br = 0; br < bCount && aIdx < bsm.args.length; br++) {
								const mt = bsm.args[aIdx++];
								if (mt && mt.descriptor) bridgeDescs.push(mt.descriptor);
							}
						}
					}

					const captured = args.slice();
					const capturedDescs = site.parsedDesc.params;
					const samParsed = parseMethodDescriptor(samType.descriptor);
					const instParsed = parseMethodDescriptor(instType.descriptor);
					const allCallDescs = capturedDescs.concat(instParsed.params);
					const self = this;

					const jsCallable = function lambdaInvoke() {
						const callArgs = new Array(captured.length + arguments.length);
						for (let i = 0; i < captured.length; i++) callArgs[i] = captured[i];
						for (let i = 0; i < arguments.length; i++) {
							const raw = arguments[i];
							const fromD = samParsed.params[i];
							const toD = instParsed.params[i];
							callArgs[captured.length + i] = self._adaptValue(raw, fromD, toD);
						}
						return self._invokeMethodHandle(implMh, callArgs, allCallDescs, samParsed.ret);
					};

					const samMethodMap = Object.create(null);
					const samName = indy.name;
					samMethodMap[samName + samType.descriptor] = jsCallable;
					samMethodMap[samName + this.canonicalizeDescriptor(samType.descriptor)] = jsCallable;
					samMethodMap[samName + instType.descriptor] = jsCallable;
					samMethodMap[samName + this.canonicalizeDescriptor(instType.descriptor)] = jsCallable;
					samMethodMap[samName] = jsCallable;
					for (let br = 0; br < bridgeDescs.length; br++) {
						samMethodMap[samName + bridgeDescs[br]] = jsCallable;
						samMethodMap[samName + this.canonicalizeDescriptor(bridgeDescs[br])] = jsCallable;
					}

					const proxy = {
						__cls: samIface,
						__interfaces: extraIfaces,
						__samName: samName,
						__samMethodMap: samMethodMap,
						__jsCallable: jsCallable
					};
					proxy[samName] = jsCallable;
					if (captured.length === 0) {
						site.cachedTarget = proxy;
					}
					return proxy;
				}

				// 2. StringConcatFactory.makeConcatWithConstants & makeConcat
				if (bOwner === "java/lang/invoke/StringConcatFactory") {
					const paramDescs = site.parsedDesc.params;
					if (bName === "makeConcat") {
						let out = "";
						for (let i = 0; i < args.length; i++) {
							out += this.jvmToString(args[i], paramDescs[i]);
						}
						return out;
					}
					if (bName === "makeConcatWithConstants") {
						let plan = site._concatPlan;
						if (!plan) {
							const recipe = bsm.args[0] ? String(bsm.args[0].value || "") : "";
							plan = [];
							let lit = "";
							let aIdx = 0;
							let cIdx = 1;
							for (let i = 0; i < recipe.length; i++) {
								const ch = recipe.charCodeAt(i);
								if (ch === 1) {
									if (lit) { plan.push(lit); lit = ""; }
									plan.push(aIdx);
									aIdx++;
								} else if (ch === 2) {
									const cEntry = bsm.args[cIdx++];
									if (!cEntry) {
										lit += "null";
									} else if (cEntry.tag === 7) {
										lit += this.jvmToString(this.getClassObject(cEntry.name), "Ljava/lang/Object;");
									} else if (cEntry.tag === 4 || cEntry.tag === 6) {
										lit += formatJavaFloat(cEntry.value);
									} else {
										lit += String(cEntry.value !== undefined ? cEntry.value : "");
									}
								} else {
									lit += recipe[i];
								}
							}
							if (lit) plan.push(lit);
							site._concatPlan = plan;
						}
						let out = "";
						for (let i = 0; i < plan.length; i++) {
							const step = plan[i];
							if (typeof step === "string") {
								out += step;
							} else {
								out += this.jvmToString(args[step], paramDescs[step]);
							}
						}
						return out;
					}
				}

				// 3. ObjectMethods.bootstrap (Java record toString / equals / hashCode)
				if (bOwner === "java/lang/runtime/ObjectMethods" && bName === "bootstrap") {
					const recClsEntry = bsm.args[0];
					const recName = recClsEntry && recClsEntry.name ? recClsEntry.name : cls.name;
					const namesRaw = bsm.args[1] && bsm.args[1].value ? String(bsm.args[1].value) : "";
					const compNames = namesRaw.length > 0 ? namesRaw.split(";") : [];
					const getters = bsm.args.slice(2);
					const self = this;

					function getComp(obj, idx) {
						const g = getters[idx];
						if (!g) return null;
						return self._invokeMethodHandle(g, [obj], null, null);
					}

					if (indy.name === "toString") {
						const target = args[0];
						const slash = recName.lastIndexOf("/");
						const simple = slash >= 0 ? recName.slice(slash + 1) : recName;
						const parts = [];
						for (let i = 0; i < getters.length; i++) {
							const g = getters[i];
							const v = getComp(target, i);
							const d = g.referenceKind <= 2 ? g.descriptor : parseMethodDescriptor(g.descriptor).ret;
							parts.push((compNames[i] || ("f" + i)) + "=" + this.jvmToString(v, d));
						}
						return simple + "[" + parts.join(", ") + "]";
					}
					if (indy.name === "equals") {
						const a = args[0];
						const b = args[1];
						if (a === b) return 1;
						if (b == null || this._getClassName(a) !== this._getClassName(b)) return 0;
						for (let i = 0; i < getters.length; i++) {
							const va = getComp(a, i);
							const vb = getComp(b, i);
							if (va === vb) continue;
							if (typeof va === "number" && typeof vb === "number" && Number.isNaN(va) && Number.isNaN(vb)) continue;
							if (va == null || vb == null) return 0;
							if (typeof va === "string" || va.__cls === "java/lang/String") {
								if (this.asJsString(va) !== this.asJsString(vb)) return 0;
							} else if (typeof va === "object") {
								if (!this.invokeVirtual(va, "equals", "(Ljava/lang/Object;)Z", [vb])) return 0;
							} else {
								return 0;
							}
						}
						return 1;
					}
					if (indy.name === "hashCode") {
						const target = args[0];
						let h = 0;
						for (let i = 0; i < getters.length; i++) {
							const v = getComp(target, i);
							let ch = 0;
							if (v == null) ch = 0;
							else if (typeof v === "string") ch = javaStringHashCode(v);
							else if (typeof v === "bigint") ch = Number(BigInt.asIntN(32, v ^ (BigInt.asUintN(64, v) >> 32n)));
							else if (typeof v === "number") ch = v | 0;
							else if (typeof v === "object") ch = this.invokeVirtual(v, "hashCode", "()I", EMPTY_ARGS) | 0;
							h = (Math.imul(31, h) + ch) | 0;
						}
						return h;
					}
				}

				// 4. SwitchBootstraps.typeSwitch / enumSwitch (Java 21+)
				if (bOwner === "java/lang/runtime/SwitchBootstraps") {
					const target = args[0];
					const start = (args[1] | 0);
					if (target == null) return -1;
					const labels = bsm.args;
					if (bName === "typeSwitch") {
						for (let i = Math.max(0, start); i < labels.length; i++) {
							const lbl = labels[i];
							if (!lbl) continue;
							if (lbl.tag === 7 && this.isInstanceOf(target, lbl.name)) return i;
							if (lbl.tag === 8 && (typeof target === "string" || target.__cls === "java/lang/String") && this.asJsString(target) === lbl.value) return i;
							if (lbl.tag === 3 && target.__cls === "java/lang/Integer" && target.value === lbl.value) return i;
						}
						return labels.length;
					}
					if (bName === "enumSwitch") {
						const enumName = target._enumName || (target.name ? this.asJsString(target.name) : null);
						for (let i = Math.max(0, start); i < labels.length; i++) {
							const lbl = labels[i];
							if (!lbl) continue;
							if (lbl.tag === 8 && enumName === lbl.value) return i;
							if (lbl.tag === 7 && this.isInstanceOf(target, lbl.name)) return i;
						}
						return labels.length;
					}
				}

				return defaultValueForDesc(site.parsedDesc.ret);
			},

			// Hook Registry
			hasHook(hookName) {
				const list = hooks.get(hookName);
				return !!(list && list.length > 0);
			},

			hasHooks(hookName) {
				const list = hooks.get(hookName);
				return !!(list && list.length > 0);
			},

			registerHook(modId, hookName, fn) {
				if (typeof fn !== "function") return function () {};
				const mid = String(modId || this.currentModId || "unknown");
				let list = hooks.get(hookName);
				if (!list) {
					list = [];
					hooks.set(hookName, list);
				}
				const entry = { modId: mid, fn };
				list.push(entry);
				return function unregisterOne() {
					const arr = hooks.get(hookName);
					if (!arr) return;
					const idx = arr.indexOf(entry);
					if (idx >= 0) {
						arr.splice(idx, 1);
						if (arr.length === 0) hooks.delete(hookName);
					}
				};
			},

			unregisterMod(modId) {
				const mid = String(modId);
				for (const [hookName, list] of hooks.entries()) {
					for (let i = list.length - 1; i >= 0; i--) {
						if (list[i].modId === mid) {
							list.splice(i, 1);
						}
					}
					if (list.length === 0) {
						hooks.delete(hookName);
					}
				}
			},

			dispatchHook(hookName, ctx) {
				const list = hooks.get(hookName);
				const context = ctx !== undefined ? ctx : {};
				if (!list || list.length === 0) return context;
				const savedSd = COMPILED_HELPERS.sd;
				if (list.length === 1) {
					const only = list[0];
					try {
						only.fn(context, this);
					} catch (err) {
						COMPILED_HELPERS.sd = savedSd;
						if (typeof console !== "undefined" && console.error) {
							console.error("[EaglerJvmTranslator] Hook error in " + only.modId + " (" + hookName + "):", err);
						}
					}
					return context;
				}
				const snap = list.slice();
				for (let i = 0; i < snap.length; i++) {
					try {
						snap[i].fn(context, this);
					} catch (err) {
						COMPILED_HELPERS.sd = savedSd;
						if (typeof console !== "undefined" && console.error) {
							console.error("[EaglerJvmTranslator] Hook error in " + snap[i].modId + " (" + hookName + "):", err);
						}
					}
				}
				return context;
			}
		};

		installCoreJdkClasses(rt);
		// Pre-warm common Object / String / Number / Enum method keys in _vcache
		for (const warmClsName of ["java/lang/Object", "java/lang/String", "java/lang/Number", "java/lang/Integer", "java/lang/Float", "java/lang/Double", "java/lang/Boolean", "java/lang/Enum"]) {
			const wc = nativeClasses.get(warmClsName);
			if (wc) {
				wc._vcache = Object.create(null);
				wc._vcacheEpoch = cacheEpoch;
				const mk = Object.keys(wc.methods);
				for (let i = 0; i < mk.length; i++) {
					if (mk[i].indexOf("(") >= 0) {
						wc._vcache[mk[i]] = { fn: wc.methods[mk[i]], owner: wc };
					}
				}
			}
		}
		return rt;
	}

	function copyOwnPropsWithDescriptors(dst, src) {
		const keys = Object.keys(src);
		for (let i = 0; i < keys.length; i++) {
			const k = keys[i];
			const d = Object.getOwnPropertyDescriptor(src, k);
			if (d) Object.defineProperty(dst, k, d);
		}
	}

	// =========================================================================
	// 7. HIGH-LEVEL FABRIC MOD LOADER & SHARED RUNTIME SINGLETON
	// =========================================================================

	let sharedRuntime = null;
	const loadedModsById = new Map();
	const loadedModsByFilename = new Map();

	const SKIP_JIJ_PATTERNS = /^(?:netty|lwjgl|jna|kotlin-stdlib|kotlinx-|scala-library|guava|fastutil|org\.ow2\.asm|asm-|sponge-mixin|mixinextras|log4j|slf4j|gson-|commons-)/i;
	const MAX_JIJ_BYTES = 2 * 1024 * 1024;

	function getBridge() {
		if (typeof window !== "undefined") {
			if (window.EaglerJvmBridge) return window.EaglerJvmBridge;
			if (window.MinecraftWebJvmBridge) return window.MinecraftWebJvmBridge;
		}
		if (globalScope) {
			if (globalScope.EaglerJvmBridge) return globalScope.EaglerJvmBridge;
			if (globalScope.MinecraftWebJvmBridge) return globalScope.MinecraftWebJvmBridge;
		}
		return null;
	}

	function getSharedRuntime() {
		if (!sharedRuntime) {
			sharedRuntime = createRuntime();
		}
		if (!sharedRuntime.__bridgeInstalled) {
			const bridge = getBridge();
			if (bridge && typeof bridge.install === "function") {
				sharedRuntime.__bridgeInstalled = true;
				bridge.install(sharedRuntime);
			}
		}
		return sharedRuntime;
	}

	function normalizeEntrypointList(raw) {
		if (!Array.isArray(raw)) return [];
		const out = [];
		for (let i = 0; i < raw.length; i++) {
			const item = raw[i];
			if (typeof item === "string" && item.trim()) {
				out.push(item.trim());
			} else if (item && typeof item === "object" && typeof item.value === "string" && item.value.trim()) {
				out.push(item.value.trim());
			}
		}
		return out;
	}

	function runEntrypoint(rt, kind, spec) {
		const parts = spec.split("::");
		const className = parts[0].replace(/\./g, "/");
		const methodName = parts[1] || (kind === "client" ? "onInitializeClient" : "onInitialize");
		const cls = rt.loadClass(className);
		if (parts.length > 1) {
			const mDef = cls.methodDefs && cls.methodDefs[methodName + "()V"];
			if (mDef && (mDef.accessFlags & ACC_STATIC) !== 0) {
				rt.invokeStatic(className, methodName, "()V", EMPTY_ARGS);
				return;
			}
		}
		const inst = rt.newInstance(className);
		rt.invokeSpecial(inst, className, "<init>", "()V", EMPTY_ARGS);
		rt.invokeVirtual(inst, methodName, "()V", EMPTY_ARGS);
	}

	function formatErrorShort(err) {
		if (!err) return "Unknown error";
		if (typeof err === "object" && err.__cls) {
			const cn = err.__cls.replace(/\//g, ".");
			return err.detailMessage ? (cn + ": " + err.detailMessage) : cn;
		}
		return err.message ? String(err.message) : String(err);
	}

	function loadJar(filename, uint8Array, opts) {
		const options = opts || {};
		const rt = options.runtime || getSharedRuntime();
		const safeFilename = String(filename || "mod.jar");
		const errors = [];
		const entrypointsRun = [];
		const unsupportedTargets = [];
		let mixinsAttached = 0;
		let mixinsUnsupported = 0;
		let classesLoaded = 0;
		const compiledBefore = rt.compiledMethodCount;

		let outerEntries;
		try {
			outerEntries = unzipJar(uint8Array);
		} catch (err) {
			const report = {
				ok: false,
				id: safeFilename.replace(/\.jar$/i, ""),
				name: safeFilename,
				version: "unknown",
				filename: safeFilename,
				classesLoaded: 0,
				methodsCompiled: 0,
				entrypointsRun,
				mixinsAttached: 0,
				mixinsUnsupported: 0,
				unsupportedTargets,
				errors: ["Unzip failed: " + formatErrorShort(err)],
				summary: "Failed to unzip " + safeFilename + ": " + formatErrorShort(err)
			};
			return report;
		}

		// Parse fabric.mod.json if present
		let modJson = null;
		const fmjBytes = outerEntries.get("fabric.mod.json");
		if (fmjBytes) {
			try {
				modJson = JSON.parse(decodeUtf8Bytes(fmjBytes));
			} catch (err) {
				errors.push("Invalid fabric.mod.json: " + formatErrorShort(err));
			}
		}

		const modId = (modJson && modJson.id) ? String(modJson.id) : safeFilename.replace(/\.jar$/i, "").replace(/[^a-zA-Z0-9_-]/g, "_");
		const modName = (modJson && modJson.name) ? String(modJson.name) : modId;
		const modVersion = (modJson && modJson.version) ? String(modJson.version) : "0.0.0";

		// Unpack 1 level of nested META-INF/jars/*.jar (skipping huge/unsupported libraries)
		const allClassBuffers = new Map(); // entryPath -> Uint8Array
		const allJsonBuffers = new Map();
		for (const [entryPath, data] of outerEntries.entries()) {
			if (entryPath.endsWith(".class") && !entryPath.startsWith("META-INF/versions/") && !entryPath.endsWith("module-info.class")) {
				allClassBuffers.set(entryPath, data);
			} else if (entryPath.endsWith(".json")) {
				allJsonBuffers.set(entryPath, data);
			}
		}

		for (const [entryPath, data] of outerEntries.entries()) {
			if (entryPath.startsWith("META-INF/jars/") && entryPath.endsWith(".jar")) {
				const base = entryPath.slice("META-INF/jars/".length);
				if (data.length > MAX_JIJ_BYTES || SKIP_JIJ_PATTERNS.test(base)) {
					continue;
				}
				try {
					const inner = unzipJar(data);
					for (const [inPath, inData] of inner.entries()) {
						if (inPath.endsWith(".class") && !inPath.startsWith("META-INF/versions/") && !inPath.endsWith("module-info.class")) {
							if (!allClassBuffers.has(inPath)) {
								allClassBuffers.set(inPath, inData);
							}
						} else if (inPath.endsWith(".json") && !allJsonBuffers.has(inPath)) {
							allJsonBuffers.set(inPath, inData);
						}
					}
				} catch (err) {
					errors.push("Nested JAR " + base + " skipped: " + formatErrorShort(err));
				}
			}
		}

		// Parse all .class files and define them in the runtime
		const parsedClasses = new Map();
		for (const [entryPath, data] of allClassBuffers.entries()) {
			if (data.length < 10 || data[0] !== 0xCA || data[1] !== 0xFE || data[2] !== 0xBA || data[3] !== 0xBE) {
				continue;
			}
			try {
				const parsed = parseClass(data);
				parsedClasses.set(parsed.name, parsed);
				rt.defineClass(parsed);
				classesLoaded++;
			} catch (err) {
				errors.push("Class parse failed (" + entryPath + "): " + formatErrorShort(err));
			}
		}

		// Parse mixin configs listed in fabric.mod.json
		const mixinConfigs = [];
		const rawMixins = (modJson && Array.isArray(modJson.mixins)) ? modJson.mixins : [];
		for (let i = 0; i < rawMixins.length; i++) {
			const mEntry = rawMixins[i];
			let cfgFile = null;
			if (typeof mEntry === "string") {
				cfgFile = mEntry;
			} else if (mEntry && typeof mEntry === "object" && typeof mEntry.config === "string") {
				if (mEntry.environment === "server") continue;
				cfgFile = mEntry.config;
			}
			if (!cfgFile) continue;
			const cfgBytes = allJsonBuffers.get(cfgFile) || outerEntries.get(cfgFile);
			if (!cfgBytes) continue;
			try {
				const cfgObj = JSON.parse(decodeUtf8Bytes(cfgBytes));
				let refmapData = null;
				if (cfgObj && typeof cfgObj.refmap === "string") {
					const rmBytes = allJsonBuffers.get(cfgObj.refmap) || outerEntries.get(cfgObj.refmap);
					if (rmBytes) {
						try { refmapData = JSON.parse(decodeUtf8Bytes(rmBytes)); } catch (_e) {}
					}
				}
				mixinConfigs.push({ filename: cfgFile, config: cfgObj, refmap: refmapData });
			} catch (err) {
				errors.push("Mixin config parse failed (" + cfgFile + "): " + formatErrorShort(err));
			}
		}

		// Unregister any previous hooks for this mod before re-running
		rt.unregisterMod(modId);
		if (safeFilename !== modId) rt.unregisterMod(safeFilename);

		const prevModId = rt.currentModId;
		rt.currentModId = modId;

		const modRecord = {
			ok: true,
			id: modId,
			name: modName,
			version: modVersion,
			filename: safeFilename,
			classesLoaded,
			methodsCompiled: 0,
			entrypointsRun,
			mixinsAttached: 0,
			mixinsUnsupported: 0,
			unsupportedTargets,
			errors,
			summary: ""
		};

		try {
			// Run main entrypoints first, then client entrypoints
			const mainEps = normalizeEntrypointList(modJson && modJson.entrypoints && modJson.entrypoints.main);
			const clientEps = normalizeEntrypointList(modJson && modJson.entrypoints && modJson.entrypoints.client);

			for (let i = 0; i < mainEps.length; i++) {
				const ep = mainEps[i];
				try {
					runEntrypoint(rt, "main", ep);
					entrypointsRun.push("main:" + ep);
				} catch (err) {
					errors.push("Entrypoint main:" + ep + " failed: " + formatErrorShort(err));
				}
			}
			for (let i = 0; i < clientEps.length; i++) {
				const ep = clientEps[i];
				try {
					runEntrypoint(rt, "client", ep);
					entrypointsRun.push("client:" + ep);
				} catch (err) {
					errors.push("Entrypoint client:" + ep + " failed: " + formatErrorShort(err));
				}
			}

			// Attach mixins via EaglerJvmBridge if installed
			const bridge = getBridge();
			if (bridge && typeof bridge.attachMixins === "function" && mixinConfigs.length > 0) {
				try {
					const mRes = bridge.attachMixins(rt, modRecord, mixinConfigs, parsedClasses);
					if (mRes && typeof mRes === "object") {
						if (typeof mRes.mixinsAttached === "number") mixinsAttached = mRes.mixinsAttached;
						if (typeof mRes.mixinsUnsupported === "number") mixinsUnsupported = mRes.mixinsUnsupported;
						if (Array.isArray(mRes.unsupportedTargets)) {
							for (let i = 0; i < mRes.unsupportedTargets.length; i++) {
								unsupportedTargets.push(mRes.unsupportedTargets[i]);
							}
						}
						if (Array.isArray(mRes.errors)) {
							for (let i = 0; i < mRes.errors.length; i++) errors.push(mRes.errors[i]);
						}
					} else {
						mixinsAttached = modRecord.mixinsAttached | 0;
						mixinsUnsupported = modRecord.mixinsUnsupported | 0;
					}
				} catch (err) {
					errors.push("Mixin attachment failed: " + formatErrorShort(err));
				}
			}
		} finally {
			rt.currentModId = prevModId;
		}

		const methodsCompiled = Math.max(0, rt.compiledMethodCount - compiledBefore);
		modRecord.methodsCompiled = methodsCompiled;
		modRecord.mixinsAttached = mixinsAttached;
		modRecord.mixinsUnsupported = mixinsUnsupported;
		modRecord.ok = errors.length === 0 || entrypointsRun.length > 0 || mixinsAttached > 0;

		const summaryParts = [
			modName + " " + modVersion + " (" + modId + ")",
			classesLoaded + " classes",
			methodsCompiled + " compiled"
		];
		if (entrypointsRun.length > 0) summaryParts.push(entrypointsRun.length + " entrypoints");
		if (mixinsAttached > 0) summaryParts.push(mixinsAttached + " mixins attached");
		if (mixinsUnsupported > 0) summaryParts.push(mixinsUnsupported + " mixins unsupported");
		if (errors.length > 0) summaryParts.push(errors.length + " errors");
		modRecord.summary = summaryParts.join(", ");

		loadedModsById.set(modId, modRecord);
		loadedModsByFilename.set(safeFilename, modRecord);
		return modRecord;
	}

	function unloadMod(filenameOrId) {
		const key = String(filenameOrId || "");
		const rec = loadedModsById.get(key) || loadedModsByFilename.get(key);
		if (!rec) return false;
		if (sharedRuntime) {
			sharedRuntime.unregisterMod(rec.id);
			sharedRuntime.unregisterMod(rec.filename);
		}
		loadedModsById.delete(rec.id);
		loadedModsByFilename.delete(rec.filename);
		return true;
	}

	function getModReport(filenameOrId) {
		const key = String(filenameOrId || "");
		return loadedModsById.get(key) || loadedModsByFilename.get(key) || null;
	}

	function listTranslatedMods() {
		return Array.from(loadedModsById.values());
	}

	function dispatchHook(hookName, ctx) {
		if (!sharedRuntime) return ctx !== undefined ? ctx : {};
		return sharedRuntime.dispatchHook(hookName, ctx);
	}

	function hasHook(hookName) {
		if (!sharedRuntime) return false;
		return sharedRuntime.hasHook(hookName);
	}

	// =========================================================================
	// 8. PUBLIC API EXPORT
	// =========================================================================

	const api = {
		unzipJar,
		parseClass,
		createRuntime,
		getSharedRuntime,
		loadJar,
		unloadMod,
		getModReport,
		listTranslatedMods,
		dispatchHook,
		hasHook
	};

	if (globalScope) {
		globalScope.EaglerJvmTranslator = api;
		globalScope.MinecraftWebJvmTranslator = api;
	}
	if (typeof module !== "undefined" && module.exports) {
		module.exports = api;
	}
})(typeof window !== "undefined" ? window : (typeof globalThis !== "undefined" ? globalThis : null));
