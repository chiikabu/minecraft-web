// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import org.teavm.classlib.PlatformDetector;

public final class WebGenMain {

	private WebGenMain() {
	}

	public static void main(final String[] args) {
		if (PlatformDetector.isWebAssemblyGC()) {
			WasmGC.touch();
		}
		if (!WebWorkerTransport.inWorker() || !WebGenWorker.isGenRole()) {
			System.out.println("[26.3-gen] not a gen worker; this module has nothing to do here");
			return;
		}
		try {
			WebGenWorker.run();
		} catch (Throwable e) {
			WebGenWorker.fail(e);
		}
	}
}
