// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

	private static final Map<String, Identifier> THUMB_TEXTURES = new HashMap<>();
	private static final Set<String> THUMB_INFLIGHT = new HashSet<>();
	private static final Set<String> THUMB_FAILED = new HashSet<>();
	private static final Set<String> SESSION_INSTALLED_IDS = new HashSet<>();
	private static final Set<String> SESSION_INSTALLED_SLUGS = new HashSet<>();

	private static int searchGeneration;

	private ModrinthClient() {
	}

	// ---- Public DTOs ----

	public static final class RelayInfo {
		public final boolean ready;
		public final int relayCount;
		public final String relayLabel;
		public final String transport;

		RelayInfo(final boolean ready, final int relayCount, final String relayLabel, final String transport) {
			this.ready = ready;
			this.relayCount = relayCount;
			this.relayLabel = relayLabel;
			this.transport = transport;
		}

		public String badgeText() {
			if (this.relayCount <= 0) {
				return "No Relay";
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

		ProjectVersion(
				final String id, final String name, final String versionNumber, final String versionType,
				final String datePublished, final long downloads, final List<String> gameVersions,
				final List<String> loaders, final String fileUrl, final String filename,
				final long fileSize, final String sha1, final int requiredDepCount) {
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
			return new RelayInfo(false, 0, "", "");
		}
		try {
			String raw = jsRelayStatusJson();
			JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
			return new RelayInfo(
				boolOf(obj, "ready"),
				intOf(obj, "relayCount"),
				strOf(obj, "relayLabel"),
				strOf(obj, "transport"));
		} catch (Throwable e) {
			return new RelayInfo(false, 0, "", "");
		}
	}

	public static boolean isInstalled(final ProjectHit hit) {
		if (hit == null) {
			return false;
		}
		if (SESSION_INSTALLED_IDS.contains(hit.id) || SESSION_INSTALLED_SLUGS.contains(hit.slug.toLowerCase(Locale.ROOT))) {
			return true;
		}
		String slugLow = hit.slug.toLowerCase(Locale.ROOT);
		String slugFlat = slugLow.replace("-", "").replace("_", "");
		if ("shader".equals(hit.projectType)) {
			for (String s : ShaderPacks.list()) {
				String sl = s.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
				if (!slugFlat.isEmpty() && sl.contains(slugFlat)) {
					return true;
				}
			}
			return false;
		}
		for (WebMods.InstalledMod mod : WebMods.listMods()) {
			String idLow = mod.info.id.toLowerCase(Locale.ROOT);
			String fileLow = mod.file.toLowerCase(Locale.ROOT);
			if (idLow.equals(slugLow) || idLow.replace("_", "").equals(slugFlat)
					|| (!slugFlat.isEmpty() && fileLow.replace("-", "").replace("_", "").contains(slugFlat))) {
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
		final boolean isDependency;

		PlanItem(final String projectId, final String slug, final String title, final String projectType,
				final String versionNumber, final String filename, final String fileUrl,
				final long fileSize, final boolean isDependency) {
			this.projectId = projectId;
			this.slug = slug;
			this.title = title;
			this.projectType = projectType;
			this.versionNumber = versionNumber;
			this.filename = filename;
			this.fileUrl = fileUrl;
			this.fileSize = fileSize;
			this.isDependency = isDependency;
		}
	}

	private static String buildInstalledTokensJson() {
		JsonArray arr = new JsonArray();
		for (String id : SESSION_INSTALLED_IDS) {
			arr.add(id);
		}
		for (String slug : SESSION_INSTALLED_SLUGS) {
			arr.add(slug);
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
			}
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
							boolOf(o, "isDependency")));
					}
					if (items.isEmpty()) {
						onDone.accept(false, "No downloadable files found for " + title);
						return;
					}
					List<String> installedDepNames = new ArrayList<>();
					List<String> depFailures = new ArrayList<>();
					executeInstallPlan(items, 0, installedDepNames, depFailures, onProgress, onDone);
				} catch (Throwable e) {
					onDone.accept(false, "Dependency resolution parse error: " + e.getMessage());
				}
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
			(loaded, total) -> {
				if (onProgress != null) {
					int tot = total > 0 ? total : (int) Math.min(Integer.MAX_VALUE, Math.max(0L, item.fileSize));
					onProgress.onProgress(labelPrefix, stepNum, totalSteps, loaded, tot);
				}
			},
			(ok, data) -> {
				String err = null;
				if (!ok || data == null || data.getLength() == 0) {
					err = "Download failed for " + item.filename;
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
				if (!item.projectId.isEmpty()) {
					SESSION_INSTALLED_IDS.add(item.projectId);
				}
				if (!item.slug.isEmpty()) {
					SESSION_INSTALLED_SLUGS.add(item.slug.toLowerCase(Locale.ROOT));
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
				intOf(o, "requiredDepCount")));
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
	private interface ProgressCallback extends JSObject {
		void progress(int loaded, int total);
	}

	@JSBody(script = "return !!(window.MinecraftWebModrinth && typeof window.MinecraftWebModrinth.search === 'function');")
	private static native boolean jsAvailable();

	@JSBody(script = "try { return JSON.stringify(window.MinecraftWebModrinth.getRelayStatus()); } catch (e) { return '{}'; }")
	private static native String jsRelayStatusJson();

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

	@JSBody(params = {"url", "onProg", "cb"}, script = ""
		+ "var m = window.MinecraftWebModrinth;"
		+ "if (!m) { cb(false, null); return; }"
		+ "var lastAt = 0;"
		+ "m.downloadFile(url, function(loaded, total) {"
		+ "  var now = Date.now();"
		+ "  if (now - lastAt >= 80 || (total > 0 && loaded >= total)) {"
		+ "    lastAt = now; onProg(loaded | 0, total | 0);"
		+ "  }"
		+ "}).then(function(bytes) { cb(true, bytes); }, function() { cb(false, null); });")
	private static native void jsDownloadFile(String url, ProgressCallback onProg, BytesCallback cb);
}
