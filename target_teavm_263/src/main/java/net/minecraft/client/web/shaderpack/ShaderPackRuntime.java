// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import com.mojang.renderpearl.backend.opengl.GlApi;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a shader pack's world, shadow and full-screen programs (prepare, gbuffers, shadow,
 * deferred, composite, final) on WebGL2.
 *
 * <p>Iris frame order: clear targets -> prepare* -> shadow* -> opaque world (terrain, entities,
 * block entities, sky) -> vanilla scene/depth prep -> deferred* -> translucent world (water,
 * translucent entities, particles, weather) -> composite* -> final.
 *
 * <p>Performance architecture:
 * <ul>
 *   <li>Per-pass validated FBO cache keyed by (depth, color texture IDs) and {@link #resizeGen}:
 *       steady-state frames issue ONE {@code glBindFramebuffer} with zero attachment mutations or
 *       {@code glCheckFramebufferStatus} IPC stalls.</li>
 *   <li>Single-bind MRT clear FBO batches clear {@code t.main} in a tight loop ({@code t.alt} is
 *       cleared only on resize).</li>
 *   <li>Per-pass {@code lastUniformFrame} skips full uniform loops when already applied this frame,
 *       updating only dynamic per-draw uniforms ({@code renderStage}, {@code iris_AlphaTestRef},
 *       {@code entityColor}).</li>
 *   <li>Staggered 1x1 PSO warmup draws ({@link #warmupStep}) trigger D3D11/ANGLE multi-RT shader
 *       compilation one pass per frame before Frame 1.</li>
 * </ul>
 */
public final class ShaderPackRuntime {
	// GL enums
	static final int TEXTURE_2D = 3553;
	static final int FRAMEBUFFER = 36160;
	static final int READ_FRAMEBUFFER = 36008;
	static final int DRAW_FRAMEBUFFER = 36009;
	static final int COLOR_ATTACHMENT0 = 36064;
	static final int DEPTH_ATTACHMENT = 36096;
	static final int TRIANGLES = 4;
	static final int BYTE = 5120;
	static final int UNSIGNED_BYTE = 5121;
	static final int SHORT = 5122;
	static final int UNSIGNED_SHORT = 5123;
	static final int INT = 5124;
	static final int UNSIGNED_INT = 5125;
	static final int FLOAT = 5126;
	static final int HALF_FLOAT = 5131;
	static final int ARRAY_BUFFER = 34962;
	static final int UNIFORM_BUFFER = 35345;
	static final int STATIC_DRAW = 35044;
	static final int VERTEX_SHADER = 35633;
	static final int FRAGMENT_SHADER = 35632;
	static final int COMPILE_STATUS = 35713;
	static final int LINK_STATUS = 35714;
	static final int MIN_FILTER = 10241;
	static final int MAG_FILTER = 10240;
	static final int WRAP_S = 10242;
	static final int WRAP_T = 10243;
	static final int CLAMP_TO_EDGE = 33071;
	static final int REPEAT = 10497;
	static final int LINEAR = 9729;
	static final int NEAREST = 9728;
	static final int NEAREST_MIPMAP_NEAREST = 9984;
	static final int LINEAR_MIPMAP_LINEAR = 9987;
	static final int NEAREST_MIPMAP_LINEAR = 9986;
	static final int TEXTURE0 = 33984;
	static final int ALWAYS = 519;
	static final int DEPTH_COMPONENT32F = 36012;
	static final int DEPTH_COMPONENT = 6402;
	static final int COMPARE_MODE = 34892;
	static final int COMPARE_REF_TO_TEXTURE = 34894;
	static final int COMPARE_FUNC = 34893;
	static final int LEQUAL = 515;
	static final int COLOR = 6144;
	static final int DEPTH = 6145;
	static final int FRAMEBUFFER_COMPLETE = 36053;
	static final int BLEND = 3042;
	static final int FUNC_ADD = 32774;

	// Texture pixel formats
	private static final int RGBA = 6408;
	private static final int RED = 6403;
	private static final int RG = 33319;
	private static final int RGB = 6407;
	private static final int RED_INTEGER = 36244;
	private static final int RG_INTEGER = 33320;
	private static final int RGBA_INTEGER = 36249;

	// Uniform GLSL types
	static final int T_FLOAT = 5126;
	static final int T_VEC2 = 35664;
	static final int T_VEC3 = 35665;
	static final int T_VEC4 = 35666;
	static final int T_INT = 5124;
	static final int T_IVEC2 = 35667;
	static final int T_IVEC3 = 35668;
	static final int T_IVEC4 = 35669;
	static final int T_BOOL = 35670;
	static final int T_BVEC2 = 35671;
	static final int T_BVEC3 = 35672;
	static final int T_BVEC4 = 35673;
	static final int T_UINT = 5125;
	static final int T_UVEC2 = 36294;
	static final int T_UVEC3 = 36295;
	static final int T_UVEC4 = 36296;
	static final int T_MAT2 = 35674;
	static final int T_MAT3 = 35675;
	static final int T_MAT4 = 35676;
	static final int T_SAMPLER_2D = 35678;
	static final int T_SAMPLER_2D_SHADOW = 35682;
	static final int T_ISAMPLER_2D = 36298;
	static final int T_USAMPLER_2D = 36306;

	/** Bit flags for custom texture {@code .png.mcmeta} settings. */
	static final int CUSTOM_FLAG_BLUR = 1;
	static final int CUSTOM_FLAG_CLAMP = 2;

	// Pre-allocated static draw-buffer / clear arrays (zero per-pass allocations)
	private static final int[] DRAW_BUF_0 = {COLOR_ATTACHMENT0};
	private static final int[] DRAW_BUF_NONE = {0};
	private static final float[] DEPTH_ONE = {1.0F};
	private static final float[] WHITE_CLEAR = {1.0F, 1.0F, 1.0F, 1.0F};
	private static final int[] WHITE_CLEAR_INT = {1, 1, 1, 1};

	/** Dynamic per-draw uniform names that may change between draws within the same frame. */
	private static final Set<String> PER_DRAW_UNIFORM_NAMES = new HashSet<>(Arrays.asList(
		"renderStage", "iris_AlphaTestRef", "alphaTestRef", "entityColor",
		"iris_ColorModulator", "colorModulator", "iris_KeepSky", "iris_DirectScene"
	));

	/** A colortex pair (main and alt), allocated on first resize. */
	private static final class Target {
		final int index;
		final Format format;
		int main;
		int alt;
		int width;
		int height;
		boolean mipmap;
		boolean nearest;
		boolean clear = true;
		boolean blendedWrite;
		float[] clearColor = {0, 0, 0, 0};
		int[] clearColorInt = {0, 0, 0, 0};

		Target(final int index, final Format format) {
			this.index = index;
			this.format = format;
		}
	}

	/** Internal format, format, type, and integer-kind for WebGL2 texImage2D and clears. */
	static final class Format {
		final String name;
		final int internal;
		final int format;
		final int type;
		/** 0 = float/normalized, 1 = signed integer, 2 = unsigned integer. */
		final int intKind;

		Format(final String name, final int internal, final int format, final int type) {
			this(name, internal, format, type, 0);
		}

		Format(final String name, final int internal, final int format, final int type, final int intKind) {
			this.name = name;
			this.internal = internal;
			this.format = format;
			this.type = type;
			this.intKind = intKind;
		}

		boolean isHighPrecision() {
			return this.type == FLOAT || this.type == HALF_FLOAT || this.internal == 35898 || this.intKind != 0;
		}
	}

	/**
	 * Maps a pack's format name to a colour-renderable WebGL2 format:
	 * <ul>
	 *   <li>Native WebGL2 integer formats ({@code R/RG/RGBA} x {@code 8/16/32} x {@code I/UI}) are
	 *       preserved natively (RGB*I/UI widened to RGBA*I/UI since 3-channel int is non-renderable).</li>
	 *   <li>{@code R8_SNORM} maps to {@code R16F} (not {@code RGBA16F}).</li>
	 *   <li>16-bit UNORM/SNORM packed-data targets promote to 32F ONLY when non-mipmapped AND both
	 *       {@code EXT_float_blend} and {@code OES_texture_float_linear} are available; otherwise 16F.</li>
	 * </ul>
	 */
	static Format format(final String name, final boolean floatTargets, final boolean float32Ok, final boolean mipmap) {
		String n = name == null ? "RGBA8" : name.toUpperCase(Locale.ROOT);
		// Native WebGL2 integer render targets (always color-renderable in WebGL2 without extensions)
		switch (n) {
			case "R8I": return new Format(n, 33329, RED_INTEGER, BYTE, 1);
			case "R8UI": return new Format(n, 33330, RED_INTEGER, UNSIGNED_BYTE, 2);
			case "R16I": return new Format(n, 33331, RED_INTEGER, SHORT, 1);
			case "R16UI": return new Format(n, 33332, RED_INTEGER, UNSIGNED_SHORT, 2);
			case "R32I": return new Format(n, 33333, RED_INTEGER, INT, 1);
			case "R32UI": return new Format(n, 33334, RED_INTEGER, UNSIGNED_INT, 2);
			case "RG8I": return new Format(n, 33335, RG_INTEGER, BYTE, 1);
			case "RG8UI": return new Format(n, 33336, RG_INTEGER, UNSIGNED_BYTE, 2);
			case "RG16I": return new Format(n, 33337, RG_INTEGER, SHORT, 1);
			case "RG16UI": return new Format(n, 33338, RG_INTEGER, UNSIGNED_SHORT, 2);
			case "RG32I": return new Format(n, 33339, RG_INTEGER, INT, 1);
			case "RG32UI": return new Format(n, 33340, RG_INTEGER, UNSIGNED_INT, 2);
			case "RGB8I":
			case "RGBA8I": return new Format("RGBA8I", 36238, RGBA_INTEGER, BYTE, 1);
			case "RGB8UI":
			case "RGBA8UI": return new Format("RGBA8UI", 36220, RGBA_INTEGER, UNSIGNED_BYTE, 2);
			case "RGB16I":
			case "RGBA16I": return new Format("RGBA16I", 36232, RGBA_INTEGER, SHORT, 1);
			case "RGB16UI":
			case "RGBA16UI": return new Format("RGBA16UI", 36214, RGBA_INTEGER, UNSIGNED_SHORT, 2);
			case "RGB32I":
			case "RGBA32I": return new Format("RGBA32I", 36226, RGBA_INTEGER, INT, 1);
			case "RGB32UI":
			case "RGBA32UI": return new Format("RGBA32UI", 36208, RGBA_INTEGER, UNSIGNED_INT, 2);
			default: break;
		}

		Format rgba8 = new Format("RGBA8", 32856, RGBA, UNSIGNED_BYTE);
		Format rgba16f = floatTargets ? new Format("RGBA16F", 34842, RGBA, HALF_FLOAT) : rgba8;

		// Only promote non-mipmapped 16-bit UNORM/SNORM targets to 32F when BOTH EXT_float_blend and OES_texture_float_linear exist
		if (floatTargets && float32Ok && !mipmap && fixedPointRange(n) != null && n.contains("16")) {
			if (n.startsWith("RG") && !n.startsWith("RGB")) {
				return new Format("RG32F", 33328, RG, FLOAT);
			}
			if (n.startsWith("R") && !n.startsWith("RG")) {
				return new Format("R32F", 33326, RED, FLOAT);
			}
			return new Format("RGBA32F", 34836, RGBA, FLOAT);
		}

		switch (n) {
			case "R8": return new Format(n, 33321, RED, UNSIGNED_BYTE);
			case "RG8": return new Format(n, 33323, RG, UNSIGNED_BYTE);
			case "RGB8":
			case "RGBA8":
			case "RGBA":
			case "RGB":
				return rgba8;
			case "RGB10_A2": return new Format(n, 32857, RGBA, 33640); // UNSIGNED_INT_2_10_10_10_REV
			case "RGB5_A1": return new Format(n, 32855, RGBA, UNSIGNED_BYTE);
			case "RGBA4": return new Format(n, 32854, RGBA, UNSIGNED_BYTE);
			case "R16F":
			case "R16":
			case "R16_SNORM":
			case "R8_SNORM":
				return floatTargets ? new Format("R16F", 33325, RED, HALF_FLOAT) : rgba8;
			case "RG16F":
			case "RG16":
			case "RG16_SNORM":
			case "RG8_SNORM":
				return floatTargets ? new Format("RG16F", 33327, RG, HALF_FLOAT) : rgba8;
			case "R32F": return floatTargets ? new Format(n, 33326, RED, FLOAT) : rgba8;
			case "RG32F": return floatTargets ? new Format(n, 33328, RG, FLOAT) : rgba8;
			case "RGB32F":
			case "RGBA32F":
				return floatTargets ? new Format("RGBA32F", 34836, RGBA, FLOAT) : rgba8;
			case "R11F_G11F_B10F": return floatTargets ? new Format(n, 35898, RGB, HALF_FLOAT) : rgba8;
			default:
				// RGB16F, RGBA16F, RGB16, RGBA16, RGB*_SNORM, RGBA*_SNORM
				return rgba16f;
		}
	}

	/** Legacy overload preserved for compatibility. */
	static Format format(final String name, final boolean floatTargets, final boolean float32) {
		return format(name, floatTargets, float32 && GlApi.hasFloatLinear(), false);
	}

	/**
	 * {min, steps} of a fixed-point format this runtime stores as float (16-bit UNORM and the SNORM
	 * formats), or null: writes to it are clamped to [min, 1] and rounded to steps levels.
	 */
	static float[] fixedPointRange(final String name) {
		if (name == null) {
			return null;
		}
		switch (name) {
			case "R16":
			case "RG16":
			case "RGB16":
			case "RGBA16":
				return new float[] {0.0F, 65535.0F};
			case "R16_SNORM":
			case "RG16_SNORM":
			case "RGB16_SNORM":
			case "RGBA16_SNORM":
				return new float[] {-1.0F, 32767.0F};
			case "R8_SNORM":
			case "RG8_SNORM":
			case "RGB8_SNORM":
			case "RGBA8_SNORM":
				return new float[] {-1.0F, 127.0F};
			default:
				return null;
		}
	}

	/** Integer textures and 32F without OES_texture_float_linear must use NEAREST filtering. */
	private static boolean filterable(final Target t) {
		return t == null || filterableFormat(t.format);
	}

	private static boolean filterableFormat(final Format f) {
		if (f == null) {
			return true;
		}
		if (f.intKind != 0) {
			return false;
		}
		return f.type != FLOAT || GlApi.hasFloatLinear();
	}

	private static boolean isIntegerGlslType(final String type) {
		return type != null && (type.equals("int") || type.equals("uint") || type.startsWith("ivec") || type.startsWith("uvec"));
	}

	private static boolean hasFloatAlpha(final String type) {
		return type == null || type.equals("vec4");
	}

	private static final Pattern MAIN_DECL = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(void\\s*)?\\)");

	/**
	 * Wraps {@code main()} to clamp and round outputs targeting emulated fixed-point float buffers.
	 * Skips {@code -1} ({@code GL_NONE}) slots and never wraps integer outputs ({@code ivec*}/{@code uvec*}).
	 */
	private String emulateFixedPoint(final String fs, final int[] drawBuffers,
		final Map<Integer, String> outputs, final Map<Integer, String> outputTypes) {
		if (outputs == null || !this.floatTargets || drawBuffers == null) {
			return fs;
		}
		StringBuilder tail = new StringBuilder();
		for (int i = 0; i < drawBuffers.length; i++) {
			int idx = drawBuffers[i];
			if (idx < 0 || idx >= ShaderPack.MAX_COLOR_TARGETS) {
				continue;
			}
			String out = outputs.get(i);
			String outType = outputTypes == null ? null : outputTypes.get(i);
			if (out == null || isIntegerGlslType(outType)) {
				continue;
			}
			float[] range = fixedPointRange(this.directives.formats[idx]);
			if (range == null) {
				continue;
			}
			String lo = range[0] == 0.0F ? "0.0" : "-1.0";
			String steps = Float.toString(range[1]);
			tail.append(' ').append(out).append(" = round(clamp(").append(out).append(", ").append(lo).append(", 1.0) * ")
				.append(steps).append(") / ").append(steps).append(';');
		}
		Matcher m = MAIN_DECL.matcher(fs);
		if (tail.length() == 0 || !m.find()) {
			return fs;
		}
		return fs.substring(0, m.start()) + "void iris_fixedMain()" + fs.substring(m.end())
			+ "\nvoid main() { iris_fixedMain();" + tail + " }\n";
	}

	/** One pre-validated FBO configuration cached on a {@link Pass}. */
	private static final class FboEntry {
		final int fbo;
		final int depth;
		final int[] colors;

		FboEntry(final int fbo, final int depth, final int[] colors) {
			this.fbo = fbo;
			this.depth = depth;
			this.colors = colors;
		}

		boolean matches(final int[] wantColors, final int count, final int wantDepth) {
			if (this.depth != wantDepth || this.colors.length != count) {
				return false;
			}
			for (int i = 0; i < count; i++) {
				if (this.colors[i] != wantColors[i]) {
					return false;
				}
			}
			return true;
		}
	}

	/** Pre-built MRT FBO batch for single-bind clearing of {@code t.main} targets sharing dimensions. */
	private static final class ClearBatch {
		final int fbo;
		final int altFbo;
		final Target[] targets;

		ClearBatch(final int fbo, final int altFbo, final Target[] targets) {
			this.fbo = fbo;
			this.altFbo = altFbo;
			this.targets = targets;
		}
	}

	/** One linked program pass (world, shadow, or full-screen). */
	private static final class Pass {
		String name;
		int program;
		int[] drawBuffers;
		boolean isFinal;
		boolean isWorldPass;
		boolean marksScene;
		boolean hasCustomAlphaTest;
		float scaleX = 1.0F;
		float scaleY = 1.0F;
		PackExpression enabledExpr;
		final Map<Integer, Boolean> flips = new HashMap<>();
		// Parsed blend directives
		boolean hasCustomBlend;
		boolean blendOff;
		int[] globalBlend; // {srcRgb, dstRgb, srcAlpha, dstAlpha}
		Map<Integer, int[]> perBufferBlend; // colortex idx -> {srcRgb, dstRgb, srcAlpha, dstAlpha} or empty = off
		// Active uniforms split for fast per-frame skip + per-draw updates
		final List<ShaderUniforms.UniformEntry> uniforms = new ArrayList<>();
		final List<ShaderUniforms.UniformEntry> perDrawUniforms = new ArrayList<>();
		int lastUniformFrame = -1;
		String vertexSource;
		String fragmentSource;
		final List<int[]> samplers = new ArrayList<>(); // {unit, kind, index_or_loc, glType}
		// Pre-validated FBO cache (2-4 entries per pass for ping-pong polarities)
		final List<FboEntry> fboCache = new ArrayList<>(4);
		int fboGen = -1;
		// World-program state
		com.mojang.renderpearl.backend.opengl.GlRenderPipeline lastVanillaPipeline;
		int unboundColorLoc = -1;
		int unboundNormalLoc = -1;
		int fallbackLightUnit = -1;
	}

	/** Deduplicated world/shadow program group backed by one {@link PackPrograms.Program}. */
	private static final class WorldProgramGroup {
		final PackPrograms.Program source;
		final String fragment;
		final boolean terrainLayout;
		final boolean isShadow;
		final boolean marksScene;
		Pass terrainPass;
		Pending pendingTerrain;
		/** Layout-specialized variants for non-terrain formats (null value = link failed). */
		final Map<String, Pass> variants = new HashMap<>();
		final Map<String, Pending> pendingVariants = new HashMap<>();

		WorldProgramGroup(final PackPrograms.Program source, final String fragment,
			final boolean terrainLayout, final boolean isShadow, final boolean marksScene) {
			this.source = source;
			this.fragment = fragment;
			this.terrainLayout = terrainLayout;
			this.isShadow = isShadow;
			this.marksScene = marksScene;
		}
	}

	/** Routing metadata for a vanilla world {@link com.mojang.renderpearl.backend.opengl.GlRenderPipeline}. */
	private static final class WorldPipelineBinding {
		final com.mojang.renderpearl.frontend.FrontendRenderPipeline frontend;
		final WorldProgramGroup group;
		final int renderStage;
		final float alphaTestRef;
		final boolean keepVanillaSampler;
		final int[] layout;
		final String layoutKey;

		WorldPipelineBinding(final com.mojang.renderpearl.frontend.FrontendRenderPipeline frontend,
			final WorldProgramGroup group, final int renderStage, final float alphaTestRef,
			final boolean keepVanillaSampler, final int[] layout) {
			this.frontend = frontend;
			this.group = group;
			this.renderStage = renderStage;
			this.alphaTestRef = alphaTestRef;
			this.keepVanillaSampler = keepVanillaSampler;
			this.layout = layout;
			this.layoutKey = Arrays.toString(layout);
		}
	}

	// Sampler kinds
	private static final int K_COLOR = 0;
	private static final int K_DEPTH = 1;
	private static final int K_NOISE = 2;
	private static final int K_SHADOW = 3;
	private static final int K_WHITE = 4;
	private static final int K_BLACK = 5;
	private static final int K_CUSTOM = 6;
	private static final int K_NONE = 7;
	private static final int K_MAIN = 8;
	private static final int K_MAIN_DEPTH = 9;
	private static final int K_ATLAS = 10;
	private static final int K_LIGHTMAP = 11;
	private static final int K_FLAT_NORMAL = 12;
	private static final int K_SHADOW_COLOR = 13;

	private final boolean floatTargets;
	private final ShaderPack.Directives directives;
	private final Target[] targets = new Target[ShaderPack.MAX_COLOR_TARGETS];
	private final List<Pass> passes = new ArrayList<>();
	private final List<Integer> customTextures = new ArrayList<>();
	private final Map<String, Integer> customBySampler = new HashMap<>();
	private final Map<Integer, Integer> customFlagsByTex = new HashMap<>();
	private Pass prep;
	private Pass copy;
	private int depthTex;
	private int noiseTex;
	private int shadowTex;
	private final int[] shadowColorTex = new int[ShaderPack.MAX_SHADOW_COLOR_TARGETS];
	private final Format[] shadowColorFmts = new Format[ShaderPack.MAX_SHADOW_COLOR_TARGETS];
	private final int[][] shadowColorClearInt = new int[ShaderPack.MAX_SHADOW_COLOR_TARGETS][];
	private final boolean[] usedShadowColors = new boolean[ShaderPack.MAX_SHADOW_COLOR_TARGETS];
	private int[] shadowDrawBuffers = DRAW_BUF_NONE;
	private int whiteTex;
	private int blackTex;
	private int flatNormalTex;
	private int blockIdTex;
	private int maxDrawBuffers = 8;
	private boolean hasIndexedBlend;
	private static int maxTexUnits = 32;

	// ---- world programs ----
	private final Map<PackPrograms.Program, WorldProgramGroup> worldGroupsByProgram = new IdentityHashMap<>();
	private final Map<String, WorldProgramGroup> worldGroupsByName = new HashMap<>();
	private Pass terrainPass;
	private Pass terrainSolidPass;
	private Pass terrainCutoutPass;
	private Pass waterPass;
	private Pass shadowPass;
	private Pass shadowSolidPass;
	private Pass shadowCutoutPass;

	/** True between beginWorld and endWorldPass/render. */
	private boolean worldFrame;
	/** True once any world program drew this frame. */
	private boolean worldRan;
	/** True once prepare* ran in beginWorld() this frame. */
	private boolean prepareRanThisFrame;
	private boolean sceneTargetPrepared;
	private boolean skyBasicRanThisFrame;
	private boolean hasPreparePasses;
	private boolean hasDeferredPasses;
	private int worldMainColor;
	private int worldMainDepth;
	private int savedFbo;

	// Cached vanilla pipeline references
	private boolean worldPipelinesInitialized;
	private Object solidPipeline;
	private Object solidMultiPipeline;
	private Object cutoutPipeline;
	private Object cutoutMultiPipeline;
	private Object translucentPipeline;
	private Object translucentMultiPipeline;
	private final Map<Object, WorldPipelineBinding> worldPipelineBindings = new IdentityHashMap<>();

	// World FBO ownership tracking (covers terrain, water, entities, extra world passes, sceneRedirect)
	private final Set<Integer> ourWorldFbos = new HashSet<>();
	private final Map<Integer, Pass> worldPassByFbo = new HashMap<>();

	// ---- shadow pass ----
	private int shadowFbo;
	private int shadowRes;
	private boolean shadowMode;
	private int shadowReturnFbo;
	private int shadowFrames;
	private int shadowBinds;
	private int farDepthTex;
	private int worldWidth;
	private int worldHeight;

	// ---- deferred before translucent terrain (Iris order) ----
	private boolean midDeferred;
	private boolean depthSnapshotted;
	private boolean needsOpaqueDepth;
	private boolean opaqueDepthValid;
	private int midFbo;
	private Pass sceneRedirect;
	private int blitFbo;
	private int depthTexOpaque;
	private boolean directScene;
	private boolean packedSceneTarget;
	private final Set<Integer> translucentReadWrite = new HashSet<>();
	private ShaderUniforms worldUniforms;

	// ---- shared GL resources ----
	private int vao;
	private int vbo;
	private int clearFbo;
	private final List<ClearBatch> clearBatches = new ArrayList<>();
	private int samplerLinear;
	private int samplerNearest;
	private int samplerRepeat;
	private int samplerRepeatNearest;
	private int samplerMip;
	private int samplerNearestMip;
	private int samplerShadowLinear;
	private int samplerShadowNearest;
	private int samplerAtlas;
	private int width = -1;
	private int height = -1;
	private int resizeGen;
	private final boolean[] flipped = new boolean[ShaderPack.MAX_COLOR_TARGETS];
	private final int[] scratchColors = new int[8];
	public final List<String> log = new ArrayList<>();
	private boolean broken;
	private int sceneTarget;
	public static int debugPasses = -1;
	public static int debugShow = -1;
	private int copySource;

	// ---- staggered 1x1 PSO warmup ----
	private final List<Pass> warmupQueue = new ArrayList<>();
	private int warmupIndex;
	private int warmupFbo;
	private int warmupUbo;
	private int warmupMainColor;
	private int warmupDepthTex;
	private int warmupIntTex;
	private int warmupUintTex;
	private final int[] warmupColorTex = new int[ShaderPack.MAX_COLOR_TARGETS];

	public ShaderPackRuntime(final ShaderPack.Directives directives) {
		this.directives = directives;
		this.floatTargets = GlApi.hasColorBufferFloat();
	}

	public boolean floatTargets() {
		return this.floatTargets;
	}

	// ---- building ----

	private static final String PREP_VS = "#version 300 es\nlayout(location = 0) in vec3 iris_Position;\nout vec2 uv;\n"
		+ "void main() { uv = iris_Position.xy; gl_Position = vec4(iris_Position.xy * 2.0 - 1.0, 0.0, 1.0); }\n";
	/** Scene colour to sceneTarget (preserving alpha!); reversed depth (near = 1) to standard depth (near = 0). */
	private static final String PREP_FS = "#version 300 es\nprecision highp float;\nuniform sampler2D iris_SceneColor;\n"
		+ "uniform highp sampler2D iris_SceneDepth;\nin vec2 uv;\nlayout(location = 0) out vec4 color;\n"
		+ "uniform int iris_KeepSky;\nuniform int iris_DirectScene;\n"
		+ "void main() { float d = texture(iris_SceneDepth, uv).r; if (iris_KeepSky == 1 && d == 0.0) { discard; }\n"
		+ "  vec4 c = texture(iris_SceneColor, uv); if (iris_DirectScene == 1 && c.a == 0.0) { discard; }\n"
		+ "  color = c; gl_FragDepth = 1.0 - d; }\n";
	private static final String COPY_FS = "#version 300 es\nprecision highp float;\nuniform sampler2D colortex0;\nin vec2 uv;\n"
		+ "layout(location = 0) out vec4 color;\nvoid main() { color = texture(colortex0, uv); }\n";

	/** Repairs a shader that failed deferred compilation via up to 10 GlslFixer rounds. */
	private int compileAfterError(final int type, String source, final String what, String errors) {
		for (int round = 0; round < 10; round++) {
			String fixed = GlslFixer.fix(source, errors);
			if (fixed == null) {
				this.log.add(what + ": " + firstLines(errors, 3));
				return 0;
			}
			source = fixed;
			int shader = GlApi.glCreateShader(type);
			GlApi.glShaderSourceRaw(shader, source);
			GlApi.glCompileShader(shader);
			if (GlApi.glGetShaderi(shader, COMPILE_STATUS) != 0) {
				this.lastSource = source;
				return shader;
			}
			errors = GlApi.glGetShaderInfoLog(shader);
			GlApi.glDeleteShader(shader);
		}
		this.log.add(what + ": still failing after 10 fix-up rounds");
		return 0;
	}

	private static String firstLines(final String s, final int n) {
		String[] lines = s.trim().split("\n");
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < Math.min(n, lines.length); i++) {
			b.append(i > 0 ? " | " : "").append(lines[i].trim());
		}
		return b.toString();
	}

	private String lastSource;
	private String lastVertexSource;
	private String lastFragmentSource;
	private int lastVsShader;
	private int lastFsShader;
	private boolean linkingEntity;
	private int[] entityLocations;
	private int lastUnboundColorLoc = -1;
	private int lastUnboundNormalLoc = -1;

	private static boolean isWorldProgramName(final String name) {
		return name.startsWith("gbuffers_") || name.startsWith("shadow");
	}

	private void bindProgramAttributes(final int p, final String what, final boolean entity, final int[] locations) {
		this.lastUnboundColorLoc = -1;
		this.lastUnboundNormalLoc = -1;
		if (entity && locations != null) {
			// Bind present attributes to vanilla's SPIR-V locations; bind missing (-1) attributes to
			// unused HIGH slots (15, 14, ...) so the linker NEVER auto-assigns them to VAO slots 0..3.
			String[] names = {"iris_Position", "iris_Color", "iris_UV0", "iris_UV2i", "iris_Normal"};
			boolean[] used = new boolean[16];
			for (int loc : locations) {
				if (loc >= 0 && loc < 16) {
					used[loc] = true;
				}
			}
			int nextHigh = 15;
			for (int i = 0; i < names.length; i++) {
				int loc = locations[i];
				if (loc >= 0 && loc < 16) {
					GlApi.glBindAttribLocation(p, loc, names[i]);
				} else {
					while (nextHigh >= 0 && used[nextHigh]) {
						nextHigh--;
					}
					if (nextHigh >= 0) {
						used[nextHigh] = true;
						GlApi.glBindAttribLocation(p, nextHigh, names[i]);
						if (i == 1) {
							this.lastUnboundColorLoc = nextHigh;
						} else if (i == 4) {
							this.lastUnboundNormalLoc = nextHigh;
						}
						nextHigh--;
					}
				}
			}
			while (nextHigh >= 0 && used[nextHigh]) {
				nextHigh--;
			}
			if (nextHigh >= 0) {
				GlApi.glBindAttribLocation(p, nextHigh, "mc_midTexCoord");
			}
		} else if (isWorldProgramName(what)) {
			// Vanilla BLOCK vertex format: Position 0, Color 1, UV0 2, UV2 (ivec2) 3; constant generic attribs 4..7
			GlApi.glBindAttribLocation(p, 0, "iris_Position");
			GlApi.glBindAttribLocation(p, 1, "iris_Color");
			GlApi.glBindAttribLocation(p, 2, "iris_UV0");
			GlApi.glBindAttribLocation(p, 3, "iris_UV2i");
			GlApi.glBindAttribLocation(p, 4, "iris_Normal");
			GlApi.glBindAttribLocation(p, 5, "mc_Entity");
			GlApi.glBindAttribLocation(p, 6, "mc_midTexCoord");
			GlApi.glBindAttribLocation(p, 7, "at_tangent");
		} else {
			GlApi.glBindAttribLocation(p, 0, "iris_Position");
			GlApi.glBindAttribLocation(p, 1, "iris_UV0");
			GlApi.glBindAttribLocation(p, 2, "iris_Color");
			GlApi.glBindAttribLocation(p, 3, "iris_Normal");
			GlApi.glBindAttribLocation(p, 4, "iris_UV2");
		}
	}

	private int startLink(final String vs, final String fs, final String what) {
		// Defer synchronous glGetShaderi(COMPILE_STATUS) until finishPass() so KHR_parallel_shader_compile
		// compiles VS & FS concurrently on ANGLE background threads without main-thread stalls.
		int v = GlApi.glCreateShader(VERTEX_SHADER);
		GlApi.glShaderSourceRaw(v, vs);
		GlApi.glCompileShader(v);
		int f = GlApi.glCreateShader(FRAGMENT_SHADER);
		GlApi.glShaderSourceRaw(f, fs);
		GlApi.glCompileShader(f);
		this.lastVertexSource = vs;
		this.lastFragmentSource = fs;
		this.lastVsShader = v;
		this.lastFsShader = f;
		int p = GlApi.glCreateProgram();
		GlApi.glAttachShader(p, v);
		GlApi.glAttachShader(p, f);
		this.bindProgramAttributes(p, what, this.linkingEntity, this.entityLocations);
		GlApi.glLinkProgram(p);
		return p;
	}

	private static final class Pending {
		String name;
		int program;
		int vsShader;
		int fsShader;
		boolean linkingEntity;
		int[] entityLocations;
		int[] drawBuffers;
		Map<Integer, String> outputs;
		Map<Integer, String> outputTypes;
		String vertexSource;
		String fragmentSource;
		PackPrograms.Program source;
		boolean isWorldPass;
		boolean marksScene;
		boolean hasCustomAlphaTest;
		int unboundColorLoc = -1;
		int unboundNormalLoc = -1;
	}

	private PackPrograms buildPrograms;
	private final List<Runnable> buildSteps = new ArrayList<>();
	private int buildNext;
	private final List<Pending> pendingPasses = new ArrayList<>();
	private final List<WorldProgramGroup> buildWorldGroups = new ArrayList<>();

	private List<Pending> pendings() {
		List<Pending> all = new ArrayList<>(this.pendingPasses);
		for (WorldProgramGroup g : this.buildWorldGroups) {
			if (g.pendingTerrain != null) {
				all.add(g.pendingTerrain);
			}
			all.addAll(g.pendingVariants.values());
		}
		return all;
	}

	public int buildStepsDone() {
		return this.buildNext;
	}

	public int buildStepsTotal() {
		return this.buildSteps.size();
	}

	public int linksStarted() {
		return this.pendings().size();
	}

	public int linksDone() {
		int done = 0;
		for (Pending p : this.pendings()) {
			if (GlApi.glProgramDone(p.program)) {
				done++;
			}
		}
		return done;
	}

	public boolean buildStep() {
		if (this.buildNext >= this.buildSteps.size()) {
			return false;
		}
		this.buildSteps.get(this.buildNext++).run();
		return this.buildNext < this.buildSteps.size();
	}

	/** Convenience overload without explicit custom texture flags. */
	public boolean buildBegin(final PackPrograms programs, final Map<String, byte[]> customTextureImages, final ImageDecoder decoder) {
		return this.buildBegin(programs, customTextureImages, null, decoder);
	}

	/**
	 * Sets up shared GL objects and queues compilation steps (one program/variant per frame).
	 */
	public boolean buildBegin(final PackPrograms programs, final Map<String, byte[]> customTextureImages,
		final Map<String, Integer> customTextureFlags, final ImageDecoder decoder) {
		this.buildPrograms = programs;
		this.maxDrawBuffers = Math.min(8, Math.max(4, GlApi.glGetInteger(34852))); // GL_MAX_DRAW_BUFFERS
		maxTexUnits = Math.min(32, Math.max(16, GlApi.glGetInteger(34930))); // GL_MAX_TEXTURE_IMAGE_UNITS
		// Probe OES_draw_buffers_indexed safely once so we never crash when absent
		try {
			GlApi.glDisablei(BLEND, 1);
			this.hasIndexedBlend = true;
		} catch (UnsupportedOperationException e) {
			this.hasIndexedBlend = false;
		}

		this.vao = GlApi.glGenVertexArrays();
		this.vbo = GlApi.glGenBuffers();
		GlStateManager._glBindVertexArray(this.vao);
		GlApi.glBindBuffer(ARRAY_BUFFER, this.vbo);
		GlApi.glBufferData(ARRAY_BUFFER, new float[] {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0}, STATIC_DRAW);
		GlApi.glEnableVertexAttribArray(0);
		GlApi.glVertexAttribPointer(0, 3, FLOAT, false, 12, 0L);
		GlApi.glEnableVertexAttribArray(1);
		GlApi.glVertexAttribPointer(1, 2, FLOAT, false, 12, 0L);
		GlStateManager._glBindVertexArray(0);
		this.clearFbo = GlApi.glGenFramebuffers();

		this.samplerLinear = sampler(LINEAR, LINEAR, CLAMP_TO_EDGE, false);
		this.samplerNearest = sampler(NEAREST, NEAREST, CLAMP_TO_EDGE, false);
		this.samplerRepeat = sampler(LINEAR, LINEAR, REPEAT, false);
		this.samplerRepeatNearest = sampler(NEAREST, NEAREST, REPEAT, false);
		this.samplerMip = sampler(LINEAR_MIPMAP_LINEAR, LINEAR, CLAMP_TO_EDGE, false);
		this.samplerNearestMip = sampler(NEAREST_MIPMAP_NEAREST, NEAREST, CLAMP_TO_EDGE, false);
		this.samplerShadowLinear = sampler(LINEAR, LINEAR, CLAMP_TO_EDGE, true);
		this.samplerShadowNearest = sampler(NEAREST, NEAREST, CLAMP_TO_EDGE, true);
		this.samplerAtlas = sampler(NEAREST_MIPMAP_LINEAR, NEAREST, CLAMP_TO_EDGE, false);

		this.whiteTex = solid(new byte[] {-1, -1, -1, -1});
		this.blackTex = solid(new byte[] {0, 0, 0, 0});
		this.flatNormalTex = solid(new byte[] {(byte) 128, (byte) 128, (byte) 255, (byte) 255});
		this.noiseTex = this.noiseTexture(customTextureImages, customTextureFlags, decoder);
		this.farDepthTex = depthTexture(1, 1);
		this.clearDepth(this.farDepthTex);
		this.shadowTex = this.farDepthTex;

		for (Map.Entry<String, byte[]> e : customTextureImages.entrySet()) {
			if (e.getKey().equals("noisetex")) {
				continue;
			}
			int flags = customTextureFlags != null ? customTextureFlags.getOrDefault(e.getKey(), CUSTOM_FLAG_BLUR) : CUSTOM_FLAG_BLUR;
			int tex = decoder == null ? 0 : decoder.upload(e.getValue(), flags);
			if (tex != 0) {
				this.customBySampler.put(e.getKey(), tex);
				this.customFlagsByTex.put(tex, flags);
				this.customTextures.add(tex);
			}
		}

		this.sceneTarget = programs.sceneTarget;
		Target st = this.target(this.sceneTarget);
		this.target(0);
		this.prep = this.pass("shaderpack_prep", PREP_VS, PREP_FS, new int[] {0});
		this.copy = this.pass("shaderpack_copy", PREP_VS, COPY_FS, new int[] {0});
		if (this.prep == null || this.copy == null) {
			this.log.add("internal programs failed to build");
			return false;
		}

		// block.properties ids per mesher table index (ShaderBlockIds), 3 bytes each, 256 per row
		float[] ids = ShaderBlockIds.table;
		int rows = Math.max(1, (ids.length + 255) / 256);
		byte[] idBytes = new byte[256 * rows * 4];
		for (int i = 0; i < ids.length; i++) {
			int v = Math.max(0, (int) ids[i]);
			idBytes[i * 4] = (byte) v;
			idBytes[i * 4 + 1] = (byte) (v >> 8);
			idBytes[i * 4 + 2] = (byte) (v >> 16);
			idBytes[i * 4 + 3] = (byte) 255;
		}
		this.blockIdTex = rgbaTexture(256, rows, idBytes);

		// Determine HDR / 16-bit directScene mode BEFORE compiling world programs so all of them get markScene
		this.packedSceneTarget = fixedPointRange(this.directives.formats[this.sceneTarget]) != null;
		boolean terrainValid = programs.terrain != null && programs.terrain.errors.isEmpty() && programs.terrain.drawBuffers != null;
		this.directScene = this.floatTargets && terrainValid
			&& (st.format.isHighPrecision() || this.packedSceneTarget)
			&& containsTarget(programs.terrain.drawBuffers, this.sceneTarget)
			&& programs.terrain.drawBuffers.length < this.maxDrawBuffers;

		this.buildSteps.clear();
		this.buildNext = 0;
		this.buildWorldGroups.clear();
		this.worldGroupsByProgram.clear();
		this.worldGroupsByName.clear();

		// Register world/shadow programs deduplicated by PackPrograms.Program identity
		if (terrainValid) {
			this.registerWorldProgram("gbuffers_terrain", programs.terrain, true, false);
			this.registerWorldProgram("gbuffers_terrain_solid", programs.worldProgram("gbuffers_terrain_solid"), true, false);
			this.registerWorldProgram("gbuffers_terrain_cutout", programs.worldProgram("gbuffers_terrain_cutout"), true, false);
			this.registerWorldProgram("gbuffers_water", programs.water != null ? programs.water : programs.worldProgram("gbuffers_water"), true, false);

			this.registerWorldProgram("shadow", programs.shadow, true, true);
			this.registerWorldProgram("shadow_solid", programs.worldProgram("shadow_solid"), true, true);
			this.registerWorldProgram("shadow_cutout", programs.worldProgram("shadow_cutout"), true, true);

			this.registerWorldProgram("gbuffers_entities", programs.entities, false, false);
			String[] extraEntityNames = {
				"gbuffers_entities_translucent", "gbuffers_entities_glowing", "gbuffers_spidereyes",
				"gbuffers_armor_glint", "gbuffers_block", "gbuffers_beaconbeam",
				"gbuffers_particles", "gbuffers_weather", "gbuffers_clouds",
				"gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_hand", "gbuffers_hand_water"
			};
			for (String req : extraEntityNames) {
				PackPrograms.Program wp = programs.worldProgram(req);
				if (wp != null && wp != programs.terrain) {
					this.registerWorldProgram(req, wp, false, false);
				}
			}
		}

		// Discover distinct common vertex layouts to pre-link during buildStep (eliminates first-encounter stalls)
		Map<WorldProgramGroup, Map<String, int[]>> prelinkLayouts = this.collectPrelinkLayouts();

		// Queue terrain & shadow world program compiles
		for (WorldProgramGroup g : this.buildWorldGroups) {
			if (g.terrainLayout) {
				this.buildSteps.add(() -> {
					int[] db = g.isShadow ? shadowProgramDrawBuffers(g.source) : g.source.drawBuffers;
					boolean applyAlpha = g.isShadow || !g.source.name.equals("gbuffers_water") || g.source.alphaTest != null;
					this.alphaTestNext = applyAlpha;
					this.alphaTestDirectiveNext = g.source.alphaTest;
					Pending pd = this.startPass(g.source.name, g.source.vertex, g.fragment, db,
						g.source.outputs, g.source.outputTypes, g.source);
					if (pd != null) {
						pd.isWorldPass = true;
						pd.marksScene = g.marksScene;
						g.pendingTerrain = pd;
					} else {
						this.log.add("world program " + g.source.name + " failed");
					}
				});
			} else {
				Map<String, int[]> layouts = prelinkLayouts.get(g);
				if (layouts == null || layouts.isEmpty()) {
					// Fallback: pre-link standard ENTITY_SOLID layout for gbuffers_entities
					if (g.source == programs.entities) {
						int[] layout = defaultEntityLayout();
						layouts = new LinkedHashMap<>();
						layouts.put(Arrays.toString(layout), layout);
					}
				}
				if (layouts != null) {
					for (Map.Entry<String, int[]> le : layouts.entrySet()) {
						String key = le.getKey();
						int[] layout = le.getValue();
						this.buildSteps.add(() -> {
							try {
								Pending pd = this.startEntityVariant(g, layout);
								if (pd != null) {
									g.pendingVariants.put(key, pd);
								}
							} catch (RuntimeException e) {
								this.log.add(g.source.name + " layout " + key + " failed: " + e);
							}
						});
					}
				}
			}
		}

		// Queue full-screen passes (prepare*, deferred*, composite*, final)
		for (PackPrograms.Program p : programs.programs) {
			this.buildSteps.add(() -> {
				if (!p.errors.isEmpty()) {
					this.log.add(p.name + ": " + p.errors.get(0));
					return;
				}
				Pending pd = this.startPass(p.name, p.vertex, p.fragment, p.drawBuffers, p.outputs, p.outputTypes, p);
				if (pd != null) {
					this.pendingPasses.add(pd);
				}
			});
		}
		return true;
	}

	private void registerWorldProgram(final String logicalName, final PackPrograms.Program prog,
		final boolean terrainLayout, final boolean isShadow) {
		if (prog == null || !prog.errors.isEmpty()) {
			return;
		}
		WorldProgramGroup existing = this.worldGroupsByProgram.get(prog);
		if (existing != null) {
			this.worldGroupsByName.put(logicalName, existing);
			return;
		}
		String fs = prog.fragment;
		boolean marks = false;
		if (!isShadow && this.directScene && prog.drawBuffers != null
			&& containsTarget(prog.drawBuffers, this.sceneTarget)
			&& prog.drawBuffers.length < this.maxDrawBuffers) {
			String markedFs = markScene(fs, prog.drawBuffers.length);
			if (markedFs != fs) {
				fs = markedFs;
				marks = true;
			}
		}
		WorldProgramGroup g = new WorldProgramGroup(prog, fs, terrainLayout, isShadow, marks);
		this.worldGroupsByProgram.put(prog, g);
		this.worldGroupsByName.put(logicalName, g);
		this.buildWorldGroups.add(g);
	}

	private static boolean containsTarget(final int[] drawBuffers, final int target) {
		if (drawBuffers == null) {
			return false;
		}
		for (int id : drawBuffers) {
			if (id == target) {
				return true;
			}
		}
		return false;
	}

	private static int[] shadowProgramDrawBuffers(final PackPrograms.Program prog) {
		if (prog.outputs == null || prog.outputs.isEmpty() || prog.drawBuffers == null) {
			return new int[0];
		}
		return prog.drawBuffers;
	}

	/** Collects distinct vertex layouts for common vanilla world pipelines so they pre-link during loading. */
	private Map<WorldProgramGroup, Map<String, int[]>> collectPrelinkLayouts() {
		Map<WorldProgramGroup, Map<String, int[]>> out = new IdentityHashMap<>();
		try {
			for (com.mojang.renderpearl.api.pipeline.RenderPipeline rp : net.minecraft.client.renderer.RenderPipelines.requiredPipelines()) {
				String key = classifyWorldPipelineProgram(rp);
				if (key == null) {
					continue;
				}
				WorldProgramGroup group = this.resolveWorldGroup(key);
				if (group == null || group.terrainLayout) {
					continue;
				}
				Object compiled = com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(rp);
				if (!(compiled instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline f)) {
					continue;
				}
				int[] layout = pipelineLayout(f.backendRenderPipeline(), f);
				if (layout[0] < 0) {
					continue; // vertex-less pipeline (e.g., 26.3 core/clouds)
				}
				Map<String, int[]> map = out.computeIfAbsent(group, k -> new LinkedHashMap<>());
				if (map.size() < 4) { // cap pre-links per group to keep load times snappy
					map.putIfAbsent(Arrays.toString(layout), layout);
				}
			}
		} catch (RuntimeException ignored) {
			// pipelines not compiled yet; fallback will link on demand
		}
		return out;
	}

	private static int[] defaultEntityLayout() {
		try {
			Object compiled = com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(net.minecraft.client.renderer.RenderPipelines.ENTITY_SOLID);
			if (compiled instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline f) {
				return pipelineLayout(f.backendRenderPipeline(), f);
			}
		} catch (RuntimeException ignored) {
			// fallback
		}
		return new int[] {0, 1, 2, 4, 5};
	}

	private WorldProgramGroup resolveWorldGroup(final String key) {
		WorldProgramGroup g = this.worldGroupsByName.get(key);
		if (g != null) {
			return g;
		}
		if (key.equals("gbuffers_entities_translucent") || key.equals("gbuffers_spidereyes")
			|| key.equals("gbuffers_armor_glint") || key.equals("gbuffers_block")
			|| key.equals("gbuffers_beaconbeam") || key.equals("gbuffers_entities_glowing")) {
			return this.worldGroupsByName.get("gbuffers_entities");
		}
		return null;
	}

	/**
	 * Maps a vanilla RenderPipeline to an Iris world program name, or null if not a supported world pipeline.
	 */
	private static String classifyWorldPipelineProgram(final com.mojang.renderpearl.api.pipeline.RenderPipeline rp) {
		net.minecraft.resources.Identifier vs = rp.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX);
		if (vs == null) {
			return null;
		}
		String vsPath = vs.getPath();
		String loc = rp.getLocation().getPath();
		if (loc.contains("gui") || loc.contains("oit_") || loc.contains("debug")) {
			return null;
		}
		switch (vsPath) {
			case "core/entity":
				if (loc.contains("eyes")) {
					return "gbuffers_spidereyes";
				}
				if (loc.contains("glint")) {
					return "gbuffers_armor_glint";
				}
				if (loc.contains("translucent") || loc.contains("breeze_wind") || loc.contains("banner_pattern") || loc.contains("energy_swirl")) {
					return "gbuffers_entities_translucent";
				}
				return "gbuffers_entities";
			case "core/item":
				if (loc.contains("glint")) {
					return "gbuffers_armor_glint";
				}
				if (loc.contains("translucent")) {
					return "gbuffers_entities_translucent";
				}
				return "gbuffers_entities";
			case "core/block":
				return "gbuffers_block";
			case "core/rendertype_beacon_beam":
				return "gbuffers_beaconbeam";
			case "core/glint":
				return "gbuffers_armor_glint";
			case "core/particle":
				return loc.contains("weather") ? "gbuffers_weather" : "gbuffers_particles";
			case "core/sky":
			case "core/stars":
				return "gbuffers_skybasic";
			case "core/position_color":
				return loc.contains("sunrise_sunset") ? "gbuffers_skybasic" : null;
			case "core/position_tex_color":
				return loc.contains("end_sky") ? "gbuffers_skytextured" : null;
			case "core/position_tex":
				return loc.contains("celestial") ? "gbuffers_skytextured" : null;
			case "core/clouds":
				return "gbuffers_clouds";
			default:
				return null;
		}
	}

	private static int classifyRenderStage(final String loc, final String vsPath) {
		switch (vsPath) {
			case "core/block":
			case "core/rendertype_beacon_beam":
				return ShaderUniforms.STAGE_BLOCK_ENTITIES;
			case "core/particle":
				return loc.contains("weather") ? ShaderUniforms.STAGE_RAIN_SNOW : ShaderUniforms.STAGE_PARTICLES;
			case "core/sky":
				return ShaderUniforms.STAGE_SKY;
			case "core/stars":
				return ShaderUniforms.STAGE_STARS;
			case "core/position_color":
				return ShaderUniforms.STAGE_SUNSET;
			case "core/position_tex":
			case "core/position_tex_color":
				return ShaderUniforms.STAGE_SKY;
			case "core/clouds":
				return ShaderUniforms.STAGE_CLOUDS;
			default:
				return ShaderUniforms.STAGE_ENTITIES;
		}
	}

	private static float classifyAlphaTestRef(final String loc, final String vsPath) {
		if (loc.contains("solid") || loc.contains("eyes") || loc.contains("glint") || loc.contains("beacon_beam")
			|| vsPath.equals("core/sky") || vsPath.equals("core/stars") || vsPath.equals("core/position_color")
			|| vsPath.equals("core/position_tex") || vsPath.equals("core/position_tex_color")) {
			return 0.0F;
		}
		return 0.1F;
	}

	/**
	 * Collects finished program links and initializes shadow targets + 1x1 warmup queue.
	 */
	public boolean buildFinish() {
		PackPrograms programs = this.buildPrograms;
		this.buildPrograms = null;

		// Finish all world/shadow program groups
		for (WorldProgramGroup g : this.buildWorldGroups) {
			if (g.pendingTerrain != null) {
				Pending pd = g.pendingTerrain;
				g.pendingTerrain = null;
				g.terrainPass = this.finishPass(pd);
				if (g.terrainPass != null && g.isShadow) {
					remapShadowUniforms(g.terrainPass);
				}
			}
			for (Map.Entry<String, Pending> ve : g.pendingVariants.entrySet()) {
				Pass vp = this.finishPass(ve.getValue());
				g.variants.put(ve.getKey(), vp);
				if (vp != null) {
					this.log.add(g.source.name + " linked for attribute layout " + ve.getKey());
				}
			}
			g.pendingVariants.clear();
		}

		WorldProgramGroup terrainGroup = this.worldGroupsByName.get("gbuffers_terrain");
		this.terrainPass = terrainGroup != null ? terrainGroup.terrainPass : null;
		WorldProgramGroup solidGroup = this.worldGroupsByName.get("gbuffers_terrain_solid");
		this.terrainSolidPass = solidGroup != null && solidGroup.terrainPass != null ? solidGroup.terrainPass : this.terrainPass;
		WorldProgramGroup cutoutGroup = this.worldGroupsByName.get("gbuffers_terrain_cutout");
		this.terrainCutoutPass = cutoutGroup != null && cutoutGroup.terrainPass != null ? cutoutGroup.terrainPass : this.terrainPass;
		WorldProgramGroup waterGroup = this.worldGroupsByName.get("gbuffers_water");
		this.waterPass = waterGroup != null ? waterGroup.terrainPass : null;
		WorldProgramGroup shadowGroup = this.worldGroupsByName.get("shadow");
		this.shadowPass = this.terrainPass != null && shadowGroup != null ? shadowGroup.terrainPass : null;
		WorldProgramGroup shadowSolidGroup = this.worldGroupsByName.get("shadow_solid");
		this.shadowSolidPass = this.shadowPass != null && shadowSolidGroup != null && shadowSolidGroup.terrainPass != null
			? shadowSolidGroup.terrainPass : this.shadowPass;
		WorldProgramGroup shadowCutoutGroup = this.worldGroupsByName.get("shadow_cutout");
		this.shadowCutoutPass = this.shadowPass != null && shadowCutoutGroup != null && shadowCutoutGroup.terrainPass != null
			? shadowCutoutGroup.terrainPass : this.shadowPass;

		if (this.terrainPass != null) {
			if (this.directScene) {
				this.log.add("scene buffer colortex" + this.sceneTarget + " is "
					+ this.target(this.sceneTarget).format.name + ": world programs write it directly (HDR/precision preserved)");
			}
			this.log.add("world program " + this.terrainPass.name + " draws terrain into " + Arrays.toString(this.terrainPass.drawBuffers));
		}

		// Finish full-screen passes
		int built = 0;
		for (Pending pd : this.pendingPasses) {
			Pass pass = this.finishPass(pd);
			if (pass == null) {
				continue;
			}
			PackPrograms.Program p = pd.source;
			pass.isFinal = p.name.equals("final");
			pass.flips.putAll(p.flips);
			pass.scaleX = p.scaleX > 0.0F && p.scaleX <= 1.0F ? p.scaleX : (p.scale > 0.0F && p.scale <= 1.0F ? p.scale : 1.0F);
			pass.scaleY = p.scaleY > 0.0F && p.scaleY <= 1.0F ? p.scaleY : (p.scale > 0.0F && p.scale <= 1.0F ? p.scale : 1.0F);
			pass.enabledExpr = p.enabledExpr;
			if (p.name.startsWith("prepare")) {
				this.hasPreparePasses = true;
			} else if (p.name.startsWith("deferred")) {
				this.hasDeferredPasses = true;
			}
			this.passes.add(pass);
			built++;
		}
		this.pendingPasses.clear();

		// Set up shadow FBO (and optional shadowcolor textures if used)
		if (this.shadowPass != null) {
			this.setupShadowFramebuffer();
		}

		// Translucent read-write conflict detection (WebGL forbids sampling an attached render target)
		Pass translucent = this.waterPass != null ? this.waterPass : this.terrainPass;
		if (translucent != null) {
			this.recordTranslucentReadWrite(translucent);
			for (String tName : new String[] {"gbuffers_entities_translucent", "gbuffers_particles", "gbuffers_weather"}) {
				WorldProgramGroup tg = this.worldGroupsByName.get(tName);
				if (tg != null) {
					for (Pass vp : tg.variants.values()) {
						if (vp != null) {
							this.recordTranslucentReadWrite(vp);
						}
					}
				}
			}
			this.sceneRedirect = new Pass();
			this.sceneRedirect.name = "shaderpack_scene";
			this.sceneRedirect.isWorldPass = true;
			this.blitFbo = GlApi.glGenFramebuffers();
		}

		// Finalize target directives (mipmap, nearest, clear, clearColor, blendedWrite)
		for (int i = 0; i < ShaderPack.MAX_COLOR_TARGETS; i++) {
			Target t = this.targets[i];
			if (t != null) {
				t.mipmap = this.directives.mipmap[i] && filterable(t);
				t.nearest = this.directives.nearest[i] || !filterable(t);
				Boolean clear = this.directives.clear[i];
				t.clear = clear == null || clear;
				float[] c = this.directives.clearColor[i];
				t.clearColor = c != null ? c.clone() : i == 1 ? new float[] {1, 1, 1, 1} : new float[] {0, 0, 0, 0};
				for (int k = 0; k < 4; k++) {
					t.clearColorInt[k] = (int) t.clearColor[k];
				}
			}
		}
		// Mark targets written with custom blend so clear batches know if alt needs clearing too
		for (Pass p : this.passes) {
			if (p.hasCustomBlend && p.drawBuffers != null) {
				for (int idx : p.drawBuffers) {
					if (idx >= 0 && idx < ShaderPack.MAX_COLOR_TARGETS && this.targets[idx] != null) {
						this.targets[idx].blendedWrite = true;
					}
				}
			}
		}

		this.log.add("built " + built + " of " + programs.programs.size() + " programs; float targets " + this.floatTargets);
		boolean ok = built > 0 || programs.programs.isEmpty();
		if (ok) {
			this.prepareWarmupQueue();
		}
		return ok;
	}

	private void recordTranslucentReadWrite(final Pass p) {
		if (p == null || p.drawBuffers == null) {
			return;
		}
		for (int[] smp : p.samplers) {
			if (smp[1] != K_COLOR) {
				continue;
			}
			for (int idx : p.drawBuffers) {
				if (idx >= 0 && idx == smp[2]) {
					this.translucentReadWrite.add(idx);
				}
			}
		}
	}

	private static void remapShadowUniforms(final Pass p) {
		for (ShaderUniforms.UniformEntry u : p.uniforms) {
			if (u.name.startsWith("iris_gb")) {
				u.name = "iris_sh" + u.name.substring("iris_gb".length());
				u.slot = null;
				u.lastVersion = -1;
			}
		}
	}

	private void setupShadowFramebuffer() {
		int res = (int) this.directives.constFloat("shadowMapResolution", 2048.0F);
		this.shadowRes = Math.max(256, Math.min(res, Math.min(4096, GlApi.glGetInteger(3379)))); // MAX_TEXTURE_SIZE
		this.shadowTex = depthTexture(this.shadowRes, this.shadowRes);
		this.shadowFbo = GlApi.glGenFramebuffers();
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.shadowFbo);
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, this.shadowTex, 0);

		// Allocate shadowcolor targets ONLY when written by shadow pass AND sampled/configured by the pack
		boolean anyShadowColor = false;
		int[] sdb = this.shadowPass.drawBuffers;
		if (sdb != null && sdb.length > 0) {
			for (int idx : sdb) {
				if (idx >= 0 && idx < ShaderPack.MAX_SHADOW_COLOR_TARGETS
					&& (this.usedShadowColors[idx] || this.directives.shadowColorFormats[idx] != null)) {
					anyShadowColor = true;
					break;
				}
			}
		}
		if (anyShadowColor) {
			int count = Math.min(sdb.length, this.maxDrawBuffers);
			int[] drawBufs = new int[count];
			boolean float32Ok = GlApi.hasFloatBlend() && GlApi.hasFloatLinear();
			for (int i = 0; i < count; i++) {
				int idx = sdb[i];
				if (idx >= 0 && idx < ShaderPack.MAX_SHADOW_COLOR_TARGETS
					&& (this.usedShadowColors[idx] || this.directives.shadowColorFormats[idx] != null)) {
					if (this.shadowColorTex[idx] == 0) {
						Format fmt = format(this.directives.shadowColorFormats[idx], this.floatTargets, float32Ok, this.directives.shadowColorMipmap[idx]);
						this.shadowColorFmts[idx] = fmt;
						float[] c = this.directives.shadowColorClearColor[idx];
						this.shadowColorClearInt[idx] = c != null
							? new int[] {(int) c[0], (int) c[1], (int) c[2], (int) c[3]}
							: WHITE_CLEAR_INT;
						Target tmp = new Target(idx, fmt);
						tmp.nearest = this.directives.shadowColorNearest[idx] || !filterable(tmp);
						this.shadowColorTex[idx] = colorTexture(tmp, this.shadowRes, this.shadowRes);
						if (this.directives.shadowColorMipmap[idx] && filterableFormat(fmt)) {
							GlApi.glGenerateMipmap(TEXTURE_2D);
						}
					}
					GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0 + i, TEXTURE_2D, this.shadowColorTex[idx], 0);
					drawBufs[i] = COLOR_ATTACHMENT0 + i;
				} else {
					drawBufs[i] = 0;
				}
			}
			this.shadowDrawBuffers = drawBufs;
			GlApi.glDrawBuffers(drawBufs);
		} else {
			this.shadowDrawBuffers = DRAW_BUF_NONE;
			GlApi.glDrawBuffers(DRAW_BUF_NONE);
		}
		GlApi.glReadBuffer(0);
		if (GlApi.glCheckFramebufferStatus(FRAMEBUFFER) != FRAMEBUFFER_COMPLETE) {
			this.log.add("shadow map framebuffer incomplete; no shadows");
			this.shadowPass = null;
			this.shadowSolidPass = null;
			this.shadowCutoutPass = null;
			this.shadowTex = this.farDepthTex;
		} else {
			this.clearDepth(this.shadowTex);
			this.log.add("shadow pass: " + this.shadowRes + "x" + this.shadowRes + " shadow map, distance "
				+ this.directives.constFloat("shadowDistance", 160.0F));
		}
	}

	// ---- staggered 1x1 PSO warmup ----

	private void prepareWarmupQueue() {
		this.warmupQueue.clear();
		this.warmupIndex = 0;
		Set<Pass> seen = new LinkedHashSet<>();
		for (WorldProgramGroup g : this.buildWorldGroups) {
			if (g.terrainPass != null && seen.add(g.terrainPass)) {
				this.warmupQueue.add(g.terrainPass);
			}
			for (Pass vp : g.variants.values()) {
				if (vp != null && seen.add(vp)) {
					this.warmupQueue.add(vp);
				}
			}
		}
		for (Pass p : this.passes) {
			if (seen.add(p)) {
				this.warmupQueue.add(p);
			}
		}
		if (this.warmupQueue.isEmpty()) {
			return;
		}
		this.warmupFbo = GlApi.glGenFramebuffers();
		this.warmupUbo = GlApi.glGenBuffers();
		GlApi.glBindBuffer(UNIFORM_BUFFER, this.warmupUbo);
		GlApi.glBufferData(UNIFORM_BUFFER, 256L, STATIC_DRAW);
		GlApi.glBindBuffer(UNIFORM_BUFFER, 0);
		this.warmupMainColor = solid(new byte[] {0, 0, 0, -1});
		this.warmupDepthTex = depthTexture(1, 1);
		for (int i = 0; i < ShaderPack.MAX_COLOR_TARGETS; i++) {
			Target t = this.targets[i];
			if (t != null) {
				this.warmupColorTex[i] = colorTexture(t, 1, 1);
			}
		}
	}

	/** Number of warmup draws completed so far. */
	public int warmupDone() {
		return this.warmupIndex;
	}

	/** Total number of compiled passes queued for 1x1 PSO warmup. */
	public int warmupTotal() {
		return this.warmupQueue.size();
	}

	/**
	 * Issues ONE 1x1 dummy draw into the pass's real MRT format layout so D3D11/ANGLE compiles its
	 * pipeline state object incrementally across frames instead of freezing Frame 1.
	 * Returns true while more passes remain to warm up.
	 */
	public boolean warmupStep() {
		if (this.warmupIndex >= this.warmupQueue.size()) {
			this.cleanupWarmup();
			return false;
		}
		Pass p = this.warmupQueue.get(this.warmupIndex++);
		try {
			GlStateManager._disableScissorTest();
			GlStateManager._disableCull();
			GlStateManager._disablePolygonOffset();
			this.disableAllBlend();
			GlStateManager._colorMaskAll(true, true, true, true);
			GlStateManager._viewport(0, 0, 1, 1);

			GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.warmupFbo);
			// Reset all attachments on warmupFbo
			for (int i = 0; i < this.maxDrawBuffers; i++) {
				GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0 + i, TEXTURE_2D, 0, 0);
			}
			GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, 0, 0);

			boolean isShadow = p.name.startsWith("shadow");
			if (p.isFinal) {
				GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, this.warmupMainColor, 0);
				GlApi.glDrawBuffers(DRAW_BUF_0);
				GlStateManager._disableDepthTest();
				GlStateManager._depthMask(false);
			} else if (isShadow) {
				GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, this.warmupDepthTex, 0);
				GlApi.glDrawBuffers(DRAW_BUF_NONE);
				GlStateManager._enableDepthTest();
				GlStateManager._depthFunc(LEQUAL);
				GlStateManager._depthMask(true);
			} else {
				int dbLen = p.drawBuffers == null ? 0 : p.drawBuffers.length;
				int extra = p.marksScene && dbLen < this.maxDrawBuffers ? 1 : 0;
				int total = Math.min(dbLen + extra, this.maxDrawBuffers);
				int[] drawBufs = total == 0 ? DRAW_BUF_NONE : new int[total];
				for (int i = 0; i < dbLen && i < this.maxDrawBuffers; i++) {
					int idx = p.drawBuffers[i];
					if (idx >= 0 && idx < ShaderPack.MAX_COLOR_TARGETS && this.warmupColorTex[idx] != 0) {
						GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0 + i, TEXTURE_2D, this.warmupColorTex[idx], 0);
						drawBufs[i] = COLOR_ATTACHMENT0 + i;
					} else {
						drawBufs[i] = 0;
					}
				}
				if (extra == 1) {
					GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0 + dbLen, TEXTURE_2D, this.warmupMainColor, 0);
					drawBufs[dbLen] = COLOR_ATTACHMENT0 + dbLen;
				}
				if (p.isWorldPass) {
					GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, this.warmupDepthTex, 0);
					GlStateManager._enableDepthTest();
					GlStateManager._depthFunc(LEQUAL);
					GlStateManager._depthMask(true);
				} else {
					GlStateManager._disableDepthTest();
					GlStateManager._depthMask(false);
				}
				GlApi.glDrawBuffers(drawBufs);
			}

			if (GlApi.glCheckFramebufferStatus(FRAMEBUFFER) == FRAMEBUFFER_COMPLETE) {
				GlStateManager._glUseProgram(p.program);
				// Bind dummy UBO to slots 0..3 so world shaders referencing UBO blocks stay valid
				for (int b = 0; b < 4; b++) {
					GlApi.glBindBufferRange(UNIFORM_BUFFER, b, this.warmupUbo, 0L, 256L);
				}
				// Bind matching-type dummy textures to every sampler unit so WebGL2 executes the draw
				for (int[] smp : p.samplers) {
					int unit = smp[0];
					if (unit < 0) {
						continue;
					}
					int glType = smp[3];
					int tex;
					int smpObj;
					if (glType == T_ISAMPLER_2D) {
						if (this.warmupIntTex == 0) {
							this.warmupIntTex = int1x1Texture(36226, RGBA_INTEGER, INT);
						}
						tex = this.warmupIntTex;
						smpObj = this.samplerNearest;
					} else if (glType == T_USAMPLER_2D) {
						if (this.warmupUintTex == 0) {
							this.warmupUintTex = int1x1Texture(36208, RGBA_INTEGER, UNSIGNED_INT);
						}
						tex = this.warmupUintTex;
						smpObj = this.samplerNearest;
					} else if (glType == T_SAMPLER_2D_SHADOW) {
						tex = this.farDepthTex;
						smpObj = this.samplerShadowLinear;
					} else {
						tex = this.whiteTex;
						smpObj = this.samplerNearest;
					}
					activeUnit(unit);
					forceBind(tex);
					GlApi.glBindSampler(unit, smpObj);
				}
				if (p.isWorldPass) {
					for (int u = 0; u < 3; u++) {
						activeUnit(u);
						forceBind(this.whiteTex);
						GlApi.glBindSampler(u, this.samplerNearest);
					}
				}
				GlStateManager._glBindVertexArray(this.vao);
				GlApi.glDrawArrays(TRIANGLES, 0, 3);
			}
		} catch (RuntimeException e) {
			this.log.add("warmup skipped for " + p.name + ": " + e.getMessage());
		} finally {
			GlStateManager._disableDepthTest();
			GlStateManager._depthMask(false);
			resyncGlState();
		}
		if (this.warmupIndex >= this.warmupQueue.size()) {
			this.cleanupWarmup();
			return false;
		}
		return true;
	}

	private static int int1x1Texture(final int internalFormat, final int format, final int type) {
		int tex = GlApi.glGenTextures();
		bindUnit0(tex);
		resetUnpack();
		GlApi.glTexImage2D(TEXTURE_2D, 0, internalFormat, 1, 1, 0, format, type, (ByteBuffer) null);
		GlApi.glTexParameteri(TEXTURE_2D, MIN_FILTER, NEAREST);
		GlApi.glTexParameteri(TEXTURE_2D, MAG_FILTER, NEAREST);
		GlApi.glTexParameteri(TEXTURE_2D, WRAP_S, CLAMP_TO_EDGE);
		GlApi.glTexParameteri(TEXTURE_2D, WRAP_T, CLAMP_TO_EDGE);
		return tex;
	}

	private void cleanupWarmup() {
		if (this.warmupFbo != 0) {
			deleteFramebuffer(this.warmupFbo);
			this.warmupFbo = 0;
		}
		if (this.warmupUbo != 0) {
			GlApi.glDeleteBuffers(this.warmupUbo);
			this.warmupUbo = 0;
		}
		if (this.warmupMainColor != 0) {
			deleteTexture(this.warmupMainColor);
			this.warmupMainColor = 0;
		}
		if (this.warmupDepthTex != 0) {
			deleteTexture(this.warmupDepthTex);
			this.warmupDepthTex = 0;
		}
		if (this.warmupIntTex != 0) {
			deleteTexture(this.warmupIntTex);
			this.warmupIntTex = 0;
		}
		if (this.warmupUintTex != 0) {
			deleteTexture(this.warmupUintTex);
			this.warmupUintTex = 0;
		}
		for (int i = 0; i < this.warmupColorTex.length; i++) {
			if (this.warmupColorTex[i] != 0) {
				deleteTexture(this.warmupColorTex[i]);
				this.warmupColorTex[i] = 0;
			}
		}
		this.warmupQueue.clear();
	}

	/** Decodes a PNG from the pack and uploads it as an RGBA8 texture; 0 on failure. */
	public interface ImageDecoder {
		int upload(byte[] png, int flags);
	}

	private int noiseTexture(final Map<String, byte[]> images, final Map<String, Integer> flagsMap, final ImageDecoder decoder) {
		byte[] png = images.get("noisetex");
		int flags = flagsMap != null ? flagsMap.getOrDefault("noisetex", CUSTOM_FLAG_BLUR) : CUSTOM_FLAG_BLUR;
		if (png != null && decoder != null) {
			int tex = decoder.upload(png, flags);
			if (tex != 0) {
				this.customFlagsByTex.put(tex, flags);
				return tex;
			}
		}
		int size = (int) this.directives.constFloat("noiseTextureResolution", 256.0F);
		size = Math.max(1, Math.min(size, 2048));
		Random random = new Random(0L);
		byte[] data = new byte[size * size * 4];
		for (int i = 0; i < size * size; i++) {
			int v = random.nextInt();
			data[i * 4] = (byte) (v >> 16);
			data[i * 4 + 1] = (byte) (v >> 8);
			data[i * 4 + 2] = (byte) v;
			data[i * 4 + 3] = (byte) 255;
		}
		int tex = rgbaTexture(size, size, data);
		this.customFlagsByTex.put(tex, CUSTOM_FLAG_BLUR);
		return tex;
	}

	static void resetUnpack() {
		GlApi.glBindBuffer(35052, 0); // GL_PIXEL_UNPACK_BUFFER
		GlApi.glPixelStorei(3314, 0); // UNPACK_ROW_LENGTH
		GlApi.glPixelStorei(3315, 0); // UNPACK_SKIP_ROWS
		GlApi.glPixelStorei(3316, 0); // UNPACK_SKIP_PIXELS
		GlApi.glPixelStorei(32878, 0); // UNPACK_IMAGE_HEIGHT
		GlApi.glPixelStorei(32877, 0); // UNPACK_SKIP_IMAGES
	}

	static int rgbaTexture(final int w, final int h, final byte[] data) {
		int tex = GlApi.glGenTextures();
		bindUnit0(tex);
		GlApi.glPixelStorei(3317, 1); // UNPACK_ALIGNMENT
		resetUnpack();
		GlApi.glTexImage2D(TEXTURE_2D, 0, 32856, w, h, 0, RGBA, UNSIGNED_BYTE, ByteBuffer.wrap(data));
		GlApi.glTexParameteri(TEXTURE_2D, MIN_FILTER, LINEAR);
		GlApi.glTexParameteri(TEXTURE_2D, MAG_FILTER, LINEAR);
		return tex;
	}

	private static int solid(final byte[] rgba) {
		return rgbaTexture(1, 1, rgba);
	}

	static void bindUnit0(final int tex) {
		activeUnit(0);
		forceBind(tex);
	}

	/** Deletes a texture and invalidates GlStateManager's cache so recycled GL IDs never hit stale entries. */
	private static void deleteTexture(final int tex) {
		if (tex != 0) {
			GlApi.glDeleteTextures(tex);
			GlStateManager._invalidateDeletedTexture(tex);
		}
	}

	/** Deletes an FBO and invalidates GlStateManager's read/write FBO cache. */
	private static void deleteFramebuffer(final int fbo) {
		if (fbo != 0) {
			GlApi.glDeleteFramebuffers(fbo);
			GlStateManager._invalidateDeletedFramebuffer(fbo);
		}
	}

	/** Bitmask of texture units touched since the last {@link #resyncGlState}. */
	private static long touchedUnits;

	private static void activeUnit(final int unit) {
		if (unit >= 0 && unit < 64) {
			touchedUnits |= 1L << unit;
		}
		if (unit < 12) {
			GlStateManager._activeTexture(TEXTURE0 + (unit == 0 ? 1 : 0));
			GlStateManager._activeTexture(TEXTURE0 + unit);
		} else {
			GlApi.glActiveTexture(TEXTURE0 + unit);
		}
	}

	private static void forceBind(final int tex) {
		GlApi.glBindTexture(TEXTURE_2D, tex);
	}

	private static int depthTexture(final int w, final int h) {
		int tex = GlApi.glGenTextures();
		bindUnit0(tex);
		resetUnpack();
		GlApi.glTexImage2D(TEXTURE_2D, 0, DEPTH_COMPONENT32F, w, h, 0, DEPTH_COMPONENT, FLOAT, (ByteBuffer) null);
		GlApi.glTexParameteri(TEXTURE_2D, MIN_FILTER, NEAREST);
		GlApi.glTexParameteri(TEXTURE_2D, MAG_FILTER, NEAREST);
		return tex;
	}

	private void clearDepth(final int tex) {
		GlStateManager._disableScissorTest();
		GlStateManager._scissorBox(0, 0, 8192, 8192);
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.clearFbo);
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, 0, 0);
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, tex, 0);
		GlStateManager._depthMask(true);
		GlApi.glClearBufferfv(DEPTH, 0, DEPTH_ONE);
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, 0, 0);
	}

	private static int sampler(final int min, final int mag, final int wrap, final boolean compare) {
		int s = GlApi.glGenSamplers();
		GlApi.glSamplerParameteri(s, MIN_FILTER, min);
		GlApi.glSamplerParameteri(s, MAG_FILTER, mag);
		GlApi.glSamplerParameteri(s, WRAP_S, wrap);
		GlApi.glSamplerParameteri(s, WRAP_T, wrap);
		if (compare) {
			GlApi.glSamplerParameteri(s, COMPARE_MODE, COMPARE_REF_TO_TEXTURE);
			GlApi.glSamplerParameteri(s, COMPARE_FUNC, LEQUAL);
		}
		return s;
	}

	private int customSamplerFor(final int texId) {
		int flags = this.customFlagsByTex.getOrDefault(texId, CUSTOM_FLAG_BLUR);
		boolean blur = (flags & CUSTOM_FLAG_BLUR) != 0;
		boolean clamp = (flags & CUSTOM_FLAG_CLAMP) != 0;
		if (blur) {
			return clamp ? this.samplerLinear : this.samplerRepeat;
		}
		return clamp ? this.samplerNearest : this.samplerRepeatNearest;
	}

	private int colorSamplerFor(final Target t) {
		if (t == null) {
			return this.samplerLinear;
		}
		if (!filterable(t)) {
			return this.samplerNearest;
		}
		if (t.nearest) {
			return t.mipmap ? this.samplerNearestMip : this.samplerNearest;
		}
		return t.mipmap ? this.samplerMip : this.samplerLinear;
	}

	private int shadowSamplerFor(final int shadowIdx, final int samplerGlType) {
		int sIdx = shadowIdx == 1 ? 1 : 0;
		boolean nearest = this.directives.shadowTexNearest[sIdx];
		if (samplerGlType == T_SAMPLER_2D_SHADOW) {
			return nearest ? this.samplerShadowNearest : this.samplerShadowLinear;
		}
		if (nearest || !this.directives.shadowHardwareFiltering[sIdx]) {
			return this.samplerNearest;
		}
		return this.samplerLinear;
	}

	private int shadowColorSamplerFor(final int idx) {
		if (idx < 0 || idx >= this.shadowColorFmts.length) {
			return this.samplerLinear;
		}
		if (!filterableFormat(this.shadowColorFmts[idx])) {
			return this.samplerNearest;
		}
		boolean mip = this.directives.shadowColorMipmap[idx];
		boolean nearest = this.directives.shadowColorNearest[idx];
		if (nearest) {
			return mip ? this.samplerNearestMip : this.samplerNearest;
		}
		return mip ? this.samplerMip : this.samplerLinear;
	}

	private Pass pass(final String name, final String vs, final String fs, final int[] drawBuffers) {
		Pending pd = this.startPass(name, vs, fs, drawBuffers, null, null, null);
		return pd == null ? null : this.finishPass(pd);
	}

	private boolean alphaTestNext;
	private String alphaTestDirectiveNext;

	private Pending startPass(final String name, final String vs, final String fs, final int[] drawBuffers,
		final Map<Integer, String> outputs, final Map<Integer, String> outputTypes, final PackPrograms.Program source) {
		boolean own = !name.startsWith("shaderpack_") && !name.equals("final");
		String frag = fs;
		boolean customAlpha = false;
		if (own && this.alphaTestNext && outputs != null && outputs.get(0) != null) {
			String out0Type = outputTypes == null ? null : outputTypes.get(0);
			if (hasFloatAlpha(out0Type)) {
				String dir = this.alphaTestDirectiveNext;
				if (dir != null && !dir.trim().isEmpty()) {
					customAlpha = true;
				}
				frag = alphaTest(frag, outputs.get(0), dir);
			}
		}
		this.alphaTestNext = false;
		this.alphaTestDirectiveNext = null;
		boolean isShadow = name.startsWith("shadow");
		String finalFrag = own && !isShadow ? this.emulateFixedPoint(frag, drawBuffers, outputs, outputTypes) : frag;
		int program = this.startLink(vs, finalFrag, name);
		if (program == 0) {
			return null;
		}
		Pending pd = new Pending();
		pd.name = name;
		pd.program = program;
		pd.vsShader = this.lastVsShader;
		pd.fsShader = this.lastFsShader;
		pd.linkingEntity = this.linkingEntity;
		pd.entityLocations = this.linkingEntity && this.entityLocations != null ? this.entityLocations.clone() : null;
		pd.drawBuffers = drawBuffers;
		pd.outputs = outputs;
		pd.outputTypes = outputTypes;
		pd.source = source;
		pd.vertexSource = this.lastVertexSource;
		pd.fragmentSource = this.lastFragmentSource;
		pd.hasCustomAlphaTest = customAlpha;
		pd.unboundColorLoc = this.lastUnboundColorLoc;
		pd.unboundNormalLoc = this.lastUnboundNormalLoc;
		return pd;
	}

	private Pass finishPass(final Pending pd) {
		String name = pd.name;
		int program = pd.program;
		int[] drawBuffers = pd.drawBuffers == null ? new int[0] : pd.drawBuffers;
		int vs = pd.vsShader;
		int fs = pd.fsShader;
		pd.vsShader = 0;
		pd.fsShader = 0;
		if (GlApi.glGetProgrami(program, LINK_STATUS) == 0) {
			boolean vsOk = vs != 0 && GlApi.glGetShaderi(vs, COMPILE_STATUS) != 0;
			boolean fsOk = fs != 0 && GlApi.glGetShaderi(fs, COMPILE_STATUS) != 0;
			if (!vsOk || !fsOk) {
				String vsErr = !vsOk && vs != 0 ? GlApi.glGetShaderInfoLog(vs) : "";
				String fsErr = !fsOk && fs != 0 ? GlApi.glGetShaderInfoLog(fs) : "";
				GlApi.glDeleteProgram(program);
				if (!vsOk) {
					if (vs != 0) {
						GlApi.glDeleteShader(vs);
					}
					vs = this.compileAfterError(VERTEX_SHADER, pd.vertexSource, name + ".vsh", vsErr);
					if (vs != 0) {
						pd.vertexSource = this.lastSource;
					}
				}
				if (!fsOk) {
					if (fs != 0) {
						GlApi.glDeleteShader(fs);
					}
					fs = vs == 0 ? 0 : this.compileAfterError(FRAGMENT_SHADER, pd.fragmentSource, name + ".fsh", fsErr);
					if (fs != 0) {
						pd.fragmentSource = this.lastSource;
					}
				}
				if (vs == 0 || fs == 0) {
					if (vs != 0) {
						GlApi.glDeleteShader(vs);
					}
					if (fs != 0) {
						GlApi.glDeleteShader(fs);
					}
					return null;
				}
				program = GlApi.glCreateProgram();
				GlApi.glAttachShader(program, vs);
				GlApi.glAttachShader(program, fs);
				this.bindProgramAttributes(program, name, pd.linkingEntity, pd.entityLocations);
				pd.unboundColorLoc = this.lastUnboundColorLoc;
				pd.unboundNormalLoc = this.lastUnboundNormalLoc;
				GlApi.glLinkProgram(program);
				GlApi.glDeleteShader(vs);
				GlApi.glDeleteShader(fs);
				if (GlApi.glGetProgrami(program, LINK_STATUS) == 0) {
					this.log.add(name + ": link failed: " + firstLines(GlApi.glGetProgramInfoLog(program), 3));
					GlApi.glDeleteProgram(program);
					return null;
				}
				pd.program = program;
			} else {
				if (vs != 0) {
					GlApi.glDeleteShader(vs);
				}
				if (fs != 0) {
					GlApi.glDeleteShader(fs);
				}
				this.log.add(name + ": link failed: " + firstLines(GlApi.glGetProgramInfoLog(program), 3));
				GlApi.glDeleteProgram(program);
				return null;
			}
		} else {
			if (vs != 0) {
				GlApi.glDeleteShader(vs);
			}
			if (fs != 0) {
				GlApi.glDeleteShader(fs);
			}
		}
		Pass pass = new Pass();
		pass.name = name;
		pass.vertexSource = pd.vertexSource;
		pass.fragmentSource = pd.fragmentSource;
		pass.program = program;
		pass.drawBuffers = drawBuffers;
		pass.isWorldPass = pd.isWorldPass;
		pass.marksScene = pd.marksScene;
		pass.hasCustomAlphaTest = pd.hasCustomAlphaTest;
		pass.unboundColorLoc = pd.unboundColorLoc;
		pass.unboundNormalLoc = pd.unboundNormalLoc;
		if (pd.source != null) {
			parseBlendDirectives(pass, pd.source.blend, pd.source.bufferBlends);
		}

		boolean internal = name.startsWith("shaderpack_");
		boolean isShadow = name.startsWith("shadow");
		if (!internal && !name.equals("final") && !isShadow) {
			for (int idx : drawBuffers) {
				if (idx >= 0 && idx < ShaderPack.MAX_COLOR_TARGETS) {
					this.target(idx);
				}
			}
		}

		GlStateManager._glUseProgram(program);
		boolean world = isWorldProgramName(name);
		// World programs share units 0..2 with vanilla's Sampler0/Sampler1/Sampler2
		int unit = world ? 3 : 0;
		for (Object[] u : GlApi.activeUniforms(program)) {
			String uname = (String) u[0];
			int type = (Integer) u[1];
			if (uname.endsWith("[0]")) {
				uname = uname.substring(0, uname.length() - 3);
			}
			int location = GlApi.glGetUniformLocation(program, uname);
			if (location < 0) {
				continue;
			}
			if (isSampler(type) && world && (uname.equals("gtexture") || uname.equals("texture") || uname.equals("tex") || uname.equals("lightmap"))) {
				boolean atlas = !uname.equals("lightmap");
				pass.samplers.add(new int[] {-1, atlas ? K_ATLAS : K_LIGHTMAP, location, type});
				continue;
			}
			if (isSampler(type)) {
				int[] binding = world && (uname.equals("normals") || uname.equals("specular"))
					? new int[] {uname.equals("normals") ? K_FLAT_NORMAL : K_BLACK, 0}
					: this.samplerBinding(uname, type, internal, stageOf(name));
				GlApi.glUniform1i(location, unit);
				pass.samplers.add(new int[] {unit, binding[0], binding[1], type});
				unit++;
				continue;
			}
			ShaderUniforms.UniformEntry entry = new ShaderUniforms.UniformEntry(uname, location, type);
			pass.uniforms.add(entry);
			if (PER_DRAW_UNIFORM_NAMES.contains(uname)) {
				pass.perDrawUniforms.add(entry);
			}
		}
		if (world) {
			// Reserve a dedicated unit for fallback white lightmap when an emissive pipeline omits Sampler2
			pass.fallbackLightUnit = unit < maxTexUnits ? unit : 2;
		}
		if (unit > 16) {
			this.log.add(name + ": uses " + unit + " samplers; WebGL2 guarantees 16");
		}
		return pass;
	}

	/**
	 * Injects alpha testing into {@code main()}. Never called for non-vec4 outputs (vec3, ivec*, uvec*).
	 * Honors {@code alphaTest.<prog>} directives ({@code off}, {@code ALWAYS}, {@code NEVER},
	 * {@code GREATER/GEQUAL/LESS/LEQUAL/EQUAL/NOTEQUAL <ref>}) or defaults to dynamic
	 * {@code iris_AlphaTestRef} (0.0 disables discard for solid geometry, 0.1 for cutout).
	 */
	private static String alphaTest(final String fs, final String out0, final String directive) {
		Matcher m = MAIN_DECL.matcher(fs);
		if (!m.find()) {
			return fs;
		}
		if (directive != null && !directive.trim().isEmpty()) {
			String d = directive.trim();
			String upper = d.toUpperCase(Locale.ROOT);
			if (upper.equals("OFF") || upper.equals("FALSE") || upper.equals("ALWAYS")) {
				return fs;
			}
			if (upper.equals("NEVER")) {
				return fs.substring(0, m.start()) + "void iris_alphaMain()" + fs.substring(m.end())
					+ "\nvoid main() { iris_alphaMain(); discard; }\n";
			}
			String[] parts = upper.split("\\s+");
			if (parts.length >= 2) {
				String op;
				switch (parts[0]) {
					case "GREATER": op = "<="; break;
					case "GEQUAL": op = "<"; break;
					case "LESS": op = ">="; break;
					case "LEQUAL": op = ">"; break;
					case "EQUAL": op = "!="; break;
					case "NOTEQUAL": op = "=="; break;
					default: op = null; break;
				}
				if (op != null) {
					try {
						float ref = Float.parseFloat(parts[1]);
						return fs.substring(0, m.start()) + "void iris_alphaMain()" + fs.substring(m.end())
							+ "\nvoid main() { iris_alphaMain(); if (" + out0 + ".a " + op + " " + ref + ") { discard; } }\n";
					} catch (NumberFormatException ignored) {
						// fall through to default dynamic alpha test
					}
				}
			}
		}
		return fs.substring(0, m.start()) + "void iris_alphaMain()" + fs.substring(m.end())
			+ "\nuniform float iris_AlphaTestRef;\n"
			+ "void main() { iris_alphaMain(); if (iris_AlphaTestRef > 0.0 && " + out0 + ".a < iris_AlphaTestRef) { discard; } }\n";
	}

	private static String markScene(final String fs, final int marker) {
		Matcher m = MAIN_DECL.matcher(fs);
		if (!m.find()) {
			return fs;
		}
		return fs.substring(0, m.start()) + "void iris_markMain()" + fs.substring(m.end())
			+ "\nlayout(location = " + marker + ") out vec4 iris_SceneMarker;\n"
			+ "void main() { iris_markMain(); iris_SceneMarker = vec4(0.0); }\n";
	}

	// ---- blend directive parsing & application ----

	private static void parseBlendDirectives(final Pass pass, final String globalBlend, final Map<Integer, String> bufferBlends) {
		if (globalBlend != null && !globalBlend.trim().isEmpty()) {
			String g = globalBlend.trim();
			if (g.equalsIgnoreCase("off")) {
				pass.hasCustomBlend = true;
				pass.blendOff = true;
			} else {
				int[] factors = parseBlendFactors(g);
				if (factors != null) {
					pass.hasCustomBlend = true;
					pass.globalBlend = factors;
				}
			}
		}
		if (bufferBlends != null && !bufferBlends.isEmpty()) {
			Map<Integer, int[]> parsed = new HashMap<>();
			for (Map.Entry<Integer, String> e : bufferBlends.entrySet()) {
				String v = e.getValue().trim();
				if (v.equalsIgnoreCase("off")) {
					parsed.put(e.getKey(), new int[0]);
				} else {
					int[] factors = parseBlendFactors(v);
					if (factors != null) {
						parsed.put(e.getKey(), factors);
					}
				}
			}
			if (!parsed.isEmpty()) {
				pass.hasCustomBlend = true;
				pass.perBufferBlend = parsed;
			}
		}
	}

	private static int[] parseBlendFactors(final String spec) {
		String[] parts = spec.trim().split("\\s+");
		if (parts.length < 2) {
			return null;
		}
		int sRgb = blendFactor(parts[0]);
		int dRgb = blendFactor(parts[1]);
		if (sRgb < 0 || dRgb < 0) {
			return null;
		}
		int sA = parts.length >= 4 ? blendFactor(parts[2]) : sRgb;
		int dA = parts.length >= 4 ? blendFactor(parts[3]) : dRgb;
		if (sA < 0 || dA < 0) {
			sA = sRgb;
			dA = dRgb;
		}
		return new int[] {sRgb, dRgb, sA, dA};
	}

	private static int blendFactor(final String raw) {
		String s = raw.toUpperCase(Locale.ROOT);
		if (s.startsWith("GL_")) {
			s = s.substring(3);
		}
		switch (s) {
			case "ZERO": return 0;
			case "ONE": return 1;
			case "SRC_COLOR": return 768;
			case "ONE_MINUS_SRC_COLOR": return 769;
			case "SRC_ALPHA": return 770;
			case "ONE_MINUS_SRC_ALPHA": return 771;
			case "DST_ALPHA": return 772;
			case "ONE_MINUS_DST_ALPHA": return 773;
			case "DST_COLOR": return 774;
			case "ONE_MINUS_DST_COLOR": return 775;
			case "SRC_ALPHA_SATURATE": return 776;
			default: return -1;
		}
	}

	private void disableAllBlend() {
		GlStateManager._disableBlend(0);
		if (this.hasIndexedBlend) {
			for (int i = 1; i < this.maxDrawBuffers; i++) {
				GlStateManager._disableBlend(i);
			}
		}
	}

	private void applyBlend(final Pass p) {
		if (!p.hasCustomBlend) {
			this.disableAllBlend();
			return;
		}
		this.disableAllBlend();
		GlStateManager._blendEquationSeparate(FUNC_ADD, FUNC_ADD);
		int[] db = p.drawBuffers;
		int len = db == null ? 0 : Math.min(db.length, this.maxDrawBuffers);
		if (!this.hasIndexedBlend) {
			// Single global blend state: honor slot 0 buffer override if present, else globalBlend
			int[] f = p.globalBlend;
			boolean off = p.blendOff;
			if (p.perBufferBlend != null && len > 0 && db[0] >= 0) {
				int[] b0 = p.perBufferBlend.get(db[0]);
				if (b0 != null) {
					off = b0.length == 0;
					f = off ? null : b0;
				}
			}
			Target t0 = len > 0 && db[0] >= 0 && db[0] < ShaderPack.MAX_COLOR_TARGETS ? this.targets[db[0]] : null;
			if (!off && f != null && (t0 == null || t0.format.intKind == 0)) {
				GlStateManager._enableBlend(0);
				GlStateManager._blendFuncSeparate(f[0], f[1], f[2], f[3]);
			}
			return;
		}
		// Indexed per-draw-buffer blend via OES_draw_buffers_indexed
		for (int i = 0; i < len; i++) {
			int colIdx = db[i];
			if (colIdx < 0 || colIdx >= ShaderPack.MAX_COLOR_TARGETS) {
				continue;
			}
			Target t = this.targets[colIdx];
			if (t != null && t.format.intKind != 0) {
				continue; // WebGL2 disallows blending on integer render targets
			}
			int[] f = p.blendOff ? null : p.globalBlend;
			if (p.perBufferBlend != null) {
				int[] ov = p.perBufferBlend.get(colIdx);
				if (ov != null) {
					f = ov.length == 0 ? null : ov;
				}
			}
			if (f != null) {
				GlStateManager._enableBlend(i);
				GlApi.glBlendFuncSeparateiARB(i, f[0], f[1], f[2], f[3]);
			}
		}
	}

	/**
	 * Extracts SPIR-V attribute locations for {Position, Color, UV0, UV2, Normal} from a pipeline's
	 * {@link com.mojang.renderpearl.api.vertex.VertexFormat} and {@code web263Offsets}.
	 */
	private static int[] pipelineLayout(final Object backend, final com.mojang.renderpearl.frontend.FrontendRenderPipeline frontend) {
		int[] out = {-1, -1, -1, -1, -1};
		if (!(backend instanceof com.mojang.renderpearl.backend.opengl.GlRenderPipeline gl)) {
			return new int[] {0, 1, 2, 4, 5};
		}
		int[] offsets = gl.vertexArray().web263Offsets;
		int[] want = {0, 12, 16, 28, 32}; // fallback ENTITY byte offsets
		if (frontend != null && !frontend.vertexFormats().isEmpty()) {
			com.mojang.renderpearl.api.vertex.VertexFormat vf = frontend.vertexFormats().get(0);
			if (vf != null) {
				String[] names = {"Position", "Color", "UV0", "UV2", "Normal"};
				for (int i = 0; i < names.length; i++) {
					com.mojang.renderpearl.api.vertex.VertexFormatElement el = vf.getElement(names[i]);
					want[i] = el != null ? el.offset() : -1;
				}
			}
		}
		for (int i = 0; i < want.length; i++) {
			if (want[i] < 0) {
				continue;
			}
			for (int loc = 0; loc < offsets.length; loc++) {
				if (offsets[loc] == want[i]) {
					out[i] = loc;
					break;
				}
			}
		}
		return out;
	}

	private Pending startEntityVariant(final WorldProgramGroup group, final int[] layout) {
		this.entityLocations = layout;
		this.linkingEntity = true;
		this.alphaTestNext = true;
		this.alphaTestDirectiveNext = group.source.alphaTest;
		try {
			Pending pd = this.startPass(group.source.name, group.source.vertex, group.fragment,
				group.source.drawBuffers, group.source.outputs, group.source.outputTypes, group.source);
			if (pd != null) {
				pd.isWorldPass = true;
				pd.marksScene = group.marksScene;
			}
			return pd;
		} finally {
			this.linkingEntity = false;
		}
	}

	/** Lazily links a layout variant in the background if not already pre-linked during loading. */
	private Pass entityVariant(final WorldProgramGroup group, final int[] layout, final String key) {
		Pass p = group.variants.get(key);
		if (p != null || group.variants.containsKey(key)) {
			return p;
		}
		Pending pd = group.pendingVariants.get(key);
		if (pd == null) {
			pd = this.startEntityVariant(group, layout);
			if (pd == null) {
				group.variants.put(key, null);
				return null;
			}
			group.pendingVariants.put(key, pd);
		}
		if (!GlApi.glProgramDone(pd.program)) {
			return null;
		}
		group.pendingVariants.remove(key);
		p = this.finishPass(pd);
		group.variants.put(key, p);
		if (p != null) {
			this.log.add(group.source.name + " linked for attribute layout " + key);
		}
		return p;
	}

	private static boolean isSampler(final int type) {
		return type == 35678 || type == 35679 || type == 35680 || type == 35682 || type == 36289 || type == 36292
			|| type == 36293 || (type >= 36298 && type <= 36311);
	}

	/** Custom-texture stage namespace (keeps final and shadow isolated, with fallback lookup in samplerBinding). */
	private static String stageOf(final String pass) {
		if (pass.equals("final")) {
			return "final";
		}
		if (pass.startsWith("shadow")) {
			return "shadow";
		}
		if (pass.startsWith("gbuffers_")) {
			return "gbuffers";
		}
		if (pass.startsWith("prepare")) {
			return "prepare";
		}
		if (pass.startsWith("deferred")) {
			return "deferred";
		}
		return "composite";
	}

	private int[] samplerBinding(final String name, final int type, final boolean internal, final String stage) {
		if (internal) {
			if (name.equals("iris_SceneColor")) {
				return new int[] {K_MAIN, 0};
			}
			if (name.equals("iris_SceneDepth")) {
				return new int[] {K_MAIN_DEPTH, 0};
			}
		}
		if (type != T_SAMPLER_2D && type != T_SAMPLER_2D_SHADOW && type != T_ISAMPLER_2D && type != T_USAMPLER_2D) {
			return new int[] {K_NONE, 0};
		}
		if (name.equals("iris_BlockIdTable")) {
			return new int[] {K_CUSTOM, this.blockIdTex};
		}
		// Stage-isolated custom texture lookup with Iris fallbacks (final -> composite, shadow -> gbuffers)
		Integer custom = this.customBySampler.get(stage + ":" + name);
		if (custom == null && stage.equals("final")) {
			custom = this.customBySampler.get("composite:" + name);
		} else if (custom == null && stage.equals("shadow")) {
			custom = this.customBySampler.get("gbuffers:" + name);
		}
		if (custom != null) {
			return new int[] {K_CUSTOM, custom};
		}
		int color = ShaderPack.colorTargetIndex(name);
		if (color >= 0) {
			this.target(color);
			return new int[] {K_COLOR, color};
		}
		int shadowColor = ShaderPack.shadowColorTargetIndex(name);
		if (shadowColor >= 0) {
			this.usedShadowColors[shadowColor] = true;
			return new int[] {K_SHADOW_COLOR, shadowColor};
		}
		switch (name) {
			case "depthtex0":
				if (stage.equals("gbuffers")) {
					this.needsOpaqueDepth = true;
				}
				return new int[] {K_DEPTH, 0};
			case "gdepthtex":
				this.needsOpaqueDepth = true;
				return new int[] {K_DEPTH, 0};
			case "depthtex1":
			case "depthtex2":
				this.needsOpaqueDepth = true;
				return new int[] {K_DEPTH, 1};
			case "noisetex":
				return new int[] {K_NOISE, 0};
			case "shadow":
			case "shadowtex0":
			case "shadowtex0HW":
				return new int[] {K_SHADOW, 0};
			case "watershadow":
			case "shadowtex1":
			case "shadowtex1HW":
				return new int[] {K_SHADOW, 1};
			default:
				return new int[] {K_BLACK, 0};
		}
	}

	private Target target(final int index) {
		Target t = this.targets[index];
		if (t == null) {
			boolean float32Ok = GlApi.hasFloatBlend() && GlApi.hasFloatLinear();
			Format fmt = format(this.directives.formats[index], this.floatTargets, float32Ok, this.directives.mipmap[index]);
			t = new Target(index, fmt);
			t.mipmap = this.directives.mipmap[index] && filterable(t);
			t.nearest = this.directives.nearest[index] || !filterable(t);
			Boolean clear = this.directives.clear[index];
			t.clear = clear == null || clear;
			float[] c = this.directives.clearColor[index];
			t.clearColor = c != null ? c.clone() : index == 1 ? new float[] {1, 1, 1, 1} : new float[] {0, 0, 0, 0};
			for (int k = 0; k < 4; k++) {
				t.clearColorInt[k] = (int) t.clearColor[k];
			}
			this.targets[index] = t;
			// If lazily requested after ensureSize() (e.g. lazy entity variant), allocate immediately
			if (this.width > 0 && this.height > 0) {
				int tw = this.width;
				int th = this.height;
				ShaderPack.BufferSize bs = this.directives.bufferSizes[index];
				if (bs != null) {
					tw = bs.relative ? Math.max(1, Math.round(this.width * bs.width)) : Math.max(1, Math.round(bs.width));
					th = bs.relative ? Math.max(1, Math.round(this.height * bs.height)) : Math.max(1, Math.round(bs.height));
				}
				t.main = colorTexture(t, tw, th);
				t.alt = colorTexture(t, tw, th);
				t.width = tw;
				t.height = th;
				this.clearTargetBothOnResize(t);
			}
		}
		return t;
	}

	// ---- resize, FBO caching, and single-bind MRT clearing ----

	private void ensureSize(final int w, final int h) {
		if (w == this.width && h == this.height) {
			return;
		}
		this.width = w;
		this.height = h;
		this.resizeGen++;
		this.staleMips.clear();

		// Tear down old MRT clear batches
		for (ClearBatch batch : this.clearBatches) {
			deleteFramebuffer(batch.fbo);
			if (batch.altFbo != 0) {
				deleteFramebuffer(batch.altFbo);
			}
		}
		this.clearBatches.clear();

		// Recreate depth textures
		if (this.depthTex != 0) {
			deleteTexture(this.depthTex);
		}
		this.depthTex = depthTexture(w, h);
		if (this.sceneRedirect != null) {
			if (this.depthTexOpaque != 0) {
				deleteTexture(this.depthTexOpaque);
			}
			this.depthTexOpaque = depthTexture(w, h);
		}

		// Recreate color targets honoring Directives.bufferSizes (size.buffer.*), clearing both main & alt once
		Map<Long, List<Target>> clearGroups = new LinkedHashMap<>();
		for (Target t : this.targets) {
			if (t == null) {
				continue;
			}
			if (t.main != 0) {
				deleteTexture(t.main);
				deleteTexture(t.alt);
			}
			int tw = w;
			int th = h;
			ShaderPack.BufferSize bs = this.directives.bufferSizes[t.index];
			if (bs != null) {
				tw = bs.relative ? Math.max(1, Math.round(w * bs.width)) : Math.max(1, Math.round(bs.width));
				th = bs.relative ? Math.max(1, Math.round(h * bs.height)) : Math.max(1, Math.round(bs.height));
			}
			t.main = colorTexture(t, tw, th);
			t.alt = colorTexture(t, tw, th);
			t.width = tw;
			t.height = th;
			this.clearTargetBothOnResize(t);
			if (t.clear) {
				long dimKey = ((long) tw << 32) | (th & 0xFFFFFFFFL);
				clearGroups.computeIfAbsent(dimKey, k -> new ArrayList<>()).add(t);
			}
		}

		// Build pre-validated MRT clear FBO batches (up to maxDrawBuffers targets per bind)
		for (List<Target> group : clearGroups.values()) {
			int n = group.size();
			for (int off = 0; off < n; off += this.maxDrawBuffers) {
				int count = Math.min(this.maxDrawBuffers, n - off);
				Target[] batchTargets = new Target[count];
				boolean needAlt = false;
				for (int i = 0; i < count; i++) {
					batchTargets[i] = group.get(off + i);
					needAlt |= batchTargets[i].blendedWrite;
				}
				int mainFbo = this.buildClearBatchFbo(batchTargets, false);
				int altFbo = needAlt ? this.buildClearBatchFbo(batchTargets, true) : 0;
				this.clearBatches.add(new ClearBatch(mainFbo, altFbo, batchTargets));
			}
		}

		// Invalidate world FBO tracking; all Pass FBO caches auto-rebuild via fboGen != resizeGen
		this.ourWorldFbos.clear();
		this.worldPassByFbo.clear();
		this.invalidateAllPassFbos();
	}

	private int buildClearBatchFbo(final Target[] batchTargets, final boolean useAlt) {
		int fbo = GlApi.glGenFramebuffers();
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, fbo);
		int[] bufs = new int[batchTargets.length];
		for (int i = 0; i < batchTargets.length; i++) {
			Target t = batchTargets[i];
			GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0 + i, TEXTURE_2D, useAlt ? t.alt : t.main, 0);
			bufs[i] = COLOR_ATTACHMENT0 + i;
		}
		GlApi.glDrawBuffers(bufs);
		return fbo;
	}

	private void invalidateAllPassFbos() {
		for (Pass p : this.passes) {
			this.clearPassFbos(p);
		}
		for (WorldProgramGroup g : this.worldGroupsByProgram.values()) {
			if (g.terrainPass != null) {
				this.clearPassFbos(g.terrainPass);
			}
			for (Pass vp : g.variants.values()) {
				if (vp != null) {
					this.clearPassFbos(vp);
				}
			}
		}
		if (this.prep != null) {
			this.clearPassFbos(this.prep);
		}
		if (this.copy != null) {
			this.clearPassFbos(this.copy);
		}
		if (this.sceneRedirect != null) {
			this.clearPassFbos(this.sceneRedirect);
		}
	}

	private void clearPassFbos(final Pass p) {
		for (int i = 0, n = p.fboCache.size(); i < n; i++) {
			int fbo = p.fboCache.get(i).fbo;
			this.ourWorldFbos.remove(fbo);
			this.worldPassByFbo.remove(fbo);
			deleteFramebuffer(fbo);
		}
		p.fboCache.clear();
		p.fboGen = -1;
	}

	private static int colorTexture(final Target t, final int w, final int h) {
		int tex = GlApi.glGenTextures();
		bindUnit0(tex);
		resetUnpack();
		GlApi.glTexImage2D(TEXTURE_2D, 0, t.format.internal, w, h, 0, t.format.format, t.format.type, (ByteBuffer) null);
		int filter = (t.nearest || !filterable(t)) ? NEAREST : LINEAR;
		GlApi.glTexParameteri(TEXTURE_2D, MIN_FILTER, filter);
		GlApi.glTexParameteri(TEXTURE_2D, MAG_FILTER, filter);
		GlApi.glTexParameteri(TEXTURE_2D, WRAP_S, CLAMP_TO_EDGE);
		GlApi.glTexParameteri(TEXTURE_2D, WRAP_T, CLAMP_TO_EDGE);
		return tex;
	}

	/** Clears both main and alt sides of one target on resize (handles float, signed int, and uint formats). */
	private void clearTargetBothOnResize(final Target t) {
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.clearFbo);
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, 0, 0);
		GlApi.glDrawBuffers(DRAW_BUF_0);
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, t.main, 0);
		clearBufferSlot(0, t);
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, t.alt, 0);
		clearBufferSlot(0, t);
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, 0, 0);
	}

	private static void clearBufferSlot(final int slot, final Target t) {
		if (t.format.intKind == 1) {
			GlApi.glClearBufferiv(COLOR, slot, t.clearColorInt);
		} else if (t.format.intKind == 2) {
			GlApi.glClearBufferuiv(COLOR, slot, t.clearColorInt);
		} else {
			GlApi.glClearBufferfv(COLOR, slot, t.clearColor);
		}
	}

	/** Single-bind per-batch MRT clear of all {@code t.clear} targets' {@code main} sides. */
	private void clearTargetsForFrame(final ShaderUniforms uniforms, final boolean includeSceneTarget) {
		Target t0 = this.targets[0];
		if (t0 != null && t0.clear && this.directives.clearColor[0] == null) {
			float[] fog = uniforms.value("fogColor");
			if (fog != null && fog.length >= 3) {
				t0.clearColor[0] = fog[0];
				t0.clearColor[1] = fog[1];
				t0.clearColor[2] = fog[2];
				t0.clearColor[3] = 1.0F;
				if (t0.format.intKind != 0) {
					t0.clearColorInt[0] = (int) fog[0];
					t0.clearColorInt[1] = (int) fog[1];
					t0.clearColorInt[2] = (int) fog[2];
					t0.clearColorInt[3] = 1;
				}
			}
		}
		for (int b = 0, bn = this.clearBatches.size(); b < bn; b++) {
			ClearBatch batch = this.clearBatches.get(b);
			GlStateManager._glBindFramebuffer(FRAMEBUFFER, batch.fbo);
			Target[] ts = batch.targets;
			for (int i = 0; i < ts.length; i++) {
				Target t = ts[i];
				if (t.index != this.sceneTarget || includeSceneTarget) {
					clearBufferSlot(i, t);
				}
			}
			if (batch.altFbo != 0) {
				GlStateManager._glBindFramebuffer(FRAMEBUFFER, batch.altFbo);
				for (int i = 0; i < ts.length; i++) {
					Target t = ts[i];
					if (t.blendedWrite && (t.index != this.sceneTarget || includeSceneTarget)) {
						clearBufferSlot(i, t);
					}
				}
			}
		}
	}

	private void markAllMipTargetsStale() {
		for (Target t : this.targets) {
			if (t != null && t.mipmap && t.main != 0) {
				this.staleMips.add(t.main);
				this.staleMips.add(t.alt);
			}
		}
	}

	// ---- per frame ----

	public void render(final int mainColor, final int mainDepth, final int w, final int h, final ShaderUniforms uniforms) {
		if (this.broken) {
			return;
		}
		try {
			this.renderUnchecked(mainColor, mainDepth, w, h, uniforms);
		} catch (RuntimeException e) {
			this.broken = true;
			this.log.add("render failed: " + e);
			System.out.println("[shaderpack] render failed, turning the pack off for this session: " + e);
		} finally {
			resyncGlState();
		}
	}

	public boolean broken() {
		return this.broken;
	}

	private void renderUnchecked(final int mainColor, final int mainDepth, final int w, final int h, final ShaderUniforms uniforms) {
		this.worldFrame = false;
		ShaderPacks.atlasSampler = 0;
		uniforms.setRenderStage(ShaderUniforms.STAGE_NONE);
		boolean mid = this.midDeferred && this.width == w && this.height == h;
		boolean snapped = this.depthSnapshotted && this.width == w && this.height == h;
		this.midDeferred = false;
		this.depthSnapshotted = false;
		this.opaqueDepthValid = mid || snapped;

		this.disableAllBlend();
		GlStateManager._disableCull();
		GlStateManager._disableScissorTest();
		GlStateManager._disablePolygonOffset();
		GlStateManager._colorMaskAll(true, true, true, true);
		this.ensureSize(w, h);
		this.markAllMipTargetsStale();

		GlStateManager._glBindVertexArray(this.vao);
		GlApi.glVertexAttrib4f(2, 1.0F, 1.0F, 1.0F, 1.0F);
		GlApi.glVertexAttrib4f(3, 0.0F, 0.0F, 1.0F, 0.0F);
		GlApi.glVertexAttrib4f(4, 240.0F, 240.0F, 0.0F, 1.0F);

		if (mid) {
			// Deferred already ran before translucents: update depthtex0 with translucents (keeping opaque depth in depthTexOpaque)
			int opaque = this.depthTex;
			this.depthTex = this.depthTexOpaque;
			this.depthTexOpaque = opaque;
			Target t0 = this.target(this.sceneTarget);
			uniforms.set("iris_KeepSky", 0.0F);
			uniforms.set("iris_DirectScene", 0.0F);
			GlStateManager._colorMaskAll(false, false, false, false);
			GlStateManager._enableDepthTest();
			GlStateManager._depthFunc(ALWAYS);
			GlStateManager._depthMask(true);
			this.scratchColors[0] = this.flipped[t0.index] ? t0.alt : t0.main;
			this.attach(this.prep, this.scratchColors, 1, this.depthTex);
			GlApi.glClearBufferfv(DEPTH, 0, DEPTH_ONE);
			this.draw(this.prep, mainColor, mainDepth, uniforms, w, h);
			GlStateManager._disableDepthTest();
			GlStateManager._depthMask(false);
			GlStateManager._colorMaskAll(true, true, true, true);
		} else {
			this.clearsAndDeferred(mainColor, mainDepth, w, h, uniforms);
		}

		int passIndex = 0;
		boolean finalRan = false;
		for (int i = 0, n = this.passes.size(); i < n; i++) {
			Pass p = this.passes.get(i);
			if (p.name.startsWith("prepare") || p.name.startsWith("deferred")) {
				continue;
			}
			if (p.enabledExpr != null && !uniforms.evalEnabled(p.enabledExpr)) {
				continue;
			}
			if (debugPasses >= 0 && passIndex >= debugPasses) {
				break;
			}
			passIndex++;
			if (p.isFinal && debugShow >= 0) {
				continue;
			}
			if (p.isFinal) {
				this.applyBlend(p);
				this.scratchColors[0] = mainColor;
				this.attach(p, this.scratchColors, 1, 0);
				this.draw(p, mainColor, mainDepth, uniforms, w, h);
				if (p.hasCustomBlend) {
					this.disableAllBlend();
				}
				finalRan = true;
				continue;
			}
			this.runPass(p, mainColor, mainDepth, uniforms, w, h);
		}
		if (!finalRan) {
			// Respect this.sceneTarget (not hardcoded 0) as fallback when final is absent
			int show = debugShow >= 0 && debugShow < ShaderPack.MAX_COLOR_TARGETS && this.targets[debugShow] != null
				? debugShow : this.sceneTarget;
			this.copySource = show;
			this.disableAllBlend();
			this.scratchColors[0] = mainColor;
			this.attach(this.copy, this.scratchColors, 1, 0);
			this.draw(this.copy, mainColor, mainDepth, uniforms, w, h);
		}

		// Persistent buffers (!clear) start the next frame in main; cached FBOs are keyed by texture ID so no invalidation needed
		for (Target t : this.targets) {
			if (t != null && !t.clear && this.flipped[t.index]) {
				int swap = t.main;
				t.main = t.alt;
				t.alt = swap;
			}
		}
		this.prepareRanThisFrame = false;
	}

	/**
	 * Runs clears and prepare* (unless already run in {@link #beginWorld}), copies vanilla scene +
	 * standard depth into the scene target via {@code prep}, then runs {@code deferred*}.
	 */
	private void clearsAndDeferred(final int mainColor, final int mainDepth, final int w, final int h, final ShaderUniforms uniforms) {
		if (!this.prepareRanThisFrame) {
			this.clearTargetsForFrame(uniforms, this.hasPreparePasses);
			Arrays.fill(this.flipped, false);
			this.sceneTargetPrepared = false;
			for (int i = 0, n = this.passes.size(); i < n; i++) {
				Pass p = this.passes.get(i);
				if (p.name.startsWith("prepare")) {
					if (p.enabledExpr != null && !uniforms.evalEnabled(p.enabledExpr)) {
						continue;
					}
					this.runPass(p, mainColor, mainDepth, uniforms, w, h);
					for (int idx : p.drawBuffers) {
						if (idx == this.sceneTarget) {
							this.sceneTargetPrepared = true;
						}
					}
				}
			}
		}

		Target t0 = this.target(this.sceneTarget);
		if (t0.main == 0) {
			t0.main = colorTexture(t0, w, h);
			t0.alt = colorTexture(t0, w, h);
			t0.width = w;
			t0.height = h;
		}
		boolean direct = this.directScene && this.worldRan;
		int sceneTex = this.flipped[t0.index] ? t0.alt : t0.main;
		this.disableAllBlend();
		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(ALWAYS);
		GlStateManager._depthMask(true);
		this.scratchColors[0] = sceneTex;
		this.attach(this.prep, this.scratchColors, 1, this.depthTex);
		GlApi.glClearBufferfv(DEPTH, 0, DEPTH_ONE);

		if (direct) {
			// Direct HDR/16-bit scene: write standard depth for every pixel first, then copy ONLY non-world vanilla pixels
			uniforms.set("iris_KeepSky", 0.0F);
			uniforms.set("iris_DirectScene", 0.0F);
			GlStateManager._colorMaskAll(false, false, false, false);
			this.draw(this.prep, mainColor, mainDepth, uniforms, w, h);
			GlStateManager._colorMaskAll(true, true, true, true);
			GlStateManager._depthMask(false);
			boolean keepSky = this.sceneTargetPrepared || this.skyBasicRanThisFrame || this.packedSceneTarget;
			uniforms.set("iris_KeepSky", keepSky ? 1.0F : 0.0F);
			uniforms.set("iris_DirectScene", 1.0F);
			this.draw(this.prep, mainColor, mainDepth, uniforms, w, h);
			uniforms.set("iris_DirectScene", 0.0F);
		} else {
			uniforms.set("iris_KeepSky", this.sceneTargetPrepared ? 1.0F : 0.0F);
			uniforms.set("iris_DirectScene", 0.0F);
			this.draw(this.prep, mainColor, mainDepth, uniforms, w, h);
		}
		GlStateManager._disableDepthTest();
		GlStateManager._depthMask(false);

		if (t0.mipmap && filterable(t0)) {
			this.staleMips.add(sceneTex);
		}

		for (int i = 0, n = this.passes.size(); i < n; i++) {
			Pass p = this.passes.get(i);
			if (p.name.startsWith("deferred")) {
				if (p.enabledExpr != null && !uniforms.evalEnabled(p.enabledExpr)) {
					continue;
				}
				this.runPass(p, mainColor, mainDepth, uniforms, w, h);
			}
		}
	}

	public boolean hasWorldProgram() {
		return this.terrainPass != null && !this.broken;
	}

	public boolean worldRan() {
		return this.worldRan;
	}

	/**
	 * Before the world is drawn: clears buffers and runs {@code prepare*} passes in Iris order so
	 * shadow and world programs can sample post-prepare LUTs without being overwritten.
	 */
	public void beginWorld(final int mainColor, final int mainDepth, final int w, final int h, final ShaderUniforms uniforms) {
		this.worldFrame = false;
		this.worldRan = false;
		this.prepareRanThisFrame = false;
		this.sceneTargetPrepared = false;
		this.skyBasicRanThisFrame = false;
		this.depthSnapshotted = false;
		this.opaqueDepthValid = false;
		if (this.terrainPass == null || this.broken) {
			return;
		}
		try {
			this.disableAllBlend();
			GlStateManager._disableCull();
			GlStateManager._disableScissorTest();
			GlStateManager._disablePolygonOffset();
			GlStateManager._colorMaskAll(true, true, true, true);
			this.ensureSize(w, h);
			this.clearTargetsForFrame(uniforms, this.directScene || this.hasPreparePasses);
			Arrays.fill(this.flipped, false);

			// Run prepare* passes BEFORE shadow/terrain/entities (Iris pipeline order)
			if (this.hasPreparePasses) {
				GlStateManager._glBindVertexArray(this.vao);
				GlApi.glVertexAttrib4f(2, 1.0F, 1.0F, 1.0F, 1.0F);
				GlApi.glVertexAttrib4f(3, 0.0F, 0.0F, 1.0F, 0.0F);
				GlApi.glVertexAttrib4f(4, 240.0F, 240.0F, 0.0F, 1.0F);
				for (int i = 0, n = this.passes.size(); i < n; i++) {
					Pass p = this.passes.get(i);
					if (p.name.startsWith("prepare")) {
						if (p.enabledExpr != null && !uniforms.evalEnabled(p.enabledExpr)) {
							continue;
						}
						this.runPass(p, mainColor, mainDepth, uniforms, w, h);
						for (int idx : p.drawBuffers) {
							if (idx == this.sceneTarget) {
								this.sceneTargetPrepared = true;
							}
						}
					}
				}
			}
			this.prepareRanThisFrame = true;
			this.worldMainColor = mainColor;
			this.worldMainDepth = mainDepth;
			this.worldWidth = w;
			this.worldHeight = h;
			this.shadowMode = false;
			this.worldUniforms = uniforms;
			this.worldFrame = true;
		} catch (RuntimeException e) {
			this.broken = true;
			this.log.add("world setup failed: " + e);
		} finally {
			resyncGlState();
		}
	}

	/**
	 * Called right after the {@code "Main"} RenderPass closes, before {@code executeOutline} /
	 * {@code executeSeeThrough}, restoring vanilla's FBO and stopping world interception.
	 */
	public void endWorldPass() {
		if (!this.worldFrame) {
			return;
		}
		this.worldFrame = false;
		ShaderPacks.atlasSampler = 0;
		int bound = GlStateManager.getFrameBuffer(DRAW_FRAMEBUFFER);
		if (this.ourWorldFbos.contains(bound)) {
			int ret = this.midDeferred && this.midFbo != 0 ? this.midFbo : this.savedFbo;
			GlStateManager._glBindFramebuffer(FRAMEBUFFER, ret);
			GlApi.glDrawBuffers(DRAW_BUF_0);
		}
	}

	private void initWorldPipelines() {
		this.worldPipelinesInitialized = true;
		this.solidPipeline = backend(net.minecraft.client.renderer.RenderPipelines.SOLID_TERRAIN);
		this.solidMultiPipeline = backend(net.minecraft.client.renderer.RenderPipelines.SOLID_TERRAIN_MULTIDRAW);
		this.cutoutPipeline = backend(net.minecraft.client.renderer.RenderPipelines.CUTOUT_TERRAIN);
		this.cutoutMultiPipeline = backend(net.minecraft.client.renderer.RenderPipelines.CUTOUT_TERRAIN_MULTIDRAW);
		this.translucentPipeline = backend(net.minecraft.client.renderer.RenderPipelines.TRANSLUCENT_TERRAIN);
		this.translucentMultiPipeline = backend(net.minecraft.client.renderer.RenderPipelines.TRANSLUCENT_TERRAIN_MULTIDRAW);

		int covered = 0;
		for (com.mojang.renderpearl.api.pipeline.RenderPipeline rp : net.minecraft.client.renderer.RenderPipelines.requiredPipelines()) {
			String key = classifyWorldPipelineProgram(rp);
			if (key == null) {
				continue;
			}
			WorldProgramGroup group = this.resolveWorldGroup(key);
			if (group == null || group.terrainLayout) {
				continue;
			}
			Object compiled = com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(rp);
			if (!(compiled instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline f)) {
				continue;
			}
			Object be = f.backendRenderPipeline();
			int[] layout = pipelineLayout(be, f);
			if (layout[0] < 0) {
				continue; // vertex-less pipeline (e.g., 26.3 core/clouds): let vanilla draw it
			}
			String vsPath = rp.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX).getPath();
			String loc = rp.getLocation().getPath();
			int stage = classifyRenderStage(loc, vsPath);
			float alphaRef = classifyAlphaTestRef(loc, vsPath);
			boolean keepVanillaSampler = !vsPath.equals("core/block");
			this.worldPipelineBindings.put(be, new WorldPipelineBinding(f, group, stage, alphaRef, keepVanillaSampler, layout));
			covered++;
		}
		if (covered > 0) {
			this.log.add("world programs cover " + covered + " vanilla entity/block/sky/particle pipelines");
		}
	}

	private boolean isSolidTerrain(final Object pipeline) {
		return pipeline == this.solidPipeline || (this.solidMultiPipeline != null && pipeline == this.solidMultiPipeline);
	}

	private boolean isCutoutTerrain(final Object pipeline) {
		return pipeline == this.cutoutPipeline || (this.cutoutMultiPipeline != null && pipeline == this.cutoutMultiPipeline);
	}

	private boolean isTranslucentTerrain(final Object pipeline) {
		return pipeline == this.translucentPipeline || (this.translucentMultiPipeline != null && pipeline == this.translucentMultiPipeline);
	}

	public void onPipelineBound(final com.mojang.renderpearl.backend.opengl.GlRenderPipeline pipeline) {
		if (!this.worldFrame || this.terrainPass == null) {
			return;
		}
		if (!this.worldPipelinesInitialized) {
			this.initWorldPipelines();
		}
		if (this.shadowMode) {
			this.bindShadow(pipeline);
			return;
		}

		int bound = GlStateManager.getFrameBuffer(DRAW_FRAMEBUFFER);
		Pass current = this.worldPassByFbo.get(bound);

		Pass want = null;
		com.mojang.renderpearl.frontend.FrontendRenderPipeline frontend = null;
		int stage = ShaderUniforms.STAGE_NONE;
		float alphaRef = 0.0F;
		boolean keepVanillaSampler = false;

		if (this.isSolidTerrain(pipeline)) {
			want = this.terrainSolidPass;
			stage = ShaderUniforms.STAGE_TERRAIN_SOLID;
			alphaRef = 0.0F; // solid terrain disables alpha test
		} else if (this.isCutoutTerrain(pipeline)) {
			want = this.terrainCutoutPass;
			stage = ShaderUniforms.STAGE_TERRAIN_CUTOUT;
			alphaRef = 0.1F;
		} else if (this.isTranslucentTerrain(pipeline)) {
			want = this.waterPass != null ? this.waterPass : this.terrainPass;
			stage = ShaderUniforms.STAGE_TERRAIN_TRANSLUCENT;
			alphaRef = 0.0F;
		} else {
			WorldPipelineBinding binding = this.worldPipelineBindings.get(pipeline);
			if (binding != null) {
				want = this.entityVariant(binding.group, binding.layout, binding.layoutKey);
				frontend = binding.frontend;
				stage = binding.renderStage;
				alphaRef = binding.alphaTestRef;
				keepVanillaSampler = binding.keepVanillaSampler;
				if (want != null && (stage == ShaderUniforms.STAGE_SKY || stage == ShaderUniforms.STAGE_SUNSET)) {
					this.skyBasicRanThisFrame = true;
				}
			}
		}

		if (want == null) {
			ShaderPacks.atlasSampler = 0;
			if (this.midDeferred) {
				// After deferred, vanilla draws (clouds, weather, particles, ...) land in the scene buffer
				if (current != this.sceneRedirect && (current != null || (bound == this.midFbo && bound != 0))) {
					Target t0 = this.targets[this.sceneTarget];
					this.scratchColors[0] = this.flipped[t0.index] ? t0.alt : t0.main;
					this.attach(this.sceneRedirect, this.scratchColors, 1, this.worldMainDepth);
				}
				return;
			}
			if (current != null) {
				GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.savedFbo);
				GlApi.glDrawBuffers(DRAW_BUF_0);
			}
			return;
		}

		try {
			if (want.lastVanillaPipeline != pipeline) {
				this.bindWorldInterface(want, pipeline, frontend);
				want.lastVanillaPipeline = pipeline;
			}
			if (current == null) {
				this.savedFbo = bound;
			}
			boolean marked = this.directScene && want.marksScene && !this.midDeferred
				&& want.drawBuffers.length < this.maxDrawBuffers;
			int dbLen = want.drawBuffers.length;
			int count = dbLen + (marked ? 1 : 0);
			for (int i = 0; i < dbLen; i++) {
				int idx = want.drawBuffers[i];
				if (idx < 0 || idx >= ShaderPack.MAX_COLOR_TARGETS) {
					this.scratchColors[i] = 0;
				} else {
					Target t = this.targets[idx];
					boolean side = this.flipped[idx];
					this.scratchColors[i] = this.midDeferred ? (side ? t.alt : t.main)
						: idx == this.sceneTarget && !this.directScene ? this.worldMainColor
						: (side ? t.alt : t.main);
				}
			}
			if (marked) {
				this.scratchColors[dbLen] = this.worldMainColor;
			}
			this.attach(want, this.scratchColors, count, this.worldMainDepth);
			GlStateManager._colorMaskAll(true, true, true, true);
			if (want.hasCustomBlend) {
				this.applyBlend(want);
			} else if (this.hasIndexedBlend) {
				// Ensure integer attachments do not inherit an enabled blend flag
				for (int i = 0; i < dbLen && i < this.maxDrawBuffers; i++) {
					int idx = want.drawBuffers[i];
					if (idx >= 0 && idx < ShaderPack.MAX_COLOR_TARGETS && this.targets[idx] != null && this.targets[idx].format.intKind != 0) {
						GlStateManager._disableBlend(i);
					}
				}
			}
			this.worldUniforms.setRenderStage(stage);
			if (!want.hasCustomAlphaTest) {
				this.worldUniforms.setAlphaTestRef(alphaRef);
			}
			this.useWorldProgram(want, keepVanillaSampler);
			this.worldRan = true;
		} catch (RuntimeException e) {
			this.worldFrame = false;
			this.broken = true;
			this.log.add("world program failed: " + e);
			System.out.println("[shaderpack] world program failed, turning the pack off: " + e);
			GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.savedFbo);
			GlApi.glDrawBuffers(DRAW_BUF_0);
		}
	}

	/** Binds program, constant attributes, samplers (with mipmap refresh), and frame/per-draw uniforms. */
	private void useWorldProgram(final Pass pass, final boolean keepVanillaSampler) {
		GlStateManager._glUseProgram(pass.program);
		if (pass.unboundColorLoc >= 0) {
			GlApi.glVertexAttrib4f(pass.unboundColorLoc, 1.0F, 1.0F, 1.0F, 1.0F);
		}
		if (pass.unboundNormalLoc >= 0) {
			GlApi.glVertexAttrib4f(pass.unboundNormalLoc, 0.0F, 1.0F, 0.0F, 0.0F);
		}
		if (!keepVanillaSampler) {
			// Constant attributes the terrain BLOCK format lacks: up normal, block id 0, midTexCoord, tangent
			GlApi.glVertexAttrib4f(4, 0.0F, 1.0F, 0.0F, 0.0F);
			GlApi.glVertexAttrib4f(5, 0.0F, 0.0F, 0.0F, 1.0F);
			GlApi.glVertexAttrib4f(6, 0.0F, 0.0F, 0.0F, 1.0F);
			GlApi.glVertexAttrib4f(7, 1.0F, 0.0F, 0.0F, 1.0F);
		}
		for (int i = 0, n = pass.samplers.size(); i < n; i++) {
			int[] smp = pass.samplers.get(i);
			if (smp[1] == K_ATLAS) {
				if (keepVanillaSampler) {
					ShaderPacks.atlasSampler = 0;
				} else if (smp[0] >= 0) {
					touchedUnits |= 1L << smp[0];
					GlApi.glBindSampler(smp[0], this.samplerAtlas);
					ShaderPacks.atlasUnit = smp[0];
					ShaderPacks.atlasSampler = this.samplerAtlas;
				}
				continue;
			}
			if (smp[1] == K_LIGHTMAP) {
				if (smp[0] == pass.fallbackLightUnit && pass.fallbackLightUnit >= 0) {
					// Emissive pipeline with no Sampler2: bind fullbright white texture
					this.bindWorldUnit(smp[0], this.whiteTex, this.samplerNearest, false);
				}
				continue;
			}
			int tex = this.worldSamplerTexture(smp);
			int samplerObj;
			boolean needMips = false;
			switch (smp[1]) {
				case K_COLOR: {
					Target t = this.targets[smp[2]];
					samplerObj = this.colorSamplerFor(t);
					if (t != null && t.mipmap && filterable(t)) {
						needMips = this.staleMips.remove(tex);
					}
					break;
				}
				case K_SHADOW:
					samplerObj = this.shadowSamplerFor(smp[2], smp[3]);
					break;
				case K_SHADOW_COLOR: {
					int sIdx = smp[2];
					samplerObj = this.shadowColorSamplerFor(sIdx);
					if (sIdx >= 0 && sIdx < this.shadowColorFmts.length
						&& this.directives.shadowColorMipmap[sIdx] && filterableFormat(this.shadowColorFmts[sIdx])) {
						needMips = this.staleMips.remove(tex);
					}
					break;
				}
				case K_NOISE:
				case K_CUSTOM:
					samplerObj = this.customSamplerFor(tex);
					break;
				case K_DEPTH:
					samplerObj = this.samplerNearest;
					break;
				default:
					samplerObj = this.samplerLinear;
					break;
			}
			this.bindWorldUnit(smp[0], tex, samplerObj, needMips);
		}
		GlStateManager._activeTexture(TEXTURE0 + 1);
		GlStateManager._activeTexture(TEXTURE0);
		int fid = this.worldUniforms.frameId();
		if (pass.lastUniformFrame != fid) {
			pass.lastUniformFrame = fid;
			this.worldUniforms.apply(pass.uniforms);
		} else {
			this.worldUniforms.applyPerDraw(pass.perDrawUniforms);
		}
	}

	private void bindWorldUnit(final int unit, final int tex, final int samplerObj, final boolean generateMips) {
		if (unit < 0) {
			return;
		}
		activeUnit(unit);
		GlApi.glBindTexture(TEXTURE_2D, tex);
		if (unit < 12) {
			GlStateManager._bindTexture(tex);
		}
		if (generateMips) {
			GlApi.glGenerateMipmap(TEXTURE_2D);
		}
		GlApi.glBindSampler(unit, samplerObj);
	}

	/**
	 * Before translucent terrain: runs {@code deferred*} over the opaque world (Iris order),
	 * copies buffers that translucent terrain both reads and writes, and marks mipmaps stale.
	 * When there are no deferred/prepare passes but an active program samples {@code depthtex1},
	 * {@code depthtex2}, or {@code gdepthtex}, snapshots opaque depth into {@link #depthTexOpaque}.
	 */
	public boolean beforeTranslucent() {
		if (!this.worldFrame || !this.worldRan || this.midDeferred || this.depthSnapshotted || this.broken || this.shadowMode
			|| this.sceneRedirect == null || debugPasses >= 0) {
			return false;
		}
		if (!this.hasDeferredPasses && !this.hasPreparePasses) {
			if (!this.needsOpaqueDepth || this.depthTexOpaque == 0) {
				return false;
			}
			int bound = GlStateManager.getFrameBuffer(DRAW_FRAMEBUFFER);
			boolean ours = this.ourWorldFbos.contains(bound);
			int ret = ours ? this.savedFbo : bound;
			int w = this.worldWidth;
			int h = this.worldHeight;
			try {
				this.disableAllBlend();
				GlStateManager._disableCull();
				GlStateManager._disableScissorTest();
				GlStateManager._disablePolygonOffset();
				GlStateManager._glBindVertexArray(this.vao);
				Target t0 = this.target(this.sceneTarget);
				this.worldUniforms.set("iris_KeepSky", 0.0F);
				this.worldUniforms.set("iris_DirectScene", 0.0F);
				GlStateManager._colorMaskAll(false, false, false, false);
				GlStateManager._enableDepthTest();
				GlStateManager._depthFunc(ALWAYS);
				GlStateManager._depthMask(true);
				this.scratchColors[0] = this.flipped[t0.index] ? t0.alt : t0.main;
				this.attach(this.prep, this.scratchColors, 1, this.depthTexOpaque);
				GlApi.glClearBufferfv(DEPTH, 0, DEPTH_ONE);
				this.draw(this.prep, this.worldMainColor, this.worldMainDepth, this.worldUniforms, w, h);
				GlStateManager._disableDepthTest();
				GlStateManager._depthMask(false);
				GlStateManager._colorMaskAll(true, true, true, true);
				this.depthSnapshotted = true;
				this.opaqueDepthValid = true;
			} catch (RuntimeException e) {
				this.fail("opaque depth snapshot before translucents failed: " + e);
			} finally {
				GlStateManager._disableDepthTest();
				GlStateManager._depthMask(false);
				GlStateManager._colorMaskAll(true, true, true, true);
				resyncGlState();
				GlStateManager._glBindFramebuffer(FRAMEBUFFER, ret);
				GlApi.glDrawBuffers(DRAW_BUF_0);
				GlStateManager._viewport(0, 0, w, h);
			}
			return true;
		}
		int bound = GlStateManager.getFrameBuffer(DRAW_FRAMEBUFFER);
		boolean ours = this.ourWorldFbos.contains(bound);
		int ret = ours ? this.savedFbo : bound;
		int w = this.worldWidth;
		int h = this.worldHeight;
		try {
			this.disableAllBlend();
			GlStateManager._disableCull();
			GlStateManager._disableScissorTest();
			GlStateManager._disablePolygonOffset();
			GlStateManager._colorMaskAll(true, true, true, true);
			this.markAllMipTargetsStale();
			this.worldUniforms.setRenderStage(ShaderUniforms.STAGE_NONE);
			GlStateManager._glBindVertexArray(this.vao);
			GlApi.glVertexAttrib4f(2, 1.0F, 1.0F, 1.0F, 1.0F);
			GlApi.glVertexAttrib4f(3, 0.0F, 0.0F, 1.0F, 0.0F);
			GlApi.glVertexAttrib4f(4, 240.0F, 240.0F, 0.0F, 1.0F);
			this.clearsAndDeferred(this.worldMainColor, this.worldMainDepth, w, h, this.worldUniforms);
			for (int idx : this.translucentReadWrite) {
				Target t = this.targets[idx];
				boolean side = this.flipped[idx];
				int src = side ? t.alt : t.main;
				int dst = side ? t.main : t.alt;
				this.blit(src, dst, t.width, t.height);
				if (t.mipmap && filterable(t)) {
					this.staleMips.add(dst);
				}
			}
			this.midDeferred = true;
			this.midFbo = ret;
			if (this.midFrames++ == 0) {
				System.out.println("[shaderpack] deferred runs before translucent terrain; translucent copies " + this.translucentReadWrite);
			}
		} catch (RuntimeException e) {
			this.fail("deferred before translucents failed: " + e);
		} finally {
			resyncGlState();
			GlStateManager._glBindFramebuffer(FRAMEBUFFER, ret);
			GlApi.glDrawBuffers(DRAW_BUF_0);
			GlStateManager._viewport(0, 0, w, h);
		}
		return true;
	}

	private int midFrames;

	private void blit(final int from, final int to, final int w, final int h) {
		GlStateManager._glBindFramebuffer(READ_FRAMEBUFFER, this.blitFbo);
		GlApi.glFramebufferTexture2D(READ_FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, from, 0);
		GlApi.glReadBuffer(COLOR_ATTACHMENT0);
		GlStateManager._glBindFramebuffer(DRAW_FRAMEBUFFER, this.clearFbo);
		GlApi.glFramebufferTexture2D(DRAW_FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, 0, 0);
		GlApi.glFramebufferTexture2D(DRAW_FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, to, 0);
		GlApi.glDrawBuffers(DRAW_BUF_0);
		GlApi.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, 16384, NEAREST);
		GlApi.glFramebufferTexture2D(DRAW_FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, 0, 0);
		GlApi.glFramebufferTexture2D(READ_FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, 0, 0);
		GlStateManager._glBindFramebuffer(READ_FRAMEBUFFER, 0);
	}

	public int shadowResolution() {
		return this.shadowRes;
	}

	public boolean hasShadowPass() {
		return this.shadowPass != null && this.terrainPass != null && !this.broken;
	}

	public static int shadowInterval = 1;
	private int shadowAge;
	private boolean shadowValid;
	private boolean shadowReuse;

	public boolean reuseShadowThisFrame() {
		this.shadowReuse = this.shadowPass != null && this.shadowValid && shadowInterval > 1 && ++this.shadowAge < shadowInterval;
		return this.shadowReuse;
	}

	public boolean beginShadow() {
		if (!this.worldFrame || this.shadowPass == null || this.broken || this.shadowReuse) {
			return false;
		}
		try {
			int bound = GlStateManager.getFrameBuffer(DRAW_FRAMEBUFFER);
			boolean ours = this.ourWorldFbos.contains(bound);
			this.shadowReturnFbo = ours ? this.savedFbo : bound;
			GlStateManager._scissorBox(0, 0, this.shadowRes, this.shadowRes);
			GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.shadowFbo);
			GlApi.glDrawBuffers(this.shadowDrawBuffers);
			GlStateManager._depthMask(true);
			GlApi.glClearBufferfv(DEPTH, 0, DEPTH_ONE);
			// Clear active shadowcolor attachments if configured
			if (this.shadowDrawBuffers != DRAW_BUF_NONE && this.shadowPass.drawBuffers != null) {
				GlStateManager._colorMaskAll(true, true, true, true);
				for (int i = 0; i < this.shadowDrawBuffers.length; i++) {
					if (this.shadowDrawBuffers[i] == 0) {
						continue;
					}
					int idx = this.shadowPass.drawBuffers[i];
					Boolean clear = this.directives.shadowColorClear[idx];
					if (clear == null || clear) {
						Format sf = this.shadowColorFmts[idx];
						if (sf != null && sf.intKind == 1) {
							int[] ci = this.shadowColorClearInt[idx];
							GlApi.glClearBufferiv(COLOR, i, ci != null ? ci : WHITE_CLEAR_INT);
						} else if (sf != null && sf.intKind == 2) {
							int[] ci = this.shadowColorClearInt[idx];
							GlApi.glClearBufferuiv(COLOR, i, ci != null ? ci : WHITE_CLEAR_INT);
						} else {
							float[] c = this.directives.shadowColorClearColor[idx];
							GlApi.glClearBufferfv(COLOR, i, c != null ? c : WHITE_CLEAR);
						}
					}
				}
			}
			this.shadowMode = true;
			return true;
		} catch (RuntimeException e) {
			this.fail("shadow setup failed: " + e);
			return false;
		}
	}

	public void endShadow() {
		if (!this.shadowMode) {
			return;
		}
		this.shadowMode = false;
		this.shadowValid = true;
		this.shadowAge = 0;
		// Mark mipmapped shadowcolor targets stale so glGenerateMipmap runs lazily before first sample.
		// Note: WebGL2 ES 3.0 forbids glGenerateMipmap on DEPTH_COMPONENT32F (GL_INVALID_OPERATION),
		// so shadowTexMipmap is intentionally not mipmapped on shadowTex.
		for (int i = 0; i < this.shadowColorTex.length; i++) {
			int stex = this.shadowColorTex[i];
			if (stex != 0 && this.directives.shadowColorMipmap[i] && filterableFormat(this.shadowColorFmts[i])) {
				this.staleMips.add(stex);
			}
		}
		if (this.worldUniforms != null) {
			this.worldUniforms.commitShadow();
		}
		if (++this.shadowFrames == 60) {
			System.out.println("[shaderpack] shadow pass: " + this.shadowBinds + " terrain pipeline binds in 60 frames");
		}
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.shadowReturnFbo);
		GlApi.glDrawBuffers(DRAW_BUF_0);
		GlStateManager._viewport(0, 0, this.worldWidth, this.worldHeight);
		GlStateManager._scissorBox(0, 0, this.worldWidth, this.worldHeight);
	}

	private void bindShadow(final com.mojang.renderpearl.backend.opengl.GlRenderPipeline pipeline) {
		boolean solid = this.isSolidTerrain(pipeline);
		boolean cutout = this.isCutoutTerrain(pipeline);
		if (!solid && !cutout) {
			return;
		}
		Pass sp = solid ? this.shadowSolidPass : this.shadowCutoutPass;
		if (sp == null) {
			return;
		}
		try {
			if (sp.lastVanillaPipeline != pipeline) {
				this.bindWorldInterface(sp, pipeline, null);
				sp.lastVanillaPipeline = pipeline;
			}
			GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.shadowFbo);
			GlApi.glDrawBuffers(this.shadowDrawBuffers);
			GlStateManager._viewport(0, 0, this.shadowRes, this.shadowRes);
			GlStateManager._scissorBox(0, 0, this.shadowRes, this.shadowRes);
			GlStateManager._enableDepthTest();
			GlStateManager._depthFunc(LEQUAL);
			GlStateManager._depthMask(true);
			GlStateManager._disableCull();
			GlStateManager._disablePolygonOffset();
			this.worldUniforms.setRenderStage(solid ? ShaderUniforms.STAGE_TERRAIN_SOLID : ShaderUniforms.STAGE_TERRAIN_CUTOUT);
			if (!sp.hasCustomAlphaTest) {
				this.worldUniforms.setAlphaTestRef(solid ? 0.0F : 0.1F);
			}
			this.useWorldProgram(sp, false);
			this.shadowBinds++;
		} catch (RuntimeException e) {
			this.shadowPass = null;
			this.shadowSolidPass = null;
			this.shadowCutoutPass = null;
			this.log.add("shadow program failed: " + e);
			System.out.println("[shaderpack] shadow program failed, shadows off: " + e);
			this.endShadow();
		}
	}

	private void fail(final String why) {
		this.worldFrame = false;
		this.broken = true;
		this.log.add(why);
		System.out.println("[shaderpack] " + why);
	}

	private int worldSamplerTexture(final int[] smp) {
		switch (smp[1]) {
			case K_COLOR: {
				Target t = this.targets[smp[2]];
				boolean side = this.flipped[t.index];
				if (!this.midDeferred) {
					// Reflect post-prepare* flip polarity so world programs read prepare* LUTs!
					return side ? t.alt : t.main;
				}
				return this.translucentReadWrite.contains(t.index) == side ? t.main : t.alt;
			}
			case K_NOISE:
				return this.noiseTex;
			case K_DEPTH:
				return this.midDeferred ? this.depthTex : (this.opaqueDepthValid ? this.depthTexOpaque : this.farDepthTex);
			case K_SHADOW:
				return this.shadowMode ? this.farDepthTex : this.shadowTex;
			case K_SHADOW_COLOR: {
				int idx = smp[2];
				int tex = !this.shadowMode && idx >= 0 && idx < this.shadowColorTex.length ? this.shadowColorTex[idx] : 0;
				return tex != 0 ? tex : this.whiteTex;
			}
			case K_WHITE:
				return this.whiteTex;
			case K_FLAT_NORMAL:
				return this.flatNormalTex;
			case K_CUSTOM:
				return smp[2];
			default:
				return this.blackTex;
		}
	}

	private static Object backend(final com.mojang.renderpearl.api.pipeline.RenderPipeline pipeline) {
		Object compiled = com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(pipeline);
		return compiled instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline f ? f.backendRenderPipeline() : null;
	}

	/**
	 * Syncs a world/shadow program's UBO bindings (Globals, ChunkSection, DynamicTransforms) and
	 * vanilla Sampler0/Sampler2 units to the exact {@code GlRenderPipeline} currently being drawn.
	 */
	private void bindWorldInterface(final Pass pass, final com.mojang.renderpearl.backend.opengl.GlRenderPipeline pipeline,
		final com.mojang.renderpearl.frontend.FrontendRenderPipeline frontend) {
		Object compiled = frontend != null ? frontend
			: com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(net.minecraft.client.renderer.RenderPipelines.SOLID_TERRAIN);
		if (!(compiled instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline f)) {
			throw new IllegalStateException("world pipeline is not a frontend pipeline");
		}
		com.mojang.renderpearl.backend.opengl.GlProgram vanilla = pipeline.program();
		int program = pass.program;
		GlStateManager._glUseProgram(program);
		String[][] uboPairs = {
			{"Globals", "iris_Globals"},
			{"ChunkSection", "iris_ChunkSection"},
			{"DynamicTransforms", "iris_DynamicTransforms"},
			{"DynamicTransforms", "DynamicTransforms"}
		};
		for (String[] pair : uboPairs) {
			int block = GlApi.glGetUniformBlockIndex(program, pair[1]);
			if (block < 0) {
				continue;
			}
			int index = f.uniformIndices().getOrDefault(pair[0], -1);
			com.mojang.renderpearl.backend.opengl.Uniform u = index < 0 ? null : vanilla.getUniform(index);
			if (u instanceof com.mojang.renderpearl.backend.opengl.Uniform.Ubo ubo) {
				GlApi.glUniformBlockBinding(program, block, ubo.blockBinding());
			} else {
				this.log.add(pass.name + ": no vanilla binding for " + pair[0]);
			}
		}
		for (int i = 0, n = pass.samplers.size(); i < n; i++) {
			int[] smp = pass.samplers.get(i);
			if (smp[1] != K_ATLAS && smp[1] != K_LIGHTMAP) {
				continue;
			}
			int index = f.uniformIndices().getOrDefault(smp[1] == K_ATLAS ? "Sampler0" : "Sampler2", -1);
			com.mojang.renderpearl.backend.opengl.Uniform u = index < 0 ? null : vanilla.getUniform(index);
			if (u instanceof com.mojang.renderpearl.backend.opengl.Uniform.Sampler sampler) {
				GlApi.glUniform1i(smp[2], sampler.samplerIndex());
				smp[0] = sampler.samplerIndex();
			} else if (smp[1] == K_LIGHTMAP && pass.fallbackLightUnit >= 0) {
				// EMISSIVE pipeline omits Sampler2: point lightmap at fallback fullbright unit
				GlApi.glUniform1i(smp[2], pass.fallbackLightUnit);
				smp[0] = pass.fallbackLightUnit;
			}
		}
	}

	/** Runs one full-screen pass into the "other" side of its draw buffers, then updates ping-pong flips & staleMips. */
	private void runPass(final Pass p, final int mainColor, final int mainDepth, final ShaderUniforms uniforms, final int w, final int h) {
		int[] db = p.drawBuffers;
		int len = db == null ? 0 : Math.min(db.length, this.maxDrawBuffers);
		int baseW = w;
		int baseH = h;
		boolean foundBase = false;
		for (int i = 0; i < len; i++) {
			int idx = db[i];
			if (idx < 0 || idx >= ShaderPack.MAX_COLOR_TARGETS) {
				this.scratchColors[i] = 0;
			} else {
				Target t = this.targets[idx];
				this.scratchColors[i] = this.flipped[t.index] ? t.main : t.alt;
				if (!foundBase && t.width > 0 && t.height > 0) {
					baseW = t.width;
					baseH = t.height;
					foundBase = true;
				}
			}
		}
		this.applyBlend(p);
		this.attach(p, this.scratchColors, len, 0);
		int vw = Math.max(1, Math.round(baseW * p.scaleX));
		int vh = Math.max(1, Math.round(baseH * p.scaleY));
		this.draw(p, mainColor, mainDepth, uniforms, vw, vh);
		if (p.hasCustomBlend) {
			this.disableAllBlend();
		}
		for (int i = 0; i < len; i++) {
			int idx = db[i];
			if (idx < 0 || idx >= ShaderPack.MAX_COLOR_TARGETS) {
				continue;
			}
			int writtenTex = this.scratchColors[i];
			Boolean force = p.flips.get(idx);
			if (force == null || force) {
				this.flipped[idx] = !this.flipped[idx];
			}
			Target t = this.targets[idx];
			// Mark the ACTUALLY WRITTEN texture stale (correct even when flip == false!)
			if (t.mipmap && filterable(t) && writtenTex != 0) {
				this.staleMips.add(writtenTex);
			}
		}
	}

	private final Set<Integer> staleMips = new HashSet<>();

	/** Debugging: a pass's final GLSL ES sources and its uniforms' current values. */
	public String describe(final String passName, final ShaderUniforms uniforms) {
		List<Pass> all = new ArrayList<>(this.passes);
		for (WorldProgramGroup g : this.worldGroupsByProgram.values()) {
			if (g.terrainPass != null) {
				all.add(g.terrainPass);
			}
			for (Pass vp : g.variants.values()) {
				if (vp != null) {
					all.add(vp);
				}
			}
		}
		for (Pass p : all) {
			if (!p.name.equals(passName)) {
				continue;
			}
			StringBuilder b = new StringBuilder();
			b.append("== ").append(p.name).append(" draw ").append(Arrays.toString(p.drawBuffers)).append('\n');
			for (ShaderUniforms.UniformEntry u : p.uniforms) {
				float[] v = uniforms.value(u.name);
				b.append("uniform ").append(u.name).append(" type ").append(u.type).append(" = ")
					.append(v == null ? "UNSET" : Arrays.toString(v)).append('\n');
			}
			for (int[] smp : p.samplers) {
				b.append("sampler unit ").append(smp[0]).append(" kind ").append(smp[1])
					.append(" index ").append(smp[2]).append(" type ").append(smp[3]).append('\n');
			}
			b.append("---- vertex ----\n").append(p.vertexSource).append("\n---- fragment ----\n").append(p.fragmentSource);
			return b.toString();
		}
		return "no pass " + passName;
	}

	/**
	 * Binds a pre-validated FBO for {@code p} matching {@code (colors[0..count-1], depth)}.
	 * Steady-state frames hit the cache with ONE {@code glBindFramebuffer} and ZERO attachment,
	 * drawBuffers, or {@code glCheckFramebufferStatus} calls.
	 */
	private int attach(final Pass p, final int[] colors, final int count, final int depth) {
		if (p.fboGen != this.resizeGen) {
			this.clearPassFbos(p);
			p.fboGen = this.resizeGen;
		}
		List<FboEntry> cache = p.fboCache;
		for (int i = 0, n = cache.size(); i < n; i++) {
			FboEntry e = cache.get(i);
			if (e.matches(colors, count, depth)) {
				GlStateManager._glBindFramebuffer(FRAMEBUFFER, e.fbo);
				return e.fbo;
			}
		}
		// Cache miss (first use or after resize): build and validate ONCE
		int fbo = GlApi.glGenFramebuffers();
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, fbo);
		int[] copyColors = new int[count];
		int[] bufs = count == 0 ? DRAW_BUF_NONE : new int[count];
		for (int i = 0; i < count; i++) {
			int tex = colors[i];
			copyColors[i] = tex;
			if (tex != 0) {
				GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0 + i, TEXTURE_2D, tex, 0);
				bufs[i] = COLOR_ATTACHMENT0 + i;
			} else {
				bufs[i] = 0; // GL_NONE
			}
		}
		if (depth != 0) {
			GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, depth, 0);
		}
		GlApi.glDrawBuffers(bufs);
		int status = GlApi.glCheckFramebufferStatus(FRAMEBUFFER);
		if (status != FRAMEBUFFER_COMPLETE) {
			deleteFramebuffer(fbo);
			throw new IllegalStateException(p.name + ": framebuffer incomplete (" + status + ")");
		}
		cache.add(new FboEntry(fbo, depth, copyColors));
		if (p.isWorldPass) {
			this.ourWorldFbos.add(fbo);
			this.worldPassByFbo.put(fbo, p);
		}
		return fbo;
	}

	private void draw(final Pass p, final int mainColor, final int mainDepth, final ShaderUniforms uniforms, final int vw, final int vh) {
		GlStateManager._viewport(0, 0, vw, vh);
		GlStateManager._glUseProgram(p.program);
		for (int i = 0, n = p.samplers.size(); i < n; i++) {
			int[] s = p.samplers.get(i);
			int unit = s[0];
			int tex;
			int samplerObj = this.samplerLinear;
			boolean mips = false;
			switch (s[1]) {
				case K_COLOR: {
					Target t = this.targets[p == this.copy ? this.copySource : s[2]];
					tex = this.flipped[t.index] ? t.alt : t.main;
					samplerObj = this.colorSamplerFor(t);
					if (t.mipmap && filterable(t)) {
						mips = this.staleMips.remove(tex);
					}
					break;
				}
				case K_DEPTH:
					tex = s[2] == 1 && this.opaqueDepthValid ? this.depthTexOpaque : this.depthTex;
					samplerObj = this.samplerNearest;
					break;
				case K_MAIN:
					tex = mainColor;
					samplerObj = this.samplerNearest;
					break;
				case K_MAIN_DEPTH:
					tex = mainDepth;
					samplerObj = this.samplerNearest;
					break;
				case K_NOISE:
					tex = this.noiseTex;
					samplerObj = this.customSamplerFor(tex);
					break;
				case K_SHADOW:
					tex = this.shadowTex;
					samplerObj = this.shadowSamplerFor(s[2], s[3]);
					break;
				case K_SHADOW_COLOR: {
					int idx = s[2];
					int stex = idx >= 0 && idx < this.shadowColorTex.length ? this.shadowColorTex[idx] : 0;
					tex = stex != 0 ? stex : this.whiteTex;
					samplerObj = this.shadowColorSamplerFor(idx);
					if (stex != 0 && idx >= 0 && idx < this.shadowColorFmts.length
						&& this.directives.shadowColorMipmap[idx] && filterableFormat(this.shadowColorFmts[idx])) {
						mips = this.staleMips.remove(stex);
					}
					break;
				}
				case K_WHITE:
					tex = this.whiteTex;
					break;
				case K_FLAT_NORMAL:
					tex = this.flatNormalTex;
					break;
				case K_CUSTOM:
					tex = s[2];
					samplerObj = this.customSamplerFor(tex);
					break;
				case K_NONE:
					tex = 0;
					break;
				default:
					tex = this.blackTex;
					break;
			}
			activeUnit(unit);
			if (s[1] != K_NONE) {
				forceBind(tex);
			}
			if (mips) {
				GlApi.glGenerateMipmap(TEXTURE_2D);
			}
			GlApi.glBindSampler(unit, samplerObj);
		}
		int fid = uniforms.frameId();
		if (p.lastUniformFrame != fid) {
			p.lastUniformFrame = fid;
			uniforms.apply(p.uniforms);
		} else {
			uniforms.applyPerDraw(p.perDrawUniforms);
		}
		GlApi.glDrawArrays(TRIANGLES, 0, 6);
	}

	/**
	 * Leaves GL state consistent with GlStateManager's caches. Binds texture 0 (NEVER texture ID 1,
	 * which may be a cubemap and trigger GL_INVALID_OPERATION) across up to {@code maxTexUnits} (32).
	 */
	public static void resyncGlState() {
		GlStateManager._glBindVertexArray(0);
		long touched = touchedUnits;
		touchedUnits = 0L;
		int limit = Math.min(32, maxTexUnits);
		for (int unit = 0; unit < limit; unit++) {
			if (((touched >>> unit) & 1L) == 0L) {
				continue;
			}
			GlApi.glBindSampler(unit, 0);
			if (unit < 12) {
				GlStateManager._activeTexture(TEXTURE0 + (unit == 0 ? 1 : 0));
				GlStateManager._activeTexture(TEXTURE0 + unit);
				GlApi.glBindTexture(TEXTURE_2D, 0);
				GlStateManager._bindTexture(0);
			} else {
				GlApi.glActiveTexture(TEXTURE0 + unit);
				GlApi.glBindTexture(TEXTURE_2D, 0);
			}
		}
		GlStateManager._activeTexture(TEXTURE0 + 1);
		GlStateManager._activeTexture(TEXTURE0);
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, 0);
		GlStateManager._depthFunc(LEQUAL);
	}

	public void close() {
		this.cleanupWarmup();
		for (Pending p : this.pendings()) {
			if (p.vsShader != 0) {
				GlApi.glDeleteShader(p.vsShader);
				p.vsShader = 0;
			}
			if (p.fsShader != 0) {
				GlApi.glDeleteShader(p.fsShader);
				p.fsShader = 0;
			}
			GlApi.glDeleteProgram(p.program);
		}
		this.pendingPasses.clear();
		for (WorldProgramGroup g : this.worldGroupsByProgram.values()) {
			g.pendingTerrain = null;
			g.pendingVariants.clear();
			if (g.terrainPass != null) {
				this.clearPassFbos(g.terrainPass);
				GlApi.glDeleteProgram(g.terrainPass.program);
				g.terrainPass = null;
			}
			for (Pass vp : g.variants.values()) {
				if (vp != null) {
					this.clearPassFbos(vp);
					GlApi.glDeleteProgram(vp.program);
				}
			}
			g.variants.clear();
		}
		this.worldGroupsByProgram.clear();
		this.worldGroupsByName.clear();
		this.buildWorldGroups.clear();

		for (Pass p : this.passes) {
			this.clearPassFbos(p);
			GlApi.glDeleteProgram(p.program);
		}
		this.passes.clear();

		for (Pass p : new Pass[] {this.prep, this.copy}) {
			if (p != null) {
				this.clearPassFbos(p);
				GlApi.glDeleteProgram(p.program);
			}
		}
		this.prep = null;
		this.copy = null;

		for (ClearBatch batch : this.clearBatches) {
			deleteFramebuffer(batch.fbo);
			if (batch.altFbo != 0) {
				deleteFramebuffer(batch.altFbo);
			}
		}
		this.clearBatches.clear();

		if (this.shadowFbo != 0) {
			deleteFramebuffer(this.shadowFbo);
			this.shadowFbo = 0;
		}
		if (this.shadowTex != 0 && this.shadowTex != this.farDepthTex) {
			deleteTexture(this.shadowTex);
		}
		this.shadowTex = 0;
		for (int i = 0; i < this.shadowColorTex.length; i++) {
			if (this.shadowColorTex[i] != 0) {
				deleteTexture(this.shadowColorTex[i]);
				this.shadowColorTex[i] = 0;
			}
		}
		for (Target t : this.targets) {
			if (t != null && t.main != 0) {
				deleteTexture(t.main);
				deleteTexture(t.alt);
				t.main = 0;
				t.alt = 0;
			}
		}
		for (int tex : new int[] {this.depthTex, this.noiseTex, this.farDepthTex, this.whiteTex, this.blackTex, this.flatNormalTex, this.blockIdTex}) {
			deleteTexture(tex);
		}
		this.depthTex = 0;
		this.noiseTex = 0;
		this.farDepthTex = 0;
		this.whiteTex = 0;
		this.blackTex = 0;
		this.flatNormalTex = 0;
		this.blockIdTex = 0;

		for (int tex : this.customTextures) {
			deleteTexture(tex);
		}
		this.customTextures.clear();
		ShaderPacks.atlasSampler = 0;

		for (int s : new int[] {
			this.samplerLinear, this.samplerNearest, this.samplerRepeat, this.samplerRepeatNearest,
			this.samplerMip, this.samplerNearestMip, this.samplerShadowLinear, this.samplerShadowNearest, this.samplerAtlas
		}) {
			if (s != 0) {
				GlApi.glDeleteSamplers(s);
			}
		}
		if (this.clearFbo != 0) {
			deleteFramebuffer(this.clearFbo);
			this.clearFbo = 0;
		}
		if (this.sceneRedirect != null) {
			this.clearPassFbos(this.sceneRedirect);
			this.sceneRedirect = null;
		}
		if (this.blitFbo != 0) {
			deleteFramebuffer(this.blitFbo);
			this.blitFbo = 0;
		}
		if (this.depthTexOpaque != 0) {
			deleteTexture(this.depthTexOpaque);
			this.depthTexOpaque = 0;
		}
		if (this.vao != 0) {
			GlApi.glDeleteVertexArrays(this.vao);
			GlApi.glDeleteBuffers(this.vbo);
			this.vao = 0;
			this.vbo = 0;
		}
		this.ourWorldFbos.clear();
		this.worldPassByFbo.clear();
		this.worldPipelineBindings.clear();
	}

	/** Memory the colour and depth targets take (bytes, both halves of each pair); 0 before first resize. */
	public long targetBytes() {
		if (this.width <= 0 || this.height <= 0) {
			return 0L;
		}
		long total = (long) this.width * this.height * 4L; // depthTex
		if (this.depthTexOpaque != 0) {
			total += (long) this.width * this.height * 4L;
		}
		for (Target t : this.targets) {
			if (t != null && t.width > 0 && t.height > 0) {
				int bpp = bytesPerPixel(t.format);
				total += 2L * t.width * t.height * bpp;
			}
		}
		return total;
	}

	private static int bytesPerPixel(final Format f) {
		int channels = (f.format == RED || f.format == RED_INTEGER) ? 1
			: (f.format == RG || f.format == RG_INTEGER) ? 2
			: (f.format == RGB) ? 3 : 4;
		if (f.internal == 35898 || f.internal == 32857) {
			return 4; // R11F_G11F_B10F, RGB10_A2
		}
		if (f.type == FLOAT || f.type == INT || f.type == UNSIGNED_INT) {
			return channels * 4;
		}
		if (f.type == HALF_FLOAT || f.type == SHORT || f.type == UNSIGNED_SHORT) {
			return channels * 2;
		}
		return channels;
	}
}
