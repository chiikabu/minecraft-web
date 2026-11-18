// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang;

public class TExceptionInInitializerError extends LinkageError {
	private final Throwable exception;

	public TExceptionInInitializerError() {
		this.exception = null;
	}

	public TExceptionInInitializerError(Throwable exception) {
		super(null, exception);
		this.exception = exception;
	}

	public TExceptionInInitializerError(String message) {
		super(message);
		this.exception = null;
	}

	public Throwable getException() {
		return exception;
	}
}
