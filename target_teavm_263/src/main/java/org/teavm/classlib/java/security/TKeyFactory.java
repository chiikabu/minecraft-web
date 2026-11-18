// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

public class TKeyFactory {
	private final String algorithm;

	private TKeyFactory(String algorithm) {
		this.algorithm = algorithm;
	}

	public static TKeyFactory getInstance(String algorithm) throws TNoSuchAlgorithmException {
		return new TKeyFactory(algorithm);
	}

	public String getAlgorithm() {
		return algorithm;
	}

	public TPublicKey generatePublic(org.teavm.classlib.java.security.spec.TKeySpec keySpec) throws java.security.GeneralSecurityException {
		throw new java.security.GeneralSecurityException("RSA key parsing unavailable in browser");
	}

	public TPrivateKey generatePrivate(org.teavm.classlib.java.security.spec.TKeySpec keySpec) throws java.security.GeneralSecurityException {
		throw new java.security.GeneralSecurityException("RSA key parsing unavailable in browser");
	}
}
