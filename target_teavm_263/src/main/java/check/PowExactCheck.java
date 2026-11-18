// minecraft-web by chiikabu (https://github.com/chiikabu)

package check;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.teavm.classlib.java.lang.TMath;

public final class PowExactCheck {
	private static final Method INT_REFERENCE = reference(int.class);
	private static final Method LONG_REFERENCE = reference(long.class);

	public static void main(String[] args) {
		for (int base = -50; base <= 50; base++) {
			for (int exponent = -2; exponent <= 15; exponent++) {
				checkInt(base, exponent);
				checkLong(base, exponent);
			}
		}

		int[][] ints = {
			{0, 0}, {0, Integer.MAX_VALUE}, {1, Integer.MAX_VALUE}, {-1, Integer.MAX_VALUE},
			{-1, Integer.MAX_VALUE - 1}, {2, 30}, {2, 31}, {-2, 31}, {-2, 32},
			{46340, 2}, {46341, 2}, {Integer.MIN_VALUE, 1}, {Integer.MIN_VALUE, 2}
		};
		for (int[] test : ints) {
			checkInt(test[0], test[1]);
		}

		long[][] longs = {
			{0, 0}, {0, Integer.MAX_VALUE}, {1, Integer.MAX_VALUE}, {-1, Integer.MAX_VALUE},
			{-1, Integer.MAX_VALUE - 1L}, {2, 62}, {2, 63}, {-2, 63}, {-2, 64},
			{3037000499L, 2}, {3037000500L, 2}, {Long.MIN_VALUE, 1}, {Long.MIN_VALUE, 2}
		};
		for (long[] test : longs) {
			checkLong(test[0], (int) test[1]);
		}

		System.out.println("OK: TMath.powExact matches Java 25");
	}

	private static void checkInt(int base, int exponent) {
		Outcome expected = invoke(INT_REFERENCE, base, exponent);
		Outcome actual;
		try {
			actual = Outcome.value(TMath.powExact(base, exponent));
		} catch (ArithmeticException e) {
			actual = Outcome.failure();
		}
		equal("int", base, exponent, expected, actual);
	}

	private static void checkLong(long base, int exponent) {
		Outcome expected = invoke(LONG_REFERENCE, base, exponent);
		Outcome actual;
		try {
			actual = Outcome.value(TMath.powExact(base, exponent));
		} catch (ArithmeticException e) {
			actual = Outcome.failure();
		}
		equal("long", base, exponent, expected, actual);
	}

	private static Method reference(Class<?> baseType) {
		try {
			return Math.class.getMethod("powExact", baseType, int.class);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Java 25 Math.powExact is unavailable", e);
		}
	}

	private static Outcome invoke(Method method, Object base, int exponent) {
		try {
			return Outcome.value(((Number) method.invoke(null, base, exponent)).longValue());
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof ArithmeticException) {
				return Outcome.failure();
			}
			throw new AssertionError(e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static void equal(String type, long base, int exponent, Outcome expected, Outcome actual) {
		if (!expected.equals(actual)) {
			throw new AssertionError(type + " " + base + "^" + exponent + ": expected " + expected + ", got " + actual);
		}
	}

	private record Outcome(boolean arithmeticError, long value) {
		static Outcome value(long value) {
			return new Outcome(false, value);
		}

		static Outcome failure() {
			return new Outcome(true, 0);
		}
	}
}
