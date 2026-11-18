// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.services;

import java.security.Signature;

import com.mojang.authlib.properties.Property;

public interface ServicesKeyInfo {
	int keyBitCount();

	default int signatureBitCount() {
		return keyBitCount();
	}

	Signature signature();

	boolean validateProperty(Property property);
}
