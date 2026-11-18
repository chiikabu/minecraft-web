// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.minecraft.report;

import java.time.Instant;

public record AbuseReport(String opinionComments, String reason, ReportEvidence evidence, String skinUrl,
		ReportedEntity reportedEntity, Instant createdTime) {
	public static AbuseReport name(String opinionComments, ReportedEntity reportedEntity, Instant createdTime) {
		return new AbuseReport(opinionComments, null, null, null, reportedEntity, createdTime);
	}

	public static AbuseReport skin(String opinionComments, String reason, String skinUrl, ReportedEntity reportedEntity,
			Instant createdTime) {
		return new AbuseReport(opinionComments, reason, null, skinUrl, reportedEntity, createdTime);
	}

	public static AbuseReport chat(String opinionComments, String reason, ReportEvidence evidence,
			ReportedEntity reportedEntity, Instant createdTime) {
		return new AbuseReport(opinionComments, reason, evidence, null, reportedEntity, createdTime);
	}
}
