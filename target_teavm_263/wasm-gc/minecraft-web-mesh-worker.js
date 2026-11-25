// minecraft-web by chiikabu (https://github.com/chiikabu)
// minecraft-web chunk section mesher (worker kernel).
//
// Plain JavaScript, no game classes. The page (Web263MeshOffload.java through minecraft-web-mesh.js)
// sends three kinds of little-endian blobs, first u32 = kind:
//   3 RESET  epoch, cutoutLeaves, stateCount            -> forget every definition
//   4 TAGS   on, stateCount, 0, then two i32 tables     -> shader pack block ids (see readTags)
//   1 DEFS   epoch, then records (shape / part / model / fluid model / state), see readDefs
//   2 JOB    header (64 B), state ids (u16 per halo cell), light (u8 per halo cell: sky << 4 | block),
//            then per-cell extras (tint colours, random offset, custom model seed)
// and gets back one ArrayBuffer per job: header (64 B), animated sprite ids, then the BLOCK-format
// vertices (7 x int32: x y z as float bits, ABGR, u v as float bits, packed light) for the SOLID,
// CUTOUT and TRANSLUCENT layers, in the order SectionCompiler would have produced them.
//
// This is a port of 26.3's SectionCompiler / ModelBlockRenderer / BlockModelLighter / FluidRenderer /
// VisGraph arithmetic. Parity target is byte-identical vertex streams (?meshverify checks it against
// the main-thread mesher), so every float operation that is a float in Java goes through Math.fround.
// A job the kernel cannot do exactly is answered with status 1 and meshed on the main thread.
//
// Provenance: written from this repository's own 26.3 classes only. No Sodium or Voxy code was read
// or adapted, and nothing here merges faces or otherwise changes the geometry vanilla produces.
"use strict";

var fr = Math.fround;

// Capability bits this kernel can mesh. Must match Web263MeshOffload.CAP_* / NEED_*.
var CAP_AO = 4, NEED_NONCUBE = 1 << 8, NEED_TINT = 1 << 9, NEED_SHAPES = 1 << 10, NEED_FLUID = 1 << 11,
	NEED_TRANSLUCENT = 1 << 12, NEED_OFFSET = 1 << 13, NEED_SEED = 1 << 14, NEED_CUTOUT = 1 << 15;
var KERNEL_CAPS = NEED_NONCUBE | NEED_TINT | NEED_SHAPES | NEED_TRANSLUCENT | NEED_OFFSET | NEED_SEED | NEED_CUTOUT | NEED_FLUID;
// Smooth lighting reads light-permeability two blocks out along the face normal: needs a halo of 2.
var KERNEL_CAPS_HALO2 = CAP_AO;

var F_AIR = 1, F_SOLID_RENDER = 2, F_MODEL = 4, F_EMISSIVE = 8, F_FORCE_OPAQUE = 16, F_LIGHT_PERMEABLE = 32,
	F_COLLISION_FULL = 64, F_HAS_OFFSET = 128, F_CUSTOM_SEED = 256, F_LEAVES = 512, F_SOLID = 1024,
	F_BLOCKS_FLUID_FLOW = 2048, F_ICE = 4096, F_OVERLAY_NEIGHBOUR = 8192, F_FLUID_SOURCE = 1 << 15,
	F_FLUID_FALLING = 1 << 16, F_FLUID_TINT = 1 << 17;
var EX_OFFSET = 1, EX_SEED = 2;
var FULL_BRIGHT = 15728880;

// Direction ordinals: DOWN UP NORTH SOUTH WEST EAST.
var STEP_X = [0, 0, 0, 0, -1, 1], STEP_Y = [-1, 1, 0, 0, 0, 0], STEP_Z = [0, 0, -1, 1, 0, 0];
var OPPOSITE = [1, 0, 3, 2, 5, 4];

// ---- definitions ---------------------------------------------------------------------------------

var epoch = 0;
var MAX_STATES = 65536;
var sKnown = new Uint8Array(MAX_STATES), sFlags = new Int32Array(MAX_STATES), sNeed = new Int32Array(MAX_STATES),
	sEmission = new Uint8Array(MAX_STATES), sShade = new Float32Array(MAX_STATES), sSkipRule = new Uint8Array(MAX_STATES),
	sSkipKey = new Uint16Array(MAX_STATES), sBlock = new Uint16Array(MAX_STATES), sOcc = new Uint16Array(MAX_STATES * 6),
	sTints = new Uint8Array(MAX_STATES), sModel = new Uint16Array(MAX_STATES), sFluidGroup = new Uint8Array(MAX_STATES),
	sFluidModel = new Uint16Array(MAX_STATES), sAmount = new Uint8Array(MAX_STATES), sOwnHeight = new Float32Array(MAX_STATES),
	sSturdy = new Uint8Array(MAX_STATES), sExtra = new Uint8Array(MAX_STATES);
var sNames = [];
var shapeRows = [null, null]; // id -> Uint16Array(16); 0 = empty, 1 = full block (handled by id)
var FULL_ROWS = new Uint16Array(16).fill(65535);
// Quads: 20 floats (12 position, 8 uv), 6 bounds floats, 8 ints of metadata.
var quadCount = 0, QF = new Float32Array(20 * 4096), QB = new Float32Array(6 * 4096), QM = new Int32Array(8 * 4096);
var QM_DIR = 0, QM_LAYER = 1, QM_TINT = 2, QM_SHADE = 3, QM_EMISSION = 4, QM_SPRITE = 5, QM_FLAGS = 6;
var QFLAG_PARTIAL = 1, QFLAG_PLANAR = 2, QFLAG_BOUNDARY = 4;
var partAO = [], partStart = [], partCount = []; // start/count: 7 buckets per part
var modelKind = [], modelData = []; // kind 0: part id; 1: {total, weights[], children[]}; 2: children[]
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
		if (rec === 1) { // shape
			var sid = dv.getUint16(p, true); p += 2;
			var rows = new Uint16Array(16);
			for (var r = 0; r < 16; r++) { rows[r] = dv.getUint16(p, true); p += 2; }
			shapeRows[sid] = rows;
		} else if (rec === 2) { // part
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
					// BlockModelLighter.prepareQuadShape, the part that only depends on the quad.
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
		} else if (rec === 3) { // model
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
		} else if (rec === 4) { // fluid model
			var fid = dv.getUint16(p, true); p += 2;
			var fm = { layer: dv.getUint8(p), overlay: dv.getUint8(p + 1) !== 0, tint: dv.getUint8(p + 2) !== 0, sprites: [] };
			p += 3;
			for (var s = 0; s < 3; s++) {
				fm.sprites.push({ u0: dv.getFloat32(p, true), u1: dv.getFloat32(p + 4, true), v0: dv.getFloat32(p + 8, true),
					v1: dv.getFloat32(p + 12, true), id: dv.getUint16(p + 16, true) });
				p += 18;
			}
			fluidModels[fid] = fm;
		} else if (rec === 5) { // state
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

// ---- 64-bit helpers (Mth.getSeed, SingleThreadedRandomSource) -------------------------------------

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

// Mth.getSeed(x, y, z) -> seedLo / seedHi.
var seedLo = 0, seedHi = 0;
function mthSeed(x, y, z) {
	var a = Math.imul(x, 3129871); // int multiply, then widened
	mul64(z | 0, z >> 31, 116129781, 0);
	var lo = a ^ m64lo ^ y, hi = (a >> 31) ^ m64hi ^ (y >> 31);
	mul64(lo, hi, lo, hi);
	mul64(m64lo, m64hi, 42317861, 0);
	var p1lo = m64lo, p1hi = m64hi;
	mul64(lo, hi, 11, 0);
	var sumLo = (p1lo >>> 0) + (m64lo >>> 0);
	var sumHi = (p1hi + m64hi + (sumLo > 4294967295 ? 1 : 0)) | 0;
	sumLo = sumLo | 0;
	seedLo = (sumLo >>> 16 | sumHi << 16) | 0; // seed >> 16
	seedHi = sumHi >> 16;
}

// java.util.Random's 48-bit LCG as two 24-bit halves.
var rngHi = 0, rngLo = 0;
function rngSetSeed(lo, hi) {
	rngLo = (lo & 16777215) ^ 15525485; // 0xECE66D
	rngHi = (((lo >>> 24) | (hi << 8)) & 16777215) ^ 1502; // 0x5DE
}
function rngNext(bits) {
	var lo = rngLo * 15525485 + 11;
	var carry = Math.floor(lo / 16777216);
	lo = lo - carry * 16777216;
	var hi = (rngHi * 15525485 + rngLo * 1502 + carry) % 16777216;
	rngLo = lo; rngHi = hi;
	// (int)(seed >> (48 - bits)), bits is 31 or 32 here
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

// ---- job state -----------------------------------------------------------------------------------

var H = 18, HH = 324, PAD = 1; // halo edge, edge squared, cells of padding around the 16^3 centre
var DIR_STEP = new Int32Array([-324, 324, -18, 18, -1, 1]), dirStepH = 18;
var states = null, light = null, exI = null, exF = null;
var cellExtra = new Int32Array(4096).fill(-1);
var extraUsed = new Int32Array(4096), extraUsedN = 0;
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

function lightAt(cell) { // LightCoordsUtil.pack(block, sky)
	var l = light[cell];
	return (l & 15) << 4 | (l >> 4) << 20;
}

// LightCoordsUtil.getLightCoords(level, state, pos) with the snapshot as the level.
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

// Block.shouldRenderFace(state, neighbourState, direction).
function shouldRenderFace(sid, nsid, dir) {
	var occ = sOcc[nsid * 6 + OPPOSITE[dir]];
	if (occ === 1) { return false; }
	var rule = sSkipRule[sid];
	if (rule !== 0) {
		if (rule === 1) { if (sBlock[nsid] === sSkipKey[sid]) { return false; } }
		else if (rule === 2) { if (sFlags[nsid] & F_LEAVES) { return false; } }
		else if (rule === 3) { if (sFluidGroup[nsid] !== 0 && sFluidGroup[nsid] === sFluidGroup[sid]) { return false; } }
		else if (rule === 4) { if (dir < 2 && sBlock[nsid] === sSkipKey[sid]) { return false; } }
		else if (rule === 5) {
			if (sBlock[nsid] === sBlock[sid] && (dir >= 2 || sSkipKey[nsid] === sSkipKey[sid])) { return false; }
		}
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
		var upper = rngNext(32), lower = rngNext(32); // RandomSource.nextLong
		var lo = lower, hi = (upper + (lower < 0 ? -1 : 0)) | 0;
		for (var k = 0; k < kids.length; k++) {
			rngSetSeed(lo, hi);
			collectParts(kids[k]);
		}
	} else {
		declined = "model " + model;
	}
}

// BlockQuadOutput: one baked quad into its layer, QuadInstance = qColor/qLight.
function putQuad(q, fx, fy, fz, forceOpaque, tintBase) {
	var m = q * 8, f = q * 20;
	var tintIndex = QM[m + QM_TINT];
	var c0 = qColor[0], c1 = qColor[1], c2 = qColor[2], c3 = qColor[3];
	var l0 = qLight[0], l1 = qLight[1], l2 = qLight[2], l3 = qLight[3];
	var flat = c0 === c1 && c0 === c2 && c0 === c3 && l0 === l1 && l0 === l2 && l0 === l3;
	if (tintIndex !== -1) {
		var tint = -1;
		if (tintIndex < tintCount) {
			if (tintBase < 0) { declined = "tint needed but not sent"; return; }
			tint = exI[tintBase + 1 + tintOffsetWords + tintIndex];
		}
		if (flat) {
			c0 = multiplyColor(c0, tint);
		} else {
			c0 = multiplyColor(c0, tint); c1 = multiplyColor(c1, tint); c2 = multiplyColor(c2, tint); c3 = multiplyColor(c3, tint);
		}
	}
	var layer = forceOpaque ? 0 : QM[m + QM_LAYER];
	var sprite = QM[m + QM_SPRITE];
	if (sprite !== 0) { sprites.add(sprite); }
	var emission = QM[m + QM_EMISSION];
	// shader packs: the block's id bits and the quad's face (direction ordinal + 1) in bits 8..10
	var tag = tagOn ? (curTag | (QM[m + QM_DIR] + 1) << 8) : 0;
	outEnsure(layer, 28);
	var F = OUT_F[layer], I = OUT_I[layer], o = OUT_N[layer];
	if (flat) {
		var abgr = c0 & -16711936 | (c0 & 16711680) >> 16 | (c0 & 255) << 16; // ARGB.toABGR
		var packedLight = withEmission(l0, emission) | tag;
		F[o] = QF[f] + fx; F[o + 1] = QF[f + 1] + fy; F[o + 2] = QF[f + 2] + fz;
		I[o + 3] = abgr; F[o + 4] = QF[f + 12]; F[o + 5] = QF[f + 13]; I[o + 6] = packedLight;
		F[o + 7] = QF[f + 3] + fx; F[o + 8] = QF[f + 4] + fy; F[o + 9] = QF[f + 5] + fz;
		I[o + 10] = abgr; F[o + 11] = QF[f + 14]; F[o + 12] = QF[f + 15]; I[o + 13] = packedLight;
		F[o + 14] = QF[f + 6] + fx; F[o + 15] = QF[f + 7] + fy; F[o + 16] = QF[f + 8] + fz;
		I[o + 17] = abgr; F[o + 18] = QF[f + 16]; F[o + 19] = QF[f + 17]; I[o + 20] = packedLight;
		F[o + 21] = QF[f + 9] + fx; F[o + 22] = QF[f + 10] + fy; F[o + 23] = QF[f + 11] + fz;
		I[o + 24] = abgr; F[o + 25] = QF[f + 18]; F[o + 26] = QF[f + 19]; I[o + 27] = packedLight;
		OUT_N[layer] = o + 28;
		return;
	}
	for (var v = 0; v < 4; v++) {
		var color = v === 0 ? c0 : (v === 1 ? c1 : (v === 2 ? c2 : c3));
		var lv = v === 0 ? l0 : (v === 1 ? l1 : (v === 2 ? l2 : l3));
		F[o] = QF[f + v * 3] + fx;
		F[o + 1] = QF[f + v * 3 + 1] + fy;
		F[o + 2] = QF[f + v * 3 + 2] + fz;
		I[o + 3] = color & -16711936 | (color & 16711680) >> 16 | (color & 255) << 16; // ARGB.toABGR
		F[o + 4] = QF[f + 12 + v * 2];
		F[o + 5] = QF[f + 13 + v * 2];
		I[o + 6] = withEmission(lv, emission) | tag;
		o += 7;
	}
	OUT_N[layer] = o;
}

// ---- shader pack tags ------------------------------------------------------------------------------
// While a shader pack's world program draws terrain, each vertex carries the block's id (from the
// pack's block.properties) and its face direction in the unused high bits of the packed light, the
// way the main-thread mesher writes them (ShaderBlockIds.java):
//   bits 8..10   face + 1 (1 down, 2 up, 3 north, 4 south, 5 west, 6 east)
//   bits 11..15  table index, low 5 bits;  bits 24..31  table index, high 8 bits
// A kind-4 blob brings the ready-made index bits per block state: one table for a state's own
// quads, one for its fluid (fluid quads carry the fluid's block id and their real face normal).
var tagOn = false, tagBlock = null, tagFluid = null, curTag = 0, curFluidTag = 0;
function readTags(buf) {
	var h = new Int32Array(buf, 0, 4), n = h[2];
	tagOn = h[1] !== 0 && n > 0 && buf.byteLength >= 16 + n * 8;
	if (!tagOn) { tagBlock = null; tagFluid = null; curTag = 0; curFluidTag = 0; return; }
	tagBlock = new Int32Array(buf.slice(16, 16 + n * 4));
	tagFluid = new Int32Array(buf.slice(16 + n * 4, 16 + n * 8));
}
var tintOffsetWords = 0; // words between an extra record's header and its colours
var tintCount = 0; // BlockColors.getTintSources(state).size() of the block being meshed

// ---- smooth lighting (BlockModelLighter.prepareQuadAmbientOcclusion) ----------------------------

// AdjacencyInfo.corners per face direction, as direction ordinals.
var AO_CORNERS = [[4, 5, 2, 3], [5, 4, 2, 3], [1, 0, 5, 4], [4, 5, 0, 1], [1, 0, 2, 3], [0, 1, 2, 3]];
// SizeInfo indices: DOWN UP NORTH SOUTH WEST EAST, then FLIP_ of the same six.
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
	var dstep = DIR_STEP[dir];
	var base = faceCubic ? cell + dstep : cell;
	var corners = AO_CORNERS[dir];
	var o0 = DIR_STEP[corners[0]];
	var o1 = DIR_STEP[corners[1]];
	var o2 = DIR_STEP[corners[2]];
	var o3 = DIR_STEP[corners[3]];
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

// ---- ModelBlockRenderer.tesselateBlock -----------------------------------------------------------

function tesselateBlock(sid, cell, x, y, z, ox, oy, oz) {
	curTag = tagOn && sid < tagBlock.length ? tagBlock[sid] : 0;
	var fl = sFlags[sid], model = sModel[sid];
	if (model === 65535) { declined = "no model for " + sNames[sid]; return; }
	var ex = -1;
	if (sExtra[sid] !== 0 || sTints[sid] !== 0 || (fl & F_FLUID_TINT)) {
		// The page leaves the record out for a tinted block that is boxed in on all six sides.
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
					var ncell = cell + DIR_STEP[dir];
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
					// prepareQuadFlat with CHECK_LIGHT: light at the neighbour when the face is on the block boundary
					var qf = QM[q * 8 + QM_FLAGS], qd = QM[q * 8 + QM_DIR];
					var cubic = (qf & QFLAG_PLANAR) !== 0 && ((qf & QFLAG_BOUNDARY) !== 0 || (fl & F_COLLISION_FULL) !== 0);
					var lcell = cubic ? cell + DIR_STEP[qd] : cell;
					qLight[0] = qLight[1] = qLight[2] = qLight[3] = lightCoords(sid, lcell);
					var sd2 = QM[q * 8 + QM_SHADE];
					qColor[0] = qColor[1] = qColor[2] = qColor[3] = grayByDir[sd2 >= 0 ? sd2 : qd];
				}
				putQuad(q, fx, fy, fz, forceOpaque, tintBase);
			}
		}
	}
}

// ---- fluids (FluidRenderer.tesselate) -----------------------------------------------------------

var SIN = null;
function mthSin(v) { // Mth.sin(double): table lookup, (long)(v * 10430.378350470453) & 65535
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
	u32[0] = 0; u32[1] = 0x42B00000; // Double.longBitsToDouble(4805340802404319232L)
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
	// i = 6910469410427058090L - (i >> 1), on the two 32-bit halves
	var lo = u32[0], hi = u32[1];
	var slo = (lo >>> 1 | hi << 31) >>> 0, shi = hi >> 1;
	// 6910469410427058090 = 0x5FE6EB50C7B537AA
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
	var index = u32[0] | 0; // (int)Double.doubleToRawLongBits(yp)
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

// FluidRenderer.isFaceOccludedByState: is the fluid face [0,1] x [0,height] covered by the neighbour's occlusion face?
function fluidOccluded(dir, height, nsid) {
	var occ = sOcc[nsid * 6 + OPPOSITE[dir]];
	if (occ === 0) { return false; }
	if (occ === 1) { return dir !== 1 || height === 1; }
	var rows = shapeRows[occ];
	if (!rows) { declined = "shape " + occ; return false; }
	var r;
	if (dir < 2) {
		// Shapes.blockOccludes needs the fluid box to reach the face: always for DOWN, only at height 1 for UP.
		if (dir === 1 && !(Math.abs(height - 1) <= 1.0E-7)) { return false; }
		for (r = 0; r < 16; r++) { if (rows[r] !== 65535) { return false; } }
		return true;
	}
	for (r = 0; r < 16; r++) {
		if (r / 16 < height && rows[r] !== 65535) { return false; }
	}
	return true;
}

function fluidHeight(group, sid, cell) { // FluidRenderer.getHeight
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

function fluidLight(cell) { // FluidRenderer.getLightCoords: max of this cell and the one above
	var a = lightCoords(states[cell], cell), b = lightCoords(states[cell + HH], cell + HH);
	return Math.max((a >> 4) & 15, (b >> 4) & 15) << 4 | Math.max((a >> 20) & 15, (b >> 20) & 15) << 20;
}

var HORIZ = [2, 5, 3, 4]; // Direction.Plane.HORIZONTAL: NORTH EAST SOUTH WEST
var flowX = 0, flowZ = 0;
function fluidFlow(sid, cell) { // FlowingFluid.getFlow, x and z of the normalized result
	var group = sFluidGroup[sid], own = sOwnHeight[sid], fx = 0, fz = 0, i, dir, ncell, ns;
	for (i = 0; i < 4; i++) {
		dir = HORIZ[i];
		ncell = cell + DIR_STEP[dir];
		ns = states[ncell];
		var ng = sFluidGroup[ns];
		if (ng === 0 || ng === group) { // affectsFlow
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
			ncell = cell + DIR_STEP[dir];
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
function fluidSolidFace(group, cell, dir) { // FlowingFluid.isSolidFace, dir is horizontal
	var s = states[cell];
	if (sFluidGroup[s] === group) { return false; }
	if (sFlags[s] & F_ICE) { return false; }
	if (sFlags[s] & F_DYNAMIC_SHAPE) { declined = "falling fluid beside " + sNames[s]; return false; }
	return (sSturdy[s] >> dir & 1) !== 0;
}

function fluidVertex(layer, x, y, z, abgr, u, v, lightTagged) {
	var F = OUT_F[layer], I = OUT_I[layer], o = OUT_N[layer];
	F[o] = x; F[o + 1] = y; F[o + 2] = z;
	I[o + 3] = abgr;
	F[o + 4] = u; F[o + 5] = v;
	I[o + 6] = lightTagged;
	OUT_N[layer] = o + 7;
}
function fluidFace(layer, x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2, x3, y3, z3, u3, v3, color, lc, back, faceDir) {
	outEnsure(layer, back ? 56 : 28);
	var abgr = color & -16711936 | (color & 16711680) >> 16 | (color & 255) << 16;
	var lt = tagOn ? (lc | curFluidTag | (faceDir + 1) << 8) : lc;
	fluidVertex(layer, x0, y0, z0, abgr, u0, v0, lt);
	fluidVertex(layer, x1, y1, z1, abgr, u1, v1, lt);
	fluidVertex(layer, x2, y2, z2, abgr, u2, v2, lt);
	fluidVertex(layer, x3, y3, z3, abgr, u3, v3, lt);
	if (back) {
		var blt = tagOn ? (lc | curFluidTag | (OPPOSITE[faceDir] + 1) << 8) : lc;
		fluidVertex(layer, x0, y0, z0, abgr, u0, v0, blt);
		fluidVertex(layer, x3, y3, z3, abgr, u3, v3, blt);
		fluidVertex(layer, x2, y2, z2, abgr, u2, v2, blt);
		fluidVertex(layer, x1, y1, z1, abgr, u1, v1, blt);
	}
}
function spriteU(sp, t) { return fr(sp.u0 + fr(fr(sp.u1 - sp.u0) * t)); }
function spriteV(sp, t) { return fr(sp.v0 + fr(fr(sp.v1 - sp.v0) * t)); }

function tesselateFluid(sid, cell, xi, yi, zi) {
	curFluidTag = tagOn && sid < tagFluid.length ? tagFluid[sid] : 0;
	var group = sFluidGroup[sid];
	var cD = cell - HH, cU = cell + HH, cN = cell - H, cS = cell + H, cW = cell - 1, cE = cell + 1;
	var sD = states[cD], sU = states[cU], sN = states[cN], sS = states[cS], sW = states[cW], sE = states[cE];
	// isFaceOccludedBySelf(state, direction) = isFaceOccludedByState(direction.getOpposite(), 1.0F, state)
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
		// FluidState.shouldRenderBackwardUpFace(level, pos.above())
		var back = false;
		for (var bz = -1; bz <= 1 && !back; bz++) {
			for (var bx = -1; bx <= 1; bx++) {
				var bs = states[cU + bx + bz * H];
				if (sFluidGroup[bs] !== group && (sFlags[bs] & F_SOLID_RENDER) === 0) { back = true; break; }
			}
		}
		fluidFace(layer, fr(x + 0), fr(y + hNW), fr(z + 0), u00, v00, fr(x + 0), fr(y + hSW), z1, u01, v01,
			x1, fr(y + hSE), z1, u10, v10, x1, fr(y + hNE), fr(z + 0), u11, v11, topColor, topLight, back, 1);
	}
	if (renderDown) {
		var yb = fr(y + bottomOffs);
		fluidFace(layer, x, yb, z, still.u0, still.v0, x1, yb, z, still.u1, still.v0, x1, yb, z1, still.u1, still.v1,
			x, yb, z1, still.u0, still.v1, scaleRGB(tint, cardinal[0]), fluidLight(cD), false, 0);
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
				fx0, yb2, fz0, su0, sv1, faceColor, sideLight, !isOverlay, faceDir);
		}
	}
}

// ---- VisGraph ------------------------------------------------------------------------------------

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

// ---- job -----------------------------------------------------------------------------------------

var visOut = new Int32Array(2);
function runJob(buf) {
	var t0 = performance.now();
	var head = new Int32Array(buf, 0, 16), headF = new Float32Array(buf, 0, 16);
	var jobId = head[1], jobEpoch = head[2], flags = head[3];
	var halo = head[14] || 18;
	H = halo; HH = H * H; PAD = (H - 16) >> 1;
	if (dirStepH !== H) {
		dirStepH = H;
		for (var ds = 0; ds < 6; ds++) {
			DIR_STEP[ds] = STEP_X[ds] + STEP_Y[ds] * HH + STEP_Z[ds] * H;
		}
	}
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
	if (extraUsedN > 0) {
		for (i = 0; i < extraUsedN; i++) { cellExtra[extraUsed[i]] = -1; }
		extraUsedN = 0;
	}
	for (i = 0; i < exI.length;) {
		var w = exI[i], exFlags = w >> 16 & 255, colours = w >>> 24 & 255;
		var cellIdx = w & 65535;
		if (cellIdx < 4096) {
			if (cellExtra[cellIdx] === -1) { extraUsed[extraUsedN++] = cellIdx; }
			cellExtra[cellIdx] = i;
		}
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

// ---- WASM-SIMD128 embedded chunk mesher kernel ---------------------------------------------------
// Compiled from scripts/wasm_simd/mesh_kernel.c via scripts/wasm_simd/embed_mesh_wasm.py.
// Zero network fetches, zero SharedArrayBuffer, 100% offline bundle safe, automatic JS fallback.

// BEGIN_MESH_WASM_B64
var MESH_WASM_B64 = "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IhogCEEqaioAADgCACAGQSxqIhsgCEEuaioAADgCACAGQTBqIAhBMmoqAAA4AgAgBkE0aiAIQTZqKgAAOAIAIAZBOGogCEE6aioAADgCACAGQTxqIAhBPmoqAAA4AgAgBkHAAGogCEHCAGoqAAA4AgAgBkHEAGogCEHGAGoqAAA4AgAgBkHIAGogCEHKAGoqAAA4AgAgBkHMAGogCEHOAGoqAAA4AgAgBSAIQdIAai0AACIcOgAAIAVBAWogCEHTAGotAAA6AAAgBUECaiAIQdQAai0AADoAACAFQQNqIAhB1QBqLQAAOgAAIAVBBGogCEHWAGotAAA6AAAgBUEGaiAIQdgAai8AADsBACAZKgIAIR0gFioCACEeIBMqAgAhHyAaKgIAISAgFyoCACEhIBQqAgAhIiARKgIAISMgBioCACEkIAdBFGogGyoCACIlIBgqAgAiJiAVKgIAIicgEioCACIoQwAAAMIgKEMAAADCXhsiKSAnICleGyIpICYgKV4bIikgJSApXhsiKjgCACAHQRBqICAgISAiICNDAAAAwiAjQwAAAMJeGyIpICIgKV4bIikgISApXhsiKSAgICleGyIpOAIAIAdBDGogHSAeIB8gJEMAAADCICRDAAAAwl4bIisgHyArXhsiKyAeICteGyIrIB0gK14bIis4AgAgB0EIaiAlICYgJyAoQwAAAEIgKEMAAABCXRsiKCAnIChdGyInICYgJ10bIiYgJSAmXRsiJTgCACAHQQRqICAgISAiICNDAAAAQiAjQwAAAEJdGyIjICIgI10bIiIgISAiXRsiISAgICFdGyIgOAIAIAcgHSAeIB8gJEMAAABCICRDAAAAQl0bIiEgHyAhXRsiHyAeIB9dGyIeIB0gHl0bIh04AgACQAJAIBxBAUsNACAdQxe30ThgICVDF7fROGByICtDcvl/P19yICpDcvl/P19yIQggICApWyERQQAhEgwBCwJAIBxBA0sNACAdQxe30ThgICBDF7fROGByICtDcvl/P19yIClDcvl/P19yIQggJSAqWyERQQIhEiAlISAgKiEpDAELICBDF7fROGAgJUMXt9E4YHIgKUNy+X8/X3IgKkNy+X8/X3IhCCAdICtbIRFBBCESIB0hICArISkLIAVBBWpBAkEAIBEbIAhyQQRBACAgQxe30ThdIClDcvl/P14g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gIAAGgsCQCABQQ9KDQBBAEEANgKggISAAEEAQQA6AJyAhIAAQQBBADYCpICEgABBAEEANgKogISAAEF/DwtBfyECAkAgACgAAEEERw0AAkACQCAAKAAERQ0AIAAoAAgiAkEBSA0AIAEgAkEDdEEQak8NAQtBAEEANgKggISAAEEAQQA6AJyAhIAAQQBBADYCpICEgABBAEEANgKogISAAEEADwsgAkECdCEDIABBEGohBCACQYCABCACQYCABEkbIQVBACEGAkACQCACQRBJDQAgACADakFwcUGQoPYGRg0AIABB/9+JeWpBEEkNAEGQoIYHIABrIgEgA2tBEEkNACABQRBJDQBBoKCGByECQaCg9gYhByAEIQEgBUH8/wdxIgYhCANAIAcgAf0AAAD9CwQAIAIgASADav0AAAD9CwQAIAFBEGohASAHQRBqIQcgAkEQaiECIAhBfGoiCA0ACyAFIAZGDQELIAZBAXIhAQJAIAVBAXFFDQAgBkECdCICIAQgAmooAAA2AqCg9gYgAiACIAQgA2pqKAAANgKgoIYHIAEhBgsgBSABRg0AIAAgA2ohAiAGQQJ0IQEgBSAGayEDQaCghgchB0GgoPYGIQgDQCAIIAFqIgYgACABaiIEQRBqKAAANgIAIAcgAWoiCSACIAFqIgpBEGooAAA2AgAgBkEEaiAEQRRqKAAANgIAIAlBBGogCkEUaigAADYCACAAQQhqIQAgAkEIaiECIAhBCGohCCAHQQhqIQcgA0F+aiIDDQALC0EAIQJBACAFNgKggISAAEEAQQE6AJyAhIAACyACC6VxFgh/AXsCfQJ/An4DfwF+BH0BfAJ/An0BfwF8G38JfQN8An8CfAR9AXwKfQF+AkBBAC0AjICEgAANABCAgICAABoLQXshAgJAAkACQCABQcAASA0AIAAoAAwhAyAAKAAIIQQgACgABCEFQQBC/////x83A9CAhIAAQQAgACgAOCICQRIgAhsiAjYCrICEgABBACACNgLMgISAAEEAIAIgAmwiBjYCsICEgABBAEEAIAJrNgLIgISAAEEAIAY2AsSAhIAAQQAgAkFwakEBdSIHNgK0gISAAEEAQQAgBms2AsCAhIAAAkACQCACQWdqQXlJDQAgAkEBcQ0AIAAoADQiCEEASA0AIAggBiACbCICQQNsQcAAaiIGaiABTA0B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AkACQAJAAkACQAJAAkACQCAAQf+/AUsNAEEAKQPQgYSAACEBQQAoAuyAhIAADQIDQAJAIAAtAKDAgQgiAkEBRg0AIAIOAwUKCQoLIABBAnQiAygCoICDCCICQQFIDQYgAEEBdC8BoICPCCEEIAMoAqCAiQghBQJAAkAgAiACQX9qcUUNAANAIAFC7cyz990AfkILfEL///////8/gyIBQhGIpyIDIAJwIgAgAiADamtBf0oNAAtBACABNwPQgYSAAAwBC0EAIAFC7cyz990AfkILfEL///////8/gyIBNwPQgYSAACABQhGIpyACZ3YhAAsCQAJAIAQNAEEAIQMMAQsgBUECdEGggJgIaiECQQAhAwNAIAAgAigCAGsiAEEASA0BIAJBBGohAiAEIANBAWoiA0cNAAwECwsgAyAERg0CIAMgBWpBAXRBoICSCGovAQAiAEGAwAFJDQALC0EAQX42AuyAhIAADwtBAEF8NgLsgISAAA8LIAAtAKDAgQgOAwABBAULAkBBACgCxIGEgAAiAkGAAkgNAEEAQXs2AuyAhIAADwsgAEECdCgCoICDCCEAQQAgAkEBajYCxIGEgAAgAkECdEHQ7LgIaiAANgIADwsgAEECdCgCoICDCCIAQQBKDQELQQBBfDYC7ICEgAAPCyAAIABBf2pxRQ0DA0AgAULtzLP33QB+Qgt8Qv///////z+DIgFCEYinIgIgAHAgACACamtBf0oNAAtBACABNwPQgYSAAA8LQQAgAULplICji+SusPcAfkK6zbehiQh8IgZC////////P4M3A9CBhIAAIABBAXQvAaCAjwgiAkUNASAGQhCGQiCHIAFCgIC0s87d9wJ+QoCALHxCgICAgPD/P4N8Qv///////z+DQu3Ms/fdAIUhASACQX9qIQMgAEECdCgCoICJCEEBdEGggJIIaiEAA0AgAyECQQAgATcD0IGEgAAgAC8BABCOgICAAEEAKALsgISAAA0CIABBAmohACACQX9qIQMgAg0ADAILC0EAQX42AuyAhIAACw8LQQAgAULtzLP33QB+Qgt8Qv///////z+DNwPQgYSAAAvvGwMcfwh9CXsjgICAgABBMGshAyACQQN0IgRBoMDGB2oiBS0AACEGAkACQCAEQaXAxgdqLQAAIgdBAnENACAGQQJ0KALAgISAACIIIAFqIQlB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";
// END_MESH_WASM_B64

var W_B64_LUT = null;
function decodeWasmBase64(b64) {
	if (W_B64_LUT === null) {
		W_B64_LUT = new Int8Array(128).fill(-1);
		var alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
		for (var k = 0; k < 64; k++) { W_B64_LUT[alpha.charCodeAt(k)] = k; }
	}
	var len = b64.length;
	while (len > 0 && b64.charCodeAt(len - 1) <= 32) { len--; }
	var pad = 0;
	if (len >= 1 && b64.charCodeAt(len - 1) === 61) { pad++; }
	if (len >= 2 && b64.charCodeAt(len - 2) === 61) { pad++; }
	var outLen = Math.max(0, ((len * 3) >>> 2) - pad);
	var out = new Uint8Array(outLen);
	var o = 0, acc = 0, bits = 0;
	for (var i = 0; i < len; i++) {
		var c = b64.charCodeAt(i);
		if (c === 61) { break; }
		if (c >= 128) { continue; }
		var v = W_B64_LUT[c];
		if (v < 0) { continue; }
		acc = (acc << 6) | v;
		bits += 6;
		if (bits >= 8) {
			bits -= 8;
			out[o++] = (acc >> bits) & 255;
		}
	}
	return o === outLen ? out : out.slice(0, o);
}

var wasmMesh = null;
var wasmDefsSeen = 0;
var wasmLastTags = null;

function wasmMemU8(w) {
	var b = w.mem.buffer;
	if (w.memBuf !== b) {
		w.memBuf = b;
		w.memU8 = new Uint8Array(b);
	}
	return w.memU8;
}

function initWasmMesh() {
	try {
		if (typeof self !== "undefined" && self.__minecraftWebDisableWasmMesh) { return null; }
		if (typeof location !== "undefined" && location && location.search &&
			/(?:^|[?&])nowasmmesh(?:[=&]|$)/.test(location.search)) { return null; }
		if (typeof WebAssembly !== "object" || WebAssembly === null ||
			typeof WebAssembly.Module !== "function" || typeof WebAssembly.Instance !== "function") { return null; }
		if (typeof MESH_WASM_B64 !== "string" || MESH_WASM_B64.length < 16) { return null; }
		var bytes = decodeWasmBase64(MESH_WASM_B64);
		if (bytes.byteLength < 8 || bytes[0] !== 0 || bytes[1] !== 97 || bytes[2] !== 115 || bytes[3] !== 109) { return null; }
		var mod = new WebAssembly.Module(bytes);
		var inst = new WebAssembly.Instance(mod, {});
		var ex = inst && inst.exports;
		if (!ex || !ex.memory ||
			typeof ex.wasm_init !== "function" ||
			typeof ex.wasm_reset !== "function" ||
			typeof ex.wasm_job_in_ptr !== "function" ||
			typeof ex.wasm_job_in_cap !== "function" ||
			typeof ex.wasm_out_ptr !== "function" ||
			typeof ex.wasm_out_cap !== "function" ||
			typeof ex.wasm_read_defs !== "function" ||
			typeof ex.wasm_read_tags !== "function" ||
			typeof ex.wasm_run_job !== "function") { return null; }
		if ((ex.wasm_init() | 0) < 0) { return null; }
		var inPtr = ex.wasm_job_in_ptr() >>> 0;
		var inCap = ex.wasm_job_in_cap() | 0;
		var outPtr = ex.wasm_out_ptr() >>> 0;
		var outCap = ex.wasm_out_cap() | 0;
		var memBuf = ex.memory.buffer;
		var memLen = memBuf.byteLength;
		if (inCap < 64 || outCap < 64 || inPtr + inCap > memLen || outPtr + outCap > memLen) { return null; }
		return {
			ex: ex,
			mem: ex.memory,
			memBuf: memBuf,
			memU8: new Uint8Array(memBuf),
			inPtr: inPtr,
			inCap: inCap,
			outPtr: outPtr,
			outCap: outCap
		};
	} catch (e) {
		return null;
	}
}

function disableWasmToJs() {
	wasmMesh = null;
	if (SIN === null) { initTrig(); }
	resetDefs();
	if (wasmLastTags !== null) {
		try { readTags(wasmLastTags); } catch (e) { /* ignore */ }
		wasmLastTags = null;
	}
}

function wasmFlushDefsChunk(w, srcU8, defEpoch, from, to) {
	var len = 8 + (to - from);
	var memU8 = wasmMemU8(w);
	var dv = new DataView(memU8.buffer, w.inPtr, 8);
	dv.setInt32(0, 1, true);
	dv.setInt32(4, defEpoch, true);
	memU8.set(srcU8.subarray(from, to), w.inPtr + 8);
	var rc = w.ex.wasm_read_defs(w.inPtr, len) | 0;
	wasmMemU8(w);
	return rc;
}

function wasmFeedDefs(w, buf) {
	var end = buf.byteLength;
	if (end < 8) { return -1; }
	var dv = new DataView(buf);
	var defEpoch = dv.getInt32(4, true);
	if (defEpoch !== epoch) { return 0; }
	var srcU8 = new Uint8Array(buf);
	var named = [];
	var fast = end <= w.inCap;
	var rc = 0;
	if (fast) {
		wasmMemU8(w).set(srcU8, w.inPtr);
		rc = w.ex.wasm_read_defs(w.inPtr, end) | 0;
		wasmMemU8(w);
		if (rc < 0) { return rc; }
	}
	var p = 8, chunkStart = 8;
	while (p < end) {
		var recStart = p;
		var rec = dv.getUint8(p); p += 1;
		if (rec === 1) {
			if (p + 34 > end) { return -2; }
			p += 34;
		} else if (rec === 2) {
			if (p + 3 > end) { return -2; }
			p += 3;
			for (var b = 0; b < 7; b++) {
				if (p + 2 > end) { return -2; }
				var n = dv.getUint16(p, true); p += 2;
				var qBytes = n * 88;
				if (p + qBytes > end) { return -2; }
				p += qBytes;
			}
		} else if (rec === 3) {
			if (p + 3 > end) { return -2; }
			var mkind = dv.getUint8(p + 2); p += 3;
			if (mkind === 0) {
				if (p + 2 > end) { return -2; }
				p += 2;
			} else if (mkind === 1) {
				if (p + 6 > end) { return -2; }
				var cn = dv.getUint16(p, true); p += 6;
				if (p + cn * 6 > end) { return -2; }
				p += cn * 6;
			} else {
				if (p + 2 > end) { return -2; }
				var mn = dv.getUint16(p, true); p += 2;
				if (p + mn * 2 > end) { return -2; }
				p += mn * 2;
			}
		} else if (rec === 4) {
			if (p + 59 > end) { return -2; }
			p += 59;
		} else if (rec === 5) {
			if (p + 47 > end) { return -2; }
			var id = dv.getUint16(p, true);
			var need = dv.getInt32(p + 6, true);
			var nlen = dv.getUint16(p + 45, true);
			p += 47;
			if (p + nlen > end) { return -2; }
			var name = "";
			for (var ch = 0; ch < nlen; ch++) { name += String.fromCharCode(srcU8[p + ch]); }
			p += nlen;
			named.push([id, name, need]);
		} else {
			return -4;
		}
		if (!fast && 8 + (p - chunkStart) > w.inCap) {
			if (recStart === chunkStart) { return -3; }
			rc = wasmFlushDefsChunk(w, srcU8, defEpoch, chunkStart, recStart);
			if (rc < 0) { return rc; }
			chunkStart = recStart;
			if (8 + (p - chunkStart) > w.inCap) { return -3; }
		}
	}
	if (!fast && chunkStart < end) {
		rc = wasmFlushDefsChunk(w, srcU8, defEpoch, chunkStart, end);
		if (rc < 0) { return rc; }
	}
	if (named.length) { postMessage({ names: named }); }
	return 0;
}

function wasmFeedTags(w, buf) {
	if (buf.byteLength < 16) {
		wasmLastTags = null;
		return -1;
	}
	if (buf.byteLength > w.inCap) { return -3; }
	var h = new Int32Array(buf, 0, 4);
	var on = h[1] !== 0 && h[2] > 0 && buf.byteLength >= 16 + h[2] * 8;
	wasmLastTags = on ? buf.slice(0) : null;
	wasmMemU8(w).set(new Uint8Array(buf), w.inPtr);
	var rc = w.ex.wasm_read_tags(w.inPtr, buf.byteLength) | 0;
	wasmMemU8(w);
	return rc;
}

function wasmDeclineReason(rc) {
	if (rc === -1) { return "wasm: undefined state"; }
	if (rc === -2) { return "wasm: unsupported feature"; }
	if (rc === -3) { return "wasm: epoch or halo mismatch"; }
	if (rc === -4) { return "wasm: missing cell data"; }
	if (rc === -5) { return "wasm: buffer overflow"; }
	return "wasm: declined (" + rc + ")";
}

function postWasmDecline(jobId, jobEpoch, reason) {
	var out = new ArrayBuffer(64), oh = new Int32Array(out);
	oh[0] = 2; oh[1] = jobId; oh[2] = jobEpoch; oh[3] = 1;
	postMessage({ declined: reason, job: jobId });
	postMessage(out, [out]);
}

function runJobWasm(w, buf) {
	var t0 = performance.now();
	var headLen = buf.byteLength >= 12 ? 3 : (buf.byteLength >> 2);
	var hdr = new Int32Array(buf, 0, headLen);
	var jobId = headLen > 1 ? hdr[1] : 0;
	var jobEpoch = headLen > 2 ? hdr[2] : 0;
	if (buf.byteLength < 64 || buf.byteLength > w.inCap) {
		postWasmDecline(jobId, jobEpoch, buf.byteLength < 64 ? "wasm: truncated job" : ("wasm: job input " + buf.byteLength + " > " + w.inCap));
		return;
	}
	var memU8 = wasmMemU8(w);
	memU8.set(new Uint8Array(buf), w.inPtr);
	var rc = w.ex.wasm_run_job(w.inPtr, buf.byteLength) | 0;
	memU8 = wasmMemU8(w);
	if (rc > 0 && rc <= w.outCap && w.outPtr + rc <= memU8.byteLength) {
		var outBuf = memU8.slice(w.outPtr, w.outPtr + rc).buffer;
		if (rc >= 64) {
			new Int32Array(outBuf, 0, 16)[11] = Math.round((performance.now() - t0) * 1000);
		}
		postMessage(outBuf, [outBuf]);
		return;
	}
	postWasmDecline(jobId, jobEpoch, rc > 0 ? "wasm: output overflow" : wasmDeclineReason(rc));
}

if (typeof self !== "undefined" && typeof postMessage === "function" && typeof importScripts === "function") {
	wasmMesh = initWasmMesh();
	if (wasmMesh === null) { initTrig(); }
	self.onmessage = function (e) {
		var buf = e.data;
		if (!(buf instanceof ArrayBuffer) || buf.byteLength < 8) { return; }
		var kind = new Int32Array(buf, 0, 2);
		if (kind[0] === 2) {
			try {
				if (wasmMesh !== null) {
					runJobWasm(wasmMesh, buf);
				} else {
					runJob(buf);
				}
			} catch (err) {
				var out = new ArrayBuffer(64), oh = new Int32Array(out);
				var jn = buf.byteLength >= 12 ? 3 : (buf.byteLength >> 2);
				var job = new Int32Array(buf, 0, jn);
				oh[0] = 2; oh[1] = jn > 1 ? job[1] : 0; oh[2] = jn > 2 ? job[2] : 0; oh[3] = 2;
				postMessage({ declined: "kernel error: " + (err && err.stack || err), job: oh[1] });
				postMessage(out, [out]);
			}
		} else if (kind[0] === 1) {
			if (kind[1] === epoch) {
				if (wasmMesh !== null) {
					var drc = -1;
					try { drc = wasmFeedDefs(wasmMesh, buf); } catch (err) { drc = -1; }
					if (drc < 0) {
						disableWasmToJs();
						readDefs(buf);
					} else {
						wasmDefsSeen++;
					}
				} else {
					readDefs(buf);
				}
			}
		} else if (kind[0] === 3) {
			epoch = kind[1];
			if (wasmMesh !== null) {
				wasmDefsSeen = 0;
				wasmLastTags = null;
				try {
					wasmMesh.ex.wasm_reset(epoch);
					wasmMemU8(wasmMesh);
				} catch (err) {
					disableWasmToJs();
				}
			} else {
				resetDefs();
			}
		} else if (kind[0] === 4) {
			if (wasmMesh !== null) {
				var trc = -1;
				try { trc = wasmFeedTags(wasmMesh, buf); } catch (err) { trc = -99; }
				if (trc === -99 || (trc < 0 && buf.byteLength > wasmMesh.inCap)) {
					disableWasmToJs();
					readTags(buf);
				}
			} else {
				readTags(buf);
			}
		}
	};
	postMessage({ ready: true, caps: KERNEL_CAPS, capsHalo2: KERNEL_CAPS_HALO2, tags: true, wasm: wasmMesh !== null });
}

if (typeof module !== "undefined") {
	// node test hook (scripts/phone_bench/mesh_kernel_test.mjs)
	module.exports = {
		mthSeed: function (x, y, z) { mthSeed(x, y, z); return [seedLo, seedHi]; },
		rngSetSeed: rngSetSeed, rngNext: rngNext, rngNextInt: rngNextInt,
		initTrig: initTrig, mthAtan2: mthAtan2, mthSin: mthSin, mthCos: mthCos, fastInvSqrt: fastInvSqrt
	};
}
