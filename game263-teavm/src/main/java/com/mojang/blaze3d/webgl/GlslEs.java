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

	private static final Pattern TERRAIN_RGSS_BRANCH = Pattern.compile(
		"if \\([^)]*\\.UseRgss == 1\\)\\s*\\{\\s*vec2 param = (\\w+);\\s*vec2 param_1 = [^;]+;\\s*(\\w+) = sampleRGSS\\((\\w+), param, param_1\\);\\s*\\}\\s*else\\s*\\{[^}]*\\}"
	);

	public static String translate(String source, boolean fragmentShader) {
		if (fragmentShader && "true".equals(System.getProperty("web263.fastTerrainShader")) && source.contains("sampleRGSS(")) {
			source = TERRAIN_RGSS_BRANCH.matcher(source).replaceAll("$2 = texture($3, $1);");
		}
		source = stubOutTextureBuffers(source);
		source = addExplicitIntToFloatCasts(source);
		Matcher versionMatch = VERSION_LINE.matcher(source);
		String body;
		int versionLineEnd;
		if (versionMatch.find()) {
			body = source.substring(0, versionMatch.start()) + "#version 300 es" + source.substring(versionMatch.end());
			versionLineEnd = versionMatch.start() + "#version 300 es".length();
		} else {
			body = "#version 300 es\n" + source;
			versionLineEnd = "#version 300 es".length();
		}

		body = EXTENSION_LINE.matcher(body).replaceAll("");

		StringBuilder precision = new StringBuilder();
		precision.append("precision highp float;\n");
		precision.append("precision highp int;\n");
		for (String type : SAMPLER_TYPES) {
			if (Pattern.compile("\\b" + type + "\\b").matcher(body).find()) {
				precision.append("precision highp ").append(type).append(";\n");
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
		source = LIGHTMAP_UV_DIV.matcher(source).replaceAll("vec2(uv) / 256.0");
		source = VERTEX_ID_DIV.matcher(source).replaceAll("float(gl_VertexID $1 $2) / $3");
		source = CHUNK_OFFSET.matcher(source).replaceAll("vec3(ChunkPosition - CameraBlockPos)");
		source = TEXTURE_SIZE_DIV.matcher(source).replaceAll("/ vec2(TextureSize)");
		source = LIGHTMAP_LEVEL.matcher(source).replaceAll("floor($1 * 16.0) / 15.0");
		source = FOG_INT_LITERAL.matcher(source).replaceAll("linear_fog_value($1, $2.0,");
		return TEXTURE_LOD_CALL.matcher(source).replaceAll("textureLod($1,$2, float($3))");
	}

	private static final Pattern SAMPLER_BUFFER_DECL = Pattern.compile(
		"(?m)^[ \\t]*uniform[ \\t]+([iu]?)samplerBuffer[ \\t]+(\\w+)[ \\t]*;[ \\t]*$"
	);

	static String stubOutTextureBuffers(String source) {
		Matcher decl = SAMPLER_BUFFER_DECL.matcher(source);
		String stripped = decl.replaceAll("");
		if (stripped.equals(source)) {
			return source;
		}
		decl.reset();
		while (decl.find()) {
			String zero = decl.group(1).isEmpty() ? "vec4(0.0)" : decl.group(1) + "vec4(0)";
			stripped = replaceTexelFetch(stripped, decl.group(2), zero);
		}
		return stripped;
	}

	private static String replaceTexelFetch(String source, String sampler, String zero) {
		String needle = "texelFetch(" + sampler;
		StringBuilder out = new StringBuilder();
		int from = 0;
		int at;
		while ((at = source.indexOf(needle, from)) >= 0) {
			int argStart = at + needle.length();
			char next = argStart < source.length() ? source.charAt(argStart) : ' ';
			if (Character.isLetterOrDigit(next) || next == '_') {
				out.append(source, from, argStart);
				from = argStart;
				continue;
			}
			int depth = 1;
			int i = argStart;
			while (i < source.length() && depth > 0) {
				char c = source.charAt(i++);
				if (c == '(') {
					depth++;
				} else if (c == ')') {
					depth--;
				}
			}
			out.append(source, from, at).append(zero);
			from = i;
		}
		return out.append(source, from, source.length()).toString();
	}

	public static String resolveImports(String source, Function<String, String> loader) {
		return resolveImports(source, loader, "minecraft:", new HashSet<>());
	}

	private static String resolveImports(String source, Function<String, String> loader, String currentDir, Set<String> imported) {
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
