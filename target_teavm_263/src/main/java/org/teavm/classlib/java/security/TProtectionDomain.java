// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

public class TProtectionDomain {
	public TProtectionDomain(TCodeSource codeSource, TPermissionCollection permissions) {
	}

	public final TCodeSource getCodeSource() {
		return null;
	}

	public boolean implies(TPermission permission) {
		return true;
	}

	@Override
	public String toString() {
		return "ProtectionDomain (browser)";
	}
}
