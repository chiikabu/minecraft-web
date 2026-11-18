// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShaderPack {
	public final String name;
	private final Map<String, byte[]> files;
	private final Map<String, String> textCache = new HashMap<>();

	private ShaderPack(final String name, final Map<String, byte[]> files) {
		this.name = name;
		this.files = files;
	}

	public static ShaderPack fromZipEntries(final String name, final Map<String, byte[]> entries) {
		String prefix = findRoot(entries.keySet());
		if (prefix == null) {
			return null;
		}
		Map<String, byte[]> files = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String path = e.getKey().replace('\\', '/');
			if (path.startsWith(prefix) && !path.endsWith("/")) {
				files.put("/" + path.substring(prefix.length()), e.getValue());
			}
		}
		return new ShaderPack(name, files);
	}

	public static String findRoot(final Iterable<String> names) {
		String best = null;
		for (String raw : names) {
			String n = raw.replace('\\', '/');
			int at = n.indexOf("shaders/");
			while (at >= 0) {
				if (at == 0 || n.charAt(at - 1) == '/') {
					String prefix = n.substring(0, at + "shaders/".length());
					String rest = n.substring(prefix.length());
					boolean program = rest.endsWith(".fsh") || rest.endsWith(".vsh") || rest.equals("shaders.properties");
					int depth = prefix.length() - prefix.replace("/", "").length();
					if (program && depth <= 2 && (best == null || prefix.length() < best.length())) {
						best = prefix;
					}
				}
				at = n.indexOf("shaders/", at + 1);
			}
		}
		return best;
	}

	public boolean exists(final String path) {
		return this.files.containsKey(path);
	}

	public byte[] bytes(final String path) {
		return this.files.get(path);
	}

	public String text(final String path) {
		String cached = this.textCache.get(path);
		if (cached != null) {
			return cached;
		}
		byte[] b = this.files.get(path);
		if (b == null) {
			return null;
		}
		String s = new String(b, StandardCharsets.UTF_8);
		if (!s.isEmpty() && s.charAt(0) == (char) 0xFEFF) {
			s = s.substring(1);
		}
		this.textCache.put(path, s);
		return s;
	}

	public Iterable<String> paths() {
		return this.files.keySet();
	}

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
		String prefix = "/" + folder + "/";
		for (String p : this.files.keySet()) {
			if (p.startsWith(prefix) && (p.endsWith(".fsh") || p.endsWith(".vsh"))) {
				return true;
			}
		}
		return false;
	}

	public String programFile(final String folder, final String program, final String extension) {
		if (!folder.isEmpty()) {
			String p = "/" + folder + "/" + program + extension;
			if (this.exists(p)) {
				return p;
			}
			return null;
		}
		String p = "/" + program + extension;
		return this.exists(p) ? p : null;
	}

	public List<String> programNames(final String folder) {
		String prefix = folder.isEmpty() ? "/" : "/" + folder + "/";
		List<String> names = new ArrayList<>();
		for (String p : this.files.keySet()) {
			if (p.startsWith(prefix) && p.endsWith(".fsh") && p.indexOf('/', prefix.length()) < 0) {
				names.add(p.substring(prefix.length(), p.length() - 4));
			}
		}
		return names;
	}

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

	public static Properties parseProperties(final String text) {
		Properties p = new Properties();
		StringBuilder logical = new StringBuilder();
		for (String rawLine : text.split("\n")) {
			String line = rawLine.replace("\r", "");
			if (line.endsWith("\\")) {
				logical.append(line, 0, line.length() - 1);
				continue;
			}
			logical.append(line);
			String l = logical.toString().trim();
			logical.setLength(0);
			if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) {
				continue;
			}
			int eq = l.indexOf('=');
			int colon = l.indexOf(':');
			int sep = eq < 0 ? colon : (colon < 0 ? eq : Math.min(eq, colon));
			if (eq >= 0) {
				sep = eq;
			}
			if (sep < 0) {
				continue;
			}
			p.map.put(l.substring(0, sep).trim(), l.substring(sep + 1).trim());
		}
		return p;
	}

	public static final int MAX_COLOR_TARGETS = 16;

	public static final String[] LEGACY_NAMES = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};

	public static int colorTargetIndex(final String name) {
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

	public static final class Directives {
		public final String[] formats = new String[MAX_COLOR_TARGETS];
		public final Boolean[] clear = new Boolean[MAX_COLOR_TARGETS];
		public final float[][] clearColor = new float[MAX_COLOR_TARGETS][];
		public final boolean[] mipmap = new boolean[MAX_COLOR_TARGETS];
		public final Map<String, String> consts = new HashMap<>();

		public float constFloat(final String name, final float fallback) {
			String v = this.consts.get(name);
			if (v == null) {
				return fallback;
			}
			try {
				return (float) GlslPreprocessor.parseNumber(v.trim());
			} catch (RuntimeException e) {
				return fallback;
			}
		}

		public boolean constBool(final String name, final boolean fallback) {
			String v = this.consts.get(name);
			return v == null ? fallback : v.trim().equals("true");
		}
	}

	private static final java.util.regex.Pattern CONST_LINE = java.util.regex.Pattern.compile(
		"\\bconst\\s+(int|float|bool|vec4|vec3|vec2)\\s+(\\w+)\\s*=\\s*([^;]+);");

	public static void collectDirectives(final GlslPreprocessor.Result result, final Directives d) {
		scanConsts(result.code.toString(), d);
		for (String comment : result.comments) {
			if (comment.contains("const")) {
				scanConsts(comment, d);
			}
		}
	}

	private static void scanConsts(final String text, final Directives d) {
		java.util.regex.Matcher m = CONST_LINE.matcher(text);
		while (m.find()) {
			String name = m.group(2);
			String value = m.group(3).trim();
			if (name.endsWith("Format")) {
				int idx = colorTargetIndex(name.substring(0, name.length() - "Format".length()));
				if (idx >= 0) {
					d.formats[idx] = value.toUpperCase(Locale.ROOT);
				}
				continue;
			}
			if (name.endsWith("ClearColor")) {
				int idx = colorTargetIndex(name.substring(0, name.length() - "ClearColor".length()));
				if (idx >= 0) {
					d.clearColor[idx] = parseVec4(value);
				}
				continue;
			}
			if (name.endsWith("Clear")) {
				int idx = colorTargetIndex(name.substring(0, name.length() - "Clear".length()));
				if (idx >= 0) {
					d.clear[idx] = value.equals("true");
				}
				continue;
			}
			if (name.endsWith("MipmapEnabled")) {
				int idx = colorTargetIndex(name.substring(0, name.length() - "MipmapEnabled".length()));
				if (idx >= 0) {
					d.mipmap[idx] = value.equals("true");
				}
				continue;
			}
			d.consts.put(name, value);
		}
	}

	private static float[] parseVec4(final String value) {
		float[] out = {0, 0, 0, 0};
		int open = value.indexOf('(');
		int close = value.lastIndexOf(')');
		if (open < 0 || close < open) {
			return out;
		}
		String[] parts = value.substring(open + 1, close).split(",");
		for (int i = 0; i < 4; i++) {
			String p = parts[Math.min(i, parts.length - 1)].trim();
			try {
				out[i] = (float) GlslPreprocessor.parseNumber(p);
			} catch (RuntimeException e) {
				out[i] = 0;
			}
		}
		return out;
	}

	public static int[] drawBuffers(final GlslPreprocessor.Result result) {
		int[] found = null;
		for (String comment : result.comments) {
			int rt = comment.indexOf("RENDERTARGETS:");
			if (rt >= 0) {
				String list = comment.substring(rt + "RENDERTARGETS:".length()).trim();
				List<Integer> ids = new ArrayList<>();
				for (String part : list.split(",")) {
					String t = part.trim();
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
			int db = comment.indexOf("DRAWBUFFERS:");
			if (db >= 0 && found == null) {
				String list = comment.substring(db + "DRAWBUFFERS:".length()).trim();
				List<Integer> ids = new ArrayList<>();
				for (int i = 0; i < list.length(); i++) {
					char c = list.charAt(i);
					int v = Character.digit(c, 16);
					if (v < 0) {
						break;
					}
					ids.add(v);
				}
				found = new int[ids.size()];
				for (int i = 0; i < found.length; i++) {
					found[i] = ids.get(i);
				}
			}
		}
		return found;
	}
}
