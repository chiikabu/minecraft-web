// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.minecraft;

import java.util.Map;

public class MinecraftProfileTexture {
	public static final int PROFILE_TEXTURE_COUNT = Type.values().length;

	public enum Type {
		SKIN,
		CAPE,
		ELYTRA
	}

	private final String url;
	private final Map<String, String> metadata;

	public MinecraftProfileTexture(String url, Map<String, String> metadata) {
		this.url = url;
		this.metadata = metadata;
	}

	public String getUrl() {
		return url;
	}

	public String getMetadata(String key) {
		return metadata == null ? null : metadata.get(key);
	}

	public String getHash() {
		int slash = url.lastIndexOf('/');
		return slash < 0 ? url : url.substring(slash + 1);
	}

	@Override
	public String toString() {
		return "MinecraftProfileTexture{url=" + url + ", metadata=" + metadata + "}";
	}
}
