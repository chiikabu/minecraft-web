// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.services.response;

import java.nio.ByteBuffer;

public record KeyPairResponse(KeyPairResponse.KeyPair keyPair, ByteBuffer publicKeySignature, String expiresAt,
		String refreshedAfter) {
	public record KeyPair(String privateKey, String publicKey) {
	}
}
