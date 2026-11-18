// minecraft-web by chiikabu (https://github.com/chiikabu)

(function () {
	"use strict";
	if (typeof WebGL2RenderingContext === "undefined") return;
	var P = WebGL2RenderingContext.prototype, bind = P.bindBufferRange, data = P.bufferData;
	if (!bind || bind.__std140) return;
	var sizes = new WeakMap();
	var BINDING = { 0x8892: 0x8894, 0x8893: 0x8895, 0x8A11: 0x8A28, 0x8F36: 0x8F36, 0x8F37: 0x8F37, 0x88EB: 0x88ED, 0x88EC: 0x88EF, 0x8C8E: 0x8C8F };
	var fixed = function (target, index, buffer, offset, size) {
		if (target === 0x8A11 && buffer && (size & 15)) {
			var padded = (size + 15) & ~15;
			var r = bind.call(this, target, index, buffer, offset, size);
			var total = sizes.get(buffer);
			if (total === undefined) {
				total = this.getBufferParameter(0x8A11, 0x8764) || 0;
				sizes.set(buffer, total);
			}
			if (offset + padded <= total) {
				bind.call(this, target, index, buffer, offset, padded);
			}
			return r;
		}
		return bind.apply(this, arguments);
	};
	fixed.__std140 = true;
	P.bindBufferRange = fixed;
	function allocBytes(src, srcOffset, length) {
		if (typeof src === "number") return src;
		if (src instanceof ArrayBuffer) return src.byteLength;
		if (ArrayBuffer.isView(src)) {
			var unit = src.BYTES_PER_ELEMENT || 1;
			return length ? length * unit : src.byteLength - (srcOffset || 0) * unit;
		}
		return -1;
	}
	if (data) {
		P.bufferData = function (target, src, usage, srcOffset, length) {
			var binding = BINDING[target];
			if (binding) {
				var buffer = this.getParameter(binding);
				if (buffer) sizes.delete(buffer);
			}
			if (target !== 0x8892 && target !== 0x8893) {
				var bytes = allocBytes(src, srcOffset, length);
				if (bytes > 0 && (bytes & 15)) {
					var padded = (bytes + 15) & ~15;
					if (typeof src === "number") return data.call(this, target, padded, usage);
					data.call(this, target, padded, usage);
					if (ArrayBuffer.isView(src) && arguments.length > 3) {
						this.bufferSubData(target, 0, src, srcOffset || 0, length || 0);
					} else {
						this.bufferSubData(target, 0, src);
					}
					return;
				}
			}
			return data.apply(this, arguments);
		};
	}
})();

(function () {
	"use strict";

	var T = window.MinecraftWeb = window.MinecraftWeb || {};
	T.credit = { author: "chiikabu", github: "https://github.com/chiikabu" };
	console.info("minecraft-web by " + T.credit.author + " - " + T.credit.github);
	T.config = Object.assign({
		name: "Minecraft"
	}, T.config || {});

	var params = new URLSearchParams(location.search);
	T.base = new URL(".", location.href).href;

	T.cleanServer = function (value) {
		if (typeof value !== "string") { return null; }
		value = value.trim();
		if (!value || value.length > 200) { return null; }
		var host = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)*";
		var port = "(?::\\d{1,5})?";
		var plain = new RegExp("^" + host + port + "$");
		var ws = new RegExp("^wss?://" + host + port + "(?:/[A-Za-z0-9._~%/-]*)?$");
		return plain.test(value) || ws.test(value) ? value : null;
	};

	T.serverLink = function (server) {
		return T.base + "play/" + encodeURIComponent(server);
	};

	T.linkServer = T.cleanServer(params.get("server"));
	T.linkWorld = (params.get("world") || "").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 32) || null;
	if (T.linkServer) {
		window.__minecraftWebServer = T.linkServer;
	}
	T.currentServer = T.linkServer;

	function el(tag, attrs, children) {
		var node = document.createElement(tag);
		Object.keys(attrs || {}).forEach(function (k) {
			if (k === "text") { node.textContent = attrs[k]; }
			else if (k.slice(0, 2) === "on") { node.addEventListener(k.slice(2), attrs[k]); }
			else { node.setAttribute(k, attrs[k]); }
		});
		(children || []).forEach(function (c) { if (c) { node.appendChild(c); } });
		return node;
	}
	T.el = el;

	function ready(fn) {
		if (document.body) { fn(); } else { document.addEventListener("DOMContentLoaded", fn); }
	}
	T.ready = ready;

	var booting = true;
	var shownPct = 0;

	T.bootStage = function (text, pct) {
		var stageEl = document.getElementById("stage");
		var fillEl = document.getElementById("fill");
		var bootEl = document.getElementById("boot");
		if (stageEl && text != null) { stageEl.textContent = text; }
		if (pct != null) {
			shownPct = Math.max(shownPct, Math.min(100, pct));
			if (fillEl) { fillEl.style.width = shownPct + "%"; }
			if (bootEl) { bootEl.setAttribute("aria-valuenow", String(Math.round(shownPct))); }
		}
	};

	T.guiUnit = function (cssWidth, cssHeight) {
		var canvas = document.getElementById("game_frame");
		var dpr = (window.__minecraftWebPixelRatio && canvas && window.__minecraftWebPixelRatio(canvas)) || window.devicePixelRatio || 1;
		var fw = cssWidth * dpr, fh = cssHeight * dpr, s = 1;
		while (fw / (s + 1) >= 320 && fh / (s + 1) >= 240) { s++; }
		return s / dpr;
	};

	function layoutBoot() {
		var bootEl = document.getElementById("boot");
		if (!bootEl) { return; }
		var w = bootEl.clientWidth || window.innerWidth, h = bootEl.clientHeight || window.innerHeight;
		var gu = T.guiUnit(w, h);
		var lh = Math.floor(Math.min(w / gu * 0.75, h / gu) * 0.25) * gu;
		bootEl.style.setProperty("--lh", lh + "px");
		bootEl.style.setProperty("--gu", gu + "px");
	}
	window.addEventListener("resize", layoutBoot);

	ready(function () {
		layoutBoot();
		if (T.linkServer) {
			var j = document.getElementById("joining");
			if (j) { j.textContent = "Joining " + T.linkServer; j.hidden = false; }
		}
	});

	var nativeFetch = window.fetch;
	var progress = { read: 0, expect: 0 };
	function encodedRatio(encoding) {
		if (encoding === "br") { return 4; }
		if (encoding === "gzip" || encoding === "deflate") { return 3.3; }
		return 1;
	}
	function updateDownload() {
		if (!booting || progress.expect <= 0) { return; }
		var mb = progress.read / 1048576;
		T.bootStage("Downloading " + mb.toFixed(mb < 10 ? 1 : 0) + " MB", 2 + 93 * Math.min(1, progress.read / progress.expect));
	}
	window.fetch = function () {
		var pending = nativeFetch.apply(this, arguments);
		if (!booting || typeof TransformStream === "undefined") { return pending; }
		return pending.then(function (response) {
			try {
				if (!response.ok || !response.body) { return response; }
				var length = +response.headers.get("content-length") || 0;
				var expect = Math.round(length * encodedRatio(response.headers.get("content-encoding")));
				var seen = 0;
				progress.expect += expect;
				var counter = new TransformStream({
					transform: function (chunk, controller) {
						seen += chunk.byteLength;
						progress.read += chunk.byteLength;
						if (seen > expect) { progress.expect += chunk.byteLength; expect = seen; }
						updateDownload();
						controller.enqueue(chunk);
					}
				});
				return new Response(response.body.pipeThrough(counter), {
					status: response.status, statusText: response.statusText, headers: response.headers
				});
			} catch (e) {
				return response;
			}
		});
	};

	var splashTimer = 0;
	T.engineStarting = function () {
		T.mark("main");
		T.bootStage("Starting", 100);
		window.fetch = nativeFetch;
		splashTimer = setTimeout(hideSplash, 20000);
	};

	function hideSplash() {
		clearTimeout(splashTimer);
		booting = false;
		var bootEl = document.getElementById("boot");
		if (!bootEl || bootEl.classList.contains("gone")) { return; }
		bootEl.classList.add("gone");
		setTimeout(function () { bootEl.style.display = "none"; }, 400);
		T.emit("ready");
	}

	T.bootFailed = function (error) {
		booting = false;
		window.fetch = nativeFetch;
		T.bootStage("Couldn't start: " + (error && error.message ? error.message : error), null);
		var bootEl = document.getElementById("boot");
		if (bootEl) { bootEl.classList.add("failed"); }
	};

	var listeners = {};
	T.on = function (name, fn) { (listeners[name] = listeners[name] || []).push(fn); };
	T.emit = function (name, data) {
		(listeners[name] || []).forEach(function (fn) { try { fn(data); } catch (e) { console.error(e); } });
	};

	var LOADING_SCREENS = /screen=(GenericMessageScreen|ConnectScreen|LevelLoadingScreen|ReceivingLevelScreen|ProgressScreen)|overlay=LoadingOverlay/;
	var loading = true;
	T.quickPlaySupported = false;

	var marks = [];
	T.mark = function (name) {
		marks.push(name + " " + (performance.now() / 1000).toFixed(1) + "s");
		if (name === "title") {
			console.info("[minecraft-web] boot: " + marks.join(", "));
			T.emit("title");
		}
	};
	T.marks = marks;

	function onLogLine(text) {
		if (text.indexOf("[26.3-web] frame ") >= 0 && text.indexOf(" screen=") >= 0) {
			if (!T.titleSeen && / screen=TitleScreen overlay=- /.test(text)) { T.titleSeen = true; T.mark("title"); }
			loading = LOADING_SCREENS.test(text);
			T.inWorld = / level=true player=true/.test(text);
			var screen = / screen=(\S+)/.exec(text);
			T.screen = screen ? screen[1] : "-";
			T.menuOpen = T.screen !== "-";
			T.emit("status", text);
		}
		if (text.indexOf("MILESTONE 4: first client frame") >= 0) {
			T.mark("firstFrame");
			hideSplash();
		} else if (text.indexOf("[26.3-web] quick play: joining") >= 0) {
			T.quickPlaySupported = true;
		} else if (text.indexOf("Resource reload finished") >= 0) {
			T.mark("resourcesLoaded");
			T.emit("reloaded");
		}
	}
	["log", "info"].forEach(function (level) {
		var original = console[level];
		console[level] = function () {
			try {
				var first = arguments[0];
				if (typeof first === "string" && first.length < 400) { onLogLine(first); }
			} catch (e) {}
			return original.apply(this, arguments);
		};
	});

	var nativeRaf = window.requestAnimationFrame.bind(window);
	var nativeCancel = window.cancelAnimationFrame.bind(window);
	var boostDeadline = performance.now() + 240000;
	window.requestAnimationFrame = function (callback) {
		if (!loading || document.hidden || performance.now() > boostDeadline) {
			return nativeRaf(callback);
		}
		var fired = false, timer = 0;
		var id = nativeRaf(function (t) {
			if (fired) { return; }
			fired = true;
			clearTimeout(timer);
			callback(t);
		});
		timer = setTimeout(function () {
			if (fired) { return; }
			fired = true;
			nativeCancel(id);
			callback(performance.now());
		}, 20);
		return id;
	};

	var NativeWebSocket = window.WebSocket;
	if (NativeWebSocket) {
		var Wrapped = function (url, protocols) {
			var socket = protocols === undefined ? new NativeWebSocket(url) : new NativeWebSocket(url, protocols);
			try {
				var direct = [].concat(protocols || []).indexOf("socket-v1") >= 0;
				var clean = direct && T.cleanServer(String(url));
				if (clean) {
					T.currentServer = clean;
					T.emit("server", clean);
				}
			} catch (e) {}
			return socket;
		};
		Wrapped.prototype = NativeWebSocket.prototype;
		["CONNECTING", "OPEN", "CLOSING", "CLOSED"].forEach(function (k) { Wrapped[k] = NativeWebSocket[k]; });
		window.WebSocket = Wrapped;
	}

	T.toast = function (title, body, actions, ms) {
		ready(function () {
			var stack = document.getElementById("tc-toasts") || document.body.appendChild(el("div", { id: "tc-toasts", "aria-live": "polite" }));
			var row = el("div", { class: "tc-toast-actions" });
			var toast = el("div", { class: "tc-toast", role: "status" }, [
				el("div", { class: "tc-toast-title", text: title }),
				body ? el("div", { class: "tc-toast-body", text: body }) : null,
				row
			]);
			(actions || []).forEach(function (a) {
				row.appendChild(el("button", { class: "tc-btn tc-small", text: a.label, onclick: function () { a.run(); close(); } }));
			});
			function close() { toast.classList.add("out"); setTimeout(function () { toast.remove(); }, 300); }
			stack.appendChild(toast);
			setTimeout(close, ms || 6000);
		});
	};

	T.on("title", function () {
		if (T.linkServer && !T.quickPlaySupported) {
			T.toast("Server link", T.linkServer + " — Multiplayer → Direct Connection", null, 15000);
		}
	});
})();
