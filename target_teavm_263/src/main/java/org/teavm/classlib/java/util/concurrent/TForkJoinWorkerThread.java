// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util.concurrent;

public class TForkJoinWorkerThread extends Thread {
	protected TForkJoinWorkerThread(TForkJoinPool pool) {
	}

	public TForkJoinPool getPool() {
		return TForkJoinPool.commonPool();
	}
}
