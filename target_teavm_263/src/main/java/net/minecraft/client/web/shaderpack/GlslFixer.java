// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GlslFixer {
	private GlslFixer() {
	}

	private static final Pattern ERROR_LINE = Pattern.compile("ERROR:\\s*\\d+:(\\d+):\\s*(.*)");
	private static final Pattern INT_DECL = Pattern.compile(
		"\\b(?:(?:const|uniform|in|out|flat|highp|mediump|lowp)\\s+)*(int|uint|ivec[234]|uvec[234])\\s+(\\w+)\\s*([^;(]*)");
	private static final Pattern FLOAT_DECL = Pattern.compile(
		"\\b(float|vec[234]|mat[234]|bool|bvec[234])\\s+(\\w+)");
	private static final Set<String> INT_CONTEXT_CALLS = new HashSet<>();

	static {
		for (String s : new String[] {"texelFetch",
			"textureSize", "texelFetchOffset", "bitfieldExtract", "findMSB", "findLSB", "bitCount", "textureOffset",
			"textureLodOffset", "textureGradOffset", "intBitsToFloat", "uintBitsToFloat", "unpackUnorm4x8", "unpackSnorm4x8",
			"unpackHalf2x16", "unpackUnorm2x16", "unpackSnorm2x16", "iris_gather", "textureGather"}) {
			INT_CONTEXT_CALLS.add(s);
		}
	}

	public static final class Error {
		public final int line;
		public final String message;

		Error(final int line, final String message) {
			this.line = line;
			this.message = message;
		}
	}

	public static List<Error> parseLog(final String log) {
		List<Error> errors = new ArrayList<>();
		if (log == null) {
			return errors;
		}
		for (String l : log.split("\n")) {
			Matcher m = ERROR_LINE.matcher(l.trim());
			if (m.find()) {
				errors.add(new Error(Integer.parseInt(m.group(1)), m.group(2)));
			}
		}
		return errors;
	}

	public static String fix(final String source, final String log) {
		List<Error> errors = parseLog(log);
		if (errors.isEmpty()) {
			return null;
		}
		String[] lines = source.split("\n", -1);
		List<int[]> declPos = new ArrayList<>();
		List<String> declNames = new ArrayList<>();
		declarations(source, declPos, declNames);
		Map<String, List<String>> functions = functionParameters(source);
		int[] lineStart = new int[lines.length + 1];
		for (int i = 0, off = 0; i < lines.length; i++) {
			lineStart[i] = off;
			off += lines[i].length() + 1;
		}
		boolean changed = false;
		Set<Integer> done = new HashSet<>();
		String renamed = null;
		for (Error e : errors) {
			int idx = e.line - 1;
			if (idx < 0 || idx >= lines.length) {
				continue;
			}
			String msg = e.message;
			if ((msg.contains("built-in") || msg.contains("builtin")) && (msg.contains("redefin") || msg.contains("redeclar") || msg.contains("overload"))) {
				Matcher name = Pattern.compile("'(\\w+)'").matcher(msg);
				if (name.find()) {
					renamed = name.group(1);
				}
				continue;
			}
			if (!done.add(idx)) {
				continue;
			}
			String fixed = lines[idx];
			if (msg.contains("assigning non-constant to") || msg.contains("must be a constant expression")
				|| msg.contains("constant expression required") || msg.contains("non-constant initializer")) {
				fixed = fixed.replaceFirst("\\bconst\\s+", "");
			}
			Matcher callee = NO_OVERLOAD.matcher(msg);
			if (callee.find() && functions.containsKey(callee.group(1))) {
				int span = Math.min(lines.length, idx + 40);
				StringBuilder joined = new StringBuilder();
				for (int k = idx; k < span; k++) {
					joined.append(lines[k]).append(k < span - 1 ? "\n" : "");
				}
				String call = literalArguments(joined.toString(), callee.group(1), functions.get(callee.group(1)));
				if (call != null) {
					String[] parts = call.split("\n", -1);
					for (int k = idx; k < span; k++) {
						if (!parts[k - idx].equals(lines[k])) {
							lines[k] = parts[k - idx];
							done.add(k);
							changed = true;
						}
					}
					continue;
				}
			}
			if (msg.contains("case label type does not match")) {
				String u = fixed.replaceAll("\\bcase\\s+(\\d+)\\s*:", "case $1u:");
				if (!u.equals(fixed)) {
					lines[idx] = u;
					changed = true;
				}
				continue;
			}
			if (msg.contains("to 'in highp uint'") || msg.contains("to 'highp uint'") && msg.contains("highp int'")) {
				String u = fixed;
				int eq = u.indexOf('=');
				boolean assignment = msg.contains("assign") && eq > 0 && u.indexOf(';', eq) > eq
					&& "=!<>".indexOf(u.charAt(eq - 1)) < 0 && (eq + 1 >= u.length() || u.charAt(eq + 1) != '=');
				if (assignment) {
					int end = u.indexOf(';', eq);
					u = u.substring(0, eq + 1) + " uint(" + u.substring(eq + 1, end).trim() + ")" + u.substring(end);
				} else {
					Map<String, Integer> ints = intNamesAt(declPos, declNames, lineStart[idx] + lines[idx].length());
					for (Map.Entry<String, Integer> n : ints.entrySet()) {
						if (n.getValue() == 1) {
							u = u.replaceAll("(?<![\\w.])" + n.getKey() + "(?![\\w(])", "uint(" + n.getKey() + ")");
						}
					}
					u = INT_LITERAL.matcher(u).replaceAll("$1u");
				}
				if (!u.equals(fixed)) {
					lines[idx] = u;
					changed = true;
				}
				continue;
			}
			if (msg.contains("uint") && (msg.contains("'const int'") || msg.contains("'const highp int'")) && !msg.contains("float")) {
				String u = uintify(fixed);
				if (u.equals(fixed)) {
					Map<String, Integer> ints = intNamesAt(declPos, declNames, lineStart[idx] + lines[idx].length());
					for (Map.Entry<String, Integer> n : ints.entrySet()) {
						if (n.getValue() == 1 && !Pattern.compile("\\b\\w+\\s+" + n.getKey() + "\\s*[=;,]").matcher(fixed).find()) {
							u = u.replaceAll("(?<![\\w.])" + n.getKey() + "(?![\\w(])", "uint(" + n.getKey() + ")");
						}
					}
				}
				if (!u.equals(fixed)) {
					lines[idx] = u;
					changed = true;
				}
				continue;
			}
			if (isConversionError(msg)) {
				int first = idx;
				while (first > 0 && !endsStatement(lines[first - 1])) {
					first--;
				}
				int last = idx;
				while (last < lines.length - 1 && !lines[last].contains(";") && !lines[last].trim().endsWith("{")) {
					last++;
				}
				StringBuilder joined = new StringBuilder();
				for (int k = first; k <= last; k++) {
					joined.append(k == idx ? fixed : lines[k]).append(k < last ? "\n" : "");
				}
				Map<String, Integer> intsHere = intNamesAt(declPos, declNames, lineStart[idx] + lines[idx].length());
				String pre = joined.toString();
				if (msg.contains("wrong operand types")) {
					Matcher mm = MOD_IN_ARITHMETIC.matcher(pre);
					StringBuilder mb = new StringBuilder();
					int mf = 0;
					while (mm.find()) {
						if (Integer.valueOf(1).equals(intsHere.get(mm.group(2)))) {
							mb.append(pre, mf, mm.start(2)).append("float(").append(mm.group(2)).append(mm.group(3)).append(')');
							mf = mm.end(3);
						}
					}
					pre = mb.append(pre.substring(mf)).toString();
				}
				String statement = floatifyLine(pre, intsHere, functions);
				statement = wrapIntResult(statement, msg);
				statement = restoreBitOperands(statement);
				statement = restoreModCompare(statement);
				String[] parts = statement.split("\n", -1);
				if (parts.length == last - first + 1) {
					for (int k = first; k <= last; k++) {
						if (k != idx && !parts[k - first].equals(lines[k])) {
							lines[k] = parts[k - first];
							done.add(k);
							changed = true;
						}
					}
					fixed = parts[idx - first];
				}
			}
			if (msg.contains("integer expression required") || msg.contains("array index") && msg.contains("integer")) {
				fixed = intIndex(fixed);
			}
			if (!fixed.equals(lines[idx])) {
				lines[idx] = fixed;
				changed = true;
			}
		}
		String result = String.join("\n", lines);
		if (renamed != null) {
			result = Pattern.compile("\\b" + renamed + "(\\s*\\()").matcher(result).replaceAll("iris_user_" + renamed + "$1");
			changed = true;
		}
		if (changed && result.contains("const ") && !result.equals(source)) {
			result = GlslTranslator.moveGlobalInitializers(result, new GlslTranslator.Output());
		}
		return changed ? result : null;
	}

	private static final Set<String> FLOAT_ONLY_CALLS = new HashSet<>(java.util.Arrays.asList(
		"sqrt", "pow", "exp", "exp2", "log", "log2", "sin", "cos", "tan", "asin", "acos", "atan", "floor", "ceil", "fract",
		"round", "mod", "mix", "smoothstep", "step", "length", "distance", "dot", "normalize", "float", "vec2", "vec3", "vec4",
		"texture", "textureLod", "inversesqrt", "radians", "degrees",
		"int", "uint", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4"));

	private static final Pattern MOD_COMPARE = Pattern.compile("(%" + "\\s*[A-Za-z_0-9.]+" + "\\s*(?:==|!=|<=|>=|<|>)" + "\\s*)(\\d+)\\.0\\b");
	private static final Pattern INT_LITERAL = Pattern.compile("(?<![\\w.])(\\d+)(?![\\w.])");
	private static final Pattern LENGTH_CALL = Pattern.compile("(\\w+(?:\\[[^\\]]*\\])?\\.length\\(\\))");

	static String restoreModCompare(final String s) {
		return MOD_COMPARE.matcher(s).replaceAll("$1$2");
	}

	static String uintify(final String s) {
		String r = INT_LITERAL.matcher(s).replaceAll("$1u");
		return LENGTH_CALL.matcher(r).replaceAll("uint($1)");
	}

	private static boolean floatOnlyAbove(final List<Frame> stack, final int depth) {
		for (int k = Math.max(0, depth); k < stack.size(); k++) {
			if (FLOAT_ONLY_CALLS.contains(stack.get(k).callee)) {
				return true;
			}
		}
		return false;
	}

	private static boolean endsStatement(final String line) {
		String t = line.trim();
		return t.isEmpty() || t.endsWith(";") || t.endsWith("{") || t.endsWith("}");
	}

	private static final Pattern INT_TO_FLOAT_ASSIGN = Pattern.compile(
		"cannot convert from '(?:\\w+ )*(?:(\\d)-component vector of int|int)' to '(?:\\w+ )*(?:(\\d)-component vector of float|float)'");

	static String wrapIntResult(final String statement, final String msg) {
		Matcher m = INT_TO_FLOAT_ASSIGN.matcher(msg);
		if (!m.find()) {
			return statement;
		}
		String n = m.group(2) != null ? m.group(2) : m.group(1);
		String wrapper = n == null ? "float" : "vec" + n;
		int level = 0;
		int eq = -1;
		for (int i = 0; i < statement.length(); i++) {
			char c = statement.charAt(i);
			if (c == '(' || c == '[') {
				level++;
			} else if (c == ')' || c == ']') {
				level--;
			} else if (c == '=' && level == 0) {
				char prev = i > 0 ? statement.charAt(i - 1) : ' ';
				char next = i + 1 < statement.length() ? statement.charAt(i + 1) : ' ';
				if (next != '=' && "=!<>%&|^".indexOf(prev) < 0) {
					eq = i;
					break;
				}
			}
		}
		int end = eq < 0 ? -1 : statement.indexOf(';', eq);
		if (eq < 0 || end < eq) {
			return statement;
		}
		return statement.substring(0, eq + 1) + " " + wrapper + "(" + statement.substring(eq + 1, end).trim() + ")" + statement.substring(end);
	}

	private static final Pattern LITERAL_GROUP_BEFORE_BIT = Pattern.compile("\\(([0-9.\\s+\\-*]+)\\)(\\s*(?:&|\\||\\^|<<|>>|%))");
	private static final Pattern BIT_AFTER_GROUP = Pattern.compile("\\(\\s*(\\d+)\\.0\\s*\\)(\\s*(?:&|\\||\\^|<<|>>|%))");
	private static final Pattern MOD_IN_ARITHMETIC = Pattern.compile("([(=,+\\-*/]\\s*)([A-Za-z_]\\w*)(\\s*%\\s*\\d+)(?=\\s*[-+*/)])");
	private static final Pattern FLOAT_OF_COMPARISON =Pattern.compile("\\bfloat\\(\\(([^()]*(?:<|>|==|!=)[^()]*)\\)\\)");
	private static final Pattern FLOAT_NAME_BIT =Pattern.compile(
		"(?:\\(float\\((\\w+)\\)\\)|\\bfloat\\((\\w+)\\))(\\s*(?:&(?!&)|\\|(?!\\|)|\\^|<<|>>|%)\\s*)(?:float\\((\\w+)\\)|(\\d+(?:\\.0)?|[A-Za-z_]\\w*)\\b(?!\\s*\\())");
	private static final Pattern BIT_BEFORE_GROUP =Pattern.compile("((?:&|\\||\\^|<<|>>|%)\\s*)\\(\\s*(\\d+)\\.0\\s*\\)");

	static String restoreBitOperands(String s) {
		Matcher b = FLOAT_NAME_BIT.matcher(s);
		StringBuilder bb = new StringBuilder();
		int bf = 0;
		while (b.find()) {
			String left = b.group(1) != null ? b.group(1) : b.group(2);
			String right = b.group(4) != null ? b.group(4) : b.group(5).replaceAll("^(\\d+)\\.0$", "$1");
			bb.append(s, bf, b.start()).append("float(").append(left).append(' ').append(b.group(3).trim()).append(' ').append(right).append(')');
			bf = b.end();
		}
		s = bb.append(s.substring(bf)).toString();
		Matcher g = LITERAL_GROUP_BEFORE_BIT.matcher(s);
		StringBuilder sb = new StringBuilder();
		int from = 0;
		while (g.find()) {
			sb.append(s, from, g.start()).append('(').append(g.group(1).replaceAll("(\\d+)\\.0\\b", "$1")).append(')').append(g.group(2));
			from = g.end();
		}
		String r = sb.append(s.substring(from)).toString();
		r = BIT_AFTER_GROUP.matcher(r).replaceAll("($1)$2");
		r = BIT_BEFORE_GROUP.matcher(r).replaceAll("$1($2)");
		return FLOAT_OF_COMPARISON.matcher(r).replaceAll("($1)");
	}

	private static boolean isConversionError(final String msg) {
		return msg.contains("wrong operand types") || msg.contains("no matching overloaded function")
			|| msg.contains("cannot convert") || msg.contains("not matching type") || msg.contains("type mismatch")
			|| msg.contains("Expression types") || msg.contains("arguments") && msg.contains("constructor")
			|| msg.contains("ternary") || msg.contains("dimension mismatch") || msg.contains("Mismatched")
			|| msg.contains("mismatch");
	}

	private static final Pattern ANY_DECL = Pattern.compile(
		"\\b(int|uint|ivec[234]|uvec[234]|float|vec[234]|mat[234]|bool|bvec[234])\\s+(\\w+)\\s*([^;(){}]*)");

	static void declarations(final String source, final List<int[]> pos, final List<String> names) {
		Matcher m = ANY_DECL.matcher(source);
		for (int from = 0; m.find(from); from = m.end(2)) {
			String type = m.group(1);
			boolean integer = type.startsWith("i") || type.startsWith("u");
			int comps = !integer ? 0 : type.endsWith("2") ? 2 : type.endsWith("3") ? 3 : type.endsWith("4") ? 4 : 1;
			if (KEYWORDS.contains(m.group(2))) {
				continue;
			}
			pos.add(new int[] {m.start(), comps});
			names.add(m.group(2));
			for (String part : GlslTranslator.splitTopLevel(m.group(3), ',')) {
				String p = part.trim();
				int end = GlslPreprocessor.identEnd(p, 0);
				if (end > 0 && !KEYWORDS.contains(p.substring(0, end))) {
					pos.add(new int[] {m.start(), comps});
					names.add(p.substring(0, end));
				}
			}
		}
	}

	static Map<String, Integer> intNamesAt(final List<int[]> pos, final List<String> names, final int offset) {
		Map<String, Integer> latest = new HashMap<>();
		for (int i = 0; i < pos.size(); i++) {
			if (pos.get(i)[0] < offset) {
				latest.put(names.get(i), pos.get(i)[1]);
			}
		}
		latest.values().removeIf(v -> v == 0);
		return latest;
	}

	static Map<String, Integer> intTypedNames(final String source) {
		Map<String, Integer> ints = new HashMap<>();
		Set<String> other = new HashSet<>();
		Matcher m = INT_DECL.matcher(source);
		while (m.find()) {
			String type = m.group(1);
			int comps = type.endsWith("2") ? 2 : type.endsWith("3") ? 3 : type.endsWith("4") ? 4 : 1;
			record(ints, other, m.group(2), comps);
			String tail = m.group(3);
			for (String part : GlslTranslator.splitTopLevel(tail, ',')) {
				String p = part.trim();
				int end = GlslPreprocessor.identEnd(p, 0);
				if (end > 0) {
					record(ints, other, p.substring(0, end), comps);
				}
			}
		}
		Matcher f = FLOAT_DECL.matcher(source);
		while (f.find()) {
			other.add(f.group(2));
		}
		for (String o : other) {
			ints.remove(o);
		}
		return ints;
	}

	private static final Set<String> KEYWORDS = new HashSet<>(java.util.Arrays.asList(
		"float", "int", "uint", "bool", "void", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
		"bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4", "in", "out", "inout", "const", "uniform", "flat", "smooth",
		"highp", "mediump", "lowp", "return", "if", "else", "for", "while", "do", "break", "continue", "discard", "struct",
		"true", "false", "layout", "centroid", "invariant", "precision"));

	private static void record(final Map<String, Integer> ints, final Set<String> other, final String name, final int comps) {
		if (KEYWORDS.contains(name)) {
			return;
		}
		Integer prev = ints.get(name);
		if (prev != null && prev != comps) {
			other.add(name);
		}
		ints.put(name, comps);
	}

	static String floatifyLine(final String line, final Map<String, Integer> intNames) {
		return floatifyLine(line, intNames, new HashMap<>());
	}

	private static final class Frame {
		final String callee;
		final boolean parentInt;
		int arg;

		Frame(final String callee, final boolean parentInt) {
			this.callee = callee;
			this.parentInt = parentInt;
		}
	}

	private static boolean isIntType(final String type) {
		return type.equals("int") || type.equals("uint") || type.startsWith("ivec") || type.startsWith("uvec");
	}

	private static boolean frameIsInt(final Frame f, final Map<String, List<String>> functions) {
		if (f.callee.equals("[")) {
			return true;
		}
		if (f.callee.isEmpty()) {
			return f.parentInt;
		}
		if (INT_CONTEXT_CALLS.contains(f.callee)) {
			return f.arg > 0;
		}
		List<String> params = functions.get(f.callee);
		if (params != null) {
			return f.arg < params.size() && isIntType(params.get(f.arg));
		}
		return false;
	}

	static String floatifyLine(final String line, final Map<String, Integer> intNames, final Map<String, List<String>> functions) {
		List<String> tokens = GlslPreprocessor.tokenize(line);
		List<Frame> stack = new ArrayList<>();
		Map<Integer, String> closeAfter = new HashMap<>();
		StringBuilder out = new StringBuilder(line.length() + 16);
		String prevSignificant = "";
		boolean intDeclaration = false;
		int intDeclDepth = -1;
		for (int i = 0; i < tokens.size(); i++) {
			String pending = closeAfter.remove(i - 1);
			if (pending != null) {
				out.append(pending);
			}
			String t = tokens.get(i);
			Frame top = stack.isEmpty() ? null : stack.get(stack.size() - 1);
			boolean intContext = top != null && frameIsInt(top, functions)
				|| intDeclaration && stack.size() > intDeclDepth && !floatOnlyAbove(stack, intDeclDepth);
			if (t.equals("(") && !intContext && !(intDeclaration && stack.size() == intDeclDepth)
				&& (prevSignificant.isEmpty() || !GlslPreprocessor.isIdentStart(prevSignificant.charAt(0)))) {
				int close = closing(tokens, i, "(", ")");
				if (close > i && integerGroup(tokens, i + 1, close, intNames)
					&& !isBitOp(operatorBefore(tokens, i)) && !isBitOp(operatorAfter(tokens, close))) {
					out.append("float(");
					for (int k = i; k <= close; k++) {
						out.append(tokens.get(k));
					}
					out.append(')');
					i = close;
					prevSignificant = ")";
					continue;
				}
			}
			if (t.equals("(")) {
				boolean call = !prevSignificant.isEmpty() && GlslPreprocessor.isIdentStart(prevSignificant.charAt(0));
				stack.add(new Frame(call ? prevSignificant : "", intContext));
				out.append(t);
				prevSignificant = t;
				continue;
			}
			if (t.equals("[")) {
				stack.add(new Frame("[", intContext));
				out.append(t);
				prevSignificant = t;
				continue;
			}
			if (t.equals(")") || t.equals("]")) {
				if (!stack.isEmpty()) {
					stack.remove(stack.size() - 1);
				}
				out.append(t);
				prevSignificant = t;
				continue;
			}
			if (t.equals(",") && top != null) {
				top.arg++;
			}
			if ((t.equals(";") || t.equals(",")) && stack.size() <= intDeclDepth) {
				intDeclaration = false;
			}
			if (t.trim().isEmpty()) {
				out.append(t);
				continue;
			}
			char c = t.charAt(0);
			boolean topLevelIntDecl = intDeclaration && stack.size() == intDeclDepth;
			if (GlslPreprocessor.isIdentStart(c)) {
				boolean intCall = isIntType(t) || t.equals("textureSize");
				if (intCall && nextSignificant(tokens, i + 1).equals("(") && !intContext && !topLevelIntDecl
					&& !((prevSignificant.equals("=") || prevSignificant.equals("return")) && wholeStatementCall(tokens, i))
					&& !bitwiseNeighbour(prevSignificant, tokens, i)
					&& !(top != null && prevSignificant.equals("(") && (top.callee.equals("float") || top.callee.startsWith("vec")))) {
					int open = i + 1;
					while (!tokens.get(open).equals("(")) {
						open++;
					}
					int close = closing(tokens, open, "(", ")");
					if (close > open) {
						int end = close;
						int comps = t.equals("textureSize") ? 2 : t.equals("int") || t.equals("uint") ? 1 : t.charAt(t.length() - 1) - '0';
						if (end + 2 < tokens.size() && tokens.get(end + 1).equals(".") && GlslPreprocessor.isIdentStart(tokens.get(end + 2).charAt(0))) {
							comps = tokens.get(end + 2).length();
							end += 2;
						}
						if (!isBitOp(operatorAfter(tokens, end))) {
							out.append(comps <= 1 ? "float" : "vec" + comps).append('(');
							closeAfter.merge(end, ")", (x, y) -> x + y);
						}
					}
				}
				if (isIntType(t) && !nextSignificant(tokens, i + 1).equals("(")) {
					intDeclaration = true;
					intDeclDepth = stack.size();
				}
				Integer comps = intNames.get(t);
				if (comps != null && !intContext && !topLevelIntDecl && !isLvalue(tokens, i) && !nextSignificant(tokens, i + 1).equals("(")
					&& !prevSignificant.equals(".")
					&& !bitwiseNeighbour(prevSignificant, tokens, i)) {
					int end = i + 1;
					StringBuilder expr = new StringBuilder(t);
					int resultComps = comps;
					while (end < tokens.size()) {
						String nx = tokens.get(end);
						if (nx.equals(".") && end + 1 < tokens.size() && GlslPreprocessor.isIdentStart(tokens.get(end + 1).charAt(0))) {
							expr.append('.').append(tokens.get(end + 1));
							resultComps = tokens.get(end + 1).length();
							end += 2;
						} else if (nx.equals("[")) {
							int close = closing(tokens, end, "[", "]");
							if (close < 0) {
								break;
							}
							for (int k = end; k <= close; k++) {
								expr.append(tokens.get(k));
							}
							resultComps = 1;
							end = close + 1;
						} else {
							break;
						}
					}
					String wrapper = resultComps <= 1 ? "float" : "vec" + resultComps;
					boolean alreadyWrapped = top != null && top.callee.equals(wrapper) && prevSignificant.equals("(")
						&& nextSignificant(tokens, end).equals(")");
					if (alreadyWrapped) {
						out.append(expr);
					} else {
						out.append(wrapper).append('(').append(expr).append(')');
					}
					i = end - 1;
					prevSignificant = ")";
					continue;
				}
				out.append(t);
				prevSignificant = t;
				continue;
			}
			if (Character.isDigit(c) && isIntLiteral(t) && !intContext && !topLevelIntDecl && !prevSignificant.equals(".")
				&& !prevSignificant.equals("case")
				&& !bitwiseNeighbour(prevSignificant, tokens, i)) {
				out.append(toFloatLiteral(t));
				prevSignificant = t;
				continue;
			}
			out.append(t);
			prevSignificant = t;
		}
		for (String pending : closeAfter.values()) {
			out.append(pending);
		}
		return out.toString();
	}

	private static boolean integerGroup(final List<String> tokens, final int from, final int to, final Map<String, Integer> intNames) {
		boolean name = false;
		for (int k = from; k < to; k++) {
			String t = tokens.get(k);
			if (t.trim().isEmpty() || "+-*/%&|^~<>()".contains(t)) {
				continue;
			}
			char c = t.charAt(0);
			if (GlslPreprocessor.isIdentStart(c)) {
				Integer comps = intNames.get(t);
				if (comps == null || comps != 1) {
					return false;
				}
				name = true;
			} else if (!(Character.isDigit(c) && isIntLiteral(t))) {
				return false;
			}
		}
		return name;
	}

	private static final Pattern FUNCTION_DEF = Pattern.compile(
		"\\b(void|float|int|uint|bool|[iub]?vec[234]|mat[234]|[A-Z]\\w*)\\s+(\\w+)\\s*\\(([^()]*)\\)\\s*\\{");

	private static final Pattern NO_OVERLOAD = Pattern.compile("'(\\w+)' : no matching overloaded function");

	static String literalArguments(final String text, final String name, final List<String> params) {
		Matcher m = Pattern.compile("\\b" + name + "\\s*\\(").matcher(text);
		if (!m.find()) {
			return null;
		}
		StringBuilder out = new StringBuilder(text.substring(0, m.end()));
		int level = 0;
		int arg = 0;
		int argStart = m.end();
		boolean changed = false;
		for (int i = m.end(); i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '(' || c == '[') {
				level++;
			} else if ((c == ')' || c == ']') && level > 0) {
				level--;
			} else if (level == 0 && (c == ',' || c == ')')) {
				String a = text.substring(argStart, i);
				String t = a.trim();
				String type = arg < params.size() ? params.get(arg) : "";
				String repl = a;
				if (type.equals("uint") && t.startsWith("int(") && t.endsWith(")")) {
					repl = a.replace(t, "u" + t);
				} else if (t.matches("\\d+")) {
					if (type.equals("uint")) {
						repl = a.replace(t, t + "u");
					} else if (type.equals("float")) {
						repl = a.replace(t, t + ".0");
					}
				}
				changed |= !repl.equals(a);
				out.append(repl).append(c);
				argStart = i + 1;
				arg++;
				if (c == ')') {
					return changed ? out.append(text.substring(i + 1)).toString() : null;
				}
			}
		}
		return null;
	}

	static Map<String, List<String>> functionParameters(final String source) {
		Map<String, List<String>> map = new HashMap<>();
		Matcher m = FUNCTION_DEF.matcher(source);
		while (m.find()) {
			List<String> types = new ArrayList<>();
			for (String param : m.group(3).split(",")) {
				String type = null;
				for (String word : param.trim().split("\\s+")) {
					if (word.isEmpty() || word.equals("in") || word.equals("out") || word.equals("inout") || word.equals("const")
						|| word.equals("highp") || word.equals("mediump") || word.equals("lowp")) {
						continue;
					}
					type = word;
					break;
				}
				if (type != null && !type.equals("void")) {
					types.add(type);
				}
			}
			List<String> previous = map.get(m.group(2));
			if (previous != null) {
				for (int i = 0; i < previous.size(); i++) {
					if (i >= types.size() || isIntType(previous.get(i)) != isIntType(types.get(i))) {
						previous.set(i, "*");
					}
				}
				continue;
			}
			map.put(m.group(2), types);
		}
		return map;
	}

	private static boolean isIntLiteral(final String t) {
		if (t.startsWith("0x") || t.startsWith("0X")) {
			return !t.endsWith("u") && !t.endsWith("U");
		}
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if (!Character.isDigit(c)) {
				return false;
			}
		}
		return true;
	}

	private static String toFloatLiteral(final String t) {
		if (t.startsWith("0x") || t.startsWith("0X")) {
			return Long.parseLong(t.substring(2), 16) + ".0";
		}
		return t + ".0";
	}

	private static boolean bitwiseNeighbour(final String prev, final List<String> tokens, final int i) {
		return isBitOp(operatorBefore(tokens, i)) || isBitOp(operatorAfter(tokens, i));
	}

	private static boolean isBitOp(final String op) {
		return op.equals("&") || op.equals("|") || op.equals("^") || op.equals("%") || op.equals("~") || op.equals("<<") || op.equals(">>");
	}

	private static String operatorBefore(final List<String> tokens, final int i) {
		int k = i - 1;
		while (k >= 0 && tokens.get(k).trim().isEmpty()) {
			k--;
		}
		if (k < 0 || tokens.get(k).length() != 1 || GlslPreprocessor.isIdentPart(tokens.get(k).charAt(0))) {
			return "";
		}
		String a = tokens.get(k);
		if (k > 0 && tokens.get(k - 1).length() == 1 && ("&|<>=!".indexOf(tokens.get(k - 1).charAt(0)) >= 0
			|| ("+-".indexOf(a.charAt(0)) >= 0 && tokens.get(k - 1).equals(a)))) {
			return tokens.get(k - 1) + a;
		}
		return a;
	}

	private static String operatorAfter(final List<String> tokens, final int i) {
		int k = i + 1;
		while (k < tokens.size() && tokens.get(k).trim().isEmpty()) {
			k++;
		}
		if (k >= tokens.size() || tokens.get(k).length() != 1 || GlslPreprocessor.isIdentPart(tokens.get(k).charAt(0))) {
			return "";
		}
		String a = tokens.get(k);
		if (k + 1 < tokens.size() && tokens.get(k + 1).length() == 1 && ("&|<>=".indexOf(tokens.get(k + 1).charAt(0)) >= 0
			|| ("+-".indexOf(a.charAt(0)) >= 0 && tokens.get(k + 1).equals(a)))) {
			return a + tokens.get(k + 1);
		}
		return a;
	}

	private static boolean wholeStatementCall(final List<String> tokens, final int i) {
		int open = i + 1;
		while (open < tokens.size() && !tokens.get(open).equals("(")) {
			open++;
		}
		int close = closing(tokens, open, "(", ")");
		if (close < 0) {
			return false;
		}
		String after = nextSignificant(tokens, close + 1);
		return after.equals(";") || after.isEmpty();
	}

	private static boolean isLvalue(final List<String> tokens, final int i) {
		String before = operatorBefore(tokens, i);
		if (before.equals("++") || before.equals("--")) {
			return true;
		}
		String next = nextSignificant(tokens, i + 1);
		String next2 = nextSignificantAfter(tokens, i + 1);
		if (next.equals("=") && !next2.equals("=")) {
			return true;
		}
		if ((next.equals("+") || next.equals("-") || next.equals("*") || next.equals("/")) && next2.equals("=")) {
			return true;
		}
		if ((next.equals("+") && next2.equals("+")) || (next.equals("-") && next2.equals("-"))) {
			return true;
		}
		return false;
	}

	private static String nextSignificant(final List<String> tokens, final int from) {
		for (int i = from; i < tokens.size(); i++) {
			if (!tokens.get(i).trim().isEmpty()) {
				return tokens.get(i);
			}
		}
		return "";
	}

	private static String nextSignificantAfter(final List<String> tokens, final int from) {
		boolean first = false;
		for (int i = from; i < tokens.size(); i++) {
			if (!tokens.get(i).trim().isEmpty()) {
				if (first) {
					return tokens.get(i);
				}
				first = true;
			}
		}
		return "";
	}

	private static int closing(final List<String> tokens, final int open, final String o, final String c) {
		int level = 0;
		for (int i = open; i < tokens.size(); i++) {
			if (tokens.get(i).equals(o)) {
				level++;
			} else if (tokens.get(i).equals(c)) {
				level--;
				if (level == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String intIndex(final String line) {
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < line.length()) {
			char c = line.charAt(i);
			if (c == '[') {
				int level = 0;
				int j = i;
				for (; j < line.length(); j++) {
					if (line.charAt(j) == '[') {
						level++;
					} else if (line.charAt(j) == ']') {
						level--;
						if (level == 0) {
							break;
						}
					}
				}
				if (j < line.length()) {
					String inner = line.substring(i + 1, j);
					out.append("[int(").append(inner).append(")]");
					i = j + 1;
					continue;
				}
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}
}
