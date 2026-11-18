// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import com.mojang.renderpearl.backend.opengl.GlApi;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class ShaderPackRuntime {
	static final int TEXTURE_2D = 3553;
	static final int FRAMEBUFFER = 36160;
	static final int COLOR_ATTACHMENT0 = 36064;
	static final int DEPTH_ATTACHMENT = 36096;
	static final int TRIANGLES = 4;
	static final int FLOAT = 5126;
	static final int UNSIGNED_BYTE = 5121;
	static final int ARRAY_BUFFER = 34962;
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
	static final int FRAMEBUFFER_COMPLETE = 36053;

	static final int T_FLOAT = 5126;
	static final int T_VEC2 = 35664;
	static final int T_VEC3 = 35665;
	static final int T_VEC4 = 35666;
	static final int T_INT = 5124;
	static final int T_IVEC2 = 35667;
	static final int T_IVEC3 = 35668;
	static final int T_IVEC4 = 35669;
	static final int T_BOOL = 35670;
	static final int T_UINT = 5125;
	static final int T_MAT3 = 35675;
	static final int T_MAT4 = 35676;
	static final int T_SAMPLER_2D = 35678;
	static final int T_SAMPLER_2D_SHADOW = 35682;

	private static final class Target {
		final int index;
		final Format format;
		int main;
		int alt;
		int width;
		int height;
		boolean mipmap;
		boolean clear = true;
		float[] clearColor = {0, 0, 0, 0};

		Target(final int index, final Format format) {
			this.index = index;
			this.format = format;
		}
	}

	static final class Format {
		final String name;
		final int internal;
		final int format;
		final int type;

		Format(final String name, final int internal, final int format, final int type) {
			this.name = name;
			this.internal = internal;
			this.format = format;
			this.type = type;
		}
	}

	private static final int HALF_FLOAT = 5131;
	private static final int RGBA = 6408;
	private static final int RED = 6403;
	private static final int RG = 33319;
	private static final int RGB = 6407;

	static Format format(final String name, final boolean floatTargets, final boolean float32) {
		String n = name == null ? "RGBA8" : name;
		Format rgba8 = new Format("RGBA8", 32856, RGBA, UNSIGNED_BYTE);
		Format rgba16f = floatTargets ? new Format("RGBA16F", 34842, RGBA, HALF_FLOAT) : rgba8;
		if (floatTargets && float32 && fixedPointRange(n) != null && n.contains("16")) {
			if (n.startsWith("RG") && !n.startsWith("RGB")) {
				return new Format("RG32F", 33328, RG, FLOAT);
			}
			return n.startsWith("R") && !n.startsWith("RG") ? new Format("R32F", 33326, RED, FLOAT) : new Format("RGBA32F", 34836, RGBA, FLOAT);
		}
		switch (n) {
			case "R8": return new Format(n, 33321, RED, UNSIGNED_BYTE);
			case "RG8": return new Format(n, 33323, RG, UNSIGNED_BYTE);
			case "RGB8":
			case "RGBA8":
			case "RGBA":
			case "RGB":
				return rgba8;
			case "RGB10_A2": return new Format(n, 32857, RGBA, 33640);
			case "RGB5_A1": return new Format(n, 32855, RGBA, UNSIGNED_BYTE);
			case "RGBA4": return new Format(n, 32854, RGBA, UNSIGNED_BYTE);
			case "R16F": return floatTargets ? new Format(n, 33325, RED, HALF_FLOAT) : rgba8;
			case "RG16F": return floatTargets ? new Format(n, 33327, RG, HALF_FLOAT) : rgba8;
			case "R16":
			case "R16_SNORM":
				return floatTargets ? new Format("R16F", 33325, RED, HALF_FLOAT) : rgba8;
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
				return rgba16f;
		}
	}

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

	private static boolean filterable(final Target t) {
		return t == null || t.format.type != FLOAT || GlApi.hasFloatLinear();
	}

	private static final java.util.regex.Pattern MAIN_DECL = java.util.regex.Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(void\\s*)?\\)");

	private String emulateFixedPoint(final String fs, final int[] drawBuffers, final Map<Integer, String> outputs) {
		if (outputs == null || !this.floatTargets) {
			return fs;
		}
		StringBuilder tail = new StringBuilder();
		for (int i = 0; i < drawBuffers.length; i++) {
			String out = outputs.get(i);
			float[] range = fixedPointRange(this.directives.formats[drawBuffers[i]]);
			if (out == null || range == null) {
				continue;
			}
			String lo = range[0] == 0.0F ? "0.0" : "-1.0";
			String steps = Float.toString(range[1]);
			tail.append(' ').append(out).append(" = round(clamp(").append(out).append(", ").append(lo).append(", 1.0) * ")
				.append(steps).append(") / ").append(steps).append(';');
		}
		java.util.regex.Matcher m = MAIN_DECL.matcher(fs);
		if (tail.length() == 0 || !m.find()) {
			return fs;
		}
		return fs.substring(0, m.start()) + "void iris_fixedMain()" + fs.substring(m.end())
			+ "\nvoid main() { iris_fixedMain();" + tail + " }\n";
	}

	private static final class Pass {
		String name;
		int program;
		int[] drawBuffers;
		boolean isFinal;
		float scale = 1.0F;
		Map<Integer, Boolean> flips = new HashMap<>();
		final List<Object[]> uniforms = new ArrayList<>();
		String vertexSource;
		String fragmentSource;
		final List<int[]> samplers = new ArrayList<>();
		int fbo;
		int[] attached = new int[8];
	}

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

	private final boolean floatTargets;
	private final ShaderPack.Directives directives;
	private final Target[] targets = new Target[ShaderPack.MAX_COLOR_TARGETS];
	private final List<Pass> passes = new ArrayList<>();
	private final List<Integer> customTextures = new ArrayList<>();
	private final Map<String, Integer> customBySampler = new HashMap<>();
	private Pass prep;
	private Pass copy;
	private int depthTex;
	private int noiseTex;
	private int shadowTex;
	private int whiteTex;
	private int blackTex;
	private int flatNormalTex;
	private int blockIdTex;

	private Pass terrainPass;
	private boolean worldFrame;
	private boolean worldRan;
	private int worldMainColor;
	private int worldMainDepth;
	private int savedFbo;
	private Object solidPipeline;
	private Object cutoutPipeline;
	private Object translucentPipeline;
	private Pass entityPass;
	private boolean linkingEntity;
	private int[] entityLocations;
	private PackPrograms.Program entitySource;
	private String entityFragment;
	private final java.util.Map<String, Pass> entityVariants = new java.util.HashMap<>();
	private final java.util.Map<Object, com.mojang.renderpearl.frontend.FrontendRenderPipeline> entityPipelines = new java.util.IdentityHashMap<>();
	private Pass waterPass;
	private final java.util.Set<Pass> worldBound = new java.util.HashSet<>();
	private Pass shadowPass;
	private int shadowFbo;
	private int shadowRes;
	private boolean shadowMode;
	private int shadowReturnFbo;
	private int shadowFrames;
	private int shadowBinds;
	private int farDepthTex;
	private int worldWidth;
	private int worldHeight;
	private boolean midDeferred;
	private boolean opaqueDepthValid;
	private int midFbo;
	private Pass sceneRedirect;
	private int blitFbo;
	private int depthTexOpaque;
	private boolean directScene;
	private final java.util.Set<Integer> translucentReadWrite = new java.util.HashSet<>();
	private ShaderUniforms worldUniforms;
	private int vao;
	private int vbo;
	private int clearFbo;
	private int samplerLinear;
	private int samplerNearest;
	private int samplerRepeat;
	private int samplerMip;
	private int samplerShadow;
	private int samplerAtlas;
	private int width = -1;
	private int height = -1;
	private final boolean[] flipped = new boolean[ShaderPack.MAX_COLOR_TARGETS];
	public final List<String> log = new ArrayList<>();
	private boolean broken;
	private int sceneTarget;
	public static int debugPasses = -1;
	public static int debugShow = -1;
	private int copySource;

	public ShaderPackRuntime(final ShaderPack.Directives directives) {
		this.directives = directives;
		this.floatTargets = GlApi.hasColorBufferFloat();
	}

	public boolean floatTargets() {
		return this.floatTargets;
	}

	private static final String PREP_VS = "#version 300 es\nlayout(location = 0) in vec3 iris_Position;\nout vec2 uv;\n"
		+ "void main() { uv = iris_Position.xy; gl_Position = vec4(iris_Position.xy * 2.0 - 1.0, 0.0, 1.0); }\n";
	private static final String PREP_FS = "#version 300 es\nprecision highp float;\nuniform sampler2D iris_SceneColor;\n"
		+ "uniform highp sampler2D iris_SceneDepth;\nin vec2 uv;\nlayout(location = 0) out vec4 color;\n"
		+ "uniform int iris_KeepSky;\nuniform int iris_DirectScene;\n"
		+ "void main() { float d = texture(iris_SceneDepth, uv).r; if (iris_KeepSky == 1 && d == 0.0) { discard; }\n"
		+ "  vec4 c = texture(iris_SceneColor, uv); if (iris_DirectScene == 1 && (d == 0.0 || c.a == 0.0)) { discard; }\n"
		+ "  color = vec4(c.rgb, 1.0); gl_FragDepth = 1.0 - d; }\n";
	private static final String COPY_FS = "#version 300 es\nprecision highp float;\nuniform sampler2D colortex0;\nin vec2 uv;\n"
		+ "layout(location = 0) out vec4 color;\nvoid main() { color = texture(colortex0, uv); }\n";

	private int compile(final int type, String source, final String what) {
		for (int round = 0; round < 10; round++) {
			int shader = GlApi.glCreateShader(type);
			GlApi.glShaderSourceRaw(shader, source);
			GlApi.glCompileShader(shader);
			if (GlApi.glGetShaderi(shader, COMPILE_STATUS) != 0) {
				this.lastSource = source;
				return shader;
			}
			String errors = GlApi.glGetShaderInfoLog(shader);
			GlApi.glDeleteShader(shader);
			String fixed = GlslFixer.fix(source, errors);
			if (fixed == null) {
				this.log.add(what + ": " + firstLines(errors, 3));
				return 0;
			}
			source = fixed;
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

	private int link(final String vs, final String fs, final String what) {
		int v = this.compile(VERTEX_SHADER, vs, what + ".vsh");
		this.lastVertexSource = this.lastSource;
		int f = v == 0 ? 0 : this.compile(FRAGMENT_SHADER, fs, what + ".fsh");
		this.lastFragmentSource = this.lastSource;
		if (v == 0 || f == 0) {
			if (v != 0) {
				GlApi.glDeleteShader(v);
			}
			return 0;
		}
		int p = GlApi.glCreateProgram();
		GlApi.glAttachShader(p, v);
		GlApi.glAttachShader(p, f);
		if (this.linkingEntity) {
			String[] names = {"iris_Position", "iris_Color", "iris_UV0", "iris_UV2i", "iris_Normal"};
			for (int i = 0; i < names.length; i++) {
				if (this.entityLocations[i] >= 0) {
					GlApi.glBindAttribLocation(p, this.entityLocations[i], names[i]);
				}
			}
			GlApi.glBindAttribLocation(p, 14, "mc_midTexCoord");
		} else if (what.startsWith("gbuffers_") || what.equals("shadow")) {
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
		GlApi.glLinkProgram(p);
		GlApi.glDeleteShader(v);
		GlApi.glDeleteShader(f);
		if (GlApi.glGetProgrami(p, LINK_STATUS) == 0) {
			this.log.add(what + ": link failed: " + firstLines(GlApi.glGetProgramInfoLog(p), 3));
			GlApi.glDeleteProgram(p);
			return 0;
		}
		return p;
	}

	public boolean build(final PackPrograms programs, final Map<String, byte[]> customTextureImages, final ImageDecoder decoder) {
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
		this.samplerMip = sampler(LINEAR_MIPMAP_LINEAR, LINEAR, CLAMP_TO_EDGE, false);
		this.samplerShadow = sampler(LINEAR, LINEAR, CLAMP_TO_EDGE, true);
		this.samplerAtlas = sampler(NEAREST_MIPMAP_LINEAR, NEAREST, CLAMP_TO_EDGE, false);

		this.whiteTex = solid(new byte[] {-1, -1, -1, -1});
		this.blackTex = solid(new byte[] {0, 0, 0, 0});
		this.flatNormalTex = solid(new byte[] {(byte) 128, (byte) 128, (byte) 255, (byte) 255});
		this.noiseTex = this.noiseTexture(programs, customTextureImages, decoder);
		this.farDepthTex = depthTexture(1, 1);
		this.clearDepth(this.farDepthTex);
		this.shadowTex = this.farDepthTex;

		for (Map.Entry<String, byte[]> e : customTextureImages.entrySet()) {
			if (e.getKey().equals("noisetex")) {
				continue;
			}
			int tex = decoder == null ? 0 : decoder.upload(e.getValue());
			if (tex != 0) {
				this.customBySampler.put(e.getKey(), tex);
				this.customTextures.add(tex);
			}
		}

		this.sceneTarget = programs.sceneTarget;
		this.target(this.sceneTarget);
		this.target(0);
		this.prep = this.pass("shaderpack_prep", PREP_VS, PREP_FS, new int[] {0});
		this.copy = this.pass("shaderpack_copy", PREP_VS, COPY_FS, new int[] {0});
		if (this.prep == null || this.copy == null) {
			this.log.add("internal programs failed to build");
			return false;
		}
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
		if (programs.terrain != null && programs.terrain.errors.isEmpty()) {
			String terrainFs = programs.terrain.fragment;
			int marker = programs.terrain.drawBuffers.length;
			this.directScene = this.floatTargets && fixedPointRange(this.directives.formats[this.sceneTarget]) != null
				&& marker < 8 && programs.terrain.drawBuffers.length > 0 && programs.terrain.drawBuffers[0] == this.sceneTarget;
			if (this.directScene) {
				java.util.regex.Matcher m = MAIN_DECL.matcher(terrainFs);
				if (m.find()) {
					terrainFs = terrainFs.substring(0, m.start()) + "void iris_markMain()" + terrainFs.substring(m.end())
						+ "\nlayout(location = " + marker + ") out vec4 iris_SceneMarker;\n"
						+ "void main() { iris_markMain(); iris_SceneMarker = vec4(0.0); }\n";
				} else {
					this.directScene = false;
				}
			}
			this.alphaTestNext = true;
			this.terrainPass = this.pass(programs.terrain.name, programs.terrain.vertex, terrainFs, programs.terrain.drawBuffers, programs.terrain.outputs);
			if (this.directScene && this.terrainPass != null) {
				this.log.add("scene buffer colortex" + this.sceneTarget + " is " + this.directives.formats[this.sceneTarget]
					+ ": terrain writes it directly");
			}
			if (this.terrainPass != null) {
				this.log.add("world program " + programs.terrain.name + " draws terrain into " + java.util.Arrays.toString(programs.terrain.drawBuffers));
			} else {
				this.log.add("world program " + programs.terrain.name + " failed; terrain stays vanilla");
			}
		}
		if (this.terrainPass != null && programs.entities != null && programs.entities.errors.isEmpty()) {
			try {
				String fs = programs.entities.fragment;
				if (this.directScene && programs.entities.drawBuffers.length > 0 && programs.entities.drawBuffers[0] == this.sceneTarget
					&& programs.entities.drawBuffers.length < 8) {
					fs = markScene(fs, programs.entities.drawBuffers.length);
				}
				this.entitySource = programs.entities;
				this.entityFragment = fs;
				this.entityPass = this.entityVariant(entityLayout(backend(net.minecraft.client.renderer.RenderPipelines.ENTITY_SOLID)));
			} catch (RuntimeException e) {
				this.entityPass = null;
				this.log.add("entities program not set up: " + e);
			} finally {
				this.linkingEntity = false;
			}
			this.log.add(this.entityPass != null ? "world program " + programs.entities.name + " draws entities"
				: "world program " + programs.entities.name + " failed; entities stay vanilla");
		}
		if (this.terrainPass != null && programs.water != null && programs.water.errors.isEmpty()) {
			this.waterPass = this.pass(programs.water.name, programs.water.vertex, programs.water.fragment, programs.water.drawBuffers, programs.water.outputs);
			this.log.add(this.waterPass != null ? "world program " + programs.water.name + " draws translucent terrain"
				: "world program " + programs.water.name + " failed; translucent terrain uses the terrain program");
		}
		if (this.terrainPass != null && programs.shadow != null && programs.shadow.errors.isEmpty()) {
			this.shadowPass = this.pass("shadow", programs.shadow.vertex, programs.shadow.fragment, new int[0]);
			if (this.shadowPass != null) {
				for (Object[] u : this.shadowPass.uniforms) {
					String n = (String) u[0];
					if (n.startsWith("iris_gb")) {
						u[0] = "iris_sh" + n.substring("iris_gb".length());
					}
				}
				int res = (int) this.directives.constFloat("shadowMapResolution", 2048.0F);
				this.shadowRes = Math.max(256, Math.min(res, Math.min(4096, GlApi.glGetInteger(3379))));
				this.shadowTex = depthTexture(this.shadowRes, this.shadowRes);
				this.shadowFbo = GlApi.glGenFramebuffers();
				GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.shadowFbo);
				GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, this.shadowTex, 0);
				GlApi.glDrawBuffers(new int[] {0});
				GlApi.glReadBuffer(0);
				if (GlApi.glCheckFramebufferStatus(FRAMEBUFFER) != FRAMEBUFFER_COMPLETE) {
					this.log.add("shadow map framebuffer incomplete; no shadows");
					this.shadowPass = null;
				} else {
					this.clearDepth(this.shadowTex);
					this.log.add("shadow pass: " + this.shadowRes + "x" + this.shadowRes + " shadow map, distance "
						+ this.directives.constFloat("shadowDistance", 160.0F));
				}
			} else {
				this.log.add("shadow program failed; no shadows");
			}
		}
		int built = 0;
		for (PackPrograms.Program p : programs.programs) {
			if (!p.errors.isEmpty()) {
				this.log.add(p.name + ": " + p.errors.get(0));
				continue;
			}
			Pass pass = this.pass(p.name, p.vertex, p.fragment, p.drawBuffers, p.outputs);
			if (pass == null) {
				continue;
			}
			pass.isFinal = p.name.equals("final");
			pass.flips.putAll(p.flips);
			pass.scale = p.scale > 0.0F && p.scale <= 1.0F ? p.scale : 1.0F;
			this.passes.add(pass);
			built++;
		}
		Pass translucent = this.waterPass != null ? this.waterPass : this.terrainPass;
		if (translucent != null) {
			for (int[] smp : translucent.samplers) {
				if (smp[1] != K_COLOR) {
					continue;
				}
				for (int idx : translucent.drawBuffers) {
					if (idx == smp[2]) {
						this.translucentReadWrite.add(idx);
					}
				}
			}
			this.sceneRedirect = new Pass();
			this.sceneRedirect.name = "shaderpack_scene";
			this.sceneRedirect.fbo = GlApi.glGenFramebuffers();
			this.blitFbo = GlApi.glGenFramebuffers();
		}
		for (int i = 0; i < ShaderPack.MAX_COLOR_TARGETS; i++) {
			if (this.targets[i] != null) {
				this.targets[i].mipmap = this.directives.mipmap[i];
				Boolean clear = this.directives.clear[i];
				this.targets[i].clear = clear == null || clear;
				float[] c = this.directives.clearColor[i];
				this.targets[i].clearColor = c != null ? c : i == 1 ? new float[] {1, 1, 1, 1} : new float[] {0, 0, 0, 0};
			}
		}
		this.log.add("built " + built + " of " + programs.programs.size() + " programs; float targets " + this.floatTargets);
		return built > 0 || programs.programs.isEmpty();
	}

	public interface ImageDecoder {
		int upload(byte[] png);
	}

	private int noiseTexture(final PackPrograms programs, final Map<String, byte[]> images, final ImageDecoder decoder) {
		byte[] png = images.get("noisetex");
		if (png != null && decoder != null) {
			int tex = decoder.upload(png);
			if (tex != 0) {
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
		return rgbaTexture(size, size, data);
	}

	static void resetUnpack() {
		GlApi.glBindBuffer(35052, 0);
		GlApi.glPixelStorei(3314, 0);
		GlApi.glPixelStorei(3315, 0);
		GlApi.glPixelStorei(3316, 0);
		GlApi.glPixelStorei(32878, 0);
		GlApi.glPixelStorei(32877, 0);
	}

	static int rgbaTexture(final int w, final int h, final byte[] data) {
		int tex = GlApi.glGenTextures();
		bindUnit0(tex);
		GlApi.glPixelStorei(3317, 1);
		resetUnpack();
		GlApi.glTexImage2D(TEXTURE_2D, 0, 32856, w, h, 0, RGBA, UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(data));
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

	private static void activeUnit(final int unit) {
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
		GlApi.glTexImage2D(TEXTURE_2D, 0, DEPTH_COMPONENT32F, w, h, 0, DEPTH_COMPONENT, FLOAT, (java.nio.ByteBuffer) null);
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
		GlApi.glClearBufferfv(6145, 0, new float[] {1.0F});
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

	private Pass pass(final String name, final String vs, final String fs, final int[] drawBuffers) {
		return this.pass(name, vs, fs, drawBuffers, null);
	}

	private Pass pass(final String name, final String vs, final String fs, final int[] drawBuffers, final Map<Integer, String> outputs) {
		boolean own = !name.startsWith("shaderpack_") && !name.equals("final");
		String frag = fs;
		if (own && this.alphaTestNext && outputs != null && outputs.get(0) != null) {
			frag = alphaTest(frag, outputs.get(0));
		}
		this.alphaTestNext = false;
		int program = this.link(vs, own ? this.emulateFixedPoint(frag, drawBuffers, outputs) : frag, name);
		if (program == 0) {
			return null;
		}
		Pass pass = new Pass();
		pass.name = name;
		pass.vertexSource = this.lastVertexSource;
		pass.fragmentSource = this.lastFragmentSource;
		pass.program = program;
		pass.drawBuffers = drawBuffers;
		pass.fbo = GlApi.glGenFramebuffers();
		boolean internal = name.startsWith("shaderpack_");
		if (!internal && !name.equals("final")) {
			for (int idx : drawBuffers) {
				this.target(idx);
			}
		}
		GlStateManager._glUseProgram(program);
		boolean world = name.startsWith("gbuffers_") || name.equals("shadow");
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
			pass.uniforms.add(new Object[] {uname, location, type});
		}
		if (unit > 16) {
			this.log.add(name + ": uses " + unit + " samplers; WebGL2 guarantees 16");
		}
		return pass;
	}

	private boolean alphaTestNext;

	private static String alphaTest(final String fs, final String out0) {
		java.util.regex.Matcher m = MAIN_DECL.matcher(fs);
		if (!m.find()) {
			return fs;
		}
		return fs.substring(0, m.start()) + "void iris_alphaMain()" + fs.substring(m.end())
			+ "\nuniform float iris_AlphaTestRef;\n"
			+ "void main() { iris_alphaMain(); if (" + out0 + ".a < iris_AlphaTestRef) { discard; } }\n";
	}

	private static String markScene(final String fs, final int marker) {
		java.util.regex.Matcher m = MAIN_DECL.matcher(fs);
		if (!m.find()) {
			return fs;
		}
		return fs.substring(0, m.start()) + "void iris_markMain()" + fs.substring(m.end())
			+ "\nlayout(location = " + marker + ") out vec4 iris_SceneMarker;\n"
			+ "void main() { iris_markMain(); iris_SceneMarker = vec4(0.0); }\n";
	}

	private static int[] entityLayout(final Object backend) {
		int[] out = {0, 1, 2, 4, 5};
		if (backend instanceof com.mojang.renderpearl.backend.opengl.GlRenderPipeline gl) {
			int[] offsets = gl.vertexArray().web263Offsets;
			int[] want = {0, 12, 16, 28, 32};
			for (int i = 0; i < want.length; i++) {
				out[i] = -1;
				for (int loc = 0; loc < offsets.length; loc++) {
					if (offsets[loc] == want[i]) {
						out[i] = loc;
					}
				}
			}
		}
		return out;
	}

	private Pass entityVariant(final int[] layout) {
		String key = java.util.Arrays.toString(layout);
		Pass p = this.entityVariants.get(key);
		if (p != null || this.entityVariants.containsKey(key)) {
			return p;
		}
		this.entityLocations = layout;
		this.linkingEntity = true;
		this.alphaTestNext = true;
		try {
			p = this.pass(this.entitySource.name, this.entitySource.vertex, this.entityFragment, this.entitySource.drawBuffers, this.entitySource.outputs);
		} finally {
			this.linkingEntity = false;
		}
		this.entityVariants.put(key, p);
		if (p != null) {
			this.log.add("entities program linked for attribute layout " + key);
		}
		return p;
	}

	private Pass entityPassByFbo(final int fbo) {
		for (Pass p : this.entityVariants.values()) {
			if (p != null && p.fbo == fbo) {
				return p;
			}
		}
		return null;
	}

	private boolean isEntityPass(final Pass p) {
		return p != null && this.entityVariants.containsValue(p);
	}

	private static boolean isSampler(final int type) {
		return type == 35678 || type == 35679 || type == 35680 || type == 35682 || type == 36289 || type == 36292
			|| type == 36293 || (type >= 36298 && type <= 36311);
	}

	private static String stageOf(final String pass) {
		return pass.startsWith("gbuffers_") || pass.equals("shadow") ? "gbuffers" : pass.startsWith("prepare") ? "prepare" : pass.startsWith("deferred") ? "deferred" : "composite";
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
		if (type != T_SAMPLER_2D && type != T_SAMPLER_2D_SHADOW) {
			return new int[] {K_NONE, 0};
		}
		if (name.equals("iris_BlockIdTable")) {
			return new int[] {K_CUSTOM, this.blockIdTex};
		}
		Integer custom = this.customBySampler.get(stage + ":" + name);
		if (custom != null) {
			return new int[] {K_CUSTOM, custom};
		}
		int color = ShaderPack.colorTargetIndex(name);
		if (color >= 0) {
			this.target(color);
			return new int[] {K_COLOR, color};
		}
		switch (name) {
			case "depthtex0":
			case "gdepthtex":
				return new int[] {K_DEPTH, 0};
			case "depthtex1":
			case "depthtex2":
				return new int[] {K_DEPTH, 1};
			case "noisetex":
				return new int[] {K_NOISE, 0};
			case "shadow":
			case "watershadow":
			case "shadowtex0":
			case "shadowtex1":
			case "shadowtex0HW":
			case "shadowtex1HW":
				return new int[] {K_SHADOW, 0};
			case "shadowcolor":
			case "shadowcolor0":
			case "shadowcolor1":
				return new int[] {K_WHITE, 0};
			default:
				return new int[] {K_BLACK, 0};
		}
	}

	private Target target(final int index) {
		if (this.targets[index] == null) {
			this.targets[index] = new Target(index, format(this.directives.formats[index], this.floatTargets, GlApi.hasFloatBlend()));
		}
		return this.targets[index];
	}

	private void ensureSize(final int w, final int h) {
		if (w == this.width && h == this.height) {
			return;
		}
		this.width = w;
		this.height = h;
		if (this.depthTex != 0) {
			GlApi.glDeleteTextures(this.depthTex);
		}
		this.depthTex = depthTexture(w, h);
		if (this.sceneRedirect != null) {
			if (this.depthTexOpaque != 0) {
				GlApi.glDeleteTextures(this.depthTexOpaque);
			}
			this.depthTexOpaque = depthTexture(w, h);
			java.util.Arrays.fill(this.sceneRedirect.attached, -1);
		}
		for (Target t : this.targets) {
			if (t == null) {
				continue;
			}
			if (t.main != 0) {
				GlApi.glDeleteTextures(t.main);
				GlApi.glDeleteTextures(t.alt);
			}
			t.main = colorTexture(t, w, h);
			t.alt = colorTexture(t, w, h);
			t.width = w;
			t.height = h;
			this.clearTarget(t, true);
		}
		for (Pass p : this.passes) {
			java.util.Arrays.fill(p.attached, -1);
		}
		java.util.Arrays.fill(this.prep.attached, -1);
		java.util.Arrays.fill(this.copy.attached, -1);
	}

	private static int colorTexture(final Target t, final int w, final int h) {
		int tex = GlApi.glGenTextures();
		bindUnit0(tex);
		resetUnpack();
		GlApi.glTexImage2D(TEXTURE_2D, 0, t.format.internal, w, h, 0, t.format.format, t.format.type, (java.nio.ByteBuffer) null);
		GlApi.glTexParameteri(TEXTURE_2D, MIN_FILTER, LINEAR);
		GlApi.glTexParameteri(TEXTURE_2D, MAG_FILTER, LINEAR);
		GlApi.glTexParameteri(TEXTURE_2D, WRAP_S, CLAMP_TO_EDGE);
		GlApi.glTexParameteri(TEXTURE_2D, WRAP_T, CLAMP_TO_EDGE);
		return tex;
	}

	private void clearTarget(final Target t, final boolean both) {
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.clearFbo);
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, 0, 0);
		GlApi.glDrawBuffers(new int[] {COLOR_ATTACHMENT0});
		for (int tex : both ? new int[] {t.main, t.alt} : new int[] {t.main, t.alt}) {
			GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, tex, 0);
			GlApi.glClearBufferfv(COLOR, 0, t.clearColor);
		}
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, 0, 0);
	}

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
		boolean mid = this.midDeferred && this.width == w && this.height == h;
		this.midDeferred = false;
		this.opaqueDepthValid = mid;
		GlStateManager._disableBlend(0);
		GlStateManager._disableCull();
		GlStateManager._disableScissorTest();
		GlStateManager._disablePolygonOffset();
		GlStateManager._colorMask(15);
		this.ensureSize(w, h);
		GlStateManager._glBindVertexArray(this.vao);
		GlApi.glVertexAttrib4f(2, 1.0F, 1.0F, 1.0F, 1.0F);
		GlApi.glVertexAttrib4f(3, 0.0F, 0.0F, 1.0F, 0.0F);
		GlApi.glVertexAttrib4f(4, 240.0F, 240.0F, 0.0F, 1.0F);

		if (mid) {
			int opaque = this.depthTex;
			this.depthTex = this.depthTexOpaque;
			this.depthTexOpaque = opaque;
			Target t0 = this.target(this.sceneTarget);
			uniforms.set("iris_KeepSky", 0.0F);
			GlStateManager._colorMask(0);
			GlStateManager._enableDepthTest();
			GlStateManager._depthFunc(ALWAYS);
			GlStateManager._depthMask(true);
			this.attach(this.prep, new int[] {this.flipped[t0.index] ? t0.alt : t0.main}, this.depthTex);
			GlApi.glClearBufferfv(6145, 0, new float[] {1.0F});
			this.draw(this.prep, mainColor, mainDepth, uniforms, w, h);
			GlStateManager._disableDepthTest();
			GlStateManager._depthMask(false);
			GlStateManager._colorMask(15);
		} else {
			this.clearsAndDeferred(mainColor, mainDepth, w, h, uniforms, false);
		}
		int passIndex = 0;
		boolean finalRan = false;
		for (Pass p : this.passes) {
			if (p.name.startsWith("prepare") || p.name.startsWith("deferred")) {
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
				this.attach(p, new int[] {mainColor}, 0);
				this.draw(p, mainColor, mainDepth, uniforms, w, h);
				finalRan = true;
				continue;
			}
			this.runPass(p, mainColor, mainDepth, uniforms, w, h);
		}
		if (!finalRan) {
			int show = debugShow >= 0 && debugShow < ShaderPack.MAX_COLOR_TARGETS && this.targets[debugShow] != null ? debugShow : 0;
			this.copySource = show;
			this.attach(this.copy, new int[] {mainColor}, 0);
			this.draw(this.copy, mainColor, mainDepth, uniforms, w, h);
		}
		for (Target t : this.targets) {
			if (t != null && !t.clear && this.flipped[t.index]) {
				int swap = t.main;
				t.main = t.alt;
				t.alt = swap;
				for (Pass p : this.passes) {
					java.util.Arrays.fill(p.attached, -1);
				}
			}
		}
	}

	private void clearsAndDeferred(final int mainColor, final int mainDepth, final int w, final int h, final ShaderUniforms uniforms, final boolean beforeTranslucent) {
		float[] fog = uniforms.value("fogColor");
		for (Target t : this.worldRan ? new Target[0] : this.targets) {
			if (t != null && t.clear && t.index != this.sceneTarget) {
				if (t.index == 0 && this.directives.clearColor[0] == null && fog != null) {
					t.clearColor = new float[] {fog[0], fog[1], fog[2], 1.0F};
				}
				this.clearTarget(t, true);
			}
		}
		java.util.Arrays.fill(this.flipped, false);

		boolean sceneTargetPrepared = false;
		for (Pass p : this.passes) {
			if (p.name.startsWith("prepare")) {
				this.runPass(p, mainColor, mainDepth, uniforms, w, h);
				for (int idx : p.drawBuffers) {
					sceneTargetPrepared |= idx == this.sceneTarget;
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
		uniforms.set("iris_KeepSky", sceneTargetPrepared && !direct ? 1.0F : 0.0F);
		uniforms.set("iris_DirectScene", 0.0F);
		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(ALWAYS);
		GlStateManager._depthMask(true);
		this.attach(this.prep, new int[] {this.flipped[t0.index] ? t0.alt : t0.main}, this.depthTex);
		GlApi.glClearBufferfv(6145, 0, new float[] {1.0F});
		if (direct) {
			GlStateManager._colorMask(0);
			this.draw(this.prep, mainColor, mainDepth, uniforms, w, h);
			GlStateManager._colorMask(15);
			GlStateManager._depthMask(false);
			uniforms.set("iris_DirectScene", 1.0F);
		}
		this.draw(this.prep, mainColor, mainDepth, uniforms, w, h);
		uniforms.set("iris_DirectScene", 0.0F);
		GlStateManager._disableDepthTest();
		GlStateManager._depthMask(false);

		for (Pass p : this.passes) {
			if (p.name.startsWith("deferred")) {
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

	public void beginWorld(final int mainColor, final int mainDepth, final int w, final int h, final ShaderUniforms uniforms) {
		this.worldFrame = false;
		this.worldRan = false;
		if (this.terrainPass == null || this.broken) {
			return;
		}
		try {
			GlStateManager._disableScissorTest();
			GlStateManager._colorMask(15);
			this.ensureSize(w, h);
			float[] fog = uniforms.value("fogColor");
			for (Target t : this.targets) {
				if (t != null && t.clear && (t.index != this.sceneTarget || this.directScene)) {
					if (t.index == 0 && this.directives.clearColor[0] == null && fog != null) {
						t.clearColor = new float[] {fog[0], fog[1], fog[2], 1.0F};
					}
					this.clearTarget(t, true);
				}
			}
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

	public void onPipelineBound(final com.mojang.renderpearl.backend.opengl.GlRenderPipeline pipeline) {
		if (!this.worldFrame || this.terrainPass == null) {
			return;
		}
		if (this.solidPipeline == null) {
			this.solidPipeline = backend(net.minecraft.client.renderer.RenderPipelines.SOLID_TERRAIN);
			this.cutoutPipeline = backend(net.minecraft.client.renderer.RenderPipelines.CUTOUT_TERRAIN);
			this.translucentPipeline = backend(net.minecraft.client.renderer.RenderPipelines.TRANSLUCENT_TERRAIN);
			if (this.entityPass != null) {
				for (com.mojang.renderpearl.api.pipeline.RenderPipeline rp : net.minecraft.client.renderer.RenderPipelines.requiredPipelines()) {
					net.minecraft.resources.Identifier vs = rp.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX);
					if (vs != null && vs.getPath().equals("core/entity")
						&& com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(rp) instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline f) {
						this.entityPipelines.put(f.backendRenderPipeline(), f);
					}
				}
				this.log.add("entities program covers " + this.entityPipelines.size() + " vanilla entity pipelines");
			}
		}
		if (this.shadowMode) {
			this.bindShadow(pipeline);
			return;
		}
		int bound = GlStateManager.getFrameBuffer(36009);
		Pass current = bound == this.terrainPass.fbo ? this.terrainPass
			: this.waterPass != null && bound == this.waterPass.fbo ? this.waterPass
			: this.entityPassByFbo(bound) != null ? this.entityPassByFbo(bound)
			: this.sceneRedirect != null && bound == this.sceneRedirect.fbo ? this.sceneRedirect : null;
		Pass want = pipeline == this.solidPipeline || pipeline == this.cutoutPipeline ? this.terrainPass
			: pipeline == this.translucentPipeline ? (this.waterPass != null ? this.waterPass : this.terrainPass)
			: this.entityPass != null && this.entityPipelines.containsKey(pipeline) ? this.entityVariant(entityLayout(pipeline)) : null;
		if (want == null) {
			ShaderPacks.atlasSampler = 0;
		}
		if (want == null && this.midDeferred) {
			if (current != this.sceneRedirect && (current != null || (bound == this.midFbo && bound != 0))) {
				Target t0 = this.targets[this.sceneTarget];
				this.attach(this.sceneRedirect, new int[] {this.flipped[t0.index] ? t0.alt : t0.main}, this.worldMainDepth);
			}
			return;
		}
		if (want == null) {
			if (current != null) {
				GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.savedFbo);
				GlApi.glDrawBuffers(new int[] {COLOR_ATTACHMENT0});
			}
			return;
		}
		try {
			if (!this.worldBound.contains(want)) {
				this.bindWorldInterface(want, pipeline, this.isEntityPass(want) ? this.entityPipelines.get(pipeline) : null);
			}
			if (current != want) {
				if (current == null) {
					this.savedFbo = bound;
				}
				boolean marked = this.directScene && (want == this.terrainPass || this.isEntityPass(want)) && !this.midDeferred
					&& want.drawBuffers.length > 0 && want.drawBuffers[0] == this.sceneTarget;
				int[] colors = new int[want.drawBuffers.length + (marked ? 1 : 0)];
				for (int i = 0; i < want.drawBuffers.length; i++) {
					int idx = want.drawBuffers[i];
					Target t = this.targets[idx];
					colors[i] = this.midDeferred ? (this.flipped[idx] ? t.alt : t.main)
						: idx == this.sceneTarget && !this.directScene ? this.worldMainColor : t.main;
				}
				if (marked) {
					colors[colors.length - 1] = this.worldMainColor;
				}
				this.attach(want, colors, this.worldMainDepth);
				GlStateManager._colorMask(15);
			}
			this.useWorldProgram(want);
			this.worldRan = true;
		} catch (RuntimeException e) {
			this.worldFrame = false;
			this.broken = true;
			this.log.add("world program failed: " + e);
			System.out.println("[shaderpack] world program failed, turning the pack off: " + e);
			GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.savedFbo);
			GlApi.glDrawBuffers(new int[] {COLOR_ATTACHMENT0});
		}
	}

	private void useWorldProgram(final Pass pass) {
		GlStateManager._glUseProgram(pass.program);
		GlApi.glVertexAttrib4f(4, 0.0F, 1.0F, 0.0F, 0.0F);
		GlApi.glVertexAttrib4f(5, 0.0F, 0.0F, 0.0F, 1.0F);
		GlApi.glVertexAttrib4f(6, 0.0F, 0.0F, 0.0F, 1.0F);
		GlApi.glVertexAttrib4f(7, 1.0F, 0.0F, 0.0F, 1.0F);
		for (int[] smp : pass.samplers) {
			if (smp[1] == K_ATLAS) {
				if (this.isEntityPass(pass)) {
					ShaderPacks.atlasSampler = 0;
				} else if (smp[0] >= 0) {
					GlApi.glBindSampler(smp[0], this.samplerAtlas);
					ShaderPacks.atlasUnit = smp[0];
					ShaderPacks.atlasSampler = this.samplerAtlas;
				}
				continue;
			}
			if (smp[1] == K_LIGHTMAP) {
				continue;
			}
			int tex = this.worldSamplerTexture(smp);
			if (smp[0] < 12) {
				GlStateManager._activeTexture(TEXTURE0 + smp[0]);
				GlStateManager._bindTexture(tex);
			} else {
				GlApi.glActiveTexture(TEXTURE0 + smp[0]);
				GlApi.glBindTexture(TEXTURE_2D, tex);
			}
			GlApi.glBindSampler(smp[0], smp[3] == T_SAMPLER_2D_SHADOW ? this.samplerShadow
				: smp[1] == K_NOISE || smp[1] == K_CUSTOM ? this.samplerRepeat : smp[1] == K_DEPTH || smp[1] == K_SHADOW ? this.samplerNearest
				: smp[1] == K_COLOR && !filterable(this.targets[smp[2]]) ? this.samplerNearest : this.samplerLinear);
		}
		GlStateManager._activeTexture(TEXTURE0);
		this.worldUniforms.apply(pass.uniforms);
	}

	public boolean beforeTranslucent() {
		if (!this.worldFrame || !this.worldRan || this.midDeferred || this.broken || this.shadowMode
			|| this.sceneRedirect == null || debugPasses >= 0) {
			return false;
		}
		boolean any = false;
		for (Pass p : this.passes) {
			any |= p.name.startsWith("deferred") || p.name.startsWith("prepare");
		}
		if (!any) {
			return false;
		}
		int bound = GlStateManager.getFrameBuffer(36009);
		boolean ours = bound == this.terrainPass.fbo || (this.waterPass != null && bound == this.waterPass.fbo);
		int ret = ours ? this.savedFbo : bound;
		int w = this.worldWidth;
		int h = this.worldHeight;
		try {
			GlStateManager._disableBlend(0);
			GlStateManager._disableCull();
			GlStateManager._disableScissorTest();
			GlStateManager._disablePolygonOffset();
			GlStateManager._colorMask(15);
			GlStateManager._glBindVertexArray(this.vao);
			GlApi.glVertexAttrib4f(2, 1.0F, 1.0F, 1.0F, 1.0F);
			GlApi.glVertexAttrib4f(3, 0.0F, 0.0F, 1.0F, 0.0F);
			GlApi.glVertexAttrib4f(4, 240.0F, 240.0F, 0.0F, 1.0F);
			this.clearsAndDeferred(this.worldMainColor, this.worldMainDepth, w, h, this.worldUniforms, true);
			for (int idx : this.translucentReadWrite) {
				Target t = this.targets[idx];
				boolean side = this.flipped[idx];
				this.blit(side ? t.alt : t.main, side ? t.main : t.alt, w, h);
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
			GlApi.glDrawBuffers(new int[] {COLOR_ATTACHMENT0});
			GlStateManager._viewport(0, 0, w, h);
		}
		return true;
	}

	private int midFrames;

	private void blit(final int from, final int to, final int w, final int h) {
		GlStateManager._glBindFramebuffer(36008, this.blitFbo);
		GlApi.glFramebufferTexture2D(36008, COLOR_ATTACHMENT0, TEXTURE_2D, from, 0);
		GlApi.glReadBuffer(COLOR_ATTACHMENT0);
		GlStateManager._glBindFramebuffer(36009, this.clearFbo);
		GlApi.glFramebufferTexture2D(36009, DEPTH_ATTACHMENT, TEXTURE_2D, 0, 0);
		GlApi.glFramebufferTexture2D(36009, COLOR_ATTACHMENT0, TEXTURE_2D, to, 0);
		GlApi.glDrawBuffers(new int[] {COLOR_ATTACHMENT0});
		GlApi.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, 16384, 9728);
		GlApi.glFramebufferTexture2D(36009, COLOR_ATTACHMENT0, TEXTURE_2D, 0, 0);
		GlApi.glFramebufferTexture2D(36008, COLOR_ATTACHMENT0, TEXTURE_2D, 0, 0);
		GlStateManager._glBindFramebuffer(36008, 0);
	}

	public int shadowResolution() {
		return this.shadowRes;
	}

	public boolean hasShadowPass() {
		return this.shadowPass != null && this.terrainPass != null && !this.broken;
	}

	public boolean beginShadow() {
		if (!this.worldFrame || this.shadowPass == null || this.broken) {
			return false;
		}
		try {
			int bound = GlStateManager.getFrameBuffer(36009);
			boolean ours = bound == this.terrainPass.fbo || (this.waterPass != null && bound == this.waterPass.fbo);
			this.shadowReturnFbo = ours ? this.savedFbo : bound;
			GlStateManager._scissorBox(0, 0, this.shadowRes, this.shadowRes);
			GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.shadowFbo);
			GlApi.glDrawBuffers(new int[] {0});
			GlStateManager._depthMask(true);
			GlApi.glClearBufferfv(6145, 0, new float[] {1.0F});
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
		if (++this.shadowFrames == 60) {
			System.out.println("[shaderpack] shadow pass: " + this.shadowBinds + " terrain pipeline binds in 60 frames");
		}
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.shadowReturnFbo);
		GlApi.glDrawBuffers(new int[] {COLOR_ATTACHMENT0});
		GlStateManager._viewport(0, 0, this.worldWidth, this.worldHeight);
		GlStateManager._scissorBox(0, 0, this.worldWidth, this.worldHeight);
	}

	private void bindShadow(final com.mojang.renderpearl.backend.opengl.GlRenderPipeline pipeline) {
		if (pipeline != this.solidPipeline && pipeline != this.cutoutPipeline) {
			return;
		}
		try {
			if (!this.worldBound.contains(this.shadowPass)) {
				this.bindWorldInterface(this.shadowPass, pipeline);
			}
			GlStateManager._glBindFramebuffer(FRAMEBUFFER, this.shadowFbo);
			GlApi.glDrawBuffers(new int[] {0});
			GlStateManager._viewport(0, 0, this.shadowRes, this.shadowRes);
			GlStateManager._scissorBox(0, 0, this.shadowRes, this.shadowRes);
			GlStateManager._enableDepthTest();
			GlStateManager._depthFunc(LEQUAL);
			GlStateManager._depthMask(true);
			GlStateManager._disableCull();
			GlStateManager._disablePolygonOffset();
			this.useWorldProgram(this.shadowPass);
			this.shadowBinds++;
		} catch (RuntimeException e) {
			this.shadowPass = null;
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
				if (!this.midDeferred) {
					return t.main;
				}
				boolean side = this.flipped[t.index];
				return this.translucentReadWrite.contains(t.index) == side ? t.main : t.alt;
			}
			case K_NOISE:
				return this.noiseTex;
			case K_DEPTH:
				return this.midDeferred ? this.depthTex : this.farDepthTex;
			case K_SHADOW:
				return this.shadowMode ? this.farDepthTex : this.shadowTex;
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

	private void bindWorldInterface(final Pass pass, final com.mojang.renderpearl.backend.opengl.GlRenderPipeline pipeline) {
		this.bindWorldInterface(pass, pipeline, null);
	}

	private void bindWorldInterface(final Pass pass, final com.mojang.renderpearl.backend.opengl.GlRenderPipeline pipeline,
		final com.mojang.renderpearl.frontend.FrontendRenderPipeline frontend) {
		Object compiled = frontend != null ? frontend
			: com.mojang.blaze3d.systems.RenderSystem.getCompiledPipeline(net.minecraft.client.renderer.RenderPipelines.SOLID_TERRAIN);
		if (!(compiled instanceof com.mojang.renderpearl.frontend.FrontendRenderPipeline f)) {
			throw new IllegalStateException("terrain pipeline is not a frontend pipeline");
		}
		com.mojang.renderpearl.backend.opengl.GlProgram vanilla = pipeline.program();
		int program = pass.program;
		GlStateManager._glUseProgram(program);
		for (String[] pair : new String[][] {{"Globals", "iris_Globals"}, {"ChunkSection", "iris_ChunkSection"}}) {
			int index = f.uniformIndices().getOrDefault(pair[0], -1);
			com.mojang.renderpearl.backend.opengl.Uniform u = index < 0 ? null : vanilla.getUniform(index);
			int block = GlApi.glGetUniformBlockIndex(program, pair[1]);
			if (block < 0) {
				continue;
			}
			if (u instanceof com.mojang.renderpearl.backend.opengl.Uniform.Ubo ubo) {
				GlApi.glUniformBlockBinding(program, block, ubo.blockBinding());
			} else {
				this.log.add(pass.name + ": no vanilla binding for " + pair[0]);
			}
		}
		for (int[] smp : pass.samplers) {
			if (smp[1] != K_ATLAS && smp[1] != K_LIGHTMAP) {
				continue;
			}
			int index = f.uniformIndices().getOrDefault(smp[1] == K_ATLAS ? "Sampler0" : "Sampler2", -1);
			com.mojang.renderpearl.backend.opengl.Uniform u = index < 0 ? null : vanilla.getUniform(index);
			if (u instanceof com.mojang.renderpearl.backend.opengl.Uniform.Sampler sampler) {
				GlApi.glUniform1i(smp[2], sampler.samplerIndex());
				smp[0] = sampler.samplerIndex();
				if (sampler.samplerIndex() >= 3) {
					this.log.add("vanilla terrain uses texture unit " + sampler.samplerIndex() + "; pack samplers start at 2 and may clash");
				}
			}
		}
		this.worldBound.add(pass);
	}

	private void runPass(final Pass p, final int mainColor, final int mainDepth, final ShaderUniforms uniforms, final int w, final int h) {
		int[] writes = new int[p.drawBuffers.length];
		for (int i = 0; i < writes.length; i++) {
			Target t = this.targets[p.drawBuffers[i]];
			writes[i] = this.flipped[t.index] ? t.main : t.alt;
		}
		this.attach(p, writes, 0);
		this.draw(p, mainColor, mainDepth, uniforms, (int) (w * p.scale), (int) (h * p.scale));
		for (int idx : p.drawBuffers) {
			Boolean force = p.flips.get(idx);
			if (force == null || force) {
				this.flipped[idx] = !this.flipped[idx];
			}
			Target t = this.targets[idx];
			if (t.mipmap && filterable(t)) {
				bindUnit0(this.flipped[idx] ? t.alt : t.main);
				GlApi.glGenerateMipmap(TEXTURE_2D);
			}
		}
	}

	public String describe(final String passName, final ShaderUniforms uniforms) {
		List<Pass> all = new ArrayList<>(this.passes);
		if (this.terrainPass != null) {
			all.add(this.terrainPass);
		}
		if (this.waterPass != null) {
			all.add(this.waterPass);
		}
		if (this.shadowPass != null) {
			all.add(this.shadowPass);
		}
		for (Pass p : all) {
			if (!p.name.equals(passName)) {
				continue;
			}
			StringBuilder b = new StringBuilder();
			b.append("== ").append(p.name).append(" draw ").append(java.util.Arrays.toString(p.drawBuffers)).append('\n');
			for (Object[] u : p.uniforms) {
				float[] v = uniforms.value((String) u[0]);
				b.append("uniform ").append(u[0]).append(" type ").append(u[2]).append(" = ").append(v == null ? "UNSET" : java.util.Arrays.toString(v)).append('\n');
			}
			for (int[] smp : p.samplers) {
				b.append("sampler unit ").append(smp[0]).append(" kind ").append(smp[1]).append(" index ").append(smp[2]).append(" type ").append(smp[3]).append('\n');
			}
			b.append("---- vertex ----\n").append(p.vertexSource).append("\n---- fragment ----\n").append(p.fragmentSource);
			return b.toString();
		}
		return "no pass " + passName;
	}

	private void attach(final Pass p, final int[] colors, final int depth) {
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, p.fbo);
		boolean changed = false;
		for (int i = 0; i < 8; i++) {
			int want = i < colors.length ? colors[i] : 0;
			if (p.attached[i] != want) {
				GlApi.glFramebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0 + i, TEXTURE_2D, want, 0);
				p.attached[i] = want;
				changed = true;
			}
		}
		GlApi.glFramebufferTexture2D(FRAMEBUFFER, DEPTH_ATTACHMENT, TEXTURE_2D, depth, 0);
		int[] bufs = new int[colors.length];
		for (int i = 0; i < bufs.length; i++) {
			bufs[i] = COLOR_ATTACHMENT0 + i;
		}
		GlApi.glDrawBuffers(bufs);
		if (changed && GlApi.glCheckFramebufferStatus(FRAMEBUFFER) != FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException(p.name + ": framebuffer incomplete (" + GlApi.glCheckFramebufferStatus(FRAMEBUFFER) + ")");
		}
	}

	private void draw(final Pass p, final int mainColor, final int mainDepth, final ShaderUniforms uniforms, final int vw, final int vh) {
		GlStateManager._viewport(0, 0, vw, vh);
		GlStateManager._glUseProgram(p.program);
		for (int[] s : p.samplers) {
			int unit = s[0];
			int tex;
			int sampler = this.samplerLinear;
			switch (s[1]) {
				case K_COLOR: {
					Target t = this.targets[p == this.copy ? this.copySource : s[2]];
					tex = this.flipped[t.index] ? t.alt : t.main;
					if (!filterable(t)) {
						sampler = this.samplerNearest;
					} else if (t.mipmap) {
						sampler = this.samplerMip;
					}
					break;
				}
				case K_DEPTH:
					tex = s[2] == 1 && this.opaqueDepthValid ? this.depthTexOpaque : this.depthTex;
					sampler = this.samplerNearest;
					break;
				case K_MAIN:
					tex = mainColor;
					sampler = this.samplerNearest;
					break;
				case K_MAIN_DEPTH:
					tex = mainDepth;
					sampler = this.samplerNearest;
					break;
				case K_NOISE:
					tex = this.noiseTex;
					sampler = this.samplerRepeat;
					break;
				case K_SHADOW:
					tex = this.shadowTex;
					sampler = s[3] == T_SAMPLER_2D_SHADOW ? this.samplerShadow : this.samplerNearest;
					break;
				case K_WHITE:
					tex = this.whiteTex;
					break;
				case K_FLAT_NORMAL:
					tex = this.flatNormalTex;
					break;
				case K_CUSTOM:
					tex = s[2];
					sampler = this.samplerRepeat;
					break;
				case K_NONE:
					tex = 0;
					break;
				default:
					tex = this.blackTex;
			}
			activeUnit(unit);
			if (s[1] != K_NONE) {
				forceBind(tex);
			}
			GlApi.glBindSampler(unit, sampler);
		}
		uniforms.apply(p.uniforms);
		GlApi.glDrawArrays(TRIANGLES, 0, 6);
	}

	public static void resyncGlState() {
		GlStateManager._glBindVertexArray(0);
		for (int unit = 0; unit < 16; unit++) {
			GlApi.glBindSampler(unit, 0);
		}
		for (int unit = 0; unit < 12; unit++) {
			GlStateManager._activeTexture(TEXTURE0 + (unit == 0 ? 1 : 0));
			GlStateManager._activeTexture(TEXTURE0 + unit);
			GlStateManager._bindTexture(1);
			GlStateManager._bindTexture(0);
		}
		for (int unit = 12; unit < 16; unit++) {
			GlApi.glActiveTexture(TEXTURE0 + unit);
			GlApi.glBindTexture(TEXTURE_2D, 0);
		}
		GlStateManager._activeTexture(TEXTURE0 + 1);
		GlStateManager._activeTexture(TEXTURE0);
		GlStateManager._glBindFramebuffer(FRAMEBUFFER, 0);
		GlStateManager._depthFunc(LEQUAL);
	}

	public void close() {
		for (Pass p : this.passes) {
			GlApi.glDeleteProgram(p.program);
			GlApi.glDeleteFramebuffers(p.fbo);
		}
		for (Pass p : this.entityVariants.values()) {
			if (p != null) {
				GlApi.glDeleteProgram(p.program);
				GlApi.glDeleteFramebuffers(p.fbo);
			}
		}
		for (Pass p : new Pass[] {this.prep, this.copy, this.terrainPass, this.waterPass, this.shadowPass}) {
			if (p != null) {
				GlApi.glDeleteProgram(p.program);
				GlApi.glDeleteFramebuffers(p.fbo);
			}
		}
		if (this.shadowFbo != 0) {
			GlApi.glDeleteFramebuffers(this.shadowFbo);
		}
		if (this.shadowTex != 0 && this.shadowTex != this.farDepthTex) {
			GlApi.glDeleteTextures(this.shadowTex);
		}
		for (Target t : this.targets) {
			if (t != null && t.main != 0) {
				GlApi.glDeleteTextures(t.main);
				GlApi.glDeleteTextures(t.alt);
			}
		}
		for (int tex : new int[] {this.depthTex, this.noiseTex, this.farDepthTex, this.whiteTex, this.blackTex, this.flatNormalTex, this.blockIdTex}) {
			if (tex != 0) {
				GlApi.glDeleteTextures(tex);
			}
		}
		for (int tex : this.customTextures) {
			GlApi.glDeleteTextures(tex);
		}
		ShaderPacks.atlasSampler = 0;
		for (int s : new int[] {this.samplerLinear, this.samplerNearest, this.samplerRepeat, this.samplerMip, this.samplerShadow, this.samplerAtlas}) {
			if (s != 0) {
				GlApi.glDeleteSamplers(s);
			}
		}
		if (this.clearFbo != 0) {
			GlApi.glDeleteFramebuffers(this.clearFbo);
		}
		if (this.sceneRedirect != null) {
			GlApi.glDeleteFramebuffers(this.sceneRedirect.fbo);
			GlApi.glDeleteFramebuffers(this.blitFbo);
			if (this.depthTexOpaque != 0) {
				GlApi.glDeleteTextures(this.depthTexOpaque);
			}
		}
		if (this.vao != 0) {
			GlApi.glDeleteVertexArrays(this.vao);
			GlApi.glDeleteBuffers(this.vbo);
		}
		this.passes.clear();
	}

	public long targetBytes() {
		long total = (long) this.width * this.height * 4L;
		for (Target t : this.targets) {
			if (t != null) {
				int bpp = t.format.type == FLOAT ? (t.format.format == RGBA ? 16 : t.format.format == RG ? 8 : 4)
					: t.format.type == HALF_FLOAT ? (t.format.format == RGBA ? 8 : t.format.format == RG ? 4 : t.format.format == RGB ? 4 : 2)
					: (t.format.format == RED ? 1 : t.format.format == RG ? 2 : 4);
				total += 2L * this.width * this.height * bpp;
			}
		}
		return total;
	}
}
