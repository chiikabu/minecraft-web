// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent.locks;

import java.util.Date;
import java.util.concurrent.TimeUnit;

public interface TCondition {
	void await() throws InterruptedException;
	void awaitUninterruptibly();
	long awaitNanos(long nanosTimeout) throws InterruptedException;
	boolean await(long time, TimeUnit unit) throws InterruptedException;
	boolean awaitUntil(Date deadline) throws InterruptedException;
	void signal();
	void signalAll();
}
