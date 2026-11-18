// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang;

import java.util.Optional;

public final class TModuleLayer {
	static final TModuleLayer BOOT = new TModuleLayer();

	private TModuleLayer() {
	}

	public static TModuleLayer boot() { return BOOT; }
	public Optional<Module> findModule(String name) { return Optional.empty(); }
	@Override public String toString() { return "empty module layer"; }
}
