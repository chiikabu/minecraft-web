// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

public final class THexFormat {
	private static final char[] LOWER = "0123456789abcdef".toCharArray();
	private static final char[] UPPER = "0123456789ABCDEF".toCharArray();

	private final String delimiter;
	private final String prefix;
	private final String suffix;
	private final boolean uppercase;

	private THexFormat(String delimiter, String prefix, String suffix, boolean uppercase) {
		this.delimiter = delimiter;
		this.prefix = prefix;
		this.suffix = suffix;
		this.uppercase = uppercase;
	}

	public static THexFormat of() {
		return new THexFormat("", "", "", false);
	}

	public static THexFormat ofDelimiter(String delimiter) {
		return new THexFormat(Objects.requireNonNull(delimiter), "", "", false);
	}

	public THexFormat withDelimiter(String delimiter) {
		return new THexFormat(Objects.requireNonNull(delimiter), prefix, suffix, uppercase);
	}

	public THexFormat withPrefix(String prefix) {
		return new THexFormat(delimiter, Objects.requireNonNull(prefix), suffix, uppercase);
	}

	public THexFormat withSuffix(String suffix) {
		return new THexFormat(delimiter, prefix, Objects.requireNonNull(suffix), uppercase);
	}

	public THexFormat withUpperCase() {
		return new THexFormat(delimiter, prefix, suffix, true);
	}

	public THexFormat withLowerCase() {
		return new THexFormat(delimiter, prefix, suffix, false);
	}

	public boolean isUpperCase() {
		return uppercase;
	}

	public String delimiter() {
		return delimiter;
	}

	public String prefix() {
		return prefix;
	}

	public String suffix() {
		return suffix;
	}

	private char[] table() {
		return uppercase ? UPPER : LOWER;
	}

	public String formatHex(byte[] bytes) {
		return formatHex(bytes, 0, bytes.length);
	}

	public String formatHex(byte[] bytes, int fromIndex, int toIndex) {
		Objects.requireNonNull(bytes);
		Objects.checkFromToIndex(fromIndex, toIndex, bytes.length);
		char[] table = table();
		int n = toIndex - fromIndex;
		StringBuilder sb = new StringBuilder(n * (2 + prefix.length() + suffix.length() + delimiter.length()));
		for (int i = fromIndex; i < toIndex; i++) {
			if (i > fromIndex) {
				sb.append(delimiter);
			}
			sb.append(prefix);
			int v = bytes[i] & 0xff;
			sb.append(table[v >>> 4]).append(table[v & 0xf]);
			sb.append(suffix);
		}
		return sb.toString();
	}

	public <A extends Appendable> A formatHex(A out, byte[] bytes) {
		Objects.requireNonNull(out);
		Objects.requireNonNull(bytes);
		try {
			char[] table = table();
			for (int i = 0; i < bytes.length; i++) {
				if (i > 0) {
					out.append(delimiter);
				}
				out.append(prefix);
				int v = bytes[i] & 0xff;
				out.append(table[v >>> 4]).append(table[v & 0xf]);
				out.append(suffix);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out;
	}

	public byte[] parseHex(CharSequence string) {
		Objects.requireNonNull(string);
		return parseHexCore(string, 0, string.length());
	}

	public byte[] parseHex(CharSequence string, int fromIndex, int toIndex) {
		Objects.requireNonNull(string);
		Objects.checkFromToIndex(fromIndex, toIndex, string.length());
		return parseHexCore(string, fromIndex, toIndex);
	}

	public byte[] parseHex(char[] chars, int fromIndex, int toIndex) {
		Objects.requireNonNull(chars);
		Objects.checkFromToIndex(fromIndex, toIndex, chars.length);
		return parseHexCore(new String(chars, fromIndex, toIndex - fromIndex), 0, toIndex - fromIndex);
	}

	private byte[] parseHexCore(CharSequence string, int fromIndex, int toIndex) {
		int len = toIndex - fromIndex;
		if (len == 0) {
			return new byte[0];
		}
		int delimLen = delimiter.length();
		int perByte = 2 + prefix.length() + suffix.length();
		int denom = perByte + delimLen;
		if ((len + delimLen) % denom != 0) {
			throw new IllegalArgumentException("extra or missing delimiters, prefixes, suffixes, "
					+ "or non-hex characters in range [" + fromIndex + ", " + toIndex + ")");
		}
		int count = (len + delimLen) / denom;
		byte[] bytes = new byte[count];
		int off = fromIndex;
		for (int i = 0; i < count; i++) {
			off = expect(string, off, prefix, "prefix");
			int hi = fromHexDigit(string.charAt(off));
			off++;
			int lo = fromHexDigit(string.charAt(off));
			off++;
			bytes[i] = (byte) ((hi << 4) | lo);
			off = expect(string, off, suffix, "suffix");
			if (i < count - 1) {
				off = expect(string, off, delimiter, "delimiter");
			}
		}
		return bytes;
	}

	private static int expect(CharSequence s, int off, String token, String name) {
		int tlen = token.length();
		for (int i = 0; i < tlen; i++) {
			if (s.charAt(off + i) != token.charAt(i)) {
				throw new IllegalArgumentException("missing " + name + " at index " + (off + i));
			}
		}
		return off + tlen;
	}

	private String toHexDigits(long value, int digits) {
		char[] table = table();
		char[] out = new char[digits];
		for (int i = digits - 1; i >= 0; i--) {
			out[i] = table[(int) (value & 0xfL)];
			value >>>= 4;
		}
		return new String(out);
	}

	public String toHexDigits(byte value) {
		return toHexDigits(value & 0xffL, 2);
	}

	public String toHexDigits(char value) {
		return toHexDigits(value & 0xffffL, 4);
	}

	public String toHexDigits(short value) {
		return toHexDigits(value & 0xffffL, 4);
	}

	public String toHexDigits(int value) {
		return toHexDigits(value & 0xffffffffL, 8);
	}

	public String toHexDigits(long value) {
		return toHexDigits(value, 16);
	}

	public static int fromHexDigit(int ch) {
		if (ch >= '0' && ch <= '9') {
			return ch - '0';
		}
		if (ch >= 'A' && ch <= 'F') {
			return ch - 'A' + 10;
		}
		if (ch >= 'a' && ch <= 'f') {
			return ch - 'a' + 10;
		}
		throw new NumberFormatException("not a hexadecimal digit: \"" + (char) ch + "\" = " + ch);
	}

	public static boolean isHexDigit(int ch) {
		return (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F') || (ch >= 'a' && ch <= 'f');
	}

	public static int fromHexDigits(CharSequence string) {
		Objects.requireNonNull(string);
		return fromHexDigits(string, 0, string.length());
	}

	public static int fromHexDigits(CharSequence string, int fromIndex, int toIndex) {
		Objects.requireNonNull(string);
		Objects.checkFromToIndex(fromIndex, toIndex, string.length());
		int limit = toIndex - fromIndex;
		if (limit < 1 || limit > 8) {
			throw new IllegalArgumentException("string length " + limit + ", must be 1 to 8 inclusive");
		}
		return (int) fromHexDigitsToLong(string, fromIndex, toIndex);
	}

	public static long fromHexDigitsToLong(CharSequence string) {
		Objects.requireNonNull(string);
		return fromHexDigitsToLong(string, 0, string.length());
	}

	public static long fromHexDigitsToLong(CharSequence string, int fromIndex, int toIndex) {
		Objects.requireNonNull(string);
		Objects.checkFromToIndex(fromIndex, toIndex, string.length());
		int limit = toIndex - fromIndex;
		if (limit < 1 || limit > 16) {
			throw new IllegalArgumentException("string length " + limit + ", must be 1 to 16 inclusive");
		}
		long value = 0L;
		for (int i = fromIndex; i < toIndex; i++) {
			value = (value << 4) | fromHexDigit(string.charAt(i));
		}
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof THexFormat)) {
			return false;
		}
		THexFormat other = (THexFormat) o;
		return uppercase == other.uppercase && delimiter.equals(other.delimiter)
				&& prefix.equals(other.prefix) && suffix.equals(other.suffix);
	}

	@Override
	public int hashCode() {
		return Objects.hash(delimiter, prefix, suffix, uppercase);
	}

	@Override
	public String toString() {
		return "uppercase: " + uppercase + ", delimiter: \"" + delimiter + "\", prefix: \"" + prefix
				+ "\", suffix: \"" + suffix + "\"";
	}
}
