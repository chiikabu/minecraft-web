// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.glfw;

public class GLFWErrorCallback implements GLFWErrorCallbackI, AutoCloseable {
	private static long nextAddress = 1L;
	private final GLFWErrorCallbackI delegate;
	private final long address;

	private GLFWErrorCallback(GLFWErrorCallbackI delegate) {
		this.delegate = delegate;
		this.address = nextAddress++;
	}

	public static GLFWErrorCallback create(GLFWErrorCallbackI delegate) {
		return new GLFWErrorCallback(delegate);
	}

	@Override
	public void invoke(int error, long description) {
		this.delegate.invoke(error, description);
	}

	public long address() {
		return this.address;
	}

	public void free() {
	}

	@Override
	public void close() {
	}
}
