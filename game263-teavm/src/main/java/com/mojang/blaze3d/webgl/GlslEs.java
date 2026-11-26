// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.webgl;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GlslEs {
	private GlslEs() {
	}

	private static final Pattern VERSION_LINE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[ \\t]+(\\d+)([ \\t]+\\w+)?[ \\t]*$");
	private static final Pattern EXTENSION_LINE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension\\b.*$");
	private static final Pattern MOJ_IMPORT = Pattern.compile("#\\s*moj_import\\s*(?:\"([^\"]*)\"|<([^>]*)>)");

	private static final String[] SAMPLER_TYPES = {
		"sampler2DArray", "sampler2DShadow", "usampler2D", "isampler2D", "samplerCube", "sampler3D", "sampler2D"
	};
	private static final Pattern[] SAMPLER_TYPE_PATTERNS = new Pattern[SAMPLER_TYPES.length];
	static {
		for (int i = 0; i < SAMPLER_TYPES.length; i++) {
			SAMPLER_TYPE_PATTERNS[i] = Pattern.compile("\\b" + SAMPLER_TYPES[i] + "\\b");
		}
	}

	private static final Pattern TERRAIN_RGSS_BRANCH = Pattern.compile(
		"if \\([^)]*\\.UseRgss == 1\\)\\s*\\{\\s*vec2 param = (\\w+);\\s*vec2 param_1 = [^;]+;\\s*(\\w+) = sampleRGSS\\((\\w+), param, param_1\\);\\s*\\}\\s*else\\s*\\{[^}]*\\}"
	);

	public static String translate(String source, boolean fragmentShader) {
		if (fragmentShader && "true".equals(System.getProperty("web263.fastTerrainShader")) && source.contains("sampleRGSS(")) {
			source = TERRAIN_RGSS_BRANCH.matcher(source).replaceAll("$2 = texture($3, $1);");
		}
		source = lowerTextureBuffers(source);
		source = addExplicitIntToFloatCasts(source);
		String body;
		int versionLineEnd;
		if (source.contains("version")) {
			Matcher versionMatch = VERSION_LINE.matcher(source);
			if (versionMatch.find()) {
				body = source.substring(0, versionMatch.start()) + "#version 300 es" + source.substring(versionMatch.end());
				versionLineEnd = versionMatch.start() + "#version 300 es".length();
			} else {
				body = "#version 300 es\n" + source;
				versionLineEnd = "#version 300 es".length();
			}
		} else {
			body = "#version 300 es\n" + source;
			versionLineEnd = "#version 300 es".length();
		}

		if (body.contains("extension")) {
			body = EXTENSION_LINE.matcher(body).replaceAll("");
		}

		StringBuilder precision = new StringBuilder();
		precision.append("precision highp float;\n");
		precision.append("precision highp int;\n");
		if (body.contains("sampler")) {
			for (int i = 0; i < SAMPLER_TYPES.length; i++) {
				String type = SAMPLER_TYPES[i];
				if (body.contains(type) && SAMPLER_TYPE_PATTERNS[i].matcher(body).find()) {
					precision.append("precision highp ").append(type).append(";\n");
				}
			}
		}

		int insertAt = body.indexOf('\n', versionLineEnd);
		insertAt = (insertAt < 0) ? body.length() : insertAt + 1;
		return body.substring(0, insertAt) + precision + body.substring(insertAt);
	}

	private static final Pattern LIGHTMAP_UV_DIV = Pattern.compile("\\buv\\s*/\\s*256\\.0");
	private static final Pattern VERTEX_ID_DIV = Pattern.compile(
		"\\(\\s*gl_VertexID\\s*(>>|<<|[-+*])\\s*(\\w+)\\s*\\)\\s*/\\s*(\\d+\\.\\d*)"
	);
	private static final Pattern TEXTURE_LOD_CALL = Pattern.compile(
		"\\btextureLod\\s*\\(([^(),]+),([^(),]+),\\s*([^(),\\s]+)\\s*\\)"
	);
	private static final Pattern CHUNK_OFFSET = Pattern.compile(
		"\\(\\s*ChunkPosition\\s*-\\s*CameraBlockPos\\s*\\)"
	);
	private static final Pattern TEXTURE_SIZE_DIV = Pattern.compile("/\\s*TextureSize\\b");
	private static final Pattern LIGHTMAP_LEVEL = Pattern.compile(
		"floor\\((texCoord\\.[xy])\\s*\\*\\s*16\\)\\s*/\\s*15\\b"
	);
	private static final Pattern FOG_INT_LITERAL = Pattern.compile(
		"linear_fog_value\\(([^(),]+),\\s*(\\d+),"
	);

	static String addExplicitIntToFloatCasts(String source) {
		if (source.contains("256.0") && source.contains("uv")) {
			source = LIGHTMAP_UV_DIV.matcher(source).replaceAll("vec2(uv) / 256.0");
		}
		if (source.contains("gl_VertexID")) {
			source = VERTEX_ID_DIV.matcher(source).replaceAll("float(gl_VertexID $1 $2) / $3");
		}
		if (source.contains("ChunkPosition")) {
			source = CHUNK_OFFSET.matcher(source).replaceAll("vec3(ChunkPosition - CameraBlockPos)");
		}
		if (source.contains("TextureSize")) {
			source = TEXTURE_SIZE_DIV.matcher(source).replaceAll("/ vec2(TextureSize)");
		}
		if (source.contains("texCoord.")) {
			source = LIGHTMAP_LEVEL.matcher(source).replaceAll("floor($1 * 16.0) / 15.0");
		}
		if (source.contains("linear_fog_value(")) {
			source = FOG_INT_LITERAL.matcher(source).replaceAll("linear_fog_value($1, $2.0,");
		}
		if (source.contains("textureLod")) {
			source = TEXTURE_LOD_CALL.matcher(source).replaceAll("textureLod($1,$2, float($3))");
		}
		return source;
	}

	private static final Pattern SAMPLER_BUFFER_DECL = Pattern.compile(
		"(?m)^([ \\t]*uniform[ \\t]+)(?:(?:highp|mediump|lowp)[ \\t]+)?([iu]?)samplerBuffer([ \\t]+)(\\w+)([ \\t]*;[ \\t]*)$"
	);

	static String lowerTextureBuffers(String source) {
		if (!source.contains("samplerBuffer")) {
			return source;
		}
		Matcher decl = SAMPLER_BUFFER_DECL.matcher(source);
		java.util.ArrayList<String> samplers = null;
		StringBuilder rewritten = new StringBuilder(source.length() + 32);
		int from = 0;
		while (decl.find()) {
			if (samplers == null) {
				samplers = new java.util.ArrayList<>(2);
			}
			samplers.add(decl.group(4));
			rewritten.append(source, from, decl.start())
				.append(decl.group(1))
				.append("highp ")
				.append(decl.group(2))
				.append("sampler2D")
				.append(decl.group(3))
				.append(decl.group(4))
				.append(decl.group(5));
			from = decl.end();
		}
		if (samplers == null) {
			return source;
		}
		rewritten.append(source, from, source.length());
		String out = rewritten.toString();
		if (out.contains("texelFetch")) {
			for (int i = 0; i < samplers.size(); i++) {
				out = rewriteBufferTexelFetch(out, samplers.get(i));
			}
		}
		return out;
	}

	private static boolean isIdentChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private static String rewriteBufferTexelFetch(String source, String sampler) {
		final String fn = "texelFetch";
		final int fnLen = fn.length();
		final int sLen = sampler.length();
		final int srcLen = source.length();
		StringBuilder out = new StringBuilder(srcLen + 64);
		int from = 0;
		int at;
		while ((at = source.indexOf(fn, from)) >= 0) {
			if (at > 0 && isIdentChar(source.charAt(at - 1))) {
				out.append(source, from, at + fnLen);
				from = at + fnLen;
				continue;
			}
			int p = at + fnLen;
			while (p < srcLen && Character.isWhitespace(source.charAt(p))) {
				p++;
			}
			if (p >= srcLen || source.charAt(p) != '(') {
				out.append(source, from, at + fnLen);
				from = at + fnLen;
				continue;
			}
			int openParen = p;
			p++;
			while (p < srcLen && Character.isWhitespace(source.charAt(p))) {
				p++;
			}
			if (p + sLen > srcLen || !source.regionMatches(p, sampler, 0, sLen)
				|| (p + sLen < srcLen && isIdentChar(source.charAt(p + sLen)))) {
				out.append(source, from, openParen + 1);
				from = openParen + 1;
				continue;
			}
			p += sLen;
			while (p < srcLen && Character.isWhitespace(source.charAt(p))) {
				p++;
			}
			if (p >= srcLen || source.charAt(p) != ',') {
				out.append(source, from, openParen + 1);
				from = openParen + 1;
				continue;
			}
			int commaPos = p;
			int depth = 1;
			int i = commaPos + 1;
			boolean extraComma = false;
			while (i < srcLen && depth > 0) {
				char c = source.charAt(i);
				if (c == '(') {
					depth++;
				} else if (c == ')') {
					depth--;
					if (depth == 0) {
						break;
					}
				} else if (c == ',' && depth == 1) {
					extraComma = true;
				}
				i++;
			}
			if (depth != 0 || extraComma) {
				out.append(source, from, openParen + 1);
				from = openParen + 1;
				continue;
			}
			String idxExpr = source.substring(commaPos + 1, i).trim();
			if (idxExpr.contains(fn)) {
				idxExpr = rewriteBufferTexelFetch(idxExpr, sampler);
			}
			out.append(source, from, at)
				.append("texelFetch(").append(sampler)
				.append(", ivec2((").append(idxExpr).append(") % textureSize(").append(sampler).append(", 0).x, (")
				.append(idxExpr).append(") / textureSize(").append(sampler).append(", 0).x), 0)");
			from = i + 1;
		}
		return out.append(source, from, srcLen).toString();
	}

	public static String resolveImports(String source, Function<String, String> loader) {
		if (!source.contains("moj_import")) {
			return source;
		}
		return resolveImports(source, loader, "minecraft:", new HashSet<>());
	}

	private static String resolveImports(String source, Function<String, String> loader, String currentDir, Set<String> imported) {
		if (!source.contains("moj_import")) {
			return source;
		}
		Matcher m = MOJ_IMPORT.matcher(source);
		StringBuilder out = new StringBuilder();
		int last = 0;
		while (m.find()) {
			out.append(source, last, m.start());
			last = m.end();

			String quoted = m.group(1);
			String resolvedPath;
			if (quoted != null) {
				resolvedPath = normalize(currentDir + quoted);
			} else {
				String path = m.group(2);
				int colon = path.indexOf(':');
				String namespace = colon >= 0 ? path.substring(0, colon) : "minecraft";
				String rest = colon >= 0 ? path.substring(colon + 1) : path;
				resolvedPath = namespace + ":include/" + rest;
			}

			if (!imported.add(resolvedPath)) {
				continue;
			}
			String content = loader.apply(resolvedPath);
			if (content == null) {
				throw new IllegalArgumentException("Missing #moj_import: " + resolvedPath);
			}
			String nested = commentOutVersionLines(resolveImports(content, loader, dirOf(resolvedPath), imported));
			out.append(nested);
			if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
				out.append('\n');
			}
		}
		out.append(source, last, source.length());
		return out.toString();
	}

	private static String commentOutVersionLines(String s) {
		if (!s.contains("version")) {
			return s;
		}
		Matcher vm = VERSION_LINE.matcher(s);
		StringBuilder sb = new StringBuilder();
		int last = 0;
		while (vm.find()) {
			sb.append(s, last, vm.start());
			sb.append("/*").append(vm.group()).append("*/");
			last = vm.end();
		}
		sb.append(s, last, s.length());
		return sb.toString();
	}

	private static String dirOf(String resolvedPath) {
		int slash = resolvedPath.lastIndexOf('/');
		return slash < 0 ? resolvedPath.substring(0, resolvedPath.indexOf(':') + 1) : resolvedPath.substring(0, slash + 1);
	}

	private static String normalize(String path) {
		int colon = path.indexOf(':');
		String namespace = colon >= 0 ? path.substring(0, colon + 1) : "";
		String rest = colon >= 0 ? path.substring(colon + 1) : path;
		String[] parts = rest.split("/");
		java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
		for (String part : parts) {
			if (part.isEmpty() || part.equals(".")) {
				continue;
			} else if (part.equals("..")) {
				if (!stack.isEmpty()) {
					stack.removeLast();
				}
			} else {
				stack.addLast(part);
			}
		}
		return namespace + String.join("/", stack);
	}
}
