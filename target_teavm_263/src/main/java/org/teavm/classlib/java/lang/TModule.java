// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang;

import java.io.InputStream;

public final class TModule {
	public String getName() { return null; }
	public boolean isNamed() { return false; }
	public TModuleLayer getLayer() { return TModuleLayer.BOOT; }
	public InputStream getResourceAsStream(String name) { return null; }
	@Override public String toString() { return "unnamed module"; }
}
