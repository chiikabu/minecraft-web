// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

public class TScanner implements AutoCloseable {
	private final List<String> tokens = new ArrayList<>();
	private int index;

	public TScanner(Readable source) {
		StringBuilder sb = new StringBuilder();
		if (source instanceof Reader) {
			Reader r = (Reader)source;
			char[] buf = new char[4096];
			int n;
			try {
				while ((n = r.read(buf)) != -1) {
					sb.append(buf, 0, n);
				}
			} catch (IOException e) {
			}
		} else {
			java.nio.CharBuffer cb = java.nio.CharBuffer.allocate(4096);
			try {
				while (source.read(cb) != -1) {
					((java.nio.Buffer)cb).flip();
					sb.append(cb);
					((java.nio.Buffer)cb).clear();
				}
			} catch (IOException e) {
			}
		}
		tokenize(sb.toString());
	}

	public TScanner(String source) {
		tokenize(source);
	}

	private void tokenize(String s) {
		int i = 0, n = s.length();
		while (i < n) {
			while (i < n && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
			int start = i;
			while (i < n && !Character.isWhitespace(s.charAt(i))) {
				i++;
			}
			if (i > start) {
				tokens.add(s.substring(start, i));
			}
		}
	}

	public TScanner useLocale(Locale locale) {
		return this;
	}

	public boolean hasNext() {
		return index < tokens.size();
	}

	public String next() {
		if (index >= tokens.size()) {
			throw new NoSuchElementException();
		}
		return tokens.get(index++);
	}

	public float nextFloat() {
		try {
			return Float.parseFloat(next());
		} catch (NumberFormatException e) {
			index--;
			throw new java.util.InputMismatchException(e.getMessage());
		}
	}

	public int nextInt() {
		try {
			return Integer.parseInt(next());
		} catch (NumberFormatException e) {
			index--;
			throw new java.util.InputMismatchException(e.getMessage());
		}
	}

	public double nextDouble() {
		try {
			return Double.parseDouble(next());
		} catch (NumberFormatException e) {
			index--;
			throw new java.util.InputMismatchException(e.getMessage());
		}
	}

	public String nextLine() {
		StringBuilder sb = new StringBuilder();
		while (hasNext()) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(next());
		}
		return sb.toString();
	}

	@Override
	public void close() {
	}
}
