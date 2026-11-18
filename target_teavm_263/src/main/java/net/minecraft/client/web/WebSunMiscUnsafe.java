// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

public final class WebSunMiscUnsafe {
	private static final WebSunMiscUnsafe theUnsafe = new WebSunMiscUnsafe();

	private WebSunMiscUnsafe() {
	}

	public static WebSunMiscUnsafe getUnsafe() {
		return theUnsafe;
	}

	public Object allocateInstance(final Class<?> type) throws InstantiationException {
		return org.teavm.classlib.impl.UnsafeSupport.allocateInstance(type);
	}

	public long objectFieldOffset(final java.lang.reflect.Field f) {
		throw new UnsupportedOperationException("Unsafe unavailable in browser");
	}

	public long staticFieldOffset(final java.lang.reflect.Field f) {
		throw new UnsupportedOperationException("Unsafe unavailable in browser");
	}

	public Object getObject(final Object o, final long offset) {
		throw new UnsupportedOperationException("Unsafe unavailable in browser");
	}

	public void putObject(final Object o, final long offset, final Object x) {
		throw new UnsupportedOperationException("Unsafe unavailable in browser");
	}

	public int arrayBaseOffset(final Class<?> arrayClass) {
		throw new UnsupportedOperationException("Unsafe unavailable in browser");
	}

	public int arrayIndexScale(final Class<?> arrayClass) {
		throw new UnsupportedOperationException("Unsafe unavailable in browser");
	}

	public void invokeCleaner(final java.nio.ByteBuffer directBuffer) {
	}

	public int addressSize() {
		return 4;
	}
}
