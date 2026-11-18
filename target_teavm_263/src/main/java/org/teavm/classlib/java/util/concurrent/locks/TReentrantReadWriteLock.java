// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent.locks;

public class TReentrantReadWriteLock implements TReadWriteLock {
	private final ReadLock read = new ReadLock();
	private final WriteLock write = new WriteLock();

	public TReentrantReadWriteLock() {
	}

	public TReentrantReadWriteLock(boolean fair) {
	}

	@Override
	public ReadLock readLock() {
		return read;
	}

	@Override
	public WriteLock writeLock() {
		return write;
	}

	public static class ReadLock extends TReentrantLock {
		ReadLock() {
		}
	}

	public static class WriteLock extends TReentrantLock {
		WriteLock() {
		}
	}
}
