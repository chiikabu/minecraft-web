// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.management;

public class TThreadMXBean {
	static final TThreadMXBean INSTANCE = new TThreadMXBean();

	public long[] getAllThreadIds() {
		return org.teavm.classlib.java.lang.TThread.getAllStackTraces().keySet().stream()
				.mapToLong(org.teavm.classlib.java.lang.TThread::getId).toArray();
	}

	public boolean isObjectMonitorUsageSupported() { return false; }
	public boolean isSynchronizerUsageSupported() { return false; }
	public TThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers) {
		if (lockedMonitors || lockedSynchronizers) throw new UnsupportedOperationException("Lock usage is unavailable in browser diagnostics");
		var snapshots = new java.util.ArrayList<TThreadInfo>();
		org.teavm.classlib.java.lang.TThread.getAllStackTraces().forEach((thread, stack) -> snapshots.add(new TThreadInfo(thread, stack)));
		return snapshots.toArray(new TThreadInfo[0]);
	}
}
