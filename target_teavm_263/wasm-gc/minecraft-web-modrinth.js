// minecraft-web by chiikabu (https://github.com/chiikabu)
// Modrinth API v2 & CDN wrapper tunneled end-to-end through the game's WISP Relays
// using RFC 8446 TLS 1.3 (X25519 + TLS_AES_128_GCM_SHA256) + HTTP/1.1 Keep-Alive.
// Zero external dependencies; HTML/XML/SVG offline-bundle safe.

(function () {
	"use strict";

	var G = typeof globalThis !== "undefined" ? globalThis : self;
	var USER_AGENT = "chiikabu/minecraft-web/26.3 (modrinth-relay-client)";
	var MAX_API_BYTES = 4 * 1024 * 1024;
	var MAX_THUMB_BYTES = 4 * 1024 * 1024;
	var MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024;
	var IDLE_CONN_MS = 28000;
	var IDLE_CARRIER_MS = 35000;
	var HANDSHAKE_TIMEOUT_MS = 16000;
	var REQUEST_TIMEOUT_MS = 25000;
	var DOWNLOAD_TIMEOUT_MS = 120000;
	var SEARCH_CACHE_TTL_MS = 90000;
	var THUMB_CACHE_MAX = 160;
	var ALLOWED_HOSTS = {
		"api.modrinth.com": 1,
		"cdn.modrinth.com": 1,
		"cdn-raw.modrinth.com": 1
	};

	// =========================================================================
	// 1. Byte helpers & UTF-8
	// =========================================================================

	var textEncoder = typeof TextEncoder === "function" ? new TextEncoder() : null;
	var textDecoder = typeof TextDecoder === "function" ? new TextDecoder("utf-8") : null;

	function strToUtf8(s) {
		if (textEncoder) { return textEncoder.encode(s); }
		var out = [];
		for (var i = 0; i < s.length; i++) {
			var c = s.charCodeAt(i);
			if (c < 0x80) { out.push(c); }
			else if (c < 0x800) { out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f)); }
			else { out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f)); }
		}
		return new Uint8Array(out);
	}

	function utf8ToStr(u8) {
		if (textDecoder) { return textDecoder.decode(u8); }
		var out = "";
		for (var i = 0; i < u8.length; i++) { out += String.fromCharCode(u8[i]); }
		return out;
	}

	function concatBytes(parts) {
		var total = 0;
		for (var i = 0; i < parts.length; i++) { total += parts[i].length; }
		var out = new Uint8Array(total);
		var off = 0;
		for (var j = 0; j < parts.length; j++) {
			out.set(parts[j], off);
			off += parts[j].length;
		}
		return out;
	}

	function u16be(v) { return new Uint8Array([(v >>> 8) & 0xff, v & 0xff]); }

	function randomBytes(n) {
		var out = new Uint8Array(n);
		if (G.crypto && typeof G.crypto.getRandomValues === "function") {
			G.crypto.getRandomValues(out);
		} else {
			for (var i = 0; i < n; i++) { out[i] = (Math.random() * 256) | 0; }
		}
		return out;
	}

	function ctEqual(a, b) {
		if (!a || !b || a.length !== b.length) { return false; }
		var diff = 0;
		for (var i = 0; i < a.length; i++) { diff |= a[i] ^ b[i]; }
		return diff === 0;
	}

	// =========================================================================
	// 2. Pure-JS SHA-256, HMAC-SHA256, HKDF-SHA256 (RFC 8446 §7.1)
	// =========================================================================

	var SHA256_K = new Uint32Array([
		0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
		0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
		0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
		0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
		0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
		0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
		0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
		0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
	]);
	var SHA256_W = new Uint32Array(64);

	function sha256(data) {
		var len = data.length;
		var bitLenHi = Math.floor(len / 0x20000000);
		var bitLenLo = (len << 3) >>> 0;
		var padLen = ((len + 9 + 63) & ~63);
		var buf = new Uint8Array(padLen);
		buf.set(data, 0);
		buf[len] = 0x80;
		var dv = new DataView(buf.buffer);
		dv.setUint32(padLen - 8, bitLenHi, false);
		dv.setUint32(padLen - 4, bitLenLo, false);

		var h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
		var h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;
		var W = SHA256_W;
		var K = SHA256_K;

		for (var off = 0; off < padLen; off += 64) {
			for (var i = 0; i < 16; i++) {
				W[i] = dv.getUint32(off + (i << 2), false);
			}
			for (var j = 16; j < 64; j++) {
				var w15 = W[j - 15], w2 = W[j - 2];
				var s0 = ((w15 >>> 7) | (w15 << 25)) ^ ((w15 >>> 18) | (w15 << 14)) ^ (w15 >>> 3);
				var s1 = ((w2 >>> 17) | (w2 << 15)) ^ ((w2 >>> 19) | (w2 << 13)) ^ (w2 >>> 10);
				W[j] = (W[j - 16] + s0 + W[j - 7] + s1) >>> 0;
			}
			var a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
			for (var r = 0; r < 64; r++) {
				var S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
				var ch = (e & f) ^ ((~e) & g);
				var t1 = (h + S1 + ch + K[r] + W[r]) >>> 0;
				var S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
				var maj = (a & b) ^ (a & c) ^ (b & c);
				var t2 = (S0 + maj) >>> 0;
				h = g; g = f; f = e; e = (d + t1) >>> 0;
				d = c; c = b; b = a; a = (t1 + t2) >>> 0;
			}
			h0 = (h0 + a) >>> 0; h1 = (h1 + b) >>> 0; h2 = (h2 + c) >>> 0; h3 = (h3 + d) >>> 0;
			h4 = (h4 + e) >>> 0; h5 = (h5 + f) >>> 0; h6 = (h6 + g) >>> 0; h7 = (h7 + h) >>> 0;
		}
		var out = new Uint8Array(32);
		var odv = new DataView(out.buffer);
		odv.setUint32(0, h0, false); odv.setUint32(4, h1, false);
		odv.setUint32(8, h2, false); odv.setUint32(12, h3, false);
		odv.setUint32(16, h4, false); odv.setUint32(20, h5, false);
		odv.setUint32(24, h6, false); odv.setUint32(28, h7, false);
		return out;
	}

	function hmacSha256(key, msg) {
		var k = key.length > 64 ? sha256(key) : key;
		var ipad = new Uint8Array(64 + msg.length);
		var opad = new Uint8Array(64 + 32);
		for (var i = 0; i < 64; i++) {
			var kb = i < k.length ? k[i] : 0;
			ipad[i] = kb ^ 0x36;
			opad[i] = kb ^ 0x5c;
		}
		ipad.set(msg, 64);
		opad.set(sha256(ipad), 64);
		return sha256(opad);
	}

	function hkdfExtract(salt, ikm) {
		var s = (salt && salt.length) ? salt : new Uint8Array(32);
		return hmacSha256(s, ikm);
	}

	function hkdfExpandLabel(secret, label, context, length) {
		var fullLabel = strToUtf8("tls13 " + label);
		var ctx = context || new Uint8Array(0);
		var info = new Uint8Array(2 + 1 + fullLabel.length + 1 + ctx.length + 1);
		info[0] = (length >>> 8) & 0xff;
		info[1] = length & 0xff;
		info[2] = fullLabel.length;
		info.set(fullLabel, 3);
		info[3 + fullLabel.length] = ctx.length;
		info.set(ctx, 4 + fullLabel.length);
		info[info.length - 1] = 1; // RFC 5869 counter T(1); all TLS 1.3 labels here are <= 32 bytes
		return hmacSha256(secret, info).subarray(0, length);
	}

	function deriveSecret(secret, label, transcriptHash) {
		return hkdfExpandLabel(secret, label, transcriptHash, 32);
	}

	// =========================================================================
	// 3. Pure-JS X25519 (RFC 7748) using BigInt Montgomery Ladder
	// =========================================================================

	var P25519 = (BigInt(1) << BigInt(255)) - BigInt(19);
	var A24 = BigInt(121665);
	var X25519_BASE = new Uint8Array(32);
	X25519_BASE[0] = 9;

	function modP(x) {
		var r = x % P25519;
		return r < BigInt(0) ? r + P25519 : r;
	}

	function invP(a) {
		var res = BigInt(1);
		var base = modP(a);
		var exp = P25519 - BigInt(2);
		while (exp > BigInt(0)) {
			if ((exp & BigInt(1)) === BigInt(1)) { res = (res * base) % P25519; }
			base = (base * base) % P25519;
			exp >>= BigInt(1);
		}
		return res;
	}

	function decodeLittleEndian25519(b) {
		var v = BigInt(0);
		for (var i = 31; i >= 0; i--) {
			v = (v << BigInt(8)) | BigInt(b[i]);
		}
		return v;
	}

	function encodeLittleEndian25519(v) {
		var out = new Uint8Array(32);
		var x = modP(v);
		for (var i = 0; i < 32; i++) {
			out[i] = Number(x & BigInt(255));
			x >>= BigInt(8);
		}
		return out;
	}

	function x25519(scalarBytes, uBytes) {
		var k = new Uint8Array(scalarBytes);
		k[0] &= 248;
		k[31] &= 127;
		k[31] |= 64;
		var uCopy = new Uint8Array(uBytes);
		uCopy[31] &= 127;
		var kInt = decodeLittleEndian25519(k);
		var x1 = decodeLittleEndian25519(uCopy) % P25519;
		var x2 = BigInt(1), z2 = BigInt(0);
		var x3 = x1, z3 = BigInt(1);
		var swap = BigInt(0);
		for (var t = 254; t >= 0; t--) {
			var kt = (kInt >> BigInt(t)) & BigInt(1);
			swap ^= kt;
			if (swap === BigInt(1)) {
				var tx = x2; x2 = x3; x3 = tx;
				var tz = z2; z2 = z3; z3 = tz;
			}
			swap = kt;
			var A = modP(x2 + z2);
			var AA = (A * A) % P25519;
			var B = modP(x2 - z2);
			var BB = (B * B) % P25519;
			var E = modP(AA - BB);
			var C = modP(x3 + z3);
			var D = modP(x3 - z3);
			var DA = (D * A) % P25519;
			var CB = (C * B) % P25519;
			var sum = modP(DA + CB);
			var dif = modP(DA - CB);
			x3 = (sum * sum) % P25519;
			z3 = (x1 * ((dif * dif) % P25519)) % P25519;
			x2 = (AA * BB) % P25519;
			z2 = (E * modP(AA + ((A24 * E) % P25519))) % P25519;
		}
		if (swap === BigInt(1)) {
			var fx = x2; x2 = x3; x3 = fx;
			var fz = z2; z2 = z3; z3 = fz;
		}
		return encodeLittleEndian25519((x2 * invP(z2)) % P25519);
	}

	// =========================================================================
	// 4. AES-128-GCM: WebCrypto fast path + Pure-JS fallback (for file:// / HTTP)
	// =========================================================================

	var AES_SBOX = new Uint8Array([
		0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
		0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
		0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
		0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
		0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
		0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
		0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
		0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
		0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
		0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
		0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
		0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
		0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
		0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
		0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
		0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
	]);
	var AES_RCON = [0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36];

	function xtime(a) { return ((a << 1) ^ ((a & 0x80) ? 0x1b : 0)) & 0xff; }

	function aes128ExpandKey(key) {
		var rk = new Uint8Array(176);
		rk.set(key, 0);
		for (var i = 16, r = 1; i < 176; i += 4) {
			var t0 = rk[i - 4], t1 = rk[i - 3], t2 = rk[i - 2], t3 = rk[i - 1];
			if ((i & 15) === 0) {
				var tmp = t0;
				t0 = AES_SBOX[t1] ^ AES_RCON[r++];
				t1 = AES_SBOX[t2];
				t2 = AES_SBOX[t3];
				t3 = AES_SBOX[tmp];
			}
			rk[i] = rk[i - 16] ^ t0;
			rk[i + 1] = rk[i - 15] ^ t1;
			rk[i + 2] = rk[i - 14] ^ t2;
			rk[i + 3] = rk[i - 13] ^ t3;
		}
		return rk;
	}

	function aes128EncryptBlock(rk, inp, out) {
		var s = new Uint8Array(16);
		var i;
		for (i = 0; i < 16; i++) { s[i] = inp[i] ^ rk[i]; }
		for (var round = 1; round <= 10; round++) {
			var b0 = AES_SBOX[s[0]], b1 = AES_SBOX[s[5]], b2 = AES_SBOX[s[10]], b3 = AES_SBOX[s[15]];
			var b4 = AES_SBOX[s[4]], b5 = AES_SBOX[s[9]], b6 = AES_SBOX[s[14]], b7 = AES_SBOX[s[3]];
			var b8 = AES_SBOX[s[8]], b9 = AES_SBOX[s[13]], b10 = AES_SBOX[s[2]], b11 = AES_SBOX[s[7]];
			var b12 = AES_SBOX[s[12]], b13 = AES_SBOX[s[1]], b14 = AES_SBOX[s[6]], b15 = AES_SBOX[s[11]];
			var ro = round << 4;
			if (round < 10) {
				var cols = [
					[b0, b1, b2, b3], [b4, b5, b6, b7], [b8, b9, b10, b11], [b12, b13, b14, b15]
				];
				for (var c = 0; c < 4; c++) {
					var c0 = cols[c][0], c1 = cols[c][1], c2 = cols[c][2], c3 = cols[c][3];
					var t = c0 ^ c1 ^ c2 ^ c3;
					var ci = c << 2;
					s[ci] = (c0 ^ t ^ xtime(c0 ^ c1)) ^ rk[ro + ci];
					s[ci + 1] = (c1 ^ t ^ xtime(c1 ^ c2)) ^ rk[ro + ci + 1];
					s[ci + 2] = (c2 ^ t ^ xtime(c2 ^ c3)) ^ rk[ro + ci + 2];
					s[ci + 3] = (c3 ^ t ^ xtime(c3 ^ c0)) ^ rk[ro + ci + 3];
				}
			} else {
				s[0] = b0 ^ rk[ro]; s[1] = b1 ^ rk[ro + 1]; s[2] = b2 ^ rk[ro + 2]; s[3] = b3 ^ rk[ro + 3];
				s[4] = b4 ^ rk[ro + 4]; s[5] = b5 ^ rk[ro + 5]; s[6] = b6 ^ rk[ro + 6]; s[7] = b7 ^ rk[ro + 7];
				s[8] = b8 ^ rk[ro + 8]; s[9] = b9 ^ rk[ro + 9]; s[10] = b10 ^ rk[ro + 10]; s[11] = b11 ^ rk[ro + 11];
				s[12] = b12 ^ rk[ro + 12]; s[13] = b13 ^ rk[ro + 13]; s[14] = b14 ^ rk[ro + 14]; s[15] = b15 ^ rk[ro + 15];
			}
		}
		out.set(s, 0);
	}

	function gf128Mul(x, h) {
		var z = new Uint8Array(16);
		var v = new Uint8Array(h);
		for (var i = 0; i < 16; i++) {
			var xb = x[i];
			for (var bit = 7; bit >= 0; bit--) {
				if ((xb >>> bit) & 1) {
					for (var k = 0; k < 16; k++) { z[k] ^= v[k]; }
				}
				var lsb = v[15] & 1;
				for (var j = 15; j > 0; j--) { v[j] = (v[j] >>> 1) | ((v[j - 1] & 1) << 7); }
				v[0] = v[0] >>> 1;
				if (lsb) { v[0] ^= 0xe1; }
			}
		}
		return z;
	}

	function ghash(H, aad, ct) {
		var y = new Uint8Array(16);
		function feed(buf) {
			var off = 0;
			while (off < buf.length) {
				var take = Math.min(16, buf.length - off);
				for (var i = 0; i < take; i++) { y[i] ^= buf[off + i]; }
				y = gf128Mul(y, H);
				off += 16;
			}
		}
		feed(aad);
		feed(ct);
		var lens = new Uint8Array(16);
		var dv = new DataView(lens.buffer);
		var aBits = aad.length * 8;
		var cBits = ct.length * 8;
		dv.setUint32(0, Math.floor(aBits / 4294967296), false);
		dv.setUint32(4, aBits >>> 0, false);
		dv.setUint32(8, Math.floor(cBits / 4294967296), false);
		dv.setUint32(12, cBits >>> 0, false);
		for (var i = 0; i < 16; i++) { y[i] ^= lens[i]; }
		return gf128Mul(y, H);
	}

	function makePureAes128Gcm(keyBytes) {
		var rk = aes128ExpandKey(keyBytes);
		var H = new Uint8Array(16);
		aes128EncryptBlock(rk, new Uint8Array(16), H);

		function gctr(iv12, inp) {
			var out = new Uint8Array(inp.length);
			var ctr = new Uint8Array(16);
			ctr.set(iv12, 0);
			var c = 2;
			var ks = new Uint8Array(16);
			for (var off = 0; off < inp.length; off += 16) {
				ctr[12] = (c >>> 24) & 0xff;
				ctr[13] = (c >>> 16) & 0xff;
				ctr[14] = (c >>> 8) & 0xff;
				ctr[15] = c & 0xff;
				c = (c + 1) >>> 0;
				aes128EncryptBlock(rk, ctr, ks);
				var take = Math.min(16, inp.length - off);
				for (var i = 0; i < take; i++) { out[off + i] = inp[off + i] ^ ks[i]; }
			}
			return out;
		}

		function j0Mask(iv12) {
			var j0 = new Uint8Array(16);
			j0.set(iv12, 0);
			j0[15] = 1;
			var mask = new Uint8Array(16);
			aes128EncryptBlock(rk, j0, mask);
			return mask;
		}

		return {
			encrypt: function (iv12, aad, pt) {
				var ct = gctr(iv12, pt);
				var s = ghash(H, aad, ct);
				var mask = j0Mask(iv12);
				var out = new Uint8Array(ct.length + 16);
				out.set(ct, 0);
				for (var i = 0; i < 16; i++) { out[ct.length + i] = s[i] ^ mask[i]; }
				return Promise.resolve(out);
			},
			decrypt: function (iv12, aad, ctAndTag) {
				if (ctAndTag.length < 16) { return Promise.reject(new Error("AES-GCM ciphertext too short")); }
				var ct = ctAndTag.subarray(0, ctAndTag.length - 16);
				var tag = ctAndTag.subarray(ctAndTag.length - 16);
				var s = ghash(H, aad, ct);
				var mask = j0Mask(iv12);
				var expected = new Uint8Array(16);
				for (var i = 0; i < 16; i++) { expected[i] = s[i] ^ mask[i]; }
				if (!ctEqual(tag, expected)) { return Promise.reject(new Error("AES-GCM authentication failed")); }
				return Promise.resolve(gctr(iv12, ct));
			}
		};
	}

	function makeAes128Gcm(keyBytes, forcePure) {
		var subtle = (!forcePure && G.crypto && G.crypto.subtle && typeof G.crypto.subtle.importKey === "function")
			? G.crypto.subtle : null;
		if (!subtle) {
			return Promise.resolve(makePureAes128Gcm(keyBytes));
		}
		return subtle.importKey("raw", keyBytes, { name: "AES-GCM" }, false, ["encrypt", "decrypt"])
			.then(function (ck) {
				return {
					encrypt: function (iv12, aad, pt) {
						return subtle.encrypt({ name: "AES-GCM", iv: iv12, additionalData: aad, tagLength: 128 }, ck, pt)
							.then(function (ab) { return new Uint8Array(ab); });
					},
					decrypt: function (iv12, aad, ctAndTag) {
						return subtle.decrypt({ name: "AES-GCM", iv: iv12, additionalData: aad, tagLength: 128 }, ck, ctAndTag)
							.then(function (ab) { return new Uint8Array(ab); });
					}
				};
			}, function () {
				return makePureAes128Gcm(keyBytes);
			});
	}

	function xorSeqIv(baseIv, seq) {
		var iv = new Uint8Array(baseIv);
		var lo = seq >>> 0;
		var hi = Math.floor(seq / 4294967296) >>> 0;
		iv[4] ^= (hi >>> 24) & 0xff;
		iv[5] ^= (hi >>> 16) & 0xff;
		iv[6] ^= (hi >>> 8) & 0xff;
		iv[7] ^= hi & 0xff;
		iv[8] ^= (lo >>> 24) & 0xff;
		iv[9] ^= (lo >>> 16) & 0xff;
		iv[10] ^= (lo >>> 8) & 0xff;
		iv[11] ^= lo & 0xff;
		return iv;
	}

	// =========================================================================
	// 5. Relay resolution & multiplexed WISP Carrier (WebRTC + WebSocket)
	// =========================================================================

	var KEY_SELECTED = "minecraft_web:relay.selected";
	var KEY_CUSTOM = "minecraft_web:relays.custom";
	var KEY_LEGACY = "minecraft_web:relays";
	var KEY_TOKEN = "minecraft_web:relay.token";
	var KEY_TRANSPORT = "minecraft_web:relay.transport";

	function lsGet(k) {
		try { return (typeof window !== "undefined" && window.localStorage) ? (window.localStorage.getItem(k) || "") : ""; }
		catch (e) { return ""; }
	}

	function normalizeRelay(url) {
		var m = /^(https?|wss?):\/\/([^\/?#@]+)(\/|\/socket\/?)?$/i.exec(String(url || "").trim());
		if (!m) { return ""; }
		var sch = m[1].toLowerCase();
		sch = sch === "http" ? "ws" : sch === "https" ? "wss" : sch;
		return sch + "://" + m[2].toLowerCase() + "/socket/";
	}

	function getNetApi() {
		try { return G[Symbol.for("net.minecraft.client.n")] || null; } catch (e) { return null; }
	}

	function listRelays() {
		var net = getNetApi();
		var out = [];
		var builtins = net && typeof net.c === "function" ? (net.c() | 0) : 0;
		for (var i = 1; i <= builtins; i++) {
			out.push({ url: "wss://relay-" + i + ".invalid/socket/", label: "Relay " + i, builtin: true });
		}
		var rawCustom = (lsGet(KEY_CUSTOM) + "\n" + lsGet(KEY_LEGACY)).split("\n");
		for (var j = 0; j < rawCustom.length; j++) {
			var line = rawCustom[j].trim();
			if (!line) { continue; }
			var aliased = (net && typeof net.a === "function") ? String(net.a(line)) : line;
			var norm = normalizeRelay(aliased);
			if (!norm) { continue; }
			var dup = false;
			for (var k = 0; k < out.length; k++) {
				if (out[k].url === norm) { dup = true; break; }
			}
			if (!dup) {
				out.push({ url: norm, label: "Relay " + (out.length + 1), builtin: false });
			}
		}
		return out;
	}

	function orderedRelays() {
		var net = getNetApi();
		var all = listRelays();
		if (!all.length) { return []; }
		var selected = normalizeRelay(lsGet(KEY_SELECTED));
		if (selected) {
			for (var i = 0; i < all.length; i++) {
				if (all[i].url === selected) {
					var rest = all.slice(0, i).concat(all.slice(i + 1));
					return [all[i]].concat(rest);
				}
			}
		}
		// Auto mode: sort ok first (lowest ping), then checking/unknown, then failed last
		var scored = all.map(function (r, idx) {
			var st = net && typeof net.s === "function" ? String(net.s(r.url) || "") : "";
			var ping = net && typeof net.t === "function" ? (net.t(r.url) | 0) : -1;
			var rank = st === "ok" ? 0 : (st === "fail" ? 2 : 1);
			return { relay: r, rank: rank, ping: ping > 0 ? ping : 99999, idx: idx };
		});
		scored.sort(function (a, b) {
			if (a.rank !== b.rank) { return a.rank - b.rank; }
			if (a.ping !== b.ping) { return a.ping - b.ping; }
			return a.idx - b.idx;
		});
		return scored.map(function (x) { return x.relay; });
	}

	function unmaskRelay(url) {
		var net = getNetApi();
		try { return (net && typeof net.u === "function") ? String(net.u(url)) : url; }
		catch (e) { return url; }
	}

	function buildCarrierSocketUrl(relayUrl) {
		var real = unmaskRelay(relayUrl);
		var token = lsGet(KEY_TOKEN);
		var transport = lsGet(KEY_TRANSPORT);
		var q = [];
		if (token) { q.push("token=" + encodeURIComponent(token)); }
		if (transport === "webrtc" || transport === "websocket") { q.push("transport=" + transport); }
		return real + (q.length ? "?" + q.join("&") : "");
	}

	// Open a WebRTC DataChannel (falling back to WebSocket socket-v1) matching WebRelayRpc
	function createWispCarrier(relayEntry, overrideSocketUrl) {
		var realUrl = overrideSocketUrl || buildCarrierSocketUrl(relayEntry.url);
		var transport = "auto";
		try {
			var sp = new URL(realUrl).searchParams.get("transport");
			if (sp === "webrtc" || sp === "websocket") { transport = sp; }
		} catch (e) {}

		var carrier = {
			relayUrl: relayEntry.url,
			relayLabel: relayEntry.label,
			kind: "connecting",
			readyState: 0,
			nextStreamId: 100,
			streams: Object.create(null),
			openWaiters: [],
			idleTimer: 0,
			closed: false,
			link: null,
			pc: null,
			outgoing: [],
			outBytes: 0,
			parts: [],
			partBytes: 0,
			partText: -1,
			fallbackTimer: 0
		};

		function failAll(reason) {
			if (carrier.closed) { return; }
			carrier.closed = true;
			carrier.readyState = 3;
			if (carrier.fallbackTimer) { clearTimeout(carrier.fallbackTimer); carrier.fallbackTimer = 0; }
			if (carrier.idleTimer) { clearTimeout(carrier.idleTimer); carrier.idleTimer = 0; }
			var waiters = carrier.openWaiters; carrier.openWaiters = [];
			for (var i = 0; i < waiters.length; i++) { waiters[i].reject(new Error(reason)); }
			var st = carrier.streams; carrier.streams = Object.create(null);
			Object.keys(st).forEach(function (k) {
				try { st[k].onClose(reason); } catch (e) {}
			});
			try { if (carrier.link) { carrier.link.close(); } } catch (e) {}
			try { if (carrier.pc) { carrier.pc.close(); } } catch (e) {}
		}

		function touchIdle() {
			if (carrier.idleTimer) { clearTimeout(carrier.idleTimer); carrier.idleTimer = 0; }
			if (carrier.closed || Object.keys(carrier.streams).length > 0) { return; }
			carrier.idleTimer = setTimeout(function () {
				if (Object.keys(carrier.streams).length === 0) { failAll("idle"); }
			}, IDLE_CARRIER_MS);
		}

		function onOpen(kind) {
			if (carrier.closed || carrier.readyState === 1) { return; }
			carrier.kind = kind;
			carrier.readyState = 1;
			if (carrier.fallbackTimer) { clearTimeout(carrier.fallbackTimer); carrier.fallbackTimer = 0; }
			var waiters = carrier.openWaiters; carrier.openWaiters = [];
			for (var i = 0; i < waiters.length; i++) { waiters[i].resolve(carrier); }
			touchIdle();
		}

		function onBinaryMessage(buf) {
			var u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
			if (u8.length < 5) { return; }
			var type = u8[0];
			var sid = (u8[1] | (u8[2] << 8) | (u8[3] << 16) | (u8[4] << 24)) >>> 0;
			var stream = carrier.streams[sid];
			if (!stream) { return; }
			if (type === 0x02) { // TYPE_DATA
				stream.onData(u8.subarray(5));
			} else if (type === 0x04) { // TYPE_CLOSE
				var reasonCode = u8.length > 5 ? u8[5] : 0;
				delete carrier.streams[sid];
				stream.onClose(reasonCode === 0 ? "eof" : ("wisp close 0x" + reasonCode.toString(16)));
				touchIdle();
			} else if (type === 0x03) { // TYPE_CONTINUE
				if (stream.onContinue && u8.length >= 9) {
					var credit = (u8[5] | (u8[6] << 8) | (u8[7] << 16) | (u8[8] << 24)) >>> 0;
					stream.onContinue(credit);
				}
			}
		}

		function pumpRtc() {
			if (carrier.closed || carrier.readyState !== 1 || carrier.kind !== "webrtc") { return; }
			try {
				while (carrier.outgoing.length) {
					var item = carrier.outgoing[0];
					var end = Math.min(item.at + 16383, item.bytes.length);
					if (carrier.link.bufferedAmount + 1 + (end - item.at) > 262144) { break; }
					var wire = new Uint8Array(1 + (end - item.at));
					wire[0] = (end === item.bytes.length) ? 2 : 0;
					wire.set(item.bytes.subarray(item.at, end), 1);
					carrier.link.send(wire);
					item.at = end;
					if (end === item.bytes.length) {
						carrier.outBytes -= item.bytes.length;
						carrier.outgoing.shift();
					}
				}
			} catch (e) {
				failAll("rtc send failed: " + e);
			}
		}

		carrier.sendRaw = function (u8) {
			if (carrier.closed || carrier.readyState !== 1 || !carrier.link) {
				throw new Error("relay carrier closed");
			}
			if (carrier.kind === "websocket") {
				carrier.link.send(u8);
				return;
			}
			var copy = new Uint8Array(u8);
			carrier.outgoing.push({ bytes: copy, at: 0 });
			carrier.outBytes += copy.length;
			pumpRtc();
		};

		carrier.waitOpen = function () {
			if (carrier.readyState === 1) { return Promise.resolve(carrier); }
			if (carrier.closed) { return Promise.reject(new Error("relay carrier closed")); }
			return new Promise(function (resolve, reject) {
				carrier.openWaiters.push({ resolve: resolve, reject: reject });
			});
		};

		carrier.openTcpStream = function (host, port) {
			return carrier.waitOpen().then(function () {
				var sid = carrier.nextStreamId++;
				if (carrier.nextStreamId > 0x7fffffff) { carrier.nextStreamId = 100; }
				var stream = {
					id: sid,
					closed: false,
					onData: function () {},
					onClose: function () {},
					onContinue: null,
					send: function (bytes) {
						if (stream.closed || carrier.closed) { throw new Error("wisp stream closed"); }
						// Chunk into <= 16000 byte WISP DATA frames
						var off = 0;
						while (off < bytes.length) {
							var end = Math.min(off + 16000, bytes.length);
							var frame = new Uint8Array(5 + (end - off));
							frame[0] = 0x02;
							frame[1] = sid & 0xff;
							frame[2] = (sid >>> 8) & 0xff;
							frame[3] = (sid >>> 16) & 0xff;
							frame[4] = (sid >>> 24) & 0xff;
							frame.set(bytes.subarray(off, end), 5);
							carrier.sendRaw(frame);
							off = end;
						}
					},
					close: function () {
						if (stream.closed) { return; }
						stream.closed = true;
						delete carrier.streams[sid];
						if (!carrier.closed && carrier.readyState === 1) {
							try {
								var cf = new Uint8Array([0x04, sid & 0xff, (sid >>> 8) & 0xff, (sid >>> 16) & 0xff, (sid >>> 24) & 0xff, 0]);
								carrier.sendRaw(cf);
							} catch (e) {}
						}
						touchIdle();
					}
				};
				if (carrier.idleTimer) { clearTimeout(carrier.idleTimer); carrier.idleTimer = 0; }
				carrier.streams[sid] = stream;
				var hb = strToUtf8(host);
				var connect = new Uint8Array(5 + 3 + hb.length);
				connect[0] = 0x01; // TYPE_CONNECT
				connect[1] = sid & 0xff;
				connect[2] = (sid >>> 8) & 0xff;
				connect[3] = (sid >>> 16) & 0xff;
				connect[4] = (sid >>> 24) & 0xff;
				connect[5] = 1; // TCP
				connect[6] = port & 0xff;
				connect[7] = (port >>> 8) & 0xff;
				connect.set(hb, 8);
				carrier.sendRaw(connect);
				return stream;
			});
		};

		carrier.close = function () { failAll("closed"); };

		function startWs() {
			if (carrier.closed || carrier.readyState === 1 || carrier.kind === "websocket") { return; }
			carrier.kind = "websocket";
			try { if (carrier.pc) { carrier.pc.close(); } } catch (e) {}
			carrier.pc = null;
			if (carrier.fallbackTimer) { clearTimeout(carrier.fallbackTimer); carrier.fallbackTimer = 0; }
			var ws;
			try {
				ws = new WebSocket(realUrl, "socket-v1");
			} catch (e) {
				failAll("WebSocket open failed: " + e);
				return;
			}
			carrier.link = ws;
			ws.binaryType = "arraybuffer";
			var connectTimeout = setTimeout(function () {
				if (carrier.readyState === 0) { failAll("relay WebSocket connect timeout"); }
			}, 12000);
			ws.onopen = function () { clearTimeout(connectTimeout); onOpen("websocket"); };
			ws.onmessage = function (ev) {
				if (typeof ev.data !== "string") { onBinaryMessage(ev.data); }
			};
			ws.onerror = function () { clearTimeout(connectTimeout); failAll("relay WebSocket error"); };
			ws.onclose = function () { clearTimeout(connectTimeout); failAll("relay WebSocket closed"); };
		}

		function fallbackToWs() {
			if (carrier.closed || carrier.readyState === 1 || carrier.kind === "websocket") { return; }
			if (transport === "webrtc") { failAll("WebRTC carrier failed"); }
			else { startWs(); }
		}

		if (transport === "websocket" || typeof RTCPeerConnection !== "function" || typeof fetch !== "function") {
			startWs();
			return carrier;
		}

		// Try WebRTC DataChannel first (same signaling as WebRelayRpc), auto-fallback to WebSocket
		carrier.kind = "webrtc";
		try {
			var pc = new RTCPeerConnection();
			carrier.pc = pc;
			var dc = pc.createDataChannel("socket-v1", { ordered: true, protocol: "socket-v1" });
			carrier.link = dc;
			dc.binaryType = "arraybuffer";
			dc.bufferedAmountLowThreshold = 131072;
			dc.onbufferedamountlow = pumpRtc;
			carrier.fallbackTimer = setTimeout(fallbackToWs, 6500);
			dc.onopen = function () {
				if (carrier.closed || carrier.link !== dc) { try { dc.close(); } catch (e) {} return; }
				onOpen("webrtc");
			};
			dc.onmessage = function (ev) {
				if (carrier.closed || carrier.link !== dc) { return; }
				try {
					var b = new Uint8Array(ev.data);
					if (!b.length || (b[0] & 252)) { throw new Error("bad RTC frame"); }
					var isText = b[0] & 1;
					if (carrier.partText !== -1 && carrier.partText !== isText) { throw new Error("mixed RTC frame"); }
					carrier.partText = isText;
					var piece = b.subarray(1);
					carrier.partBytes += piece.length;
					carrier.parts.push(piece);
					if (!(b[0] & 2)) { return; }
					var all = concatBytes(carrier.parts);
					carrier.parts = [];
					carrier.partBytes = 0;
					carrier.partText = -1;
					if (!isText) { onBinaryMessage(all); }
				} catch (err) {
					failAll("RTC frame error: " + err);
				}
			};
			dc.onerror = function () { if (carrier.readyState === 1) { failAll("RTC error"); } else { fallbackToWs(); } };
			dc.onclose = function () { if (carrier.readyState === 1) { failAll("RTC closed"); } else { fallbackToWs(); } };
			var gatherIce = function () {
				if (pc.iceGatheringState === "complete") { return Promise.resolve(); }
				return new Promise(function (ok, no) {
					var t = setTimeout(function () { no(new Error("ICE timeout")); }, 4000);
					pc.onicegatheringstatechange = function () {
						if (pc.iceGatheringState === "complete") { clearTimeout(t); ok(); }
					};
				});
			};
			pc.createOffer()
				.then(function (offer) { return pc.setLocalDescription(offer); })
				.then(gatherIce)
				.then(function () {
					if (carrier.closed || carrier.readyState === 1 || carrier.kind !== "webrtc") { return null; }
					var ep = new URL(realUrl);
					ep.protocol = ep.protocol === "wss:" ? "https:" : "http:";
					ep.pathname = "/rtc/offer";
					ep.hash = "";
					return fetch(ep.toString(), {
						method: "POST",
						headers: { "content-type": "application/json", "x-socket-protocol": "socket-v1" },
						body: JSON.stringify(pc.localDescription)
					}).then(function (r) {
						if (!r.ok) { throw new Error("RTC signaling HTTP " + r.status); }
						return r.json();
					}).then(function (ans) {
						if (!ans || ans.type !== "answer" || typeof ans.sdp !== "string") { throw new Error("bad answer"); }
						return pc.setRemoteDescription(ans);
					});
				})
				.catch(function () { fallbackToWs(); });
		} catch (e) {
			fallbackToWs();
		}
		return carrier;
	}

	var activeCarrier = null;
	var overrideTestRelayUrl = null;

	function getCarrier() {
		if (overrideTestRelayUrl) {
			if (activeCarrier && !activeCarrier.closed && activeCarrier.relayUrl === overrideTestRelayUrl) {
				return activeCarrier.waitOpen();
			}
			activeCarrier = createWispCarrier({ url: overrideTestRelayUrl, label: "Relay 1" }, overrideTestRelayUrl);
			return activeCarrier.waitOpen();
		}
		var list = orderedRelays();
		if (!list.length) {
			return Promise.reject(new Error("NO_RELAY"));
		}
		var preferred = list[0];
		if (activeCarrier && !activeCarrier.closed && activeCarrier.relayUrl === preferred.url) {
			return activeCarrier.waitOpen();
		}
		if (activeCarrier && !activeCarrier.closed) {
			// Only switch if user changed explicit relay
			var sel = normalizeRelay(lsGet(KEY_SELECTED));
			if (!sel || activeCarrier.relayUrl === sel) {
				return activeCarrier.waitOpen();
			}
			activeCarrier.close();
			activeCarrier = null;
		}
		// Try relays in ranked order until one opens
		function tryIdx(idx, lastErr) {
			if (idx >= list.length) {
				return Promise.reject(lastErr || new Error("All configured relays failed"));
			}
			var c = createWispCarrier(list[idx]);
			activeCarrier = c;
			return c.waitOpen().catch(function (err) {
				if (activeCarrier === c) { activeCarrier = null; }
				return tryIdx(idx + 1, err);
			});
		}
		return tryIdx(0, null);
	}

	// =========================================================================
	// 6. RFC 8446 TLS 1.3 Client over WISP TCP stream
	// =========================================================================

	var HRR_RANDOM = new Uint8Array([
		0xcf, 0x21, 0xad, 0x74, 0xe5, 0x9a, 0x61, 0x11, 0xbe, 0x1d, 0x8c, 0x02, 0x1e, 0x65, 0xb8, 0x91,
		0xc2, 0xa2, 0x11, 0x16, 0x7a, 0xbb, 0x8c, 0x5e, 0x07, 0x9e, 0x09, 0xe2, 0xc8, 0xa8, 0x33, 0x9c
	]);

	function buildTlsExtension(type, body) {
		var out = new Uint8Array(4 + body.length);
		out[0] = (type >>> 8) & 0xff; out[1] = type & 0xff;
		out[2] = (body.length >>> 8) & 0xff; out[3] = body.length & 0xff;
		out.set(body, 4);
		return out;
	}

	function buildClientHello(host, clientRandom, legacySid, clientPubKey) {
		var hostBytes = strToUtf8(host);
		// SNI extension (0x0000)
		var sniList = new Uint8Array(3 + hostBytes.length);
		sniList[0] = 0; // host_name
		sniList[1] = (hostBytes.length >>> 8) & 0xff;
		sniList[2] = hostBytes.length & 0xff;
		sniList.set(hostBytes, 3);
		var sniExt = buildTlsExtension(0x0000, concatBytes([u16be(sniList.length), sniList]));
		// supported_groups (0x000a): x25519 (0x001d)
		var groupsExt = buildTlsExtension(0x000a, new Uint8Array([0x00, 0x02, 0x00, 0x1d]));
		// signature_algorithms (0x000d)
		var sigAlgsExt = buildTlsExtension(0x000d, new Uint8Array([
			0x00, 0x0c,
			0x04, 0x03, // ecdsa_secp256r1_sha256
			0x05, 0x03, // ecdsa_secp384r1_sha384
			0x08, 0x04, // rsa_pss_rsae_sha256
			0x08, 0x05, // rsa_pss_rsae_sha384
			0x08, 0x07, // ed25519
			0x04, 0x01  // rsa_pkcs1_sha256
		]));
		// supported_versions (0x002b): TLS 1.3 (0x0304)
		var versionsExt = buildTlsExtension(0x002b, new Uint8Array([0x02, 0x03, 0x04]));
		// key_share (0x0033): x25519 (0x001d), 32 bytes
		var ksEntry = concatBytes([new Uint8Array([0x00, 0x1d, 0x00, 0x20]), clientPubKey]);
		var keyShareExt = buildTlsExtension(0x0033, concatBytes([u16be(ksEntry.length), ksEntry]));
		// ALPN (0x0010): "http/1.1"
		var alpnProto = strToUtf8("http/1.1");
		var alpnEntry = concatBytes([new Uint8Array([alpnProto.length]), alpnProto]);
		var alpnExt = buildTlsExtension(0x0010, concatBytes([u16be(alpnEntry.length), alpnEntry]));

		var exts = concatBytes([sniExt, groupsExt, sigAlgsExt, versionsExt, keyShareExt, alpnExt]);
		var body = concatBytes([
			new Uint8Array([0x03, 0x03]), // legacy_version TLS 1.2
			clientRandom,
			new Uint8Array([legacySid.length]), legacySid,
			new Uint8Array([0x00, 0x02, 0x13, 0x01]), // TLS_AES_128_GCM_SHA256
			new Uint8Array([0x01, 0x00]), // legacy_compression_methods = [null]
			u16be(exts.length), exts
		]);
		var hs = new Uint8Array(4 + body.length);
		hs[0] = 0x01; // ClientHello
		hs[1] = (body.length >>> 16) & 0xff;
		hs[2] = (body.length >>> 8) & 0xff;
		hs[3] = body.length & 0xff;
		hs.set(body, 4);
		return hs;
	}

	function wrapTlsRecord(contentType, legacyVerMinor, payload) {
		var rec = new Uint8Array(5 + payload.length);
		rec[0] = contentType;
		rec[1] = 0x03;
		rec[2] = legacyVerMinor;
		rec[3] = (payload.length >>> 8) & 0xff;
		rec[4] = payload.length & 0xff;
		rec.set(payload, 5);
		return rec;
	}

	function parseServerHello(body) {
		if (body.length < 38) { throw new Error("ServerHello too short"); }
		var serverRandom = body.subarray(2, 34);
		if (ctEqual(serverRandom, HRR_RANDOM)) {
			throw new Error("Server requested HelloRetryRequest (unsupported group)");
		}
		var pos = 34;
		var sidLen = body[pos++];
		pos += sidLen;
		var cipherSuite = (body[pos] << 8) | body[pos + 1];
		pos += 2;
		pos += 1; // compression
		if (cipherSuite !== 0x1301) {
			throw new Error("Unexpected TLS 1.3 cipher suite: 0x" + cipherSuite.toString(16));
		}
		var extLen = (body[pos] << 8) | body[pos + 1];
		pos += 2;
		var end = pos + extLen;
		var tls13 = false;
		var serverPub = null;
		while (pos + 4 <= end) {
			var et = (body[pos] << 8) | body[pos + 1];
			var el = (body[pos + 2] << 8) | body[pos + 3];
			pos += 4;
			if (et === 0x002b && el === 2) {
				if (body[pos] === 0x03 && body[pos + 1] === 0x04) { tls13 = true; }
			} else if (et === 0x0033 && el >= 4) {
				var group = (body[pos] << 8) | body[pos + 1];
				var klen = (body[pos + 2] << 8) | body[pos + 3];
				if (group === 0x001d && klen === 32 && pos + 4 + 32 <= end) {
					serverPub = body.subarray(pos + 4, pos + 36);
				}
			}
			pos += el;
		}
		if (!tls13) { throw new Error("Server did not negotiate TLS 1.3"); }
		if (!serverPub) { throw new Error("Server did not send X25519 key_share"); }
		return { serverPub: serverPub };
	}

	function openTls13Connection(carrier, host, port, forcePureAes) {
		return carrier.openTcpStream(host, port).then(function (wispStream) {
			return new Promise(function (resolveConn, rejectConn) {
				var state = "wait_sh"; // wait_sh -> wait_hs -> open -> closed
				var rawBuf = new Uint8Array(0);
				var hsFragBuf = new Uint8Array(0);
				var transcript = [];
				var clientPriv = randomBytes(32);
				var clientPub = x25519(clientPriv, X25519_BASE);
				var clientRandom = randomBytes(32);
				var legacySid = randomBytes(32);

				var handshakeSecret = null;
				var clientHsSecret = null;
				var serverHsSecret = null;
				var readCipher = null, readIv = null, readSeq = 0;
				var writeCipher = null, writeIv = null, writeSeq = 0;
				var appWriteCipher = null, appWriteIv = null;
				var appReadCipher = null, appReadIv = null;

				var processing = Promise.resolve();
				var appDataListeners = [];
				var appDataQueue = [];
				var closeListeners = [];
				var handshakeSettled = false;

				var hsTimer = setTimeout(function () {
					failConn(new Error("TLS 1.3 handshake timed out (" + host + ")"));
				}, HANDSHAKE_TIMEOUT_MS);

				function failConn(err) {
					clearTimeout(hsTimer);
					if (state === "closed") { return; }
					state = "closed";
					wispStream.close();
					if (!handshakeSettled) {
						handshakeSettled = true;
						rejectConn(err);
					}
					var cl = closeListeners; closeListeners = [];
					for (var i = 0; i < cl.length; i++) {
						try { cl[i](err); } catch (e) {}
					}
				}

				function emitAppData(chunk) {
					if (appDataListeners.length > 0) {
						var fn = appDataListeners[0];
						fn(chunk);
					} else {
						appDataQueue.push(chunk);
					}
				}

				function encryptAndSend(innerType, payload) {
					var inner = new Uint8Array(payload.length + 1);
					inner.set(payload, 0);
					inner[payload.length] = innerType;
					var ctLen = inner.length + 16;
					var aad = new Uint8Array([0x17, 0x03, 0x03, (ctLen >>> 8) & 0xff, ctLen & 0xff]);
					var iv = xorSeqIv(writeIv, writeSeq++);
					return writeCipher.encrypt(iv, aad, inner).then(function (ct) {
						var rec = new Uint8Array(5 + ct.length);
						rec.set(aad, 0);
						rec.set(ct, 5);
						wispStream.send(rec);
					});
				}

				function handleHandshakeMessage(hsMsg) {
					var hsType = hsMsg[0];
					var body = hsMsg.subarray(4);
					if (state === "wait_sh") {
						if (hsType !== 0x02) {
							throw new Error("Expected ServerHello (0x02), got 0x" + hsType.toString(16));
						}
						var parsed = parseServerHello(body);
						transcript.push(hsMsg);
						var chShHash = sha256(concatBytes(transcript));
						var shared = x25519(clientPriv, parsed.serverPub);
						var zero32 = new Uint8Array(32);
						var early = hkdfExtract(zero32, zero32);
						var derived = deriveSecret(early, "derived", sha256(new Uint8Array(0)));
						handshakeSecret = hkdfExtract(derived, shared);
						clientHsSecret = deriveSecret(handshakeSecret, "c hs traffic", chShHash);
						serverHsSecret = deriveSecret(handshakeSecret, "s hs traffic", chShHash);
						var sKey = hkdfExpandLabel(serverHsSecret, "key", null, 16);
						var sIv = hkdfExpandLabel(serverHsSecret, "iv", null, 12);
						var cKey = hkdfExpandLabel(clientHsSecret, "key", null, 16);
						var cIv = hkdfExpandLabel(clientHsSecret, "iv", null, 12);
						return Promise.all([
							makeAes128Gcm(sKey, forcePureAes),
							makeAes128Gcm(cKey, forcePureAes)
						]).then(function (ciphers) {
							readCipher = ciphers[0]; readIv = sIv; readSeq = 0;
							writeCipher = ciphers[1]; writeIv = cIv; writeSeq = 0;
							state = "wait_hs";
						});
					}
					if (state === "wait_hs") {
						if (hsType === 0x14) { // Server Finished
							var beforeFinHash = sha256(concatBytes(transcript));
							var finishedKey = hkdfExpandLabel(serverHsSecret, "finished", null, 32);
							var expectedVerify = hmacSha256(finishedKey, beforeFinHash);
							if (!ctEqual(body, expectedVerify)) {
								throw new Error("TLS 1.3 Server Finished verification failed");
							}
							transcript.push(hsMsg);
							var fullHash = sha256(concatBytes(transcript));
							var zero32b = new Uint8Array(32);
							var derived2 = deriveSecret(handshakeSecret, "derived", sha256(new Uint8Array(0)));
							var masterSecret = hkdfExtract(derived2, zero32b);
							var cAppSecret = deriveSecret(masterSecret, "c ap traffic", fullHash);
							var sAppSecret = deriveSecret(masterSecret, "s ap traffic", fullHash);
							var cAppKey = hkdfExpandLabel(cAppSecret, "key", null, 16);
							var cAppIv = hkdfExpandLabel(cAppSecret, "iv", null, 12);
							var sAppKey = hkdfExpandLabel(sAppSecret, "key", null, 16);
							var sAppIv = hkdfExpandLabel(sAppSecret, "iv", null, 12);

							// Build client Finished message using clientHsSecret
							var cFinKey = hkdfExpandLabel(clientHsSecret, "finished", null, 32);
							var cFinVerify = hmacSha256(cFinKey, fullHash);
							var cFinMsg = new Uint8Array(4 + 32);
							cFinMsg[0] = 0x14; cFinMsg[3] = 32;
							cFinMsg.set(cFinVerify, 4);

							// Send dummy ChangeCipherSpec for middlebox compatibility, then encrypted Client Finished
							wispStream.send(wrapTlsRecord(0x14, 0x03, new Uint8Array([0x01])));
							return encryptAndSend(0x16, cFinMsg).then(function () {
								return Promise.all([
									makeAes128Gcm(sAppKey, forcePureAes),
									makeAes128Gcm(cAppKey, forcePureAes)
								]);
							}).then(function (appCiphers) {
								appReadCipher = appCiphers[0]; appReadIv = sAppIv;
								appWriteCipher = appCiphers[1]; appWriteIv = cAppIv;
								readCipher = appReadCipher; readIv = appReadIv; readSeq = 0;
								writeCipher = appWriteCipher; writeIv = appWriteIv; writeSeq = 0;
								transcript = [];
								state = "open";
								clearTimeout(hsTimer);
								if (!handshakeSettled) {
									handshakeSettled = true;
									resolveConn(conn);
								}
							});
						} else {
							// EncryptedExtensions (8), Certificate (11), CertificateVerify (15), CertificateRequest (13)
							transcript.push(hsMsg);
							return Promise.resolve();
						}
					}
					// Post-handshake messages (e.g. NewSessionTicket type 4): ignore cleanly
					return Promise.resolve();
				}

				function drainHandshakeBytes(bytes) {
					hsFragBuf = hsFragBuf.length ? concatBytes([hsFragBuf, bytes]) : bytes;
					var chain = Promise.resolve();
					function next() {
						if (state === "closed" || hsFragBuf.length < 4) { return Promise.resolve(); }
						var mlen = (hsFragBuf[1] << 16) | (hsFragBuf[2] << 8) | hsFragBuf[3];
						if (hsFragBuf.length < 4 + mlen) { return Promise.resolve(); }
						var msg = hsFragBuf.subarray(0, 4 + mlen);
						hsFragBuf = hsFragBuf.subarray(4 + mlen);
						return Promise.resolve().then(function () {
							return handleHandshakeMessage(msg);
						}).then(next);
					}
					return chain.then(next);
				}

				function processRecord(recType, header, payload) {
					if (state === "closed") { return Promise.resolve(); }
					if (recType === 0x14) { // ChangeCipherSpec
						return Promise.resolve();
					}
					if (recType === 0x15) { // Plaintext Alert
						var desc = payload.length >= 2 ? payload[1] : 0;
						failConn(new Error("TLS alert " + desc));
						return Promise.resolve();
					}
					if (recType === 0x16) { // Plaintext Handshake
						return drainHandshakeBytes(payload);
					}
					if (recType === 0x17) { // Encrypted TLSInnerPlaintext
						if (!readCipher) {
							failConn(new Error("Unexpected encrypted record before handshake keys"));
							return Promise.resolve();
						}
						var iv = xorSeqIv(readIv, readSeq++);
						return readCipher.decrypt(iv, header, payload).then(function (plain) {
							var end = plain.length - 1;
							while (end >= 0 && plain[end] === 0) { end--; }
							if (end < 0) { throw new Error("Empty TLS inner plaintext"); }
							var innerType = plain[end];
							var content = plain.subarray(0, end);
							if (innerType === 0x16) { // Encrypted Handshake
								return drainHandshakeBytes(content);
							}
							if (innerType === 0x17) { // Application Data
								emitAppData(content);
								return;
							}
							if (innerType === 0x15) { // Encrypted Alert
								var alertLevel = content.length >= 1 ? content[0] : 2;
								var alertDesc = content.length >= 2 ? content[1] : 0;
								if (alertDesc === 0) {
									failConn(null); // clean close_notify EOF
								} else {
									failConn(new Error("TLS encrypted alert level=" + alertLevel + " desc=" + alertDesc));
								}
							}
						});
					}
					return Promise.resolve();
				}

				function drainRecords() {
					var records = [];
					while (rawBuf.length >= 5) {
						var rtype = rawBuf[0];
						var rlen = (rawBuf[3] << 8) | rawBuf[4];
						if (rlen > 18432) {
							failConn(new Error("TLS record overflow: " + rlen));
							return;
						}
						if (rawBuf.length < 5 + rlen) { break; }
						var hdr = rawBuf.subarray(0, 5);
						var pay = rawBuf.subarray(5, 5 + rlen);
						rawBuf = rawBuf.subarray(5 + rlen);
						records.push([rtype, hdr, pay]);
					}
					for (var i = 0; i < records.length; i++) {
						(function (rec) {
							processing = processing.then(function () {
								return processRecord(rec[0], rec[1], rec[2]);
							}).catch(function (err) {
								failConn(err);
							});
						})(records[i]);
					}
				}

				wispStream.onData = function (chunk) {
					if (state === "closed") { return; }
					rawBuf = rawBuf.length ? concatBytes([rawBuf, chunk]) : new Uint8Array(chunk);
					drainRecords();
				};

				wispStream.onClose = function (reason) {
					failConn(reason === "eof" ? null : new Error("Relay stream closed: " + reason));
				};

				var sendChain = Promise.resolve();
				var conn = {
					host: host,
					port: port,
					carrier: carrier,
					isOpen: function () { return state === "open"; },
					write: function (bytes) {
						if (state !== "open") { return Promise.reject(new Error("TLS connection not open")); }
						sendChain = sendChain.then(function () {
							var p = Promise.resolve();
							var off = 0;
							while (off < bytes.length) {
								(function (slice) {
									p = p.then(function () { return encryptAndSend(0x17, slice); });
								})(bytes.subarray(off, Math.min(off + 16384, bytes.length)));
								off += 16384;
							}
							return p;
						});
						return sendChain;
					},
					setConsumer: function (onChunk, onClose) {
						appDataListeners = onChunk ? [onChunk] : [];
						closeListeners = onClose ? [onClose] : [];
						if (onChunk && appDataQueue.length) {
							var q = appDataQueue; appDataQueue = [];
							for (var i = 0; i < q.length; i++) { onChunk(q[i]); }
						}
						if (state === "closed" && onClose) {
							onClose(null);
						}
					},
					close: function () {
						if (state === "open") {
							try { encryptAndSend(0x15, new Uint8Array([1, 0])).catch(function () {}); } catch (e) {}
						}
						failConn(null);
					}
				};

				// Kick off ClientHello
				try {
					var ch = buildClientHello(host, clientRandom, legacySid, clientPub);
					transcript.push(ch);
					wispStream.send(wrapTlsRecord(0x16, 0x01, ch));
				} catch (err) {
					failConn(err);
				}
			});
		});
	}

	// =========================================================================
	// 7. HTTP/1.1 Keep-Alive Pool over TLS 1.3 + Gzip & Chunked Decoding
	// =========================================================================

	var connPools = Object.create(null); // host -> { idle: [], active: 0, max: N, queue: [] }

	function getPool(host) {
		var p = connPools[host];
		if (!p) {
			p = connPools[host] = {
				idle: [],
				active: 0,
				max: host === "cdn.modrinth.com" ? 3 : 2,
				queue: []
			};
		}
		return p;
	}

	function acquireTls(host, port) {
		var pool = getPool(host);
		while (pool.idle.length) {
			var entry = pool.idle.pop();
			clearTimeout(entry.timer);
			if (entry.conn.isOpen() && !entry.conn.carrier.closed) {
				pool.active++;
				return Promise.resolve(entry.conn);
			}
			entry.conn.close();
		}
		if (pool.active >= pool.max) {
			return new Promise(function (resolve, reject) {
				pool.queue.push({ resolve: resolve, reject: reject, host: host, port: port });
			});
		}
		pool.active++;
		return getCarrier().then(function (carrier) {
			return openTls13Connection(carrier, host, port, false);
		}).catch(function (err) {
			pool.active--;
			drainPoolQueue(pool);
			throw err;
		});
	}

	function releaseTls(host, conn, reusable) {
		var pool = getPool(host);
		conn.setConsumer(null, null);
		pool.active = Math.max(0, pool.active - 1);
		if (reusable && conn.isOpen() && !conn.carrier.closed) {
			if (pool.queue.length) {
				var next = pool.queue.shift();
				pool.active++;
				next.resolve(conn);
				return;
			}
			var item = { conn: conn, timer: 0 };
			item.timer = setTimeout(function () {
				var idx = pool.idle.indexOf(item);
				if (idx >= 0) { pool.idle.splice(idx, 1); }
				conn.close();
			}, IDLE_CONN_MS);
			pool.idle.push(item);
			return;
		}
		conn.close();
		drainPoolQueue(pool);
	}

	function drainPoolQueue(pool) {
		while (pool.queue.length && pool.active < pool.max) {
			var next = pool.queue.shift();
			pool.active++;
			(function (item) {
				getCarrier().then(function (carrier) {
					return openTls13Connection(carrier, item.host, item.port, false);
				}).then(item.resolve, function (err) {
					pool.active = Math.max(0, pool.active - 1);
					item.reject(err);
					drainPoolQueue(pool);
				});
			})(next);
		}
	}

	function gunzip(u8) {
		if (typeof DecompressionStream !== "function") {
			return Promise.reject(new Error("DecompressionStream unavailable"));
		}
		var ds = new DecompressionStream("gzip");
		var w = ds.writable.getWriter();
		var r = ds.readable.getReader();
		w.write(u8);
		w.close();
		var chunks = [];
		function pump() {
			return r.read().then(function (res) {
				if (res.done) { return concatBytes(chunks); }
				chunks.push(res.value);
				return pump();
			});
		}
		return pump();
	}

	function findCrlfCrlf(u8) {
		for (var i = 0; i <= u8.length - 4; i++) {
			if (u8[i] === 13 && u8[i + 1] === 10 && u8[i + 2] === 13 && u8[i + 3] === 10) {
				return i;
			}
		}
		return -1;
	}

	function findCrlf(u8, from) {
		for (var i = from; i <= u8.length - 2; i++) {
			if (u8[i] === 13 && u8[i + 1] === 10) { return i; }
		}
		return -1;
	}

	function isAllowedUrl(urlStr) {
		try {
			var u = new URL(urlStr);
			if (u.protocol !== "https:") { return false; }
			var h = u.hostname.toLowerCase();
			return !!(ALLOWED_HOSTS[h] || h.endsWith(".modrinth.com"));
		} catch (e) {
			return false;
		}
	}

	function relayHttpGetOnce(urlStr, opts) {
		var u = new URL(urlStr);
		var host = u.hostname.toLowerCase();
		var port = u.port ? (parseInt(u.port, 10) || 443) : 443;
		var path = (u.pathname || "/") + (u.search || "");
		var maxBytes = (opts && opts.maxBytes) || MAX_API_BYTES;
		var timeoutMs = (opts && opts.timeoutMs) || REQUEST_TIMEOUT_MS;
		var wantGzip = !!(opts && opts.gzip && typeof DecompressionStream === "function");
		var onProgress = opts && opts.onProgress;

		return acquireTls(host, port).then(function (conn) {
			return new Promise(function (resolve, reject) {
				var settled = false;
				var buf = new Uint8Array(0);
				var headersParsed = false;
				var status = 0;
				var headers = Object.create(null);
				var chunked = false;
				var contentLength = -1;
				var connClose = false;
				var bodyParts = [];
				var bodyBytes = 0;
				var chunkNeed = -1; // -1 = reading size line, >=0 = reading chunk payload + CRLF

				var timer = setTimeout(function () {
					finish(new Error("Relay HTTP request timed out"), null, false);
				}, timeoutMs);

				function resetTimer() {
					clearTimeout(timer);
					timer = setTimeout(function () {
						finish(new Error("Relay HTTP read stalled"), null, false);
					}, timeoutMs);
				}

				function finish(err, body, reusable) {
					if (settled) { return; }
					settled = true;
					clearTimeout(timer);
					releaseTls(host, conn, reusable && !err && !connClose);
					if (err) { reject(err); return; }
					var ce = headers["content-encoding"] || "";
					var resMeta = {
						status: status,
						headers: headers,
						relayLabel: conn.carrier.relayLabel,
						transport: conn.carrier.kind
					};
					if (ce.toLowerCase() === "gzip" && body.length > 0) {
						gunzip(body).then(function (plain) {
							resMeta.body = plain;
							resolve(resMeta);
						}, reject);
					} else {
						resMeta.body = body;
						resolve(resMeta);
					}
				}

				function appendBody(slice) {
					if (!slice.length) { return; }
					bodyBytes += slice.length;
					if (bodyBytes > maxBytes) {
						finish(new Error("Response exceeded max size (" + maxBytes + " bytes)"), null, false);
						return;
					}
					bodyParts.push(new Uint8Array(slice));
					if (onProgress) {
						try { onProgress(bodyBytes, contentLength); } catch (e) {}
					}
				}

				function pumpBuffer() {
					if (settled) { return; }
					if (!headersParsed) {
						var sep = findCrlfCrlf(buf);
						if (sep < 0) {
							if (buf.length > 32768) { finish(new Error("HTTP headers too large"), null, false); }
							return;
						}
						var headText = utf8ToStr(buf.subarray(0, sep));
						buf = buf.subarray(sep + 4);
						var lines = headText.split("\r\n");
						var statusMatch = /^HTTP\/1\.[01]\s+(\d{3})/.exec(lines[0] || "");
						if (!statusMatch) { finish(new Error("Malformed HTTP status line"), null, false); return; }
						status = parseInt(statusMatch[1], 10);
						for (var i = 1; i < lines.length; i++) {
							var colon = lines[i].indexOf(":");
							if (colon > 0) {
								var k = lines[i].slice(0, colon).trim().toLowerCase();
								var v = lines[i].slice(colon + 1).trim();
								headers[k] = v;
							}
						}
						var te = (headers["transfer-encoding"] || "").toLowerCase();
						chunked = te.indexOf("chunked") >= 0;
						if (!chunked && headers["content-length"] !== undefined) {
							contentLength = parseInt(headers["content-length"], 10);
							if (!(contentLength >= 0)) { contentLength = -1; }
						}
						connClose = (headers["connection"] || "").toLowerCase().indexOf("close") >= 0;
						headersParsed = true;
						if (status === 204 || status === 304) {
							finish(null, new Uint8Array(0), !connClose);
							return;
						}
					}
					if (chunked) {
						while (!settled) {
							if (chunkNeed < 0) {
								var nl = findCrlf(buf, 0);
								if (nl < 0) { return; }
								var szStr = utf8ToStr(buf.subarray(0, nl)).split(";")[0].trim();
								var sz = parseInt(szStr, 16);
								if (!(sz >= 0)) { finish(new Error("Invalid HTTP chunk size"), null, false); return; }
								buf = buf.subarray(nl + 2);
								if (sz === 0) {
									// Wait for final CRLF after trailers (if present), or complete immediately
									finish(null, concatBytes(bodyParts), !connClose);
									return;
								}
								chunkNeed = sz;
							}
							if (buf.length < chunkNeed + 2) { return; }
							appendBody(buf.subarray(0, chunkNeed));
							buf = buf.subarray(chunkNeed + 2);
							chunkNeed = -1;
						}
					} else if (contentLength >= 0) {
						var rem = contentLength - bodyBytes;
						if (rem > 0 && buf.length > 0) {
							var take = Math.min(rem, buf.length);
							appendBody(buf.subarray(0, take));
							buf = buf.subarray(take);
						}
						if (bodyBytes >= contentLength) {
							finish(null, concatBytes(bodyParts), !connClose);
						}
					} else {
						if (buf.length > 0) {
							appendBody(buf);
							buf = new Uint8Array(0);
						}
					}
				}

				conn.setConsumer(
					function (chunk) {
						resetTimer();
						buf = buf.length ? concatBytes([buf, chunk]) : new Uint8Array(chunk);
						pumpBuffer();
					},
					function (err) {
						if (settled) { return; }
						if (!err && headersParsed && !chunked && contentLength < 0) {
							finish(null, concatBytes(bodyParts), false);
						} else {
							finish(err || new Error("Connection closed before HTTP response completed"), null, false);
						}
					}
				);

				var reqLines = [
					"GET " + path + " HTTP/1.1",
					"Host: " + host,
					"User-Agent: " + USER_AGENT,
					"Accept: " + ((opts && opts.accept) || "*/*"),
					"Accept-Encoding: " + (wantGzip ? "gzip" : "identity"),
					"Connection: keep-alive",
					"", ""
				];
				conn.write(strToUtf8(reqLines.join("\r\n"))).catch(function (err) {
					finish(err, null, false);
				});
			});
		});
	}

	function relayHttpGet(urlStr, opts, redirectsLeft) {
		var left = redirectsLeft === undefined ? 4 : redirectsLeft;
		if (!isAllowedUrl(urlStr)) {
			return Promise.reject(new Error("Blocked non-Modrinth host: " + urlStr));
		}
		// Retry once on stale keep-alive socket reset
		return relayHttpGetOnce(urlStr, opts).catch(function (err) {
			if (opts && opts._retried) { throw err; }
			var msg = String(err || "");
			if (msg.indexOf("NO_RELAY") >= 0) { throw err; }
			var nextOpts = Object.assign({}, opts, { _retried: true });
			return relayHttpGetOnce(urlStr, nextOpts);
		}).then(function (res) {
			if ((res.status === 301 || res.status === 302 || res.status === 303 || res.status === 307 || res.status === 308)
					&& res.headers.location && left > 0) {
				var nextUrl = new URL(res.headers.location, urlStr).toString();
				return relayHttpGet(nextUrl, opts, left - 1);
			}
			return res;
		});
	}

	// =========================================================================
	// 8. Modrinth v2 Wrapper, Search Cache & 64x64 RGBA Thumbnail Decoder
	// =========================================================================

	var searchCache = Object.create(null);
	var thumbCache = Object.create(null);
	var thumbOrder = [];
	var thumbInflight = Object.create(null);
	var lastRelayInfo = { relayLabel: "", transport: "" };

	function recordRelay(res) {
		if (res && res.relayLabel) {
			lastRelayInfo.relayLabel = res.relayLabel;
			lastRelayInfo.transport = res.transport || "";
		}
	}

	function getRelayStatus() {
		var list = orderedRelays();
		if (!list.length) {
			return { ready: false, relayCount: 0, relayLabel: "", transport: "" };
		}
		var top = list[0];
		var net = getNetApi();
		var st = net && typeof net.s === "function" ? String(net.s(top.url) || "") : "";
		var transport = (activeCarrier && !activeCarrier.closed && activeCarrier.readyState === 1)
			? activeCarrier.kind : (lastRelayInfo.transport || "");
		return {
			ready: st !== "fail",
			relayCount: list.length,
			relayLabel: (activeCarrier && !activeCarrier.closed ? activeCarrier.relayLabel : top.label),
			transport: transport
		};
	}

	function buildFacets(projectType, mcVersion) {
		var facets = [];
		if (projectType === "mod") {
			facets.push(["project_type:mod"]);
			facets.push(["categories:fabric"]);
		} else if (projectType === "datapack") {
			facets.push(["project_type:datapack"]);
		} else if (projectType === "resourcepack") {
			facets.push(["project_type:resourcepack"]);
		} else if (projectType === "shader") {
			facets.push(["project_type:shader"]);
			facets.push(["categories:iris", "categories:optifine"]);
		} else {
			facets.push(["project_type:mod", "project_type:resourcepack", "project_type:shader", "project_type:datapack"]);
		}
		if (mcVersion && mcVersion !== "any") {
			if (mcVersion === "26.3") {
				facets.push(["versions:26.3", "versions:1.21.11", "versions:1.21.10", "versions:1.21.8", "versions:1.21.5", "versions:1.21.4"]);
			} else if (mcVersion === "1.21") {
				facets.push(["versions:1.21.5", "versions:1.21.4", "versions:1.21.3", "versions:1.21.1", "versions:1.21"]);
			} else {
				facets.push(["versions:" + mcVersion]);
			}
		}
		return JSON.stringify(facets);
	}

	function search(opts) {
		var q = String((opts && opts.query) || "").trim();
		var projectType = String((opts && opts.projectType) || "mod");
		var mcVersion = String((opts && opts.mcVersion) || "any");
		var sort = String((opts && opts.sort) || "relevance");
		var offset = Math.max(0, (opts && opts.offset) | 0);
		var limit = Math.max(1, Math.min(30, ((opts && opts.limit) | 0) || 15));

		var params = [];
		if (q) { params.push("query=" + encodeURIComponent(q)); }
		params.push("facets=" + encodeURIComponent(buildFacets(projectType, mcVersion)));
		params.push("index=" + encodeURIComponent(sort));
		params.push("offset=" + offset);
		params.push("limit=" + limit);
		var url = "https://api.modrinth.com/v2/search?" + params.join("&");

		var cached = searchCache[url];
		if (cached && (Date.now() - cached.at) < SEARCH_CACHE_TTL_MS) {
			return Promise.resolve(cached.data);
		}
		return relayHttpGet(url, { accept: "application/json", gzip: true, maxBytes: MAX_API_BYTES }).then(function (res) {
			recordRelay(res);
			if (res.status !== 200) {
				throw new Error("Modrinth search HTTP " + res.status);
			}
			var parsed = JSON.parse(utf8ToStr(res.body));
			var hits = Array.isArray(parsed.hits) ? parsed.hits.map(function (h) {
				return {
					id: String(h.project_id || ""),
					slug: String(h.slug || ""),
					title: String(h.title || h.slug || "Untitled"),
					description: String(h.description || ""),
					author: String(h.author || ""),
					projectType: String(h.project_type || "mod"),
					iconUrl: String(h.icon_url || ""),
					downloads: Number(h.downloads || 0),
					follows: Number(h.follows || 0),
					latestVersion: String(h.latest_version || ""),
					clientSide: String(h.client_side || ""),
					serverSide: String(h.server_side || ""),
					categories: Array.isArray(h.categories) ? h.categories.slice(0, 6) : [],
					displayCategories: Array.isArray(h.display_categories) ? h.display_categories.slice(0, 6) : [],
					versions: Array.isArray(h.versions) ? h.versions.slice(-4) : []
				};
			}) : [];
			var out = {
				hits: hits,
				offset: Number(parsed.offset || 0),
				limit: Number(parsed.limit || limit),
				totalHits: Number(parsed.total_hits || hits.length),
				relayLabel: res.relayLabel,
				transport: res.transport
			};
			searchCache[url] = { at: Date.now(), data: out };
			return out;
		});
	}

	function getProject(idOrSlug) {
		var clean = encodeURIComponent(String(idOrSlug || "").trim());
		var url = "https://api.modrinth.com/v2/project/" + clean;
		return relayHttpGet(url, { accept: "application/json", gzip: true, maxBytes: MAX_API_BYTES }).then(function (res) {
			recordRelay(res);
			if (res.status !== 200) { throw new Error("Modrinth project HTTP " + res.status); }
			var p = JSON.parse(utf8ToStr(res.body));
			return {
				id: String(p.id || ""),
				slug: String(p.slug || ""),
				title: String(p.title || ""),
				description: String(p.description || ""),
				body: String(p.body || ""),
				projectType: String(p.project_type || "mod"),
				iconUrl: String(p.icon_url || ""),
				downloads: Number(p.downloads || 0),
				followers: Number(p.followers || 0),
				clientSide: String(p.client_side || ""),
				serverSide: String(p.server_side || ""),
				license: p.license && p.license.id ? String(p.license.id) : "",
				categories: Array.isArray(p.categories) ? p.categories : [],
				loaders: Array.isArray(p.loaders) ? p.loaders : [],
				gameVersions: Array.isArray(p.game_versions) ? p.game_versions : [],
				sourceUrl: String(p.source_url || ""),
				wikiUrl: String(p.wiki_url || ""),
				relayLabel: res.relayLabel,
				transport: res.transport
			};
		});
	}

	function mapVersionObj(v) {
		if (!v || typeof v !== "object") { return null; }
		var files = Array.isArray(v.files) ? v.files : [];
		var primary = null;
		for (var j = 0; j < files.length; j++) {
			if (files[j] && files[j].primary) { primary = files[j]; break; }
		}
		if (!primary && files.length) { primary = files[0]; }
		if (!primary || !primary.url) { return null; }
		var rawDeps = Array.isArray(v.dependencies) ? v.dependencies : [];
		var deps = [];
		var reqDepCount = 0;
		for (var d = 0; d < rawDeps.length; d++) {
			var dep = rawDeps[d];
			if (!dep || typeof dep !== "object") { continue; }
			var dtype = String(dep.dependency_type || "");
			var dpId = dep.project_id ? String(dep.project_id) : "";
			var dvId = dep.version_id ? String(dep.version_id) : "";
			if (!dpId && !dvId) { continue; }
			if (dtype === "required") { reqDepCount++; }
			deps.push({
				projectId: dpId,
				versionId: dvId,
				dependencyType: dtype
			});
		}
		return {
			id: String(v.id || ""),
			projectId: String(v.project_id || ""),
			name: String(v.name || v.version_number || ""),
			versionNumber: String(v.version_number || ""),
			versionType: String(v.version_type || "release"),
			datePublished: String(v.date_published || ""),
			downloads: Number(v.downloads || 0),
			gameVersions: Array.isArray(v.game_versions) ? v.game_versions : [],
			loaders: Array.isArray(v.loaders) ? v.loaders : [],
			fileUrl: String(primary.url),
			filename: String(primary.filename || "modrinth-download.jar"),
			fileSize: Number(primary.size || 0),
			sha1: primary.hashes && primary.hashes.sha1 ? String(primary.hashes.sha1) : "",
			requiredDepCount: reqDepCount,
			dependencies: deps
		};
	}

	function getVersion(versionId) {
		var clean = encodeURIComponent(String(versionId || "").trim());
		var url = "https://api.modrinth.com/v2/version/" + clean;
		return relayHttpGet(url, { accept: "application/json", gzip: true, maxBytes: MAX_API_BYTES }).then(function (res) {
			recordRelay(res);
			if (res.status !== 200) { throw new Error("Modrinth version HTTP " + res.status); }
			return mapVersionObj(JSON.parse(utf8ToStr(res.body)));
		});
	}

	function getProjectsBatch(ids) {
		if (!ids || !ids.length) { return Promise.resolve([]); }
		var url = "https://api.modrinth.com/v2/projects?ids=" + encodeURIComponent(JSON.stringify(ids));
		return relayHttpGet(url, { accept: "application/json", gzip: true, maxBytes: MAX_API_BYTES }).then(function (res) {
			recordRelay(res);
			if (res.status !== 200) { return []; }
			var arr = JSON.parse(utf8ToStr(res.body));
			return Array.isArray(arr) ? arr : [];
		}, function () { return []; });
	}

	function getVersions(idOrSlug, opts) {
		var clean = encodeURIComponent(String(idOrSlug || "").trim());
		var params = [];
		if (opts && opts.loader) {
			params.push("loaders=" + encodeURIComponent(JSON.stringify([opts.loader])));
		}
		var url = "https://api.modrinth.com/v2/project/" + clean + "/version" + (params.length ? "?" + params.join("&") : "");
		return relayHttpGet(url, { accept: "application/json", gzip: true, maxBytes: MAX_API_BYTES }).then(function (res) {
			recordRelay(res);
			if (res.status !== 200) { throw new Error("Modrinth versions HTTP " + res.status); }
			var arr = JSON.parse(utf8ToStr(res.body));
			if (!Array.isArray(arr)) { return []; }
			var out = [];
			for (var i = 0; i < arr.length && out.length < 40; i++) {
				var mapped = mapVersionObj(arr[i]);
				if (mapped) { out.push(mapped); }
			}
			return out;
		});
	}

	function pickMatchingDepVersion(versions, parentMcVersions) {
		if (!versions || !versions.length) { return null; }
		var parentSet = Object.create(null);
		if (Array.isArray(parentMcVersions)) {
			for (var i = 0; i < parentMcVersions.length; i++) { parentSet[parentMcVersions[i]] = true; }
		}
		function hasOverlap(v) {
			if (!v.gameVersions || !v.gameVersions.length) { return false; }
			for (var k = 0; k < v.gameVersions.length; k++) {
				if (parentSet[v.gameVersions[k]]) { return true; }
			}
			return false;
		}
		function hasRecent(v) {
			if (!v.gameVersions) { return false; }
			for (var k = 0; k < v.gameVersions.length; k++) {
				var gv = v.gameVersions[k];
				if (gv.indexOf("26.") === 0 || gv.indexOf("1.21") === 0) { return true; }
			}
			return false;
		}
		var passes = [
			function (v) { return v.versionType === "release" && hasOverlap(v); },
			function (v) { return hasOverlap(v); },
			function (v) { return v.versionType === "release" && hasRecent(v); },
			function (v) { return hasRecent(v); },
			function (v) { return v.versionType === "release"; }
		];
		for (var p = 0; p < passes.length; p++) {
			for (var vi = 0; vi < versions.length; vi++) {
				if (passes[p](versions[vi])) { return versions[vi]; }
			}
		}
		return versions[0];
	}

	function resolveInstallPlan(opts) {
		var rootProjectId = String((opts && opts.projectId) || "");
		var rootSlug = String((opts && opts.slug) || "");
		var rootTitle = String((opts && opts.title) || rootSlug || "Mod");
		var rootType = String((opts && opts.projectType) || "mod");
		var rootVersionId = String((opts && opts.versionId) || "");
		var preferredLoader = String((opts && opts.loader) || (rootType === "mod" ? "fabric" : ""));
		var installedTokens = Object.create(null);
		var rawInstalled = (opts && Array.isArray(opts.installed)) ? opts.installed : [];
		for (var ii = 0; ii < rawInstalled.length; ii++) {
			var tok = String(rawInstalled[ii] || "").toLowerCase().replace(/[-_]/g, "");
			if (tok) { installedTokens[tok] = true; }
		}
		function isAlreadyInstalled(pid, slug, filename) {
			var p = String(pid || "").toLowerCase().replace(/[-_]/g, "");
			var s = String(slug || "").toLowerCase().replace(/[-_]/g, "");
			var f = String(filename || "").toLowerCase().replace(/[-_]/g, "");
			if (p && installedTokens[p]) { return true; }
			if (s && installedTokens[s]) { return true; }
			if (f && installedTokens[f]) { return true; }
			return false;
		}

		var MAX_DEPTH = 3;
		var MAX_DEPS = 8;
		var visitedProjects = Object.create(null);
		var resolvedDeps = [];
		var skippedDeps = [];
		var warnings = [];
		var projectMetaCache = Object.create(null);

		function loadRootVersion() {
			if (rootVersionId) {
				return getVersion(rootVersionId);
			}
			return getVersions(rootProjectId, preferredLoader ? { loader: preferredLoader } : {}).then(function (list) {
				if (!list.length && preferredLoader) {
					return getVersions(rootProjectId, {});
				}
				return list;
			}).then(function (list) {
				if (!list.length) { throw new Error("No downloadable files found for " + rootTitle); }
				return pickMatchingDepVersion(list, ["26.3", "26.2", "1.21.11", "1.21.10", "1.21.8", "1.21.5", "1.21.4"]);
			});
		}

		function resolveOneDep(dep, parentVersion, depth) {
			if (depth > MAX_DEPTH || resolvedDeps.length >= MAX_DEPS) {
				return Promise.resolve();
			}
			var depProjId = dep.projectId || "";
			if (depProjId && visitedProjects[depProjId]) {
				return Promise.resolve();
			}
			var verPromise;
			if (dep.versionId) {
				verPromise = getVersion(dep.versionId).catch(function () { return null; });
			} else {
				verPromise = Promise.resolve(null);
			}
			return verPromise.then(function (exactVer) {
				var pid = (exactVer && exactVer.projectId) || depProjId;
				if (!pid || visitedProjects[pid]) { return null; }
				visitedProjects[pid] = true;
				var metaPromise = projectMetaCache[pid]
					? Promise.resolve(projectMetaCache[pid])
					: getProject(pid).then(function (m) { projectMetaCache[pid] = m; return m; }, function () {
						return { id: pid, slug: pid, title: pid, projectType: "mod", loaders: [] };
					});
				return metaPromise.then(function (meta) {
					// For universal multi-loader JARs (e.g. fabric+neoforge), skip Forge/NeoForge-only deps when targeting Fabric.
					if (preferredLoader && meta.projectType === "mod" && Array.isArray(meta.loaders) && meta.loaders.length > 0) {
						var hasLoader = false;
						for (var li = 0; li < meta.loaders.length; li++) {
							if (meta.loaders[li] === preferredLoader) { hasLoader = true; break; }
						}
						if (!hasLoader) {
							return null;
						}
					}
					if (isAlreadyInstalled(meta.id, meta.slug, exactVer ? exactVer.filename : "")) {
						skippedDeps.push(meta.title || meta.slug || pid);
						return null;
					}
					if (exactVer) {
						if (preferredLoader && meta.projectType === "mod" && Array.isArray(exactVer.loaders) && exactVer.loaders.length > 0) {
							var evHasLoader = false;
							for (var eli = 0; eli < exactVer.loaders.length; eli++) {
								if (exactVer.loaders[eli] === preferredLoader) { evHasLoader = true; break; }
							}
							if (!evHasLoader) {
								return null;
							}
						}
						return { meta: meta, version: exactVer };
					}
					var depLoader = (meta.projectType === "mod" && preferredLoader) ? preferredLoader : "";
					return getVersions(pid, depLoader ? { loader: depLoader } : {}).then(function (vlist) {
						var picked = pickMatchingDepVersion(vlist, parentVersion ? parentVersion.gameVersions : []);
						if (!picked) {
							if (!depLoader) {
								warnings.push("No file for dependency " + (meta.title || pid));
							}
							return null;
						}
						if (isAlreadyInstalled(meta.id, meta.slug, picked.filename)) {
							skippedDeps.push(meta.title || meta.slug || pid);
							return null;
						}
						return { meta: meta, version: picked };
					});
				});
			}).then(function (resolved) {
				if (!resolved || !resolved.version) { return; }
				// Recurse into transitive required dependencies first (bottom-up install order!)
				return resolveChildren(resolved.version, depth + 1).then(function () {
					if (resolvedDeps.length < MAX_DEPS) {
						resolvedDeps.push({
							projectId: resolved.meta.id || resolved.version.projectId,
							slug: resolved.meta.slug || resolved.meta.id,
							title: resolved.meta.title || resolved.meta.slug || resolved.version.filename,
							projectType: resolved.meta.projectType || "mod",
							versionId: resolved.version.id,
							versionNumber: resolved.version.versionNumber,
							filename: resolved.version.filename,
							fileUrl: resolved.version.fileUrl,
							fileSize: resolved.version.fileSize,
							isDependency: true
						});
					}
				});
			}, function (err) {
				warnings.push("Dependency lookup failed: " + String(err && err.message ? err.message : err));
			});
		}

		function resolveChildren(versionObj, depth) {
			if (!versionObj || !Array.isArray(versionObj.dependencies) || depth > MAX_DEPTH) {
				return Promise.resolve();
			}
			var reqs = [];
			var missingMetaIds = [];
			for (var i = 0; i < versionObj.dependencies.length; i++) {
				var d = versionObj.dependencies[i];
				if (d && d.dependencyType === "required") {
					reqs.push(d);
					if (d.projectId && !projectMetaCache[d.projectId] && !visitedProjects[d.projectId]) {
						missingMetaIds.push(d.projectId);
					}
				}
			}
			if (!reqs.length) { return Promise.resolve(); }
			var preBatch = missingMetaIds.length > 1
				? getProjectsBatch(missingMetaIds).then(function (arr) {
					for (var m = 0; m < arr.length; m++) {
						var p = arr[m];
						if (p && p.id) {
							projectMetaCache[p.id] = {
								id: String(p.id),
								slug: String(p.slug || p.id),
								title: String(p.title || p.slug || p.id),
								projectType: String(p.project_type || "mod"),
								loaders: Array.isArray(p.loaders) ? p.loaders : []
							};
						}
					}
				})
				: Promise.resolve();
			return preBatch.then(function () {
				var chain = Promise.resolve();
				reqs.forEach(function (dep) {
					chain = chain.then(function () {
						return resolveOneDep(dep, versionObj, depth);
					});
				});
				return chain;
			});
		}

		return loadRootVersion().then(function (rootVer) {
			if (!rootVer) { throw new Error("No downloadable version found for " + rootTitle); }
			var rPid = rootProjectId || rootVer.projectId;
			if (rPid) { visitedProjects[rPid] = true; }
			return resolveChildren(rootVer, 1).then(function () {
				var items = resolvedDeps.slice();
				items.push({
					projectId: rPid,
					slug: rootSlug || rPid,
					title: rootTitle,
					projectType: rootType,
					versionId: rootVer.id,
					versionNumber: rootVer.versionNumber,
					filename: rootVer.filename,
					fileUrl: rootVer.fileUrl,
					fileSize: rootVer.fileSize,
					isDependency: false
				});
				return {
					items: items,
					skippedDeps: skippedDeps,
					warnings: warnings
				};
			});
		});
	}

	function decodeImageToRgba(bytes, contentType, size) {
		var dim = size || 64;
		if (typeof document === "undefined") {
			return Promise.reject(new Error("No DOM canvas for image decode"));
		}
		var blob = new Blob([bytes], { type: contentType || "image/png" });
		function drawSource(src, sw, sh) {
			var canvas = document.createElement("canvas");
			canvas.width = dim;
			canvas.height = dim;
			var ctx = canvas.getContext("2d");
			ctx.clearRect(0, 0, dim, dim);
			ctx.imageSmoothingEnabled = true;
			var scale = Math.min(dim / Math.max(1, sw), dim / Math.max(1, sh));
			var dw = Math.max(1, Math.round(sw * scale));
			var dh = Math.max(1, Math.round(sh * scale));
			var dx = Math.floor((dim - dw) / 2);
			var dy = Math.floor((dim - dh) / 2);
			ctx.drawImage(src, dx, dy, dw, dh);
			var imgData = ctx.getImageData(0, 0, dim, dim);
			return new Int8Array(imgData.data.buffer, imgData.data.byteOffset, imgData.data.byteLength);
		}
		if (typeof createImageBitmap === "function") {
			return createImageBitmap(blob).then(function (bmp) {
				try {
					return drawSource(bmp, bmp.width, bmp.height);
				} finally {
					if (typeof bmp.close === "function") { bmp.close(); }
				}
			});
		}
		return new Promise(function (resolve, reject) {
			var blobUrl = URL.createObjectURL(blob);
			var img = new Image();
			img.onload = function () {
				URL.revokeObjectURL(blobUrl);
				try { resolve(drawSource(img, img.naturalWidth || dim, img.naturalHeight || dim)); }
				catch (e) { reject(e); }
			};
			img.onerror = function () {
				URL.revokeObjectURL(blobUrl);
				reject(new Error("Image decode failed"));
			};
			img.src = blobUrl;
		});
	}

	function putThumbCache(key, rgba) {
		if (!Object.prototype.hasOwnProperty.call(thumbCache, key)) {
			thumbOrder.push(key);
			while (thumbOrder.length > THUMB_CACHE_MAX) {
				var evict = thumbOrder.shift();
				delete thumbCache[evict];
			}
		}
		thumbCache[key] = rgba;
	}

	function fetchThumbnailRgba(urlStr, size) {
		var dim = size || 64;
		if (!urlStr || !isAllowedUrl(urlStr)) {
			return Promise.reject(new Error("Invalid thumbnail URL"));
		}
		var cacheKey = dim + "|" + urlStr;
		if (thumbCache[cacheKey]) {
			return Promise.resolve(thumbCache[cacheKey]);
		}
		if (thumbInflight[cacheKey]) {
			return thumbInflight[cacheKey];
		}
		var p = relayHttpGet(urlStr, {
			accept: "image/webp,image/png,image/jpeg,image/*;q=0.8",
			gzip: false,
			maxBytes: MAX_THUMB_BYTES,
			timeoutMs: 20000
		}).then(function (res) {
			recordRelay(res);
			if (res.status !== 200 || !res.body || !res.body.length) {
				throw new Error("Thumbnail HTTP " + res.status);
			}
			return decodeImageToRgba(res.body, res.headers["content-type"] || "image/png", dim);
		}).then(function (rgba) {
			putThumbCache(cacheKey, rgba);
			delete thumbInflight[cacheKey];
			return rgba;
		}, function (err) {
			delete thumbInflight[cacheKey];
			throw err;
		});
		thumbInflight[cacheKey] = p;
		return p;
	}

	function downloadFile(urlStr, onProgress) {
		if (!urlStr || !isAllowedUrl(urlStr)) {
			return Promise.reject(new Error("Invalid Modrinth download URL"));
		}
		return relayHttpGet(urlStr, {
			accept: "application/java-archive,application/zip,application/octet-stream,*/*",
			gzip: false,
			maxBytes: MAX_DOWNLOAD_BYTES,
			timeoutMs: DOWNLOAD_TIMEOUT_MS,
			onProgress: onProgress
		}).then(function (res) {
			recordRelay(res);
			if (res.status !== 200 || !res.body || !res.body.length) {
				throw new Error("Modrinth download HTTP " + res.status);
			}
			return new Int8Array(res.body.buffer, res.body.byteOffset, res.body.byteLength);
		});
	}

	// =========================================================================
	// 9. Public API & Self-Test Hooks
	// =========================================================================

	G.MinecraftWebModrinth = {
		getRelayStatus: getRelayStatus,
		search: search,
		getProject: getProject,
		getVersion: getVersion,
		getVersions: getVersions,
		resolveInstallPlan: resolveInstallPlan,
		fetchThumbnailRgba: fetchThumbnailRgba,
		downloadFile: downloadFile,
		relayHttpGet: relayHttpGet,
		_setTestRelay: function (wsUrl) {
			overrideTestRelayUrl = wsUrl || null;
			if (activeCarrier) { activeCarrier.close(); activeCarrier = null; }
		},
		_crypto: {
			sha256: sha256,
			hmacSha256: hmacSha256,
			hkdfExtract: hkdfExtract,
			hkdfExpandLabel: hkdfExpandLabel,
			x25519: x25519,
			makeAes128Gcm: makeAes128Gcm,
			makePureAes128Gcm: makePureAes128Gcm,
			openTls13Connection: openTls13Connection
		}
	};
})();
