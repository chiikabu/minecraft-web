// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.minecraft;

import java.time.Instant;
import java.util.UUID;

public record BanDetails(UUID id, Instant expires, String reason, String reasonMessage) {
	public static final String MULTIPLAYER_SCOPE = "MULTIPLAYER";
}
