// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

import org.lwjgl.system.CustomBuffer;

public class GLFWVidMode {
	private final int width;
	private final int height;
	private final int redBits;
	private final int greenBits;
	private final int blueBits;
	private final int refreshRate;

	public GLFWVidMode(int width, int height, int redBits, int greenBits, int blueBits, int refreshRate) {
		this.width = width;
		this.height = height;
		this.redBits = redBits;
		this.greenBits = greenBits;
		this.blueBits = blueBits;
		this.refreshRate = refreshRate;
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	public int redBits() {
		return this.redBits;
	}

	public int greenBits() {
		return this.greenBits;
	}

	public int blueBits() {
		return this.blueBits;
	}

	public int refreshRate() {
		return this.refreshRate;
	}

	public static class Buffer extends CustomBuffer {
		private final GLFWVidMode[] modes;
		private int position;

		public Buffer(GLFWVidMode[] modes) {
			this.modes = modes;
		}

		public int limit() {
			return this.modes.length;
		}

		public CustomBuffer position(int newPosition) {
			this.position = newPosition;
			return this;
		}

		private GLFWVidMode current() {
			return this.modes[this.position];
		}

		public int width() {
			return this.current().width();
		}

		public int height() {
			return this.current().height();
		}

		public int redBits() {
			return this.current().redBits();
		}

		public int greenBits() {
			return this.current().greenBits();
		}

		public int blueBits() {
			return this.current().blueBits();
		}

		public int refreshRate() {
			return this.current().refreshRate();
		}
	}
}
