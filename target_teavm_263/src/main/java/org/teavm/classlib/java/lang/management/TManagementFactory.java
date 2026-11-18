// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.management;

import java.util.Collections;
import javax.management.MBeanServer;
import java.util.List;

public final class TManagementFactory {
	private TManagementFactory() {
	}

	public static TRuntimeMXBean getRuntimeMXBean() { return TRuntimeMXBean.INSTANCE; }
	public static TMemoryMXBean getMemoryMXBean() { return TMemoryMXBean.INSTANCE; }
	public static TThreadMXBean getThreadMXBean() { return TThreadMXBean.INSTANCE; }
	public static List<TGarbageCollectorMXBean> getGarbageCollectorMXBeans() { return Collections.emptyList(); }

	public static MBeanServer getPlatformMBeanServer() { return null; }
}
