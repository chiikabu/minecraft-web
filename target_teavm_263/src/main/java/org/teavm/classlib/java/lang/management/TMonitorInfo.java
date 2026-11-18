// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.management;

public class TMonitorInfo extends TLockInfo {
    private final int stackDepth;
    private final StackTraceElement stackFrame;

    public TMonitorInfo(String className, int identityHashCode, int stackDepth, StackTraceElement stackFrame) {
        super(className, identityHashCode);
        if ((stackDepth >= 0) != (stackFrame != null)) throw new IllegalArgumentException("Inconsistent monitor stack depth/frame");
        this.stackDepth = stackDepth;
        this.stackFrame = stackFrame;
    }

    public int getLockedStackDepth() { return stackDepth; }
    public StackTraceElement getLockedStackFrame() { return stackFrame; }
}
