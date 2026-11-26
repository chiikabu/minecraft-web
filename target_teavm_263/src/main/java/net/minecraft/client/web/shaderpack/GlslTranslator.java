// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites preprocessed shader pack GLSL (any desktop version, compatibility or Iris 1.17+ core
 * profile) as GLSL ES 3.00 for WebGL2.
 *
 * <p>Input is the output of {@link GlslPreprocessor}: macro-expanded, no comments, no directives.
 * Handles legacy fixed-function attributes/matrices, Iris 1.17+ core profile attributes/uniforms,
 * desktop layout stripping, interface block flattening, unsized arrays & C-style aggregate
 * initializers, double->float lowering, auto-{@code flat} integer varyings, legacy texture/shadow
 * & derivative rewrites, fragment output location assignment, global initializer hoisting, and
 * bit-exact GLSL 4.x polyfills.
 */
public final class GlslTranslator {
	private GlslTranslator() {
	}

	public static final class Output {
		public String source;
		/** Number of fragment outputs (max location + 1), 0 for vertex shaders. */
		public int fragOutputs;
		/** Fragment output variable name per location (for RENDERTARGETS mapping). */
		public final Map<Integer, String> outputsByLocation = new LinkedHashMap<>();
		/** Fragment output GLSL base type per location (vec4, vec3, ivec4, uvec4, float, ...). */
		public final Map<Integer, String> outputTypesByLocation = new LinkedHashMap<>();
		public final List<String> notes = new ArrayList<>();
		/** World vertex shaders: statements run before the pack's main (block id, tangent, core attrs). */
		public String vertexInit = "";
	}

	/**
	 * Identifiers that are reserved keywords in ES 3.00 (excluding double/dvec* which are lowered
	 * to float/vec*, and packed/shared which are valid ES 3.00 layout qualifiers).
	 */
	private static final String[] RENAMED_IDENTIFIERS = {
		"input", "output", "filter", "sample", "active", "common", "partition", "resource", "union", "enum",
		"typedef", "template", "this", "goto", "inline", "noinline", "volatile", "public", "static",
		"extern", "external", "interface", "long", "short", "half", "fixed", "unsigned", "sizeof",
		"cast", "namespace", "using", "superp", "buffer", "coherent",
		"readonly", "writeonly", "restrict", "subroutine", "patch"
	};

	private static final Pattern RENAMED_IDENT_PATTERN;
	static {
		StringBuilder sb = new StringBuilder("\\b(");
		for (int i = 0; i < RENAMED_IDENTIFIERS.length; i++) {
			if (i > 0) {
				sb.append('|');
			}
			sb.append(RENAMED_IDENTIFIERS[i]);
		}
		sb.append(")\\b");
		RENAMED_IDENT_PATTERN = Pattern.compile(sb.toString());
	}

	/** ES 3.00 built-in functions that GLSL 1.20 did not have; packs written for 1.20 define their own. */
	private static final String[] NEW_BUILTINS_FOR_OLD_VERSIONS = {
		"round", "roundEven", "trunc", "inverse", "transpose", "determinant", "outerProduct", "isnan", "isinf",
		"sinh", "cosh", "tanh", "asinh", "acosh", "atanh", "modf", "texelFetch", "textureSize",
		"floatBitsToInt", "floatBitsToUint", "intBitsToFloat", "uintBitsToFloat", "packHalf2x16", "unpackHalf2x16",
		"packUnorm2x16", "unpackUnorm2x16", "packSnorm2x16", "unpackSnorm2x16", "textureLod", "textureGrad",
		"textureOffset", "textureProj", "textureLodOffset", "texelFetchOffset", "textureGradOffset"
	};

	private static final Pattern[] NEW_BUILTIN_DEF_PATTERNS = new Pattern[NEW_BUILTINS_FOR_OLD_VERSIONS.length];
	private static final Pattern[] NEW_BUILTIN_CALL_PATTERNS = new Pattern[NEW_BUILTINS_FOR_OLD_VERSIONS.length];
	static {
		for (int i = 0; i < NEW_BUILTINS_FOR_OLD_VERSIONS.length; i++) {
			String fn = NEW_BUILTINS_FOR_OLD_VERSIONS[i];
			NEW_BUILTIN_DEF_PATTERNS[i] = Pattern.compile(
				"\\b(?:void|float|int|uint|bool|[iu]?vec[234]|mat[234](?:x[234])?)\\s+" + fn + "\\s*\\(");
			NEW_BUILTIN_CALL_PATTERNS[i] = Pattern.compile("\\b" + fn + "(\\s*\\()");
		}
	}

	private static final Map<String, String> SIMPLE_FUNC_REWRITES = new LinkedHashMap<>();
	private static final Pattern SIMPLE_FUNC_PATTERN;
	static {
		String[][] pairs = {
			{"texture2DLodEXT", "textureLod"}, {"texture2DLod", "textureLod"},
			{"texture2DGradARB", "textureGrad"}, {"texture2DGradEXT", "textureGrad"}, {"texture2DGrad", "textureGrad"},
			{"texture2DOffset", "textureOffset"}, {"texture2DLodOffset", "textureLodOffset"}, {"texture2DGradOffset", "textureGradOffset"},
			{"texture2DProjLod", "textureProjLod"}, {"texture2DProj", "textureProj"}, {"texture2DRect", "texture"},
			{"texture3DLod", "textureLod"}, {"texture3D", "texture"},
			{"textureCubeLodEXT", "textureLod"}, {"textureCubeLod", "textureLod"}, {"textureCube", "texture"},
			{"texture2D", "texture"}, {"texture1D", "texture"},
			{"texelFetch2DOffset", "texelFetchOffset"}, {"texelFetch2D", "texelFetch"}, {"texelFetch3D", "texelFetch"},
			{"textureSize2D", "textureSize"},
			{"dFdxFine", "dFdx"}, {"dFdyFine", "dFdy"}, {"dFdxCoarse", "dFdx"}, {"dFdyCoarse", "dFdy"},
			{"fwidthFine", "fwidth"}, {"fwidthCoarse", "fwidth"}
		};
		StringBuilder sb = new StringBuilder("\\b(");
		for (int i = 0; i < pairs.length; i++) {
			SIMPLE_FUNC_REWRITES.put(pairs[i][0], pairs[i][1]);
			if (i > 0) {
				sb.append('|');
			}
			sb.append(pairs[i][0]);
		}
		sb.append(")(?=\\s*\\()");
		SIMPLE_FUNC_PATTERN = Pattern.compile(sb.toString());
	}

	// Cached compiled Patterns (no Pattern.compile in hot paths)
	private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
	private static final Pattern FRAG_COLOR = Pattern.compile("\\bgl_FragColor\\b");
	private static final Pattern FRAG_COORD = Pattern.compile("\\bgl_FragCoord\\b");
	private static final Pattern FRAG_DEPTH = Pattern.compile("\\bgl_FragDepth\\b");
	private static final Pattern TEX_COORD = Pattern.compile("\\bgl_TexCoord\\s*\\[\\s*(\\d+)\\s*\\]");
	private static final Pattern TEXTURE_MATRIX_CONST = Pattern.compile("\\bgl_TextureMatrix\\s*\\[\\s*(\\d+)\\s*\\]");
	private static final Pattern TEXTURE_MATRIX_DYN = Pattern.compile("\\bgl_TextureMatrix\\s*\\[");
	private static final Pattern FTRANSFORM = Pattern.compile("\\bftransform\\s*\\(\\s*\\)");
	private static final Pattern MAIN_DECL = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
	private static final Pattern WRAPPER_MAIN_DECL = Pattern.compile(
		"\\bvoid\\s+(?:iris_pack_main|iris_alphaMain|iris_fixedMain|iris_markMain)\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
	private static final Pattern SAMPLER_TEXTURE_DECL = Pattern.compile("\\bsampler\\w*\\s+texture\\s*[;,=]");
	private static final Pattern VAR_TEXTURE_DECL = Pattern.compile(
		"\\b(?:float|int|uint|bool|[iub]?vec[234]|mat[234](?:x[234])?)\\s+(?:[\\w\\[\\]]+\\s*,\\s*)*texture\\b(?!\\s*\\()");
	private static final Pattern BARE_TEXTURE_IDENT = Pattern.compile("(?<!\\.)\\btexture\\b(?!\\s*\\()");
	private static final Pattern SMOOTH_VAR_DECL = Pattern.compile("\\b(?:float|int|vec[234]|bool|ivec[234])\\s+smooth\\b");
	private static final Pattern FLAT_VAR_DECL = Pattern.compile("\\b(?:float|int|vec[234]|bool|ivec[234])\\s+flat\\b");
	private static final Pattern DOUBLE_VAR_DECL = Pattern.compile("\\b(?:float|int|vec[234]|bool|ivec[234])\\s+double\\b");
	private static final Pattern DOUBLE_TYPE_USE = Pattern.compile("\\b(double|dvec[234]|dmat[234](?:x[234])?)\\b");
	private static final Pattern DOUBLE_LITERAL_SUFFIX = Pattern.compile("(\\b(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)[lL][fF]\\b");
	private static final Pattern SHADOW_SAMPLER_DECL = Pattern.compile("\\bsampler(?:2D|Cube|2DArray)Shadow\\s+(\\w+)\\b");
	private static final Pattern LAYOUT_BLOCK = Pattern.compile("\\blayout\\s*\\(([^)]*)\\)");
	private static final Pattern DESKTOP_LAYOUT_QUAL = Pattern.compile(
		"\\b(?:binding|index|offset|align|xfb_buffer|xfb_offset|xfb_stride)\\s*=\\s*[^,]+|"
		+ "\\b(?:early_fragment_tests|depth_any|depth_greater|depth_less|depth_unchanged|origin_upper_left|pixel_center_integer|"
		+ "rgba32f|rgba16f|rg32f|rg16f|r11f_g11f_b10f|r32f|r16f|rgba16|rgb10_a2|rgba8|rg16|rg8|r16|r8|"
		+ "rgba16_snorm|rgba8_snorm|rg16_snorm|rg8_snorm|r16_snorm|r8_snorm|"
		+ "rgba32i|rgba16i|rgba8i|rg32i|rg16i|rg8i|r32i|r16i|r8i|"
		+ "rgba32ui|rgba16ui|rgb10_a2ui|rgba8ui|rg32ui|rg16ui|rg8ui|r32ui|r16ui|r8ui)\\b");
	private static final Pattern EMPTY_IN_OUT_STMT = Pattern.compile(
		"(?m)^[ \\t]*(?:in\\s*;|out\\s+float\\s+gl_FragDepth\\s*;|in\\s+vec4\\s+gl_FragCoord\\s*;)");
	private static final Pattern INTERFACE_BLOCK = Pattern.compile(
		"(?:layout\\s*\\([^)]*\\)\\s*)?((?:(?:flat|smooth|noperspective|centroid|invariant)\\s+)*)(in|out)\\s+(\\w+)\\s*\\{([^}]*)\\}\\s*(\\w+)?\\s*;");
	private static final Pattern MEMBER_DECL = Pattern.compile(
		"^((?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\\s+)*)(\\w+)\\s+(.+);$");
	private static final Pattern UNSIZED_CTOR = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\[\\s*\\]\\s*\\(");
	private static final Pattern UNSIZED_DECL_AFTER = Pattern.compile(
		"\\b((?:(?:const|highp|mediump|lowp)\\s+)*)(\\w+)\\s+(\\w+)\\s*\\[\\s*\\]\\s*=\\s*\\2\\s*\\[\\s*([^\\]]+)\\s*\\]");
	private static final Pattern UNSIZED_DECL_BEFORE = Pattern.compile(
		"\\b((?:(?:const|highp|mediump|lowp)\\s+)*)(\\w+)\\s*\\[\\s*\\]\\s+(\\w+)\\s*=\\s*\\2\\s*\\[\\s*([^\\]]+)\\s*\\]");
	private static final Pattern BRACE_INIT_DECL = Pattern.compile(
		"((?:(?:const|highp|mediump|lowp)\\s+)*)(\\w+)\\s*(?:\\[([^\\]]*)\\]\\s*)?(\\w+)\\s*(?:\\[([^\\]]*)\\]\\s*)?=\\s*\\{");
	private static final Pattern STAGE_VARYING_STMT = Pattern.compile(
		"^((?:layout\\s*\\([^)]*\\)\\s*)?)((?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\\s+)*)(in|out)\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w+)\\s+(.+);$");
	private static final Pattern LOCATION_QUAL = Pattern.compile("location\\s*=\\s*(\\d+)");
	private static final Pattern UNIFORM_INIT = Pattern.compile(
		"(\\buniform\\s+(?:(?:highp|mediump|lowp)\\s+)?\\w+\\s+\\w+(?:\\s*\\[[^\\]]*\\])?)\\s*=\\s*[^;]+;");
	private static final Pattern CORE_UNIFORM_DECL = Pattern.compile(
		"(?m)^[ \\t]*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?(vec3|vec4|mat3|mat4)\\s+"
		+ "(chunkOffset|modelViewMatrix|modelViewMatrixInverse|projectionMatrix|projectionMatrixInverse|"
		+ "modelViewProjectionMatrix|normalMatrix|textureMatrix|colorModulator|iris_ColorModulator)\\s*;");
	private static final Pattern IMAGE_DECL = Pattern.compile("(?m)^[ \\t]*(?:layout\\s*\\([^)]*\\)\\s*)?"
		+ "(?:(?:writeonly|readonly|coherent|restrict|volatile|highp|mediump|lowp|uniform)\\s+)+([iu]?)image(?:1D|2D|3D|Cube|2DArray|Buffer|2DRect)\\w*\\s+(\\w+)\\s*;");
	private static final Pattern IMAGE_CALL = Pattern.compile("\\b(imageStore|imageLoad|imageAtomic\\w+|imageSize)\\s*\\(\\s*(\\w+)");
	private static final Pattern TYPE_START = Pattern.compile(
		"^(?:(?:highp|mediump|lowp|const)\\s+)*(?:float|int|uint|bool|[iub]?vec[234]|mat[234](?:x[234])?|[A-Z]\\w*)\\b");
	private static final Pattern ARRAY_SUFFIX = Pattern.compile("\\[.*$");
	private static final Pattern ARRAY_SIZE = Pattern.compile("^(\\w+)\\s*(?:\\[\\s*(\\d+)\\s*\\])?");

	private static final Map<String, Pattern> IDENT_PATTERNS = new HashMap<>();
	private static final Map<String, Pattern> CALL_PATTERNS = new HashMap<>();

	private static Pattern identPattern(final String name) {
		Pattern p = IDENT_PATTERNS.get(name);
		if (p == null) {
			p = Pattern.compile("\\b" + name + "\\b");
			IDENT_PATTERNS.put(name, p);
		}
		return p;
	}

	private static Pattern callPattern(final String name) {
		Pattern p = CALL_PATTERNS.get(name);
		if (p == null) {
			p = Pattern.compile("\\b" + name + "\\s*\\(");
			CALL_PATTERNS.put(name, p);
		}
		return p;
	}

	private static final String[] HEADER_SAMPLERS = {
		"sampler2D", "sampler3D", "samplerCube", "sampler2DShadow", "sampler2DArray",
		"isampler2D", "usampler2D", "isampler3D", "usampler3D",
		"isamplerCube", "usamplerCube", "isampler2DArray", "usampler2DArray",
		"sampler2DArrayShadow", "samplerCubeShadow"
	};

	private static final String HEADER_PREFIX;
	static {
		StringBuilder header = new StringBuilder();
		header.append("#version 300 es\n");
		header.append("precision highp float;\nprecision highp int;\n");
		for (String sampler : HEADER_SAMPLERS) {
			header.append("precision highp ").append(sampler).append(";\n");
		}
		HEADER_PREFIX = header.toString();
	}

	private static final String GBUFFERS_VERTEX_DECLS =
		"layout(std140) uniform iris_Globals { ivec3 iris_CameraBlockPos; float iris_GlintAlpha;"
		+ " vec3 iris_CameraOffset; float iris_GameTime; vec2 iris_ScreenSize; int iris_MenuBlurRadius; int iris_UseRgss; };\n"
		+ "layout(std140) uniform iris_ChunkSection { ivec3 iris_ChunkPosition; float iris_ChunkVisibility; };\n"
		+ "in vec3 iris_Position;\n"
		+ "vec3 iris_chunkOffset() { return vec3(iris_ChunkPosition - iris_CameraBlockPos) + iris_CameraOffset; }\n";

	// Exact std140 field order verified from assets/minecraft/shaders/include/dynamictransforms.glsl
	private static final String DYNAMIC_TRANSFORMS_UBO =
		"layout(std140) uniform iris_DynamicTransforms { mat4 iris_DynModelViewMat; mat4 iris_DynTextureMat; vec4 iris_DynColorModulator; vec3 iris_DynModelOffset; };\n";
	private static final String DYN_NORMAL_MATRIX_FN =
		"mat3 iris_dynNormalMatrix() { mat3 m = mat3(iris_DynModelViewMat); return abs(determinant(m)) > 1e-8 ? transpose(inverse(m)) : mat3(1.0); }\n";

	private static final String ENTITY_VERTEX_DECLS =
		DYNAMIC_TRANSFORMS_UBO
		+ "in vec3 iris_Position;\nin ivec2 iris_UV2i;\nin vec3 iris_Normal;\n"
		+ "vec4 iris_entityTangent() { float l = dot(iris_Normal, iris_Normal);\n"
		+ "  vec3 n = l > 1e-8 ? iris_Normal * inversesqrt(l) : vec3(0.0, 1.0, 0.0);\n"
		+ "  vec3 t = abs(n.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : normalize(cross(vec3(0.0, 1.0, 0.0), n));\n"
		+ "  return vec4(t, n.y < -0.9 ? -1.0 : 1.0); }\n";

	private static final String BLOCK_TAG_DECLS =
		"in ivec2 iris_UV2i;\n"
		+ "uniform highp sampler2D iris_BlockIdTable;\n"
		+ "vec3 iris_faceNormal() { int f = (iris_UV2i.x >> 8) & 7;\n"
		+ "  return f == 1 ? vec3(0.0, -1.0, 0.0) : f == 3 ? vec3(0.0, 0.0, -1.0) : f == 4 ? vec3(0.0, 0.0, 1.0)\n"
		+ "    : f == 5 ? vec3(-1.0, 0.0, 0.0) : f == 6 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0); }\n"
		+ "float iris_blockId() { int i = ((iris_UV2i.x >> 11) & 31) | (((iris_UV2i.y >> 8) & 255) << 5);\n"
		+ "  if (i == 0) { return -1.0; }\n"
		+ "  ivec4 b = ivec4(texelFetch(iris_BlockIdTable, ivec2(i & 255, i >> 8), 0) * 255.0 + 0.5);\n"
		+ "  return float(b.r | (b.g << 8) | (b.b << 16)); }\n"
		+ "vec4 iris_tangent() { vec3 n = iris_faceNormal();\n"
		+ "  return vec4(abs(n.y) > 0.5 ? vec3(1.0, 0.0, 0.0) : abs(n.x) > 0.5 ? vec3(0.0, 0.0, -n.x) : vec3(n.z, 0.0, 0.0), n.y < -0.5 ? -1.0 : 1.0); }\n";

	public static Output translate(final String code, final boolean vertex, final int version) {
		return translate(code, vertex, version, false);
	}

	public static Output translate(final String code, final boolean vertex, final int version, final boolean gbuffers) {
		return translate(code, vertex, version, gbuffers, true);
	}

	public static Output translate(final String code, final boolean vertex, final int version, final boolean gbuffers, final boolean flipZ) {
		return translate(code, vertex, version, gbuffers, flipZ, false);
	}

	public static Output translate(final String code, final boolean vertex, final int version, final boolean gbuffers,
		final boolean flipZ, final boolean entity) {
		Output out = new Output();
		String s = code;

		s = stripImages(s, out);
		s = stripDesktopLayouts(s);
		s = convertDoubleTypes(s);
		s = renameIdentifiers(s, version, out);
		s = qualifiers(s, vertex, version);
		s = flattenInterfaceBlocks(s);
		s = convertArraysAndBraces(s);
		s = autoFlatIntVaryings(s, vertex);
		s = textureFunctions(s, version);

		StringBuilder decls = new StringBuilder();
		s = rewriteCoreProfileUniforms(s, vertex, gbuffers, entity, decls);

		boolean wrapFragDepth = false;
		if (gbuffers) {
			if (vertex) {
				if (entity) {
					ensureDecl(decls, ENTITY_VERTEX_DECLS);
					s = replaceIfPresent(s, "gl_Vertex", "vec4(iris_Position + iris_DynModelOffset, 1.0)");
					s = replaceIfPresent(s, "gl_MultiTexCoord1", "vec4(vec2(iris_UV2i), 0.0, 1.0)");
					s = replaceIfPresent(s, "gl_MultiTexCoord2", "vec4(vec2(iris_UV2i), 0.0, 1.0)");
				} else {
					decls.append(GBUFFERS_VERTEX_DECLS);
					decls.append(BLOCK_TAG_DECLS);
					s = replaceIfPresent(s, "gl_Vertex", "vec4(iris_Position + iris_chunkOffset(), 1.0)");
					s = replaceIfPresent(s, "gl_MultiTexCoord1", "vec4(vec2(iris_UV2i & 255), 0.0, 1.0)");
					s = replaceIfPresent(s, "gl_MultiTexCoord2", "vec4(vec2(iris_UV2i & 255), 0.0, 1.0)");
					s = replaceIfPresent(s, "gl_Normal", "iris_faceNormal()");
				}
			} else if (flipZ) {
				if (s.contains("gl_FragCoord") && FRAG_COORD.matcher(s).find()) {
					s = replaceIdent(s, "gl_FragCoord", "iris_fragCoord()");
					decls.append("vec4 iris_fragCoord() { return vec4(gl_FragCoord.xy, 1.0 - gl_FragCoord.z, gl_FragCoord.w); }\n");
				}
				if (s.contains("gl_FragDepth") && FRAG_DEPTH.matcher(s).find()) {
					s = replaceIdent(s, "gl_FragDepth", "iris_FragDepth");
					decls.append("float iris_FragDepth;\n");
					decls.append("float iris_fragCoordZ() { return 1.0 - gl_FragCoord.z; }\n");
					wrapFragDepth = true;
				}
			}
			if (entity) {
				// Per-draw DynamicTransforms UBO for entities, block entities, sky, particles, glint
				s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixInverseTranspose",
					"transpose(inverse(iris_DynModelViewMat) * iris_gbProjectionInverse)", decls,
					DYNAMIC_TRANSFORMS_UBO + "uniform mat4 iris_gbProjectionInverse;\n");
				s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixInverse",
					"(inverse(iris_DynModelViewMat) * iris_gbProjectionInverse)", decls,
					DYNAMIC_TRANSFORMS_UBO + "uniform mat4 iris_gbProjectionInverse;\n");
				s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixTranspose",
					"transpose(iris_gbProjection * iris_DynModelViewMat)", decls,
					DYNAMIC_TRANSFORMS_UBO + "uniform mat4 iris_gbProjection;\n");
				s = replaceIfPresent(s, "gl_ModelViewProjectionMatrix",
					"(iris_gbProjection * iris_DynModelViewMat)", decls,
					DYNAMIC_TRANSFORMS_UBO + "uniform mat4 iris_gbProjection;\n");
				s = replaceIfPresent(s, "gl_ModelViewMatrixInverseTranspose",
					"transpose(inverse(iris_DynModelViewMat))", decls, DYNAMIC_TRANSFORMS_UBO);
				s = replaceIfPresent(s, "gl_ProjectionMatrixInverseTranspose",
					"transpose(iris_gbProjectionInverse)", decls, "uniform mat4 iris_gbProjectionInverse;\n");
				s = replaceIfPresent(s, "gl_ModelViewMatrixInverse",
					"inverse(iris_DynModelViewMat)", decls, DYNAMIC_TRANSFORMS_UBO);
				s = replaceIfPresent(s, "gl_ProjectionMatrixInverse",
					"iris_gbProjectionInverse", decls, "uniform mat4 iris_gbProjectionInverse;\n");
				s = replaceIfPresent(s, "gl_ModelViewMatrixTranspose",
					"transpose(iris_DynModelViewMat)", decls, DYNAMIC_TRANSFORMS_UBO);
				s = replaceIfPresent(s, "gl_ProjectionMatrixTranspose",
					"transpose(iris_gbProjection)", decls, "uniform mat4 iris_gbProjection;\n");
				s = replaceIfPresent(s, "gl_ModelViewMatrix", "iris_DynModelViewMat", decls, DYNAMIC_TRANSFORMS_UBO);
				s = replaceIfPresent(s, "gl_ProjectionMatrix", "iris_gbProjection", decls, "uniform mat4 iris_gbProjection;\n");
				s = replaceIfPresent(s, "gl_NormalMatrix", "iris_dynNormalMatrix()", decls,
					DYNAMIC_TRANSFORMS_UBO + DYN_NORMAL_MATRIX_FN);
				s = replaceIfPresent(s, "iris_ColorModulator", "iris_DynColorModulator", decls, DYNAMIC_TRANSFORMS_UBO);
				if (vertex && s.contains("ftransform")) {
					s = FTRANSFORM.matcher(s).replaceAll(
						"(iris_gbProjection * iris_DynModelViewMat * vec4(iris_Position + iris_DynModelOffset, 1.0))");
					ensureDecl(decls, DYNAMIC_TRANSFORMS_UBO + "uniform mat4 iris_gbProjection;\n");
				}
			} else {
				// World matrices (with pre-multiplied iris_gbModelViewProjection for MVP and ftransform)
				s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixInverseTranspose",
					"transpose(iris_gbModelViewInverse * iris_gbProjectionInverse)", decls,
					"uniform mat4 iris_gbModelViewInverse;\nuniform mat4 iris_gbProjectionInverse;\n");
				s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixInverse",
					"(iris_gbModelViewInverse * iris_gbProjectionInverse)", decls,
					"uniform mat4 iris_gbModelViewInverse;\nuniform mat4 iris_gbProjectionInverse;\n");
				s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixTranspose",
					"transpose(iris_gbModelViewProjection)", decls,
					"uniform mat4 iris_gbModelViewProjection;\n");
				s = replaceIfPresent(s, "gl_ModelViewProjectionMatrix",
					"iris_gbModelViewProjection", decls,
					"uniform mat4 iris_gbModelViewProjection;\n");
				s = replaceIfPresent(s, "gl_ModelViewMatrixInverseTranspose",
					"transpose(iris_gbModelViewInverse)", decls, "uniform mat4 iris_gbModelViewInverse;\n");
				s = replaceIfPresent(s, "gl_ProjectionMatrixInverseTranspose",
					"transpose(iris_gbProjectionInverse)", decls, "uniform mat4 iris_gbProjectionInverse;\n");
				s = replaceIfPresent(s, "gl_ModelViewMatrixInverse",
					"iris_gbModelViewInverse", decls, "uniform mat4 iris_gbModelViewInverse;\n");
				s = replaceIfPresent(s, "gl_ProjectionMatrixInverse",
					"iris_gbProjectionInverse", decls, "uniform mat4 iris_gbProjectionInverse;\n");
				s = replaceIfPresent(s, "gl_ModelViewMatrixTranspose",
					"transpose(iris_gbModelView)", decls, "uniform mat4 iris_gbModelView;\n");
				s = replaceIfPresent(s, "gl_ProjectionMatrixTranspose",
					"transpose(iris_gbProjection)", decls, "uniform mat4 iris_gbProjection;\n");
				s = replaceIfPresent(s, "gl_ModelViewMatrix", "iris_gbModelView", decls, "uniform mat4 iris_gbModelView;\n");
				s = replaceIfPresent(s, "gl_ProjectionMatrix", "iris_gbProjection", decls, "uniform mat4 iris_gbProjection;\n");
				s = replaceIfPresent(s, "gl_NormalMatrix", "iris_gbNormalMatrix", decls, "uniform mat3 iris_gbNormalMatrix;\n");
				if (vertex && s.contains("ftransform")) {
					s = FTRANSFORM.matcher(s).replaceAll("(iris_gbModelViewProjection * vec4(iris_Position + iris_chunkOffset(), 1.0))");
					ensureDecl(decls, "uniform mat4 iris_gbModelViewProjection;\n");
				}
			}
		}

		if (vertex) {
			s = rewriteVertexInputs(s, gbuffers, entity, decls, out);
		}
		s = fixedFunction(s, vertex, gbuffers, entity, decls);
		StringBuilder fragInit = new StringBuilder();
		if (!vertex) {
			s = fragmentOutputs(s, out, decls, fragInit);
		}
		s = stripUniformInitializers(s);
		// Move global initializers into the pack's own main() BEFORE wrapping main() into iris_pack_main(),
		// so moved initializers always run AFTER mc_Entity / at_tangent / va* / iris_FragDepth setup.
		s = moveGlobalInitializers(s, out);
		if (fragInit.length() > 0) {
			s = prependIntoMain(s, fragInit.toString());
		}

		if (vertex && (gbuffers && flipZ || !out.vertexInit.isEmpty())) {
			Matcher main = MAIN_DECL.matcher(s);
			if (main.find()) {
				s = s.substring(0, main.start()) + "void iris_pack_main() {" + s.substring(main.end())
					+ "\nvoid main() { " + out.vertexInit + "iris_pack_main();"
					+ (gbuffers && flipZ ? " gl_Position.z = -gl_Position.z;" : "") + " }\n";
			}
		} else if (wrapFragDepth) {
			Matcher main = MAIN_DECL.matcher(s);
			if (main.find()) {
				s = s.substring(0, main.start()) + "void iris_pack_main() {" + s.substring(main.end())
					+ "\nvoid main() { iris_FragDepth = iris_fragCoordZ(); iris_pack_main(); gl_FragDepth = 1.0 - iris_FragDepth; }\n";
			}
		}

		String polyfills = polyfills(s);
		out.source = HEADER_PREFIX + decls + polyfills + s;
		return out;
	}

	private static void ensureDecl(final StringBuilder decls, final String decl) {
		for (String line : decl.split("\n")) {
			if (!line.isEmpty() && decls.indexOf(line) < 0) {
				decls.append(line).append('\n');
			}
		}
	}

	private static String prependIntoMain(final String s, final String code) {
		Matcher m = WRAPPER_MAIN_DECL.matcher(s);
		if (!m.find()) {
			m = MAIN_DECL.matcher(s);
			if (!m.find()) {
				return s;
			}
		}
		return s.substring(0, m.end()) + "\n" + code + s.substring(m.end());
	}

	/** Strips desktop-only layout qualifiers and empty/builtin redeclarations left behind. */
	static String stripDesktopLayouts(String s) {
		if (!s.contains("layout")) {
			return s;
		}
		Matcher m = LAYOUT_BLOCK.matcher(s);
		StringBuilder out = new StringBuilder(s.length());
		int from = 0;
		while (m.find()) {
			String inner = m.group(1);
			String cleaned = DESKTOP_LAYOUT_QUAL.matcher(inner).replaceAll("");
			// Rebuild remaining comma-separated qualifiers
			StringBuilder kept = new StringBuilder();
			for (String part : cleaned.split(",")) {
				String t = part.trim();
				if (!t.isEmpty()) {
					if (kept.length() > 0) {
						kept.append(", ");
					}
					kept.append(t);
				}
			}
			out.append(s, from, m.start());
			if (kept.length() > 0) {
				out.append("layout(").append(kept).append(')');
			}
			from = m.end();
		}
		out.append(s, from, s.length());
		return EMPTY_IN_OUT_STMT.matcher(out.toString()).replaceAll("");
	}

	/** Lowers GLSL 4.00 double/dvecN/dmatN types and lf literals to float/vecN/matN. */
	static String convertDoubleTypes(String s) {
		if (s.contains("lf") || s.contains("LF") || s.contains("lF") || s.contains("Lf")) {
			s = DOUBLE_LITERAL_SUFFIX.matcher(s).replaceAll("$1");
		}
		if (!s.contains("double") && !s.contains("dvec") && !s.contains("dmat")) {
			return s;
		}
		boolean doubleIsVar = s.contains("double") && DOUBLE_VAR_DECL.matcher(s).find();
		Matcher m = DOUBLE_TYPE_USE.matcher(s);
		StringBuilder out = new StringBuilder(s.length());
		int from = 0;
		while (m.find()) {
			String tok = m.group(1);
			String repl;
			if (tok.equals("double")) {
				repl = doubleIsVar ? "double_" : "float";
			} else {
				repl = tok.substring(1); // dvec3 -> vec3, dmat4x3 -> mat4x3
			}
			out.append(s, from, m.start()).append(repl);
			from = m.end();
		}
		return out.append(s, from, s.length()).toString();
	}

	/** Flattens desktop in/out interface blocks and drops gl_PerVertex redeclarations. */
	static String flattenInterfaceBlocks(final String s) {
		if (!s.contains("{")) {
			return s;
		}
		Matcher m = INTERFACE_BLOCK.matcher(s);
		if (!m.find()) {
			return s;
		}
		// Only replace global-scope interface blocks (not inside functions)
		List<int[]> spans = new ArrayList<>();
		List<String> replacements = new ArrayList<>();
		Map<String, String> memberRenames = new LinkedHashMap<>();
		m.reset();
		while (m.find()) {
			if (braceDepthAt(s, m.start()) != 0) {
				continue;
			}
			String blockQuals = m.group(1) == null ? "" : m.group(1);
			String dir = m.group(2);
			String blockName = m.group(3);
			String body = m.group(4);
			String inst = m.group(5);
			spans.add(new int[] {m.start(), m.end()});
			if (blockName.equals("gl_PerVertex")) {
				replacements.add("");
				continue;
			}
			StringBuilder flat = new StringBuilder();
			for (String rawStmt : body.split(";")) {
				String stmt = rawStmt.trim();
				if (stmt.isEmpty()) {
					continue;
				}
				Matcher mm = MEMBER_DECL.matcher(stmt + ";");
				if (!mm.matches()) {
					continue;
				}
				String mQuals = mm.group(1) == null ? "" : mm.group(1);
				String mType = mm.group(2);
				for (String item : splitTopLevel(mm.group(3), ',')) {
					String decl = item.trim();
					int br = decl.indexOf('[');
					String memberName = (br >= 0 ? decl.substring(0, br) : decl).trim();
					String arr = br >= 0 ? decl.substring(br) : "";
					if (memberName.isEmpty()) {
						continue;
					}
					String outName = inst != null ? "iris_blk_" + blockName + "_" + memberName : memberName;
					String combinedQuals = mergeQuals(blockQuals, mQuals);
					flat.append(combinedQuals).append(dir).append(' ').append(mType).append(' ').append(outName).append(arr).append(";\n");
					if (inst != null) {
						memberRenames.put(inst + "." + memberName, outName);
					}
				}
			}
			replacements.add(flat.toString());
		}
		if (spans.isEmpty()) {
			return s;
		}
		StringBuilder rebuilt = new StringBuilder(s.length());
		int from = 0;
		for (int i = 0; i < spans.size(); i++) {
			rebuilt.append(s, from, spans.get(i)[0]).append(replacements.get(i));
			from = spans.get(i)[1];
		}
		rebuilt.append(s, from, s.length());
		String res = rebuilt.toString();
		for (Map.Entry<String, String> e : memberRenames.entrySet()) {
			int dot = e.getKey().indexOf('.');
			String inst = e.getKey().substring(0, dot);
			String mem = e.getKey().substring(dot + 1);
			res = Pattern.compile("\\b" + Pattern.quote(inst) + "\\s*\\.\\s*" + Pattern.quote(mem) + "\\b")
				.matcher(res).replaceAll(e.getValue());
		}
		return res;
	}

	private static String mergeQuals(final String a, final String b) {
		if (a.isEmpty()) {
			return b;
		}
		if (b.isEmpty()) {
			return a;
		}
		StringBuilder out = new StringBuilder(a);
		for (String q : b.trim().split("\\s+")) {
			if (!q.isEmpty() && !a.contains(q)) {
				out.append(q).append(' ');
			}
		}
		return out.toString();
	}

	private static int braceDepthAt(final String s, final int pos) {
		int depth = 0;
		for (int i = 0; i < pos; i++) {
			char c = s.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
			}
		}
		return depth;
	}

	/** Converts C-style `{ ... }` aggregate initializers and unsized `type[]` arrays to ES 3.00 syntax. */
	static String convertArraysAndBraces(String s) {
		// 1. C-style `{ ... }` initializers on declarations: `const float a[] = { 1.0, 2.0 };`
		if (s.contains("= {") || s.contains("={") || s.contains("=\n{") || s.contains("=\t{")) {
			Matcher m = BRACE_INIT_DECL.matcher(s);
			StringBuilder out = new StringBuilder(s.length() + 32);
			int from = 0;
			while (m.find(from)) {
				int openBrace = m.end() - 1;
				int closeBrace = matchBrace(s, openBrace);
				if (closeBrace < 0) {
					break;
				}
				String quals = m.group(1) == null ? "" : m.group(1);
				String type = m.group(2);
				String sizeBefore = m.group(3);
				String name = m.group(4);
				String sizeAfter = m.group(5);
				String body = s.substring(openBrace + 1, closeBrace);
				List<String> items = splitNonEmptyTopLevel(body, ',');
				StringBuilder convertedItems = new StringBuilder();
				for (int i = 0; i < items.size(); i++) {
					if (i > 0) {
						convertedItems.append(", ");
					}
					String it = items.get(i).trim();
					if (it.startsWith("{") && it.endsWith("}")) {
						convertedItems.append(type).append('(').append(it, 1, it.length() - 1).append(')');
					} else {
						convertedItems.append(it);
					}
				}
				String explicitSize = sizeBefore != null && !sizeBefore.trim().isEmpty() ? sizeBefore.trim()
					: sizeAfter != null && !sizeAfter.trim().isEmpty() ? sizeAfter.trim()
					: Integer.toString(items.size());
				boolean isArray = sizeBefore != null || sizeAfter != null;
				out.append(s, from, m.start()).append(quals).append(type).append(' ').append(name);
				if (isArray) {
					out.append('[').append(explicitSize).append("] = ")
						.append(type).append('[').append(explicitSize).append("](").append(convertedItems).append(')');
				} else {
					out.append(" = ").append(type).append('(').append(convertedItems).append(')');
				}
				from = closeBrace + 1;
			}
			out.append(s, from, s.length());
			s = out.toString();
		}
		// 2. Unsized array constructors `type[](a, b, c)` -> `type[3](a, b, c)`
		if (s.contains("[]")) {
			Matcher m = UNSIZED_CTOR.matcher(s);
			StringBuilder out = new StringBuilder(s.length() + 16);
			int from = 0;
			while (m.find(from)) {
				int open = m.end() - 1;
				int close = matchParen(s, open);
				if (close < 0) {
					break;
				}
				String type = m.group(1);
				String inner = s.substring(open + 1, close);
				int count = splitNonEmptyTopLevel(inner, ',').size();
				out.append(s, from, m.start()).append(type).append('[').append(count).append("](").append(inner).append(')');
				from = close + 1;
			}
			out.append(s, from, s.length());
			s = out.toString();
			// 3. Unsized array declarations initialized from sized constructors
			s = UNSIZED_DECL_AFTER.matcher(s).replaceAll("$1$2 $3[$4] = $2[$4]");
			s = UNSIZED_DECL_BEFORE.matcher(s).replaceAll("$1$2 $3[$4] = $2[$4]");
		}
		return s;
	}

	private static int matchBrace(final String s, final int open) {
		int level = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '{') {
				level++;
			} else if (c == '}') {
				if (--level == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static List<String> splitNonEmptyTopLevel(final String s, final char sep) {
		List<String> out = new ArrayList<>();
		for (String p : splitTopLevel(s, sep)) {
			if (!p.trim().isEmpty()) {
				out.add(p);
			}
		}
		return out;
	}

	/** Adds `flat` to global-scope integer varyings (vertex `out`, fragment `in`) as ES 3.00 requires. */
	static String autoFlatIntVaryings(final String s, final boolean vertex) {
		String targetDir = vertex ? "out" : "in";
		StringBuilder out = new StringBuilder(s.length() + 32);
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
					out.append(s, stmtStart, i + 1);
					stmtStart = i + 1;
				}
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					out.append(s, stmtStart, i + 1);
					stmtStart = i + 1;
				}
			} else if (c == ';' && depth == 0 && parens == 0) {
				String stmt = s.substring(stmtStart, i + 1);
				String trimmed = stmt.trim();
				Matcher m = STAGE_VARYING_STMT.matcher(trimmed);
				if (m.matches() && m.group(3).equals(targetDir) && isIntVaryingType(m.group(4))
					&& !m.group(2).contains("flat")) {
					String layout = m.group(1) == null ? "" : m.group(1);
					String rest = trimmed.substring(layout.length());
					int lead = stmt.indexOf(trimmed);
					out.append(stmt, 0, Math.max(0, lead)).append(layout).append("flat ").append(rest);
				} else {
					out.append(stmt);
				}
				stmtStart = i + 1;
			}
		}
		return out.append(s, stmtStart, n).toString();
	}

	private static boolean isIntVaryingType(final String type) {
		return type.equals("int") || type.equals("uint") || type.startsWith("ivec") || type.startsWith("uvec");
	}

	/** Maps Iris 1.17+ Core Profile uniforms (when declared as uniforms) to iris_* uniforms/helpers. */
	private static String rewriteCoreProfileUniforms(String s, final boolean vertex, final boolean gbuffers,
		final boolean entity, final StringBuilder decls) {
		Matcher m = CORE_UNIFORM_DECL.matcher(s);
		Map<String, String> found = new LinkedHashMap<>();
		StringBuilder stripped = new StringBuilder(s.length());
		int from = 0;
		while (m.find()) {
			found.put(m.group(2), m.group(1));
			stripped.append(s, from, m.start());
			from = m.end();
		}
		if (found.isEmpty()) {
			return s;
		}
		s = stripped.append(s, from, s.length()).toString();
		for (String name : found.keySet()) {
			switch (name) {
				case "chunkOffset":
					if (gbuffers && vertex && !entity) {
						s = replaceIdent(s, "chunkOffset", "iris_chunkOffset()");
					} else {
						s = replaceIdent(s, "chunkOffset", "vec3(0.0)");
					}
					break;
				case "modelViewMatrix":
					if (gbuffers && entity) {
						s = replaceIfPresent(s, "modelViewMatrix", "iris_DynModelViewMat", decls, DYNAMIC_TRANSFORMS_UBO);
					} else if (gbuffers) {
						s = replaceIfPresent(s, "modelViewMatrix", "iris_gbModelView", decls, "uniform mat4 iris_gbModelView;\n");
					} else {
						s = replaceIfPresent(s, "modelViewMatrix", "iris_ModelViewMatrix", decls, "uniform mat4 iris_ModelViewMatrix;\n");
					}
					break;
				case "modelViewMatrixInverse":
					if (gbuffers && entity) {
						s = replaceIfPresent(s, "modelViewMatrixInverse", "inverse(iris_DynModelViewMat)", decls, DYNAMIC_TRANSFORMS_UBO);
					} else if (gbuffers) {
						s = replaceIfPresent(s, "modelViewMatrixInverse", "iris_gbModelViewInverse", decls, "uniform mat4 iris_gbModelViewInverse;\n");
					} else {
						s = replaceIfPresent(s, "modelViewMatrixInverse", "iris_ModelViewMatrixInverse", decls, "uniform mat4 iris_ModelViewMatrixInverse;\n");
					}
					break;
				case "projectionMatrix":
					if (gbuffers) {
						s = replaceIfPresent(s, "projectionMatrix", "iris_gbProjection", decls, "uniform mat4 iris_gbProjection;\n");
					} else {
						s = replaceIfPresent(s, "projectionMatrix", "iris_ProjectionMatrix", decls, "uniform mat4 iris_ProjectionMatrix;\n");
					}
					break;
				case "projectionMatrixInverse":
					if (gbuffers) {
						s = replaceIfPresent(s, "projectionMatrixInverse", "iris_gbProjectionInverse", decls, "uniform mat4 iris_gbProjectionInverse;\n");
					} else {
						s = replaceIfPresent(s, "projectionMatrixInverse", "iris_ProjectionMatrixInverse", decls, "uniform mat4 iris_ProjectionMatrixInverse;\n");
					}
					break;
				case "modelViewProjectionMatrix":
					if (gbuffers && entity) {
						s = replaceIfPresent(s, "modelViewProjectionMatrix", "(iris_gbProjection * iris_DynModelViewMat)",
							decls, DYNAMIC_TRANSFORMS_UBO + "uniform mat4 iris_gbProjection;\n");
					} else if (gbuffers) {
						s = replaceIfPresent(s, "modelViewProjectionMatrix", "iris_gbModelViewProjection", decls, "uniform mat4 iris_gbModelViewProjection;\n");
					} else {
						s = replaceIfPresent(s, "modelViewProjectionMatrix", "(iris_ProjectionMatrix * iris_ModelViewMatrix)",
							decls, "uniform mat4 iris_ProjectionMatrix;\nuniform mat4 iris_ModelViewMatrix;\n");
					}
					break;
				case "normalMatrix":
					if (gbuffers && entity) {
						s = replaceIfPresent(s, "normalMatrix", "iris_dynNormalMatrix()", decls, DYNAMIC_TRANSFORMS_UBO + DYN_NORMAL_MATRIX_FN);
					} else if (gbuffers) {
						s = replaceIfPresent(s, "normalMatrix", "iris_gbNormalMatrix", decls, "uniform mat3 iris_gbNormalMatrix;\n");
					} else {
						s = replaceIfPresent(s, "normalMatrix", "iris_NormalMatrix", decls, "uniform mat3 iris_NormalMatrix;\n");
					}
					break;
				case "textureMatrix":
					if (gbuffers && entity) {
						s = replaceIfPresent(s, "textureMatrix", "iris_DynTextureMat", decls, DYNAMIC_TRANSFORMS_UBO);
					} else {
						s = replaceIfPresent(s, "textureMatrix", "iris_TextureMatrix0", decls, "uniform mat4 iris_TextureMatrix0;\n");
					}
					break;
				case "colorModulator":
					if (gbuffers && entity) {
						s = replaceIfPresent(s, "colorModulator", "iris_DynColorModulator", decls, DYNAMIC_TRANSFORMS_UBO);
					} else {
						s = replaceIfPresent(s, "colorModulator", "iris_ColorModulator", decls, "uniform vec4 iris_ColorModulator;\n");
					}
					break;
				case "iris_ColorModulator":
					if (gbuffers && entity) {
						s = replaceIfPresent(s, "iris_ColorModulator", "iris_DynColorModulator", decls, DYNAMIC_TRANSFORMS_UBO);
					} else {
						ensureDecl(decls, "uniform vec4 iris_ColorModulator;\n");
					}
					break;
				default:
					break;
			}
		}
		return s;
	}

	/**
	 * Rewrites all pack-declared vertex {@code in} attributes (supporting {@code layout(...)},
	 * precision qualifiers, and comma-separated lists): known Iris core/OptiFine attributes
	 * ({@code vaPosition}, {@code vaColor}, {@code vaUV0}, {@code vaUV1}, {@code vaUV2},
	 * {@code vaNormal}, {@code mc_Entity}, {@code at_tangent}, {@code mc_midTexCoord},
	 * {@code at_midBlock}, {@code at_velocity}) become globals initialized in {@link Output#vertexInit},
	 * and any unknown unbound {@code in} is synthesized as a zero-initialized global so it can
	 * NEVER collide with VAO attribute locations 0..3.
	 */
	private static String rewriteVertexInputs(final String s, final boolean gbuffers, final boolean entity,
		final StringBuilder decls, final Output out) {
		StringBuilder init = new StringBuilder(out.vertexInit);
		StringBuilder rebuilt = new StringBuilder(s.length() + 64);
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
					rebuilt.append(s, stmtStart, i + 1);
					stmtStart = i + 1;
				}
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					rebuilt.append(s, stmtStart, i + 1);
					stmtStart = i + 1;
				}
			} else if (c == ';' && depth == 0 && parens == 0) {
				String stmt = s.substring(stmtStart, i + 1);
				String trimmed = stmt.trim();
				Matcher m = STAGE_VARYING_STMT.matcher(trimmed);
				if (m.matches() && m.group(3).equals("in")) {
					String type = m.group(4);
					String declList = m.group(5);
					int lead = stmt.indexOf(trimmed);
					String indent = lead > 0 ? stmt.substring(0, lead) : "";
					StringBuilder replacement = new StringBuilder();
					for (String item : splitTopLevel(declList, ',')) {
						String decl = item.trim();
						if (decl.isEmpty()) {
							continue;
						}
						int eq = decl.indexOf('=');
						if (eq >= 0) {
							decl = decl.substring(0, eq).trim();
						}
						int br = decl.indexOf('[');
						String name = (br >= 0 ? decl.substring(0, br) : decl).trim();
						String arr = br >= 0 ? decl.substring(br) : "";
						if (name.startsWith("iris_")) {
							replacement.append(indent).append("in ").append(type).append(' ').append(name).append(arr).append(";\n");
							continue;
						}
						String initExpr = arr.isEmpty() ? vertexAttrInitExpr(name, type, gbuffers, entity, decls) : null;
						if (initExpr != null) {
							replacement.append(indent).append(type).append(' ').append(name).append(";\n");
							init.append(name).append(" = ").append(initExpr).append("; ");
						} else if (arr.isEmpty()) {
							replacement.append(indent).append(type).append(' ').append(name).append(" = ").append(zeroInit(type)).append(";\n");
						} else {
							replacement.append(indent).append(type).append(' ').append(name).append(arr).append(";\n");
						}
					}
					rebuilt.append(replacement);
				} else {
					rebuilt.append(stmt);
				}
				stmtStart = i + 1;
			}
		}
		rebuilt.append(s, stmtStart, n);
		out.vertexInit = init.toString();
		return rebuilt.toString();
	}

	private static String vertexAttrInitExpr(final String name, final String type, final boolean gbuffers,
		final boolean entity, final StringBuilder decls) {
		switch (name) {
			case "vaPosition":
				ensureDecl(decls, "in vec3 iris_Position;\n");
				if (gbuffers && entity) {
					ensureDecl(decls, DYNAMIC_TRANSFORMS_UBO);
					return convertVec3(type, "(iris_Position + iris_DynModelOffset)", "1.0");
				}
				return convertVec3(type, "iris_Position", "1.0");
			case "vaColor":
				ensureDecl(decls, "in vec4 iris_Color;\n");
				return convertVec4(type, "iris_Color");
			case "vaUV0":
				ensureDecl(decls, "in vec2 iris_UV0;\n");
				return convertVec2(type, "iris_UV0");
			case "vaUV1":
				return convertIvec2(type, "ivec2(0, 10)");
			case "vaUV2":
				if (gbuffers) {
					ensureDecl(decls, "in ivec2 iris_UV2i;\n");
					return convertIvec2(type, entity ? "iris_UV2i" : "(iris_UV2i & 255)");
				}
				ensureDecl(decls, "in vec2 iris_UV2;\n");
				return convertVec2(type, "iris_UV2");
			case "vaNormal":
				if (gbuffers && !entity) {
					return convertVec3(type, "iris_faceNormal()", "0.0");
				}
				ensureDecl(decls, "in vec3 iris_Normal;\n");
				return convertVec3(type, "iris_Normal", "0.0");
			case "mc_Entity":
				if (gbuffers && !entity) {
					return convertScalarId(type, "iris_blockId()");
				}
				return convertScalarId(type, "-1.0");
			case "at_tangent":
				if (gbuffers) {
					return convertVec4(type, entity ? "iris_entityTangent()" : "iris_tangent()");
				}
				return convertVec4(type, "vec4(1.0, 0.0, 0.0, 1.0)");
			case "mc_midTexCoord":
				ensureDecl(decls, "in vec2 iris_UV0;\n");
				return convertVec2(type, "iris_UV0");
			case "at_midBlock":
			case "at_velocity":
				return zeroInit(type);
			default:
				return null;
		}
	}

	private static String convertVec2(final String type, final String expr) {
		switch (type) {
			case "vec2": return expr;
			case "vec3": return "vec3(" + expr + ", 0.0)";
			case "vec4": return "vec4(" + expr + ", 0.0, 1.0)";
			case "float": return "(" + expr + ").x";
			case "ivec2": return "ivec2(" + expr + ")";
			case "ivec3": return "ivec3(ivec2(" + expr + "), 0)";
			case "ivec4": return "ivec4(ivec2(" + expr + "), 0, 1)";
			case "uvec2": return "uvec2(" + expr + ")";
			default: return type + "(" + expr + ")";
		}
	}

	private static String convertIvec2(final String type, final String expr) {
		switch (type) {
			case "ivec2": return expr;
			case "ivec3": return "ivec3(" + expr + ", 0)";
			case "ivec4": return "ivec4(" + expr + ", 0, 1)";
			case "uvec2": return "uvec2(" + expr + ")";
			case "vec2": return "vec2(" + expr + ")";
			case "vec3": return "vec3(vec2(" + expr + "), 0.0)";
			case "vec4": return "vec4(vec2(" + expr + "), 0.0, 1.0)";
			case "int": return "(" + expr + ").x";
			case "float": return "float((" + expr + ").x)";
			default: return type + "(" + expr + ")";
		}
	}

	private static String convertVec3(final String type, final String expr, final String w) {
		switch (type) {
			case "vec3": return expr;
			case "vec4": return "vec4(" + expr + ", " + w + ")";
			case "vec2": return "(" + expr + ").xy";
			case "float": return "(" + expr + ").x";
			case "ivec3": return "ivec3(" + expr + ")";
			case "ivec4": return "ivec4(ivec3(" + expr + "), " + (w.equals("1.0") ? "1" : "0") + ")";
			default: return type + "(" + expr + ")";
		}
	}

	private static String convertVec4(final String type, final String expr) {
		switch (type) {
			case "vec4": return expr;
			case "vec3": return "(" + expr + ").xyz";
			case "vec2": return "(" + expr + ").xy";
			case "float": return "(" + expr + ").x";
			case "ivec4": return "ivec4(" + expr + ")";
			default: return type + "(" + expr + ")";
		}
	}

	private static String convertScalarId(final String type, final String floatExpr) {
		switch (type) {
			case "float": return floatExpr;
			case "vec2": return "vec2(" + floatExpr + ", 0.0)";
			case "vec3": return "vec3(" + floatExpr + ", 0.0, 0.0)";
			case "vec4": return "vec4(" + floatExpr + ", 0.0, 0.0, 0.0)";
			case "int": return "int(" + floatExpr + ")";
			case "uint": return "uint(max(0.0, " + floatExpr + "))";
			case "ivec2": return "ivec2(int(" + floatExpr + "), 0)";
			case "ivec3": return "ivec3(int(" + floatExpr + "), 0, 0)";
			case "ivec4": return "ivec4(int(" + floatExpr + "), 0, 0, 0)";
			case "uvec2": return "uvec2(uint(max(0.0, " + floatExpr + ")), 0u)";
			case "uvec3": return "uvec3(uint(max(0.0, " + floatExpr + ")), 0u, 0u)";
			case "uvec4": return "uvec4(uint(max(0.0, " + floatExpr + ")), 0u, 0u, 0u)";
			default: return type + "(" + floatExpr + ")";
		}
	}

	private static String zeroInit(final String type) {
		if (type.equals("float") || type.startsWith("vec") || type.startsWith("mat")) {
			return type + "(0.0)";
		}
		if (type.equals("uint") || type.startsWith("uvec")) {
			return type + "(0u)";
		}
		if (type.equals("bool") || type.startsWith("bvec")) {
			return type + "(false)";
		}
		return type + "(0)";
	}

	/**
	 * Image load/store (voxelization in Solas and others) has no WebGL2 equivalent: image uniforms
	 * are dropped, stores become no-ops, loads read zero and sizes are 1.
	 */
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
			int close = open >= 0 ? matchParen(s, open) : -1;
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

	/** Line number (1-based) in {@link Output#source} where the translated body starts. */
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
		// Single-pass rewrite for all reserved ES 3.00 identifiers
		Matcher rm = RENAMED_IDENT_PATTERN.matcher(s);
		if (rm.find()) {
			StringBuilder sb = new StringBuilder(s.length() + 32);
			int from = 0;
			do {
				sb.append(s, from, rm.start()).append(rm.group(1)).append('_');
				from = rm.end();
			} while (rm.find());
			s = sb.append(s, from, s.length()).toString();
		}
		// Rename `uniform sampler* texture;` AND non-sampler `texture` variables on ALL GLSL versions
		if (s.contains("texture")) {
			if (SAMPLER_TEXTURE_DECL.matcher(s).find()) {
				s = BARE_TEXTURE_IDENT.matcher(s).replaceAll("gtexture");
				out.notes.add("renamed sampler 'texture' to 'gtexture'");
			} else if (VAR_TEXTURE_DECL.matcher(s).find()) {
				s = BARE_TEXTURE_IDENT.matcher(s).replaceAll("iris_var_texture");
				out.notes.add("renamed variable 'texture' to 'iris_var_texture'");
			}
		}
		if (version < 130) {
			if (s.contains("smooth") && SMOOTH_VAR_DECL.matcher(s).find()) {
				s = replaceIdent(s, "smooth", "smooth_");
			}
			if (s.contains("flat") && FLAT_VAR_DECL.matcher(s).find()) {
				s = replaceIdent(s, "flat", "flat_");
			}
			for (int i = 0; i < NEW_BUILTINS_FOR_OLD_VERSIONS.length; i++) {
				String fn = NEW_BUILTINS_FOR_OLD_VERSIONS[i];
				if (s.contains(fn) && NEW_BUILTIN_DEF_PATTERNS[i].matcher(s).find()) {
					s = NEW_BUILTIN_CALL_PATTERNS[i].matcher(s).replaceAll("iris_user_" + fn + "$1");
					out.notes.add("renamed user function " + fn);
				}
			}
		}
		return s;
	}

	/** True if the source defines (not just calls) a function with this name. */
	static boolean definesFunction(final String s, final String name) {
		return Pattern.compile("\\b(?:void|float|int|uint|bool|[iu]?vec[234]|mat[234](?:x[234])?)\\s+" + name + "\\s*\\(")
			.matcher(s).find();
	}

	static String replaceIdent(final String s, final String from, final String to) {
		return identPattern(from).matcher(s).replaceAll(to);
	}

	private static String qualifiers(String s, final boolean vertex, final int version) {
		if (vertex && s.contains("attribute")) {
			s = replaceIdent(s, "attribute", "in");
		}
		if (s.contains("varying")) {
			s = replaceIdent(s, "varying", vertex ? "out" : "in");
		}
		if (s.contains("noperspective")) {
			s = replaceIdent(s, "noperspective", "");
		}
		return s;
	}

	private static String textureFunctions(String s, final int version) {
		// Single-pass rewrite for simple legacy texture and GLSL 4.50 derivative functions
		Matcher sm = SIMPLE_FUNC_PATTERN.matcher(s);
		if (sm.find()) {
			StringBuilder sb = new StringBuilder(s.length());
			int from = 0;
			do {
				sb.append(s, from, sm.start()).append(SIMPLE_FUNC_REWRITES.get(sm.group(1)));
				from = sm.end();
			} while (sm.find());
			s = sb.append(s, from, s.length()).toString();
		}
		// shadow2DLod(s, coord, lod) -> vec4(texture(s, coord)) (ES 3.00 has no textureLod(sampler2DShadow))
		if (s.contains("shadow2DLod")) {
			s = rewriteShadowLodCalls(s, "shadow2DLod", "vec4(texture(", "))");
		}
		if (s.contains("shadow2DProjLod")) {
			s = rewriteShadowLodCalls(s, "shadow2DProjLod", "vec4(textureProj(", "))");
		}
		if (s.contains("shadow2DProj")) {
			s = wrapCalls(s, "shadow2DProj", "vec4(textureProj(", "))");
		}
		if (s.contains("shadow2D")) {
			s = wrapCalls(s, "shadow2D", "vec4(texture(", "))");
		}
		// Rewrite textureLod/textureGrad/textureLodOffset on sampler*Shadow uniforms to ES 3.00 texture/textureOffset
		if (s.contains("Shadow")) {
			Set<String> shadowSamplers = new HashSet<>();
			Matcher shm = SHADOW_SAMPLER_DECL.matcher(s);
			while (shm.find()) {
				shadowSamplers.add(shm.group(1));
			}
			if (!shadowSamplers.isEmpty()) {
				if (s.contains("textureLodOffset")) {
					s = stripShadowLodForSamplers(s, "textureLodOffset", "textureOffset", shadowSamplers, true);
				}
				if (s.contains("textureLod")) {
					s = stripShadowLodForSamplers(s, "textureLod", "texture", shadowSamplers, false);
				}
				if (s.contains("textureGrad")) {
					s = stripShadowLodForSamplers(s, "textureGrad", "texture", shadowSamplers, false);
				}
			}
		}
		return s;
	}

	/** Rewrites `shadow2DLod(s, coord, lod)` dropping the 3rd (lod) argument, recursively handling nesting. */
	private static String rewriteShadowLodCalls(final String s, final String name, final String prefix, final String suffix) {
		Pattern p = callPattern(name);
		Matcher m = p.matcher(s);
		if (!m.find()) {
			return s;
		}
		StringBuilder out = new StringBuilder(s.length());
		int from = 0;
		m.reset();
		while (m.find(from)) {
			int open = m.end() - 1;
			int close = matchParen(s, open);
			if (close < 0) {
				break;
			}
			String inner = rewriteShadowLodCalls(s.substring(open + 1, close), name, prefix, suffix);
			List<String> args = splitTopLevel(inner, ',');
			String keptArgs = args.size() >= 2 ? args.get(0).trim() + ", " + args.get(1).trim() : inner;
			out.append(s, from, m.start()).append(prefix).append(keptArgs).append(suffix);
			from = close + 1;
		}
		return out.append(s, from, s.length()).toString();
	}

	private static String stripShadowLodForSamplers(final String s, final String callName, final String targetName,
		final Set<String> shadowSamplers, final boolean keepFourthArg) {
		Pattern p = callPattern(callName);
		Matcher m = p.matcher(s);
		if (!m.find()) {
			return s;
		}
		StringBuilder out = new StringBuilder(s.length());
		int from = 0;
		m.reset();
		while (m.find(from)) {
			int open = m.end() - 1;
			int close = matchParen(s, open);
			if (close < 0) {
				break;
			}
			String inner = stripShadowLodForSamplers(s.substring(open + 1, close), callName, targetName, shadowSamplers, keepFourthArg);
			List<String> args = splitTopLevel(inner, ',');
			if (args.size() >= 3 && shadowSamplers.contains(args.get(0).trim())) {
				out.append(s, from, m.start()).append(targetName).append('(')
					.append(args.get(0).trim()).append(", ").append(args.get(1).trim());
				if (keepFourthArg && args.size() >= 4) {
					out.append(", ").append(args.get(3).trim());
				}
				out.append(')');
			} else {
				out.append(s, from, open + 1).append(inner).append(')');
			}
			from = close + 1;
		}
		return out.append(s, from, s.length()).toString();
	}

	/** Replaces every call {@code name(args)} with {@code prefix + args + suffix} (balanced & nested parens). */
	static String wrapCalls(final String s, final String name, final String prefix, final String suffix) {
		Pattern p = callPattern(name);
		Matcher m = p.matcher(s);
		if (!m.find()) {
			return s;
		}
		StringBuilder out = new StringBuilder(s.length() + 16);
		int from = 0;
		m.reset();
		while (m.find(from)) {
			int open = m.end() - 1;
			int close = matchParen(s, open);
			if (close < 0) {
				break;
			}
			String inner = wrapCalls(s.substring(open + 1, close), name, prefix, suffix);
			out.append(s, from, m.start()).append(prefix).append(inner).append(suffix);
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

	private static int matchBracket(final String s, final int open) {
		int level = 0;
		for (int i = open; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '[') {
				level++;
			} else if (c == ']') {
				level--;
				if (level == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String fixedFunction(String s, final boolean vertex, final boolean gbuffers, final boolean entity, final StringBuilder decls) {
		if (vertex) {
			if (s.contains("ftransform")) {
				s = FTRANSFORM.matcher(s).replaceAll("(gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex)");
			}
			s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixInverseTranspose",
				"transpose(gl_ModelViewMatrixInverse * gl_ProjectionMatrixInverse)");
			s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixInverse",
				"(gl_ModelViewMatrixInverse * gl_ProjectionMatrixInverse)");
			s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixTranspose",
				"transpose(gl_ProjectionMatrix * gl_ModelViewMatrix)");
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
			s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixInverseTranspose",
				"transpose(gl_ModelViewMatrixInverse * gl_ProjectionMatrixInverse)");
			s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixInverse",
				"(gl_ModelViewMatrixInverse * gl_ProjectionMatrixInverse)");
			s = replaceIfPresent(s, "gl_ModelViewProjectionMatrixTranspose",
				"transpose(gl_ProjectionMatrix * gl_ModelViewMatrix)");
			s = replaceIfPresent(s, "gl_ModelViewProjectionMatrix", "(gl_ProjectionMatrix * gl_ModelViewMatrix)");
			s = replaceIfPresent(s, "gl_Color", "iris_FrontColor", decls, "in vec4 iris_FrontColor;\n");
			s = replaceIfPresent(s, "gl_FogFragCoord", "iris_FogFragCoord", decls, "in float iris_FogFragCoord;\n");
			s = texCoordArray(s, decls, "in");
		}
		s = replaceIfPresent(s, "gl_ModelViewMatrixInverseTranspose",
			"transpose(iris_ModelViewMatrixInverse)", decls, "uniform mat4 iris_ModelViewMatrixInverse;\n");
		s = replaceIfPresent(s, "gl_ProjectionMatrixInverseTranspose",
			"transpose(iris_ProjectionMatrixInverse)", decls, "uniform mat4 iris_ProjectionMatrixInverse;\n");
		s = replaceIfPresent(s, "gl_ModelViewMatrixInverse",
			"iris_ModelViewMatrixInverse", decls, "uniform mat4 iris_ModelViewMatrixInverse;\n");
		s = replaceIfPresent(s, "gl_ProjectionMatrixInverse",
			"iris_ProjectionMatrixInverse", decls, "uniform mat4 iris_ProjectionMatrixInverse;\n");
		s = replaceIfPresent(s, "gl_ModelViewMatrixTranspose",
			"transpose(iris_ModelViewMatrix)", decls, "uniform mat4 iris_ModelViewMatrix;\n");
		s = replaceIfPresent(s, "gl_ProjectionMatrixTranspose",
			"transpose(iris_ProjectionMatrix)", decls, "uniform mat4 iris_ProjectionMatrix;\n");
		s = replaceIfPresent(s, "gl_ModelViewMatrix", "iris_ModelViewMatrix", decls, "uniform mat4 iris_ModelViewMatrix;\n");
		s = replaceIfPresent(s, "gl_ProjectionMatrix", "iris_ProjectionMatrix", decls, "uniform mat4 iris_ProjectionMatrix;\n");
		s = replaceIfPresent(s, "gl_NormalMatrix", "iris_NormalMatrix", decls, "uniform mat3 iris_NormalMatrix;\n");

		if (s.contains("gl_TextureMatrix")) {
			final boolean dynTex0 = gbuffers && entity;
			final String texMat0 = dynTex0 ? "iris_DynTextureMat" : "iris_TextureMatrix0";
			final String texMat0Decl = dynTex0 ? DYNAMIC_TRANSFORMS_UBO : "uniform mat4 iris_TextureMatrix0;\n";
			// Constant gl_TextureMatrix[0] -> iris_DynTextureMat (entity) / iris_TextureMatrix0, [1] & [2] -> iris_TextureMatrix1, [3+] -> mat4(1.0)
			Matcher tm = TEXTURE_MATRIX_CONST.matcher(s);
			StringBuilder out = new StringBuilder(s.length());
			int from = 0;
			boolean need0 = false;
			boolean need1 = false;
			while (tm.find()) {
				int idx = Integer.parseInt(tm.group(1));
				String repl;
				if (idx == 0) {
					repl = texMat0;
					need0 = true;
				} else if (idx == 1 || idx == 2) {
					repl = "iris_TextureMatrix1";
					need1 = true;
				} else {
					repl = "mat4(1.0)";
				}
				out.append(s, from, tm.start()).append(repl);
				from = tm.end();
			}
			s = out.append(s, from, s.length()).toString();
			if (need0) {
				ensureDecl(decls, texMat0Decl);
			}
			if (need1) {
				ensureDecl(decls, "uniform mat4 iris_TextureMatrix1;\n");
			}
			// Dynamic gl_TextureMatrix[expr] -> iris_textureMatrix(expr)
			if (s.contains("gl_TextureMatrix")) {
				Matcher dyn = TEXTURE_MATRIX_DYN.matcher(s);
				StringBuilder dout = new StringBuilder(s.length());
				int dfrom = 0;
				boolean anyDyn = false;
				while (dyn.find(dfrom)) {
					int open = dyn.end() - 1;
					int close = matchBracket(s, open);
					if (close < 0) {
						break;
					}
					anyDyn = true;
					dout.append(s, dfrom, dyn.start()).append("iris_textureMatrix(")
						.append(s, open + 1, close).append(')');
					dfrom = close + 1;
				}
				if (anyDyn) {
					s = dout.append(s, dfrom, s.length()).toString();
					ensureDecl(decls, texMat0Decl + "uniform mat4 iris_TextureMatrix1;\n");
					ensureDecl(decls,
						"mat4 iris_textureMatrix(int i) { return i == 0 ? " + texMat0 + " : (i == 1 || i == 2) ? iris_TextureMatrix1 : mat4(1.0); }\n");
				}
			}
		}
		if (s.contains("gl_Fog")) {
			s = s.replace("gl_Fog.color", "iris_FogColor").replace("gl_Fog.start", "iris_FogStart")
				.replace("gl_Fog.end", "iris_FogEnd").replace("gl_Fog.density", "iris_FogDensity").replace("gl_Fog.scale", "iris_FogScale");
			ensureDecl(decls, "uniform vec4 iris_FogColor;\nuniform float iris_FogStart;\nuniform float iris_FogEnd;\n"
				+ "uniform float iris_FogDensity;\nuniform float iris_FogScale;\n");
		}
		return s;
	}

	private static String texCoordArray(final String s, final StringBuilder decls, final String qualifier) {
		if (!s.contains("gl_TexCoord")) {
			return s;
		}
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
		if (!s.contains(from) || !identPattern(from).matcher(s).find()) {
			return s;
		}
		ensureDecl(decls, decl);
		return replaceIdent(s, from, to);
	}

	/**
	 * Assigns explicit {@code layout(location = N)} to every fragment output (handling
	 * {@code gl_FragColor}, {@code gl_FragData[n]}, comma-separated {@code out} lists, array outputs,
	 * and initialized outputs) and records both output names and GLSL base types on {@code out}.
	 */
	private static String fragmentOutputs(String s, final Output out, final StringBuilder decls, final StringBuilder fragInit) {
		TreeSet<Integer> used = new TreeSet<>();
		if (s.contains("gl_FragData")) {
			Matcher m = FRAG_DATA.matcher(s);
			while (m.find()) {
				used.add(Integer.parseInt(m.group(1)));
			}
		}
		if (s.contains("gl_FragColor") && FRAG_COLOR.matcher(s).find()) {
			s = replaceIdent(s, "gl_FragColor", "iris_FragData0");
			used.add(0);
		}
		if (!used.isEmpty()) {
			s = FRAG_DATA.matcher(s).replaceAll("iris_FragData$1");
			for (int i : used) {
				decls.append("layout(location = ").append(i).append(") out vec4 iris_FragData").append(i).append(";\n");
				out.outputsByLocation.put(i, "iris_FragData" + i);
				out.outputTypesByLocation.put(i, "vec4");
			}
			out.fragOutputs = used.last() + 1;
			return s;
		}

		// Collect explicitly assigned locations first so auto-assigned locations never collide with them
		Set<Integer> explicitLocs = new HashSet<>();
		scanExplicitOutLocations(s, explicitLocs);

		StringBuilder rebuilt = new StringBuilder(s.length() + 64);
		int depth = 0;
		int parens = 0;
		int stmtStart = 0;
		int nextLoc = 0;
		int maxLoc = -1;
		int n = s.length();
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			if (c == '(') {
				parens++;
			} else if (c == ')') {
				parens--;
			} else if (c == '{') {
				if (depth == 0) {
					rebuilt.append(s, stmtStart, i + 1);
					stmtStart = i + 1;
				}
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					rebuilt.append(s, stmtStart, i + 1);
					stmtStart = i + 1;
				}
			} else if (c == ';' && depth == 0 && parens == 0) {
				String stmt = s.substring(stmtStart, i + 1);
				String trimmed = stmt.trim();
				Matcher d = STAGE_VARYING_STMT.matcher(trimmed);
				if (d.matches() && d.group(3).equals("out")) {
					String layout = d.group(1) == null ? "" : d.group(1);
					String quals = d.group(2) == null ? "" : d.group(2);
					String type = d.group(4);
					String declList = d.group(5);
					Matcher locM = LOCATION_QUAL.matcher(layout);
					int baseLoc = locM.find() ? Integer.parseInt(locM.group(1)) : -1;
					int lead = stmt.indexOf(trimmed);
					String indent = lead > 0 ? stmt.substring(0, lead) : "";
					List<String> items = splitTopLevel(declList, ',');
					StringBuilder rep = new StringBuilder();
					for (int k = 0; k < items.size(); k++) {
						String item = items.get(k).trim();
						if (item.isEmpty()) {
							continue;
						}
						int eq = topLevelIndexOf(item, '=');
						String declPart = eq >= 0 ? item.substring(0, eq).trim() : item;
						String initExpr = eq >= 0 ? item.substring(eq + 1).trim() : null;
						Matcher am = ARRAY_SIZE.matcher(declPart);
						if (!am.find()) {
							continue;
						}
						String varName = am.group(1);
						if (varName.startsWith("gl_")) {
							continue;
						}
						int arrayLen = am.group(2) != null ? Math.max(1, Integer.parseInt(am.group(2))) : 1;
						String arraySuffix = am.group(2) != null ? "[" + am.group(2) + "]" : "";
						int loc;
						if (baseLoc >= 0 && k == 0) {
							loc = baseLoc;
						} else {
							while (explicitLocs.contains(nextLoc)) {
								nextLoc++;
							}
							loc = nextLoc;
							nextLoc += arrayLen;
						}
						for (int a = 0; a < arrayLen; a++) {
							int slot = loc + a;
							explicitLocs.add(slot);
							maxLoc = Math.max(maxLoc, slot);
							out.outputsByLocation.put(slot, arrayLen > 1 ? varName + "[" + a + "]" : varName);
							out.outputTypesByLocation.put(slot, type);
						}
						String locLayout = (baseLoc >= 0 && k == 0 && !layout.isEmpty()) ? layout : "layout(location = " + loc + ") ";
						rep.append(indent).append(locLayout).append(quals).append("out ").append(type).append(' ')
							.append(varName).append(arraySuffix).append(";\n");
						if (initExpr != null) {
							fragInit.append("    ").append(varName).append(" = ").append(initExpr).append(";\n");
						}
					}
					rebuilt.append(rep);
				} else {
					rebuilt.append(stmt);
				}
				stmtStart = i + 1;
			}
		}
		rebuilt.append(s, stmtStart, n);
		out.fragOutputs = maxLoc + 1;
		return rebuilt.toString();
	}

	private static void scanExplicitOutLocations(final String s, final Set<Integer> locs) {
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
					stmtStart = i + 1;
				}
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					stmtStart = i + 1;
				}
			} else if (c == ';' && depth == 0 && parens == 0) {
				String trimmed = s.substring(stmtStart, i + 1).trim();
				Matcher d = STAGE_VARYING_STMT.matcher(trimmed);
				if (d.matches() && d.group(3).equals("out") && d.group(1) != null) {
					Matcher locM = LOCATION_QUAL.matcher(d.group(1));
					if (locM.find()) {
						int base = Integer.parseInt(locM.group(1));
						Matcher am = ARRAY_SIZE.matcher(d.group(5).trim());
						int len = am.find() && am.group(2) != null ? Math.max(1, Integer.parseInt(am.group(2))) : 1;
						for (int k = 0; k < len; k++) {
							locs.add(base + k);
						}
					}
				}
				stmtStart = i + 1;
			}
		}
	}

	private static String stripUniformInitializers(final String s) {
		if (!s.contains("uniform")) {
			return s;
		}
		return UNIFORM_INIT.matcher(s).replaceAll("$1;");
	}

	/**
	 * Moves initializers of global, non-const variables into the start of the pack's main
	 * ({@code iris_pack_main} when wrapped, otherwise {@code main}). Desktop GLSL allows any
	 * expression at global scope; ES 3.00 requires a constant expression.
	 */
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
		// Prefer iris_pack_main / wrapper main so moved initializers run AFTER mc_Entity / at_tangent / iris_FragDepth setup
		Matcher main = WRAPPER_MAIN_DECL.matcher(result);
		if (!main.find()) {
			main = MAIN_DECL.matcher(result);
			if (!main.find()) {
				out.notes.add("no main(): global initializers left in place");
				return s;
			}
		}
		out.notes.add("moved global initializers into main()");
		return result.substring(0, main.end()) + "\n" + moved + result.substring(main.end());
	}

	/** One global statement: returns what stays global; appends assignments to {@code moved}. */
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
				String bare = ARRAY_SUFFIX.matcher(name).replaceAll("").trim();
				moved.append("    ").append(bare).append(" = ").append(declarator.substring(eq + 1).trim()).append(";\n");
			}
		}
		int lead = stmt.indexOf(trimmed);
		return stmt.substring(0, Math.max(lead, 0)) + typeText + " " + global + ";";
	}

	private static final Set<String> CONSTANT_IDENTIFIERS = new HashSet<>(Arrays.asList(
		"float", "int", "uint", "bool", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
		"bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4", "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3", "mat3x4",
		"mat4x2", "mat4x3", "mat4x4", "true", "false",
		"abs", "sign", "floor", "ceil", "fract", "mod", "min", "max", "clamp", "mix", "step", "smoothstep", "sqrt",
		"inversesqrt", "pow", "exp", "exp2", "log", "log2", "sin", "cos", "tan", "asin", "acos", "atan", "radians",
		"degrees", "length", "normalize", "dot", "cross"));

	/** True if an initializer only uses literals, constructors and built-in math (so ES accepts it globally). */
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
			if (c == '(' || c == '[' || c == '{') {
				level++;
			} else if (c == ')' || c == ']' || c == '}') {
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
			p.append("uint packUnorm4x8(vec4 v) { uvec4 u = uvec4(round(clamp(v, 0.0, 1.0) * 255.0)); return u.x | (u.y << 8u) | (u.z << 16u) | (u.w << 24u); }\n");
		}
		if (usesCall(s, "unpackUnorm4x8") && !definesFunction(s, "unpackUnorm4x8")) {
			p.append("vec4 unpackUnorm4x8(uint p) { return vec4(uvec4(p, p >> 8u, p >> 16u, p >> 24u) & 255u) / 255.0; }\n");
		}
		if (usesCall(s, "packSnorm4x8") && !definesFunction(s, "packSnorm4x8")) {
			p.append("uint packSnorm4x8(vec4 v) { uvec4 u = uvec4(ivec4(round(clamp(v, -1.0, 1.0) * 127.0)) & 255); return u.x | (u.y << 8u) | (u.z << 16u) | (u.w << 24u); }\n");
		}
		if (usesCall(s, "unpackSnorm4x8") && !definesFunction(s, "unpackSnorm4x8")) {
			p.append("vec4 unpackSnorm4x8(uint p) { ivec4 i = ivec4(uvec4(p << 24u, p << 16u, p << 8u, p)) >> 24; return clamp(vec4(i) / 127.0, -1.0, 1.0); }\n");
		}
		if (usesCall(s, "findMSB") && !definesFunction(s, "findMSB")) {
			p.append("int findMSB(uint v) { if (v == 0u) return -1; int r = 0;\n")
				.append("  if ((v & 0xFFFF0000u) != 0u) { v >>= 16u; r += 16; }\n")
				.append("  if ((v & 0x0000FF00u) != 0u) { v >>= 8u; r += 8; }\n")
				.append("  if ((v & 0x000000F0u) != 0u) { v >>= 4u; r += 4; }\n")
				.append("  if ((v & 0x0000000Cu) != 0u) { v >>= 2u; r += 2; }\n")
				.append("  if ((v & 0x00000002u) != 0u) { r += 1; } return r; }\n")
				.append("int findMSB(int v) { return findMSB(uint(v < 0 ? ~v : v)); }\n")
				.append("ivec2 findMSB(ivec2 v) { return ivec2(findMSB(v.x), findMSB(v.y)); }\n")
				.append("ivec3 findMSB(ivec3 v) { return ivec3(findMSB(v.x), findMSB(v.y), findMSB(v.z)); }\n")
				.append("ivec4 findMSB(ivec4 v) { return ivec4(findMSB(v.x), findMSB(v.y), findMSB(v.z), findMSB(v.w)); }\n")
				.append("ivec2 findMSB(uvec2 v) { return ivec2(findMSB(v.x), findMSB(v.y)); }\n")
				.append("ivec3 findMSB(uvec3 v) { return ivec3(findMSB(v.x), findMSB(v.y), findMSB(v.z)); }\n")
				.append("ivec4 findMSB(uvec4 v) { return ivec4(findMSB(v.x), findMSB(v.y), findMSB(v.z), findMSB(v.w)); }\n");
		}
		if (usesCall(s, "findLSB") && !definesFunction(s, "findLSB")) {
			p.append("int findLSB(uint v) { if (v == 0u) return -1; int r = 0;\n")
				.append("  if ((v & 0x0000FFFFu) == 0u) { v >>= 16u; r += 16; }\n")
				.append("  if ((v & 0x000000FFu) == 0u) { v >>= 8u; r += 8; }\n")
				.append("  if ((v & 0x0000000Fu) == 0u) { v >>= 4u; r += 4; }\n")
				.append("  if ((v & 0x00000003u) == 0u) { v >>= 2u; r += 2; }\n")
				.append("  if ((v & 0x00000001u) == 0u) { r += 1; } return r; }\n")
				.append("int findLSB(int v) { return findLSB(uint(v)); }\n")
				.append("ivec2 findLSB(ivec2 v) { return ivec2(findLSB(v.x), findLSB(v.y)); }\n")
				.append("ivec3 findLSB(ivec3 v) { return ivec3(findLSB(v.x), findLSB(v.y), findLSB(v.z)); }\n")
				.append("ivec4 findLSB(ivec4 v) { return ivec4(findLSB(v.x), findLSB(v.y), findLSB(v.z), findLSB(v.w)); }\n")
				.append("ivec2 findLSB(uvec2 v) { return ivec2(findLSB(v.x), findLSB(v.y)); }\n")
				.append("ivec3 findLSB(uvec3 v) { return ivec3(findLSB(v.x), findLSB(v.y), findLSB(v.z)); }\n")
				.append("ivec4 findLSB(uvec4 v) { return ivec4(findLSB(v.x), findLSB(v.y), findLSB(v.z), findLSB(v.w)); }\n");
		}
		if (usesCall(s, "bitfieldExtract") && !definesFunction(s, "bitfieldExtract")) {
			p.append("uint bitfieldExtract(uint v, int o, int b) { if (b <= 0 || o >= 32) return 0u; uint s = v >> uint(o); return b >= 32 ? s : (s & ((1u << uint(b)) - 1u)); }\n")
				.append("int bitfieldExtract(int v, int o, int b) { if (b <= 0 || o >= 32) return 0; uint u = bitfieldExtract(uint(v), o, b); int sh = 32 - min(b, 32); return (int(u) << sh) >> sh; }\n")
				.append("ivec2 bitfieldExtract(ivec2 v, int o, int b) { return ivec2(bitfieldExtract(v.x, o, b), bitfieldExtract(v.y, o, b)); }\n")
				.append("ivec3 bitfieldExtract(ivec3 v, int o, int b) { return ivec3(bitfieldExtract(v.x, o, b), bitfieldExtract(v.y, o, b), bitfieldExtract(v.z, o, b)); }\n")
				.append("ivec4 bitfieldExtract(ivec4 v, int o, int b) { return ivec4(bitfieldExtract(v.x, o, b), bitfieldExtract(v.y, o, b), bitfieldExtract(v.z, o, b), bitfieldExtract(v.w, o, b)); }\n")
				.append("uvec2 bitfieldExtract(uvec2 v, int o, int b) { return uvec2(bitfieldExtract(v.x, o, b), bitfieldExtract(v.y, o, b)); }\n")
				.append("uvec3 bitfieldExtract(uvec3 v, int o, int b) { return uvec3(bitfieldExtract(v.x, o, b), bitfieldExtract(v.y, o, b), bitfieldExtract(v.z, o, b)); }\n")
				.append("uvec4 bitfieldExtract(uvec4 v, int o, int b) { return uvec4(bitfieldExtract(v.x, o, b), bitfieldExtract(v.y, o, b), bitfieldExtract(v.z, o, b), bitfieldExtract(v.w, o, b)); }\n");
		}
		if (usesCall(s, "bitfieldInsert") && !definesFunction(s, "bitfieldInsert")) {
			p.append("uint bitfieldInsert(uint base, uint ins, int o, int b) { if (b <= 0 || o >= 32) return base; uint mask = (b >= 32 ? 0xFFFFFFFFu : ((1u << uint(b)) - 1u)) << uint(o); return (base & ~mask) | ((ins << uint(o)) & mask); }\n")
				.append("int bitfieldInsert(int base, int ins, int o, int b) { return int(bitfieldInsert(uint(base), uint(ins), o, b)); }\n");
		}
		if (usesCall(s, "bitfieldReverse") && !definesFunction(s, "bitfieldReverse")) {
			p.append("uint bitfieldReverse(uint v) { v = ((v >> 1u) & 0x55555555u) | ((v & 0x55555555u) << 1u); v = ((v >> 2u) & 0x33333333u) | ((v & 0x33333333u) << 2u); v = ((v >> 4u) & 0x0F0F0F0Fu) | ((v & 0x0F0F0F0Fu) << 4u); v = ((v >> 8u) & 0x00FF00FFu) | ((v & 0x00FF00FFu) << 8u); return (v >> 16u) | (v << 16u); }\n")
				.append("int bitfieldReverse(int v) { return int(bitfieldReverse(uint(v))); }\n");
		}
		if (usesCall(s, "bitCount") && !definesFunction(s, "bitCount")) {
			p.append("int bitCount(uint v) { v = v - ((v >> 1u) & 0x55555555u); v = (v & 0x33333333u) + ((v >> 2u) & 0x33333333u); return int((((v + (v >> 4u)) & 0x0F0F0F0Fu) * 0x01010101u) >> 24u); }\n")
				.append("int bitCount(int v) { return bitCount(uint(v)); }\n")
				.append("ivec2 bitCount(ivec2 v) { return ivec2(bitCount(v.x), bitCount(v.y)); }\n")
				.append("ivec3 bitCount(ivec3 v) { return ivec3(bitCount(v.x), bitCount(v.y), bitCount(v.z)); }\n")
				.append("ivec4 bitCount(ivec4 v) { return ivec4(bitCount(v.x), bitCount(v.y), bitCount(v.z), bitCount(v.w)); }\n")
				.append("ivec2 bitCount(uvec2 v) { return ivec2(bitCount(v.x), bitCount(v.y)); }\n")
				.append("ivec3 bitCount(uvec3 v) { return ivec3(bitCount(v.x), bitCount(v.y), bitCount(v.z)); }\n")
				.append("ivec4 bitCount(uvec4 v) { return ivec4(bitCount(v.x), bitCount(v.y), bitCount(v.z), bitCount(v.w)); }\n");
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
		return s.contains(name) && callPattern(name).matcher(s).find();
	}
}
