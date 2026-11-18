// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import org.lwjgl.system.SharedLibrary;

public final class SDL {
	private static final SharedLibrary LIBRARY = new SharedLibrary() {
		@Override
		public String toString() {
			return "SDL3 (browser shim)";
		}
	};

	private SDL() {
	}

	public static SharedLibrary getLibrary() {
		return LIBRARY;
	}
}
