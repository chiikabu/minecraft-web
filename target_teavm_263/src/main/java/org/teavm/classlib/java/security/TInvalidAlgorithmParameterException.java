// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import org.teavm.classlib.java.lang.TThrowable;

public class TInvalidAlgorithmParameterException extends TGeneralSecurityException {
	public TInvalidAlgorithmParameterException() {
	}

	public TInvalidAlgorithmParameterException(String message) {
		super(message);
	}

	public TInvalidAlgorithmParameterException(String message, TThrowable cause) {
		super(message, cause);
	}

	public TInvalidAlgorithmParameterException(TThrowable cause) {
		super(cause);
	}
}
