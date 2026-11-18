// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang;

public class TThreadGroup {
	private final String name;

	public TThreadGroup(String name) {
		this.name = name;
	}

	public TThreadGroup(TThreadGroup parent, String name) {
		this.name = name;
	}

	public final String getName() {
		return name;
	}

	public final TThreadGroup getParent() {
		return null;
	}

	public int activeCount() {
		return 1;
	}

	public final void setDaemon(boolean daemon) {
	}

	public final boolean isDaemon() {
		return false;
	}

	public final void setMaxPriority(int priority) {
	}

	public final int getMaxPriority() {
		return 10;
	}

	@Override
	public String toString() {
		return "java.lang.ThreadGroup[name=" + name + "]";
	}
}
