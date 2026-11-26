// minecraft-web 26.3 In-Browser JDK + Fabric API + Minecraft 26.3 + Mixin Runtime Bridge.
// 100% offline Chromebook-ready runtime bridge for minecraft-web-jvm-translator.js & WebMods.java.
// Exposes window.EaglerJvmBridge and window.MinecraftWebJvmBridge (and module.exports under CommonJS).
(function (root) {
	"use strict";

	// =========================================================================
	// 1. Low-Level Runtime Interop, Type Coercion & Identity Helpers
	// =========================================================================

	var nextIdentityHash = 0x100001;
	var identityHashes = typeof WeakMap === "function" ? new WeakMap() : null;

	function getIdentityHash(obj) {
		if (obj === null || obj === undefined) { return 0; }
		if (typeof obj === "string") { return javaStringHash(obj); }
		if (typeof obj === "number") { return obj | 0; }
		if (typeof obj === "boolean") { return obj ? 1231 : 1237; }
		if (typeof obj === "bigint") { return Number(BigInt.asIntN(32, obj ^ (obj >> BigInt(32)))) | 0; }
		if (typeof obj === "object" || typeof obj === "function") {
			if (identityHashes) {
				var h = identityHashes.get(obj);
				if (h === undefined) {
					nextIdentityHash = (Math.imul(nextIdentityHash, 1664525) + 1013904223) | 0;
					h = (nextIdentityHash & 0x7fffffff) || 1;
					identityHashes.set(obj, h);
				}
				return h;
			}
			if (obj.__idHash === undefined) {
				nextIdentityHash = (Math.imul(nextIdentityHash, 1664525) + 1013904223) | 0;
				obj.__idHash = (nextIdentityHash & 0x7fffffff) || 1;
			}
			return obj.__idHash;
		}
		return 0;
	}

	function javaStringHash(s) {
		var h = 0;
		for (var i = 0; i < s.length; i++) {
			h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
		}
		return h;
	}

	function isJavaString(v) {
		return typeof v === "string" || (v !== null && typeof v === "object" &&
			(v.__cls === "java/lang/String" || v.__class === "java/lang/String" || v.$class === "java/lang/String"));
	}

	function toJsStr(v) {
		if (v === null || v === undefined) { return "null"; }
		if (typeof v === "string") { return v; }
		if (typeof v === "boolean") { return v ? "true" : "false"; }
		if (typeof v === "number") { return String(v); }
		if (typeof v === "bigint") { return v.toString(); }
		if (typeof v === "object") {
			if (typeof v.__value === "string") { return v.__value; }
			if (typeof v._strValue === "string") { return v._strValue; }
			if (v.__cls === "java/lang/String" || v.__class === "java/lang/String" || v.$class === "java/lang/String") {
				return typeof v.value === "string" ? v.value : "";
			}
			var cName = v.__cls || v.__class || v.$class || "";
			if (typeof v.getString === "function" &&
				(cName === "net/minecraft/network/chat/Component" ||
				 cName === "net/minecraft/network/chat/MutableComponent")) {
				return String(v.getString());
			}
			if (typeof v.toString === "function" && v.toString !== Object.prototype.toString) {
				var r = v.toString();
				if (typeof r === "string") { return r; }
			}
			var cls = cName || "java/lang/Object";
			return cls.replace(/\//g, ".") + "@" + (getIdentityHash(v) >>> 0).toString(16);
		}
		return String(v);
	}

	function toJsStrOrNull(v) {
		if (v === null || v === undefined) { return null; }
		return toJsStr(v);
	}

	function toNum(v) {
		if (typeof v === "number") { return v; }
		if (typeof v === "bigint") { return Number(v); }
		if (typeof v === "boolean") { return v ? 1 : 0; }
		if (v && typeof v === "object") {
			if (typeof v._numValue === "number") { return v._numValue; }
			if (typeof v._numValue === "bigint") { return Number(v._numValue); }
			if (typeof v.value === "number") { return v.value; }
			if (typeof v.value === "bigint") { return Number(v.value); }
			if (typeof v.lo === "number" && typeof v.hi === "number") {
				return v.hi * 4294967296 + (v.lo >>> 0);
			}
			if (typeof v.doubleValue === "function") { return Number(v.doubleValue()); }
		}
		var n = Number(v);
		return isNaN(n) ? 0 : n;
	}

	function toInt(v) {
		return toNum(v) | 0;
	}

	function toCharCode(v) {
		if (typeof v === "number") { return v & 0xffff; }
		if (typeof v === "string") { return v.length ? (v.charCodeAt(0) & 0xffff) : 0; }
		if (v && typeof v === "object") {
			if (typeof v._charValue === "number") { return v._charValue & 0xffff; }
			if (typeof v.value === "number") { return v.value & 0xffff; }
		}
		return toInt(v) & 0xffff;
	}

	function toBool(v) {
		if (typeof v === "boolean") { return v; }
		if (typeof v === "number") { return v !== 0; }
		if (v && typeof v === "object") {
			if (typeof v._boolValue === "boolean") { return v._boolValue; }
			if (typeof v.value === "boolean") { return v.value; }
		}
		return !!v;
	}

	function unwrapArray(a) {
		if (!a) { return null; }
		if (Array.isArray(a) || ArrayBuffer.isView(a)) { return a; }
		if (a.data && (Array.isArray(a.data) || ArrayBuffer.isView(a.data))) { return a.data; }
		if (a.elements && (Array.isArray(a.elements) || ArrayBuffer.isView(a.elements))) { return a.elements; }
		if (a._arr && (Array.isArray(a._arr) || ArrayBuffer.isView(a._arr))) { return a._arr; }
		return a;
	}

	function makeTypedArray(rt, desc, arr) {
		if (rt && typeof rt.createArray === "function") {
			try {
				var created = rt.createArray(desc, arr.length);
				for (var ci = 0; ci < arr.length; ci++) { created[ci] = arr[ci]; }
				return created;
			} catch (e0) { /* fallback */ }
		}
		if (rt && typeof rt.newArray === "function") {
			try {
				var wrapped = rt.newArray(desc, arr.length);
				var dst = unwrapArray(wrapped);
				if (dst && dst !== wrapped || (dst && typeof dst.length === "number")) {
					for (var i = 0; i < arr.length; i++) { dst[i] = arr[i]; }
					return wrapped;
				}
			} catch (e) { /* fall back to raw typed array */ }
		}
		if (arr && typeof arr === "object" && !arr.__cls) { arr.__cls = desc; }
		return arr;
	}

	function makeByteArray(rt, bytes) {
		var out = bytes instanceof Int8Array ? bytes : new Int8Array(bytes);
		return makeTypedArray(rt, "[B", out);
	}

	function makeCharArray(rt, codes) {
		var out = codes instanceof Uint16Array ? codes : new Uint16Array(codes);
		return makeTypedArray(rt, "[C", out);
	}

	function makeObjectArray(rt, elemDesc, items) {
		var out = Array.isArray(items) ? items : Array.prototype.slice.call(items || []);
		return makeTypedArray(rt, "[" + elemDesc, out);
	}

	function getClassNameOf(obj) {
		if (obj === null || obj === undefined) { return "java/lang/Object"; }
		if (typeof obj === "string") { return "java/lang/String"; }
		if (typeof obj === "boolean") { return "java/lang/Boolean"; }
		if (typeof obj === "number") { return "java/lang/Double"; }
		if (typeof obj === "bigint") { return "java/lang/Long"; }
		if (typeof obj === "object" && (obj.__cls || obj.__class || obj.$class || obj._class)) {
			return obj.__cls || obj.__class || obj.$class || obj._class;
		}
		if (Array.isArray(obj)) { return "[Ljava/lang/Object;"; }
		if (obj instanceof Int8Array) { return "[B"; }
		if (obj instanceof Uint16Array) { return "[C"; }
		if (obj instanceof Int32Array) { return "[I"; }
		if (obj instanceof Float32Array) { return "[F"; }
		if (obj instanceof Float64Array) { return "[D"; }
		return "java/lang/Object";
	}

	function javaEquals(rt, a, b) {
		if (a === b) { return true; }
		if (a === null || a === undefined || b === null || b === undefined) { return false; }
		if (isJavaString(a) && isJavaString(b)) { return toJsStr(a) === toJsStr(b); }
		if (typeof a === "object" && typeof a.equals === "function") {
			try { return !!a.equals(b); } catch (e) { /* fallback */ }
		}
		if (rt && typeof a === "object" && (a.__cls || a.__class || a.$class)) {
			try {
				if (typeof rt.invokeVirtual === "function") {
					return !!rt.invokeVirtual(a, "equals", "(Ljava/lang/Object;)Z", [b]);
				}
			} catch (e) { /* fallback */ }
		}
		return false;
	}

	function javaHashCode(rt, a) {
		if (a === null || a === undefined) { return 0; }
		if (isJavaString(a)) { return javaStringHash(toJsStr(a)); }
		if (typeof a === "object" && typeof a.hashCode === "function") {
			try { return toInt(a.hashCode()); } catch (e) { /* fallback */ }
		}
		if (rt && typeof a === "object" && (a.__cls || a.__class || a.$class)) {
			try {
				if (typeof rt.invokeVirtual === "function") {
					return toInt(rt.invokeVirtual(a, "hashCode", "()I", []));
				}
			} catch (e) { /* fallback */ }
		}
		return getIdentityHash(a);
	}

	function javaToString(rt, a) {
		if (a === null || a === undefined) { return "null"; }
		if (isJavaString(a)) { return toJsStr(a); }
		if (typeof a === "object" && typeof a.toString === "function" && a.toString !== Object.prototype.toString) {
			try { return toJsStr(a.toString()); } catch (e) { /* fallback */ }
		}
		if (rt && typeof a === "object" && (a.__cls || a.__class || a.$class)) {
			try {
				if (typeof rt.invokeVirtual === "function") {
					return toJsStr(rt.invokeVirtual(a, "toString", "()Ljava/lang/String;", []));
				}
			} catch (e) { /* fallback */ }
		}
		return toJsStr(a);
	}

	function javaCompare(rt, a, b) {
		if (a === b) { return 0; }
		if (a === null || a === undefined) { return -1; }
		if (b === null || b === undefined) { return 1; }
		if (typeof a === "number" && typeof b === "number") { return a < b ? -1 : (a > b ? 1 : 0); }
		if (typeof a === "bigint" && typeof b === "bigint") { return a < b ? -1 : (a > b ? 1 : 0); }
		if (isJavaString(a) && isJavaString(b)) {
			var sa = toJsStr(a), sb = toJsStr(b);
			return sa < sb ? -1 : (sa > sb ? 1 : 0);
		}
		if (typeof a === "object" && typeof a.compareTo === "function") {
			return toInt(a.compareTo(b));
		}
		if (rt && typeof rt.invokeVirtual === "function" && typeof a === "object" && (a.__cls || a.__class || a.$class)) {
			try { return toInt(rt.invokeVirtual(a, "compareTo", "(Ljava/lang/Object;)I", [b])); } catch (e) {}
		}
		var s1 = javaToString(rt, a), s2 = javaToString(rt, b);
		return s1 < s2 ? -1 : (s1 > s2 ? 1 : 0);
	}

	var EMPTY_ARGS = [];

	// Universal callback / functional-interface invoker for translated Java lambdas & classes.
	function invokeJavaCallback(rt, target, methodNames, desc, args) {
		if (target === null || target === undefined) { return undefined; }
		var callArgs = args || EMPTY_ARGS;
		if (typeof target === "function") {
			return target.apply(null, callArgs);
		}
		// minecraft-web-jvm-translator.js LambdaMetafactory proxy fast path
		if (typeof target.__jsCallable === "function") {
			return target.__jsCallable.apply(target, callArgs);
		}
		var isArr = Array.isArray(methodNames);
		var nameCount = isArr ? methodNames.length : 1;
		if (target.__samMethodMap) {
			var smap = target.__samMethodMap;
			for (var si = 0; si < nameCount; si++) {
				var sm = isArr ? methodNames[si] : methodNames;
				var sfn = (desc && smap[sm + desc]) || smap[sm];
				if (typeof sfn === "function") { return sfn.apply(target, callArgs); }
			}
			if (target.__samName && typeof smap[target.__samName] === "function") {
				return smap[target.__samName].apply(target, callArgs);
			}
		}
		// Other lambda wrapper shapes
		if (typeof target.__lambda === "function") {
			return target.__lambda.apply(target, callArgs);
		}
		if (typeof target.fn === "function" && !target.__cls && !target.__class && !target.$class) {
			return target.fn.apply(target, callArgs);
		}
		// Direct JS method on object (with descriptor or bare name)
		for (var i = 0; i < nameCount; i++) {
			var m = isArr ? methodNames[i] : methodNames;
			if (desc && typeof target[m + desc] === "function") {
				return target[m + desc].apply(target, callArgs);
			}
			if (typeof target[m] === "function") {
				return target[m].apply(target, callArgs);
			}
		}
		// Runtime virtual / interface dispatch
		var clsName = target.__cls || target.__class || target.$class;
		if (rt && clsName) {
			for (var j = 0; j < nameCount; j++) {
				var mn = isArr ? methodNames[j] : methodNames;
				if (typeof rt.invokeVirtual === "function") {
					try { return rt.invokeVirtual(target, mn, desc || "()V", callArgs); } catch (e1) {
						if (e1 && (e1.__javaException || (e1.__cls && e1.__cls !== "java/lang/NoSuchMethodError"))) { throw e1; }
					}
				}
				if (typeof rt.invokeInterface === "function") {
					try { return rt.invokeInterface(target, mn, desc || "()V", callArgs); } catch (e2) {
						if (e2 && (e2.__javaException || (e2.__cls && e2.__cls !== "java/lang/AbstractMethodError"))) { throw e2; }
					}
				}
				if (typeof rt.invoke === "function") {
					try { return rt.invoke(target, mn, desc || null, callArgs); } catch (e3) {
						if (e3 && e3.__javaException) { throw e3; }
					}
				}
			}
			// Fallback: inspect single abstract method on class if translated lambda kept SAM name
			var clsObj = (rt.definedClasses && rt.definedClasses.get(clsName)) ||
				(rt.classes && (typeof rt.classes.get === "function" ? rt.classes.get(clsName) : rt.classes[clsName]));
			if (clsObj && clsObj.methodDefs) {
				var dKeys = Object.keys(clsObj.methodDefs);
				for (var dk = 0; dk < dKeys.length; dk++) {
					var dm = clsObj.methodDefs[dKeys[dk]];
					if (!dm || dm.name === "<init>" || dm.name === "<clinit>") { continue; }
					if (typeof rt.invokeVirtual === "function") {
						return rt.invokeVirtual(target, dm.name, dm.descriptor, callArgs);
					}
				}
			}
			if (clsObj && clsObj.methods) {
				var keys = Object.keys(clsObj.methods);
				for (var k = 0; k < keys.length; k++) {
					var mk = keys[k];
					if (mk.indexOf("<init>") === 0 || mk.indexOf("<clinit>") === 0) { continue; }
					if (typeof clsObj.methods[mk] === "function") {
						return clsObj.methods[mk].apply(target, callArgs);
					}
				}
			}
		}
		if (typeof target.invoke === "function") {
			return target.invoke.apply(target, callArgs);
		}
		return undefined;
	}

	// Pre-resolve a Java lambda / SAM listener once at registration time for zero-allocation hot dispatch.
	function bindDirectCaller(fn, thisArg, arity) {
		if (arity === 0) { return function () { return fn.call(thisArg); }; }
		if (arity === 1) { return function (a0) { return fn.call(thisArg, a0); }; }
		if (arity === 2) { return function (a0, a1) { return fn.call(thisArg, a0, a1); }; }
		if (arity === 3) { return function (a0, a1, a2) { return fn.call(thisArg, a0, a1, a2); }; }
		if (arity === 4) { return function (a0, a1, a2, a3) { return fn.call(thisArg, a0, a1, a2, a3); }; }
		return function () { return fn.apply(thisArg, arguments); };
	}

	function compileFastCallbackInvoker(rt, target, methodNames, desc, arity) {
		if (target === null || target === undefined) { return function () { return undefined; }; }
		if (typeof target === "function") {
			return arity >= 0 && arity <= 4 ? bindDirectCaller(target, null, arity) : target;
		}
		if (typeof target.__jsCallable === "function") {
			return bindDirectCaller(target.__jsCallable, target, arity);
		}
		var names = Array.isArray(methodNames) ? methodNames : [methodNames];
		if (target.__samMethodMap) {
			var smap = target.__samMethodMap;
			for (var si = 0; si < names.length; si++) {
				var sm = names[si];
				var sfn = (desc && smap[sm + desc]) || smap[sm];
				if (typeof sfn === "function") { return bindDirectCaller(sfn, target, arity); }
			}
			if (target.__samName && typeof smap[target.__samName] === "function") {
				return bindDirectCaller(smap[target.__samName], target, arity);
			}
		}
		if (typeof target.__lambda === "function") {
			return bindDirectCaller(target.__lambda, target, arity);
		}
		if (typeof target.fn === "function" && !target.__cls && !target.__class && !target.$class) {
			return bindDirectCaller(target.fn, target, arity);
		}
		for (var i = 0; i < names.length; i++) {
			var m = names[i];
			if (desc && typeof target[m + desc] === "function") {
				return bindDirectCaller(target[m + desc], target, arity);
			}
			if (typeof target[m] === "function") {
				return bindDirectCaller(target[m], target, arity);
			}
		}
		var clsName = target.__cls || target.__class || target.$class;
		if (rt && clsName && typeof rt._resolveClassNoInit === "function" && typeof rt._lookupMethodHierarchy === "function") {
			try {
				var cls = rt._resolveClassNoInit(clsName);
				var found = null;
				for (var j = 0; j < names.length && !found; j++) {
					found = rt._lookupMethodHierarchy(cls, names[j], desc || "()V", true);
				}
				if (found && typeof found.fn === "function") {
					var ffn = found.fn;
					if (ffn.__isCompiled) {
						if (arity === 0) {
							var c0 = [target];
							return function () { return ffn(rt, c0); };
						}
						if (arity === 1) {
							var c1 = [target, null], b1 = false;
							return function (a0) {
								if (!b1) {
									b1 = true; c1[1] = a0;
									try { return ffn(rt, c1); } finally { c1[1] = null; b1 = false; }
								}
								return ffn(rt, [target, a0]);
							};
						}
						if (arity === 2) {
							var c2 = [target, null, null], b2 = false;
							return function (a0, a1) {
								if (!b2) {
									b2 = true; c2[1] = a0; c2[2] = a1;
									try { return ffn(rt, c2); } finally { c2[1] = null; c2[2] = null; b2 = false; }
								}
								return ffn(rt, [target, a0, a1]);
							};
						}
						if (arity === 3) {
							var c3 = [target, null, null, null], b3 = false;
							return function (a0, a1, a2) {
								if (!b3) {
									b3 = true; c3[1] = a0; c3[2] = a1; c3[3] = a2;
									try { return ffn(rt, c3); } finally { c3[1] = null; c3[2] = null; c3[3] = null; b3 = false; }
								}
								return ffn(rt, [target, a0, a1, a2]);
							};
						}
						if (arity === 4) {
							var c4 = [target, null, null, null, null], b4 = false;
							return function (a0, a1, a2, a3) {
								if (!b4) {
									b4 = true; c4[1] = a0; c4[2] = a1; c4[3] = a2; c4[4] = a3;
									try { return ffn(rt, c4); } finally { c4[1] = null; c4[2] = null; c4[3] = null; c4[4] = null; b4 = false; }
								}
								return ffn(rt, [target, a0, a1, a2, a3]);
							};
						}
					} else {
						return bindDirectCaller(ffn, target, arity);
					}
				}
			} catch (e) { /* fall through to universal fallback */ }
		}
		// Fallback with pre-allocated fixed-arity arg array
		if (arity === 0) {
			return function () { return invokeJavaCallback(rt, target, names, desc, EMPTY_ARGS); };
		}
		if (arity === 1) {
			var f1 = [null], fb1 = false;
			return function (a0) {
				if (!fb1) {
					fb1 = true; f1[0] = a0;
					try { return invokeJavaCallback(rt, target, names, desc, f1); } finally { f1[0] = null; fb1 = false; }
				}
				return invokeJavaCallback(rt, target, names, desc, [a0]);
			};
		}
		if (arity === 2) {
			var f2 = [null, null], fb2 = false;
			return function (a0, a1) {
				if (!fb2) {
					fb2 = true; f2[0] = a0; f2[1] = a1;
					try { return invokeJavaCallback(rt, target, names, desc, f2); } finally { f2[0] = null; f2[1] = null; fb2 = false; }
				}
				return invokeJavaCallback(rt, target, names, desc, [a0, a1]);
			};
		}
		return function () {
			var a = new Array(arguments.length);
			for (var ai = 0; ai < arguments.length; ai++) { a[ai] = arguments[ai]; }
			return invokeJavaCallback(rt, target, names, desc, a);
		};
	}

	function detectLongMode(rt) {
		var mode = { useBigInt: typeof BigInt === "function", factory: null };
		if (!rt) { return mode; }
		if (typeof rt.makeLong === "function") {
			mode.factory = function (n) { return rt.makeLong(n); };
			try { mode.useBigInt = typeof rt.makeLong(0) === "bigint"; } catch (e) {}
			return mode;
		}
		if (typeof rt.toLong === "function") {
			try {
				var probe = rt.toLong(0);
				mode.useBigInt = typeof probe === "bigint";
				mode.factory = function (n) { return rt.toLong(n); };
				return mode;
			} catch (e) {}
		}
		if (typeof rt.useBigInt === "boolean") {
			mode.useBigInt = rt.useBigInt;
			return mode;
		}
		if (typeof rt.bigIntLongs === "boolean") {
			mode.useBigInt = rt.bigIntLongs;
			return mode;
		}
		if (rt.longMode === "number") {
			mode.useBigInt = false;
			return mode;
		}
		return mode;
	}

	function makeLong(ctx, n) {
		if (ctx && ctx.longMode) {
			if (ctx.longMode.factory) { return ctx.longMode.factory(n); }
			if (ctx.longMode.useBigInt && typeof BigInt === "function") {
				return typeof n === "bigint" ? BigInt.asIntN(64, n) : BigInt.asIntN(64, BigInt(Math.trunc(Number(n) || 0)));
			}
		}
		return typeof n === "bigint" ? Number(n) : Math.trunc(Number(n) || 0);
	}

	function observeLongArg(ctx, v) {
		if (ctx && ctx.longMode && !ctx.longMode.factory) {
			if (typeof v === "bigint") { ctx.longMode.useBigInt = true; }
		}
	}

	// =========================================================================
	// 2. Persistent Virtual Filesystem (localStorage "eagler.jvmfs." + RAM fallback)
	// =========================================================================

	var FS_PREFIX = "eagler.jvmfs.";
	var FS_DIRS_KEY = "eagler.jvmfs.__dirs__";

	function createVirtualFs() {
		var memFiles = Object.create(null);
		var memDirs = Object.create(null);
		var lsAvailable = false;
		try {
			if (typeof root.localStorage !== "undefined" && root.localStorage) {
				root.localStorage.setItem("__eagler_jvmfs_probe__", "1");
				root.localStorage.removeItem("__eagler_jvmfs_probe__");
				lsAvailable = true;
			}
		} catch (e) { lsAvailable = false; }

		function normalizePath(p) {
			var s = String(p == null ? "" : p).replace(/\\/g, "/").trim();
			if (!s) { return "/"; }
			var parts = s.split("/");
			var out = [];
			for (var i = 0; i < parts.length; i++) {
				var seg = parts[i];
				if (!seg || seg === ".") { continue; }
				if (seg === "..") {
					if (out.length > 0) { out.pop(); }
				} else {
					out.push(seg);
				}
			}
			return "/" + out.join("/");
		}

		function loadDirs() {
			var set = Object.create(null);
			set["/"] = true;
			set["/config"] = true;
			set["/config/mods"] = true;
			set["/logs"] = true;
			set["/saves"] = true;
			set["/resourcepacks"] = true;
			if (lsAvailable) {
				try {
					var raw = root.localStorage.getItem(FS_DIRS_KEY);
					if (raw) {
						var arr = JSON.parse(raw);
						if (Array.isArray(arr)) {
							for (var i = 0; i < arr.length; i++) {
								if (typeof arr[i] === "string") { set[normalizePath(arr[i])] = true; }
							}
						}
					}
				} catch (e) {}
			}
			var memKeys = Object.keys(memDirs);
			for (var j = 0; j < memKeys.length; j++) { set[memKeys[j]] = true; }
			return set;
		}

		function saveDirs(set) {
			memDirs = set;
			if (lsAvailable) {
				try {
					root.localStorage.setItem(FS_DIRS_KEY, JSON.stringify(Object.keys(set)));
				} catch (e) {}
			}
		}

		function ensureParents(path) {
			var norm = normalizePath(path);
			var idx = norm.lastIndexOf("/");
			if (idx <= 0) { return; }
			var dirs = loadDirs();
			var cur = "";
			var parts = norm.slice(1, idx).split("/");
			for (var i = 0; i < parts.length; i++) {
				cur += "/" + parts[i];
				dirs[cur] = true;
			}
			saveDirs(dirs);
		}

		function bytesToBase64(bytes) {
			var bin = "";
			for (var i = 0; i < bytes.length; i++) {
				bin += String.fromCharCode(bytes[i] & 0xff);
			}
			if (typeof btoa === "function") { return btoa(bin); }
			if (typeof Buffer !== "undefined") { return Buffer.from(bytes).toString("base64"); }
			return bin;
		}

		function base64ToBytes(b64) {
			var bin;
			if (typeof atob === "function") {
				bin = atob(b64);
			} else if (typeof Buffer !== "undefined") {
				var buf = Buffer.from(b64, "base64");
				var arr = new Uint8Array(buf.length);
				for (var bi = 0; bi < buf.length; bi++) { arr[bi] = buf[bi]; }
				return arr;
			} else {
				bin = b64;
			}
			var out = new Uint8Array(bin.length);
			for (var i = 0; i < bin.length; i++) { out[i] = bin.charCodeAt(i) & 0xff; }
			return out;
		}

		function encodeUtf8(str) {
			if (typeof TextEncoder === "function") {
				return new TextEncoder().encode(str);
			}
			var out = [];
			for (var i = 0; i < str.length; i++) {
				var c = str.charCodeAt(i);
				if (c < 0x80) { out.push(c); }
				else if (c < 0x800) { out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f)); }
				else { out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f)); }
			}
			return new Uint8Array(out);
		}

		function decodeUtf8(bytes) {
			var u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes.buffer || bytes, bytes.byteOffset || 0, bytes.length);
			if (typeof TextDecoder === "function") {
				return new TextDecoder("utf-8").decode(u8);
			}
			var out = "";
			for (var i = 0; i < u8.length; i++) {
				var c = u8[i];
				if (c < 0x80) { out += String.fromCharCode(c); }
				else if ((c & 0xe0) === 0xc0 && i + 1 < u8.length) {
					out += String.fromCharCode(((c & 0x1f) << 6) | (u8[++i] & 0x3f));
				} else if ((c & 0xf0) === 0xe0 && i + 2 < u8.length) {
					out += String.fromCharCode(((c & 0x0f) << 12) | ((u8[++i] & 0x3f) << 6) | (u8[++i] & 0x3f));
				} else {
					out += String.fromCharCode(c);
				}
			}
			return out;
		}

		function rawGet(norm) {
			if (lsAvailable) {
				try {
					var v = root.localStorage.getItem(FS_PREFIX + norm);
					if (v !== null) { return v; }
				} catch (e) {}
			}
			return Object.prototype.hasOwnProperty.call(memFiles, norm) ? memFiles[norm] : null;
		}

		function rawSet(norm, encoded) {
			memFiles[norm] = encoded;
			if (lsAvailable) {
				try { root.localStorage.setItem(FS_PREFIX + norm, encoded); } catch (e) {}
			}
		}

		function rawRemove(norm) {
			delete memFiles[norm];
			if (lsAvailable) {
				try { root.localStorage.removeItem(FS_PREFIX + norm); } catch (e) {}
			}
		}

		return {
			normalize: normalizePath,
			encodeUtf8: encodeUtf8,
			decodeUtf8: decodeUtf8,
			isDirectory: function (p) {
				var norm = normalizePath(p);
				var dirs = loadDirs();
				return !!dirs[norm];
			},
			isFile: function (p) {
				var norm = normalizePath(p);
				return rawGet(norm) !== null;
			},
			exists: function (p) {
				var norm = normalizePath(p);
				return rawGet(norm) !== null || !!loadDirs()[norm];
			},
			mkdirs: function (p) {
				var norm = normalizePath(p);
				var dirs = loadDirs();
				var parts = norm.split("/").filter(Boolean);
				var cur = "";
				for (var i = 0; i < parts.length; i++) {
					cur += "/" + parts[i];
					dirs[cur] = true;
				}
				saveDirs(dirs);
				return true;
			},
			readString: function (p) {
				var norm = normalizePath(p);
				var raw = rawGet(norm);
				if (raw === null) { return null; }
				if (raw.indexOf("utf8:") === 0) { return raw.slice(5); }
				if (raw.indexOf("b64:") === 0) { return decodeUtf8(base64ToBytes(raw.slice(4))); }
				return raw;
			},
			writeString: function (p, text, append) {
				var norm = normalizePath(p);
				ensureParents(norm);
				var content = String(text == null ? "" : text);
				if (append) {
					var existing = this.readString(norm);
					if (existing !== null) { content = existing + content; }
				}
				rawSet(norm, "utf8:" + content);
			},
			readBytes: function (p) {
				var norm = normalizePath(p);
				var raw = rawGet(norm);
				if (raw === null) { return null; }
				if (raw.indexOf("b64:") === 0) { return base64ToBytes(raw.slice(4)); }
				if (raw.indexOf("utf8:") === 0) { return encodeUtf8(raw.slice(5)); }
				return encodeUtf8(raw);
			},
			writeBytes: function (p, bytes, append) {
				var norm = normalizePath(p);
				ensureParents(norm);
				var src = unwrapArray(bytes) || new Uint8Array(0);
				var u8 = new Uint8Array(src.length);
				for (var i = 0; i < src.length; i++) { u8[i] = src[i] & 0xff; }
				if (append) {
					var prev = this.readBytes(norm);
					if (prev && prev.length) {
						var merged = new Uint8Array(prev.length + u8.length);
						merged.set(prev, 0);
						merged.set(u8, prev.length);
						u8 = merged;
					}
				}
				rawSet(norm, "b64:" + bytesToBase64(u8));
			},
			deleteEntry: function (p) {
				var norm = normalizePath(p);
				if (rawGet(norm) !== null) {
					rawRemove(norm);
					return true;
				}
				var dirs = loadDirs();
				if (dirs[norm] && norm !== "/") {
					delete dirs[norm];
					saveDirs(dirs);
					return true;
				}
				return false;
			},
			list: function (dirPath) {
				var norm = normalizePath(dirPath);
				var prefix = norm === "/" ? "/" : norm + "/";
				var seen = Object.create(null);
				var allPaths = Object.keys(memFiles).concat(Object.keys(loadDirs()));
				if (lsAvailable) {
					try {
						for (var i = 0; i < root.localStorage.length; i++) {
							var k = root.localStorage.key(i);
							if (k && k.indexOf(FS_PREFIX) === 0 && k !== FS_DIRS_KEY) {
								allPaths.push(k.slice(FS_PREFIX.length));
							}
						}
					} catch (e) {}
				}
				for (var j = 0; j < allPaths.length; j++) {
					var ap = allPaths[j];
					if (ap.indexOf(prefix) === 0 && ap.length > prefix.length) {
						var rest = ap.slice(prefix.length);
						var slash = rest.indexOf("/");
						var child = slash >= 0 ? rest.slice(0, slash) : rest;
						if (child) { seen[child] = true; }
					}
				}
				return Object.keys(seen).sort();
			}
		};
	}

	var VFS = createVirtualFs();

	// =========================================================================
	// 3. Full GLFW Keycode Table & Live Browser Keyboard / Mouse Input Tracker
	// =========================================================================

	var GLFW_KEYS = {
		GLFW_RELEASE: 0, GLFW_PRESS: 1, GLFW_REPEAT: 2,
		GLFW_MOD_SHIFT: 1, GLFW_MOD_CONTROL: 2, GLFW_MOD_ALT: 4, GLFW_MOD_SUPER: 8,
		GLFW_MOD_CAPS_LOCK: 16, GLFW_MOD_NUM_LOCK: 32,
		GLFW_MOUSE_BUTTON_1: 0, GLFW_MOUSE_BUTTON_2: 1, GLFW_MOUSE_BUTTON_3: 2,
		GLFW_MOUSE_BUTTON_4: 3, GLFW_MOUSE_BUTTON_5: 4, GLFW_MOUSE_BUTTON_6: 5,
		GLFW_MOUSE_BUTTON_7: 6, GLFW_MOUSE_BUTTON_8: 7,
		GLFW_MOUSE_BUTTON_LEFT: 0, GLFW_MOUSE_BUTTON_RIGHT: 1, GLFW_MOUSE_BUTTON_MIDDLE: 2,
		GLFW_KEY_UNKNOWN: -1,
		GLFW_KEY_SPACE: 32, GLFW_KEY_APOSTROPHE: 39, GLFW_KEY_COMMA: 44,
		GLFW_KEY_MINUS: 45, GLFW_KEY_PERIOD: 46, GLFW_KEY_SLASH: 47,
		GLFW_KEY_0: 48, GLFW_KEY_1: 49, GLFW_KEY_2: 50, GLFW_KEY_3: 51, GLFW_KEY_4: 52,
		GLFW_KEY_5: 53, GLFW_KEY_6: 54, GLFW_KEY_7: 55, GLFW_KEY_8: 56, GLFW_KEY_9: 57,
		GLFW_KEY_SEMICOLON: 59, GLFW_KEY_EQUAL: 61,
		GLFW_KEY_A: 65, GLFW_KEY_B: 66, GLFW_KEY_C: 67, GLFW_KEY_D: 68, GLFW_KEY_E: 69,
		GLFW_KEY_F: 70, GLFW_KEY_G: 71, GLFW_KEY_H: 72, GLFW_KEY_I: 73, GLFW_KEY_J: 74,
		GLFW_KEY_K: 75, GLFW_KEY_L: 76, GLFW_KEY_M: 77, GLFW_KEY_N: 78, GLFW_KEY_O: 79,
		GLFW_KEY_P: 80, GLFW_KEY_Q: 81, GLFW_KEY_R: 82, GLFW_KEY_S: 83, GLFW_KEY_T: 84,
		GLFW_KEY_U: 85, GLFW_KEY_V: 86, GLFW_KEY_W: 87, GLFW_KEY_X: 88, GLFW_KEY_Y: 89,
		GLFW_KEY_Z: 90,
		GLFW_KEY_LEFT_BRACKET: 91, GLFW_KEY_BACKSLASH: 92, GLFW_KEY_RIGHT_BRACKET: 93,
		GLFW_KEY_GRAVE_ACCENT: 96, GLFW_KEY_WORLD_1: 161, GLFW_KEY_WORLD_2: 162,
		GLFW_KEY_ESCAPE: 256, GLFW_KEY_ENTER: 257, GLFW_KEY_TAB: 258,
		GLFW_KEY_BACKSPACE: 259, GLFW_KEY_INSERT: 260, GLFW_KEY_DELETE: 261,
		GLFW_KEY_RIGHT: 262, GLFW_KEY_LEFT: 263, GLFW_KEY_DOWN: 264, GLFW_KEY_UP: 265,
		GLFW_KEY_PAGE_UP: 266, GLFW_KEY_PAGE_DOWN: 267, GLFW_KEY_HOME: 268, GLFW_KEY_END: 269,
		GLFW_KEY_CAPS_LOCK: 280, GLFW_KEY_SCROLL_LOCK: 281, GLFW_KEY_NUM_LOCK: 282,
		GLFW_KEY_PRINT_SCREEN: 283, GLFW_KEY_PAUSE: 284,
		GLFW_KEY_F1: 290, GLFW_KEY_F2: 291, GLFW_KEY_F3: 292, GLFW_KEY_F4: 293,
		GLFW_KEY_F5: 294, GLFW_KEY_F6: 295, GLFW_KEY_F7: 296, GLFW_KEY_F8: 297,
		GLFW_KEY_F9: 298, GLFW_KEY_F10: 299, GLFW_KEY_F11: 300, GLFW_KEY_F12: 301,
		GLFW_KEY_F13: 302, GLFW_KEY_F14: 303, GLFW_KEY_F15: 304, GLFW_KEY_F16: 305,
		GLFW_KEY_F17: 306, GLFW_KEY_F18: 307, GLFW_KEY_F19: 308, GLFW_KEY_F20: 309,
		GLFW_KEY_F21: 310, GLFW_KEY_F22: 311, GLFW_KEY_F23: 312, GLFW_KEY_F24: 313,
		GLFW_KEY_F25: 314,
		GLFW_KEY_KP_0: 320, GLFW_KEY_KP_1: 321, GLFW_KEY_KP_2: 322, GLFW_KEY_KP_3: 323,
		GLFW_KEY_KP_4: 324, GLFW_KEY_KP_5: 325, GLFW_KEY_KP_6: 326, GLFW_KEY_KP_7: 327,
		GLFW_KEY_KP_8: 328, GLFW_KEY_KP_9: 329,
		GLFW_KEY_KP_DECIMAL: 330, GLFW_KEY_KP_DIVIDE: 331, GLFW_KEY_KP_MULTIPLY: 332,
		GLFW_KEY_KP_SUBTRACT: 333, GLFW_KEY_KP_ADD: 334, GLFW_KEY_KP_ENTER: 335,
		GLFW_KEY_KP_EQUAL: 336,
		GLFW_KEY_LEFT_SHIFT: 340, GLFW_KEY_LEFT_CONTROL: 341, GLFW_KEY_LEFT_ALT: 342,
		GLFW_KEY_LEFT_SUPER: 343, GLFW_KEY_RIGHT_SHIFT: 344, GLFW_KEY_RIGHT_CONTROL: 345,
		GLFW_KEY_RIGHT_ALT: 346, GLFW_KEY_RIGHT_SUPER: 347, GLFW_KEY_MENU: 348,
		GLFW_KEY_LAST: 348
	};

	var DOM_CODE_TO_GLFW = {
		Space: 32, Quote: 39, Comma: 44, Minus: 45, Period: 46, Slash: 47,
		Digit0: 48, Digit1: 49, Digit2: 50, Digit3: 51, Digit4: 52,
		Digit5: 53, Digit6: 54, Digit7: 55, Digit8: 56, Digit9: 57,
		Semicolon: 59, Equal: 61,
		KeyA: 65, KeyB: 66, KeyC: 67, KeyD: 68, KeyE: 69, KeyF: 70, KeyG: 71,
		KeyH: 72, KeyI: 73, KeyJ: 74, KeyK: 75, KeyL: 76, KeyM: 77, KeyN: 78,
		KeyO: 79, KeyP: 80, KeyQ: 81, KeyR: 82, KeyS: 83, KeyT: 84, KeyU: 85,
		KeyV: 86, KeyW: 87, KeyX: 88, KeyY: 89, KeyZ: 90,
		BracketLeft: 91, Backslash: 92, BracketRight: 93, Backquote: 96,
		Escape: 256, Enter: 257, Tab: 258, Backspace: 259, Insert: 260, Delete: 261,
		ArrowRight: 262, ArrowLeft: 263, ArrowDown: 264, ArrowUp: 265,
		PageUp: 266, PageDown: 267, Home: 268, End: 269,
		CapsLock: 280, ScrollLock: 281, NumLock: 282, PrintScreen: 283, Pause: 284,
		F1: 290, F2: 291, F3: 292, F4: 293, F5: 294, F6: 295, F7: 296, F8: 297,
		F9: 298, F10: 299, F11: 300, F12: 301, F13: 302, F14: 303, F15: 304, F16: 305,
		Numpad0: 320, Numpad1: 321, Numpad2: 322, Numpad3: 323, Numpad4: 324,
		Numpad5: 325, Numpad6: 326, Numpad7: 327, Numpad8: 328, Numpad9: 329,
		NumpadDecimal: 330, NumpadDivide: 331, NumpadMultiply: 332,
		NumpadSubtract: 333, NumpadAdd: 334, NumpadEnter: 335, NumpadEqual: 336,
		ShiftLeft: 340, ControlLeft: 341, AltLeft: 342, MetaLeft: 343,
		ShiftRight: 344, ControlRight: 345, AltRight: 346, MetaRight: 347,
		ContextMenu: 348
	};

	function domEventToGlfwKey(ev) {
		if (!ev) { return -1; }
		if (ev.code && Object.prototype.hasOwnProperty.call(DOM_CODE_TO_GLFW, ev.code)) {
			return DOM_CODE_TO_GLFW[ev.code];
		}
		var k = ev.key;
		if (typeof k === "string" && k.length === 1) {
			var up = k.toUpperCase().charCodeAt(0);
			if (up >= 65 && up <= 90) { return up; }
			if (up >= 48 && up <= 57) { return up; }
			if (k === " ") { return 32; }
		}
		return -1;
	}

	function domModsToGlfw(ev) {
		var m = 0;
		if (ev.shiftKey) { m |= 1; }
		if (ev.ctrlKey) { m |= 2; }
		if (ev.altKey) { m |= 4; }
		if (ev.metaKey) { m |= 8; }
		return m;
	}

	function glfwKeyName(code) {
		if (code >= 65 && code <= 90) { return "key.keyboard." + String.fromCharCode(code + 32); }
		if (code >= 48 && code <= 57) { return "key.keyboard." + String.fromCharCode(code); }
		if (code >= 290 && code <= 314) { return "key.keyboard.f" + (code - 289); }
		var names = {
			32: "space", 256: "escape", 257: "enter", 258: "tab", 259: "backspace",
			260: "insert", 261: "delete", 262: "right", 263: "left", 264: "down", 265: "up",
			340: "left.shift", 341: "left.control", 342: "left.alt", 343: "left.win",
			344: "right.shift", 345: "right.control", 346: "right.alt", 347: "right.win"
		};
		return "key.keyboard." + (names[code] || "unknown");
	}

	var TEXT_SCREEN_RE = /Chat|Edit|Anvil|Sign|Book|CommandBlock/i;

	function isTextInputActive() {
		if (typeof document === "undefined") { return false; }
		var el = document.activeElement;
		if (el) {
			var tag = (el.tagName || "").toUpperCase();
			if (tag === "INPUT" || tag === "TEXTAREA" || el.isContentEditable) { return true; }
		}
		try {
			var em = root.EaglerMods;
			var st = (em && em._liveState) || (activeBridgeCtx && activeBridgeCtx.cachedState) || null;
			if (!st && em && typeof em.getState === "function") {
				st = em.getState();
			}
			if (st && st.screen && TEXT_SCREEN_RE.test(st.screen)) {
				return true;
			}
		} catch (e) {}
		return false;
	}

	var _keyDownBits = new Uint8Array(512);
	var _keyClickCounts = new Int32Array(512);
	var _mouseDownBits = new Uint8Array(16);

	var InputState = {
		keyDownBits: _keyDownBits,
		keysDown: _keyDownBits,
		keyClickCounts: _keyClickCounts,
		mouseDownBits: _mouseDownBits,
		mouseDown: _mouseDownBits,
		registeredMappings: [],
		listenersAttached: false,
		activeRuntimes: [],
		_keyEv: { key: 0, scancode: 0, action: 0, modifiers: 0, cancelled: false },
		_mouseEv: { button: 0, action: 0, modifiers: 0, cancelled: false },
		_scrollEv: { horizontal: 0, vertical: 0, xoffset: 0, yoffset: 0, cancelled: false },
		hasInputHooks: function (hookName) {
			var rts = this.activeRuntimes;
			for (var i = 0, n = rts.length; i < n; i++) {
				if (hasRegisteredHooks(rts[i], hookName)) { return true; }
			}
			return false;
		},
		attachDomListeners: function () {
			if (this.listenersAttached || typeof window === "undefined" || typeof window.addEventListener !== "function") {
				return;
			}
			this.listenersAttached = true;
			var self = this;
			var kBits = self.keyDownBits;
			var kClicks = self.keyClickCounts;
			var mBits = self.mouseDownBits;

			window.addEventListener("keydown", function (ev) {
				var glfw = domEventToGlfwKey(ev);
				if (glfw < 0 || glfw >= 512) { return; }
				var wasDown = kBits[glfw] !== 0;
				kBits[glfw] = 1;
				if (!isTextInputActive()) {
					if (!wasDown) { kClicks[glfw] = (kClicks[glfw] + 1) | 0; }
					var mappings = self.registeredMappings;
					for (var i = 0, len = mappings.length; i < len; i++) {
						var km = mappings[i];
						if (km && km._keyType === "KEYSYM" && km._keyCode === glfw) {
							km._down = true;
							if (!wasDown) { km._clickCount = (km._clickCount + 1) | 0; }
						}
					}
				}
				if (self.hasInputHooks("key")) {
					var kev = self._keyEv;
					kev.key = glfw;
					kev.scancode = 0;
					kev.action = ev.repeat ? 2 : 1;
					kev.modifiers = domModsToGlfw(ev);
					kev.cancelled = false;
					self.fireInputHook("key", kev);
				}
			}, true);

			window.addEventListener("keyup", function (ev) {
				var glfw = domEventToGlfwKey(ev);
				if (glfw < 0 || glfw >= 512) { return; }
				kBits[glfw] = 0;
				var mappings = self.registeredMappings;
				for (var i = 0, len = mappings.length; i < len; i++) {
					var km = mappings[i];
					if (km && km._keyType === "KEYSYM" && km._keyCode === glfw) {
						km._down = false;
					}
				}
				if (self.hasInputHooks("key")) {
					var kev = self._keyEv;
					kev.key = glfw;
					kev.scancode = 0;
					kev.action = 0;
					kev.modifiers = domModsToGlfw(ev);
					kev.cancelled = false;
					self.fireInputHook("key", kev);
				}
			}, true);

			window.addEventListener("mousedown", function (ev) {
				var btn = ev.button | 0;
				if (btn < 0 || btn >= 16) { return; }
				var wasDown = mBits[btn] !== 0;
				mBits[btn] = 1;
				if (!isTextInputActive()) {
					var mappings = self.registeredMappings;
					for (var i = 0, len = mappings.length; i < len; i++) {
						var km = mappings[i];
						if (km && km._keyType === "MOUSE" && km._keyCode === btn) {
							km._down = true;
							if (!wasDown) { km._clickCount = (km._clickCount + 1) | 0; }
						}
					}
				}
				if (self.hasInputHooks("mouse")) {
					var mev = self._mouseEv;
					mev.button = btn;
					mev.action = 1;
					mev.modifiers = domModsToGlfw(ev);
					mev.cancelled = false;
					self.fireInputHook("mouse", mev);
				}
			}, true);

			window.addEventListener("mouseup", function (ev) {
				var btn = ev.button | 0;
				if (btn < 0 || btn >= 16) { return; }
				mBits[btn] = 0;
				var mappings = self.registeredMappings;
				for (var i = 0, len = mappings.length; i < len; i++) {
					var km = mappings[i];
					if (km && km._keyType === "MOUSE" && km._keyCode === btn) {
						km._down = false;
					}
				}
				if (self.hasInputHooks("mouse")) {
					var mev = self._mouseEv;
					mev.button = btn;
					mev.action = 0;
					mev.modifiers = domModsToGlfw(ev);
					mev.cancelled = false;
					self.fireInputHook("mouse", mev);
				}
			}, true);

			window.addEventListener("wheel", function (ev) {
				if (!self.hasInputHooks("scroll")) { return; }
				var dx = -(ev.deltaX || 0) / 100;
				var dy = -(ev.deltaY || 0) / 100;
				var sev = self._scrollEv;
				sev.horizontal = dx;
				sev.vertical = dy;
				sev.xoffset = dx;
				sev.yoffset = dy;
				sev.cancelled = false;
				self.fireInputHook("scroll", sev);
			}, { passive: true, capture: true });

			window.addEventListener("blur", function () {
				kBits.fill(0);
				mBits.fill(0);
				var mappings = self.registeredMappings;
				for (var i = 0, len = mappings.length; i < len; i++) {
					if (mappings[i]) { mappings[i]._down = false; }
				}
			});
		},
		fireInputHook: function (hookName, payload) {
			var rts = this.activeRuntimes;
			for (var i = 0, len = rts.length; i < len; i++) {
				fireHooks(rts[i], hookName, payload);
			}
		}
	};

	// =========================================================================
	// 4. Runtime Context & Native Class Registry Builder
	// =========================================================================

	var activeBridgeCtx = null;

	function formatHookError(e) {
		if (!e) { return String(e); }
		if (e.__cls) { return e.__cls + (e.detailMessage ? ": " + e.detailMessage : ""); }
		if (e.message) { return String(e.message); }
		return String(e);
	}

	function logHookErrorOnce(ctx, hookName, modId, e) {
		if (!ctx._loggedHookErrors) { ctx._loggedHookErrors = Object.create(null); }
		var key = String(modId || "") + "|" + String(hookName || "") + "|" + formatHookError(e);
		if (ctx._loggedHookErrors[key]) { return; }
		ctx._loggedHookErrors[key] = true;
		if (typeof console !== "undefined" && console.warn) {
			console.warn("[jvm-bridge] hook skipped (" + (modId ? modId + ":" : "") + hookName + "): " + formatHookError(e));
		}
	}

	function fireHooks(ctx, hookName, payload) {
		if (!ctx) { return payload; }
		var rt = ctx.rt;
		if (rt) {
			// Zero-allocation fast path directly over translator runtime's hooks Map
			if (rt.hooks && typeof rt.hooks.get === "function") {
				var rList = rt.hooks.get(hookName);
				if (rList && rList.length > 0) {
					for (var ri = 0; ri < rList.length; ri++) {
						var rEntry = rList[ri];
						if (!rEntry) { continue; }
						try { rEntry.fn(payload, rt); } catch (e) { logHookErrorOnce(ctx, hookName, rEntry.modId, e); }
					}
				}
				return payload;
			}
			if (typeof rt.dispatchHook === "function") {
				try { rt.dispatchHook(hookName, payload); } catch (e) { logHookErrorOnce(ctx, hookName, "", e); }
				return payload;
			}
			if (typeof rt.emitHook === "function") {
				try { rt.emitHook(hookName, payload); } catch (e) { logHookErrorOnce(ctx, hookName, "", e); }
				return payload;
			}
		}
		var list = ctx.localHooks[hookName];
		if (list && list.length > 0) {
			for (var i = 0; i < list.length; i++) {
				var entry = list[i];
				if (!entry) { continue; }
				try { entry.fn(payload); } catch (e) { logHookErrorOnce(ctx, hookName, entry.modId, e); }
			}
		}
		return payload;
	}

	function hasRegisteredHooks(ctx, hookName) {
		if (!ctx) { return false; }
		var localList = ctx.localHooks[hookName];
		if (localList && localList.length > 0) { return true; }
		var rt = ctx.rt;
		if (!rt) { return false; }
		if (rt.hooks) {
			if (typeof rt.hooks.get === "function") {
				var mList = rt.hooks.get(hookName);
				return !!(mList && mList.length > 0);
			}
			var oList = rt.hooks[hookName];
			return !!(oList && oList.length > 0);
		}
		if (typeof rt.hasHooks === "function" && rt.hasHooks(hookName)) { return true; }
		return false;
	}

	function registerHookOn(ctx, modId, hookName, fn) {
		var rt = ctx.rt;
		var ownerId = modId || (rt && rt.currentModId) || "__bridge__";
		if (rt && typeof rt.registerHook === "function") {
			return rt.registerHook(ownerId, hookName, fn);
		}
		if (!ctx.localHooks[hookName]) { ctx.localHooks[hookName] = []; }
		var entry = { modId: ownerId, fn: fn };
		ctx.localHooks[hookName].push(entry);
		return function () {
			var arr = ctx.localHooks[hookName];
			if (!arr) { return; }
			var idx = arr.indexOf(entry);
			if (idx >= 0) { arr.splice(idx, 1); }
		};
	}

	function addOverloads(map, fn, keys) {
		for (var i = 0; i < keys.length; i++) {
			map[keys[i]] = fn;
		}
	}

	function removeDenseByModId(fns, modIds, rawList, targetModId) {
		var w = 0;
		for (var i = 0, len = modIds.length; i < len; i++) {
			if (modIds[i] !== targetModId) {
				if (w !== i) {
					fns[w] = fns[i];
					modIds[w] = modIds[i];
					if (rawList) { rawList[w] = rawList[i]; }
				}
				w++;
			}
		}
		fns.length = w;
		modIds.length = w;
		if (rawList) { rawList.length = w; }
	}

	function buildBridgeContext(rt) {
		var fallbackState = {
			fps: 60, frame: 0, tick: 0, inWorld: false, paused: false,
			screen: null, screenTitle: "", username: "Player", profile: "vanilla",
			renderDistance: 8, fov: 70, gamma: 0.5, player: null, world: null
		};
		var ctx = {
			rt: rt,
			longMode: detectLongMode(rt),
			nativeDefs: Object.create(null),
			nativeProtos: Object.create(null),
			classObjects: Object.create(null),
			aliases: Object.create(null),
			localHooks: Object.create(null),
			_fallbackState: fallbackState,
			cachedState: null,
			_cachedState: null,
			cachedStateSeq: -1,
			cachedStateTime: 0,
			statePulseId: 0,
			cachedPulseId: -1,
			dispatchDepth: 0,
			lastTickNumber: -1,
			lastFrameNumber: -1,
			lastTickTimeMs: Date.now(),
			baseFov: null,
			baseGamma: null,
			fovOverrideActive: false,
			gammaOverrideActive: false,
			hudCallbacks: [],
			hudHookRegistered: false,
			tickStartListeners: [],
			tickEndListeners: [],
			worldTickStartListeners: [],
			worldTickEndListeners: [],
			// Flat dense pre-resolved Fabric listener arrays (zero slice/iterator alloc per tick/frame)
			_tickStartFns: [],
			_tickStartModIds: [],
			_tickEndFns: [],
			_tickEndModIds: [],
			_worldTickStartFns: [],
			_worldTickStartModIds: [],
			_worldTickEndFns: [],
			_worldTickEndModIds: [],
			_hudRenderFns: [],
			_hudRenderModIds: [],
			_unregTickStartHook: null,
			_unregTickEndHook: null,
			_unregHudHook: null,
			worldJoinListeners: [],
			worldDisconnectListeners: [],
			screenBeforeInitListeners: [],
			screenAfterInitListeners: [],
			chatReceiveGameListeners: [],
			chatReceiveAllowGameListeners: [],
			chatReceiveChatListeners: [],
			chatReceiveAllowChatListeners: [],
			chatSendAllowChatListeners: [],
			chatSendChatListeners: [],
			chatSendAllowCmdListeners: [],
			chatSendCmdListeners: [],
			commandDispatcher: null,
			minecraftSingleton: null,
			fabricLoaderSingleton: null,
			fabricObjectShare: null,
			overlayCanvas: null,
			overlayCtx: null,
			guiGraphicsSingleton: null,
			poseStackSingleton: null,
			cameraSingleton: null,
			deltaTrackerSingleton: null,
			fontSingleton: null,
			windowSingleton: null,
			optionsSingleton: null,
			guiSingleton: null,
			chatComponentSingleton: null,
			playerSingleton: null,
			foodDataSingleton: null,
			levelSingleton: null,
			emptyItemStack: null,
			_vec3Scratch: null,
			_compProto: null,
			_vec3Proto: null,
			_bpProto: null,
			_idProto: null,
			_dimKeyCache: Object.create(null),
			_cachedScreenName: null,
			_cachedScreenTitle: null,
			_cachedScreenW: -1,
			_cachedScreenH: -1,
			_cachedScreenObj: null,
			_cachedUuidName: null,
			_cachedUuidObj: null,
			_cachedUuidStr: null,
			// Reusable depth-2 Mixin CallbackInfo / CallbackInfoReturnable pools
			_ciPool: null,
			_ciDepth: 0,
			_cirPool: null,
			_cirDepth: 0,
			// Reusable hot-path event payloads (zero per-tick / per-frame object alloc)
			_tickEv: { tick: 0, state: null, cancelled: false },
			_hudEv: { guiGraphics: null, deltaTracker: null, tickDelta: 0, guiWidth: 0, guiHeight: 0, guiScale: 1, state: null },
			_fovEv: { value: 70, returnValue: undefined, cancelled: false },
			_gammaEv: { value: 0.5, returnValue: undefined, cancelled: false },
			_frameRenderCtx: { rt: rt, frameInfo: null },
			// Cached window metrics + ResizeObserver layout tracking (zero per-frame getBoundingClientRect)
			_gameCanvas: null,
			_observedCanvas: null,
			_resizeObserver: null,
			_resizeListenersAttached: false,
			_metricsDirty: true,
			_metricsVersion: 0,
			_ovLayoutVersion: -1,
			_metrics: { canvas: null, fbW: 854, fbH: 480, cssW: 854, cssH: 480, left: 0, top: 0, guiScale: 2, guiWidth: 427, guiHeight: 240 },
			_overlayHandle: { canvas: null, ctx2d: null, metrics: null },
			_overlayVisible: false,
			_overlayDirty: false,
			// Double-buffered flat HUD draw command lists for 0% GPU redraw on steady frames
			_cmdCurNum: new Float64Array(256 * 7),
			_cmdCurStr: new Array(256),
			_cmdPrevNum: new Float64Array(256 * 7),
			_cmdPrevStr: new Array(256),
			_cmdCurCount: 0,
			_cmdCurHash: 0,
			_cmdPrevCount: 0,
			_cmdPrevHash: 0,
			_lastRenderW: -1,
			_lastRenderH: -1,
			_lastRenderScale: -1,
			sprintingLocal: null,
			yawLocal: null,
			pitchLocal: null,
			bobViewLocal: true,
			hideGuiLocal: false,
			pumpInstalled: false,
			sysProps: {
				"os.name": "Linux",
				"os.arch": "wasm",
				"os.version": "6.0",
				"java.version": "21.0.0",
				"java.vm.name": "minecraft-web In-Browser JVM",
				"java.vendor": "minecraft-web",
				"user.dir": "/",
				"user.home": "/",
				"user.name": "Player",
				"file.separator": "/",
				"path.separator": ":",
				"line.separator": "\n"
			}
		};

		ctx.removeModListeners = function (modId) {
			var mid = String(modId || "");
			if (!mid) { return; }
			removeDenseByModId(ctx._tickStartFns, ctx._tickStartModIds, ctx.tickStartListeners, mid);
			removeDenseByModId(ctx._tickEndFns, ctx._tickEndModIds, ctx.tickEndListeners, mid);
			removeDenseByModId(ctx._worldTickStartFns, ctx._worldTickStartModIds, ctx.worldTickStartListeners, mid);
			removeDenseByModId(ctx._worldTickEndFns, ctx._worldTickEndModIds, ctx.worldTickEndListeners, mid);
			removeDenseByModId(ctx._hudRenderFns, ctx._hudRenderModIds, ctx.hudCallbacks, mid);
			if (ctx._tickStartFns.length === 0 && ctx._worldTickStartFns.length === 0 && ctx._unregTickStartHook) {
				ctx._unregTickStartHook();
				ctx._unregTickStartHook = null;
			}
			if (ctx._tickEndFns.length === 0 && ctx._worldTickEndFns.length === 0 && ctx._unregTickEndHook) {
				ctx._unregTickEndHook();
				ctx._unregTickEndHook = null;
			}
			if (ctx._hudRenderFns.length === 0 && ctx._unregHudHook) {
				ctx._unregHudHook();
				ctx._unregHudHook = null;
			}
			// Also clean localHooks if fallback registry was used
			var hkKeys = Object.keys(ctx.localHooks);
			for (var hi = 0; hi < hkKeys.length; hi++) {
				var hl = ctx.localHooks[hkKeys[hi]];
				if (!hl) { continue; }
				for (var j = hl.length - 1; j >= 0; j--) {
					if (hl[j] && hl[j].modId === mid) { hl.splice(j, 1); }
				}
			}
		};

		function wrapJvmBoolFn(fn) {
			return function () { return fn.apply(this, arguments) ? 1 : 0; };
		}

		ctx.regClass = function (name, spec) {
			var methods = Object.create(null);
			var staticMethods = Object.create(null);
			var boolBareNames = Object.create(null);

			function collect(src, dst) {
				if (!src) { return; }
				var ks = Object.keys(src);
				for (var i = 0; i < ks.length; i++) {
					var k = ks[i];
					var fn = src[k];
					if (typeof fn === "function" && k.length > 2 && k.slice(-2) === ")Z") {
						var bare = k.slice(0, k.indexOf("("));
						boolBareNames[bare] = true;
						dst[k] = wrapJvmBoolFn(fn);
					} else {
						dst[k] = fn;
					}
				}
			}
			collect(spec.methods, methods);
			collect(spec.staticMethods, staticMethods);

			// Also coerce bare-name boolean methods to 1/0 for JVM IFEQ/IFNE compatibility
			var mKeys = Object.keys(methods);
			for (var bi = 0; bi < mKeys.length; bi++) {
				var mk = mKeys[bi];
				if (mk.indexOf("(") < 0 && boolBareNames[mk] && typeof methods[mk] === "function") {
					methods[mk] = wrapJvmBoolFn(methods[mk]);
				}
			}
			var sKeys = Object.keys(staticMethods);
			for (var si = 0; si < sKeys.length; si++) {
				var sk = sKeys[si];
				if (sk.indexOf("(") < 0 && boolBareNames[sk] && typeof staticMethods[sk] === "function") {
					staticMethods[sk] = wrapJvmBoolFn(staticMethods[sk]);
				}
				if (!(sk in methods)) { methods[sk] = staticMethods[sk]; }
			}

			var superName = spec.superName !== undefined ? spec.superName : (name === "java/lang/Object" ? null : "java/lang/Object");
			var staticFieldMap = spec.staticFields || spec.statics || {};
			var def = {
				name: name,
				superName: superName,
				interfaces: spec.interfaces || [],
				isInterface: !!spec.isInterface,
				statics: staticFieldMap,
				staticFields: staticFieldMap,
				fields: spec.fields || {},
				methods: methods,
				staticMethods: staticMethods,
				ctor: spec.ctor || spec.construct || null,
				construct: spec.construct || spec.ctor || null
			};
			ctx.nativeDefs[name] = def;
			if (rt && typeof rt.registerNativeClass === "function") {
				rt.registerNativeClass(name, def);
			}
			return def;
		};

		ctx.setStaticField = function (className, fieldName, value) {
			var def = ctx.nativeDefs[className];
			if (def) {
				def.statics[fieldName] = value;
				def.staticFields[fieldName] = value;
			}
			if (rt && typeof rt.registerNativeClass === "function") {
				var patch = {};
				patch[fieldName] = value;
				rt.registerNativeClass(className, { staticFields: patch });
			}
		};

		ctx.getProto = function (className) {
			var resolved = ctx.aliases[className] || className;
			if (ctx.nativeProtos[resolved]) { return ctx.nativeProtos[resolved]; }
			var def = ctx.nativeDefs[resolved];
			if (!def) { return Object.prototype; }
			var parentProto = def.superName ? ctx.getProto(def.superName) : Object.prototype;
			var proto = Object.create(parentProto);
			var mk = Object.keys(def.methods);
			for (var i = 0; i < mk.length; i++) {
				var k = mk[i];
				if (k !== "<init>" && k.indexOf("(") < 0) {
					proto[k] = def.methods[k];
				}
			}
			ctx.nativeProtos[resolved] = proto;
			return proto;
		};

		ctx.makeObj = function (className, props) {
			var resolved = ctx.aliases[className] || className;
			var obj = null;
			if (rt && typeof rt.allocObject === "function") {
				try { obj = rt.allocObject(resolved); } catch (e) { obj = null; }
			}
			if (!obj || typeof obj !== "object") {
				obj = Object.create(ctx.getProto(resolved));
			}
			obj.__cls = resolved;
			obj.__class = resolved;
			obj.$class = resolved;
			if (props) {
				var keys = Object.keys(props);
				for (var i = 0; i < keys.length; i++) {
					var k = keys[i];
					var pd = Object.getOwnPropertyDescriptor(props, k);
					if (pd && (pd.get || pd.set)) {
						Object.defineProperty(obj, k, pd);
					} else {
						obj[k] = props[k];
					}
				}
			}
			return obj;
		};

		ctx.getClassObj = function (internalName) {
			var clean = String(internalName || "java/lang/Object").replace(/\./g, "/");
			if (ctx.aliases[clean]) { clean = ctx.aliases[clean]; }
			if (rt && typeof rt.getClassObject === "function") {
				var rObj = rt.getClassObject(clean);
				if (rObj && !rObj._name) { rObj._name = clean; }
				return rObj;
			}
			if (!ctx.classObjects[clean]) {
				ctx.classObjects[clean] = ctx.makeObj("java/lang/Class", { _name: clean, _internalName: clean });
			}
			return ctx.classObjects[clean];
		};

		ctx.regAlias = function (from, to) {
			ctx.aliases[from] = to;
			if (rt && typeof rt.registerAlias === "function") {
				rt.registerAlias(from, to);
			}
		};

		ctx.regMemberAlias = function (owner, from, to) {
			if (rt && typeof rt.registerMemberAlias === "function") {
				rt.registerMemberAlias(owner, from, to);
			}
			// Also attach directly onto native class def so JS/fallback lookup resolves cleanly
			var def = ctx.nativeDefs[owner];
			if (def) {
				if (def.methods[to] && !def.methods[from]) { def.methods[from] = def.methods[to]; }
				if (def.staticMethods[to] && !def.staticMethods[from]) { def.staticMethods[from] = def.staticMethods[to]; }
			}
		};

		// Zero-JSON per-frame state cache refresh (called ONCE at start of tickStart & hudRender)
		ctx.refreshStateCache = function (passedState, frameSeq) {
			ctx.statePulseId = (ctx.statePulseId + 1) | 0;
			var em = root.EaglerMods;
			// 1. Direct zero-JSON / zero-WASM live state object when exposed by WebMods.java
			if (em && em._liveState && typeof em._liveState === "object") {
				ctx.cachedState = em._liveState;
				ctx._cachedState = em._liveState;
				ctx.cachedPulseId = ctx.statePulseId;
				if (typeof em._liveState.frameSeq === "number") {
					ctx.cachedStateSeq = em._liveState.frameSeq;
				} else if (typeof em.frameSeq === "number") {
					ctx.cachedStateSeq = em.frameSeq;
				} else if (typeof frameSeq === "number" && frameSeq >= 0) {
					ctx.cachedStateSeq = frameSeq;
				}
				return ctx.cachedState;
			}
			// 2. Direct state object passed by tick/frame pump
			if (passedState && typeof passedState === "object") {
				ctx.cachedState = passedState;
				ctx._cachedState = passedState;
				ctx.cachedPulseId = ctx.statePulseId;
				if (typeof frameSeq === "number" && frameSeq >= 0) {
					ctx.cachedStateSeq = frameSeq;
				}
				ctx.cachedStateTime = Date.now();
				return ctx.cachedState;
			}
			// 3. Fallback to EaglerMods.getState() cached at most once per frameSeq / pulse
			var seq = (typeof frameSeq === "number" && frameSeq >= 0) ? frameSeq
				: (em && typeof em.frameSeq === "number" ? em.frameSeq : -1);
			if (seq >= 0 && seq === ctx.cachedStateSeq && ctx.cachedState !== null) {
				ctx.cachedPulseId = ctx.statePulseId;
				return ctx.cachedState;
			}
			var st = null;
			try {
				if (em && typeof em.getState === "function") {
					st = em.getState();
				}
			} catch (e) { st = null; }
			ctx.cachedState = st || ctx._fallbackState;
			ctx._cachedState = ctx.cachedState;
			ctx.cachedPulseId = ctx.statePulseId;
			if (seq >= 0) { ctx.cachedStateSeq = seq; }
			ctx.cachedStateTime = Date.now();
			return ctx.cachedState;
		};

		ctx.getGameState = function (forceRefresh) {
			if (!forceRefresh) {
				// Fast path during tick or HUD render: 0 WASM calls, 0 JSON.parse, 0 Date.now()
				if (ctx.dispatchDepth > 0 && ctx.cachedState !== null) {
					return ctx.cachedState;
				}
				var em = root.EaglerMods;
				if (em && em._liveState && typeof em._liveState === "object") {
					ctx.cachedState = em._liveState;
					ctx._cachedState = em._liveState;
					return ctx.cachedState;
				}
				if (ctx.cachedState !== null) {
					if (em && typeof em.frameSeq === "number") {
						if (em.frameSeq === ctx.cachedStateSeq) { return ctx.cachedState; }
					} else if ((Date.now() - ctx.cachedStateTime) < 12) {
						return ctx.cachedState;
					}
				}
			}
			return ctx.refreshStateCache(null, -1);
		};

		ctx.eagler = function () {
			return root.EaglerMods || null;
		};

		return ctx;
	}

	// =========================================================================
	// 5. Java String.format (printf) & SLF4J {} Formatter
	// =========================================================================

	function javaStringFormat(fmt, varargs) {
		var s = toJsStr(fmt);
		var args = unwrapArray(varargs) || [];
		var autoIdx = 0;
		return s.replace(/%(?:(\d+)\$)?([-#+ 0,(<]*)(\d+)?(?:\.(\d+))?([bBhHsScCdoxXeEfgGaAtTn%])/g,
			function (match, posStr, flags, widthStr, precStr, conv) {
				if (conv === "%") { return "%"; }
				if (conv === "n") { return "\n"; }
				var idx = posStr ? (parseInt(posStr, 10) - 1) : autoIdx++;
				var val = idx < args.length ? args[idx] : null;
				var width = widthStr ? parseInt(widthStr, 10) : 0;
				var prec = precStr !== undefined ? parseInt(precStr, 10) : -1;
				var left = flags.indexOf("-") >= 0;
				var zeroPad = flags.indexOf("0") >= 0 && !left;
				var plusSign = flags.indexOf("+") >= 0;
				var spaceSign = flags.indexOf(" ") >= 0;
				var grouping = flags.indexOf(",") >= 0;
				var out = "";

				if (conv === "s" || conv === "S") {
					out = val === null || val === undefined ? "null" : toJsStr(val);
					if (prec >= 0 && out.length > prec) { out = out.slice(0, prec); }
					if (conv === "S") { out = out.toUpperCase(); }
				} else if (conv === "b" || conv === "B") {
					var bv = (val !== null && val !== undefined && val !== false && val !== 0);
					out = bv ? "true" : "false";
					if (conv === "B") { out = out.toUpperCase(); }
				} else if (conv === "c" || conv === "C") {
					out = String.fromCharCode(toCharCode(val));
					if (conv === "C") { out = out.toUpperCase(); }
				} else if (conv === "d") {
					var iv = Math.trunc(toNum(val));
					var neg = iv < 0;
					var digits = String(Math.abs(iv));
					if (grouping) { digits = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ","); }
					var sign = neg ? "-" : (plusSign ? "+" : (spaceSign ? " " : ""));
					out = sign + digits;
				} else if (conv === "x" || conv === "X" || conv === "o") {
					var u = toNum(val);
					var hex = (u < 0 ? (u >>> 0) : Math.trunc(u)).toString(conv === "o" ? 8 : 16);
					out = conv === "X" ? hex.toUpperCase() : hex;
				} else if (conv === "f" || conv === "e" || conv === "E" || conv === "g" || conv === "G") {
					var fv = toNum(val);
					var p = prec >= 0 ? prec : 6;
					var negF = fv < 0;
					var absF = Math.abs(fv);
					var body;
					if (conv === "f") {
						body = absF.toFixed(p);
						if (grouping) {
							var sp = body.split(".");
							sp[0] = sp[0].replace(/\B(?=(\d{3})+(?!\d))/g, ",");
							body = sp.join(".");
						}
					} else if (conv === "e" || conv === "E") {
						body = absF.toExponential(p);
						if (conv === "E") { body = body.toUpperCase(); }
					} else {
						body = absF.toPrecision(Math.max(1, p));
						if (conv === "G") { body = body.toUpperCase(); }
					}
					var fSign = negF ? "-" : (plusSign ? "+" : (spaceSign ? " " : ""));
					out = fSign + body;
				} else if (conv === "h" || conv === "H") {
					out = val === null || val === undefined ? "null" : (getIdentityHash(val) >>> 0).toString(16);
					if (conv === "H") { out = out.toUpperCase(); }
				} else {
					out = toJsStr(val);
				}

				if (width > out.length) {
					var padLen = width - out.length;
					if (left) {
						out = out + " ".repeat(padLen);
					} else if (zeroPad && (out[0] === "-" || out[0] === "+" || out[0] === " ")) {
						out = out[0] + "0".repeat(padLen) + out.slice(1);
					} else {
						out = (zeroPad ? "0" : " ").repeat(padLen) + out;
					}
				}
				return out;
			});
	}

	function formatSlf4j(rt, msg, extraArgs) {
		var text = toJsStr(msg);
		var args = extraArgs || [];
		if (args.length === 1 && (Array.isArray(args[0]) || (args[0] && Array.isArray(args[0].data)))) {
			args = unwrapArray(args[0]);
		}
		var idx = 0;
		return text.replace(/\{\}/g, function () {
			if (idx < args.length) { return javaToString(rt, args[idx++]); }
			return "{}";
		});
	}

	// =========================================================================
	// 6. Core JDK Native Classes Registration (java/lang/*)
	// =========================================================================

	function installJavaLang(ctx) {
		var rt = ctx.rt;

		// Common marker & functional interfaces
		var markerInterfaces = [
			"java/lang/CharSequence", "java/lang/Comparable", "java/lang/Iterable",
			"java/lang/AutoCloseable", "java/io/Closeable", "java/io/Flushable",
			"java/io/Serializable", "java/lang/Cloneable", "java/lang/Appendable",
			"java/lang/Runnable", "java/util/concurrent/Callable",
			"java/util/function/Consumer", "java/util/function/BiConsumer",
			"java/util/function/Function", "java/util/function/BiFunction",
			"java/util/function/Predicate", "java/util/function/BiPredicate",
			"java/util/function/Supplier", "java/util/function/UnaryOperator",
			"java/util/function/BinaryOperator", "java/util/function/IntSupplier",
			"java/util/function/BooleanSupplier", "java/util/function/DoubleSupplier",
			"java/util/function/LongSupplier", "java/util/function/ToIntFunction",
			"java/util/function/ToDoubleFunction", "java/util/function/ToLongFunction"
		];
		for (var mi = 0; mi < markerInterfaces.length; mi++) {
			ctx.regClass(markerInterfaces[mi], { isInterface: true, superName: "java/lang/Object" });
		}

		// ---- java/lang/Object ----
		var objMethods = {};
		addOverloads(objMethods, function () {}, ["<init>", "<init>()V"]);
		addOverloads(objMethods, function () {
			if (typeof this === "string") { return this; }
			var c = getClassNameOf(this).replace(/\//g, ".");
			return c + "@" + (getIdentityHash(this) >>> 0).toString(16);
		}, ["toString", "toString()Ljava/lang/String;"]);
		addOverloads(objMethods, function (other) { return this === other; }, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(objMethods, function () { return getIdentityHash(this); }, ["hashCode", "hashCode()I"]);
		addOverloads(objMethods, function () { return ctx.getClassObj(getClassNameOf(this)); }, ["getClass", "getClass()Ljava/lang/Class;"]);
		addOverloads(objMethods, function () {}, ["notify", "notify()V", "notifyAll", "notifyAll()V", "wait", "wait()V", "wait(J)V", "wait(JI)V"]);
		addOverloads(objMethods, function () {
			if (!this || typeof this !== "object") { return this; }
			return Object.assign(Object.create(Object.getPrototypeOf(this)), this);
		}, ["clone", "clone()Ljava/lang/Object;"]);
		ctx.regClass("java/lang/Object", { superName: null, methods: objMethods });

		// ---- java/lang/Class ----
		function clsInternalName(c) { return String((c && (c._internalName || c._name)) || "java/lang/Object"); }
		var classMethods = {};
		var classStatics = {};
		addOverloads(classMethods, function () {
			return clsInternalName(this).replace(/\//g, ".");
		}, ["getName", "getName()Ljava/lang/String;", "getCanonicalName", "getCanonicalName()Ljava/lang/String;", "getTypeName", "getTypeName()Ljava/lang/String;"]);
		addOverloads(classMethods, function () {
			var n = clsInternalName(this);
			var idx = Math.max(n.lastIndexOf("/"), n.lastIndexOf("$"));
			return idx >= 0 ? n.slice(idx + 1) : n;
		}, ["getSimpleName", "getSimpleName()Ljava/lang/String;"]);
		addOverloads(classMethods, function (obj) {
			if (obj === null || obj === undefined) { return false; }
			var rawN = clsInternalName(this);
			var target = ctx.aliases[rawN] || rawN;
			if (target === "java/lang/Object") { return true; }
			var actual = getClassNameOf(obj);
			if (actual === target || (ctx.aliases[actual] || actual) === target) { return true; }
			if (rt && typeof rt.isInstanceOf === "function") {
				try { return !!rt.isInstanceOf(obj, target); } catch (e) {}
			}
			// Walk native hierarchy
			var cur = ctx.aliases[actual] || actual;
			while (cur) {
				if (cur === target) { return true; }
				var d = ctx.nativeDefs[cur];
				if (!d) { break; }
				if (d.interfaces && d.interfaces.indexOf(target) >= 0) { return true; }
				cur = d.superName;
			}
			return false;
		}, ["isInstance", "isInstance(Ljava/lang/Object;)Z"]);
		addOverloads(classMethods, function (obj) { return obj; }, ["cast", "cast(Ljava/lang/Object;)Ljava/lang/Object;"]);
		addOverloads(classMethods, function (other) {
			if (!other) { return false; }
			var tRaw = clsInternalName(this), sRaw = clsInternalName(other);
			var target = ctx.aliases[tRaw] || tRaw;
			var sub = ctx.aliases[sRaw] || sRaw;
			if (target === "java/lang/Object" || target === sub) { return true; }
			var cur = sub;
			while (cur) {
				if (cur === target) { return true; }
				var d = ctx.nativeDefs[cur];
				if (!d) { break; }
				if (d.interfaces && d.interfaces.indexOf(target) >= 0) { return true; }
				cur = d.superName;
			}
			return false;
		}, ["isAssignableFrom", "isAssignableFrom(Ljava/lang/Class;)Z"]);
		addOverloads(classMethods, function () {
			var d = ctx.nativeDefs[clsInternalName(this)];
			return !!(d && d.isInterface);
		}, ["isInterface", "isInterface()Z"]);
		addOverloads(classMethods, function () {
			return clsInternalName(this).indexOf("[") === 0;
		}, ["isArray", "isArray()Z"]);
		addOverloads(classMethods, function () {
			return !!this._isPrimitive || "ZBSCIJFDV".indexOf(clsInternalName(this)) >= 0;
		}, ["isPrimitive", "isPrimitive()Z"]);
		addOverloads(classMethods, function () {
			var d = ctx.nativeDefs[clsInternalName(this)];
			return !!(d && d.superName === "java/lang/Enum");
		}, ["isEnum", "isEnum()Z"]);
		addOverloads(classMethods, function () {
			var d = ctx.nativeDefs[clsInternalName(this)];
			return d && d.superName ? ctx.getClassObj(d.superName) : null;
		}, ["getSuperclass", "getSuperclass()Ljava/lang/Class;"]);
		addOverloads(classMethods, function () {
			return "class " + clsInternalName(this).replace(/\//g, ".");
		}, ["toString", "toString()Ljava/lang/String;"]);
		addOverloads(classMethods, function (other) {
			return !!(other && clsInternalName(other) === clsInternalName(this));
		}, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(classMethods, function () { return javaStringHash(clsInternalName(this)); }, ["hashCode", "hashCode()I"]);
		addOverloads(classStatics, function (dotName) {
			return ctx.getClassObj(toJsStr(dotName).replace(/\./g, "/"));
		}, ["forName", "forName(Ljava/lang/String;)Ljava/lang/Class;"]);
		ctx.regClass("java/lang/Class", { methods: classMethods, staticMethods: classStatics });

		// ---- java/lang/String ----
		var strMethods = {};
		var strStatics = {};
		function setStrVal(self, v) { self.__value = v; self._strValue = v; self.value = v; }
		strMethods["<init>()V"] = function () { setStrVal(this, ""); };
		strMethods["<init>(Ljava/lang/String;)V"] = function (s) { setStrVal(this, toJsStr(s)); };
		strMethods["<init>(Ljava/lang/StringBuilder;)V"] = function (sb) { setStrVal(this, toJsStr(sb)); };
		strMethods["<init>(Ljava/lang/StringBuffer;)V"] = function (sb) { setStrVal(this, toJsStr(sb)); };
		strMethods["<init>([C)V"] = function (chars) {
			var a = unwrapArray(chars) || [];
			var out = "";
			for (var i = 0; i < a.length; i++) { out += String.fromCharCode(a[i] & 0xffff); }
			setStrVal(this, out);
		};
		strMethods["<init>([CII)V"] = function (chars, off, count) {
			var a = unwrapArray(chars) || [];
			var o = toInt(off), n = toInt(count), out = "";
			for (var i = 0; i < n && (o + i) < a.length; i++) { out += String.fromCharCode(a[o + i] & 0xffff); }
			setStrVal(this, out);
		};
		strMethods["<init>([B)V"] = function (bytes) {
			var a = unwrapArray(bytes) || new Uint8Array(0);
			var u8 = new Uint8Array(a.length);
			for (var i = 0; i < a.length; i++) { u8[i] = a[i] & 0xff; }
			setStrVal(this, VFS.decodeUtf8(u8));
		};
		strMethods["<init>([BII)V"] = function (bytes, off, len) {
			var a = unwrapArray(bytes) || new Uint8Array(0);
			var o = toInt(off), n = toInt(len);
			var u8 = new Uint8Array(n);
			for (var i = 0; i < n && (o + i) < a.length; i++) { u8[i] = a[o + i] & 0xff; }
			setStrVal(this, VFS.decodeUtf8(u8));
		};
		strMethods["<init>([BLjava/nio/charset/Charset;)V"] = strMethods["<init>([B)V"];
		strMethods["<init>([BLjava/lang/String;)V"] = strMethods["<init>([B)V"];
		strMethods["<init>([BIILjava/nio/charset/Charset;)V"] = strMethods["<init>([BII)V"];
		strMethods["<init>"] = function (a, b, c) {
			if (arguments.length === 0) { setStrVal(this, ""); return; }
			if (isJavaString(a)) { setStrVal(this, toJsStr(a)); return; }
			var arr = unwrapArray(a);
			if (arr && (arr instanceof Int8Array || arr instanceof Uint8Array)) {
				if (arguments.length >= 3 && typeof b === "number") {
					strMethods["<init>([BII)V"].call(this, a, b, c);
				} else {
					strMethods["<init>([B)V"].call(this, a);
				}
				return;
			}
			if (arr && (arr instanceof Uint16Array || Array.isArray(arr))) {
				if (arguments.length >= 3) { strMethods["<init>([CII)V"].call(this, a, b, c); }
				else { strMethods["<init>([C)V"].call(this, a); }
				return;
			}
			setStrVal(this, toJsStr(a));
		};

		addOverloads(strMethods, function () { return toJsStr(this).length; }, ["length", "length()I"]);
		addOverloads(strMethods, function () { return toJsStr(this).length === 0; }, ["isEmpty", "isEmpty()Z"]);
		addOverloads(strMethods, function () { return toJsStr(this).trim().length === 0; }, ["isBlank", "isBlank()Z"]);
		addOverloads(strMethods, function (idx) { return toJsStr(this).charCodeAt(toInt(idx)) & 0xffff; }, ["charAt", "charAt(I)C"]);
		addOverloads(strMethods, function (idx) { return toJsStr(this).codePointAt(toInt(idx)) | 0; }, ["codePointAt", "codePointAt(I)I"]);
		addOverloads(strMethods, function (begin, end) {
			var s = toJsStr(this);
			return arguments.length >= 2 && end !== undefined ? s.substring(toInt(begin), toInt(end)) : s.substring(toInt(begin));
		}, ["substring", "substring(I)Ljava/lang/String;", "substring(II)Ljava/lang/String;", "subSequence", "subSequence(II)Ljava/lang/CharSequence;"]);
		strMethods["indexOf(I)I"] = function (ch) { return toJsStr(this).indexOf(String.fromCharCode(toCharCode(ch))); };
		strMethods["indexOf(II)I"] = function (ch, from) { return toJsStr(this).indexOf(String.fromCharCode(toCharCode(ch)), toInt(from)); };
		strMethods["indexOf(Ljava/lang/String;)I"] = function (sub) { return toJsStr(this).indexOf(toJsStr(sub)); };
		strMethods["indexOf(Ljava/lang/String;I)I"] = function (sub, from) { return toJsStr(this).indexOf(toJsStr(sub), toInt(from)); };
		strMethods["indexOf"] = function (needle, from) {
			var s = toJsStr(this);
			var target = typeof needle === "number" ? String.fromCharCode(needle & 0xffff) : toJsStr(needle);
			return arguments.length >= 2 && from !== undefined ? s.indexOf(target, toInt(from)) : s.indexOf(target);
		};
		strMethods["lastIndexOf(I)I"] = function (ch) { return toJsStr(this).lastIndexOf(String.fromCharCode(toCharCode(ch))); };
		strMethods["lastIndexOf(II)I"] = function (ch, from) { return toJsStr(this).lastIndexOf(String.fromCharCode(toCharCode(ch)), toInt(from)); };
		strMethods["lastIndexOf(Ljava/lang/String;)I"] = function (sub) { return toJsStr(this).lastIndexOf(toJsStr(sub)); };
		strMethods["lastIndexOf(Ljava/lang/String;I)I"] = function (sub, from) { return toJsStr(this).lastIndexOf(toJsStr(sub), toInt(from)); };
		strMethods["lastIndexOf"] = function (needle, from) {
			var s = toJsStr(this);
			var target = typeof needle === "number" ? String.fromCharCode(needle & 0xffff) : toJsStr(needle);
			return arguments.length >= 2 && from !== undefined ? s.lastIndexOf(target, toInt(from)) : s.lastIndexOf(target);
		};
		addOverloads(strMethods, function (seq) { return toJsStr(this).indexOf(toJsStr(seq)) >= 0; }, ["contains", "contains(Ljava/lang/CharSequence;)Z"]);
		addOverloads(strMethods, function (pref, off) {
			var s = toJsStr(this), p = toJsStr(pref);
			return arguments.length >= 2 && off !== undefined ? s.startsWith(p, toInt(off)) : s.startsWith(p);
		}, ["startsWith", "startsWith(Ljava/lang/String;)Z", "startsWith(Ljava/lang/String;I)Z"]);
		addOverloads(strMethods, function (suf) { return toJsStr(this).endsWith(toJsStr(suf)); }, ["endsWith", "endsWith(Ljava/lang/String;)Z"]);
		addOverloads(strMethods, function (other) {
			if (!isJavaString(other)) { return false; }
			return toJsStr(this) === toJsStr(other);
		}, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(strMethods, function (other) {
			if (other === null || other === undefined) { return false; }
			return toJsStr(this).toLowerCase() === toJsStr(other).toLowerCase();
		}, ["equalsIgnoreCase", "equalsIgnoreCase(Ljava/lang/String;)Z"]);
		addOverloads(strMethods, function (seq) {
			return seq !== null && seq !== undefined && toJsStr(this) === toJsStr(seq);
		}, ["contentEquals", "contentEquals(Ljava/lang/CharSequence;)Z"]);
		addOverloads(strMethods, function (other) {
			var a = toJsStr(this), b = toJsStr(other);
			var min = Math.min(a.length, b.length);
			for (var i = 0; i < min; i++) {
				var d = a.charCodeAt(i) - b.charCodeAt(i);
				if (d !== 0) { return d; }
			}
			return a.length - b.length;
		}, ["compareTo", "compareTo(Ljava/lang/String;)I", "compareTo(Ljava/lang/Object;)I"]);
		addOverloads(strMethods, function (other) {
			var a = toJsStr(this).toLowerCase(), b = toJsStr(other).toLowerCase();
			return a < b ? -1 : (a > b ? 1 : 0);
		}, ["compareToIgnoreCase", "compareToIgnoreCase(Ljava/lang/String;)I"]);
		addOverloads(strMethods, function () { return toJsStr(this).toLowerCase(); }, ["toLowerCase", "toLowerCase()Ljava/lang/String;", "toLowerCase(Ljava/util/Locale;)Ljava/lang/String;"]);
		addOverloads(strMethods, function () { return toJsStr(this).toUpperCase(); }, ["toUpperCase", "toUpperCase()Ljava/lang/String;", "toUpperCase(Ljava/util/Locale;)Ljava/lang/String;"]);
		addOverloads(strMethods, function () {
			var s = toJsStr(this), start = 0, end = s.length;
			while (start < end && s.charCodeAt(start) <= 0x20) { start++; }
			while (end > start && s.charCodeAt(end - 1) <= 0x20) { end--; }
			return s.substring(start, end);
		}, ["trim", "trim()Ljava/lang/String;"]);
		addOverloads(strMethods, function () { return toJsStr(this).trim(); }, ["strip", "strip()Ljava/lang/String;"]);
		addOverloads(strMethods, function () { return toJsStr(this).replace(/^\s+/, ""); }, ["stripLeading", "stripLeading()Ljava/lang/String;"]);
		addOverloads(strMethods, function () { return toJsStr(this).replace(/\s+$/, ""); }, ["stripTrailing", "stripTrailing()Ljava/lang/String;"]);
		strMethods["replace(CC)Ljava/lang/String;"] = function (oldC, newC) {
			return toJsStr(this).split(String.fromCharCode(toCharCode(oldC))).join(String.fromCharCode(toCharCode(newC)));
		};
		strMethods["replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;"] = function (target, repl) {
			return toJsStr(this).split(toJsStr(target)).join(toJsStr(repl));
		};
		strMethods["replace"] = function (a, b) {
			if (typeof a === "number" && typeof b === "number") {
				return strMethods["replace(CC)Ljava/lang/String;"].call(this, a, b);
			}
			return strMethods["replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;"].call(this, a, b);
		};
		addOverloads(strMethods, function (regex, repl) {
			return toJsStr(this).replace(new RegExp(toJsStr(regex), "g"), toJsStr(repl));
		}, ["replaceAll", "replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"]);
		addOverloads(strMethods, function (regex, repl) {
			return toJsStr(this).replace(new RegExp(toJsStr(regex)), toJsStr(repl));
		}, ["replaceFirst", "replaceFirst(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"]);
		addOverloads(strMethods, function (regex, limit) {
			var s = toJsStr(this);
			var re = new RegExp(toJsStr(regex), "g");
			var lim = arguments.length >= 2 && limit !== undefined ? toInt(limit) : 0;
			var parts;
			if (lim > 0) {
				parts = [];
				var lastIdx = 0;
				var m;
				while (parts.length < lim - 1 && (m = re.exec(s)) !== null) {
					parts.push(s.slice(lastIdx, m.index));
					lastIdx = m.index + m[0].length;
					if (m[0].length === 0) { re.lastIndex++; }
				}
				parts.push(s.slice(lastIdx));
			} else {
				parts = s.split(new RegExp(toJsStr(regex)));
				if (lim === 0) {
					while (parts.length > 0 && parts[parts.length - 1] === "") { parts.pop(); }
				}
			}
			return makeObjectArray(rt, "Ljava/lang/String;", parts);
		}, ["split", "split(Ljava/lang/String;)[Ljava/lang/String;", "split(Ljava/lang/String;I)[Ljava/lang/String;"]);
		addOverloads(strMethods, function (other) { return toJsStr(this) + toJsStr(other); }, ["concat", "concat(Ljava/lang/String;)Ljava/lang/String;"]);
		addOverloads(strMethods, function (count) { return toJsStr(this).repeat(Math.max(0, toInt(count))); }, ["repeat", "repeat(I)Ljava/lang/String;"]);
		addOverloads(strMethods, function (args) { return javaStringFormat(toJsStr(this), args); }, ["formatted", "formatted([Ljava/lang/Object;)Ljava/lang/String;"]);
		addOverloads(strMethods, function () {
			var s = toJsStr(this);
			var out = new Uint16Array(s.length);
			for (var i = 0; i < s.length; i++) { out[i] = s.charCodeAt(i); }
			return makeCharArray(rt, out);
		}, ["toCharArray", "toCharArray()[C"]);
		addOverloads(strMethods, function () {
			var u8 = VFS.encodeUtf8(toJsStr(this));
			var i8 = new Int8Array(u8.length);
			for (var i = 0; i < u8.length; i++) { i8[i] = (u8[i] << 24) >> 24; }
			return makeByteArray(rt, i8);
		}, ["getBytes", "getBytes()[B", "getBytes(Ljava/nio/charset/Charset;)[B", "getBytes(Ljava/lang/String;)[B"]);
		addOverloads(strMethods, function (regex) {
			return new RegExp("^(?:" + toJsStr(regex) + ")$").test(toJsStr(this));
		}, ["matches", "matches(Ljava/lang/String;)Z"]);
		addOverloads(strMethods, function () { return toJsStr(this); }, ["intern", "intern()Ljava/lang/String;", "toString", "toString()Ljava/lang/String;"]);
		addOverloads(strMethods, function () { return javaStringHash(toJsStr(this)); }, ["hashCode", "hashCode()I"]);

		// Static methods on String
		strStatics["format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"] = function (fmt, args) {
			return javaStringFormat(fmt, args);
		};
		strStatics["format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"] = function (_loc, fmt, args) {
			return javaStringFormat(fmt, args);
		};
		strStatics["format"] = function (a, b, c) {
			if (isJavaString(a)) { return javaStringFormat(a, b); }
			return javaStringFormat(b, c);
		};
		strStatics["valueOf(Ljava/lang/Object;)Ljava/lang/String;"] = function (v) { return v === null || v === undefined ? "null" : javaToString(rt, v); };
		strStatics["valueOf(Z)Ljava/lang/String;"] = function (v) { return toBool(v) ? "true" : "false"; };
		strStatics["valueOf(C)Ljava/lang/String;"] = function (v) { return String.fromCharCode(toCharCode(v)); };
		strStatics["valueOf(I)Ljava/lang/String;"] = function (v) { return String(toInt(v)); };
		strStatics["valueOf(J)Ljava/lang/String;"] = function (v) { observeLongArg(ctx, v); return typeof v === "bigint" ? v.toString() : String(Math.trunc(toNum(v))); };
		strStatics["valueOf(F)Ljava/lang/String;"] = function (v) { return String(toNum(v)); };
		strStatics["valueOf(D)Ljava/lang/String;"] = function (v) { return String(toNum(v)); };
		strStatics["valueOf([C)Ljava/lang/String;"] = function (chars) {
			var a = unwrapArray(chars) || [], out = "";
			for (var i = 0; i < a.length; i++) { out += String.fromCharCode(a[i] & 0xffff); }
			return out;
		};
		strStatics["valueOf([CII)Ljava/lang/String;"] = function (chars, off, count) {
			var a = unwrapArray(chars) || [], o = toInt(off), n = toInt(count), out = "";
			for (var i = 0; i < n && (o + i) < a.length; i++) { out += String.fromCharCode(a[o + i] & 0xffff); }
			return out;
		};
		strStatics["valueOf"] = function (v) { return v === null || v === undefined ? "null" : javaToString(rt, v); };
		strStatics["copyValueOf([C)Ljava/lang/String;"] = strStatics["valueOf([C)Ljava/lang/String;"];
		strStatics["copyValueOf([CII)Ljava/lang/String;"] = strStatics["valueOf([CII)Ljava/lang/String;"];
		addOverloads(strStatics, function (delim, elems) {
			var d = toJsStr(delim);
			var parts = [];
			if (elems && (Array.isArray(elems) || Array.isArray(elems.data))) {
				var arr = unwrapArray(elems);
				for (var i = 0; i < arr.length; i++) { parts.push(toJsStr(arr[i])); }
			} else if (elems && elems._arr) {
				for (var j = 0; j < elems._arr.length; j++) { parts.push(toJsStr(elems._arr[j])); }
			} else if (elems && typeof elems.iterator === "function") {
				var it = elems.iterator();
				while (it.hasNext()) { parts.push(toJsStr(it.next())); }
			}
			return parts.join(d);
		}, ["join", "join(Ljava/lang/CharSequence;[Ljava/lang/CharSequence;)Ljava/lang/String;", "join(Ljava/lang/CharSequence;Ljava/lang/Iterable;)Ljava/lang/String;"]);

		var ciComparator = ctx.makeObj("java/util/Comparator", {
			compare: function (a, b) {
				var sa = toJsStr(a).toLowerCase(), sb = toJsStr(b).toLowerCase();
				return sa < sb ? -1 : (sa > sb ? 1 : 0);
			}
		});
		ctx.regClass("java/lang/String", {
			interfaces: ["java/io/Serializable", "java/lang/Comparable", "java/lang/CharSequence"],
			statics: { CASE_INSENSITIVE_ORDER: ciComparator },
			methods: strMethods,
			staticMethods: strStatics
		});

		// ---- java/lang/StringBuilder & java/lang/StringBuffer ----
		function makeStringBuilderClass(clsName) {
			var retDesc = "L" + clsName + ";";
			var m = {};
			m["<init>()V"] = function () { this._buf = ""; };
			m["<init>(I)V"] = function () { this._buf = ""; };
			m["<init>(Ljava/lang/String;)V"] = function (s) { this._buf = s === null || s === undefined ? "" : toJsStr(s); };
			m["<init>(Ljava/lang/CharSequence;)V"] = function (s) { this._buf = s === null || s === undefined ? "" : toJsStr(s); };
			m["<init>"] = function (a) {
				if (arguments.length === 0 || typeof a === "number") { this._buf = ""; }
				else { this._buf = a === null || a === undefined ? "" : toJsStr(a); }
			};
			function bufOf(self) { return typeof self._buf === "string" ? self._buf : (self._buf = ""); }
			m["append(Ljava/lang/String;)" + retDesc] = function (s) { this._buf = bufOf(this) + (s === null || s === undefined ? "null" : toJsStr(s)); return this; };
			m["append(Ljava/lang/Object;)" + retDesc] = function (o) { this._buf = bufOf(this) + (o === null || o === undefined ? "null" : javaToString(rt, o)); return this; };
			m["append(Ljava/lang/CharSequence;)" + retDesc] = function (s) { this._buf = bufOf(this) + (s === null || s === undefined ? "null" : toJsStr(s)); return this; };
			m["append(Ljava/lang/CharSequence;II)" + retDesc] = function (s, st, en) {
				var str = s === null || s === undefined ? "null" : toJsStr(s);
				this._buf = bufOf(this) + str.substring(toInt(st), toInt(en));
				return this;
			};
			m["append(Ljava/lang/StringBuffer;)" + retDesc] = m["append(Ljava/lang/CharSequence;)" + retDesc];
			m["append([C)" + retDesc] = function (chars) {
				var a = unwrapArray(chars) || [], s = "";
				for (var i = 0; i < a.length; i++) { s += String.fromCharCode(a[i] & 0xffff); }
				this._buf = bufOf(this) + s;
				return this;
			};
			m["append([CII)" + retDesc] = function (chars, off, len) {
				var a = unwrapArray(chars) || [], o = toInt(off), n = toInt(len), s = "";
				for (var i = 0; i < n && (o + i) < a.length; i++) { s += String.fromCharCode(a[o + i] & 0xffff); }
				this._buf = bufOf(this) + s;
				return this;
			};
			m["append(Z)" + retDesc] = function (b) { this._buf = bufOf(this) + (toBool(b) ? "true" : "false"); return this; };
			m["append(C)" + retDesc] = function (c) { this._buf = bufOf(this) + String.fromCharCode(toCharCode(c)); return this; };
			m["append(I)" + retDesc] = function (n) { this._buf = bufOf(this) + String(toInt(n)); return this; };
			m["append(J)" + retDesc] = function (n) { observeLongArg(ctx, n); this._buf = bufOf(this) + (typeof n === "bigint" ? n.toString() : String(Math.trunc(toNum(n)))); return this; };
			m["append(F)" + retDesc] = function (n) { this._buf = bufOf(this) + String(toNum(n)); return this; };
			m["append(D)" + retDesc] = function (n) { this._buf = bufOf(this) + String(toNum(n)); return this; };
			m["append"] = function (v) {
				this._buf = bufOf(this) + (v === null || v === undefined ? "null" : javaToString(rt, v));
				return this;
			};
			addOverloads(m, function () { return bufOf(this); }, ["toString", "toString()Ljava/lang/String;"]);
			addOverloads(m, function () { return bufOf(this).length; }, ["length", "length()I"]);
			addOverloads(m, function () { return bufOf(this).length === 0; }, ["isEmpty", "isEmpty()Z"]);
			addOverloads(m, function () { return Math.max(16, bufOf(this).length); }, ["capacity", "capacity()I"]);
			addOverloads(m, function () {}, ["ensureCapacity", "ensureCapacity(I)V"]);
			addOverloads(m, function (newLen) {
				var b = bufOf(this), n = Math.max(0, toInt(newLen));
				this._buf = n < b.length ? b.slice(0, n) : b + "\0".repeat(n - b.length);
			}, ["setLength", "setLength(I)V"]);
			addOverloads(m, function (i) { return bufOf(this).charCodeAt(toInt(i)) & 0xffff; }, ["charAt", "charAt(I)C"]);
			addOverloads(m, function (i, c) {
				var b = bufOf(this), idx = toInt(i);
				this._buf = b.slice(0, idx) + String.fromCharCode(toCharCode(c)) + b.slice(idx + 1);
			}, ["setCharAt", "setCharAt(IC)V"]);
			addOverloads(m, function (start, end) {
				var b = bufOf(this);
				this._buf = b.slice(0, toInt(start)) + b.slice(toInt(end));
				return this;
			}, ["delete", "delete(II)" + retDesc]);
			addOverloads(m, function (idx) {
				var b = bufOf(this), i = toInt(idx);
				this._buf = b.slice(0, i) + b.slice(i + 1);
				return this;
			}, ["deleteCharAt", "deleteCharAt(I)" + retDesc]);
			function doInsert(self, off, str) {
				var b = bufOf(self), idx = Math.max(0, Math.min(b.length, toInt(off)));
				self._buf = b.slice(0, idx) + str + b.slice(idx);
				return self;
			}
			m["insert(IC)" + retDesc] = function (o, c) { return doInsert(this, o, String.fromCharCode(toCharCode(c))); };
			m["insert(IZ)" + retDesc] = function (o, z) { return doInsert(this, o, toBool(z) ? "true" : "false"); };
			m["insert(II)" + retDesc] = function (o, n) { return doInsert(this, o, String(toInt(n))); };
			m["insert(IJ)" + retDesc] = function (o, n) { observeLongArg(ctx, n); return doInsert(this, o, typeof n === "bigint" ? n.toString() : String(Math.trunc(toNum(n)))); };
			m["insert(IF)" + retDesc] = function (o, n) { return doInsert(this, o, String(toNum(n))); };
			m["insert(ID)" + retDesc] = function (o, n) { return doInsert(this, o, String(toNum(n))); };
			m["insert(ILjava/lang/String;)" + retDesc] = function (o, s) { return doInsert(this, o, s === null || s === undefined ? "null" : toJsStr(s)); };
			m["insert(ILjava/lang/Object;)" + retDesc] = function (o, x) { return doInsert(this, o, x === null || x === undefined ? "null" : javaToString(rt, x)); };
			m["insert(ILjava/lang/CharSequence;)" + retDesc] = m["insert(ILjava/lang/String;)" + retDesc];
			m["insert"] = function (o, x) { return doInsert(this, o, x === null || x === undefined ? "null" : javaToString(rt, x)); };
			addOverloads(m, function (start, end, str) {
				var b = bufOf(this);
				this._buf = b.slice(0, toInt(start)) + toJsStr(str) + b.slice(toInt(end));
				return this;
			}, ["replace", "replace(IILjava/lang/String;)" + retDesc]);
			addOverloads(m, function () {
				this._buf = bufOf(this).split("").reverse().join("");
				return this;
			}, ["reverse", "reverse()" + retDesc]);
			addOverloads(m, function (s, e) {
				var b = bufOf(this);
				return arguments.length >= 2 && e !== undefined ? b.substring(toInt(s), toInt(e)) : b.substring(toInt(s));
			}, ["substring", "substring(I)Ljava/lang/String;", "substring(II)Ljava/lang/String;"]);
			addOverloads(m, function (sub, from) {
				var b = bufOf(this);
				return arguments.length >= 2 && from !== undefined ? b.indexOf(toJsStr(sub), toInt(from)) : b.indexOf(toJsStr(sub));
			}, ["indexOf", "indexOf(Ljava/lang/String;)I", "indexOf(Ljava/lang/String;I)I"]);
			addOverloads(m, function (sub, from) {
				var b = bufOf(this);
				return arguments.length >= 2 && from !== undefined ? b.lastIndexOf(toJsStr(sub), toInt(from)) : b.lastIndexOf(toJsStr(sub));
			}, ["lastIndexOf", "lastIndexOf(Ljava/lang/String;)I", "lastIndexOf(Ljava/lang/String;I)I"]);
			ctx.regClass(clsName, {
				interfaces: ["java/lang/CharSequence", "java/lang/Appendable", "java/io/Serializable"],
				methods: m
			});
		}
		makeStringBuilderClass("java/lang/StringBuilder");
		makeStringBuilderClass("java/lang/StringBuffer");

		// ---- java/lang/Math & java/lang/StrictMath ----
		var mathStatics = {};
		var trig1 = ["sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
			"exp", "expm1", "log", "log10", "log1p", "sqrt", "cbrt", "floor", "ceil"];
		for (var ti = 0; ti < trig1.length; ti++) {
			(function (fnName) {
				var fn = function (x) { return Math[fnName](toNum(x)); };
				addOverloads(mathStatics, fn, [fnName, fnName + "(D)D"]);
			})(trig1[ti]);
		}
		addOverloads(mathStatics, function (y, x) { return Math.atan2(toNum(y), toNum(x)); }, ["atan2", "atan2(DD)D"]);
		addOverloads(mathStatics, function (a, b) { return Math.pow(toNum(a), toNum(b)); }, ["pow", "pow(DD)D"]);
		addOverloads(mathStatics, function (a, b) { return Math.hypot(toNum(a), toNum(b)); }, ["hypot", "hypot(DD)D"]);
		addOverloads(mathStatics, function (x) { return Math.rint ? Math.rint(toNum(x)) : Math.round(toNum(x)); }, ["rint", "rint(D)D"]);
		mathStatics["abs(I)I"] = function (x) { return Math.abs(toInt(x)) | 0; };
		mathStatics["abs(J)J"] = function (x) { observeLongArg(ctx, x); return typeof x === "bigint" ? (x < 0 ? -x : x) : makeLong(ctx, Math.abs(toNum(x))); };
		mathStatics["abs(F)F"] = function (x) { return Math.abs(toNum(x)); };
		mathStatics["abs(D)D"] = function (x) { return Math.abs(toNum(x)); };
		mathStatics["abs"] = function (x) { return typeof x === "bigint" ? (x < 0 ? -x : x) : Math.abs(toNum(x)); };
		mathStatics["min(II)I"] = function (a, b) { return Math.min(toInt(a), toInt(b)) | 0; };
		mathStatics["min(JJ)J"] = function (a, b) { observeLongArg(ctx, a); return typeof a === "bigint" && typeof b === "bigint" ? (a < b ? a : b) : makeLong(ctx, Math.min(toNum(a), toNum(b))); };
		mathStatics["min(FF)F"] = function (a, b) { return Math.min(toNum(a), toNum(b)); };
		mathStatics["min(DD)D"] = function (a, b) { return Math.min(toNum(a), toNum(b)); };
		mathStatics["min"] = function (a, b) { return typeof a === "bigint" && typeof b === "bigint" ? (a < b ? a : b) : Math.min(toNum(a), toNum(b)); };
		mathStatics["max(II)I"] = function (a, b) { return Math.max(toInt(a), toInt(b)) | 0; };
		mathStatics["max(JJ)J"] = function (a, b) { observeLongArg(ctx, a); return typeof a === "bigint" && typeof b === "bigint" ? (a > b ? a : b) : makeLong(ctx, Math.max(toNum(a), toNum(b))); };
		mathStatics["max(FF)F"] = function (a, b) { return Math.max(toNum(a), toNum(b)); };
		mathStatics["max(DD)D"] = function (a, b) { return Math.max(toNum(a), toNum(b)); };
		mathStatics["max"] = function (a, b) { return typeof a === "bigint" && typeof b === "bigint" ? (a > b ? a : b) : Math.max(toNum(a), toNum(b)); };
		mathStatics["round(F)I"] = function (x) { return Math.round(toNum(x)) | 0; };
		mathStatics["round(D)J"] = function (x) { return makeLong(ctx, Math.round(toNum(x))); };
		mathStatics["round"] = function (x) { return Math.round(toNum(x)); };
		mathStatics["clamp(III)I"] = function (v, lo, hi) { return Math.max(toInt(lo), Math.min(toInt(hi), toInt(v))) | 0; };
		mathStatics["clamp(JII)I"] = function (v, lo, hi) { return Math.max(toInt(lo), Math.min(toInt(hi), Math.trunc(toNum(v)))) | 0; };
		mathStatics["clamp(JJJ)J"] = function (v, lo, hi) { observeLongArg(ctx, v); return makeLong(ctx, Math.max(toNum(lo), Math.min(toNum(hi), toNum(v)))); };
		mathStatics["clamp(FFF)F"] = function (v, lo, hi) { return Math.max(toNum(lo), Math.min(toNum(hi), toNum(v))); };
		mathStatics["clamp(DDD)D"] = function (v, lo, hi) { return Math.max(toNum(lo), Math.min(toNum(hi), toNum(v))); };
		mathStatics["clamp"] = mathStatics["clamp(DDD)D"];
		addOverloads(mathStatics, function () { return Math.random(); }, ["random", "random()D"]);
		addOverloads(mathStatics, function (x) { var n = toNum(x); return n > 0 ? 1 : (n < 0 ? -1 : n); }, ["signum", "signum(D)D", "signum(F)F"]);
		addOverloads(mathStatics, function (mag, sign) { var m = Math.abs(toNum(mag)); return toNum(sign) < 0 ? -m : m; }, ["copySign", "copySign(DD)D", "copySign(FF)F"]);
		addOverloads(mathStatics, function (deg) { return toNum(deg) * Math.PI / 180.0; }, ["toRadians", "toRadians(D)D"]);
		addOverloads(mathStatics, function (rad) { return toNum(rad) * 180.0 / Math.PI; }, ["toDegrees", "toDegrees(D)D"]);
		addOverloads(mathStatics, function (x, y) { return Math.floor(toNum(x) / toNum(y)) | 0; }, ["floorDiv", "floorDiv(II)I"]);
		addOverloads(mathStatics, function (x, y) { var a = toInt(x), b = toInt(y); return ((a % b) + b) % b; }, ["floorMod", "floorMod(II)I"]);
		addOverloads(mathStatics, function (a, b) { return (toInt(a) + toInt(b)) | 0; }, ["addExact", "addExact(II)I"]);
		addOverloads(mathStatics, function (a, b) { return (toInt(a) - toInt(b)) | 0; }, ["subtractExact", "subtractExact(II)I"]);
		addOverloads(mathStatics, function (a, b) { return Math.imul(toInt(a), toInt(b)); }, ["multiplyExact", "multiplyExact(II)I"]);
		addOverloads(mathStatics, function (v) { return toInt(v); }, ["toIntExact", "toIntExact(J)I"]);
		var mathFields = { PI: Math.PI, E: Math.E, TAU: Math.PI * 2 };
		ctx.regClass("java/lang/Math", { statics: mathFields, staticMethods: mathStatics });
		ctx.regClass("java/lang/StrictMath", { statics: mathFields, staticMethods: mathStatics });

		// ---- java/lang/Number & Primitive Wrappers ----
		function numValOf(o) { return o && o._numValue !== undefined ? o._numValue : (o ? o.value : 0); }
		ctx.regClass("java/lang/Number", {
			interfaces: ["java/io/Serializable"],
			methods: {
				intValue: function () { return toInt(numValOf(this)); },
				"intValue()I": function () { return toInt(numValOf(this)); },
				longValue: function () { return makeLong(ctx, numValOf(this)); },
				"longValue()J": function () { return makeLong(ctx, numValOf(this)); },
				floatValue: function () { return Math.fround(toNum(numValOf(this))); },
				"floatValue()F": function () { return Math.fround(toNum(numValOf(this))); },
				doubleValue: function () { return toNum(numValOf(this)); },
				"doubleValue()D": function () { return toNum(numValOf(this)); },
				byteValue: function () { return (toInt(numValOf(this)) << 24) >> 24; },
				"byteValue()B": function () { return (toInt(numValOf(this)) << 24) >> 24; },
				shortValue: function () { return (toInt(numValOf(this)) << 16) >> 16; },
				"shortValue()S": function () { return (toInt(numValOf(this)) << 16) >> 16; }
			}
		});

		function makeNumWrapper(clsName, primDesc, parseFn, coerceFn, staticsExtra, methodsExtra) {
			var m = {
				"<init>": function (v) { this._numValue = coerceFn(isJavaString(v) ? parseFn(toJsStr(v)) : v); this.value = this._numValue; },
				toString: function () { var nv = numValOf(this); return typeof nv === "bigint" ? nv.toString() : String(nv); },
				"toString()Ljava/lang/String;": function () { var nv = numValOf(this); return typeof nv === "bigint" ? nv.toString() : String(nv); },
				equals: function (o) { return !!(o && getClassNameOf(o) === clsName && toNum(numValOf(o)) === toNum(numValOf(this))); },
				"equals(Ljava/lang/Object;)Z": function (o) { return !!(o && getClassNameOf(o) === clsName && toNum(numValOf(o)) === toNum(numValOf(this))); },
				hashCode: function () { return toInt(numValOf(this)); },
				"hashCode()I": function () { return toInt(numValOf(this)); },
				compareTo: function (o) { var a = toNum(numValOf(this)), b = toNum(o); return a < b ? -1 : (a > b ? 1 : 0); },
				"compareTo(Ljava/lang/Object;)I": function (o) { var a = toNum(numValOf(this)), b = toNum(o); return a < b ? -1 : (a > b ? 1 : 0); }
			};
			m["<init>(" + primDesc + ")V"] = function (v) { this._numValue = coerceFn(v); this.value = this._numValue; };
			m["<init>(Ljava/lang/String;)V"] = function (s) { this._numValue = coerceFn(parseFn(toJsStr(s))); this.value = this._numValue; };
			m["compareTo(L" + clsName + ";)I"] = m.compareTo;
			if (methodsExtra) { Object.assign(m, methodsExtra); }
			var sm = {};
			sm["valueOf(" + primDesc + ")L" + clsName + ";"] = function (v) {
				var cv = coerceFn(v);
				return ctx.makeObj(clsName, { _numValue: cv, value: cv });
			};
			sm["valueOf(Ljava/lang/String;)L" + clsName + ";"] = function (s) {
				var cv = coerceFn(parseFn(toJsStr(s)));
				return ctx.makeObj(clsName, { _numValue: cv, value: cv });
			};
			sm["valueOf"] = function (v) {
				var cv = coerceFn(isJavaString(v) ? parseFn(toJsStr(v)) : v);
				return ctx.makeObj(clsName, { _numValue: cv, value: cv });
			};
			sm["toString(" + primDesc + ")Ljava/lang/String;"] = function (v) {
				var cv = coerceFn(v);
				return typeof cv === "bigint" ? cv.toString() : String(cv);
			};
			sm["compare(" + primDesc + primDesc + ")I"] = function (a, b) {
				var na = toNum(a), nb = toNum(b);
				return na < nb ? -1 : (na > nb ? 1 : 0);
			};
			sm["compare"] = sm["compare(" + primDesc + primDesc + ")I"];
			if (staticsExtra && staticsExtra.methods) { Object.assign(sm, staticsExtra.methods); }
			ctx.regClass(clsName, {
				superName: "java/lang/Number",
				interfaces: ["java/lang/Comparable"],
				statics: (staticsExtra && staticsExtra.fields) || {},
				methods: m,
				staticMethods: sm
			});
		}

		// Integer
		makeNumWrapper("java/lang/Integer", "I", function (s, r) { return parseInt(s, r || 10) | 0; }, toInt, {
			fields: { MAX_VALUE: 2147483647, MIN_VALUE: -2147483648, SIZE: 32, BYTES: 4, TYPE: ctx.getClassObj("I") },
			methods: {
				parseInt: function (s, r) { return parseInt(toJsStr(s), r ? toInt(r) : 10) | 0; },
				"parseInt(Ljava/lang/String;)I": function (s) { return parseInt(toJsStr(s), 10) | 0; },
				"parseInt(Ljava/lang/String;I)I": function (s, r) { return parseInt(toJsStr(s), toInt(r)) | 0; },
				"parseUnsignedInt(Ljava/lang/String;)I": function (s) { return parseInt(toJsStr(s), 10) | 0; },
				"parseUnsignedInt(Ljava/lang/String;I)I": function (s, r) { return parseInt(toJsStr(s), toInt(r)) | 0; },
				"valueOf(Ljava/lang/String;I)Ljava/lang/Integer;": function (s, r) { var cv = parseInt(toJsStr(s), toInt(r)) | 0; return ctx.makeObj("java/lang/Integer", { _numValue: cv, value: cv }); },
				"toString(II)Ljava/lang/String;": function (n, r) { return toInt(n).toString(toInt(r)); },
				toHexString: function (n) { return (toInt(n) >>> 0).toString(16); },
				"toHexString(I)Ljava/lang/String;": function (n) { return (toInt(n) >>> 0).toString(16); },
				toBinaryString: function (n) { return (toInt(n) >>> 0).toString(2); },
				"toBinaryString(I)Ljava/lang/String;": function (n) { return (toInt(n) >>> 0).toString(2); },
				toOctalString: function (n) { return (toInt(n) >>> 0).toString(8); },
				"toOctalString(I)Ljava/lang/String;": function (n) { return (toInt(n) >>> 0).toString(8); },
				bitCount: function (n) { var v = toInt(n) >>> 0, c = 0; while (v) { v &= v - 1; c++; } return c; },
				"bitCount(I)I": function (n) { var v = toInt(n) >>> 0, c = 0; while (v) { v &= v - 1; c++; } return c; },
				numberOfLeadingZeros: function (n) { return Math.clz32(toInt(n)); },
				"numberOfLeadingZeros(I)I": function (n) { return Math.clz32(toInt(n)); },
				numberOfTrailingZeros: function (n) { var v = toInt(n); if (v === 0) { return 32; } return 31 - Math.clz32(v & -v); },
				"numberOfTrailingZeros(I)I": function (n) { var v = toInt(n); if (v === 0) { return 32; } return 31 - Math.clz32(v & -v); },
				signum: function (n) { var v = toInt(n); return v > 0 ? 1 : (v < 0 ? -1 : 0); },
				"signum(I)I": function (n) { var v = toInt(n); return v > 0 ? 1 : (v < 0 ? -1 : 0); },
				sum: function (a, b) { return (toInt(a) + toInt(b)) | 0; },
				max: function (a, b) { return Math.max(toInt(a), toInt(b)) | 0; },
				min: function (a, b) { return Math.min(toInt(a), toInt(b)) | 0; }
			}
		});

		// Long
		var longFields = { SIZE: 64, BYTES: 8, TYPE: ctx.getClassObj("J") };
		Object.defineProperty(longFields, "MAX_VALUE", { enumerable: true, get: function () { return ctx.longMode.useBigInt && typeof BigInt === "function" ? BigInt("9223372036854775807") : 9007199254740991; } });
		Object.defineProperty(longFields, "MIN_VALUE", { enumerable: true, get: function () { return ctx.longMode.useBigInt && typeof BigInt === "function" ? BigInt("-9223372036854775808") : -9007199254740991; } });
		makeNumWrapper("java/lang/Long", "J", function (s, r) {
			var str = toJsStr(s).trim();
			var rad = r ? toInt(r) : 10;
			if (ctx.longMode.useBigInt && typeof BigInt === "function" && rad === 10) { return BigInt(str); }
			return makeLong(ctx, parseInt(str, rad));
		}, function (v) { observeLongArg(ctx, v); return makeLong(ctx, v); }, {
			fields: longFields,
			methods: {
				parseLong: function (s, r) { return makeLong(ctx, parseInt(toJsStr(s), r ? toInt(r) : 10)); },
				"parseLong(Ljava/lang/String;)J": function (s) {
					var str = toJsStr(s).trim();
					if (ctx.longMode.useBigInt && typeof BigInt === "function") { return BigInt(str); }
					return makeLong(ctx, parseInt(str, 10));
				},
				"parseLong(Ljava/lang/String;I)J": function (s, r) { return makeLong(ctx, parseInt(toJsStr(s), toInt(r))); },
				toHexString: function (n) { return typeof n === "bigint" ? BigInt.asUintN(64, n).toString(16) : (Math.trunc(toNum(n)) >>> 0).toString(16); },
				"toHexString(J)Ljava/lang/String;": function (n) { return typeof n === "bigint" ? BigInt.asUintN(64, n).toString(16) : (Math.trunc(toNum(n)) >>> 0).toString(16); }
			}
		});

		// Float
		var f32Buf = new ArrayBuffer(4);
		var f32View = new Float32Array(f32Buf);
		var i32View = new Int32Array(f32Buf);
		makeNumWrapper("java/lang/Float", "F", parseFloat, function (v) { return Math.fround(toNum(v)); }, {
			fields: { MAX_VALUE: 3.4028235e38, MIN_VALUE: 1.4e-45, NaN: NaN, POSITIVE_INFINITY: Infinity, NEGATIVE_INFINITY: -Infinity, SIZE: 32, BYTES: 4, TYPE: ctx.getClassObj("F") },
			methods: {
				parseFloat: function (s) { return Math.fround(parseFloat(toJsStr(s))); },
				"parseFloat(Ljava/lang/String;)F": function (s) { return Math.fround(parseFloat(toJsStr(s))); },
				isNaN: function (v) { return isNaN(toNum(v)); },
				"isNaN(F)Z": function (v) { return isNaN(toNum(v)); },
				isInfinite: function (v) { var n = toNum(v); return n === Infinity || n === -Infinity; },
				"isInfinite(F)Z": function (v) { var n = toNum(v); return n === Infinity || n === -Infinity; },
				isFinite: function (v) { return isFinite(toNum(v)); },
				"isFinite(F)Z": function (v) { return isFinite(toNum(v)); },
				floatToIntBits: function (v) { f32View[0] = toNum(v); return i32View[0]; },
				"floatToIntBits(F)I": function (v) { f32View[0] = toNum(v); return i32View[0]; },
				floatToRawIntBits: function (v) { f32View[0] = toNum(v); return i32View[0]; },
				"floatToRawIntBits(F)I": function (v) { f32View[0] = toNum(v); return i32View[0]; },
				intBitsToFloat: function (i) { i32View[0] = toInt(i); return f32View[0]; },
				"intBitsToFloat(I)F": function (i) { i32View[0] = toInt(i); return f32View[0]; }
			}
		});

		// Double
		var f64Buf = new ArrayBuffer(8);
		var f64View = new Float64Array(f64Buf);
		var u32View = new Uint32Array(f64Buf);
		makeNumWrapper("java/lang/Double", "D", parseFloat, toNum, {
			fields: { MAX_VALUE: Number.MAX_VALUE, MIN_VALUE: Number.MIN_VALUE, NaN: NaN, POSITIVE_INFINITY: Infinity, NEGATIVE_INFINITY: -Infinity, SIZE: 64, BYTES: 8, TYPE: ctx.getClassObj("D") },
			methods: {
				parseDouble: function (s) { return parseFloat(toJsStr(s)); },
				"parseDouble(Ljava/lang/String;)D": function (s) { return parseFloat(toJsStr(s)); },
				isNaN: function (v) { return isNaN(toNum(v)); },
				"isNaN(D)Z": function (v) { return isNaN(toNum(v)); },
				isInfinite: function (v) { var n = toNum(v); return n === Infinity || n === -Infinity; },
				"isInfinite(D)Z": function (v) { var n = toNum(v); return n === Infinity || n === -Infinity; },
				isFinite: function (v) { return isFinite(toNum(v)); },
				"isFinite(D)Z": function (v) { return isFinite(toNum(v)); },
				doubleToLongBits: function (v) {
					f64View[0] = toNum(v);
					if (ctx.longMode.useBigInt && typeof BigInt === "function") {
						return (BigInt(u32View[1]) << BigInt(32)) | BigInt(u32View[0]);
					}
					return u32View[1] * 4294967296 + u32View[0];
				},
				"doubleToLongBits(D)J": function (v) { return ctx.nativeDefs["java/lang/Double"].staticMethods.doubleToLongBits(v); },
				longBitsToDouble: function (bits) {
					if (typeof bits === "bigint") {
						u32View[0] = Number(bits & BigInt(0xffffffff));
						u32View[1] = Number((bits >> BigInt(32)) & BigInt(0xffffffff));
					} else {
						u32View[0] = bits >>> 0;
						u32View[1] = Math.floor(bits / 4294967296) >>> 0;
					}
					return f64View[0];
				},
				"longBitsToDouble(J)D": function (b) { return ctx.nativeDefs["java/lang/Double"].staticMethods.longBitsToDouble(b); }
			}
		});

		// Byte & Short
		makeNumWrapper("java/lang/Byte", "B", function (s, r) { return ((parseInt(s, r || 10) << 24) >> 24); }, function (v) { return (toInt(v) << 24) >> 24; }, {
			fields: { MAX_VALUE: 127, MIN_VALUE: -128, SIZE: 8, BYTES: 1, TYPE: ctx.getClassObj("B") },
			methods: {
				parseByte: function (s, r) { return (parseInt(toJsStr(s), r ? toInt(r) : 10) << 24) >> 24; },
				"parseByte(Ljava/lang/String;)B": function (s) { return (parseInt(toJsStr(s), 10) << 24) >> 24; },
				toUnsignedInt: function (b) { return toInt(b) & 0xff; },
				"toUnsignedInt(B)I": function (b) { return toInt(b) & 0xff; }
			}
		});
		makeNumWrapper("java/lang/Short", "S", function (s, r) { return ((parseInt(s, r || 10) << 16) >> 16); }, function (v) { return (toInt(v) << 16) >> 16; }, {
			fields: { MAX_VALUE: 32767, MIN_VALUE: -32768, SIZE: 16, BYTES: 2, TYPE: ctx.getClassObj("S") },
			methods: {
				parseShort: function (s, r) { return (parseInt(toJsStr(s), r ? toInt(r) : 10) << 16) >> 16; },
				"parseShort(Ljava/lang/String;)S": function (s) { return (parseInt(toJsStr(s), 10) << 16) >> 16; },
				toUnsignedInt: function (s) { return toInt(s) & 0xffff; },
				"toUnsignedInt(S)I": function (s) { return toInt(s) & 0xffff; }
			}
		});

		// Boolean
		function boolValOf(o) { return toBool(o && o._boolValue !== undefined ? o._boolValue : (o ? o.value : false)); }
		var boolTrue = null, boolFalse = null;
		var boolMethods = {
			"<init>": function (v) { var b = isJavaString(v) ? toJsStr(v).toLowerCase() === "true" : toBool(v); this._boolValue = b; this.value = b ? 1 : 0; },
			"<init>(Z)V": function (v) { var b = toBool(v); this._boolValue = b; this.value = b ? 1 : 0; },
			"<init>(Ljava/lang/String;)V": function (s) { var b = toJsStr(s).toLowerCase() === "true"; this._boolValue = b; this.value = b ? 1 : 0; },
			booleanValue: function () { return boolValOf(this); },
			"booleanValue()Z": function () { return boolValOf(this); },
			toString: function () { return boolValOf(this) ? "true" : "false"; },
			"toString()Ljava/lang/String;": function () { return boolValOf(this) ? "true" : "false"; },
			equals: function (o) { return !!(o && getClassNameOf(o) === "java/lang/Boolean" && boolValOf(o) === boolValOf(this)); },
			"equals(Ljava/lang/Object;)Z": function (o) { return !!(o && getClassNameOf(o) === "java/lang/Boolean" && boolValOf(o) === boolValOf(this)); },
			hashCode: function () { return boolValOf(this) ? 1231 : 1237; },
			"hashCode()I": function () { return boolValOf(this) ? 1231 : 1237; },
			compareTo: function (o) { var a = boolValOf(this), b = boolValOf(o); return a === b ? 0 : (a ? 1 : -1); },
			"compareTo(Ljava/lang/Boolean;)I": function (o) { var a = boolValOf(this), b = boolValOf(o); return a === b ? 0 : (a ? 1 : -1); }
		};
		var boolStatics = {
			parseBoolean: function (s) { return s !== null && s !== undefined && toJsStr(s).toLowerCase() === "true"; },
			"parseBoolean(Ljava/lang/String;)Z": function (s) { return s !== null && s !== undefined && toJsStr(s).toLowerCase() === "true"; },
			"valueOf(Z)Ljava/lang/Boolean;": function (z) { return toBool(z) ? boolTrue : boolFalse; },
			"valueOf(Ljava/lang/String;)Ljava/lang/Boolean;": function (s) { return (s && toJsStr(s).toLowerCase() === "true") ? boolTrue : boolFalse; },
			valueOf: function (v) { return (isJavaString(v) ? toJsStr(v).toLowerCase() === "true" : toBool(v)) ? boolTrue : boolFalse; },
			"toString(Z)Ljava/lang/String;": function (z) { return toBool(z) ? "true" : "false"; },
			compare: function (a, b) { var x = toBool(a), y = toBool(b); return x === y ? 0 : (x ? 1 : -1); },
			"compare(ZZ)I": function (a, b) { var x = toBool(a), y = toBool(b); return x === y ? 0 : (x ? 1 : -1); },
			logicalAnd: function (a, b) { return toBool(a) && toBool(b); },
			logicalOr: function (a, b) { return toBool(a) || toBool(b); },
			logicalXor: function (a, b) { return toBool(a) !== toBool(b); }
		};
		var boolFieldMap = { TYPE: ctx.getClassObj("Z") };
		ctx.regClass("java/lang/Boolean", { interfaces: ["java/io/Serializable", "java/lang/Comparable"], statics: boolFieldMap, methods: boolMethods, staticMethods: boolStatics });
		boolTrue = ctx.makeObj("java/lang/Boolean", { _boolValue: true, value: 1 });
		boolFalse = ctx.makeObj("java/lang/Boolean", { _boolValue: false, value: 0 });
		ctx.setStaticField("java/lang/Boolean", "TRUE", boolTrue);
		ctx.setStaticField("java/lang/Boolean", "FALSE", boolFalse);

		// Character
		function charValOf(o) { return toCharCode(o && o._charValue !== undefined ? o._charValue : (o ? o.value : 0)); }
		var charMethods = {
			"<init>": function (c) { var v = toCharCode(c); this._charValue = v; this.value = v; },
			"<init>(C)V": function (c) { var v = toCharCode(c); this._charValue = v; this.value = v; },
			charValue: function () { return charValOf(this); },
			"charValue()C": function () { return charValOf(this); },
			toString: function () { return String.fromCharCode(charValOf(this)); },
			"toString()Ljava/lang/String;": function () { return String.fromCharCode(charValOf(this)); },
			equals: function (o) { return !!(o && getClassNameOf(o) === "java/lang/Character" && charValOf(o) === charValOf(this)); },
			"equals(Ljava/lang/Object;)Z": function (o) { return !!(o && getClassNameOf(o) === "java/lang/Character" && charValOf(o) === charValOf(this)); },
			hashCode: function () { return charValOf(this); },
			"hashCode()I": function () { return charValOf(this); }
		};
		var charStatics = {
			valueOf: function (c) { var v = toCharCode(c); return ctx.makeObj("java/lang/Character", { _charValue: v, value: v }); },
			"valueOf(C)Ljava/lang/Character;": function (c) { var v = toCharCode(c); return ctx.makeObj("java/lang/Character", { _charValue: v, value: v }); },
			"toString(C)Ljava/lang/String;": function (c) { return String.fromCharCode(toCharCode(c)); },
			isDigit: function (c) { var v = toCharCode(c); return v >= 48 && v <= 57; },
			"isDigit(C)Z": function (c) { var v = toCharCode(c); return v >= 48 && v <= 57; },
			isLetter: function (c) { return /[A-Za-z]/.test(String.fromCharCode(toCharCode(c))); },
			"isLetter(C)Z": function (c) { return /[A-Za-z]/.test(String.fromCharCode(toCharCode(c))); },
			isLetterOrDigit: function (c) { return /[A-Za-z0-9]/.test(String.fromCharCode(toCharCode(c))); },
			"isLetterOrDigit(C)Z": function (c) { return /[A-Za-z0-9]/.test(String.fromCharCode(toCharCode(c))); },
			isWhitespace: function (c) { return /\s/.test(String.fromCharCode(toCharCode(c))); },
			"isWhitespace(C)Z": function (c) { return /\s/.test(String.fromCharCode(toCharCode(c))); },
			isUpperCase: function (c) { var ch = String.fromCharCode(toCharCode(c)); return ch !== ch.toLowerCase() && ch === ch.toUpperCase(); },
			"isUpperCase(C)Z": function (c) { var ch = String.fromCharCode(toCharCode(c)); return ch !== ch.toLowerCase() && ch === ch.toUpperCase(); },
			isLowerCase: function (c) { var ch = String.fromCharCode(toCharCode(c)); return ch !== ch.toUpperCase() && ch === ch.toLowerCase(); },
			"isLowerCase(C)Z": function (c) { var ch = String.fromCharCode(toCharCode(c)); return ch !== ch.toUpperCase() && ch === ch.toLowerCase(); },
			toLowerCase: function (c) { return String.fromCharCode(toCharCode(c)).toLowerCase().charCodeAt(0); },
			"toLowerCase(C)C": function (c) { return String.fromCharCode(toCharCode(c)).toLowerCase().charCodeAt(0); },
			toUpperCase: function (c) { return String.fromCharCode(toCharCode(c)).toUpperCase().charCodeAt(0); },
			"toUpperCase(C)C": function (c) { return String.fromCharCode(toCharCode(c)).toUpperCase().charCodeAt(0); },
			compare: function (a, b) { return toCharCode(a) - toCharCode(b); },
			"compare(CC)I": function (a, b) { return toCharCode(a) - toCharCode(b); },
			digit: function (c, r) { var d = parseInt(String.fromCharCode(toCharCode(c)), toInt(r)); return isNaN(d) ? -1 : d; },
			"digit(CI)I": function (c, r) { var d = parseInt(String.fromCharCode(toCharCode(c)), toInt(r)); return isNaN(d) ? -1 : d; }
		};
		ctx.regClass("java/lang/Character", {
			interfaces: ["java/io/Serializable", "java/lang/Comparable"],
			statics: { MIN_VALUE: 0, MAX_VALUE: 65535, SIZE: 16, BYTES: 2, TYPE: ctx.getClassObj("C") },
			methods: charMethods,
			staticMethods: charStatics
		});

		// ---- java/lang/Enum & java/lang/Record ----
		var enumMethods = {};
		addOverloads(enumMethods, function (name, ord) { var s = toJsStr(name); this._enumName = s; this._name = s; this._ordinal = toInt(ord); }, ["<init>", "<init>(Ljava/lang/String;I)V"]);
		addOverloads(enumMethods, function () { return this._enumName || this._name || ""; }, ["name", "name()Ljava/lang/String;", "toString", "toString()Ljava/lang/String;"]);
		addOverloads(enumMethods, function () { return this._ordinal | 0; }, ["ordinal", "ordinal()I"]);
		addOverloads(enumMethods, function (other) { return (this._ordinal | 0) - (other ? (other._ordinal | 0) : 0); }, ["compareTo", "compareTo(Ljava/lang/Enum;)I", "compareTo(Ljava/lang/Object;)I"]);
		addOverloads(enumMethods, function () { return ctx.getClassObj(getClassNameOf(this)); }, ["getDeclaringClass", "getDeclaringClass()Ljava/lang/Class;"]);
		var enumStatics = {};
		addOverloads(enumStatics, function (enumCls, name) {
			var cn = enumCls && (enumCls._internalName || enumCls._name) ? (enumCls._internalName || enumCls._name) : toJsStr(enumCls);
			var target = toJsStr(name);
			var def = ctx.nativeDefs[cn];
			if (def && def.statics && def.statics[target]) { return def.statics[target]; }
			if (rt && rt.definedClasses && rt.definedClasses.has(cn)) {
				var dc = rt.definedClasses.get(cn);
				if (dc && dc.staticFields && dc.staticFields[target]) { return dc.staticFields[target]; }
			}
			if (rt && rt.classes) {
				var cObj = typeof rt.classes.get === "function" ? rt.classes.get(cn) : rt.classes[cn];
				if (cObj && cObj.statics && cObj.statics[target]) { return cObj.statics[target]; }
			}
			return null;
		}, ["valueOf", "valueOf(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;"]);
		ctx.regClass("java/lang/Enum", { interfaces: ["java/lang/Comparable", "java/io/Serializable"], methods: enumMethods, staticMethods: enumStatics });

		var recordMethods = {};
		addOverloads(recordMethods, function () {}, ["<init>", "<init>()V"]);
		addOverloads(recordMethods, function (other) {
			if (this === other) { return true; }
			if (!other || getClassNameOf(this) !== getClassNameOf(other)) { return false; }
			var keys = Object.keys(this);
			for (var i = 0; i < keys.length; i++) {
				var k = keys[i];
				if (k.charAt(0) === "_" || k.charAt(0) === "$") { continue; }
				if (!javaEquals(rt, this[k], other[k])) { return false; }
			}
			return true;
		}, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(recordMethods, function () {
			var h = 1, keys = Object.keys(this);
			for (var i = 0; i < keys.length; i++) {
				var k = keys[i];
				if (k.charAt(0) === "_" || k.charAt(0) === "$") { continue; }
				h = (Math.imul(h, 31) + javaHashCode(rt, this[k])) | 0;
			}
			return h;
		}, ["hashCode", "hashCode()I"]);
		addOverloads(recordMethods, function () {
			var simple = getClassNameOf(this).split("/").pop();
			var parts = [], keys = Object.keys(this);
			for (var i = 0; i < keys.length; i++) {
				var k = keys[i];
				if (k.charAt(0) === "_" || k.charAt(0) === "$") { continue; }
				parts.push(k + "=" + javaToString(rt, this[k]));
			}
			return simple + "[" + parts.join(", ") + "]";
		}, ["toString", "toString()Ljava/lang/String;"]);
		ctx.regClass("java/lang/Record", { methods: recordMethods });

		// ---- java/lang/Throwable & Exception Hierarchy ----
		var throwableMethods = {};
		throwableMethods["<init>()V"] = function () { this.detailMessage = null; this._message = null; this.cause = null; this._cause = null; this._stack = (new Error()).stack || ""; };
		throwableMethods["<init>(Ljava/lang/String;)V"] = function (m) { var s = m === null || m === undefined ? null : toJsStr(m); this.detailMessage = s; this._message = s; this.cause = null; this._cause = null; this._stack = (new Error(s || "")).stack || ""; };
		throwableMethods["<init>(Ljava/lang/String;Ljava/lang/Throwable;)V"] = function (m, c) { var s = m === null || m === undefined ? null : toJsStr(m); this.detailMessage = s; this._message = s; this.cause = c || null; this._cause = c || null; this._stack = (new Error(s || "")).stack || ""; };
		throwableMethods["<init>(Ljava/lang/Throwable;)V"] = function (c) { var s = c ? javaToString(rt, c) : null; this.detailMessage = s; this._message = s; this.cause = c || null; this._cause = c || null; this._stack = (new Error(s || "")).stack || ""; };
		throwableMethods["<init>"] = function (a, b) {
			if (arguments.length === 0) { throwableMethods["<init>()V"].call(this); }
			else if (arguments.length === 1) {
				if (isJavaString(a)) { throwableMethods["<init>(Ljava/lang/String;)V"].call(this, a); }
				else { throwableMethods["<init>(Ljava/lang/Throwable;)V"].call(this, a); }
			} else { throwableMethods["<init>(Ljava/lang/String;Ljava/lang/Throwable;)V"].call(this, a, b); }
		};
		addOverloads(throwableMethods, function () { var m = this.detailMessage !== undefined ? this.detailMessage : this._message; return m != null ? m : null; }, ["getMessage", "getMessage()Ljava/lang/String;", "getLocalizedMessage", "getLocalizedMessage()Ljava/lang/String;"]);
		addOverloads(throwableMethods, function () { return this.cause || this._cause || null; }, ["getCause", "getCause()Ljava/lang/Throwable;"]);
		addOverloads(throwableMethods, function (c) { this.cause = c; this._cause = c; return this; }, ["initCause", "initCause(Ljava/lang/Throwable;)Ljava/lang/Throwable;"]);
		addOverloads(throwableMethods, function () { return this; }, ["fillInStackTrace", "fillInStackTrace()Ljava/lang/Throwable;"]);
		addOverloads(throwableMethods, function () {
			var cls = getClassNameOf(this).replace(/\//g, ".");
			var m = this.detailMessage !== undefined ? this.detailMessage : this._message;
			return m != null ? (cls + ": " + m) : cls;
		}, ["toString", "toString()Ljava/lang/String;"]);
		addOverloads(throwableMethods, function (stream) {
			var msg = throwableMethods.toString.call(this);
			if (stream && typeof stream.println === "function") { stream.println(msg); }
			else { console.error("[jvm] " + msg + (this._stack ? "\n" + this._stack : "")); }
		}, ["printStackTrace", "printStackTrace()V", "printStackTrace(Ljava/io/PrintStream;)V", "printStackTrace(Ljava/io/PrintWriter;)V"]);
		ctx.regClass("java/lang/Throwable", { interfaces: ["java/io/Serializable"], methods: throwableMethods });

		var exceptionSubclasses = [
			["java/lang/Exception", "java/lang/Throwable"],
			["java/lang/Error", "java/lang/Throwable"],
			["java/lang/RuntimeException", "java/lang/Exception"],
			["java/lang/NullPointerException", "java/lang/RuntimeException"],
			["java/lang/IllegalArgumentException", "java/lang/RuntimeException"],
			["java/lang/IllegalStateException", "java/lang/RuntimeException"],
			["java/lang/IndexOutOfBoundsException", "java/lang/RuntimeException"],
			["java/lang/ArrayIndexOutOfBoundsException", "java/lang/IndexOutOfBoundsException"],
			["java/lang/StringIndexOutOfBoundsException", "java/lang/IndexOutOfBoundsException"],
			["java/lang/ClassCastException", "java/lang/RuntimeException"],
			["java/lang/UnsupportedOperationException", "java/lang/RuntimeException"],
			["java/lang/NumberFormatException", "java/lang/IllegalArgumentException"],
			["java/lang/ArithmeticException", "java/lang/RuntimeException"],
			["java/util/NoSuchElementException", "java/lang/RuntimeException"],
			["java/util/ConcurrentModificationException", "java/lang/RuntimeException"],
			["java/lang/ReflectiveOperationException", "java/lang/Exception"],
			["java/lang/ClassNotFoundException", "java/lang/ReflectiveOperationException"],
			["java/lang/NoSuchMethodException", "java/lang/ReflectiveOperationException"],
			["java/lang/NoSuchFieldException", "java/lang/ReflectiveOperationException"],
			["java/lang/InterruptedException", "java/lang/Exception"],
			["java/lang/AssertionError", "java/lang/Error"],
			["java/lang/StackOverflowError", "java/lang/Error"],
			["java/lang/OutOfMemoryError", "java/lang/Error"],
			["java/io/IOException", "java/lang/Exception"],
			["java/io/FileNotFoundException", "java/io/IOException"],
			["java/io/UncheckedIOException", "java/lang/RuntimeException"],
			["java/nio/file/NoSuchFileException", "java/io/IOException"],
			["com/google/gson/JsonParseException", "java/lang/RuntimeException"],
			["com/google/gson/JsonSyntaxException", "com/google/gson/JsonParseException"],
			["com/mojang/brigadier/exceptions/CommandSyntaxException", "java/lang/Exception"]
		];
		for (var ei = 0; ei < exceptionSubclasses.length; ei++) {
			ctx.regClass(exceptionSubclasses[ei][0], { superName: exceptionSubclasses[ei][1], methods: {
				"<init>": throwableMethods["<init>"],
				"<init>()V": throwableMethods["<init>()V"],
				"<init>(Ljava/lang/String;)V": throwableMethods["<init>(Ljava/lang/String;)V"],
				"<init>(Ljava/lang/String;Ljava/lang/Throwable;)V": throwableMethods["<init>(Ljava/lang/String;Ljava/lang/Throwable;)V"],
				"<init>(Ljava/lang/Throwable;)V": throwableMethods["<init>(Ljava/lang/Throwable;)V"]
			} });
		}
	}

	// =========================================================================
	// 7. IO, NIO Virtual Filesystem & System (java/io/*, java/nio/*, System)
	// =========================================================================

	function installJavaIoAndSystem(ctx) {
		var rt = ctx.rt;

		// ---- java/nio/charset/Charset & StandardCharsets ----
		var charsetMethods = {};
		addOverloads(charsetMethods, function () { return this._charsetName || "UTF-8"; }, ["name", "name()Ljava/lang/String;", "displayName", "displayName()Ljava/lang/String;", "toString", "toString()Ljava/lang/String;"]);
		addOverloads(charsetMethods, function (o) { return !!(o && o._charsetName === this._charsetName); }, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(charsetMethods, function () { return javaStringHash(this._charsetName || "UTF-8"); }, ["hashCode", "hashCode()I"]);
		var charsetStatics = {};
		addOverloads(charsetStatics, function (n) { return ctx.makeObj("java/nio/charset/Charset", { _charsetName: toJsStr(n).toUpperCase() }); }, ["forName", "forName(Ljava/lang/String;)Ljava/nio/charset/Charset;"]);
		addOverloads(charsetStatics, function () { return stdUtf8; }, ["defaultCharset", "defaultCharset()Ljava/nio/charset/Charset;"]);
		ctx.regClass("java/nio/charset/Charset", { methods: charsetMethods, staticMethods: charsetStatics });
		var stdUtf8 = ctx.makeObj("java/nio/charset/Charset", { _charsetName: "UTF-8" });
		var stdLatin1 = ctx.makeObj("java/nio/charset/Charset", { _charsetName: "ISO-8859-1" });
		var stdAscii = ctx.makeObj("java/nio/charset/Charset", { _charsetName: "US-ASCII" });
		var stdUtf16 = ctx.makeObj("java/nio/charset/Charset", { _charsetName: "UTF-16" });
		ctx.regClass("java/nio/charset/StandardCharsets", {
			statics: { UTF_8: stdUtf8, ISO_8859_1: stdLatin1, US_ASCII: stdAscii, UTF_16: stdUtf16 }
		});

		// ---- Base IO Classes ----
		ctx.regClass("java/io/InputStream", {
			interfaces: ["java/io/Closeable", "java/lang/AutoCloseable"],
			methods: {
				"<init>": function () {}, "<init>()V": function () {},
				read: function () { return -1; }, "read()I": function () { return -1; },
				available: function () { return 0; }, "available()I": function () { return 0; },
				close: function () {}, "close()V": function () {},
				readAllBytes: function () {
					var out = [], b;
					while ((b = this.read()) !== -1) { out.push((b << 24) >> 24); }
					return makeByteArray(rt, out);
				},
				"readAllBytes()[B": function () { return this.readAllBytes(); }
			}
		});
		ctx.regClass("java/io/OutputStream", {
			interfaces: ["java/io/Closeable", "java/io/Flushable", "java/lang/AutoCloseable"],
			methods: {
				"<init>": function () {}, "<init>()V": function () {},
				write: function () {}, "write(I)V": function () {},
				"write([B)V": function (arr) { var a = unwrapArray(arr) || []; for (var i = 0; i < a.length; i++) { this.write(a[i] & 0xff); } },
				"write([BII)V": function (arr, off, len) { var a = unwrapArray(arr) || [], o = toInt(off), n = toInt(len); for (var i = 0; i < n && (o + i) < a.length; i++) { this.write(a[o + i] & 0xff); } },
				flush: function () {}, "flush()V": function () {},
				close: function () {}, "close()V": function () {}
			}
		});
		ctx.regClass("java/io/Reader", {
			interfaces: ["java/io/Closeable", "java/lang/AutoCloseable"],
			methods: {
				"<init>": function () {}, "<init>()V": function () {},
				read: function (cbuf, off, len) {
					var s = this._text != null ? this._text : "";
					if (this._pos === undefined) { this._pos = 0; }
					if (arguments.length === 0) {
						if (this._pos >= s.length) { return -1; }
						return s.charCodeAt(this._pos++) & 0xffff;
					}
					var a = unwrapArray(cbuf);
					if (!a) { return -1; }
					var o = arguments.length >= 2 ? toInt(off) : 0;
					var n = arguments.length >= 3 ? toInt(len) : a.length - o;
					if (this._pos >= s.length) { return -1; }
					var count = 0;
					while (count < n && this._pos < s.length && (o + count) < a.length) {
						a[o + count] = s.charCodeAt(this._pos++) & 0xffff;
						count++;
					}
					return count > 0 ? count : -1;
				},
				"read()I": function () { return ctx.nativeDefs["java/io/Reader"].methods.read.call(this); },
				"read([C)I": function (c) { return ctx.nativeDefs["java/io/Reader"].methods.read.call(this, c); },
				"read([CII)I": function (c, o, l) { return ctx.nativeDefs["java/io/Reader"].methods.read.call(this, c, o, l); },
				ready: function () { return (this._pos | 0) < (this._text ? this._text.length : 0); },
				"ready()Z": function () { return (this._pos | 0) < (this._text ? this._text.length : 0); },
				close: function () {}, "close()V": function () {}
			}
		});
		ctx.regClass("java/io/Writer", {
			interfaces: ["java/io/Closeable", "java/io/Flushable", "java/lang/Appendable", "java/lang/AutoCloseable"],
			methods: {
				"<init>": function () { this._out = ""; }, "<init>()V": function () { this._out = ""; },
				write: function (a, b, c) {
					if (this._out === undefined) { this._out = ""; }
					if (typeof a === "number") { this._out += String.fromCharCode(a & 0xffff); }
					else if (isJavaString(a)) {
						var str = toJsStr(a);
						this._out += (arguments.length >= 3 ? str.substr(toInt(b), toInt(c)) : str);
					} else {
						var arr = unwrapArray(a) || [];
						var o = arguments.length >= 2 ? toInt(b) : 0;
						var n = arguments.length >= 3 ? toInt(c) : arr.length - o;
						for (var i = 0; i < n && (o + i) < arr.length; i++) { this._out += String.fromCharCode(arr[o + i] & 0xffff); }
					}
				},
				"write(I)V": function (c) { this.write(c); },
				"write(Ljava/lang/String;)V": function (s) { this.write(s); },
				"write(Ljava/lang/String;II)V": function (s, o, l) { this.write(s, o, l); },
				"write([C)V": function (c) { this.write(c); },
				"write([CII)V": function (c, o, l) { this.write(c, o, l); },
				append: function (s) { this.write(toJsStr(s)); return this; },
				"append(Ljava/lang/CharSequence;)Ljava/io/Writer;": function (s) { this.write(toJsStr(s)); return this; },
				"append(C)Ljava/io/Writer;": function (c) { this.write(toCharCode(c)); return this; },
				flush: function () { if (this._path) { VFS.writeString(this._path, this._out || "", !!this._append); } },
				"flush()V": function () { this.flush(); },
				close: function () { this.flush(); }, "close()V": function () { this.flush(); }
			}
		});

		// ---- PrintStream & PrintWriter ----
		var psMethods = {};
		psMethods["<init>"] = function (target) {
			this._lineBuf = "";
			this._target = target || null;
			this._sink = null;
		};
		psMethods["<init>(Ljava/io/OutputStream;)V"] = psMethods["<init>"];
		psMethods["<init>(Ljava/io/OutputStream;Z)V"] = psMethods["<init>"];
		function psEmit(self, text, newline) {
			var s = (self._lineBuf || "") + text;
			if (self._target && typeof self._target.write === "function") {
				var outBytes = VFS.encodeUtf8(text + (newline ? "\n" : ""));
				for (var i = 0; i < outBytes.length; i++) { self._target.write(outBytes[i]); }
				return;
			}
			if (newline) {
				self._lineBuf = "";
				if (self._sink === "err") { console.error(s); }
				else { console.log(s); }
			} else {
				var idx = s.lastIndexOf("\n");
				if (idx >= 0) {
					var chunk = s.slice(0, idx);
					self._lineBuf = s.slice(idx + 1);
					if (self._sink === "err") { console.error(chunk); }
					else { console.log(chunk); }
				} else {
					self._lineBuf = s;
				}
			}
		}
		psMethods["print(Ljava/lang/String;)V"] = function (s) { psEmit(this, s === null || s === undefined ? "null" : toJsStr(s), false); };
		psMethods["print(Ljava/lang/Object;)V"] = function (o) { psEmit(this, o === null || o === undefined ? "null" : javaToString(rt, o), false); };
		psMethods["print(Z)V"] = function (z) { psEmit(this, toBool(z) ? "true" : "false", false); };
		psMethods["print(C)V"] = function (c) { psEmit(this, String.fromCharCode(toCharCode(c)), false); };
		psMethods["print(I)V"] = function (n) { psEmit(this, String(toInt(n)), false); };
		psMethods["print(J)V"] = function (n) { psEmit(this, typeof n === "bigint" ? n.toString() : String(Math.trunc(toNum(n))), false); };
		psMethods["print(F)V"] = function (n) { psEmit(this, String(toNum(n)), false); };
		psMethods["print(D)V"] = function (n) { psEmit(this, String(toNum(n)), false); };
		psMethods["print"] = function (v) { psEmit(this, v === null || v === undefined ? "null" : javaToString(rt, v), false); };
		psMethods["println()V"] = function () { psEmit(this, "", true); };
		psMethods["println(Ljava/lang/String;)V"] = function (s) { psEmit(this, s === null || s === undefined ? "null" : toJsStr(s), true); };
		psMethods["println(Ljava/lang/Object;)V"] = function (o) { psEmit(this, o === null || o === undefined ? "null" : javaToString(rt, o), true); };
		psMethods["println(Z)V"] = function (z) { psEmit(this, toBool(z) ? "true" : "false", true); };
		psMethods["println(C)V"] = function (c) { psEmit(this, String.fromCharCode(toCharCode(c)), true); };
		psMethods["println(I)V"] = function (n) { psEmit(this, String(toInt(n)), true); };
		psMethods["println(J)V"] = function (n) { psEmit(this, typeof n === "bigint" ? n.toString() : String(Math.trunc(toNum(n))), true); };
		psMethods["println(F)V"] = function (n) { psEmit(this, String(toNum(n)), true); };
		psMethods["println(D)V"] = function (n) { psEmit(this, String(toNum(n)), true); };
		psMethods["println"] = function (v) { psEmit(this, arguments.length === 0 ? "" : (v === null || v === undefined ? "null" : javaToString(rt, v)), true); };
		addOverloads(psMethods, function (fmt, args) { psEmit(this, javaStringFormat(fmt, args), false); return this; },
			["printf", "printf(Ljava/lang/String;[Ljava/lang/Object;)Ljava/io/PrintStream;", "format", "format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/io/PrintStream;"]);
		addOverloads(psMethods, function () { if (this._lineBuf) { psEmit(this, "", true); } }, ["flush", "flush()V", "close", "close()V"]);
		ctx.regClass("java/io/PrintStream", { superName: "java/io/OutputStream", methods: psMethods });
		ctx.regClass("java/io/PrintWriter", { superName: "java/io/Writer", methods: psMethods });

		// ---- java/lang/System ----
		var sysOut = ctx.makeObj("java/io/PrintStream", { _sink: "out", _lineBuf: "" });
		var sysErr = ctx.makeObj("java/io/PrintStream", { _sink: "err", _lineBuf: "" });
		var sysStatics = {};
		addOverloads(sysStatics, function () { return makeLong(ctx, Date.now()); }, ["currentTimeMillis", "currentTimeMillis()J"]);
		addOverloads(sysStatics, function () {
			var ms = (typeof performance !== "undefined" && typeof performance.now === "function") ? performance.now() : Date.now();
			return makeLong(ctx, Math.floor(ms * 1000000));
		}, ["nanoTime", "nanoTime()J"]);
		addOverloads(sysStatics, function (src, srcPos, dest, destPos, length) {
			var s = unwrapArray(src), d = unwrapArray(dest);
			var sp = toInt(srcPos), dp = toInt(destPos), len = toInt(length);
			if (!s || !d || len <= 0) { return; }
			if (s === d && dp > sp && dp < sp + len) {
				for (var i = len - 1; i >= 0; i--) { d[dp + i] = s[sp + i]; }
			} else {
				for (var j = 0; j < len; j++) { d[dp + j] = s[sp + j]; }
			}
		}, ["arraycopy", "arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V"]);
		addOverloads(sysStatics, function (o) { return getIdentityHash(o); }, ["identityHashCode", "identityHashCode(Ljava/lang/Object;)I"]);
		addOverloads(sysStatics, function (key, defVal) {
			var k = toJsStr(key);
			if (Object.prototype.hasOwnProperty.call(ctx.sysProps, k)) { return ctx.sysProps[k]; }
			return arguments.length >= 2 && defVal !== undefined ? toJsStrOrNull(defVal) : null;
		}, ["getProperty", "getProperty(Ljava/lang/String;)Ljava/lang/String;", "getProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"]);
		addOverloads(sysStatics, function (key, val) {
			var k = toJsStr(key), prev = ctx.sysProps[k] || null;
			ctx.sysProps[k] = toJsStr(val);
			return prev;
		}, ["setProperty", "setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"]);
		addOverloads(sysStatics, function () { return null; }, ["getenv", "getenv(Ljava/lang/String;)Ljava/lang/String;"]);
		addOverloads(sysStatics, function () { return "\n"; }, ["lineSeparator", "lineSeparator()Ljava/lang/String;"]);
		addOverloads(sysStatics, function () {}, ["gc", "gc()V"]);
		addOverloads(sysStatics, function (code) { console.warn("[jvm] System.exit(" + toInt(code) + ") ignored in browser"); }, ["exit", "exit(I)V"]);
		ctx.regClass("java/lang/System", {
			statics: { out: sysOut, err: sysErr },
			staticMethods: sysStatics
		});

		// ---- java/io/File ----
		var fileMethods = {};
		fileMethods["<init>(Ljava/lang/String;)V"] = function (p) { this._path = VFS.normalize(toJsStr(p)); };
		fileMethods["<init>(Ljava/lang/String;Ljava/lang/String;)V"] = function (p, c) { this._path = VFS.normalize(toJsStr(p) + "/" + toJsStr(c)); };
		fileMethods["<init>(Ljava/io/File;Ljava/lang/String;)V"] = function (p, c) { this._path = VFS.normalize((p ? p._path : "") + "/" + toJsStr(c)); };
		fileMethods["<init>"] = function (a, b) {
			if (arguments.length >= 2 && b !== undefined) {
				var base = (a && typeof a === "object" && a._path) ? a._path : toJsStr(a);
				this._path = VFS.normalize(base + "/" + toJsStr(b));
			} else {
				this._path = VFS.normalize(toJsStr(a));
			}
		};
		addOverloads(fileMethods, function () { return this._path || "/"; }, ["getPath", "getPath()Ljava/lang/String;", "getAbsolutePath", "getAbsolutePath()Ljava/lang/String;", "getCanonicalPath", "getCanonicalPath()Ljava/lang/String;", "toString", "toString()Ljava/lang/String;"]);
		addOverloads(fileMethods, function () {
			var p = this._path || "/";
			var i = p.lastIndexOf("/");
			return i >= 0 ? p.slice(i + 1) : p;
		}, ["getName", "getName()Ljava/lang/String;"]);
		addOverloads(fileMethods, function () {
			var p = this._path || "/";
			if (p === "/") { return null; }
			var i = p.lastIndexOf("/");
			return i <= 0 ? "/" : p.slice(0, i);
		}, ["getParent", "getParent()Ljava/lang/String;"]);
		addOverloads(fileMethods, function () {
			var par = fileMethods.getParent.call(this);
			return par === null ? null : ctx.makeObj("java/io/File", { _path: par });
		}, ["getParentFile", "getParentFile()Ljava/io/File;"]);
		addOverloads(fileMethods, function () { return VFS.exists(this._path); }, ["exists", "exists()Z"]);
		addOverloads(fileMethods, function () { return VFS.isFile(this._path); }, ["isFile", "isFile()Z"]);
		addOverloads(fileMethods, function () { return VFS.isDirectory(this._path); }, ["isDirectory", "isDirectory()Z"]);
		addOverloads(fileMethods, function () { return VFS.mkdirs(this._path); }, ["mkdir", "mkdir()Z", "mkdirs", "mkdirs()Z"]);
		addOverloads(fileMethods, function () {
			if (VFS.exists(this._path)) { return false; }
			VFS.writeString(this._path, "", false);
			return true;
		}, ["createNewFile", "createNewFile()Z"]);
		addOverloads(fileMethods, function () { return VFS.deleteEntry(this._path); }, ["delete", "delete()Z"]);
		addOverloads(fileMethods, function () {
			var b = VFS.readBytes(this._path);
			return makeLong(ctx, b ? b.length : 0);
		}, ["length", "length()J"]);
		addOverloads(fileMethods, function () {
			if (!VFS.isDirectory(this._path)) { return null; }
			return makeObjectArray(rt, "Ljava/lang/String;", VFS.list(this._path));
		}, ["list", "list()[Ljava/lang/String;"]);
		addOverloads(fileMethods, function () {
			if (!VFS.isDirectory(this._path)) { return null; }
			var names = VFS.list(this._path), out = [];
			for (var i = 0; i < names.length; i++) {
				out.push(ctx.makeObj("java/io/File", { _path: VFS.normalize(this._path + "/" + names[i]) }));
			}
			return makeObjectArray(rt, "Ljava/io/File;", out);
		}, ["listFiles", "listFiles()[Ljava/io/File;"]);
		addOverloads(fileMethods, function () { return ctx.makeObj("java/nio/file/Path", { _path: this._path }); }, ["toPath", "toPath()Ljava/nio/file/Path;"]);
		addOverloads(fileMethods, function (o) { return !!(o && o._path === this._path); }, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(fileMethods, function () { return javaStringHash(this._path || ""); }, ["hashCode", "hashCode()I"]);
		ctx.regClass("java/io/File", {
			interfaces: ["java/io/Serializable", "java/lang/Comparable"],
			statics: { separator: "/", pathSeparator: ":", separatorChar: 47, pathSeparatorChar: 58 },
			methods: fileMethods
		});

		// ---- FileReader / FileWriter / BufferedReader / BufferedWriter / InputStreamReader / OutputStreamWriter / StringReader / StringWriter / ByteArray*Stream ----
		function pathOfArg(a) { return (a && typeof a === "object" && a._path) ? a._path : VFS.normalize(toJsStr(a)); }

		ctx.regClass("java/io/FileReader", {
			superName: "java/io/Reader",
			methods: {
				"<init>": function (f) { this._path = pathOfArg(f); this._text = VFS.readString(this._path) || ""; this._pos = 0; },
				"<init>(Ljava/lang/String;)V": function (p) { this._path = pathOfArg(p); this._text = VFS.readString(this._path) || ""; this._pos = 0; },
				"<init>(Ljava/io/File;)V": function (f) { this._path = pathOfArg(f); this._text = VFS.readString(this._path) || ""; this._pos = 0; },
				"<init>(Ljava/io/File;Ljava/nio/charset/Charset;)V": function (f) { this._path = pathOfArg(f); this._text = VFS.readString(this._path) || ""; this._pos = 0; }
			}
		});
		ctx.regClass("java/io/FileWriter", {
			superName: "java/io/Writer",
			methods: {
				"<init>": function (f, append) { this._path = pathOfArg(f); this._append = !!append; this._out = ""; },
				"<init>(Ljava/lang/String;)V": function (p) { this._path = pathOfArg(p); this._append = false; this._out = ""; },
				"<init>(Ljava/lang/String;Z)V": function (p, ap) { this._path = pathOfArg(p); this._append = toBool(ap); this._out = ""; },
				"<init>(Ljava/io/File;)V": function (f) { this._path = pathOfArg(f); this._append = false; this._out = ""; },
				"<init>(Ljava/io/File;Z)V": function (f, ap) { this._path = pathOfArg(f); this._append = toBool(ap); this._out = ""; }
			}
		});
		ctx.regClass("java/io/StringReader", {
			superName: "java/io/Reader",
			methods: {
				"<init>": function (s) { this._text = toJsStr(s); this._pos = 0; },
				"<init>(Ljava/lang/String;)V": function (s) { this._text = toJsStr(s); this._pos = 0; }
			}
		});
		ctx.regClass("java/io/StringWriter", {
			superName: "java/io/Writer",
			methods: {
				"<init>": function () { this._out = ""; },
				"<init>()V": function () { this._out = ""; },
				"<init>(I)V": function () { this._out = ""; },
				toString: function () { return this._out || ""; },
				"toString()Ljava/lang/String;": function () { return this._out || ""; }
			}
		});
		ctx.regClass("java/io/InputStreamReader", {
			superName: "java/io/Reader",
			methods: {
				"<init>": function (ins) {
					this._pos = 0;
					if (ins && ins._bytes) { this._text = VFS.decodeUtf8(ins._bytes); }
					else if (ins && typeof ins.readAllBytes === "function") {
						var b = unwrapArray(ins.readAllBytes()) || new Uint8Array(0);
						var u8 = new Uint8Array(b.length);
						for (var i = 0; i < b.length; i++) { u8[i] = b[i] & 0xff; }
						this._text = VFS.decodeUtf8(u8);
					} else { this._text = ""; }
				},
				"<init>(Ljava/io/InputStream;)V": function (ins) { ctx.nativeDefs["java/io/InputStreamReader"].methods["<init>"].call(this, ins); },
				"<init>(Ljava/io/InputStream;Ljava/nio/charset/Charset;)V": function (ins) { ctx.nativeDefs["java/io/InputStreamReader"].methods["<init>"].call(this, ins); },
				"<init>(Ljava/io/InputStream;Ljava/lang/String;)V": function (ins) { ctx.nativeDefs["java/io/InputStreamReader"].methods["<init>"].call(this, ins); }
			}
		});
		ctx.regClass("java/io/OutputStreamWriter", {
			superName: "java/io/Writer",
			methods: {
				"<init>": function (os) { this._os = os; this._out = ""; },
				"<init>(Ljava/io/OutputStream;)V": function (os) { this._os = os; this._out = ""; },
				"<init>(Ljava/io/OutputStream;Ljava/nio/charset/Charset;)V": function (os) { this._os = os; this._out = ""; },
				"<init>(Ljava/io/OutputStream;Ljava/lang/String;)V": function (os) { this._os = os; this._out = ""; },
				flush: function () {
					if (this._os && this._out) {
						var u8 = VFS.encodeUtf8(this._out);
						this._out = "";
						for (var i = 0; i < u8.length; i++) { this._os.write(u8[i]); }
						if (typeof this._os.flush === "function") { this._os.flush(); }
					}
				},
				"flush()V": function () { this.flush(); },
				close: function () { this.flush(); if (this._os && typeof this._os.close === "function") { this._os.close(); } },
				"close()V": function () { this.close(); }
			}
		});
		ctx.regClass("java/io/BufferedReader", {
			superName: "java/io/Reader",
			methods: {
				"<init>": function (r) {
					this._inner = r;
					this._text = r && typeof r._text === "string" ? r._text : "";
					this._pos = r && typeof r._pos === "number" ? r._pos : 0;
				},
				"<init>(Ljava/io/Reader;)V": function (r) { ctx.nativeDefs["java/io/BufferedReader"].methods["<init>"].call(this, r); },
				"<init>(Ljava/io/Reader;I)V": function (r) { ctx.nativeDefs["java/io/BufferedReader"].methods["<init>"].call(this, r); },
				readLine: function () {
					var s = this._text || "";
					var p = this._pos | 0;
					if (p >= s.length) { return null; }
					var nl = s.indexOf("\n", p);
					var line;
					if (nl < 0) {
						line = s.slice(p);
						this._pos = s.length;
					} else {
						line = s.slice(p, nl);
						this._pos = nl + 1;
					}
					if (line.endsWith("\r")) { line = line.slice(0, -1); }
					return line;
				},
				"readLine()Ljava/lang/String;": function () { return this.readLine(); },
				lines: function () {
					var arr = [], ln;
					while ((ln = this.readLine()) !== null) { arr.push(ln); }
					return ctx.makeObj("java/util/stream/Stream", { _arr: arr });
				},
				"lines()Ljava/util/stream/Stream;": function () { return this.lines(); },
				close: function () { if (this._inner && typeof this._inner.close === "function") { this._inner.close(); } },
				"close()V": function () { this.close(); }
			}
		});
		ctx.regClass("java/io/BufferedWriter", {
			superName: "java/io/Writer",
			methods: {
				"<init>": function (w) { this._inner = w; this._out = ""; this._path = w ? w._path : null; this._append = w ? w._append : false; },
				"<init>(Ljava/io/Writer;)V": function (w) { ctx.nativeDefs["java/io/BufferedWriter"].methods["<init>"].call(this, w); },
				"<init>(Ljava/io/Writer;I)V": function (w) { ctx.nativeDefs["java/io/BufferedWriter"].methods["<init>"].call(this, w); },
				newLine: function () { this.write("\n"); },
				"newLine()V": function () { this.write("\n"); },
				flush: function () {
					if (this._inner && typeof this._inner.write === "function" && this._out) {
						this._inner.write(this._out);
						this._out = "";
						if (typeof this._inner.flush === "function") { this._inner.flush(); }
					} else if (this._path) {
						VFS.writeString(this._path, this._out || "", !!this._append);
					}
				},
				"flush()V": function () { this.flush(); },
				close: function () { this.flush(); if (this._inner && typeof this._inner.close === "function") { this._inner.close(); } },
				"close()V": function () { this.close(); }
			}
		});
		ctx.regClass("java/io/ByteArrayInputStream", {
			superName: "java/io/InputStream",
			methods: {
				"<init>": function (buf, off, len) {
					var a = unwrapArray(buf) || new Uint8Array(0);
					var o = arguments.length >= 2 ? toInt(off) : 0;
					var n = arguments.length >= 3 ? toInt(len) : a.length - o;
					this._bytes = new Uint8Array(n);
					for (var i = 0; i < n && (o + i) < a.length; i++) { this._bytes[i] = a[o + i] & 0xff; }
					this._pos = 0;
				},
				"<init>([B)V": function (b) { ctx.nativeDefs["java/io/ByteArrayInputStream"].methods["<init>"].call(this, b); },
				"<init>([BII)V": function (b, o, l) { ctx.nativeDefs["java/io/ByteArrayInputStream"].methods["<init>"].call(this, b, o, l); },
				read: function (dst, off, len) {
					var b = this._bytes || new Uint8Array(0);
					if (arguments.length === 0) { return this._pos < b.length ? b[this._pos++] : -1; }
					var a = unwrapArray(dst);
					if (!a || this._pos >= b.length) { return -1; }
					var o = arguments.length >= 2 ? toInt(off) : 0;
					var n = arguments.length >= 3 ? toInt(len) : a.length - o;
					var count = 0;
					while (count < n && this._pos < b.length && (o + count) < a.length) {
						a[o + count] = (b[this._pos++] << 24) >> 24;
						count++;
					}
					return count > 0 ? count : -1;
				},
				"read()I": function () { return this.read(); },
				"read([B)I": function (d) { return this.read(d); },
				"read([BII)I": function (d, o, l) { return this.read(d, o, l); },
				available: function () { return Math.max(0, (this._bytes ? this._bytes.length : 0) - (this._pos | 0)); },
				"available()I": function () { return this.available(); }
			}
		});
		ctx.regClass("java/io/ByteArrayOutputStream", {
			superName: "java/io/OutputStream",
			methods: {
				"<init>": function () { this._chunks = []; },
				"<init>()V": function () { this._chunks = []; },
				"<init>(I)V": function () { this._chunks = []; },
				write: function (a, off, len) {
					if (!this._chunks) { this._chunks = []; }
					if (typeof a === "number") { this._chunks.push(a & 0xff); }
					else {
						var arr = unwrapArray(a) || [];
						var o = arguments.length >= 2 ? toInt(off) : 0;
						var n = arguments.length >= 3 ? toInt(len) : arr.length - o;
						for (var i = 0; i < n && (o + i) < arr.length; i++) { this._chunks.push(arr[o + i] & 0xff); }
					}
				},
				"write(I)V": function (b) { this.write(b); },
				"write([B)V": function (a) { this.write(a); },
				"write([BII)V": function (a, o, l) { this.write(a, o, l); },
				toByteArray: function () {
					var c = this._chunks || [], out = new Int8Array(c.length);
					for (var i = 0; i < c.length; i++) { out[i] = (c[i] << 24) >> 24; }
					return makeByteArray(rt, out);
				},
				"toByteArray()[B": function () { return this.toByteArray(); },
				size: function () { return (this._chunks || []).length; },
				"size()I": function () { return this.size(); },
				reset: function () { this._chunks = []; },
				"reset()V": function () { this._chunks = []; },
				toString: function () { return VFS.decodeUtf8(new Uint8Array(this._chunks || [])); },
				"toString()Ljava/lang/String;": function () { return this.toString(); },
				"toString(Ljava/nio/charset/Charset;)Ljava/lang/String;": function () { return this.toString(); }
			}
		});
		ctx.regClass("java/io/FileInputStream", {
			superName: "java/io/ByteArrayInputStream",
			methods: {
				"<init>": function (f) { this._path = pathOfArg(f); this._bytes = VFS.readBytes(this._path) || new Uint8Array(0); this._pos = 0; },
				"<init>(Ljava/lang/String;)V": function (f) { this._path = pathOfArg(f); this._bytes = VFS.readBytes(this._path) || new Uint8Array(0); this._pos = 0; },
				"<init>(Ljava/io/File;)V": function (f) { this._path = pathOfArg(f); this._bytes = VFS.readBytes(this._path) || new Uint8Array(0); this._pos = 0; }
			}
		});
		ctx.regClass("java/io/FileOutputStream", {
			superName: "java/io/ByteArrayOutputStream",
			methods: {
				"<init>": function (f, ap) { this._path = pathOfArg(f); this._append = toBool(ap); this._chunks = []; },
				"<init>(Ljava/lang/String;)V": function (f) { this._path = pathOfArg(f); this._append = false; this._chunks = []; },
				"<init>(Ljava/io/File;)V": function (f) { this._path = pathOfArg(f); this._append = false; this._chunks = []; },
				"<init>(Ljava/io/File;Z)V": function (f, ap) { this._path = pathOfArg(f); this._append = toBool(ap); this._chunks = []; },
				flush: function () { if (this._path) { VFS.writeBytes(this._path, new Uint8Array(this._chunks || []), !!this._append); } },
				"flush()V": function () { this.flush(); },
				close: function () { this.flush(); }, "close()V": function () { this.flush(); }
			}
		});

		// ---- java/nio/file/Path, Paths, Files ----
		function makePathObj(p) { return ctx.makeObj("java/nio/file/Path", { _path: VFS.normalize(p) }); }
		function joinPathArgs(first, more) {
			var acc = toJsStr(first);
			if (more) {
				var arr = unwrapArray(more);
				if (arr && typeof arr.length === "number") {
					for (var i = 0; i < arr.length; i++) { acc += "/" + toJsStr(arr[i]); }
				} else if (typeof more === "string") {
					acc += "/" + more;
				}
			}
			return makePathObj(acc);
		}
		var pathMethods = {};
		var pathStatics = {};
		addOverloads(pathStatics, joinPathArgs, ["of", "of(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"]);
		addOverloads(pathMethods, function (other) {
			var s = (other && other._path) ? other._path : toJsStr(other);
			if (s.indexOf("/") === 0) { return makePathObj(s); }
			return makePathObj((this._path || "/") + "/" + s);
		}, ["resolve", "resolve(Ljava/lang/String;)Ljava/nio/file/Path;", "resolve(Ljava/nio/file/Path;)Ljava/nio/file/Path;"]);
		addOverloads(pathMethods, function (other) {
			var p = this._path || "/";
			var i = p.lastIndexOf("/");
			var base = i <= 0 ? "/" : p.slice(0, i);
			var s = (other && other._path) ? other._path : toJsStr(other);
			return makePathObj(base + "/" + s);
		}, ["resolveSibling", "resolveSibling(Ljava/lang/String;)Ljava/nio/file/Path;", "resolveSibling(Ljava/nio/file/Path;)Ljava/nio/file/Path;"]);
		addOverloads(pathMethods, function () {
			var p = this._path || "/";
			if (p === "/") { return null; }
			var i = p.lastIndexOf("/");
			return makePathObj(i <= 0 ? "/" : p.slice(0, i));
		}, ["getParent", "getParent()Ljava/nio/file/Path;"]);
		addOverloads(pathMethods, function () {
			var p = this._path || "/";
			var i = p.lastIndexOf("/");
			return makePathObj(i >= 0 ? p.slice(i + 1) : p);
		}, ["getFileName", "getFileName()Ljava/nio/file/Path;"]);
		addOverloads(pathMethods, function () { return makePathObj(this._path || "/"); },
			["normalize", "normalize()Ljava/nio/file/Path;", "toAbsolutePath", "toAbsolutePath()Ljava/nio/file/Path;"]);
		addOverloads(pathMethods, function () { return ctx.makeObj("java/io/File", { _path: this._path || "/" }); }, ["toFile", "toFile()Ljava/io/File;"]);
		addOverloads(pathMethods, function () { return this._path || "/"; }, ["toString", "toString()Ljava/lang/String;"]);
		addOverloads(pathMethods, function (o) { return !!(o && o._path === this._path); }, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(pathMethods, function () { return javaStringHash(this._path || "/"); }, ["hashCode", "hashCode()I"]);
		ctx.regClass("java/nio/file/Path", { isInterface: true, methods: pathMethods, staticMethods: pathStatics });
		ctx.regClass("java/nio/file/Paths", {
			staticMethods: {
				get: joinPathArgs,
				"get(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;": joinPathArgs
			}
		});

		var filesStatics = {};
		function pStr(p) { return (p && p._path) ? p._path : VFS.normalize(toJsStr(p)); }
		addOverloads(filesStatics, function (p) { return VFS.exists(pStr(p)); }, ["exists", "exists(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"]);
		addOverloads(filesStatics, function (p) { return !VFS.exists(pStr(p)); }, ["notExists", "notExists(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"]);
		addOverloads(filesStatics, function (p) { return VFS.isDirectory(pStr(p)); }, ["isDirectory", "isDirectory(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"]);
		addOverloads(filesStatics, function (p) { return VFS.isFile(pStr(p)); }, ["isRegularFile", "isRegularFile(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"]);
		addOverloads(filesStatics, function (p) { VFS.mkdirs(pStr(p)); return p && p._path ? p : makePathObj(pStr(p)); },
			["createDirectories", "createDirectories(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;",
			 "createDirectory", "createDirectory(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;"]);
		addOverloads(filesStatics, function (p) {
			var s = VFS.readString(pStr(p));
			return s === null ? "" : s;
		}, ["readString", "readString(Ljava/nio/file/Path;)Ljava/lang/String;", "readString(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/lang/String;"]);
		addOverloads(filesStatics, function (p, content) {
			VFS.writeString(pStr(p), toJsStr(content), false);
			return p && p._path ? p : makePathObj(pStr(p));
		}, ["writeString",
			"writeString(Ljava/nio/file/Path;Ljava/lang/CharSequence;[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;",
			"writeString(Ljava/nio/file/Path;Ljava/lang/CharSequence;Ljava/nio/charset/Charset;[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;"]);
		addOverloads(filesStatics, function (p) {
			var u8 = VFS.readBytes(pStr(p)) || new Uint8Array(0);
			var i8 = new Int8Array(u8.length);
			for (var i = 0; i < u8.length; i++) { i8[i] = (u8[i] << 24) >> 24; }
			return makeByteArray(rt, i8);
		}, ["readAllBytes", "readAllBytes(Ljava/nio/file/Path;)[B"]);
		addOverloads(filesStatics, function (p) {
			var s = VFS.readString(pStr(p));
			var lines = s === null ? [] : s.split(/\r?\n/);
			return ctx.makeObj("java/util/ArrayList", { _arr: lines });
		}, ["readAllLines", "readAllLines(Ljava/nio/file/Path;)Ljava/util/List;", "readAllLines(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/util/List;"]);
		addOverloads(filesStatics, function (p, bytes) {
			VFS.writeBytes(pStr(p), bytes, false);
			return p && p._path ? p : makePathObj(pStr(p));
		}, ["write", "write(Ljava/nio/file/Path;[B[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;"]);
		addOverloads(filesStatics, function (p) { return VFS.deleteEntry(pStr(p)); },
			["deleteIfExists", "deleteIfExists(Ljava/nio/file/Path;)Z", "delete", "delete(Ljava/nio/file/Path;)V"]);
		addOverloads(filesStatics, function (p) {
			var u8 = VFS.readBytes(pStr(p));
			return makeLong(ctx, u8 ? u8.length : 0);
		}, ["size", "size(Ljava/nio/file/Path;)J"]);
		addOverloads(filesStatics, function (p) {
			var text = VFS.readString(pStr(p)) || "";
			return ctx.makeObj("java/io/BufferedReader", { _text: text, _pos: 0 });
		}, ["newBufferedReader", "newBufferedReader(Ljava/nio/file/Path;)Ljava/io/BufferedReader;", "newBufferedReader(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/io/BufferedReader;"]);
		addOverloads(filesStatics, function (p) {
			return ctx.makeObj("java/io/BufferedWriter", { _path: pStr(p), _out: "", _append: false });
		}, ["newBufferedWriter", "newBufferedWriter(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/BufferedWriter;", "newBufferedWriter(Ljava/nio/file/Path;Ljava/nio/charset/Charset;[Ljava/nio/file/OpenOption;)Ljava/io/BufferedWriter;"]);
		addOverloads(filesStatics, function (p) {
			return ctx.makeObj("java/io/FileInputStream", { _path: pStr(p), _bytes: VFS.readBytes(pStr(p)) || new Uint8Array(0), _pos: 0 });
		}, ["newInputStream", "newInputStream(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;"]);
		addOverloads(filesStatics, function (p) {
			return ctx.makeObj("java/io/FileOutputStream", { _path: pStr(p), _chunks: [], _append: false });
		}, ["newOutputStream", "newOutputStream(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/OutputStream;"]);
		addOverloads(filesStatics, function (p) {
			var names = VFS.list(pStr(p)), base = pStr(p), items = [];
			for (var i = 0; i < names.length; i++) { items.push(makePathObj(base + "/" + names[i])); }
			return ctx.makeObj("java/util/stream/Stream", { _arr: items });
		}, ["list", "list(Ljava/nio/file/Path;)Ljava/util/stream/Stream;"]);
		ctx.regClass("java/nio/file/Files", { staticMethods: filesStatics });
	}

	// =========================================================================
	// 8. Collections, Optional, Random, UUID, Properties, Atomics, Streams
	// =========================================================================

	function installJavaUtil(ctx) {
		var rt = ctx.rt;

		// ---- java/util/Objects ----
		var objectsStatics = {};
		addOverloads(objectsStatics, function (a, b) { return javaEquals(rt, a, b); }, ["equals", "equals(Ljava/lang/Object;Ljava/lang/Object;)Z", "deepEquals", "deepEquals(Ljava/lang/Object;Ljava/lang/Object;)Z"]);
		addOverloads(objectsStatics, function (o) { return javaHashCode(rt, o); }, ["hashCode", "hashCode(Ljava/lang/Object;)I"]);
		addOverloads(objectsStatics, function (arr) {
			var a = unwrapArray(arr) || [], h = 1;
			for (var i = 0; i < a.length; i++) { h = (Math.imul(h, 31) + javaHashCode(rt, a[i])) | 0; }
			return h;
		}, ["hash", "hash([Ljava/lang/Object;)I"]);
		addOverloads(objectsStatics, function (o, defStr) {
			if (o === null || o === undefined) { return arguments.length >= 2 && defStr !== undefined ? toJsStr(defStr) : "null"; }
			return javaToString(rt, o);
		}, ["toString", "toString(Ljava/lang/Object;)Ljava/lang/String;", "toString(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;"]);
		addOverloads(objectsStatics, function (o, msg) {
			if (o === null || o === undefined) {
				var err = new Error(msg ? toJsStr(msg) : "NullPointerException");
				err.__javaClass = "java/lang/NullPointerException";
				throw err;
			}
			return o;
		}, ["requireNonNull", "requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;", "requireNonNull(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", "requireNonNull(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/lang/Object;"]);
		addOverloads(objectsStatics, function (o, defVal) { return o !== null && o !== undefined ? o : defVal; }, ["requireNonNullElse", "requireNonNullElse(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"]);
		addOverloads(objectsStatics, function (o, sup) { return o !== null && o !== undefined ? o : invokeJavaCallback(rt, sup, "get", "()Ljava/lang/Object;", []); }, ["requireNonNullElseGet", "requireNonNullElseGet(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/lang/Object;"]);
		addOverloads(objectsStatics, function (o) { return o === null || o === undefined; }, ["isNull", "isNull(Ljava/lang/Object;)Z"]);
		addOverloads(objectsStatics, function (o) { return o !== null && o !== undefined; }, ["nonNull", "nonNull(Ljava/lang/Object;)Z"]);
		addOverloads(objectsStatics, function (a, b, cmp) {
			if (a === b) { return 0; }
			return toInt(invokeJavaCallback(rt, cmp, "compare", "(Ljava/lang/Object;Ljava/lang/Object;)I", [a, b]));
		}, ["compare", "compare(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/Comparator;)I"]);
		addOverloads(objectsStatics, function (i) { return toInt(i); }, ["checkIndex", "checkIndex(II)I"]);
		ctx.regClass("java/util/Objects", { staticMethods: objectsStatics });

		// ---- Iterator & Collection Interfaces ----
		function iterArr(it) { return it && (it._arr || it._items) ? (it._arr || it._items) : []; }
		var iterMethods = {
			hasNext: function () { return (this._idx | 0) < iterArr(this).length; },
			"hasNext()Z": function () { return (this._idx | 0) < iterArr(this).length; },
			next: function () { return iterArr(this)[this._idx++]; },
			"next()Ljava/lang/Object;": function () { return iterArr(this)[this._idx++]; },
			remove: function () { var a = this._arr || this._items; if (a && this._idx > 0) { a.splice(--this._idx, 1); } },
			"remove()V": function () { var a = this._arr || this._items; if (a && this._idx > 0) { a.splice(--this._idx, 1); } },
			forEachRemaining: function (consumer) {
				while (iterMethods.hasNext.call(this)) { invokeJavaCallback(rt, consumer, "accept", "(Ljava/lang/Object;)V", [iterMethods.next.call(this)]); }
			}
		};
		ctx.regClass("java/util/Iterator", { isInterface: true, methods: iterMethods });
		ctx.regClass("java/util/ListIterator", { isInterface: true, superName: "java/util/Iterator", methods: iterMethods });
		ctx.regClass("java/util/Collection", { isInterface: true, interfaces: ["java/lang/Iterable"] });
		ctx.regClass("java/util/Queue", { isInterface: true, interfaces: ["java/util/Collection"] });
		ctx.regClass("java/util/Deque", { isInterface: true, interfaces: ["java/util/Queue"] });
		ctx.regClass("java/util/Comparator", {
			isInterface: true,
			staticMethods: {
				naturalOrder: function () { return ctx.makeObj("java/util/Comparator", { compare: function (a, b) { return javaCompare(rt, a, b); } }); },
				reverseOrder: function () { return ctx.makeObj("java/util/Comparator", { compare: function (a, b) { return javaCompare(rt, b, a); } }); },
				comparing: function (keyExt) {
					return ctx.makeObj("java/util/Comparator", {
						compare: function (a, b) {
							return javaCompare(rt, invokeJavaCallback(rt, keyExt, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [a]),
								invokeJavaCallback(rt, keyExt, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [b]));
						}
					});
				},
				comparingInt: function (fn) {
					return ctx.makeObj("java/util/Comparator", {
						compare: function (a, b) {
							return toInt(invokeJavaCallback(rt, fn, "applyAsInt", "(Ljava/lang/Object;)I", [a])) -
								toInt(invokeJavaCallback(rt, fn, "applyAsInt", "(Ljava/lang/Object;)I", [b]));
						}
					});
				}
			}
		});

		function extractIterableItems(coll) {
			if (!coll) { return []; }
			if (Array.isArray(coll._items)) { return coll._items.slice(); }
			if (Array.isArray(coll._arr)) { return coll._arr.slice(); }
			if (Array.isArray(coll._entries)) { return coll._entries.slice(); }
			if (Array.isArray(coll)) { return coll.slice(); }
			var out = [];
			if (typeof coll.iterator === "function") {
				var it = coll.iterator();
				while (it && it.hasNext()) { out.push(it.next()); }
			}
			return out;
		}

		function makeListObj(arr, clsName) {
			var a = arr || [];
			return ctx.makeObj(clsName || "java/util/ArrayList", { _arr: a, _items: a });
		}

		// ---- ArrayList, LinkedList, List ----
		function arrOf(self) {
			if (Array.isArray(self._items)) { self._arr = self._items; return self._items; }
			if (Array.isArray(self._arr)) { self._items = self._arr; return self._arr; }
			self._arr = [];
			self._items = self._arr;
			return self._arr;
		}
		var listMethods = {};
		listMethods["<init>()V"] = function () { this._arr = []; this._items = this._arr; };
		listMethods["<init>(I)V"] = function () { this._arr = []; this._items = this._arr; };
		listMethods["<init>(Ljava/util/Collection;)V"] = function (c) { this._arr = extractIterableItems(c); this._items = this._arr; };
		listMethods["<init>"] = function (a) {
			this._arr = (a && typeof a === "object") ? extractIterableItems(a) : [];
			this._items = this._arr;
		};
		addOverloads(listMethods, function () { return arrOf(this).length; }, ["size", "size()I"]);
		addOverloads(listMethods, function () { return arrOf(this).length === 0; }, ["isEmpty", "isEmpty()Z"]);
		addOverloads(listMethods, function (o) {
			var a = arrOf(this);
			for (var i = 0; i < a.length; i++) { if (javaEquals(rt, a[i], o)) { return true; } }
			return false;
		}, ["contains", "contains(Ljava/lang/Object;)Z"]);
		addOverloads(listMethods, function (c) {
			var items = extractIterableItems(c);
			for (var i = 0; i < items.length; i++) { if (!listMethods.contains.call(this, items[i])) { return false; } }
			return true;
		}, ["containsAll", "containsAll(Ljava/util/Collection;)Z"]);
		addOverloads(listMethods, function (o) {
			var a = arrOf(this);
			for (var i = 0; i < a.length; i++) { if (javaEquals(rt, a[i], o)) { return i; } }
			return -1;
		}, ["indexOf", "indexOf(Ljava/lang/Object;)I"]);
		addOverloads(listMethods, function (o) {
			var a = arrOf(this);
			for (var i = a.length - 1; i >= 0; i--) { if (javaEquals(rt, a[i], o)) { return i; } }
			return -1;
		}, ["lastIndexOf", "lastIndexOf(Ljava/lang/Object;)I"]);
		addOverloads(listMethods, function (idx) { return arrOf(this)[toInt(idx)]; }, ["get", "get(I)Ljava/lang/Object;"]);
		addOverloads(listMethods, function (idx, val) {
			var a = arrOf(this), i = toInt(idx), old = a[i];
			a[i] = val;
			return old;
		}, ["set", "set(ILjava/lang/Object;)Ljava/lang/Object;"]);
		listMethods["add(Ljava/lang/Object;)Z"] = function (e) { arrOf(this).push(e); return true; };
		listMethods["add(ILjava/lang/Object;)V"] = function (idx, e) { arrOf(this).splice(toInt(idx), 0, e); };
		listMethods["add"] = function (a, b) {
			if (arguments.length >= 2) { arrOf(this).splice(toInt(a), 0, b); return; }
			arrOf(this).push(a);
			return true;
		};
		listMethods["addAll(Ljava/util/Collection;)Z"] = function (c) {
			var items = extractIterableItems(c), a = arrOf(this);
			for (var i = 0; i < items.length; i++) { a.push(items[i]); }
			return items.length > 0;
		};
		listMethods["addAll(ILjava/util/Collection;)Z"] = function (idx, c) {
			var items = extractIterableItems(c), a = arrOf(this);
			Array.prototype.splice.apply(a, [toInt(idx), 0].concat(items));
			return items.length > 0;
		};
		listMethods["addAll"] = function (a, b) {
			if (arguments.length >= 2) { return listMethods["addAll(ILjava/util/Collection;)Z"].call(this, a, b); }
			return listMethods["addAll(Ljava/util/Collection;)Z"].call(this, a);
		};
		listMethods["remove(I)Ljava/lang/Object;"] = function (idx) {
			var a = arrOf(this), i = toInt(idx);
			return a.splice(i, 1)[0];
		};
		listMethods["remove(Ljava/lang/Object;)Z"] = function (o) {
			var a = arrOf(this);
			for (var i = 0; i < a.length; i++) {
				if (javaEquals(rt, a[i], o)) { a.splice(i, 1); return true; }
			}
			return false;
		};
		listMethods["remove"] = function (x) {
			if (typeof x === "number") { return listMethods["remove(I)Ljava/lang/Object;"].call(this, x); }
			return listMethods["remove(Ljava/lang/Object;)Z"].call(this, x);
		};
		addOverloads(listMethods, function (c) {
			var items = extractIterableItems(c), a = arrOf(this), changed = false;
			for (var i = a.length - 1; i >= 0; i--) {
				for (var j = 0; j < items.length; j++) {
					if (javaEquals(rt, a[i], items[j])) { a.splice(i, 1); changed = true; break; }
				}
			}
			return changed;
		}, ["removeAll", "removeAll(Ljava/util/Collection;)Z"]);
		addOverloads(listMethods, function (c) {
			var items = extractIterableItems(c), a = arrOf(this), changed = false;
			for (var i = a.length - 1; i >= 0; i--) {
				var keep = false;
				for (var j = 0; j < items.length; j++) {
					if (javaEquals(rt, a[i], items[j])) { keep = true; break; }
				}
				if (!keep) { a.splice(i, 1); changed = true; }
			}
			return changed;
		}, ["retainAll", "retainAll(Ljava/util/Collection;)Z"]);
		addOverloads(listMethods, function (pred) {
			var a = arrOf(this), changed = false;
			for (var i = a.length - 1; i >= 0; i--) {
				if (toBool(invokeJavaCallback(rt, pred, "test", "(Ljava/lang/Object;)Z", [a[i]]))) {
					a.splice(i, 1); changed = true;
				}
			}
			return changed;
		}, ["removeIf", "removeIf(Ljava/util/function/Predicate;)Z"]);
		addOverloads(listMethods, function () { arrOf(this).length = 0; }, ["clear", "clear()V"]);
		addOverloads(listMethods, function () { return ctx.makeObj("java/util/Iterator", { _arr: arrOf(this), _idx: 0 }); },
			["iterator", "iterator()Ljava/util/Iterator;", "listIterator", "listIterator()Ljava/util/ListIterator;"]);
		addOverloads(listMethods, function (consumer) {
			var a = arrOf(this).slice();
			for (var i = 0; i < a.length; i++) { invokeJavaCallback(rt, consumer, "accept", "(Ljava/lang/Object;)V", [a[i]]); }
		}, ["forEach", "forEach(Ljava/util/function/Consumer;)V"]);
		addOverloads(listMethods, function (cmp) {
			var a = arrOf(this);
			if (!cmp) { a.sort(function (x, y) { return javaCompare(rt, x, y); }); }
			else { a.sort(function (x, y) { return toInt(invokeJavaCallback(rt, cmp, "compare", "(Ljava/lang/Object;Ljava/lang/Object;)I", [x, y])); }); }
		}, ["sort", "sort(Ljava/util/Comparator;)V"]);
		addOverloads(listMethods, function (op) {
			var a = arrOf(this);
			for (var i = 0; i < a.length; i++) { a[i] = invokeJavaCallback(rt, op, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [a[i]]); }
		}, ["replaceAll", "replaceAll(Ljava/util/function/UnaryOperator;)V"]);
		addOverloads(listMethods, function (from, to) { return makeListObj(arrOf(this).slice(toInt(from), toInt(to))); }, ["subList", "subList(II)Ljava/util/List;"]);
		addOverloads(listMethods, function (target) {
			var a = arrOf(this);
			if (target) {
				var dst = unwrapArray(target);
				if (dst && dst.length >= a.length) {
					for (var i = 0; i < a.length; i++) { dst[i] = a[i]; }
					if (dst.length > a.length) { dst[a.length] = null; }
					return target;
				}
			}
			return makeObjectArray(rt, "Ljava/lang/Object;", a.slice());
		}, ["toArray", "toArray()[Ljava/lang/Object;", "toArray([Ljava/lang/Object;)[Ljava/lang/Object;"]);
		addOverloads(listMethods, function () { return ctx.makeObj("java/util/stream/Stream", { _arr: arrOf(this).slice() }); }, ["stream", "stream()Ljava/util/stream/Stream;"]);
		addOverloads(listMethods, function () {
			var a = arrOf(this), parts = [];
			for (var i = 0; i < a.length; i++) { parts.push(javaToString(rt, a[i])); }
			return "[" + parts.join(", ") + "]";
		}, ["toString", "toString()Ljava/lang/String;"]);
		addOverloads(listMethods, function (o) {
			if (this === o) { return true; }
			if (!o || !Array.isArray(o._arr)) { return false; }
			var a = arrOf(this), b = o._arr;
			if (a.length !== b.length) { return false; }
			for (var i = 0; i < a.length; i++) { if (!javaEquals(rt, a[i], b[i])) { return false; } }
			return true;
		}, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(listMethods, function () {
			var a = arrOf(this), h = 1;
			for (var i = 0; i < a.length; i++) { h = (Math.imul(h, 31) + javaHashCode(rt, a[i])) | 0; }
			return h;
		}, ["hashCode", "hashCode()I"]);

		var listStatics = {};
		function listOfFn() {
			if (arguments.length === 1 && (Array.isArray(arguments[0]) || (arguments[0] && Array.isArray(arguments[0].data)))) {
				return makeListObj(unwrapArray(arguments[0]).slice());
			}
			var out = [];
			for (var i = 0; i < arguments.length; i++) { out.push(arguments[i]); }
			return makeListObj(out);
		}
		listStatics["of"] = listOfFn;
		listStatics["of([Ljava/lang/Object;)Ljava/util/List;"] = function (arr) { return makeListObj((unwrapArray(arr) || []).slice()); };
		var objSig = "";
		for (var loi = 0; loi <= 10; loi++) {
			listStatics["of(" + objSig + ")Ljava/util/List;"] = listOfFn;
			objSig += "Ljava/lang/Object;";
		}
		addOverloads(listStatics, function (c) { return makeListObj(extractIterableItems(c)); }, ["copyOf", "copyOf(Ljava/util/Collection;)Ljava/util/List;"]);

		ctx.regClass("java/util/List", { isInterface: true, interfaces: ["java/util/Collection"], methods: listMethods, staticMethods: listStatics });
		ctx.regClass("java/util/AbstractCollection", { interfaces: ["java/util/Collection"], methods: listMethods });
		ctx.regClass("java/util/AbstractList", { superName: "java/util/AbstractCollection", interfaces: ["java/util/List"], methods: listMethods });
		ctx.regClass("java/util/ArrayList", { superName: "java/util/AbstractList", interfaces: ["java/util/List"], methods: listMethods });
		ctx.regClass("java/util/Vector", { superName: "java/util/AbstractList", interfaces: ["java/util/List"], methods: listMethods });

		var dequeExtras = {
			addFirst: function (e) { arrOf(this).unshift(e); }, "addFirst(Ljava/lang/Object;)V": function (e) { arrOf(this).unshift(e); },
			addLast: function (e) { arrOf(this).push(e); }, "addLast(Ljava/lang/Object;)V": function (e) { arrOf(this).push(e); },
			offerFirst: function (e) { arrOf(this).unshift(e); return true; }, "offerFirst(Ljava/lang/Object;)Z": function (e) { arrOf(this).unshift(e); return true; },
			offerLast: function (e) { arrOf(this).push(e); return true; }, "offerLast(Ljava/lang/Object;)Z": function (e) { arrOf(this).push(e); return true; },
			offer: function (e) { arrOf(this).push(e); return true; }, "offer(Ljava/lang/Object;)Z": function (e) { arrOf(this).push(e); return true; },
			getFirst: function () { return arrOf(this)[0]; }, "getFirst()Ljava/lang/Object;": function () { return arrOf(this)[0]; },
			getLast: function () { var a = arrOf(this); return a[a.length - 1]; }, "getLast()Ljava/lang/Object;": function () { var a = arrOf(this); return a[a.length - 1]; },
			peek: function () { var a = arrOf(this); return a.length ? a[0] : null; }, "peek()Ljava/lang/Object;": function () { var a = arrOf(this); return a.length ? a[0] : null; },
			peekFirst: function () { var a = arrOf(this); return a.length ? a[0] : null; }, "peekFirst()Ljava/lang/Object;": function () { var a = arrOf(this); return a.length ? a[0] : null; },
			peekLast: function () { var a = arrOf(this); return a.length ? a[a.length - 1] : null; }, "peekLast()Ljava/lang/Object;": function () { var a = arrOf(this); return a.length ? a[a.length - 1] : null; },
			poll: function () { var a = arrOf(this); return a.length ? a.shift() : null; }, "poll()Ljava/lang/Object;": function () { var a = arrOf(this); return a.length ? a.shift() : null; },
			pollFirst: function () { var a = arrOf(this); return a.length ? a.shift() : null; }, "pollFirst()Ljava/lang/Object;": function () { var a = arrOf(this); return a.length ? a.shift() : null; },
			pollLast: function () { var a = arrOf(this); return a.length ? a.pop() : null; }, "pollLast()Ljava/lang/Object;": function () { var a = arrOf(this); return a.length ? a.pop() : null; },
			removeFirst: function () { return arrOf(this).shift(); }, "removeFirst()Ljava/lang/Object;": function () { return arrOf(this).shift(); },
			removeLast: function () { return arrOf(this).pop(); }, "removeLast()Ljava/lang/Object;": function () { return arrOf(this).pop(); },
			push: function (e) { arrOf(this).unshift(e); }, "push(Ljava/lang/Object;)V": function (e) { arrOf(this).unshift(e); },
			pop: function () { return arrOf(this).shift(); }, "pop()Ljava/lang/Object;": function () { return arrOf(this).shift(); }
		};
		ctx.regClass("java/util/LinkedList", { superName: "java/util/ArrayList", interfaces: ["java/util/List", "java/util/Deque"], methods: dequeExtras });
		ctx.regClass("java/util/ArrayDeque", { superName: "java/util/AbstractCollection", interfaces: ["java/util/Deque"], methods: Object.assign({}, listMethods, dequeExtras) });

		// ---- Map, Map$Entry, HashMap, LinkedHashMap, TreeMap, ConcurrentHashMap ----
		var entryMethods = {
			getKey: function () { return this._key; }, "getKey()Ljava/lang/Object;": function () { return this._key; },
			getValue: function () { return this._val; }, "getValue()Ljava/lang/Object;": function () { return this._val; },
			setValue: function (v) { var old = this._val; this._val = v; return old; }, "setValue(Ljava/lang/Object;)Ljava/lang/Object;": function (v) { var old = this._val; this._val = v; return old; },
			toString: function () { return javaToString(rt, this._key) + "=" + javaToString(rt, this._val); },
			"toString()Ljava/lang/String;": function () { return javaToString(rt, this._key) + "=" + javaToString(rt, this._val); },
			equals: function (o) { return !!(o && javaEquals(rt, this._key, o._key) && javaEquals(rt, this._val, o._val)); },
			hashCode: function () { return (javaHashCode(rt, this._key) ^ javaHashCode(rt, this._val)) | 0; }
		};
		ctx.regClass("java/util/Map$Entry", { isInterface: true, methods: entryMethods });
		function makeEntry(k, v) { return ctx.makeObj("java/util/Map$Entry", { _key: k, _val: v }); }

		function mapEntries(self) {
			if (!Array.isArray(self._entries)) { self._entries = []; }
			return self._entries;
		}
		function entryKey(e) { return Array.isArray(e) ? e[0] : e._key; }
		function entryVal(e) { return Array.isArray(e) ? e[1] : e._val; }
		function setEntryVal(e, v) { if (Array.isArray(e)) { e[1] = v; } else { e._val = v; } }
		function mapFindIdx(self, key) {
			var es = mapEntries(self);
			for (var i = 0; i < es.length; i++) {
				if (javaEquals(rt, entryKey(es[i]), key)) { return i; }
			}
			return -1;
		}
		var mapMethods = {};
		mapMethods["<init>()V"] = function () { this._entries = []; };
		mapMethods["<init>(I)V"] = function () { this._entries = []; };
		mapMethods["<init>(IF)V"] = function () { this._entries = []; };
		mapMethods["<init>(Ljava/util/Map;)V"] = function (m) {
			this._entries = [];
			if (m && Array.isArray(m._entries)) {
				for (var i = 0; i < m._entries.length; i++) {
					this._entries.push([entryKey(m._entries[i]), entryVal(m._entries[i])]);
				}
			}
		};
		mapMethods["<init>"] = function (a) {
			if (a && typeof a === "object" && Array.isArray(a._entries)) {
				mapMethods["<init>(Ljava/util/Map;)V"].call(this, a);
			} else { this._entries = []; }
		};
		addOverloads(mapMethods, function () { return mapEntries(this).length; }, ["size", "size()I"]);
		addOverloads(mapMethods, function () { return mapEntries(this).length === 0; }, ["isEmpty", "isEmpty()Z"]);
		addOverloads(mapMethods, function (k) { return mapFindIdx(this, k) >= 0; }, ["containsKey", "containsKey(Ljava/lang/Object;)Z"]);
		addOverloads(mapMethods, function (v) {
			var es = mapEntries(this);
			for (var i = 0; i < es.length; i++) { if (javaEquals(rt, entryVal(es[i]), v)) { return true; } }
			return false;
		}, ["containsValue", "containsValue(Ljava/lang/Object;)Z"]);
		addOverloads(mapMethods, function (k) {
			var idx = mapFindIdx(this, k);
			return idx >= 0 ? entryVal(mapEntries(this)[idx]) : null;
		}, ["get", "get(Ljava/lang/Object;)Ljava/lang/Object;"]);
		addOverloads(mapMethods, function (k, defVal) {
			var idx = mapFindIdx(this, k);
			return idx >= 0 ? entryVal(mapEntries(this)[idx]) : defVal;
		}, ["getOrDefault", "getOrDefault(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"]);
		addOverloads(mapMethods, function (k, v) {
			var es = mapEntries(this), idx = mapFindIdx(this, k);
			if (idx >= 0) { var old = entryVal(es[idx]); setEntryVal(es[idx], v); return old; }
			es.push([k, v]);
			return null;
		}, ["put", "put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"]);
		addOverloads(mapMethods, function (k, v) {
			var es = mapEntries(this), idx = mapFindIdx(this, k);
			if (idx >= 0 && entryVal(es[idx]) !== null && entryVal(es[idx]) !== undefined) { return entryVal(es[idx]); }
			if (idx >= 0) { setEntryVal(es[idx], v); return null; }
			es.push([k, v]);
			return null;
		}, ["putIfAbsent", "putIfAbsent(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"]);
		addOverloads(mapMethods, function (other) {
			if (other && Array.isArray(other._entries)) {
				for (var i = 0; i < other._entries.length; i++) {
					mapMethods.put.call(this, entryKey(other._entries[i]), entryVal(other._entries[i]));
				}
			}
		}, ["putAll", "putAll(Ljava/util/Map;)V"]);
		mapMethods["remove(Ljava/lang/Object;)Ljava/lang/Object;"] = function (k) {
			var es = mapEntries(this), idx = mapFindIdx(this, k);
			if (idx < 0) { return null; }
			return entryVal(es.splice(idx, 1)[0]);
		};
		mapMethods["remove(Ljava/lang/Object;Ljava/lang/Object;)Z"] = function (k, v) {
			var es = mapEntries(this), idx = mapFindIdx(this, k);
			if (idx >= 0 && javaEquals(rt, entryVal(es[idx]), v)) { es.splice(idx, 1); return true; }
			return false;
		};
		mapMethods["remove"] = function (k, v) {
			if (arguments.length >= 2) { return mapMethods["remove(Ljava/lang/Object;Ljava/lang/Object;)Z"].call(this, k, v); }
			return mapMethods["remove(Ljava/lang/Object;)Ljava/lang/Object;"].call(this, k);
		};
		addOverloads(mapMethods, function (k, v) {
			var es = mapEntries(this), idx = mapFindIdx(this, k);
			if (idx < 0) { return null; }
			var old = entryVal(es[idx]); setEntryVal(es[idx], v); return old;
		}, ["replace", "replace(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"]);
		addOverloads(mapMethods, function () { mapEntries(this).length = 0; }, ["clear", "clear()V"]);
		addOverloads(mapMethods, function () {
			var es = mapEntries(this), ks = [];
			for (var i = 0; i < es.length; i++) { ks.push(entryKey(es[i])); }
			return ctx.makeObj("java/util/LinkedHashSet", { _arr: ks, _items: ks });
		}, ["keySet", "keySet()Ljava/util/Set;"]);
		addOverloads(mapMethods, function () {
			var es = mapEntries(this), vs = [];
			for (var i = 0; i < es.length; i++) { vs.push(entryVal(es[i])); }
			return makeListObj(vs);
		}, ["values", "values()Ljava/util/Collection;"]);
		addOverloads(mapMethods, function () {
			var es = mapEntries(this), out = [];
			for (var i = 0; i < es.length; i++) { out.push(makeEntry(entryKey(es[i]), entryVal(es[i]))); }
			return ctx.makeObj("java/util/LinkedHashSet", { _arr: out, _items: out });
		}, ["entrySet", "entrySet()Ljava/util/Set;"]);
		addOverloads(mapMethods, function (biConsumer) {
			var es = mapEntries(this).slice();
			for (var i = 0; i < es.length; i++) {
				invokeJavaCallback(rt, biConsumer, "accept", "(Ljava/lang/Object;Ljava/lang/Object;)V", [entryKey(es[i]), entryVal(es[i])]);
			}
		}, ["forEach", "forEach(Ljava/util/function/BiConsumer;)V"]);
		addOverloads(mapMethods, function (k, fn) {
			var es = mapEntries(this), idx = mapFindIdx(this, k);
			if (idx >= 0 && entryVal(es[idx]) !== null && entryVal(es[idx]) !== undefined) { return entryVal(es[idx]); }
			var nv = invokeJavaCallback(rt, fn, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [k]);
			if (nv !== null && nv !== undefined) {
				if (idx >= 0) { setEntryVal(es[idx], nv); } else { es.push([k, nv]); }
			}
			return nv;
		}, ["computeIfAbsent", "computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"]);
		addOverloads(mapMethods, function (k, fn) {
			var es = mapEntries(this), idx = mapFindIdx(this, k);
			if (idx < 0 || entryVal(es[idx]) === null || entryVal(es[idx]) === undefined) { return null; }
			var nv = invokeJavaCallback(rt, fn, "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", [k, entryVal(es[idx])]);
			if (nv === null || nv === undefined) { es.splice(idx, 1); return null; }
			setEntryVal(es[idx], nv);
			return nv;
		}, ["computeIfPresent", "computeIfPresent(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"]);
		addOverloads(mapMethods, function (k, fn) {
			var es = mapEntries(this), idx = mapFindIdx(this, k);
			var old = idx >= 0 ? entryVal(es[idx]) : null;
			var nv = invokeJavaCallback(rt, fn, "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", [k, old]);
			if (nv === null || nv === undefined) { if (idx >= 0) { es.splice(idx, 1); } return null; }
			if (idx >= 0) { setEntryVal(es[idx], nv); } else { es.push([k, nv]); }
			return nv;
		}, ["compute", "compute(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"]);
		addOverloads(mapMethods, function (k, val, remapper) {
			var es = mapEntries(this), idx = mapFindIdx(this, k);
			var old = idx >= 0 ? entryVal(es[idx]) : null;
			var nv = (old === null || old === undefined) ? val : invokeJavaCallback(rt, remapper, "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", [old, val]);
			if (nv === null || nv === undefined) { if (idx >= 0) { es.splice(idx, 1); } return null; }
			if (idx >= 0) { setEntryVal(es[idx], nv); } else { es.push([k, nv]); }
			return nv;
		}, ["merge", "merge(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"]);
		addOverloads(mapMethods, function () {
			var es = mapEntries(this), parts = [];
			for (var i = 0; i < es.length; i++) { parts.push(javaToString(rt, entryKey(es[i])) + "=" + javaToString(rt, entryVal(es[i]))); }
			return "{" + parts.join(", ") + "}";
		}, ["toString", "toString()Ljava/lang/String;"]);
		addOverloads(mapMethods, function (o) {
			if (this === o) { return true; }
			if (!o || !Array.isArray(o._entries)) { return false; }
			var es = mapEntries(this);
			if (es.length !== o._entries.length) { return false; }
			for (var i = 0; i < es.length; i++) {
				var idx = mapFindIdx(o, entryKey(es[i]));
				if (idx < 0 || !javaEquals(rt, entryVal(es[i]), entryVal(o._entries[idx]))) { return false; }
			}
			return true;
		}, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(mapMethods, function () {
			var es = mapEntries(this), h = 0;
			for (var i = 0; i < es.length; i++) { h = (h + (javaHashCode(rt, entryKey(es[i])) ^ javaHashCode(rt, entryVal(es[i])))) | 0; }
			return h;
		}, ["hashCode", "hashCode()I"]);

		var mapStatics = {};
		function mapOfFn() {
			var m = ctx.makeObj("java/util/LinkedHashMap", { _entries: [] });
			for (var i = 0; i + 1 < arguments.length; i += 2) {
				mapMethods.put.call(m, arguments[i], arguments[i + 1]);
			}
			return m;
		}
		mapStatics["of"] = mapOfFn;
		var mSig = "";
		for (var moi = 0; moi <= 10; moi++) {
			mapStatics["of(" + mSig + ")Ljava/util/Map;"] = mapOfFn;
			mSig += "Ljava/lang/Object;Ljava/lang/Object;";
		}
		addOverloads(mapStatics, function (entries) {
			var m = ctx.makeObj("java/util/LinkedHashMap", { _entries: [] });
			var arr = unwrapArray(entries) || [];
			for (var i = 0; i < arr.length; i++) {
				if (arr[i]) { mapMethods.put.call(m, arr[i]._key, arr[i]._val); }
			}
			return m;
		}, ["ofEntries", "ofEntries([Ljava/util/Map$Entry;)Ljava/util/Map;"]);
		addOverloads(mapStatics, function (k, v) { return makeEntry(k, v); }, ["entry", "entry(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map$Entry;"]);
		addOverloads(mapStatics, function (other) {
			var m = ctx.makeObj("java/util/LinkedHashMap", { _entries: [] });
			mapMethods.putAll.call(m, other);
			return m;
		}, ["copyOf", "copyOf(Ljava/util/Map;)Ljava/util/Map;"]);

		ctx.regClass("java/util/Map", { isInterface: true, methods: mapMethods, staticMethods: mapStatics });
		ctx.regClass("java/util/AbstractMap", { interfaces: ["java/util/Map"], methods: mapMethods });
		ctx.regClass("java/util/HashMap", { superName: "java/util/AbstractMap", interfaces: ["java/util/Map"], methods: mapMethods });
		ctx.regClass("java/util/LinkedHashMap", { superName: "java/util/HashMap", interfaces: ["java/util/Map"], methods: mapMethods });
		ctx.regClass("java/util/TreeMap", { superName: "java/util/AbstractMap", interfaces: ["java/util/Map"], methods: mapMethods });
		ctx.regClass("java/util/concurrent/ConcurrentMap", { isInterface: true, interfaces: ["java/util/Map"] });
		ctx.regClass("java/util/concurrent/ConcurrentHashMap", { superName: "java/util/HashMap", interfaces: ["java/util/concurrent/ConcurrentMap"], methods: mapMethods });

		// ---- Set, HashSet, LinkedHashSet, TreeSet ----
		var setMethods = Object.assign({}, listMethods);
		setMethods["<init>(Ljava/util/Collection;)V"] = function (c) {
			this._arr = [];
			var items = extractIterableItems(c);
			for (var i = 0; i < items.length; i++) { setMethods["add(Ljava/lang/Object;)Z"].call(this, items[i]); }
		};
		setMethods["<init>"] = function (a) {
			this._arr = [];
			if (a && typeof a === "object") { setMethods["<init>(Ljava/util/Collection;)V"].call(this, a); }
		};
		setMethods["add(Ljava/lang/Object;)Z"] = function (e) {
			var a = arrOf(this);
			for (var i = 0; i < a.length; i++) { if (javaEquals(rt, a[i], e)) { return false; } }
			a.push(e);
			return true;
		};
		setMethods["add"] = setMethods["add(Ljava/lang/Object;)Z"];
		setMethods["addAll(Ljava/util/Collection;)Z"] = function (c) {
			var items = extractIterableItems(c), changed = false;
			for (var i = 0; i < items.length; i++) { if (setMethods["add(Ljava/lang/Object;)Z"].call(this, items[i])) { changed = true; } }
			return changed;
		};
		setMethods["addAll"] = setMethods["addAll(Ljava/util/Collection;)Z"];

		var setStatics = {};
		function setOfFn() {
			var s = ctx.makeObj("java/util/LinkedHashSet", { _arr: [] });
			var items = (arguments.length === 1 && (Array.isArray(arguments[0]) || (arguments[0] && Array.isArray(arguments[0].data))))
				? unwrapArray(arguments[0]) : arguments;
			for (var i = 0; i < items.length; i++) { setMethods["add(Ljava/lang/Object;)Z"].call(s, items[i]); }
			return s;
		}
		setStatics["of"] = setOfFn;
		setStatics["of([Ljava/lang/Object;)Ljava/util/Set;"] = function (arr) {
			var s = ctx.makeObj("java/util/LinkedHashSet", { _arr: [] });
			var a = unwrapArray(arr) || [];
			for (var i = 0; i < a.length; i++) { setMethods["add(Ljava/lang/Object;)Z"].call(s, a[i]); }
			return s;
		};
		var sSig = "";
		for (var soi = 0; soi <= 10; soi++) {
			setStatics["of(" + sSig + ")Ljava/util/Set;"] = setOfFn;
			sSig += "Ljava/lang/Object;";
		}
		addOverloads(setStatics, function (c) {
			var s = ctx.makeObj("java/util/LinkedHashSet", { _arr: [] });
			setMethods["addAll(Ljava/util/Collection;)Z"].call(s, c);
			return s;
		}, ["copyOf", "copyOf(Ljava/util/Collection;)Ljava/util/Set;"]);

		ctx.regClass("java/util/Set", { isInterface: true, interfaces: ["java/util/Collection"], methods: setMethods, staticMethods: setStatics });
		ctx.regClass("java/util/HashSet", { superName: "java/util/AbstractCollection", interfaces: ["java/util/Set"], methods: setMethods });
		ctx.regClass("java/util/LinkedHashSet", { superName: "java/util/HashSet", interfaces: ["java/util/Set"], methods: setMethods });
		ctx.regClass("java/util/TreeSet", { superName: "java/util/HashSet", interfaces: ["java/util/Set"], methods: setMethods });

		// ---- java/util/Arrays ----
		var arraysStatics = {};
		addOverloads(arraysStatics, function (arr) { return makeListObj((unwrapArray(arr) || []).slice()); },
			["asList", "asList([Ljava/lang/Object;)Ljava/util/List;"]);
		addOverloads(arraysStatics, function (arr, newLen) {
			var src = unwrapArray(arr) || [], n = Math.max(0, toInt(newLen));
			if (src instanceof Int8Array) { var b = new Int8Array(n); b.set(src.subarray(0, Math.min(src.length, n))); return makeByteArray(rt, b); }
			if (src instanceof Uint16Array) { var c = new Uint16Array(n); c.set(src.subarray(0, Math.min(src.length, n))); return makeCharArray(rt, c); }
			if (src instanceof Int32Array) { var ii = new Int32Array(n); ii.set(src.subarray(0, Math.min(src.length, n))); return makeTypedArray(rt, "[I", ii); }
			if (src instanceof Float32Array) { var ff = new Float32Array(n); ff.set(src.subarray(0, Math.min(src.length, n))); return makeTypedArray(rt, "[F", ff); }
			if (src instanceof Float64Array) { var dd = new Float64Array(n); dd.set(src.subarray(0, Math.min(src.length, n))); return makeTypedArray(rt, "[D", dd); }
			var out = new Array(n);
			for (var i = 0; i < n; i++) { out[i] = i < src.length ? src[i] : null; }
			return makeObjectArray(rt, "Ljava/lang/Object;", out);
		}, ["copyOf", "copyOf([Ljava/lang/Object;I)[Ljava/lang/Object;", "copyOf([BI)[B", "copyOf([CI)[C", "copyOf([II)[I", "copyOf([FI)[F", "copyOf([DI)[D", "copyOf([ZI)[Z", "copyOf([Ljava/lang/Object;ILjava/lang/Class;)[Ljava/lang/Object;"]);
		addOverloads(arraysStatics, function (arr, from, to) {
			var src = unwrapArray(arr) || [], f = toInt(from), t = toInt(to), out = [];
			for (var i = f; i < t; i++) { out.push(i < src.length ? src[i] : null); }
			return makeObjectArray(rt, "Ljava/lang/Object;", out);
		}, ["copyOfRange", "copyOfRange([Ljava/lang/Object;II)[Ljava/lang/Object;"]);
		addOverloads(arraysStatics, function (arr, a, b, c) {
			var dst = unwrapArray(arr);
			if (!dst) { return; }
			var start = arguments.length >= 4 ? toInt(a) : 0;
			var end = arguments.length >= 4 ? toInt(b) : dst.length;
			var val = arguments.length >= 4 ? c : a;
			for (var i = start; i < end && i < dst.length; i++) { dst[i] = val; }
		}, ["fill", "fill([Ljava/lang/Object;Ljava/lang/Object;)V", "fill([Ljava/lang/Object;IILjava/lang/Object;)V",
			"fill([II)V", "fill([IIII)V", "fill([BB)V", "fill([CC)V", "fill([DD)V", "fill([FF)V", "fill([ZZ)V"]);
		addOverloads(arraysStatics, function (arr, cmp) {
			var dst = unwrapArray(arr);
			if (!dst) { return; }
			if (ArrayBuffer.isView(dst)) { dst.sort(); return; }
			if (cmp && typeof cmp === "object" || typeof cmp === "function") {
				dst.sort(function (x, y) { return toInt(invokeJavaCallback(rt, cmp, "compare", "(Ljava/lang/Object;Ljava/lang/Object;)I", [x, y])); });
			} else {
				dst.sort(function (x, y) { return javaCompare(rt, x, y); });
			}
		}, ["sort", "sort([Ljava/lang/Object;)V", "sort([Ljava/lang/Object;Ljava/util/Comparator;)V", "sort([I)V", "sort([J)V", "sort([F)V", "sort([D)V", "sort([B)V", "sort([C)V"]);
		addOverloads(arraysStatics, function (a, b) {
			var x = unwrapArray(a), y = unwrapArray(b);
			if (x === y) { return true; }
			if (!x || !y || x.length !== y.length) { return false; }
			for (var i = 0; i < x.length; i++) { if (!javaEquals(rt, x[i], y[i])) { return false; } }
			return true;
		}, ["equals", "equals([Ljava/lang/Object;[Ljava/lang/Object;)Z", "equals([I[I)Z", "equals([B[B)Z", "equals([C[C)Z", "equals([D[D)Z", "equals([F[F)Z", "equals([Z[Z)Z"]);
		addOverloads(arraysStatics, function (arr) {
			var a = unwrapArray(arr);
			if (!a) { return "null"; }
			var parts = [];
			for (var i = 0; i < a.length; i++) { parts.push(javaToString(rt, a[i])); }
			return "[" + parts.join(", ") + "]";
		}, ["toString", "toString([Ljava/lang/Object;)Ljava/lang/String;", "toString([I)Ljava/lang/String;", "toString([B)Ljava/lang/String;", "toString([C)Ljava/lang/String;", "toString([D)Ljava/lang/String;", "toString([F)Ljava/lang/String;", "toString([Z)Ljava/lang/String;", "deepToString", "deepToString([Ljava/lang/Object;)Ljava/lang/String;"]);
		addOverloads(arraysStatics, function (arr) {
			var a = unwrapArray(arr) || [], out = [];
			for (var i = 0; i < a.length; i++) { out.push(a[i]); }
			return ctx.makeObj("java/util/stream/Stream", { _arr: out });
		}, ["stream", "stream([Ljava/lang/Object;)Ljava/util/stream/Stream;", "stream([I)Ljava/util/stream/IntStream;"]);
		ctx.regClass("java/util/Arrays", { staticMethods: arraysStatics });

		// ---- java/util/Collections ----
		var colStatics = {};
		addOverloads(colStatics, function () { return makeListObj([]); }, ["emptyList", "emptyList()Ljava/util/List;"]);
		addOverloads(colStatics, function () { return ctx.makeObj("java/util/HashMap", { _entries: [] }); }, ["emptyMap", "emptyMap()Ljava/util/Map;"]);
		addOverloads(colStatics, function () { return ctx.makeObj("java/util/HashSet", { _arr: [] }); }, ["emptySet", "emptySet()Ljava/util/Set;"]);
		addOverloads(colStatics, function () { return ctx.makeObj("java/util/Iterator", { _arr: [], _idx: 0 }); }, ["emptyIterator", "emptyIterator()Ljava/util/Iterator;"]);
		addOverloads(colStatics, function (e) { return makeListObj([e]); }, ["singletonList", "singletonList(Ljava/lang/Object;)Ljava/util/List;"]);
		addOverloads(colStatics, function (e) { return ctx.makeObj("java/util/HashSet", { _arr: [e] }); }, ["singleton", "singleton(Ljava/lang/Object;)Ljava/util/Set;"]);
		addOverloads(colStatics, function (k, v) { return ctx.makeObj("java/util/HashMap", { _entries: [makeEntry(k, v)] }); }, ["singletonMap", "singletonMap(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"]);
		addOverloads(colStatics, function (l) { return l; },
			["unmodifiableList", "unmodifiableList(Ljava/util/List;)Ljava/util/List;",
			 "unmodifiableMap", "unmodifiableMap(Ljava/util/Map;)Ljava/util/Map;",
			 "unmodifiableSet", "unmodifiableSet(Ljava/util/Set;)Ljava/util/Set;",
			 "unmodifiableCollection", "unmodifiableCollection(Ljava/util/Collection;)Ljava/util/Collection;",
			 "synchronizedList", "synchronizedList(Ljava/util/List;)Ljava/util/List;",
			 "synchronizedMap", "synchronizedMap(Ljava/util/Map;)Ljava/util/Map;",
			 "synchronizedSet", "synchronizedSet(Ljava/util/Set;)Ljava/util/Set;"]);
		addOverloads(colStatics, function (l, cmp) { listMethods.sort.call(l, cmp); }, ["sort", "sort(Ljava/util/List;)V", "sort(Ljava/util/List;Ljava/util/Comparator;)V"]);
		addOverloads(colStatics, function (l) { arrOf(l).reverse(); }, ["reverse", "reverse(Ljava/util/List;)V"]);
		addOverloads(colStatics, function (l) {
			var a = arrOf(l);
			for (var i = a.length - 1; i > 0; i--) {
				var j = Math.floor(Math.random() * (i + 1));
				var tmp = a[i]; a[i] = a[j]; a[j] = tmp;
			}
		}, ["shuffle", "shuffle(Ljava/util/List;)V", "shuffle(Ljava/util/List;Ljava/util/Random;)V"]);
		addOverloads(colStatics, function () { return ctx.nativeDefs["java/util/Comparator"].staticMethods.reverseOrder(); }, ["reverseOrder", "reverseOrder()Ljava/util/Comparator;"]);
		addOverloads(colStatics, function (c, elems) {
			var arr = unwrapArray(elems) || [], changed = false;
			for (var i = 0; i < arr.length; i++) { if (c.add(arr[i])) { changed = true; } }
			return changed;
		}, ["addAll", "addAll(Ljava/util/Collection;[Ljava/lang/Object;)Z"]);
		ctx.regClass("java/util/Collections", { staticMethods: colStatics });

		// ---- java/util/Optional ----
		var optEmpty = null;
		function optVal(o) { return o._value !== undefined ? o._value : o._val; }
		var optMethods = {};
		addOverloads(optMethods, function () { var v = optVal(this); return v !== null && v !== undefined; }, ["isPresent", "isPresent()Z"]);
		addOverloads(optMethods, function () { var v = optVal(this); return v === null || v === undefined; }, ["isEmpty", "isEmpty()Z"]);
		addOverloads(optMethods, function () { return optVal(this); }, ["get", "get()Ljava/lang/Object;", "orElseThrow", "orElseThrow()Ljava/lang/Object;"]);
		addOverloads(optMethods, function (other) { var v = optVal(this); return v !== null && v !== undefined ? v : other; }, ["orElse", "orElse(Ljava/lang/Object;)Ljava/lang/Object;"]);
		addOverloads(optMethods, function (sup) { var v = optVal(this); return v !== null && v !== undefined ? v : invokeJavaCallback(rt, sup, "get", "()Ljava/lang/Object;", []); }, ["orElseGet", "orElseGet(Ljava/util/function/Supplier;)Ljava/lang/Object;"]);
		addOverloads(optMethods, function (fn) {
			var v = optVal(this);
			if (v === null || v === undefined) { return optEmpty; }
			var res = invokeJavaCallback(rt, fn, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [v]);
			return res === null || res === undefined ? optEmpty : ctx.makeObj("java/util/Optional", { _value: res, _val: res });
		}, ["map", "map(Ljava/util/function/Function;)Ljava/util/Optional;"]);
		addOverloads(optMethods, function (fn) {
			var v = optVal(this);
			if (v === null || v === undefined) { return optEmpty; }
			return invokeJavaCallback(rt, fn, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [v]) || optEmpty;
		}, ["flatMap", "flatMap(Ljava/util/function/Function;)Ljava/util/Optional;"]);
		addOverloads(optMethods, function (pred) {
			var v = optVal(this);
			if (v === null || v === undefined) { return optEmpty; }
			return toBool(invokeJavaCallback(rt, pred, "test", "(Ljava/lang/Object;)Z", [v])) ? this : optEmpty;
		}, ["filter", "filter(Ljava/util/function/Predicate;)Ljava/util/Optional;"]);
		addOverloads(optMethods, function (cons) {
			var v = optVal(this);
			if (v !== null && v !== undefined) { invokeJavaCallback(rt, cons, "accept", "(Ljava/lang/Object;)V", [v]); }
		}, ["ifPresent", "ifPresent(Ljava/util/function/Consumer;)V"]);
		addOverloads(optMethods, function (cons, emptyAct) {
			var v = optVal(this);
			if (v !== null && v !== undefined) { invokeJavaCallback(rt, cons, "accept", "(Ljava/lang/Object;)V", [v]); }
			else { invokeJavaCallback(rt, emptyAct, "run", "()V", []); }
		}, ["ifPresentOrElse", "ifPresentOrElse(Ljava/util/function/Consumer;Ljava/lang/Runnable;)V"]);
		addOverloads(optMethods, function () { var v = optVal(this); return v !== null && v !== undefined ? ("Optional[" + javaToString(rt, v) + "]") : "Optional.empty"; }, ["toString", "toString()Ljava/lang/String;"]);
		addOverloads(optMethods, function (o) { return !!(o && getClassNameOf(o) === "java/util/Optional" && javaEquals(rt, optVal(this), optVal(o))); }, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(optMethods, function () { return javaHashCode(rt, optVal(this)); }, ["hashCode", "hashCode()I"]);
		var optStatics = {};
		addOverloads(optStatics, function () { return optEmpty; }, ["empty", "empty()Ljava/util/Optional;"]);
		addOverloads(optStatics, function (v) { return ctx.makeObj("java/util/Optional", { _value: v, _val: v }); }, ["of", "of(Ljava/lang/Object;)Ljava/util/Optional;"]);
		addOverloads(optStatics, function (v) { return v === null || v === undefined ? optEmpty : ctx.makeObj("java/util/Optional", { _value: v, _val: v }); }, ["ofNullable", "ofNullable(Ljava/lang/Object;)Ljava/util/Optional;"]);
		ctx.regClass("java/util/Optional", { methods: optMethods, staticMethods: optStatics });
		optEmpty = ctx.makeObj("java/util/Optional", { _value: null, _val: null });

		// ---- java/util/Random ----
		var randMethods = {};
		randMethods["<init>()V"] = function () { this._seed = (Date.now() ^ ((Math.random() * 0x7fffffff) | 0)) >>> 0; };
		randMethods["<init>(J)V"] = function (s) { this._seed = toInt(s) >>> 0; };
		randMethods["<init>"] = function (s) { this._seed = arguments.length ? (toInt(s) >>> 0) : ((Date.now() ^ ((Math.random() * 0x7fffffff) | 0)) >>> 0); };
		function nextU32(self) {
			var t = ((self._seed === undefined ? (self._seed = 0x12345678) : self._seed) + 0x6D2B79F5) >>> 0;
			self._seed = t;
			t = Math.imul(t ^ (t >>> 15), t | 1);
			t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
			return (t ^ (t >>> 14)) >>> 0;
		}
		randMethods["setSeed(J)V"] = function (s) { this._seed = toInt(s) >>> 0; };
		randMethods["setSeed"] = randMethods["setSeed(J)V"];
		randMethods["nextInt()I"] = function () { return nextU32(this) | 0; };
		randMethods["nextInt(I)I"] = function (bound) { var b = Math.max(1, toInt(bound)); return (nextU32(this) % b) | 0; };
		randMethods["nextInt(II)I"] = function (lo, hi) { var a = toInt(lo), b = Math.max(a + 1, toInt(hi)); return (a + (nextU32(this) % (b - a))) | 0; };
		randMethods["nextInt"] = function (a, b) {
			if (arguments.length >= 2) { return randMethods["nextInt(II)I"].call(this, a, b); }
			if (arguments.length === 1) { return randMethods["nextInt(I)I"].call(this, a); }
			return randMethods["nextInt()I"].call(this);
		};
		addOverloads(randMethods, function () { return makeLong(ctx, nextU32(this) * 4294967296 + nextU32(this)); }, ["nextLong", "nextLong()J"]);
		addOverloads(randMethods, function () { return Math.fround(nextU32(this) / 4294967296); }, ["nextFloat", "nextFloat()F"]);
		addOverloads(randMethods, function () { return nextU32(this) / 4294967296; }, ["nextDouble", "nextDouble()D"]);
		addOverloads(randMethods, function () { return (nextU32(this) & 1) === 1; }, ["nextBoolean", "nextBoolean()Z"]);
		addOverloads(randMethods, function () {
			var u = Math.max(1e-12, nextU32(this) / 4294967296), v = nextU32(this) / 4294967296;
			return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v);
		}, ["nextGaussian", "nextGaussian()D"]);
		addOverloads(randMethods, function (arr) {
			var a = unwrapArray(arr);
			if (!a) { return; }
			for (var i = 0; i < a.length; i++) { a[i] = ((nextU32(this) & 0xff) << 24) >> 24; }
		}, ["nextBytes", "nextBytes([B)V"]);
		ctx.regClass("java/util/Random", { interfaces: ["java/io/Serializable"], methods: randMethods });

		// ---- java/util/UUID ----
		function hexPad(n, digits) { var s = (n >>> 0).toString(16); while (s.length < digits) { s = "0" + s; } return s; }
		function makeUuidFromParts(msbHi, msbLo, lsbHi, lsbLo) {
			var str = hexPad(msbHi, 8) + "-" + hexPad(msbLo >>> 16, 4) + "-" + hexPad(msbLo & 0xffff, 4) + "-" +
				hexPad(lsbHi >>> 16, 4) + "-" + hexPad(lsbHi & 0xffff, 4) + hexPad(lsbLo, 8);
			var msbBig = typeof BigInt === "function" ? BigInt.asIntN(64, (BigInt(msbHi >>> 0) << BigInt(32)) | BigInt(msbLo >>> 0)) : ((msbHi | 0) * 4294967296 + (msbLo >>> 0));
			var lsbBig = typeof BigInt === "function" ? BigInt.asIntN(64, (BigInt(lsbHi >>> 0) << BigInt(32)) | BigInt(lsbLo >>> 0)) : ((lsbHi | 0) * 4294967296 + (lsbLo >>> 0));
			return ctx.makeObj("java/util/UUID", {
				_msbHi: msbHi >>> 0, _msbLo: msbLo >>> 0, _lsbHi: lsbHi >>> 0, _lsbLo: lsbLo >>> 0,
				_msb: msbBig, _lsb: lsbBig, _str: str
			});
		}
		function parseUuidStr(raw) {
			var clean = toJsStr(raw).trim().replace(/-/g, "").toLowerCase();
			while (clean.length < 32) { clean = "0" + clean; }
			return makeUuidFromParts(
				parseInt(clean.slice(0, 8), 16) >>> 0,
				parseInt(clean.slice(8, 16), 16) >>> 0,
				parseInt(clean.slice(16, 24), 16) >>> 0,
				parseInt(clean.slice(24, 32), 16) >>> 0
			);
		}
		var uuidMethods = {};
		addOverloads(uuidMethods, function (msb, lsb) {
			if (typeof msb === "bigint") {
				this._msb = msb;
				this._msbHi = Number((msb >> BigInt(32)) & BigInt(0xffffffff)) >>> 0;
				this._msbLo = Number(msb & BigInt(0xffffffff)) >>> 0;
			} else {
				this._msbHi = Math.floor(toNum(msb) / 4294967296) >>> 0;
				this._msbLo = toNum(msb) >>> 0;
				this._msb = makeLong(ctx, msb);
			}
			if (typeof lsb === "bigint") {
				this._lsb = lsb;
				this._lsbHi = Number((lsb >> BigInt(32)) & BigInt(0xffffffff)) >>> 0;
				this._lsbLo = Number(lsb & BigInt(0xffffffff)) >>> 0;
			} else {
				this._lsbHi = Math.floor(toNum(lsb) / 4294967296) >>> 0;
				this._lsbLo = toNum(lsb) >>> 0;
				this._lsb = makeLong(ctx, lsb);
			}
			this._str = hexPad(this._msbHi, 8) + "-" + hexPad(this._msbLo >>> 16, 4) + "-" + hexPad(this._msbLo & 0xffff, 4) + "-" +
				hexPad(this._lsbHi >>> 16, 4) + "-" + hexPad(this._lsbHi & 0xffff, 4) + hexPad(this._lsbLo, 8);
		}, ["<init>", "<init>(JJ)V"]);
		addOverloads(uuidMethods, function () { return this._str || "00000000-0000-0000-0000-000000000000"; }, ["toString", "toString()Ljava/lang/String;"]);
		addOverloads(uuidMethods, function () {
			if (ctx.longMode.useBigInt && typeof BigInt === "function") {
				if (typeof this._msb === "bigint") { return this._msb; }
				return BigInt.asIntN(64, (BigInt(this._msbHi >>> 0) << BigInt(32)) | BigInt(this._msbLo >>> 0));
			}
			return makeLong(ctx, (this._msbHi | 0) * 4294967296 + (this._msbLo >>> 0));
		}, ["getMostSignificantBits", "getMostSignificantBits()J"]);
		addOverloads(uuidMethods, function () {
			if (ctx.longMode.useBigInt && typeof BigInt === "function") {
				if (typeof this._lsb === "bigint") { return this._lsb; }
				return BigInt.asIntN(64, (BigInt(this._lsbHi >>> 0) << BigInt(32)) | BigInt(this._lsbLo >>> 0));
			}
			return makeLong(ctx, (this._lsbHi | 0) * 4294967296 + (this._lsbLo >>> 0));
		}, ["getLeastSignificantBits", "getLeastSignificantBits()J"]);
		addOverloads(uuidMethods, function (o) { return !!(o && getClassNameOf(o) === "java/util/UUID" && o._str === this._str); }, ["equals", "equals(Ljava/lang/Object;)Z"]);
		addOverloads(uuidMethods, function () { return (this._msbHi ^ this._msbLo ^ this._lsbHi ^ this._lsbLo) | 0; }, ["hashCode", "hashCode()I"]);
		addOverloads(uuidMethods, function (o) { var a = this._str || "", b = o ? (o._str || "") : ""; return a < b ? -1 : (a > b ? 1 : 0); }, ["compareTo", "compareTo(Ljava/util/UUID;)I", "compareTo(Ljava/lang/Object;)I"]);
		var uuidStatics = {};
		addOverloads(uuidStatics, function () {
			var r = function () { return (Math.random() * 0x100000000) >>> 0; };
			var a = r(), b = (r() & 0xffff0fff) | 0x00004000, c = (r() & 0x3fffffff) | 0x80000000, d = r();
			return makeUuidFromParts(a, b, c, d);
		}, ["randomUUID", "randomUUID()Ljava/util/UUID;"]);
		addOverloads(uuidStatics, parseUuidStr, ["fromString", "fromString(Ljava/lang/String;)Ljava/util/UUID;"]);
		addOverloads(uuidStatics, function (bytes) {
			var a = unwrapArray(bytes) || [], h1 = 0x811c9dc5, h2 = 0x9e3779b9, h3 = 0x85ebca6b, h4 = 0xc2b2ae35;
			for (var i = 0; i < a.length; i++) {
				var v = a[i] & 0xff;
				h1 = Math.imul(h1 ^ v, 0x01000193) >>> 0;
				h2 = Math.imul(h2 ^ v, 0x85ebca6b) >>> 0;
				h3 = Math.imul(h3 ^ v, 0xc2b2ae35) >>> 0;
				h4 = Math.imul(h4 ^ v, 0x27d4eb2f) >>> 0;
			}
			return makeUuidFromParts(h1, (h2 & 0xffff0fff) | 0x3000, (h3 & 0x3fffffff) | 0x80000000, h4);
		}, ["nameUUIDFromBytes", "nameUUIDFromBytes([B)Ljava/util/UUID;"]);
		ctx.regClass("java/util/UUID", { interfaces: ["java/io/Serializable", "java/lang/Comparable"], methods: uuidMethods, staticMethods: uuidStatics });

		// ---- java/util/Locale ----
		var locMethods = {
			"<init>": function (lang, country) { this._lang = toJsStr(lang || ""); this._country = toJsStr(country || ""); },
			getLanguage: function () { return this._lang || ""; }, "getLanguage()Ljava/lang/String;": function () { return this._lang || ""; },
			getCountry: function () { return this._country || ""; }, "getCountry()Ljava/lang/String;": function () { return this._country || ""; },
			toString: function () { return this._country ? (this._lang + "_" + this._country) : (this._lang || ""); },
			"toString()Ljava/lang/String;": function () { return this._country ? (this._lang + "_" + this._country) : (this._lang || ""); }
		};
		var locRoot = null, locUs = null, locEn = null;
		var locFields = {};
		ctx.regClass("java/util/Locale", {
			statics: locFields,
			methods: locMethods,
			staticMethods: {
				getDefault: function () { return locUs; }, "getDefault()Ljava/util/Locale;": function () { return locUs; },
				forLanguageTag: function (tag) { var p = toJsStr(tag).split(/[-_]/); return ctx.makeObj("java/util/Locale", { _lang: p[0] || "", _country: p[1] || "" }); },
				"forLanguageTag(Ljava/lang/String;)Ljava/util/Locale;": function (t) { var p = toJsStr(t).split(/[-_]/); return ctx.makeObj("java/util/Locale", { _lang: p[0] || "", _country: p[1] || "" }); },
				of: function (l, c) { return ctx.makeObj("java/util/Locale", { _lang: toJsStr(l || ""), _country: toJsStr(c || "") }); }
			}
		});
		locRoot = ctx.makeObj("java/util/Locale", { _lang: "", _country: "" });
		locUs = ctx.makeObj("java/util/Locale", { _lang: "en", _country: "US" });
		locEn = ctx.makeObj("java/util/Locale", { _lang: "en", _country: "" });
		ctx.setStaticField("java/util/Locale", "ROOT", locRoot);
		ctx.setStaticField("java/util/Locale", "US", locUs);
		ctx.setStaticField("java/util/Locale", "ENGLISH", locEn);
		ctx.setStaticField("java/util/Locale", "UK", ctx.makeObj("java/util/Locale", { _lang: "en", _country: "GB" }));

		// ---- java/util/Properties ----
		var propsMethods = {};
		propsMethods["<init>()V"] = function () { this._entries = []; this._defaults = null; };
		propsMethods["<init>(Ljava/util/Properties;)V"] = function (d) { this._entries = []; this._defaults = d || null; };
		propsMethods["<init>"] = function (d) { this._entries = []; this._defaults = d || null; };
		addOverloads(propsMethods, function (src) {
			var text = "";
			if (src && typeof src._text === "string") { text = src._text; }
			else if (src && src._bytes) { text = VFS.decodeUtf8(src._bytes); }
			else if (src && typeof src.readLine === "function") {
				var lines = [], ln;
				while ((ln = src.readLine()) !== null) { lines.push(ln); }
				text = lines.join("\n");
			}
			var arr = text.split(/\r?\n/);
			for (var i = 0; i < arr.length; i++) {
				var line = arr[i].trim();
				if (!line || line.charAt(0) === "#" || line.charAt(0) === "!") { continue; }
				var eq = line.search(/[=:]/);
				if (eq >= 0) {
					mapMethods.put.call(this, line.slice(0, eq).trim(), line.slice(eq + 1).trim());
				}
			}
		}, ["load", "load(Ljava/io/Reader;)V", "load(Ljava/io/InputStream;)V"]);
		addOverloads(propsMethods, function (dst, comments) {
			var lines = [];
			if (comments) { lines.push("#" + toJsStr(comments)); }
			var es = mapEntries(this);
			for (var i = 0; i < es.length; i++) { lines.push(toJsStr(es[i]._key) + "=" + toJsStr(es[i]._val)); }
			var out = lines.join("\n") + "\n";
			if (dst && typeof dst.write === "function") {
				var dstCls = getClassNameOf(dst);
				if (dstCls === "java/io/FileOutputStream" || dstCls === "java/io/ByteArrayOutputStream") {
					var u8 = VFS.encodeUtf8(out);
					for (var j = 0; j < u8.length; j++) { dst.write(u8[j]); }
				} else {
					dst.write(out);
				}
				if (typeof dst.flush === "function") { dst.flush(); }
			}
		}, ["store", "store(Ljava/io/Writer;Ljava/lang/String;)V", "store(Ljava/io/OutputStream;Ljava/lang/String;)V"]);
		addOverloads(propsMethods, function (key, defVal) {
			var v = mapMethods.get.call(this, toJsStr(key));
			if (v !== null && v !== undefined) { return toJsStr(v); }
			if (this._defaults) {
				var dv = propsMethods.getProperty.call(this._defaults, key);
				if (dv !== null && dv !== undefined) { return dv; }
			}
			return arguments.length >= 2 && defVal !== undefined ? toJsStrOrNull(defVal) : null;
		}, ["getProperty", "getProperty(Ljava/lang/String;)Ljava/lang/String;", "getProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"]);
		addOverloads(propsMethods, function (k, v) { return mapMethods.put.call(this, toJsStr(k), toJsStr(v)); },
			["setProperty", "setProperty(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;"]);
		addOverloads(propsMethods, function () { return mapMethods.keySet.call(this); }, ["stringPropertyNames", "stringPropertyNames()Ljava/util/Set;"]);
		ctx.regClass("java/util/Properties", { superName: "java/util/HashMap", methods: propsMethods });

		// ---- AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference ----
		ctx.regClass("java/util/concurrent/atomic/AtomicBoolean", {
			methods: {
				"<init>": function (v) { this._val = toBool(v); }, "<init>()V": function () { this._val = false; }, "<init>(Z)V": function (v) { this._val = toBool(v); },
				get: function () { return !!this._val; }, "get()Z": function () { return !!this._val; },
				set: function (v) { this._val = toBool(v); }, "set(Z)V": function (v) { this._val = toBool(v); },
				lazySet: function (v) { this._val = toBool(v); }, "lazySet(Z)V": function (v) { this._val = toBool(v); },
				getAndSet: function (v) { var o = !!this._val; this._val = toBool(v); return o; }, "getAndSet(Z)Z": function (v) { var o = !!this._val; this._val = toBool(v); return o; },
				compareAndSet: function (exp, upd) { if (!!this._val === toBool(exp)) { this._val = toBool(upd); return true; } return false; },
				"compareAndSet(ZZ)Z": function (e, u) { return this.compareAndSet(e, u); },
				toString: function () { return this._val ? "true" : "false"; }, "toString()Ljava/lang/String;": function () { return this._val ? "true" : "false"; }
			}
		});
		ctx.regClass("java/util/concurrent/atomic/AtomicInteger", {
			superName: "java/lang/Number",
			methods: {
				"<init>": function (v) { this._val = toInt(v); this._numValue = this._val; }, "<init>()V": function () { this._val = 0; this._numValue = 0; }, "<init>(I)V": function (v) { this._val = toInt(v); this._numValue = this._val; },
				get: function () { return this._val | 0; }, "get()I": function () { return this._val | 0; },
				set: function (v) { this._val = toInt(v); this._numValue = this._val; }, "set(I)V": function (v) { this.set(v); },
				getAndSet: function (v) { var o = this._val | 0; this.set(v); return o; }, "getAndSet(I)I": function (v) { return this.getAndSet(v); },
				compareAndSet: function (e, u) { if ((this._val | 0) === toInt(e)) { this.set(u); return true; } return false; }, "compareAndSet(II)Z": function (e, u) { return this.compareAndSet(e, u); },
				incrementAndGet: function () { this.set((this._val | 0) + 1); return this._val; }, "incrementAndGet()I": function () { return this.incrementAndGet(); },
				getAndIncrement: function () { var o = this._val | 0; this.set(o + 1); return o; }, "getAndIncrement()I": function () { return this.getAndIncrement(); },
				decrementAndGet: function () { this.set((this._val | 0) - 1); return this._val; }, "decrementAndGet()I": function () { return this.decrementAndGet(); },
				getAndDecrement: function () { var o = this._val | 0; this.set(o - 1); return o; }, "getAndDecrement()I": function () { return this.getAndDecrement(); },
				addAndGet: function (d) { this.set((this._val | 0) + toInt(d)); return this._val; }, "addAndGet(I)I": function (d) { return this.addAndGet(d); },
				getAndAdd: function (d) { var o = this._val | 0; this.set(o + toInt(d)); return o; }, "getAndAdd(I)I": function (d) { return this.getAndAdd(d); },
				toString: function () { return String(this._val | 0); }, "toString()Ljava/lang/String;": function () { return String(this._val | 0); }
			}
		});
		ctx.regClass("java/util/concurrent/atomic/AtomicLong", {
			superName: "java/lang/Number",
			methods: {
				"<init>": function (v) { this._val = makeLong(ctx, v || 0); this._numValue = this._val; }, "<init>()V": function () { this._val = makeLong(ctx, 0); this._numValue = this._val; }, "<init>(J)V": function (v) { observeLongArg(ctx, v); this._val = makeLong(ctx, v); this._numValue = this._val; },
				get: function () { return this._val !== undefined ? this._val : makeLong(ctx, 0); }, "get()J": function () { return this.get(); },
				set: function (v) { observeLongArg(ctx, v); this._val = makeLong(ctx, v); this._numValue = this._val; }, "set(J)V": function (v) { this.set(v); },
				getAndSet: function (v) { var o = this.get(); this.set(v); return o; }, "getAndSet(J)J": function (v) { return this.getAndSet(v); },
				compareAndSet: function (e, u) { if (toNum(this.get()) === toNum(e)) { this.set(u); return true; } return false; }, "compareAndSet(JJ)Z": function (e, u) { return this.compareAndSet(e, u); },
				incrementAndGet: function () { var n = makeLong(ctx, toNum(this.get()) + 1); this.set(n); return n; }, "incrementAndGet()J": function () { return this.incrementAndGet(); },
				getAndIncrement: function () { var o = this.get(); this.set(makeLong(ctx, toNum(o) + 1)); return o; }, "getAndIncrement()J": function () { return this.getAndIncrement(); },
				decrementAndGet: function () { var n = makeLong(ctx, toNum(this.get()) - 1); this.set(n); return n; }, "decrementAndGet()J": function () { return this.decrementAndGet(); },
				getAndDecrement: function () { var o = this.get(); this.set(makeLong(ctx, toNum(o) - 1)); return o; }, "getAndDecrement()J": function () { return this.getAndDecrement(); },
				addAndGet: function (d) { var n = makeLong(ctx, toNum(this.get()) + toNum(d)); this.set(n); return n; }, "addAndGet(J)J": function (d) { return this.addAndGet(d); },
				getAndAdd: function (d) { var o = this.get(); this.set(makeLong(ctx, toNum(o) + toNum(d))); return o; }, "getAndAdd(J)J": function (d) { return this.getAndAdd(d); },
				toString: function () { return String(this.get()); }, "toString()Ljava/lang/String;": function () { return String(this.get()); }
			}
		});
		ctx.regClass("java/util/concurrent/atomic/AtomicReference", {
			methods: {
				"<init>": function (v) { this._val = v === undefined ? null : v; }, "<init>()V": function () { this._val = null; }, "<init>(Ljava/lang/Object;)V": function (v) { this._val = v; },
				get: function () { return this._val === undefined ? null : this._val; }, "get()Ljava/lang/Object;": function () { return this.get(); },
				set: function (v) { this._val = v; }, "set(Ljava/lang/Object;)V": function (v) { this._val = v; },
				getAndSet: function (v) { var o = this.get(); this._val = v; return o; }, "getAndSet(Ljava/lang/Object;)Ljava/lang/Object;": function (v) { return this.getAndSet(v); },
				compareAndSet: function (e, u) { if (this._val === e) { this._val = u; return true; } return false; }, "compareAndSet(Ljava/lang/Object;Ljava/lang/Object;)Z": function (e, u) { return this.compareAndSet(e, u); },
				toString: function () { return javaToString(rt, this.get()); }, "toString()Ljava/lang/String;": function () { return this.toString(); }
			}
		});

		// ---- java/util/stream/Stream & Collectors ----
		var streamMethods = {};
		function sArr(self) { return Array.isArray(self._arr) ? self._arr : []; }
		addOverloads(streamMethods, function (pred) {
			var a = sArr(this), out = [];
			for (var i = 0; i < a.length; i++) {
				if (toBool(invokeJavaCallback(rt, pred, "test", "(Ljava/lang/Object;)Z", [a[i]]))) { out.push(a[i]); }
			}
			return ctx.makeObj("java/util/stream/Stream", { _arr: out });
		}, ["filter", "filter(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;"]);
		addOverloads(streamMethods, function (fn) {
			var a = sArr(this), out = [];
			for (var i = 0; i < a.length; i++) {
				out.push(invokeJavaCallback(rt, fn, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [a[i]]));
			}
			return ctx.makeObj("java/util/stream/Stream", { _arr: out });
		}, ["map", "map(Ljava/util/function/Function;)Ljava/util/stream/Stream;"]);
		addOverloads(streamMethods, function (fn) {
			var a = sArr(this), out = [];
			for (var i = 0; i < a.length; i++) {
				var sub = invokeJavaCallback(rt, fn, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [a[i]]);
				if (sub && Array.isArray(sub._arr)) { for (var j = 0; j < sub._arr.length; j++) { out.push(sub._arr[j]); } }
			}
			return ctx.makeObj("java/util/stream/Stream", { _arr: out });
		}, ["flatMap", "flatMap(Ljava/util/function/Function;)Ljava/util/stream/Stream;"]);
		addOverloads(streamMethods, function () {
			var a = sArr(this), out = [];
			for (var i = 0; i < a.length; i++) {
				var dup = false;
				for (var j = 0; j < out.length; j++) { if (javaEquals(rt, out[j], a[i])) { dup = true; break; } }
				if (!dup) { out.push(a[i]); }
			}
			return ctx.makeObj("java/util/stream/Stream", { _arr: out });
		}, ["distinct", "distinct()Ljava/util/stream/Stream;"]);
		addOverloads(streamMethods, function (cmp) {
			var out = sArr(this).slice();
			if (cmp) { out.sort(function (x, y) { return toInt(invokeJavaCallback(rt, cmp, "compare", "(Ljava/lang/Object;Ljava/lang/Object;)I", [x, y])); }); }
			else { out.sort(function (x, y) { return javaCompare(rt, x, y); }); }
			return ctx.makeObj("java/util/stream/Stream", { _arr: out });
		}, ["sorted", "sorted()Ljava/util/stream/Stream;", "sorted(Ljava/util/Comparator;)Ljava/util/stream/Stream;"]);
		addOverloads(streamMethods, function (n) { return ctx.makeObj("java/util/stream/Stream", { _arr: sArr(this).slice(0, Math.max(0, toNum(n))) }); }, ["limit", "limit(J)Ljava/util/stream/Stream;"]);
		addOverloads(streamMethods, function (n) { return ctx.makeObj("java/util/stream/Stream", { _arr: sArr(this).slice(Math.max(0, toNum(n))) }); }, ["skip", "skip(J)Ljava/util/stream/Stream;"]);
		addOverloads(streamMethods, function (cons) {
			var a = sArr(this);
			for (var i = 0; i < a.length; i++) { invokeJavaCallback(rt, cons, "accept", "(Ljava/lang/Object;)V", [a[i]]); }
			return this;
		}, ["peek", "peek(Ljava/util/function/Consumer;)Ljava/util/stream/Stream;"]);
		addOverloads(streamMethods, function (cons) {
			var a = sArr(this);
			for (var i = 0; i < a.length; i++) { invokeJavaCallback(rt, cons, "accept", "(Ljava/lang/Object;)V", [a[i]]); }
		}, ["forEach", "forEach(Ljava/util/function/Consumer;)V", "forEachOrdered", "forEachOrdered(Ljava/util/function/Consumer;)V"]);
		addOverloads(streamMethods, function () { return makeListObj(sArr(this).slice()); }, ["toList", "toList()Ljava/util/List;"]);
		addOverloads(streamMethods, function () { return makeObjectArray(rt, "Ljava/lang/Object;", sArr(this).slice()); }, ["toArray", "toArray()[Ljava/lang/Object;"]);
		addOverloads(streamMethods, function (collector) {
			var a = sArr(this);
			if (collector && typeof collector._collect === "function") { return collector._collect(a); }
			return makeListObj(a.slice());
		}, ["collect", "collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"]);
		addOverloads(streamMethods, function (pred) {
			var a = sArr(this);
			for (var i = 0; i < a.length; i++) { if (toBool(invokeJavaCallback(rt, pred, "test", "(Ljava/lang/Object;)Z", [a[i]]))) { return true; } }
			return false;
		}, ["anyMatch", "anyMatch(Ljava/util/function/Predicate;)Z"]);
		addOverloads(streamMethods, function (pred) {
			var a = sArr(this);
			for (var i = 0; i < a.length; i++) { if (!toBool(invokeJavaCallback(rt, pred, "test", "(Ljava/lang/Object;)Z", [a[i]]))) { return false; } }
			return true;
		}, ["allMatch", "allMatch(Ljava/util/function/Predicate;)Z"]);
		addOverloads(streamMethods, function (pred) {
			var a = sArr(this);
			for (var i = 0; i < a.length; i++) { if (toBool(invokeJavaCallback(rt, pred, "test", "(Ljava/lang/Object;)Z", [a[i]]))) { return false; } }
			return true;
		}, ["noneMatch", "noneMatch(Ljava/util/function/Predicate;)Z"]);
		addOverloads(streamMethods, function () { return makeLong(ctx, sArr(this).length); }, ["count", "count()J"]);
		addOverloads(streamMethods, function () {
			var a = sArr(this);
			return a.length ? ctx.makeObj("java/util/Optional", { _val: a[0] }) : optEmpty;
		}, ["findFirst", "findFirst()Ljava/util/Optional;", "findAny", "findAny()Ljava/util/Optional;"]);
		addOverloads(streamMethods, function () {}, ["close", "close()V"]);
		var streamStatics = {
			empty: function () { return ctx.makeObj("java/util/stream/Stream", { _arr: [] }); },
			"empty()Ljava/util/stream/Stream;": function () { return ctx.makeObj("java/util/stream/Stream", { _arr: [] }); },
			of: function (x) {
				var a = (Array.isArray(x) || (x && Array.isArray(x.data))) ? unwrapArray(x).slice() : [x];
				return ctx.makeObj("java/util/stream/Stream", { _arr: a });
			},
			"of(Ljava/lang/Object;)Ljava/util/stream/Stream;": function (x) { return ctx.makeObj("java/util/stream/Stream", { _arr: [x] }); },
			"of([Ljava/lang/Object;)Ljava/util/stream/Stream;": function (x) { return ctx.makeObj("java/util/stream/Stream", { _arr: (unwrapArray(x) || []).slice() }); },
			concat: function (s1, s2) { return ctx.makeObj("java/util/stream/Stream", { _arr: sArr(s1).concat(sArr(s2)) }); },
			"concat(Ljava/util/stream/Stream;Ljava/util/stream/Stream;)Ljava/util/stream/Stream;": function (s1, s2) { return ctx.makeObj("java/util/stream/Stream", { _arr: sArr(s1).concat(sArr(s2)) }); }
		};
		ctx.regClass("java/util/stream/Stream", { isInterface: true, interfaces: ["java/lang/AutoCloseable"], methods: streamMethods, staticMethods: streamStatics });
		ctx.regClass("java/util/stream/Collector", { isInterface: true });
		var collectorsStatics = {};
		addOverloads(collectorsStatics, function () {
			return ctx.makeObj("java/util/stream/Collector", { _collect: function (a) { return makeListObj(a.slice()); } });
		}, ["toList", "toList()Ljava/util/stream/Collector;", "toUnmodifiableList", "toUnmodifiableList()Ljava/util/stream/Collector;"]);
		addOverloads(collectorsStatics, function () {
			return ctx.makeObj("java/util/stream/Collector", {
				_collect: function (a) {
					var s = ctx.makeObj("java/util/LinkedHashSet", { _arr: [] });
					for (var i = 0; i < a.length; i++) { setMethods["add(Ljava/lang/Object;)Z"].call(s, a[i]); }
					return s;
				}
			});
		}, ["toSet", "toSet()Ljava/util/stream/Collector;", "toUnmodifiableSet", "toUnmodifiableSet()Ljava/util/stream/Collector;"]);
		addOverloads(collectorsStatics, function (delim, prefix, suffix) {
			var d = arguments.length >= 1 && delim !== undefined ? toJsStr(delim) : "";
			var pre = arguments.length >= 2 && prefix !== undefined ? toJsStr(prefix) : "";
			var suf = arguments.length >= 3 && suffix !== undefined ? toJsStr(suffix) : "";
			return ctx.makeObj("java/util/stream/Collector", {
				_collect: function (a) {
					var parts = [];
					for (var i = 0; i < a.length; i++) { parts.push(toJsStr(a[i])); }
					return pre + parts.join(d) + suf;
				}
			});
		}, ["joining", "joining()Ljava/util/stream/Collector;", "joining(Ljava/lang/CharSequence;)Ljava/util/stream/Collector;",
			"joining(Ljava/lang/CharSequence;Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/util/stream/Collector;"]);
		addOverloads(collectorsStatics, function (kFn, vFn) {
			return ctx.makeObj("java/util/stream/Collector", {
				_collect: function (a) {
					var m = ctx.makeObj("java/util/LinkedHashMap", { _entries: [] });
					for (var i = 0; i < a.length; i++) {
						mapMethods.put.call(m,
							invokeJavaCallback(rt, kFn, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [a[i]]),
							invokeJavaCallback(rt, vFn, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [a[i]]));
					}
					return m;
				}
			});
		}, ["toMap", "toMap(Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/util/stream/Collector;"]);
		ctx.regClass("java/util/stream/Collectors", { staticMethods: collectorsStatics });
	}

	// =========================================================================
	// 9. Gson (Full POJO + DOM JSON) & SLF4J / Log4j Loggers
	// =========================================================================

	function installGsonAndLoggers(ctx) {
		var rt = ctx.rt;

		function jsToJsonElement(val) {
			if (val === null || val === undefined) { return jsonNullSingleton; }
			var vc = val && typeof val === "object" ? getClassNameOf(val) : "";
			if (vc === "com/google/gson/JsonObject" || vc === "com/google/gson/JsonArray" ||
				vc === "com/google/gson/JsonPrimitive" || vc === "com/google/gson/JsonNull") {
				return val;
			}
			if (typeof val === "boolean" || typeof val === "number" || typeof val === "string") {
				return ctx.makeObj("com/google/gson/JsonPrimitive", { _val: val });
			}
			if (Array.isArray(val)) {
				var arr = [];
				for (var i = 0; i < val.length; i++) { arr.push(jsToJsonElement(val[i])); }
				return ctx.makeObj("com/google/gson/JsonArray", { _arr: arr });
			}
			var map = Object.create(null);
			var keys = Object.keys(val);
			for (var k = 0; k < keys.length; k++) { map[keys[k]] = jsToJsonElement(val[keys[k]]); }
			return ctx.makeObj("com/google/gson/JsonObject", { _props: map });
		}

		function jsonElementToJs(el) {
			var ec = el && typeof el === "object" ? getClassNameOf(el) : "";
			if (el === null || el === undefined || ec === "com/google/gson/JsonNull") { return null; }
			if (ec === "com/google/gson/JsonPrimitive") { return el._val; }
			if (ec === "com/google/gson/JsonArray") {
				var out = [], a = el._arr || [];
				for (var i = 0; i < a.length; i++) { out.push(jsonElementToJs(a[i])); }
				return out;
			}
			if (ec === "com/google/gson/JsonObject") {
				var obj = {}, props = el._props || {};
				var keys = Object.keys(props);
				for (var k = 0; k < keys.length; k++) { obj[keys[k]] = jsonElementToJs(props[keys[k]]); }
				return obj;
			}
			return el;
		}

		function javaObjToJsData(val, serializeNulls, seen) {
			if (val === null || val === undefined) { return null; }
			if (typeof val === "boolean" || typeof val === "string") { return val; }
			if (typeof val === "number") { return isFinite(val) ? val : null; }
			if (typeof val === "bigint") { return Number(val); }
			if (typeof val !== "object") { return null; }
			var cls = getClassNameOf(val);
			if (cls === "java/lang/String") { return toJsStr(val); }
			if (cls === "java/lang/Boolean") { return toBool(val._boolValue); }
			if (cls === "java/lang/Integer" || cls === "java/lang/Long" || cls === "java/lang/Float" ||
				cls === "java/lang/Double" || cls === "java/lang/Byte" || cls === "java/lang/Short") {
				return toNum(val._numValue);
			}
			if (cls === "java/lang/Character") { return String.fromCharCode(toCharCode(val._charValue)); }
			if (cls === "com/google/gson/JsonObject" || cls === "com/google/gson/JsonArray" ||
				cls === "com/google/gson/JsonPrimitive" || cls === "com/google/gson/JsonNull") {
				return jsonElementToJs(val);
			}
			if (typeof val._enumName === "string") { return val._enumName; }
			if (seen.indexOf(val) >= 0) { return null; }
			seen.push(val);
			try {
				if (Array.isArray(val) || ArrayBuffer.isView(val) || (val.data && Array.isArray(val.data))) {
					var arr = unwrapArray(val), outA = [];
					for (var ai = 0; ai < arr.length; ai++) { outA.push(javaObjToJsData(arr[ai], serializeNulls, seen)); }
					return outA;
				}
				if (Array.isArray(val._items) || Array.isArray(val._arr)) {
					var listA = Array.isArray(val._items) ? val._items : val._arr;
					var outL = [];
					for (var li = 0; li < listA.length; li++) { outL.push(javaObjToJsData(listA[li], serializeNulls, seen)); }
					return outL;
				}
				if (Array.isArray(val._entries)) {
					var outM = {};
					for (var mi = 0; mi < val._entries.length; mi++) {
						var eItem = val._entries[mi];
						var ek = Array.isArray(eItem) ? eItem[0] : eItem._key;
						var ev = Array.isArray(eItem) ? eItem[1] : eItem._val;
						var mv = javaObjToJsData(ev, serializeNulls, seen);
						if (mv !== null || serializeNulls) {
							outM[javaToString(rt, ek)] = mv;
						}
					}
					return outM;
				}
				if (cls === "java/util/UUID") { return val._str || ""; }
				if (cls === "net/minecraft/resources/Identifier" || cls === "net/minecraft/resources/ResourceLocation") {
					return (val._ns || "minecraft") + ":" + (val._path || "");
				}
				// Translated Java POJO: inspect own enumerable fields and optional __fields / $fields / fields bags
				var res = {};
				var bags = [val];
				if (val.__fields && typeof val.__fields === "object") { bags.push(val.__fields); }
				if (val.$fields && typeof val.$fields === "object") { bags.push(val.$fields); }
				if (val.fields && typeof val.fields === "object" && !Array.isArray(val.fields)) { bags.push(val.fields); }
				for (var bi = 0; bi < bags.length; bi++) {
					var bag = bags[bi];
					var keys = Object.keys(bag);
					for (var ki = 0; ki < keys.length; ki++) {
						var k = keys[ki];
						if (!k || k.charAt(0) === "_" || k.charAt(0) === "$" || k === "fields") { continue; }
						var fv = bag[k];
						if (typeof fv === "function") { continue; }
						var conv = javaObjToJsData(fv, serializeNulls, seen);
						if (conv !== null || serializeNulls) { res[k] = conv; }
					}
				}
				return res;
			} finally {
				seen.pop();
			}
		}

		function jsDataToJavaValue(data, targetClassName) {
			if (data === null || data === undefined) { return null; }
			var cls = targetClassName ? (ctx.aliases[targetClassName] || targetClassName) : null;
			if (cls === "com/google/gson/JsonElement" || cls === "com/google/gson/JsonObject" ||
				cls === "com/google/gson/JsonArray" || cls === "com/google/gson/JsonPrimitive") {
				return jsToJsonElement(data);
			}
			if (cls === "java/lang/String") { return String(data); }
			if (cls === "java/lang/Integer" || cls === "I") { var iv = toInt(data); return ctx.makeObj("java/lang/Integer", { _numValue: iv, value: iv }); }
			if (cls === "java/lang/Long" || cls === "J") { var lv = makeLong(ctx, data); return ctx.makeObj("java/lang/Long", { _numValue: lv, value: lv }); }
			if (cls === "java/lang/Float" || cls === "F") { var fv = Math.fround(toNum(data)); return ctx.makeObj("java/lang/Float", { _numValue: fv, value: fv }); }
			if (cls === "java/lang/Double" || cls === "D") { var dv = toNum(data); return ctx.makeObj("java/lang/Double", { _numValue: dv, value: dv }); }
			if (cls === "java/lang/Boolean" || cls === "Z") { return ctx.nativeDefs["java/lang/Boolean"].staticMethods.valueOf(!!data); }
			if (Array.isArray(data)) {
				var listItems = [];
				for (var i = 0; i < data.length; i++) { listItems.push(jsDataToJavaValue(data[i], null)); }
				return ctx.makeObj("java/util/ArrayList", { _arr: listItems, _items: listItems });
			}
			if (typeof data !== "object") { return data; }
			if (!cls || cls === "java/lang/Object" || cls === "java/util/Map" || cls === "java/util/HashMap" || cls === "java/util/LinkedHashMap") {
				var mapObj = ctx.makeObj("java/util/LinkedHashMap", { _entries: [] });
				var mks = Object.keys(data);
				for (var mi = 0; mi < mks.length; mi++) {
					mapObj._entries.push([mks[mi], jsDataToJavaValue(data[mks[mi]], null)]);
				}
				return mapObj;
			}
			// Instantiate translated Java POJO class & call default <init>()V so default field values survive!
			var inst = null;
			if (rt && typeof rt.newInstance === "function") {
				try { inst = rt.newInstance(cls, []); } catch (e0) {
					try { inst = rt.newInstance(cls); } catch (e0b) { inst = null; }
				}
			}
			if (!inst && rt && typeof rt.newObject === "function") {
				try { inst = rt.newObject(cls); } catch (e) { inst = null; }
			}
			if (!inst && rt && typeof rt.allocObject === "function") {
				try {
					inst = rt.allocObject(cls);
					if (typeof rt.invokeSpecial === "function") {
						try { rt.invokeSpecial(inst, cls, "<init>", "()V", []); } catch (e2) {}
					}
				} catch (e1) { inst = null; }
			}
			if (!inst) {
				inst = ctx.makeObj(cls);
			}
			// Inspect field descriptor metadata on translated class for primitive coercion (e.g. Z -> 1/0, J -> BigInt)
			var fieldDescMap = Object.create(null);
			if (rt && rt.definedClasses && rt.definedClasses.has(cls)) {
				var defCls = rt.definedClasses.get(cls);
				var fDefs = (defCls && defCls.instanceFieldDefs) || [];
				for (var fdi = 0; fdi < fDefs.length; fdi++) { fieldDescMap[fDefs[fdi].name] = fDefs[fdi].descriptor; }
			}
			// Populate JSON keys onto the Java POJO instance
			var keys = Object.keys(data);
			for (var ki = 0; ki < keys.length; ki++) {
				var key = keys[ki];
				var rawV = data[key];
				var fDesc = fieldDescMap[key];
				var converted;
				if (fDesc === "Z") { converted = rawV ? 1 : 0; }
				else if (fDesc === "I" || fDesc === "B" || fDesc === "S" || fDesc === "C") { converted = toInt(rawV); }
				else if (fDesc === "J") { converted = makeLong(ctx, rawV); }
				else if (fDesc === "F") { converted = Math.fround(toNum(rawV)); }
				else if (fDesc === "D") { converted = toNum(rawV); }
				else if (fDesc && fDesc.charAt(0) === "L" && fDesc.charAt(fDesc.length - 1) === ";") {
					converted = jsDataToJavaValue(rawV, fDesc.slice(1, -1));
				} else {
					var existing = inst[key];
					if (existing === undefined && inst.__fields) { existing = inst.__fields[key]; }
					if (existing === undefined && inst.$fields) { existing = inst.$fields[key]; }
					var targetFieldCls = (existing && typeof existing === "object" && (existing.__cls || existing.__class || existing.$class))
						? (existing.__cls || existing.__class || existing.$class) : null;
					converted = jsDataToJavaValue(rawV, targetFieldCls);
				}
				if (rt && typeof rt.putField === "function") {
					try { rt.putField(inst, cls, key, fDesc || null, converted); } catch (ep) { inst[key] = converted; }
				} else {
					inst[key] = converted;
				}
				if (inst.$fields && typeof inst.$fields === "object") { inst.$fields[key] = converted; }
			}
			return inst;
		}

		// ---- JsonElement hierarchy ----
		var jeMethods = {
			isJsonObject: function () { return getClassNameOf(this) === "com/google/gson/JsonObject"; },
			"isJsonObject()Z": function () { return getClassNameOf(this) === "com/google/gson/JsonObject"; },
			isJsonArray: function () { return getClassNameOf(this) === "com/google/gson/JsonArray"; },
			"isJsonArray()Z": function () { return getClassNameOf(this) === "com/google/gson/JsonArray"; },
			isJsonPrimitive: function () { return getClassNameOf(this) === "com/google/gson/JsonPrimitive"; },
			"isJsonPrimitive()Z": function () { return getClassNameOf(this) === "com/google/gson/JsonPrimitive"; },
			isJsonNull: function () { return getClassNameOf(this) === "com/google/gson/JsonNull"; },
			"isJsonNull()Z": function () { return getClassNameOf(this) === "com/google/gson/JsonNull"; },
			getAsJsonObject: function () { return this; }, "getAsJsonObject()Lcom/google/gson/JsonObject;": function () { return this; },
			getAsJsonArray: function () { return this; }, "getAsJsonArray()Lcom/google/gson/JsonArray;": function () { return this; },
			getAsJsonPrimitive: function () { return this; }, "getAsJsonPrimitive()Lcom/google/gson/JsonPrimitive;": function () { return this; },
			getAsString: function () { return String(this._val != null ? this._val : ""); }, "getAsString()Ljava/lang/String;": function () { return String(this._val != null ? this._val : ""); },
			getAsInt: function () { return toInt(this._val); }, "getAsInt()I": function () { return toInt(this._val); },
			getAsLong: function () { return makeLong(ctx, this._val); }, "getAsLong()J": function () { return makeLong(ctx, this._val); },
			getAsFloat: function () { return Math.fround(toNum(this._val)); }, "getAsFloat()F": function () { return Math.fround(toNum(this._val)); },
			getAsDouble: function () { return toNum(this._val); }, "getAsDouble()D": function () { return toNum(this._val); },
			getAsBoolean: function () { return typeof this._val === "string" ? this._val.toLowerCase() === "true" : !!this._val; },
			"getAsBoolean()Z": function () { return typeof this._val === "string" ? this._val.toLowerCase() === "true" : !!this._val; },
			toString: function () { return JSON.stringify(jsonElementToJs(this)); },
			"toString()Ljava/lang/String;": function () { return JSON.stringify(jsonElementToJs(this)); }
		};
		ctx.regClass("com/google/gson/JsonElement", { methods: jeMethods });
		ctx.regClass("com/google/gson/JsonNull", { superName: "com/google/gson/JsonElement", statics: {} });
		var jsonNullSingleton = ctx.makeObj("com/google/gson/JsonNull");
		ctx.setStaticField("com/google/gson/JsonNull", "INSTANCE", jsonNullSingleton);

		ctx.regClass("com/google/gson/JsonPrimitive", {
			superName: "com/google/gson/JsonElement",
			methods: {
				"<init>": function (v) { this._val = isJavaString(v) ? toJsStr(v) : (typeof v === "boolean" ? v : toNum(v)); },
				"<init>(Ljava/lang/String;)V": function (s) { this._val = toJsStr(s); },
				"<init>(Ljava/lang/Boolean;)V": function (b) { this._val = toBool(b); },
				"<init>(Ljava/lang/Number;)V": function (n) { this._val = toNum(n); },
				"<init>(Ljava/lang/Character;)V": function (c) { this._val = String.fromCharCode(toCharCode(c)); },
				isBoolean: function () { return typeof this._val === "boolean"; }, "isBoolean()Z": function () { return typeof this._val === "boolean"; },
				isNumber: function () { return typeof this._val === "number"; }, "isNumber()Z": function () { return typeof this._val === "number"; },
				isString: function () { return typeof this._val === "string"; }, "isString()Z": function () { return typeof this._val === "string"; }
			}
		});

		function joProps(self) { return self._props || (self._props = Object.create(null)); }
		var joMethods = {
			"<init>": function () { this._props = Object.create(null); }, "<init>()V": function () { this._props = Object.create(null); },
			has: function (k) { return Object.prototype.hasOwnProperty.call(joProps(this), toJsStr(k)); },
			"has(Ljava/lang/String;)Z": function (k) { return Object.prototype.hasOwnProperty.call(joProps(this), toJsStr(k)); },
			get: function (k) { var p = joProps(this), s = toJsStr(k); return Object.prototype.hasOwnProperty.call(p, s) ? p[s] : null; },
			"get(Ljava/lang/String;)Lcom/google/gson/JsonElement;": function (k) { var p = joProps(this), s = toJsStr(k); return Object.prototype.hasOwnProperty.call(p, s) ? p[s] : null; },
			getAsJsonObject: function (k) { return arguments.length ? joMethods.get.call(this, k) : this; },
			"getAsJsonObject(Ljava/lang/String;)Lcom/google/gson/JsonObject;": function (k) { return joMethods.get.call(this, k); },
			getAsJsonArray: function (k) { return arguments.length ? joMethods.get.call(this, k) : this; },
			"getAsJsonArray(Ljava/lang/String;)Lcom/google/gson/JsonArray;": function (k) { return joMethods.get.call(this, k); },
			getAsJsonPrimitive: function (k) { return arguments.length ? joMethods.get.call(this, k) : this; },
			"getAsJsonPrimitive(Ljava/lang/String;)Lcom/google/gson/JsonPrimitive;": function (k) { return joMethods.get.call(this, k); },
			add: function (k, v) { joProps(this)[toJsStr(k)] = v || jsonNullSingleton; },
			"add(Ljava/lang/String;Lcom/google/gson/JsonElement;)V": function (k, v) { joProps(this)[toJsStr(k)] = v || jsonNullSingleton; },
			addProperty: function (k, v) {
				var jv = v === null || v === undefined ? jsonNullSingleton
					: ctx.makeObj("com/google/gson/JsonPrimitive", { _val: isJavaString(v) ? toJsStr(v) : (typeof v === "boolean" ? v : toNum(v)) });
				joProps(this)[toJsStr(k)] = jv;
			},
			"addProperty(Ljava/lang/String;Ljava/lang/String;)V": function (k, v) { joProps(this)[toJsStr(k)] = v == null ? jsonNullSingleton : ctx.makeObj("com/google/gson/JsonPrimitive", { _val: toJsStr(v) }); },
			"addProperty(Ljava/lang/String;Ljava/lang/Number;)V": function (k, v) { joProps(this)[toJsStr(k)] = v == null ? jsonNullSingleton : ctx.makeObj("com/google/gson/JsonPrimitive", { _val: toNum(v) }); },
			"addProperty(Ljava/lang/String;Ljava/lang/Boolean;)V": function (k, v) { joProps(this)[toJsStr(k)] = v == null ? jsonNullSingleton : ctx.makeObj("com/google/gson/JsonPrimitive", { _val: toBool(v) }); },
			"addProperty(Ljava/lang/String;Ljava/lang/Character;)V": function (k, v) { joProps(this)[toJsStr(k)] = v == null ? jsonNullSingleton : ctx.makeObj("com/google/gson/JsonPrimitive", { _val: String.fromCharCode(toCharCode(v)) }); },
			remove: function (k) { var p = joProps(this), s = toJsStr(k), old = p[s] || null; delete p[s]; return old; },
			"remove(Ljava/lang/String;)Lcom/google/gson/JsonElement;": function (k) { return joMethods.remove.call(this, k); },
			keySet: function () { return ctx.makeObj("java/util/LinkedHashSet", { _arr: Object.keys(joProps(this)) }); },
			"keySet()Ljava/util/Set;": function () { return joMethods.keySet.call(this); },
			entrySet: function () {
				var p = joProps(this), ks = Object.keys(p), es = [];
				for (var i = 0; i < ks.length; i++) { es.push(ctx.makeObj("java/util/Map$Entry", { _key: ks[i], _val: p[ks[i]] })); }
				return ctx.makeObj("java/util/LinkedHashSet", { _arr: es });
			},
			"entrySet()Ljava/util/Set;": function () { return joMethods.entrySet.call(this); },
			size: function () { return Object.keys(joProps(this)).length; },
			"size()I": function () { return Object.keys(joProps(this)).length; }
		};
		ctx.regClass("com/google/gson/JsonObject", { superName: "com/google/gson/JsonElement", methods: joMethods });

		var jaMethods = {
			"<init>": function () { this._arr = []; }, "<init>()V": function () { this._arr = []; },
			add: function (v) {
				if (!this._arr) { this._arr = []; }
				if (v && typeof v === "object" && getClassNameOf(v).indexOf("com/google/gson/") === 0) { this._arr.push(v); }
				else { this._arr.push(jsToJsonElement(v === null || v === undefined ? null : (isJavaString(v) ? toJsStr(v) : (typeof v === "boolean" ? v : toNum(v))))); }
			},
			"add(Lcom/google/gson/JsonElement;)V": function (v) { if (!this._arr) { this._arr = []; } this._arr.push(v || jsonNullSingleton); },
			"add(Ljava/lang/String;)V": function (v) { if (!this._arr) { this._arr = []; } this._arr.push(jsToJsonElement(v == null ? null : toJsStr(v))); },
			"add(Ljava/lang/Number;)V": function (v) { if (!this._arr) { this._arr = []; } this._arr.push(jsToJsonElement(v == null ? null : toNum(v))); },
			"add(Ljava/lang/Boolean;)V": function (v) { if (!this._arr) { this._arr = []; } this._arr.push(jsToJsonElement(v == null ? null : toBool(v))); },
			get: function (i) { return (this._arr || [])[toInt(i)]; }, "get(I)Lcom/google/gson/JsonElement;": function (i) { return (this._arr || [])[toInt(i)]; },
			size: function () { return (this._arr || []).length; }, "size()I": function () { return (this._arr || []).length; },
			isEmpty: function () { return (this._arr || []).length === 0; }, "isEmpty()Z": function () { return (this._arr || []).length === 0; },
			iterator: function () { return ctx.makeObj("java/util/Iterator", { _arr: this._arr || [], _idx: 0 }); },
			"iterator()Ljava/util/Iterator;": function () { return ctx.makeObj("java/util/Iterator", { _arr: this._arr || [], _idx: 0 }); }
		};
		ctx.regClass("com/google/gson/JsonArray", { superName: "com/google/gson/JsonElement", interfaces: ["java/lang/Iterable"], methods: jaMethods });

		function readAllReaderText(src) {
			if (isJavaString(src)) { return toJsStr(src); }
			if (src && typeof src._text === "string") { return src._text.slice(src._pos | 0); }
			if (src && typeof src.readLine === "function") {
				var lines = [], ln;
				while ((ln = src.readLine()) !== null) { lines.push(ln); }
				return lines.join("\n");
			}
			return toJsStr(src);
		}

		// ---- JsonParser ----
		var jpMethods = {};
		var jpStatics = {};
		addOverloads(jpStatics, function (s) { return jsToJsonElement(JSON.parse(toJsStr(s))); }, ["parseString", "parseString(Ljava/lang/String;)Lcom/google/gson/JsonElement;"]);
		addOverloads(jpStatics, function (r) { return jsToJsonElement(JSON.parse(readAllReaderText(r))); }, ["parseReader", "parseReader(Ljava/io/Reader;)Lcom/google/gson/JsonElement;"]);
		addOverloads(jpMethods, function () {}, ["<init>", "<init>()V"]);
		addOverloads(jpMethods, function (s) { return jsToJsonElement(JSON.parse(readAllReaderText(s))); },
			["parse", "parse(Ljava/lang/String;)Lcom/google/gson/JsonElement;", "parse(Ljava/io/Reader;)Lcom/google/gson/JsonElement;"]);
		ctx.regClass("com/google/gson/JsonParser", { methods: jpMethods, staticMethods: jpStatics });

		// ---- Gson & GsonBuilder ----
		var gsonMethods = {};
		gsonMethods["<init>"] = function () { this._pretty = false; this._serializeNulls = false; };
		gsonMethods["<init>()V"] = gsonMethods["<init>"];
		addOverloads(gsonMethods, function (src, dst) {
			var plain = javaObjToJsData(src, !!this._serializeNulls, []);
			var out = JSON.stringify(plain, null, this._pretty ? 2 : 0);
			if (arguments.length >= 2 && dst && typeof dst.write === "function") {
				dst.write(out);
				return;
			}
			return out;
		}, ["toJson", "toJson(Ljava/lang/Object;)Ljava/lang/String;", "toJson(Ljava/lang/Object;Ljava/lang/reflect/Type;)Ljava/lang/String;",
			"toJson(Lcom/google/gson/JsonElement;)Ljava/lang/String;", "toJson(Ljava/lang/Object;Ljava/lang/Appendable;)V"]);
		addOverloads(gsonMethods, function (src) { return jsToJsonElement(javaObjToJsData(src, !!this._serializeNulls, [])); },
			["toJsonTree", "toJsonTree(Ljava/lang/Object;)Lcom/google/gson/JsonElement;"]);
		addOverloads(gsonMethods, function (src, classOfT) {
			if (src === null || src === undefined) { return null; }
			var parsed;
			if (src && typeof src === "object" && getClassNameOf(src).indexOf("com/google/gson/") === 0) {
				parsed = jsonElementToJs(src);
			} else {
				var raw = readAllReaderText(src);
				if (!raw || !raw.trim()) { return null; }
				parsed = JSON.parse(raw);
			}
			var clsName = (classOfT && (classOfT._internalName || classOfT._name))
				? (classOfT._internalName || classOfT._name)
				: (typeof classOfT === "string" ? classOfT.replace(/\./g, "/") : null);
			return jsDataToJavaValue(parsed, clsName);
		}, ["fromJson", "fromJson(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
			"fromJson(Ljava/io/Reader;Ljava/lang/Class;)Ljava/lang/Object;",
			"fromJson(Lcom/google/gson/JsonElement;Ljava/lang/Class;)Ljava/lang/Object;",
			"fromJson(Ljava/lang/String;Ljava/lang/reflect/Type;)Ljava/lang/Object;",
			"fromJson(Ljava/io/Reader;Ljava/lang/reflect/Type;)Ljava/lang/Object;"]);
		ctx.regClass("com/google/gson/Gson", { methods: gsonMethods });

		var gbMethods = {
			"<init>": function () { this._pretty = false; this._serializeNulls = false; },
			"<init>()V": function () { this._pretty = false; this._serializeNulls = false; },
			setPrettyPrinting: function () { this._pretty = true; return this; },
			"setPrettyPrinting()Lcom/google/gson/GsonBuilder;": function () { this._pretty = true; return this; },
			serializeNulls: function () { this._serializeNulls = true; return this; },
			"serializeNulls()Lcom/google/gson/GsonBuilder;": function () { this._serializeNulls = true; return this; },
			disableHtmlEscaping: function () { return this; },
			"disableHtmlEscaping()Lcom/google/gson/GsonBuilder;": function () { return this; },
			setLenient: function () { return this; },
			"setLenient()Lcom/google/gson/GsonBuilder;": function () { return this; },
			excludeFieldsWithoutExposeAnnotation: function () { return this; },
			"excludeFieldsWithoutExposeAnnotation()Lcom/google/gson/GsonBuilder;": function () { return this; },
			registerTypeAdapter: function () { return this; },
			"registerTypeAdapter(Ljava/lang/reflect/Type;Ljava/lang/Object;)Lcom/google/gson/GsonBuilder;": function () { return this; },
			create: function () { return ctx.makeObj("com/google/gson/Gson", { _pretty: !!this._pretty, _serializeNulls: !!this._serializeNulls }); },
			"create()Lcom/google/gson/Gson;": function () { return ctx.makeObj("com/google/gson/Gson", { _pretty: !!this._pretty, _serializeNulls: !!this._serializeNulls }); }
		};
		ctx.regClass("com/google/gson/GsonBuilder", { methods: gbMethods });

		// ---- SLF4J & Log4j Loggers ----
		function makeLoggerMethods(prefixTag) {
			function emit(level, self, msg, rest) {
				var name = self._logName || "mod";
				var formatted = formatSlf4j(rt, msg, rest);
				var line = "[" + prefixTag + ":" + name + "] " + formatted;
				if (level === "error" || level === "fatal") { console.error(line); }
				else if (level === "warn") { console.warn(line); }
				else if (level === "debug" || level === "trace") { console.debug ? console.debug(line) : console.log(line); }
				else { console.info ? console.info(line) : console.log(line); }
			}
			var lm = {
				getName: function () { return this._logName || "mod"; },
				"getName()Ljava/lang/String;": function () { return this._logName || "mod"; }
			};
			var levels = ["info", "warn", "error", "debug", "trace", "fatal"];
			for (var i = 0; i < levels.length; i++) {
				(function (lv) {
					var fn = function (msg) {
						var rest = Array.prototype.slice.call(arguments, 1);
						emit(lv, this, msg, rest);
					};
					addOverloads(lm, fn, [
						lv,
						lv + "(Ljava/lang/String;)V",
						lv + "(Ljava/lang/Object;)V",
						lv + "(Ljava/lang/String;Ljava/lang/Object;)V",
						lv + "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
						lv + "(Ljava/lang/String;[Ljava/lang/Object;)V",
						lv + "(Ljava/lang/String;Ljava/lang/Throwable;)V"
					]);
					var enName = "is" + lv.charAt(0).toUpperCase() + lv.slice(1) + "Enabled";
					addOverloads(lm, function () { return lv !== "trace"; }, [enName, enName + "()Z"]);
				})(levels[i]);
			}
			return lm;
		}
		ctx.regClass("org/slf4j/Logger", { isInterface: true, methods: makeLoggerMethods("mod") });
		ctx.regClass("org/slf4j/LoggerFactory", {
			staticMethods: {
				getLogger: function (arg) {
					var cn = arg && (arg._internalName || arg._name);
					var n = cn ? cn.split("/").pop() : toJsStr(arg || "mod");
					return ctx.makeObj("org/slf4j/Logger", { _logName: n });
				},
				"getLogger(Ljava/lang/String;)Lorg/slf4j/Logger;": function (s) { return ctx.makeObj("org/slf4j/Logger", { _logName: toJsStr(s) }); },
				"getLogger(Ljava/lang/Class;)Lorg/slf4j/Logger;": function (c) { var cn = c && (c._internalName || c._name); var n = cn ? cn.split("/").pop() : toJsStr(c); return ctx.makeObj("org/slf4j/Logger", { _logName: n }); }
			}
		});
		ctx.regClass("org/apache/logging/log4j/Logger", { isInterface: true, methods: makeLoggerMethods("mod") });
		ctx.regClass("org/apache/logging/log4j/LogManager", {
			staticMethods: {
				getLogger: function (arg) {
					var cn = arg && (arg._internalName || arg._name);
					var n = !arg ? "mod" : (cn ? cn.split("/").pop() : toJsStr(arg));
					return ctx.makeObj("org/apache/logging/log4j/Logger", { _logName: n });
				},
				"getLogger()Lorg/apache/logging/log4j/Logger;": function () { return ctx.makeObj("org/apache/logging/log4j/Logger", { _logName: "mod" }); },
				"getLogger(Ljava/lang/String;)Lorg/apache/logging/log4j/Logger;": function (s) { return ctx.makeObj("org/apache/logging/log4j/Logger", { _logName: toJsStr(s) }); },
				"getLogger(Ljava/lang/Class;)Lorg/apache/logging/log4j/Logger;": function (c) { var cn = c && (c._internalName || c._name); var n = cn ? cn.split("/").pop() : toJsStr(c); return ctx.makeObj("org/apache/logging/log4j/Logger", { _logName: n }); }
			}
		});
	}

	// =========================================================================
	// 10. Fabric Loader, Fabric API Events, KeyBindingHelper & Brigadier Commands
	// =========================================================================

	function installFabricAndBrigadier(ctx) {
		var rt = ctx.rt;

		// ---- Fabric Entrypoint Interfaces & EnvType ----
		ctx.regClass("net/fabricmc/api/ModInitializer", { isInterface: true });
		ctx.regClass("net/fabricmc/api/ClientModInitializer", { isInterface: true });
		ctx.regClass("net/fabricmc/api/DedicatedServerModInitializer", { isInterface: true });
		ctx.regClass("net/fabricmc/api/Environment", { isInterface: true });

		var envStatics = {};
		ctx.regClass("net/fabricmc/api/EnvType", {
			superName: "java/lang/Enum",
			statics: envStatics,
			staticMethods: {
				values: function () { return makeObjectArray(rt, "Lnet/fabricmc/api/EnvType;", [envStatics.CLIENT, envStatics.SERVER]); },
				valueOf: function (n) { return envStatics[toJsStr(n)] || envStatics.CLIENT; }
			}
		});
		envStatics.CLIENT = ctx.makeObj("net/fabricmc/api/EnvType", { _enumName: "CLIENT", _ordinal: 0 });
		envStatics.SERVER = ctx.makeObj("net/fabricmc/api/EnvType", { _enumName: "SERVER", _ordinal: 1 });
		ctx.setStaticField("net/fabricmc/api/EnvType", "CLIENT", envStatics.CLIENT);
		ctx.setStaticField("net/fabricmc/api/EnvType", "SERVER", envStatics.SERVER);

		// ---- FabricLoader, ModContainer, ModMetadata, Version ----
		ctx.regClass("net/fabricmc/loader/api/Version", {
			isInterface: true,
			methods: {
				getFriendlyString: function () { return this._ver || "1.0.0"; },
				"getFriendlyString()Ljava/lang/String;": function () { return this._ver || "1.0.0"; },
				toString: function () { return this._ver || "1.0.0"; },
				"toString()Ljava/lang/String;": function () { return this._ver || "1.0.0"; }
			}
		});
		ctx.regClass("net/fabricmc/loader/api/metadata/ModMetadata", {
			isInterface: true,
			methods: {
				getId: function () { return this._id || ""; }, "getId()Ljava/lang/String;": function () { return this._id || ""; },
				getName: function () { return this._name || this._id || ""; }, "getName()Ljava/lang/String;": function () { return this._name || this._id || ""; },
				getVersion: function () { return ctx.makeObj("net/fabricmc/loader/api/Version", { _ver: this._version || "1.0.0" }); },
				"getVersion()Lnet/fabricmc/loader/api/Version;": function () { return this.getVersion(); },
				getDescription: function () { return this._description || ""; }, "getDescription()Ljava/lang/String;": function () { return this._description || ""; },
				getType: function () { return "fabric"; }, "getType()Ljava/lang/String;": function () { return "fabric"; }
			}
		});
		ctx.regClass("net/fabricmc/loader/api/ModContainer", {
			isInterface: true,
			methods: {
				getMetadata: function () { return this._meta; }, "getMetadata()Lnet/fabricmc/loader/api/metadata/ModMetadata;": function () { return this._meta; },
				getRootPaths: function () { return ctx.makeObj("java/util/ArrayList", { _arr: [ctx.makeObj("java/nio/file/Path", { _path: "/" })] }); },
				findPath: function (rel) { return ctx.nativeDefs["java/util/Optional"].staticMethods.of(ctx.makeObj("java/nio/file/Path", { _path: VFS.normalize(toJsStr(rel)) })); }
			}
		});

		var BUILTIN_MODS = [
			{ id: "minecraft", name: "Minecraft", version: "26.3", description: "Minecraft 26.3 Browser Edition" },
			{ id: "java", name: "OpenJDK", version: "21", description: "minecraft-web In-Browser JVM" },
			{ id: "fabricloader", name: "Fabric Loader", version: "0.16.10", description: "In-Browser Fabric Loader Bridge" },
			{ id: "fabric", name: "Fabric API", version: "0.115.0", description: "In-Browser Fabric API Bridge" },
			{ id: "fabric-api", name: "Fabric API", version: "0.115.0", description: "In-Browser Fabric API Bridge" },
			{ id: "fabric-api-base", name: "Fabric API Base", version: "0.4.54", description: "" },
			{ id: "fabric-lifecycle-events-v1", name: "Fabric Lifecycle Events", version: "2.5.4", description: "" },
			{ id: "fabric-rendering-v1", name: "Fabric Rendering", version: "10.2.1", description: "" },
			{ id: "fabric-key-binding-api-v1", name: "Fabric Key Binding API", version: "1.0.57", description: "" },
			{ id: "fabric-command-api-v2", name: "Fabric Command API", version: "2.2.41", description: "" },
			{ id: "fabric-networking-api-v1", name: "Fabric Networking API", version: "4.3.10", description: "" },
			{ id: "fabric-message-api-v1", name: "Fabric Message API", version: "6.0.25", description: "" },
			{ id: "fabric-screen-api-v1", name: "Fabric Screen API", version: "2.0.38", description: "" }
		];

		function collectAllModContainers() {
			var byId = Object.create(null);
			var out = [];
			function addMod(m) {
				if (!m || !m.id || byId[m.id]) { return; }
				byId[m.id] = true;
				var meta = ctx.makeObj("net/fabricmc/loader/api/metadata/ModMetadata", {
					_id: String(m.id), _name: String(m.name || m.id),
					_version: String(m.version || "1.0.0"), _description: String(m.description || "")
				});
				out.push(ctx.makeObj("net/fabricmc/loader/api/ModContainer", { _meta: meta }));
			}
			for (var i = 0; i < BUILTIN_MODS.length; i++) { addMod(BUILTIN_MODS[i]); }
			if (rt && rt.mods) {
				var list = typeof rt.mods.values === "function" ? Array.from(rt.mods.values())
					: (Array.isArray(rt.mods) ? rt.mods : Object.keys(rt.mods).map(function (k) { return rt.mods[k]; }));
				for (var j = 0; j < list.length; j++) { addMod(list[j]); }
			}
			if (root.EaglerJvmTranslator && typeof root.EaglerJvmTranslator.listTranslatedMods === "function") {
				try {
					var trMods = root.EaglerJvmTranslator.listTranslatedMods() || [];
					for (var t = 0; t < trMods.length; t++) { addMod(trMods[t]); }
				} catch (e) {}
			}
			var em = ctx.eagler();
			if (em && typeof em.list === "function") {
				try {
					var emMods = em.list() || [];
					for (var k = 0; k < emMods.length; k++) {
						if (emMods[k] && emMods[k].enabled) { addMod(emMods[k]); }
					}
				} catch (e) {}
			}
			return out;
		}

		var flMethods = {
			isModLoaded: function (id) {
				var want = toJsStr(id);
				var all = collectAllModContainers();
				for (var i = 0; i < all.length; i++) {
					if (all[i]._meta._id === want) { return true; }
				}
				return false;
			},
			"isModLoaded(Ljava/lang/String;)Z": function (id) { return flMethods.isModLoaded.call(this, id); },
			getConfigDir: function () { VFS.mkdirs("/config"); return ctx.makeObj("java/nio/file/Path", { _path: "/config" }); },
			"getConfigDir()Ljava/nio/file/Path;": function () { return flMethods.getConfigDir.call(this); },
			getGameDir: function () { return ctx.makeObj("java/nio/file/Path", { _path: "/" }); },
			"getGameDir()Ljava/nio/file/Path;": function () { return flMethods.getGameDir.call(this); },
			getAllMods: function () { return ctx.makeObj("java/util/ArrayList", { _arr: collectAllModContainers() }); },
			"getAllMods()Ljava/util/Collection;": function () { return flMethods.getAllMods.call(this); },
			getModContainer: function (id) {
				var want = toJsStr(id), all = collectAllModContainers();
				for (var i = 0; i < all.length; i++) {
					if (all[i]._meta._id === want) { return ctx.nativeDefs["java/util/Optional"].staticMethods.of(all[i]); }
				}
				return ctx.nativeDefs["java/util/Optional"].staticMethods.empty();
			},
			"getModContainer(Ljava/lang/String;)Ljava/util/Optional;": function (id) { return flMethods.getModContainer.call(this, id); },
			getEnvironmentType: function () { return envStatics.CLIENT; },
			"getEnvironmentType()Lnet/fabricmc/api/EnvType;": function () { return envStatics.CLIENT; },
			isDevelopmentEnvironment: function () { return false; },
			"isDevelopmentEnvironment()Z": function () { return false; },
			getObjectShare: function () {
				if (!ctx.fabricObjectShare) { ctx.fabricObjectShare = ctx.makeObj("java/util/HashMap", { _entries: [] }); }
				return ctx.fabricObjectShare;
			}
		};
		ctx.regClass("net/fabricmc/loader/api/FabricLoader", {
			isInterface: true,
			methods: flMethods,
			staticMethods: {
				getInstance: function () {
					if (!ctx.fabricLoaderSingleton) { ctx.fabricLoaderSingleton = ctx.makeObj("net/fabricmc/loader/api/FabricLoader"); }
					return ctx.fabricLoaderSingleton;
				},
				"getInstance()Lnet/fabricmc/loader/api/FabricLoader;": function () {
					return ctx.nativeDefs["net/fabricmc/loader/api/FabricLoader"].staticMethods.getInstance();
				}
			}
		});

		// ---- Fabric Event & EventFactory ----
		function createFabricEvent(onReg, invokerFactory) {
			return ctx.makeObj("net/fabricmc/fabric/api/event/Event", {
				_listeners: [],
				_fastListeners: [],
				_cachedInvoker: null,
				_onRegister: onReg || null,
				_invokerFactory: invokerFactory || null
			});
		}
		var eventMethods = {
			register: function (a, b) {
				var listener = arguments.length >= 2 ? b : a;
				if (!this._listeners) { this._listeners = []; }
				if (!this._fastListeners) { this._fastListeners = []; }
				if (listener) {
					this._listeners.push(listener);
					this._fastListeners.push(compileFastCallbackInvoker(rt, listener, ["run", "accept", "apply", "onEvent"], null, -1));
					this._cachedInvoker = null;
					if (typeof this._onRegister === "function") { this._onRegister(listener); }
				}
			},
			"register(Ljava/lang/Object;)V": function (l) { eventMethods.register.call(this, l); },
			"register(Lnet/minecraft/resources/Identifier;Ljava/lang/Object;)V": function (p, l) { eventMethods.register.call(this, p, l); },
			"register(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)V": function (p, l) { eventMethods.register.call(this, p, l); },
			invoker: function () {
				if (this._cachedInvoker) { return this._cachedInvoker; }
				var listeners = this._listeners || [];
				if (typeof this._invokerFactory === "function") {
					this._cachedInvoker = this._invokerFactory(listeners);
					return this._cachedInvoker;
				}
				if (this._invokerFactory && typeof this._invokerFactory === "object") {
					this._cachedInvoker = invokeJavaCallback(rt, this._invokerFactory, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;",
						[makeObjectArray(rt, "Ljava/lang/Object;", listeners.slice())]);
					return this._cachedInvoker;
				}
				var fast = this._fastListeners || [];
				var inv = {
					__listeners: listeners,
					invoke: function () {
						var res;
						for (var i = 0, n = fast.length; i < n; i++) {
							res = fast[i].apply(null, arguments);
						}
						return res;
					}
				};
				this._cachedInvoker = inv;
				return inv;
			},
			"invoker()Ljava/lang/Object;": function () { return eventMethods.invoker.call(this); }
		};
		ctx.regClass("net/fabricmc/fabric/api/event/Event", { methods: eventMethods });
		ctx.regClass("net/fabricmc/fabric/api/event/EventFactory", {
			staticMethods: {
				createArrayBacked: function (type, invokerFactory) {
					return createFabricEvent(null, invokerFactory);
				},
				"createArrayBacked(Ljava/lang/Class;Ljava/util/function/Function;)Lnet/fabricmc/fabric/api/event/Event;": function (t, f) {
					return createFabricEvent(null, f);
				},
				"createArrayBacked(Ljava/lang/Class;Ljava/lang/Object;Ljava/util/function/Function;)Lnet/fabricmc/fabric/api/event/Event;": function (t, _empty, f) {
					return createFabricEvent(null, f);
				}
			}
		});

		// ---- ClientTickEvents (flat dense pre-resolved listener arrays + single hook dispatcher) ----
		function ensureTickStartHook() {
			if (ctx._unregTickStartHook) { return; }
			ctx._unregTickStartHook = registerHookOn(ctx, "__fabric_tick__", "tickStart", function (ev) {
				if (ctx.dispatchDepth === 0) {
					ctx.refreshStateCache(ev && ev.state, -1);
				}
				var fns = ctx._tickStartFns;
				var n = fns.length;
				if (n > 0) {
					var mc = getMinecraftInstance(ctx);
					for (var i = 0; i < n; i++) { fns[i](mc); }
				}
				var wfns = ctx._worldTickStartFns;
				var wn = wfns.length;
				if (wn > 0) {
					var st = (ev && ev.state) || ctx.getGameState(false);
					if (st && st.inWorld) {
						var lvl = getLevelInstance(ctx);
						if (lvl) {
							for (var j = 0; j < wn; j++) { wfns[j](lvl); }
						}
					}
				}
			});
		}
		function ensureTickEndHook() {
			if (ctx._unregTickEndHook) { return; }
			ctx._unregTickEndHook = registerHookOn(ctx, "__fabric_tick__", "tickEnd", function (ev) {
				var fns = ctx._tickEndFns;
				var n = fns.length;
				if (n > 0) {
					var mc = getMinecraftInstance(ctx);
					for (var i = 0; i < n; i++) { fns[i](mc); }
				}
				var wfns = ctx._worldTickEndFns;
				var wn = wfns.length;
				if (wn > 0) {
					var st = (ev && ev.state) || ctx.getGameState(false);
					if (st && st.inWorld) {
						var lvl = getLevelInstance(ctx);
						if (lvl) {
							for (var j = 0; j < wn; j++) { wfns[j](lvl); }
						}
					}
				}
			});
		}
		var tickStartEv = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_tick__";
			ctx.tickStartListeners.push(l);
			ctx._tickStartFns.push(compileFastCallbackInvoker(rt, l, ["onStartTick", "accept"], "(Lnet/minecraft/client/Minecraft;)V", 1));
			ctx._tickStartModIds.push(ownerId);
			ensureTickStartHook();
		});
		var tickEndEv = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_tick__";
			ctx.tickEndListeners.push(l);
			ctx._tickEndFns.push(compileFastCallbackInvoker(rt, l, ["onEndTick", "accept"], "(Lnet/minecraft/client/Minecraft;)V", 1));
			ctx._tickEndModIds.push(ownerId);
			ensureTickEndHook();
		});
		var worldTickStartEv = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_tick__";
			ctx.worldTickStartListeners.push(l);
			ctx._worldTickStartFns.push(compileFastCallbackInvoker(rt, l, ["onStartTick", "accept"], "(Lnet/minecraft/client/multiplayer/ClientLevel;)V", 1));
			ctx._worldTickStartModIds.push(ownerId);
			ensureTickStartHook();
		});
		var worldTickEndEv = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_tick__";
			ctx.worldTickEndListeners.push(l);
			ctx._worldTickEndFns.push(compileFastCallbackInvoker(rt, l, ["onEndTick", "accept"], "(Lnet/minecraft/client/multiplayer/ClientLevel;)V", 1));
			ctx._worldTickEndModIds.push(ownerId);
			ensureTickEndHook();
		});
		ctx.regClass("net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents", {
			statics: {
				START_CLIENT_TICK: tickStartEv,
				END_CLIENT_TICK: tickEndEv,
				START_WORLD_TICK: worldTickStartEv,
				END_WORLD_TICK: worldTickEndEv
			}
		});
		ctx.regClass("net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$StartTick", { isInterface: true });
		ctx.regClass("net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$EndTick", { isInterface: true });
		ctx.regClass("net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$StartWorldTick", { isInterface: true });
		ctx.regClass("net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$EndWorldTick", { isInterface: true });

		// ---- HudRenderCallback & HudElementRegistry (26.x / 1.21.6+) ----
		function ensureHudRenderHook() {
			if (ctx._unregHudHook) { return; }
			ctx._unregHudHook = registerHookOn(ctx, "__fabric_hud__", "hudRender", function (ev) {
				var fns = ctx._hudRenderFns;
				var n = fns.length;
				if (n === 0) { return; }
				var gg = (ev && ev.guiGraphics) || getGuiGraphicsInstance(ctx);
				var dt = (ev && ev.deltaTracker) || getDeltaTrackerInstance(ctx, ev && ev.tickDelta);
				for (var i = 0; i < n; i++) { fns[i](gg, dt); }
			});
		}
		function registerHudCb(l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_hud__";
			ctx.hudCallbacks.push(l);
			ctx._hudRenderFns.push(compileFastCallbackInvoker(rt, l, ["onHudRender", "render", "accept"],
				"(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", 2));
			ctx._hudRenderModIds.push(ownerId);
			ensureHudRenderHook();
		}
		var hudEvent = createFabricEvent(registerHudCb);
		ctx.regClass("net/fabricmc/fabric/api/client/rendering/v1/HudRenderCallback", {
			isInterface: true,
			statics: { EVENT: hudEvent }
		});
		var hudElemRegStatics = {};
		addOverloads(hudElemRegStatics, function (a, b, c) {
			var elem = arguments.length >= 3 ? c : b;
			if (elem) { registerHudCb(elem); }
		}, ["attachElementBefore", "attachElementAfter", "addFirst", "addLast", "replaceElement"]);
		ctx.regClass("net/fabricmc/fabric/api/client/rendering/v1/hud/HudElementRegistry", { staticMethods: hudElemRegStatics });
		ctx.regClass("net/fabricmc/fabric/api/client/rendering/v1/hud/HudElement", { isInterface: true });

		// ---- KeyBindingHelper ----
		var kbhStatics = {};
		addOverloads(kbhStatics, function (km) {
			if (km && InputState.registeredMappings.indexOf(km) < 0) {
				InputState.registeredMappings.push(km);
			}
			InputState.attachDomListeners();
			return km;
		}, ["registerKeyBinding", "registerKeyBinding(Lnet/minecraft/client/KeyMapping;)Lnet/minecraft/client/KeyMapping;"]);
		addOverloads(kbhStatics, function (km) {
			return km && typeof km.getDefaultKey === "function" ? km.getDefaultKey()
				: ctx.makeObj("com/mojang/blaze3d/platform/InputConstants$Key", { _type: "KEYSYM", _code: km ? (km._keyCode | 0) : -1 });
		}, ["getBoundKeyOf", "getBoundKeyOf(Lnet/minecraft/client/KeyMapping;)Lcom/mojang/blaze3d/platform/InputConstants$Key;"]);
		ctx.regClass("net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper", { staticMethods: kbhStatics });

		// ---- ClientPlayConnectionEvents ----
		var joinEv = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_net__";
			var inv = compileFastCallbackInvoker(rt, l, ["onPlayReady", "accept"], null, 3);
			ctx.worldJoinListeners.push(l);
			registerHookOn(ctx, ownerId, "worldJoin", function () {
				inv(null, null, getMinecraftInstance(ctx));
			});
		});
		var discEv = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_net__";
			var inv = compileFastCallbackInvoker(rt, l, ["onPlayDisconnect", "accept"], null, 2);
			ctx.worldDisconnectListeners.push(l);
			registerHookOn(ctx, ownerId, "worldLeave", function () {
				inv(null, getMinecraftInstance(ctx));
			});
		});
		ctx.regClass("net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents", {
			statics: { JOIN: joinEv, DISCONNECT: discEv, INIT: joinEv }
		});
		ctx.regClass("net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents$Join", { isInterface: true });
		ctx.regClass("net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents$Disconnect", { isInterface: true });

		// ---- ClientReceiveMessageEvents & ClientSendMessageEvents ----
		var rcvGame = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_msg__";
			var inv = compileFastCallbackInvoker(rt, l, ["onReceiveGameMessage", "accept"], null, 2);
			ctx.chatReceiveGameListeners.push(l);
			registerHookOn(ctx, ownerId, "chatReceive", function (ev) {
				if (ev && ev.cancelled) { return; }
				inv(makeComponentLiteral(ctx, ev ? ev.message : ""), !!(ev && ev.overlay));
			});
		});
		var rcvAllowGame = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_msg__";
			var inv = compileFastCallbackInvoker(rt, l, ["allowReceiveGameMessage", "test"], null, 2);
			ctx.chatReceiveAllowGameListeners.push(l);
			registerHookOn(ctx, ownerId, "chatReceive", function (ev) {
				if (ev && ev.cancelled) { return; }
				var ok = inv(makeComponentLiteral(ctx, ev ? ev.message : ""), !!(ev && ev.overlay));
				if (ok === false || ok === 0) { if (ev) { ev.cancelled = true; } }
			});
		});
		var rcvChat = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_msg__";
			var inv = compileFastCallbackInvoker(rt, l, ["onReceiveChatMessage", "accept"], null, -1);
			ctx.chatReceiveChatListeners.push(l);
			registerHookOn(ctx, ownerId, "chatReceive", function (ev) {
				if (ev && ev.cancelled) { return; }
				inv(makeComponentLiteral(ctx, ev ? ev.message : ""), null, null, null, null);
			});
		});
		var rcvAllowChat = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_msg__";
			var inv = compileFastCallbackInvoker(rt, l, ["allowReceiveChatMessage", "test"], null, -1);
			ctx.chatReceiveAllowChatListeners.push(l);
			registerHookOn(ctx, ownerId, "chatReceive", function (ev) {
				if (ev && ev.cancelled) { return; }
				var ok = inv(makeComponentLiteral(ctx, ev ? ev.message : ""), null, null, null, null);
				if (ok === false || ok === 0) { if (ev) { ev.cancelled = true; } }
			});
		});
		ctx.regClass("net/fabricmc/fabric/api/client/message/v1/ClientReceiveMessageEvents", {
			statics: { GAME: rcvGame, ALLOW_GAME: rcvAllowGame, CHAT: rcvChat, ALLOW_CHAT: rcvAllowChat }
		});

		var sndAllowChat = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_msg__";
			var inv = compileFastCallbackInvoker(rt, l, ["allowSendChatMessage", "test"], "(Ljava/lang/String;)Z", 1);
			ctx.chatSendAllowChatListeners.push(l);
			registerHookOn(ctx, ownerId, "chatSend", function (ev) {
				if (!ev || ev.cancelled || String(ev.message || "").indexOf("/") === 0) { return; }
				var ok = inv(String(ev.message || ""));
				if (ok === false || ok === 0) { ev.cancelled = true; }
			});
		});
		var sndChat = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_msg__";
			var inv = compileFastCallbackInvoker(rt, l, ["onSendChatMessage", "accept"], "(Ljava/lang/String;)V", 1);
			ctx.chatSendChatListeners.push(l);
			registerHookOn(ctx, ownerId, "chatSend", function (ev) {
				if (!ev || ev.cancelled || String(ev.message || "").indexOf("/") === 0) { return; }
				inv(String(ev.message || ""));
			});
		});
		var sndAllowCmd = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_msg__";
			var inv = compileFastCallbackInvoker(rt, l, ["allowSendCommandMessage", "test"], "(Ljava/lang/String;)Z", 1);
			ctx.chatSendAllowCmdListeners.push(l);
			registerHookOn(ctx, ownerId, "chatSend", function (ev) {
				if (!ev || ev.cancelled || String(ev.message || "").indexOf("/") !== 0) { return; }
				var ok = inv(String(ev.message).slice(1));
				if (ok === false || ok === 0) { ev.cancelled = true; }
			});
		});
		var sndCmd = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_msg__";
			var inv = compileFastCallbackInvoker(rt, l, ["onSendCommandMessage", "accept"], "(Ljava/lang/String;)V", 1);
			ctx.chatSendCmdListeners.push(l);
			registerHookOn(ctx, ownerId, "chatSend", function (ev) {
				if (!ev || ev.cancelled || String(ev.message || "").indexOf("/") !== 0) { return; }
				inv(String(ev.message).slice(1));
			});
		});
		ctx.regClass("net/fabricmc/fabric/api/client/message/v1/ClientSendMessageEvents", {
			statics: { ALLOW_CHAT: sndAllowChat, CHAT: sndChat, ALLOW_COMMAND: sndAllowCmd, COMMAND: sndCmd }
		});

		// ---- ScreenEvents ----
		var scrBefore = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_scr__";
			var inv = compileFastCallbackInvoker(rt, l, ["beforeInit", "accept"], null, 4);
			ctx.screenBeforeInitListeners.push(l);
			registerHookOn(ctx, ownerId, "screenInit", function (ev) {
				var win = getWindowInstance(ctx);
				inv(getMinecraftInstance(ctx), (ev && ev.screenObj) || makeScreenObj(ctx, ev && ev.screen, ev && ev.title), win.getGuiScaledWidth(), win.getGuiScaledHeight());
			});
		});
		var scrAfter = createFabricEvent(function (l) {
			var ownerId = (rt && rt.currentModId) || "__fabric_scr__";
			var inv = compileFastCallbackInvoker(rt, l, ["afterInit", "accept"], null, 4);
			ctx.screenAfterInitListeners.push(l);
			registerHookOn(ctx, ownerId, "screenInit", function (ev) {
				var win = getWindowInstance(ctx);
				inv(getMinecraftInstance(ctx), (ev && ev.screenObj) || makeScreenObj(ctx, ev && ev.screen, ev && ev.title), win.getGuiScaledWidth(), win.getGuiScaledHeight());
			});
		});
		ctx.regClass("net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", {
			statics: { BEFORE_INIT: scrBefore, AFTER_INIT: scrAfter }
		});
		var noopScreenEvt = createFabricEvent(function () {});
		ctx.regClass("net/fabricmc/fabric/impl/client/screen/ScreenExtensions", {
			isInterface: true,
			methods: {
				fabric_getRemoveEvent: function () { return noopScreenEvt; },
				fabric_getBeforeRenderEvent: function () { return noopScreenEvt; },
				fabric_getAfterRenderEvent: function () { return noopScreenEvt; },
				fabric_getBeforeTickEvent: function () { return noopScreenEvt; },
				fabric_getAfterTickEvent: function () { return noopScreenEvt; },
				fabric_getAllowMouseClickEvent: function () { return noopScreenEvt; },
				fabric_getBeforeMouseClickEvent: function () { return noopScreenEvt; },
				fabric_getAfterMouseClickEvent: function () { return noopScreenEvt; },
				fabric_getAllowMouseReleaseEvent: function () { return noopScreenEvt; },
				fabric_getBeforeMouseReleaseEvent: function () { return noopScreenEvt; },
				fabric_getAfterMouseReleaseEvent: function () { return noopScreenEvt; },
				fabric_getAllowMouseScrollEvent: function () { return noopScreenEvt; },
				fabric_getBeforeMouseScrollEvent: function () { return noopScreenEvt; },
				fabric_getAfterMouseScrollEvent: function () { return noopScreenEvt; },
				fabric_getAllowKeyPressEvent: function () { return noopScreenEvt; },
				fabric_getBeforeKeyPressEvent: function () { return noopScreenEvt; },
				fabric_getAfterKeyPressEvent: function () { return noopScreenEvt; },
				fabric_getAllowKeyReleaseEvent: function () { return noopScreenEvt; },
				fabric_getBeforeKeyReleaseEvent: function () { return noopScreenEvt; },
				fabric_getAfterKeyReleaseEvent: function () { return noopScreenEvt; },
				fabric_getButtons: function () { return makeJavaList(ctx, []); }
			}
		});
		ctx._noopScreenEvt = noopScreenEvt;

		// ---- Brigadier Command Engine ----
		ctx.regClass("com/mojang/brigadier/Command", { isInterface: true });
		ctx.regClass("com/mojang/brigadier/arguments/ArgumentType", { isInterface: true });
		ctx.regClass("com/mojang/brigadier/tree/CommandNode", {});
		ctx.regClass("com/mojang/brigadier/tree/LiteralCommandNode", { superName: "com/mojang/brigadier/tree/CommandNode" });
		ctx.regClass("com/mojang/brigadier/tree/ArgumentCommandNode", { superName: "com/mojang/brigadier/tree/CommandNode" });

		// Argument Types
		ctx.regClass("com/mojang/brigadier/arguments/StringArgumentType", {
			interfaces: ["com/mojang/brigadier/arguments/ArgumentType"],
			staticMethods: {
				word: function () { return ctx.makeObj("com/mojang/brigadier/arguments/StringArgumentType", { _kind: "word" }); },
				"word()Lcom/mojang/brigadier/arguments/StringArgumentType;": function () { return ctx.makeObj("com/mojang/brigadier/arguments/StringArgumentType", { _kind: "word" }); },
				string: function () { return ctx.makeObj("com/mojang/brigadier/arguments/StringArgumentType", { _kind: "string" }); },
				"string()Lcom/mojang/brigadier/arguments/StringArgumentType;": function () { return ctx.makeObj("com/mojang/brigadier/arguments/StringArgumentType", { _kind: "string" }); },
				greedyString: function () { return ctx.makeObj("com/mojang/brigadier/arguments/StringArgumentType", { _kind: "greedy" }); },
				"greedyString()Lcom/mojang/brigadier/arguments/StringArgumentType;": function () { return ctx.makeObj("com/mojang/brigadier/arguments/StringArgumentType", { _kind: "greedy" }); },
				getString: function (c, name) { return toJsStr(c.getArgument(name, null)); },
				"getString(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)Ljava/lang/String;": function (c, name) { return toJsStr(c.getArgument(name, null)); }
			}
		});
		ctx.regClass("com/mojang/brigadier/arguments/IntegerArgumentType", {
			interfaces: ["com/mojang/brigadier/arguments/ArgumentType"],
			staticMethods: {
				integer: function (min, max) { return ctx.makeObj("com/mojang/brigadier/arguments/IntegerArgumentType", { _kind: "int", _min: arguments.length >= 1 ? toInt(min) : -2147483648, _max: arguments.length >= 2 ? toInt(max) : 2147483647 }); },
				"integer()Lcom/mojang/brigadier/arguments/IntegerArgumentType;": function () { return ctx.nativeDefs["com/mojang/brigadier/arguments/IntegerArgumentType"].staticMethods.integer(); },
				"integer(I)Lcom/mojang/brigadier/arguments/IntegerArgumentType;": function (lo) { return ctx.nativeDefs["com/mojang/brigadier/arguments/IntegerArgumentType"].staticMethods.integer(lo); },
				"integer(II)Lcom/mojang/brigadier/arguments/IntegerArgumentType;": function (lo, hi) { return ctx.nativeDefs["com/mojang/brigadier/arguments/IntegerArgumentType"].staticMethods.integer(lo, hi); },
				getInteger: function (c, name) { return toInt(c.getArgument(name, null)); },
				"getInteger(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)I": function (c, name) { return toInt(c.getArgument(name, null)); }
			}
		});
		ctx.regClass("com/mojang/brigadier/arguments/LongArgumentType", {
			interfaces: ["com/mojang/brigadier/arguments/ArgumentType"],
			staticMethods: {
				longArg: function (min, max) { return ctx.makeObj("com/mojang/brigadier/arguments/LongArgumentType", { _kind: "long", _min: arguments.length >= 1 ? toNum(min) : -9007199254740991, _max: arguments.length >= 2 ? toNum(max) : 9007199254740991 }); },
				getLong: function (c, name) { return makeLong(ctx, c.getArgument(name, null)); },
				"getLong(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)J": function (c, name) { return makeLong(ctx, c.getArgument(name, null)); }
			}
		});
		ctx.regClass("com/mojang/brigadier/arguments/FloatArgumentType", {
			interfaces: ["com/mojang/brigadier/arguments/ArgumentType"],
			staticMethods: {
				floatArg: function (min, max) { return ctx.makeObj("com/mojang/brigadier/arguments/FloatArgumentType", { _kind: "float", _min: arguments.length >= 1 ? toNum(min) : -3.4028235e38, _max: arguments.length >= 2 ? toNum(max) : 3.4028235e38 }); },
				"floatArg()Lcom/mojang/brigadier/arguments/FloatArgumentType;": function () { return ctx.nativeDefs["com/mojang/brigadier/arguments/FloatArgumentType"].staticMethods.floatArg(); },
				"floatArg(F)Lcom/mojang/brigadier/arguments/FloatArgumentType;": function (lo) { return ctx.nativeDefs["com/mojang/brigadier/arguments/FloatArgumentType"].staticMethods.floatArg(lo); },
				"floatArg(FF)Lcom/mojang/brigadier/arguments/FloatArgumentType;": function (lo, hi) { return ctx.nativeDefs["com/mojang/brigadier/arguments/FloatArgumentType"].staticMethods.floatArg(lo, hi); },
				getFloat: function (c, name) { return Math.fround(toNum(c.getArgument(name, null))); },
				"getFloat(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)F": function (c, name) { return Math.fround(toNum(c.getArgument(name, null))); }
			}
		});
		ctx.regClass("com/mojang/brigadier/arguments/DoubleArgumentType", {
			interfaces: ["com/mojang/brigadier/arguments/ArgumentType"],
			staticMethods: {
				doubleArg: function (min, max) { return ctx.makeObj("com/mojang/brigadier/arguments/DoubleArgumentType", { _kind: "double", _min: arguments.length >= 1 ? toNum(min) : -Number.MAX_VALUE, _max: arguments.length >= 2 ? toNum(max) : Number.MAX_VALUE }); },
				"doubleArg()Lcom/mojang/brigadier/arguments/DoubleArgumentType;": function () { return ctx.nativeDefs["com/mojang/brigadier/arguments/DoubleArgumentType"].staticMethods.doubleArg(); },
				"doubleArg(D)Lcom/mojang/brigadier/arguments/DoubleArgumentType;": function (lo) { return ctx.nativeDefs["com/mojang/brigadier/arguments/DoubleArgumentType"].staticMethods.doubleArg(lo); },
				"doubleArg(DD)Lcom/mojang/brigadier/arguments/DoubleArgumentType;": function (lo, hi) { return ctx.nativeDefs["com/mojang/brigadier/arguments/DoubleArgumentType"].staticMethods.doubleArg(lo, hi); },
				getDouble: function (c, name) { return toNum(c.getArgument(name, null)); },
				"getDouble(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)D": function (c, name) { return toNum(c.getArgument(name, null)); }
			}
		});
		ctx.regClass("com/mojang/brigadier/arguments/BoolArgumentType", {
			interfaces: ["com/mojang/brigadier/arguments/ArgumentType"],
			staticMethods: {
				bool: function () { return ctx.makeObj("com/mojang/brigadier/arguments/BoolArgumentType", { _kind: "bool" }); },
				"bool()Lcom/mojang/brigadier/arguments/BoolArgumentType;": function () { return ctx.makeObj("com/mojang/brigadier/arguments/BoolArgumentType", { _kind: "bool" }); },
				getBool: function (c, name) { return toBool(c.getArgument(name, null)); },
				"getBool(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)Z": function (c, name) { return toBool(c.getArgument(name, null)); }
			}
		});

		// Argument Builders
		var builderMethods = {
			then: function (child) {
				if (!this._children) { this._children = []; }
				if (child) { this._children.push(child); }
				return this;
			},
			"then(Lcom/mojang/brigadier/builder/ArgumentBuilder;)Lcom/mojang/brigadier/builder/ArgumentBuilder;": function (c) { return builderMethods.then.call(this, c); },
			"then(Lcom/mojang/brigadier/tree/CommandNode;)Lcom/mojang/brigadier/builder/ArgumentBuilder;": function (c) { return builderMethods.then.call(this, c); },
			executes: function (cmd) { this._command = cmd; return this; },
			"executes(Lcom/mojang/brigadier/Command;)Lcom/mojang/brigadier/builder/ArgumentBuilder;": function (c) { this._command = c; return this; },
			requires: function (pred) { this._requires = pred; return this; },
			"requires(Ljava/util/function/Predicate;)Lcom/mojang/brigadier/builder/ArgumentBuilder;": function (p) { this._requires = p; return this; },
			redirect: function (target) { this._redirect = target; return this; },
			suggests: function () { return this; },
			build: function () { return this; }
		};
		ctx.regClass("com/mojang/brigadier/builder/ArgumentBuilder", { methods: builderMethods });
		ctx.regClass("com/mojang/brigadier/builder/LiteralArgumentBuilder", {
			superName: "com/mojang/brigadier/builder/ArgumentBuilder",
			methods: builderMethods,
			staticMethods: {
				literal: function (name) {
					return ctx.makeObj("com/mojang/brigadier/builder/LiteralArgumentBuilder", {
						_nodeKind: "literal", _literal: toJsStr(name), _children: [], _command: null
					});
				},
				"literal(Ljava/lang/String;)Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;": function (n) {
					return ctx.nativeDefs["com/mojang/brigadier/builder/LiteralArgumentBuilder"].staticMethods.literal(n);
				}
			}
		});
		ctx.regClass("com/mojang/brigadier/builder/RequiredArgumentBuilder", {
			superName: "com/mojang/brigadier/builder/ArgumentBuilder",
			methods: builderMethods,
			staticMethods: {
				argument: function (name, type) {
					return ctx.makeObj("com/mojang/brigadier/builder/RequiredArgumentBuilder", {
						_nodeKind: "argument", _argName: toJsStr(name), _argType: type || null, _children: [], _command: null
					});
				},
				"argument(Ljava/lang/String;Lcom/mojang/brigadier/arguments/ArgumentType;)Lcom/mojang/brigadier/builder/RequiredArgumentBuilder;": function (n, t) {
					return ctx.nativeDefs["com/mojang/brigadier/builder/RequiredArgumentBuilder"].staticMethods.argument(n, t);
				}
			}
		});

		// CommandContext
		ctx.regClass("com/mojang/brigadier/context/CommandContext", {
			methods: {
				getSource: function () { return this._source; },
				"getSource()Ljava/lang/Object;": function () { return this._source; },
				getArgument: function (name) {
					var m = this._args || {};
					return m[toJsStr(name)];
				},
				"getArgument(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;": function (name) {
					var m = this._args || {};
					return m[toJsStr(name)];
				},
				getInput: function () { return this._input || ""; },
				"getInput()Ljava/lang/String;": function () { return this._input || ""; }
			}
		});

		// Brigadier Command Tokenizer & Matcher
		function readCommandToken(str, pos, kind) {
			while (pos < str.length && str.charCodeAt(pos) <= 32) { pos++; }
			if (pos >= str.length) { return null; }
			if (kind === "greedy") {
				return { value: str.slice(pos), nextPos: str.length };
			}
			var ch = str.charAt(pos);
			if (kind === "string" && (ch === "\"" || ch === "'")) {
				var quote = ch, i = pos + 1, out = "";
				while (i < str.length) {
					var c = str.charAt(i++);
					if (c === "\\" && i < str.length) { out += str.charAt(i++); }
					else if (c === quote) { break; }
					else { out += c; }
				}
				return { value: out, nextPos: i };
			}
			var start = pos;
			while (pos < str.length && str.charCodeAt(pos) > 32) { pos++; }
			return { value: str.slice(start, pos), nextPos: pos };
		}

		function parseArgToken(str, pos, argType) {
			var kind = (argType && argType._kind) || "word";
			var tok = readCommandToken(str, pos, kind);
			if (!tok) { return null; }
			if (kind === "int") {
				if (!/^-?\d+$/.test(tok.value)) { return null; }
				var iv = parseInt(tok.value, 10) | 0;
				if (iv < argType._min || iv > argType._max) { return null; }
				return { value: iv, nextPos: tok.nextPos };
			}
			if (kind === "long") {
				if (!/^-?\d+$/.test(tok.value)) { return null; }
				var lv = parseInt(tok.value, 10);
				if (lv < argType._min || lv > argType._max) { return null; }
				return { value: makeLong(ctx, lv), nextPos: tok.nextPos };
			}
			if (kind === "float" || kind === "double") {
				var fv = parseFloat(tok.value);
				if (isNaN(fv) || fv < argType._min || fv > argType._max) { return null; }
				return { value: kind === "float" ? Math.fround(fv) : fv, nextPos: tok.nextPos };
			}
			if (kind === "bool") {
				var low = tok.value.toLowerCase();
				if (low !== "true" && low !== "false") { return null; }
				return { value: low === "true", nextPos: tok.nextPos };
			}
			return tok;
		}

		function matchBrigadierTree(nodes, input, pos, captured) {
			while (pos < input.length && input.charCodeAt(pos) <= 32) { pos++; }
			// Try literal children first, then argument children
			for (var pass = 0; pass < 2; pass++) {
				for (var i = 0; i < nodes.length; i++) {
					var node = nodes[i];
					if (!node) { continue; }
					if (pass === 0 && node._nodeKind === "literal") {
						var tok = readCommandToken(input, pos, "word");
						if (!tok || tok.value !== node._literal) { continue; }
						var rem = input.slice(tok.nextPos).trim();
						if (!rem && node._command) {
							return { node: node, args: captured };
						}
						if (node._children && node._children.length) {
							var sub = matchBrigadierTree(node._children, input, tok.nextPos, captured);
							if (sub) { return sub; }
						}
					} else if (pass === 1 && node._nodeKind === "argument") {
						var parsed = parseArgToken(input, pos, node._argType);
						if (!parsed) { continue; }
						var nextCap = Object.assign({}, captured);
						nextCap[node._argName] = parsed.value;
						var rem2 = input.slice(parsed.nextPos).trim();
						if (!rem2 && node._command) {
							return { node: node, args: nextCap };
						}
						if (node._children && node._children.length) {
							var sub2 = matchBrigadierTree(node._children, input, parsed.nextPos, nextCap);
							if (sub2) { return sub2; }
						}
					}
				}
			}
			return null;
		}

		var dispatcherMethods = {
			"<init>": function () { this._roots = []; },
			"<init>()V": function () { this._roots = []; },
			register: function (litBuilder) {
				if (!this._roots) { this._roots = []; }
				if (litBuilder) { this._roots.push(litBuilder); }
				return litBuilder;
			},
			"register(Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;)Lcom/mojang/brigadier/tree/LiteralCommandNode;": function (b) {
				return dispatcherMethods.register.call(this, b);
			},
			execute: function (input, source) {
				var raw = toJsStr(input).trim();
				if (raw.charAt(0) === "/") { raw = raw.slice(1).trim(); }
				var matched = matchBrigadierTree(this._roots || [], raw, 0, {});
				if (!matched || !matched.node._command) { return 0; }
				var cmdCtx = ctx.makeObj("com/mojang/brigadier/context/CommandContext", {
					_source: source, _args: matched.args, _input: raw
				});
				var res = invokeJavaCallback(rt, matched.node._command, ["run", "apply"], "(Lcom/mojang/brigadier/context/CommandContext;)I", [cmdCtx]);
				return res === undefined ? 1 : toInt(res);
			},
			"execute(Ljava/lang/String;Ljava/lang/Object;)I": function (i, s) { return dispatcherMethods.execute.call(this, i, s); }
		};
		ctx.regClass("com/mojang/brigadier/CommandDispatcher", { methods: dispatcherMethods });

		// ---- FabricClientCommandSource, ClientCommandManager, ClientCommandRegistrationCallback ----
		ctx.regClass("net/fabricmc/fabric/api/client/command/v2/FabricClientCommandSource", {
			isInterface: true,
			methods: {
				sendFeedback: function (comp) {
					var em = ctx.eagler();
					var txt = componentToText(comp);
					if (em && typeof em.chat === "function") { em.chat(txt); } else { console.log("[cmd] " + txt); }
				},
				"sendFeedback(Lnet/minecraft/network/chat/Component;)V": function (c) { this.sendFeedback(c); },
				sendError: function (comp) {
					var em = ctx.eagler();
					var txt = "\u00a7c" + componentToText(comp);
					if (em && typeof em.chat === "function") { em.chat(txt); } else { console.error("[cmd] " + txt); }
				},
				"sendError(Lnet/minecraft/network/chat/Component;)V": function (c) { this.sendError(c); },
				getPlayer: function () { return getPlayerInstance(ctx); },
				"getPlayer()Lnet/minecraft/client/player/LocalPlayer;": function () { return getPlayerInstance(ctx); },
				getEntity: function () { return getPlayerInstance(ctx); },
				getClient: function () { return getMinecraftInstance(ctx); },
				"getClient()Lnet/minecraft/client/Minecraft;": function () { return getMinecraftInstance(ctx); },
				getWorld: function () { return getLevelInstance(ctx); },
				"getWorld()Lnet/minecraft/client/multiplayer/ClientLevel;": function () { return getLevelInstance(ctx); },
				getPosition: function () { return getPlayerInstance(ctx).position(); }
			}
		});
		ctx.regClass("net/fabricmc/fabric/api/client/command/v2/ClientCommandManager", {
			staticMethods: {
				literal: function (n) { return ctx.nativeDefs["com/mojang/brigadier/builder/LiteralArgumentBuilder"].staticMethods.literal(n); },
				"literal(Ljava/lang/String;)Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;": function (n) { return ctx.nativeDefs["com/mojang/brigadier/builder/LiteralArgumentBuilder"].staticMethods.literal(n); },
				argument: function (n, t) { return ctx.nativeDefs["com/mojang/brigadier/builder/RequiredArgumentBuilder"].staticMethods.argument(n, t); },
				"argument(Ljava/lang/String;Lcom/mojang/brigadier/arguments/ArgumentType;)Lcom/mojang/brigadier/builder/RequiredArgumentBuilder;": function (n, t) { return ctx.nativeDefs["com/mojang/brigadier/builder/RequiredArgumentBuilder"].staticMethods.argument(n, t); }
			}
		});

		ctx.commandDispatcher = ctx.makeObj("com/mojang/brigadier/CommandDispatcher", { _roots: [] });
		var cmdRegEv = createFabricEvent(function (callback) {
			try {
				invokeJavaCallback(rt, callback, ["register", "accept"], null, [ctx.commandDispatcher, null]);
			} catch (e) { console.error("[jvm-bridge] ClientCommandRegistrationCallback error:", e); }
		});
		ctx.regClass("net/fabricmc/fabric/api/client/command/v2/ClientCommandRegistrationCallback", {
			isInterface: true,
			statics: { EVENT: cmdRegEv }
		});

		ctx.tryExecuteClientCommand = function (rawMsg) {
			var s = String(rawMsg || "").trim();
			if (s.charAt(0) !== "/") { return false; }
			var body = s.slice(1).trim();
			if (!body || !ctx.commandDispatcher || !ctx.commandDispatcher._roots.length) { return false; }
			var firstTok = body.split(/\s+/)[0];
			var knownRoot = false;
			for (var i = 0; i < ctx.commandDispatcher._roots.length; i++) {
				if (ctx.commandDispatcher._roots[i] && ctx.commandDispatcher._roots[i]._literal === firstTok) {
					knownRoot = true;
					break;
				}
			}
			if (!knownRoot) { return false; }
			var src = ctx.makeObj("net/fabricmc/fabric/api/client/command/v2/FabricClientCommandSource");
			try {
				var res = dispatcherMethods.execute.call(ctx.commandDispatcher, body, src);
				if (res === 0) {
					src.sendError(makeComponentLiteral(ctx, "Invalid command syntax: /" + body));
				}
			} catch (e) {
				console.error("[jvm-bridge] command error:", e);
				src.sendError(makeComponentLiteral(ctx, "Command error: " + (e && e.message ? e.message : String(e))));
			}
			return true;
		};

		registerHookOn(ctx, "__fabric_commands__", "chatSend", function (ev) {
			if (!ev || ev.cancelled) { return; }
			if (ctx.tryExecuteClientCommand(ev.message)) {
				ev.cancelled = true;
			}
		});
	}

	// =========================================================================
	// 11. Minecraft 26.3 Client Bridge + Pixel-Aligned HUD Canvas + Aliases
	// =========================================================================

	var MC_COLOR_CODES = {
		"0": "#000000", "1": "#0000aa", "2": "#00aa00", "3": "#00aaaa",
		"4": "#aa0000", "5": "#aa00aa", "6": "#ffaa00", "7": "#aaaaaa",
		"8": "#555555", "9": "#5555ff", "a": "#55ff55", "b": "#55ffff",
		"c": "#ff5555", "d": "#ff55ff", "e": "#ffff55", "f": "#ffffff"
	};

	var MC_SHADOW_CODES = {
		"0": "rgb(0,0,0)", "1": "rgb(0,0,42)", "2": "rgb(0,42,0)", "3": "rgb(0,42,42)",
		"4": "rgb(42,0,0)", "5": "rgb(42,0,42)", "6": "rgb(63,42,0)", "7": "rgb(42,42,42)",
		"8": "rgb(21,21,21)", "9": "rgb(21,21,63)", "a": "rgb(21,63,21)", "b": "rgb(21,63,63)",
		"c": "rgb(63,21,21)", "d": "rgb(63,21,63)", "e": "rgb(63,63,21)", "f": "rgb(63,63,63)"
	};

	var MC_FONT_REGULAR     = "8px \"Minecraft\", \"Minecraftia\", ui-monospace, Menlo, Consolas, monospace";
	var MC_FONT_BOLD        = "bold 8px \"Minecraft\", \"Minecraftia\", ui-monospace, Menlo, Consolas, monospace";
	var MC_FONT_ITALIC      = "italic 8px \"Minecraft\", \"Minecraftia\", ui-monospace, Menlo, Consolas, monospace";
	var MC_FONT_BOLD_ITALIC = "italic bold 8px \"Minecraft\", \"Minecraftia\", ui-monospace, Menlo, Consolas, monospace";

	// Direct-mapped 64-slot CSS color caches (avoid per-frame rgb/rgba string allocations)
	var _argbOpKey = new Int32Array(64), _argbOpPop = new Uint8Array(64), _argbOpVal = new Array(64);
	var _argbTrKey = new Int32Array(64), _argbTrPop = new Uint8Array(64), _argbTrVal = new Array(64);
	var _argbShKey = new Int32Array(64), _argbShPop = new Uint8Array(64), _argbShVal = new Array(64);

	function argbToCss(colorInt, defaultOpaque) {
		var c = colorInt | 0;
		var slot = (c ^ (c >>> 12) ^ (c >>> 20)) & 63;
		if (defaultOpaque) {
			if (_argbOpPop[slot] && _argbOpKey[slot] === c) { return _argbOpVal[slot]; }
		} else {
			if (_argbTrPop[slot] && _argbTrKey[slot] === c) { return _argbTrVal[slot]; }
		}
		var a = (c >>> 24) & 0xff;
		if (defaultOpaque && (c & 0xfc000000) === 0) { a = 255; }
		var r = (c >>> 16) & 0xff;
		var g = (c >>> 8) & 0xff;
		var b = c & 0xff;
		var css = a >= 255
			? ("rgb(" + r + "," + g + "," + b + ")")
			: ("rgba(" + r + "," + g + "," + b + "," + (a / 255).toFixed(3) + ")");
		if (defaultOpaque) { _argbOpKey[slot] = c; _argbOpPop[slot] = 1; _argbOpVal[slot] = css; }
		else { _argbTrKey[slot] = c; _argbTrPop[slot] = 1; _argbTrVal[slot] = css; }
		return css;
	}

	function argbShadowCss(colorInt, defaultOpaque) {
		var c = colorInt | 0;
		var slot = (c ^ (c >>> 12) ^ (c >>> 20)) & 63;
		if (defaultOpaque && _argbShPop[slot] && _argbShKey[slot] === c) { return _argbShVal[slot]; }
		var a = (c >>> 24) & 0xff;
		if (defaultOpaque && (c & 0xfc000000) === 0) { a = 255; }
		var r = ((c >>> 16) & 0xff) >> 2;
		var g = ((c >>> 8) & 0xff) >> 2;
		var b = (c & 0xff) >> 2;
		var css = a >= 255
			? ("rgb(" + r + "," + g + "," + b + ")")
			: ("rgba(" + r + "," + g + "," + b + "," + (a / 255).toFixed(3) + ")");
		if (defaultOpaque) { _argbShKey[slot] = c; _argbShPop[slot] = 1; _argbShVal[slot] = css; }
		return css;
	}

	function hexToShadowCss(hex) {
		var v = parseInt(hex.slice(1), 16) | 0;
		return "rgb(" + (((v >> 16) & 0xff) >> 2) + "," + (((v >> 8) & 0xff) >> 2) + "," + ((v & 0xff) >> 2) + ")";
	}

	function measureMcWidth(str) {
		if (!str) { return 0; }
		var s = typeof str === "string" ? str : String(str);
		var w = 0, len = s.length;
		for (var i = 0; i < len; i++) {
			var c = s.charCodeAt(i);
			if (c === 0x00a7 && i + 1 < len) { i++; continue; }
			if (c === 105 || c === 33 || c === 46 || c === 44 || c === 58 || c === 59 || c === 124) { w += 2; }
			else if (c === 108 || c === 39 || c === 96) { w += 3; }
			else if (c === 73 || c === 116 || c === 32 || c === 91 || c === 93) { w += 4; }
			else if (c === 102 || c === 107 || c === 40 || c === 41 || c === 123 || c === 125) { w += 5; }
			else { w += 6; }
		}
		return w;
	}

	function componentToText(comp) {
		if (comp === null || comp === undefined) { return ""; }
		if (typeof comp === "string") { return comp; }
		if (comp._siblings === null && typeof comp._text === "string") {
			return comp._stylePrefix ? (comp._stylePrefix + comp._text) : comp._text;
		}
		if (typeof comp._formattedString === "function") { return comp._formattedString(); }
		if (typeof comp.getString === "function") { return String(comp.getString()); }
		return toJsStr(comp);
	}

	function makeComponentLiteral(ctx, str) {
		var s = str === null || str === undefined ? "" : (typeof str === "string" ? str : toJsStr(str));
		var proto = ctx._compProto || (ctx._compProto = ctx.getProto("net/minecraft/network/chat/MutableComponent"));
		var o = Object.create(proto);
		o.__cls = "net/minecraft/network/chat/MutableComponent";
		o.__class = "net/minecraft/network/chat/MutableComponent";
		o.$class = "net/minecraft/network/chat/MutableComponent";
		o._text = s;
		o._siblings = null;
		o._stylePrefix = "";
		o._colorInt = null;
		return o;
	}

	function makeScreenObj(ctx, name, title) {
		if (!name) { return null; }
		var win = getWindowInstance(ctx);
		var w = win.getGuiScaledWidth();
		var h = win.getGuiScaledHeight();
		var t = title || name;
		if (ctx._cachedScreenObj && ctx._cachedScreenName === name && ctx._cachedScreenTitle === t &&
			ctx._cachedScreenW === w && ctx._cachedScreenH === h) {
			return ctx._cachedScreenObj;
		}
		var cls = "net/minecraft/client/gui/screens/Screen";
		if (name === "TitleScreen") { cls = "net/minecraft/client/gui/screens/TitleScreen"; }
		else if (name === "PauseScreen") { cls = "net/minecraft/client/gui/screens/PauseScreen"; }
		else if (name === "ChatScreen") { cls = "net/minecraft/client/gui/screens/ChatScreen"; }
		var noopEvt = ctx._noopScreenEvt || null;
		var scr = ctx.makeObj(cls, {
			_screenName: name,
			_title: makeComponentLiteral(ctx, t),
			minecraft: getMinecraftInstance(ctx),
			width: w,
			height: h,
			__interfaces: ["net/fabricmc/fabric/impl/client/screen/ScreenExtensions"],
			fabric_getRemoveEvent: function () { return noopEvt; },
			fabric_getBeforeRenderEvent: function () { return noopEvt; },
			fabric_getAfterRenderEvent: function () { return noopEvt; },
			fabric_getBeforeTickEvent: function () { return noopEvt; },
			fabric_getAfterTickEvent: function () { return noopEvt; },
			fabric_getAllowMouseClickEvent: function () { return noopEvt; },
			fabric_getBeforeMouseClickEvent: function () { return noopEvt; },
			fabric_getAfterMouseClickEvent: function () { return noopEvt; },
			fabric_getAllowMouseReleaseEvent: function () { return noopEvt; },
			fabric_getBeforeMouseReleaseEvent: function () { return noopEvt; },
			fabric_getAfterMouseReleaseEvent: function () { return noopEvt; },
			fabric_getAllowMouseScrollEvent: function () { return noopEvt; },
			fabric_getBeforeMouseScrollEvent: function () { return noopEvt; },
			fabric_getAfterMouseScrollEvent: function () { return noopEvt; },
			fabric_getAllowKeyPressEvent: function () { return noopEvt; },
			fabric_getBeforeKeyPressEvent: function () { return noopEvt; },
			fabric_getAfterKeyPressEvent: function () { return noopEvt; },
			fabric_getAllowKeyReleaseEvent: function () { return noopEvt; },
			fabric_getBeforeKeyReleaseEvent: function () { return noopEvt; },
			fabric_getAfterKeyReleaseEvent: function () { return noopEvt; },
			fabric_getButtons: function () { return makeJavaList(ctx, []); }
		});
		ctx._cachedScreenName = name;
		ctx._cachedScreenTitle = t;
		ctx._cachedScreenW = w;
		ctx._cachedScreenH = h;
		ctx._cachedScreenObj = scr;
		return scr;
	}

	// HUD command buffer opcodes (stride = 7 Float64 slots + 1 string slot)
	var HUD_CMD_STRIDE = 7;
	var CMD_TEXT = 1;
	var CMD_FILL = 2;
	var CMD_FILL_GRAD = 3;
	var CMD_OUTLINE = 4;
	var CMD_PUSH = 5;
	var CMD_POP = 6;
	var CMD_TRANSLATE = 7;
	var CMD_SCALE = 8;

	function pushHudCmd(ctx, op, a, b, c, d, e, f, str) {
		var idx = ctx._cmdCurCount;
		var base = idx * HUD_CMD_STRIDE;
		var num = ctx._cmdCurNum;
		if (base + HUD_CMD_STRIDE > num.length) {
			var nextCap = (num.length / HUD_CMD_STRIDE) * 2;
			if (nextCap < 256) { nextCap = 256; }
			var nextNum = new Float64Array(nextCap * HUD_CMD_STRIDE);
			nextNum.set(num);
			ctx._cmdCurNum = nextNum;
			num = nextNum;
			var nextStr = new Array(nextCap);
			for (var si = 0; si < idx; si++) { nextStr[si] = ctx._cmdCurStr[si]; }
			ctx._cmdCurStr = nextStr;
		}
		num[base] = op;
		num[base + 1] = a;
		num[base + 2] = b;
		num[base + 3] = c;
		num[base + 4] = d;
		num[base + 5] = e;
		num[base + 6] = f;
		if (op === CMD_TEXT) { ctx._cmdCurStr[idx] = str; }
		var h = ctx._cmdCurHash | 0;
		h = Math.imul(h ^ (op | 0), 16777619);
		h = Math.imul(h ^ ((a * 16) | 0), 16777619);
		h = Math.imul(h ^ ((b * 16) | 0), 16777619);
		h = Math.imul(h ^ ((c * 16) | 0), 16777619);
		h = Math.imul(h ^ ((d * 16) | 0), 16777619);
		h = Math.imul(h ^ (e | 0), 16777619);
		h = Math.imul(h ^ (f | 0), 16777619);
		if (op === CMD_TEXT && str) {
			h = Math.imul(h ^ javaStringHash(str), 16777619);
		}
		ctx._cmdCurHash = h;
		ctx._cmdCurCount = idx + 1;
	}

	function hudCmdsEqual(ctx) {
		var count = ctx._cmdCurCount;
		if (count !== ctx._cmdPrevCount || ctx._cmdCurHash !== ctx._cmdPrevHash) { return false; }
		var curN = ctx._cmdCurNum, prevN = ctx._cmdPrevNum;
		var total = count * HUD_CMD_STRIDE;
		if (prevN.length < total) { return false; }
		for (var i = 0; i < total; i++) {
			if (curN[i] !== prevN[i]) { return false; }
		}
		var curS = ctx._cmdCurStr, prevS = ctx._cmdPrevStr;
		for (var j = 0; j < count; j++) {
			if (curN[j * HUD_CMD_STRIDE] === CMD_TEXT && curS[j] !== prevS[j]) { return false; }
		}
		return true;
	}

	function drawStyledMcTextCached(c2d, text, x, y, colorInt, shadow, state) {
		if (!c2d) { return x; }
		var s = typeof text === "string" ? text : String(text || "");
		var curX = Math.floor(typeof x === "number" ? x : toNum(x));
		var curY = Math.floor(typeof y === "number" ? y : toNum(y));
		if (s.length === 0) { return curX | 0; }
		// Fast path: plain text with no section-sign formatting codes (zero char loop, cached font)
		if (s.indexOf("\u00a7") < 0) {
			if (state.font !== MC_FONT_REGULAR) {
				c2d.font = MC_FONT_REGULAR;
				state.font = MC_FONT_REGULAR;
			}
			if (shadow) {
				var sh = argbShadowCss(colorInt, true);
				if (state.fill !== sh) { c2d.fillStyle = sh; state.fill = sh; }
				c2d.fillText(s, curX + 1, curY + 1);
			}
			var fg = argbToCss(colorInt, true);
			if (state.fill !== fg) { c2d.fillStyle = fg; state.fill = fg; }
			c2d.fillText(s, curX, curY);
			return (curX + measureMcWidth(s)) | 0;
		}
		var baseCss = argbToCss(colorInt, true);
		var baseShadow = argbShadowCss(colorInt, true);
		var curCss = baseCss, curShadow = baseShadow;
		var bold = false, italic = false;
		var runStart = 0;
		var len = s.length;

		function flushSlice(endIdx) {
			if (endIdx <= runStart) { return; }
			var run = s.slice(runStart, endIdx);
			var fontStr = bold ? (italic ? MC_FONT_BOLD_ITALIC : MC_FONT_BOLD) : (italic ? MC_FONT_ITALIC : MC_FONT_REGULAR);
			if (state.font !== fontStr) { c2d.font = fontStr; state.font = fontStr; }
			if (shadow) {
				if (state.fill !== curShadow) { c2d.fillStyle = curShadow; state.fill = curShadow; }
				c2d.fillText(run, curX + 1, curY + 1);
			}
			if (state.fill !== curCss) { c2d.fillStyle = curCss; state.fill = curCss; }
			c2d.fillText(run, curX, curY);
			curX += measureMcWidth(run);
		}

		for (var i = 0; i < len; i++) {
			if (s.charCodeAt(i) === 0x00a7 && i + 1 < len) {
				flushSlice(i);
				var code = s.charAt(++i).toLowerCase();
				if (MC_COLOR_CODES[code]) {
					curCss = MC_COLOR_CODES[code];
					curShadow = MC_SHADOW_CODES[code];
					bold = false; italic = false;
				} else if (code === "l") { bold = true; }
				else if (code === "o") { italic = true; }
				else if (code === "r") { curCss = baseCss; curShadow = baseShadow; bold = false; italic = false; }
				runStart = i + 1;
			}
		}
		flushSlice(len);
		return curX | 0;
	}

	var _replayCanvasState = { font: "", fill: "" };

	function replayHudCmds(ctx, c2d) {
		var count = ctx._cmdCurCount;
		var num = ctx._cmdCurNum;
		var strs = ctx._cmdCurStr;
		var st = _replayCanvasState;
		st.font = "";
		st.fill = "";
		c2d.textBaseline = "top";
		c2d.textAlign = "left";
		for (var i = 0; i < count; i++) {
			var base = i * HUD_CMD_STRIDE;
			var op = num[base];
			if (op === CMD_TEXT) {
				drawStyledMcTextCached(c2d, strs[i], num[base + 1], num[base + 2], num[base + 3] | 0, num[base + 4] !== 0, st);
			} else if (op === CMD_FILL) {
				var fCss = argbToCss(num[base + 5] | 0, false);
				if (st.fill !== fCss) { c2d.fillStyle = fCss; st.fill = fCss; }
				c2d.fillRect(num[base + 1], num[base + 2], num[base + 3], num[base + 4]);
			} else if (op === CMD_FILL_GRAD) {
				var gx = num[base + 1], gy = num[base + 2], gw = num[base + 3], gh = num[base + 4];
				var grad = c2d.createLinearGradient(gx, gy, gx, gy + gh);
				grad.addColorStop(0, argbToCss(num[base + 5] | 0, false));
				grad.addColorStop(1, argbToCss(num[base + 6] | 0, false));
				c2d.fillStyle = grad;
				st.fill = "";
				c2d.fillRect(gx, gy, gw, gh);
			} else if (op === CMD_OUTLINE) {
				var ox = num[base + 1], oy = num[base + 2], ow = num[base + 3], oh = num[base + 4];
				var oCss = argbToCss(num[base + 5] | 0, false);
				if (st.fill !== oCss) { c2d.fillStyle = oCss; st.fill = oCss; }
				c2d.fillRect(ox, oy, ow, 1);
				c2d.fillRect(ox, oy + oh - 1, ow, 1);
				var innerH = oh - 2;
				if (innerH > 0) {
					c2d.fillRect(ox, oy + 1, 1, innerH);
					c2d.fillRect(ox + ow - 1, oy + 1, 1, innerH);
				}
			} else if (op === CMD_PUSH) {
				c2d.save();
			} else if (op === CMD_POP) {
				c2d.restore();
				st.font = "";
				st.fill = "";
				c2d.textBaseline = "top";
				c2d.textAlign = "left";
			} else if (op === CMD_TRANSLATE) {
				c2d.translate(num[base + 1], num[base + 2]);
			} else if (op === CMD_SCALE) {
				c2d.scale(num[base + 1], num[base + 2]);
			}
		}
	}

	function clearHudOverlayForCtx(ctx) {
		if (!ctx) { return; }
		var ov = ctx.overlayCanvas;
		var c2d = ctx.overlayCtx;
		if (ov && c2d) {
			if (ctx._overlayVisible || ctx._overlayDirty || ctx._cmdPrevCount > 0) {
				c2d.clearRect(0, 0, ov.width, ov.height);
			}
			if (ov.style && ov.style.display !== "none") {
				ov.style.display = "none";
			}
		}
		ctx._overlayVisible = false;
		ctx._overlayDirty = false;
		ctx._cmdPrevCount = 0;
		ctx._cmdPrevHash = 0;
		ctx._cmdCurCount = 0;
		ctx._lastRenderW = -1;
		ctx._lastRenderH = -1;
		ctx._lastRenderScale = -1;
	}

	function findGameCanvas() {
		if (typeof document === "undefined") { return null; }
		return document.getElementById("game_canvas") || document.getElementById("game_frame") ||
			(typeof document.querySelector === "function" ? document.querySelector("canvas") : null);
	}

	function attachResizeTracking(ctx, gc) {
		if (!ctx._resizeListenersAttached && typeof window !== "undefined" && typeof window.addEventListener === "function") {
			ctx._resizeListenersAttached = true;
			var onResize = function () { ctx._metricsDirty = true; };
			window.addEventListener("resize", onResize, { passive: true });
			if (typeof document !== "undefined" && typeof document.addEventListener === "function") {
				document.addEventListener("fullscreenchange", onResize, { passive: true });
				document.addEventListener("webkitfullscreenchange", onResize, { passive: true });
			}
		}
		if (gc && ctx._observedCanvas !== gc && typeof ResizeObserver === "function") {
			if (ctx._resizeObserver) {
				try { ctx._resizeObserver.disconnect(); } catch (e) {}
			}
			try {
				ctx._resizeObserver = new ResizeObserver(function () { ctx._metricsDirty = true; });
				ctx._resizeObserver.observe(gc);
				ctx._observedCanvas = gc;
			} catch (e) {
				ctx._observedCanvas = null;
			}
		}
	}

	function computeWindowMetrics(ctx) {
		var m = ctx._metrics;
		var gc = ctx._gameCanvas;
		if (!gc || gc.isConnected === false) {
			gc = findGameCanvas();
			if (gc !== ctx._gameCanvas) {
				ctx._gameCanvas = gc;
				ctx._metricsDirty = true;
			}
		}
		if (gc) {
			attachResizeTracking(ctx, gc);
			var curFbW = (gc.width | 0) || m.cssW || 854;
			var curFbH = (gc.height | 0) || m.cssH || 480;
			if (curFbW !== m.fbW || curFbH !== m.fbH) {
				ctx._metricsDirty = true;
			}
		}
		if (ctx._metricsDirty) {
			var fbW = 854, fbH = 480, cssW = 854, cssH = 480, left = 0, top = 0;
			if (gc) {
				var rect = gc.getBoundingClientRect ? gc.getBoundingClientRect() : null;
				cssW = Math.max(1, Math.round((rect && rect.width) || gc.clientWidth || gc.width || 854));
				cssH = Math.max(1, Math.round((rect && rect.height) || gc.clientHeight || gc.height || 480));
				left = rect ? Math.round(rect.left) : 0;
				top = rect ? Math.round(rect.top) : 0;
				fbW = Math.max(1, (gc.width | 0) || cssW);
				fbH = Math.max(1, (gc.height | 0) || cssH);
			}
			m.canvas = gc;
			m.fbW = fbW;
			m.fbH = fbH;
			m.cssW = cssW;
			m.cssH = cssH;
			m.left = left;
			m.top = top;
			ctx._metricsDirty = false;
			ctx._metricsVersion = (ctx._metricsVersion + 1) | 0;
		}
		var guiScale = 0;
		var em = ctx && ctx.eagler();
		var st = (em && em._liveState) || (ctx && ctx.cachedState) || null;
		if (st && typeof st.guiScale === "number" && st.guiScale > 0) {
			guiScale = st.guiScale | 0;
		} else if (em && typeof em.getGuiScale === "function") {
			try { guiScale = em.getGuiScale() | 0; } catch (e) { guiScale = 0; }
		}
		if (guiScale <= 0) {
			guiScale = 1;
			while (guiScale < 4 && Math.floor(m.fbW / (guiScale + 1)) >= 320 && Math.floor(m.fbH / (guiScale + 1)) >= 240) {
				guiScale++;
			}
		}
		if (m.guiScale !== guiScale || m.guiWidth !== Math.max(1, Math.ceil(m.fbW / guiScale)) || m.guiHeight !== Math.max(1, Math.ceil(m.fbH / guiScale))) {
			m.guiScale = guiScale;
			m.guiWidth = Math.max(1, Math.ceil(m.fbW / guiScale));
			m.guiHeight = Math.max(1, Math.ceil(m.fbH / guiScale));
		}
		return m;
	}

	function ensureOverlayCanvas(ctx) {
		if (typeof document === "undefined" || !document.body) { return null; }
		var m = computeWindowMetrics(ctx);
		var ov = ctx.overlayCanvas;
		if (!ov || (typeof document.body.contains === "function" && !document.body.contains(ov))) {
			ov = document.getElementById("eagler-mod-hud-canvas");
			if (!ov) {
				ov = document.createElement("canvas");
				ov.id = "eagler-mod-hud-canvas";
				ov.style.position = "fixed";
				ov.style.pointerEvents = "none";
				ov.style.zIndex = "25";
				ov.style.background = "transparent";
				ov.style.imageRendering = "pixelated";
				ov.style.display = "none";
				document.body.appendChild(ov);
			}
			ctx.overlayCanvas = ov;
			ctx.overlayCtx = ov.getContext("2d");
			ctx._overlayVisible = ov.style.display !== "none";
			ctx._ovLayoutVersion = -1;
		}
		if (ctx._ovLayoutVersion !== ctx._metricsVersion) {
			if (ov.width !== m.fbW || ov.height !== m.fbH) {
				ov.width = m.fbW;
				ov.height = m.fbH;
				ctx._lastRenderW = -1;
			}
			var leftPx = m.left + "px", topPx = m.top + "px", wPx = m.cssW + "px", hPx = m.cssH + "px";
			if (ov.style.left !== leftPx) { ov.style.left = leftPx; }
			if (ov.style.top !== topPx) { ov.style.top = topPx; }
			if (ov.style.width !== wPx) { ov.style.width = wPx; }
			if (ov.style.height !== hPx) { ov.style.height = hPx; }
			ctx._ovLayoutVersion = ctx._metricsVersion;
		}
		var handle = ctx._overlayHandle;
		handle.canvas = ov;
		handle.ctx2d = ctx.overlayCtx;
		handle.metrics = m;
		return handle;
	}

	function getMinecraftInstance(ctx) {
		if (!ctx.minecraftSingleton) {
			ctx.minecraftSingleton = ctx.makeObj("net/minecraft/client/Minecraft");
			Object.defineProperties(ctx.minecraftSingleton, {
				player: { enumerable: true, configurable: true, get: function () { return getPlayerInstance(ctx); } },
				level: { enumerable: true, configurable: true, get: function () { return getLevelInstance(ctx); } },
				options: { enumerable: true, configurable: true, get: function () { return getOptionsInstance(ctx); } },
				screen: { enumerable: true, configurable: true, get: function () { var s = ctx.getGameState(false); return makeScreenObj(ctx, s.screen, s.screenTitle); } },
				gui: { enumerable: true, configurable: true, get: function () { return getGuiInstance(ctx); } },
				font: { enumerable: true, configurable: true, get: function () { return getFontInstance(ctx); } },
				window: { enumerable: true, configurable: true, get: function () { return getWindowInstance(ctx); } }
			});
		}
		return ctx.minecraftSingleton;
	}

	function getWindowInstance(ctx) {
		if (!ctx.windowSingleton) { ctx.windowSingleton = ctx.makeObj("com/mojang/blaze3d/platform/Window"); }
		return ctx.windowSingleton;
	}

	function getFontInstance(ctx) {
		if (!ctx.fontSingleton) { ctx.fontSingleton = ctx.makeObj("net/minecraft/client/gui/Font", { lineHeight: 9 }); }
		return ctx.fontSingleton;
	}

	function getGuiInstance(ctx) {
		if (!ctx.guiSingleton) { ctx.guiSingleton = ctx.makeObj("net/minecraft/client/gui/Gui"); }
		return ctx.guiSingleton;
	}

	function getOptionsInstance(ctx) {
		if (!ctx.optionsSingleton) { ctx.optionsSingleton = ctx.makeObj("net/minecraft/client/Options"); }
		return ctx.optionsSingleton;
	}

	function getDeltaTrackerInstance(ctx, tickDelta) {
		if (!ctx.deltaTrackerSingleton) {
			ctx.deltaTrackerSingleton = ctx.makeObj("net/minecraft/client/DeltaTracker", { _partial: 0, _ticks: 1 });
		}
		if (typeof tickDelta === "number" && !isNaN(tickDelta)) {
			ctx.deltaTrackerSingleton._partial = Math.max(0, Math.min(1, tickDelta));
		}
		return ctx.deltaTrackerSingleton;
	}

	function getGuiGraphicsInstance(ctx) {
		if (!ctx.guiGraphicsSingleton) {
			ctx.guiGraphicsSingleton = ctx.makeObj("net/minecraft/client/gui/GuiGraphics", {
				_ctx: ctx, _ctx2d: null, _recording: false, _guiWidth: 427, _guiHeight: 240, _guiScale: 2
			});
		}
		return ctx.guiGraphicsSingleton;
	}

	function getPoseStackInstance(ctx, gg) {
		if (!ctx.poseStackSingleton) {
			ctx.poseStackSingleton = ctx.makeObj("com/mojang/blaze3d/vertex/PoseStack", { _ctx: ctx, _gg: null, _ctx2d: null });
		}
		ctx.poseStackSingleton._gg = gg;
		ctx.poseStackSingleton._ctx2d = gg ? gg._ctx2d : null;
		return ctx.poseStackSingleton;
	}

	function getCameraInstance(ctx) {
		if (!ctx.cameraSingleton) {
			ctx.cameraSingleton = ctx.makeObj("net/minecraft/client/Camera");
		}
		return ctx.cameraSingleton;
	}

	function getPlayerInstance(ctx) {
		var st = ctx.getGameState(false);
		if (!st || !st.inWorld || !st.player) { return null; }
		if (!ctx.playerSingleton) {
			ctx.playerSingleton = ctx.makeObj("net/minecraft/client/player/LocalPlayer");
			Object.defineProperties(ctx.playerSingleton, {
				experienceLevel: { enumerable: true, configurable: true, get: function () { var p = ctx.getGameState(false).player; return p ? (p.xpLevel | 0) : 0; } },
				experienceProgress: { enumerable: true, configurable: true, get: function () { var p = ctx.getGameState(false).player; return p && typeof p.xpProgress === "number" ? p.xpProgress : 0; } },
				tickCount: { enumerable: true, configurable: true, get: function () { return ctx.getGameState(false).tick | 0; } }
			});
		}
		return ctx.playerSingleton;
	}

	function getLevelInstance(ctx) {
		var st = ctx.getGameState(false);
		if (!st || !st.inWorld || !st.world) { return null; }
		if (!ctx.levelSingleton) {
			ctx.levelSingleton = ctx.makeObj("net/minecraft/client/multiplayer/ClientLevel", { isClientSide: true });
		}
		return ctx.levelSingleton;
	}

	function installMinecraftBridge(ctx) {
		var rt = ctx.rt;

		// ---- ChatFormatting ----
		var cfSpecs = [
			["BLACK", "0", 0x000000, true], ["DARK_BLUE", "1", 0x0000aa, true],
			["DARK_GREEN", "2", 0x00aa00, true], ["DARK_AQUA", "3", 0x00aaaa, true],
			["DARK_RED", "4", 0xaa0000, true], ["DARK_PURPLE", "5", 0xaa00aa, true],
			["GOLD", "6", 0xffaa00, true], ["GRAY", "7", 0xaaaaaa, true],
			["DARK_GRAY", "8", 0x555555, true], ["BLUE", "9", 0x5555ff, true],
			["GREEN", "a", 0x55ff55, true], ["AQUA", "b", 0x55ffff, true],
			["RED", "c", 0xff5555, true], ["LIGHT_PURPLE", "d", 0xff55ff, true],
			["YELLOW", "e", 0xffff55, true], ["WHITE", "f", 0xffffff, true],
			["OBFUSCATED", "k", null, false], ["BOLD", "l", null, false],
			["STRIKETHROUGH", "m", null, false], ["UNDERLINE", "n", null, false],
			["ITALIC", "o", null, false], ["RESET", "r", null, false]
		];
		var cfStatics = {};
		var cfList = [];
		var cfMethods = {
			getChar: function () { return this._code.charCodeAt(0); }, "getChar()C": function () { return this._code.charCodeAt(0); },
			getColor: function () { return this._color === null ? null : ctx.nativeDefs["java/lang/Integer"].staticMethods.valueOf(this._color); },
			"getColor()Ljava/lang/Integer;": function () { return this.getColor(); },
			getName: function () { return this._enumName.toLowerCase(); }, "getName()Ljava/lang/String;": function () { return this._enumName.toLowerCase(); },
			isColor: function () { return !!this._isColor; }, "isColor()Z": function () { return !!this._isColor; },
			isFormat: function () { return !this._isColor && this._code !== "r"; }, "isFormat()Z": function () { return !this._isColor && this._code !== "r"; },
			toString: function () { return "\u00a7" + this._code; }, "toString()Ljava/lang/String;": function () { return "\u00a7" + this._code; }
		};
		ctx.regClass("net/minecraft/ChatFormatting", {
			superName: "java/lang/Enum",
			statics: cfStatics,
			methods: cfMethods,
			staticMethods: {
				values: function () { return makeObjectArray(rt, "Lnet/minecraft/ChatFormatting;", cfList); },
				valueOf: function (n) { return cfStatics[toJsStr(n)] || cfStatics.WHITE; },
				stripFormatting: function (s) { return s === null || s === undefined ? null : toJsStr(s).replace(/\u00a7[0-9a-fk-or]/gi, ""); },
				"stripFormatting(Ljava/lang/String;)Ljava/lang/String;": function (s) { return s === null || s === undefined ? null : toJsStr(s).replace(/\u00a7[0-9a-fk-or]/gi, ""); },
				getByCode: function (c) {
					var ch = String.fromCharCode(toCharCode(c)).toLowerCase();
					for (var i = 0; i < cfList.length; i++) { if (cfList[i]._code === ch) { return cfList[i]; } }
					return null;
				},
				getByName: function (n) { return n ? (cfStatics[toJsStr(n).toUpperCase()] || null) : null; }
			}
		});
		for (var ci = 0; ci < cfSpecs.length; ci++) {
			var sp = cfSpecs[ci];
			var cfObj = ctx.makeObj("net/minecraft/ChatFormatting", {
				_enumName: sp[0], _ordinal: ci, _code: sp[1], _color: sp[2], _isColor: sp[3]
			});
			cfStatics[sp[0]] = cfObj;
			ctx.setStaticField("net/minecraft/ChatFormatting", sp[0], cfObj);
			cfList.push(cfObj);
		}
		ctx.regAlias("net/minecraft/network/chat/ChatFormatting", "net/minecraft/ChatFormatting");

		// ---- Style, Component & MutableComponent ----
		var styleEmpty = null;
		var styleMethods = {
			withColor: function (c) {
				var col = (c && typeof c === "object" && typeof c._color === "number") ? c._color : toInt(c);
				return ctx.makeObj("net/minecraft/network/chat/Style", { _color: col, _bold: this._bold, _italic: this._italic, _code: (c && c._code) || this._code });
			},
			withBold: function (b) { return ctx.makeObj("net/minecraft/network/chat/Style", { _color: this._color, _bold: toBool(b), _italic: this._italic, _code: this._code }); },
			withItalic: function (b) { return ctx.makeObj("net/minecraft/network/chat/Style", { _color: this._color, _bold: this._bold, _italic: toBool(b), _code: this._code }); },
			applyFormat: function (cf) {
				if (!cf || !cf._code) { return this; }
				return ctx.makeObj("net/minecraft/network/chat/Style", { _color: cf._color != null ? cf._color : this._color, _bold: cf._code === "l" ? true : this._bold, _italic: cf._code === "o" ? true : this._italic, _code: cf._code });
			}
		};
		var styleFields = {};
		ctx.regClass("net/minecraft/network/chat/Style", { statics: styleFields, methods: styleMethods });
		styleEmpty = ctx.makeObj("net/minecraft/network/chat/Style", { _color: null, _bold: false, _italic: false, _code: "" });
		ctx.setStaticField("net/minecraft/network/chat/Style", "EMPTY", styleEmpty);

		var compMethods = {
			getString: function (maxLen) {
				var out = String(this._text || "");
				var sibs = this._siblings;
				if (sibs && sibs.length > 0) {
					for (var i = 0; i < sibs.length; i++) { out += sibs[i].getString(); }
				}
				if (arguments.length >= 1 && typeof maxLen === "number") { return out.slice(0, toInt(maxLen)); }
				return out;
			},
			"getString()Ljava/lang/String;": function () { return compMethods.getString.call(this); },
			"getString(I)Ljava/lang/String;": function (m) { return compMethods.getString.call(this, m); },
			_formattedString: function () {
				var pre = this._stylePrefix || "";
				var out = pre ? (pre + String(this._text || "")) : String(this._text || "");
				var sibs = this._siblings;
				if (sibs && sibs.length > 0) {
					for (var i = 0; i < sibs.length; i++) {
						out += (typeof sibs[i]._formattedString === "function" ? sibs[i]._formattedString() : sibs[i].getString());
					}
				}
				return out;
			},
			copy: function () {
				var o = makeComponentLiteral(ctx, this._text || "");
				o._siblings = this._siblings ? this._siblings.slice() : null;
				o._stylePrefix = this._stylePrefix || "";
				o._colorInt = this._colorInt != null ? this._colorInt : null;
				return o;
			},
			"copy()Lnet/minecraft/network/chat/MutableComponent;": function () { return compMethods.copy.call(this); },
			plainCopy: function () { return makeComponentLiteral(ctx, compMethods.getString.call(this)); },
			getStyle: function () { return this._style || styleEmpty; },
			getSiblings: function () {
				if (!this._siblings) { this._siblings = []; }
				return ctx.makeObj("java/util/ArrayList", { _arr: this._siblings });
			},
			toString: function () { return compMethods.getString.call(this); },
			"toString()Ljava/lang/String;": function () { return compMethods.getString.call(this); }
		};
		var compStatics = {
			literal: function (s) { return makeComponentLiteral(ctx, s); },
			"literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;": function (s) { return makeComponentLiteral(ctx, s); },
			translatable: function (key, args) {
				var k = toJsStr(key);
				var a = unwrapArray(args);
				if (a && a.length) {
					var i = 0;
					k = k.replace(/%[sd]/g, function () { return i < a.length ? javaToString(rt, a[i++]) : ""; });
				}
				return makeComponentLiteral(ctx, k);
			},
			"translatable(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;": function (k) { return makeComponentLiteral(ctx, k); },
			"translatable(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/network/chat/MutableComponent;": function (k, a) { return compStatics.translatable(k, a); },
			empty: function () { return makeComponentLiteral(ctx, ""); },
			"empty()Lnet/minecraft/network/chat/MutableComponent;": function () { return makeComponentLiteral(ctx, ""); },
			nullToEmpty: function (s) { return makeComponentLiteral(ctx, s === null || s === undefined ? "" : s); },
			"nullToEmpty(Ljava/lang/String;)Lnet/minecraft/network/chat/Component;": function (s) { return makeComponentLiteral(ctx, s === null || s === undefined ? "" : s); },
			keybind: function (k) { return makeComponentLiteral(ctx, k); }
		};
		ctx.regClass("net/minecraft/network/chat/Component", { isInterface: true, methods: compMethods, staticMethods: compStatics });

		var mutCompMethods = Object.assign({}, compMethods, {
			append: function (x) {
				if (!this._siblings) { this._siblings = []; }
				if (isJavaString(x)) { this._siblings.push(makeComponentLiteral(ctx, toJsStr(x))); }
				else if (x) { this._siblings.push(x); }
				return this;
			},
			"append(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;": function (s) { return mutCompMethods.append.call(this, s); },
			"append(Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;": function (c) { return mutCompMethods.append.call(this, c); },
			withStyle: function (arg) {
				if (arg && typeof arg === "object") {
					if (arg._code) {
						this._stylePrefix = (this._stylePrefix || "") + "\u00a7" + arg._code;
						if (typeof arg._color === "number") { this._colorInt = arg._color; }
					} else if (Array.isArray(arg) || Array.isArray(arg.data)) {
						var arr = unwrapArray(arg);
						for (var i = 0; i < arr.length; i++) {
							if (arr[i] && arr[i]._code) {
								this._stylePrefix = (this._stylePrefix || "") + "\u00a7" + arr[i]._code;
								if (typeof arr[i]._color === "number") { this._colorInt = arr[i]._color; }
							}
						}
					} else if (getClassNameOf(arg) === "net/minecraft/network/chat/Style") {
						this._style = arg;
						if (arg._code) { this._stylePrefix = (this._stylePrefix || "") + "\u00a7" + arg._code; }
						if (typeof arg._color === "number") { this._colorInt = arg._color; }
					} else {
						var st2 = invokeJavaCallback(rt, arg, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", [this._style || styleEmpty]);
						if (st2 && getClassNameOf(st2) === "net/minecraft/network/chat/Style") {
							this._style = st2;
							if (typeof st2._color === "number") { this._colorInt = st2._color; }
						}
					}
				}
				return this;
			},
			"withStyle(Lnet/minecraft/ChatFormatting;)Lnet/minecraft/network/chat/MutableComponent;": function (cf) { return mutCompMethods.withStyle.call(this, cf); },
			"withStyle([Lnet/minecraft/ChatFormatting;)Lnet/minecraft/network/chat/MutableComponent;": function (cfs) { return mutCompMethods.withStyle.call(this, cfs); },
			"withStyle(Ljava/util/function/UnaryOperator;)Lnet/minecraft/network/chat/MutableComponent;": function (op) { return mutCompMethods.withStyle.call(this, op); },
			"withStyle(Lnet/minecraft/network/chat/Style;)Lnet/minecraft/network/chat/MutableComponent;": function (st) { return mutCompMethods.withStyle.call(this, st); },
			withColor: function (rgb) { this._colorInt = toInt(rgb); return this; },
			"withColor(I)Lnet/minecraft/network/chat/MutableComponent;": function (rgb) { this._colorInt = toInt(rgb); return this; },
			setStyle: function (st) { this._style = st; return this; }
		});
		ctx.regClass("net/minecraft/network/chat/MutableComponent", {
			interfaces: ["net/minecraft/network/chat/Component"],
			methods: mutCompMethods,
			staticMethods: compStatics
		});

		// ---- Identifier & ResourceLocation ----
		function makeIdentifier(ns, path) {
			var proto = ctx._idProto || (ctx._idProto = ctx.getProto("net/minecraft/resources/Identifier"));
			var o = Object.create(proto);
			o.__cls = "net/minecraft/resources/Identifier";
			o.__class = "net/minecraft/resources/Identifier";
			o.$class = "net/minecraft/resources/Identifier";
			o._ns = String(ns || "minecraft");
			o._path = String(path || "");
			return o;
		}
		function parseIdentifier(raw) {
			if (raw === null || raw === undefined) { return null; }
			var s = toJsStr(raw);
			var idx = s.indexOf(":");
			if (idx >= 0) { return makeIdentifier(s.slice(0, idx) || "minecraft", s.slice(idx + 1)); }
			return makeIdentifier("minecraft", s);
		}
		var idMethods = {
			"<init>": function (a, b) {
				if (arguments.length >= 2) { this._ns = toJsStr(a); this._path = toJsStr(b); }
				else { var p = parseIdentifier(a); this._ns = p._ns; this._path = p._path; }
			},
			"<init>(Ljava/lang/String;Ljava/lang/String;)V": function (ns, p) { this._ns = toJsStr(ns); this._path = toJsStr(p); },
			"<init>(Ljava/lang/String;)V": function (s) { var p = parseIdentifier(s); this._ns = p._ns; this._path = p._path; },
			getNamespace: function () { return this._ns || "minecraft"; }, "getNamespace()Ljava/lang/String;": function () { return this._ns || "minecraft"; },
			getPath: function () { return this._path || ""; }, "getPath()Ljava/lang/String;": function () { return this._path || ""; },
			toString: function () { return (this._ns || "minecraft") + ":" + (this._path || ""); },
			"toString()Ljava/lang/String;": function () { return (this._ns || "minecraft") + ":" + (this._path || ""); },
			equals: function (o) { return !!(o && o._ns === this._ns && o._path === this._path); },
			"equals(Ljava/lang/Object;)Z": function (o) { return !!(o && o._ns === this._ns && o._path === this._path); },
			hashCode: function () { return javaStringHash((this._ns || "minecraft") + ":" + (this._path || "")); },
			"hashCode()I": function () { return javaStringHash((this._ns || "minecraft") + ":" + (this._path || "")); },
			compareTo: function (o) { var a = this.toString(), b = o ? o.toString() : ""; return a < b ? -1 : (a > b ? 1 : 0); },
			withPrefix: function (pre) { return makeIdentifier(this._ns, toJsStr(pre) + (this._path || "")); },
			withSuffix: function (suf) { return makeIdentifier(this._ns, (this._path || "") + toJsStr(suf)); },
			withPath: function (p) { return makeIdentifier(this._ns, toJsStr(p)); }
		};
		var idStatics = {
			fromNamespaceAndPath: function (ns, p) { return makeIdentifier(toJsStr(ns), toJsStr(p)); },
			"fromNamespaceAndPath(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;": function (ns, p) { return makeIdentifier(toJsStr(ns), toJsStr(p)); },
			"fromNamespaceAndPath(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;": function (ns, p) { return makeIdentifier(toJsStr(ns), toJsStr(p)); },
			of: function (ns, p) { return arguments.length >= 2 ? makeIdentifier(toJsStr(ns), toJsStr(p)) : parseIdentifier(ns); },
			parse: parseIdentifier,
			"parse(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;": parseIdentifier,
			"parse(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;": parseIdentifier,
			tryParse: parseIdentifier,
			"tryParse(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;": parseIdentifier,
			"tryParse(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;": parseIdentifier,
			withDefaultNamespace: function (p) { return makeIdentifier("minecraft", toJsStr(p)); },
			"withDefaultNamespace(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;": function (p) { return makeIdentifier("minecraft", toJsStr(p)); },
			"withDefaultNamespace(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;": function (p) { return makeIdentifier("minecraft", toJsStr(p)); },
			tryBuild: function (ns, p) { return makeIdentifier(toJsStr(ns), toJsStr(p)); }
		};
		ctx.regClass("net/minecraft/resources/Identifier", { interfaces: ["java/lang/Comparable"], methods: idMethods, staticMethods: idStatics });
		ctx.regAlias("net/minecraft/resources/ResourceLocation", "net/minecraft/resources/Identifier");

		// ---- ResourceKey ----
		ctx.regClass("net/minecraft/resources/ResourceKey", {
			methods: {
				identifier: function () { return this._id || parseIdentifier("minecraft:overworld"); },
				"identifier()Lnet/minecraft/resources/Identifier;": function () { return this.identifier(); },
				location: function () { return this.identifier(); },
				"location()Lnet/minecraft/resources/ResourceLocation;": function () { return this.identifier(); },
				toString: function () { return "ResourceKey[minecraft:dimension / " + this.identifier().toString() + "]"; }
			}
		});

		// ---- Vec3i & BlockPos ----
		function makeBlockPos(x, y, z) {
			var proto = ctx._bpProto || (ctx._bpProto = ctx.getProto("net/minecraft/core/BlockPos"));
			var o = Object.create(proto);
			o.__cls = "net/minecraft/core/BlockPos";
			o.__class = "net/minecraft/core/BlockPos";
			o.$class = "net/minecraft/core/BlockPos";
			o.x = x | 0;
			o.y = y | 0;
			o.z = z | 0;
			return o;
		}
		var bpMethods = {
			"<init>": function (x, y, z) { this.x = Math.floor(toNum(x)) | 0; this.y = Math.floor(toNum(y)) | 0; this.z = Math.floor(toNum(z)) | 0; },
			"<init>(III)V": function (x, y, z) { this.x = toInt(x); this.y = toInt(y); this.z = toInt(z); },
			"<init>(DDD)V": function (x, y, z) { this.x = Math.floor(toNum(x)) | 0; this.y = Math.floor(toNum(y)) | 0; this.z = Math.floor(toNum(z)) | 0; },
			getX: function () { return this.x | 0; }, "getX()I": function () { return this.x | 0; },
			getY: function () { return this.y | 0; }, "getY()I": function () { return this.y | 0; },
			getZ: function () { return this.z | 0; }, "getZ()I": function () { return this.z | 0; },
			above: function (n) { return makeBlockPos(this.x, this.y + (arguments.length ? toInt(n) : 1), this.z); },
			"above()Lnet/minecraft/core/BlockPos;": function () { return makeBlockPos(this.x, this.y + 1, this.z); },
			"above(I)Lnet/minecraft/core/BlockPos;": function (n) { return makeBlockPos(this.x, this.y + toInt(n), this.z); },
			below: function (n) { return makeBlockPos(this.x, this.y - (arguments.length ? toInt(n) : 1), this.z); },
			"below()Lnet/minecraft/core/BlockPos;": function () { return makeBlockPos(this.x, this.y - 1, this.z); },
			"below(I)Lnet/minecraft/core/BlockPos;": function (n) { return makeBlockPos(this.x, this.y - toInt(n), this.z); },
			north: function (n) { return makeBlockPos(this.x, this.y, this.z - (arguments.length ? toInt(n) : 1)); },
			"north()Lnet/minecraft/core/BlockPos;": function () { return makeBlockPos(this.x, this.y, this.z - 1); },
			"north(I)Lnet/minecraft/core/BlockPos;": function (n) { return makeBlockPos(this.x, this.y, this.z - toInt(n)); },
			south: function (n) { return makeBlockPos(this.x, this.y, this.z + (arguments.length ? toInt(n) : 1)); },
			"south()Lnet/minecraft/core/BlockPos;": function () { return makeBlockPos(this.x, this.y, this.z + 1); },
			"south(I)Lnet/minecraft/core/BlockPos;": function (n) { return makeBlockPos(this.x, this.y, this.z + toInt(n)); },
			east: function (n) { return makeBlockPos(this.x + (arguments.length ? toInt(n) : 1), this.y, this.z); },
			"east()Lnet/minecraft/core/BlockPos;": function () { return makeBlockPos(this.x + 1, this.y, this.z); },
			"east(I)Lnet/minecraft/core/BlockPos;": function (n) { return makeBlockPos(this.x + toInt(n), this.y, this.z); },
			west: function (n) { return makeBlockPos(this.x - (arguments.length ? toInt(n) : 1), this.y, this.z); },
			"west()Lnet/minecraft/core/BlockPos;": function () { return makeBlockPos(this.x - 1, this.y, this.z); },
			"west(I)Lnet/minecraft/core/BlockPos;": function (n) { return makeBlockPos(this.x - toInt(n), this.y, this.z); },
			offset: function (a, b, c) {
				if (arguments.length >= 3) { return makeBlockPos(this.x + toInt(a), this.y + toInt(b), this.z + toInt(c)); }
				return makeBlockPos(this.x + (a ? a.x | 0 : 0), this.y + (a ? a.y | 0 : 0), this.z + (a ? a.z | 0 : 0));
			},
			"offset(III)Lnet/minecraft/core/BlockPos;": function (dx, dy, dz) { return makeBlockPos(this.x + toInt(dx), this.y + toInt(dy), this.z + toInt(dz)); },
			"offset(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/core/BlockPos;": function (v) { return makeBlockPos(this.x + (v.x | 0), this.y + (v.y | 0), this.z + (v.z | 0)); },
			subtract: function (v) { return makeBlockPos(this.x - (v.x | 0), this.y - (v.y | 0), this.z - (v.z | 0)); },
			distSqr: function (v) {
				var dx = (this.x | 0) - (v.x | 0), dy = (this.y | 0) - (v.y | 0), dz = (this.z | 0) - (v.z | 0);
				return dx * dx + dy * dy + dz * dz;
			},
			"distSqr(Lnet/minecraft/core/Vec3i;)D": function (v) { return bpMethods.distSqr.call(this, v); },
			distToCenterSqr: function (x, y, z) {
				var dx = (this.x + 0.5) - toNum(x), dy = (this.y + 0.5) - toNum(y), dz = (this.z + 0.5) - toNum(z);
				return dx * dx + dy * dy + dz * dz;
			},
			distManhattan: function (v) { return Math.abs((this.x | 0) - (v.x | 0)) + Math.abs((this.y | 0) - (v.y | 0)) + Math.abs((this.z | 0) - (v.z | 0)); },
			immutable: function () { return this; },
			toShortString: function () { return (this.x | 0) + ", " + (this.y | 0) + ", " + (this.z | 0); },
			toString: function () { return "BlockPos{x=" + (this.x | 0) + ", y=" + (this.y | 0) + ", z=" + (this.z | 0) + "}"; },
			equals: function (o) { return !!(o && (o.x | 0) === (this.x | 0) && (o.y | 0) === (this.y | 0) && (o.z | 0) === (this.z | 0)); },
			hashCode: function () { return (((this.y + this.z * 31) * 31) + this.x) | 0; }
		};
		var bpFields = {};
		ctx.regClass("net/minecraft/core/Vec3i", { methods: bpMethods });
		ctx.regClass("net/minecraft/core/BlockPos", {
			superName: "net/minecraft/core/Vec3i",
			statics: bpFields,
			methods: bpMethods,
			staticMethods: {
				containing: function (a, b, c) {
					if (arguments.length >= 3) { return makeBlockPos(Math.floor(toNum(a)), Math.floor(toNum(b)), Math.floor(toNum(c))); }
					return makeBlockPos(Math.floor(toNum(a.x)), Math.floor(toNum(a.y)), Math.floor(toNum(a.z)));
				},
				"containing(DDD)Lnet/minecraft/core/BlockPos;": function (x, y, z) { return makeBlockPos(Math.floor(toNum(x)), Math.floor(toNum(y)), Math.floor(toNum(z))); }
			}
		});
		ctx.setStaticField("net/minecraft/core/BlockPos", "ZERO", makeBlockPos(0, 0, 0));

		// ---- Vec3 ----
		function makeVec3(x, y, z) {
			var proto = ctx._vec3Proto || (ctx._vec3Proto = ctx.getProto("net/minecraft/world/phys/Vec3"));
			var o = Object.create(proto);
			o.__cls = "net/minecraft/world/phys/Vec3";
			o.__class = "net/minecraft/world/phys/Vec3";
			o.$class = "net/minecraft/world/phys/Vec3";
			o.x = typeof x === "number" ? x : toNum(x);
			o.y = typeof y === "number" ? y : toNum(y);
			o.z = typeof z === "number" ? z : toNum(z);
			return o;
		}
		var vec3Methods = {
			"<init>": function (x, y, z) { this.x = toNum(x); this.y = toNum(y); this.z = toNum(z); },
			"<init>(DDD)V": function (x, y, z) { this.x = toNum(x); this.y = toNum(y); this.z = toNum(z); },
			x: function () { return this.x; }, "x()D": function () { return this.x; },
			y: function () { return this.y; }, "y()D": function () { return this.y; },
			z: function () { return this.z; }, "z()D": function () { return this.z; },
			length: function () { return Math.sqrt(this.x * this.x + this.y * this.y + this.z * this.z); },
			"length()D": function () { return this.length(); },
			lengthSqr: function () { return this.x * this.x + this.y * this.y + this.z * this.z; },
			horizontalDistance: function () { return Math.sqrt(this.x * this.x + this.z * this.z); },
			distanceTo: function (v) { var dx = v.x - this.x, dy = v.y - this.y, dz = v.z - this.z; return Math.sqrt(dx * dx + dy * dy + dz * dz); },
			"distanceTo(Lnet/minecraft/world/phys/Vec3;)D": function (v) { return this.distanceTo(v); },
			distanceToSqr: function (a, b, c) {
				var dx = (arguments.length >= 3 ? toNum(a) : a.x) - this.x;
				var dy = (arguments.length >= 3 ? toNum(b) : a.y) - this.y;
				var dz = (arguments.length >= 3 ? toNum(c) : a.z) - this.z;
				return dx * dx + dy * dy + dz * dz;
			},
			normalize: function () { var l = this.length(); return l < 1e-4 ? vec3Fields.ZERO : makeVec3(this.x / l, this.y / l, this.z / l); },
			"normalize()Lnet/minecraft/world/phys/Vec3;": function () { return this.normalize(); },
			add: function (a, b, c) {
				if (arguments.length >= 3) { return makeVec3(this.x + toNum(a), this.y + toNum(b), this.z + toNum(c)); }
				return makeVec3(this.x + a.x, this.y + a.y, this.z + a.z);
			},
			"add(DDD)Lnet/minecraft/world/phys/Vec3;": function (x, y, z) { return makeVec3(this.x + toNum(x), this.y + toNum(y), this.z + toNum(z)); },
			"add(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;": function (v) { return makeVec3(this.x + v.x, this.y + v.y, this.z + v.z); },
			subtract: function (a, b, c) {
				if (arguments.length >= 3) { return makeVec3(this.x - toNum(a), this.y - toNum(b), this.z - toNum(c)); }
				return makeVec3(this.x - a.x, this.y - a.y, this.z - a.z);
			},
			"subtract(DDD)Lnet/minecraft/world/phys/Vec3;": function (x, y, z) { return makeVec3(this.x - toNum(x), this.y - toNum(y), this.z - toNum(z)); },
			"subtract(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;": function (v) { return makeVec3(this.x - v.x, this.y - v.y, this.z - v.z); },
			scale: function (f) { var s = toNum(f); return makeVec3(this.x * s, this.y * s, this.z * s); },
			"scale(D)Lnet/minecraft/world/phys/Vec3;": function (f) { return this.scale(f); },
			multiply: function (a, b, c) {
				if (arguments.length >= 3) { return makeVec3(this.x * toNum(a), this.y * toNum(b), this.z * toNum(c)); }
				return makeVec3(this.x * a.x, this.y * a.y, this.z * a.z);
			},
			dot: function (v) { return this.x * v.x + this.y * v.y + this.z * v.z; },
			"dot(Lnet/minecraft/world/phys/Vec3;)D": function (v) { return this.dot(v); },
			cross: function (v) { return makeVec3(this.y * v.z - this.z * v.y, this.z * v.x - this.x * v.z, this.x * v.y - this.y * v.x); },
			"cross(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;": function (v) { return this.cross(v); },
			lerp: function (v, t) { var d = toNum(t); return makeVec3(this.x + (v.x - this.x) * d, this.y + (v.y - this.y) * d, this.z + (v.z - this.z) * d); },
			reverse: function () { return makeVec3(-this.x, -this.y, -this.z); },
			toString: function () { return "(" + this.x + ", " + this.y + ", " + this.z + ")"; },
			equals: function (o) { return !!(o && o.x === this.x && o.y === this.y && o.z === this.z); },
			hashCode: function () { return ((this.x * 31 + this.y) * 31 + this.z) | 0; }
		};
		var vec3Fields = {};
		ctx.regClass("net/minecraft/world/phys/Vec3", { statics: vec3Fields, methods: vec3Methods });
		ctx.setStaticField("net/minecraft/world/phys/Vec3", "ZERO", makeVec3(0, 0, 0));
		ctx._vec3Scratch = makeVec3(0, 0, 0);

		// ---- Mth ----
		var mthStatics = {
			clamp: function (v, lo, hi) { return Math.max(toNum(lo), Math.min(toNum(hi), toNum(v))); },
			"clamp(III)I": function (v, lo, hi) { return Math.max(toInt(lo), Math.min(toInt(hi), toInt(v))) | 0; },
			"clamp(JJJ)J": function (v, lo, hi) { return makeLong(ctx, Math.max(toNum(lo), Math.min(toNum(hi), toNum(v)))); },
			"clamp(FFF)F": function (v, lo, hi) { return Math.fround(Math.max(toNum(lo), Math.min(toNum(hi), toNum(v)))); },
			"clamp(DDD)D": function (v, lo, hi) { return Math.max(toNum(lo), Math.min(toNum(hi), toNum(v))); },
			lerp: function (t, a, b) { return toNum(a) + toNum(t) * (toNum(b) - toNum(a)); },
			"lerp(FFF)F": function (t, a, b) { return Math.fround(toNum(a) + toNum(t) * (toNum(b) - toNum(a))); },
			"lerp(DDD)D": function (t, a, b) { return toNum(a) + toNum(t) * (toNum(b) - toNum(a)); },
			clampedLerp: function (a, b, t) { var cl = Math.max(0, Math.min(1, toNum(t))); return toNum(a) + cl * (toNum(b) - toNum(a)); },
			inverseLerp: function (v, a, b) { return (toNum(v) - toNum(a)) / (toNum(b) - toNum(a)); },
			floor: function (v) { return Math.floor(toNum(v)) | 0; },
			"floor(F)I": function (v) { return Math.floor(toNum(v)) | 0; },
			"floor(D)I": function (v) { return Math.floor(toNum(v)) | 0; },
			lfloor: function (v) { return makeLong(ctx, Math.floor(toNum(v))); },
			ceil: function (v) { return Math.ceil(toNum(v)) | 0; },
			"ceil(F)I": function (v) { return Math.ceil(toNum(v)) | 0; },
			"ceil(D)I": function (v) { return Math.ceil(toNum(v)) | 0; },
			wrapDegrees: function (deg) {
				var d = toNum(deg) % 360.0;
				if (d >= 180.0) { d -= 360.0; }
				if (d < -180.0) { d += 360.0; }
				return d;
			},
			"wrapDegrees(F)F": function (d) { return Math.fround(mthStatics.wrapDegrees(d)); },
			"wrapDegrees(D)D": function (d) { return mthStatics.wrapDegrees(d); },
			"wrapDegrees(I)I": function (d) { return Math.trunc(mthStatics.wrapDegrees(d)) | 0; },
			degreesDifference: function (a, b) { return mthStatics.wrapDegrees(toNum(b) - toNum(a)); },
			sin: function (x) { return Math.fround(Math.sin(toNum(x))); },
			"sin(F)F": function (x) { return Math.fround(Math.sin(toNum(x))); },
			cos: function (x) { return Math.fround(Math.cos(toNum(x))); },
			"cos(F)F": function (x) { return Math.fround(Math.cos(toNum(x))); },
			sqrt: function (x) { return Math.fround(Math.sqrt(toNum(x))); },
			"sqrt(F)F": function (x) { return Math.fround(Math.sqrt(toNum(x))); },
			abs: function (x) { return Math.abs(toNum(x)); },
			"abs(F)F": function (x) { return Math.fround(Math.abs(toNum(x))); },
			"abs(I)I": function (x) { return Math.abs(toInt(x)) | 0; },
			atan2: function (y, x) { return Math.atan2(toNum(y), toNum(x)); },
			positiveModulo: function (a, b) { var x = toNum(a), y = toNum(b); return ((x % y) + y) % y; },
			sign: function (x) { var n = toNum(x); return n > 0 ? 1 : (n < 0 ? -1 : 0); },
			equal: function (a, b) { return Math.abs(toNum(b) - toNum(a)) < 1e-5; }
		};
		ctx.regClass("net/minecraft/util/Mth", {
			statics: { PI: Math.PI, HALF_PI: Math.PI / 2, TWO_PI: Math.PI * 2, DEG_TO_RAD: Math.PI / 180, RAD_TO_DEG: 180 / Math.PI, EPSILON: 1e-5 },
			staticMethods: mthStatics
		});

		// ---- InputConstants & GLFW ----
		var icTypeStatics = {};
		ctx.regClass("com/mojang/blaze3d/platform/InputConstants$Type", {
			superName: "java/lang/Enum",
			statics: icTypeStatics,
			methods: {
				getOrCreate: function (code) {
					return ctx.makeObj("com/mojang/blaze3d/platform/InputConstants$Key", {
						_type: this._enumName || "KEYSYM", _code: toInt(code)
					});
				},
				"getOrCreate(I)Lcom/mojang/blaze3d/platform/InputConstants$Key;": function (c) { return this.getOrCreate(c); }
			}
		});
		icTypeStatics.KEYSYM = ctx.makeObj("com/mojang/blaze3d/platform/InputConstants$Type", { _enumName: "KEYSYM", _ordinal: 0 });
		icTypeStatics.SCANCODE = ctx.makeObj("com/mojang/blaze3d/platform/InputConstants$Type", { _enumName: "SCANCODE", _ordinal: 1 });
		icTypeStatics.MOUSE = ctx.makeObj("com/mojang/blaze3d/platform/InputConstants$Type", { _enumName: "MOUSE", _ordinal: 2 });
		ctx.setStaticField("com/mojang/blaze3d/platform/InputConstants$Type", "KEYSYM", icTypeStatics.KEYSYM);
		ctx.setStaticField("com/mojang/blaze3d/platform/InputConstants$Type", "SCANCODE", icTypeStatics.SCANCODE);
		ctx.setStaticField("com/mojang/blaze3d/platform/InputConstants$Type", "MOUSE", icTypeStatics.MOUSE);

		ctx.regClass("com/mojang/blaze3d/platform/InputConstants$Key", {
			methods: {
				getValue: function () { return this._code | 0; }, "getValue()I": function () { return this._code | 0; },
				getType: function () { return icTypeStatics[this._type] || icTypeStatics.KEYSYM; },
				getName: function () { return glfwKeyName(this._code | 0); }, "getName()Ljava/lang/String;": function () { return glfwKeyName(this._code | 0); },
				getDisplayName: function () { return makeComponentLiteral(ctx, glfwKeyName(this._code | 0)); },
				toString: function () { return glfwKeyName(this._code | 0); },
				equals: function (o) { return !!(o && o._type === this._type && (o._code | 0) === (this._code | 0)); }
			}
		});

		var icStatics = Object.assign({}, GLFW_KEYS, {
			UNKNOWN: ctx.makeObj("com/mojang/blaze3d/platform/InputConstants$Key", { _type: "KEYSYM", _code: -1 })
		});
		// Also alias KEY_A..KEY_Z without GLFW_ prefix as InputConstants uses KEY_* constants!
		var gkNames = Object.keys(GLFW_KEYS);
		for (var gi = 0; gi < gkNames.length; gi++) {
			var kn = gkNames[gi];
			if (kn.indexOf("GLFW_") === 0) { icStatics[kn.slice(5)] = GLFW_KEYS[kn]; }
		}
		ctx.regClass("com/mojang/blaze3d/platform/InputConstants", {
			statics: icStatics,
			staticMethods: {
				isKeyDown: function (_win, key) {
					InputState.attachDomListeners();
					var k = toInt(key);
					return k >= 0 && k < 512 && InputState.keyDownBits[k] !== 0;
				},
				"isKeyDown(JI)Z": function (_w, key) {
					var k = toInt(key);
					return k >= 0 && k < 512 && InputState.keyDownBits[k] !== 0;
				},
				"isKeyDown(Lcom/mojang/blaze3d/platform/Window;I)Z": function (_w, key) {
					var k = toInt(key);
					return k >= 0 && k < 512 && InputState.keyDownBits[k] !== 0;
				},
				getKey: function (a, b) {
					if (isJavaString(a)) { return ctx.makeObj("com/mojang/blaze3d/platform/InputConstants$Key", { _type: "KEYSYM", _code: -1, _name: toJsStr(a) }); }
					return ctx.makeObj("com/mojang/blaze3d/platform/InputConstants$Key", { _type: "KEYSYM", _code: toInt(a) });
				},
				"getKey(II)Lcom/mojang/blaze3d/platform/InputConstants$Key;": function (k) { return ctx.makeObj("com/mojang/blaze3d/platform/InputConstants$Key", { _type: "KEYSYM", _code: toInt(k) }); }
			}
		});
		ctx.regClass("org/lwjgl/glfw/GLFW", {
			statics: GLFW_KEYS,
			staticMethods: {
				glfwGetKey: function (_win, key) {
					InputState.attachDomListeners();
					var k = toInt(key);
					return (k >= 0 && k < 512 && InputState.keyDownBits[k] !== 0) ? 1 : 0;
				},
				"glfwGetKey(JI)I": function (_w, key) {
					var k = toInt(key);
					return (k >= 0 && k < 512 && InputState.keyDownBits[k] !== 0) ? 1 : 0;
				},
				glfwGetMouseButton: function (_win, btn) {
					InputState.attachDomListeners();
					var b = toInt(btn);
					return (b >= 0 && b < 16 && InputState.mouseDownBits[b] !== 0) ? 1 : 0;
				},
				"glfwGetMouseButton(JI)I": function (_w, btn) {
					var b = toInt(btn);
					return (b >= 0 && b < 16 && InputState.mouseDownBits[b] !== 0) ? 1 : 0;
				},
				glfwGetTime: function () { return (typeof performance !== "undefined" ? performance.now() : Date.now()) / 1000.0; },
				"glfwGetTime()D": function () { return (typeof performance !== "undefined" ? performance.now() : Date.now()) / 1000.0; }
			}
		});

		// ---- KeyMapping ----
		var kmMethods = {
			"<init>": function (name, a, b, c) {
				this._name = toJsStr(name);
				if (arguments.length >= 4) {
					this._keyType = (a && a._enumName) ? a._enumName : "KEYSYM";
					this._keyCode = toInt(b);
					this._category = toJsStr(c);
				} else {
					this._keyType = "KEYSYM";
					this._keyCode = toInt(a);
					this._category = toJsStr(b || "key.categories.misc");
				}
				this._down = false;
				this._clickCount = 0;
				if (InputState.registeredMappings.indexOf(this) < 0) {
					InputState.registeredMappings.push(this);
				}
				InputState.attachDomListeners();
			},
			"<init>(Ljava/lang/String;ILjava/lang/String;)V": function (n, k, c) { kmMethods["<init>"].call(this, n, k, c); },
			"<init>(Ljava/lang/String;Lcom/mojang/blaze3d/platform/InputConstants$Type;ILjava/lang/String;)V": function (n, t, k, c) { kmMethods["<init>"].call(this, n, t, k, c); },
			"<init>(Ljava/lang/String;ILnet/minecraft/client/KeyMapping$Category;)V": function (n, k, c) { kmMethods["<init>"].call(this, n, k, c); },
			"<init>(Ljava/lang/String;Lcom/mojang/blaze3d/platform/InputConstants$Type;ILnet/minecraft/client/KeyMapping$Category;)V": function (n, t, k, c) { kmMethods["<init>"].call(this, n, t, k, c); },
			isDown: function () {
				if (this._down) { return true; }
				var code = this._keyCode | 0;
				if (this._keyType === "MOUSE") {
					return code >= 0 && code < 16 && InputState.mouseDownBits[code] !== 0;
				}
				return code >= 0 && code < 512 && InputState.keyDownBits[code] !== 0;
			},
			"isDown()Z": function () { return kmMethods.isDown.call(this); },
			setDown: function (v) { this._down = toBool(v); },
			"setDown(Z)V": function (v) { this._down = toBool(v); },
			consumeClick: function () {
				var c = this._clickCount | 0;
				if (c > 0) { this._clickCount = (c - 1) | 0; return true; }
				return false;
			},
			"consumeClick()Z": function () { return kmMethods.consumeClick.call(this); },
			getName: function () { return this._name || ""; }, "getName()Ljava/lang/String;": function () { return this._name || ""; },
			getCategory: function () { return this._category || "key.categories.misc"; },
			"getCategory()Ljava/lang/String;": function () { return this._category || "key.categories.misc"; },
			getDefaultKey: function () { return ctx.makeObj("com/mojang/blaze3d/platform/InputConstants$Key", { _type: this._keyType || "KEYSYM", _code: this._keyCode | 0 }); },
			"getDefaultKey()Lcom/mojang/blaze3d/platform/InputConstants$Key;": function () { return kmMethods.getDefaultKey.call(this); },
			getTranslatedKeyMessage: function () { return makeComponentLiteral(ctx, glfwKeyName(this._keyCode | 0)); },
			matches: function (key, scan) { return (this._keyType === "KEYSYM" || !this._keyType) && (this._keyCode | 0) === toInt(key); },
			"matches(II)Z": function (k, s) { return kmMethods.matches.call(this, k, s); },
			matchesMouse: function (btn) { return this._keyType === "MOUSE" && (this._keyCode | 0) === toInt(btn); },
			isUnbound: function () { return (this._keyCode | 0) < 0; },
			same: function (other) { return !!(other && other._keyCode === this._keyCode && other._keyType === this._keyType); }
		};
		ctx.regClass("net/minecraft/client/KeyMapping$Category", {
			methods: { "<init>": function (id) { this._id = id; }, toString: function () { return javaToString(rt, this._id); } },
			staticMethods: { register: function (id) { return ctx.makeObj("net/minecraft/client/KeyMapping$Category", { _id: id }); } }
		});
		ctx.regClass("net/minecraft/client/KeyMapping", { methods: kmMethods });

		// ---- Window & DeltaTracker ----
		ctx.regClass("com/mojang/blaze3d/platform/Window", {
			methods: {
				getGuiScaledWidth: function () { return computeWindowMetrics(ctx).guiWidth; },
				"getGuiScaledWidth()I": function () { return computeWindowMetrics(ctx).guiWidth; },
				getGuiScaledHeight: function () { return computeWindowMetrics(ctx).guiHeight; },
				"getGuiScaledHeight()I": function () { return computeWindowMetrics(ctx).guiHeight; },
				getWidth: function () { return computeWindowMetrics(ctx).fbW; },
				"getWidth()I": function () { return computeWindowMetrics(ctx).fbW; },
				getHeight: function () { return computeWindowMetrics(ctx).fbH; },
				"getHeight()I": function () { return computeWindowMetrics(ctx).fbH; },
				getScreenWidth: function () { return computeWindowMetrics(ctx).cssW; },
				getScreenHeight: function () { return computeWindowMetrics(ctx).cssH; },
				getGuiScale: function () { return computeWindowMetrics(ctx).guiScale; },
				"getGuiScale()I": function () { return computeWindowMetrics(ctx).guiScale; },
				"getGuiScale()D": function () { return computeWindowMetrics(ctx).guiScale; },
				getWindow: function () { return makeLong(ctx, 1); },
				"getWindow()J": function () { return makeLong(ctx, 1); }
			}
		});
		ctx.regClass("net/minecraft/client/DeltaTracker", {
			isInterface: true,
			methods: {
				getGameTimeDeltaPartialTick: function () { return Math.fround(this._partial || 0); },
				"getGameTimeDeltaPartialTick(Z)F": function () { return Math.fround(this._partial || 0); },
				getGameTimeDeltaTicks: function () { return Math.fround(this._ticks || 1); },
				"getGameTimeDeltaTicks()F": function () { return Math.fround(this._ticks || 1); },
				getRealtimeDeltaTicks: function () { return Math.fround(this._ticks || 1); },
				valueOf: function () { return Math.fround(this._partial || 0); }
			}
		});
		ctx.regClass("net/minecraft/client/DeltaTracker$Timer", { superName: "net/minecraft/client/DeltaTracker" });

		// ---- FoodData & ItemStack & Item ----
		ctx.regClass("net/minecraft/world/food/FoodData", {
			methods: {
				getFoodLevel: function () { var p = ctx.getGameState(false).player; return p ? (p.food | 0) : 20; },
				"getFoodLevel()I": function () { return this.getFoodLevel(); },
				getSaturationLevel: function () { var p = ctx.getGameState(false).player; return Math.fround(p && typeof p.saturation === "number" ? p.saturation : 5.0); },
				"getSaturationLevel()F": function () { return this.getSaturationLevel(); },
				getExhaustionLevel: function () { return 0; },
				"getExhaustionLevel()F": function () { return 0; },
				needsFood: function () { return this.getFoodLevel() < 20; }
			}
		});
		ctx.regClass("net/minecraft/world/item/Item", {
			methods: {
				toString: function () { return this._id || "minecraft:air"; },
				"toString()Ljava/lang/String;": function () { return this._id || "minecraft:air"; }
			}
		});
		var airItem = ctx.makeObj("net/minecraft/world/item/Item", { _id: "minecraft:air" });
		var itemStackStatics = {};
		ctx.regClass("net/minecraft/world/item/ItemStack", {
			statics: itemStackStatics,
			methods: {
				isEmpty: function () { return !this._count || this._count <= 0 || this._id === "minecraft:air"; },
				"isEmpty()Z": function () { return this.isEmpty(); },
				getCount: function () { return this._count | 0; }, "getCount()I": function () { return this._count | 0; },
				getMaxStackSize: function () { return 64; },
				getDamageValue: function () { return this._damage | 0; }, "getDamageValue()I": function () { return this._damage | 0; },
				getMaxDamage: function () { return this._maxDamage | 0; }, "getMaxDamage()I": function () { return this._maxDamage | 0; },
				isDamageableItem: function () { return (this._maxDamage | 0) > 0; },
				isDamaged: function () { return (this._damage | 0) > 0; },
				getHoverName: function () { return makeComponentLiteral(ctx, this._name || "Air"); },
				"getHoverName()Lnet/minecraft/network/chat/Component;": function () { return this.getHoverName(); },
				getDisplayName: function () { return this.getHoverName(); },
				getItem: function () { return this._item || airItem; },
				"getItem()Lnet/minecraft/world/item/Item;": function () { return this.getItem(); },
				copy: function () { return ctx.makeObj("net/minecraft/world/item/ItemStack", { _id: this._id, _name: this._name, _count: this._count, _damage: this._damage, _maxDamage: this._maxDamage, _item: this._item }); },
				toString: function () { return (this._count | 0) + " " + (this._id || "minecraft:air"); },
				"toString()Ljava/lang/String;": function () { return this.toString(); }
			}
		});
		ctx.emptyItemStack = ctx.makeObj("net/minecraft/world/item/ItemStack", { _id: "minecraft:air", _name: "Air", _count: 0, _damage: 0, _maxDamage: 0, _item: airItem });
		itemStackStatics.EMPTY = ctx.emptyItemStack;
		ctx.setStaticField("net/minecraft/world/item/ItemStack", "EMPTY", ctx.emptyItemStack);

		// ---- Entity -> LivingEntity -> Player -> AbstractClientPlayer -> LocalPlayer ----
		function playerState() { var st = ctx.getGameState(false); return (st && st.player) || null; }
		var entityMethods = {
			getX: function () { var p = playerState(); return p ? toNum(p.x) : 0; }, "getX()D": function () { return entityMethods.getX.call(this); },
			getY: function () { var p = playerState(); return p ? toNum(p.y) : 0; }, "getY()D": function () { return entityMethods.getY.call(this); },
			getZ: function () { var p = playerState(); return p ? toNum(p.z) : 0; }, "getZ()D": function () { return entityMethods.getZ.call(this); },
			getBlockX: function () { return Math.floor(entityMethods.getX.call(this)) | 0; },
			getBlockY: function () { return Math.floor(entityMethods.getY.call(this)) | 0; },
			getBlockZ: function () { return Math.floor(entityMethods.getZ.call(this)) | 0; },
			blockPosition: function () { return makeBlockPos(Math.floor(entityMethods.getX.call(this)), Math.floor(entityMethods.getY.call(this)), Math.floor(entityMethods.getZ.call(this))); },
			"blockPosition()Lnet/minecraft/core/BlockPos;": function () { return entityMethods.blockPosition.call(this); },
			position: function () { return makeVec3(entityMethods.getX.call(this), entityMethods.getY.call(this), entityMethods.getZ.call(this)); },
			"position()Lnet/minecraft/world/phys/Vec3;": function () { return entityMethods.position.call(this); },
			getEyePosition: function () { return makeVec3(entityMethods.getX.call(this), entityMethods.getY.call(this) + 1.62, entityMethods.getZ.call(this)); },
			getYRot: function () { var p = playerState(); return Math.fround(ctx.yawLocal !== null ? ctx.yawLocal : (p ? toNum(p.yaw) : 0)); },
			"getYRot()F": function () { return entityMethods.getYRot.call(this); },
			getXRot: function () { var p = playerState(); return Math.fround(ctx.pitchLocal !== null ? ctx.pitchLocal : (p ? toNum(p.pitch) : 0)); },
			"getXRot()F": function () { return entityMethods.getXRot.call(this); },
			setYRot: function (yaw) {
				var y = Math.fround(toNum(yaw));
				ctx.yawLocal = y;
				var em = ctx.eagler();
				if (em && typeof em.setRotation === "function") { em.setRotation(y, entityMethods.getXRot.call(this)); }
				if (ctx.cachedState && ctx.cachedState.player) { ctx.cachedState.player.yaw = y; }
			},
			"setYRot(F)V": function (y) { entityMethods.setYRot.call(this, y); },
			setXRot: function (pitch) {
				var pt = Math.fround(Math.max(-90, Math.min(90, toNum(pitch))));
				ctx.pitchLocal = pt;
				var em = ctx.eagler();
				if (em && typeof em.setRotation === "function") { em.setRotation(entityMethods.getYRot.call(this), pt); }
				if (ctx.cachedState && ctx.cachedState.player) { ctx.cachedState.player.pitch = pt; }
			},
			"setXRot(F)V": function (p) { entityMethods.setXRot.call(this, p); },
			isSprinting: function () { var p = playerState(); return ctx.sprintingLocal !== null ? ctx.sprintingLocal : !!(p && p.sprinting); },
			"isSprinting()Z": function () { return entityMethods.isSprinting.call(this); },
			setSprinting: function (v) {
				var b = toBool(v);
				ctx.sprintingLocal = b;
				var em = ctx.eagler();
				if (em && typeof em.setSprinting === "function") { em.setSprinting(b); }
				if (ctx.cachedState && ctx.cachedState.player) { ctx.cachedState.player.sprinting = b; }
			},
			"setSprinting(Z)V": function (v) { entityMethods.setSprinting.call(this, v); },
			isShiftKeyDown: function () { var p = playerState(); return !!(p && p.crouching) || InputState.keyDownBits[340] !== 0; },
			"isShiftKeyDown()Z": function () { return entityMethods.isShiftKeyDown.call(this); },
			isCrouching: function () { var p = playerState(); return !!(p && p.crouching); },
			"isCrouching()Z": function () { return entityMethods.isCrouching.call(this); },
			onGround: function () { var p = playerState(); return !!(p && p.onGround); },
			"onGround()Z": function () { return entityMethods.onGround.call(this); },
			isOnGround: function () { return entityMethods.onGround.call(this); },
			"isOnGround()Z": function () { return entityMethods.onGround.call(this); },
			isInWater: function () { var p = playerState(); return !!(p && p.inWater); },
			"isInWater()Z": function () { return entityMethods.isInWater.call(this); },
			isUnderWater: function () { var p = playerState(); return !!(p && (p.underWater || p.inWater)); },
			"isUnderWater()Z": function () { return entityMethods.isUnderWater.call(this); },
			getDeltaMovement: function () {
				var p = playerState();
				return makeVec3(p && p.vx || 0, p && p.vy || 0, p && p.vz || 0);
			},
			"getDeltaMovement()Lnet/minecraft/world/phys/Vec3;": function () { return entityMethods.getDeltaMovement.call(this); },
			getName: function () { var p = playerState(); return makeComponentLiteral(ctx, (p && p.name) || ctx.getGameState(false).username || "Player"); },
			"getName()Lnet/minecraft/network/chat/Component;": function () { return entityMethods.getName.call(this); },
			getDisplayName: function () { return entityMethods.getName.call(this); },
			getScoreboardName: function () { var p = playerState(); return (p && p.name) || ctx.getGameState(false).username || "Player"; },
			"getScoreboardName()Ljava/lang/String;": function () { return entityMethods.getScoreboardName.call(this); },
			getUUID: function () {
				var name = entityMethods.getScoreboardName.call(this);
				if (ctx._cachedUuidObj && ctx._cachedUuidName === name) { return ctx._cachedUuidObj; }
				var u = ctx.nativeDefs["java/util/UUID"].staticMethods.nameUUIDFromBytes(VFS.encodeUtf8("OfflinePlayer:" + name));
				ctx._cachedUuidName = name;
				ctx._cachedUuidObj = u;
				ctx._cachedUuidStr = u.toString();
				return u;
			},
			"getUUID()Ljava/util/UUID;": function () { return entityMethods.getUUID.call(this); },
			getStringUUID: function () {
				var name = entityMethods.getScoreboardName.call(this);
				if (ctx._cachedUuidStr && ctx._cachedUuidName === name) { return ctx._cachedUuidStr; }
				return entityMethods.getUUID.call(this).toString();
			},
			"getStringUUID()Ljava/lang/String;": function () { return entityMethods.getStringUUID.call(this); },
			sendSystemMessage: function (comp) {
				var em = ctx.eagler(), txt = componentToText(comp);
				if (em && typeof em.chat === "function") { em.chat(txt); } else { console.log("[chat] " + txt); }
			},
			"sendSystemMessage(Lnet/minecraft/network/chat/Component;)V": function (c) { entityMethods.sendSystemMessage.call(this, c); },
			level: function () { return getLevelInstance(ctx); },
			isAlive: function () { var p = playerState(); return !p || toNum(p.health) > 0; }
		};
		var livingMethods = {
			getHealth: function () { var p = playerState(); return Math.fround(p ? toNum(p.health) : 20); },
			"getHealth()F": function () { return livingMethods.getHealth.call(this); },
			getMaxHealth: function () { var p = playerState(); return Math.fround(p ? toNum(p.maxHealth) : 20); },
			"getMaxHealth()F": function () { return livingMethods.getMaxHealth.call(this); },
			getAbsorptionAmount: function () { var p = playerState(); return Math.fround(p && typeof p.absorption === "number" ? p.absorption : 0); },
			"getAbsorptionAmount()F": function () { return livingMethods.getAbsorptionAmount.call(this); },
			isFallFlying: function () { var p = playerState(); return !!(p && p.fallFlying); },
			"isFallFlying()Z": function () { return livingMethods.isFallFlying.call(this); },
			getArmorValue: function () { var p = playerState(); return p && typeof p.armor === "number" ? (p.armor | 0) : 0; },
			"getArmorValue()I": function () { return livingMethods.getArmorValue.call(this); },
			getMainHandItem: function () { return ctx.emptyItemStack; },
			"getMainHandItem()Lnet/minecraft/world/item/ItemStack;": function () { return ctx.emptyItemStack; },
			getOffhandItem: function () { return ctx.emptyItemStack; },
			"getOffhandItem()Lnet/minecraft/world/item/ItemStack;": function () { return ctx.emptyItemStack; },
			aiStep: function () {}
		};
		var playerMethods = {
			getFoodData: function () {
				if (!ctx.foodDataSingleton) { ctx.foodDataSingleton = ctx.makeObj("net/minecraft/world/food/FoodData"); }
				return ctx.foodDataSingleton;
			},
			"getFoodData()Lnet/minecraft/world/food/FoodData;": function () { return playerMethods.getFoodData.call(this); },
			displayClientMessage: function (comp, actionBar) {
				var em = ctx.eagler(), txt = componentToText(comp);
				if (toBool(actionBar)) {
					if (em && typeof em.actionBar === "function") { em.actionBar(txt); } else { console.log("[actionBar] " + txt); }
				} else {
					if (em && typeof em.chat === "function") { em.chat(txt); } else { console.log("[chat] " + txt); }
				}
			},
			"displayClientMessage(Lnet/minecraft/network/chat/Component;Z)V": function (c, ab) { playerMethods.displayClientMessage.call(this, c, ab); },
			isCreative: function () { var p = playerState(); return !!(p && p.gameMode === "creative"); },
			isSpectator: function () { var p = playerState(); return !!(p && p.gameMode === "spectator"); }
		};
		ctx.regClass("net/minecraft/world/entity/Entity", {
			fields: {
				tickCount: { get: function () { return ctx.getGameState(false).tick | 0; } }
			},
			methods: entityMethods
		});
		ctx.regClass("net/minecraft/world/entity/LivingEntity", { superName: "net/minecraft/world/entity/Entity", methods: livingMethods });
		ctx.regClass("net/minecraft/world/entity/player/Player", {
			superName: "net/minecraft/world/entity/LivingEntity",
			fields: {
				experienceLevel: { get: function () { var p = ctx.getGameState(false).player; return p ? (p.xpLevel | 0) : 0; } },
				experienceProgress: { get: function () { var p = ctx.getGameState(false).player; return p && typeof p.xpProgress === "number" ? Math.fround(p.xpProgress) : 0; } }
			},
			methods: playerMethods
		});
		ctx.regClass("net/minecraft/client/player/AbstractClientPlayer", { superName: "net/minecraft/world/entity/player/Player" });
		ctx.regClass("net/minecraft/client/player/LocalPlayer", { superName: "net/minecraft/client/player/AbstractClientPlayer" });

		// ---- Level & ClientLevel ----
		var levelMethods = {
			getGameTime: function () { var w = ctx.getGameState(false).world; return makeLong(ctx, w ? w.gameTime : 0); },
			"getGameTime()J": function () { return levelMethods.getGameTime.call(this); },
			getDayTime: function () { var w = ctx.getGameState(false).world; return makeLong(ctx, w ? (w.clockTime != null ? w.clockTime : w.gameTime) : 0); },
			"getDayTime()J": function () { return levelMethods.getDayTime.call(this); },
			isRaining: function () { var w = ctx.getGameState(false).world; return !!(w && w.raining); },
			"isRaining()Z": function () { return levelMethods.isRaining.call(this); },
			isThundering: function () { var w = ctx.getGameState(false).world; return !!(w && w.thundering); },
			"isThundering()Z": function () { return levelMethods.isThundering.call(this); },
			dimension: function () {
				var w = ctx.getGameState(false).world;
				var dimStr = (w && w.dimension) || "minecraft:overworld";
				var cached = ctx._dimKeyCache[dimStr];
				if (!cached) {
					cached = ctx.makeObj("net/minecraft/resources/ResourceKey", { _id: parseIdentifier(dimStr) });
					ctx._dimKeyCache[dimStr] = cached;
				}
				return cached;
			},
			"dimension()Lnet/minecraft/resources/ResourceKey;": function () { return levelMethods.dimension.call(this); },
			isClientSide: function () { return true; }, "isClientSide()Z": function () { return true; },
			players: function () {
				var p = getPlayerInstance(ctx);
				return ctx.makeObj("java/util/ArrayList", { _arr: p ? [p] : [] });
			},
			"players()Ljava/util/List;": function () { return levelMethods.players.call(this); },
			isDay: function () { var t = toNum(levelMethods.getDayTime.call(this)) % 24000; return t < 12000; },
			isNight: function () { return !levelMethods.isDay.call(this); }
		};
		ctx.regClass("net/minecraft/world/level/Level", { fields: { isClientSide: 1 }, methods: levelMethods });
		ctx.regClass("net/minecraft/client/multiplayer/ClientLevel", { superName: "net/minecraft/world/level/Level", fields: { isClientSide: 1 }, methods: levelMethods });

		// ---- OptionInstance & Options ----
		ctx.regClass("net/minecraft/client/OptionInstance", {
			methods: {
				get: function () { return typeof this._getter === "function" ? this._getter() : this._val; },
				"get()Ljava/lang/Object;": function () { return this.get(); },
				set: function (v) { if (typeof this._setter === "function") { this._setter(v); } else { this._val = v; } },
				"set(Ljava/lang/Object;)V": function (v) { this.set(v); }
			}
		});

		function makeOption(getter, setter) {
			return ctx.makeObj("net/minecraft/client/OptionInstance", { _getter: getter, _setter: setter });
		}
		var fovOpt = makeOption(
			function () {
				var em = ctx.eagler();
				var v = (em && typeof em.getFov === "function") ? (em.getFov() | 0) : (ctx.getGameState(false).fov | 0 || 70);
				return ctx.nativeDefs["java/lang/Integer"].staticMethods.valueOf(v);
			},
			function (val) {
				var n = Math.max(30, Math.min(110, Math.round(toNum(val)) | 0));
				var em = ctx.eagler();
				if (em && typeof em.setFov === "function") { em.setFov(n); }
				if (ctx.cachedState) { ctx.cachedState.fov = n; }
			}
		);
		var gammaOpt = makeOption(
			function () {
				var em = ctx.eagler();
				var v = (em && typeof em.getGamma === "function") ? toNum(em.getGamma()) : toNum(ctx.getGameState(false).gamma);
				return ctx.nativeDefs["java/lang/Double"].staticMethods.valueOf(v);
			},
			function (val) {
				var n = toNum(val);
				var em = ctx.eagler();
				if (em && typeof em.setGamma === "function") { em.setGamma(n); }
				if (ctx.cachedState) { ctx.cachedState.gamma = n; }
			}
		);
		var rdOpt = makeOption(
			function () { return ctx.nativeDefs["java/lang/Integer"].staticMethods.valueOf(ctx.getGameState(false).renderDistance | 0 || 8); },
			function (val) { if (ctx.cachedState) { ctx.cachedState.renderDistance = toInt(val); } }
		);
		var bobOpt = makeOption(
			function () { return ctx.nativeDefs["java/lang/Boolean"].staticMethods.valueOf(ctx.bobViewLocal); },
			function (val) { ctx.bobViewLocal = toBool(val); }
		);
		var guiScaleOpt = makeOption(
			function () { return ctx.nativeDefs["java/lang/Integer"].staticMethods.valueOf(computeWindowMetrics(ctx).guiScale); },
			function () {}
		);

		function makeDefaultKey(name, type, code) {
			var km = ctx.makeObj("net/minecraft/client/KeyMapping", {
				_name: name, _keyType: type, _keyCode: code, _category: "key.categories.movement", _down: false, _clickCount: 0
			});
			InputState.registeredMappings.push(km);
			return km;
		}
		var optFields = {
			hideGui: false,
			keyUp: makeDefaultKey("key.forward", "KEYSYM", 87),
			keyDown: makeDefaultKey("key.back", "KEYSYM", 83),
			keyLeft: makeDefaultKey("key.left", "KEYSYM", 65),
			keyRight: makeDefaultKey("key.right", "KEYSYM", 68),
			keyJump: makeDefaultKey("key.jump", "KEYSYM", 32),
			keyShift: makeDefaultKey("key.sneak", "KEYSYM", 340),
			keySprint: makeDefaultKey("key.sprint", "KEYSYM", 341),
			keyAttack: makeDefaultKey("key.attack", "MOUSE", 0),
			keyUse: makeDefaultKey("key.use", "MOUSE", 2),
			keyChat: makeDefaultKey("key.chat", "KEYSYM", 84),
			keyCommand: makeDefaultKey("key.command", "KEYSYM", 47),
			keyInventory: makeDefaultKey("key.inventory", "KEYSYM", 69)
		};
		var optFieldDefs = Object.assign({}, optFields, {
			hideGui: {
				get: function () { return ctx.hideGuiLocal ? 1 : 0; },
				set: function (v) { ctx.hideGuiLocal = toBool(v); }
			}
		});
		ctx.regClass("net/minecraft/client/Options", {
			fields: optFieldDefs,
			methods: {
				fov: function () { return fovOpt; }, "fov()Lnet/minecraft/client/OptionInstance;": function () { return fovOpt; },
				gamma: function () { return gammaOpt; }, "gamma()Lnet/minecraft/client/OptionInstance;": function () { return gammaOpt; },
				renderDistance: function () { return rdOpt; }, "renderDistance()Lnet/minecraft/client/OptionInstance;": function () { return rdOpt; },
				bobView: function () { return bobOpt; }, "bobView()Lnet/minecraft/client/OptionInstance;": function () { return bobOpt; },
				guiScale: function () { return guiScaleOpt; }, "guiScale()Lnet/minecraft/client/OptionInstance;": function () { return guiScaleOpt; }
			}
		});
		// Ensure singleton options has the key fields directly attached
		var optsSingle = getOptionsInstance(ctx);
		Object.assign(optsSingle, optFields);

		// ---- Font & PoseStack & GuiGraphics & GuiGraphicsExtractor ----
		ctx.regClass("net/minecraft/client/gui/Font", {
			fields: { lineHeight: 9 },
			methods: {
				width: function (text) { return measureMcWidth(componentToText(text)); },
				"width(Ljava/lang/String;)I": function (s) { return measureMcWidth(toJsStr(s)); },
				"width(Lnet/minecraft/network/chat/FormattedText;)I": function (c) { return measureMcWidth(componentToText(c)); },
				"width(Lnet/minecraft/network/chat/Component;)I": function (c) { return measureMcWidth(componentToText(c)); },
				"width(Lnet/minecraft/util/FormattedCharSequence;)I": function (c) { return measureMcWidth(componentToText(c)); }
			}
		});

		ctx.regClass("com/mojang/blaze3d/vertex/PoseStack", {
			methods: {
				pushPose: function () {
					if (this._gg && this._gg._recording) { pushHudCmd(this._ctx, CMD_PUSH, 0, 0, 0, 0, 0, 0, ""); }
					else if (this._ctx2d) { this._ctx2d.save(); }
				},
				"pushPose()V": function () { this.pushPose(); },
				popPose: function () {
					if (this._gg && this._gg._recording) { pushHudCmd(this._ctx, CMD_POP, 0, 0, 0, 0, 0, 0, ""); }
					else if (this._ctx2d) { this._ctx2d.restore(); }
				},
				"popPose()V": function () { this.popPose(); },
				translate: function (x, y) {
					var tx = toNum(x), ty = toNum(y);
					if (this._gg && this._gg._recording) { pushHudCmd(this._ctx, CMD_TRANSLATE, tx, ty, 0, 0, 0, 0, ""); }
					else if (this._ctx2d) { this._ctx2d.translate(tx, ty); }
				},
				"translate(DDD)V": function (x, y) { this.translate(x, y); },
				"translate(FFF)V": function (x, y) { this.translate(x, y); },
				scale: function (sx, sy) {
					var fsx = toNum(sx), fsy = toNum(sy);
					if (this._gg && this._gg._recording) { pushHudCmd(this._ctx, CMD_SCALE, fsx, fsy, 0, 0, 0, 0, ""); }
					else if (this._ctx2d) { this._ctx2d.scale(fsx, fsy); }
				},
				"scale(FFF)V": function (sx, sy) { this.scale(sx, sy); },
				pushMatrix: function () { this.pushPose(); },
				popMatrix: function () { this.popPose(); }
			}
		});

		var _immCanvasState = { font: "", fill: "" };

		function ggDrawStringImpl(font, text, x, y, color, shadow) {
			var str = componentToText(text);
			var rawCol = toInt(color);
			var col = (text && typeof text === "object" && typeof text._colorInt === "number" && (rawCol === -1 || rawCol === 0xffffff))
				? (0xff000000 | text._colorInt) : rawCol;
			var sh = arguments.length >= 6 ? toBool(shadow) : true;
			var ix = Math.floor(toNum(x));
			var iy = Math.floor(toNum(y));
			if (this._recording) {
				pushHudCmd(this._ctx, CMD_TEXT, ix, iy, col, sh ? 1 : 0, 0, 0, str);
				return (ix + measureMcWidth(str)) | 0;
			}
			var c2d = this._ctx2d;
			if (!c2d) { return ix | 0; }
			_immCanvasState.font = "";
			_immCanvasState.fill = "";
			c2d.textBaseline = "top";
			c2d.textAlign = "left";
			return drawStyledMcTextCached(c2d, str, ix, iy, col, sh, _immCanvasState);
		}

		var ggMethods = {
			drawString: ggDrawStringImpl,
			"drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I": function (f, s, x, y, c) { return ggDrawStringImpl.call(this, f, s, x, y, c, true); },
			"drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I": ggDrawStringImpl,
			"drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I": function (f, s, x, y, c) { return ggDrawStringImpl.call(this, f, s, x, y, c, true); },
			"drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I": ggDrawStringImpl,
			"drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I": function (f, s, x, y, c) { return ggDrawStringImpl.call(this, f, s, x, y, c, true); },
			"drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I": ggDrawStringImpl,
			drawCenteredString: function (font, text, centerX, y, color) {
				var str = componentToText(text);
				var w = measureMcWidth(str);
				return ggDrawStringImpl.call(this, font, text, Math.floor(toNum(centerX) - w / 2), y, color, true);
			},
			"drawCenteredString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V": function (f, s, cx, y, c) { ggMethods.drawCenteredString.call(this, f, s, cx, y, c); },
			"drawCenteredString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V": function (f, s, cx, y, c) { ggMethods.drawCenteredString.call(this, f, s, cx, y, c); },
			fill: function (a, b, c, d, e, f) {
				var x1, y1, x2, y2, col;
				if (typeof a === "object" && a !== null) { x1 = toInt(b); y1 = toInt(c); x2 = toInt(d); y2 = toInt(e); col = toInt(f); }
				else if (arguments.length >= 6) { x1 = toInt(a); y1 = toInt(b); x2 = toInt(c); y2 = toInt(d); col = toInt(f); }
				else { x1 = toInt(a); y1 = toInt(b); x2 = toInt(c); y2 = toInt(d); col = toInt(e); }
				if (x1 > x2) { var tx = x1; x1 = x2; x2 = tx; }
				if (y1 > y2) { var ty = y1; y1 = y2; y2 = ty; }
				if (this._recording) {
					pushHudCmd(this._ctx, CMD_FILL, x1, y1, x2 - x1, y2 - y1, col, 0, "");
					return;
				}
				var c2d = this._ctx2d;
				if (!c2d) { return; }
				c2d.fillStyle = argbToCss(col, false);
				c2d.fillRect(x1, y1, x2 - x1, y2 - y1);
			},
			"fill(IIIII)V": function (x1, y1, x2, y2, col) { ggMethods.fill.call(this, x1, y1, x2, y2, col); },
			"fill(IIIIII)V": function (x1, y1, x2, y2, z, col) { ggMethods.fill.call(this, x1, y1, x2, y2, z, col); },
			fillGradient: function (a, b, c, d, e, f, g) {
				var x1 = toInt(a), y1 = toInt(b), x2 = toInt(c), y2 = toInt(d);
				var cFrom = arguments.length >= 7 ? toInt(f) : toInt(e);
				var cTo = arguments.length >= 7 ? toInt(g) : toInt(f);
				if (x1 > x2) { var tx = x1; x1 = x2; x2 = tx; }
				if (y1 > y2) { var ty = y1; y1 = y2; y2 = ty; }
				if (this._recording) {
					pushHudCmd(this._ctx, CMD_FILL_GRAD, x1, y1, x2 - x1, y2 - y1, cFrom, cTo, "");
					return;
				}
				var c2d = this._ctx2d;
				if (!c2d) { return; }
				var grad = c2d.createLinearGradient(x1, y1, x1, y2);
				grad.addColorStop(0, argbToCss(cFrom, false));
				grad.addColorStop(1, argbToCss(cTo, false));
				c2d.fillStyle = grad;
				c2d.fillRect(x1, y1, x2 - x1, y2 - y1);
			},
			"fillGradient(IIIIII)V": function (x1, y1, x2, y2, cf, ct) { ggMethods.fillGradient.call(this, x1, y1, x2, y2, cf, ct); },
			"fillGradient(IIIIIII)V": function (x1, y1, x2, y2, z, cf, ct) { ggMethods.fillGradient.call(this, x1, y1, x2, y2, z, cf, ct); },
			renderOutline: function (x, y, w, h, color) {
				var ix = toInt(x), iy = toInt(y), iw = toInt(w), ih = toInt(h), col = toInt(color);
				if (this._recording) {
					pushHudCmd(this._ctx, CMD_OUTLINE, ix, iy, iw, ih, col, 0, "");
					return;
				}
				var c2d = this._ctx2d;
				if (!c2d) { return; }
				c2d.fillStyle = argbToCss(col, false);
				c2d.fillRect(ix, iy, iw, 1);
				c2d.fillRect(ix, iy + ih - 1, iw, 1);
				c2d.fillRect(ix, iy + 1, 1, Math.max(0, ih - 2));
				c2d.fillRect(ix + iw - 1, iy + 1, 1, Math.max(0, ih - 2));
			},
			"renderOutline(IIIII)V": function (x, y, w, h, c) { ggMethods.renderOutline.call(this, x, y, w, h, c); },
			hLine: function (x1, x2, y, col) { ggMethods.fill.call(this, x1, y, toInt(x2) + 1, toInt(y) + 1, col); },
			vLine: function (x, y1, y2, col) { ggMethods.fill.call(this, x, y1, toInt(x) + 1, toInt(y2) + 1, col); },
			guiWidth: function () { return this._guiWidth || computeWindowMetrics(ctx).guiWidth; },
			"guiWidth()I": function () { return ggMethods.guiWidth.call(this); },
			guiHeight: function () { return this._guiHeight || computeWindowMetrics(ctx).guiHeight; },
			"guiHeight()I": function () { return ggMethods.guiHeight.call(this); },
			pose: function () { return getPoseStackInstance(ctx, this); },
			"pose()Lcom/mojang/blaze3d/vertex/PoseStack;": function () { return getPoseStackInstance(ctx, this); }
		};
		ctx.regClass("net/minecraft/client/gui/GuiGraphics", { methods: ggMethods });
		ctx.regAlias("net/minecraft/client/gui/GuiGraphicsExtractor", "net/minecraft/client/gui/GuiGraphics");

		// ---- ChatComponent, Gui, Hud, Screens, GameRenderer, Camera, Keyboard/MouseHandler ----
		ctx.regClass("net/minecraft/client/gui/components/ChatComponent", {
			methods: {
				addMessage: function (comp) {
					var em = ctx.eagler(), txt = componentToText(comp);
					if (em && typeof em.chat === "function") { em.chat(txt); } else { console.log("[chat] " + txt); }
				},
				"addMessage(Lnet/minecraft/network/chat/Component;)V": function (c) { this.addMessage(c); },
				addClientSystemMessage: function (comp) { this.addMessage(comp); },
				clearMessages: function () {}
			}
		});
		ctx.regClass("net/minecraft/client/gui/Gui", {
			methods: {
				setOverlayMessage: function (comp) {
					var em = ctx.eagler(), txt = componentToText(comp);
					if (em && typeof em.actionBar === "function") { em.actionBar(txt); } else { console.log("[actionBar] " + txt); }
				},
				"setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V": function (c) { this.setOverlayMessage(c); },
				getChat: function () {
					if (!ctx.chatComponentSingleton) { ctx.chatComponentSingleton = ctx.makeObj("net/minecraft/client/gui/components/ChatComponent"); }
					return ctx.chatComponentSingleton;
				},
				"getChat()Lnet/minecraft/client/gui/components/ChatComponent;": function () { return this.getChat(); },
				getGuiTicks: function () { return ctx.getGameState(false).tick | 0; },
				render: function () {}
			}
		});
		ctx.regAlias("net/minecraft/client/gui/Hud", "net/minecraft/client/gui/Gui");

		var screenMethods = {
			"<init>": function (title) { this._title = title || makeComponentLiteral(ctx, ""); },
			"<init>(Lnet/minecraft/network/chat/Component;)V": function (t) { this._title = t || makeComponentLiteral(ctx, ""); },
			getTitle: function () { return this._title || makeComponentLiteral(ctx, this._screenName || ""); },
			"getTitle()Lnet/minecraft/network/chat/Component;": function () { return this.getTitle(); },
			init: function () {}, render: function () {}, tick: function () {}, onClose: function () {},
			isPauseScreen: function () { return this._screenName === "PauseScreen"; }
		};
		ctx.regClass("net/minecraft/client/gui/screens/Screen", { methods: screenMethods });
		ctx.regClass("net/minecraft/client/gui/screens/TitleScreen", { superName: "net/minecraft/client/gui/screens/Screen" });
		ctx.regClass("net/minecraft/client/gui/screens/PauseScreen", { superName: "net/minecraft/client/gui/screens/Screen" });
		ctx.regClass("net/minecraft/client/gui/screens/ChatScreen", { superName: "net/minecraft/client/gui/screens/Screen" });
		ctx.regClass("net/minecraft/client/renderer/GameRenderer", { methods: { getFov: function () { return ctx.getGameState(false).fov || 70; } } });
		ctx.regClass("net/minecraft/client/Camera", {});
		ctx.regClass("net/minecraft/client/KeyboardHandler", {});
		ctx.regClass("net/minecraft/client/MouseHandler", {});

		// ---- Minecraft ----
		ctx.regClass("net/minecraft/client/Minecraft", {
			fields: {
				player: { get: function () { return getPlayerInstance(ctx); } },
				level: { get: function () { return getLevelInstance(ctx); } },
				options: { get: function () { return getOptionsInstance(ctx); } },
				screen: { get: function () { var s = ctx.getGameState(false); return makeScreenObj(ctx, s.screen, s.screenTitle); } },
				gui: { get: function () { return getGuiInstance(ctx); } },
				font: { get: function () { return getFontInstance(ctx); } },
				window: { get: function () { return getWindowInstance(ctx); } }
			},
			methods: {
				getFps: function () { return ctx.getGameState(false).fps | 0; },
				"getFps()I": function () { return ctx.getGameState(false).fps | 0; },
				getWindow: function () { return getWindowInstance(ctx); },
				"getWindow()Lcom/mojang/blaze3d/platform/Window;": function () { return getWindowInstance(ctx); },
				setScreen: function (scr) {
					var em = ctx.eagler();
					if (scr && scr._screenName === "ModsScreen" && em && typeof em.openModsScreen === "function") {
						em.openModsScreen();
					}
				},
				"setScreen(Lnet/minecraft/client/gui/screens/Screen;)V": function (s) { this.setScreen(s); },
				execute: function (runnable) {
					if (runnable) {
						setTimeout(function () {
							try { invokeJavaCallback(rt, runnable, "run", "()V", []); } catch (e) { console.error("[jvm-bridge] mc.execute error:", e); }
						}, 0);
					}
				},
				"execute(Ljava/lang/Runnable;)V": function (r) { this.execute(r); },
				isPaused: function () { return !!ctx.getGameState(false).paused; },
				"isPaused()Z": function () { return !!ctx.getGameState(false).paused; },
				getDeltaTracker: function () { return getDeltaTrackerInstance(ctx); },
				"getDeltaTracker()Lnet/minecraft/client/DeltaTracker;": function () { return getDeltaTrackerInstance(ctx); },
				getCameraEntity: function () { return getPlayerInstance(ctx); },
				isSameThread: function () { return true; },
				getGameDirectory: function () { return ctx.makeObj("java/io/File", { _path: "/" }); },
				tick: function () {}
			},
			staticMethods: {
				getInstance: function () { return getMinecraftInstance(ctx); },
				"getInstance()Lnet/minecraft/client/Minecraft;": function () { return getMinecraftInstance(ctx); }
			}
		});

		// ---- Intermediary -> Mojmap Class Aliases ----
		var classAliases = [
			["net/minecraft/class_310", "net/minecraft/client/Minecraft"],
			["net/minecraft/class_1041", "com/mojang/blaze3d/platform/Window"],
			["net/minecraft/class_9779", "net/minecraft/client/DeltaTracker"],
			["net/minecraft/class_746", "net/minecraft/client/player/LocalPlayer"],
			["net/minecraft/class_742", "net/minecraft/client/player/AbstractClientPlayer"],
			["net/minecraft/class_1657", "net/minecraft/world/entity/player/Player"],
			["net/minecraft/class_1309", "net/minecraft/world/entity/LivingEntity"],
			["net/minecraft/class_1297", "net/minecraft/world/entity/Entity"],
			["net/minecraft/class_638", "net/minecraft/client/multiplayer/ClientLevel"],
			["net/minecraft/class_1937", "net/minecraft/world/level/Level"],
			["net/minecraft/class_315", "net/minecraft/client/Options"],
			["net/minecraft/class_7172", "net/minecraft/client/OptionInstance"],
			["net/minecraft/class_304", "net/minecraft/client/KeyMapping"],
			["net/minecraft/class_332", "net/minecraft/client/gui/GuiGraphics"],
			["net/minecraft/class_327", "net/minecraft/client/gui/Font"],
			["net/minecraft/class_2561", "net/minecraft/network/chat/Component"],
			["net/minecraft/class_5250", "net/minecraft/network/chat/MutableComponent"],
			["net/minecraft/class_2583", "net/minecraft/network/chat/Style"],
			["net/minecraft/class_124", "net/minecraft/ChatFormatting"],
			["net/minecraft/class_2960", "net/minecraft/resources/Identifier"],
			["net/minecraft/class_5321", "net/minecraft/resources/ResourceKey"],
			["net/minecraft/class_2338", "net/minecraft/core/BlockPos"],
			["net/minecraft/class_2382", "net/minecraft/core/Vec3i"],
			["net/minecraft/class_243", "net/minecraft/world/phys/Vec3"],
			["net/minecraft/class_3532", "net/minecraft/util/Mth"],
			["net/minecraft/class_1799", "net/minecraft/world/item/ItemStack"],
			["net/minecraft/class_1792", "net/minecraft/world/item/Item"],
			["net/minecraft/class_1702", "net/minecraft/world/food/FoodData"],
			["net/minecraft/class_437", "net/minecraft/client/gui/screens/Screen"],
			["net/minecraft/class_442", "net/minecraft/client/gui/screens/TitleScreen"],
			["net/minecraft/class_433", "net/minecraft/client/gui/screens/PauseScreen"],
			["net/minecraft/class_408", "net/minecraft/client/gui/screens/ChatScreen"],
			["net/minecraft/class_757", "net/minecraft/client/renderer/GameRenderer"],
			["net/minecraft/class_4184", "net/minecraft/client/Camera"],
			["net/minecraft/class_329", "net/minecraft/client/gui/Gui"],
			["net/minecraft/class_338", "net/minecraft/client/gui/components/ChatComponent"],
			["net/minecraft/class_309", "net/minecraft/client/KeyboardHandler"],
			["net/minecraft/class_312", "net/minecraft/client/MouseHandler"],
			["net/minecraft/class_3675", "com/mojang/blaze3d/platform/InputConstants"],
			["net/minecraft/class_3675$class_307", "com/mojang/blaze3d/platform/InputConstants$Type"],
			["net/minecraft/class_3675$class_306", "com/mojang/blaze3d/platform/InputConstants$Key"],
			["net/minecraft/class_4587", "com/mojang/blaze3d/vertex/PoseStack"]
		];
		for (var cai = 0; cai < classAliases.length; cai++) {
			ctx.regAlias(classAliases[cai][0], classAliases[cai][1]);
		}

		// ---- Intermediary & Yarn -> Mojmap Member Aliases ----
		var memberAliases = [
			["net/minecraft/client/Minecraft", "method_1551", "getInstance"],
			["net/minecraft/client/Minecraft", "field_1724", "player"],
			["net/minecraft/client/Minecraft", "field_1687", "level"],
			["net/minecraft/client/Minecraft", "field_1690", "options"],
			["net/minecraft/client/Minecraft", "field_1772", "font"],
			["net/minecraft/client/Minecraft", "field_1755", "screen"],
			["net/minecraft/client/Minecraft", "field_1705", "gui"],
			["net/minecraft/client/Minecraft", "method_47599", "getFps"],
			["net/minecraft/client/Minecraft", "method_22683", "getWindow"],
			["net/minecraft/client/Minecraft", "method_1507", "setScreen"],
			["net/minecraft/client/Minecraft", "method_1493", "isPaused"],
			["net/minecraft/client/Minecraft", "method_60646", "getDeltaTracker"],
			["net/minecraft/client/Minecraft", "method_1574", "tick"],
			["net/minecraft/client/Minecraft", "method_18859", "execute"],
			["com/mojang/blaze3d/platform/Window", "method_4486", "getGuiScaledWidth"],
			["com/mojang/blaze3d/platform/Window", "method_4502", "getGuiScaledHeight"],
			["com/mojang/blaze3d/platform/Window", "method_4480", "getWidth"],
			["com/mojang/blaze3d/platform/Window", "method_4507", "getHeight"],
			["com/mojang/blaze3d/platform/Window", "method_4495", "getGuiScale"],
			["com/mojang/blaze3d/platform/Window", "method_4490", "getWindow"],
			["net/minecraft/client/DeltaTracker", "method_60637", "getGameTimeDeltaPartialTick"],
			["net/minecraft/client/DeltaTracker", "method_60636", "getGameTimeDeltaTicks"],
			["net/minecraft/world/entity/Entity", "method_23317", "getX"],
			["net/minecraft/world/entity/Entity", "method_23318", "getY"],
			["net/minecraft/world/entity/Entity", "method_23321", "getZ"],
			["net/minecraft/world/entity/Entity", "method_24515", "blockPosition"],
			["net/minecraft/world/entity/Entity", "method_19538", "position"],
			["net/minecraft/world/entity/Entity", "method_36454", "getYRot"],
			["net/minecraft/world/entity/Entity", "method_36455", "getXRot"],
			["net/minecraft/world/entity/Entity", "method_36456", "setYRot"],
			["net/minecraft/world/entity/Entity", "method_36457", "setXRot"],
			["net/minecraft/world/entity/Entity", "method_5624", "isSprinting"],
			["net/minecraft/world/entity/Entity", "method_5728", "setSprinting"],
			["net/minecraft/world/entity/Entity", "method_5715", "isShiftKeyDown"],
			["net/minecraft/world/entity/Entity", "method_18276", "isCrouching"],
			["net/minecraft/world/entity/Entity", "method_24828", "onGround"],
			["net/minecraft/world/entity/Entity", "method_5799", "isInWater"],
			["net/minecraft/world/entity/Entity", "method_5869", "isUnderWater"],
			["net/minecraft/world/entity/Entity", "method_18798", "getDeltaMovement"],
			["net/minecraft/world/entity/Entity", "method_5477", "getName"],
			["net/minecraft/world/entity/Entity", "method_5820", "getScoreboardName"],
			["net/minecraft/world/entity/Entity", "method_5667", "getUUID"],
			["net/minecraft/world/entity/Entity", "method_5845", "getStringUUID"],
			["net/minecraft/world/entity/Entity", "method_43496", "sendSystemMessage"],
			["net/minecraft/world/entity/LivingEntity", "method_6032", "getHealth"],
			["net/minecraft/world/entity/LivingEntity", "method_6063", "getMaxHealth"],
			["net/minecraft/world/entity/LivingEntity", "method_6067", "getAbsorptionAmount"],
			["net/minecraft/world/entity/LivingEntity", "method_6128", "isFallFlying"],
			["net/minecraft/world/entity/LivingEntity", "method_6096", "getArmorValue"],
			["net/minecraft/world/entity/LivingEntity", "method_6047", "getMainHandItem"],
			["net/minecraft/world/entity/LivingEntity", "method_6079", "getOffhandItem"],
			["net/minecraft/world/entity/LivingEntity", "method_6007", "aiStep"],
			["net/minecraft/world/entity/player/Player", "method_7344", "getFoodData"],
			["net/minecraft/world/entity/player/Player", "field_7520", "experienceLevel"],
			["net/minecraft/world/entity/player/Player", "field_7510", "experienceProgress"],
			["net/minecraft/world/entity/player/Player", "method_7353", "displayClientMessage"],
			["net/minecraft/world/food/FoodData", "method_7586", "getFoodLevel"],
			["net/minecraft/world/food/FoodData", "method_7589", "getSaturationLevel"],
			["net/minecraft/world/food/FoodData", "method_7582", "getExhaustionLevel"],
			["net/minecraft/world/item/ItemStack", "method_7960", "isEmpty"],
			["net/minecraft/world/item/ItemStack", "method_7947", "getCount"],
			["net/minecraft/world/item/ItemStack", "method_7919", "getDamageValue"],
			["net/minecraft/world/item/ItemStack", "method_7936", "getMaxDamage"],
			["net/minecraft/world/item/ItemStack", "method_7964", "getHoverName"],
			["net/minecraft/world/item/ItemStack", "method_7909", "getItem"],
			["net/minecraft/world/level/Level", "method_8510", "getGameTime"],
			["net/minecraft/world/level/Level", "method_8532", "getDayTime"],
			["net/minecraft/world/level/Level", "method_8419", "isRaining"],
			["net/minecraft/world/level/Level", "method_8546", "isThundering"],
			["net/minecraft/world/level/Level", "method_27983", "dimension"],
			["net/minecraft/world/level/Level", "field_9236", "isClientSide"],
			["net/minecraft/world/level/Level", "method_8608", "isClientSide"],
			["net/minecraft/world/level/Level", "method_18456", "players"],
			["net/minecraft/client/Options", "method_41808", "fov"],
			["net/minecraft/client/Options", "method_42473", "gamma"],
			["net/minecraft/client/Options", "method_42503", "renderDistance"],
			["net/minecraft/client/Options", "method_42437", "bobView"],
			["net/minecraft/client/Options", "field_1842", "hideGui"],
			["net/minecraft/client/Options", "field_1867", "keySprint"],
			["net/minecraft/client/Options", "field_1903", "keyJump"],
			["net/minecraft/client/Options", "field_1886", "keyAttack"],
			["net/minecraft/client/Options", "field_1904", "keyUse"],
			["net/minecraft/client/Options", "field_1832", "keyShift"],
			["net/minecraft/client/Options", "field_1894", "keyUp"],
			["net/minecraft/client/Options", "field_1881", "keyDown"],
			["net/minecraft/client/Options", "field_1913", "keyLeft"],
			["net/minecraft/client/Options", "field_1849", "keyRight"],
			["net/minecraft/client/OptionInstance", "method_41753", "get"],
			["net/minecraft/client/OptionInstance", "method_41748", "set"],
			["net/minecraft/client/KeyMapping", "method_1434", "isDown"],
			["net/minecraft/client/KeyMapping", "method_23481", "setDown"],
			["net/minecraft/client/KeyMapping", "method_1436", "consumeClick"],
			["net/minecraft/client/KeyMapping", "method_1431", "getName"],
			["net/minecraft/client/KeyMapping", "method_1423", "getCategory"],
			["net/minecraft/client/KeyMapping", "method_1429", "getDefaultKey"],
			["net/minecraft/client/KeyMapping", "method_1417", "matches"],
			["net/minecraft/client/gui/GuiGraphics", "method_25303", "drawString"],
			["net/minecraft/client/gui/GuiGraphics", "method_51433", "drawString"],
			["net/minecraft/client/gui/GuiGraphics", "method_51439", "drawString"],
			["net/minecraft/client/gui/GuiGraphics", "method_27534", "drawString"],
			["net/minecraft/client/gui/GuiGraphics", "drawText", "drawString"],
			["net/minecraft/client/gui/GuiGraphics", "drawTextWithShadow", "drawString"],
			["net/minecraft/client/gui/GuiGraphics", "method_27535", "drawCenteredString"],
			["net/minecraft/client/gui/GuiGraphics", "method_25300", "drawCenteredString"],
			["net/minecraft/client/gui/GuiGraphics", "drawCenteredTextWithShadow", "drawCenteredString"],
			["net/minecraft/client/gui/GuiGraphics", "method_25294", "fill"],
			["net/minecraft/client/gui/GuiGraphics", "method_25296", "fillGradient"],
			["net/minecraft/client/gui/GuiGraphics", "method_49601", "renderOutline"],
			["net/minecraft/client/gui/GuiGraphics", "drawBorder", "renderOutline"],
			["net/minecraft/client/gui/GuiGraphics", "method_51421", "guiWidth"],
			["net/minecraft/client/gui/GuiGraphics", "method_51443", "guiHeight"],
			["net/minecraft/client/gui/GuiGraphics", "getScaledWindowWidth", "guiWidth"],
			["net/minecraft/client/gui/GuiGraphics", "getScaledWindowHeight", "guiHeight"],
			["net/minecraft/client/gui/GuiGraphics", "method_51448", "pose"],
			["net/minecraft/client/gui/Font", "method_1727", "width"],
			["net/minecraft/client/gui/Font", "method_27525", "width"],
			["net/minecraft/client/gui/Font", "field_2000", "lineHeight"],
			["net/minecraft/network/chat/Component", "method_43470", "literal"],
			["net/minecraft/network/chat/Component", "method_43471", "translatable"],
			["net/minecraft/network/chat/Component", "method_43473", "empty"],
			["net/minecraft/network/chat/Component", "method_30163", "nullToEmpty"],
			["net/minecraft/network/chat/Component", "method_10851", "getString"],
			["net/minecraft/network/chat/MutableComponent", "method_27693", "append"],
			["net/minecraft/network/chat/MutableComponent", "method_10852", "append"],
			["net/minecraft/network/chat/MutableComponent", "method_27692", "withStyle"],
			["net/minecraft/network/chat/MutableComponent", "method_54663", "withColor"],
			["net/minecraft/network/chat/MutableComponent", "formatted", "withStyle"],
			["net/minecraft/resources/Identifier", "method_60655", "fromNamespaceAndPath"],
			["net/minecraft/resources/Identifier", "method_60654", "parse"],
			["net/minecraft/resources/Identifier", "method_12829", "tryParse"],
			["net/minecraft/resources/Identifier", "method_60656", "withDefaultNamespace"],
			["net/minecraft/resources/Identifier", "method_12836", "getNamespace"],
			["net/minecraft/resources/Identifier", "method_12832", "getPath"],
			["net/minecraft/core/BlockPos", "method_10263", "getX"],
			["net/minecraft/core/BlockPos", "method_10264", "getY"],
			["net/minecraft/core/BlockPos", "method_10260", "getZ"],
			["net/minecraft/core/BlockPos", "method_10084", "above"],
			["net/minecraft/core/BlockPos", "method_10074", "below"],
			["net/minecraft/core/BlockPos", "method_10095", "north"],
			["net/minecraft/core/BlockPos", "method_10072", "south"],
			["net/minecraft/core/BlockPos", "method_10078", "east"],
			["net/minecraft/core/BlockPos", "method_10067", "west"],
			["net/minecraft/core/BlockPos", "method_10069", "offset"],
			["net/minecraft/core/BlockPos", "method_10262", "distSqr"],
			["net/minecraft/world/phys/Vec3", "field_1352", "x"],
			["net/minecraft/world/phys/Vec3", "field_1351", "y"],
			["net/minecraft/world/phys/Vec3", "field_1350", "z"],
			["net/minecraft/world/phys/Vec3", "method_1033", "length"],
			["net/minecraft/world/phys/Vec3", "method_1022", "distanceTo"],
			["net/minecraft/world/phys/Vec3", "method_1029", "normalize"],
			["net/minecraft/world/phys/Vec3", "method_1019", "add"],
			["net/minecraft/world/phys/Vec3", "method_1020", "subtract"],
			["net/minecraft/world/phys/Vec3", "method_1021", "scale"],
			["net/minecraft/world/phys/Vec3", "method_1026", "dot"],
			["net/minecraft/world/phys/Vec3", "method_1036", "cross"],
			["net/minecraft/util/Mth", "method_15340", "clamp"],
			["net/minecraft/util/Mth", "method_15363", "clamp"],
			["net/minecraft/util/Mth", "method_15350", "clamp"],
			["net/minecraft/util/Mth", "method_16439", "lerp"],
			["net/minecraft/util/Mth", "method_16436", "lerp"],
			["net/minecraft/util/Mth", "method_15357", "floor"],
			["net/minecraft/util/Mth", "method_15375", "floor"],
			["net/minecraft/util/Mth", "method_15386", "ceil"],
			["net/minecraft/util/Mth", "method_15384", "ceil"],
			["net/minecraft/util/Mth", "method_15393", "wrapDegrees"],
			["net/minecraft/util/Mth", "method_15374", "sin"],
			["net/minecraft/util/Mth", "method_15362", "cos"],
			["net/minecraft/util/Mth", "method_15355", "sqrt"],
			["net/minecraft/util/Mth", "method_15379", "abs"],
			["net/minecraft/client/gui/Gui", "method_1753", "render"],
			["net/minecraft/client/gui/Gui", "method_1743", "getChat"],
			["net/minecraft/client/gui/Gui", "method_1732", "setOverlayMessage"],
			["net/minecraft/client/gui/components/ChatComponent", "method_1812", "addMessage"],
			["net/minecraft/client/renderer/GameRenderer", "method_3196", "getFov"],
			["net/minecraft/client/KeyboardHandler", "method_1466", "keyPress"],
			["net/minecraft/client/MouseHandler", "method_1601", "onButton"],
			["net/minecraft/client/MouseHandler", "method_1598", "onScroll"],
			["net/minecraft/client/gui/screens/Screen", "method_25426", "init"],
			["net/minecraft/client/gui/screens/Screen", "method_25394", "render"],
			["net/minecraft/client/gui/screens/Screen", "method_25393", "tick"],
			["net/minecraft/client/gui/screens/Screen", "method_25419", "onClose"],
			["net/minecraft/client/gui/screens/Screen", "method_25440", "getTitle"]
		];
		for (var mai = 0; mai < memberAliases.length; mai++) {
			ctx.regMemberAlias(memberAliases[mai][0], memberAliases[mai][1], memberAliases[mai][2]);
		}
	}

	// =========================================================================
	// 12. Mixin Callback Classes & Runtime Mixin Hook Weaver (attachMixins)
	// =========================================================================

	function installMixinClasses(ctx) {
		var ciMethods = {
			"<init>": function (id, cancellable) {
				this._id = toJsStr(id || "");
				this._cancellable = toBool(cancellable);
				this._cancelled = false;
			},
			"<init>(Ljava/lang/String;Z)V": function (id, c) { ciMethods["<init>"].call(this, id, c); },
			cancel: function () { this._cancelled = true; }, "cancel()V": function () { this._cancelled = true; },
			isCancelled: function () { return !!this._cancelled; }, "isCancelled()Z": function () { return !!this._cancelled; },
			isCancellable: function () { return !!this._cancellable; }, "isCancellable()Z": function () { return !!this._cancellable; },
			getId: function () { return this._id || ""; }, "getId()Ljava/lang/String;": function () { return this._id || ""; }
		};
		ctx.regClass("org/spongepowered/asm/mixin/injection/callback/CallbackInfo", { methods: ciMethods });

		var cirMethods = {
			"<init>": function (id, cancellable, retVal) {
				this._id = toJsStr(id || "");
				this._cancellable = toBool(cancellable);
				this._cancelled = false;
				this._returnValue = arguments.length >= 3 ? retVal : null;
				this._hasReturnValue = arguments.length >= 3;
			},
			"<init>(Ljava/lang/String;Z)V": function (id, c) { cirMethods["<init>"].call(this, id, c); },
			"<init>(Ljava/lang/String;ZLjava/lang/Object;)V": function (id, c, v) { cirMethods["<init>"].call(this, id, c, v); },
			setReturnValue: function (val) { this._returnValue = val; this._hasReturnValue = true; this._cancelled = true; },
			"setReturnValue(Ljava/lang/Object;)V": function (v) { cirMethods.setReturnValue.call(this, v); },
			getReturnValue: function () { return this._returnValue; },
			"getReturnValue()Ljava/lang/Object;": function () { return this._returnValue; },
			getReturnValueF: function () { return Math.fround(toNum(this._returnValue)); },
			"getReturnValueF()F": function () { return Math.fround(toNum(this._returnValue)); },
			getReturnValueD: function () { return toNum(this._returnValue); },
			"getReturnValueD()D": function () { return toNum(this._returnValue); },
			getReturnValueI: function () { return toInt(this._returnValue); },
			"getReturnValueI()I": function () { return toInt(this._returnValue); },
			getReturnValueJ: function () { return makeLong(ctx, this._returnValue); },
			"getReturnValueJ()J": function () { return makeLong(ctx, this._returnValue); },
			getReturnValueZ: function () { return toBool(this._returnValue); },
			"getReturnValueZ()Z": function () { return toBool(this._returnValue); },
			getReturnValueB: function () { return (toInt(this._returnValue) << 24) >> 24; },
			getReturnValueS: function () { return (toInt(this._returnValue) << 16) >> 16; },
			getReturnValueC: function () { return toCharCode(this._returnValue); }
		};
		ctx.regClass("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable", {
			superName: "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
			methods: cirMethods
		});

		ctx.regClass("com/llamalad7/mixinextras/injector/wrapoperation/Operation", {
			isInterface: true,
			methods: {
				call: function (args) { return typeof this._orig === "function" ? this._orig(unwrapArray(args) || []) : null; },
				"call([Ljava/lang/Object;)Ljava/lang/Object;": function (a) { return this.call(a); }
			}
		});
	}

	function normalizeAnnoType(t) {
		var s = String(t || "").trim();
		if (s.charAt(0) === "L" && s.charAt(s.length - 1) === ";") { s = s.slice(1, -1); }
		return s.replace(/\./g, "/");
	}

	function getAnnotationList(holder) {
		if (!holder || typeof holder !== "object") { return []; }
		var out = [];
		var sources = [holder.annotations, holder.runtimeVisibleAnnotations, holder.runtimeInvisibleAnnotations];
		for (var i = 0; i < sources.length; i++) {
			var src = sources[i];
			if (Array.isArray(src)) {
				for (var j = 0; j < src.length; j++) { if (src[j]) { out.push(src[j]); } }
			}
		}
		return out;
	}

	function findAnnotation(holder, internalTypes) {
		var wants = Array.isArray(internalTypes) ? internalTypes : [internalTypes];
		var list = getAnnotationList(holder);
		for (var i = 0; i < list.length; i++) {
			var ann = list[i];
			var t = normalizeAnnoType(ann.type || ann.desc || ann.descriptor || ann.name || "");
			for (var j = 0; j < wants.length; j++) {
				if (t === wants[j] || t.endsWith("/" + wants[j])) { return ann; }
			}
		}
		return null;
	}

	function annoVal(ann, key) {
		if (!ann) { return undefined; }
		var bag = ann.values || ann.elements || ann.params || ann;
		if (bag && Object.prototype.hasOwnProperty.call(bag, key)) { return bag[key]; }
		return undefined;
	}

	function unwrapAnnoStrings(val) {
		if (val === null || val === undefined) { return []; }
		if (typeof val === "string") { return [val]; }
		if (Array.isArray(val)) {
			var out = [];
			for (var i = 0; i < val.length; i++) {
				var sub = unwrapAnnoStrings(val[i]);
				for (var j = 0; j < sub.length; j++) { out.push(sub[j]); }
			}
			return out;
		}
		if (typeof val === "object") {
			if (typeof val.value === "string") { return [val.value]; }
			if (typeof val.className === "string") { return [val.className]; }
			if (typeof val.descriptor === "string") { return [val.descriptor]; }
			if (typeof val.name === "string") { return [val.name]; }
			if (Array.isArray(val.value)) { return unwrapAnnoStrings(val.value); }
		}
		return [];
	}

	function extractAtValue(ann) {
		var at = annoVal(ann, "at");
		if (!at) { return "HEAD"; }
		var first = Array.isArray(at) ? at[0] : at;
		if (typeof first === "string") { return first.toUpperCase(); }
		if (first && typeof first === "object") {
			var v = annoVal(first, "value");
			var list = unwrapAnnoStrings(v);
			if (list.length) { return list[0].toUpperCase(); }
		}
		return "HEAD";
	}

	function stripMethodSpec(spec) {
		var s = String(spec || "").trim();
		// Handle owner-qualified Mixin target like "Lnet/minecraft/client/Minecraft;tick()V"
		var semi = s.indexOf(";");
		if (semi >= 0 && s.charAt(0) === "L") { s = s.slice(semi + 1); }
		var paren = s.indexOf("(");
		if (paren >= 0) { s = s.slice(0, paren); }
		var dot = s.lastIndexOf(".");
		if (dot >= 0) { s = s.slice(dot + 1); }
		return s.trim();
	}

	function parseJvmParamTypes(desc) {
		var s = String(desc || "()V");
		var start = s.indexOf("("), end = s.indexOf(")");
		if (start < 0 || end < 0) { return []; }
		var body = s.slice(start + 1, end);
		var out = [];
		var i = 0;
		while (i < body.length) {
			var c = body.charAt(i);
			if (c === "L") {
				var semi = body.indexOf(";", i);
				if (semi < 0) { break; }
				out.push(body.slice(i, semi + 1));
				i = semi + 1;
			} else if (c === "[") {
				var st = i;
				while (i < body.length && body.charAt(i) === "[") { i++; }
				if (i < body.length && body.charAt(i) === "L") {
					var s2 = body.indexOf(";", i);
					i = s2 >= 0 ? s2 + 1 : body.length;
				} else { i++; }
				out.push(body.slice(st, i));
			} else {
				out.push(c);
				i++;
			}
		}
		return out;
	}

	function resolveMixinHookName(canonicalTarget, methodName, atPos) {
		var t = canonicalTarget;
		var m = stripMethodSpec(methodName);
		var tail = atPos === "TAIL" || atPos === "RETURN";

		if (t === "net/minecraft/client/Minecraft") {
			if (m === "tick" || m === "method_1574") { return tail ? "tickEnd" : "tickStart"; }
		}
		if (t === "net/minecraft/client/gui/Gui" || t === "net/minecraft/client/gui/Hud") {
			if (m === "render" || m === "method_1753" || m === "renderHotbar" || m === "renderMainHud") { return "hudRender"; }
		}
		if (t === "net/minecraft/client/renderer/GameRenderer") {
			if (m === "getFov" || m === "calculateFov" || m === "method_3196") { return "fovModifier"; }
		}
		if (t === "net/minecraft/client/Options" || t === "net/minecraft/client/OptionInstance" ||
			t === "net/minecraft/client/renderer/LightTexture" || t === "net/minecraft/class_765") {
			if (m === "gamma" || m === "getGamma" || m === "getValue" || m === "get" ||
				m === "updateLightTexture" || m === "method_42473" || m === "method_41753" || m === "method_3313") {
				return "gammaModifier";
			}
			if (m === "fov" || m === "getFov" || m === "method_41808") { return "fovModifier"; }
		}
		if (t === "net/minecraft/client/player/LocalPlayer" || t === "net/minecraft/world/entity/player/Player" ||
			t === "net/minecraft/world/entity/LivingEntity" || t === "net/minecraft/world/entity/Entity") {
			if (m === "tick" || m === "aiStep" || m === "method_5773" || m === "method_6007") { return "playerTick"; }
			if (m === "sendChat" || m === "sendCommand" || m === "method_44096") { return "chatSend"; }
		}
		if (t === "net/minecraft/client/gui/components/ChatComponent") {
			if (m === "addMessage" || m === "addClientSystemMessage" || m === "method_1812") { return "chatReceive"; }
		}
		if (t === "net/minecraft/client/multiplayer/ClientPacketListener" || t === "net/minecraft/class_634" ||
			t === "net/minecraft/client/gui/screens/ChatScreen") {
			if (m === "sendChat" || m === "sendCommand" || m === "handleChatInput" ||
				m === "method_45729" || m === "method_44096") { return "chatSend"; }
		}
		if (t === "net/minecraft/client/KeyboardHandler") {
			if (m === "keyPress" || m === "onKey" || m === "method_1466") { return "key"; }
		}
		if (t === "net/minecraft/client/MouseHandler") {
			if (m === "onButton" || m === "onPress" || m === "onMouseButton" || m === "method_1601") { return "mouse"; }
			if (m === "onScroll" || m === "onMouseScroll" || m === "method_1598") { return "scroll"; }
		}
		if (t === "net/minecraft/client/gui/screens/Screen" ||
			t === "net/minecraft/client/gui/screens/TitleScreen" ||
			t === "net/minecraft/client/gui/screens/PauseScreen") {
			if (m === "init" || m === "method_25426") { return "screenInit"; }
			if (m === "render" || m === "method_25394") { return "hudRender"; }
			if (m === "tick" || m === "method_25393") { return "tickEnd"; }
		}
		return null;
	}

	function resolveTargetLiveObject(ctx, canonicalTarget) {
		if (canonicalTarget === "net/minecraft/client/Minecraft") { return getMinecraftInstance(ctx); }
		if (canonicalTarget === "net/minecraft/client/player/LocalPlayer" ||
			canonicalTarget === "net/minecraft/world/entity/player/Player" ||
			canonicalTarget === "net/minecraft/world/entity/LivingEntity" ||
			canonicalTarget === "net/minecraft/world/entity/Entity") { return getPlayerInstance(ctx); }
		if (canonicalTarget === "net/minecraft/client/multiplayer/ClientLevel" ||
			canonicalTarget === "net/minecraft/world/level/Level") { return getLevelInstance(ctx); }
		if (canonicalTarget === "net/minecraft/client/Options") { return getOptionsInstance(ctx); }
		if (canonicalTarget === "net/minecraft/client/gui/Gui" ||
			canonicalTarget === "net/minecraft/client/gui/Hud") { return getGuiInstance(ctx); }
		if (canonicalTarget === "net/minecraft/client/gui/components/ChatComponent") { return getGuiInstance(ctx).getChat(); }
		if (canonicalTarget === "com/mojang/blaze3d/platform/Window") { return getWindowInstance(ctx); }
		if (canonicalTarget === "net/minecraft/client/KeyboardHandler") {
			if (!ctx._keyboardHandlerObj) {
				var mcK = getMinecraftInstance(ctx);
				ctx._keyboardHandlerObj = ctx.makeObj("net/minecraft/client/KeyboardHandler", { minecraft: mcK, field_1778: mcK });
			}
			return ctx._keyboardHandlerObj;
		}
		if (canonicalTarget === "net/minecraft/client/MouseHandler") {
			if (!ctx._mouseHandlerObj) {
				var mcM = getMinecraftInstance(ctx);
				ctx._mouseHandlerObj = ctx.makeObj("net/minecraft/client/MouseHandler", { minecraft: mcM, field_1779: mcM, xpos: 0, ypos: 0 });
			}
			return ctx._mouseHandlerObj;
		}
		if (canonicalTarget === "net/minecraft/client/gui/screens/Screen" ||
			canonicalTarget === "net/minecraft/client/gui/screens/TitleScreen" ||
			canonicalTarget === "net/minecraft/client/gui/screens/PauseScreen") {
			return ctx._cachedScreenObj || makeScreenObj(ctx, "Screen", "Screen");
		}
		return null;
	}

	function createMixinReceiver(ctx, mixinClassName, canonicalTarget, shadows) {
		var rt = ctx.rt;
		var inst = null;
		if (rt && typeof rt.newInstance === "function") {
			try { inst = rt.newInstance(mixinClassName); } catch (e) { inst = null; }
		}
		if (!inst && rt && typeof rt.allocObject === "function") {
			try { inst = rt.allocObject(mixinClassName); } catch (e) { inst = null; }
		}
		if (!inst) { inst = { __cls: mixinClassName, __class: mixinClassName, $class: mixinClassName }; }
		else {
			inst.__cls = mixinClassName;
			inst.__class = mixinClassName;
			inst.$class = mixinClassName;
		}
		// Mixin classes are woven into their target class on a real JVM, so (Target)(Object)this must pass checkcast.
		if (canonicalTarget) {
			var ifaces = inst.__interfaces ? inst.__interfaces.slice() : [];
			if (ifaces.indexOf(canonicalTarget) < 0) { ifaces.push(canonicalTarget); }
			if ((canonicalTarget === "net/minecraft/client/gui/screens/Screen" ||
				canonicalTarget === "net/minecraft/client/gui/screens/TitleScreen" ||
				canonicalTarget === "net/minecraft/client/gui/screens/PauseScreen") &&
				ifaces.indexOf("net/fabricmc/fabric/impl/client/screen/ScreenExtensions") < 0) {
				ifaces.push("net/fabricmc/fabric/impl/client/screen/ScreenExtensions");
			}
			inst.__interfaces = ifaces;
		}
		// Wire @Shadow fields dynamically to the live Minecraft target object
		if (shadows && shadows.length) {
			for (var i = 0; i < shadows.length; i++) {
				(function (fieldName) {
					var mappedName = (rt && typeof rt.resolveMemberAlias === "function")
						? rt.resolveMemberAlias(canonicalTarget, fieldName) : fieldName;
					if (inst.__fields && Object.prototype.hasOwnProperty.call(inst.__fields, fieldName)) {
						delete inst.__fields[fieldName];
					}
					if (inst.__fields && mappedName !== fieldName && Object.prototype.hasOwnProperty.call(inst.__fields, mappedName)) {
						delete inst.__fields[mappedName];
					}
					var shadowDescriptor = {
						enumerable: true, configurable: true,
						get: function () {
							var tgt = resolveTargetLiveObject(ctx, canonicalTarget);
							if (!tgt) { return undefined; }
							if (rt && typeof rt.getField === "function") {
								try { return rt.getField(tgt, canonicalTarget, mappedName, "Ljava/lang/Object;"); } catch (e) {}
							}
							return tgt[mappedName] !== undefined ? tgt[mappedName] : tgt[fieldName];
						},
						set: function (v) {
							var tgt = resolveTargetLiveObject(ctx, canonicalTarget);
							if (!tgt) { return; }
							if (rt && typeof rt.putField === "function") {
								try { rt.putField(tgt, canonicalTarget, mappedName, "Ljava/lang/Object;", v); return; } catch (e) {}
							}
							tgt[mappedName] = v;
						}
					};
					Object.defineProperty(inst, fieldName, shadowDescriptor);
					if (mappedName !== fieldName) {
						Object.defineProperty(inst, mappedName, shadowDescriptor);
					}
				})(shadows[i]);
			}
		}
		return inst;
	}

	// Reusable depth-2 CallbackInfo / CallbackInfoReturnable pools (re-entrancy safe, zero hot alloc)
	function acquireCi(ctx, id) {
		var d = ctx._ciDepth++;
		if (d < 2) {
			if (!ctx._ciPool) {
				ctx._ciPool = [
					ctx.makeObj("org/spongepowered/asm/mixin/injection/callback/CallbackInfo", { _id: "", _cancellable: true, _cancelled: false }),
					ctx.makeObj("org/spongepowered/asm/mixin/injection/callback/CallbackInfo", { _id: "", _cancellable: true, _cancelled: false })
				];
			}
			var ci = ctx._ciPool[d];
			ci._id = id;
			ci._cancellable = true;
			ci._cancelled = false;
			return ci;
		}
		return ctx.makeObj("org/spongepowered/asm/mixin/injection/callback/CallbackInfo", { _id: id, _cancellable: true, _cancelled: false });
	}

	function releaseCi(ctx) {
		ctx._ciDepth--;
	}

	function acquireCir(ctx, id, initVal) {
		var d = ctx._cirDepth++;
		if (d < 2) {
			if (!ctx._cirPool) {
				ctx._cirPool = [
					ctx.makeObj("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable", { _id: "", _cancellable: true, _cancelled: false, _returnValue: null, _hasReturnValue: false }),
					ctx.makeObj("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable", { _id: "", _cancellable: true, _cancelled: false, _returnValue: null, _hasReturnValue: false })
				];
			}
			var cir = ctx._cirPool[d];
			cir._id = id;
			cir._cancellable = true;
			cir._cancelled = false;
			cir._returnValue = initVal;
			cir._hasReturnValue = false;
			return cir;
		}
		return ctx.makeObj("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable", {
			_id: id, _cancellable: true, _cancelled: false, _returnValue: initVal, _hasReturnValue: false
		});
	}

	function releaseCir(ctx) {
		ctx._cirDepth--;
	}

	// Pre-compiled Mixin parameter plan opcodes (parsed ONCE at attachMixins time)
	var MP_CI = 1, MP_CIR = 2, MP_MOD_RET = 3, MP_GG = 4, MP_DT = 5, MP_COMP = 6,
		MP_STR = 7, MP_CAM = 8, MP_MC = 9, MP_SCR = 10, MP_LONG1 = 11, MP_LONG0 = 12,
		MP_KEY_0 = 13, MP_KEY_1 = 14, MP_KEY_2 = 15, MP_KEY_3 = 16,
		MP_MOUSE_0 = 17, MP_MOUSE_1 = 18, MP_MOUSE_2 = 19,
		MP_SCROLL_0 = 20, MP_SCROLL_1 = 21, MP_HUD_DELTA = 22,
		MP_FOV_NUM = 23, MP_GAMMA_NUM = 24, MP_ONE = 25, MP_ZERO = 26, MP_NULL = 27;

	function compileMixinParamPlan(desc, hookName, kind) {
		var params = parseJvmParamTypes(desc);
		var codes = new Uint8Array(params.length);
		var needsCi = false;
		var needsCir = false;
		var numSeen = 0;
		for (var i = 0; i < params.length; i++) {
			var p = params[i];
			if (p === "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;") {
				codes[i] = MP_CI;
				needsCi = true;
			} else if (p === "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;") {
				codes[i] = MP_CIR;
				needsCir = true;
			} else if (kind === "ModifyReturnValue" && i === 0) {
				codes[i] = MP_MOD_RET;
			} else if (p === "Lnet/minecraft/client/gui/GuiGraphics;" || p === "Lnet/minecraft/class_332;" || p === "Lnet/minecraft/client/gui/GuiGraphicsExtractor;") {
				codes[i] = MP_GG;
			} else if (p === "Lnet/minecraft/client/DeltaTracker;" || p === "Lnet/minecraft/class_9779;") {
				codes[i] = MP_DT;
			} else if (p === "Lnet/minecraft/network/chat/Component;" || p === "Lnet/minecraft/class_2561;") {
				codes[i] = MP_COMP;
			} else if (p === "Ljava/lang/String;") {
				codes[i] = MP_STR;
			} else if (p === "Lnet/minecraft/client/Camera;" || p === "Lnet/minecraft/class_4184;") {
				codes[i] = MP_CAM;
			} else if (p === "Lnet/minecraft/client/Minecraft;" || p === "Lnet/minecraft/class_310;") {
				codes[i] = MP_MC;
			} else if (p === "Lnet/minecraft/client/gui/screens/Screen;" || p === "Lnet/minecraft/class_437;") {
				codes[i] = MP_SCR;
			} else if (hookName === "key" && (p === "I" || p === "J")) {
				if (p === "J") { codes[i] = MP_LONG1; }
				else {
					var ks = numSeen++;
					codes[i] = ks === 0 ? MP_KEY_0 : (ks === 1 ? MP_KEY_1 : (ks === 2 ? MP_KEY_2 : (ks === 3 ? MP_KEY_3 : MP_ZERO)));
				}
			} else if (hookName === "mouse" && (p === "I" || p === "J")) {
				if (p === "J") { codes[i] = MP_LONG1; }
				else {
					var ms = numSeen++;
					codes[i] = ms === 0 ? MP_MOUSE_0 : (ms === 1 ? MP_MOUSE_1 : (ms === 2 ? MP_MOUSE_2 : MP_ZERO));
				}
			} else if (hookName === "scroll" && (p === "D" || p === "J")) {
				if (p === "J") { codes[i] = MP_LONG1; }
				else {
					var ss = numSeen++;
					codes[i] = ss === 0 ? MP_SCROLL_0 : (ss === 1 ? MP_SCROLL_1 : MP_ZERO);
				}
			} else if (hookName === "hudRender" && p === "F") {
				codes[i] = MP_HUD_DELTA;
			} else if (hookName === "fovModifier" && (p === "F" || p === "D")) {
				codes[i] = MP_FOV_NUM;
			} else if (hookName === "fovModifier" && p === "Z") {
				codes[i] = MP_ONE;
			} else if (hookName === "gammaModifier" && (p === "F" || p === "D")) {
				codes[i] = MP_GAMMA_NUM;
			} else if (p === "Z" || p === "I" || p === "F" || p === "D" || p === "B" || p === "S" || p === "C") {
				codes[i] = MP_ZERO;
			} else if (p === "J") {
				codes[i] = MP_LONG0;
			} else {
				codes[i] = MP_NULL;
			}
		}
		return { codes: codes, length: params.length, needsCi: needsCi, needsCir: needsCir, hookName: hookName };
	}

	function fillMixinArgs(ctx, plan, outArr, offset, ev, ciObj) {
		var codes = plan.codes;
		var len = plan.length;
		var hkName = plan.hookName;
		for (var i = 0; i < len; i++) {
			var code = codes[i];
			var val;
			switch (code) {
				case MP_CI:
				case MP_CIR:
					val = ciObj; break;
				case MP_MOD_RET:
					val = (ev && ev.value !== undefined) ? ev.value : (hkName === "fovModifier" ? (ctx.getGameState(false).fov || 70) : (hkName === "gammaModifier" ? (ctx.getGameState(false).gamma || 0.5) : 0));
					break;
				case MP_GG:
					val = (ev && ev.guiGraphics) || getGuiGraphicsInstance(ctx); break;
				case MP_DT:
					val = (ev && ev.deltaTracker) || getDeltaTrackerInstance(ctx, ev && ev.tickDelta); break;
				case MP_COMP:
					val = makeComponentLiteral(ctx, ev && ev.message != null ? ev.message : ""); break;
				case MP_STR:
					val = ev && ev.message != null ? String(ev.message) : ""; break;
				case MP_CAM:
					val = getCameraInstance(ctx); break;
				case MP_MC:
					val = getMinecraftInstance(ctx); break;
				case MP_SCR:
					val = (ev && ev.screenObj) || makeScreenObj(ctx, ev && ev.screen, ev && ev.title); break;
				case MP_LONG1:
					val = makeLong(ctx, 1); break;
				case MP_LONG0:
					val = makeLong(ctx, 0); break;
				case MP_KEY_0:
					val = ev ? (ev.key | 0) : 0; break;
				case MP_KEY_1:
					val = ev ? (ev.scancode | 0) : 0; break;
				case MP_KEY_2:
					val = ev ? (ev.action | 0) : 0; break;
				case MP_KEY_3:
					val = ev ? (ev.modifiers | 0) : 0; break;
				case MP_MOUSE_0:
					val = ev ? (ev.button | 0) : 0; break;
				case MP_MOUSE_1:
					val = ev ? (ev.action | 0) : 0; break;
				case MP_MOUSE_2:
					val = ev ? (ev.modifiers | 0) : 0; break;
				case MP_SCROLL_0:
					val = ev ? toNum(ev.horizontal) : 0; break;
				case MP_SCROLL_1:
					val = ev ? toNum(ev.vertical) : 0; break;
				case MP_HUD_DELTA:
					val = Math.fround((ev && ev.tickDelta) || 0); break;
				case MP_FOV_NUM:
					val = (ev && ev.value !== undefined) ? toNum(ev.value) : (ctx.getGameState(false).fov || 70); break;
				case MP_GAMMA_NUM:
					val = (ev && ev.value !== undefined) ? toNum(ev.value) : (ctx.getGameState(false).gamma || 0.5); break;
				case MP_ONE:
					val = 1; break;
				case MP_ZERO:
					val = 0; break;
				default:
					val = null; break;
			}
			outArr[offset + i] = val;
		}
	}

	function attachMixinsImpl(rt, modRecord, mixinConfigs, parsedClasses) {
		var ctx = (rt && rt.__bridgeCtx) || activeBridgeCtx || buildBridgeContext(rt);
		var attached = [];
		var unsupported = [];
		var modId = (modRecord && (modRecord.id || modRecord.file || modRecord.name)) || "unknown";

		// Normalize parsedClasses into a map by internal name
		var classMap = Object.create(null);
		if (parsedClasses) {
			if (typeof parsedClasses.forEach === "function" && !Array.isArray(parsedClasses)) {
				parsedClasses.forEach(function (v, k) {
					var cn = normalizeAnnoType(k || (v && (v.name || v.thisClass)));
					if (cn) { classMap[cn] = v; }
				});
			} else if (Array.isArray(parsedClasses)) {
				for (var pi = 0; pi < parsedClasses.length; pi++) {
					var pc = parsedClasses[pi];
					var cnA = normalizeAnnoType(pc && (pc.name || pc.thisClass));
					if (cnA) { classMap[cnA] = pc; }
				}
			} else if (typeof parsedClasses === "object") {
				var pks = Object.keys(parsedClasses);
				for (var pk = 0; pk < pks.length; pk++) {
					var cnO = normalizeAnnoType(pks[pk]);
					classMap[cnO] = parsedClasses[pks[pk]];
				}
			}
		}

		// Determine candidate mixin class names & refmap tables from mixinConfigs
		var candidateNames = [];
		var seenCandidates = Object.create(null);
		var refmapByMixin = Object.create(null);
		function addCand(name) {
			var norm = normalizeAnnoType(name);
			if (norm && !seenCandidates[norm]) { seenCandidates[norm] = true; candidateNames.push(norm); }
		}
		function recordRefmap(rm) {
			if (!rm || typeof rm !== "object") { return; }
			var buckets = [];
			if (rm.mappings && typeof rm.mappings === "object") { buckets.push(rm.mappings); }
			if (rm.data && typeof rm.data === "object") {
				var dKeys = Object.keys(rm.data);
				for (var di = 0; di < dKeys.length; di++) {
					if (rm.data[dKeys[di]] && typeof rm.data[dKeys[di]] === "object") { buckets.push(rm.data[dKeys[di]]); }
				}
			}
			for (var bi = 0; bi < buckets.length; bi++) {
				var b = buckets[bi];
				var mKeys = Object.keys(b);
				for (var mi2 = 0; mi2 < mKeys.length; mi2++) {
					var mn = normalizeAnnoType(mKeys[mi2]);
					if (!refmapByMixin[mn]) { refmapByMixin[mn] = Object.create(null); }
					Object.assign(refmapByMixin[mn], b[mKeys[mi2]]);
				}
			}
		}
		if (Array.isArray(mixinConfigs)) {
			for (var mci = 0; mci < mixinConfigs.length; mci++) {
				var entry = mixinConfigs[mci];
				if (typeof entry === "string") {
					try { entry = JSON.parse(entry); } catch (e) { entry = null; }
				}
				if (!entry || typeof entry !== "object") { continue; }
				// Support both { filename, config, refmap } (from minecraft-web-jvm-translator.js) and raw config JSON
				var cfg = (entry.config && typeof entry.config === "object") ? entry.config : entry;
				if (entry.refmap) { recordRefmap(entry.refmap); }
				var pkg = String(cfg.package || "").replace(/\./g, "/").replace(/\/+$/, "");
				var lists = [cfg.mixins, cfg.client];
				for (var li = 0; li < lists.length; li++) {
					if (!Array.isArray(lists[li])) { continue; }
					for (var ci = 0; ci < lists[li].length; ci++) {
						var rel = String(lists[li][ci] || "").replace(/\./g, "/");
						if (rel) { addCand(pkg ? (pkg + "/" + rel) : rel); }
					}
				}
			}
		}
		// Also include any parsed class carrying @Mixin even if config list was omitted
		var allParsedKeys = Object.keys(classMap);
		for (var ak = 0; ak < allParsedKeys.length; ak++) {
			if (findAnnotation(classMap[allParsedKeys[ak]], "org/spongepowered/asm/mixin/Mixin")) {
				addCand(allParsedKeys[ak]);
			}
		}

		for (var i = 0; i < candidateNames.length; i++) {
			var mixinName = candidateNames[i];
			var clsInfo = classMap[mixinName];
			if (!clsInfo) { continue; }
			var mixinAnn = findAnnotation(clsInfo, "org/spongepowered/asm/mixin/Mixin");
			if (!mixinAnn) { continue; }
			var rmMap = refmapByMixin[mixinName] || null;

			var rawTargets = unwrapAnnoStrings(annoVal(mixinAnn, "value"))
				.concat(unwrapAnnoStrings(annoVal(mixinAnn, "targets")));
			var targets = [];
			for (var ti = 0; ti < rawTargets.length; ti++) {
				var mappedTgt = (rmMap && rmMap[rawTargets[ti]]) || rawTargets[ti];
				var tn = normalizeAnnoType(mappedTgt);
				if (tn) { targets.push(ctx.aliases[tn] || tn); }
			}
			if (!targets.length) {
				unsupported.push({ mixin: mixinName, target: "", method: "", annotation: "@Mixin", reason: "No target class specified" });
				continue;
			}

			// Collect @Shadow fields
			var shadows = [];
			var fields = clsInfo.fields || [];
			for (var fi = 0; fi < fields.length; fi++) {
				if (findAnnotation(fields[fi], "org/spongepowered/asm/mixin/Shadow")) {
					shadows.push(fields[fi].name);
				}
			}

			var methods = clsInfo.methods || [];
			for (var mi = 0; mi < methods.length; mi++) {
				var meth = methods[mi];
				if (!meth) { continue; }
				var mName = meth.name || "";
				var mDesc = meth.descriptor || meth.desc || "()V";
				var isStatic = !!((meth.accessFlags || meth.access || 0) & 0x0008);

				// Check @Accessor / @Invoker
				var accAnn = findAnnotation(meth, "org/spongepowered/asm/mixin/gen/Accessor");
				var invAnn = findAnnotation(meth, "org/spongepowered/asm/mixin/gen/Invoker");
				if (accAnn || invAnn) {
					var targetCls = targets[0];
					var explicitVal = unwrapAnnoStrings(annoVal(accAnn || invAnn, "value"))[0];
					var memberName = explicitVal || mName.replace(/^(get|set|is|invoke|call)/, function () { return ""; });
					if (memberName) { memberName = memberName.charAt(0).toLowerCase() + memberName.slice(1); }
					var targetDef = ctx.nativeDefs[targetCls];
					if (targetDef) {
						(function (tCls, methName, methDesc, mem, isAcc) {
							var mappedMem = (rt && typeof rt.resolveMemberAlias === "function")
								? rt.resolveMemberAlias(tCls, mem) : mem;
							var fn = function (arg) {
								var live = (this && getClassNameOf(this) === tCls) ? this : (resolveTargetLiveObject(ctx, tCls) || this);
								if (!live) { return null; }
								if (isAcc) {
									if (methName.indexOf("set") === 0 && arguments.length >= 1) {
										if (rt && typeof rt.putField === "function") {
											try { rt.putField(live, tCls, mappedMem, "Ljava/lang/Object;", arg); return; } catch (e) {}
										}
										live[mappedMem] = arg;
										return;
									}
									if (rt && typeof rt.getField === "function") {
										try { return rt.getField(live, tCls, mappedMem, "Ljava/lang/Object;"); } catch (e) {}
									}
									return live[mappedMem] !== undefined ? live[mappedMem] : live[mem];
								}
								if (typeof live[mappedMem] === "function") { return live[mappedMem].apply(live, arguments); }
								if (typeof live[mem] === "function") { return live[mem].apply(live, arguments); }
								return null;
							};
							var wrappedFn = methDesc.slice(-2) === ")Z" ? wrapJvmBoolFn(fn) : fn;
							targetDef.methods[methName] = wrappedFn;
							targetDef.methods[methName + methDesc] = wrappedFn;
							if (rt && typeof rt.registerNativeClass === "function") {
								var mPatch = {};
								mPatch[methName] = wrappedFn;
								mPatch[methName + methDesc] = wrappedFn;
								rt.registerNativeClass(tCls, { methods: mPatch });
							}
						})(targetCls, mName, mDesc, memberName || mName, !!accAnn);
						attached.push({ mixin: mixinName, target: targetCls, method: mName, annotation: accAnn ? "@Accessor" : "@Invoker", hook: "accessor:" + (memberName || mName) });
					} else {
						unsupported.push({ mixin: mixinName, target: targetCls, method: mName, annotation: accAnn ? "@Accessor" : "@Invoker", reason: "Target class not bridged" });
					}
					continue;
				}

				// Check @Inject / @ModifyReturnValue / @Redirect / @Overwrite
				var injAnn = findAnnotation(meth, "org/spongepowered/asm/mixin/injection/Inject");
				var mrvAnn = findAnnotation(meth, ["com/llamalad7/mixinextras/injector/ModifyReturnValue", "ModifyReturnValue"]);
				var redAnn = findAnnotation(meth, "org/spongepowered/asm/mixin/injection/Redirect");
				var ovwAnn = findAnnotation(meth, "org/spongepowered/asm/mixin/Overwrite");
				var activeAnn = injAnn || mrvAnn || redAnn || ovwAnn;
				if (!activeAnn) { continue; }

				var annKind = injAnn ? "Inject" : (mrvAnn ? "ModifyReturnValue" : (redAnn ? "Redirect" : "Overwrite"));
				var atPos = extractAtValue(activeAnn);
				var targetMethodSpecs = ovwAnn ? [mName] : unwrapAnnoStrings(annoVal(activeAnn, "method"));
				if (!targetMethodSpecs.length) { targetMethodSpecs = [mName]; }

				var wiredAny = false;
				for (var tIdx = 0; tIdx < targets.length; tIdx++) {
					var canonTarget = targets[tIdx];
					for (var msIdx = 0; msIdx < targetMethodSpecs.length; msIdx++) {
						var rawSpec = targetMethodSpecs[msIdx];
						var resolvedSpec = (rmMap && rmMap[rawSpec]) || rawSpec;
						var hookName = resolveMixinHookName(canonTarget, resolvedSpec, atPos) ||
							(resolvedSpec !== rawSpec ? resolveMixinHookName(canonTarget, rawSpec, atPos) : null);
						if (!hookName) { continue; }
						wiredAny = true;

						(function (mxCls, cTarget, mxMethod, mxDesc, mxStatic, hkName, aKind, shList) {
							// Pre-compile parameter plan, receiver, compiled JS fn, and reusable arg array ONCE at load time
							var plan = compileMixinParamPlan(mxDesc, hkName, aKind);
							var isRetMod = (aKind === "ModifyReturnValue" || aKind === "Overwrite" || aKind === "Redirect");
							var receiver = null;
							var compiledFn = null;
							var nativeFn = null;
							var nativeOwner = null;
							var resolvedOnce = false;

							function resolveHandlerOnce() {
								if (resolvedOnce) { return; }
								resolvedOnce = true;
								if (!mxStatic && !receiver) {
									try { receiver = createMixinReceiver(ctx, mxCls, cTarget, shList); } catch (e0) { receiver = null; }
								}
								if (rt && typeof rt.loadClass === "function" && typeof rt._lookupMethodHierarchy === "function") {
									try {
										var mxClsObj = rt.loadClass(mxCls);
										var found = rt._lookupMethodHierarchy(mxClsObj, mxMethod, mxDesc, false);
										if (found && typeof found.fn === "function") {
											if (found.fn.__isCompiled) {
												compiledFn = found.fn;
											} else {
												nativeFn = found.fn;
												nativeOwner = found.owner;
											}
										}
									} catch (e1) { /* fallback cleanly to rt.invoke* */ }
								}
							}

							// Resolve eagerly at attach time so first tick/frame has zero lookup overhead
							resolveHandlerOnce();

							var compiledOffset = mxStatic ? 0 : 1;
							var reusableCompiledArgs = new Array(compiledOffset + plan.length);
							if (!mxStatic) { reusableCompiledArgs[0] = receiver; }
							var reusablePlainArgs = new Array(plan.length);
							var busy = false;

							registerHookOn(ctx, modId, hkName, function (ev) {
								if (!mxStatic && !receiver) {
									receiver = createMixinReceiver(ctx, mxCls, cTarget, shList);
									reusableCompiledArgs[0] = receiver;
								}
								var ciObj = null;
								var acquiredKind = 0; // 1 = CI, 2 = CIR
								if (plan.needsCir) {
									ciObj = acquireCir(ctx, mxMethod, ev && ev.value !== undefined ? ev.value : null);
									acquiredKind = 2;
								} else if (plan.needsCi) {
									ciObj = acquireCi(ctx, mxMethod);
									acquiredKind = 1;
								}
								var useReuse = !busy;
								if (useReuse) { busy = true; }
								var retVal;
								try {
									if (compiledFn) {
										var cArgs = useReuse ? reusableCompiledArgs : new Array(compiledOffset + plan.length);
										if (!mxStatic) { cArgs[0] = receiver; }
										fillMixinArgs(ctx, plan, cArgs, compiledOffset, ev, ciObj);
										retVal = compiledFn(rt, cArgs);
									} else if (nativeFn) {
										var nArgs = useReuse ? reusablePlainArgs : new Array(plan.length);
										fillMixinArgs(ctx, plan, nArgs, 0, ev, ciObj);
										retVal = nativeFn.apply(mxStatic ? nativeOwner : receiver, nArgs);
									} else if (rt) {
										var pArgs = useReuse ? reusablePlainArgs : new Array(plan.length);
										fillMixinArgs(ctx, plan, pArgs, 0, ev, ciObj);
										if (mxStatic && typeof rt.invokeStatic === "function") {
											retVal = rt.invokeStatic(mxCls, mxMethod, mxDesc, pArgs);
										} else if (!mxStatic && typeof rt.invokeVirtual === "function") {
											retVal = rt.invokeVirtual(receiver, mxMethod, mxDesc, pArgs, mxCls);
										} else if (!mxStatic && typeof rt.invokeSpecial === "function") {
											retVal = rt.invokeSpecial(receiver, mxCls, mxMethod, mxDesc, pArgs);
										} else if (typeof rt.invoke === "function") {
											retVal = rt.invoke(mxStatic ? null : receiver, mxCls, mxMethod, mxDesc, pArgs);
										}
									}
									if (ev && typeof ev === "object") {
										if (ciObj) {
											if (ciObj._cancelled) { ev.cancelled = true; }
											if (ciObj._hasReturnValue) {
												ev.returnValue = ciObj._returnValue;
												ev.value = ciObj._returnValue;
											} else if (isRetMod && retVal !== undefined) {
												ev.returnValue = retVal;
												ev.value = retVal;
											}
										} else if (isRetMod && retVal !== undefined) {
											ev.returnValue = retVal;
											ev.value = retVal;
										}
									}
									return ev && ev.returnValue !== undefined ? ev.returnValue : retVal;
								} finally {
									if (useReuse) { busy = false; }
									if (acquiredKind === 1) { releaseCi(ctx); }
									else if (acquiredKind === 2) { releaseCir(ctx); }
								}
							});
						})(mixinName, canonTarget, mName, mDesc, isStatic, hookName, annKind, shadows);

						attached.push({
							mixin: mixinName, target: canonTarget,
							method: stripMethodSpec(resolvedSpec),
							handler: mName, annotation: "@" + annKind, hook: hookName
						});
					}
				}

				if (!wiredAny) {
					unsupported.push({
						mixin: mixinName, target: targets.join(","),
						method: targetMethodSpecs.join(","),
						handler: mName, annotation: "@" + annKind,
						reason: "Target method has no browser runtime hook"
					});
				}
			}
		}

		var unsupportedTargets = [];
		for (var ui = 0; ui < unsupported.length; ui++) {
			var u = unsupported[ui];
			unsupportedTargets.push((u.target || u.mixin) + (u.method ? ("." + u.method) : ""));
		}
		return {
			attached: attached,
			unsupported: unsupported,
			mixinsAttached: attached.length,
			mixinsUnsupported: unsupported.length,
			unsupportedTargets: unsupportedTargets,
			errors: []
		};
	}

	// =========================================================================
	// 13. HUD Overlay Renderer (renderHudOverlay) & Live EaglerMods Event Pump
	// =========================================================================

	function renderHudOverlayImpl(renderCtx) {
		var ctx = (renderCtx && renderCtx.rt && renderCtx.rt.__bridgeCtx) || activeBridgeCtx;
		if (!ctx) { return; }

		var hudFns = ctx._hudRenderFns;
		var hasFabricHud = hudFns.length > 0;
		var hasHookHud = hasRegisteredHooks(ctx, "hudRender");
		var hasCustomCb = !!(renderCtx && typeof renderCtx.callback === "function");

		if (!hasFabricHud && !hasHookHud && !hasCustomCb) {
			clearHudOverlayForCtx(ctx);
			return;
		}

		ctx.dispatchDepth++;
		try {
			var frameSeq = (renderCtx && renderCtx.frameInfo && typeof renderCtx.frameInfo.frame === "number")
				? renderCtx.frameInfo.frame : -1;
			var st = ctx.refreshStateCache(renderCtx && renderCtx.state, frameSeq);

			// Respect Minecraft F1 hideGui option
			if (ctx.hideGuiLocal || (ctx.optionsSingleton && ctx.optionsSingleton.hideGui)) {
				clearHudOverlayForCtx(ctx);
				return;
			}

			var m = computeWindowMetrics(ctx);
			var tickDelta = (renderCtx && typeof renderCtx.tickDelta === "number")
				? renderCtx.tickDelta
				: Math.min(1, Math.max(0, (Date.now() - ctx.lastTickTimeMs) / 50));

			var gg = getGuiGraphicsInstance(ctx);
			gg._ctx = ctx;
			gg._guiWidth = m.guiWidth;
			gg._guiHeight = m.guiHeight;
			gg._guiScale = m.guiScale;
			var dt = getDeltaTrackerInstance(ctx, tickDelta);

			// Immediate fallback only when a caller supplies a custom raw-c2d callback
			if (hasCustomCb) {
				var ovImm = ensureOverlayCanvas(ctx);
				if (!ovImm || !ovImm.ctx2d) { return; }
				var c2dImm = ovImm.ctx2d;
				if (!ctx._overlayVisible) {
					ovImm.canvas.style.display = "block";
					ctx._overlayVisible = true;
				}
				c2dImm.clearRect(0, 0, ovImm.canvas.width, ovImm.canvas.height);
				gg._recording = false;
				gg._ctx2d = c2dImm;
				c2dImm.save();
				c2dImm.scale(m.guiScale, m.guiScale);
				c2dImm.imageSmoothingEnabled = false;
				try {
					if (hasHookHud) {
						var evImm = ctx._hudEv;
						evImm.guiGraphics = gg; evImm.deltaTracker = dt; evImm.tickDelta = tickDelta;
						evImm.guiWidth = m.guiWidth; evImm.guiHeight = m.guiHeight; evImm.guiScale = m.guiScale; evImm.state = st;
						fireHooks(ctx, "hudRender", evImm);
					} else if (hasFabricHud) {
						for (var ii = 0, inLen = hudFns.length; ii < inLen; ii++) {
							try { hudFns[ii](gg, dt); } catch (e0) { console.error("[jvm-bridge] HUD listener error:", e0); }
						}
					}
					renderCtx.callback(gg, dt, c2dImm, m);
					ctx._overlayDirty = true;
					ctx._cmdPrevCount = -1;
				} catch (e) {
					console.error("[jvm-bridge] HUD render error:", e);
				} finally {
					c2dImm.restore();
				}
				return;
			}

			// Record frame draw commands into flat reusable buffer for dirty-check
			ctx._cmdCurCount = 0;
			ctx._cmdCurHash = 0x811c9dc5 | 0;
			gg._recording = true;
			gg._ctx2d = null;
			try {
				if (hasHookHud) {
					var ev = ctx._hudEv;
					ev.guiGraphics = gg; ev.deltaTracker = dt; ev.tickDelta = tickDelta;
					ev.guiWidth = m.guiWidth; ev.guiHeight = m.guiHeight; ev.guiScale = m.guiScale; ev.state = st;
					fireHooks(ctx, "hudRender", ev);
				} else if (hasFabricHud) {
					for (var hi = 0, hn = hudFns.length; hi < hn; hi++) {
						try { hudFns[hi](gg, dt); } catch (e1) { console.error("[jvm-bridge] HUD listener error:", e1); }
					}
				}
			} catch (e) {
				console.error("[jvm-bridge] HUD render error:", e);
			} finally {
				gg._recording = false;
			}

			// 0 commands drawn: clear once and hide overlay canvas so compositor skips it completely
			if (ctx._cmdCurCount === 0) {
				if (ctx._overlayVisible || ctx._overlayDirty || ctx._cmdPrevCount > 0) {
					clearHudOverlayForCtx(ctx);
				}
				return;
			}

			var ov = ensureOverlayCanvas(ctx);
			if (!ov || !ov.ctx2d) { return; }
			var c2d = ov.ctx2d;
			m = ov.metrics;

			// Steady HUD frame fast path: identical commands + size + guiScale -> 0 clearRect, 0 GPU redraw!
			if (ctx._overlayVisible &&
				m.fbW === ctx._lastRenderW &&
				m.fbH === ctx._lastRenderH &&
				m.guiScale === ctx._lastRenderScale &&
				hudCmdsEqual(ctx)) {
				return;
			}

			if (!ctx._overlayVisible) {
				ov.canvas.style.display = "block";
				ctx._overlayVisible = true;
			}

			c2d.clearRect(0, 0, ov.canvas.width, ov.canvas.height);
			c2d.save();
			c2d.scale(m.guiScale, m.guiScale);
			c2d.imageSmoothingEnabled = false;
			try {
				replayHudCmds(ctx, c2d);
			} catch (e2) {
				console.error("[jvm-bridge] HUD replay error:", e2);
			} finally {
				c2d.restore();
			}

			// Swap current & previous command buffers with zero allocation
			var tmpN = ctx._cmdPrevNum; ctx._cmdPrevNum = ctx._cmdCurNum; ctx._cmdCurNum = tmpN;
			var tmpS = ctx._cmdPrevStr; ctx._cmdPrevStr = ctx._cmdCurStr; ctx._cmdCurStr = tmpS;
			ctx._cmdPrevCount = ctx._cmdCurCount;
			ctx._cmdPrevHash = ctx._cmdCurHash;
			ctx._lastRenderW = m.fbW;
			ctx._lastRenderH = m.fbH;
			ctx._lastRenderScale = m.guiScale;
			ctx._overlayDirty = true;
		} finally {
			ctx.dispatchDepth--;
		}
	}

	function applyLiveDynamicModifiers(ctx) {
		var em = ctx.eagler();
		if (!em) { return; }
		if (hasRegisteredHooks(ctx, "fovModifier") && typeof em.getFov === "function" && typeof em.setFov === "function") {
			var curFov = em.getFov();
			if (!ctx.fovOverrideActive) { ctx.baseFov = curFov; }
			var baseF = ctx.baseFov !== null ? ctx.baseFov : curFov;
			var fovEv = ctx._fovEv;
			fovEv.value = baseF;
			fovEv.returnValue = undefined;
			fovEv.cancelled = false;
			fireHooks(ctx, "fovModifier", fovEv);
			var targetFov = fovEv.returnValue !== undefined ? toNum(fovEv.returnValue) : toNum(fovEv.value);
			if (!isNaN(targetFov) && Math.abs(targetFov - baseF) > 0.25) {
				ctx.fovOverrideActive = true;
				var clampedFov = Math.max(30, Math.min(110, Math.round(targetFov)));
				if (clampedFov !== curFov) { em.setFov(clampedFov); }
			} else if (ctx.fovOverrideActive && ctx.baseFov !== null) {
				ctx.fovOverrideActive = false;
				if (ctx.baseFov !== curFov) { em.setFov(ctx.baseFov); }
			}
		}

		if (hasRegisteredHooks(ctx, "gammaModifier") && typeof em.getGamma === "function" && typeof em.setGamma === "function") {
			var curG = em.getGamma();
			if (!ctx.gammaOverrideActive) { ctx.baseGamma = curG; }
			var baseG = ctx.baseGamma !== null ? ctx.baseGamma : curG;
			var gEv = ctx._gammaEv;
			gEv.value = baseG;
			gEv.returnValue = undefined;
			gEv.cancelled = false;
			fireHooks(ctx, "gammaModifier", gEv);
			var targetG = gEv.returnValue !== undefined ? toNum(gEv.returnValue) : toNum(gEv.value);
			if (!isNaN(targetG) && Math.abs(targetG - baseG) > 0.01) {
				ctx.gammaOverrideActive = true;
				if (Math.abs(targetG - curG) > 1e-4) { em.setGamma(targetG); }
			} else if (ctx.gammaOverrideActive && ctx.baseGamma !== null) {
				ctx.gammaOverrideActive = false;
				if (Math.abs(ctx.baseGamma - curG) > 1e-4) { em.setGamma(ctx.baseGamma); }
			}
		}
	}

	function attachLiveEventPump(ctx) {
		if (ctx.pumpInstalled) { return; }
		var em = ctx.eagler();
		if (!em || typeof em.onTick !== "function") {
			// Retry shortly until WebMods.install() populates window.EaglerMods
			if (typeof setTimeout === "function") {
				setTimeout(function () { attachLiveEventPump(ctx); }, 250);
			}
			return;
		}
		ctx.pumpInstalled = true;

		// Wrap EaglerMods.sendChat once so Brigadier client commands & ClientSendMessageEvents intercept cleanly
		if (typeof em.sendChat === "function" && !em.__jvmSendChatWrapped) {
			var origSendChat = em.sendChat.bind(em);
			em.__jvmSendChatWrapped = true;
			em.sendChat = function (msg) {
				var ev = { message: String(msg == null ? "" : msg), cancelled: false };
				fireHooks(ctx, "chatSend", ev);
				if (ev.cancelled) { return; }
				return origSendChat(ev.message);
			};
		}

		// When WebMods.java drives JVM lifecycle hooks directly (jsEmitJvmTick, jsEmitJvmHudRender,
		// jsDispatchJvmScreenInit/WorldJoin/WorldLeave), skip registering permanent __global__
		// EaglerMods listeners to avoid double/triple dispatch and keep the zero-overhead vanilla fast path.
		if (root.__mcWebModsDrivesJvm) {
			return;
		}

		em.onTick(function (st, tickNum) {
			if (typeof tickNum === "number" && tickNum === ctx.lastTickNumber) { return; }
			ctx.lastTickNumber = tickNum;
			ctx.lastTickTimeMs = Date.now();
			ctx.sprintingLocal = null;
			ctx.yawLocal = null;
			ctx.pitchLocal = null;

			ctx.dispatchDepth++;
			try {
				// Refresh cached state ONCE at tickStart (0 WASM / 0 JSON calls on all subsequent getters)
				var curState = ctx.refreshStateCache(st, -1);
				var inWorld = !!(curState && curState.inWorld);

				var tickEv = ctx._tickEv;
				tickEv.tick = tickNum;
				tickEv.state = curState;
				tickEv.cancelled = false;

				// 1. tickStart (single dense Fabric dispatcher hook + Mixin hooks)
				if (hasRegisteredHooks(ctx, "tickStart")) {
					fireHooks(ctx, "tickStart", tickEv);
				}

				// 2. playerTick (Mixin hooks)
				if (inWorld && curState.player && hasRegisteredHooks(ctx, "playerTick")) {
					fireHooks(ctx, "playerTick", tickEv);
				}

				// 3. tickEnd (single dense Fabric dispatcher hook + Mixin hooks)
				if (hasRegisteredHooks(ctx, "tickEnd")) {
					fireHooks(ctx, "tickEnd", tickEv);
				}

				applyLiveDynamicModifiers(ctx);
			} finally {
				ctx.dispatchDepth--;
			}
		});

		if (typeof em.onFrame === "function") {
			em.onFrame(function (info) {
				if (info && typeof info.frame === "number" && info.frame === ctx.lastFrameNumber) { return; }
				if (info && typeof info.frame === "number") { ctx.lastFrameNumber = info.frame; }
				var fCtx = ctx._frameRenderCtx;
				fCtx.rt = ctx.rt;
				fCtx.frameInfo = info;
				renderHudOverlayImpl(fCtx);
			});
		}
		if (typeof em.onScreenChange === "function") {
			em.onScreenChange(function (next, prev, title) {
				ctx.cachedStateSeq = -1;
				ctx.cachedStateTime = 0;
				ctx.refreshStateCache(null, -1);
				fireHooks(ctx, "screenInit", { screen: next, prev: prev, title: title, screenObj: makeScreenObj(ctx, next, title) });
			});
		}
		if (typeof em.onWorldJoin === "function") {
			em.onWorldJoin(function (dim) {
				ctx.cachedStateSeq = -1;
				ctx.cachedStateTime = 0;
				ctx.refreshStateCache(null, -1);
				fireHooks(ctx, "worldJoin", { dimension: dim });
			});
		}
		if (typeof em.onWorldLeave === "function") {
			em.onWorldLeave(function () {
				ctx.cachedStateSeq = -1;
				ctx.cachedStateTime = 0;
				ctx.refreshStateCache(null, -1);
				fireHooks(ctx, "worldLeave", {});
			});
		}
	}

	// =========================================================================
	// 14. Public API (window.EaglerJvmBridge & module.exports)
	// =========================================================================

	var Bridge = {
		version: "1.0.0",
		fs: VFS,
		GLFW_KEYS: GLFW_KEYS,
		install: function (rt) {
			var ctx = buildBridgeContext(rt);
			if (rt && typeof rt === "object") {
				rt.__bridgeCtx = ctx;
				// Wrap unregisterMod so unloading mods cleans dense Fabric listeners & hides empty HUD overlay
				if (typeof rt.unregisterMod === "function" && !rt.__bridgeUnregisterWrapped) {
					var origUnregisterMod = rt.unregisterMod.bind(rt);
					rt.__bridgeUnregisterWrapped = true;
					rt.unregisterMod = function (modId) {
						var res = origUnregisterMod(modId);
						ctx.removeModListeners(modId);
						if (ctx._hudRenderFns.length === 0 && !hasRegisteredHooks(ctx, "hudRender")) {
							clearHudOverlayForCtx(ctx);
						}
						return res;
					};
				}
			}
			activeBridgeCtx = ctx;
			if (InputState.activeRuntimes.indexOf(ctx) < 0) {
				InputState.activeRuntimes.push(ctx);
			}
			installJavaLang(ctx);
			installJavaIoAndSystem(ctx);
			installJavaUtil(ctx);
			installGsonAndLoggers(ctx);
			installFabricAndBrigadier(ctx);
			installMinecraftBridge(ctx);
			installMixinClasses(ctx);
			InputState.attachDomListeners();
			attachLiveEventPump(ctx);
			return ctx;
		},
		attachMixins: function (rt, modRecord, mixinConfigs, parsedClasses) {
			return attachMixinsImpl(rt, modRecord, mixinConfigs, parsedClasses);
		},
		renderHudOverlayDispatchesHud: true,
		renderHudOverlay: function (ctx) {
			return renderHudOverlayImpl(ctx);
		},
		prepareTick: function (tickNum, passedState) {
			if (!activeBridgeCtx) { return; }
			activeBridgeCtx.lastTickNumber = tickNum;
			activeBridgeCtx.lastTickTimeMs = Date.now();
			activeBridgeCtx.sprintingLocal = null;
			activeBridgeCtx.yawLocal = null;
			activeBridgeCtx.pitchLocal = null;
			activeBridgeCtx.refreshStateCache(passedState || null, -1);
		},
		invalidateStateCache: function () {
			if (!activeBridgeCtx) { return; }
			activeBridgeCtx.cachedStateSeq = -1;
			activeBridgeCtx.cachedStateTime = 0;
			activeBridgeCtx.refreshStateCache(null, -1);
		},
		makeScreenObj: function (next, title) {
			return activeBridgeCtx ? makeScreenObj(activeBridgeCtx, next, title) : null;
		},
		clearHudOverlay: function () {
			if (activeBridgeCtx) { clearHudOverlayForCtx(activeBridgeCtx); }
		},
		refreshStateCache: function (passedState, frameSeq) {
			return activeBridgeCtx ? activeBridgeCtx.refreshStateCache(passedState, frameSeq) : null;
		},
		dispatchCommand: function (rawCommand) {
			return activeBridgeCtx ? activeBridgeCtx.tryExecuteClientCommand(rawCommand) : false;
		},
		isKeyDown: function (glfwCode) {
			var k = glfwCode | 0;
			return k >= 0 && k < 512 && InputState.keyDownBits[k] !== 0;
		},
		getOverlayCanvas: function () {
			return activeBridgeCtx ? activeBridgeCtx.overlayCanvas : null;
		}
	};

	root.EaglerJvmBridge = Bridge;
	root.MinecraftWebJvmBridge = Bridge;
	if (typeof module !== "undefined" && module.exports) {
		module.exports = Bridge;
	}
})(typeof globalThis !== "undefined" ? globalThis : (typeof window !== "undefined" ? window : this));
