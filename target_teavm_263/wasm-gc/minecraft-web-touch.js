// minecraft-web by chiikabu (https://github.com/chiikabu)

(function () {
	"use strict";

	var T = window.MinecraftWeb = window.MinecraftWeb || {};
	var forced = /[?&]touch=1(?:&|$)/.test(location.search);
	if (!forced && !(navigator.maxTouchPoints > 0)) { return; }

	var HOLD_MS = 280;
	var MOVE_SLOP = 10;

	var root, pad, playing = false, shown = false;
	var canvas = function () { return document.getElementById("game_frame"); };

	var KEYS = {
		forward: ["KeyW", "w"], left: ["KeyA", "a"], back: ["KeyS", "s"], right: ["KeyD", "d"],
		jump: ["Space", " "], sneak: ["ShiftLeft", "Shift"], inventory: ["KeyE", "e"],
		chat: ["KeyT", "t"], pause: ["Escape", "Escape"], enter: ["Enter", "Enter"], erase: ["Backspace", "Backspace"]
	};
	var down = {};

	function key(name, isDown) {
		var k = KEYS[name];
		if (!k || !!down[name] === isDown) { return; }
		down[name] = isDown;
		document.dispatchEvent(new KeyboardEvent(isDown ? "keydown" : "keyup", { code: k[0], key: k[1], bubbles: true, cancelable: true }));
	}
	function tapKey(name) { key(name, true); setTimeout(function () { key(name, false); }, 60); }

	function typeChar(ch) {
		document.dispatchEvent(new KeyboardEvent("keydown", { code: "", key: ch, bubbles: true, cancelable: true }));
		document.dispatchEvent(new KeyboardEvent("keyup", { code: "", key: ch, bubbles: true, cancelable: true }));
	}

	function mouse(button, isDown) {
		var c = canvas();
		if (c) { c.dispatchEvent(new MouseEvent(isDown ? "mousedown" : "mouseup", { button: button, bubbles: true, cancelable: true })); }
	}

	var resetPad = function () {};
	function releaseAll() {
		resetPad();
		Object.keys(down).forEach(function (name) { if (down[name] && name !== "sneak") { key(name, false); } });
		if (breaking) { mouse(0, false); breaking = false; }
		setDiagonals(false);
	}

	function btn(cls, label, glyph) {
		var b = T.el("button", { class: "tc-t " + cls, "aria-label": label, type: "button" });
		b.innerHTML = glyph || "";
		return b;
	}

	function holdable(b, onDown, onUp) {
		b.addEventListener("pointerdown", function (e) {
			e.preventDefault();
			try { b.setPointerCapture(e.pointerId); } catch (x) {}
			b.classList.add("on");
			onDown(e);
		});
		var up = function (e) {
			if (!b.classList.contains("on")) { return; }
			b.classList.remove("on");
			onUp(e);
		};
		b.addEventListener("pointerup", up);
		b.addEventListener("pointercancel", up);
		b.addEventListener("lostpointercapture", up);
	}

	function tappable(b, fn) {
		b.addEventListener("pointerdown", function (e) { e.preventDefault(); b.classList.add("on"); });
		b.addEventListener("pointerup", function (e) { e.preventDefault(); b.classList.remove("on"); fn(); });
		b.addEventListener("pointercancel", function () { b.classList.remove("on"); });
	}

	var ARROW = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3 14 11H2z"/></svg>';
	var diagonals = [];
	function setDiagonals(on) { diagonals.forEach(function (d) { d.hidden = !on; }); }

	function build() {
		root = T.el("div", { id: "tc-touch", "aria-label": "Phone controls" });

		pad = T.el("div", { class: "tc-pad" });
		var cells = [
			["ul", "Forward left", ["forward", "left"], -45], ["up", "Forward", ["forward"], 0], ["ur", "Forward right", ["forward", "right"], 45],
			["lt", "Left", ["left"], -90], ["mid", "Sneak", [], 0], ["rt", "Right", ["right"], 90],
			null, ["dn", "Back", ["back"], 180], null
		];
		var nodes = cells.map(function (c) {
			if (!c) { return pad.appendChild(T.el("span")); }
			var b = c[0] === "mid"
				? btn("tc-sneak", "Sneak", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" aria-hidden="true"><rect x="4" y="4" width="8" height="8" rx="1"/></svg>')
				: btn("tc-dir tc-" + c[0], c[1], ARROW);
			if (c[0] !== "mid") { b.firstChild.style.transform = "rotate(" + c[3] + "deg)"; }
			if (c[0] === "ul" || c[0] === "ur") { b.hidden = true; diagonals.push(b); }
			b.tabIndex = -1;
			return pad.appendChild(b);
		});
		var padFinger = null, active = -1;
		function cellAt(e) {
			var r = pad.getBoundingClientRect();
			var col = Math.floor((e.clientX - r.left) / (r.width / 3));
			var row = Math.floor((e.clientY - r.top) / (r.height / 3));
			if (col < 0 || col > 2 || row < 0 || row > 2) { return -1; }
			var i = row * 3 + col;
			if (!cells[i]) { return -1; }
			if ((i === 0 || i === 2) && active !== 1 && active !== 0 && active !== 2) { return 1; }
			return i;
		}
		function select(i) {
			if (i === active) { return; }
			var want = i >= 0 ? cells[i][2] : [];
			["forward", "back", "left", "right"].forEach(function (k) { key(k, want.indexOf(k) >= 0); });
			if (active >= 0) { nodes[active].classList.remove("on"); }
			if (i >= 0 && i !== 4) { nodes[i].classList.add("on"); }
			active = i;
			setDiagonals(i === 0 || i === 1 || i === 2);
		}
		pad.addEventListener("pointerdown", function (e) {
			e.preventDefault();
			if (padFinger !== null) { return; }
			var i = cellAt(e);
			if (i === 4) {
				key("sneak", !down.sneak);
				nodes[4].classList.toggle("latched", !!down.sneak);
				return;
			}
			padFinger = e.pointerId;
			try { pad.setPointerCapture(e.pointerId); } catch (x) {}
			select(i);
		});
		pad.addEventListener("pointermove", function (e) {
			if (e.pointerId !== padFinger) { return; }
			e.preventDefault();
			var i = cellAt(e);
			select(i === 4 ? -1 : i);
		});
		var padUp = function (e) {
			if (e.pointerId !== padFinger) { return; }
			padFinger = null;
			select(-1);
		};
		pad.addEventListener("pointerup", padUp);
		pad.addEventListener("pointercancel", padUp);
		pad.addEventListener("lostpointercapture", padUp);
		resetPad = function () { padFinger = null; select(-1); };
		root.appendChild(pad);

		var jump = btn("tc-jump", "Jump", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 2 14 9H10v5H6V9H2z"/></svg>');
		holdable(jump, function () { key("jump", true); }, function () { key("jump", false); });
		root.appendChild(jump);

		var top = T.el("div", { class: "tc-top" });
		var pause = btn("tc-small-btn tc-esc", "Pause or back",
			'<svg class="tc-when-playing" viewBox="0 0 16 16" aria-hidden="true"><rect x="4" y="3" width="3" height="10"/><rect x="9" y="3" width="3" height="10"/></svg>'
			+ '<svg class="tc-when-menu" viewBox="0 0 16 16" aria-hidden="true"><path d="M7 3 2 8l5 5V10h7V6H7z"/></svg>');
		tappable(pause, function () { tapKey("pause"); });
		var chat = btn("tc-small-btn", "Chat", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" aria-hidden="true"><path d="M2 3h12v8H7l-3 3v-3H2z"/></svg>');
		tappable(chat, function () { tapKey("chat"); setTimeout(openKeyboard, 150); });
		top.appendChild(pause);
		top.appendChild(chat);
		root.appendChild(top);

		var inv = btn("tc-inv", "Inventory", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" aria-hidden="true"><circle cx="3" cy="8" r="1.6"/><circle cx="8" cy="8" r="1.6"/><circle cx="13" cy="8" r="1.6"/></svg>');
		tappable(inv, function () { tapKey("inventory"); });
		root.appendChild(inv);

		var hotbar = T.el("div", { class: "tc-hotbar", "aria-label": "Hotbar" });
		hotbar.addEventListener("pointerdown", function (e) {
			e.preventDefault();
			var r = hotbar.getBoundingClientRect();
			var slot = Math.max(0, Math.min(8, Math.floor((e.clientX - r.left) / (r.width / 9))));
			var code = "Digit" + (slot + 1);
			document.dispatchEvent(new KeyboardEvent("keydown", { code: code, key: String(slot + 1), bubbles: true }));
			document.dispatchEvent(new KeyboardEvent("keyup", { code: code, key: String(slot + 1), bubbles: true }));
		});
		root.appendChild(hotbar);
		T.layoutHotbar = function () {
			var c = canvas();
			if (!c) { return; }
			var unit = T.guiUnit(c.clientWidth, c.clientHeight);
			hotbar.style.width = (182 * unit) + "px";
			hotbar.style.height = (22 * unit) + "px";
			inv.style.left = "calc(50% + " + (91 * unit + 6) + "px)";
			inv.style.height = inv.style.width = Math.max(30, 22 * unit) + "px";
		};
		window.addEventListener("resize", T.layoutHotbar);
		T.layoutHotbar();

		var field = T.el("input", { class: "tc-field", type: "text", autocomplete: "off", autocapitalize: "off", "aria-label": "Type into the game" });
		var last = "";
		field.addEventListener("input", function () {
			var v = field.value;
			if (v.length < last.length) { for (var i = v.length; i < last.length; i++) { tapKey("erase"); } }
			else { Array.from(v.slice(last.length)).forEach(typeChar); }
			last = v;
		});
		field.addEventListener("keydown", function (e) {
			if (e.key === "Enter") { e.preventDefault(); tapKey("enter"); field.value = last = ""; field.blur(); }
		});
		field.addEventListener("blur", function () { field.value = last = ""; });
		root.appendChild(field);
		var kb = btn("tc-small-btn tc-kb", "Keyboard", '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" aria-hidden="true"><path d="M1 4h14v8H1zM3 6v1h1V6zm2 0v1h1V6zm2 0v1h1V6zm2 0v1h1V6zm2 0v1h1V6zM4 9v1h8V9z"/></svg>');
		tappable(kb, openKeyboard);
		root.appendChild(kb);
		function openKeyboard() { field.value = last = ""; field.focus(); }
		T.openKeyboard = openKeyboard;

		var rotate = T.el("div", { class: "tc-rotate", text: "Turn your phone sideways to play" });
		root.appendChild(rotate);

		document.body.appendChild(root);
		watchGameView();
		setPlaying(playing);
	}

	var breaking = false;
	function watchGameView() {
		var press = null;
		document.addEventListener("pointerdown", function (e) {
			if (!playing || e.target !== canvas()) { return; }
			if (press) { return; }
			press = { id: e.pointerId, x: e.clientX, y: e.clientY, moved: false };
			press.timer = setTimeout(function () {
				if (press && !press.moved) { breaking = true; mouse(0, true); }
			}, HOLD_MS);
		}, true);
		document.addEventListener("pointermove", function (e) {
			if (!press || e.pointerId !== press.id) { return; }
			if (Math.abs(e.clientX - press.x) + Math.abs(e.clientY - press.y) > MOVE_SLOP) { press.moved = true; }
		}, true);
		var end = function (e) {
			if (!press || e.pointerId !== press.id) { return; }
			clearTimeout(press.timer);
			if (breaking) { mouse(0, false); breaking = false; }
			else if (!press.moved && e.type === "pointerup") { mouse(2, true); setTimeout(function () { mouse(2, false); }, 50); }
			press = null;
		};
		document.addEventListener("pointerup", end, true);
		document.addEventListener("pointercancel", end, true);
	}

	function setPlaying(value) {
		if (playing !== value) { releaseAll(); }
		playing = value;
		T.touchPlaying = shown && value;
		if (root) { root.classList.toggle("playing", value); }
		T.emit && T.emit("touchmode", value);
	}

	function followEngineMode() {
		var forward = document.querySelector('[aria-label="Touch controls"] > button[aria-label="Forward"]');
		if (!forward) { return false; }
		var read = function () { setPlaying(forward.style.display !== "none"); };
		new MutationObserver(read).observe(forward, { attributes: true, attributeFilter: ["style"] });
		read();
		return true;
	}

	function show() {
		if (shown) { return; }
		shown = true;
		document.documentElement.classList.add("tc-touch-on");
		if (!root) { build(); }
		if (!followEngineMode()) {
			var following = false;
			T.on("status", function () { if (!following) { setPlaying(!!T.inWorld && T.screen === "-"); } });
			var tries = 0, timer = setInterval(function () {
				if (followEngineMode()) { following = true; clearInterval(timer); }
				else if (++tries > 120) { clearInterval(timer); }
			}, 500);
		}
	}

	T.ready(function () {
		if (forced) { show(); }
		document.addEventListener("pointerdown", function (e) { if (e.pointerType === "touch") { show(); } }, true);
		window.addEventListener("blur", releaseAll);
		document.addEventListener("visibilitychange", function () { if (document.hidden) { releaseAll(); } });
		T.on && T.on("status", function (text) {
			if (!shown) { return; }
			if (/ screen=ChatScreen /.test(text)) { T.openKeyboard && T.openKeyboard(); }
			if (T.layoutHotbar) { T.layoutHotbar(); }
		});
	});
})();
