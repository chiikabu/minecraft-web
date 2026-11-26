// minecraft-web by chiikabu (https://github.com/chiikabu)

(function () {
	"use strict";
	var search = "";
	try { search = location.search || ""; } catch (e) {  }
	var flag = function (name) { return new RegExp("(?:^|[?&])" + name + "(?:[=&]|$)").test(search); };
	var param = function (name) { var m = new RegExp("[?&]" + name + "=([^&]*)").exec(search); return m ? decodeURIComponent(m[1]) : null; };
	if (flag("nomeshworker") || typeof Worker !== "function") { return; }

	var CAP_ACTIVE = 1, CAP_VERIFY = 2, CAP_AO = 4, NEED_MASK = 0x3FFFFF00;
	var NEED_NAMES = { 8: "non-cube model", 9: "tint", 10: "partial occlusion shape", 11: "fluid", 12: "translucent", 13: "random offset",
		14: "custom model seed", 15: "cutout", 17: "dynamic-shape neighbour (smooth light)", 29: "off-grid occlusion shape", 30: "unsupported block" };
	var verify = flag("meshverify");
	var capMask = -1;
	var capsParam = param("meshcaps");
	if (capsParam === "cube") { capMask = 0; } else if (capsParam) { capMask = parseInt(capsParam, 16) | 0; }
	var nav = typeof navigator !== "undefined" ? navigator : null;
	var cores = Math.max(1, Number(nav && nav.hardwareConcurrency) || 4);
	var memGb = Number(nav && nav.deviceMemory) || 8;
	var isCrOS = !!(nav && /\bCrOS\b/.test(nav.userAgent || ""));
	var lowEnd = cores <= 4 || memGb <= 4 || isCrOS;
	var defaultMeshWorkers = lowEnd ? 1 : Math.max(2, Math.min(8, memGb <= 4 ? 3 : Math.floor((cores - 2) / 2)));
	var workerCount = Math.max(1, Math.min(12, parseInt(param("meshworkers") || String(defaultMeshWorkers), 10) || defaultMeshWorkers));
	var defaultMaxInflight = Math.max(10, workerCount * 4);
	var maxInflight = Math.max(1, parseInt(param("meshinflight") || String(defaultMaxInflight), 10) || defaultMaxInflight);
	var killAfter = parseInt(param("meshkillafter") || "0", 10) || 0;

	var workers = [], results = [], jobs = new Map(), references = new Map(), names = new Map();
	var kernelCaps = 0, kernelCapsHalo2 = 0, engineHalo = 20, ready = 0, dead = 0, kernelTags = false;
	var lastTagBuf = null;
	var stats = {
		submitted: 0, jobsPosted: 0, completed: 0, fallback: 0, stale: 0, declined: 0, errors: 0, parkedPeak: 0, resets: 0,
		snapshotMicros: 0, publishMicros: 0, kernelMicros: 0, roundTripMs: 0, resultBytes: 0, jobBytes: 0, defBytes: 0,
		verifyOk: 0, verifyBad: 0, fallbackByNeed: {}, offenders: {}, declineReasons: {}
	};

	function declineResult(jobId, epoch) {
		var out = new ArrayBuffer(64), h = new Int32Array(out);
		h[0] = 2; h[1] = jobId; h[2] = epoch; h[3] = 1;
		return out;
	}

	function onResult(slot, buf) {
		var h = new Int32Array(buf, 0, 16), jobId = h[1], job = jobs.get(jobId);
		slot.inflight = Math.max(0, slot.inflight - 1);
		if (!job) { return; }
		jobs.delete(jobId);
		stats.completed++;
		stats.kernelMicros += h[11];
		stats.roundTripMs += performance.now() - job.t0;
		stats.resultBytes += buf.byteLength;
		if (h[3] !== 0) { stats.declined++; }
		if (job.verify) {
			var ref = references.get(jobId);
			if (ref) { references.delete(jobId); compare(jobId, job, ref, buf); } else { job.result = buf; references.set(-jobId, job); }
			return;
		}
		results.push(buf);
	}

	function start(index) {
		var slot = { w: null, inflight: 0, ready: false, dead: false };
		try {
			slot.w = new Worker(new URL("minecraft-web-mesh-worker.js", location.href));
		} catch (e) {
			console.warn("[minecraft-web] mesh worker failed to start:", e);
			slot.dead = true; dead++;
			return slot;
		}
		slot.w.onmessage = function (e) {
			var d = e.data;
			if (d instanceof ArrayBuffer) { onResult(slot, d); return; }
			if (d && d.ready) {
				kernelCaps = d.caps | 0; kernelCapsHalo2 = d.capsHalo2 | 0; kernelTags = !!d.tags;
				if (lastTagBuf !== null && d.tags) {
					var tagCopy = lastTagBuf.slice(0);
					slot.w.postMessage(tagCopy, [tagCopy]);
				}
				slot.ready = true; ready++;
				console.log("[minecraft-web] mesh worker " + index + " ready, caps 0x" + (kernelCaps >>> 0).toString(16) + (d.wasm ? " [wasm-simd]" : "") + (verify ? " (verify mode)" : ""));
			} else if (d && d.names) {
				for (var i = 0; i < d.names.length; i++) { names.set(d.names[i][0], d.names[i]); }
			} else if (d && d.declined) {
				var key = String(d.declined).slice(0, 120);
				stats.declineReasons[key] = (stats.declineReasons[key] || 0) + 1;
				if (stats.declined < 5) { console.warn("[minecraft-web] mesh worker declined job " + d.job + ": " + d.declined); }
			}
		};
		slot.fail = function (why) {
			console.warn("[minecraft-web] mesh worker " + index + " died: " + why + "; its sections go back to the main thread");
			if (!slot.dead) { slot.dead = true; dead++; if (slot.ready) { ready--; } slot.ready = false; }
			jobs.forEach(function (job, jobId) {
				if (job.slot === slot) { jobs.delete(jobId); if (!job.verify) { results.push(declineResult(jobId, job.epoch)); } }
			});
			slot.inflight = 0;
		};
		slot.w.onerror = function (e) {
			if (e && e.preventDefault) { e.preventDefault(); }
			slot.fail(e && e.message || String(e));
		};
		return slot;
	}
	var spawned = false;
	function ensureWorkers() {
		if (spawned) { return; }
		spawned = true;
		for (var i = 0; i < workerCount; i++) { workers.push(start(i)); }
	}
	if (typeof window !== "undefined" && window.MinecraftWeb && typeof window.MinecraftWeb.on === "function") {
		window.MinecraftWeb.on("ready", ensureWorkers);
	}

	var LAYER_NAMES = ["solid", "cutout", "translucent"];
	function quadText(i32, f32, at) {
		var parts = [];
		for (var v = 0; v < 4; v++) {
			var o = at + v * 7;
			parts.push("(" + f32[o].toFixed(4) + "," + f32[o + 1].toFixed(4) + "," + f32[o + 2].toFixed(4) + " c=" + (i32[o + 3] >>> 0).toString(16)
				+ " uv=" + f32[o + 4].toFixed(5) + "," + f32[o + 5].toFixed(5) + " l=" + (i32[o + 6] >>> 0).toString(16) + ")");
		}
		return parts.join(" ");
	}
	function stateAt(job, x, y, z) {
		var h = new Int32Array(job.bytes, 0, 16), halo = h[14] || 18, pad = (halo - 16) >> 1;
		if (x < -pad || y < -pad || z < -pad || x >= 16 + pad || y >= 16 + pad || z >= 16 + pad) { return "?"; }
		var id = new Uint16Array(job.bytes, 64, halo * halo * halo)[((y + pad) * halo + (z + pad)) * halo + x + pad];
		var n = names.get(id);
		return n ? n[1] : "#" + id;
	}
	function quadKey(i32, at) {
		var k = "";
		for (var i = 0; i < 28; i++) { k += i32[at + i] + ","; }
		return k;
	}
	function compare(jobId, job, refBuf, gotBuf) {
		var r = new Int32Array(refBuf, 0, 16), g = new Int32Array(gotBuf, 0, 16), problems = [];
		var origin = new Int32Array(job.bytes, 40, 3);
		if (g[3] !== 0) { stats.verifyBad++; if (stats.verifyBad <= 20) { console.warn("[meshverify] job " + jobId + " declined by the kernel"); } return; }
		if (r[4] !== g[4] || (r[5] & 15) !== (g[5] & 15)) { problems.push("visibility " + (g[4] >>> 0).toString(16) + "/" + (g[5] & 15) + " != " + (r[4] >>> 0).toString(16) + "/" + (r[5] & 15)); }
		var ra = new Int32Array(refBuf, 64, r[9]), ga = new Int32Array(gotBuf, 64, g[9]);
		var rs = Array.from(ra).sort().join(","), gs = Array.from(ga).sort().join(",");
		if (rs !== gs) { problems.push("animated sprites [" + gs + "] != [" + rs + "]"); }
		var rAt = 64 + r[9] * 4, gAt = 64 + g[9] * 4;
		for (var layer = 0; layer < 3; layer++) {
			var rn = r[6 + layer], gn = g[6 + layer];
			var ri = new Int32Array(refBuf, rAt, rn >> 2), gi = new Int32Array(gotBuf, gAt, gn >> 2);
			var rf = new Float32Array(refBuf, rAt, rn >> 2), gf = new Float32Array(gotBuf, gAt, gn >> 2);
			rAt += rn; gAt += gn;
			var same = rn === gn;
			if (same) { for (var i = 0; i < ri.length; i++) { if (ri[i] !== gi[i]) { same = false; break; } } }
			if (same) { continue; }
			var count = new Map(), q;
			for (q = 0; q + 28 <= ri.length; q += 28) { var k1 = quadKey(ri, q); count.set(k1, (count.get(k1) || 0) + 1); }
			var extra = 0, firstExtra = -1;
			for (q = 0; q + 28 <= gi.length; q += 28) {
				var k2 = quadKey(gi, q), c = count.get(k2) || 0;
				if (c > 0) { count.set(k2, c - 1); } else { extra++; if (firstExtra < 0) { firstExtra = q; } }
			}
			var missing = 0;
			count.forEach(function (c2) { missing += c2; });
			var first = 0;
			while (first < ri.length && first < gi.length && ri[first] === gi[first]) { first++; }
			var fq = Math.floor(first / 28) * 28;
			var msg = LAYER_NAMES[layer] + ": " + (gn / 112) + " quads vs " + (rn / 112) + " expected, " + extra + " not in the reference, " + missing + " missing"
				+ (extra === 0 && missing === 0 ? " (same quads, different order)" : "");
			var at = firstExtra >= 0 ? firstExtra : fq, src = firstExtra >= 0 || fq < gi.length ? gf : rf, srcI = firstExtra >= 0 || fq < gi.length ? gi : ri;
			if (at + 28 <= srcI.length) {
				var cx = Math.floor((src[at] + src[at + 14]) / 2 - 1e-4 * 0), cy = Math.floor((src[at + 1] + src[at + 15]) / 2), cz = Math.floor((src[at + 2] + src[at + 16]) / 2);
				cx = Math.max(0, Math.min(15, cx)); cy = Math.max(0, Math.min(15, cy)); cz = Math.max(0, Math.min(15, cz));
				msg += "\n  near block " + (origin[0] + cx) + "," + (origin[1] + cy) + "," + (origin[2] + cz) + " (" + stateAt(job, cx, cy, cz) + ")";
				if (at + 28 <= gi.length) { msg += "\n  worker: " + quadText(gi, gf, at); }
				if (fq + 28 <= ri.length) { msg += "\n  main  : " + quadText(ri, rf, firstExtra >= 0 ? Math.min(fq, ri.length - 28) : fq); }
			}
			problems.push(msg);
		}
		if (problems.length === 0) { stats.verifyOk++; return; }
		stats.verifyBad++;
		if (stats.verifyBad <= 12) {
			console.warn("[meshverify] section " + (origin[0] >> 4) + "," + (origin[1] >> 4) + "," + (origin[2] >> 4) + " job " + jobId + " differs:\n " + problems.join("\n "));
		}
	}

	var api = {
		stats: stats,
		ensureWorkers: ensureWorkers,
		caps: function () {
			ensureWorkers();
			if (ready === 0) { return 0; }
			var caps = kernelCaps & NEED_MASK & capMask;
			if (engineHalo >= 20 && capMask !== 0) { caps |= kernelCapsHalo2 & CAP_AO; }
			return caps | CAP_ACTIVE | (verify ? CAP_VERIFY : 0);
		},
		canTag: function () { return ready > 0 && kernelTags; },
		setTags: function (bytes) {
			ensureWorkers();
			if (!bytes || !bytes.byteLength) { lastTagBuf = null; return; }
			var buf = bytes.slice().buffer;
			var h = buf.byteLength >= 8 ? new Int32Array(buf, 0, 2) : null;
			lastTagBuf = (h && h[0] === 4 && h[1] !== 0) ? buf : null;
			for (var i = 0; i < workers.length; i++) {
				if (!workers[i].dead) { var copy = buf.slice(0); workers[i].w.postMessage(copy, [copy]); }
			}
		},
		capacity: function () {
			ensureWorkers();
			if (verify) { return 1 << 20; }
			var free = 0;
			for (var i = 0; i < workers.length; i++) {
				if (workers[i].ready) { free += Math.max(0, maxInflight - workers[i].inflight); }
			}
			return free;
		},
		post: function (memory, address, length) {
			ensureWorkers();
			var bytes = new Uint8Array(memory, address, length).slice().buffer;
			var h = new Int32Array(bytes, 0, 2), kind = h[0], i;
			if (kind !== 2) {
				stats.defBytes += length;
				for (i = 0; i < workers.length; i++) {
					if (!workers[i].dead) {
						var copy = i === workers.length - 1 ? bytes : bytes.slice(0);
						workers[i].w.postMessage(copy, [copy]);
						if (kind === 3 && lastTagBuf !== null) {
							var tagCopy = lastTagBuf.slice(0);
							workers[i].w.postMessage(tagCopy, [tagCopy]);
						}
					}
				}
				return;
			}
			var best = null;
			for (i = 0; i < workers.length; i++) {
				if (workers[i].ready && (best === null || workers[i].inflight < best.inflight)) { best = workers[i]; }
			}
			var jobId = h[1], epoch = new Int32Array(bytes, 8, 1)[0];
			stats.jobBytes += length;
			if (best === null) { if (!verify) { results.push(declineResult(jobId, epoch)); } return; }
			var job = { slot: best, t0: performance.now(), verify: verify, epoch: epoch, bytes: verify ? bytes.slice(0) : null, result: null };
			jobs.set(jobId, job);
			best.inflight++;
			best.w.postMessage(bytes, [bytes]);
			if (killAfter > 0 && stats.jobsPosted + 1 >= killAfter) {
				killAfter = 0;
				for (i = 0; i < workers.length; i++) { if (!workers[i].dead) { workers[i].w.terminate(); workers[i].fail("?meshkillafter"); } }
			}
			stats.jobsPosted++;
		},
		reference: function (memory, address, length) {
			var bytes = new Uint8Array(memory, address, length).slice().buffer;
			var jobId = new Int32Array(bytes, 4, 1)[0], waiting = references.get(-jobId);
			if (waiting) { references.delete(-jobId); compare(jobId, waiting, bytes, waiting.result); } else { references.set(jobId, bytes); }
		},
		peek: function () { return results.length ? results[0].byteLength : -1; },
		take: function (memory, address) {
			var buf = results.shift();
			if (buf) { new Uint8Array(memory, address, buf.byteLength).set(new Uint8Array(buf)); }
		},
		note: function (kind, a, b) {
			if (kind === 1) { stats.submitted++; }
			else if (kind === 2) {
				stats.fallback++;
				var missing = a & ~api.caps() & (NEED_MASK | 1 << 30);
				stats.fallbackByNeed[missing] = (stats.fallbackByNeed[missing] || 0) + 1;
				if (b >= 0) { stats.offenders[b] = (stats.offenders[b] || 0) + 1; }
			}
			else if (kind === 3) { stats.stale++; }
			else if (kind === 5) { stats.errors++; }
			else if (kind === 6) { if (a > stats.parkedPeak) { stats.parkedPeak = a; } }
			else if (kind === 7) { stats.resets++; }
			else if (kind === 8) { stats.snapshotMicros += a; }
			else if (kind === 9) { stats.publishMicros += a; }
			else if (kind === 10) { engineHalo = b; }
		},
		report: function (quiet) {
			var needText = function (bits) {
				var out = [];
				for (var bit = 8; bit < 31; bit++) { if (bits >> bit & 1) { out.push(NEED_NAMES[bit] || "bit " + bit); } }
				return out.join(" + ") || "none";
			};
			var total = stats.submitted + stats.fallback;
			var byNeed = Object.keys(stats.fallbackByNeed).map(function (k) { return { why: needText(k | 0), sections: stats.fallbackByNeed[k] }; })
				.sort(function (x, y) { return y.sections - x.sections; });
			var offenders = Object.keys(stats.offenders).map(function (k) {
				var n = names.get(k | 0);
				return { state: n ? n[1] : "#" + k, need: n ? needText(n[2]) : "?", sections: stats.offenders[k] };
			}).sort(function (x, y) { return y.sections - x.sections; }).slice(0, 25);
			var done = Math.max(1, stats.completed);
			var seenByNeed = {}, blocks = {};
			names.forEach(function (n) {
				for (var bit = 8; bit < 31; bit++) { if (n[2] >> bit & 1) { var key = NEED_NAMES[bit] || "bit " + bit; seenByNeed[key] = (seenByNeed[key] || 0) + 1; } }
				var m = /\{([^}]*)\}/.exec(n[1]);
				blocks[m ? m[1].replace("minecraft:", "") : n[1]] = 1;
			});
			var summary = {
				workers: ready, verify: verify, caps: "0x" + (api.caps() >>> 0).toString(16), engineHalo: engineHalo,
				sectionsToWorker: stats.submitted, sectionsOnMainThread: stats.fallback,
				workerShare: total ? Math.round(1000 * stats.submitted / total) / 10 : 0,
				completed: stats.completed, stale: stats.stale, declined: stats.declined, errors: stats.errors, parkedPeak: stats.parkedPeak,
				snapshotMsPerSection: Math.round(stats.snapshotMicros / Math.max(1, stats.submitted)) / 1000,
				publishMsPerSection: Math.round(stats.publishMicros / done) / 1000,
				kernelMsPerSection: Math.round(stats.kernelMicros / done) / 1000,
				roundTripMsPerSection: Math.round(10 * stats.roundTripMs / done) / 10,
				resultKbPerSection: Math.round(stats.resultBytes / done / 102.4) / 10,
				definitionKb: Math.round(stats.defBytes / 102.4) / 10,
				verifyOk: stats.verifyOk, verifyBad: stats.verifyBad,
				statesSeen: names.size, statesSeenByNeed: seenByNeed, blocksSeen: Object.keys(blocks).sort(),
				fallbackReasons: byNeed, fallbackStates: offenders, declineReasons: stats.declineReasons
			};
			if (!quiet) {
				console.log("[minecraft-web] mesh worker report", summary);
				if (byNeed.length) { console.table(byNeed); }
				if (offenders.length) { console.table(offenders); }
			}
			return summary;
		}
	};
	globalThis.__twitMesh = api;
})();
