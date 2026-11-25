// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs translated shader source from the WebGL compiler's own error list.
 *
 * <p>Desktop GLSL converts int to float (and int to uint) implicitly in arithmetic, comparisons,
 * assignments and function arguments; GLSL ES 3.00 does not, and every pack leans on it.
 * Each line the compiler rejects is rewritten using scoped variable type declarations, per-function
 * integer parameter masks, array-vs-vector tracking, and texture dimension lookup.
 *
 * <p>Error lines are found from ANGLE's {@code ERROR: 0:<line>: ...} format.
 */
public final class GlslFixer {
	private GlslFixer() {
	}

	private static final Pattern ERROR_LINE = Pattern.compile("ERROR:\\s*\\d+:(\\d+):\\s*(.*)");
	private static final Pattern INT_DECL = Pattern.compile(
		"\\b(?:(?:const|uniform|in|out|flat|highp|mediump|lowp)\\s+)*(int|uint|ivec[234]|uvec[234])\\s+(\\w+)\\s*([^;(]*)");
	private static final Pattern FLOAT_DECL = Pattern.compile(
		"\\b(float|vec[234]|mat[234]|bool|bvec[234])\\s+(\\w+)");
	private static final Pattern SAMPLER_3D_DECL = Pattern.compile(
		"\\b[iu]?sampler(?:3D|2DArray)(?:Shadow)?\\s+(\\w+)\\b");

	/**
	 * Per-function bitmask of parameter indices that MUST stay integer (bit {@code 1 << argIndex}).
	 * Prevents e.g. {@code bitCount(n)} arg 0 from becoming float or {@code textureOffset(s, uv, off)}
	 * arg 1 ({@code uv}) from being treated as int.
	 */
	private static final Map<String, Integer> INT_PARAM_MASKS = new HashMap<>();
	/** Parameters that specifically require signed {@code int} (must NEVER be converted to {@code uint}). */
	private static final Map<String, Integer> STRICT_SIGNED_INT_MASKS = new HashMap<>();

	static {
		INT_PARAM_MASKS.put("texelFetch", 0b110);           // (sampler, P, lod)
		INT_PARAM_MASKS.put("texelFetchOffset", 0b1110);    // (sampler, P, lod, offset)
		INT_PARAM_MASKS.put("textureSize", 0b10);           // (sampler, lod)
		INT_PARAM_MASKS.put("textureOffset", 0b100);        // (sampler, P, offset [, bias])
		INT_PARAM_MASKS.put("textureLodOffset", 0b1000);    // (sampler, P, lod, offset)
		INT_PARAM_MASKS.put("textureGradOffset", 0b10000);  // (sampler, P, dPdx, dPdy, offset)
		INT_PARAM_MASKS.put("textureProjOffset", 0b100);    // (sampler, P, offset [, bias])
		INT_PARAM_MASKS.put("textureProjLodOffset", 0b1000);// (sampler, P, lod, offset)
		INT_PARAM_MASKS.put("textureProjGradOffset", 0b10000);
		INT_PARAM_MASKS.put("textureGather", 0b100);        // (sampler, P [, comp])
		INT_PARAM_MASKS.put("iris_gather", 0b100);          // (sampler, P, comp)
		INT_PARAM_MASKS.put("textureGatherOffset", 0b1100); // (sampler, P, offset [, comp])
		INT_PARAM_MASKS.put("bitfieldExtract", 0b111);      // (value, offset, bits)
		INT_PARAM_MASKS.put("bitfieldInsert", 0b1111);      // (base, insert, offset, bits)
		INT_PARAM_MASKS.put("bitfieldReverse", 0b1);        // (value)
		INT_PARAM_MASKS.put("findMSB", 0b1);                // (value)
		INT_PARAM_MASKS.put("findLSB", 0b1);                // (value)
		INT_PARAM_MASKS.put("bitCount", 0b1);               // (value)
		INT_PARAM_MASKS.put("intBitsToFloat", 0b1);         // (value)
		INT_PARAM_MASKS.put("uintBitsToFloat", 0b1);        // (value)
		INT_PARAM_MASKS.put("unpackUnorm4x8", 0b1);         // (value)
		INT_PARAM_MASKS.put("unpackSnorm4x8", 0b1);         // (value)
		INT_PARAM_MASKS.put("unpackHalf2x16", 0b1);         // (value)
		INT_PARAM_MASKS.put("unpackUnorm2x16", 0b1);        // (value)
		INT_PARAM_MASKS.put("unpackSnorm2x16", 0b1);        // (value)
		INT_PARAM_MASKS.put("ldexp", 0b10);                 // (x, exp)

		STRICT_SIGNED_INT_MASKS.put("texelFetch", 0b110);
		STRICT_SIGNED_INT_MASKS.put("texelFetchOffset", 0b1110);
		STRICT_SIGNED_INT_MASKS.put("textureSize", 0b10);
		STRICT_SIGNED_INT_MASKS.put("textureOffset", 0b100);
		STRICT_SIGNED_INT_MASKS.put("textureLodOffset", 0b1000);
		STRICT_SIGNED_INT_MASKS.put("textureGradOffset", 0b10000);
		STRICT_SIGNED_INT_MASKS.put("textureProjOffset", 0b100);
		STRICT_SIGNED_INT_MASKS.put("textureProjLodOffset", 0b1000);
		STRICT_SIGNED_INT_MASKS.put("textureProjGradOffset", 0b10000);
		STRICT_SIGNED_INT_MASKS.put("textureGather", 0b100);
		STRICT_SIGNED_INT_MASKS.put("iris_gather", 0b100);
		STRICT_SIGNED_INT_MASKS.put("textureGatherOffset", 0b1100);
		STRICT_SIGNED_INT_MASKS.put("bitfieldExtract", 0b110);
		STRICT_SIGNED_INT_MASKS.put("bitfieldInsert", 0b1100);
		STRICT_SIGNED_INT_MASKS.put("int", 0b1);
		STRICT_SIGNED_INT_MASKS.put("ivec2", 0b11);
		STRICT_SIGNED_INT_MASKS.put("ivec3", 0b111);
		STRICT_SIGNED_INT_MASKS.put("ivec4", 0b1111);
		STRICT_SIGNED_INT_MASKS.put("ldexp", 0b10);
	}

	public static final class Error {
		public final int line;
		public final String message;

		Error(final int line, final String message) {
			this.line = line;
			this.message = message;
		}
	}

	private static final class DeclInfo {
		final int comps; // 1..4
		final boolean isUint;
		final boolean isArray;

		DeclInfo(final int comps, final boolean isUint, final boolean isArray) {
			this.comps = comps;
			this.isUint = isUint;
			this.isArray = isArray;
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

	private static final Pattern BUILTIN_NAME = Pattern.compile("'(\\w+)'");
	private static final Pattern CONST_QUAL = Pattern.compile("\\bconst\\s+");
	private static final Pattern CASE_LITERAL = Pattern.compile("\\bcase\\s+(0[xX][0-9a-fA-F]+|\\d+)\\s*:");
	private static final Pattern CASE_UINT_LITERAL = Pattern.compile("\\bcase\\s+(0[xX][0-9a-fA-F]+|\\d+)[uU]\\s*:");
	private static final Pattern CASE_IDENT = Pattern.compile("\\bcase\\s+([A-Za-z_]\\w*)\\s*:");

	/** Returns patched source, or null if nothing in the log is something this class can fix. */
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
		Set<String> samplers3D = find3DSamplers(source);
		int[] lineStart = new int[lines.length + 1];
		for (int i = 0, off = 0; i < lines.length; i++) {
			lineStart[i] = off;
			off += lines[i].length() + 1;
		}
		boolean changed = false;
		boolean strippedConst = false;
		// Track handled (lineIdx + category) so multiple distinct errors on one line all get fixed
		Set<String> handled = new HashSet<>();
		String renamed = null;

		for (Error e : errors) {
			int idx = e.line - 1;
			if (idx < 0 || idx >= lines.length) {
				continue;
			}
			String msg = e.message;
			if ((msg.contains("built-in") || msg.contains("builtin"))
				&& (msg.contains("redefin") || msg.contains("redeclar") || msg.contains("overload"))) {
				Matcher name = BUILTIN_NAME.matcher(msg);
				if (name.find()) {
					renamed = name.group(1);
				}
				continue;
			}

			// 1. Non-constant const initializer (scan backwards for multi-line const declarations!)
			if (msg.contains("assigning non-constant to") || msg.contains("must be a constant expression")
				|| msg.contains("constant expression required") || msg.contains("non-constant initializer")) {
				if (handled.add(idx + ":const")) {
					if (CONST_QUAL.matcher(lines[idx]).find()) {
						lines[idx] = CONST_QUAL.matcher(lines[idx]).replaceFirst("");
						changed = true;
						strippedConst = true;
					} else {
						int limit = Math.max(0, idx - 40);
						for (int k = idx - 1; k >= limit; k--) {
							if (CONST_QUAL.matcher(lines[k]).find()) {
								lines[k] = CONST_QUAL.matcher(lines[k]).replaceFirst("");
								changed = true;
								strippedConst = true;
								break;
							}
							if (endsStatement(lines[k])) {
								break;
							}
						}
					}
				}
			}

			String fixed = lines[idx];

			// 2. Call to user function with wrong literal types
			Matcher callee = NO_OVERLOAD.matcher(msg);
			if (callee.find() && functions.containsKey(callee.group(1)) && handled.add(idx + ":call:" + callee.group(1))) {
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
							changed = true;
						}
					}
					continue;
				}
			}

			// 3. Switch case label type mismatch (decimal, hex, named constants, uint<->int)
			if (msg.contains("case label type does not match") && handled.add(idx + ":case")) {
				String u = fixed;
				if (CASE_UINT_LITERAL.matcher(u).find()) {
					u = CASE_UINT_LITERAL.matcher(u).replaceAll("case $1:");
				} else if (CASE_LITERAL.matcher(u).find()) {
					u = CASE_LITERAL.matcher(u).replaceAll("case $1u:");
				} else if (CASE_IDENT.matcher(u).find()) {
					u = CASE_IDENT.matcher(u).replaceAll("case uint($1):");
				}
				if (!u.equals(fixed)) {
					lines[idx] = u;
					changed = true;
				}
				continue;
			}

			// 4. Signed int <-> unsigned uint mismatch (const or non-const, without touching l-values or texelFetch LOD)
			if (isUintMismatchError(msg) && handled.add(idx + ":uint")) {
				String u = fixed;
				int eq = topLevelAssignIndex(u);
				boolean assignment = msg.contains("assign") && eq > 0 && u.indexOf(';', eq) > eq;
				if (assignment && (msg.contains("to 'highp uint'") || msg.contains("to 'uint'"))) {
					int end = u.indexOf(';', eq);
					u = u.substring(0, eq + 1) + " uint(" + u.substring(eq + 1, end).trim() + ")" + u.substring(end);
				} else {
					Map<String, DeclInfo> declsHere = declsAt(declPos, declNames, lineStart[idx] + lines[idx].length());
					u = uintifyLine(u, declsHere, functions);
				}
				if (!u.equals(fixed)) {
					lines[idx] = u;
					changed = true;
				}
				continue;
			}

			// 5. General int -> float conversion error
			if (isConversionError(msg) && handled.add(idx + ":conv")) {
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
				Map<String, DeclInfo> declsHere = declsAt(declPos, declNames, lineStart[idx] + lines[idx].length());
				String pre = joined.toString();
				if (msg.contains("wrong operand types")) {
					Matcher mm = MOD_IN_ARITHMETIC.matcher(pre);
					StringBuilder mb = new StringBuilder();
					int mf = 0;
					while (mm.find()) {
						DeclInfo di = declsHere.get(mm.group(2));
						if (di != null && di.comps == 1) {
							mb.append(pre, mf, mm.start(2)).append("float(").append(mm.group(2)).append(mm.group(3)).append(')');
							mf = mm.end(3);
						}
					}
					pre = mb.append(pre.substring(mf)).toString();
				}
				String statement = floatifyLine(pre, declsHere, functions, samplers3D);
				statement = wrapIntResult(statement, msg);
				statement = restoreBitOperands(statement);
				statement = restoreModCompare(statement);
				String[] parts = statement.split("\n", -1);
				if (parts.length == last - first + 1) {
					for (int k = first; k <= last; k++) {
						if (k != idx && !parts[k - first].equals(lines[k])) {
							lines[k] = parts[k - first];
							handled.add(k + ":conv");
							changed = true;
						}
					}
					fixed = parts[idx - first];
				}
			}

			// 6. Float array index -> wrap subscript in int()
			if ((msg.contains("integer expression required") || (msg.contains("array index") && msg.contains("integer")))
				&& handled.add(idx + ":idx")) {
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
		if (strippedConst) {
			// A const that lost its constness at global scope must move into main like any other initializer
			result = GlslTranslator.moveGlobalInitializers(result, new GlslTranslator.Output());
		}
		return changed ? result : null;
	}

	private static boolean isUintMismatchError(final String msg) {
		if (msg.contains("float")) {
			return false;
		}
		if (msg.contains("to 'in highp uint'") || msg.contains("to 'in uint'")) {
			return true;
		}
		return (msg.contains("uint") || msg.contains("uvec"))
			&& (msg.contains("'int'") || msg.contains("highp int'") || msg.contains("mediump int'")
				|| msg.contains("lowp int'") || msg.contains("const int'") || msg.contains("ivec")
				|| msg.contains("component vector of int"));
	}

	private static int topLevelAssignIndex(final String s) {
		int level = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(' || c == '[') {
				level++;
			} else if (c == ')' || c == ']') {
				level--;
			} else if (c == '=' && level == 0) {
				char prev = i > 0 ? s.charAt(i - 1) : ' ';
				char next = i + 1 < s.length() ? s.charAt(i + 1) : ' ';
				if (next != '=' && "=!<>".indexOf(prev) < 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static Set<String> find3DSamplers(final String source) {
		Set<String> out = new HashSet<>();
		if (!source.contains("3D") && !source.contains("2DArray")) {
			return out;
		}
		Matcher m = SAMPLER_3D_DECL.matcher(source);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

	/** Built-ins with float-only overloads: inside them an int declaration's initializer is float again. */
	private static final Set<String> FLOAT_ONLY_CALLS = new HashSet<>(Arrays.asList(
		"sqrt", "pow", "exp", "exp2", "log", "log2", "sin", "cos", "tan", "asin", "acos", "atan", "floor", "ceil", "fract",
		"round", "mod", "mix", "smoothstep", "step", "length", "distance", "dot", "normalize", "float", "vec2", "vec3", "vec4",
		"texture", "textureLod", "inversesqrt", "radians", "degrees",
		"int", "uint", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4"));

	private static final Pattern MOD_COMPARE = Pattern.compile("(%\\s*[A-Za-z_0-9.]+\\s*(?:==|!=|<=|>=|<|>)\\s*)(\\d+)\\.0\\b");
	private static final Pattern INT_LITERAL = Pattern.compile("(?<![\\w.])(\\d+)(?![\\w.])");
	private static final Pattern LENGTH_CALL = Pattern.compile("(\\w+(?:\\[[^\\]]*\\])?\\.length\\(\\))");

	/** Integer comparisons against a % result keep integer literals ({@code i % 10 == 0}). */
	static String restoreModCompare(final String s) {
		return MOD_COMPARE.matcher(s).replaceAll("$1$2");
	}

	/** Legacy helper retained for compatibility: uintify literals and .length(). */
	static String uintify(final String s) {
		String r = INT_LITERAL.matcher(s).replaceAll("$1u");
		return LENGTH_CALL.matcher(r).replaceAll("uint($1)");
	}

	/**
	 * Token-aware uint conversion: converts conflicting int literals, signed scalar int variables,
	 * and {@code .length()} to uint while NEVER touching l-values, declaration names, {@code int}
	 * initializers, or parameters of built-ins that strictly require signed {@code int} (such as
	 * {@code texelFetch} coords/LOD, {@code textureSize} LOD, {@code bitfieldExtract} offset/bits).
	 */
	static String uintifyLine(final String line, final Map<String, DeclInfo> decls, final Map<String, List<String>> functions) {
		String work = LENGTH_CALL.matcher(line).replaceAll("uint($1)");
		List<String> tokens = GlslPreprocessor.tokenize(work);
		List<Frame> stack = new ArrayList<>();
		StringBuilder out = new StringBuilder(work.length() + 16);
		String prevSig = "";
		boolean signedIntDecl = false;
		int signedDeclDepth = -1;

		for (int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);
			Frame top = stack.isEmpty() ? null : stack.get(stack.size() - 1);
			boolean strictSigned = top != null && frameIsStrictSigned(top, functions);

			if (t.equals("(")) {
				boolean call = !prevSig.isEmpty() && GlslPreprocessor.isIdentStart(prevSig.charAt(0));
				stack.add(new Frame(call ? prevSig : "", strictSigned));
				out.append(t);
				prevSig = t;
				continue;
			}
			if (t.equals("[")) {
				stack.add(new Frame("[", strictSigned));
				out.append(t);
				prevSig = t;
				continue;
			}
			if (t.equals(")") || t.equals("]")) {
				if (!stack.isEmpty()) {
					stack.remove(stack.size() - 1);
				}
				out.append(t);
				prevSig = t;
				continue;
			}
			if (t.equals(",") && top != null) {
				top.arg++;
			}
			if ((t.equals(";") || t.equals(",")) && stack.size() <= signedDeclDepth) {
				signedIntDecl = false;
			}
			if (t.trim().isEmpty()) {
				out.append(t);
				continue;
			}
			char c = t.charAt(0);
			boolean skipForDecl = signedIntDecl && stack.size() <= signedDeclDepth;
			if (GlslPreprocessor.isIdentStart(c)) {
				if ((t.equals("int") || t.startsWith("ivec")) && !nextSignificant(tokens, i + 1).equals("(")) {
					signedIntDecl = true;
					signedDeclDepth = stack.size();
					out.append(t);
					prevSig = t;
					continue;
				}
				DeclInfo di = decls.get(t);
				if (di != null && !di.isUint && di.comps == 1 && !di.isArray
					&& !strictSigned && !skipForDecl
					&& !isLvalue(tokens, i) && !nextSignificant(tokens, i + 1).equals("(")
					&& !prevSig.equals(".") && !isTypeKeyword(prevSig)
					&& !(top != null && top.callee.equals("uint") && prevSig.equals("(") && nextSignificant(tokens, i + 1).equals(")"))) {
					out.append("uint(").append(t).append(')');
					prevSig = ")";
					continue;
				}
				out.append(t);
				prevSig = t;
				continue;
			}
			if (Character.isDigit(c) && isIntLiteral(t) && !strictSigned && !skipForDecl
				&& !prevSig.equals(".") && !prevSig.equals("case")
				&& !(top != null && top.callee.equals("["))) {
				out.append(t).append('u');
				prevSig = t;
				continue;
			}
			out.append(t);
			prevSig = t;
		}
		return out.toString();
	}

	private static boolean isTypeKeyword(final String t) {
		return t.equals("int") || t.equals("uint") || t.equals("float") || t.equals("bool")
			|| t.startsWith("vec") || t.startsWith("ivec") || t.startsWith("uvec") || t.startsWith("bvec");
	}

	private static boolean frameIsStrictSigned(final Frame f, final Map<String, List<String>> functions) {
		if (f.callee.equals("[")) {
			return true;
		}
		if (f.callee.isEmpty()) {
			return f.parentInt;
		}
		Integer mask = STRICT_SIGNED_INT_MASKS.get(f.callee);
		if (mask != null) {
			return f.arg < 31 && ((mask >>> f.arg) & 1) != 0;
		}
		List<String> params = functions.get(f.callee);
		if (params != null && f.arg < params.size()) {
			String p = params.get(f.arg);
			return p.equals("int") || p.startsWith("ivec");
		}
		return false;
	}

	/** Any frame above the declaration's depth is a float-only call (or a cast). */
	private static boolean floatOnlyAbove(final List<Frame> stack, final int depth) {
		for (int k = Math.max(0, depth); k < stack.size(); k++) {
			if (FLOAT_ONLY_CALLS.contains(stack.get(k).callee)) {
				return true;
			}
		}
		return false;
	}

	private static final Pattern CONTROL_HEADER = Pattern.compile("^(?:if|for|while)\\s*\\(.*\\)$");

	/** True when a line ends a statement or is a complete control-flow header (e.g. braceless `if (c)`). */
	private static boolean endsStatement(final String line) {
		String t = line.trim();
		if (t.isEmpty() || t.endsWith(";") || t.endsWith("{") || t.endsWith("}") || t.endsWith(":") || t.equals("else")) {
			return true;
		}
		if (t.endsWith(")") && CONTROL_HEADER.matcher(t).matches() && hasBalancedParens(t)) {
			return true;
		}
		return false;
	}

	private static boolean hasBalancedParens(final String s) {
		int bal = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(') {
				bal++;
			} else if (c == ')') {
				if (--bal < 0) {
					return false;
				}
			}
		}
		return bal == 0;
	}

	private static final Pattern INT_TO_FLOAT_ASSIGN = Pattern.compile(
		"cannot convert from '(?:\\w+ )*(?:(\\d)-component vector of u?int|u?int)' to '(?:\\w+ )*(?:(\\d)-component vector of float|float)'");
	private static final Pattern INT_DECL_START = Pattern.compile(
		"^(?:(?:const|highp|mediump|lowp)\\s+)*(?:int|uint|ivec[234]|uvec[234])\\b");
	private static final Pattern RETURN_STMT = Pattern.compile("\\breturn\\s+([^;]+);");

	/**
	 * {@code vec2 size = textureSize(tex, 0);} and {@code return intExpr;}: the message names the
	 * types, so wrap the right-hand side or returned expression in {@code vecN()}/{@code float()}.
	 * Guards against {@code for (int i = 0; ...)} and integer declarations.
	 */
	static String wrapIntResult(final String statement, final String msg) {
		Matcher m = INT_TO_FLOAT_ASSIGN.matcher(msg);
		if (!m.find()) {
			return statement;
		}
		String trimmed = statement.trim();
		if (trimmed.startsWith("for ") || trimmed.startsWith("for(") || INT_DECL_START.matcher(trimmed).find()) {
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
		if (eq >= 0 && end > eq) {
			return statement.substring(0, eq + 1) + " " + wrapper + "(" + statement.substring(eq + 1, end).trim() + ")" + statement.substring(end);
		}
		// Support `return intExpr;`
		Matcher rm = RETURN_STMT.matcher(statement);
		if (rm.find()) {
			return statement.substring(0, rm.start(1)) + wrapper + "(" + rm.group(1).trim() + ")" + statement.substring(rm.end(1));
		}
		return statement;
	}

	private static final Pattern LITERAL_GROUP_BEFORE_BIT = Pattern.compile("\\(([0-9.\\s+\\-*]+)\\)(\\s*(?:&|\\||\\^|<<|>>|%))");
	private static final Pattern BIT_AFTER_GROUP = Pattern.compile("\\(\\s*(\\d+)\\.0\\s*\\)(\\s*(?:&|\\||\\^|<<|>>|%))");
	private static final Pattern MOD_IN_ARITHMETIC = Pattern.compile("([(=,+\\-*/]\\s*)([A-Za-z_]\\w*)(\\s*%\\s*\\d+)(?=\\s*[-+*/)])");
	private static final Pattern FLOAT_OF_COMPARISON = Pattern.compile("\\bfloat\\(\\(([^()]*(?:<|>|==|!=)[^()]*)\\)\\)");
	private static final Pattern FLOAT_NAME_BIT = Pattern.compile(
		"(?:\\(float\\((\\w+)\\)\\)|\\bfloat\\((\\w+)\\))(\\s*(?:&(?!&)|\\|(?!\\|)|\\^|<<|>>|%)\\s*)(?:float\\((\\w+)\\)|(\\d+(?:\\.0)?|[A-Za-z_]\\w*)\\b(?!\\s*\\())");
	private static final Pattern BIT_BEFORE_GROUP = Pattern.compile("((?:&|\\||\\^|<<|>>|%)\\s*)\\(\\s*(\\d+)\\.0\\s*\\)");

	/** Undo literal conversions that ended up as operands of bit operators, e.g. {@code (0.0) & 1}. */
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
			|| msg.contains("Expression types") || (msg.contains("arguments") && msg.contains("constructor"))
			|| msg.contains("ternary") || msg.contains("dimension mismatch") || msg.contains("Mismatched")
			|| msg.contains("mismatch");
	}

	private static final Pattern ANY_DECL = Pattern.compile(
		"\\b(int|uint|ivec[234]|uvec[234]|float|vec[234]|mat[234]|bool|bvec[234])\\s*(\\[[^\\]]*\\]\\s*)?(\\w+)\\s*([^;(){}]*)");

	/**
	 * Every declaration as {@code {offset, comps (0 = not integer), scopeEnd, isUint (0/1), isArray (0/1)}}
	 * plus its name, tracking brace scopes so local variables never leak across functions or blocks.
	 */
	static void declarations(final String source, final List<int[]> pos, final List<String> names) {
		// Precompute enclosing brace scope end for every character offset in O(n)
		int n = source.length();
		int[] matchingClose = new int[n];
		Arrays.fill(matchingClose, -1);
		int[] braceStack = new int[Math.max(16, n / 16)];
		int sp = 0;
		for (int i = 0; i < n; i++) {
			char c = source.charAt(i);
			if (c == '{') {
				if (sp == braceStack.length) {
					braceStack = Arrays.copyOf(braceStack, braceStack.length * 2);
				}
				braceStack[sp++] = i;
			} else if (c == '}' && sp > 0) {
				int open = braceStack[--sp];
				matchingClose[open] = i;
			}
		}

		Matcher m = ANY_DECL.matcher(source);
		for (int from = 0; m.find(from); from = m.end(3)) {
			String type = m.group(1);
			boolean typeHasArray = m.group(2) != null;
			String firstName = m.group(3);
			if (KEYWORDS.contains(firstName)) {
				continue;
			}
			boolean isUint = type.startsWith("u");
			boolean integer = type.startsWith("i") || isUint;
			int comps = !integer ? 0 : type.endsWith("2") ? 2 : type.endsWith("3") ? 3 : type.endsWith("4") ? 4 : 1;
			int scopeEnd = findScopeEnd(source, m.start(), matchingClose);
			String tail = m.group(4);
			List<String> tailParts = GlslTranslator.splitTopLevel(tail, ',');
			boolean firstIsArray = typeHasArray || (!tailParts.isEmpty() && tailParts.get(0).trim().startsWith("["));
			pos.add(new int[] {m.start(), comps, scopeEnd, isUint ? 1 : 0, firstIsArray ? 1 : 0});
			names.add(firstName);
			for (int pi = 1; pi < tailParts.size(); pi++) {
				String p = tailParts.get(pi).trim();
				int end = GlslPreprocessor.identEnd(p, 0);
				if (end > 0) {
					String extraName = p.substring(0, end);
					if (!KEYWORDS.contains(extraName)) {
						boolean extraArr = typeHasArray || p.substring(end).trim().startsWith("[");
						pos.add(new int[] {m.start(), comps, scopeEnd, isUint ? 1 : 0, extraArr ? 1 : 0});
						names.add(extraName);
					}
				}
			}
		}
	}

	/** Finds the end offset of the enclosing brace/paren-header scope for a declaration at {@code offset}. */
	private static int findScopeEnd(final String source, final int offset, final int[] matchingClose) {
		int n = source.length();
		// Check if inside a function parameter list `(...) {` or `for (...) {`
		int parenDepth = 0;
		for (int i = offset - 1; i >= 0; i--) {
			char c = source.charAt(i);
			if (c == ')') {
				parenDepth++;
			} else if (c == '(') {
				if (parenDepth == 0) {
					int closeParen = GlslTranslator.matchParen(source, i);
					if (closeParen > offset) {
						int k = closeParen + 1;
						while (k < n && Character.isWhitespace(source.charAt(k))) {
							k++;
						}
						if (k < n && source.charAt(k) == '{' && matchingClose[k] > k) {
							return matchingClose[k];
						}
						// Braceless `for (int i = 0; ...)` -> ends at the next `;` after `)`
						int semi = source.indexOf(';', closeParen + 1);
						if (semi > closeParen) {
							return semi;
						}
					}
					break;
				}
				parenDepth--;
			} else if (c == ';' || c == '{' || c == '}') {
				break;
			}
		}
		// Otherwise find the innermost enclosing `{ ... }` block
		int depth = 0;
		for (int i = offset - 1; i >= 0; i--) {
			char c = source.charAt(i);
			if (c == '}') {
				depth++;
			} else if (c == '{') {
				if (depth == 0) {
					return matchingClose[i] > i ? matchingClose[i] : Integer.MAX_VALUE;
				}
				depth--;
			}
		}
		return Integer.MAX_VALUE; // file / global scope
	}

	/** Scoped integer declarations visible at {@code offset} (with component count, uint flag, array flag). */
	private static Map<String, DeclInfo> declsAt(final List<int[]> pos, final List<String> names, final int offset) {
		Map<String, DeclInfo> latest = new HashMap<>();
		for (int i = 0; i < pos.size(); i++) {
			int[] p = pos.get(i);
			if (p[0] < offset && offset <= p[2]) {
				if (p[1] == 0) {
					latest.remove(names.get(i));
				} else {
					latest.put(names.get(i), new DeclInfo(p[1], p[3] != 0, p[4] != 0));
				}
			}
		}
		return latest;
	}

	/** Integer-typed names as of a source offset: nearest in-scope preceding declaration wins. */
	static Map<String, Integer> intNamesAt(final List<int[]> pos, final List<String> names, final int offset) {
		Map<String, Integer> out = new HashMap<>();
		for (Map.Entry<String, DeclInfo> e : declsAt(pos, names, offset).entrySet()) {
			out.put(e.getKey(), e.getValue().comps);
		}
		return out;
	}

	/** Names whose every declaration in the source has an integer type, with their component count. */
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

	private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
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

	static String floatifyLine(final String line, final Map<String, Integer> intNames, final Map<String, List<String>> functions) {
		Map<String, DeclInfo> wrapped = new HashMap<>();
		for (Map.Entry<String, Integer> e : intNames.entrySet()) {
			wrapped.put(e.getKey(), new DeclInfo(e.getValue(), false, false));
		}
		return floatifyLine(line, wrapped, functions, new HashSet<>());
	}

	/** One open bracket on the line: a call (with the callee and argument index), a group or a subscript. */
	private static final class Frame {
		final String callee; // "" for a grouping parenthesis, "[" for a subscript
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
		Integer mask = INT_PARAM_MASKS.get(f.callee);
		if (mask != null) {
			return f.arg < 31 && ((mask >>> f.arg) & 1) != 0;
		}
		List<String> params = functions.get(f.callee);
		if (params != null) {
			return f.arg < params.size() && isIntType(params.get(f.arg));
		}
		return false;
	}

	/**
	 * Converts integer literals to float literals and wraps integer-typed identifiers, outside
	 * integer contexts (subscripts, per-function integer arguments, bit operations, int declaration
	 * initializers). Preserves vector component counts for 3D/2DArray {@code textureSize} and for
	 * subscripted integer vector arrays ({@code ivec2 arr[4]; arr[k]} -> {@code vec2}).
	 */
	private static String floatifyLine(final String line, final Map<String, DeclInfo> decls,
		final Map<String, List<String>> functions, final Set<String> samplers3D) {
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
				if (close > i && integerGroup(tokens, i + 1, close, decls)
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
						int comps;
						if (t.equals("textureSize")) {
							String firstArg = nextSignificant(tokens, open + 1);
							comps = samplers3D.contains(firstArg) ? 3 : 2;
						} else if (t.equals("int") || t.equals("uint")) {
							comps = 1;
						} else {
							comps = t.charAt(t.length() - 1) - '0';
						}
						if (end + 2 < tokens.size() && tokens.get(end + 1).equals(".")
							&& GlslPreprocessor.isIdentStart(tokens.get(end + 2).charAt(0))) {
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
				DeclInfo di = decls.get(t);
				if (di != null && !intContext && !topLevelIntDecl && !isLvalue(tokens, i) && !nextSignificant(tokens, i + 1).equals("(")
					&& !prevSignificant.equals(".")
					&& !bitwiseNeighbour(prevSignificant, tokens, i)) {
					int end = i + 1;
					StringBuilder expr = new StringBuilder(t);
					int resultComps = di.comps;
					boolean arrayRemaining = di.isArray;
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
							// First subscript on an array indexes the array (preserves element vector width);
							// subscript on a vector indexes a scalar component.
							if (arrayRemaining) {
								arrayRemaining = false;
							} else {
								resultComps = 1;
							}
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

	/**
	 * Tokens [from, to) hold only scalar int names, int literals and integer operators (including
	 * shift {@code <<}/{@code >>}, but NEVER relational {@code <}/{@code >}), with at least one name.
	 */
	private static boolean integerGroup(final List<String> tokens, final int from, final int to, final Map<String, DeclInfo> decls) {
		boolean name = false;
		for (int k = from; k < to; k++) {
			String t = tokens.get(k);
			if (t.trim().isEmpty() || "+-*/%&|^~()".contains(t)) {
				continue;
			}
			if ((t.equals("<") || t.equals(">")) && k + 1 < to && tokens.get(k + 1).equals(t)) {
				k++; // allow << and >> shifts, reject standalone < / > comparisons
				continue;
			}
			char c = t.charAt(0);
			if (GlslPreprocessor.isIdentStart(c)) {
				DeclInfo di = decls.get(t);
				if (di == null || di.comps != 1 || di.isArray) {
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

	/**
	 * In the first call to {@code name} in {@code text}: int literal arguments for uint parameters
	 * get a {@code u}, for float parameters a {@code .0}. Null if nothing changed.
	 */
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
		if ((next.equals("+") || next.equals("-") || next.equals("*") || next.equals("/")
			|| next.equals("%") || next.equals("&") || next.equals("|") || next.equals("^")) && next2.equals("=")) {
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

	/** {@code a[x]} with a float x: wrap subscripts in int(). */
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
