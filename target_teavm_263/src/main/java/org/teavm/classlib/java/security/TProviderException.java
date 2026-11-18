// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

public class TProviderException extends RuntimeException {
	public TProviderException() {
	}

	public TProviderException(String message) {
		super(message);
	}

	public TProviderException(String message, Throwable cause) {
		super(message, cause);
	}

	public TProviderException(Throwable cause) {
		super(cause);
	}
}
