// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A shader pack's user options, discovered the way OptiFine and Iris do it, plus the option menu
 * layout, the profiles and the English labels.
 *
 * <p>Options come from the raw lines of every shader source in the pack and of every file those
 * include (not just the files one program reaches, so the result depends on neither the dimension
 * nor the option values):
 * <ul>
 * <li>value options: {@code #define NAME VALUE // comment [v1 v2 v3]} or {@code /* [v1 v2 v3] *\/}
 * (the list is required; the default is appended when the list lacks it);</li>
 * <li>boolean options: {@code #define NAME} (on) and {@code //#define NAME} (off), each with an
 * optional trailing comment, counted only when {@code #ifdef NAME}, {@code #ifndef NAME},
 * {@code #if/#elif defined(NAME)}, or a {@code shaders.properties}/{@code block.properties}
 * reference appears somewhere in the pack;</li>
 * <li>const options: {@code const int|float NAME = V; // [list]}, and {@code const bool NAME = true|false;}
 * for the engine settings Iris accepts.</li>
 * </ul>
 * The same name with different defaults in different places is ambiguous and ignored, as in Iris.
 *
 * <p>The menu ({@code screen}, {@code screen.<NAME>}, {@code *.columns}), {@code sliders} and
 * {@code profile.*} are read from {@code shaders.properties} preprocessed in properties mode with
 * standard macros ({@code IS_IRIS}, {@code MC_VERSION}, etc.), without user option values.
 *
 * <p>Pure Java (no TeaVM or Minecraft imports) so the desktop harness can run it over real packs.
 */
public final class PackOptions {
	/** One user option. */
	public static final class Option {
		public final String name;
		/** A boolean define, or a {@code const bool}. Its values are "true"/"false". */
		public final boolean bool;
		/** A {@code const} line rather than a {@code #define}. */
		public final boolean isConst;
		/** "true"/"false" for booleans, else the value as written. */
		public final String defaultValue;
		/** Allowed values in the pack's order (booleans: false, true). */
		public final List<String> values;
		/** The trailing source comment without the value list; the fallback tooltip. */
		public final String sourceComment;
		/** Where it was first found. */
		public final String file;

		Option(final String name, final boolean bool, final boolean isConst, final String defaultValue, final List<String> values,
			final String sourceComment, final String file) {
			this.name = name;
			this.bool = bool;
			this.isConst = isConst;
			this.defaultValue = defaultValue;
			this.values = values;
			this.sourceComment = sourceComment;
			this.file = file;
		}
	}

	/** One cell of an option screen. */
	public static final class Element {
		public enum Kind {
			/** An option button or slider. */
			OPTION,
			/** {@code [NAME]}: a button opening another screen. */
			SCREEN,
			/** {@code <profile>}: the profile switcher. */
			PROFILE,
			/** {@code <empty>}: a blank cell. */
			EMPTY,
			/** A name that is neither an option nor known; shown disabled so the layout keeps its shape. */
			UNKNOWN
		}

		public final Kind kind;
		public final String name;

		Element(final Kind kind, final String name) {
			this.kind = kind;
			this.name = name;
		}
	}

	/** The main screen ({@code name == null}) or a sub screen. */
	public static final class Screen {
		public final String name;
		public final int columns;
		public final List<Element> elements;

		Screen(final String name, final int columns, final List<Element> elements) {
			this.name = name;
			this.columns = columns;
			this.elements = elements;
		}
	}

	/** {@code profile.<NAME>}, with inherited profiles already folded in. */
	public static final class Profile {
		public final String name;
		/** Option values the profile sets, in order. */
		public final Map<String, String> values = new LinkedHashMap<>();
		/** {@code !program.X} entries disabled by this profile. */
		public final List<String> disabledPrograms = new ArrayList<>();

		Profile(final String name) {
			this.name = name;
		}
	}

	/**
	 * {@code const} names Iris accepts as options. Numeric consts are also accepted under any name
	 * when they carry a value list; {@code const bool} lines have no list, so only these count.
	 */
	private static final Set<String> CONST_NAMES = new HashSet<>();

	static {
		Collections.addAll(CONST_NAMES, "shadowMapResolution", "shadowDistance", "voxelDistance", "shadowDistanceRenderMul",
			"entityShadowDistanceMul", "shadowIntervalSize", "generateShadowMipmap", "generateShadowColorMipmap", "shadowHardwareFiltering",
			"shadowtex0Mipmap", "shadowtexMipmap", "shadowtex1Mipmap", "shadowtex0Nearest", "shadowtexNearest", "shadow0MinMagNearest",
			"shadowtex1Nearest", "shadow1MinMagNearest", "wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife", "centerDepthHalflife",
			"sunPathRotation", "ambientOcclusionLevel", "superSamplingLevel", "noiseTextureResolution",
			"shadowOptimization", "oldLighting", "oldHandLight");
		for (int i = 0; i < 8; i++) {
			Collections.addAll(CONST_NAMES, "shadowcolor" + i + "Mipmap", "shadowColor" + i + "Mipmap",
				"shadowcolor" + i + "MipmapEnabled", "shadowColor" + i + "MipmapEnabled",
				"shadowcolor" + i + "Nearest", "shadowColor" + i + "Nearest",
				"shadowcolor" + i + "MinMagNearest", "shadowColor" + i + "MinMagNearest",
				"shadowcolor" + i + "Clear", "shadowColor" + i + "Clear",
				"shadowHardwareFiltering" + i);
		}
		for (int i = 0; i < ShaderPack.MAX_COLOR_TARGETS; i++) {
			String c = "colortex" + i;
			Collections.addAll(CONST_NAMES, c + "MipmapEnabled", c + "Mipmap", c + "Clear", c + "Nearest", c + "MinMagNearest");
		}
		for (String legacy : ShaderPack.LEGACY_NAMES) {
			Collections.addAll(CONST_NAMES, legacy + "MipmapEnabled", legacy + "Mipmap", legacy + "Clear",
				legacy + "Nearest", legacy + "MinMagNearest");
		}
	}

	private static final String VALUE_TOKEN =
		"(?:\\(\\s*[-+]?(?:0[xX][0-9a-fA-F]+[uUlL]*|(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?[fFuUlL]*)\\s*\\)"
		+ "|[-+]?(?:0[xX][0-9a-fA-F]+[uUlL]*|(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?[fFuUlL]*)"
		+ "|[A-Za-z_]\\w*)";
	private static final String TRAILING_COMMENT = "(?://(.*)|/\\*(.*?)\\*/.*)";

	private static final Pattern DEFINE_BOOL = Pattern.compile("#\\s*define\\s+([A-Za-z_]\\w*)\\s*(?:" + TRAILING_COMMENT + ")?");
	private static final Pattern DEFINE_OFF = Pattern.compile("//\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s*(?:" + TRAILING_COMMENT + ")?");
	private static final Pattern DEFINE_VALUE = Pattern.compile("#\\s*define\\s+([A-Za-z_]\\w*)\\s+(" + VALUE_TOKEN + ")\\s*" + TRAILING_COMMENT);
	private static final Pattern CONST = Pattern.compile(
		"const\\s+(int|float|bool)\\s+([A-Za-z_]\\w*)\\s*=\\s*(" + VALUE_TOKEN + ")\\s*;\\s*(?:" + TRAILING_COMMENT + ")?");
	private static final Pattern IFDEF = Pattern.compile("#\\s*ifn?def\\s+([A-Za-z_]\\w*).*");
	private static final Pattern IF_ELIF = Pattern.compile("#\\s*(?:el)?if\\b(.*)");
	private static final Pattern DEFINED_CALL = Pattern.compile("\\bdefined\\s*(?:\\(\\s*([A-Za-z_]\\w*)\\s*\\)|([A-Za-z_]\\w*))");
	private static final Pattern LIST = Pattern.compile("\\[([^\\]]*)\\]");
	private static final Pattern INCLUDE = Pattern.compile("#\\s*include\\s+[\"<]([^\">]+)[\">].*");

	private final Map<String, Option> options = new LinkedHashMap<>();
	private Screen main;
	private final Map<String, Screen> screens = new LinkedHashMap<>();
	private final Map<String, Profile> profiles = new LinkedHashMap<>();
	private final Set<String> profileOptions = new LinkedHashSet<>();
	private final Set<String> sliders = new LinkedHashSet<>();
	private final Map<String, String> lang = new HashMap<>();
	/** Ambiguous options, unknown menu entries and the like, for the log. */
	public final List<String> warnings = new ArrayList<>();
	/** Counts by kind, for logs and the desktop check. */
	public int boolCount;
	public int valueCount;
	public int constCount;

	private PackOptions() {
	}

	// ---- discovery ----

	/** One sighting of an option-shaped line. */
	private static final class Sighting {
		final int kind; // 0 boolean define, 1 value define, 2 numeric const, 3 const bool
		final String value;
		final List<String> list;
		final String comment;
		final String file;

		Sighting(final int kind, final String value, final List<String> list, final String comment, final String file) {
			this.kind = kind;
			this.value = value;
			this.list = list;
			this.comment = comment;
			this.file = file;
		}
	}

	private static boolean isShaderSource(final String path) {
		String p = path.toLowerCase(Locale.ROOT);
		return p.endsWith(".glsl") || p.endsWith(".fsh") || p.endsWith(".vsh") || p.endsWith(".csh") || p.endsWith(".gsh");
	}

	public static PackOptions parse(final ShaderPack pack) {
		PackOptions out = new PackOptions();
		Map<String, List<Sighting>> seen = new LinkedHashMap<>();
		Set<String> ifdefs = new HashSet<>();
		List<String> paths = new ArrayList<>();
		for (String p : pack.paths()) {
			if (isShaderSource(p)) {
				paths.add(p);
			}
		}
		// included files are scanned too, whatever their extension: Sildur's keeps every option in
		// "shaders.settings", which Iris reaches through its include graph
		Set<String> queued = new HashSet<>(paths);
		for (int i = 0; i < paths.size(); i++) {
			String path = paths.get(i);
			String text = pack.text(path);
			if (text == null) {
				continue;
			}
			List<String> includes = new ArrayList<>();
			scanFile(path, text, seen, ifdefs, includes);
			for (String target : includes) {
				String abs = GlslPreprocessor.resolve(path, target);
				if (pack.exists(abs) && queued.add(abs)) {
					paths.add(abs);
				}
			}
		}
		// Preprocess shaders.properties in propertiesMode with standard macros (IS_IRIS, MC_VERSION, ...)
		String rawProps = pack.text("/shaders.properties");
		ShaderPack.Properties menuProps;
		if (rawProps != null) {
			scanPropertiesReferences(rawProps, ifdefs);
			GlslPreprocessor pp = GlslPreprocessor.forProperties(
				p -> p.equals("/shaders.properties") ? rawProps : pack.text(p),
				PackPrograms.standardMacros("minecraft:overworld"), null);
			menuProps = ShaderPack.parseProperties(pp.process("/shaders.properties").code.toString());
			collectMenuOptionReferences(menuProps, ifdefs);
		} else {
			menuProps = new ShaderPack.Properties();
		}
		String rawBlocks = pack.text("/block.properties");
		if (rawBlocks != null) {
			scanPropertiesReferences(rawBlocks, ifdefs);
		}
		out.resolveOptions(seen, ifdefs);
		out.readMenu(menuProps);
		out.readLang(pack);
		return out;
	}

	private static void scanPropertiesReferences(final String text, final Set<String> ifdefs) {
		for (String raw : text.split("\n")) {
			String line = raw.trim();
			if (!line.startsWith("#")) {
				continue;
			}
			Matcher m = IFDEF.matcher(line);
			if (m.matches()) {
				ifdefs.add(m.group(1));
				continue;
			}
			m = IF_ELIF.matcher(line);
			if (m.matches()) {
				Matcher def = DEFINED_CALL.matcher(m.group(1));
				while (def.find()) {
					ifdefs.add(def.group(1) != null ? def.group(1) : def.group(2));
				}
			}
		}
	}

	private static void collectMenuOptionReferences(final ShaderPack.Properties props, final Set<String> ifdefs) {
		for (Map.Entry<String, String> e : props.asMap().entrySet()) {
			String k = e.getKey();
			String v = e.getValue();
			if (k.equals("screen") || (k.startsWith("screen.") && !k.endsWith(".columns"))) {
				for (String t : v.trim().split("\\s+")) {
					if (!t.isEmpty() && GlslPreprocessor.isIdentStart(t.charAt(0))) {
						ifdefs.add(t);
					}
				}
			} else if (k.startsWith("profile.")) {
				for (String t : v.trim().split("\\s+")) {
					if (t.isEmpty() || t.startsWith("profile.") || t.startsWith("!program.")) {
						continue;
					}
					String opt = t.startsWith("!") ? t.substring(1) : t;
					int eq = opt.indexOf('=');
					if (eq < 0) {
						eq = opt.indexOf(':');
					}
					if (eq > 0) {
						opt = opt.substring(0, eq);
					}
					if (!opt.isEmpty() && GlslPreprocessor.isIdentStart(opt.charAt(0))) {
						ifdefs.add(opt);
					}
				}
			} else if (k.startsWith("program.") && k.endsWith(".enabled")) {
				for (String t : GlslPreprocessor.tokenize(v)) {
					if (!t.isEmpty() && GlslPreprocessor.isIdentStart(t.charAt(0))) {
						ifdefs.add(t);
					}
				}
			}
		}
	}

	private static void scanFile(final String path, final String text, final Map<String, List<Sighting>> seen, final Set<String> ifdefs,
		final List<String> includes) {
		int n = text.length();
		int start = 0;
		boolean inBlock = false;
		while (start <= n) {
			int end = text.indexOf('\n', start);
			if (end < 0) {
				end = n;
			}
			String rawLine = text.substring(start, end);
			start = end + 1;

			// Track /* ... */ block comments so commented-out #define / const lines are ignored
			String line = rawLine.trim();
			if (inBlock) {
				int close = line.indexOf("*/");
				if (close < 0) {
					if (end == n) {
						break;
					}
					continue;
				}
				inBlock = false;
				line = line.substring(close + 2).trim();
			}
			while (line.startsWith("/*")) {
				int close = line.indexOf("*/", 2);
				if (close < 0) {
					inBlock = true;
					line = "";
					break;
				}
				line = line.substring(close + 2).trim();
			}
			// Update inBlock for subsequent lines (ignoring // line comments)
			if (!inBlock && line.contains("/*")) {
				inBlock = hasUnclosedBlockComment(line);
			}

			if (line.length() < 4) {
				if (end == n) {
					break;
				}
				continue;
			}
			char c = line.charAt(0);
			if (c == '#') {
				Matcher m = IFDEF.matcher(line);
				if (m.matches()) {
					ifdefs.add(m.group(1));
					if (end == n) {
						break;
					}
					continue;
				}
				m = IF_ELIF.matcher(line);
				if (m.matches()) {
					Matcher def = DEFINED_CALL.matcher(m.group(1));
					while (def.find()) {
						ifdefs.add(def.group(1) != null ? def.group(1) : def.group(2));
					}
					if (end == n) {
						break;
					}
					continue;
				}
				m = INCLUDE.matcher(line);
				if (m.matches()) {
					includes.add(m.group(1));
					if (end == n) {
						break;
					}
					continue;
				}
				m = DEFINE_VALUE.matcher(line);
				if (m.matches()) {
					String comment = extractComment(m, 3, 4);
					Matcher list = LIST.matcher(comment);
					if (list.find()) {
						add(seen, m.group(1), new Sighting(1, m.group(2), splitList(list.group(1)), stripList(comment), path));
					}
					if (end == n) {
						break;
					}
					continue;
				}
				m = DEFINE_BOOL.matcher(line);
				if (m.matches()) {
					String comment = extractComment(m, 2, 3);
					add(seen, m.group(1), new Sighting(0, "true", null, comment.isEmpty() ? "" : stripList(comment), path));
				}
			} else if (c == '/') {
				Matcher m = DEFINE_OFF.matcher(line);
				if (m.matches()) {
					String comment = extractComment(m, 2, 3);
					add(seen, m.group(1), new Sighting(0, "false", null, comment.isEmpty() ? "" : stripList(comment), path));
				}
			} else if (c == 'c' && line.startsWith("const")) {
				Matcher m = CONST.matcher(line);
				if (m.matches()) {
					String type = m.group(1);
					String name = m.group(2);
					String value = m.group(3);
					String comment = extractComment(m, 4, 5);
					if (type.equals("bool")) {
						if (CONST_NAMES.contains(name) && (value.equals("true") || value.equals("false"))) {
							add(seen, name, new Sighting(3, value, null, stripList(comment), path));
						}
					} else {
						Matcher list = LIST.matcher(comment);
						if (list.find()) {
							add(seen, name, new Sighting(2, value, splitList(list.group(1)), stripList(comment), path));
						}
					}
				}
			}
			if (end == n) {
				break;
			}
		}
	}

	private static boolean hasUnclosedBlockComment(final String line) {
		boolean open = false;
		int len = line.length();
		for (int i = 0; i < len - 1; i++) {
			char a = line.charAt(i);
			char b = line.charAt(i + 1);
			if (!open) {
				if (a == '/' && b == '/') {
					return false;
				}
				if (a == '/' && b == '*') {
					open = true;
					i++;
				}
			} else if (a == '*' && b == '/') {
				open = false;
				i++;
			}
		}
		return open;
	}

	private static String extractComment(final Matcher m, final int slashGroup, final int blockGroup) {
		String s = m.group(slashGroup);
		if (s != null) {
			return s;
		}
		String b = m.group(blockGroup);
		return b != null ? b : "";
	}

	private static void add(final Map<String, List<Sighting>> seen, final String name, final Sighting s) {
		List<Sighting> l = seen.get(name);
		if (l == null) {
			l = new ArrayList<>();
			seen.put(name, l);
		}
		l.add(s);
	}

	private static List<String> splitList(final String list) {
		List<String> out = new ArrayList<>();
		for (String v : list.trim().split("\\s+")) {
			if (!v.isEmpty() && !out.contains(v)) {
				out.add(v);
			}
		}
		return out;
	}

	/** The comment without its value list and without leading slashes ({@code // [0 1] // text}). */
	private static String stripList(final String comment) {
		String s = LIST.matcher(comment).replaceFirst("").trim();
		int i = 0;
		while (i < s.length() && (s.charAt(i) == '/' || s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
			i++;
		}
		return s.substring(i);
	}

	/** True when two option values match literally or represent the same numeric value ({@code 128} vs {@code 128.0}). */
	public static boolean valuesEqual(final String a, final String b) {
		if (a == null || b == null) {
			return false;
		}
		if (a.equals(b)) {
			return true;
		}
		Double da = parseOptionNumber(a);
		Double db = parseOptionNumber(b);
		if (da != null && db != null) {
			return Math.abs(da - db) <= 1e-6 * Math.max(1.0, Math.max(Math.abs(da), Math.abs(db)));
		}
		return false;
	}

	private static Double parseOptionNumber(final String raw) {
		String s = raw.trim();
		while (s.length() >= 2 && s.charAt(0) == '(' && s.charAt(s.length() - 1) == ')') {
			s = s.substring(1, s.length() - 1).trim();
		}
		if (s.isEmpty()) {
			return null;
		}
		char c0 = s.charAt(0);
		if (!(Character.isDigit(c0) || c0 == '.' || c0 == '-' || c0 == '+')) {
			return null;
		}
		try {
			double d = GlslPreprocessor.parseNumber(s);
			return Double.isFinite(d) ? d : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String findMatchingValue(final List<String> values, final String candidate) {
		if (candidate == null) {
			return null;
		}
		if (values.contains(candidate)) {
			return candidate;
		}
		for (String v : values) {
			if (valuesEqual(v, candidate)) {
				return v;
			}
		}
		return null;
	}

	private void resolveOptions(final Map<String, List<Sighting>> seen, final Set<String> ifdefs) {
		for (Map.Entry<String, List<Sighting>> e : seen.entrySet()) {
			String name = e.getKey();
			List<Sighting> all = new ArrayList<>();
			for (Sighting s : e.getValue()) {
				// a bare #define that nothing tests or references is a guard or a constant, not a switch
				if (s.kind != 0 || ifdefs.contains(name)) {
					all.add(s);
				}
			}
			if (all.isEmpty()) {
				continue;
			}
			Sighting first = all.get(0);
			boolean ambiguous = false;
			List<String> values = new ArrayList<>();
			String comment = "";
			for (Sighting s : all) {
				if (s.kind != first.kind || !valuesEqual(s.value, first.value)) {
					ambiguous = true;
					break;
				}
				if (s.list != null) {
					for (String v : s.list) {
						if (findMatchingValue(values, v) == null) {
							values.add(v);
						}
					}
				}
				if (comment.isEmpty() && !s.comment.isEmpty()) {
					comment = s.comment;
				}
			}
			if (ambiguous) {
				this.warnings.add("option " + name + " has different defaults in different places; ignored");
				continue;
			}
			boolean bool = first.kind == 0 || first.kind == 3;
			String defaultVal = first.value;
			if (bool) {
				values.clear();
				values.add("false");
				values.add("true");
			} else {
				String match = findMatchingValue(values, defaultVal);
				if (match != null) {
					defaultVal = match;
				} else {
					values.add(defaultVal);
				}
			}
			Option o = new Option(name, bool, first.kind >= 2, defaultVal, Collections.unmodifiableList(values), comment, first.file);
			this.options.put(name, o);
			if (o.isConst) {
				this.constCount++;
			} else if (bool) {
				this.boolCount++;
			} else {
				this.valueCount++;
			}
		}
	}

	// ---- shaders.properties: screens, sliders, profiles ----

	private void readMenu(final ShaderPack.Properties props) {
		Map<String, String> raw = props.asMap();
		Map<String, Integer> columns = new HashMap<>();
		Map<String, String> screenLines = new LinkedHashMap<>();
		String mainLine = null;
		int mainColumns = 2;
		for (Map.Entry<String, String> e : raw.entrySet()) {
			String k = e.getKey();
			String v = e.getValue();
			if (k.equals("screen")) {
				mainLine = v;
			} else if (k.equals("screen.columns")) {
				mainColumns = parseColumns(v, 2);
			} else if (k.startsWith("screen.")) {
				String rest = k.substring("screen.".length());
				if (rest.endsWith(".columns")) {
					columns.put(rest.substring(0, rest.length() - ".columns".length()), parseColumns(v, 2));
				} else if (!rest.isEmpty()) {
					screenLines.put(rest, v);
				}
			} else if (k.equals("sliders")) {
				for (String s : v.trim().split("\\s+")) {
					if (!s.isEmpty()) {
						this.sliders.add(s);
					}
				}
			}
		}
		// profiles after the options are known: bare names mean "on" only for boolean options
		for (Map.Entry<String, String> e : raw.entrySet()) {
			if (e.getKey().startsWith("profile.") && !this.profiles.containsKey(e.getKey().substring("profile.".length()))) {
				this.resolveProfile(e.getKey().substring("profile.".length()), raw, new HashSet<>());
			}
		}
		for (Profile p : this.profiles.values()) {
			this.profileOptions.addAll(p.values.keySet());
		}
		// screens: '*' is filled in after every screen is read, with the options placed nowhere else
		Set<String> placed = new HashSet<>();
		List<Element> mainElements = mainLine == null ? null : this.elements(mainLine, placed);
		Map<String, List<Element>> subs = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : screenLines.entrySet()) {
			subs.put(e.getKey(), this.elements(e.getValue(), placed));
		}
		List<Element> rest = new ArrayList<>();
		for (Option o : this.options.values()) {
			if (!placed.contains(o.name)) {
				rest.add(new Element(Element.Kind.OPTION, o.name));
			}
		}
		if (mainElements == null) {
			mainElements = new ArrayList<>(rest);
		} else {
			mainElements = expandStar(mainElements, rest);
		}
		this.main = new Screen(null, mainColumns, Collections.unmodifiableList(mainElements));
		for (Map.Entry<String, List<Element>> e : subs.entrySet()) {
			Integer cols = columns.get(e.getKey());
			this.screens.put(e.getKey(), new Screen(e.getKey(), cols == null ? 2 : cols, Collections.unmodifiableList(expandStar(e.getValue(), rest))));
		}
		for (Screen s : this.allScreens()) {
			for (Element el : s.elements) {
				if (el.kind == Element.Kind.SCREEN && !this.screens.containsKey(el.name)) {
					this.warnings.add("screen [" + el.name + "] is not defined");
				}
			}
		}
	}

	private static int parseColumns(final String v, final int fallback) {
		try {
			int c = Integer.parseInt(v.trim());
			return c < 1 ? 1 : Math.min(c, 6);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static final String STAR = "*";

	private List<Element> elements(final String line, final Set<String> placed) {
		List<Element> out = new ArrayList<>();
		for (String t : line.trim().split("\\s+")) {
			if (t.isEmpty()) {
				continue;
			}
			if (t.equals("<empty>")) {
				out.add(new Element(Element.Kind.EMPTY, ""));
			} else if (t.equals("<profile>")) {
				out.add(new Element(Element.Kind.PROFILE, ""));
			} else if (t.equals(STAR)) {
				out.add(new Element(Element.Kind.EMPTY, STAR));
			} else if (t.length() > 2 && t.startsWith("[") && t.endsWith("]")) {
				out.add(new Element(Element.Kind.SCREEN, t.substring(1, t.length() - 1)));
			} else if (this.options.containsKey(t)) {
				placed.add(t);
				out.add(new Element(Element.Kind.OPTION, t));
			} else {
				this.warnings.add("menu entry " + t + " is not an option");
				out.add(new Element(Element.Kind.UNKNOWN, t));
			}
		}
		return out;
	}

	private static List<Element> expandStar(final List<Element> elements, final List<Element> rest) {
		List<Element> out = new ArrayList<>();
		for (Element e : elements) {
			if (e.kind == Element.Kind.EMPTY && STAR.equals(e.name)) {
				out.addAll(rest);
			} else {
				out.add(e);
			}
		}
		return out;
	}

	private Profile resolveProfile(final String name, final Map<String, String> raw, final Set<String> visiting) {
		Profile done = this.profiles.get(name);
		if (done != null) {
			return done;
		}
		String line = raw.get("profile." + name);
		if (line == null || !visiting.add(name)) {
			this.warnings.add(line == null ? "profile " + name + " is not defined" : "profile " + name + " inherits from itself");
			return null;
		}
		Profile p = new Profile(name);
		for (String t : line.trim().split("\\s+")) {
			if (t.isEmpty()) {
				continue;
			}
			if (t.startsWith("profile.")) {
				Profile parent = this.resolveProfile(t.substring("profile.".length()), raw, visiting);
				if (parent != null) {
					p.values.putAll(parent.values);
					for (String dp : parent.disabledPrograms) {
						if (!p.disabledPrograms.contains(dp)) {
							p.disabledPrograms.add(dp);
						}
					}
				}
				continue;
			}
			if (t.startsWith("!program.")) {
				String prog = t.substring("!program.".length());
				if (!p.disabledPrograms.contains(prog)) {
					p.disabledPrograms.add(prog);
				}
				continue;
			}
			if (t.startsWith("program.")) {
				p.disabledPrograms.remove(t.substring("program.".length()));
				continue;
			}
			String opt;
			String value;
			if (t.startsWith("!")) {
				opt = t.substring(1);
				value = "false";
			} else {
				int eq = t.indexOf('=');
				if (eq < 0) {
					eq = t.indexOf(':');
				}
				if (eq > 0) {
					opt = t.substring(0, eq);
					value = t.substring(eq + 1);
				} else {
					opt = t;
					value = "true";
				}
			}
			Option o = this.options.get(opt);
			if (o == null) {
				continue;
			}
			String canonical;
			if (o.bool) {
				canonical = (value.equals("true") || value.equals("false")) ? value : null;
			} else {
				canonical = findMatchingValue(o.values, value);
			}
			if (canonical == null) {
				this.warnings.add("profile " + name + ": " + opt + "=" + value + " is not an allowed value");
				continue;
			}
			p.values.remove(opt);
			p.values.put(opt, canonical);
		}
		visiting.remove(name);
		this.profiles.put(name, p);
		return p;
	}

	// ---- language ----

	private void readLang(final ShaderPack pack) {
		String text = pack.text("/lang/en_US.lang");
		if (text == null) {
			text = pack.text("/lang/en_us.lang");
		}
		if (text != null) {
			parseLang(text, this.lang);
		}
	}

	/** Java-properties-style lines with the common escapes ({@code \n}, {@code \t}, {@code \\uXXXX}). */
	static void parseLang(final String text, final Map<String, String> out) {
		StringBuilder logical = new StringBuilder();
		for (String rawLine : text.split("\n")) {
			String line = rawLine.replace("\r", "");
			String trimmedStart = logical.length() > 0 ? stripLeading(line) : line;
			if (endsWithOddBackslashes(trimmedStart)) {
				logical.append(trimmedStart, 0, trimmedStart.length() - 1);
				continue;
			}
			logical.append(trimmedStart);
			String l = stripLeading(logical.toString());
			logical.setLength(0);
			if (l.isEmpty() || l.charAt(0) == '#' || l.charAt(0) == '!') {
				continue;
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
				continue;
			}
			String key = unescape(l.substring(0, sep).trim());
			String value = unescape(stripLeading(l.substring(sep + 1)));
			out.put(key, value);
		}
	}

	private static String stripLeading(final String s) {
		int i = 0;
		while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
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

	private static String unescape(final String s) {
		if (s.indexOf('\\') < 0) {
			return s;
		}
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c != '\\' || i + 1 >= s.length()) {
				out.append(c);
				continue;
			}
			char d = s.charAt(++i);
			switch (d) {
				case 'n': out.append('\n'); break;
				case 't': out.append('\t'); break;
				case 'r': break;
				case 'u':
					if (i + 4 < s.length()) {
						try {
							out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
							i += 4;
							break;
						} catch (NumberFormatException e) {
							// fall through to the literal
						}
					}
					out.append('u');
					break;
				default: out.append(d);
			}
		}
		return out.toString();
	}

	/** Removes Minecraft {@code §x} formatting codes. */
	public static String stripFormatting(final String s) {
		if (s == null || s.indexOf('§') < 0) {
			return s;
		}
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '§') {
				i++;
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	// ---- queries ----

	public Collection<Option> options() {
		return Collections.unmodifiableCollection(this.options.values());
	}

	public Option option(final String name) {
		return this.options.get(name);
	}

	public Screen mainScreen() {
		return this.main;
	}

	/** A sub screen by name, or null. */
	public Screen screen(final String name) {
		return this.screens.get(name);
	}

	public Map<String, Screen> screens() {
		return Collections.unmodifiableMap(this.screens);
	}

	private List<Screen> allScreens() {
		List<Screen> l = new ArrayList<>();
		l.add(this.main);
		l.addAll(this.screens.values());
		return l;
	}

	public Collection<Profile> profiles() {
		return Collections.unmodifiableCollection(this.profiles.values());
	}

	public Profile profile(final String name) {
		return this.profiles.get(name);
	}

	public Set<String> sliders() {
		return Collections.unmodifiableSet(this.sliders);
	}

	/** A value option the pack wants as a slider. */
	public boolean isSlider(final Option o) {
		return !o.bool && this.sliders.contains(o.name);
	}

	/** A raw language entry (may hold {@code §} codes), or null. */
	public String lang(final String key) {
		return this.lang.get(key);
	}

	public String optionLabel(final Option o) {
		String s = this.lang.get("option." + o.name);
		return s != null ? s : o.name;
	}

	/** The tooltip: {@code option.NAME.comment}, else the source comment; null if neither. */
	public String optionComment(final Option o) {
		String s = this.lang.get("option." + o.name + ".comment");
		if (s != null) {
			return s;
		}
		return o.sourceComment.isEmpty() ? null : o.sourceComment;
	}

	/** {@code value.NAME.VALUE}, else {@code prefix.NAME + value + suffix.NAME}. Booleans are left to the caller. */
	public String valueLabel(final Option o, final String value) {
		String s = this.lang.get("value." + o.name + "." + value);
		if (s != null) {
			return s;
		}
		String prefix = this.lang.get("prefix." + o.name);
		String suffix = this.lang.get("suffix." + o.name);
		return (prefix == null ? "" : prefix) + value + (suffix == null ? "" : suffix);
	}

	public String screenLabel(final String name) {
		String s = this.lang.get("screen." + name);
		return s != null ? s : name;
	}

	public String screenComment(final String name) {
		return this.lang.get("screen." + name + ".comment");
	}

	public String profileLabel(final String name) {
		String s = this.lang.get("profile." + name);
		return s != null ? s : name;
	}

	public String profileComment(final String name) {
		String s = this.lang.get("profile." + name + ".comment");
		return s != null ? s : this.lang.get("profile.comment");
	}

	public boolean allowed(final Option o, final String value) {
		return findMatchingValue(o.values, value) != null;
	}

	// ---- values ----

	/** Every option's value: the user's where valid, else the default. */
	public Map<String, String> values(final Map<String, String> userValues) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Option o : this.options.values()) {
			String v = userValues == null ? null : userValues.get(o.name);
			String match = findMatchingValue(o.values, v);
			out.put(o.name, match != null ? match : o.defaultValue);
		}
		return out;
	}

	/**
	 * The overrides for {@link PackPrograms} / {@link GlslPreprocessor}: only options whose value
	 * differs from the pack default, booleans as "true"/"false", others as the value.
	 */
	public Map<String, String> effective(final Map<String, String> userValues) {
		Map<String, String> out = new TreeMap<>();
		if (userValues == null) {
			return out;
		}
		for (Option o : this.options.values()) {
			String match = findMatchingValue(o.values, userValues.get(o.name));
			if (match != null && !valuesEqual(match, o.defaultValue)) {
				out.put(o.name, match);
			}
		}
		return out;
	}

	/**
	 * The options as macros, the way Iris defines them for {@code shaders.properties}: a boolean that
	 * is on becomes {@code #define NAME}, a value option {@code #define NAME value}. Consts are left out.
	 */
	public Map<String, String> macros(final Map<String, String> userValues) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : this.values(userValues).entrySet()) {
			Option o = this.options.get(e.getKey());
			if (o.isConst) {
				continue;
			}
			if (!o.bool) {
				out.put(o.name, e.getValue());
			} else if (e.getValue().equals("true")) {
				out.put(o.name, "");
			}
		}
		return out;
	}

	/**
	 * The best-fit profile whose values all match and whose unspecified profile-controlled options
	 * remain at their defaults, preferring the most specific profile (most option entries), or null
	 * ("Custom").
	 */
	public String currentProfile(final Map<String, String> values) {
		String bestName = null;
		int bestSize = -1;
		for (Profile p : this.profiles.values()) {
			boolean match = true;
			for (Map.Entry<String, String> e : p.values.entrySet()) {
				Option o = this.options.get(e.getKey());
				String current = values == null ? null : values.get(e.getKey());
				if (current == null && o != null) {
					current = o.defaultValue;
				}
				if (!valuesEqual(current, e.getValue())) {
					match = false;
					break;
				}
			}
			if (!match) {
				continue;
			}
			// Options controlled by other profiles (not set by this profile) must be at default
			for (String optName : this.profileOptions) {
				if (p.values.containsKey(optName)) {
					continue;
				}
				Option o = this.options.get(optName);
				if (o == null) {
					continue;
				}
				String current = values == null ? null : values.get(optName);
				if (current != null && !valuesEqual(current, o.defaultValue)) {
					match = false;
					break;
				}
			}
			if (match && p.values.size() > bestSize) {
				bestName = p.name;
				bestSize = p.values.size();
			}
		}
		return bestName;
	}

	/**
	 * Applies a profile: resets options controlled by other profiles back to their defaults so
	 * switching profiles never leaves conflicting values behind, then applies the profile's values.
	 */
	public void applyProfile(final String name, final Map<String, String> values) {
		Profile p = this.profiles.get(name);
		if (p == null) {
			return;
		}
		for (String optName : this.profileOptions) {
			Option o = this.options.get(optName);
			if (o != null && !p.values.containsKey(optName)) {
				values.put(optName, o.defaultValue);
			}
		}
		values.putAll(p.values);
	}

	/** Programs disabled via {@code !program.X} by the currently matched profile, or empty if none. */
	public Set<String> disabledPrograms(final Map<String, String> values) {
		String prof = this.currentProfile(values);
		if (prof == null) {
			return Collections.emptySet();
		}
		Profile p = this.profiles.get(prof);
		if (p == null || p.disabledPrograms.isEmpty()) {
			return Collections.emptySet();
		}
		return Collections.unmodifiableSet(new LinkedHashSet<>(p.disabledPrograms));
	}

	/** Reads an OptiFine {@code <pack>.txt}: {@code NAME=value} lines. */
	public static Map<String, String> readValues(final String text) {
		Map<String, String> out = new LinkedHashMap<>();
		if (text == null) {
			return out;
		}
		for (String raw : text.split("\n")) {
			String l = raw.trim();
			if (l.isEmpty() || l.startsWith("#")) {
				continue;
			}
			int eq = l.indexOf('=');
			if (eq > 0) {
				out.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
			}
		}
		return out;
	}

	/** Writes {@code NAME=value} lines for the given (non-default) values, sorted by name. */
	public static String writeValues(final Map<String, String> nonDefault) {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, String> e : new TreeMap<>(nonDefault).entrySet()) {
			out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
		}
		return out.toString();
	}
}
