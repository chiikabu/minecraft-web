// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent.locks;

import java.util.Date;
import java.util.concurrent.TimeUnit;

class TNoopCondition implements TCondition {
	@Override public void await() { }
	@Override public void awaitUninterruptibly() { }
	@Override public long awaitNanos(long nanosTimeout) { return nanosTimeout; }
	@Override public boolean await(long time, TimeUnit unit) { return true; }
	@Override public boolean awaitUntil(Date deadline) { return true; }
	@Override public void signal() { }
	@Override public void signalAll() { }
}
