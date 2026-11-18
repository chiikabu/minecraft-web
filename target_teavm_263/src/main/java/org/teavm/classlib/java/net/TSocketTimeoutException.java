// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

import java.io.InterruptedIOException;

public class TSocketTimeoutException extends InterruptedIOException {
	public TSocketTimeoutException() {
	}

	public TSocketTimeoutException(final String message) {
		super(message);
	}
}
