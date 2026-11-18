// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent.locks;

import org.teavm.classlib.impl.Trampoline;

public final class TLockSupport {
	private TLockSupport() {
	}

	public static void park() { Trampoline.drainPending(); }
	public static void park(Object blocker) { Trampoline.drainPending(); }
	public static void parkNanos(long nanos) {
		Trampoline.drainUntil(System.nanoTime() + Math.max(nanos, 1L));
	}
	public static void parkNanos(Object blocker, long nanos) { parkNanos(nanos); }
	public static void parkUntil(long deadline) { Trampoline.drainPending(); }
	public static void parkUntil(Object blocker, long deadline) { Trampoline.drainPending(); }
	public static void unpark(Thread thread) { }
}
