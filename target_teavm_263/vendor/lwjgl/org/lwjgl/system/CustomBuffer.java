// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.system;

public abstract class CustomBuffer {
	public long address() {
		throw new UnsupportedOperationException(getClass().getName() + " is not backed by shim memory");
	}
}
