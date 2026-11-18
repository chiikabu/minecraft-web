// minecraft-web by chiikabu (https://github.com/chiikabu)

package sun.misc;

public final class Unsafe {
	private static final Unsafe theUnsafe = new Unsafe();

	public static Unsafe getUnsafe() { return theUnsafe; }
	public Object allocateInstance(Class<?> type) throws InstantiationException {
		return org.teavm.classlib.impl.UnsafeSupport.allocateInstance(type);
	}
	public long objectFieldOffset(java.lang.reflect.Field f) { throw new UnsupportedOperationException("Unsafe unavailable in browser"); }
	public long staticFieldOffset(java.lang.reflect.Field f) { throw new UnsupportedOperationException("Unsafe unavailable in browser"); }
	public Object getObject(Object o, long offset) { throw new UnsupportedOperationException("Unsafe unavailable in browser"); }
	public void putObject(Object o, long offset, Object x) { throw new UnsupportedOperationException("Unsafe unavailable in browser"); }
	public int arrayBaseOffset(Class<?> arrayClass) { throw new UnsupportedOperationException("Unsafe unavailable in browser"); }
	public int arrayIndexScale(Class<?> arrayClass) { throw new UnsupportedOperationException("Unsafe unavailable in browser"); }
	public int addressSize() { return 4; }
}
