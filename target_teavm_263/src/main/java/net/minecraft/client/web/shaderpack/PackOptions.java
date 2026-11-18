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

public final class PackOptions {
	public static final class Option {
		public final String name;
		public final boolean bool;
		public final boolean isConst;
		public final String defaultValue;
		public final List<String> values;
		public final String sourceComment;
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

	public static final class Element {
		public enum Kind {
			OPTION,
			SCREEN,
			PROFILE,
			EMPTY,
			UNKNOWN
		}

		public final Kind kind;
		public final String name;

		Element(final Kind kind, final String name) {
			this.kind = kind;
			this.name = name;
		}
	}

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

	public static final class Profile {
		public final String name;
		public final Map<String, String> values = new LinkedHashMap<>();
		public final List<String> disabledPrograms = new ArrayList<>();

		Profile(final String name) {
			this.name = name;
		}
	}

	private static final Set<String> CONST_NAMES = new HashSet<>();

	static {
		Collections.addAll(CONST_NAMES, "shadowMapResolution", "shadowDistance", "voxelDistance", "shadowDistanceRenderMul",
			"entityShadowDistanceMul", "shadowIntervalSize", "generateShadowMipmap", "generateShadowColorMipmap", "shadowHardwareFiltering",
			"shadowtex0Mipmap", "shadowtexMipmap", "shadowtex1Mipmap", "shadowtex0Nearest", "shadowtexNearest", "shadow0MinMagNearest",
			"shadowtex1Nearest", "shadow1MinMagNearest", "wetnessHalflife", "drynessHalflife", "eyeBrightnessHalflife", "centerDepthHalflife",
			"sunPathRotation", "ambientOcclusionLevel", "superSamplingLevel", "noiseTextureResolution");
		for (int i = 0; i < 8; i++) {
			Collections.addAll(CONST_NAMES, "shadowcolor" + i + "Mipmap", "shadowColor" + i + "Mipmap", "shadowcolor" + i + "Nearest",
				"shadowColor" + i + "Nearest", "shadowcolor" + i + "MinMagNearest", "shadowColor" + i + "MinMagNearest", "shadowHardwareFiltering" + i);
		}
	}

	private static final Pattern DEFINE_BOOL = Pattern.compile("#\\s*define\\s+([A-Za-z_]\\w*)\\s*(?://(.*))?");
	private static final Pattern DEFINE_OFF = Pattern.compile("//\\s*#\\s*define\\s+([A-Za-z_]\\w*)\\s*(?://(.*))?");
	private static final Pattern DEFINE_VALUE = Pattern.compile("#\\s*define\\s+([A-Za-z_]\\w*)\\s+([-+]?[\\w.]+)\\s*//(.*)");
	private static final Pattern CONST = Pattern.compile("const\\s+(int|float|bool)\\s+([A-Za-z_]\\w*)\\s*=\\s*([-+]?[\\w.]+)\\s*;\\s*(?://(.*))?");
	private static final Pattern IFDEF = Pattern.compile("#\\s*ifn?def\\s+([A-Za-z_]\\w*).*");
	private static final Pattern LIST = Pattern.compile("\\[([^\\]]*)\\]");
	private static final Pattern INCLUDE = Pattern.compile("#\\s*include\\s+[\"<]([^\">]+)[\">].*");

	private final Map<String, Option> options = new LinkedHashMap<>();
	private Screen main;
	private final Map<String, Screen> screens = new LinkedHashMap<>();
	private final Map<String, Profile> profiles = new LinkedHashMap<>();
	private final Set<String> sliders = new LinkedHashSet<>();
	private final Map<String, String> lang = new HashMap<>();
	public final List<String> warnings = new ArrayList<>();
	public int boolCount;
	public int valueCount;
	public int constCount;

	private PackOptions() {
	}

	private static final class Sighting {
		final int kind;
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
		out.resolveOptions(seen, ifdefs);
		String props = pack.text("/shaders.properties");
		out.readMenu(props == null ? new ShaderPack.Properties() : ShaderPack.parseProperties(props));
		out.readLang(pack);
		return out;
	}

	private static void scanFile(final String path, final String text, final Map<String, List<Sighting>> seen, final Set<String> ifdefs,
		final List<String> includes) {
		int n = text.length();
		int start = 0;
		while (start <= n) {
			int end = text.indexOf('\n', start);
			if (end < 0) {
				end = n;
			}
			String line = text.substring(start, end).trim();
			start = end + 1;
			if (line.length() < 7) {
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
					continue;
				}
				m = INCLUDE.matcher(line);
				if (m.matches()) {
					includes.add(m.group(1));
					continue;
				}
				m = DEFINE_VALUE.matcher(line);
				if (m.matches()) {
					String comment = m.group(3);
					Matcher list = LIST.matcher(comment);
					if (list.find()) {
						add(seen, m.group(1), new Sighting(1, m.group(2), splitList(list.group(1)), stripList(comment), path));
					}
					continue;
				}
				m = DEFINE_BOOL.matcher(line);
				if (m.matches()) {
					add(seen, m.group(1), new Sighting(0, "true", null, m.group(2) == null ? "" : stripList(m.group(2)), path));
				}
			} else if (c == '/') {
				Matcher m = DEFINE_OFF.matcher(line);
				if (m.matches()) {
					add(seen, m.group(1), new Sighting(0, "false", null, m.group(2) == null ? "" : stripList(m.group(2)), path));
				}
			} else if (c == 'c' && line.startsWith("const")) {
				Matcher m = CONST.matcher(line);
				if (!m.matches()) {
					continue;
				}
				String type = m.group(1);
				String name = m.group(2);
				String value = m.group(3);
				String comment = m.group(4) == null ? "" : m.group(4);
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
			if (end == n) {
				break;
			}
		}
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

	private static String stripList(final String comment) {
		String s = LIST.matcher(comment).replaceFirst("").trim();
		int i = 0;
		while (i < s.length() && (s.charAt(i) == '/' || s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
			i++;
		}
		return s.substring(i);
	}

	private void resolveOptions(final Map<String, List<Sighting>> seen, final Set<String> ifdefs) {
		for (Map.Entry<String, List<Sighting>> e : seen.entrySet()) {
			String name = e.getKey();
			List<Sighting> all = new ArrayList<>();
			for (Sighting s : e.getValue()) {
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
				if (s.kind != first.kind || !s.value.equals(first.value)) {
					ambiguous = true;
					break;
				}
				if (s.list != null) {
					for (String v : s.list) {
						if (!values.contains(v)) {
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
			if (bool) {
				values.clear();
				values.add("false");
				values.add("true");
			} else if (!values.contains(first.value)) {
				values.add(first.value);
			}
			Option o = new Option(name, bool, first.kind >= 2, first.value, Collections.unmodifiableList(values), comment, first.file);
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
		for (Map.Entry<String, String> e : raw.entrySet()) {
			if (e.getKey().startsWith("profile.") && !this.profiles.containsKey(e.getKey().substring("profile.".length()))) {
				this.resolveProfile(e.getKey().substring("profile.".length()), raw, new HashSet<>());
			}
		}
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
					p.disabledPrograms.addAll(parent.disabledPrograms);
				}
				continue;
			}
			if (t.startsWith("!program.")) {
				p.disabledPrograms.add(t.substring("!program.".length()));
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
			if (o.bool ? !(value.equals("true") || value.equals("false")) : !o.values.contains(value)) {
				this.warnings.add("profile " + name + ": " + opt + "=" + value + " is not an allowed value");
				continue;
			}
			p.values.remove(opt);
			p.values.put(opt, value);
		}
		visiting.remove(name);
		this.profiles.put(name, p);
		return p;
	}

	private void readLang(final ShaderPack pack) {
		String text = pack.text("/lang/en_US.lang");
		if (text == null) {
			text = pack.text("/lang/en_us.lang");
		}
		if (text == null) {
			for (String p : pack.paths()) {
				if (p.toLowerCase(Locale.ROOT).equals("/lang/en_us.lang")) {
					text = pack.text(p);
					break;
				}
			}
		}
		if (text != null) {
			parseLang(text, this.lang);
		}
	}

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
						}
					}
					out.append('u');
					break;
				default: out.append(d);
			}
		}
		return out.toString();
	}

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

	public Collection<Option> options() {
		return Collections.unmodifiableCollection(this.options.values());
	}

	public Option option(final String name) {
		return this.options.get(name);
	}

	public Screen mainScreen() {
		return this.main;
	}

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

	public boolean isSlider(final Option o) {
		return !o.bool && this.sliders.contains(o.name);
	}

	public String lang(final String key) {
		return this.lang.get(key);
	}

	public String optionLabel(final Option o) {
		String s = this.lang.get("option." + o.name);
		return s != null ? s : o.name;
	}

	public String optionComment(final Option o) {
		String s = this.lang.get("option." + o.name + ".comment");
		if (s != null) {
			return s;
		}
		return o.sourceComment.isEmpty() ? null : o.sourceComment;
	}

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
		return value != null && o.values.contains(value);
	}

	public Map<String, String> values(final Map<String, String> userValues) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Option o : this.options.values()) {
			String v = userValues == null ? null : userValues.get(o.name);
			out.put(o.name, this.allowed(o, v) ? v : o.defaultValue);
		}
		return out;
	}

	public Map<String, String> effective(final Map<String, String> userValues) {
		Map<String, String> out = new TreeMap<>();
		if (userValues == null) {
			return out;
		}
		for (Option o : this.options.values()) {
			String v = userValues.get(o.name);
			if (this.allowed(o, v) && !v.equals(o.defaultValue)) {
				out.put(o.name, v);
			}
		}
		return out;
	}

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

	public String currentProfile(final Map<String, String> values) {
		for (Profile p : this.profiles.values()) {
			boolean match = true;
			for (Map.Entry<String, String> e : p.values.entrySet()) {
				Option o = this.options.get(e.getKey());
				String current = values.get(e.getKey());
				if (current == null) {
					current = o.defaultValue;
				}
				if (!current.equals(e.getValue())) {
					match = false;
					break;
				}
			}
			if (match) {
				return p.name;
			}
		}
		return null;
	}

	public void applyProfile(final String name, final Map<String, String> values) {
		Profile p = this.profiles.get(name);
		if (p != null) {
			values.putAll(p.values);
		}
	}

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

	public static String writeValues(final Map<String, String> nonDefault) {
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, String> e : new TreeMap<>(nonDefault).entrySet()) {
			out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
		}
		return out.toString();
	}
}
