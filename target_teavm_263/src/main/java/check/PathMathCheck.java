// minecraft-web by chiikabu (https://github.com/chiikabu)

package check;

import org.teavm.classlib.java.nio.file.TPath;
import org.teavm.classlib.java.nio.file.TSimplePath;

public final class PathMathCheck {
	public static void main(String[] args) {
		eq("/a/b/c", TPath.of("/a", "b", "c"));
		eq("/a/b", TPath.of("/a/b/"));
		eq("/x", TPath.of("/a/b").resolve("/x"));
		eq("/a/b/c/d", TPath.of("/a/b").resolve("c", "d"));
		eq("/a/b/c", TPath.of("/a/b").resolve(TPath.of("c")));
		eq("/a/z", TPath.of("/a/b").resolveSibling("z"));
		eq("z", TPath.of("b").resolveSibling("z"));

		eq("/a/c", TPath.of("/a/b/../c").normalize());
		eq("/a/b", TPath.of("/a/./b").normalize());
		eq("/", TPath.of("/a/..").normalize());
		eq("../x", TPath.of("../x").normalize());

		eq("c/d", TPath.of("/a/b").relativize(TPath.of("/a/b/c/d")));
		eq("../c", TPath.of("/a/b").relativize(TPath.of("/a/c")));
		eq("", TPath.of("/a/b").relativize(TPath.of("/a/b")));

		is(TPath.of("/a/b/c").startsWith(TPath.of("/a/b")));
		is(TPath.of("/a/b").startsWith(TPath.of("/a/b")));
		is(!TPath.of("/a/bc").startsWith(TPath.of("/a/b")));
		is(!TPath.of("/a").startsWith(TPath.of("/a/b")));

		eq("/a", TPath.of("/a/b").getParent());
		eq("b", TPath.of("/a/b").getFileName());
		is(TPath.of("/a").isAbsolute() && !TPath.of("a").isAbsolute());
		is(TPath.of("/a/b").getNameCount() == 2);
		eq("b", TPath.of("/a/b").getName(1));
		is("file:///a/b".equals(TPath.of("/a/b").toUri().toString()));

		int parts = 0;
		for (TPath part : new TSimplePath("/a/b/c")) {
			parts++;
		}
		is(parts == 3);

		System.out.println("OK: TSimplePath path math");
	}

	private static void eq(String expected, TPath actual) {
		if (!expected.equals(actual.toString())) {
			throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void is(boolean condition) {
		if (!condition) {
			throw new AssertionError("predicate failed");
		}
	}
}
