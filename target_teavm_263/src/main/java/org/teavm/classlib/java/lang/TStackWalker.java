// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang;

import java.util.function.Function;
import java.util.stream.Stream;

public final class TStackWalker {
	public enum Option {
		RETAIN_CLASS_REFERENCE,
		SHOW_HIDDEN_FRAMES,
		SHOW_REFLECT_FRAMES
	}

	public interface StackFrame {
		String getClassName();
		String getMethodName();
		Class<?> getDeclaringClass();
		String getFileName();
		int getLineNumber();
	}

	private static final TStackWalker INSTANCE = new TStackWalker();

	public static TStackWalker getInstance() { return INSTANCE; }
	public static TStackWalker getInstance(Option option) { return INSTANCE; }
	public static TStackWalker getInstance(java.util.Set<Option> options) { return INSTANCE; }
	public static TStackWalker getInstance(java.util.Set<Option> options, int estimateDepth) { return INSTANCE; }

	public <T> T walk(Function<? super Stream<StackFrame>, ? extends T> function) {
		return function.apply(Stream.empty());
	}

	public Class<?> getCallerClass() { return Object.class; }
}
