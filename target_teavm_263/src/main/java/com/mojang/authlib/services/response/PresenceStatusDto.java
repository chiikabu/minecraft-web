// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.services.response;

import java.time.Instant;
import java.util.UUID;

public record PresenceStatusDto(UUID profileId, UUID pmid, PresenceStatus status, Instant lastUpdated) {
}
