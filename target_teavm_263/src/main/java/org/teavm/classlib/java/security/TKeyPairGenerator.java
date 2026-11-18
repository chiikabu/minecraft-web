// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public class TKeyPairGenerator {
	private final String algorithm;

	protected TKeyPairGenerator(String algorithm) {
		this.algorithm = algorithm;
	}

	public static TKeyPairGenerator getInstance(String algorithm) throws NoSuchAlgorithmException {
		return new TKeyPairGenerator(algorithm);
	}

	public final String getAlgorithm() { return algorithm; }

	public void initialize(int keysize) { }
	public void initialize(int keysize, SecureRandom random) { }

	public KeyPair generateKeyPair() {
		throw new UnsupportedOperationException("key generation is disabled in the browser build");
	}

	public final KeyPair genKeyPair() {
		return generateKeyPair();
	}
}
