// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.reflect;

public class TUndeclaredThrowableException extends RuntimeException {
	private final Throwable undeclaredThrowable;

	public TUndeclaredThrowableException(Throwable undeclaredThrowable) {
		this(undeclaredThrowable, null);
	}

	public TUndeclaredThrowableException(Throwable undeclaredThrowable, String message) {
		super(message, undeclaredThrowable);
		this.undeclaredThrowable = undeclaredThrowable;
	}

	public Throwable getUndeclaredThrowable() {
		return undeclaredThrowable;
	}
}
