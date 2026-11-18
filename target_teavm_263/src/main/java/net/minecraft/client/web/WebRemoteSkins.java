// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;

public final class WebRemoteSkins {
	private WebRemoteSkins() {
	}

	private static final Map<String, PlayerSkin> RESOLVED = new HashMap<>();
	private static final Set<String> INFLIGHT = new HashSet<>();
	private static final Map<String, Long> RETRY_AT = new HashMap<>();
	private static final Map<String, Integer> ATTEMPTS = new HashMap<>();
	private static final int MAX_ATTEMPTS = 6;
	private static final PlayerSkin NONE = DefaultPlayerSkin.getDefaultSkin();

	@Nullable
	public static PlayerSkin get(final GameProfile profile) {
		String packed = packedTextures(profile);
		String key = keyOf(profile.id(), packed);
		PlayerSkin have = RESOLVED.get(key);
		if (have != null) {
			return have == NONE ? null : have;
		}
		Long retryAt = RETRY_AT.get(key);
		if (retryAt != null && System.currentTimeMillis() < retryAt) {
			return null;
		}
		if (INFLIGHT.add(key)) {
			fetch(profile, packed, key);
		}
		return null;
	}

	public static boolean pending(final GameProfile profile) {
		return !RESOLVED.containsKey(keyOf(profile.id(), packedTextures(profile)));
	}

	private static boolean transient_(final int status) {
		return status == 0 || status == 408 || status == 429 || status >= 500;
	}

	private static void deferRetry(final String key, final UUID id, final int status) {
		int attempt = ATTEMPTS.merge(key, 1, Integer::sum);
		INFLIGHT.remove(key);
		if (attempt >= MAX_ATTEMPTS) {
			System.out.println("[26.3-web] remote skin: giving up on " + id + " after " + attempt + " attempts (relay HTTP " + status + ")");
			RESOLVED.put(key, NONE);
			return;
		}
		long delayMs = 2000L << (attempt - 1);
		RETRY_AT.put(key, System.currentTimeMillis() + delayMs);
		System.out.println("[26.3-web] remote skin: retry " + id + " in " + delayMs + " ms (relay HTTP " + status + ", attempt " + attempt + ")");
	}

	private static String keyOf(final UUID id, final String packed) {
		return id + "|" + (packed.isEmpty() ? "-" : Integer.toHexString(packed.hashCode()));
	}

	private static void fetch(final GameProfile profile, final String packed, final String key) {
		UUID id = profile.id();
		String relay = WebRelayAuth.relayUrl();
		if (relay == null || relay.isEmpty()) {
			RESOLVED.put(key, NONE);
			return;
		}
		String hint = packed.isEmpty() ? "" : decodeTextures(packed);
		String url = "";
		String model = "";
		int tab = hint.indexOf('\t');
		if (tab >= 0) {
			url = hint.substring(0, tab);
			model = hint.substring(tab + 1);
		}
		if (url.isEmpty() && id.version() != 4) {
			System.out.println("[26.3-web] remote skin: skipped " + id + " (no textures, uuid v" + id.version() + ")");
			RESOLVED.put(key, NONE);
			return;
		}
		StringBuilder body = new StringBuilder("{\"uuid\":\"");
		body.append(undash(id)).append('"');
		if (!url.isEmpty()) {
			body.append(",\"url\":\"").append(escape(url)).append('"');
		}
		body.append('}');
		final String knownModel = model;
		WebRelayRpc.post("/auth/skin-uuid", body.toString(), (status, text) -> apply(id, key, status, text, knownModel));
	}

	private static void apply(final UUID id, final String key, final int status, final String text, final String knownModel) {
		try {
			if (transient_(status)) {
				deferRetry(key, id, status);
				return;
			}
			String pngB64 = status == 200 ? field(text, "skinPng") : "";
			if (pngB64 == null || pngB64.isEmpty()) {
				System.out.println("[26.3-web] remote skin: none for " + id + " (relay HTTP " + status + "): " + (text == null ? "" : text.substring(0, Math.min(160, text.length()))));
				RESOLVED.put(key, NONE);
				return;
			}
			String modelName = knownModel.isEmpty() ? field(text, "model") : knownModel;
			PlayerModelType model = PlayerModelType.byLegacyServicesName(modelName);
			NativeImage image = NativeImage.read(Base64.getDecoder().decode(pngB64));
			image = SkinTextureDownloader.processLegacySkin(image, "relay skin " + id);
			Identifier tex = Identifier.fromNamespaceAndPath("minecraft_web", "skins/remote/" + undash(id) + "-" + Integer.toHexString(key.hashCode()));
			Minecraft.getInstance().getTextureManager().register(tex, new DynamicTexture(() -> "minecraft_web remote skin", image));
			RESOLVED.put(key, new PlayerSkin(new ClientAsset.ResourceTexture(tex, tex), null, null, model, false));
			System.out.println("[26.3-web] remote skin applied for " + id + " (" + image.getWidth() + "x" + image.getHeight() + ", " + model + ")");
		} catch (Throwable e) {
			System.out.println("[26.3-web] remote skin failed for " + id + ": " + e);
			RESOLVED.put(key, NONE);
		}
	}

	private static String packedTextures(final GameProfile profile) {
		for (Property property : profile.properties().get("textures")) {
			if (property != null && property.value() != null && !property.value().isEmpty()) {
				return property.value();
			}
		}
		return "";
	}

	private static String undash(final UUID id) {
		return id.toString().replace("-", "");
	}

	private static String escape(final String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '"' || c == '\\') {
				out.append('\\');
			}
			if (c >= ' ') {
				out.append(c);
			}
		}
		return out.toString();
	}

	@JSBody(params = { "b64" }, script = "try {"
		+ "  var o = JSON.parse(atob(b64));"
		+ "  var s = o && o.textures && o.textures.SKIN;"
		+ "  if (!s || !s.url) { return ''; }"
		+ "  var m = (s.metadata && s.metadata.model === 'slim') ? 'slim' : 'classic';"
		+ "  return String(s.url) + '\\t' + m;"
		+ "} catch (e) { return ''; }")
	private static native String decodeTextures(String b64);

	@JSBody(params = { "text", "name" }, script = "try {"
		+ "  var v = JSON.parse(text)[name];"
		+ "  if (v === undefined || v === null) { return ''; }"
		+ "  return typeof v === 'string' ? v : JSON.stringify(v);"
		+ "} catch (e) { return ''; }")
	private static native String field(String text, String name);
}
