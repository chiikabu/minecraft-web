// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.time.format;

import org.teavm.classlib.java.time.TDateTimeException;

public class TDateTimeParseException extends TDateTimeException {
	private final String parsedData;
	private final int errorIndex;

	public TDateTimeParseException(final String message, final CharSequence parsedData, final int errorIndex) {
		this(message, parsedData, errorIndex, null);
	}

	public TDateTimeParseException(final String message, final CharSequence parsedData, final int errorIndex,
			final Throwable cause) {
		super(message, cause);
		this.parsedData = parsedData.toString();
		this.errorIndex = errorIndex;
	}

	public String getParsedString() {
		return this.parsedData;
	}

	public int getErrorIndex() {
		return this.errorIndex;
	}
}
