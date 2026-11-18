// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

public class TReentrantLock implements TLock {
	private int holds;

	public TReentrantLock() {
	}

	public TReentrantLock(boolean fair) {
	}

	@Override public void lock() { holds++; }
	@Override public void lockInterruptibly() { holds++; }
	@Override public void unlock() { holds--; }
	@Override public boolean tryLock() { holds++; return true; }
	@Override public boolean tryLock(long time, TimeUnit unit) { holds++; return true; }
	@Override public TCondition newCondition() { return new TNoopCondition(); }
	public boolean isHeldByCurrentThread() { return holds > 0; }
	public boolean isLocked() { return holds > 0; }
	public int getHoldCount() { return holds; }
}
