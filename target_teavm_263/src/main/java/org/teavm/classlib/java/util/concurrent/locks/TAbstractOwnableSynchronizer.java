// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent.locks;

import java.io.Serializable;

public abstract class TAbstractOwnableSynchronizer implements Serializable {
    private transient Thread exclusiveOwnerThread;

    protected TAbstractOwnableSynchronizer() { }
    protected final void setExclusiveOwnerThread(Thread thread) { exclusiveOwnerThread = thread; }
    protected final Thread getExclusiveOwnerThread() { return exclusiveOwnerThread; }
}
