// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.minecraft;

import java.net.InetAddress;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.services.ProfileResult;

public interface SessionService {
	void joinServer(UUID profileId, String authenticationToken, String serverId) throws AuthenticationException;

	ProfileResult hasJoinedServer(String profileName, String serverId, InetAddress address)
			throws AuthenticationUnavailableException;

	Property getPackedTextures(GameProfile profile);

	MinecraftProfileTextures unpackTextures(Property packedTextures);

	default MinecraftProfileTextures getTextures(GameProfile profile) {
		Property packed = getPackedTextures(profile);
		return packed == null ? MinecraftProfileTextures.EMPTY : unpackTextures(packed);
	}

	ProfileResult fetchProfile(UUID profileId, boolean requireSecure);

	String getSecurePropertyValue(Property property) throws InsecurePublicKeyException;
}
