// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.system;

public interface NativeResource extends AutoCloseable {
	void free();

	@Override
	default void close() {
		free();
	}
}
