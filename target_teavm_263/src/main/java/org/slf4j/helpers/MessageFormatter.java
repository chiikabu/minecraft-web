// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.slf4j.helpers;

import java.util.Arrays;

public class MessageFormatter {

	static final char DELIM_START = '{';
	static final char DELIM_STOP = '}';
	static final String DELIM_STR = "{}";
	private static final char ESCAPE_CHAR = '\\';

	private MessageFormatter() {
	}

	public static FormattingTuple format(String messagePattern, Object arg) {
		return arrayFormat(messagePattern, new Object[] { arg });
	}

	public static FormattingTuple format(String messagePattern, Object arg1, Object arg2) {
		return arrayFormat(messagePattern, new Object[] { arg1, arg2 });
	}

	public static FormattingTuple arrayFormat(String messagePattern, Object[] argArray) {
		Throwable throwableCandidate = getThrowableCandidate(argArray);
		Object[] args = argArray;
		if (throwableCandidate != null) {
			args = FormattingTuple.trimmedCopy(argArray);
		}
		return arrayFormat(messagePattern, args, throwableCandidate);
	}

	public static FormattingTuple arrayFormat(final String messagePattern, final Object[] argArray, Throwable throwable) {
		if (messagePattern == null) {
			return new FormattingTuple(null, argArray, throwable);
		}
		if (argArray == null) {
			return new FormattingTuple(messagePattern);
		}

		int i = 0;
		int j;
		StringBuilder sbuf = new StringBuilder(messagePattern.length() + 50);

		int argIndex = 0;
		for (; argIndex < argArray.length; argIndex++) {
			j = messagePattern.indexOf(DELIM_STR, i);
			if (j == -1) {
				if (i == 0) {
					return new FormattingTuple(messagePattern, argArray, throwable);
				} else {
					sbuf.append(messagePattern, i, messagePattern.length());
					return new FormattingTuple(sbuf.toString(), argArray, throwable);
				}
			} else {
				if (isEscapedDelimeter(messagePattern, j)) {
					if (!isDoubleEscaped(messagePattern, j)) {
						argIndex--;
						sbuf.append(messagePattern, i, j - 1);
						sbuf.append(DELIM_START);
						i = j + 1;
					} else {
						sbuf.append(messagePattern, i, j - 1);
						appendParameter(sbuf, argArray[argIndex]);
						i = j + 2;
					}
				} else {
					sbuf.append(messagePattern, i, j);
					appendParameter(sbuf, argArray[argIndex]);
					i = j + 2;
				}
			}
		}
		sbuf.append(messagePattern, i, messagePattern.length());
		return new FormattingTuple(sbuf.toString(), argArray, throwable);
	}

	static boolean isEscapedDelimeter(String messagePattern, int delimeterStartIndex) {
		if (delimeterStartIndex == 0) {
			return false;
		}
		char potentialEscape = messagePattern.charAt(delimeterStartIndex - 1);
		return potentialEscape == ESCAPE_CHAR;
	}

	static boolean isDoubleEscaped(String messagePattern, int delimeterStartIndex) {
		return delimeterStartIndex >= 2 && messagePattern.charAt(delimeterStartIndex - 2) == ESCAPE_CHAR;
	}

	static void appendParameter(StringBuilder sbuf, Object o) {
		if (o == null) {
			sbuf.append("null");
			return;
		}
		if (!o.getClass().isArray()) {
			sbuf.append(safeToString(o));
			return;
		}
		if (o instanceof Object[]) {
			sbuf.append(Arrays.deepToString((Object[]) o));
		} else if (o instanceof byte[]) {
			sbuf.append(Arrays.toString((byte[]) o));
		} else if (o instanceof short[]) {
			sbuf.append(Arrays.toString((short[]) o));
		} else if (o instanceof int[]) {
			sbuf.append(Arrays.toString((int[]) o));
		} else if (o instanceof long[]) {
			sbuf.append(Arrays.toString((long[]) o));
		} else if (o instanceof char[]) {
			sbuf.append(Arrays.toString((char[]) o));
		} else if (o instanceof float[]) {
			sbuf.append(Arrays.toString((float[]) o));
		} else if (o instanceof double[]) {
			sbuf.append(Arrays.toString((double[]) o));
		} else if (o instanceof boolean[]) {
			sbuf.append(Arrays.toString((boolean[]) o));
		} else {
			sbuf.append(safeToString(o));
		}
	}

	private static String safeToString(Object o) {
		try {
			return o.toString();
		} catch (Throwable t) {
			return "[FAILED toString()]";
		}
	}

	static Throwable getThrowableCandidate(Object[] argArray) {
		if (argArray == null || argArray.length == 0) {
			return null;
		}
		final Object lastEntry = argArray[argArray.length - 1];
		if (lastEntry instanceof Throwable) {
			return (Throwable) lastEntry;
		}
		return null;
	}
}
