// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GlslTranslator {
	private GlslTranslator() {
	}

	public static final class Output {
		public String source;
		public int fragOutputs;
		public final Map<Integer, String> outputsByLocation = new LinkedHashMap<>();
		public final List<String> notes = new ArrayList<>();
		public String vertexInit = "";
	}

	private static final String[] RENAMED_IDENTIFIERS = {
		"input", "output", "filter", "sample", "active", "common", "partition", "resource", "union", "enum",
		"typedef", "template", "this", "packed", "goto", "inline", "noinline", "volatile", "public", "static",
		"extern", "external", "interface", "long", "short", "double", "half", "fixed", "unsigned", "sizeof",
		"cast", "namespace", "using", "superp", "dvec2", "dvec3", "dvec4", "buffer", "shared", "coherent",
		"readonly", "writeonly", "restrict", "subroutine", "patch"
	};

	private static final String[] NEW_BUILTINS_FOR_OLD_VERSIONS = {
		"round", "roundEven", "trunc", "inverse", "transpose", "determinant", "outerProduct", "isnan", "isinf",
		"sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "modf", "texelFetch", "textureSize",
		"floatBitsToInt", "floatBitsToUint", "intBitsToFloat", "uintBitsToFloat", "packHalf2x16", "unpackHalf2x16",
		"packUnorm2x16", "unpackUnorm2x16", "packSnorm2x16", "unpackSnorm2x16", "textureLod", "textureGrad",
		"textureOffset", "textureProj", "textureLodOffset", "texelFetchOffset", "textureGradOffset"
	};

	private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
	private static final Pattern TEX_COORD = Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*(\\d+)\\s*\\]");
	private static final Pattern TEXTURE_MATRIX = Pattern.compile("\\bgl_TextureMatrix\\s*\\[\\s*(\\d+)\\s*\\]");
	private static final Pattern OUT_DECL = Pattern.compile(
		"(^|[;{}\\n])(\\s*)((?:layout\\s*\\([^)]*\\)\\s*)?)((?:flat\\s+|smooth\\s+|noperspective\\s+|centroid\\s+|invariant\\s+|highp\\s+|mediump\\s+|lowp\\s+)*)out\\s+(\\w+)\\s+(\\w+)\\s*;");

	public static Output translate(final String code, final boolean vertex, final int version) {
		return translate(code, vertex, version, false);
	}

	private static final String GBUFFERS_VERTEX_DECLS = "layout(std140) uniform iris_Globals { ivec3 iris_CameraBlockPos; float iris_GlintAlpha;"
		+ " vec3 iris_CameraOffset; float iris_GameTime; vec2 iris_ScreenSize; int iris_MenuBlurRadius; int iris_UseRgss; };\n"
		+ "layout(std140) uniform iris_ChunkSection { ivec3 iris_ChunkPosition; float iris_ChunkVisibility; };\n"
		+ "in vec3 iris_Position;\n"
		+ "vec3 iris_chunkOffset() { return vec3(iris_ChunkPosition - iris_CameraBlockPos) + iris_CameraOffset; }\n";

	private static final String ENTITY_VERTEX_DECLS = "in vec3 iris_Position;\nin ivec2 iris_UV2i;\nin vec3 iris_Normal;\n"
		+ "vec4 iris_entityTangent() { vec3 n = normalize(iris_Normal);\n"
		+ "  vec3 t = abs(n.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : normalize(cross(vec3(0.0, 1.0, 0.0), n));\n"
		+ "  return vec4(t, 1.0); }\n";

	private static final String BLOCK_TAG_DECLS = "in ivec2 iris_UV2i;\n"
		+ "uniform highp sampler2D iris_BlockIdTable;\n"
		+ "vec3 iris_faceNormal() { int f = (iris_UV2i.x >> 8) & 7;\n"
		+ "  return f == 1 ? vec3(0.0, -1.0, 0.0) : f == 3 ? vec3(0.0, 0.0, -1.0) : f == 4 ? vec3(0.0, 0.0, 1.0)\n"
		+ "    : f == 5 ? vec3(-1.0, 0.0, 0.0) : f == 6 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0); }\n"
		+ "float iris_blockId() { int i = ((iris_UV2i.x >> 11) & 31) | (((iris_UV2i.y >> 8) & 255) << 5);\n"
		+ "  if (i == 0) { return -1.0; }\n"
		+ "  ivec4 b = ivec4(texelFetch(iris_BlockIdTable, ivec2(i & 255, i >> 8), 0) * 255.0 + 0.5);\n"
		+ "  return float(b.r | (b.g << 8) | (b.b << 16)); }\n"
		+ "vec4 iris_tangent() { vec3 n = iris_faceNormal();\n"
		+ "  return vec4(abs(n.y) > 0.5 ? vec3(1.0, 0.0, 0.0) : abs(n.x) > 0.5 ? vec3(0.0, 0.0, -n.x) : vec3(n.z, 0.0, 0.0), 1.0); }\n";

	public static Output translate(final String code, final boolean vertex, final int version, final boolean gbuffers) {
		return translate(code, vertex, version, gbuffers, true);
	}

	public static Output translate(final String code, final boolean vertex, final int version, final boolean gbuffers, final boolean flipZ) {
		return translate(code, vertex, version, gbuffers, flipZ, false);
	}

	public static Output translate(final String code, final boolean vertex, final int version, final boolean gbuffers, final boolean flipZ,
		final boolean entity) {
		Output out = new Output();
		String s = code;

		s = stripImages(s, out);
		s = renameIdentifiers(s, version, out);
		s = qualifiers(s, vertex, version);
		s = textureFunctions(s, version);
		StringBuilder decls = new StringBuilder();
		if (gbuffers) {
			if (vertex) {
				if (entity) {
					decls.append(ENTITY_VERTEX_DECLS);
					s = replaceIdent(s, "gl_Vertex", "vec4(iris_Position, 1.0)");
					for (String lm : new String[] {"gl_MultiTexCoord1", "gl_MultiTexCoord2"}) {
						s = replaceIfPresent(s, lm, "vec4(vec2(iris_UV2i), 0.0, 1.0)");
					}
				} else {
					decls.append(GBUFFERS_VERTEX_DECLS);
					decls.append(BLOCK_TAG_DECLS);
					s = replaceIdent(s, "gl_Vertex", "vec4(iris_Position + iris_chunkOffset(), 1.0)");
					for (String lm : new String[] {"gl_MultiTexCoord1", "gl_MultiTexCoord2"}) {
						s = replaceIfPresent(s, lm, "vec4(vec2(iris_UV2i & 255), 0.0, 1.0)");
					}
					s = replaceIfPresent(s, "gl_Normal", "iris_faceNormal()");
				}
				StringBuilder init = new StringBuilder();
				Matcher attr = Pattern.compile("(?m)^(\\s*)in\\s+(float|vec2|vec3|vec4)\\s+(mc_Entity|at_tangent)\\s*;").matcher(s);
				StringBuilder rebuilt = new StringBuilder();
				int from = 0;
				while (attr.find()) {
					String value = attr.group(3).equals("mc_Entity") ? (entity ? "vec4(-1.0, 0.0, 0.0, 0.0)" : "vec4(iris_blockId(), 0.0, 0.0, 0.0)")
						: entity ? "iris_entityTangent()" : "iris_tangent()";
					init.append(attr.group(3)).append(" = ").append(attr.group(2)).append('(').append(value).append("); ");
					rebuilt.append(s, from, attr.start()).append(attr.group(1)).append(attr.group(2)).append(' ').append(attr.group(3)).append(';');
					from = attr.end();
				}
				rebuilt.append(s.substring(from));
				s = rebuilt.toString();
				out.vertexInit = init.toString();
				s = Pattern.compile("(?m)^(\\s*)in\\s+(int|uint|ivec[234]|uvec[234])\\s+(\\w+)\\s*;")
					.matcher(s).replaceAll("$1$2 $3 = $2(0);");
				Matcher main = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)").matcher(s);
				if ((flipZ || !out.vertexInit.isEmpty()) && main.find()) {
					s = s.substring(0, main.start()) + "void iris_pack_main()" + s.substring(main.end())
						+ "\nvoid main() { " + out.vertexInit + "iris_pack_main();" + (flipZ ? " gl_Position.z = -gl_Position.z;" : "") + " }\n";
				}
			}
			if (!vertex && flipZ) {
				if (Pattern.compile("\\bgl_FragCoord\\b").matcher(s).find()) {
					s = replaceIdent(s, "gl_FragCoord", "iris_fragCoord()");
					decls.append("vec4 iris_fragCoord() { return vec4(gl_FragCoord.xy, 1.0 - gl_FragCoord.z, gl_FragCoord.w); }\n");
				}
				Matcher main = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)").matcher(s);
				if (Pattern.compile("\\bgl_FragDepth\\b").matcher(s).find() && main.find()) {
					s = replaceIdent(s, "gl_FragDepth", "iris_FragDepth");
					decls.append("float iris_FragDepth;\n");
					s = s.substring(0, main.start()) + "void iris_pack_main()" + s.substring(main.end())
						+ "\nvoid main() { iris_FragDepth = iris_fragCoordZ(); iris_pack_main(); gl_FragDepth = 1.0 - iris_FragDepth; }\n";
					decls.append("float iris_fragCoordZ() { return 1.0 - gl_FragCoord.z; }\n");
				}
			}
			s = replaceIfPresent(s, "gl_ModelViewMatrixInverse", "iris_gbModelViewInverse", decls, "uniform mat4 iris_gbModelViewInverse;\n");
			s = replaceIfPresent(s, "gl_ProjectionMatrixInverse", "iris_gbProjectionInverse", decls, "uniform mat4 iris_gbProjectionInverse;\n");
			s = replaceIfPresent(s, "gl_ModelViewProjectionMatrix", "(iris_gbProjection * iris_gbModelView)", decls,
				"uniform mat4 iris_gbProjection;\nuniform mat4 iris_gbModelView;\n");
			s = replaceIfPresent(s, "gl_ModelViewMatrix", "iris_gbModelView", decls, "uniform mat4 iris_gbModelView;\n");
			s = replaceIfPresent(s, "gl_ProjectionMatrix", "iris_gbProjection", decls, "uniform mat4 iris_gbProjection;\n");
			s = replaceIfPresent(s, "gl_NormalMatrix", "iris_gbNormalMatrix", decls, "uniform mat3 iris_gbNormalMatrix;\n");
			if (vertex && s.contains("ftransform")) {
				s = Pattern.compile("\\bftransform\\s*\\(\\s*\\)").matcher(s).replaceAll(entity
					? "(iris_gbProjection * iris_gbModelView * vec4(iris_Position, 1.0))"
					: "(iris_gbProjection * iris_gbModelView * vec4(iris_Position + iris_chunkOffset(), 1.0))");
				if (decls.indexOf("uniform mat4 iris_gbProjection;") < 0) {
					decls.append("uniform mat4 iris_gbProjection;\n");
				}
				if (decls.indexOf("uniform mat4 iris_gbModelView;") < 0) {
					decls.append("uniform mat4 iris_gbModelView;\n");
				}
			}
		}
		s = fixedFunction(s, vertex, decls);
		if (!vertex) {
			s = fragmentOutputs(s, out, decls);
		}
		s = stripUniformInitializers(s);
		s = moveGlobalInitializers(s, out);
		String polyfills = polyfills(s);

		StringBuilder header = new StringBuilder();
		header.append("#version 300 es\n");
		header.append("precision highp float;\nprecision highp int;\n");
		for (String sampler : new String[] {"sampler2D", "sampler3D", "samplerCube", "sampler2DShadow", "sampler2DArray",
			"isampler2D", "usampler2D", "isampler3D", "usampler3D", "sampler2DArrayShadow", "samplerCubeShadow"}) {
			header.append("precision highp ").append(sampler).append(";\n");
		}
		out.source = header + decls.toString() + polyfills + s;
		return out;
	}

	private static final Pattern IMAGE_DECL = Pattern.compile("(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?"
		+ "(?:(?:writeonly|readonly|coherent|restrict|volatile|highp|mediump|lowp|uniform)\\s+)+([iu]?)image(?:1D|2D|3D|Cube|2DArray|Buffer|2DRect)\\w*\\s+(\\w+)\\s*;");
	private static final Pattern IMAGE_CALL = Pattern.compile("\\b(imageStore|imageLoad|imageAtomic\\w+|imageSize)\\s*\\(\\s*(\\w+)");

	static String stripImages(String s, final Output out) {
		if (!s.contains("image")) {
			return s;
		}
		Map<String, String> images = new LinkedHashMap<>();
		Matcher d = IMAGE_DECL.matcher(s);
		while (d.find()) {
			images.put(d.group(2), d.group(1));
		}
		if (images.isEmpty()) {
			return s;
		}
		s = IMAGE_DECL.matcher(s).replaceAll("");
		StringBuilder b = new StringBuilder();
		int from = 0;
		Matcher c = IMAGE_CALL.matcher(s);
		while (c.find(from)) {
			String prefix = images.get(c.group(2));
			int open = s.indexOf('(', c.start());
			int level = 0;
			int close = -1;
			for (int i = open; i < s.length(); i++) {
				char ch = s.charAt(i);
				if (ch == '(') {
					level++;
				} else if (ch == ')' && --level == 0) {
					close = i;
					break;
				}
			}
			if (prefix == null || close < 0) {
				b.append(s, from, c.end());
				from = c.end();
				continue;
			}
			String fn = c.group(1);
			String repl = fn.equals("imageStore") ? "0"
				: fn.equals("imageSize") ? "ivec3(1)"
				: fn.equals("imageLoad") ? (prefix.equals("u") ? "uvec4(0u)" : prefix.equals("i") ? "ivec4(0)" : "vec4(0.0)")
				: prefix.equals("u") ? "0u" : prefix.equals("i") ? "0" : "0.0";
			b.append(s, from, c.start()).append(repl);
			from = close + 1;
		}
		out.notes.add("image load/store removed (" + images.keySet() + ")");
		return b.append(s.substring(from)).toString();
	}

	public static int bodyStartLine(final String source, final String body) {
		int at = source.lastIndexOf(body);
		int line = 1;
		for (int i = 0; i < at; i++) {
			if (source.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
	}

	private static String renameIdentifiers(String s, final int version, final Output out) {
		for (String name : RENAMED_IDENTIFIERS) {
			if (s.contains(name)) {
				s = replaceIdent(s, name, name + "_");
			}
		}
		if (version < 130) {
			for (String q : new String[] {"smooth", "flat"}) {
				if (Pattern.compile("\\b(?:float|int|vec[234]|bool|ivec[234])\\s+" + q + "\\b").matcher(s).find()) {
					s = replaceIdent(s, q, q + "_");
				}
			}
			if (Pattern.compile("\\bsampler\\w*\\s+texture\\s*[;,=]").matcher(s).find()) {
				s = Pattern.compile("\\btexture\\b(?!\\s*\\()").matcher(s).replaceAll("gtexture");
				out.notes.add("renamed sampler 'texture' to 'gtexture'");
			}
			for (String fn : NEW_BUILTINS_FOR_OLD_VERSIONS) {
				if (definesFunction(s, fn)) {
					s = Pattern.compile("\\b" + fn + "(\\s*\\()").matcher(s).replaceAll("iris_user_" + fn + "$1");
					out.notes.add("renamed user function " + fn);
				}
			}
		}
		return s;
	}

	static boolean definesFunction(final String s, final String name) {
		Matcher m = Pattern.compile("\\b(?:void|float|int|uint|bool|[iu]?vec[234]|mat[234](?:x[234])?)\\s+" + name + "\\s*\\(").matcher(s);
		return m.find();
	}

	static String replaceIdent(final String s, final String from, final String to) {
		return Pattern.compile("\\b" + from + "\\b").matcher(s).replaceAll(to);
	}

	private static String qualifiers(String s, final boolean vertex, final int version) {
		s = replaceIdent(s, "attribute", vertex ? "in" : "attribute");
		s = replaceIdent(s, "varying", vertex ? "out" : "in");
		s = replaceIdent(s, "noperspective", "");
		return s;
	}

	private static String textureFunctions(String s, final int version) {
		String[][] simple = {
			{"texture2DLodEXT", "textureLod"}, {"texture2DLod", "textureLod"}, {"texture2DGradARB", "textureGrad"},
			{"texture2DGradEXT", "textureGrad"}, {"texture2DGrad", "textureGrad"}, {"texture2DProjLod", "textureProjLod"},
			{"texture2DProj", "textureProj"}, {"texture3DLod", "textureLod"}, {"texture3D", "texture"},
			{"textureCubeLod", "textureLod"}, {"textureCube", "texture"}, {"texture2D", "texture"},
			{"texelFetch2D", "texelFetch"}, {"texelFetch3D", "texelFetch"}, {"textureSize2D", "textureSize"},
			{"texture1D", "texture"}
		};
		for (String[] r : simple) {
			if (s.contains(r[0])) {
				s = Pattern.compile("\\b" + r[0] + "(\\s*\\()").matcher(s).replaceAll(r[1] + "$1");
			}
		}
		for (String[] r : new String[][] {{"shadow2DLod", "textureLod"}, {"shadow2D", "texture"}}) {
			if (s.contains(r[0])) {
				s = wrapCalls(s, r[0], "vec4(" + r[1] + "(", "))");
			}
		}
		return s;
	}

	static String wrapCalls(final String s, final String name, final String prefix, final String suffix) {
		Pattern p = Pattern.compile("\\b" + name + "\\s*\\(");
		Matcher m = p.matcher(s);
		StringBuilder out = new StringBuilder();
		int from = 0;
		while (m.find(from)) {
			int open = m.end() - 1;
			int close = matchParen(s, open);
			if (close < 0) {
				break;
			}
			out.append(s, from, m.start()).append(prefix).append(s, open + 1, close).append(suffix);
			from = close + 1;
		}
		out.append(s.substring(from));
		return out.toString();
	}

	static int matchParen(final String s, final int open) {
		int level = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(') {
				level++;
			} else if (c == ')') {
				level--;
				if (level == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String fixedFunction(String s, final boolean vertex, final StringBuilder decls) {
		if (vertex) {
			if (s.contains("ftransform")) {
				s = Pattern.compile("\\bftransform\\s*\\(\\s*\\)").matcher(s)
					.replaceAll("(gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex)");
			}
			s = replaceIfPresent(s, "gl_ModelViewProjectionMatrix", "(gl_ProjectionMatrix * gl_ModelViewMatrix)");
			s = replaceIfPresent(s, "gl_Vertex", "vec4(iris_Position, 1.0)", decls, "in vec3 iris_Position;\n");
			s = replaceIfPresent(s, "gl_Color", "iris_Color", decls, "in vec4 iris_Color;\n");
			s = replaceIfPresent(s, "gl_Normal", "iris_Normal", decls, "in vec3 iris_Normal;\n");
			s = replaceIfPresent(s, "gl_MultiTexCoord0", "vec4(iris_UV0, 0.0, 1.0)", decls, "in vec2 iris_UV0;\n");
			s = replaceIfPresent(s, "gl_MultiTexCoord1", "vec4(iris_UV2, 0.0, 1.0)", decls, "in vec2 iris_UV2;\n");
			s = replaceIfPresent(s, "gl_MultiTexCoord2", "vec4(iris_UV2, 0.0, 1.0)", decls, "in vec2 iris_UV2;\n");
			s = replaceIfPresent(s, "gl_MultiTexCoord3", "vec4(0.0, 0.0, 0.0, 1.0)");
			s = replaceIfPresent(s, "gl_FrontColor", "iris_FrontColor", decls, "out vec4 iris_FrontColor;\n");
			s = replaceIfPresent(s, "gl_FogFragCoord", "iris_FogFragCoord", decls, "out float iris_FogFragCoord;\n");
			s = texCoordArray(s, decls, "out");
		} else {
			s = replaceIfPresent(s, "gl_Color", "iris_FrontColor", decls, "in vec4 iris_FrontColor;\n");
			s = replaceIfPresent(s, "gl_FogFragCoord", "iris_FogFragCoord", decls, "in float iris_FogFragCoord;\n");
			s = texCoordArray(s, decls, "in");
		}
		s = replaceIfPresent(s, "gl_ModelViewMatrixInverse", "iris_ModelViewMatrixInverse", decls, "uniform mat4 iris_ModelViewMatrixInverse;\n");
		s = replaceIfPresent(s, "gl_ProjectionMatrixInverse", "iris_ProjectionMatrixInverse", decls, "uniform mat4 iris_ProjectionMatrixInverse;\n");
		s = replaceIfPresent(s, "gl_ModelViewMatrix", "iris_ModelViewMatrix", decls, "uniform mat4 iris_ModelViewMatrix;\n");
		s = replaceIfPresent(s, "gl_ProjectionMatrix", "iris_ProjectionMatrix", decls, "uniform mat4 iris_ProjectionMatrix;\n");
		s = replaceIfPresent(s, "gl_NormalMatrix", "iris_NormalMatrix", decls, "uniform mat3 iris_NormalMatrix;\n");
		Matcher tm = TEXTURE_MATRIX.matcher(s);
		TreeSet<Integer> matrices = new TreeSet<>();
		while (tm.find()) {
			matrices.add(Integer.parseInt(tm.group(1)));
		}
		if (!matrices.isEmpty()) {
			s = TEXTURE_MATRIX.matcher(s).replaceAll("iris_TextureMatrix$1");
			for (int i : matrices) {
				decls.append("uniform mat4 iris_TextureMatrix").append(i).append(";\n");
			}
		}
		if (s.contains("gl_Fog")) {
			s = s.replace("gl_Fog.color", "iris_FogColor").replace("gl_Fog.start", "iris_FogStart")
				.replace("gl_Fog.end", "iris_FogEnd").replace("gl_Fog.density", "iris_FogDensity").replace("gl_Fog.scale", "iris_FogScale");
			decls.append("uniform vec4 iris_FogColor;\nuniform float iris_FogStart;\nuniform float iris_FogEnd;\n")
				.append("uniform float iris_FogDensity;\nuniform float iris_FogScale;\n");
		}
		return s;
	}

	private static String texCoordArray(String s, final StringBuilder decls, final String qualifier) {
		Matcher m = TEX_COORD.matcher(s);
		TreeSet<Integer> used = new TreeSet<>();
		while (m.find()) {
			used.add(Integer.parseInt(m.group(1)));
		}
		if (used.isEmpty()) {
			return s;
		}
		for (int i : used) {
			decls.append(qualifier).append(" vec4 iris_TexCoord").append(i).append(";\n");
		}
		return TEX_COORD.matcher(s).replaceAll("iris_TexCoord$1");
	}

	private static String replaceIfPresent(final String s, final String from, final String to) {
		return s.contains(from) ? replaceIdent(s, from, to) : s;
	}

	private static String replaceIfPresent(final String s, final String from, final String to, final StringBuilder decls, final String decl) {
		if (!Pattern.compile("\\b" + from + "\\b").matcher(s).find()) {
			return s;
		}
		if (decls.indexOf(decl) < 0) {
			decls.append(decl);
		}
		return replaceIdent(s, from, to);
	}

	private static String fragmentOutputs(String s, final Output out, final StringBuilder decls) {
		TreeSet<Integer> used = new TreeSet<>();
		Matcher m = FRAG_DATA.matcher(s);
		while (m.find()) {
			used.add(Integer.parseInt(m.group(1)));
		}
		if (Pattern.compile("\\bgl_FragColor\\b").matcher(s).find()) {
			s = replaceIdent(s, "gl_FragColor", "iris_FragData0");
			used.add(0);
		}
		if (!used.isEmpty()) {
			s = FRAG_DATA.matcher(s).replaceAll("iris_FragData$1");
			for (int i : used) {
				decls.append("layout(location = ").append(i).append(") out vec4 iris_FragData").append(i).append(";\n");
				out.outputsByLocation.put(i, "iris_FragData" + i);
			}
			out.fragOutputs = used.last() + 1;
			return s;
		}
		Matcher d = OUT_DECL.matcher(s);
		StringBuilder rebuilt = new StringBuilder();
		int from = 0;
		int next = 0;
		int max = -1;
		while (d.find()) {
			String layout = d.group(3);
			int location;
			Matcher loc = Pattern.compile("location\\s*=\\s*(\\d+)").matcher(layout);
			String replacement;
			if (loc.find()) {
				location = Integer.parseInt(loc.group(1));
				replacement = d.group(0);
			} else {
				location = next;
				replacement = d.group(1) + d.group(2) + "layout(location = " + location + ") " + d.group(4) + "out " + d.group(5) + " " + d.group(6) + ";";
			}
			next = Math.max(next, location + 1);
			max = Math.max(max, location);
			out.outputsByLocation.put(location, d.group(6));
			rebuilt.append(s, from, d.start()).append(replacement);
			from = d.end();
		}
		rebuilt.append(s.substring(from));
		out.fragOutputs = max + 1;
		return rebuilt.toString();
	}

	private static String stripUniformInitializers(final String s) {
		return Pattern.compile("(\\buniform\\s+\\w+\\s+\\w+(?:\\s*\\[[^\\]]*\\])?)\\s*=\\s*[^;]+;").matcher(s).replaceAll("$1;");
	}

	private static final Pattern TYPE_START = Pattern.compile(
		"^(?:(?:highp|mediump|lowp|const)\\s+)*(?:float|int|uint|bool|[iub]?vec[234]|mat[234](?:x[234])?|[A-Z]\\w*)\\b");

	static String moveGlobalInitializers(final String s, final Output out) {
		StringBuilder kept = new StringBuilder(s.length());
		StringBuilder moved = new StringBuilder();
		int depth = 0;
		int parens = 0;
		int stmtStart = 0;
		int n = s.length();
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			if (c == '(') {
				parens++;
			} else if (c == ')') {
				parens--;
			} else if (c == '{') {
				if (depth == 0) {
					kept.append(s, stmtStart, i + 1);
					stmtStart = i + 1;
				}
				depth++;
				continue;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					kept.append(s, stmtStart, i + 1);
					stmtStart = i + 1;
				}
				continue;
			}
			if (depth == 0 && parens == 0 && c == ';') {
				String stmt = s.substring(stmtStart, i + 1);
				kept.append(splitGlobal(stmt, moved));
				stmtStart = i + 1;
			}
		}
		kept.append(s.substring(stmtStart));
		if (moved.length() == 0) {
			return kept.toString();
		}
		String result = kept.toString();
		Matcher main = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{").matcher(result);
		if (!main.find()) {
			out.notes.add("no main(): global initializers left in place");
			return s;
		}
		out.notes.add("moved global initializers into main()");
		return result.substring(0, main.end()) + "\n" + moved + result.substring(main.end());
	}

	private static String splitGlobal(final String stmt, final StringBuilder moved) {
		String trimmed = stmt.trim();
		if (!trimmed.contains("=") || trimmed.startsWith("const ") || trimmed.startsWith("uniform ") || trimmed.startsWith("in ")
			|| trimmed.startsWith("out ") || trimmed.startsWith("layout") || trimmed.startsWith("struct") || trimmed.startsWith("precision")) {
			return stmt;
		}
		Matcher type = TYPE_START.matcher(trimmed);
		if (!type.find()) {
			return stmt;
		}
		String typeText = trimmed.substring(0, type.end());
		String rest = trimmed.substring(type.end(), trimmed.length() - 1);
		if (rest.trim().startsWith("(")) {
			return stmt;
		}
		if (isConstantLooking(rest)) {
			return stmt;
		}
		List<String> declarators = splitTopLevel(rest, ',');
		StringBuilder global = new StringBuilder();
		for (String declarator : declarators) {
			int eq = topLevelIndexOf(declarator, '=');
			String name = eq < 0 ? declarator.trim() : declarator.substring(0, eq).trim();
			if (global.length() > 0) {
				global.append(", ");
			}
			global.append(name);
			if (eq >= 0) {
				String bare = name.replaceAll("\\[.*$", "").trim();
				moved.append("    ").append(bare).append(" = ").append(declarator.substring(eq + 1).trim()).append(";\n");
			}
		}
		int lead = stmt.indexOf(trimmed);
		return stmt.substring(0, Math.max(lead, 0)) + typeText + " " + global + ";";
	}

	private static final java.util.Set<String> CONSTANT_IDENTIFIERS = new java.util.HashSet<>(java.util.Arrays.asList(
		"float", "int", "uint", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
		"bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4", "mat2x2", "mat3x3", "mat4x4", "true", "false", "e", "E",
		"abs", "sign", "floor", "ceil", "fract", "mod", "min", "max", "clamp", "mix", "step", "smoothstep", "sqrt",
		"inversesqrt", "pow", "exp", "exp2", "log", "log2", "sin", "cos", "tan", "asin", "acos", "atan", "radians",
		"degrees", "length", "normalize", "dot", "cross", "u", "U", "f", "F"));

	static boolean isConstantLooking(final String declaratorsAndInitializers) {
		int eq = declaratorsAndInitializers.indexOf('=');
		if (eq < 0) {
			return false;
		}
		for (String t : GlslPreprocessor.tokenize(declaratorsAndInitializers.substring(eq + 1))) {
			if (GlslPreprocessor.isIdentStart(t.charAt(0)) && !CONSTANT_IDENTIFIERS.contains(t)) {
				return false;
			}
		}
		return true;
	}

	static List<String> splitTopLevel(final String s, final char separator) {
		List<String> parts = new ArrayList<>();
		int level = 0;
		int start = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(' || c == '[' || c == '{') {
				level++;
			} else if (c == ')' || c == ']' || c == '}') {
				level--;
			} else if (c == separator && level == 0) {
				parts.add(s.substring(start, i));
				start = i + 1;
			}
		}
		parts.add(s.substring(start));
		return parts;
	}

	private static int topLevelIndexOf(final String s, final char target) {
		int level = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '(' || c == '[') {
				level++;
			} else if (c == ')' || c == ']') {
				level--;
			} else if (c == target && level == 0) {
				boolean comparison = (i + 1 < s.length() && s.charAt(i + 1) == '=') || (i > 0 && "=!<>".indexOf(s.charAt(i - 1)) >= 0);
				if (!comparison) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String polyfills(final String s) {
		StringBuilder p = new StringBuilder();
		if (usesCall(s, "fma") && !definesFunction(s, "fma")) {
			p.append("float fma(float a, float b, float c) { return a * b + c; }\n")
				.append("vec2 fma(vec2 a, vec2 b, vec2 c) { return a * b + c; }\n")
				.append("vec3 fma(vec3 a, vec3 b, vec3 c) { return a * b + c; }\n")
				.append("vec4 fma(vec4 a, vec4 b, vec4 c) { return a * b + c; }\n");
		}
		if (usesCall(s, "packUnorm4x8") && !definesFunction(s, "packUnorm4x8")) {
			p.append("uint packUnorm4x8(vec4 v) { uvec4 u = uvec4(round(clamp(v, 0.0, 1.0) * 255.0)); return u.x | (u.y << 8) | (u.z << 16) | (u.w << 24); }\n");
		}
		if (usesCall(s, "unpackUnorm4x8") && !definesFunction(s, "unpackUnorm4x8")) {
			p.append("vec4 unpackUnorm4x8(uint p) { return vec4(uvec4(p, p >> 8, p >> 16, p >> 24) & 255u) / 255.0; }\n");
		}
		if (usesCall(s, "packSnorm4x8") && !definesFunction(s, "packSnorm4x8")) {
			p.append("uint packSnorm4x8(vec4 v) { uvec4 u = uvec4(ivec4(round(clamp(v, -1.0, 1.0) * 127.0)) & 255); return u.x | (u.y << 8) | (u.z << 16) | (u.w << 24); }\n");
		}
		if (usesCall(s, "unpackSnorm4x8") && !definesFunction(s, "unpackSnorm4x8")) {
			p.append("vec4 unpackSnorm4x8(uint p) { ivec4 i = ivec4(uvec4(p << 24, p << 16, p << 8, p)) >> 24; return clamp(vec4(i) / 127.0, -1.0, 1.0); }\n");
		}
		if (usesCall(s, "findMSB") && !definesFunction(s, "findMSB")) {
			p.append("int findMSB(int v) { if (v < 0) v = ~v; if (v == 0) return -1; return int(floor(log2(float(v)))); }\n")
				.append("int findMSB(uint v) { if (v == 0u) return -1; return int(floor(log2(float(v)))); }\n");
		}
		if (usesCall(s, "findLSB") && !definesFunction(s, "findLSB")) {
			p.append("int findLSB(int v) { if (v == 0) return -1; return int(log2(float(v & -v))); }\n")
				.append("int findLSB(uint v) { if (v == 0u) return -1; return int(log2(float(v & (~v + 1u)))); }\n");
		}
		if (usesCall(s, "bitfieldExtract") && !definesFunction(s, "bitfieldExtract")) {
			p.append("uint bitfieldExtract(uint v, int o, int b) { return b == 0 ? 0u : (v >> uint(o)) & ((b >= 32) ? 0xFFFFFFFFu : ((1u << uint(b)) - 1u)); }\n")
				.append("int bitfieldExtract(int v, int o, int b) { if (b == 0) return 0; int r = v << (32 - o - b); return r >> (32 - b); }\n");
		}
		if (usesCall(s, "bitCount") && !definesFunction(s, "bitCount")) {
			p.append("int bitCount(uint v) { v = v - ((v >> 1) & 0x55555555u); v = (v & 0x33333333u) + ((v >> 2) & 0x33333333u); return int((((v + (v >> 4)) & 0x0F0F0F0Fu) * 0x01010101u) >> 24); }\n")
				.append("int bitCount(int v) { return bitCount(uint(v)); }\n");
		}
		if (usesCall(s, "textureGather") && !definesFunction(s, "textureGather")) {
			p.append("vec4 iris_gather(sampler2D s, vec2 uv, int c) { vec2 size = vec2(textureSize(s, 0)); vec2 base = uv * size - 0.5; ivec2 i = ivec2(floor(base));\n")
				.append("  ivec2 lim = textureSize(s, 0) - 1;\n")
				.append("  return vec4(texelFetch(s, clamp(i + ivec2(0, 1), ivec2(0), lim), 0)[c], texelFetch(s, clamp(i + ivec2(1, 1), ivec2(0), lim), 0)[c],\n")
				.append("              texelFetch(s, clamp(i + ivec2(1, 0), ivec2(0), lim), 0)[c], texelFetch(s, clamp(i, ivec2(0), lim), 0)[c]); }\n")
				.append("vec4 textureGather(sampler2D s, vec2 uv) { return iris_gather(s, uv, 0); }\n")
				.append("vec4 textureGather(sampler2D s, vec2 uv, int c) { return iris_gather(s, uv, c); }\n");
		}
		return p.toString();
	}

	private static boolean usesCall(final String s, final String name) {
		return s.contains(name) && Pattern.compile("\\b" + name + "\\s*\\(").matcher(s).find();
	}
}
