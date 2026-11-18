// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;
import org.teavm.jso.JSObject;
import org.teavm.runtime.Fiber;

public final class WasmGC {
	private WasmGC() {
	}

	static void touch() {
	}

	@JSExport
	public static void fiberCall() {
		JSObject pending = takePending();
		if (pending != null) {
			Fiber.start(() -> invokePendingJs(pending), true);
		}
	}

	@JSBody(script = ""
		+ "var p = globalThis.__minecraftFiberPending;"
		+ "globalThis.__minecraftFiberPending = null;"
		+ "return p || null;")
	private static native JSObject takePending();

	@JSBody(params = "pending", script = ""
		+ "globalThis.__minecraftFiberPendingRet = pending.fn.apply(null, pending.args);")
	private static native void invokePendingJs(JSObject pending);
}
