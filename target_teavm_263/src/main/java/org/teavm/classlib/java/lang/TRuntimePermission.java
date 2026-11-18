// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang;

public final class TRuntimePermission {
	private final String name;

	public TRuntimePermission(String name) { this.name = name; }
	public TRuntimePermission(String name, String actions) { this.name = name; }
	public String getName() { return name; }
}
