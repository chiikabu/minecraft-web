// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.services;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.services.response.FriendData;
import com.mojang.authlib.services.response.PresenceResponse;

public interface FriendsService {
	enum ResultCode {
		SUCCESS,
		ERROR,
		SERVICE_NOT_AVAILABLE,
		TOO_MANY_REQUESTS,
		FORBIDDEN,
		UPGRADE_NEEDED,
		CONNECTION_ISSUE,
		TEMPORARY_UNAVAILABLE,
		UNKNOWN_PROFILE,
		UNAUTHORIZED,
		GENERIC_ERROR
	}

	record PlayerData(UUID id, String name) {
	}

	ResultCode getFriendData(Consumer<FriendData> consumer);

	ResultCode removeFriend(UUID id);

	ResultCode acceptIncomingFriendRequest(UUID id);

	ResultCode declineIncomingFriendRequest(UUID id);

	ResultCode sendFriendRequest(String name);

	ResultCode sendFriendRequest(UUID id);

	ResultCode revokeOutgoingFriendRequest(UUID id);

	ResultCode updateFriendSettings(boolean friendsListEnabled, boolean allowInvites);

	PresenceResponse presence(String status);

	default Optional<Duration> getFriendsPollInterval() {
		return Optional.empty();
	}

	default Optional<Duration> getPresencePollInterval() {
		return Optional.empty();
	}
}
