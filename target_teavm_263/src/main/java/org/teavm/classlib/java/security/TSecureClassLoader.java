// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import org.teavm.classlib.java.lang.TClassLoader;

public class TSecureClassLoader extends TClassLoader {
	protected TSecureClassLoader() {
	}

	protected TSecureClassLoader(TClassLoader parent) {
		super(parent);
	}

	protected TProtectionDomain getProtectionDomain(TCodeSource codeSource) {
		return null;
	}
}
