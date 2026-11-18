// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.minecraft.report;

import java.util.List;

public record ReportEvidence(List<ReportChatMessage> messages) {
}
