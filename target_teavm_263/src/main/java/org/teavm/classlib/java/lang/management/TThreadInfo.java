// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.management;

import org.teavm.classlib.java.lang.TThread;

public class TThreadInfo {
    private final String name;
    private final long id;
    private final int priority;
    private final boolean daemon;
    private final TThread.State state;
    private final StackTraceElement[] stack;

    TThreadInfo(TThread thread, StackTraceElement[] stack) {
        name = thread.getName();
        id = thread.getId();
        priority = thread.getPriority();
        daemon = thread.isDaemon();
        state = thread.getState();
        this.stack = stack.clone();
    }
    public String getThreadName() { return name; }
    public long getThreadId() { return id; }
    public int getPriority() { return priority; }
    public boolean isDaemon() { return daemon; }
    public TThread.State getThreadState() { return state; }
    public StackTraceElement[] getStackTrace() { return stack.clone(); }
    public boolean isInNative() { return false; }
    public boolean isSuspended() { return false; }
    public TLockInfo getLockInfo() { return null; }
    public String getLockName() { return null; }
    public String getLockOwnerName() { return null; }
    public long getLockOwnerId() { return -1; }
    public TMonitorInfo[] getLockedMonitors() { return new TMonitorInfo[0]; }
    public TLockInfo[] getLockedSynchronizers() { return new TLockInfo[0]; }
    @Override public String toString() { return "\"" + name + "\" Id=" + id + " " + state; }
}
