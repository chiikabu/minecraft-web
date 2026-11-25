// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An OptiFine/Iris shader pack read from a zip: the files under its {@code shaders/} folder,
 * {@code shaders.properties}, the dimension folders and the program lookup.
 *
 * <p>Pure Java so the desktop harness can load real packs.
 */
public final class ShaderPack {
	public final String name;
	/** Files under the shaders root, keyed by absolute path ("/composite.fsh"). */
	private final Map<String, byte[]> files;
	/** Case-insensitive fallback map (lowercased path -> canonical path) for Windows-authored packs. */
	private final Map<String, String> lowerPaths;
	private final Map<String, String> textCache = new HashMap<>();

	private ShaderPack(final String name, final Map<String, byte[]> files) {
		this.name = name;
		this.files = files;
		this.lowerPaths = new HashMap<>(files.size() * 2);
		for (String p : files.keySet()) {
			this.lowerPaths.putIfAbsent(p.toLowerCase(Locale.ROOT), p);
		}
	}

	private static String normalizeZipEntry(final String raw) {
		String n = raw.replace('\\', '/');
		while (true) {
			if (n.startsWith("./")) {
				n = n.substring(2);
			} else if (n.startsWith("/")) {
				n = n.substring(1);
			} else {
				break;
			}
		}
		return n;
	}

	/**
	 * Null if the archive has no {@code shaders/} folder. The folder may sit at the top of the zip
	 * or one folder down (a zip of the pack's own folder), like resource packs.
	 */
	public static ShaderPack fromZipEntries(final String name, final Map<String, byte[]> entries) {
		String prefix = findRoot(entries.keySet());
		if (prefix == null) {
			return null;
		}
		String prefixLower = prefix.toLowerCase(Locale.ROOT);
		int prefixLen = prefix.length();
		Map<String, byte[]> files = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String path = normalizeZipEntry(e.getKey());
			if (!path.endsWith("/") && path.length() > prefixLen
				&& path.regionMatches(true, 0, prefixLower, 0, prefixLen)) {
				files.put("/" + path.substring(prefixLen), e.getValue());
			}
		}
		return new ShaderPack(name, files);
	}

	/** The entry prefix ending in "shaders/" that holds programs, or null. */
	public static String findRoot(final Iterable<String> names) {
		String best = null;
		for (String raw : names) {
			String n = normalizeZipEntry(raw);
			String lower = n.toLowerCase(Locale.ROOT);
			int at = lower.indexOf("shaders/");
			while (at >= 0) {
				if (at == 0 || lower.charAt(at - 1) == '/') {
					String prefix = n.substring(0, at + "shaders/".length());
					String rest = lower.substring(prefix.length());
					boolean program = rest.endsWith(".fsh") || rest.endsWith(".vsh")
						|| rest.equals("shaders.properties");
					int depth = prefix.length() - prefix.replace("/", "").length();
					if (program && depth <= 2 && (best == null || prefix.length() < best.length())) {
						best = prefix;
					}
				}
				at = lower.indexOf("shaders/", at + 1);
			}
		}
		return best;
	}

	private String canonicalPath(final String path) {
		if (path == null) {
			return null;
		}
		if (this.files.containsKey(path)) {
			return path;
		}
		return this.lowerPaths.get(path.toLowerCase(Locale.ROOT));
	}

	public boolean exists(final String path) {
		return this.canonicalPath(path) != null;
	}

	public byte[] bytes(final String path) {
		String key = this.canonicalPath(path);
		return key == null ? null : this.files.get(key);
	}

	public String text(final String path) {
		String key = this.canonicalPath(path);
		if (key == null) {
			return null;
		}
		String cached = this.textCache.get(key);
		if (cached != null) {
			return cached;
		}
		byte[] b = this.files.get(key);
		if (b == null) {
			return null;
		}
		String s = new String(b, StandardCharsets.UTF_8);
		if (!s.isEmpty() && s.charAt(0) == (char) 0xFEFF) {
			s = s.substring(1);
		}
		this.textCache.put(key, s);
		return s;
	}

	public Iterable<String> paths() {
		return this.files.keySet();
	}

	// ---- dimensions ----

	/**
	 * Folder holding the programs for a dimension ("" = the root): {@code dimension.properties}
	 * first ({@code dimension.<folder> = <ids>}, {@code *} matches any), then the OptiFine names.
	 */
	public String dimensionFolder(final String dimensionId) {
		String props = this.text("/dimension.properties");
		if (props != null) {
			String wildcard = null;
			for (String[] kv : parseProperties(props).entrySetArray()) {
				if (!kv[0].startsWith("dimension.")) {
					continue;
				}
				String folder = kv[0].substring("dimension.".length());
				for (String id : kv[1].trim().split("\\s+")) {
					if (id.equals("*")) {
						wildcard = folder;
					} else if (id.equals(dimensionId) || ("minecraft:" + id).equals(dimensionId)) {
						return this.hasPrograms(folder) ? folder : "";
					}
				}
			}
			if (wildcard != null && this.hasPrograms(wildcard)) {
				return wildcard;
			}
		}
		String legacy;
		switch (dimensionId) {
			case "minecraft:the_nether": legacy = "world-1"; break;
			case "minecraft:the_end": legacy = "world1"; break;
			case "minecraft:overworld": legacy = "world0"; break;
			default: legacy = null;
		}
		return legacy != null && this.hasPrograms(legacy) ? legacy : "";
	}

	private boolean hasPrograms(final String folder) {
		String prefix = ("/" + folder + "/").toLowerCase(Locale.ROOT);
		for (String p : this.files.keySet()) {
			String lp = p.toLowerCase(Locale.ROOT);
			if (lp.startsWith(prefix) && (lp.endsWith(".fsh") || lp.endsWith(".vsh"))) {
				return true;
			}
		}
		return false;
	}

	/** Absolute path of a program stage in a dimension folder, falling back to the root; null if absent. */
	public String programFile(final String folder, final String program, final String extension) {
		if (!folder.isEmpty()) {
			String p = "/" + folder + "/" + program + extension;
			String found = this.canonicalPath(p);
			if (found != null) {
				return found;
			}
			// a dimension folder that has programs of its own is complete; do not mix in the root's
			return null;
		}
		return this.canonicalPath("/" + program + extension);
	}

	/** Program names present in a folder, for listing (composite, composite1, final, ...). */
	public List<String> programNames(final String folder) {
		String prefix = (folder.isEmpty() ? "/" : "/" + folder + "/").toLowerCase(Locale.ROOT);
		List<String> names = new ArrayList<>();
		for (String p : this.files.keySet()) {
			String lp = p.toLowerCase(Locale.ROOT);
			if (lp.startsWith(prefix) && lp.endsWith(".fsh") && lp.indexOf('/', prefix.length()) < 0) {
				names.add(p.substring(prefix.length(), p.length() - 4));
			}
		}
		return names;
	}

	// ---- properties ----

	/** Ordered key/value pairs; later duplicates replace earlier ones but keep the first position. */
	public static final class Properties {
		final LinkedHashMap<String, String> map = new LinkedHashMap<>();

		public String get(final String key) {
			return this.map.get(key);
		}

		public String get(final String key, final String fallback) {
			String v = this.map.get(key);
			return v == null ? fallback : v;
		}

		public List<String[]> entrySetArray() {
			List<String[]> out = new ArrayList<>();
			for (Map.Entry<String, String> e : this.map.entrySet()) {
				out.add(new String[] {e.getKey(), e.getValue()});
			}
			return out;
		}

		public Map<String, String> asMap() {
			return this.map;
		}
	}

	/**
	 * Java-properties-style parsing of already preprocessed text: handles odd-backslash line
	 * continuations (stripping leading whitespace on continuation lines), flushes any trailing
	 * logical line, and splits on the first unescaped {@code =} or {@code :}.
	 */
	public static Properties parseProperties(final String text) {
		Properties p = new Properties();
		if (text == null || text.isEmpty()) {
			return p;
		}
		StringBuilder logical = new StringBuilder();
		boolean continuing = false;
		for (String rawLine : text.split("\n", -1)) {
			String line = rawLine.replace("\r", "");
			if (!continuing) {
				String trimmedStart = stripLeadingWhitespace(line);
				if (trimmedStart.isEmpty() || trimmedStart.charAt(0) == '#' || trimmedStart.charAt(0) == '!') {
					continue;
				}
			} else {
				line = stripLeadingWhitespace(line);
			}
			if (endsWithOddBackslashes(line)) {
				logical.append(line, 0, line.length() - 1);
				continuing = true;
				continue;
			}
			logical.append(line);
			continuing = false;
			parsePropertyEntry(logical.toString(), p);
			logical.setLength(0);
		}
		if (logical.length() > 0) {
			parsePropertyEntry(logical.toString(), p);
		}
		return p;
	}

	private static void parsePropertyEntry(final String raw, final Properties p) {
		String l = raw.trim();
		if (l.isEmpty() || l.charAt(0) == '#' || l.charAt(0) == '!') {
			return;
		}
		int sep = -1;
		for (int i = 0; i < l.length(); i++) {
			char c = l.charAt(i);
			if (c == '\\') {
				i++;
			} else if (c == '=' || c == ':') {
				sep = i;
				break;
			}
		}
		if (sep < 0) {
			return;
		}
		String key = unescapeKey(l.substring(0, sep).trim());
		String val = l.substring(sep + 1).trim();
		if (!key.isEmpty()) {
			p.map.put(key, val);
		}
	}

	private static String stripLeadingWhitespace(final String s) {
		int i = 0;
		while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\f')) {
			i++;
		}
		return s.substring(i);
	}

	private static boolean endsWithOddBackslashes(final String s) {
		int count = 0;
		for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
			count++;
		}
		return (count & 1) == 1;
	}

	private static String unescapeKey(final String s) {
		if (s.indexOf('\\') < 0) {
			return s;
		}
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				out.append(s.charAt(++i));
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	// ---- render target directives ----

	public static final int MAX_COLOR_TARGETS = 16;
	public static final int MAX_SHADOW_COLOR_TARGETS = 8;

	/** Legacy names of colortex0..7. */
	public static final String[] LEGACY_NAMES = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};

	/** Index of a buffer name (colortex5, gaux2, ...) or -1. */
	public static int colorTargetIndex(final String name) {
		if (name == null) {
			return -1;
		}
		if (name.startsWith("colortex")) {
			try {
				int i = Integer.parseInt(name.substring("colortex".length()));
				return i >= 0 && i < MAX_COLOR_TARGETS ? i : -1;
			} catch (NumberFormatException e) {
				return -1;
			}
		}
		for (int i = 0; i < LEGACY_NAMES.length; i++) {
			if (LEGACY_NAMES[i].equals(name)) {
				return i;
			}
		}
		return -1;
	}

	/** Index of a shadowcolor name (shadowcolor0..7, shadowColor0..7, shadowcolor) or -1. */
	public static int shadowColorTargetIndex(final String name) {
		if (name == null) {
			return -1;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.equals("shadowcolor")) {
			return 0;
		}
		if (lower.startsWith("shadowcolor")) {
			try {
				int i = Integer.parseInt(lower.substring("shadowcolor".length()));
				return i >= 0 && i < MAX_SHADOW_COLOR_TARGETS ? i : -1;
			} catch (NumberFormatException e) {
				return -1;
			}
		}
		return -1;
	}

	/** Relative or fixed buffer size override from {@code size.buffer.<buf>}. */
	public static final class BufferSize {
		public final boolean relative;
		public final float width;
		public final float height;

		public BufferSize(final boolean relative, final float width, final float height) {
			this.relative = relative;
			this.width = width;
			this.height = height;
		}
	}

	/** Pack-wide settings collected from the {@code const} lines of every program and {@code shaders.properties}. */
	public static final class Directives {
		public final String[] formats = new String[MAX_COLOR_TARGETS];
		public final Boolean[] clear = new Boolean[MAX_COLOR_TARGETS];
		public final float[][] clearColor = new float[MAX_COLOR_TARGETS][];
		public final boolean[] mipmap = new boolean[MAX_COLOR_TARGETS];
		public final boolean[] nearest = new boolean[MAX_COLOR_TARGETS];
		public final BufferSize[] bufferSizes = new BufferSize[MAX_COLOR_TARGETS];
		public final String[] shadowColorFormats = new String[MAX_SHADOW_COLOR_TARGETS];
		public final Boolean[] shadowColorClear = new Boolean[MAX_SHADOW_COLOR_TARGETS];
		public final float[][] shadowColorClearColor = new float[MAX_SHADOW_COLOR_TARGETS][];
		public final boolean[] shadowColorMipmap = new boolean[MAX_SHADOW_COLOR_TARGETS];
		public final boolean[] shadowColorNearest = new boolean[MAX_SHADOW_COLOR_TARGETS];
		public final boolean[] shadowHardwareFiltering = new boolean[2];
		public final boolean[] shadowTexNearest = new boolean[2];
		public final boolean[] shadowTexMipmap = new boolean[2];
		public boolean separateAo;
		public Boolean oldLighting;
		public Boolean oldHandLight;
		public Boolean shadowFrustumCulling;
		public Boolean particlesBeforeDeferred;
		public Boolean separateHardwareSamplers;
		public final Map<String, String> consts = new HashMap<>();
		/** Directive keys set from active GLSL code (cannot be overwritten by block comments). */
		final Set<String> fromActiveCode = new HashSet<>();

		public float constFloat(final String name, final float fallback) {
			String v = this.consts.get(name);
			if (v == null) {
				return fallback;
			}
			return evalConstFloat(v, this.consts, fallback);
		}

		public boolean constBool(final String name, final boolean fallback) {
			String v = this.consts.get(name);
			if (v == null) {
				return fallback;
			}
			String t = v.trim();
			if (t.equals("true") || t.equals("1")) {
				return true;
			}
			if (t.equals("false") || t.equals("0")) {
				return false;
			}
			return fallback;
		}
	}

	private static final Pattern CONST_LINE = Pattern.compile(
		"\\bconst\\s+(int|float|bool|vec4|vec3|vec2)\\s+(\\w+)\\s*=\\s*([^;]+);");

	/**
	 * Reads {@code const} directives from active code and {@code /* ... *\/} block comments (never
	 * {@code //} line comments). Active code declarations always take precedence over commented ones.
	 */
	public static void collectDirectives(final GlslPreprocessor.Result result, final Directives d) {
		for (String comment : result.blockComments) {
			if (comment.contains("const")) {
				scanConsts(comment, d, false);
			}
		}
		scanConsts(result.code.toString(), d, true);
	}

	private static void scanConsts(final String text, final Directives d, final boolean activeCode) {
		Matcher m = CONST_LINE.matcher(text);
		while (m.find()) {
			String name = m.group(2);
			String value = m.group(3).trim();
			if (!activeCode && d.fromActiveCode.contains(name)) {
				continue;
			}
			if (activeCode) {
				d.fromActiveCode.add(name);
			}
			if (name.endsWith("Format")) {
				String base = name.substring(0, name.length() - "Format".length());
				int idx = colorTargetIndex(base);
				if (idx >= 0) {
					d.formats[idx] = value.toUpperCase(Locale.ROOT);
					continue;
				}
				int sidx = shadowColorTargetIndex(base);
				if (sidx >= 0) {
					d.shadowColorFormats[sidx] = value.toUpperCase(Locale.ROOT);
					continue;
				}
			}
			if (name.endsWith("ClearColor")) {
				String base = name.substring(0, name.length() - "ClearColor".length());
				int idx = colorTargetIndex(base);
				if (idx >= 0) {
					d.clearColor[idx] = parseVec4(value, d.consts);
					continue;
				}
				int sidx = shadowColorTargetIndex(base);
				if (sidx >= 0) {
					d.shadowColorClearColor[sidx] = parseVec4(value, d.consts);
					continue;
				}
			}
			if (name.endsWith("Clear")) {
				String base = name.substring(0, name.length() - "Clear".length());
				int idx = colorTargetIndex(base);
				if (idx >= 0) {
					d.clear[idx] = value.equals("true");
					continue;
				}
				int sidx = shadowColorTargetIndex(base);
				if (sidx >= 0) {
					d.shadowColorClear[sidx] = value.equals("true");
					continue;
				}
			}
			if (name.endsWith("MipmapEnabled") || name.endsWith("Mipmap")) {
				String base = name.endsWith("MipmapEnabled")
					? name.substring(0, name.length() - "MipmapEnabled".length())
					: name.substring(0, name.length() - "Mipmap".length());
				int idx = colorTargetIndex(base);
				if (idx >= 0) {
					d.mipmap[idx] = value.equals("true");
					continue;
				}
				int sidx = shadowColorTargetIndex(base);
				if (sidx >= 0) {
					d.shadowColorMipmap[sidx] = value.equals("true");
					continue;
				}
			}
			if (name.endsWith("MinMagNearest") || name.endsWith("Nearest")) {
				String base = name.endsWith("MinMagNearest")
					? name.substring(0, name.length() - "MinMagNearest".length())
					: name.substring(0, name.length() - "Nearest".length());
				int idx = colorTargetIndex(base);
				if (idx >= 0) {
					d.nearest[idx] = value.equals("true");
					continue;
				}
				int sidx = shadowColorTargetIndex(base);
				if (sidx >= 0) {
					d.shadowColorNearest[sidx] = value.equals("true");
					continue;
				}
			}
			boolean boolVal = value.equals("true");
			switch (name) {
				case "shadowHardwareFiltering":
					d.shadowHardwareFiltering[0] = boolVal;
					d.shadowHardwareFiltering[1] = boolVal;
					break;
				case "shadowHardwareFiltering0":
					d.shadowHardwareFiltering[0] = boolVal;
					break;
				case "shadowHardwareFiltering1":
					d.shadowHardwareFiltering[1] = boolVal;
					break;
				case "shadowtexNearest":
				case "shadowtex0Nearest":
				case "shadow0MinMagNearest":
					d.shadowTexNearest[0] = boolVal;
					break;
				case "shadowtex1Nearest":
				case "shadow1MinMagNearest":
					d.shadowTexNearest[1] = boolVal;
					break;
				case "generateShadowMipmap":
					d.shadowTexMipmap[0] = boolVal;
					d.shadowTexMipmap[1] = boolVal;
					break;
				case "shadowtexMipmap":
				case "shadowtex0Mipmap":
					d.shadowTexMipmap[0] = boolVal;
					break;
				case "shadowtex1Mipmap":
					d.shadowTexMipmap[1] = boolVal;
					break;
				case "generateShadowColorMipmap":
					d.shadowColorMipmap[0] = boolVal;
					d.shadowColorMipmap[1] = boolVal;
					break;
				default:
					break;
			}
			d.consts.put(name, value);
		}
	}

	static float evalConstFloat(final String expr, final Map<String, String> consts, final float fallback) {
		String t = expr == null ? "" : expr.trim();
		if (t.isEmpty()) {
			return fallback;
		}
		try {
			return (float) GlslPreprocessor.parseNumber(t);
		} catch (RuntimeException ignored) {
			// fall through to expression evaluator
		}
		try {
			PackExpression parsed = PackExpression.parse(t);
			PackExpression.Context ctx = new PackExpression.Context();
			Set<String> visiting = new HashSet<>();
			ctx.scope = varName -> {
				if (consts == null || !visiting.add(varName)) {
					return null;
				}
				try {
					String v = consts.get(varName);
					if (v == null) {
						return null;
					}
					return new float[] {evalConstFloat(v, consts, 0.0F)};
				} finally {
					visiting.remove(varName);
				}
			};
			float[] r = parsed.eval(ctx);
			if (r != null && r.length > 0 && Float.isFinite(r[0])) {
				return r[0];
			}
		} catch (RuntimeException ignored) {
			// not an arithmetic expression
		}
		return fallback;
	}

	static float[] parseVec4(final String value, final Map<String, String> consts) {
		float[] out = {0, 0, 0, 0};
		if (value == null || value.trim().isEmpty()) {
			return out;
		}
		String trimmed = value.trim();
		int open = trimmed.indexOf('(');
		int close = trimmed.lastIndexOf(')');
		if (open >= 0 && close > open) {
			// evaluate as vec4(...) expression first so nested calls/math work
			try {
				PackExpression parsed = PackExpression.parse(trimmed);
				PackExpression.Context ctx = new PackExpression.Context();
				ctx.scope = varName -> {
					if (consts == null) {
						return null;
					}
					String v = consts.get(varName);
					return v == null ? null : new float[] {evalConstFloat(v, consts, 0.0F)};
				};
				float[] r = parsed.eval(ctx);
				if (r != null && r.length > 0) {
					for (int i = 0; i < 4; i++) {
						out[i] = r[Math.min(i, r.length - 1)];
					}
					return out;
				}
			} catch (RuntimeException ignored) {
				// fallback to component split
			}
			List<String> parts = GlslTranslator.splitTopLevel(trimmed.substring(open + 1, close), ',');
			for (int i = 0; i < 4; i++) {
				String p = parts.get(Math.min(i, parts.size() - 1)).trim();
				out[i] = evalConstFloat(p, consts, 0.0F);
			}
			return out;
		}
		// space- or comma-separated numbers (used in shaders.properties colortexN.clearColor = r g b a)
		String[] parts = trimmed.contains(",") ? trimmed.split(",") : trimmed.split("\\s+");
		for (int i = 0; i < 4 && i < parts.length; i++) {
			out[i] = evalConstFloat(parts[i].trim(), consts, 0.0F);
		}
		if (parts.length == 3) {
			out[3] = 1.0F;
		}
		return out;
	}

	/**
	 * Draw buffer list from a fragment program's {@code /* ... *\/} block comments:
	 * {@code RENDERTARGETS: 0,1,2} wins over {@code DRAWBUFFERS:012} (one character per buffer,
	 * digits 0-9, A-F, or {@code N} for {@code -1} / {@code GL_NONE}). Within each directive type,
	 * the last active block comment wins. Null if neither is present.
	 */
	public static int[] drawBuffers(final GlslPreprocessor.Result result) {
		int[] lastRenderTargets = null;
		int[] lastDrawBuffers = null;
		for (String comment : result.blockComments) {
			int pos = 0;
			while (true) {
				int rt = comment.indexOf("RENDERTARGETS:", pos);
				if (rt < 0) {
					break;
				}
				int after = rt + "RENDERTARGETS:".length();
				lastRenderTargets = parseRenderTargets(comment.substring(after));
				pos = after;
			}
			pos = 0;
			while (true) {
				int db = comment.indexOf("DRAWBUFFERS:", pos);
				if (db < 0) {
					break;
				}
				int after = db + "DRAWBUFFERS:".length();
				lastDrawBuffers = parseDrawBuffers(comment.substring(after));
				pos = after;
			}
		}
		return lastRenderTargets != null ? lastRenderTargets : lastDrawBuffers;
	}

	private static int[] parseRenderTargets(final String tail) {
		int nl = tail.indexOf('\n');
		String line = (nl >= 0 ? tail.substring(0, nl) : tail).trim();
		if (line.isEmpty()) {
			return new int[0];
		}
		List<Integer> ids = new ArrayList<>();
		for (String part : line.split(",")) {
			String t = part.trim();
			if (t.isEmpty()) {
				break;
			}
			char c0 = t.charAt(0);
			if (c0 == 'N' || c0 == 'n') {
				if (t.length() == 1 || !Character.isLetterOrDigit(t.charAt(1))) {
					ids.add(-1);
					continue;
				}
				break;
			}
			if (c0 == '-') {
				int end = 1;
				while (end < t.length() && Character.isDigit(t.charAt(end))) {
					end++;
				}
				if (end == 1) {
					break;
				}
				int val = Integer.parseInt(t.substring(0, end));
				ids.add(val < 0 ? -1 : val);
				continue;
			}
			int end = 0;
			while (end < t.length() && Character.isDigit(t.charAt(end))) {
				end++;
			}
			if (end == 0) {
				break;
			}
			ids.add(Integer.parseInt(t.substring(0, end)));
		}
		int[] a = new int[ids.size()];
		for (int i = 0; i < a.length; i++) {
			a[i] = ids.get(i);
		}
		return a;
	}

	private static int[] parseDrawBuffers(final String tail) {
		String list = tail.trim();
		List<Integer> ids = new ArrayList<>();
		for (int i = 0; i < list.length(); i++) {
			char c = list.charAt(i);
			if (c == 'N' || c == 'n') {
				ids.add(-1);
				continue;
			}
			int v = Character.digit(c, 16);
			if (v < 0) {
				break;
			}
			ids.add(v);
		}
		int[] a = new int[ids.size()];
		for (int i = 0; i < a.length; i++) {
			a[i] = ids.get(i);
		}
		return a;
	}
}
