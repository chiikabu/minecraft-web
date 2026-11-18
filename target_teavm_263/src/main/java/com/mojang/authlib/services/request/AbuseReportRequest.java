// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.services.request;

import java.util.UUID;

import com.mojang.authlib.minecraft.report.AbuseReport;

public record AbuseReportRequest(int version, UUID id, AbuseReport report, AbuseReportRequest.ClientInfo clientInfo,
		AbuseReportRequest.ThirdPartyServerInfo thirdPartyServerInfo, AbuseReportRequest.RealmInfo realmInfo,
		String reportType) {
	public record ClientInfo(String clientVersion, String locale) {
	}

	public record ThirdPartyServerInfo(String address) {
	}

	public record RealmInfo(String realmId, int slotId) {
	}
}
