// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.management;

public class TMemoryMXBean {
	static final TMemoryMXBean INSTANCE = new TMemoryMXBean();

	public TMemoryUsage getHeapMemoryUsage() { return new TMemoryUsage(); }
	public TMemoryUsage getNonHeapMemoryUsage() { return new TMemoryUsage(); }
}
