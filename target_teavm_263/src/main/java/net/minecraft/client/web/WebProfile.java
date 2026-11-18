// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Base64;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public final class WebProfile {
	public static final int MAX_NAME = 16;

	private static final String KEY_NAME = "minecraft_web:username";
	private static final String KEY_SKIN = "minecraft_web:skin";
	private static final String KEY_SLIM = "minecraft_web:skin.slim";
	private static final String KEY_PNG = "minecraft_web:skin.png";
	private static final String KEY_MODE = "minecraft_web:playMode";

	private static final int DEFAULT_SKIN = 6;

	private static String username = "Player";
	private static int skinIndex = DEFAULT_SKIN;
	private static boolean slim;
	private static boolean microsoftMode;
	@Nullable
	private static String customPng;
	@Nullable
	private static Runnable customSkinListener;
	@Nullable
	private static PlayerSkin customSkin;
	@Nullable
	private static String customSkinKey;

	private WebProfile() {
	}

	public static void load() {
		String stored = get(KEY_NAME, "");
		username = stored.isEmpty() ? "Player" + (1000 + (int)(Math.random() * 9000)) : sanitize(stored);
		skinIndex = parseIndex(get(KEY_SKIN, ""), DEFAULT_SKIN);
		slim = "1".equals(get(KEY_SLIM, "0"));
		String png = get(KEY_PNG, "");
		customPng = png.isEmpty() ? null : png;
		if (stored.isEmpty()) {
			set(KEY_NAME, username);
		}
		String mode = get(KEY_MODE, "");
		microsoftMode = "1".equals(mode) || (mode.isEmpty() && WebRelayAuth.hasStoredAccount());
		System.out.println("[26.3-web] profile: username=" + username
			+ ", skin=" + (customPng != null ? "custom" : "builtin#" + skinIndex)
			+ ", model=" + (slim ? "slim" : "wide")
			+ ", mode=" + (microsoftMode ? "microsoft" : "offline"));
	}

	public static boolean microsoftMode() {
		return microsoftMode;
	}

	public static void setMicrosoftMode(final boolean microsoft) {
		microsoftMode = microsoft;
		set(KEY_MODE, microsoft ? "1" : "0");
		if (microsoft) {
			if (WebRelayAuth.state() == WebRelayAuth.LINKED && !WebRelayAuth.linkedName().isEmpty()) {
				applySession(WebRelayAuth.linkedName());
			}
		} else {
			applySession(username);
		}
	}

	public static void install() {
		TitleScreen.profileScreen = ProfileScreen::new;
		RelaysScreen.install();
		WebLanDiscovery.install();
		installSkinInput(WebProfile::acceptUpload);
	}

	public static String username() {
		return username;
	}

	public static UUID uuid() {
		if (microsoftMode) {
			UUID linked = WebRelayAuth.linkedUuid();
			if (linked != null) {
				return linked;
			}
		}
		return UUIDUtil.createOfflinePlayerUUID(username);
	}

	public static int skinIndex() {
		return skinIndex;
	}

	public static boolean slim() {
		return slim;
	}

	public static boolean hasCustomSkin() {
		return customPng != null;
	}

	public static PlayerSkin skin() {
		if (customPng != null) {
			String key = (slim ? "slim:" : "wide:") + customPng;
			if (!key.equals(customSkinKey)) {
				customSkin = registerCustomSkin(customPng, slim ? PlayerModelType.SLIM : PlayerModelType.WIDE);
				customSkinKey = key;
			}
			if (customSkin != null) {
				return customSkin;
			}
		}
		return DefaultPlayerSkin.builtin((slim ? 0 : DefaultPlayerSkin.CHARACTERS.length) + skinIndex);
	}

	public static void applySkin() {
		DefaultPlayerSkin.setLocalSkin(uuid(), skin());
	}

	public static void save(final String name, final int skin, final boolean slimArms) {
		username = sanitize(name);
		skinIndex = Math.floorMod(skin, DefaultPlayerSkin.CHARACTERS.length);
		slim = slimArms;
		set(KEY_NAME, username);
		set(KEY_SKIN, Integer.toString(skinIndex));
		set(KEY_SLIM, slim ? "1" : "0");

		if (!microsoftMode) {
			applySession(username);
		}
		applySkin();
	}

	public static void applySession(final String name) {
		String session = sanitize(name);
		if (session.isEmpty()) {
			session = username;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.level == null) {
			minecraft.setUser(new User(session, uuid(), "", java.util.Optional.empty(), java.util.Optional.empty()));
		}
	}

	public static void applyLinkedSkin(final String dataUrl, final boolean slimArms) {
		slim = slimArms;
		set(KEY_SLIM, slim ? "1" : "0");
		acceptUpload(dataUrl);
	}

	public static void clearCustomSkin() {
		customPng = null;
		remove(KEY_PNG);
		applySkin();
	}

	public static void chooseSkinFile() {
		clickSkinPicker();
	}

	public static void setCustomSkinListener(@Nullable final Runnable listener) {
		customSkinListener = listener;
	}

	private static void acceptUpload(final String dataUrl) {
		customPng = dataUrl;
		set(KEY_PNG, dataUrl);
		applySkin();
		if (customSkinListener != null) {
			customSkinListener.run();
		}
	}

	@Nullable
	private static PlayerSkin registerCustomSkin(final String dataUrl, final PlayerModelType model) {
		Identifier id = Identifier.fromNamespaceAndPath("minecraft_web", "skins/custom");
		try {
			NativeImage image = NativeImage.read(decodeDataUrl(dataUrl));
			image = SkinTextureDownloader.processLegacySkin(image, "custom skin upload");
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "minecraft_web custom skin", image));
			return new PlayerSkin(new ClientAsset.ResourceTexture(id, id), null, null, model, false);
		} catch (Throwable e) {
			System.out.println("[26.3-web] custom skin rejected, falling back to the default: " + e);
			return null;
		}
	}

	public static String sanitize(final String raw) {
		StringBuilder out = new StringBuilder(MAX_NAME);
		for (int i = 0; i < raw.length() && out.length() < MAX_NAME; i++) {
			char c = raw.charAt(i);
			if (c == '_' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
				out.append(c);
			}
		}
		return out.length() == 0 ? "Player" : out.toString();
	}

	private static int parseIndex(final String raw, final int fallback) {
		try {
			int i = Integer.parseInt(raw);
			return i >= 0 && i < DefaultPlayerSkin.CHARACTERS.length ? i : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public static byte[] faceIcon() {
		try {
			byte[] png;
			if (customPng != null) {
				png = decodeDataUrl(customPng);
			} else {
				Identifier path = skin().body().texturePath();
				try (java.io.InputStream in = Minecraft.getInstance().getResourceManager().open(path)) {
					png = in.readAllBytes();
				}
			}
			return FaceIcon.fromSkinPng(png);
		} catch (Throwable e) {
			System.out.println("[26.3-web] LAN icon: no face from the current skin (" + e + ")");
			return new byte[0];
		}
	}

	private static byte[] decodeDataUrl(final String dataUrl) {
		int comma = dataUrl.indexOf(',');
		return Base64.getDecoder().decode(comma < 0 ? dataUrl : dataUrl.substring(comma + 1));
	}

	@JSFunctor
	private interface SkinHandler extends JSObject {
		void skin(String dataUrl);
	}

	@JSBody(params = { "k", "d" }, script = "try { var v = window.localStorage.getItem(k);"
		+ " return v === null || v === undefined ? d : v; } catch (e) { return d; }")
	private static native String get(String k, String d);

	@JSBody(params = { "k", "v" }, script = "try { window.localStorage.setItem(k, v); } catch (e) {}")
	private static native void set(String k, String v);

	@JSBody(params = { "k" }, script = "try { window.localStorage.removeItem(k); } catch (e) {}")
	private static native void remove(String k);

	@JSBody(params = { "onSkin" }, script = ""
		+ "if (document.getElementById('minecraft-skin-file')) { return; }"
		+ "var input = document.createElement('input');"
		+ "input.type = 'file'; input.accept = 'image/png,.png';"
		+ "input.id = 'minecraft-skin-file'; input.style.display = 'none';"
		+ "input.onchange = function() {"
		+ "  var f = input.files && input.files[0];"
		+ "  input.value = '';"
		+ "  if (!f) { return; }"
		+ "  var fr = new FileReader();"
		+ "  fr.onload = function() {"
		+ "    var img = new Image();"
		+ "    img.onload = function() {"
		+ "      if (img.width === 64 && img.height === 64) { onSkin(fr.result); }"
		+ "      else { console.info('[26.3-web] skin must be 64x64, got ' + img.width + 'x' + img.height); }"
		+ "    };"
		+ "    img.onerror = function() { console.info('[26.3-web] not a readable PNG'); };"
		+ "    img.src = fr.result;"
		+ "  };"
		+ "  fr.readAsDataURL(f);"
		+ "};"
		+ "document.body.appendChild(input);")
	private static native void installSkinInput(SkinHandler onSkin);

	@JSBody(params = {}, script = ""
		+ "var i = document.getElementById('minecraft-skin-file');"
		+ "if (!i) { console.error('[26.3-web] skin file input missing'); return; }"
		+ "if (navigator.userActivation && !navigator.userActivation.isActive) {"
		+ "  console.error('[26.3-web] the browser will not open a file dialog here: no user activation');"
		+ "}"
		+ "i.click();")
	private static native void clickSkinPicker();
}
