// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import java.net.URL;

public class TCodeSource {
	private final URL location;

	public TCodeSource(URL location, Object certificates) {
		this.location = location;
	}

	public final URL getLocation() {
		return location;
	}

	@Override
	public String toString() {
		return "(" + location + ")";
	}
}
