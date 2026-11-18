// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

public interface TRejectedExecutionHandler {
    void rejectedExecution(Runnable task, TThreadPoolExecutor executor);
}
