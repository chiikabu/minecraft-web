// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.services;

import java.util.Set;

import com.mojang.authlib.GameProfile;

public record ProfileResult(GameProfile profile, Set<ProfileActionType> actions) {
	public ProfileResult(GameProfile profile) {
		this(profile, Set.of());
	}
}
