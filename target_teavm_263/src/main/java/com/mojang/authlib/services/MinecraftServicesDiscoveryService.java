// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.services;

import java.net.InetAddress;
import java.net.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.Environment;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.HttpDiscoveryService;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.services.response.FriendData;
import com.mojang.authlib.services.response.NameAndId;
import com.mojang.authlib.services.response.PresenceResponse;

public class MinecraftServicesDiscoveryService extends HttpDiscoveryService {
	private MinecraftServicesDiscoveryService(Proxy proxy) {
		super(proxy);
	}

	public static MinecraftServicesDiscoveryService create(Proxy proxy) {
		return new MinecraftServicesDiscoveryService(proxy);
	}

	public static MinecraftServicesDiscoveryService create(Proxy proxy, boolean servicesKeySetEnabled) {
		return new MinecraftServicesDiscoveryService(proxy);
	}

	public static MinecraftServicesDiscoveryService create(Proxy proxy, boolean servicesKeySetEnabled, Environment environment) {
		return new MinecraftServicesDiscoveryService(proxy);
	}

	public static MinecraftServicesDiscoveryService createOffline(Proxy proxy) {
		return new MinecraftServicesDiscoveryService(proxy);
	}

	@Override
	public SessionService createMinecraftSessionService() {
		return new OfflineSessionService();
	}

	@Override
	public GameProfileRepository createProfileRepository() {
		return new OfflineProfileRepository();
	}

	public UserApiService createUserApiService(String accessToken) {
		return UserApiService.OFFLINE;
	}

	public FriendsService createFriendsService(String accessToken) {
		return new OfflineFriendsService();
	}

	public ServicesKeySet getServicesKeySet() {
		return ServicesKeySet.EMPTY;
	}

	private static class OfflineSessionService implements SessionService {
		@Override
		public void joinServer(UUID profileId, String authenticationToken, String serverId) {
		}

		@Override
		public ProfileResult hasJoinedServer(String profileName, String serverId, InetAddress address) {
			return null;
		}

		@Override
		public Property getPackedTextures(GameProfile profile) {
			return profile.properties().get("textures").stream().findFirst().orElse(null);
		}

		@Override
		public MinecraftProfileTextures unpackTextures(Property packedTextures) {
			return MinecraftProfileTextures.EMPTY;
		}

		@Override
		public ProfileResult fetchProfile(UUID profileId, boolean requireSecure) {
			return null;
		}

		@Override
		public String getSecurePropertyValue(Property property) {
			return property.value();
		}
	}

	private static class OfflineProfileRepository implements GameProfileRepository {
		@Override
		public void findProfilesByNames(String[] names, ProfileLookupCallback callback) {
			for (String name : names) {
				callback.onProfileLookupFailed(name, new ProfileNotFoundException("Offline mode"));
			}
		}

		@Override
		public Optional<NameAndId> findProfileByName(String name) {
			return Optional.empty();
		}
	}

	private static class OfflineFriendsService implements FriendsService {
		@Override
		public ResultCode getFriendData(Consumer<FriendData> consumer) {
			consumer.accept(FriendData.empty());
			return ResultCode.SERVICE_NOT_AVAILABLE;
		}

		@Override
		public ResultCode removeFriend(UUID id) {
			return ResultCode.SERVICE_NOT_AVAILABLE;
		}

		@Override
		public ResultCode acceptIncomingFriendRequest(UUID id) {
			return ResultCode.SERVICE_NOT_AVAILABLE;
		}

		@Override
		public ResultCode declineIncomingFriendRequest(UUID id) {
			return ResultCode.SERVICE_NOT_AVAILABLE;
		}

		@Override
		public ResultCode sendFriendRequest(String name) {
			return ResultCode.SERVICE_NOT_AVAILABLE;
		}

		@Override
		public ResultCode sendFriendRequest(UUID id) {
			return ResultCode.SERVICE_NOT_AVAILABLE;
		}

		@Override
		public ResultCode revokeOutgoingFriendRequest(UUID id) {
			return ResultCode.SERVICE_NOT_AVAILABLE;
		}

		@Override
		public ResultCode updateFriendSettings(boolean friendsListEnabled, boolean allowInvites) {
			return ResultCode.SERVICE_NOT_AVAILABLE;
		}

		@Override
		public PresenceResponse presence(String status) {
			return PresenceResponse.empty();
		}
	}
}
