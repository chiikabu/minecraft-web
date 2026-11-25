package net.minecraft.client.web.mods;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Pure-Java inspector and pack extractor for browser mods ({@code .js}, {@code .mcmod.zip},
 * {@code .jar}).
 *
 * <p>Deliberately carries ZERO TeaVM, Minecraft, or third-party imports so it can be compiled and
 * unit-tested directly on JDK 25 ({@code scripts/ModJarInspectorSelfCheck.java}) as well as linked
 * into the TeaVM browser build.
 */
public final class ModJarInspector {
	private static final int MAX_NESTED_JARS = 32;
	private static final int MAX_HEADER_SCAN_BYTES = 4096;

	private ModJarInspector() {
	}

	/** High-level classification of an uploaded or stored mod artifact. */
	public enum ModKind {
		JS_SCRIPT("[JS]", 0xFF55FF55, true),
		JS_BUNDLE("[JS]", 0xFF55FF55, true),
		DATA_ASSET_MOD("[Data/Assets]", 0xFF55FFFF, true),
		FABRIC_NEEDS_BAKE("[Fabric - Needs Bake]", 0xFFFFAA00, false),
		FORGE_UNSUPPORTED("[Forge - Unsupported]", 0xFFFF5555, false),
		UNKNOWN_JAR("[JAR - Unsupported]", 0xFFA0A0A0, false),
		INVALID("[Invalid]", 0xFFFF5555, false);

		private final String badge;
		private final int badgeColor;
		private final boolean runnableInBrowser;

		ModKind(final String badge, final int badgeColor, final boolean runnableInBrowser) {
			this.badge = badge;
			this.badgeColor = badgeColor;
			this.runnableInBrowser = runnableInBrowser;
		}

		public String badge() {
			return this.badge;
		}

		public int badgeColor() {
			return this.badgeColor;
		}

		public boolean canRunInBrowser() {
			return this.runnableInBrowser;
		}

		public boolean isJavaScript() {
			return this == JS_SCRIPT || this == JS_BUNDLE;
		}

		public boolean isDataAssetMod() {
			return this == DATA_ASSET_MOD;
		}
	}

	/** Immutable inspection report produced by {@link #inspect(String, byte[])}. */
	public static final class ModInfo {
		public final String filename;
		public final ModKind kind;
		public final String id;
		public final String name;
		public final String version;
		public final String description;
		public final String author;
		public final String loader;
		public final String jsEntrypoint;
		public final int classCount;
		public final int assetFileCount;
		public final int dataFileCount;
		public final int nestedJarCount;
		public final boolean hasPackMcmeta;
		public final boolean hasMixins;
		public final List<String> entrypoints;
		public final String compatibilitySummary;

		public ModInfo(
				final String filename,
				final ModKind kind,
				final String id,
				final String name,
				final String version,
				final String description,
				final String author,
				final String loader,
				final String jsEntrypoint,
				final int classCount,
				final int assetFileCount,
				final int dataFileCount,
				final int nestedJarCount,
				final boolean hasPackMcmeta,
				final boolean hasMixins,
				final List<String> entrypoints,
				final String compatibilitySummary) {
			this.filename = filename == null ? "" : filename;
			this.kind = kind;
			this.id = id == null ? "" : id;
			this.name = name == null ? "" : name;
			this.version = version == null ? "" : version;
			this.description = description == null ? "" : description;
			this.author = author == null ? "" : author;
			this.loader = loader == null ? "unknown" : loader;
			this.jsEntrypoint = jsEntrypoint == null ? "" : jsEntrypoint;
			this.classCount = classCount;
			this.assetFileCount = assetFileCount;
			this.dataFileCount = dataFileCount;
			this.nestedJarCount = nestedJarCount;
			this.hasPackMcmeta = hasPackMcmeta;
			this.hasMixins = hasMixins;
			this.entrypoints = entrypoints == null
				? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(entrypoints));
			this.compatibilitySummary = compatibilitySummary == null ? "" : compatibilitySummary;
		}

		public boolean canRunInBrowser() {
			return this.kind.canRunInBrowser();
		}

		public boolean isJavaScript() {
			return this.kind.isJavaScript();
		}

		public boolean hasAssets() {
			return this.assetFileCount > 0;
		}

		public boolean hasData() {
			return this.dataFileCount > 0;
		}

		public boolean canExtractPack() {
			return this.kind != ModKind.INVALID && (this.assetFileCount > 0 || this.dataFileCount > 0);
		}

		public String displayTitle() {
			if (this.version.isEmpty()) {
				return this.name;
			}
			return this.name + " " + this.version;
		}
	}

	// ---- Public inspection API ----

	/** Inspect a {@code .js}, {@code .mjs}, {@code .mcmod.zip}, {@code .zip}, or {@code .jar} mod. */
	public static ModInfo inspect(final String rawFilename, final byte[] bytes) {
		String filename = rawFilename == null ? "" : rawFilename.trim();
		String fallbackName = stripExtension(filename.isEmpty() ? "unnamed" : filename);
		String fallbackId = slugify(fallbackName);

		if (bytes == null || bytes.length == 0) {
			return invalid(filename, fallbackId, fallbackName, "Empty file.");
		}

		String lower = filename.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
			return inspectJsScript(filename, fallbackId, fallbackName, bytes);
		}

		if (!isZipMagic(bytes)) {
			// Allow extensionless plain text only if explicitly .js/.mjs; otherwise reject cleanly.
			return invalid(filename, fallbackId, fallbackName, "Not a valid .js script or .jar/.zip archive.");
		}

		Map<String, byte[]> rawEntries = unzipSafe(bytes);
		if (rawEntries == null || rawEntries.isEmpty()) {
			return invalid(filename, fallbackId, fallbackName, "Unreadable or empty .jar/.zip archive.");
		}

		String rootPrefix = detectOptionalSingleRoot(rawEntries);
		Map<String, byte[]> entries = stripRootPrefix(rawEntries, rootPrefix);

		// 1. Check for JS bundle (.mcmod.zip or zip with eagler.mod.json / mod.json + JS)
		ModInfo jsBundle = tryInspectJsBundle(filename, fallbackId, fallbackName, lower, entries, rootPrefix);
		if (jsBundle != null) {
			return jsBundle;
		}

		// 2. Inspect archive structure (classes, nested jars, assets, data, mixins, loader descriptors)
		int classCount = 0;
		int assetCount = 0;
		int dataCount = 0;
		int[] nestedJarCountRef = new int[]{0};
		NestedJarStats nestedStats = new NestedJarStats();
		byte[] packMcmetaBytes = null;
		boolean hasMixins = false;

		byte[] fabricJson = null;
		byte[] quiltJson = null;
		byte[] neoforgeToml = null;
		byte[] forgeToml = null;
		byte[] mcmodInfo = null;
		boolean hasFmlAnnotation = false;

		for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
			String path = entry.getKey();
			String pathLower = path.toLowerCase(Locale.ROOT);

			if (path.equals("fabric.mod.json")) {
				fabricJson = entry.getValue();
			} else if (path.equals("quilt.mod.json")) {
				quiltJson = entry.getValue();
			} else if (path.equals("META-INF/neoforge.mods.toml")) {
				neoforgeToml = entry.getValue();
			} else if (path.equals("META-INF/mods.toml")) {
				forgeToml = entry.getValue();
			} else if (path.equals("mcmod.info")) {
				mcmodInfo = entry.getValue();
			} else if (path.equals("META-INF/fml_cache_annotation.json")) {
				hasFmlAnnotation = true;
			} else if (path.equals("pack.mcmeta")) {
				packMcmetaBytes = entry.getValue();
			}

			if (pathLower.endsWith(".class") && !isNonExecutableClass(pathLower)) {
				classCount++;
			} else if (path.startsWith("assets/") && path.length() > "assets/".length()) {
				assetCount++;
			} else if (path.startsWith("data/") && path.length() > "data/".length()) {
				dataCount++;
			} else if (pathLower.startsWith("meta-inf/jars/") && pathLower.endsWith(".jar")) {
				nestedJarCountRef[0]++;
				if (nestedJarCountRef[0] <= MAX_NESTED_JARS) {
					inspectNestedJar(entry.getValue(), nestedStats, nestedJarCountRef, 1);
				}
			}

			if ((pathLower.endsWith(".mixins.json") || pathLower.contains("mixins.") && pathLower.endsWith(".json"))
					&& !path.startsWith("assets/") && !path.startsWith("data/")) {
				hasMixins = true;
			}
		}

		boolean hasPackMcmeta = packMcmetaBytes != null;
		int nestedJarCount = nestedJarCountRef[0];
		int totalClasses = classCount + nestedStats.classCount;
		assetCount += nestedStats.assetCount;
		dataCount += nestedStats.dataCount;
		if (nestedStats.hasMixins) {
			hasMixins = true;
		}

		// 3. Parse loader metadata (prefer Fabric/Quilt, then NeoForge/Forge/mcmod.info, then pack.mcmeta)
		ParsedMeta meta = new ParsedMeta(fallbackId, fallbackName);
		boolean hasFabric = fabricJson != null;
		boolean hasQuilt = quiltJson != null;
		boolean hasNeoForge = neoforgeToml != null;
		boolean hasForge = forgeToml != null || mcmodInfo != null || hasFmlAnnotation;

		if (hasFabric) {
			parseFabricJson(fabricJson, meta);
			if (meta.hasMixins) {
				hasMixins = true;
			}
		} else if (hasQuilt) {
			parseQuiltJson(quiltJson, meta);
			if (meta.hasMixins) {
				hasMixins = true;
			}
		} else if (hasNeoForge) {
			parseModsToml(neoforgeToml, meta);
		} else if (forgeToml != null) {
			parseModsToml(forgeToml, meta);
		} else if (mcmodInfo != null) {
			parseMcmodInfo(mcmodInfo, meta);
		} else if (packMcmetaBytes != null) {
			parsePackMcmeta(packMcmetaBytes, meta);
		}

		String loader;
		if (hasFabric) {
			loader = "fabric";
		} else if (hasQuilt) {
			loader = "quilt";
		} else if (hasNeoForge) {
			loader = "neoforge";
		} else if (hasForge) {
			loader = "forge";
		} else if (assetCount > 0 && dataCount == 0) {
			loader = "resourcepack";
		} else if (dataCount > 0 || hasPackMcmeta) {
			loader = "datapack";
		} else {
			loader = "unknown";
		}

		// 4. Classify: Pure Data/Asset mod vs Fabric/Quilt bytecode vs Forge/NeoForge bytecode vs Unknown JAR
		if (totalClasses == 0 && (assetCount > 0 || dataCount > 0 || hasPackMcmeta) && meta.entrypoints.isEmpty()) {
			String summary = buildDataAssetSummary(assetCount, dataCount, loader);
			return new ModInfo(
				filename, ModKind.DATA_ASSET_MOD, meta.id, meta.name, meta.version, meta.description,
				meta.author, loader, "", 0, assetCount, dataCount, nestedJarCount,
				hasPackMcmeta, false, Collections.emptyList(), summary);
		}

		if (hasFabric || hasQuilt) {
			StringBuilder s = new StringBuilder();
			s.append(hasQuilt ? "Quilt" : "Fabric").append(" mod (");
			s.append(totalClasses).append(totalClasses == 1 ? " class" : " classes");
			if (hasMixins) {
				s.append(", mixins");
			}
			if (nestedJarCount > 0) {
				s.append(", ").append(nestedJarCount).append(" nested jar").append(nestedJarCount == 1 ? "" : "s");
			}
			s.append(") - requires ahead-of-time WASM bake");
			if (assetCount > 0 || dataCount > 0) {
				s.append("; assets/data extractable");
			}
			return new ModInfo(
				filename, ModKind.FABRIC_NEEDS_BAKE, meta.id, meta.name, meta.version, meta.description,
				meta.author, loader, "", totalClasses, assetCount, dataCount, nestedJarCount,
				hasPackMcmeta, hasMixins, meta.entrypoints, s.toString());
		}

		if (hasNeoForge || hasForge) {
			String loaderLabel = hasNeoForge ? "NeoForge" : "Forge";
			StringBuilder s = new StringBuilder();
			s.append(loaderLabel).append(" mod (").append(totalClasses)
				.append(totalClasses == 1 ? " class" : " classes")
				.append(") - unsupported in browser WASM runtime");
			if (assetCount > 0 || dataCount > 0) {
				s.append("; assets/data extractable");
			}
			return new ModInfo(
				filename, ModKind.FORGE_UNSUPPORTED, meta.id, meta.name, meta.version, meta.description,
				meta.author, loader, "", totalClasses, assetCount, dataCount, nestedJarCount,
				hasPackMcmeta, hasMixins, meta.entrypoints, s.toString());
		}

		if (totalClasses > 0) {
			String summary = "Java archive (" + totalClasses + (totalClasses == 1 ? " class" : " classes")
				+ ") with no recognized mod metadata - cannot run in browser";
			return new ModInfo(
				filename, ModKind.UNKNOWN_JAR, meta.id, meta.name, meta.version, meta.description,
				meta.author, "unknown", "", totalClasses, assetCount, dataCount, nestedJarCount,
				hasPackMcmeta, hasMixins, Collections.emptyList(), summary);
		}

		return invalid(filename, meta.id, meta.name, "Archive has no JS entrypoint, assets, data, or mod metadata.");
	}

	/** Quick filename heuristic for routing dropped or picked files to the Mod Manager. */
	public static boolean looksLikeModFilename(final String filename) {
		if (filename == null) {
			return false;
		}
		String lower = filename.toLowerCase(Locale.ROOT);
		return lower.endsWith(".js") || lower.endsWith(".mjs")
			|| lower.endsWith(".jar") || lower.endsWith(".mcmod.zip");
	}

	/**
	 * Quick check used by {@code WebPacks} drag-and-drop routing: true when the dropped file is a
	 * browser mod ({@code .js}, {@code .mcmod.zip}, {@code .jar}) or a {@code .zip} carrying mod
	 * descriptors ({@code eagler.mod.json}, {@code fabric.mod.json}, {@code META-INF/mods.toml},
	 * {@code META-INF/neoforge.mods.toml}, {@code quilt.mod.json}).
	 */
	public static boolean looksLikeMod(final String filename, final byte[] bytes) {
		if (looksLikeModFilename(filename)) {
			return true;
		}
		if (bytes == null || !isZipMagic(bytes)) {
			return false;
		}
		ModInfo info = inspect(filename, bytes);
		return info.kind == ModKind.JS_BUNDLE
			|| info.kind == ModKind.FABRIC_NEEDS_BAKE
			|| info.kind == ModKind.FORGE_UNSUPPORTED
			|| (info.kind == ModKind.DATA_ASSET_MOD
				&& !"datapack".equals(info.loader)
				&& !"resourcepack".equals(info.loader));
	}

	/** Sanitize an uploaded filename for safe flat storage under {@code /config/mods/}. */
	public static String sanitizeFilename(final String filename) {
		String raw = filename == null ? "" : filename.trim();
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < raw.length() && out.length() < 80; i++) {
			char c = raw.charAt(i);
			if (c == '/' || c == '\\' || c == ':' || c == '?' || c == '*' || c == '"'
					|| c == '<' || c == '>' || c == '|' || c < ' ') {
				out.append('_');
			} else {
				out.append(c);
			}
		}
		String name = out.toString().replace("..", "_");
		while (name.startsWith(".")) {
			name = name.substring(1);
		}
		if (name.isEmpty()) {
			return "mod.jar";
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".jar")
				|| lower.endsWith(".mcmod.zip") || lower.endsWith(".zip")) {
			return name;
		}
		return name + ".jar";
	}

	/** Stable filesystem-safe stem for extracted {@code mod_<stem>_assets.zip} / {@code _data.zip}. */
	public static String stemForPack(final String filename) {
		String base = stripExtension(filename == null ? "mod" : filename);
		String slug = slugify(base);
		return slug.length() > 40 ? slug.substring(0, 40) : slug;
	}

	/** Read the JavaScript source for a {@link ModKind#JS_SCRIPT} or {@link ModKind#JS_BUNDLE}. */
	public static String extractJsSource(final String filename, final byte[] bytes, final ModInfo info) {
		if (bytes == null || info == null) {
			return null;
		}
		if (info.kind == ModKind.JS_SCRIPT) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		if (info.kind == ModKind.JS_BUNDLE) {
			Map<String, byte[]> entries = unzipSafe(bytes);
			if (entries == null || entries.isEmpty()) {
				return null;
			}
			byte[] script = entries.get(info.jsEntrypoint);
			if (script == null && !info.jsEntrypoint.isEmpty()) {
				String root = detectOptionalSingleRoot(entries);
				if (!root.isEmpty()) {
					script = entries.get(root + info.jsEntrypoint);
				}
			}
			return script == null ? null : new String(script, StandardCharsets.UTF_8);
		}
		return null;
	}

	/**
	 * Build a Minecraft 26.3 resource pack {@code .zip} from a mod archive's {@code assets/} files.
	 * Returns {@code null} if the archive contains no {@code assets/} entries.
	 */
	public static byte[] buildResourcePackZip(final byte[] modArchive, final ModInfo info, final int resourceMajor) {
		return buildExtractedPackZip(modArchive, info, "assets/", "Mod Assets", resourceMajor);
	}

	/**
	 * Build a Minecraft 26.3 data pack {@code .zip} from a mod archive's {@code data/} files.
	 * Returns {@code null} if the archive contains no {@code data/} entries.
	 */
	public static byte[] buildDataPackZip(final byte[] modArchive, final ModInfo info, final int dataMajor) {
		return buildExtractedPackZip(modArchive, info, "data/", "Mod Data", dataMajor);
	}

	/** Build a valid Minecraft 26.3 {@code pack.mcmeta} using {@code min_format} / {@code max_format}. */
	public static String buildPackMcmetaJson(final String description, final int formatMajor) {
		int major = Math.max(1, formatMajor);
		return "{\"pack\":{\"description\":\"" + escapeJson(description)
			+ "\",\"min_format\":[" + major + ",0],\"max_format\":[" + major + ",999]}}";
	}

	/** Escape a string for safe embedding inside a JSON double-quoted literal. */
	public static String escapeJson(final String value) {
		if (value == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '\\': sb.append("\\\\"); break;
				case '"': sb.append("\\\""); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				case '\b': sb.append("\\b"); break;
				case '\f': sb.append("\\f"); break;
				default:
					if (c < 0x20) {
						String hex = Integer.toHexString(c);
						sb.append("\\u");
						for (int z = hex.length(); z < 4; z++) {
							sb.append('0');
						}
						sb.append(hex);
					} else {
						sb.append(c);
					}
					break;
			}
		}
		return sb.toString();
	}

	// ---- Pack extraction implementation ----

	private static byte[] buildExtractedPackZip(
			final byte[] modArchive,
			final ModInfo info,
			final String folderPrefix,
			final String suffixLabel,
			final int formatMajor) {
		if (modArchive == null || !isZipMagic(modArchive)) {
			return null;
		}
		Map<String, byte[]> rawEntries = unzipSafe(modArchive);
		if (rawEntries == null || rawEntries.isEmpty()) {
			return null;
		}
		String rootPrefix = detectOptionalSingleRoot(rawEntries);
		Map<String, byte[]> entries = stripRootPrefix(rawEntries, rootPrefix);

		Map<String, byte[]> outEntries = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String path = e.getKey();
			if (path.startsWith(folderPrefix) && path.length() > folderPrefix.length()) {
				outEntries.put(path, e.getValue());
			}
		}
		int[] nestedPackCount = new int[]{0};
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String pathLower = e.getKey().toLowerCase(Locale.ROOT);
			if (pathLower.startsWith("meta-inf/jars/") && pathLower.endsWith(".jar")) {
				nestedPackCount[0]++;
				if (nestedPackCount[0] <= MAX_NESTED_JARS) {
					collectNestedPackEntries(e.getValue(), folderPrefix, outEntries, nestedPackCount, 1);
				}
			}
		}
		if (outEntries.isEmpty()) {
			return null;
		}

		// Preserve pack icon when present.
		byte[] icon = entries.get("pack.png");
		if (icon != null) {
			outEntries.put("pack.png", icon);
		}

		// Preserve existing pack.mcmeta only when it already declares 26.3 min_format & max_format;
		// otherwise synthesize a valid 26.3 pack.mcmeta so Minecraft accepts the pack without errors.
		byte[] existingMeta = entries.get("pack.mcmeta");
		String existingDesc = null;
		boolean keepExistingMeta = false;
		if (existingMeta != null) {
			String text = new String(existingMeta, StandardCharsets.UTF_8);
			Object parsed = parseJson(text);
			if (parsed instanceof Map<?, ?> root) {
				Object packObj = root.get("pack");
				if (packObj instanceof Map<?, ?> pack) {
					if (pack.containsKey("min_format") && pack.containsKey("max_format")) {
						keepExistingMeta = true;
					}
					Object descObj = pack.get("description");
					if (descObj instanceof String s && !s.trim().isEmpty()) {
						existingDesc = s.trim();
					}
				}
			}
		}

		if (keepExistingMeta) {
			outEntries.put("pack.mcmeta", existingMeta);
		} else {
			String label = existingDesc != null
				? existingDesc
				: ((info != null && !info.name.isEmpty() ? info.name : "Browser Mod") + " (" + suffixLabel + ")");
			outEntries.put("pack.mcmeta", buildPackMcmetaJson(label, formatMajor).getBytes(StandardCharsets.UTF_8));
		}

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(baos)) {
			zos.setLevel(1);
			for (Map.Entry<String, byte[]> e : outEntries.entrySet()) {
				zos.putNextEntry(new ZipEntry(e.getKey()));
				zos.write(e.getValue());
				zos.closeEntry();
			}
		} catch (IOException e) {
			return null;
		}
		return baos.toByteArray();
	}

	// ---- JS script & JS bundle inspection ----

	private static ModInfo inspectJsScript(
			final String filename, final String fallbackId, final String fallbackName, final byte[] bytes) {
		int checkLen = Math.min(bytes.length, MAX_HEADER_SCAN_BYTES);
		for (int i = 0; i < checkLen; i++) {
			if (bytes[i] == 0) {
				return invalid(filename, fallbackId, fallbackName, "Binary data in .js file.");
			}
		}
		String fullText = new String(bytes, StandardCharsets.UTF_8);
		if (fullText.trim().isEmpty()) {
			return invalid(filename, fallbackId, fallbackName, "Empty JavaScript file.");
		}

		String header = fullText.length() > MAX_HEADER_SCAN_BYTES
			? fullText.substring(0, MAX_HEADER_SCAN_BYTES)
			: fullText;
		String id = fallbackId;
		String name = fallbackName;
		String version = "";
		String author = "";
		String description = "";

		String[] lines = header.split("\\r?\\n");
		for (String rawLine : lines) {
			String line = rawLine.trim();
			if (line.startsWith("//")) {
				line = line.substring(2).trim();
			} else if (line.startsWith("/*")) {
				line = line.substring(2).trim();
			} else if (line.startsWith("*")) {
				line = line.substring(1).trim();
			} else {
				continue;
			}
			if (line.endsWith("*/")) {
				line = line.substring(0, line.length() - 2).trim();
			}
			if (!line.startsWith("@")) {
				continue;
			}
			int split = -1;
			for (int i = 1; i < line.length(); i++) {
				char c = line.charAt(i);
				if (c == ' ' || c == '\t' || c == ':') {
					split = i;
					break;
				}
			}
			if (split <= 1) {
				continue;
			}
			String tag = line.substring(1, split).toLowerCase(Locale.ROOT);
			String val = line.substring(split + 1).trim();
			if (val.startsWith(":")) {
				val = val.substring(1).trim();
			}
			if (val.isEmpty()) {
				continue;
			}
			if ("id".equals(tag) || "modid".equals(tag)) {
				id = slugify(val);
			} else if ("name".equals(tag) || "title".equals(tag)) {
				name = val;
			} else if ("version".equals(tag)) {
				version = val;
			} else if ("author".equals(tag) || "authors".equals(tag)) {
				author = val;
			} else if ("description".equals(tag) || "desc".equals(tag)) {
				description = val;
			}
		}

		return new ModInfo(
			filename, ModKind.JS_SCRIPT, id, name, version, description, author,
			"js", filename, 0, 0, 0, 0, false, false,
			Collections.singletonList(filename),
			"Runs live via window.EaglerMods JS runtime");
	}

	private static ModInfo tryInspectJsBundle(
			final String filename,
			final String fallbackId,
			final String fallbackName,
			final String lowerFilename,
			final Map<String, byte[]> entries,
			final String rootPrefix) {
		byte[] manifestBytes = entries.get("eagler.mod.json");
		if (manifestBytes == null) {
			manifestBytes = entries.get("mod.json");
		}
		boolean isExplicitMcmodZip = lowerFilename.endsWith(".mcmod.zip");
		if (manifestBytes == null && !isExplicitMcmodZip) {
			return null;
		}

		String id = fallbackId;
		String name = fallbackName;
		String version = "";
		String description = "";
		String author = "";
		String entryPath = "";

		if (manifestBytes != null) {
			Object parsed = parseJson(new String(manifestBytes, StandardCharsets.UTF_8));
			if (parsed instanceof Map<?, ?> map) {
				String s;
				if (!(s = asString(map.get("id"))).isEmpty()) {
					id = slugify(s);
				}
				if (!(s = asString(map.get("name"))).isEmpty()) {
					name = s;
				}
				if (!(s = asString(map.get("version"))).isEmpty()) {
					version = s;
				}
				if (!(s = asString(map.get("description"))).isEmpty()) {
					description = s;
				}
				author = extractAuthors(map.get("authors"), map.get("author"));
				entryPath = asString(map.get("main"));
				if (entryPath.isEmpty()) {
					entryPath = asString(map.get("entrypoint"));
				}
				if (entryPath.isEmpty()) {
					entryPath = asString(map.get("entry"));
				}
				while (entryPath.startsWith("./")) {
					entryPath = entryPath.substring(2);
				}
				while (entryPath.startsWith("/")) {
					entryPath = entryPath.substring(1);
				}
			}
		}

		if (entryPath.isEmpty() || !entries.containsKey(entryPath)) {
			entryPath = findDefaultJsEntrypoint(entries);
		}
		if (entryPath.isEmpty()) {
			if (isExplicitMcmodZip) {
				return invalid(filename, id, name, "No .js entrypoint found inside .mcmod.zip.");
			}
			return null;
		}

		int assetCount = 0;
		int dataCount = 0;
		boolean hasPackMcmeta = entries.containsKey("pack.mcmeta");
		for (String p : entries.keySet()) {
			if (p.startsWith("assets/") && p.length() > "assets/".length()) {
				assetCount++;
			} else if (p.startsWith("data/") && p.length() > "data/".length()) {
				dataCount++;
			}
		}

		String storedEntry = rootPrefix.isEmpty() ? entryPath : rootPrefix + entryPath;
		StringBuilder summary = new StringBuilder("JS bundle (").append(entryPath).append(") - runs via window.EaglerMods");
		if (assetCount > 0 || dataCount > 0) {
			summary.append(" + ").append(assetCount).append(" assets, ").append(dataCount).append(" data");
		}
		return new ModInfo(
			filename, ModKind.JS_BUNDLE, id, name, version, description, author,
			"js", storedEntry, 0, assetCount, dataCount, 0, hasPackMcmeta, false,
			Collections.singletonList(entryPath), summary.toString());
	}

	private static String findDefaultJsEntrypoint(final Map<String, byte[]> entries) {
		if (entries.containsKey("main.js")) {
			return "main.js";
		}
		if (entries.containsKey("index.js")) {
			return "index.js";
		}
		if (entries.containsKey("mod.js")) {
			return "mod.js";
		}
		String singleTopLevelJs = null;
		for (String p : entries.keySet()) {
			String pl = p.toLowerCase(Locale.ROOT);
			if (!p.contains("/") && (pl.endsWith(".js") || pl.endsWith(".mjs"))) {
				if (singleTopLevelJs != null) {
					return singleTopLevelJs;
				}
				singleTopLevelJs = p;
			}
		}
		return singleTopLevelJs == null ? "" : singleTopLevelJs;
	}

	// ---- Fabric / Quilt / Forge / NeoForge metadata parsers ----

	private static final class ParsedMeta {
		String id;
		String name;
		String version = "";
		String description = "";
		String author = "";
		boolean hasMixins;
		final List<String> entrypoints = new ArrayList<>();

		ParsedMeta(final String fallbackId, final String fallbackName) {
			this.id = fallbackId;
			this.name = fallbackName;
		}
	}

	private static void parseFabricJson(final byte[] bytes, final ParsedMeta out) {
		Object parsed = parseJson(new String(bytes, StandardCharsets.UTF_8));
		if (!(parsed instanceof Map<?, ?> map)) {
			return;
		}
		String id = asString(map.get("id"));
		if (!id.isEmpty()) {
			out.id = id;
		}
		String name = asString(map.get("name"));
		if (!name.isEmpty()) {
			out.name = name;
		} else if (!id.isEmpty()) {
			out.name = id;
		}
		String version = asString(map.get("version"));
		if (!version.isEmpty()) {
			out.version = version;
		}
		String desc = asString(map.get("description"));
		if (!desc.isEmpty()) {
			out.description = desc;
		}
		out.author = extractAuthors(map.get("authors"), map.get("author"));

		Object mixins = map.get("mixins");
		if (mixins instanceof List<?> list && !list.isEmpty()) {
			out.hasMixins = true;
		} else if (mixins instanceof String s && !s.isEmpty()) {
			out.hasMixins = true;
		}

		Object epObj = map.get("entrypoints");
		if (epObj instanceof Map<?, ?> epMap) {
			for (Map.Entry<?, ?> e : epMap.entrySet()) {
				String key = asString(e.getKey());
				Object val = e.getValue();
				if (val instanceof List<?> list) {
					for (Object item : list) {
						String cls = item instanceof Map<?, ?> m ? asString(m.get("value")) : asString(item);
						if (!cls.isEmpty()) {
							out.entrypoints.add(key + ":" + cls);
						}
					}
				} else if (val instanceof String s && !s.isEmpty()) {
					out.entrypoints.add(key + ":" + s);
				}
			}
		}
	}

	private static void parseQuiltJson(final byte[] bytes, final ParsedMeta out) {
		Object parsed = parseJson(new String(bytes, StandardCharsets.UTF_8));
		if (!(parsed instanceof Map<?, ?> root)) {
			return;
		}
		Object loaderObj = root.get("quilt_loader");
		if (loaderObj instanceof Map<?, ?> loader) {
			String id = asString(loader.get("id"));
			if (!id.isEmpty()) {
				out.id = id;
				out.name = id;
			}
			String version = asString(loader.get("version"));
			if (!version.isEmpty()) {
				out.version = version;
			}
			Object metaObj = loader.get("metadata");
			if (metaObj instanceof Map<?, ?> meta) {
				String name = asString(meta.get("name"));
				if (!name.isEmpty()) {
					out.name = name;
				}
				String desc = asString(meta.get("description"));
				if (!desc.isEmpty()) {
					out.description = desc;
				}
				Object contributors = meta.get("contributors");
				if (contributors instanceof Map<?, ?> cmap && !cmap.isEmpty()) {
					List<String> names = new ArrayList<>();
					for (Object k : cmap.keySet()) {
						String s = asString(k);
						if (!s.isEmpty()) {
							names.add(s);
						}
					}
					out.author = String.join(", ", names);
				}
			}
			Object epObj = loader.get("entrypoints");
			if (epObj instanceof Map<?, ?> epMap) {
				for (Map.Entry<?, ?> e : epMap.entrySet()) {
					String key = asString(e.getKey());
					Object val = e.getValue();
					if (val instanceof List<?> list) {
						for (Object item : list) {
							String cls = asString(item);
							if (!cls.isEmpty()) {
								out.entrypoints.add(key + ":" + cls);
							}
						}
					} else if (val instanceof String s && !s.isEmpty()) {
						out.entrypoints.add(key + ":" + s);
					}
				}
			}
		}
		Object mixinObj = root.get("mixin");
		if ((mixinObj instanceof List<?> list && !list.isEmpty())
				|| (mixinObj instanceof String s && !s.isEmpty())) {
			out.hasMixins = true;
		}
	}

	private static void parseModsToml(final byte[] bytes, final ParsedMeta out) {
		String text = new String(bytes, StandardCharsets.UTF_8);
		String[] lines = text.split("\\r?\\n");
		boolean inModsSection = false;
		boolean parsedFirstMod = false;
		String multilineKey = null;
		String multilineDelim = null;
		StringBuilder multilineVal = null;

		for (String rawLine : lines) {
			if (multilineKey != null) {
				int endIdx = rawLine.indexOf(multilineDelim);
				if (endIdx >= 0) {
					multilineVal.append(rawLine, 0, endIdx);
					applyTomlField(multilineKey, multilineVal.toString().trim(), out);
					multilineKey = null;
					multilineDelim = null;
					multilineVal = null;
				} else {
					if (multilineVal.length() > 0) {
						multilineVal.append(' ');
					}
					multilineVal.append(rawLine.trim());
				}
				continue;
			}

			String line = stripTomlComment(rawLine).trim();
			if (line.isEmpty()) {
				continue;
			}
			if (line.startsWith("[[") && line.endsWith("]]")) {
				String sec = line.substring(2, line.length() - 2).trim();
				if ("mods".equals(sec)) {
					if (parsedFirstMod) {
						break;
					}
					inModsSection = true;
				} else {
					if (inModsSection) {
						parsedFirstMod = true;
					}
					inModsSection = false;
				}
				continue;
			}
			if (line.startsWith("[") && line.endsWith("]")) {
				if (inModsSection) {
					parsedFirstMod = true;
				}
				inModsSection = false;
				continue;
			}
			if (!inModsSection) {
				continue;
			}
			int eq = line.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = line.substring(0, eq).trim();
			String val = line.substring(eq + 1).trim();
			if (val.startsWith("'''") || val.startsWith("\"\"\"")) {
				String delim = val.substring(0, 3);
				String rest = val.substring(3);
				int endIdx = rest.indexOf(delim);
				if (endIdx >= 0) {
					applyTomlField(key, rest.substring(0, endIdx).trim(), out);
				} else {
					multilineKey = key;
					multilineDelim = delim;
					multilineVal = new StringBuilder(rest.trim());
				}
			} else {
				applyTomlField(key, unquoteToml(val), out);
			}
		}
	}

	private static void applyTomlField(final String key, final String value, final ParsedMeta out) {
		if (value.isEmpty() || "${file.jarVersion}".equals(value)) {
			return;
		}
		if ("modId".equals(key)) {
			out.id = value;
			if (out.name.isEmpty()) {
				out.name = value;
			}
		} else if ("displayName".equals(key)) {
			out.name = value;
		} else if ("version".equals(key)) {
			out.version = value;
		} else if ("authors".equals(key)) {
			out.author = value;
		} else if ("description".equals(key)) {
			out.description = value;
		}
	}

	private static String stripTomlComment(final String line) {
		boolean inDouble = false;
		boolean inSingle = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '\\' && inDouble && i + 1 < line.length()) {
				i++;
				continue;
			}
			if (c == '"' && !inSingle) {
				inDouble = !inDouble;
			} else if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '#' && !inDouble && !inSingle) {
				return line.substring(0, i);
			}
		}
		return line;
	}

	private static String unquoteToml(final String raw) {
		if (raw.length() >= 2 && ((raw.startsWith("\"") && raw.endsWith("\""))
				|| (raw.startsWith("'") && raw.endsWith("'")))) {
			String inner = raw.substring(1, raw.length() - 1);
			if (raw.startsWith("\"")) {
				return inner.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\");
			}
			return inner;
		}
		return raw;
	}

	private static void parseMcmodInfo(final byte[] bytes, final ParsedMeta out) {
		Object parsed = parseJson(new String(bytes, StandardCharsets.UTF_8));
		Map<?, ?> first = null;
		if (parsed instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
			first = m;
		} else if (parsed instanceof Map<?, ?> root) {
			Object modList = root.get("modList");
			if (modList instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> m) {
				first = m;
			} else {
				first = root;
			}
		}
		if (first == null) {
			return;
		}
		String id = asString(first.get("modid"));
		if (!id.isEmpty()) {
			out.id = id;
		}
		String name = asString(first.get("name"));
		if (!name.isEmpty()) {
			out.name = name;
		}
		String version = asString(first.get("version"));
		if (!version.isEmpty()) {
			out.version = version;
		}
		String desc = asString(first.get("description"));
		if (!desc.isEmpty()) {
			out.description = desc;
		}
		out.author = extractAuthors(first.get("authorList"), first.get("authors"));
	}

	// ---- ZIP utilities ----

	private static boolean isZipMagic(final byte[] bytes) {
		return bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B
			&& (bytes[2] == 0x03 || bytes[2] == 0x05 || bytes[2] == 0x07);
	}

	private static Map<String, byte[]> unzipSafe(final byte[] archive) {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			byte[] buffer = new byte[8192];
			while ((entry = in.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				String name = normalizeZipPath(entry.getName());
				if (name.isEmpty()) {
					continue;
				}
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				int n;
				while ((n = in.read(buffer, 0, buffer.length)) > 0) {
					out.write(buffer, 0, n);
				}
				entries.put(name, out.toByteArray());
			}
		} catch (Throwable e) {
			return null;
		}
		return entries;
	}

	private static final class NestedJarStats {
		int classCount;
		int assetCount;
		int dataCount;
		boolean hasMixins;
	}

	private static void inspectNestedJar(
			final byte[] jarBytes,
			final NestedJarStats stats,
			final int[] nestedCountRef,
			final int depth) {
		if (jarBytes == null || !isZipMagic(jarBytes) || depth > 2) {
			return;
		}
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
			ZipEntry entry;
			byte[] buffer = new byte[4096];
			while ((entry = in.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				String p = normalizeZipPath(entry.getName());
				if (p.isEmpty()) {
					continue;
				}
				String pLower = p.toLowerCase(Locale.ROOT);
				if (pLower.endsWith(".class") && !isNonExecutableClass(pLower)) {
					stats.classCount++;
				} else if (p.startsWith("assets/") && p.length() > "assets/".length()) {
					stats.assetCount++;
				} else if (p.startsWith("data/") && p.length() > "data/".length()) {
					stats.dataCount++;
				} else if (depth < 2 && pLower.startsWith("meta-inf/jars/") && pLower.endsWith(".jar")) {
					nestedCountRef[0]++;
					if (nestedCountRef[0] <= MAX_NESTED_JARS) {
						ByteArrayOutputStream nestedOut = new ByteArrayOutputStream();
						int n;
						while ((n = in.read(buffer, 0, buffer.length)) > 0) {
							nestedOut.write(buffer, 0, n);
						}
						inspectNestedJar(nestedOut.toByteArray(), stats, nestedCountRef, depth + 1);
					}
				}
				if ((pLower.endsWith(".mixins.json") || (pLower.contains("mixins.") && pLower.endsWith(".json")))
						&& !p.startsWith("assets/") && !p.startsWith("data/")) {
					stats.hasMixins = true;
				}
			}
		} catch (Throwable ignored) {
			// Corrupt nested jar shouldn't abort outer inspection.
		}
	}

	private static void collectNestedPackEntries(
			final byte[] jarBytes,
			final String folderPrefix,
			final Map<String, byte[]> outEntries,
			final int[] nestedCountRef,
			final int depth) {
		if (jarBytes == null || !isZipMagic(jarBytes) || depth > 2) {
			return;
		}
		Map<String, byte[]> nestedEntries = unzipSafe(jarBytes);
		if (nestedEntries == null || nestedEntries.isEmpty()) {
			return;
		}
		for (Map.Entry<String, byte[]> e : nestedEntries.entrySet()) {
			String p = e.getKey();
			if (p.startsWith(folderPrefix) && p.length() > folderPrefix.length()) {
				outEntries.putIfAbsent(p, e.getValue());
			} else if (depth < 2) {
				String pLower = p.toLowerCase(Locale.ROOT);
				if (pLower.startsWith("meta-inf/jars/") && pLower.endsWith(".jar")) {
					nestedCountRef[0]++;
					if (nestedCountRef[0] <= MAX_NESTED_JARS) {
						collectNestedPackEntries(e.getValue(), folderPrefix, outEntries, nestedCountRef, depth + 1);
					}
				}
			}
		}
	}

	private static void parsePackMcmeta(final byte[] bytes, final ParsedMeta out) {
		Object parsed = parseJson(new String(bytes, StandardCharsets.UTF_8));
		if (!(parsed instanceof Map<?, ?> root)) {
			return;
		}
		Object packObj = root.get("pack");
		if (!(packObj instanceof Map<?, ?> pack)) {
			return;
		}
		String desc = extractTextComponent(pack.get("description"));
		if (!desc.isEmpty()) {
			out.description = desc;
		}
	}

	private static String extractTextComponent(final Object obj) {
		if (obj instanceof String s) {
			return s.trim();
		}
		if (obj instanceof Map<?, ?> map) {
			StringBuilder sb = new StringBuilder();
			String text = asString(map.get("text"));
			if (!text.isEmpty()) {
				sb.append(text);
			}
			Object extra = map.get("extra");
			if (extra instanceof List<?> list) {
				for (Object item : list) {
					String part = extractTextComponent(item);
					if (!part.isEmpty()) {
						sb.append(part);
					}
				}
			}
			return sb.toString().trim();
		}
		if (obj instanceof List<?> list) {
			StringBuilder sb = new StringBuilder();
			for (Object item : list) {
				String part = extractTextComponent(item);
				if (!part.isEmpty()) {
					sb.append(part);
				}
			}
			return sb.toString().trim();
		}
		return "";
	}

	private static boolean isNonExecutableClass(final String lowerPath) {
		return lowerPath.equals("module-info.class")
			|| lowerPath.endsWith("/module-info.class")
			|| lowerPath.equals("package-info.class")
			|| lowerPath.endsWith("/package-info.class");
	}

	private static String normalizeZipPath(final String raw) {
		if (raw == null) {
			return "";
		}
		String p = raw.replace('\\', '/');
		while (p.startsWith("/")) {
			p = p.substring(1);
		}
		if (p.contains("../") || p.equals("..")) {
			return "";
		}
		return p;
	}

	/**
	 * When a zip wraps its entire contents in one top-level folder (and does NOT already have
	 * standard root descriptors/folders at root), return that folder prefix (with trailing '/').
	 */
	private static String detectOptionalSingleRoot(final Map<String, byte[]> entries) {
		String common = null;
		for (String name : entries.keySet()) {
			int slash = name.indexOf('/');
			if (slash <= 0) {
				return "";
			}
			String first = name.substring(0, slash + 1);
			if (first.equals("assets/") || first.equals("data/") || first.equals("META-INF/")) {
				return "";
			}
			if (common == null) {
				common = first;
			} else if (!common.equals(first)) {
				return "";
			}
		}
		return common == null ? "" : common;
	}

	private static Map<String, byte[]> stripRootPrefix(final Map<String, byte[]> raw, final String prefix) {
		if (prefix.isEmpty()) {
			return raw;
		}
		Map<String, byte[]> out = new LinkedHashMap<>(raw.size());
		for (Map.Entry<String, byte[]> e : raw.entrySet()) {
			String key = e.getKey();
			if (key.startsWith(prefix) && key.length() > prefix.length()) {
				out.put(key.substring(prefix.length()), e.getValue());
			}
		}
		return out;
	}

	// ---- Formatting & Helpers ----

	private static ModInfo invalid(final String filename, final String id, final String name, final String reason) {
		return new ModInfo(
			filename, ModKind.INVALID, id, name, "", "", "", "invalid", "",
			0, 0, 0, 0, false, false, Collections.emptyList(), reason);
	}

	private static String buildDataAssetSummary(final int assetCount, final int dataCount, final String loader) {
		StringBuilder sb = new StringBuilder("Pure ");
		if (assetCount > 0 && dataCount > 0) {
			sb.append("asset+data mod (").append(assetCount).append(" assets, ").append(dataCount).append(" data)");
		} else if (assetCount > 0) {
			sb.append("asset mod (").append(assetCount).append(" assets)");
		} else if (dataCount > 0) {
			sb.append("data mod (").append(dataCount).append(" data files)");
		} else {
			sb.append("pack mod");
		}
		if (!"datapack".equals(loader) && !"unknown".equals(loader)) {
			sb.append(" [").append(loader).append("]");
		}
		sb.append(" - loads directly in browser");
		return sb.toString();
	}

	private static String stripExtension(final String filename) {
		String lower = filename.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".mcmod.zip")) {
			return filename.substring(0, filename.length() - ".mcmod.zip".length());
		}
		int dot = filename.lastIndexOf('.');
		return dot > 0 ? filename.substring(0, dot) : filename;
	}

	private static String slugify(final String text) {
		StringBuilder sb = new StringBuilder();
		boolean prevDash = false;
		for (int i = 0; i < text.length(); i++) {
			char c = Character.toLowerCase(text.charAt(i));
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
				sb.append(c);
				prevDash = false;
			} else if (!prevDash && sb.length() > 0) {
				sb.append('-');
				prevDash = true;
			}
		}
		int end = sb.length();
		while (end > 0 && sb.charAt(end - 1) == '-') {
			end--;
		}
		return end == 0 ? "mod" : sb.substring(0, end);
	}

	private static String asString(final Object o) {
		if (o instanceof String s) {
			return s.trim();
		}
		if (o instanceof Number || o instanceof Boolean) {
			return String.valueOf(o);
		}
		return "";
	}

	private static String extractAuthors(final Object primary, final Object secondary) {
		String out = extractAuthorsOne(primary);
		return out.isEmpty() ? extractAuthorsOne(secondary) : out;
	}

	private static String extractAuthorsOne(final Object obj) {
		if (obj instanceof String s) {
			return s.trim();
		}
		if (obj instanceof List<?> list) {
			List<String> names = new ArrayList<>();
			for (Object item : list) {
				if (item instanceof String s && !s.trim().isEmpty()) {
					names.add(s.trim());
				} else if (item instanceof Map<?, ?> m) {
					String n = asString(m.get("name"));
					if (!n.isEmpty()) {
						names.add(n);
					}
				}
			}
			return String.join(", ", names);
		}
		return "";
	}

	// ---- Minimal zero-dependency JSON parser ----

	static Object parseJson(final String text) {
		if (text == null) {
			return null;
		}
		JsonReader r = new JsonReader(text);
		try {
			return r.readValue();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static final class JsonReader {
		private final String src;
		private final int len;
		private int pos;

		JsonReader(final String src) {
			this.src = src;
			this.len = src.length();
		}

		Object readValue() {
			skipWs();
			if (this.pos >= this.len) {
				return null;
			}
			char c = this.src.charAt(this.pos);
			if (c == '{') {
				return readObject();
			}
			if (c == '[') {
				return readArray();
			}
			if (c == '"') {
				return readString();
			}
			if (c == 't' && this.src.startsWith("true", this.pos)) {
				this.pos += 4;
				return Boolean.TRUE;
			}
			if (c == 'f' && this.src.startsWith("false", this.pos)) {
				this.pos += 5;
				return Boolean.FALSE;
			}
			if (c == 'n' && this.src.startsWith("null", this.pos)) {
				this.pos += 4;
				return null;
			}
			if (c == '-' || (c >= '0' && c <= '9')) {
				return readNumber();
			}
			throw new IllegalArgumentException("Unexpected char at " + this.pos);
		}

		private Map<String, Object> readObject() {
			Map<String, Object> map = new LinkedHashMap<>();
			this.pos++; // '{'
			skipWs();
			if (this.pos < this.len && this.src.charAt(this.pos) == '}') {
				this.pos++;
				return map;
			}
			while (this.pos < this.len) {
				skipWs();
				if (this.pos >= this.len || this.src.charAt(this.pos) != '"') {
					break;
				}
				String key = readString();
				skipWs();
				if (this.pos >= this.len || this.src.charAt(this.pos) != ':') {
					break;
				}
				this.pos++;
				Object val = readValue();
				map.put(key, val);
				skipWs();
				if (this.pos < this.len && this.src.charAt(this.pos) == ',') {
					this.pos++;
					continue;
				}
				if (this.pos < this.len && this.src.charAt(this.pos) == '}') {
					this.pos++;
				}
				break;
			}
			return map;
		}

		private List<Object> readArray() {
			List<Object> list = new ArrayList<>();
			this.pos++; // '['
			skipWs();
			if (this.pos < this.len && this.src.charAt(this.pos) == ']') {
				this.pos++;
				return list;
			}
			while (this.pos < this.len) {
				list.add(readValue());
				skipWs();
				if (this.pos < this.len && this.src.charAt(this.pos) == ',') {
					this.pos++;
					continue;
				}
				if (this.pos < this.len && this.src.charAt(this.pos) == ']') {
					this.pos++;
				}
				break;
			}
			return list;
		}

		private String readString() {
			this.pos++; // opening '"'
			StringBuilder sb = new StringBuilder();
			while (this.pos < this.len) {
				char c = this.src.charAt(this.pos++);
				if (c == '"') {
					return sb.toString();
				}
				if (c == '\\' && this.pos < this.len) {
					char esc = this.src.charAt(this.pos++);
					switch (esc) {
						case '"': sb.append('"'); break;
						case '\\': sb.append('\\'); break;
						case '/': sb.append('/'); break;
						case 'b': sb.append('\b'); break;
						case 'f': sb.append('\f'); break;
						case 'n': sb.append('\n'); break;
						case 'r': sb.append('\r'); break;
						case 't': sb.append('\t'); break;
						case 'u':
							if (this.pos + 4 <= this.len) {
								try {
									sb.append((char) Integer.parseInt(this.src.substring(this.pos, this.pos + 4), 16));
								} catch (NumberFormatException ignored) {
									sb.append('?');
								}
								this.pos += 4;
							}
							break;
						default:
							sb.append(esc);
							break;
					}
				} else {
					sb.append(c);
				}
			}
			return sb.toString();
		}

		private Number readNumber() {
			int start = this.pos;
			if (this.src.charAt(this.pos) == '-') {
				this.pos++;
			}
			boolean fp = false;
			while (this.pos < this.len) {
				char c = this.src.charAt(this.pos);
				if (c >= '0' && c <= '9') {
					this.pos++;
				} else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
					fp = true;
					this.pos++;
				} else {
					break;
				}
			}
			String token = this.src.substring(start, this.pos);
			try {
				return fp ? Double.valueOf(token) : Long.valueOf(token);
			} catch (NumberFormatException e) {
				return 0;
			}
		}

		private void skipWs() {
			while (this.pos < this.len) {
				char c = this.src.charAt(this.pos);
				if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\uFEFF') {
					this.pos++;
				} else {
					break;
				}
			}
		}
	}
}
