// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

public abstract class TSecureRandomSpi {
	protected abstract void engineSetSeed(byte[] seed);

	protected abstract void engineNextBytes(byte[] bytes);

	protected abstract byte[] engineGenerateSeed(int numBytes);
}
