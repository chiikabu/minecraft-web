// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import org.teavm.classlib.java.lang.TThrowable;

public class TInvalidKeyException extends TGeneralSecurityException {
	public TInvalidKeyException() {
	}

	public TInvalidKeyException(String message) {
		super(message);
	}

	public TInvalidKeyException(String message, TThrowable cause) {
		super(message, cause);
	}

	public TInvalidKeyException(TThrowable cause) {
		super(cause);
	}
}
