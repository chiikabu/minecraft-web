// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.net.URI;

public final class TPaths {
	private TPaths() {
	}

	public static TPath get(String first, String... more) {
		return TPath.of(first, more);
	}

	public static TPath get(URI uri) {
		return new TSimplePath(uri.getPath());
	}
}
