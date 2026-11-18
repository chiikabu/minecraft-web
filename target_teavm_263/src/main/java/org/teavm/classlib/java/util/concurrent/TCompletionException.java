// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

public class TCompletionException extends RuntimeException {
	private static final long serialVersionUID = 7830266012832686185L;

	protected TCompletionException() {
		super();
	}

	protected TCompletionException(String message) {
		super(message);
	}

	public TCompletionException(String message, Throwable cause) {
		super(message, cause);
	}

	public TCompletionException(Throwable cause) {
		super(cause);
	}
}
