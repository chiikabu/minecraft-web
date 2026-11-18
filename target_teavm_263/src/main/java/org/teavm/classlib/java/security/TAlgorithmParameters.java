// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import java.io.IOException;
import java.security.spec.AlgorithmParameterSpec;

public class TAlgorithmParameters {
	private final String algorithm;

	protected TAlgorithmParameters(String algorithm) {
		this.algorithm = algorithm;
	}

	public static TAlgorithmParameters getInstance(String algorithm) throws TNoSuchAlgorithmException {
		throw new TNoSuchAlgorithmException("no security providers in the browser");
	}

	public final String getAlgorithm() {
		return algorithm;
	}

	public final void init(AlgorithmParameterSpec paramSpec) throws TInvalidParameterException {
	}

	public final void init(byte[] params) throws IOException {
	}

	public final byte[] getEncoded() throws IOException {
		throw new IOException("no algorithm parameters in the browser");
	}

	public final <T extends AlgorithmParameterSpec> T getParameterSpec(Class<T> paramSpec) {
		return null;
	}

	@Override
	public String toString() {
		return algorithm + " AlgorithmParameters";
	}
}
