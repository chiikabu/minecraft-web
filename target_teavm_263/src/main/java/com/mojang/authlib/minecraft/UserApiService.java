// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.minecraft;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.report.AbuseReportLimits;
import com.mojang.authlib.services.request.AbuseReportRequest;
import com.mojang.authlib.services.response.KeyPairResponse;
import net.minecraft.client.web.WebRelayAuth;

public interface UserApiService {
	enum UserFlag {
		SERVERS_ALLOWED,
		REALMS_ALLOWED,
		CHAT_ALLOWED,
		CHAT_FRIENDS_ONLY,
		TELEMETRY_ENABLED,
		PROFANITY_FILTER_ENABLED,
		OPTIONAL_TELEMETRY_AVAILABLE,
		FRIENDS_ENABLED,
		ACCEPT_FRIEND_INVITES
	}

	record UserProperties(Set<UserFlag> flags, Map<String, BanDetails> bannedScopes) {
		public boolean flag(UserFlag flag) {
			return flags.contains(flag);
		}
	}

	UserProperties OFFLINE_PROPERTIES = new UserProperties(
			Set.of(UserFlag.CHAT_ALLOWED, UserFlag.REALMS_ALLOWED, UserFlag.SERVERS_ALLOWED), Map.of());

	UserApiService OFFLINE = new UserApiService() {
		@Override
		public UserProperties fetchProperties() {
			return OFFLINE_PROPERTIES;
		}

		@Override
		public boolean isBlockedPlayer(UUID id) {
			return false;
		}

		@Override
		public void refreshBlockList() {
		}

		@Override
		public TelemetrySession newTelemetrySession(Executor executor) {
			return TelemetrySession.DISABLED;
		}

		@Override
		public KeyPairResponse getKeyPair() {
			WebRelayAuth.Certificates certs = WebRelayAuth.certificatesSync();
			if (certs == null) {
				return null;
			}
			try {
				byte[] sig = Base64.getDecoder().decode(certs.publicKeySignature);
				return new KeyPairResponse(
					new KeyPairResponse.KeyPair(certs.privateKey, certs.publicKey),
					ByteBuffer.wrap(sig),
					certs.expiresAt,
					certs.refreshedAfter
				);
			} catch (RuntimeException e) {
				return null;
			}
		}

		@Override
		public void reportAbuse(AbuseReportRequest request) {
		}

		@Override
		public boolean canSendReports() {
			return false;
		}

		@Override
		public AbuseReportLimits getAbuseReportLimits() {
			return AbuseReportLimits.DEFAULTS;
		}
	};

	UserProperties fetchProperties() throws AuthenticationException;

	boolean isBlockedPlayer(UUID id);

	void refreshBlockList();

	TelemetrySession newTelemetrySession(Executor executor);

	KeyPairResponse getKeyPair();

	void reportAbuse(AbuseReportRequest request);

	boolean canSendReports();

	AbuseReportLimits getAbuseReportLimits();
}
