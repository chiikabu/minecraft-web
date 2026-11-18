// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security.spec;

public abstract class TEncodedKeySpec implements TKeySpec {
	private final byte[] encoded;

	public TEncodedKeySpec(byte[] encoded) {
		this.encoded = encoded.clone();
	}

	public byte[] getEncoded() {
		return encoded.clone();
	}

	public abstract String getFormat();
}
