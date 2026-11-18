// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;

public class TSignature {
	private final String algorithm;

	protected TSignature(String algorithm) {
		this.algorithm = algorithm;
	}

	public static TSignature getInstance(String algorithm) throws NoSuchAlgorithmException {
		return new TSignature(algorithm);
	}

	public final String getAlgorithm() {
		return algorithm;
	}

	public final void initVerify(PublicKey publicKey) {
	}

	public final void initSign(PrivateKey privateKey) {
	}

	public final void update(byte data) {
	}

	public final void update(byte[] data) {
	}

	public final void update(byte[] data, int off, int len) {
	}

	public final boolean verify(byte[] signature) throws SignatureException {
		return false;
	}

	public final byte[] sign() throws SignatureException {
		throw new SignatureException("signing is unavailable in the browser");
	}
}
