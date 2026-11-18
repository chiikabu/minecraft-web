// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

public interface TLock {
	void lock();
	void lockInterruptibly() throws InterruptedException;
	void unlock();
	boolean tryLock();
	boolean tryLock(long time, TimeUnit unit) throws InterruptedException;
	TCondition newCondition();
}
