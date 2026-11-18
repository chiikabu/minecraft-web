// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.slf4j.helpers;

public class FormattingTuple {

	public static final FormattingTuple NULL = new FormattingTuple(null);

	private final String message;
	private final Throwable throwable;
	private final Object[] argArray;

	public FormattingTuple(String message) {
		this(message, null, null);
	}

	public FormattingTuple(String message, Object[] argArray, Throwable throwable) {
		this.message = message;
		this.throwable = throwable;
		if (throwable == null) {
			this.argArray = argArray;
		} else {
			this.argArray = trimmedCopy(argArray);
		}
	}

	static Object[] trimmedCopy(Object[] argArray) {
		if (argArray == null || argArray.length == 0) {
			throw new IllegalStateException("non-sensical empty or null argument array");
		}
		final int trimmedLen = argArray.length - 1;
		Object[] trimmed = new Object[trimmedLen];
		if (trimmedLen > 0) {
			System.arraycopy(argArray, 0, trimmed, 0, trimmedLen);
		}
		return trimmed;
	}

	public String getMessage() {
		return message;
	}

	public Object[] getArgArray() {
		return argArray;
	}

	public Throwable getThrowable() {
		return throwable;
	}
}
