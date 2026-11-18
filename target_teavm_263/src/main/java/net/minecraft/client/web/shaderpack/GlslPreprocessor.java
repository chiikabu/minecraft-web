// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GlslPreprocessor {
	public interface Loader {
		String load(String absolutePath);
	}

	public static final class Result {
		public final StringBuilder code = new StringBuilder();
		public int version = 110;
		public String profile = "";
		public final List<String> extensions = new ArrayList<>();
		public final List<String> comments = new ArrayList<>();
		public final List<String> errors = new ArrayList<>();
		public final Set<String> includedFiles = new HashSet<>();
		boolean versionSeen;
	}

	private static final class Macro {
		final List<String> params;
		final String body;

		Macro(final List<String> params, final String body) {
			this.params = params;
			this.body = body;
		}
	}

	private final Loader loader;
	private final Map<String, Macro> macros = new HashMap<>();
	private final Map<String, String> optionOverrides;
	private Result result;

	public GlslPreprocessor(final Loader loader, final Map<String, String> predefined, final Map<String, String> optionOverrides) {
		this.loader = loader;
		this.optionOverrides = optionOverrides == null ? new HashMap<>() : optionOverrides;
		if (predefined != null) {
			for (Map.Entry<String, String> e : predefined.entrySet()) {
				this.macros.put(e.getKey(), new Macro(null, e.getValue()));
			}
		}
	}

	public Result process(final String absolutePath) {
		this.result = new Result();
		this.processFile(absolutePath, 0, new ArrayList<>());
		return this.result;
	}

	public boolean isDefined(final String name) {
		return this.macros.containsKey(name);
	}

	public String expandText(final String text) {
		return this.expand(text, new HashSet<>(), 0);
	}

	public boolean evaluate(final String expression) {
		return this.evalCondition(expression) != 0.0;
	}

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

	private void processFile(final String path, final int depth, final List<Frame> stackForErrors) {
		if (depth > 64) {
			this.result.errors.add("include depth over 64 at " + path);
			return;
		}
		String text = this.loader.load(path);
		if (text == null) {
			this.result.errors.add("missing file " + path);
			return;
		}
		this.result.includedFiles.add(path);
		List<String[]> lines = splitCodeAndComments(text);
		List<Frame> stack = new ArrayList<>();
		for (int index = 0; index < lines.size(); index++) {
			String code = lines.get(index)[0];
			String comment = lines.get(index)[1];
			boolean active = stack.isEmpty() || stack.get(stack.size() - 1).active;
			String trimmed = code.trim();
			if (trimmed.startsWith("#")) {
				this.directive(trimmed.substring(1).trim(), stack, active, path, depth);
				continue;
			}
			if (!active) {
				continue;
			}
			if (!comment.isEmpty()) {
				this.result.comments.add(comment);
				if (trimmed.isEmpty()) {
					this.commentedDefine(comment);
				}
			}
			if (!trimmed.isEmpty()) {
				int lines_joined = 0;
				while (this.opensMacroCall(code) && index + 1 < lines.size() && lines_joined < 64) {
					String next = lines.get(index + 1)[0];
					if (next.trim().startsWith("#")) {
						break;
					}
					index++;
					lines_joined++;
					code = code + " " + next;
					if (!lines.get(index)[1].isEmpty()) {
						this.result.comments.add(lines.get(index)[1]);
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

	private static final java.util.regex.Pattern CONST_OPTION = java.util.regex.Pattern.compile(
		"\\bconst\\s+(int|float|bool)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);");

	private String overrideConst(final String line) {
		java.util.regex.Matcher m = CONST_OPTION.matcher(line);
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

	private void commentedDefine(final String comment) {
		String c = comment.trim();
		if (!c.startsWith("#define")) {
			return;
		}
		String rest = c.substring("#define".length()).trim();
		int end = identEnd(rest, 0);
		if (end == 0) {
			return;
		}
		String name = rest.substring(0, end);
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
				this.processFile(resolve(path, target), depth + 1, stack);
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
			case "error":
				this.result.errors.add("#error " + rest + " (" + path + ")");
				return;
			default:
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
				return r.substring(1, end);
			}
		}
		return null;
	}

	static String resolve(final String from, final String target) {
		String joined;
		if (target.startsWith("/")) {
			joined = target;
		} else {
			int slash = from.lastIndexOf('/');
			joined = (slash >= 0 ? from.substring(0, slash + 1) : "/") + target;
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

	static List<String[]> splitCodeAndComments(final String text) {
		List<String[]> out = new ArrayList<>();
		StringBuilder code = new StringBuilder();
		StringBuilder comment = new StringBuilder();
		boolean inBlock = false;
		int n = text.length();
		for (int i = 0; i < n; i++) {
			char c = text.charAt(i);
			if (c == '\r') {
				continue;
			}
			if (c == '\n') {
				int len = code.length();
				if (!inBlock && len > 0 && code.charAt(len - 1) == '\\') {
					code.setLength(len - 1);
					continue;
				}
				out.add(new String[] {code.toString(), comment.toString().trim()});
				code.setLength(0);
				comment.setLength(0);
				continue;
			}
			if (inBlock) {
				if (c == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
					inBlock = false;
					i++;
					code.append(' ');
					comment.append(' ');
				} else {
					comment.append(c);
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
					comment.append(text, i + 2, end).append(' ');
					i = end - 1;
					continue;
				}
				if (d == '*') {
					inBlock = true;
					i++;
					continue;
				}
			}
			code.append(c);
		}
		if (code.length() > 0 || comment.length() > 0) {
			out.add(new String[] {code.toString(), comment.toString().trim()});
		}
		return out;
	}

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
				disabled.add(t);
				out.append(this.expand(m.body, disabled, depth + 1));
				disabled.remove(t);
				continue;
			}
			int j = i + 1;
			while (j < tokens.size() && tokens.get(j).trim().isEmpty()) {
				j++;
			}
			if (j >= tokens.size() || !tokens.get(j).equals("(")) {
				out.append(t);
				continue;
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
				out.append(t);
				continue;
			}
			if (arg.length() > 0 || !args.isEmpty() || !m.params.isEmpty()) {
				args.add(arg.toString());
			}
			out.append(this.expand(this.substitute(m, args, disabled, depth), withName(disabled, t), depth + 1));
			i = k;
		}
		return out.toString();
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
				int len = out.length();
				while (len > 0 && out.charAt(len - 1) == ' ') {
					len--;
				}
				out.setLength(len);
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

	private double evalCondition(final String expression) {
		StringBuilder pre = new StringBuilder();
		List<String> tokens = tokenize(expression);
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
		String expanded = this.expand(pre.toString(), new HashSet<>(), 0);
		try {
			return new ExprParser(tokenize(expanded)).parseTernary();
		} catch (RuntimeException e) {
			this.result.errors.add("bad #if expression: " + expression + " -> " + expanded);
			return 0.0;
		}
	}

	private static final class ExprParser {
		private final List<String> tokens = new ArrayList<>();
		private int pos;

		ExprParser(final List<String> raw) {
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
				return t.equals("true") ? 1 : 0;
			}
			return parseNumber(t);
		}
	}

	static double parseNumber(final String token) {
		String t = token;
		if (t.startsWith("0x") || t.startsWith("0X")) {
			t = t.substring(2);
			while (!t.isEmpty() && (t.endsWith("u") || t.endsWith("U") || t.endsWith("l") || t.endsWith("L"))) {
				t = t.substring(0, t.length() - 1);
			}
			return Long.parseLong(t, 16);
		}
		while (!t.isEmpty() && "uUlLfF".indexOf(t.charAt(t.length() - 1)) >= 0) {
			t = t.substring(0, t.length() - 1);
		}
		return Double.parseDouble(t);
	}
}
