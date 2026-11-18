// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security.spec;

public class TX509EncodedKeySpec extends TEncodedKeySpec {
	public TX509EncodedKeySpec(byte[] encoded) {
		super(encoded);
	}

	@Override
	public String getFormat() {
		return "X.509";
	}
}
