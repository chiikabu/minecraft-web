// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib;

import java.util.Optional;

import com.mojang.authlib.services.response.NameAndId;

public interface GameProfileRepository {
	void findProfilesByNames(String[] names, ProfileLookupCallback callback);

	Optional<NameAndId> findProfileByName(String name);
}
