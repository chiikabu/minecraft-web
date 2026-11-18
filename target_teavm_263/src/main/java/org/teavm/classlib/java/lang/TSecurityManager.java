// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang;

import org.teavm.classlib.java.security.TPermission;

public class TSecurityManager {
	public TSecurityManager() {
	}

	protected TClass<?>[] getClassContext() {
		return new TClass<?>[0];
	}

	public void checkPermission(TPermission perm) {
	}

	public void checkPermission(TPermission perm, Object context) {
	}

	public void checkPackageAccess(String pkg) {
	}

	public void checkPackageDefinition(String pkg) {
	}
}
