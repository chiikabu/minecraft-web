// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import org.teavm.classlib.java.lang.TThrowable;

public class TSignatureException extends TGeneralSecurityException {
	public TSignatureException() {
	}

	public TSignatureException(String message) {
		super(message);
	}

	public TSignatureException(String message, TThrowable cause) {
		super(message, cause);
	}

	public TSignatureException(TThrowable cause) {
		super(cause);
	}
}
