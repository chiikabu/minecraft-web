// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

import java.nio.ByteBuffer;
import org.lwjgl.system.CustomBuffer;
import org.lwjgl.system.MemoryStack;

public class GLFWImage {
	private GLFWImage() {
	}

	public static Buffer malloc(int count, MemoryStack stack) {
		throw new UnsupportedOperationException("GLFWImage is not available in the browser");
	}

	public static class Buffer extends CustomBuffer {
		public CustomBuffer position(int newPosition) {
			return this;
		}

		public Buffer width(int value) {
			return this;
		}

		public Buffer height(int value) {
			return this;
		}

		public Buffer pixels(ByteBuffer value) {
			return this;
		}
	}
}
