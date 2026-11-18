// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security.spec;

public class TPKCS8EncodedKeySpec extends TEncodedKeySpec {
	public TPKCS8EncodedKeySpec(byte[] encoded) {
		super(encoded);
	}

	@Override
	public String getFormat() {
		return "PKCS#8";
	}
}
