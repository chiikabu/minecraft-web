// minecraft-web by chiikabu (https://github.com/chiikabu)

package check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.teavm.classlib.java.io.TInputStream;
import org.teavm.classlib.java.io.TReader;
import org.teavm.classlib.java.util.TEnumeration;
import org.teavm.classlib.java.util.TProperties;

public final class PropertiesLoadCheck {
	public static void main(String[] args) throws Exception {
		String[] cases = {
			"",
			"a=1\nb=2\n",
			"a:1\r\nb 2\rc=3\n",
			"  # comment\n\t! comment\r\nkey=value\n",
			"space\\ key=leading\\ value\npunct\\:\\==\\#\\!\\:\\=\\ \nctrl=\\t\\n\\r\\f\\b\n",
			"unicode=\\u03A9\\uD83D\\uDE00\nliteral=漢字😀\n",
			"latin=éÿ\n",
			"a=one\\\n  two\\\r\n\tthree\\\rfour\n",
			"=first\nempty\n=second\nkey=old\nkey=new\ncolon:\nspaces   \n",
			"trail=value\\",
			"\u000Bvertical=value\n\u2003wide=value\n"
		};
		for (String text : cases) {
			equal(nativeReader(text), teaReader(text), "Reader", text);
			equal(nativeBytes(text), teaBytes(text), "InputStream", text);
		}
		String large = "large=" + "x".repeat(9000) + "é\nnext=value\n";
		equal(nativeReader(large), teaReader(large), "Reader", "8192-character boundary");
		equal(nativeBytes(large), teaBytes(large), "InputStream", "8192-byte boundary");

		for (String malformed : new String[] { "bad=\\u12", "bad=\\u12xz", "bad=\\uu123" }) {
			invalidInput(() -> nativeReader(malformed));
			invalidInput(() -> teaReader(malformed));
			invalidInput(() -> nativeBytes(malformed));
			invalidInput(() -> teaBytes(malformed));
		}

		NativeReader nativeReader = new NativeReader("a=1\nb=2", 4);
		TeaReader teaReader = new TeaReader("a=1\nb=2", 4);
		ioFailure(() -> new Properties().load(nativeReader));
		ioFailure(() -> new TProperties().load(teaReader));
		is(!nativeReader.closed && !teaReader.closed, "load(Reader) closed its input");

		byte[] bytes = "a=1\nb=2".getBytes(StandardCharsets.ISO_8859_1);
		NativeInput nativeInput = new NativeInput(bytes, 4);
		TeaInput teaInput = new TeaInput(bytes, 4);
		ioFailure(() -> new Properties().load(nativeInput));
		ioFailure(() -> new TProperties().load(teaInput));
		is(!nativeInput.closed && !teaInput.closed, "load(InputStream) closed its input");

		System.out.println("OK: TProperties.load matches Java 25");
	}

	private static Map<String, String> nativeReader(String text) throws IOException {
		Properties properties = new Properties();
		NativeReader reader = new NativeReader(text, -1);
		properties.load(reader);
		is(!reader.closed, "native load(Reader) closed its input");
		Map<String, String> result = new TreeMap<>();
		for (String key : properties.stringPropertyNames()) {
			result.put(key, properties.getProperty(key));
		}
		return result;
	}

	private static Map<String, String> teaReader(String text) throws IOException {
		TProperties properties = new TProperties();
		TeaReader reader = new TeaReader(text, -1);
		properties.load(reader);
		is(!reader.closed, "TeaVM load(Reader) closed its input");
		return teaMap(properties);
	}

	private static Map<String, String> nativeBytes(String text) throws IOException {
		Properties properties = new Properties();
		NativeInput input = new NativeInput(text.getBytes(StandardCharsets.ISO_8859_1), -1);
		properties.load(input);
		is(!input.closed, "native load(InputStream) closed its input");
		Map<String, String> result = new TreeMap<>();
		for (String key : properties.stringPropertyNames()) {
			result.put(key, properties.getProperty(key));
		}
		return result;
	}

	private static Map<String, String> teaBytes(String text) throws IOException {
		TProperties properties = new TProperties();
		TeaInput input = new TeaInput(text.getBytes(StandardCharsets.ISO_8859_1), -1);
		properties.load(input);
		is(!input.closed, "TeaVM load(InputStream) closed its input");
		return teaMap(properties);
	}

	private static Map<String, String> teaMap(TProperties properties) {
		Map<String, String> result = new TreeMap<>();
		TEnumeration<Object> keys = properties.keys();
		while (keys.hasMoreElements()) {
			String key = (String) keys.nextElement();
			result.put(key, properties.getProperty(key));
		}
		return result;
	}

	private static void equal(Map<String, String> expected, Map<String, String> actual, String kind, String text) {
		if (!expected.equals(actual)) {
			throw new AssertionError(kind + " mismatch for " + text.replace("\n", "<LF>").replace("\r", "<CR>")
				+ ": expected " + expected + ", got " + actual);
		}
	}

	private static void invalidInput(CheckedRunnable action) throws Exception {
		try {
			action.run();
			throw new AssertionError("expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}

	private static void ioFailure(CheckedRunnable action) throws Exception {
		try {
			action.run();
			throw new AssertionError("expected IOException");
		} catch (IOException expected) {
		}
	}

	private static void is(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	@FunctionalInterface
	private interface CheckedRunnable {
		void run() throws Exception;
	}

	private static final class NativeReader extends java.io.Reader {
		private final String text;
		private final int failAt;
		private int index;
		private boolean closed;

		NativeReader(String text, int failAt) {
			this.text = text;
			this.failAt = failAt;
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException {
			if (failAt >= 0 && index >= failAt) {
				throw new IOException("injected");
			}
			if (index == text.length()) {
				return -1;
			}
			int count = Math.min(length, text.length() - index);
			if (failAt >= 0) {
				count = Math.min(count, failAt - index);
			}
			text.getChars(index, index + count, buffer, offset);
			index += count;
			return count;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private static final class TeaReader extends TReader {
		private final String text;
		private final int failAt;
		private int index;
		private boolean closed;

		TeaReader(String text, int failAt) {
			this.text = text;
			this.failAt = failAt;
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException {
			if (failAt >= 0 && index >= failAt) {
				throw new IOException("injected");
			}
			if (index == text.length()) {
				return -1;
			}
			int count = Math.min(length, text.length() - index);
			if (failAt >= 0) {
				count = Math.min(count, failAt - index);
			}
			text.getChars(index, index + count, buffer, offset);
			index += count;
			return count;
		}

		@Override
		public boolean ready() {
			return index < text.length();
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private static final class NativeInput extends java.io.InputStream {
		private final byte[] bytes;
		private final int failAt;
		private int index;
		private boolean closed;

		NativeInput(byte[] bytes, int failAt) {
			this.bytes = bytes;
			this.failAt = failAt;
		}

		@Override
		public int read() throws IOException {
			if (failAt >= 0 && index >= failAt) {
				throw new IOException("injected");
			}
			return index == bytes.length ? -1 : bytes[index++] & 0xff;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private static final class TeaInput extends TInputStream {
		private final byte[] bytes;
		private final int failAt;
		private int index;
		private boolean closed;

		TeaInput(byte[] bytes, int failAt) {
			this.bytes = bytes;
			this.failAt = failAt;
		}

		@Override
		public int read() throws IOException {
			if (failAt >= 0 && index >= failAt) {
				throw new IOException("injected");
			}
			return index == bytes.length ? -1 : bytes[index++] & 0xff;
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
