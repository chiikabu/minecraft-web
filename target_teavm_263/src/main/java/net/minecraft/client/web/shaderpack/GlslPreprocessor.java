// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A C-style preprocessor for shader pack GLSL and properties files: {@code #include}, object- and
 * function-like {@code #define}, {@code #undef}, {@code #pragma once}, and
 * {@code #if/#ifdef/#ifndef/#elif/#else/#endif}.
 *
 * <p>In GLSL mode, the output is fully macro-expanded with comments removed and every directive
 * gone, so the line a GLSL ES compiler error points at holds the literal that caused it (the
 * error-driven fix-up in {@link GlslFixer} depends on that). {@code #version} and
 * {@code #extension} are recorded, not emitted. Block comments ({@code /* ... *\/}) from active
 * regions are preserved intact in {@link Result#blockComments} separately from line comments so
 * directives like {@code DRAWBUFFERS}, {@code RENDERTARGETS}, and commented {@code const} lines
 * are never polluted by {@code //} comments.
 *
 * <p>In {@code propertiesMode} (for {@code shaders.properties} and {@code block.properties}),
 * backslash continuations are NOT joined prior to {@code #if} filtering, comments are NOT stripped,
 * property keys/values are NOT macro-expanded, and only exact preprocessor directives are handled.
 *
 * <p>Pure Java (no TeaVM imports) so a desktop harness can run it over whole packs.
 */
public final class GlslPreprocessor {
	/** Loads a pack file by its absolute path under the shaders root ({@code /lib/a.glsl}); null if absent. */
	public interface Loader {
		String load(String absolutePath);
	}

	public static final class Result {
		public final StringBuilder code = new StringBuilder();
		public int version = 110;
		public String profile = "";
		public final List<String> extensions = new ArrayList<>();
		/** All comment bodies (both line and block) from active regions, in order. */
		public final List<String> comments = new ArrayList<>();
		/** Only {@code /* ... *\/} block comment bodies from active regions (multiline blocks intact). */
		public final List<String> blockComments = new ArrayList<>();
		public final List<String> errors = new ArrayList<>();
		public final Set<String> includedFiles = new HashSet<>();
		boolean versionSeen;
	}

	private static final class Macro {
		final List<String> params; // null for object-like
		final String body;

		Macro(final List<String> params, final String body) {
			this.params = params;
			this.body = body;
		}
	}

	private static final class SourceLine {
		final String code;
		final String lineComment;
		final List<String> blockComments;

		SourceLine(final String code, final String lineComment, final List<String> blockComments) {
			this.code = code;
			this.lineComment = lineComment;
			this.blockComments = blockComments;
		}
	}

	private final Loader loader;
	private final Map<String, Macro> macros = new HashMap<>();
	private final Map<String, String> optionOverrides;
	private final boolean propertiesMode;
	private final Set<String> pragmaOnce = new HashSet<>();
	private Result result;

	/**
	 * @param predefined macros defined before the first line (name -> body, "" for a bare define)
	 * @param optionOverrides user option values: "true"/"false" for boolean defines, a value for value defines
	 */
	public GlslPreprocessor(final Loader loader, final Map<String, String> predefined, final Map<String, String> optionOverrides) {
		this(loader, predefined, optionOverrides, false);
	}

	/**
	 * @param propertiesMode true for {@code shaders.properties}/{@code block.properties}: keep backslash
	 *     continuations and comments, only process exact {@code #if/#ifdef/#ifndef/#elif/#else/#endif/#define/#undef/#include},
	 *     and do not macro-expand property lines.
	 */
	public GlslPreprocessor(final Loader loader, final Map<String, String> predefined, final Map<String, String> optionOverrides,
		final boolean propertiesMode) {
		this.loader = loader;
		this.optionOverrides = optionOverrides == null ? new HashMap<>() : optionOverrides;
		this.propertiesMode = propertiesMode;
		if (predefined != null) {
			for (Map.Entry<String, String> e : predefined.entrySet()) {
				this.macros.put(e.getKey(), new Macro(null, e.getValue()));
			}
		}
		if (!this.macros.containsKey("__VERSION__")) {
			this.macros.put("__VERSION__", new Macro(null, "110"));
		}
	}

	public static GlslPreprocessor forProperties(final Loader loader, final Map<String, String> predefined,
		final Map<String, String> optionOverrides) {
		return new GlslPreprocessor(loader, predefined, optionOverrides, true);
	}

	public Result process(final String absolutePath) {
		this.result = new Result();
		this.pragmaOnce.clear();
		this.processFile(absolutePath, 0);
		return this.result;
	}

	/** Whether a macro is defined after {@link #process} (e.g. to read a pack's own feature switches). */
	public boolean isDefined(final String name) {
		return this.macros.containsKey(name);
	}

	/** Expands macros in an arbitrary expression with the current definitions. */
	public String expandText(final String text) {
		return this.expand(text, new HashSet<>(), 0);
	}

	/** Evaluates a {@code #if}-style expression with the current definitions. */
	public boolean evaluate(final String expression) {
		return this.evalCondition(expression) != 0.0;
	}

	// ---- files and lines ----

	private static final class Frame {
		final boolean parentActive;
		boolean active;
		boolean taken;
		boolean elseSeen;

		Frame(final boolean parentActive, final boolean active) {
			this.parentActive = parentActive;
			this.active = parentActive && active;
			this.taken = active;
		}
	}

	private void processFile(final String path, final int depth) {
		if (depth > 64) {
			this.result.errors.add("include depth over 64 at " + path);
			return;
		}
		if (this.pragmaOnce.contains(path)) {
			return;
		}
		String text = this.loader.load(path);
		if (text == null) {
			this.result.errors.add("missing file " + path);
			return;
		}
		this.result.includedFiles.add(path);
		if (this.propertiesMode) {
			this.processPropertiesFile(path, text, depth);
			return;
		}
		List<SourceLine> lines = splitLines(text);
		List<Frame> stack = new ArrayList<>();
		for (int index = 0; index < lines.size(); index++) {
			SourceLine sl = lines.get(index);
			String code = sl.code;
			boolean active = stack.isEmpty() || stack.get(stack.size() - 1).active;
			String trimmed = code.trim();
			if (trimmed.startsWith("#")) {
				this.directive(trimmed.substring(1).trim(), stack, active, path, depth);
				continue;
			}
			if (!active) {
				continue;
			}
			if (!sl.blockComments.isEmpty()) {
				for (String bc : sl.blockComments) {
					this.result.blockComments.add(bc);
					this.result.comments.add(bc);
				}
			}
			if (!sl.lineComment.isEmpty()) {
				this.result.comments.add(sl.lineComment);
				if (trimmed.isEmpty()) {
					this.commentedDefine(sl.lineComment);
				}
			}
			if (!trimmed.isEmpty()) {
				// a function-like macro call whose arguments continue on the next lines
				// (`clamp01(\n a * b\n)`): join until the parentheses balance
				int linesJoined = 0;
				while (this.opensMacroCall(code) && index + 1 < lines.size() && linesJoined < 64) {
					SourceLine nextLine = lines.get(index + 1);
					if (nextLine.code.trim().startsWith("#")) {
						break;
					}
					index++;
					linesJoined++;
					code = code + " " + nextLine.code;
					for (String bc : nextLine.blockComments) {
						this.result.blockComments.add(bc);
						this.result.comments.add(bc);
					}
					if (!nextLine.lineComment.isEmpty()) {
						this.result.comments.add(nextLine.lineComment);
					}
				}
				String expanded = this.expand(code, new HashSet<>(), 0);
				if (!this.optionOverrides.isEmpty() && expanded.indexOf("const") >= 0) {
					expanded = this.overrideConst(expanded);
				}
				this.result.code.append(expanded).append('\n');
			}
		}
		if (!stack.isEmpty()) {
			this.result.errors.add("unterminated #if in " + path);
		}
	}

	private void processPropertiesFile(final String path, final String text, final int depth) {
		List<Frame> stack = new ArrayList<>();
		String[] rawLines = text.split("\n", -1);
		for (String rawLine : rawLines) {
			String line = rawLine.replace("\r", "");
			boolean active = stack.isEmpty() || stack.get(stack.size() - 1).active;
			String trimmed = line.trim();
			if (trimmed.startsWith("#")) {
				String afterHash = trimmed.substring(1).trim();
				if (isPropertiesDirective(afterHash)) {
					this.directive(stripDirectiveComments(afterHash), stack, active, path, depth);
					continue;
				}
			}
			if (!active) {
				continue;
			}
			this.result.code.append(line).append('\n');
		}
		if (!stack.isEmpty()) {
			this.result.errors.add("unterminated #if in " + path);
		}
	}

	private static boolean isPropertiesDirective(final String afterHash) {
		int end = identEnd(afterHash, 0);
		if (end == 0) {
			return false;
		}
		String kw = afterHash.substring(0, end);
		boolean hasTail = end < afterHash.length();
		char next = hasTail ? afterHash.charAt(end) : '\0';
		switch (kw) {
			case "else":
			case "endif":
				return !hasTail || next == ' ' || next == '\t' || next == '/' || next == '#';
			case "if":
			case "elif":
				return hasTail && (next == ' ' || next == '\t' || next == '(' || next == '!' || next == '~' || next == '+' || next == '-');
			case "ifdef":
			case "ifndef":
			case "define":
			case "undef":
				return hasTail && (next == ' ' || next == '\t');
			case "include":
				return hasTail && (next == ' ' || next == '\t' || next == '"' || next == '<');
			default:
				return false;
		}
	}

	private static String stripDirectiveComments(final String s) {
		StringBuilder out = new StringBuilder(s.length());
		int n = s.length();
		boolean inQuote = false;
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			if (c == '"') {
				inQuote = !inQuote;
				out.append(c);
				continue;
			}
			if (!inQuote && c == '/' && i + 1 < n) {
				char d = s.charAt(i + 1);
				if (d == '/') {
					break;
				}
				if (d == '*') {
					int close = s.indexOf("*/", i + 2);
					if (close < 0) {
						break;
					}
					out.append(' ');
					i = close + 1;
					continue;
				}
			}
			out.append(c);
		}
		return out.toString().trim();
	}

	/** True if the line calls a function-like macro whose argument list is not closed on the line. */
	private boolean opensMacroCall(final String code) {
		int balance = 0;
		for (int i = 0; i < code.length(); i++) {
			char c = code.charAt(i);
			if (c == '(') {
				balance++;
			} else if (c == ')') {
				balance--;
			}
		}
		if (balance <= 0) {
			return false;
		}
		List<String> tokens = tokenize(code);
		for (int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);
			if (!t.isEmpty() && isIdentStart(t.charAt(0))) {
				Macro m = this.macros.get(t);
				if (m != null && m.params != null) {
					return true;
				}
			}
		}
		return false;
	}

	private static final Pattern CONST_OPTION = Pattern.compile(
		"\\bconst\\s+(int|float|bool)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);");

	/**
	 * Const options ({@code const int shadowMapResolution = 2048; // [1024 2048]}) are not macros, so
	 * a user value cannot reach them through {@link #define}: the value of the declaration is
	 * replaced instead, as Iris rewrites the line. The const directives the runtime reads
	 * (shadowMapResolution, sunPathRotation, ...) are collected from this output, so they see it too.
	 */
	private String overrideConst(final String line) {
		Matcher m = CONST_OPTION.matcher(line);
		StringBuilder out = null;
		int last = 0;
		while (m.find()) {
			String value = this.optionOverrides.get(m.group(2));
			if (value == null) {
				continue;
			}
			if (out == null) {
				out = new StringBuilder(line.length() + 8);
			}
			out.append(line, last, m.start(3)).append(value);
			last = m.end(3);
		}
		if (out == null) {
			return line;
		}
		return out.append(line, last, line.length()).toString();
	}

	/** {@code //#define NAME} / {@code // # define NAME} lines are boolean options that are off; an override can turn one on. */
	private void commentedDefine(final String comment) {
		String c = comment.trim();
		if (!c.startsWith("#")) {
			return;
		}
		String afterHash = c.substring(1).trim();
		int kwEnd = identEnd(afterHash, 0);
		if (!afterHash.substring(0, kwEnd).equals("define")) {
			return;
		}
		String rest = afterHash.substring(kwEnd).trim();
		int end = identEnd(rest, 0);
		if (end == 0) {
			return;
		}
		String name = rest.substring(0, end);
		String tail = rest.substring(end).trim();
		if (!tail.isEmpty() && !tail.startsWith("//") && !tail.startsWith("/*")) {
			return;
		}
		if ("true".equals(this.optionOverrides.get(name))) {
			this.macros.put(name, new Macro(null, ""));
		}
	}

	private void directive(final String body, final List<Frame> stack, final boolean active, final String path, final int depth) {
		int nameEnd = identEnd(body, 0);
		String name = body.substring(0, nameEnd);
		String rest = body.substring(nameEnd).trim();
		switch (name) {
			case "if":
				stack.add(new Frame(active, active && this.evalCondition(rest) != 0.0));
				return;
			case "ifdef":
				stack.add(new Frame(active, active && this.macros.containsKey(firstIdent(rest))));
				return;
			case "ifndef":
				stack.add(new Frame(active, active && !this.macros.containsKey(firstIdent(rest))));
				return;
			case "elif": {
				if (stack.isEmpty()) {
					this.result.errors.add("#elif without #if in " + path);
					return;
				}
				Frame f = stack.get(stack.size() - 1);
				if (f.taken || !f.parentActive) {
					f.active = false;
				} else {
					boolean v = this.evalCondition(rest) != 0.0;
					f.active = v;
					f.taken = v;
				}
				return;
			}
			case "else": {
				if (stack.isEmpty()) {
					this.result.errors.add("#else without #if in " + path);
					return;
				}
				Frame f = stack.get(stack.size() - 1);
				f.active = f.parentActive && !f.taken;
				f.taken = true;
				f.elseSeen = true;
				return;
			}
			case "endif":
				if (stack.isEmpty()) {
					this.result.errors.add("#endif without #if in " + path);
				} else {
					stack.remove(stack.size() - 1);
				}
				return;
			default:
				break;
		}
		if (!active) {
			return;
		}
		switch (name) {
			case "define":
				this.define(rest);
				return;
			case "undef":
				this.macros.remove(firstIdent(rest));
				return;
			case "include": {
				String target = includeTarget(rest);
				if (target == null) {
					this.result.errors.add("bad #include in " + path + ": " + rest);
					return;
				}
				this.processFile(resolve(path, target), depth + 1);
				return;
			}
			case "version": {
				if (!this.result.versionSeen) {
					this.result.versionSeen = true;
					String[] parts = rest.split("\\s+");
					try {
						this.result.version = Integer.parseInt(parts[0]);
					} catch (NumberFormatException e) {
						this.result.version = 120;
					}
					this.result.profile = parts.length > 1 ? parts[1] : "";
					this.macros.put("__VERSION__", new Macro(null, Integer.toString(this.result.version)));
				}
				return;
			}
			case "extension":
				this.result.extensions.add(rest);
				return;
			case "pragma": {
				String p = rest.trim();
				if (p.equals("once") || p.startsWith("once ") || p.startsWith("once\t")) {
					this.pragmaOnce.add(path);
				}
				return;
			}
			case "error":
				this.result.errors.add("#error " + rest + " (" + path + ")");
				return;
			default:
				// #line and anything else: dropped
		}
	}

	private void define(final String rest) {
		int end = identEnd(rest, 0);
		if (end == 0) {
			return;
		}
		String name = rest.substring(0, end);
		List<String> params = null;
		String body;
		if (end < rest.length() && rest.charAt(end) == '(') {
			int close = rest.indexOf(')', end);
			if (close < 0) {
				return;
			}
			params = new ArrayList<>();
			for (String p : rest.substring(end + 1, close).split(",")) {
				String t = p.trim();
				if (!t.isEmpty()) {
					params.add(t);
				}
			}
			body = rest.substring(close + 1).trim();
		} else {
			body = rest.substring(end).trim();
		}
		String override = this.optionOverrides.get(name);
		if (override != null && params == null) {
			if ("false".equals(override)) {
				this.macros.remove(name);
				return;
			}
			if (!"true".equals(override) && !body.isEmpty()) {
				body = override;
			}
		}
		this.macros.put(name, new Macro(params, body));
	}

	private static String includeTarget(final String rest) {
		String r = rest.trim();
		if (r.length() >= 2 && (r.charAt(0) == '"' || r.charAt(0) == '<')) {
			char close = r.charAt(0) == '"' ? '"' : '>';
			int end = r.indexOf(close, 1);
			if (end > 0) {
				return r.substring(1, end).replace('\\', '/');
			}
		}
		return null;
	}

	/** Absolute paths start at the shaders root; relative ones at the including file's folder. */
	static String resolve(final String from, final String target) {
		String normFrom = from.replace('\\', '/');
		String normTarget = target.replace('\\', '/');
		String joined;
		if (normTarget.startsWith("/")) {
			joined = normTarget;
		} else {
			int slash = normFrom.lastIndexOf('/');
			joined = (slash >= 0 ? normFrom.substring(0, slash + 1) : "/") + normTarget;
		}
		List<String> parts = new ArrayList<>();
		for (String p : joined.split("/")) {
			if (p.isEmpty() || p.equals(".")) {
				continue;
			}
			if (p.equals("..")) {
				if (!parts.isEmpty()) {
					parts.remove(parts.size() - 1);
				}
			} else {
				parts.add(p);
			}
		}
		return "/" + String.join("/", parts);
	}

	/**
	 * Splits GLSL source into logical lines (backslash continuations joined), separating line
	 * comments ({@code //}) from block comments ({@code /* ... *\/}) and keeping multiline block
	 * comments intact as single entries.
	 */
	private static List<SourceLine> splitLines(final String text) {
		List<SourceLine> out = new ArrayList<>();
		StringBuilder code = new StringBuilder();
		StringBuilder lineComment = new StringBuilder();
		StringBuilder blockBuf = new StringBuilder();
		List<String> blocks = new ArrayList<>();
		boolean inBlock = false;
		int n = text.length();
		for (int i = 0; i < n; i++) {
			char c = text.charAt(i);
			if (c == '\r') {
				continue;
			}
			if (c == '\n') {
				if (inBlock) {
					blockBuf.append('\n');
					out.add(new SourceLine(code.toString(), lineComment.toString().trim(),
						blocks.isEmpty() ? List.of() : new ArrayList<>(blocks)));
					code.setLength(0);
					lineComment.setLength(0);
					blocks.clear();
					continue;
				}
				int len = code.length();
				if (len > 0 && code.charAt(len - 1) == '\\') {
					code.setLength(len - 1);
					continue;
				}
				out.add(new SourceLine(code.toString(), lineComment.toString().trim(),
					blocks.isEmpty() ? List.of() : new ArrayList<>(blocks)));
				code.setLength(0);
				lineComment.setLength(0);
				blocks.clear();
				continue;
			}
			if (inBlock) {
				if (c == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
					inBlock = false;
					i++;
					code.append(' ');
					String body = blockBuf.toString().trim();
					if (!body.isEmpty()) {
						blocks.add(body);
					}
					blockBuf.setLength(0);
				} else {
					blockBuf.append(c);
				}
				continue;
			}
			if (c == '/' && i + 1 < n) {
				char d = text.charAt(i + 1);
				if (d == '/') {
					int end = text.indexOf('\n', i);
					if (end < 0) {
						end = n;
					}
					String body = text.substring(i + 2, end).replace("\r", "");
					if (lineComment.length() > 0) {
						lineComment.append(' ');
					}
					lineComment.append(body);
					i = end - 1;
					continue;
				}
				if (d == '*') {
					inBlock = true;
					blockBuf.setLength(0);
					i++;
					continue;
				}
			}
			code.append(c);
		}
		if (inBlock && blockBuf.length() > 0) {
			blocks.add(blockBuf.toString().trim());
		}
		if (code.length() > 0 || lineComment.length() > 0 || !blocks.isEmpty()) {
			out.add(new SourceLine(code.toString(), lineComment.toString().trim(),
				blocks.isEmpty() ? List.of() : new ArrayList<>(blocks)));
		}
		return out;
	}

	/** Legacy package-private helper retained for compatibility: [code, all comments]. */
	static List<String[]> splitCodeAndComments(final String text) {
		List<SourceLine> lines = splitLines(text);
		List<String[]> out = new ArrayList<>(lines.size());
		for (SourceLine sl : lines) {
			StringBuilder c = new StringBuilder();
			for (String b : sl.blockComments) {
				if (c.length() > 0) {
					c.append(' ');
				}
				c.append(b);
			}
			if (!sl.lineComment.isEmpty()) {
				if (c.length() > 0) {
					c.append(' ');
				}
				c.append(sl.lineComment);
			}
			out.add(new String[] {sl.code, c.toString()});
		}
		return out;
	}

	// ---- macro expansion ----

	static boolean isIdentStart(final char c) {
		return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
	}

	static boolean isIdentPart(final char c) {
		return isIdentStart(c) || (c >= '0' && c <= '9');
	}

	static int identEnd(final String s, final int from) {
		int i = from;
		if (i < s.length() && isIdentStart(s.charAt(i))) {
			i++;
			while (i < s.length() && isIdentPart(s.charAt(i))) {
				i++;
			}
		}
		return i;
	}

	private static String firstIdent(final String s) {
		String t = s.trim();
		return t.substring(0, identEnd(t, 0));
	}

	/** Tokens: identifiers, pp-numbers, runs of whitespace, single other characters. */
	static List<String> tokenize(final String s) {
		List<String> tokens = new ArrayList<>();
		int n = s.length();
		int i = 0;
		while (i < n) {
			char c = s.charAt(i);
			int j = i + 1;
			if (isIdentStart(c)) {
				while (j < n && isIdentPart(s.charAt(j))) {
					j++;
				}
			} else if ((c >= '0' && c <= '9') || (c == '.' && j < n && Character.isDigit(s.charAt(j)))) {
				while (j < n) {
					char d = s.charAt(j);
					if (isIdentPart(d) || d == '.') {
						j++;
					} else if ((d == '+' || d == '-') && (s.charAt(j - 1) == 'e' || s.charAt(j - 1) == 'E')
						&& !s.substring(i, j).startsWith("0x") && !s.substring(i, j).startsWith("0X")) {
						j++;
					} else {
						break;
					}
				}
			} else if (c == ' ' || c == '\t') {
				while (j < n && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) {
					j++;
				}
			} else if (c == '#' && j < n && s.charAt(j) == '#') {
				j++;
			}
			tokens.add(s.substring(i, j));
			i = j;
		}
		return tokens;
	}

	private static String pasteTokens(final String bodyText) {
		if (!bodyText.contains("##")) {
			return bodyText;
		}
		List<String> body = tokenize(bodyText);
		StringBuilder out = new StringBuilder(bodyText.length());
		for (int i = 0; i < body.size(); i++) {
			String t = body.get(i);
			if (t.equals("##")) {
				trimTrailingHorizontalSpace(out);
				i++;
				while (i < body.size() && body.get(i).trim().isEmpty()) {
					i++;
				}
				if (i < body.size()) {
					out.append(body.get(i));
				}
				continue;
			}
			out.append(t);
		}
		return out.toString();
	}

	private static void trimTrailingHorizontalSpace(final StringBuilder sb) {
		int len = sb.length();
		while (len > 0 && (sb.charAt(len - 1) == ' ' || sb.charAt(len - 1) == '\t')) {
			len--;
		}
		sb.setLength(len);
	}

	private String expand(final String text, final Set<String> disabled, final int depth) {
		if (depth > 32 || text.isEmpty()) {
			return text;
		}
		List<String> tokens = tokenize(text);
		StringBuilder out = new StringBuilder(text.length() + 16);
		for (int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);
			Macro m = isIdentStart(t.charAt(0)) && !disabled.contains(t) ? this.macros.get(t) : null;
			if (m == null) {
				out.append(t);
				continue;
			}
			if (m.params == null) {
				String pasted = pasteTokens(m.body);
				Set<String> innerDisabled = withName(disabled, t);
				String exp = this.expand(pasted, innerDisabled, depth + 1);
				// Rescan if the expanded object-like macro ends with a function-like macro name
				// immediately followed by '(' in the outer token stream.
				i = this.appendOrInvokeTrailingCall(out, exp, tokens, i, disabled, innerDisabled, depth + 1);
				continue;
			}
			int[] endOut = new int[1];
			List<String> args = parseMacroArgs(tokens, i, m.params.isEmpty(), endOut);
			if (args == null) {
				out.append(t);
				continue;
			}
			Set<String> innerDisabled = withName(disabled, t);
			String exp = this.expand(this.substitute(m, args, disabled, depth), innerDisabled, depth + 1);
			i = this.appendOrInvokeTrailingCall(out, exp, tokens, endOut[0], disabled, innerDisabled, depth + 1);
		}
		return out.toString();
	}

	/**
	 * Parses a function-like macro argument list following token {@code nameIdx}.
	 * Returns null if the next non-space token is not '(' or the parens do not close.
	 */
	private static List<String> parseMacroArgs(final List<String> tokens, final int nameIdx, final boolean zeroParams, final int[] closeOut) {
		int j = nameIdx + 1;
		while (j < tokens.size() && tokens.get(j).trim().isEmpty()) {
			j++;
		}
		if (j >= tokens.size() || !tokens.get(j).equals("(")) {
			return null;
		}
		List<String> args = new ArrayList<>();
		StringBuilder arg = new StringBuilder();
		int level = 0;
		int k = j + 1;
		boolean closed = false;
		for (; k < tokens.size(); k++) {
			String a = tokens.get(k);
			if (a.equals("(")) {
				level++;
			} else if (a.equals(")")) {
				if (level == 0) {
					closed = true;
					break;
				}
				level--;
			} else if (a.equals(",") && level == 0) {
				args.add(arg.toString());
				arg.setLength(0);
				continue;
			}
			arg.append(a);
		}
		if (!closed) {
			return null;
		}
		if (arg.length() > 0 || !args.isEmpty() || !zeroParams) {
			args.add(arg.toString());
		}
		closeOut[0] = k;
		return args;
	}

	private int appendOrInvokeTrailingCall(final StringBuilder out, String expanded, final List<String> outerTokens,
		int outerIdx, final Set<String> outerDisabled, Set<String> callDisabled, int depth) {
		while (depth <= 32) {
			int nextNonSpace = outerIdx + 1;
			while (nextNonSpace < outerTokens.size() && outerTokens.get(nextNonSpace).trim().isEmpty()) {
				nextNonSpace++;
			}
			if (nextNonSpace >= outerTokens.size() || !outerTokens.get(nextNonSpace).equals("(")) {
				out.append(expanded);
				return outerIdx;
			}
			// Check whether `expanded` ends with an identifier that names a function-like macro
			int end = expanded.length();
			while (end > 0 && (expanded.charAt(end - 1) == ' ' || expanded.charAt(end - 1) == '\t')) {
				end--;
			}
			int start = end;
			while (start > 0 && isIdentPart(expanded.charAt(start - 1))) {
				start--;
			}
			if (start == end || !isIdentStart(expanded.charAt(start))) {
				out.append(expanded);
				return outerIdx;
			}
			String fnName = expanded.substring(start, end);
			Macro fn = !callDisabled.contains(fnName) ? this.macros.get(fnName) : null;
			if (fn == null || fn.params == null) {
				out.append(expanded);
				return outerIdx;
			}
			int[] closeOut = new int[1];
			List<String> args = parseMacroArgs(outerTokens, outerIdx, fn.params.isEmpty(), closeOut);
			if (args == null) {
				out.append(expanded);
				return outerIdx;
			}
			out.append(expanded, 0, start);
			callDisabled = withName(callDisabled, fnName);
			expanded = this.expand(this.substitute(fn, args, outerDisabled, depth), callDisabled, depth + 1);
			outerIdx = closeOut[0];
			depth++;
		}
		out.append(expanded);
		return outerIdx;
	}

	private static Set<String> withName(final Set<String> disabled, final String name) {
		Set<String> s = new HashSet<>(disabled);
		s.add(name);
		return s;
	}

	private String substitute(final Macro m, final List<String> args, final Set<String> disabled, final int depth) {
		Map<String, String> raw = new LinkedHashMap<>();
		for (int p = 0; p < m.params.size(); p++) {
			raw.put(m.params.get(p), p < args.size() ? args.get(p).trim() : "");
		}
		List<String> body = tokenize(m.body);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < body.size(); i++) {
			String t = body.get(i);
			if (t.equals("##")) {
				// paste: drop surrounding horizontal whitespace (spaces and tabs)
				trimTrailingHorizontalSpace(out);
				i++;
				while (i < body.size() && body.get(i).trim().isEmpty()) {
					i++;
				}
				if (i < body.size()) {
					String next = body.get(i);
					out.append(raw.containsKey(next) ? raw.get(next) : next);
				}
				continue;
			}
			if (raw.containsKey(t)) {
				boolean pasted = nextNonSpace(body, i + 1).equals("##");
				out.append(pasted ? raw.get(t) : this.expand(raw.get(t), disabled, depth + 1));
			} else {
				out.append(t);
			}
		}
		return out.toString();
	}

	private static String nextNonSpace(final List<String> tokens, final int from) {
		for (int i = from; i < tokens.size(); i++) {
			if (!tokens.get(i).trim().isEmpty()) {
				return tokens.get(i);
			}
		}
		return "";
	}

	// ---- #if expressions ----

	private String resolveDefined(final String expr) {
		if (!expr.contains("defined")) {
			return expr;
		}
		StringBuilder pre = new StringBuilder(expr.length());
		List<String> tokens = tokenize(expr);
		for (int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);
			if (!t.equals("defined")) {
				pre.append(t);
				continue;
			}
			int j = i + 1;
			while (j < tokens.size() && tokens.get(j).trim().isEmpty()) {
				j++;
			}
			boolean paren = j < tokens.size() && tokens.get(j).equals("(");
			if (paren) {
				j++;
				while (j < tokens.size() && tokens.get(j).trim().isEmpty()) {
					j++;
				}
			}
			String name = j < tokens.size() ? tokens.get(j) : "";
			if (paren) {
				j++;
				while (j < tokens.size() && !tokens.get(j).equals(")")) {
					j++;
				}
			}
			pre.append(this.macros.containsKey(name) ? " 1 " : " 0 ");
			i = j;
		}
		return pre.toString();
	}

	private double evalCondition(final String expression) {
		// Resolve defined X / defined(X) first (before expansion mutates macro names), expand,
		// then resolve any defined(...) produced by macro expansion (e.g. #define HAS(x) defined(x)).
		String pre = this.resolveDefined(expression);
		String expanded = this.expand(pre, new HashSet<>(), 0);
		if (expanded.contains("defined")) {
			expanded = this.expand(this.resolveDefined(expanded), new HashSet<>(), 0);
		}
		try {
			return new ExprParser(tokenize(expanded)).parseTernary();
		} catch (RuntimeException e) {
			if (this.result != null) {
				this.result.errors.add("bad #if expression: " + expression + " -> " + expanded);
			}
			return 0.0;
		}
	}

	/** Recursive-descent evaluator over doubles (packs compare float macros in #if; OptiFine allows it). */
	private static final class ExprParser {
		private final List<String> tokens = new ArrayList<>();
		private int pos;

		ExprParser(final List<String> raw) {
			// merge two-character operators
			for (int i = 0; i < raw.size(); i++) {
				String t = raw.get(i);
				if (t.trim().isEmpty()) {
					continue;
				}
				if (i + 1 < raw.size()) {
					String two = t + raw.get(i + 1);
					if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=") || two.equals("<=")
						|| two.equals(">=") || two.equals("<<") || two.equals(">>")) {
						this.tokens.add(two);
						i++;
						continue;
					}
				}
				this.tokens.add(t);
			}
		}

		private String peek() {
			return this.pos < this.tokens.size() ? this.tokens.get(this.pos) : "";
		}

		private boolean eat(final String t) {
			if (this.peek().equals(t)) {
				this.pos++;
				return true;
			}
			return false;
		}

		double parseTernary() {
			double c = this.parseBinary(0);
			if (this.eat("?")) {
				double a = this.parseTernary();
				if (!this.eat(":")) {
					throw new IllegalStateException("expected :");
				}
				double b = this.parseTernary();
				return c != 0.0 ? a : b;
			}
			return c;
		}

		private static final String[][] LEVELS = {
			{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}
		};

		private double parseBinary(final int level) {
			if (level >= LEVELS.length) {
				return this.parseUnary();
			}
			double left = this.parseBinary(level + 1);
			while (true) {
				String op = null;
				for (String candidate : LEVELS[level]) {
					if (this.peek().equals(candidate)) {
						op = candidate;
						break;
					}
				}
				if (op == null) {
					return left;
				}
				this.pos++;
				double right = this.parseBinary(level + 1);
				left = apply(op, left, right);
			}
		}

		private static double apply(final String op, final double a, final double b) {
			switch (op) {
				case "||": return (a != 0.0 || b != 0.0) ? 1 : 0;
				case "&&": return (a != 0.0 && b != 0.0) ? 1 : 0;
				case "|": return (long) a | (long) b;
				case "^": return (long) a ^ (long) b;
				case "&": return (long) a & (long) b;
				case "==": return a == b ? 1 : 0;
				case "!=": return a != b ? 1 : 0;
				case "<": return a < b ? 1 : 0;
				case ">": return a > b ? 1 : 0;
				case "<=": return a <= b ? 1 : 0;
				case ">=": return a >= b ? 1 : 0;
				case "<<": return (long) a << (long) b;
				case ">>": return (long) a >> (long) b;
				case "+": return a + b;
				case "-": return a - b;
				case "*": return a * b;
				case "/": return b == 0.0 ? 0.0 : (a == Math.floor(a) && b == Math.floor(b) ? (double) ((long) a / (long) b) : a / b);
				case "%": return b == 0.0 ? 0.0 : (long) a % (long) b;
				default: throw new IllegalStateException(op);
			}
		}

		private double parseUnary() {
			if (this.eat("!")) {
				return this.parseUnary() == 0.0 ? 1 : 0;
			}
			if (this.eat("-")) {
				return -this.parseUnary();
			}
			if (this.eat("+")) {
				return this.parseUnary();
			}
			if (this.eat("~")) {
				return ~(long) this.parseUnary();
			}
			if (this.eat("(")) {
				double v = this.parseTernary();
				this.eat(")");
				return v;
			}
			String t = this.peek();
			this.pos++;
			if (t.isEmpty()) {
				throw new IllegalStateException("unexpected end");
			}
			if (isIdentStart(t.charAt(0))) {
				return t.equals("true") ? 1 : 0; // undefined identifiers are 0
			}
			return parseNumber(t);
		}
	}

	static double parseNumber(final String token) {
		String t = token.trim();
		if (t.startsWith("0x") || t.startsWith("0X")) {
			t = t.substring(2);
			while (!t.isEmpty() && "uUlL".indexOf(t.charAt(t.length() - 1)) >= 0) {
				t = t.substring(0, t.length() - 1);
			}
			return Long.parseUnsignedLong(t, 16);
		}
		boolean hasFloatMarker = t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0
			|| t.endsWith("f") || t.endsWith("F");
		if (!hasFloatMarker && t.length() > 1 && t.charAt(0) == '0') {
			String oct = t;
			while (!oct.isEmpty() && "uUlL".indexOf(oct.charAt(oct.length() - 1)) >= 0) {
				oct = oct.substring(0, oct.length() - 1);
			}
			boolean allOctal = oct.length() > 1;
			for (int i = 1; i < oct.length() && allOctal; i++) {
				char ch = oct.charAt(i);
				if (ch < '0' || ch > '7') {
					allOctal = false;
				}
			}
			if (allOctal) {
				return Long.parseLong(oct, 8);
			}
		}
		while (!t.isEmpty() && "uUlLfF".indexOf(t.charAt(t.length() - 1)) >= 0) {
			t = t.substring(0, t.length() - 1);
		}
		return Double.parseDouble(t);
	}
}
