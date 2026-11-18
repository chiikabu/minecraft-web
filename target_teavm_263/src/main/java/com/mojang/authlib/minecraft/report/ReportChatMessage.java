// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.minecraft.report;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReportChatMessage(int index, UUID profileId, UUID sessionId, Instant timestamp, long salt,
		List<ByteBuffer> lastSeen, String message, ByteBuffer signature, boolean messageReported) {
}
