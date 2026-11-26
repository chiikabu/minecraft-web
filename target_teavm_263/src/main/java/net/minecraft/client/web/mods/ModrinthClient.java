// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.web.WebPacks;
import net.minecraft.client.web.shaderpack.ShaderPacks;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Java bridge to {@code window.MinecraftWebModrinth} (Modrinth v2 API &amp; CDN tunneled end-to-end
 * over WISP Relays via TLS 1.3 + HTTP/1.1 keep-alive). Manages thumbnail {@link DynamicTexture}
 * caching, search/project/version queries, and 1-click installation into {@link WebMods},
 * {@link ShaderPacks}, and {@link WebPacks}.
 */
public final class ModrinthClient {
	public static final int THUMB_SIZE = 64;
	private static final int MAX_THUMB_TEXTURES = 160;
	private static final String LS_INSTALLED_MAP = "minecraft_web:modrinth.installed";
	private static final String RESOURCEPACKS_DIR = "/resourcepacks";

	private static final Map<String, Identifier> THUMB_TEXTURES = new LinkedHashMap<>(64, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(final Map.Entry<String, Identifier> eldest) {
			if (this.size() > MAX_THUMB_TEXTURES) {
				Identifier evicted = eldest.getValue();
				if (evicted != null) {
					Minecraft mc = Minecraft.getInstance();
					if (mc != null && mc.getTextureManager() != null) {
						try {
							mc.getTextureManager().release(evicted);
						} catch (Throwable ignored) {
						}
					}
				}
				return true;
			}
			return false;
		}
	};
	private static final Set<String> THUMB_INFLIGHT = new HashSet<>();
	private static final Set<String> THUMB_FAILED = new HashSet<>();
	private static final Set<String> SESSION_INSTALLED_IDS = new HashSet<>();
	private static final Set<String> SESSION_INSTALLED_SLUGS = new HashSet<>();
	private static final Map<String, String> SESSION_FILE_TO_SLUG = new HashMap<>();
	private static final Map<String, String> INSTALLED_PROJECT_FILES = new HashMap<>();
	private static boolean installedMapLoaded;

	private static int searchGeneration;

	private ModrinthClient() {
	}

	// ---- Public DTOs ----

	public static final class RelayInfo {
		public final boolean ready;
		public final int relayCount;
		public final String relayLabel;
		public final String transport;
		public final String routeMode;

		RelayInfo(final boolean ready, final int relayCount, final String relayLabel, final String transport, final String routeMode) {
			this.ready = ready;
			this.relayCount = relayCount;
			this.relayLabel = relayLabel;
			this.transport = transport;
			this.routeMode = routeMode;
		}

		public boolean isDirect() {
			return "direct".equals(this.transport) || "direct".equals(this.routeMode);
		}

		public boolean needsRelaySetup() {
			return "relay".equals(this.routeMode) && this.relayCount <= 0;
		}

		public String badgeText() {
			if ("direct".equals(this.routeMode)) {
				return "Direct";
			}
			if (this.needsRelaySetup()) {
				return "No Relay";
			}
			if ("direct".equals(this.transport) || this.relayCount <= 0) {
				return "Direct";
			}
			String name = this.relayLabel.isEmpty() ? "Relay" : this.relayLabel;
			if ("webrtc".equals(this.transport)) {
				return name + " (RTC)";
			}
			if ("websocket".equals(this.transport)) {
				return name + " (WS)";
			}
			return name;
		}

		public String routeButtonLabel() {
			if ("direct".equals(this.routeMode)) {
				return "Net: Direct";
			}
			if ("relay".equals(this.routeMode)) {
				return this.relayCount <= 0 ? "Add Relay..." : ("Net: " + badgeText());
			}
			return "direct".equals(this.transport) || this.relayCount <= 0
				? "Net: Direct"
				: ("Net: " + (this.relayLabel.isEmpty() ? "Relay" : this.relayLabel));
		}
	}

	public static final class ProjectHit {
		public final String id;
		public final String slug;
		public final String title;
		public final String description;
		public final String author;
		public final String projectType;
		public final String iconUrl;
		public final long downloads;
		public final long follows;
		public final String latestVersion;
		public final String clientSide;
		public final String serverSide;
		public final List<String> categories;
		public final List<String> gameVersions;

		ProjectHit(
				final String id, final String slug, final String title, final String description,
				final String author, final String projectType, final String iconUrl,
				final long downloads, final long follows, final String latestVersion,
				final String clientSide, final String serverSide,
				final List<String> categories, final List<String> gameVersions) {
			this.id = id;
			this.slug = slug;
			this.title = title;
			this.description = description;
			this.author = author;
			this.projectType = projectType;
			this.iconUrl = iconUrl;
			this.downloads = downloads;
			this.follows = follows;
			this.latestVersion = latestVersion;
			this.clientSide = clientSide;
			this.serverSide = serverSide;
			this.categories = categories;
			this.gameVersions = gameVersions;
		}

		public String formattedDownloads() {
			return formatCount(this.downloads);
		}

		public String typeBadge() {
			if ("shader".equals(this.projectType)) {
				return "[Shader]";
			}
			if ("resourcepack".equals(this.projectType)) {
				return "[Resource]";
			}
			if ("datapack".equals(this.projectType)) {
				return "[Data]";
			}
			return "[Fabric]";
		}

		public int typeBadgeColor() {
			if ("shader".equals(this.projectType)) {
				return 0xFFFF77FF;
			}
			if ("resourcepack".equals(this.projectType)) {
				return 0xFF55FFFF;
			}
			if ("datapack".equals(this.projectType)) {
				return 0xFFFFAA00;
			}
			return 0xFF55FF55;
		}

		public int accentColor() {
			int h = this.slug.hashCode();
			int r = 70 + ((h >>> 16) & 0x7F);
			int g = 70 + ((h >>> 8) & 0x7F);
			int b = 70 + (h & 0x7F);
			return 0xFF000000 | (r << 16) | (g << 8) | b;
		}

		public String monogram() {
			String s = this.title.isEmpty() ? this.slug : this.title;
			return s.isEmpty() ? "M" : s.substring(0, 1).toUpperCase(Locale.ROOT);
		}

		public String metaSummary() {
			StringBuilder sb = new StringBuilder();
			int added = 0;
			for (String cat : this.categories) {
				if ("fabric".equalsIgnoreCase(cat) || "quilt".equalsIgnoreCase(cat)
						|| "forge".equalsIgnoreCase(cat) || "neoforge".equalsIgnoreCase(cat)
						|| "iris".equalsIgnoreCase(cat) || "optifine".equalsIgnoreCase(cat)) {
					continue;
				}
				if (added > 0) {
					sb.append(" · ");
				}
				sb.append(capitalize(cat));
				if (++added >= 3) {
					break;
				}
			}
			if (!this.gameVersions.isEmpty()) {
				String latest = this.gameVersions.get(this.gameVersions.size() - 1);
				if (sb.length() > 0) {
					sb.append(" · ");
				}
				sb.append("MC ").append(latest);
			}
			if ("required".equals(this.clientSide) || "optional".equals(this.clientSide)) {
				if (sb.length() > 0) {
					sb.append(" · ");
				}
				sb.append("Client");
			}
			return sb.toString();
		}
	}

	public static final class SearchResult {
		public final List<ProjectHit> hits;
		public final int offset;
		public final int limit;
		public final int totalHits;
		public final String relayLabel;
		public final String transport;

		SearchResult(final List<ProjectHit> hits, final int offset, final int limit, final int totalHits,
				final String relayLabel, final String transport) {
			this.hits = hits;
			this.offset = offset;
			this.limit = limit;
			this.totalHits = totalHits;
			this.relayLabel = relayLabel;
			this.transport = transport;
		}

		public boolean hasMore() {
			return this.offset + this.hits.size() < this.totalHits;
		}
	}

	public static final class ProjectDetail {
		public final String id;
		public final String slug;
		public final String title;
		public final String description;
		public final String body;
		public final String projectType;
		public final String iconUrl;
		public final long downloads;
		public final long followers;
		public final String license;
		public final String clientSide;
		public final String serverSide;
		public final List<String> categories;
		public final List<String> loaders;
		public final List<String> gameVersions;

		ProjectDetail(
				final String id, final String slug, final String title, final String description,
				final String body, final String projectType, final String iconUrl,
				final long downloads, final long followers, final String license,
				final String clientSide, final String serverSide,
				final List<String> categories, final List<String> loaders, final List<String> gameVersions) {
			this.id = id;
			this.slug = slug;
			this.title = title;
			this.description = description;
			this.body = body;
			this.projectType = projectType;
			this.iconUrl = iconUrl;
			this.downloads = downloads;
			this.followers = followers;
			this.license = license;
			this.clientSide = clientSide;
			this.serverSide = serverSide;
			this.categories = categories;
			this.loaders = loaders;
			this.gameVersions = gameVersions;
		}
	}

	public static final class ProjectVersion {
		public final String id;
		public final String name;
		public final String versionNumber;
		public final String versionType;
		public final String datePublished;
		public final long downloads;
		public final List<String> gameVersions;
		public final List<String> loaders;
		public final String fileUrl;
		public final String filename;
		public final long fileSize;
		public final String sha1;
		public final int requiredDepCount;
		public final List<String> requiredDepIds;

		ProjectVersion(
				final String id, final String name, final String versionNumber, final String versionType,
				final String datePublished, final long downloads, final List<String> gameVersions,
				final List<String> loaders, final String fileUrl, final String filename,
				final long fileSize, final String sha1, final int requiredDepCount,
				final List<String> requiredDepIds) {
			this.id = id;
			this.name = name;
			this.versionNumber = versionNumber;
			this.versionType = versionType;
			this.datePublished = datePublished;
			this.downloads = downloads;
			this.gameVersions = gameVersions;
			this.loaders = loaders;
			this.fileUrl = fileUrl;
			this.filename = filename;
			this.fileSize = fileSize;
			this.sha1 = sha1;
			this.requiredDepCount = requiredDepCount;
			this.requiredDepIds = requiredDepIds;
		}

		public String formattedSize() {
			if (this.fileSize <= 0L) {
				return "";
			}
			if (this.fileSize >= 1024L * 1024L) {
				long tenths = (this.fileSize * 10L) / (1024L * 1024L);
				return (tenths / 10L) + "." + (tenths % 10L) + " MB";
			}
			return Math.max(1L, this.fileSize / 1024L) + " KB";
		}

		public String channelBadge() {
			if ("beta".equalsIgnoreCase(this.versionType)) {
				return "[Beta]";
			}
			if ("alpha".equalsIgnoreCase(this.versionType)) {
				return "[Alpha]";
			}
			return "[Release]";
		}

		public int channelColor() {
			if ("beta".equalsIgnoreCase(this.versionType)) {
				return 0xFFFFAA00;
			}
			if ("alpha".equalsIgnoreCase(this.versionType)) {
				return 0xFFFF5555;
			}
			return 0xFF55FF55;
		}

		public String mcVersionsShort() {
			if (this.gameVersions.isEmpty()) {
				return "";
			}
			if (this.gameVersions.size() == 1) {
				return this.gameVersions.get(0);
			}
			return this.gameVersions.get(0) + ".." + this.gameVersions.get(this.gameVersions.size() - 1);
		}
	}

	// ---- Public API ----

	public static boolean isAvailable() {
		return jsAvailable();
	}

	public static RelayInfo relayInfo() {
		if (!jsAvailable()) {
			return new RelayInfo(false, 0, "", "", "auto");
		}
		try {
			String raw = jsRelayStatusJson();
			JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
			String route = strOf(obj, "routeMode");
			return new RelayInfo(
				boolOf(obj, "ready"),
				intOf(obj, "relayCount"),
				strOf(obj, "relayLabel"),
				strOf(obj, "transport"),
				route.isEmpty() ? "auto" : route);
		} catch (Throwable e) {
			return new RelayInfo(false, 0, "", "", "auto");
		}
	}

	public static String cycleRouteMode() {
		THUMB_FAILED.clear();
		return jsAvailable() ? jsCycleRouteMode() : "auto";
	}

	public static void clearFailedThumbnails() {
		THUMB_FAILED.clear();
	}

	private static void ensureInstalledMapLoaded() {
		if (installedMapLoaded) {
			return;
		}
		installedMapLoaded = true;
		String raw = jsLsGet(LS_INSTALLED_MAP);
		if (raw == null || raw.isEmpty()) {
			return;
		}
		try {
			JsonElement el = JsonParser.parseString(raw);
			if (el != null && el.isJsonObject()) {
				for (Map.Entry<String, JsonElement> entry : el.getAsJsonObject().entrySet()) {
					if (entry.getValue() != null && !entry.getValue().isJsonNull()) {
						String pid = entry.getKey();
						String fn = entry.getValue().getAsString();
						if (pid != null && !pid.isEmpty() && fn != null && !fn.isEmpty()) {
							INSTALLED_PROJECT_FILES.put(pid, fn);
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	private static void saveInstalledMap() {
		try {
			JsonObject obj = new JsonObject();
			for (Map.Entry<String, String> entry : INSTALLED_PROJECT_FILES.entrySet()) {
				obj.addProperty(entry.getKey(), entry.getValue());
			}
			jsLsSet(LS_INSTALLED_MAP, obj.size() == 0 ? "" : obj.toString());
		} catch (Throwable ignored) {
		}
	}

	private static String normalizeToken(final String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
	}

	private static String stripShaderSuffix(final String slugFlat) {
		if (slugFlat == null || slugFlat.isEmpty()) {
			return "";
		}
		if (slugFlat.endsWith("shaders") && slugFlat.length() > "shaders".length()) {
			return slugFlat.substring(0, slugFlat.length() - "shaders".length());
		}
		if (slugFlat.endsWith("shader") && slugFlat.length() > "shader".length()) {
			return slugFlat.substring(0, slugFlat.length() - "shader".length());
		}
		return slugFlat;
	}

	private static List<String> listResourcePackFiles() {
		File dir = new File(RESOURCEPACKS_DIR);
		String[] names = dir.list();
		if (names == null || names.length == 0) {
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>(names.length);
		for (String n : names) {
			if (n != null && !n.isEmpty()) {
				out.add(n);
			}
		}
		return out;
	}

	private static boolean trackedFileExists(final String filename) {
		if (filename == null || filename.isEmpty()) {
			return false;
		}
		String targetLow = filename.toLowerCase(Locale.ROOT);
		String targetFlat = normalizeToken(filename);
		for (WebMods.InstalledMod mod : WebMods.listMods()) {
			if (mod.file.equalsIgnoreCase(filename) || normalizeToken(mod.file).equals(targetFlat)) {
				return true;
			}
		}
		for (String sp : ShaderPacks.list()) {
			if (sp.equalsIgnoreCase(filename) || normalizeToken(sp).equals(targetFlat)
					|| (!targetLow.endsWith(".zip") && sp.equalsIgnoreCase(filename + ".zip"))) {
				return true;
			}
		}
		for (String rp : listResourcePackFiles()) {
			if (rp.equalsIgnoreCase(filename) || normalizeToken(rp).equals(targetFlat)
					|| (!targetLow.endsWith(".zip") && rp.equalsIgnoreCase(filename + ".zip"))) {
				return true;
			}
		}
		return false;
	}

	/** Forget cached/persisted Modrinth install state when a mod/pack is removed from disk. */
	public static void forgetInstalled(final String modId, final String filename) {
		ensureInstalledMapLoaded();
		String idLow = modId == null ? "" : modId.toLowerCase(Locale.ROOT);
		String idFlat = normalizeToken(modId);
		String fileLow = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
		String fileFlat = normalizeToken(filename);

		if (!idLow.isEmpty()) {
			SESSION_INSTALLED_IDS.remove(modId);
			SESSION_INSTALLED_SLUGS.remove(idLow);
		}
		if (!fileLow.isEmpty()) {
			String mappedSlug = SESSION_FILE_TO_SLUG.remove(fileLow);
			if (mappedSlug != null) {
				SESSION_INSTALLED_SLUGS.remove(mappedSlug);
			}
		}
		boolean mapChanged = false;
		Iterator<Map.Entry<String, String>> it = INSTALLED_PROJECT_FILES.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, String> e = it.next();
			String pid = e.getKey();
			String fn = e.getValue();
			boolean matchFile = !fileLow.isEmpty() && (fn.equalsIgnoreCase(filename) || normalizeToken(fn).equals(fileFlat));
			boolean matchId = !idLow.isEmpty() && (pid.equalsIgnoreCase(modId) || normalizeToken(pid).equals(idFlat));
			if (matchFile || matchId) {
				SESSION_INSTALLED_IDS.remove(pid);
				it.remove();
				mapChanged = true;
			}
		}
		if (!idFlat.isEmpty() || !fileFlat.isEmpty()) {
			Iterator<String> slugIt = SESSION_INSTALLED_SLUGS.iterator();
			while (slugIt.hasNext()) {
				String s = slugIt.next();
				String sf = normalizeToken(s);
				if ((!idFlat.isEmpty() && sf.equals(idFlat))
						|| (!fileFlat.isEmpty() && sf.length() >= 3 && fileFlat.contains(sf))) {
					slugIt.remove();
				}
			}
		}
		if (mapChanged) {
			saveInstalledMap();
		}
	}

	public static void fetchProjectNames(final List<String> ids, final Consumer<List<String>> onDone) {
		if (!jsAvailable() || ids == null || ids.isEmpty()) {
			onDone.accept(Collections.emptyList());
			return;
		}
		JsonArray arr = new JsonArray();
		for (String id : ids) {
			if (id != null && !id.isEmpty()) {
				arr.add(id);
			}
		}
		if (arr.isEmpty()) {
			onDone.accept(Collections.emptyList());
			return;
		}
		jsGetProjectNames(arr.toString(), (ok, payload) -> {
			if (!ok || payload == null || payload.isEmpty()) {
				onDone.accept(Collections.emptyList());
				return;
			}
			try {
				JsonArray outArr = JsonParser.parseString(payload).getAsJsonArray();
				List<String> names = new ArrayList<>(outArr.size());
				for (JsonElement el : outArr) {
					if (el != null && !el.isJsonNull()) {
						names.add(el.getAsString());
					}
				}
				onDone.accept(names);
			} catch (Throwable e) {
				onDone.accept(Collections.emptyList());
			}
		});
	}

	public static boolean isInstalled(final ProjectHit hit) {
		if (hit == null) {
			return false;
		}
		ensureInstalledMapLoaded();
		if (hit.id != null && !hit.id.isEmpty()) {
			String mappedFile = INSTALLED_PROJECT_FILES.get(hit.id);
			if (mappedFile != null) {
				if (trackedFileExists(mappedFile)) {
					return true;
				}
				INSTALLED_PROJECT_FILES.remove(hit.id);
				SESSION_INSTALLED_IDS.remove(hit.id);
				saveInstalledMap();
			}
		}
		if (SESSION_INSTALLED_IDS.contains(hit.id) || SESSION_INSTALLED_SLUGS.contains(hit.slug.toLowerCase(Locale.ROOT))) {
			return true;
		}
		String slugLow = hit.slug.toLowerCase(Locale.ROOT);
		String slugFlat = normalizeToken(slugLow);
		if ("shader".equals(hit.projectType)) {
			String slugBase = stripShaderSuffix(slugFlat);
			for (String s : ShaderPacks.list()) {
				String sl = normalizeToken(s);
				if ((!slugFlat.isEmpty() && sl.contains(slugFlat))
						|| (slugBase.length() >= 3 && sl.contains(slugBase))) {
					return true;
				}
			}
			return false;
		}
		if ("resourcepack".equals(hit.projectType)) {
			for (String rp : listResourcePackFiles()) {
				String rpl = normalizeToken(rp);
				if (!slugFlat.isEmpty() && rpl.contains(slugFlat)) {
					return true;
				}
			}
			return false;
		}
		for (WebMods.InstalledMod mod : WebMods.listMods()) {
			String idLow = mod.info.id.toLowerCase(Locale.ROOT);
			String fileLow = mod.file.toLowerCase(Locale.ROOT);
			if (idLow.equals(slugLow) || idLow.replace("_", "").equals(slugFlat)
					|| (!slugFlat.isEmpty() && normalizeToken(fileLow).contains(slugFlat))) {
				return true;
			}
		}
		return false;
	}

	public static int nextSearchGeneration() {
		return ++searchGeneration;
	}

	public static void search(
			final int gen,
			final String query,
			final String projectType,
			final String mcVersion,
			final String sort,
			final int offset,
			final int limit,
			final Consumer<SearchResult> onSuccess,
			final Consumer<String> onError) {
		if (!jsAvailable()) {
			onError.accept("Modrinth relay wrapper script not loaded");
			return;
		}
		jsSearch(
			query == null ? "" : query,
			projectType == null ? "mod" : projectType,
			mcVersion == null ? "any" : mcVersion,
			sort == null ? "relevance" : sort,
			offset,
			limit,
			(ok, payload) -> {
				if (gen != searchGeneration) {
					return;
				}
				if (!ok) {
					onError.accept(friendlyError(payload));
					return;
				}
				try {
					onSuccess.accept(parseSearchResult(payload));
				} catch (Throwable e) {
					onError.accept("Failed to parse Modrinth results: " + e.getMessage());
				}
			});
	}

	public static void fetchProject(
			final String idOrSlug,
			final Consumer<ProjectDetail> onSuccess,
			final Consumer<String> onError) {
		if (!jsAvailable()) {
			onError.accept("Modrinth relay wrapper script not loaded");
			return;
		}
		jsGetProject(idOrSlug, (ok, payload) -> {
			if (!ok) {
				onError.accept(friendlyError(payload));
				return;
			}
			try {
				onSuccess.accept(parseProjectDetail(payload));
			} catch (Throwable e) {
				onError.accept("Failed to parse project details: " + e.getMessage());
			}
		});
	}

	public static void fetchVersions(
			final String idOrSlug,
			final String loader,
			final Consumer<List<ProjectVersion>> onSuccess,
			final Consumer<String> onError) {
		if (!jsAvailable()) {
			onError.accept("Modrinth relay wrapper script not loaded");
			return;
		}
		jsGetVersions(idOrSlug, loader == null ? "" : loader, (ok, payload) -> {
			if (!ok) {
				onError.accept(friendlyError(payload));
				return;
			}
			try {
				onSuccess.accept(parseVersions(payload));
			} catch (Throwable e) {
				onError.accept("Failed to parse versions: " + e.getMessage());
			}
		});
	}

	@Nullable
	public static Identifier getOrRequestThumbnail(final String iconUrl, final @Nullable Runnable onLoaded) {
		if (iconUrl == null || iconUrl.isEmpty() || !jsAvailable()) {
			return null;
		}
		Identifier existing = THUMB_TEXTURES.get(iconUrl);
		if (existing != null) {
			return existing;
		}
		if (THUMB_FAILED.contains(iconUrl) || !THUMB_INFLIGHT.add(iconUrl)) {
			return null;
		}
		jsFetchThumb(iconUrl, THUMB_SIZE, (ok, rgba) -> {
			THUMB_INFLIGHT.remove(iconUrl);
			if (!ok || rgba == null || rgba.getLength() < THUMB_SIZE * THUMB_SIZE * 4) {
				THUMB_FAILED.add(iconUrl);
				return;
			}
			try {
				byte[] pixels = rgba.copyToJavaArray();
				NativeImage image = new NativeImage(THUMB_SIZE, THUMB_SIZE, false);
				int idx = 0;
				for (int y = 0; y < THUMB_SIZE; y++) {
					for (int x = 0; x < THUMB_SIZE; x++) {
						int r = pixels[idx++] & 0xFF;
						int g = pixels[idx++] & 0xFF;
						int b = pixels[idx++] & 0xFF;
						int a = pixels[idx++] & 0xFF;
						int abgr = (a << 24) | (b << 16) | (g << 8) | r;
						image.setPixelABGR(x, y, abgr);
					}
				}
				String hex = Integer.toHexString(iconUrl.hashCode()) + "_" + Integer.toHexString(iconUrl.length());
				Identifier tex = Identifier.fromNamespaceAndPath("minecraft_web", "modrinth/thumb/" + hex);
				Minecraft mc = Minecraft.getInstance();
				if (mc != null) {
					mc.getTextureManager().register(tex, new DynamicTexture(() -> "modrinth_thumb_" + hex, image));
					THUMB_TEXTURES.put(iconUrl, tex);
					if (onLoaded != null) {
						onLoaded.run();
					}
				} else {
					image.close();
				}
			} catch (Throwable e) {
				THUMB_FAILED.add(iconUrl);
			}
		});
		return null;
	}

	public interface InstallProgressListener {
		void onProgress(String label, int stepIndex, int totalSteps, int loadedBytes, int totalBytes);
	}

	private static final class PlanItem {
		final String projectId;
		final String slug;
		final String title;
		final String projectType;
		final String versionNumber;
		final String filename;
		final String fileUrl;
		final long fileSize;
		final String sha1;
		final String sha512;
		final boolean isDependency;

		PlanItem(final String projectId, final String slug, final String title, final String projectType,
				final String versionNumber, final String filename, final String fileUrl,
				final long fileSize, final String sha1, final String sha512, final boolean isDependency) {
			this.projectId = projectId;
			this.slug = slug;
			this.title = title;
			this.projectType = projectType;
			this.versionNumber = versionNumber;
			this.filename = filename;
			this.fileUrl = fileUrl;
			this.fileSize = fileSize;
			this.sha1 = sha1;
			this.sha512 = sha512;
			this.isDependency = isDependency;
		}
	}

	private static String buildInstalledTokensJson() {
		ensureInstalledMapLoaded();
		JsonArray arr = new JsonArray();
		for (String id : SESSION_INSTALLED_IDS) {
			arr.add(id);
		}
		for (String slug : SESSION_INSTALLED_SLUGS) {
			arr.add(slug);
		}
		for (Map.Entry<String, String> entry : INSTALLED_PROJECT_FILES.entrySet()) {
			if (trackedFileExists(entry.getValue())) {
				arr.add(entry.getKey());
				arr.add(entry.getValue());
			}
		}
		for (WebMods.InstalledMod mod : WebMods.listMods()) {
			if (!mod.info.id.isEmpty()) {
				arr.add(mod.info.id);
			}
			if (!mod.file.isEmpty()) {
				arr.add(mod.file);
			}
		}
		for (String sp : ShaderPacks.list()) {
			if (sp != null && !sp.isEmpty()) {
				arr.add(sp);
				String stem = sp.toLowerCase(Locale.ROOT).endsWith(".zip") ? sp.substring(0, sp.length() - 4) : sp;
				String flat = normalizeToken(stem);
				String stripped = stripShaderSuffix(flat);
				if (stripped.length() >= 3 && !stripped.equals(flat)) {
					arr.add(stripped);
				}
			}
		}
		for (String rp : listResourcePackFiles()) {
			arr.add(rp);
		}
		return arr.toString();
	}

	public static void quickInstall(
			final ProjectHit project,
			final InstallProgressListener onProgress,
			final BiConsumer<Boolean, String> onDone) {
		resolveAndInstall(project.id, project.slug, project.title, project.projectType, "", onProgress, onDone);
	}

	public static void downloadAndInstall(
			final String projectId,
			final String projectSlug,
			final String projectTitle,
			final String projectType,
			final ProjectVersion version,
			final InstallProgressListener onProgress,
			final BiConsumer<Boolean, String> onDone) {
		resolveAndInstall(projectId, projectSlug, projectTitle, projectType, version.id, onProgress, onDone);
	}

	private static void resolveAndInstall(
			final String projectId,
			final String projectSlug,
			final String projectTitle,
			final String projectType,
			final String versionId,
			final InstallProgressListener onProgress,
			final BiConsumer<Boolean, String> onDone) {
		if (!jsAvailable()) {
			onDone.accept(false, "Modrinth relay wrapper script not loaded");
			return;
		}
		String title = (projectTitle == null || projectTitle.isEmpty()) ? projectSlug : projectTitle;
		if (onProgress != null) {
			onProgress.onProgress("Resolving dependencies for " + title + "...", 0, 1, 0, 0);
		}
		String loader = "mod".equals(projectType) ? "fabric" : "";
		jsResolveInstallPlan(
			projectId, projectSlug, title, projectType, versionId == null ? "" : versionId,
			loader, buildInstalledTokensJson(),
			(ok, payload) -> {
				if (!ok) {
					onDone.accept(false, friendlyError(payload));
					return;
				}
				try {
					JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
					JsonArray arr = root.has("items") && root.get("items").isJsonArray()
						? root.getAsJsonArray("items") : new JsonArray();
					List<PlanItem> items = new ArrayList<>();
					for (JsonElement el : arr) {
						if (!el.isJsonObject()) {
							continue;
						}
						JsonObject o = el.getAsJsonObject();
						items.add(new PlanItem(
							strOf(o, "projectId"),
							strOf(o, "slug"),
							strOf(o, "title"),
							strOf(o, "projectType"),
							strOf(o, "versionNumber"),
							strOf(o, "filename"),
							strOf(o, "fileUrl"),
							longOf(o, "fileSize"),
							strOf(o, "sha1"),
							strOf(o, "sha512"),
							boolOf(o, "isDependency")));
					}
					if (items.isEmpty()) {
						onDone.accept(false, "No downloadable files found for " + title);
						return;
					}
					List<String> installedDepNames = new ArrayList<>();
					List<String> depFailures = new ArrayList<>();
					if (items.size() > 1) {
						executeInstallPlanParallel(items, title, installedDepNames, depFailures, onProgress, onDone);
					} else {
						executeInstallPlan(items, 0, installedDepNames, depFailures, onProgress, onDone);
					}
				} catch (Throwable e) {
					onDone.accept(false, "Dependency resolution parse error: " + e.getMessage());
				}
			});
	}

	private static void executeInstallPlanParallel(
			final List<PlanItem> items,
			final String rootTitle,
			final List<String> installedDepNames,
			final List<String> depFailures,
			final InstallProgressListener onProgress,
			final BiConsumer<Boolean, String> onDone) {
		JsonArray reqArr = new JsonArray();
		long totalEst = 0L;
		for (PlanItem item : items) {
			JsonObject o = new JsonObject();
			o.addProperty("fileUrl", item.fileUrl);
			o.addProperty("filename", item.filename);
			o.addProperty("title", item.title);
			o.addProperty("fileSize", item.fileSize);
			o.addProperty("sha1", item.sha1);
			o.addProperty("sha512", item.sha512);
			reqArr.add(o);
			if (item.fileSize > 0L) {
				totalEst += item.fileSize;
			}
		}
		final int totalSteps = items.size();
		final int estCap = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, totalEst));
		if (onProgress != null) {
			onProgress.onProgress(rootTitle + " + " + (totalSteps - 1) + " dep(s)", 1, totalSteps, 0, estCap);
		}
		jsDownloadFilesBatch(
			reqArr.toString(),
			(doneCount, totalCount, loaded, total) -> {
				if (onProgress != null) {
					int stepShown = Math.min(totalSteps, Math.max(1, doneCount + 1));
					int tot = total > 0 ? total : estCap;
					onProgress.onProgress(rootTitle + " (" + doneCount + "/" + totalCount + " files)", stepShown, totalSteps, loaded, tot);
				}
			},
			batchId -> {
				String fatalErr = null;
				PlanItem rootItem = items.get(items.size() - 1);
				WebMods.beginBatch();
				try {
					ensureInstalledMapLoaded();
					boolean mapDirty = false;
					for (int i = 0; i < items.size(); i++) {
						PlanItem item = items.get(i);
						boolean ok = jsBatchResultOk(batchId, i);
						Int8Array data = ok ? jsBatchResultBytes(batchId, i) : null;
						String dlErr = ok ? "" : jsBatchResultErr(batchId, i);
						String err = null;
						if (!ok || data == null || data.getLength() == 0) {
							err = (dlErr != null && !dlErr.isEmpty())
								? friendlyError(dlErr)
								: ("Download failed for " + item.filename);
						} else {
							try {
								byte[] raw = data.copyToJavaArray();
								if ("shader".equals(item.projectType)) {
									err = ShaderPacks.accept(item.filename, raw);
								} else if ("resourcepack".equals(item.projectType)) {
									err = WebPacks.installResourcePack(item.filename, raw);
								} else {
									err = WebMods.accept(item.filename, raw);
								}
							} catch (Throwable e) {
								err = "Install error: " + e.getMessage();
							}
						}
						if (err != null && !err.isEmpty()) {
							if (item.isDependency && i + 1 < totalSteps) {
								depFailures.add(item.title);
								continue;
							}
							fatalErr = err;
							break;
						}
						if (!item.projectId.isEmpty()) {
							SESSION_INSTALLED_IDS.add(item.projectId);
							if (!item.filename.isEmpty()) {
								INSTALLED_PROJECT_FILES.put(item.projectId, item.filename);
								mapDirty = true;
							}
						}
						if (!item.slug.isEmpty()) {
							String slugLow = item.slug.toLowerCase(Locale.ROOT);
							SESSION_INSTALLED_SLUGS.add(slugLow);
							if (!item.filename.isEmpty()) {
								SESSION_FILE_TO_SLUG.put(item.filename.toLowerCase(Locale.ROOT), slugLow);
							}
						}
						if (item.isDependency) {
							installedDepNames.add(item.title);
						}
					}
					if (mapDirty) {
						saveInstalledMap();
					}
				} finally {
					jsClearBatch(batchId);
					WebMods.endBatch();
				}
				if (fatalErr != null) {
					onDone.accept(false, fatalErr);
					return;
				}
				String baseMsg = "shader".equals(rootItem.projectType)
					? "Added shader pack " + rootItem.title + " (enable in Video Settings > Shader Packs)"
					: ("resourcepack".equals(rootItem.projectType)
						? "Installed & enabled resource pack " + rootItem.title
						: "Installed " + rootItem.title + (rootItem.versionNumber.isEmpty() ? "" : (" (" + rootItem.versionNumber + ")")));
				if (!installedDepNames.isEmpty()) {
					baseMsg += " + " + installedDepNames.size()
						+ (installedDepNames.size() == 1 ? " dep (" : " deps (")
						+ joinShort(installedDepNames, 2) + ")";
				}
				if (!depFailures.isEmpty()) {
					baseMsg += " [failed dep: " + joinShort(depFailures, 2) + "]";
				}
				onDone.accept(true, baseMsg);
			});
	}

	private static void executeInstallPlan(
			final List<PlanItem> items,
			final int index,
			final List<String> installedDepNames,
			final List<String> depFailures,
			final InstallProgressListener onProgress,
			final BiConsumer<Boolean, String> onDone) {
		PlanItem item = items.get(index);
		int totalSteps = items.size();
		int stepNum = index + 1;
		String labelPrefix = item.isDependency ? ("dependency " + item.title) : item.title;
		if (onProgress != null) {
			onProgress.onProgress(labelPrefix, stepNum, totalSteps, 0, (int) Math.min(Integer.MAX_VALUE, Math.max(0L, item.fileSize)));
		}
		jsDownloadFile(
			item.fileUrl,
			item.sha1,
			item.sha512,
			(loaded, total) -> {
				if (onProgress != null) {
					int tot = total > 0 ? total : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, item.fileSize));
					onProgress.onProgress(labelPrefix, stepNum, totalSteps, loaded, tot);
				}
			},
			(ok, data, dlErr) -> {
				String err = null;
				if (!ok || data == null || data.getLength() == 0) {
					err = (dlErr != null && !dlErr.isEmpty())
						? friendlyError(dlErr)
						: ("Download failed for " + item.filename);
				} else {
					try {
						byte[] raw = data.copyToJavaArray();
						if ("shader".equals(item.projectType)) {
							err = ShaderPacks.accept(item.filename, raw);
						} else if ("resourcepack".equals(item.projectType)) {
							err = WebPacks.installResourcePack(item.filename, raw);
						} else {
							err = WebMods.accept(item.filename, raw);
						}
					} catch (Throwable e) {
						err = "Install error: " + e.getMessage();
					}
				}
				if (err != null && !err.isEmpty()) {
					if (item.isDependency && index + 1 < totalSteps) {
						depFailures.add(item.title);
						executeInstallPlan(items, index + 1, installedDepNames, depFailures, onProgress, onDone);
						return;
					}
					onDone.accept(false, err);
					return;
				}
				ensureInstalledMapLoaded();
				if (!item.projectId.isEmpty()) {
					SESSION_INSTALLED_IDS.add(item.projectId);
					if (!item.filename.isEmpty()) {
						INSTALLED_PROJECT_FILES.put(item.projectId, item.filename);
						saveInstalledMap();
					}
				}
				if (!item.slug.isEmpty()) {
					String slugLow = item.slug.toLowerCase(Locale.ROOT);
					SESSION_INSTALLED_SLUGS.add(slugLow);
					if (!item.filename.isEmpty()) {
						SESSION_FILE_TO_SLUG.put(item.filename.toLowerCase(Locale.ROOT), slugLow);
					}
				}
				if (item.isDependency) {
					installedDepNames.add(item.title);
				}
				if (index + 1 < totalSteps) {
					executeInstallPlan(items, index + 1, installedDepNames, depFailures, onProgress, onDone);
					return;
				}
				String baseMsg = "shader".equals(item.projectType)
					? "Added shader pack " + item.title + " (enable in Video Settings > Shader Packs)"
					: ("resourcepack".equals(item.projectType)
						? "Installed & enabled resource pack " + item.title
						: "Installed " + item.title + (item.versionNumber.isEmpty() ? "" : (" (" + item.versionNumber + ")")));
				if (!installedDepNames.isEmpty()) {
					baseMsg += " + " + installedDepNames.size()
						+ (installedDepNames.size() == 1 ? " dep (" : " deps (")
						+ joinShort(installedDepNames, 2) + ")";
				}
				if (!depFailures.isEmpty()) {
					baseMsg += " [failed dep: " + joinShort(depFailures, 2) + "]";
				}
				onDone.accept(true, baseMsg);
			});
	}

	private static String joinShort(final List<String> names, final int maxShow) {
		StringBuilder sb = new StringBuilder();
		int limit = Math.min(names.size(), maxShow);
		for (int i = 0; i < limit; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(names.get(i));
		}
		if (names.size() > maxShow) {
			sb.append(" +").append(names.size() - maxShow);
		}
		return sb.toString();
	}

	public static String formatCount(final long n) {
		if (n >= 1_000_000L) {
			long tenths = n / 100_000L;
			return (tenths / 10L) + "." + (tenths % 10L) + "M";
		}
		if (n >= 1_000L) {
			long tenths = n / 100L;
			return (tenths / 10L) + "." + (tenths % 10L) + "K";
		}
		return Long.toString(Math.max(0L, n));
	}

	private static String capitalize(final String s) {
		if (s == null || s.isEmpty()) {
			return "";
		}
		String clean = s.replace('-', ' ').replace('_', ' ');
		return Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
	}

	private static String friendlyError(final String raw) {
		if (raw == null || raw.isEmpty()) {
			return "Relay request failed";
		}
		if (raw.contains("NO_RELAY")) {
			return "No Relay configured - open Relays to add or select a relay";
		}
		return raw.length() > 140 ? raw.substring(0, 140) + "..." : raw;
	}

	// ---- JSON Parsers (Gson) ----

	private static SearchResult parseSearchResult(final String json) {
		JsonObject root = JsonParser.parseString(json).getAsJsonObject();
		List<ProjectHit> hits = new ArrayList<>();
		JsonArray arr = root.has("hits") && root.get("hits").isJsonArray() ? root.getAsJsonArray("hits") : null;
		if (arr != null) {
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject o = el.getAsJsonObject();
				List<String> cats = strList(o, "displayCategories");
				if (cats.isEmpty()) {
					cats = strList(o, "categories");
				}
				hits.add(new ProjectHit(
					strOf(o, "id"),
					strOf(o, "slug"),
					strOf(o, "title"),
					strOf(o, "description"),
					strOf(o, "author"),
					strOf(o, "projectType"),
					strOf(o, "iconUrl"),
					longOf(o, "downloads"),
					longOf(o, "follows"),
					strOf(o, "latestVersion"),
					strOf(o, "clientSide"),
					strOf(o, "serverSide"),
					cats,
					strList(o, "versions")));
			}
		}
		return new SearchResult(
			hits,
			intOf(root, "offset"),
			intOf(root, "limit"),
			intOf(root, "totalHits"),
			strOf(root, "relayLabel"),
			strOf(root, "transport"));
	}

	private static ProjectDetail parseProjectDetail(final String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		return new ProjectDetail(
			strOf(o, "id"),
			strOf(o, "slug"),
			strOf(o, "title"),
			strOf(o, "description"),
			strOf(o, "body"),
			strOf(o, "projectType"),
			strOf(o, "iconUrl"),
			longOf(o, "downloads"),
			longOf(o, "followers"),
			strOf(o, "license"),
			strOf(o, "clientSide"),
			strOf(o, "serverSide"),
			strList(o, "categories"),
			strList(o, "loaders"),
			strList(o, "gameVersions"));
	}

	private static List<ProjectVersion> parseVersions(final String json) {
		JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
		List<ProjectVersion> out = new ArrayList<>();
		for (JsonElement el : arr) {
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject o = el.getAsJsonObject();
			List<String> reqDepIds = new ArrayList<>();
			if (o.has("dependencies") && o.get("dependencies").isJsonArray()) {
				for (JsonElement de : o.getAsJsonArray("dependencies")) {
					if (de != null && de.isJsonObject()) {
						JsonObject dObj = de.getAsJsonObject();
						if ("required".equals(strOf(dObj, "dependencyType"))) {
							String pid = strOf(dObj, "projectId");
							if (!pid.isEmpty() && !reqDepIds.contains(pid)) {
								reqDepIds.add(pid);
							}
						}
					}
				}
			}
			out.add(new ProjectVersion(
				strOf(o, "id"),
				strOf(o, "name"),
				strOf(o, "versionNumber"),
				strOf(o, "versionType"),
				strOf(o, "datePublished"),
				longOf(o, "downloads"),
				strList(o, "gameVersions"),
				strList(o, "loaders"),
				strOf(o, "fileUrl"),
				strOf(o, "filename"),
				longOf(o, "fileSize"),
				strOf(o, "sha1"),
				intOf(o, "requiredDepCount"),
				reqDepIds));
		}
		return out;
	}

	private static String strOf(final JsonObject o, final String k) {
		JsonElement e = o.get(k);
		return (e != null && !e.isJsonNull()) ? e.getAsString() : "";
	}

	private static int intOf(final JsonObject o, final String k) {
		JsonElement e = o.get(k);
		return (e != null && !e.isJsonNull()) ? e.getAsInt() : 0;
	}

	private static long longOf(final JsonObject o, final String k) {
		JsonElement e = o.get(k);
		return (e != null && !e.isJsonNull()) ? e.getAsLong() : 0L;
	}

	private static boolean boolOf(final JsonObject o, final String k) {
		JsonElement e = o.get(k);
		return e != null && !e.isJsonNull() && e.getAsBoolean();
	}

	private static List<String> strList(final JsonObject o, final String k) {
		JsonElement e = o.get(k);
		if (e == null || !e.isJsonArray()) {
			return Collections.emptyList();
		}
		JsonArray a = e.getAsJsonArray();
		List<String> out = new ArrayList<>(a.size());
		for (JsonElement item : a) {
			if (item != null && !item.isJsonNull()) {
				out.add(item.getAsString());
			}
		}
		return out;
	}

	// ---- Strict ES5 @JSBody bindings ----

	@JSFunctor
	private interface TextCallback extends JSObject {
		void done(boolean ok, String payload);
	}

	@JSFunctor
	private interface BytesCallback extends JSObject {
		void done(boolean ok, Int8Array data);
	}

	@JSFunctor
	private interface DownloadCallback extends JSObject {
		void done(boolean ok, Int8Array data, String err);
	}

	@JSFunctor
	private interface ProgressCallback extends JSObject {
		void progress(int loaded, int total);
	}

	@JSFunctor
	private interface BatchProgressCallback extends JSObject {
		void progress(int completed, int totalItems, int loaded, int total);
	}

	@JSFunctor
	private interface BatchDoneCallback extends JSObject {
		void done(int batchId);
	}

	@JSBody(params = {"k"}, script = "try { return window.localStorage.getItem(k) || ''; } catch (e) { return ''; }")
	private static native String jsLsGet(String k);

	@JSBody(params = {"k", "v"}, script = "try { if (!v) { window.localStorage.removeItem(k); } else { window.localStorage.setItem(k, v); } } catch (e) {}")
	private static native void jsLsSet(String k, String v);

	@JSBody(script = "return !!(window.MinecraftWebModrinth && typeof window.MinecraftWebModrinth.search === 'function');")
	private static native boolean jsAvailable();

	@JSBody(script = "try { return JSON.stringify(window.MinecraftWebModrinth.getRelayStatus()); } catch (e) { return '{}'; }")
	private static native String jsRelayStatusJson();

	@JSBody(script = "try { return String(window.MinecraftWebModrinth.cycleRouteMode()); } catch (e) { return 'auto'; }")
	private static native String jsCycleRouteMode();

	@JSBody(params = {"idsJson", "cb"}, script = ""
		+ "var m = window.MinecraftWebModrinth;"
		+ "if (!m || typeof m.getProjectNames !== 'function') { cb(false, '[]'); return; }"
		+ "var ids = []; try { ids = JSON.parse(idsJson || '[]'); } catch (e) {}"
		+ "m.getProjectNames(ids).then(function(res) { cb(true, JSON.stringify(res || [])); }, function() { cb(false, '[]'); });")
	private static native void jsGetProjectNames(String idsJson, TextCallback cb);

	@JSBody(params = {"query", "projectType", "mcVersion", "sort", "offset", "limit", "cb"}, script = ""
		+ "var m = window.MinecraftWebModrinth;"
		+ "if (!m) { cb(false, 'Modrinth wrapper unavailable'); return; }"
		+ "m.search({ query: query, projectType: projectType, mcVersion: mcVersion, sort: sort, offset: offset, limit: limit })"
		+ ".then(function(res) { cb(true, JSON.stringify(res)); }, function(err) { cb(false, String(err && err.message ? err.message : err)); });")
	private static native void jsSearch(String query, String projectType, String mcVersion, String sort, int offset, int limit, TextCallback cb);

	@JSBody(params = {"idOrSlug", "cb"}, script = ""
		+ "var m = window.MinecraftWebModrinth;"
		+ "if (!m) { cb(false, 'Modrinth wrapper unavailable'); return; }"
		+ "m.getProject(idOrSlug)"
		+ ".then(function(res) { cb(true, JSON.stringify(res)); }, function(err) { cb(false, String(err && err.message ? err.message : err)); });")
	private static native void jsGetProject(String idOrSlug, TextCallback cb);

	@JSBody(params = {"idOrSlug", "loader", "cb"}, script = ""
		+ "var m = window.MinecraftWebModrinth;"
		+ "if (!m) { cb(false, 'Modrinth wrapper unavailable'); return; }"
		+ "m.getVersions(idOrSlug, loader ? { loader: loader } : {})"
		+ ".then(function(res) { cb(true, JSON.stringify(res)); }, function(err) { cb(false, String(err && err.message ? err.message : err)); });")
	private static native void jsGetVersions(String idOrSlug, String loader, TextCallback cb);

	@JSBody(params = {"projectId", "slug", "title", "projectType", "versionId", "loader", "installedJson", "cb"}, script = ""
		+ "var m = window.MinecraftWebModrinth;"
		+ "if (!m || typeof m.resolveInstallPlan !== 'function') { cb(false, 'Modrinth dependency resolver unavailable'); return; }"
		+ "var inst = []; try { inst = JSON.parse(installedJson || '[]'); } catch (e) {}"
		+ "m.resolveInstallPlan({ projectId: projectId, slug: slug, title: title, projectType: projectType, versionId: versionId, loader: loader, installed: inst })"
		+ ".then(function(res) { cb(true, JSON.stringify(res)); }, function(err) { cb(false, String(err && err.message ? err.message : err)); });")
	private static native void jsResolveInstallPlan(
		String projectId, String slug, String title, String projectType,
		String versionId, String loader, String installedJson, TextCallback cb);

	@JSBody(params = {"url", "size", "cb"}, script = ""
		+ "var m = window.MinecraftWebModrinth;"
		+ "if (!m) { cb(false, null); return; }"
		+ "m.fetchThumbnailRgba(url, size)"
		+ ".then(function(rgba) { cb(true, rgba); }, function() { cb(false, null); });")
	private static native void jsFetchThumb(String url, int size, BytesCallback cb);

	@JSBody(params = {"url", "sha1", "sha512", "onProg", "cb"}, script = ""
		+ "var m = window.MinecraftWebModrinth;"
		+ "if (!m) { cb(false, null, 'Modrinth wrapper unavailable'); return; }"
		+ "var lastAt = 0;"
		+ "m.downloadFile(url, function(loaded, total) {"
		+ "  var now = Date.now();"
		+ "  if (now - lastAt >= 80 || (total > 0 && loaded >= total)) {"
		+ "    lastAt = now; onProg(loaded | 0, total | 0);"
		+ "  }"
		+ "}, sha1 || '', sha512 || '').then(function(bytes) { cb(true, bytes, ''); }, function(err) { cb(false, null, String(err && err.message ? err.message : err)); });")
	private static native void jsDownloadFile(String url, String sha1, String sha512, ProgressCallback onProg, DownloadCallback cb);

	@JSBody(params = {"itemsJson", "onProg", "cb"}, script = ""
		+ "var store = window.__mcModrinthDlBatches || (window.__mcModrinthDlBatches = {});"
		+ "var seq = (window.__mcModrinthDlSeq || 0) + 1;"
		+ "window.__mcModrinthDlSeq = seq;"
		+ "var m = window.MinecraftWebModrinth;"
		+ "var items = []; try { items = JSON.parse(itemsJson || '[]'); } catch (e) {}"
		+ "if (!m || typeof m.downloadFiles !== 'function') {"
		+ "  store[seq] = []; cb(seq); return;"
		+ "}"
		+ "m.downloadFiles(items, function(doneCount, totalCount, loaded, total) {"
		+ "  onProg(doneCount | 0, totalCount | 0, loaded | 0, total | 0);"
		+ "}).then(function(results) {"
		+ "  store[seq] = results || [];"
		+ "  cb(seq);"
		+ "}, function(err) {"
		+ "  var msg = String(err && err.message ? err.message : err);"
		+ "  var failArr = [];"
		+ "  for (var i = 0; i < items.length; i++) { failArr.push({ ok: false, bytes: null, error: msg }); }"
		+ "  store[seq] = failArr;"
		+ "  cb(seq);"
		+ "});")
	private static native void jsDownloadFilesBatch(String itemsJson, BatchProgressCallback onProg, BatchDoneCallback cb);

	@JSBody(params = {"batchId", "index"}, script = "var b = window.__mcModrinthDlBatches && window.__mcModrinthDlBatches[batchId]; return !!(b && b[index] && b[index].ok);")
	private static native boolean jsBatchResultOk(int batchId, int index);

	@JSBody(params = {"batchId", "index"}, script = "var b = window.__mcModrinthDlBatches && window.__mcModrinthDlBatches[batchId]; return (b && b[index] && b[index].bytes) ? b[index].bytes : null;")
	private static native Int8Array jsBatchResultBytes(int batchId, int index);

	@JSBody(params = {"batchId", "index"}, script = "var b = window.__mcModrinthDlBatches && window.__mcModrinthDlBatches[batchId]; return (b && b[index] && b[index].error) ? String(b[index].error) : '';")
	private static native String jsBatchResultErr(int batchId, int index);

	@JSBody(params = {"batchId"}, script = "if (window.__mcModrinthDlBatches) { delete window.__mcModrinthDlBatches[batchId]; }")
	private static native void jsClearBatch(int batchId);
}
