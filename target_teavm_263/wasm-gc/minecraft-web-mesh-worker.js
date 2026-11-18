// minecraft-web by chiikabu (https://github.com/chiikabu)

"use strict";

var fr = Math.fround;

var CAP_AO = 4, NEED_NONCUBE = 1 << 8, NEED_TINT = 1 << 9, NEED_SHAPES = 1 << 10, NEED_FLUID = 1 << 11,
	NEED_TRANSLUCENT = 1 << 12, NEED_OFFSET = 1 << 13, NEED_SEED = 1 << 14, NEED_CUTOUT = 1 << 15;
var KERNEL_CAPS = NEED_NONCUBE | NEED_TINT | NEED_SHAPES | NEED_TRANSLUCENT | NEED_OFFSET | NEED_SEED | NEED_CUTOUT | NEED_FLUID;
var KERNEL_CAPS_HALO2 = CAP_AO;

var F_AIR = 1, F_SOLID_RENDER = 2, F_MODEL = 4, F_EMISSIVE = 8, F_FORCE_OPAQUE = 16, F_LIGHT_PERMEABLE = 32,
	F_COLLISION_FULL = 64, F_HAS_OFFSET = 128, F_CUSTOM_SEED = 256, F_LEAVES = 512, F_SOLID = 1024,
	F_BLOCKS_FLUID_FLOW = 2048, F_ICE = 4096, F_OVERLAY_NEIGHBOUR = 8192, F_FLUID_SOURCE = 1 << 15,
	F_FLUID_FALLING = 1 << 16, F_FLUID_TINT = 1 << 17;
var EX_OFFSET = 1, EX_SEED = 2;
var FULL_BRIGHT = 15728880;

var STEP_X = [0, 0, 0, 0, -1, 1], STEP_Y = [-1, 1, 0, 0, 0, 0], STEP_Z = [0, 0, -1, 1, 0, 0];
var OPPOSITE = [1, 0, 3, 2, 5, 4];

var epoch = 0;
var MAX_STATES = 65536;
var sKnown = new Uint8Array(MAX_STATES), sFlags = new Int32Array(MAX_STATES), sNeed = new Int32Array(MAX_STATES),
	sEmission = new Uint8Array(MAX_STATES), sShade = new Float32Array(MAX_STATES), sSkipRule = new Uint8Array(MAX_STATES),
	sSkipKey = new Uint16Array(MAX_STATES), sBlock = new Uint16Array(MAX_STATES), sOcc = new Uint16Array(MAX_STATES * 6),
	sTints = new Uint8Array(MAX_STATES), sModel = new Uint16Array(MAX_STATES), sFluidGroup = new Uint8Array(MAX_STATES),
	sFluidModel = new Uint16Array(MAX_STATES), sAmount = new Uint8Array(MAX_STATES), sOwnHeight = new Float32Array(MAX_STATES),
	sSturdy = new Uint8Array(MAX_STATES), sExtra = new Uint8Array(MAX_STATES);
var sNames = [];
var shapeRows = [null, null];
var FULL_ROWS = new Uint16Array(16).fill(65535);
var quadCount = 0, QF = new Float32Array(20 * 4096), QB = new Float32Array(6 * 4096), QM = new Int32Array(8 * 4096);
var QM_DIR = 0, QM_LAYER = 1, QM_TINT = 2, QM_SHADE = 3, QM_EMISSION = 4, QM_SPRITE = 5, QM_FLAGS = 6;
var QFLAG_PARTIAL = 1, QFLAG_PLANAR = 2, QFLAG_BOUNDARY = 4;
var partAO = [], partStart = [], partCount = [];
var modelKind = [], modelData = [];
var fluidModels = [];
var E4 = fr(1.0E-4), N4 = fr(0.9999);

function resetDefs() {
	sKnown.fill(0);
	sNames = [];
	shapeRows = [null, null];
	quadCount = 0;
	partAO = []; partStart = []; partCount = [];
	modelKind = []; modelData = [];
	fluidModels = [];
}

function growQuads() {
	var n = QF.length / 20 * 2;
	var f = new Float32Array(20 * n); f.set(QF); QF = f;
	var b = new Float32Array(6 * n); b.set(QB); QB = b;
	var m = new Int32Array(8 * n); m.set(QM); QM = m;
}

function readDefs(buf) {
	var dv = new DataView(buf), p = 8, end = buf.byteLength, named = [];
	while (p < end) {
		var rec = dv.getUint8(p); p += 1;
		if (rec === 1) {
			var sid = dv.getUint16(p, true); p += 2;
			var rows = new Uint16Array(16);
			for (var r = 0; r < 16; r++) { rows[r] = dv.getUint16(p, true); p += 2; }
			shapeRows[sid] = rows;
		} else if (rec === 2) {
			var pid = dv.getUint16(p, true); p += 2;
			partAO[pid] = dv.getUint8(p); p += 1;
			for (var b = 0; b < 7; b++) {
				var n = dv.getUint16(p, true); p += 2;
				partStart[pid * 7 + b] = quadCount;
				partCount[pid * 7 + b] = n;
				for (var i = 0; i < n; i++) {
					if ((quadCount + 1) * 20 > QF.length) { growQuads(); }
					var q = quadCount++, f = q * 20, m = q * 8;
					for (var k = 0; k < 20; k++) { QF[f + k] = dv.getFloat32(p, true); p += 4; }
					var dir = dv.getUint8(p);
					QM[m + QM_DIR] = dir;
					QM[m + QM_LAYER] = dv.getUint8(p + 1);
					QM[m + QM_TINT] = dv.getInt8(p + 2);
					QM[m + QM_SHADE] = dv.getInt8(p + 3);
					QM[m + QM_EMISSION] = dv.getUint8(p + 4);
					QM[m + QM_SPRITE] = dv.getUint16(p + 6, true);
					p += 8;
					var minX = 32, minY = 32, minZ = 32, maxX = -32, maxY = -32, maxZ = -32;
					for (var v = 0; v < 4; v++) {
						var x = QF[f + v * 3], y = QF[f + v * 3 + 1], z = QF[f + v * 3 + 2];
						minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
						maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
					}
					var bb = q * 6;
					QB[bb] = minX; QB[bb + 1] = minY; QB[bb + 2] = minZ; QB[bb + 3] = maxX; QB[bb + 4] = maxY; QB[bb + 5] = maxZ;
					var partial, planar, boundary;
					if (dir < 2) {
						partial = minX >= E4 || minZ >= E4 || maxX <= N4 || maxZ <= N4;
						planar = minY === maxY; boundary = dir === 0 ? minY < E4 : maxY > N4;
					} else if (dir < 4) {
						partial = minX >= E4 || minY >= E4 || maxX <= N4 || maxY <= N4;
						planar = minZ === maxZ; boundary = dir === 2 ? minZ < E4 : maxZ > N4;
					} else {
						partial = minY >= E4 || minZ >= E4 || maxY <= N4 || maxZ <= N4;
						planar = minX === maxX; boundary = dir === 4 ? minX < E4 : maxX > N4;
					}
					QM[m + QM_FLAGS] = (partial ? QFLAG_PARTIAL : 0) | (planar ? QFLAG_PLANAR : 0) | (boundary ? QFLAG_BOUNDARY : 0);
				}
			}
		} else if (rec === 3) {
			var mid = dv.getUint16(p, true); p += 2;
			var kind = dv.getUint8(p); p += 1;
			modelKind[mid] = kind;
			if (kind === 0) {
				modelData[mid] = dv.getUint16(p, true); p += 2;
			} else if (kind === 1) {
				var cn = dv.getUint16(p, true); p += 2;
				var total = dv.getInt32(p, true); p += 4;
				var weights = new Int32Array(cn), children = new Int32Array(cn);
				for (var c = 0; c < cn; c++) { weights[c] = dv.getInt32(p, true); children[c] = dv.getUint16(p + 4, true); p += 6; }
				modelData[mid] = { total: total, weights: weights, children: children };
			} else {
				var mn = dv.getUint16(p, true); p += 2;
				var kids = new Int32Array(mn);
				for (var c2 = 0; c2 < mn; c2++) { kids[c2] = dv.getUint16(p, true); p += 2; }
				modelData[mid] = kids;
			}
		} else if (rec === 4) {
			var fid = dv.getUint16(p, true); p += 2;
			var fm = { layer: dv.getUint8(p), overlay: dv.getUint8(p + 1) !== 0, tint: dv.getUint8(p + 2) !== 0, sprites: [] };
			p += 3;
			for (var s = 0; s < 3; s++) {
				fm.sprites.push({ u0: dv.getFloat32(p, true), u1: dv.getFloat32(p + 4, true), v0: dv.getFloat32(p + 8, true),
					v1: dv.getFloat32(p + 12, true), id: dv.getUint16(p + 16, true) });
				p += 18;
			}
			fluidModels[fid] = fm;
		} else if (rec === 5) {
			var id = dv.getUint16(p, true); p += 2;
			sFlags[id] = dv.getInt32(p, true); sNeed[id] = dv.getInt32(p + 4, true); p += 8;
			sEmission[id] = dv.getUint8(p); p += 1;
			sShade[id] = dv.getFloat32(p, true); p += 4;
			sSkipRule[id] = dv.getUint8(p); sSkipKey[id] = dv.getUint16(p + 1, true); sBlock[id] = dv.getUint16(p + 3, true); p += 5;
			for (var d = 0; d < 6; d++) { sOcc[id * 6 + d] = dv.getUint16(p, true); p += 2; }
			sTints[id] = dv.getUint8(p); sModel[id] = dv.getUint16(p + 1, true); p += 3;
			sFluidGroup[id] = dv.getUint8(p); sFluidModel[id] = dv.getUint16(p + 1, true); sAmount[id] = dv.getUint8(p + 3); p += 4;
			sOwnHeight[id] = dv.getFloat32(p, true); p += 4;
			sSturdy[id] = dv.getUint8(p); sExtra[id] = dv.getUint8(p + 1); p += 2;
			var len = dv.getUint16(p, true); p += 2;
			var name = "";
			for (var ch = 0; ch < len; ch++) { name += String.fromCharCode(dv.getUint8(p + ch)); }
			p += len;
			sNames[id] = name;
			sKnown[id] = 1;
			named.push([id, name, sNeed[id]]);
		} else {
			throw new Error("mesh kernel: unknown definition record " + rec + " at " + (p - 1));
		}
	}
	if (named.length) { postMessage({ names: named }); }
}

var m64lo = 0, m64hi = 0;
function mul64(alo, ahi, blo, bhi) {
	var a48 = ahi >>> 16, a32 = ahi & 65535, a16 = alo >>> 16, a00 = alo & 65535;
	var b48 = bhi >>> 16, b32 = bhi & 65535, b16 = blo >>> 16, b00 = blo & 65535;
	var c48 = 0, c32 = 0, c16 = 0, c00 = 0;
	c00 += a00 * b00; c16 += c00 >>> 16; c00 &= 65535;
	c16 += a16 * b00; c32 += c16 >>> 16; c16 &= 65535;
	c16 += a00 * b16; c32 += c16 >>> 16; c16 &= 65535;
	c32 += a32 * b00; c48 += c32 >>> 16; c32 &= 65535;
	c32 += a16 * b16; c48 += c32 >>> 16; c32 &= 65535;
	c32 += a00 * b32; c48 += c32 >>> 16; c32 &= 65535;
	c48 += a48 * b00 + a32 * b16 + a16 * b32 + a00 * b48; c48 &= 65535;
	m64lo = (c16 << 16 | c00) | 0;
	m64hi = (c48 << 16 | c32) | 0;
}

var seedLo = 0, seedHi = 0;
function mthSeed(x, y, z) {
	var a = Math.imul(x, 3129871);
	mul64(z | 0, z >> 31, 116129781, 0);
	var lo = a ^ m64lo ^ y, hi = (a >> 31) ^ m64hi ^ (y >> 31);
	mul64(lo, hi, lo, hi);
	mul64(m64lo, m64hi, 42317861, 0);
	var p1lo = m64lo, p1hi = m64hi;
	mul64(lo, hi, 11, 0);
	var sumLo = (p1lo >>> 0) + (m64lo >>> 0);
	var sumHi = (p1hi + m64hi + (sumLo > 4294967295 ? 1 : 0)) | 0;
	sumLo = sumLo | 0;
	seedLo = (sumLo >>> 16 | sumHi << 16) | 0;
	seedHi = sumHi >> 16;
}

var rngHi = 0, rngLo = 0;
function rngSetSeed(lo, hi) {
	rngLo = (lo & 16777215) ^ 15525485;
	rngHi = (((lo >>> 24) | (hi << 8)) & 16777215) ^ 1502;
}
function rngNext(bits) {
	var lo = rngLo * 15525485 + 11;
	var carry = Math.floor(lo / 16777216);
	lo = lo - carry * 16777216;
	var hi = (rngHi * 15525485 + rngLo * 1502 + carry) % 16777216;
	rngLo = lo; rngHi = hi;
	return bits === 32 ? (hi * 256 + (lo >>> 16)) | 0 : (hi * 128 + (lo >>> 17)) | 0;
}
function rngNextInt(bound) {
	if ((bound & (bound - 1)) === 0) {
		return rngNext(31) >>> (31 - (31 - Math.clz32(bound)));
	}
	var sample, modulo;
	do {
		sample = rngNext(31);
		modulo = sample % bound;
	} while (((sample - modulo + (bound - 1)) | 0) < 0);
	return modulo;
}

var H = 18, HH = 324, PAD = 1;
var states = null, light = null, exI = null, exF = null;
var cellExtra = new Int32Array(4096);
var ao = false;
var cardinal = new Float32Array(6);
var grayByDir = new Int32Array(6);
var OUT_F = [null, null, null], OUT_I = [null, null, null], OUT_N = new Int32Array(3);
var sprites = new Set();
var declined = "";
var parts = new Int32Array(64), partN = 0;
var qColor = new Int32Array(4), qLight = new Int32Array(4);
var faceShape = new Float32Array(12);

function outEnsure(layer, ints) {
	var need = OUT_N[layer] + ints;
	if (OUT_I[layer] === null || need > OUT_I[layer].length) {
		var cap = Math.max(need, OUT_I[layer] === null ? 28 * 2048 : OUT_I[layer].length * 2);
		var buf = new ArrayBuffer(cap * 4), ni = new Int32Array(buf);
		if (OUT_I[layer] !== null) { ni.set(OUT_I[layer].subarray(0, OUT_N[layer])); }
		OUT_I[layer] = ni; OUT_F[layer] = new Float32Array(buf);
	}
}

function gray(brightness) {
	var c = Math.floor(fr(brightness * 255)) & 255;
	return (255 << 24 | c << 16 | c << 8 | c) | 0;
}

function multiplyColor(lhs, rhs) {
	if (lhs === -1) { return rhs; }
	if (rhs === -1) { return lhs; }
	return ((((lhs >>> 24) * (rhs >>> 24) / 255) & 255) << 24
		| ((((lhs >> 16) & 255) * ((rhs >> 16) & 255) / 255) & 255) << 16
		| ((((lhs >> 8) & 255) * ((rhs >> 8) & 255) / 255) & 255) << 8
		| (((lhs & 255) * (rhs & 255) / 255) & 255)) | 0;
}

function clamp255(v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
function scaleRGB3(color, sr, sg, sb) {
	var r = clamp255(Math.trunc(fr(((color >> 16) & 255) * sr)));
	var g = clamp255(Math.trunc(fr(((color >> 8) & 255) * sg)));
	var b = clamp255(Math.trunc(fr((color & 255) * sb)));
	return (color & -16777216 | r << 16 | g << 8 | b) | 0;
}
function scaleRGB(color, scale) { return scaleRGB3(color, scale, scale, scale); }

function lightAt(cell) {
	var l = light[cell];
	return (l & 15) << 4 | (l >> 4) << 20;
}

function lightCoords(sid, cell) {
	if (sFlags[sid] & F_EMISSIVE) { return FULL_BRIGHT; }
	var packed = lightAt(cell), e = sEmission[sid];
	return ((packed >> 4) & 15) < e ? (packed & 16711680 | e << 4) : packed;
}

function withEmission(lc, emission) {
	if (emission === 0) { return lc; }
	var sky = Math.max((lc >> 20) & 15, emission), block = Math.max((lc >> 4) & 15, emission);
	return block << 4 | sky << 20;
}

function shouldRenderFace(sid, nsid, dir) {
	var occ = sOcc[nsid * 6 + OPPOSITE[dir]];
	if (occ === 1) { return false; }
	var rule = sSkipRule[sid];
	if (rule !== 0) {
		if (rule === 1) { if (sBlock[nsid] === sSkipKey[sid]) { return false; } }
		else if (rule === 2) { if (sFlags[nsid] & F_LEAVES) { return false; } }
		else if (rule === 3) { if (sFluidGroup[nsid] !== 0 && sFluidGroup[nsid] === sFluidGroup[sid]) { return false; } }
		else if (rule === 4) { if (dir < 2 && sBlock[nsid] === sSkipKey[sid]) { return false; } }
		else { declined = "skip rule " + rule; return false; }
	}
	if (occ === 0) { return true; }
	var sh = sOcc[sid * 6 + dir];
	if (sh === 0) { return true; }
	var a = sh === 1 ? FULL_ROWS : shapeRows[sh], b = shapeRows[occ];
	if (!a || !b) { declined = "shape " + sh + "/" + occ; return true; }
	for (var r = 0; r < 16; r++) {
		if (a[r] & ~b[r]) { return true; }
	}
	return false;
}

function collectParts(model) {
	var kind = modelKind[model];
	if (kind === 0) {
		if (partN === parts.length) { var np = new Int32Array(partN * 2); np.set(parts); parts = np; }
		parts[partN++] = modelData[model];
	} else if (kind === 1) {
		var w = modelData[model], sel = rngNextInt(w.total), weights = w.weights, i = 0;
		for (; i < weights.length; i++) {
			sel -= weights[i];
			if (sel < 0) { break; }
		}
		if (i === weights.length) { declined = "weighted selection"; return; }
		collectParts(w.children[i]);
	} else if (kind === 2) {
		var kids = modelData[model];
		var upper = rngNext(32), lower = rngNext(32);
		var lo = lower, hi = (upper + (lower < 0 ? -1 : 0)) | 0;
		for (var k = 0; k < kids.length; k++) {
			rngSetSeed(lo, hi);
			collectParts(kids[k]);
		}
	} else {
		declined = "model " + model;
	}
}

function putQuad(q, fx, fy, fz, forceOpaque, tintBase) {
	var m = q * 8, f = q * 20;
	var tintIndex = QM[m + QM_TINT];
	var c0 = qColor[0], c1 = qColor[1], c2 = qColor[2], c3 = qColor[3];
	if (tintIndex !== -1) {
		var tint = -1;
		if (tintIndex < tintCount) {
			if (tintBase < 0) { declined = "tint needed but not sent"; return; }
			tint = exI[tintBase + 1 + tintOffsetWords + tintIndex];
		}
		c0 = multiplyColor(c0, tint); c1 = multiplyColor(c1, tint); c2 = multiplyColor(c2, tint); c3 = multiplyColor(c3, tint);
	}
	var layer = forceOpaque ? 0 : QM[m + QM_LAYER];
	var sprite = QM[m + QM_SPRITE];
	if (sprite !== 0) { sprites.add(sprite); }
	var emission = QM[m + QM_EMISSION];
	outEnsure(layer, 28);
	var F = OUT_F[layer], I = OUT_I[layer], o = OUT_N[layer];
	for (var v = 0; v < 4; v++) {
		var color = v === 0 ? c0 : (v === 1 ? c1 : (v === 2 ? c2 : c3));
		F[o] = QF[f + v * 3] + fx;
		F[o + 1] = QF[f + v * 3 + 1] + fy;
		F[o + 2] = QF[f + v * 3 + 2] + fz;
		I[o + 3] = color & -16711936 | (color & 16711680) >> 16 | (color & 255) << 16;
		F[o + 4] = QF[f + 12 + v * 2];
		F[o + 5] = QF[f + 13 + v * 2];
		I[o + 6] = withEmission(qLight[v], emission);
		o += 7;
	}
	OUT_N[layer] = o;
}
var tintOffsetWords = 0;
var tintCount = 0;

var AO_CORNERS = [[4, 5, 2, 3], [5, 4, 2, 3], [1, 0, 5, 4], [4, 5, 0, 1], [1, 0, 2, 3], [0, 1, 2, 3]];
var D = 0, U = 1, N = 2, S = 3, W = 4, E = 5, FD = 6, FU = 7, FN = 8, FS = 9, FW = 10, FE = 11;
var AO_WEIGHTS = [
	[[FW, S, FW, FS, W, FS, W, S], [FW, N, FW, FN, W, FN, W, N], [FE, N, FE, FN, E, FN, E, N], [FE, S, FE, FS, E, FS, E, S]],
	[[E, S, E, FS, FE, FS, FE, S], [E, N, E, FN, FE, FN, FE, N], [W, N, W, FN, FW, FN, FW, N], [W, S, W, FS, FW, FS, FW, S]],
	[[U, FW, U, W, FU, W, FU, FW], [U, FE, U, E, FU, E, FU, FE], [D, FE, D, E, FD, E, FD, FE], [D, FW, D, W, FD, W, FD, FW]],
	[[U, FW, FU, FW, FU, W, U, W], [D, FW, FD, FW, FD, W, D, W], [D, FE, FD, FE, FD, E, D, E], [U, FE, FU, FE, FU, E, U, E]],
	[[U, S, U, FS, FU, FS, FU, S], [U, N, U, FN, FU, FN, FU, N], [D, N, D, FN, FD, FN, FD, N], [D, S, D, FS, FD, FS, FD, S]],
	[[FD, S, FD, FS, D, FS, D, S], [FD, N, FD, FN, D, FN, D, N], [FU, N, FU, FN, U, FN, U, N], [FU, S, FU, FS, U, FS, U, S]]
];
var AO_REMAP = [[0, 1, 2, 3], [2, 3, 0, 1], [3, 0, 1, 2], [0, 1, 2, 3], [3, 0, 1, 2], [1, 2, 3, 0]];

function smoothBlend(n1, n2, n3, center) {
	if (((center >> 20) & 15) > 2 || ((center >> 4) & 15) > 2) {
		if (n1 === 0) { n1 = center; } else if (((n1 >> 20) & 15) === 0) { n1 |= center & 16711680; }
		if (n2 === 0) { n2 = center; } else if (((n2 >> 20) & 15) === 0) { n2 |= center & 16711680; }
		if (n3 === 0) { n3 = center; } else if (((n3 >> 20) & 15) === 0) { n3 |= center & 16711680; }
	}
	return (n1 + n2 + n3 + center) >> 2 & 16711935;
}

function smoothWeightedBlend(c1, c2, c3, c4, w1, w2, w3, w4) {
	var sky = Math.trunc(fr(fr(fr(fr(((c1 >> 16) & 255) * w1) + fr(((c2 >> 16) & 255) * w2)) + fr(((c3 >> 16) & 255) * w3)) + fr(((c4 >> 16) & 255) * w4)));
	var block = Math.trunc(fr(fr(fr(fr((c1 & 255) * w1) + fr((c2 & 255) * w2)) + fr((c3 & 255) * w3)) + fr((c4 & 255) * w4)));
	return (block & 255) | (sky & 255) << 16;
}

function weighted(t1, t2, t3, t4, w1, w2, w3, w4) {
	var v = fr(fr(fr(fr(t1 * w1) + fr(t2 * w2)) + fr(t3 * w3)) + fr(t4 * w4));
	return v < 0 ? 0 : (v > 1 ? 1 : v);
}

function prepareQuadAO(sid, cell, q) {
	var m = q * 8, bb = q * 6;
	var dir = QM[m + QM_DIR], flags = QM[m + QM_FLAGS];
	var faceCubic = (flags & QFLAG_PLANAR) !== 0 && ((flags & QFLAG_BOUNDARY) !== 0 || (sFlags[sid] & F_COLLISION_FULL) !== 0);
	var dstep = STEP_X[dir] + STEP_Y[dir] * HH + STEP_Z[dir] * H;
	var base = faceCubic ? cell + dstep : cell;
	var corners = AO_CORNERS[dir];
	var o0 = STEP_X[corners[0]] + STEP_Y[corners[0]] * HH + STEP_Z[corners[0]] * H;
	var o1 = STEP_X[corners[1]] + STEP_Y[corners[1]] * HH + STEP_Z[corners[1]] * H;
	var o2 = STEP_X[corners[2]] + STEP_Y[corners[2]] * HH + STEP_Z[corners[2]] * H;
	var o3 = STEP_X[corners[3]] + STEP_Y[corners[3]] * HH + STEP_Z[corners[3]] * H;
	var s0 = states[base + o0], s1 = states[base + o1], s2 = states[base + o2], s3 = states[base + o3];
	var light0 = lightCoords(s0, base + o0), shade0 = sShade[s0];
	var light1 = lightCoords(s1, base + o1), shade1 = sShade[s1];
	var light2 = lightCoords(s2, base + o2), shade2 = sShade[s2];
	var light3 = lightCoords(s3, base + o3), shade3 = sShade[s3];
	var perm0 = (sFlags[states[base + o0 + dstep]] & F_LIGHT_PERMEABLE) !== 0;
	var perm1 = (sFlags[states[base + o1 + dstep]] & F_LIGHT_PERMEABLE) !== 0;
	var perm2 = (sFlags[states[base + o2 + dstep]] & F_LIGHT_PERMEABLE) !== 0;
	var perm3 = (sFlags[states[base + o3 + dstep]] & F_LIGHT_PERMEABLE) !== 0;
	var shade02, light02, shade03, light03, shade12, light12, shade13, light13, c, cs;
	if (!perm2 && !perm0) { shade02 = shade0; light02 = light0; } else { c = base + o0 + o2; cs = states[c]; shade02 = sShade[cs]; light02 = lightCoords(cs, c); }
	if (!perm3 && !perm0) { shade03 = shade0; light03 = light0; } else { c = base + o0 + o3; cs = states[c]; shade03 = sShade[cs]; light03 = lightCoords(cs, c); }
	if (!perm2 && !perm1) { shade12 = shade0; light12 = light0; } else { c = base + o1 + o2; cs = states[c]; shade12 = sShade[cs]; light12 = lightCoords(cs, c); }
	if (!perm3 && !perm1) { shade13 = shade0; light13 = light0; } else { c = base + o1 + o3; cs = states[c]; shade13 = sShade[cs]; light13 = lightCoords(cs, c); }
	var lightCenter = lightCoords(sid, cell);
	var next = states[cell + dstep];
	if (faceCubic || (sFlags[next] & F_SOLID_RENDER) === 0) { lightCenter = lightCoords(next, cell + dstep); }
	var shadeCenter = faceCubic ? sShade[states[base]] : sShade[states[cell]];
	var remap = AO_REMAP[dir];
	var t1 = fr(fr(fr(fr(shade3 + shade0) + shade03) + shadeCenter) * 0.25);
	var t2 = fr(fr(fr(fr(shade2 + shade0) + shade02) + shadeCenter) * 0.25);
	var t3 = fr(fr(fr(fr(shade2 + shade1) + shade12) + shadeCenter) * 0.25);
	var t4 = fr(fr(fr(fr(shade3 + shade1) + shade13) + shadeCenter) * 0.25);
	var tc1 = smoothBlend(light3, light0, light03, lightCenter);
	var tc2 = smoothBlend(light2, light0, light02, lightCenter);
	var tc3 = smoothBlend(light2, light1, light12, lightCenter);
	var tc4 = smoothBlend(light3, light1, light13, lightCenter);
	if (flags & QFLAG_PARTIAL) {
		var fs = faceShape;
		fs[W] = QB[bb]; fs[E] = QB[bb + 3]; fs[D] = QB[bb + 1]; fs[U] = QB[bb + 4]; fs[N] = QB[bb + 2]; fs[S] = QB[bb + 5];
		fs[FW] = 1 - fs[W]; fs[FE] = 1 - fs[E]; fs[FD] = 1 - fs[D]; fs[FU] = 1 - fs[U]; fs[FN] = 1 - fs[N]; fs[FS] = 1 - fs[S];
		var ws = AO_WEIGHTS[dir];
		for (var v = 0; v < 4; v++) {
			var w = ws[v];
			var w01 = fr(fs[w[0]] * fs[w[1]]), w23 = fr(fs[w[2]] * fs[w[3]]), w45 = fr(fs[w[4]] * fs[w[5]]), w67 = fr(fs[w[6]] * fs[w[7]]);
			qColor[remap[v]] = gray(weighted(t1, t2, t3, t4, w01, w23, w45, w67));
			qLight[remap[v]] = smoothWeightedBlend(tc1, tc2, tc3, tc4, w01, w23, w45, w67);
		}
	} else {
		qLight[remap[0]] = tc1; qLight[remap[1]] = tc2; qLight[remap[2]] = tc3; qLight[remap[3]] = tc4;
		qColor[remap[0]] = gray(t1); qColor[remap[1]] = gray(t2); qColor[remap[2]] = gray(t3); qColor[remap[3]] = gray(t4);
	}
	var shadeDir = QM[m + QM_SHADE];
	var scale = cardinal[shadeDir >= 0 ? shadeDir : dir];
	qColor[0] = scaleRGB(qColor[0], scale); qColor[1] = scaleRGB(qColor[1], scale);
	qColor[2] = scaleRGB(qColor[2], scale); qColor[3] = scaleRGB(qColor[3], scale);
}

function tesselateBlock(sid, cell, x, y, z, ox, oy, oz) {
	var fl = sFlags[sid], model = sModel[sid];
	if (model === 65535) { declined = "no model for " + sNames[sid]; return; }
	var ex = -1;
	if (sExtra[sid] !== 0 || sTints[sid] !== 0 || (fl & F_FLUID_TINT)) {
		ex = cellExtra[x | z << 4 | y << 8];
		if (ex < 0 && sExtra[sid] !== 0) { declined = "missing cell extras for " + sNames[sid]; return; }
	}
	partN = 0;
	if (modelKind[model] === 0) {
		parts[partN++] = modelData[model];
	} else {
		if (fl & F_CUSTOM_SEED) {
			var so = ex + 1 + ((exI[ex] >> 16 & EX_OFFSET) ? 3 : 0);
			rngSetSeed(exI[so], exI[so + 1]);
		} else {
			mthSeed(ox + x, oy + y, oz + z);
			rngSetSeed(seedLo, seedHi);
		}
		collectParts(model);
		if (declined) { return; }
	}
	if (partN === 0) { return; }
	var fx = x, fy = y, fz = z, tintBase = -1;
	if (ex >= 0) {
		var exFlags = exI[ex] >> 16 & 255;
		tintOffsetWords = ((exFlags & EX_OFFSET) ? 3 : 0) + ((exFlags & EX_SEED) ? 2 : 0);
		tintBase = ex;
		if (exFlags & EX_OFFSET) {
			fx = fr(x + exF[ex + 1]); fy = fr(y + exF[ex + 2]); fz = fr(z + exF[ex + 3]);
		}
	}
	tintCount = sTints[sid];
	var forceOpaque = (fl & F_FORCE_OPAQUE) !== 0;
	var smooth = ao && sEmission[sid] === 0 && partAO[parts[0]] !== 0;
	var cacheValid = 0, renderCache = 0, pi, dir, q, n, start, i;
	for (pi = 0; pi < partN; pi++) {
		var part = parts[pi] * 7;
		for (dir = 0; dir < 6; dir++) {
			var mask = 1 << dir;
			var valid = (cacheValid & mask) !== 0, render = (renderCache & mask) !== 0;
			if (!valid || render) {
				n = partCount[part + dir];
				if (n !== 0) {
					var ncell = cell + STEP_X[dir] + STEP_Y[dir] * HH + STEP_Z[dir] * H;
					if (!valid) {
						render = shouldRenderFace(sid, states[ncell], dir);
						cacheValid |= mask;
						if (render) { renderCache |= mask; }
					}
					if (render) {
						start = partStart[part + dir];
						if (smooth) {
							for (i = 0; i < n; i++) {
								q = start + i;
								prepareQuadAO(sid, cell, q);
								putQuad(q, fx, fy, fz, forceOpaque, tintBase);
							}
						} else {
							var lc = lightCoords(sid, ncell);
							for (i = 0; i < n; i++) {
								q = start + i;
								qLight[0] = qLight[1] = qLight[2] = qLight[3] = lc;
								var sd = QM[q * 8 + QM_SHADE];
								qColor[0] = qColor[1] = qColor[2] = qColor[3] = grayByDir[sd >= 0 ? sd : QM[q * 8 + QM_DIR]];
								putQuad(q, fx, fy, fz, forceOpaque, tintBase);
							}
						}
					}
				}
			}
		}
		n = partCount[part + 6];
		if (n !== 0) {
			start = partStart[part + 6];
			for (i = 0; i < n; i++) {
				q = start + i;
				if (smooth) {
					prepareQuadAO(sid, cell, q);
				} else {
					var qf = QM[q * 8 + QM_FLAGS], qd = QM[q * 8 + QM_DIR];
					var cubic = (qf & QFLAG_PLANAR) !== 0 && ((qf & QFLAG_BOUNDARY) !== 0 || (fl & F_COLLISION_FULL) !== 0);
					var lcell = cubic ? cell + STEP_X[qd] + STEP_Y[qd] * HH + STEP_Z[qd] * H : cell;
					qLight[0] = qLight[1] = qLight[2] = qLight[3] = lightCoords(sid, lcell);
					var sd2 = QM[q * 8 + QM_SHADE];
					qColor[0] = qColor[1] = qColor[2] = qColor[3] = grayByDir[sd2 >= 0 ? sd2 : qd];
				}
				putQuad(q, fx, fy, fz, forceOpaque, tintBase);
			}
		}
	}
}

var SIN = null;
function mthSin(v) {
	return SIN[Math.trunc(v * 10430.378350470453) & 65535];
}
function mthCos(v) {
	return SIN[Math.trunc(v * 10430.378350470453 + 16384.0) & 65535];
}
var ASIN_TAB = null, COS_TAB = null, FRAC_BIAS = 0;
var f64 = new Float64Array(1), u32 = new Uint32Array(f64.buffer);
function initTrig() {
	SIN = new Float32Array(65536);
	for (var i = 0; i < 65536; i++) { SIN[i] = Math.sin(i / 10430.378350470453); }
	u32[0] = 0; u32[1] = 0x42B00000;
	FRAC_BIAS = f64[0];
	ASIN_TAB = new Float64Array(257); COS_TAB = new Float64Array(257);
	for (var ind = 0; ind < 257; ind++) {
		var asinv = Math.asin(ind / 256.0);
		COS_TAB[ind] = Math.cos(asinv);
		ASIN_TAB[ind] = asinv;
	}
}
function fastInvSqrt(x) {
	var xhalf = 0.5 * x;
	f64[0] = x;
	var lo = u32[0], hi = u32[1];
	var slo = (lo >>> 1 | hi << 31) >>> 0, shi = hi >> 1;
	var rlo = 0xC7B537AA - slo, borrow = rlo < 0 ? 1 : 0;
	u32[0] = rlo >>> 0; u32[1] = (0x5FE6EB50 - shi - borrow) >>> 0;
	x = f64[0];
	return x * (1.5 - xhalf * x * x);
}
function mthAtan2(y, x) {
	var d2 = x * x + y * y;
	if (d2 !== d2) { return NaN; }
	var negY = y < 0; if (negY) { y = -y; }
	var negX = x < 0; if (negX) { x = -x; }
	var steep = y > x;
	if (steep) { var t = x; x = y; y = t; }
	var rinv = fastInvSqrt(d2);
	x *= rinv; y *= rinv;
	var yp = FRAC_BIAS + y;
	f64[0] = yp;
	var index = u32[0] | 0;
	var phi = ASIN_TAB[index], cPhi = COS_TAB[index];
	var sPhi = yp - FRAC_BIAS;
	var sd = y * cPhi - x * sPhi;
	var d = (6.0 + sd * sd) * sd * 0.16666666666666666;
	var theta = phi + d;
	if (steep) { theta = Math.PI / 2 - theta; }
	if (negX) { theta = Math.PI - theta; }
	if (negY) { theta = -theta; }
	return theta;
}

var MAX_FLUID_HEIGHT = fr(0.8888889), OFFS = fr(0.001), TINY = fr(1.0E-5), F_DYNAMIC_SHAPE = 1 << 18;

function fluidOccluded(dir, height, nsid) {
	var occ = sOcc[nsid * 6 + OPPOSITE[dir]];
	if (occ === 0) { return false; }
	if (occ === 1) { return dir !== 1 || height === 1; }
	var rows = shapeRows[occ];
	if (!rows) { declined = "shape " + occ; return false; }
	var r;
	if (dir < 2) {
		if (dir === 1 && !(Math.abs(height - 1) <= 1.0E-7)) { return false; }
		for (r = 0; r < 16; r++) { if (rows[r] !== 65535) { return false; } }
		return true;
	}
	for (r = 0; r < 16; r++) {
		if (r / 16 < height && rows[r] !== 65535) { return false; }
	}
	return true;
}

function fluidHeight(group, sid, cell) {
	if (sFluidGroup[sid] === group) {
		return sFluidGroup[states[cell + HH]] === group ? 1 : sOwnHeight[sid];
	}
	return (sFlags[sid] & F_SOLID) === 0 ? 0 : -1;
}

var whSum = 0, whWeight = 0;
function addWeightedHeight(h) {
	if (h >= fr(0.8)) { whSum = fr(whSum + fr(h * 10)); whWeight = fr(whWeight + 10); }
	else if (h >= 0) { whSum = fr(whSum + h); whWeight = fr(whWeight + 1); }
}
function averageHeight(group, heightSelf, height2, height1, cornerCell) {
	if (!(height1 >= 1) && !(height2 >= 1)) {
		whSum = 0; whWeight = 0;
		if (height1 > 0 || height2 > 0) {
			var hc = fluidHeight(group, states[cornerCell], cornerCell);
			if (hc >= 1) { return 1; }
			addWeightedHeight(hc);
		}
		addWeightedHeight(heightSelf); addWeightedHeight(height1); addWeightedHeight(height2);
		return fr(whSum / whWeight);
	}
	return 1;
}

function fluidLight(cell) {
	var a = lightCoords(states[cell], cell), b = lightCoords(states[cell + HH], cell + HH);
	return Math.max((a >> 4) & 15, (b >> 4) & 15) << 4 | Math.max((a >> 20) & 15, (b >> 20) & 15) << 20;
}

var HORIZ = [2, 5, 3, 4];
var flowX = 0, flowZ = 0;
function fluidFlow(sid, cell) {
	var group = sFluidGroup[sid], own = sOwnHeight[sid], fx = 0, fz = 0, i, dir, ncell, ns;
	for (i = 0; i < 4; i++) {
		dir = HORIZ[i];
		ncell = cell + STEP_X[dir] + STEP_Z[dir] * H;
		ns = states[ncell];
		var ng = sFluidGroup[ns];
		if (ng === 0 || ng === group) {
			var nh = ng === 0 ? 0 : sOwnHeight[ns], distance = 0;
			if (nh === 0) {
				if ((sFlags[ns] & F_BLOCKS_FLUID_FLOW) === 0) {
					var bs = states[ncell - HH], bg = sFluidGroup[bs];
					if (bg === 0 || bg === group) {
						nh = bg === 0 ? 0 : sOwnHeight[bs];
						if (nh > 0) { distance = fr(own - fr(nh - MAX_FLUID_HEIGHT)); }
					}
				}
			} else if (nh > 0) {
				distance = fr(own - nh);
			}
			if (distance !== 0) {
				fx += STEP_X[dir] * distance;
				fz += STEP_Z[dir] * distance;
			}
		}
	}
	var vx = fx, vy = 0, vz = fz, len;
	if (sFlags[sid] & F_FLUID_FALLING) {
		for (i = 0; i < 4; i++) {
			dir = HORIZ[i];
			ncell = cell + STEP_X[dir] + STEP_Z[dir] * H;
			if (fluidSolidFace(group, ncell, dir) || fluidSolidFace(group, ncell + HH, dir)) {
				len = Math.sqrt(vx * vx + vy * vy + vz * vz);
				if (len < TINY) { vx = 0; vy = 0; vz = 0; } else { vx /= len; vy /= len; vz /= len; }
				vy += -6.0;
				break;
			}
		}
	}
	len = Math.sqrt(vx * vx + vy * vy + vz * vz);
	if (len < TINY) { flowX = 0; flowZ = 0; } else { flowX = vx / len; flowZ = vz / len; }
}
function fluidSolidFace(group, cell, dir) {
	var s = states[cell];
	if (sFluidGroup[s] === group) { return false; }
	if (sFlags[s] & F_ICE) { return false; }
	if (sFlags[s] & F_DYNAMIC_SHAPE) { declined = "falling fluid beside " + sNames[s]; return false; }
	return (sSturdy[s] >> dir & 1) !== 0;
}

function fluidVertex(layer, x, y, z, color, u, v, lc) {
	var F = OUT_F[layer], I = OUT_I[layer], o = OUT_N[layer];
	F[o] = x; F[o + 1] = y; F[o + 2] = z;
	I[o + 3] = color & -16711936 | (color & 16711680) >> 16 | (color & 255) << 16;
	F[o + 4] = u; F[o + 5] = v;
	I[o + 6] = lc;
	OUT_N[layer] = o + 7;
}
function fluidFace(layer, x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2, x3, y3, z3, u3, v3, color, lc, back) {
	outEnsure(layer, 56);
	fluidVertex(layer, x0, y0, z0, color, u0, v0, lc);
	fluidVertex(layer, x1, y1, z1, color, u1, v1, lc);
	fluidVertex(layer, x2, y2, z2, color, u2, v2, lc);
	fluidVertex(layer, x3, y3, z3, color, u3, v3, lc);
	if (back) {
		fluidVertex(layer, x0, y0, z0, color, u0, v0, lc);
		fluidVertex(layer, x3, y3, z3, color, u3, v3, lc);
		fluidVertex(layer, x2, y2, z2, color, u2, v2, lc);
		fluidVertex(layer, x1, y1, z1, color, u1, v1, lc);
	}
}
function spriteU(sp, t) { return fr(sp.u0 + fr(fr(sp.u1 - sp.u0) * t)); }
function spriteV(sp, t) { return fr(sp.v0 + fr(fr(sp.v1 - sp.v0) * t)); }

function tesselateFluid(sid, cell, xi, yi, zi) {
	var group = sFluidGroup[sid];
	var cD = cell - HH, cU = cell + HH, cN = cell - H, cS = cell + H, cW = cell - 1, cE = cell + 1;
	var sD = states[cD], sU = states[cU], sN = states[cN], sS = states[cS], sW = states[cW], sE = states[cE];
	var renderUp = sFluidGroup[sU] !== group;
	var renderDown = sFluidGroup[sD] !== group && !fluidOccluded(1, 1, sid) && !fluidOccluded(0, MAX_FLUID_HEIGHT, sD);
	var renderNorth = sFluidGroup[sN] !== group && !fluidOccluded(3, 1, sid) && !fluidOccluded(2, 1, sN);
	var renderSouth = sFluidGroup[sS] !== group && !fluidOccluded(2, 1, sid) && !fluidOccluded(3, 1, sS);
	var renderWest = sFluidGroup[sW] !== group && !fluidOccluded(5, 1, sid) && !fluidOccluded(4, 1, sW);
	var renderEast = sFluidGroup[sE] !== group && !fluidOccluded(4, 1, sid) && !fluidOccluded(5, 1, sE);
	if (!(renderUp || renderDown || renderEast || renderWest || renderNorth || renderSouth)) { return; }
	var model = fluidModels[sFluidModel[sid]];
	if (!model) { declined = "fluid model for " + sNames[sid]; return; }
	var still = model.sprites[0], flowing = model.sprites[1], overlay = model.sprites[2];
	if (still.id) { sprites.add(still.id); }
	if (flowing.id) { sprites.add(flowing.id); }
	if (model.overlay && overlay.id) { sprites.add(overlay.id); }
	var layer = model.layer, tint = -1;
	if (model.tint) {
		var ex = cellExtra[xi | zi << 4 | yi << 8];
		if (ex < 0) { declined = "missing fluid tint for " + sNames[sid]; return; }
		var exFlags = exI[ex] >> 16 & 255, colours = exI[ex] >>> 24 & 255;
		tint = exI[ex + 1 + ((exFlags & EX_OFFSET) ? 3 : 0) + ((exFlags & EX_SEED) ? 2 : 0) + colours - 1];
	}
	var heightSelf = fluidHeight(group, sid, cell), hNE, hNW, hSE, hSW;
	if (heightSelf >= 1) {
		hNE = 1; hNW = 1; hSE = 1; hSW = 1;
	} else {
		var hN = fluidHeight(group, sN, cN), hS = fluidHeight(group, sS, cS), hE = fluidHeight(group, sE, cE), hW = fluidHeight(group, sW, cW);
		hNE = averageHeight(group, heightSelf, hN, hE, cN + 1);
		hNW = averageHeight(group, heightSelf, hN, hW, cN - 1);
		hSE = averageHeight(group, heightSelf, hS, hE, cS + 1);
		hSW = averageHeight(group, heightSelf, hS, hW, cS - 1);
	}
	var x = xi, y = yi, z = zi;
	var bottomOffs = renderDown ? OFFS : 0;
	var x1 = fr(x + 1), z1 = fr(z + 1);
	if (renderUp && !fluidOccluded(1, Math.min(Math.min(hNW, hSW), Math.min(hSE, hNE)), sU)) {
		hNW = fr(hNW - OFFS); hSW = fr(hSW - OFFS); hSE = fr(hSE - OFFS); hNE = fr(hNE - OFFS);
		fluidFlow(sid, cell);
		var u00, u01, u10, u11, v00, v01, v10, v11;
		if (flowX === 0 && flowZ === 0) {
			u00 = still.u0; v00 = still.v0; u01 = u00; v01 = still.v1; u10 = still.u1; v10 = v01; u11 = u10; v11 = v00;
		} else {
			var angle = fr(fr(mthAtan2(flowZ, flowX)) - fr(Math.PI / 2));
			var s = fr(mthSin(angle) * 0.25), c = fr(mthCos(angle) * 0.25);
			u00 = spriteU(flowing, fr(0.5 + fr(fr(-c) - s))); v00 = spriteV(flowing, fr(0.5 + fr(fr(-c) + s)));
			u01 = spriteU(flowing, fr(0.5 + fr(fr(-c) + s))); v01 = spriteV(flowing, fr(0.5 + fr(c + s)));
			u10 = spriteU(flowing, fr(0.5 + fr(c + s))); v10 = spriteV(flowing, fr(0.5 + fr(c - s)));
			u11 = spriteU(flowing, fr(0.5 + fr(c - s))); v11 = spriteV(flowing, fr(0.5 + fr(fr(-c) - s)));
		}
		var topLight = fluidLight(cell), topColor = scaleRGB(tint, cardinal[1]);
		var back = false;
		for (var bz = -1; bz <= 1 && !back; bz++) {
			for (var bx = -1; bx <= 1; bx++) {
				var bs = states[cU + bx + bz * H];
				if (sFluidGroup[bs] !== group && (sFlags[bs] & F_SOLID_RENDER) === 0) { back = true; break; }
			}
		}
		fluidFace(layer, fr(x + 0), fr(y + hNW), fr(z + 0), u00, v00, fr(x + 0), fr(y + hSW), z1, u01, v01,
			x1, fr(y + hSE), z1, u10, v10, x1, fr(y + hNE), fr(z + 0), u11, v11, topColor, topLight, back);
	}
	if (renderDown) {
		var yb = fr(y + bottomOffs);
		fluidFace(layer, x, yb, z, still.u0, still.v0, x1, yb, z, still.u1, still.v0, x1, yb, z1, still.u1, still.v1,
			x, yb, z1, still.u0, still.v1, scaleRGB(tint, cardinal[0]), fluidLight(cD), false);
	}
	var sideLight = fluidLight(cell);
	for (var hi = 0; hi < 4; hi++) {
		var faceDir = HORIZ[hi], hh0, hh1, fx0, fz0, fx1, fz1, cond, faceState;
		if (faceDir === 2) {
			hh0 = hNW; hh1 = hNE; fx0 = x; fx1 = x1; fz0 = fr(z + OFFS); fz1 = fz0; cond = renderNorth; faceState = sN;
		} else if (faceDir === 3) {
			hh0 = hSE; hh1 = hSW; fx0 = x1; fx1 = x; fz0 = fr(z1 - OFFS); fz1 = fz0; cond = renderSouth; faceState = sS;
		} else if (faceDir === 4) {
			hh0 = hSW; hh1 = hNW; fx0 = fr(x + OFFS); fx1 = fx0; fz0 = z1; fz1 = z; cond = renderWest; faceState = sW;
		} else {
			hh0 = hNE; hh1 = hSE; fx0 = fr(x1 - OFFS); fx1 = fx0; fz0 = z; fz1 = z1; cond = renderEast; faceState = sE;
		}
		if (cond && !fluidOccluded(faceDir, Math.max(hh0, hh1), faceState)) {
			var sprite = flowing, isOverlay = false;
			if (model.overlay && (sFlags[faceState] & F_OVERLAY_NEIGHBOUR)) { sprite = overlay; isOverlay = true; }
			var su0 = spriteU(sprite, 0), su1 = spriteU(sprite, 0.5);
			var sv01 = spriteV(sprite, fr(fr(1 - hh0) * 0.5)), sv02 = spriteV(sprite, fr(fr(1 - hh1) * 0.5)), sv1 = spriteV(sprite, 0.5);
			var shadeSide = faceDir < 4 ? cardinal[2] : cardinal[4];
			var faceColor = scaleRGB(tint, fr(cardinal[1] * shadeSide));
			var yb2 = fr(y + bottomOffs);
			fluidFace(layer, fx0, fr(y + hh0), fz0, su0, sv01, fx1, fr(y + hh1), fz1, su1, sv02, fx1, yb2, fz1, su1, sv1,
				fx0, yb2, fz0, su0, sv1, faceColor, sideLight, !isOverlay);
		}
	}
}

var visBits = new Uint8Array(4096), visQueue = new Int32Array(4096), visEdges = null;
function visResolve(opaque, out) {
	if (opaque < 256) { out[0] = -1; out[1] = 15; return; }
	out[0] = 0; out[1] = 0;
	if (opaque === 4096) { return; }
	if (visEdges === null) {
		visEdges = new Int32Array(1352);
		var n = 0;
		for (var ex = 0; ex < 16; ex++) { for (var ey = 0; ey < 16; ey++) { for (var ez = 0; ez < 16; ez++) {
			if (ex === 0 || ex === 15 || ey === 0 || ey === 15 || ez === 0 || ez === 15) { visEdges[n++] = ex | ey << 8 | ez << 4; }
		} } }
	}
	for (var e = 0; e < 1352; e++) {
		var startIndex = visEdges[e];
		if (visBits[startIndex]) { continue; }
		var faces = 0, head = 0, tail = 0;
		visQueue[tail++] = startIndex; visBits[startIndex] = 1;
		while (head < tail) {
			var index = visQueue[head++];
			var x = index & 15, y = index >> 8 & 15, z = index >> 4 & 15;
			if (x === 0) { faces |= 16; } else if (x === 15) { faces |= 32; }
			if (y === 0) { faces |= 1; } else if (y === 15) { faces |= 2; }
			if (z === 0) { faces |= 4; } else if (z === 15) { faces |= 8; }
			if (y !== 0 && !visBits[index - 256]) { visBits[index - 256] = 1; visQueue[tail++] = index - 256; }
			if (y !== 15 && !visBits[index + 256]) { visBits[index + 256] = 1; visQueue[tail++] = index + 256; }
			if (z !== 0 && !visBits[index - 16]) { visBits[index - 16] = 1; visQueue[tail++] = index - 16; }
			if (z !== 15 && !visBits[index + 16]) { visBits[index + 16] = 1; visQueue[tail++] = index + 16; }
			if (x !== 0 && !visBits[index - 1]) { visBits[index - 1] = 1; visQueue[tail++] = index - 1; }
			if (x !== 15 && !visBits[index + 1]) { visBits[index + 1] = 1; visQueue[tail++] = index + 1; }
		}
		for (var d1 = 0; d1 < 6; d1++) {
			if (!(faces >> d1 & 1)) { continue; }
			for (var d2 = 0; d2 < 6; d2++) {
				if (faces >> d2 & 1) {
					var bit = d1 + d2 * 6;
					if (bit < 32) { out[0] |= 1 << bit; } else { out[1] |= 1 << (bit - 32); }
				}
			}
		}
	}
}

var visOut = new Int32Array(2);
function runJob(buf) {
	var t0 = performance.now();
	var head = new Int32Array(buf, 0, 16), headF = new Float32Array(buf, 0, 16);
	var jobId = head[1], jobEpoch = head[2], flags = head[3];
	var halo = head[14] || 18;
	H = halo; HH = H * H; PAD = (H - 16) >> 1;
	var cells = H * H * H;
	states = new Uint16Array(buf, 64, cells);
	light = new Uint8Array(buf, 64 + cells * 2, cells);
	var extraAt = 64 + cells * 3, extraBytes = head[13];
	exI = new Int32Array(buf, extraAt, extraBytes >> 2);
	exF = new Float32Array(buf, extraAt, extraBytes >> 2);
	ao = (flags & 1) !== 0;
	declined = "";
	if (jobEpoch !== epoch) { declined = "epoch " + jobEpoch + " != " + epoch; }
	if (ao && PAD < 2) { declined = "smooth lighting needs a halo of 2"; }
	var i, ox = head[10], oy = head[11], oz = head[12];
	for (i = 0; i < 6; i++) { cardinal[i] = headF[4 + i]; grayByDir[i] = gray(cardinal[i]); }
	cellExtra.fill(-1);
	for (i = 0; i < exI.length;) {
		var w = exI[i], exFlags = w >> 16 & 255, colours = w >>> 24 & 255;
		cellExtra[w & 65535] = i;
		i += 1 + ((exFlags & EX_OFFSET) ? 3 : 0) + ((exFlags & EX_SEED) ? 2 : 0) + colours;
	}
	OUT_N[0] = 0; OUT_N[1] = 0; OUT_N[2] = 0;
	sprites.clear();
	visBits.fill(0);
	var opaque = 0;
	if (!declined) {
		for (var z = 0; z < 16 && !declined; z++) {
			for (var y = 0; y < 16 && !declined; y++) {
				var cell = ((y + PAD) * H + (z + PAD)) * H + PAD;
				for (var x = 0; x < 16; x++, cell++) {
					var sid = states[cell];
					if (!sKnown[sid]) { declined = "undefined state " + sid; break; }
					var fl = sFlags[sid];
					if (fl & F_AIR) { continue; }
					if (fl & F_SOLID_RENDER) { visBits[x | y << 8 | z << 4] = 1; opaque++; }
					if (sFluidGroup[sid] !== 0) { tesselateFluid(sid, cell, x, y, z); }
					if (fl & F_MODEL) { tesselateBlock(sid, cell, x, y, z, ox, oy, oz); }
					if (declined) { break; }
				}
			}
		}
	}
	var out, oh;
	if (declined) {
		out = new ArrayBuffer(64);
		oh = new Int32Array(out);
		oh[0] = 2; oh[1] = jobId; oh[2] = jobEpoch; oh[3] = 1;
		postMessage({ declined: declined, job: jobId });
		postMessage(out, [out]);
		return;
	}
	visResolve(opaque, visOut);
	var animated = sprites.size, n0 = OUT_N[0], n1 = OUT_N[1], n2 = OUT_N[2];
	out = new ArrayBuffer(64 + animated * 4 + (n0 + n1 + n2) * 4);
	oh = new Int32Array(out);
	oh[0] = 2; oh[1] = jobId; oh[2] = jobEpoch; oh[3] = 0;
	oh[4] = visOut[0]; oh[5] = visOut[1];
	oh[6] = n0 * 4; oh[7] = n1 * 4; oh[8] = n2 * 4;
	oh[9] = animated;
	var p = 16;
	sprites.forEach(function (id) { oh[p++] = id; });
	if (n0) { oh.set(OUT_I[0].subarray(0, n0), p); p += n0; }
	if (n1) { oh.set(OUT_I[1].subarray(0, n1), p); p += n1; }
	if (n2) { oh.set(OUT_I[2].subarray(0, n2), p); }
	oh[11] = Math.round((performance.now() - t0) * 1000);
	postMessage(out, [out]);
}

if (typeof self !== "undefined" && typeof postMessage === "function" && typeof importScripts === "function") {
	initTrig();
	self.onmessage = function (e) {
		var buf = e.data;
		if (!(buf instanceof ArrayBuffer)) { return; }
		var kind = new Int32Array(buf, 0, 2);
		if (kind[0] === 2) {
			try {
				runJob(buf);
			} catch (err) {
				var out = new ArrayBuffer(64), oh = new Int32Array(out), job = new Int32Array(buf, 0, 3);
				oh[0] = 2; oh[1] = job[1]; oh[2] = job[2]; oh[3] = 2;
				postMessage({ declined: "kernel error: " + (err && err.stack || err), job: job[1] });
				postMessage(out, [out]);
			}
		} else if (kind[0] === 1) {
			if (kind[1] === epoch) { readDefs(buf); }
		} else if (kind[0] === 3) {
			epoch = kind[1];
			resetDefs();
		}
	};
	postMessage({ ready: true, caps: KERNEL_CAPS, capsHalo2: KERNEL_CAPS_HALO2 });
}

if (typeof module !== "undefined") {
	module.exports = {
		mthSeed: function (x, y, z) { mthSeed(x, y, z); return [seedLo, seedHi]; },
		rngSetSeed: rngSetSeed, rngNext: rngNext, rngNextInt: rngNextInt,
		initTrig: initTrig, mthAtan2: mthAtan2, mthSin: mthSin, mthCos: mthCos, fastInvSqrt: fastInvSqrt
	};
}
