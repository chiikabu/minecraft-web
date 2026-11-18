// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util.internal;

import java.util.Random;

public final class ThreadLocalRandom extends Random {
	private static final ThreadLocalRandom INSTANCE = new ThreadLocalRandom();

	private ThreadLocalRandom() {
	}

	public static ThreadLocalRandom current() {
		return INSTANCE;
	}
}
