// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.management;

import java.util.Objects;

public class TLockInfo {
    private final String className;
    private final int identityHashCode;

    public TLockInfo(String className, int identityHashCode) {
        this.className = Objects.requireNonNull(className);
        this.identityHashCode = identityHashCode;
    }

    public String getClassName() { return className; }
    public int getIdentityHashCode() { return identityHashCode; }
    @Override public String toString() { return className + "@" + Integer.toHexString(identityHashCode); }
}
