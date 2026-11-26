// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import com.mojang.renderpearl.backend.opengl.GlApi;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * The OptiFine/Iris uniform set, computed once a frame from the extracted render state, plus the
 * pack's own {@code uniform.*}/{@code variable.*} expressions from shaders.properties.
 *
 * <p>26.3 renders with reversed depth: its projection maps near to 1 and far to 0. On WebGL2
 * (no clip control) that projection is the OpenGL one with the z row negated, so
 * {@code gbufferProjection = diag(1, 1, -1, 1) * vanilla}; the runtime converts the depth
 * buffer with {@code 1 - d} to match, and packs see the depth convention they were written for.
 */
public final class ShaderUniforms {
	// Iris MC_RENDER_STAGE_* indices (matches PackPrograms.standardMacros)
	public static final int STAGE_NONE = 0;
	public static final int STAGE_SKY = 1;
	public static final int STAGE_SUNSET = 2;
	public static final int STAGE_CUSTOM_SKY = 3;
	public static final int STAGE_SUN = 4;
	public static final int STAGE_MOON = 5;
	public static final int STAGE_STARS = 6;
	public static final int STAGE_VOID = 7;
	public static final int STAGE_TERRAIN_SOLID = 8;
	public static final int STAGE_TERRAIN_CUTOUT_MIPPED = 9;
	public static final int STAGE_TERRAIN_CUTOUT = 10;
	public static final int STAGE_ENTITIES = 11;
	public static final int STAGE_BLOCK_ENTITIES = 12;
	public static final int STAGE_DESTROY = 13;
	public static final int STAGE_OUTLINE = 14;
	public static final int STAGE_DEBUG = 15;
	public static final int STAGE_HAND_SOLID = 16;
	public static final int STAGE_TERRAIN_TRANSLUCENT = 17;
	public static final int STAGE_TRIPWIRE = 18;
	public static final int STAGE_PARTICLES = 19;
	public static final int STAGE_CLOUDS = 20;
	public static final int STAGE_RAIN_SNOW = 21;
	public static final int STAGE_WORLD_BORDER = 22;
	public static final int STAGE_HAND_TRANSLUCENT = 23;

	/**
	 * Mutable, reused uniform value slot. Version increments ONLY when one or more floats change,
	 * so per-pass uniform application skips unchanged values in a single integer comparison with
	 * zero allocations and zero array comparisons.
	 */
	static final class Slot {
		float[] value;
		int version;

		Slot(final int length) {
			this.value = new float[length];
		}
	}

	/** One active uniform binding on a linked pass. */
	static final class UniformEntry {
		String name;
		final int location;
		final int type;
		Slot slot;
		int lastVersion = -1;

		UniformEntry(final String name, final int location, final int type) {
			this.name = name;
			this.location = location;
			this.type = type;
		}
	}

	/** One parsed custom {@code uniform.*} or {@code variable.*} entry from shaders.properties. */
	private static final class CustomEntry {
		final String name;
		final String glslType;
		final int targetLen;
		final int convKind; // 0 = float, 1 = int/uint, 2 = bool
		final PackExpression expr;
		final Slot slot;

		CustomEntry(final String name, final String glslType, final PackExpression expr, final Slot slot) {
			this.name = name;
			this.glslType = glslType;
			this.targetLen = typeLength(glslType);
			this.convKind = typeConvKind(glslType);
			this.expr = expr;
			this.slot = slot;
		}
	}

	private static int typeLength(final String glslType) {
		switch (glslType) {
			case "vec2":
			case "ivec2":
			case "bvec2":
			case "uvec2":
				return 2;
			case "vec3":
			case "ivec3":
			case "bvec3":
			case "uvec3":
				return 3;
			case "vec4":
			case "ivec4":
			case "bvec4":
			case "uvec4":
			case "mat2":
				return 4;
			case "mat3":
				return 9;
			case "mat4":
				return 16;
			default:
				return 1;
		}
	}

	private static int typeConvKind(final String glslType) {
		if (glslType.equals("int") || glslType.equals("uint") || glslType.startsWith("ivec") || glslType.startsWith("uvec")) {
			return 1;
		}
		if (glslType.equals("bool") || glslType.startsWith("bvec")) {
			return 2;
		}
		return 0;
	}

	private static final Matrix4f IDENTITY_MAT4 = new Matrix4f();
	/** Standard glOrtho(0, 1, 0, 1, 0, 1): m00=2, m11=2, m22=-2, m30=-1, m31=-1, m32=-1, m33=1. */
	private static final Matrix4f ORTHO_MAT4 = new Matrix4f().setOrtho(0.0F, 1.0F, 0.0F, 1.0F, 0.0F, 1.0F);
	private static final Matrix4f ORTHO_MAT4_INV = new Matrix4f(ORTHO_MAT4).invert();
	private static final Matrix4f LIGHTMAP_TEX_MAT4 = new Matrix4f().scaling(1.0F / 256.0F).translate(8.0F, 8.0F, 8.0F);
	private static final Matrix3f IDENTITY_MAT3 = new Matrix3f();

	private final Map<String, Slot> slots = new HashMap<>(160);
	private final List<CustomEntry> customs = new ArrayList<>();
	private final PackExpression.Context context = new PackExpression.Context();
	private final float sunPathRotation;
	private final float shadowDistance;
	private final float shadowHalfDepth;
	private final float shadowInterval;
	private final float eyeBrightnessHalflife;
	private final float centerDepthHalflife;
	private final float wetnessHalflife;
	private final float drynessHalflife;

	private final Matrix4f prevModelView = new Matrix4f();
	private final Matrix4f prevProjection = new Matrix4f();
	private double prevCamX;
	private double prevCamY;
	private double prevCamZ;
	private boolean first = true;
	private long lastNanos;
	private double timeCounter;
	private int frameCounter;
	private int frameId;
	private float eyeSkySmooth = -1.0F;
	private float eyeBlockSmooth;
	private float wetness;
	private float centerDepth = 1.0F;

	// Pre-allocated scratch math objects (zero allocations per frame in update())
	private final Matrix4f scratchBobbed = new Matrix4f();
	private final Matrix4f scratchProj = new Matrix4f();
	private final Matrix4f scratchProjInv = new Matrix4f();
	private final Matrix4f scratchBob = new Matrix4f();
	private final Matrix4f scratchModelView = new Matrix4f();
	private final Matrix4f scratchModelViewInv = new Matrix4f();
	private final Matrix4f scratchGbMvp = new Matrix4f();
	private final Matrix4f scratchCelestial = new Matrix4f();
	private final Matrix4f scratchShadowMV = new Matrix4f();
	private final Matrix4f scratchShadowMVInv = new Matrix4f();
	private final Matrix4f scratchShadowMvp = new Matrix4f();
	private final Matrix4f shadowProj = new Matrix4f();
	private final Matrix4f shadowProjInv = new Matrix4f();
	private final Matrix3f scratchMat3 = new Matrix3f();
	private final Vector4f scratchVec4A = new Vector4f();
	private final Vector4f scratchVec4B = new Vector4f();
	private final Vector4f scratchVec4C = new Vector4f();
	private final Vector4f scratchRow0 = new Vector4f();
	private final Vector4f scratchRow1 = new Vector4f();
	private final Vector4f scratchRow2 = new Vector4f();
	private final Vector4f scratchRow3 = new Vector4f();
	private final float[] scratchMat16 = new float[16];
	private final float[] scratchMat9 = new float[9];

	// Pre-bound frequently updated slots (zero HashMap lookups for per-draw updates)
	private final Slot renderStageSlot;
	private final Slot alphaTestRefSlot;
	private final Slot entityColorSlot;
	private final Slot colorModulatorSlot;

	/** Set by the runtime before {@link #update}: this frame reads the shadow map drawn earlier. */
	public boolean reuseShadow;
	private final Matrix4f freshShadowMV = new Matrix4f();
	private final Matrix4f heldShadowMV = new Matrix4f();
	private double freshCamX;
	private double freshCamY;
	private double freshCamZ;
	private double heldCamX;
	private double heldCamY;
	private double heldCamZ;
	private boolean heldShadow;

	public ShaderUniforms(final PackPrograms programs) {
		ShaderPack.Directives d = programs.directives;
		this.sunPathRotation = d.constFloat("sunPathRotation", 0.0F);
		this.shadowDistance = d.constFloat("shadowDistance", 160.0F);
		this.shadowHalfDepth = Math.max(128.0F, this.shadowDistance * 1.25F);
		this.shadowInterval = d.constFloat("shadowIntervalSize", 2.0F);
		this.eyeBrightnessHalflife = d.constFloat("eyeBrightnessHalflife", 10.0F);
		this.centerDepthHalflife = d.constFloat("centerDepthHalflife", 1.0F);
		this.wetnessHalflife = d.constFloat("wetnessHalflife", 600.0F);
		this.drynessHalflife = d.constFloat("drynessHalflife", 200.0F);

		this.shadowProj.setOrthoSymmetric(this.shadowDistance * 2.0F, this.shadowDistance * 2.0F, 0.05F, this.shadowHalfDepth * 2.0F);
		this.shadowProjInv.set(this.shadowProj).invert();

		// Pre-register static and aliased slots so aliases share one Slot & version counter
		this.initAliasesAndStatics();
		this.renderStageSlot = this.slot("renderStage", 1);
		this.alphaTestRefSlot = this.slot("iris_AlphaTestRef", 1);
		this.slots.put("alphaTestRef", this.alphaTestRefSlot);
		this.entityColorSlot = this.slot("entityColor", 4);
		this.colorModulatorSlot = this.slot("iris_ColorModulator", 4);
		this.slots.put("colorModulator", this.colorModulatorSlot);

		// Parse custom uniform.* / variable.* and topologically sort by referencedVars()
		Map<String, CustomEntry> parsedMap = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : programs.properties.asMap().entrySet()) {
			String k = e.getKey();
			boolean isUniform = k.startsWith("uniform.");
			if (!isUniform && !k.startsWith("variable.")) {
				continue;
			}
			String[] parts = k.split("\\.", 3);
			if (parts.length < 3) {
				continue;
			}
			String glslType = parts[1].trim().toLowerCase(Locale.ROOT);
			String name = parts[2].trim();
			try {
				PackExpression expr = PackExpression.parse(e.getValue());
				Slot s = this.slot(name, typeLength(glslType));
				parsedMap.put(name, new CustomEntry(name, glslType, expr, s));
			} catch (IllegalArgumentException ex) {
				programs.log.add("custom uniform " + name + " not understood: " + ex.getMessage());
			}
		}
		this.topologicalSortCustoms(parsedMap);

		this.context.scope = name -> {
			Slot s = this.slots.get(name);
			return s == null ? null : s.value;
		};
	}

	private void initAliasesAndStatics() {
		// Matrix aliases: computed ONCE per frame, shared across Iris/OptiFine/Core/DH names
		alias("gbufferModelView", 16, "iris_gbModelView", "modelViewMatrix");
		alias("gbufferModelViewInverse", 16, "iris_gbModelViewInverse", "modelViewMatrixInverse");
		alias("gbufferProjection", 16, "iris_gbProjection", "projectionMatrix", "dhProjection");
		alias("gbufferProjectionInverse", 16, "iris_gbProjectionInverse", "projectionMatrixInverse", "dhProjectionInverse");
		alias("iris_gbModelViewProjection", 16, "modelViewProjectionMatrix");
		alias("iris_gbNormalMatrix", 9, "normalMatrix");
		alias("gbufferPreviousProjection", 16, "dhPreviousProjection");

		alias("shadowModelView", 16, "iris_shModelView");
		alias("shadowModelViewInverse", 16, "iris_shModelViewInverse");
		alias("shadowProjection", 16, "iris_shProjection");
		alias("shadowProjectionInverse", 16, "iris_shProjectionInverse");

		// Static matrices (invariant across frames)
		putMat4("shadowProjection", this.shadowProj);
		putMat4("shadowProjectionInverse", this.shadowProjInv);
		putMat4("iris_ModelViewMatrix", IDENTITY_MAT4);
		aliasExisting("iris_ModelViewMatrixInverse", "iris_ModelViewMatrix");
		putMat4("iris_ProjectionMatrix", ORTHO_MAT4);
		putMat4("iris_ProjectionMatrixInverse", ORTHO_MAT4_INV);
		putMat4("iris_TextureMatrix0", IDENTITY_MAT4);
		aliasExisting("textureMatrix", "iris_TextureMatrix0");
		putMat4("iris_TextureMatrix1", LIGHTMAP_TEX_MAT4);
		aliasExisting("iris_TextureMatrix2", "iris_TextureMatrix1");
		putMat3("iris_NormalMatrix", IDENTITY_MAT3);
	}

	private void alias(final String primary, final int len, final String... others) {
		Slot s = this.slot(primary, len);
		for (String other : others) {
			this.slots.put(other, s);
		}
	}

	private void aliasExisting(final String aliasName, final String targetName) {
		Slot s = this.slots.get(targetName);
		if (s != null) {
			this.slots.put(aliasName, s);
		}
	}

	private void topologicalSortCustoms(final Map<String, CustomEntry> parsed) {
		Set<String> visited = new HashSet<>();
		Set<String> visiting = new HashSet<>();
		for (CustomEntry entry : parsed.values()) {
			this.visitCustom(entry, parsed, visited, visiting);
		}
	}

	private void visitCustom(final CustomEntry entry, final Map<String, CustomEntry> parsed,
		final Set<String> visited, final Set<String> visiting) {
		if (!visited.add(entry.name)) {
			return;
		}
		visiting.add(entry.name);
		for (String dep : entry.expr.referencedVars()) {
			CustomEntry depEntry = parsed.get(dep);
			if (depEntry != null && !visiting.contains(dep)) {
				this.visitCustom(depEntry, parsed, visited, visiting);
			}
		}
		visiting.remove(entry.name);
		this.customs.add(entry);
	}

	/**
	 * The perspective part of {@code P * B} (B rigid, applied in view space). For a perspective
	 * matrix the bottom row is (0, 0, -1, 0), so the product's bottom row is minus B's third row;
	 * rows 0 and 1 are B's first rows scaled by the x and y focal terms, and row 2 gives the depth
	 * terms. Without bobbing B is the identity and the result equals the input.
	 */
	static Matrix4f purePerspective(final Matrix4f m) {
		return purePerspectiveInto(m, new Matrix4f(), new Vector4f(), new Vector4f(), new Vector4f(), new Vector4f());
	}

	private static Matrix4f purePerspectiveInto(final Matrix4f m, final Matrix4f dst,
		final Vector4f r0, final Vector4f r1, final Vector4f r2, final Vector4f r3) {
		m.getRow(0, r0);
		m.getRow(1, r1);
		m.getRow(2, r2);
		m.getRow(3, r3);
		float bx = -r3.x;
		float by = -r3.y;
		float bz = -r3.z;
		float tz = -r3.w;
		float a = (float) Math.sqrt(r0.x * r0.x + r0.y * r0.y + r0.z * r0.z);
		float b = (float) Math.sqrt(r1.x * r1.x + r1.y * r1.y + r1.z * r1.z);
		if (a < 1.0E-6F || b < 1.0E-6F || Math.abs(bx * bx + by * by + bz * bz - 1.0F) > 0.01F) {
			return dst.set(m); // not a perspective times a rigid transform: leave it
		}
		float c = r2.x * bx + r2.y * by + r2.z * bz;
		float d = r2.w - c * tz;
		dst.zero();
		dst.m00(a);
		dst.m11(b);
		dst.m22(c);
		dst.m23(-1.0F);
		dst.m32(d);
		return dst;
	}

	private Slot slot(final String name, final int len) {
		Slot s = this.slots.get(name);
		if (s == null) {
			s = new Slot(len);
			this.slots.put(name, s);
		} else if (s.value.length != len) {
			s.value = new float[len];
			s.version++;
		}
		return s;
	}

	private void put1(final String name, final float x) {
		Slot s = this.slot(name, 1);
		if (s.version == 0 || s.value[0] != x) {
			s.value[0] = x;
			s.version++;
		}
	}

	private void put2(final String name, final float x, final float y) {
		Slot s = this.slot(name, 2);
		float[] v = s.value;
		if (s.version == 0 || v[0] != x || v[1] != y) {
			v[0] = x;
			v[1] = y;
			s.version++;
		}
	}

	private void put3(final String name, final float x, final float y, final float z) {
		Slot s = this.slot(name, 3);
		float[] v = s.value;
		if (s.version == 0 || v[0] != x || v[1] != y || v[2] != z) {
			v[0] = x;
			v[1] = y;
			v[2] = z;
			s.version++;
		}
	}

	private void put4(final String name, final float x, final float y, final float z, final float w) {
		Slot s = this.slot(name, 4);
		float[] v = s.value;
		if (s.version == 0 || v[0] != x || v[1] != y || v[2] != z || v[3] != w) {
			v[0] = x;
			v[1] = y;
			v[2] = z;
			v[3] = w;
			s.version++;
		}
	}

	private void putMat4(final String name, final Matrix4f m) {
		Slot s = this.slot(name, 16);
		m.get(this.scratchMat16);
		float[] v = s.value;
		boolean diff = s.version == 0;
		for (int i = 0; i < 16; i++) {
			if (v[i] != this.scratchMat16[i]) {
				v[i] = this.scratchMat16[i];
				diff = true;
			}
		}
		if (diff) {
			s.version++;
		}
	}

	private void putMat3(final String name, final Matrix3f m) {
		Slot s = this.slot(name, 9);
		m.get(this.scratchMat9);
		float[] v = s.value;
		boolean diff = s.version == 0;
		for (int i = 0; i < 9; i++) {
			if (v[i] != this.scratchMat9[i]) {
				v[i] = this.scratchMat9[i];
				diff = true;
			}
		}
		if (diff) {
			s.version++;
		}
	}

	/** Monotonic frame id incremented per {@link #update}, used to skip redundant uniform loops. */
	public int frameId() {
		return this.frameId;
	}

	/** The shadow map has just been drawn with this frame's matrices: later frames may keep it. */
	public void commitShadow() {
		this.heldShadowMV.set(this.freshShadowMV);
		this.heldCamX = this.freshCamX;
		this.heldCamY = this.freshCamY;
		this.heldCamZ = this.freshCamZ;
		this.heldShadow = true;
	}

	/** Halflife in seconds (Iris/OptiFine units are already seconds). */
	private static float halflifeStep(final float current, final float target, final float halflifeSeconds, final float dt) {
		if (halflifeSeconds <= 0.0F) {
			return target;
		}
		float hl = Math.max(halflifeSeconds, 0.001F);
		float k = (float) (1.0 - Math.pow(0.5, dt / hl));
		return current + (target - current) * k;
	}

	private static float heldBlockLight(final ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0.0F;
		}
		Item item = stack.getItem();
		if (item == Items.LAVA_BUCKET) {
			return 15.0F;
		}
		if (item == Items.GLOW_BERRIES) {
			return 14.0F;
		}
		if (item == Items.BLAZE_ROD) {
			return 10.0F;
		}
		if (item == Items.GLOW_INK_SAC || item == Items.SPECTRAL_ARROW) {
			return 8.0F;
		}
		if (item instanceof BlockItem blockItem) {
			return (float) blockItem.getBlock().defaultBlockState().getLightEmission();
		}
		return 0.0F;
	}

	/** Computes every value for this frame. {@code projection} is vanilla's (bobbing included). */
	public void update(final Minecraft mc, final GameRenderState state, final Matrix4f projection, final int width, final int height) {
		this.frameId++;
		long now = System.nanoTime();
		float dt = this.lastNanos == 0 ? 0.016F : (float) Math.min((now - this.lastNanos) / 1.0E9, 1.0);
		this.lastNanos = now;
		this.timeCounter = (this.timeCounter + dt) % 3600.0;
		this.frameCounter = (this.frameCounter + 1) % 720720;
		this.context.frameTime = dt;

		CameraRenderState camera = state.levelRenderState.cameraRenderState;
		SkyRenderState sky = state.levelRenderState.skyRenderState;
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		float partial = state.levelRenderState.worldPartialTicks;

		// Split view bobbing out of projection into modelView (zero allocations)
		this.scratchBobbed.scaling(1.0F, 1.0F, -1.0F).mul(projection);
		purePerspectiveInto(this.scratchBobbed, this.scratchProj, this.scratchRow0, this.scratchRow1, this.scratchRow2, this.scratchRow3);
		this.scratchProjInv.set(this.scratchProj).invert();
		this.scratchBob.set(this.scratchProjInv).mul(this.scratchBobbed);
		this.scratchModelView.set(this.scratchBob).mul(camera.viewRotationMatrix);
		this.scratchModelViewInv.set(this.scratchModelView).invert();
		this.scratchGbMvp.set(this.scratchProj).mul(this.scratchModelView);

		if (this.first) {
			this.prevModelView.set(this.scratchModelView);
			this.prevProjection.set(this.scratchProj);
		}
		// Aliased: updates gbufferModelView, iris_gbModelView, modelViewMatrix (and inverses, proj, DH)
		putMat4("gbufferModelView", this.scratchModelView);
		putMat4("gbufferModelViewInverse", this.scratchModelViewInv);
		putMat4("gbufferProjection", this.scratchProj);
		putMat4("gbufferProjectionInverse", this.scratchProjInv);
		putMat4("iris_gbModelViewProjection", this.scratchGbMvp);

		// Upper 3x3 inverse-transpose of modelView for normalMatrix / iris_gbNormalMatrix
		this.scratchMat3.set(this.scratchModelView);
		if (Math.abs(this.scratchMat3.determinant()) > 1.0E-8F) {
			this.scratchMat3.invert().transpose();
		}
		putMat3("iris_gbNormalMatrix", this.scratchMat3);

		// Write previous matrices (including dhPreviousProjection alias) BEFORE overwriting prev*
		putMat4("gbufferPreviousModelView", this.prevModelView);
		putMat4("gbufferPreviousProjection", this.prevProjection);
		this.prevModelView.set(this.scratchModelView);
		this.prevProjection.set(this.scratchProj);

		// Per-draw defaults at frame start
		this.setAlphaTestRef(0.1F);
		this.setRenderStage(STAGE_NONE);
		this.setDynamicEntityColor(0.0F, 0.0F, 0.0F, 0.0F);
		this.setColorModulator(1.0F, 1.0F, 1.0F, 1.0F);

		// Double-precision camera position & split int/fract for TAA stability far from spawn
		double camX = camera.pos.x;
		double camY = camera.pos.y;
		double camZ = camera.pos.z;
		if (this.first) {
			this.prevCamX = camX;
			this.prevCamY = camY;
			this.prevCamZ = camZ;
		}
		double camIntX = Math.floor(camX);
		double camIntY = Math.floor(camY);
		double camIntZ = Math.floor(camZ);
		double prevIntX = Math.floor(this.prevCamX);
		double prevIntY = Math.floor(this.prevCamY);
		double prevIntZ = Math.floor(this.prevCamZ);
		put3("cameraPosition", (float) camX, (float) camY, (float) camZ);
		put3("previousCameraPosition", (float) this.prevCamX, (float) this.prevCamY, (float) this.prevCamZ);
		put3("cameraPositionInt", (float) camIntX, (float) camIntY, (float) camIntZ);
		put3("cameraPositionFract", (float) (camX - camIntX), (float) (camY - camIntY), (float) (camZ - camIntZ));
		put3("previousCameraPositionInt", (float) prevIntX, (float) prevIntY, (float) prevIntZ);
		put3("previousCameraPositionFract", (float) (this.prevCamX - prevIntX), (float) (this.prevCamY - prevIntY), (float) (this.prevCamZ - prevIntZ));
		this.prevCamX = camX;
		this.prevCamY = camY;
		this.prevCamZ = camZ;
		put1("eyeAltitude", (float) camY);

		// Celestial: vanilla's sun angle is 0 at noon; OptiFine's sunAngle is 0.25 at noon
		boolean hasSun = sky.skybox == DimensionType.Skybox.OVERWORLD;
		float celestial = hasSun ? (float) (sky.sunAngle / (2.0 * Math.PI)) : 0.0F;
		celestial -= (float) Math.floor(celestial);
		float sunAngle = celestial < 0.75F ? celestial + 0.25F : celestial - 0.75F;
		float shadowAngle = sunAngle <= 0.5F ? sunAngle : sunAngle - 0.5F;
		put1("sunAngle", sunAngle);
		put1("shadowAngle", shadowAngle);
		this.scratchCelestial.set(this.scratchModelView)
			.rotateY((float) Math.toRadians(-90.0))
			.rotateZ((float) Math.toRadians(this.sunPathRotation))
			.rotateX((float) (celestial * 2.0 * Math.PI));
		this.scratchVec4A.set(0.0F, 100.0F, 0.0F, 0.0F);
		this.scratchCelestial.transform(this.scratchVec4A);
		this.scratchVec4B.set(0.0F, -100.0F, 0.0F, 0.0F);
		this.scratchCelestial.transform(this.scratchVec4B);
		this.scratchVec4C.set(0.0F, 100.0F, 0.0F, 0.0F);
		this.scratchModelView.transform(this.scratchVec4C);
		put3("sunPosition", this.scratchVec4A.x, this.scratchVec4A.y, this.scratchVec4A.z);
		put3("moonPosition", this.scratchVec4B.x, this.scratchVec4B.y, this.scratchVec4B.z);
		Vector4f light = sunAngle <= 0.5F ? this.scratchVec4A : this.scratchVec4B;
		put3("shadowLightPosition", light.x, light.y, light.z);
		put3("upPosition", this.scratchVec4C.x, this.scratchVec4C.y, this.scratchVec4C.z);

		// Shadow matrices (dynamically scaled depth range for large shadowDistance)
		float skyAngle = shadowAngle - 0.25F;
		if (skyAngle < 0.0F) {
			skyAngle += 1.0F;
		}
		this.scratchShadowMV.identity()
			.translate(0.0F, 0.0F, -this.shadowHalfDepth)
			.rotateX((float) (Math.PI / 2.0))
			.rotateZ((float) (-skyAngle * 2.0 * Math.PI))
			.rotateX((float) Math.toRadians(this.sunPathRotation));
		float iv = this.shadowInterval;
		if (iv > 0.0F) {
			double div = iv;
			float mx = (float) (camX - Math.floor(camX / div) * div) - iv * 0.5F;
			float my = (float) (camY - Math.floor(camY / div) * div) - iv * 0.5F;
			float mz = (float) (camZ - Math.floor(camZ / div) * div) - iv * 0.5F;
			this.scratchShadowMV.translate(mx, my, mz);
		}
		this.freshShadowMV.set(this.scratchShadowMV);
		this.freshCamX = camX;
		this.freshCamY = camY;
		this.freshCamZ = camZ;
		if (this.reuseShadow && this.heldShadow) {
			this.scratchShadowMV.set(this.heldShadowMV).translate(
				(float) (camX - this.heldCamX),
				(float) (camY - this.heldCamY),
				(float) (camZ - this.heldCamZ));
		}
		this.scratchShadowMVInv.set(this.scratchShadowMV).invert();
		this.scratchShadowMvp.set(this.shadowProj).mul(this.scratchShadowMV);
		putMat4("shadowModelView", this.scratchShadowMV);
		putMat4("shadowModelViewInverse", this.scratchShadowMVInv);
		putMat4("iris_shModelViewProjection", this.scratchShadowMvp);
		this.scratchMat3.set(this.scratchShadowMV);
		if (Math.abs(this.scratchMat3.determinant()) > 1.0E-8F) {
			this.scratchMat3.invert().transpose();
		}
		putMat3("iris_shNormalMatrix", this.scratchMat3);

		long time = level == null ? 6000L : level.getDefaultClockTime();
		put1("worldTime", (float) Math.floorMod(time, 24000L));
		put1("worldDay", (float) Math.floorDiv(time, 24000L));
		put1("moonPhase", (float) (sky.moonPhase == null ? 0 : sky.moonPhase.index()));
		put1("frameCounter", (float) this.frameCounter);
		put1("frameTime", dt);
		put1("frameTimeCounter", (float) this.timeCounter);
		put1("viewWidth", (float) width);
		put1("viewHeight", (float) height);
		put1("aspectRatio", (float) width / Math.max(1, height));
		float near = 0.05F;
		float far = mc.options.getEffectiveRenderDistance() * 16.0F;
		put1("near", near);
		put1("far", far);

		float rain = level == null ? 0.0F : level.getRainLevel(partial);
		float thunder = level == null ? 0.0F : level.getThunderLevel(partial);
		this.wetness = halflifeStep(this.wetness, rain, rain > this.wetness ? this.wetnessHalflife : this.drynessHalflife, dt);
		put1("rainStrength", rain);
		put1("thunderStrength", thunder);
		put1("wetness", this.wetness);

		FogType fogType = camera.fogType;
		put1("isEyeInWater", fogType == FogType.WATER ? 1.0F : fogType == FogType.LAVA ? 2.0F : fogType == FogType.POWDER_SNOW ? 3.0F : 0.0F);
		float skyLight = 15.0F;
		float blockLight = 0.0F;
		if (level != null) {
			BlockPos pos = camera.blockPos;
			skyLight = level.getBrightness(LightLayer.SKY, pos);
			blockLight = level.getBrightness(LightLayer.BLOCK, pos);
		}
		if (this.eyeSkySmooth < 0.0F) {
			this.eyeSkySmooth = skyLight * 16.0F;
			this.eyeBlockSmooth = blockLight * 16.0F;
		}
		this.eyeSkySmooth = halflifeStep(this.eyeSkySmooth, skyLight * 16.0F, this.eyeBrightnessHalflife, dt);
		this.eyeBlockSmooth = halflifeStep(this.eyeBlockSmooth, blockLight * 16.0F, this.eyeBrightnessHalflife, dt);
		put2("eyeBrightness", blockLight * 16.0F, skyLight * 16.0F);
		put2("eyeBrightnessSmooth", Math.round(this.eyeBlockSmooth), Math.round(this.eyeSkySmooth));

		Vector4f fog = camera.fogData.color;
		put3("fogColor", fog.x, fog.y, fog.z);
		Vector3fc skyColor = sky.skyColor;
		if (skyColor != null && hasSun) {
			put3("skyColor", skyColor.x(), skyColor.y(), skyColor.z());
		} else {
			put3("skyColor", fog.x, fog.y, fog.z);
		}
		put1("fogStart", camera.fogData.renderDistanceStart);
		put1("fogEnd", camera.fogData.renderDistanceEnd);
		put1("fogDensity", 0.0F);
		put1("fogMode", 9729.0F);
		put1("fogShape", 1.0F);

		float nightVision = 0.0F;
		float blindness = 0.0F;
		float darkness = 0.0F;
		float heldLightMain = 0.0F;
		float heldLightOff = 0.0F;
		if (player != null) {
			MobEffectInstance nv = player.getEffect(MobEffects.NIGHT_VISION);
			if (nv != null) {
				nightVision = nv.endsWithin(200) ? 0.7F + 0.3F * (float) Math.sin(nv.getDuration() * Math.PI * 0.2F) : 1.0F;
			}
			MobEffectInstance bl = player.getEffect(MobEffects.BLINDNESS);
			if (bl != null) {
				blindness = bl.getBlendFactor(player, partial);
			}
			MobEffectInstance dk = player.getEffect(MobEffects.DARKNESS);
			if (dk != null) {
				darkness = dk.getBlendFactor(player, partial);
			}
			heldLightMain = heldBlockLight(player.getMainHandItem());
			heldLightOff = heldBlockLight(player.getOffhandItem());
			put1("is_on_ground", player.onGround() ? 1.0F : 0.0F);
			put1("is_sneaking", player.isShiftKeyDown() ? 1.0F : 0.0F);
			put1("is_sprinting", player.isSprinting() ? 1.0F : 0.0F);
			put1("is_burning", player.isOnFire() ? 1.0F : 0.0F);
			put1("is_invisible", player.isInvisible() ? 1.0F : 0.0F);
			put1("is_hurt", player.hurtTime > 0 ? 1.0F : 0.0F);
			put1("is_alive", player.isAlive() ? 1.0F : 0.0F);
			put1("is_in_water", player.isInWater() ? 1.0F : 0.0F);
			put1("currentPlayerHealth", player.getHealth() / Math.max(1.0F, player.getMaxHealth()));
			put1("maxPlayerHealth", player.getMaxHealth());
			put1("currentPlayerHunger", player.getFoodData().getFoodLevel() / 20.0F);
			put1("currentPlayerAir", player.getAirSupply() / (float) Math.max(1, player.getMaxAirSupply()));
			net.minecraft.world.phys.Vec3 eye = player.getEyePosition(partial);
			net.minecraft.world.phys.Vec3 look = player.getViewVector(partial);
			put3("eyePosition", (float) eye.x, (float) eye.y, (float) eye.z);
			put3("relativeEyePosition", (float) (camX - eye.x), (float) (camY - eye.y), (float) (camZ - eye.z));
			put3("playerLookVector", (float) look.x, (float) look.y, (float) look.z);
			put1("isElytraFlying", player.isFallFlying() ? 1.0F : 0.0F);
		}
		put1("nightVision", nightVision);
		put1("blindness", blindness);
		put1("darknessFactor", darkness);
		put1("darknessLightFactor", 0.0F);
		put1("screenBrightness", mc.options.gamma().get().floatValue());
		put1("hideGUI", state.guiRenderState.isHudHidden ? 1.0F : 0.0F);
		put1("heldItemId", -1.0F);
		put1("heldItemId2", -1.0F);
		put1("heldBlockLightValue", heldLightMain);
		put1("heldBlockLightValue2", heldLightOff);
		put1("playerMood", 0.0F);
		put1("endFlashIntensity", sky.skybox == DimensionType.Skybox.END ? sky.endFlashIntensity : 0.0F);
		put3("endFlashPosition", 0.0F, 100.0F, 0.0F);
		if (level != null) {
			put1("bedrockLevel", (float) level.getMinY());
			put1("heightLimit", (float) level.getHeight());
			put1("logicalHeightLimit", (float) level.getHeight());
			put1("hasSkylight", level.dimensionType().hasSkyLight() ? 1.0F : 0.0F);
			put1("hasCeiling", level.dimensionType().hasCeiling() ? 1.0F : 0.0F);
		}
		put1("cloudHeight", state.levelRenderState.cloudHeight);

		// Biome at camera, numbered like Iris's BIOME_* macros (the registry id)
		float biomeId = -1.0F;
		float temperature = 0.8F;
		float precipitation = 0.0F;
		float rainfall = 0.4F;
		if (level != null) {
			try {
				net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> holder = level.getBiome(camera.blockPos);
				net.minecraft.world.level.biome.Biome biome = holder.value();
				biomeId = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).getId(biome);
				temperature = biome.getBaseTemperature();
				precipitation = biome.getPrecipitationAt(camera.blockPos, level.getSeaLevel()).ordinal();
				rainfall = biome.hasPrecipitation() ? 0.5F : 0.0F;
			} catch (RuntimeException e) {
				biomeId = -1.0F;
			}
		}
		put1("biome", biomeId);
		put1("biome_category", -1.0F);
		put1("biome_precipitation", precipitation);
		put1("temperature", temperature);
		put1("rainfall", rainfall);

		// Real block atlas dimensions (fallback 1024x1024, never 0x0 which causes 1/0 = NaN)
		float atlasW = 1024.0F;
		float atlasH = 1024.0F;
		try {
			AbstractTexture atlasTex = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
			if (atlasTex != null) {
				GpuTextureView view = atlasTex.getTextureView();
				int aw = view.getWidth(0);
				int ah = view.getHeight(0);
				if (aw > 0 && ah > 0) {
					atlasW = (float) aw;
					atlasH = (float) ah;
				}
			}
		} catch (RuntimeException ignored) {
			// atlas not initialized yet; keep 1024x1024 fallback
		}
		put2("atlasSize", atlasW, atlasH);

		put4("lightningBoltPosition", 0.0F, 0.0F, 0.0F, 0.0F);
		put1("entityId", 0.0F);
		put1("blockEntityId", 0.0F);
		put1("currentRenderedItemId", 0.0F);

		// Distant Horizons uniforms (dhProjection/Inverse/Previous are aliased above)
		put1("dhNearPlane", near);
		put1("dhFarPlane", far);
		put1("dhRenderDistance", far);

		// centerDepthSmooth from crosshair hit or extended camera raycast up to actual far plane (no GPU readback)
		float depthFar = camera.depthFar > near ? camera.depthFar
			: Math.max(far * 4.0F, mc.options.cloudRange().get() * 16.0F);
		float target = 1.0F;
		HitResult hit = mc.hitResult;
		if ((hit == null || hit.getType() == HitResult.Type.MISS) && level != null && camera.pos != null) {
			// Beyond ~4.5-block interaction reach: cast a ray along the screen-center view axis up to loaded render distance
			this.scratchVec4A.set(0.0F, 0.0F, -1.0F, 0.0F);
			this.scratchModelViewInv.transform(this.scratchVec4A);
			float rx = this.scratchVec4A.x;
			float ry = this.scratchVec4A.y;
			float rz = this.scratchVec4A.z;
			float rlenSq = rx * rx + ry * ry + rz * rz;
			if (rlenSq > 1.0E-8F) {
				float invLen = (float) (1.0 / Math.sqrt(rlenSq));
				double rayDist = Math.min(depthFar, Math.max(far, 128.0F));
				Vec3 end = camera.pos.add(rx * invLen * rayDist, ry * invLen * rayDist, rz * invLen * rayDist);
				ClipContext clip = player != null
					? new ClipContext(camera.pos, end, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, player)
					: new ClipContext(camera.pos, end, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, CollisionContext.empty());
				hit = level.clip(clip);
			}
		}
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			double dx = hit.getLocation().x - camX;
			double dy = hit.getLocation().y - camY;
			double dz = hit.getLocation().z - camZ;
			float dist = Math.min((float) Math.sqrt(dx * dx + dy * dy + dz * dz), depthFar);
			this.scratchVec4A.set(0.0F, 0.0F, -Math.max(dist, near), 1.0F);
			this.scratchProj.transform(this.scratchVec4A);
			target = Math.max(0.0F, Math.min(1.0F, this.scratchVec4A.z / this.scratchVec4A.w * 0.5F + 0.5F));
		}
		this.centerDepth = this.first ? target : halflifeStep(this.centerDepth, target, this.centerDepthHalflife, dt);
		put1("centerDepthSmooth", this.centerDepth);

		put4("iris_FogColor", fog.x, fog.y, fog.z, fog.w);
		put1("iris_FogStart", camera.fogData.renderDistanceStart);
		put1("iris_FogEnd", camera.fogData.renderDistanceEnd);
		put1("iris_FogDensity", 0.0F);
		put1("iris_FogScale", 1.0F / Math.max(0.001F, camera.fogData.renderDistanceEnd - camera.fogData.renderDistanceStart));

		// Evaluate custom uniform.* / variable.* in topological dependency order with scalar splatting
		for (int i = 0, n = this.customs.size(); i < n; i++) {
			CustomEntry c = this.customs.get(i);
			float[] raw = c.expr.eval(this.context);
			int rawLen = raw == null || raw.length == 0 ? 0 : raw.length;
			int len = c.targetLen;
			float[] dst = c.slot.value;
			if (dst.length != len) {
				dst = new float[len];
				c.slot.value = dst;
			}
			boolean diff = c.slot.version == 0;
			for (int k = 0; k < len; k++) {
				float val;
				if (rawLen == 0) {
					val = 0.0F;
				} else if (rawLen == 1) {
					val = raw[0]; // splat scalar into vec2/3/4
				} else {
					val = k < rawLen ? raw[k] : raw[rawLen - 1];
				}
				if (c.convKind == 1) {
					val = (float) (int) val;
				} else if (c.convKind == 2) {
					val = val != 0.0F ? 1.0F : 0.0F;
				}
				if (dst[k] != val) {
					dst[k] = val;
					diff = true;
				}
			}
			if (diff) {
				c.slot.version++;
			}
		}
		this.first = false;
	}

	/** Evaluates a dynamic {@code program.<name>.enabled} expression against current uniforms. */
	public boolean evalEnabled(final PackExpression expr) {
		if (expr == null) {
			return true;
		}
		float[] v = expr.eval(this.context);
		return v != null && v.length > 0 && v[0] != 0.0F;
	}

	/** Updates the active Iris render stage ({@code MC_RENDER_STAGE_*}). */
	public void setRenderStage(final int stage) {
		float f = (float) stage;
		if (this.renderStageSlot.version == 0 || this.renderStageSlot.value[0] != f) {
			this.renderStageSlot.value[0] = f;
			this.renderStageSlot.version++;
		}
	}

	/** Updates {@code iris_AlphaTestRef} and {@code alphaTestRef} (0.0 disables alpha test). */
	public void setAlphaTestRef(final float ref) {
		if (this.alphaTestRefSlot.version == 0 || this.alphaTestRefSlot.value[0] != ref) {
			this.alphaTestRefSlot.value[0] = ref;
			this.alphaTestRefSlot.version++;
		}
	}

	/** Updates {@code entityColor} (hurt/flash overlay color). */
	public void setDynamicEntityColor(final float r, final float g, final float b, final float a) {
		float[] v = this.entityColorSlot.value;
		if (this.entityColorSlot.version == 0 || v[0] != r || v[1] != g || v[2] != b || v[3] != a) {
			v[0] = r;
			v[1] = g;
			v[2] = b;
			v[3] = a;
			this.entityColorSlot.version++;
		}
	}

	/** Updates {@code iris_ColorModulator} and {@code colorModulator}. */
	public void setColorModulator(final float r, final float g, final float b, final float a) {
		float[] v = this.colorModulatorSlot.value;
		if (this.colorModulatorSlot.version == 0 || v[0] != r || v[1] != g || v[2] != b || v[3] != a) {
			v[0] = r;
			v[1] = g;
			v[2] = b;
			v[3] = a;
			this.colorModulatorSlot.version++;
		}
	}

	/**
	 * Iris's {@code BIOME_*} macros: one per registered biome, upper-cased path, valued with the
	 * registry id that the {@code biome} uniform reports ({@code BIOME_PLAINS}, {@code BIOME_CHERRY_GROVE}).
	 * Packs use them in shaders.properties ({@code in(biome, BIOME_DESERT)}) and in #if.
	 */
	public static Map<String, String> biomeMacros(final ClientLevel level) {
		Map<String, String> macros = new HashMap<>();
		if (level == null) {
			return macros;
		}
		try {
			net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> registry =
				level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
			for (net.minecraft.resources.Identifier id : registry.keySet()) {
				net.minecraft.world.level.biome.Biome biome = registry.getValue(id);
				if (biome != null) {
					String name = id.getPath().toUpperCase(Locale.ROOT).replace('/', '_');
					macros.put("BIOME_" + name, Integer.toString(registry.getId(biome)));
				}
			}
		} catch (RuntimeException e) {
			// a missing registry just leaves the macros undefined, as before
		}
		return macros;
	}

	/**
	 * Uploads values for the current program's active uniforms. Unchanged values are skipped via
	 * a single integer version comparison with zero allocations or HashMap lookups after first use.
	 */
	public void apply(final List<UniformEntry> uniforms) {
		for (int i = 0, n = uniforms.size(); i < n; i++) {
			UniformEntry u = uniforms.get(i);
			Slot s = u.slot;
			if (s == null) {
				s = this.slots.get(u.name);
				if (s == null) {
					continue;
				}
				u.slot = s;
			}
			if (u.lastVersion == s.version) {
				continue;
			}
			u.lastVersion = s.version;
			uploadUniform(u.location, u.type, s.value);
		}
	}

	/** Uploads only dynamic per-draw uniforms when the pass's full uniform set is already up-to-date this frame. */
	public void applyPerDraw(final List<UniformEntry> perDrawUniforms) {
		if (!perDrawUniforms.isEmpty()) {
			this.apply(perDrawUniforms);
		}
	}

	private static void uploadUniform(final int loc, final int type, final float[] v) {
		if (v == null || v.length == 0) {
			return;
		}
		switch (type) {
			case ShaderPackRuntime.T_FLOAT:
				GlApi.glUniform1f(loc, v[0]);
				break;
			case ShaderPackRuntime.T_VEC2:
				GlApi.glUniform2f(loc, v[0], at(v, 1));
				break;
			case ShaderPackRuntime.T_VEC3:
				GlApi.glUniform3f(loc, v[0], at(v, 1), at(v, 2));
				break;
			case ShaderPackRuntime.T_VEC4:
				GlApi.glUniform4f(loc, v[0], at(v, 1), at(v, 2), at(v, 3));
				break;
			case ShaderPackRuntime.T_INT:
				GlApi.glUniform1i(loc, (int) v[0]);
				break;
			case ShaderPackRuntime.T_IVEC2:
				GlApi.glUniform2i(loc, (int) v[0], (int) at(v, 1));
				break;
			case ShaderPackRuntime.T_IVEC3:
				GlApi.glUniform3i(loc, (int) v[0], (int) at(v, 1), (int) at(v, 2));
				break;
			case ShaderPackRuntime.T_IVEC4:
				GlApi.glUniform4i(loc, (int) v[0], (int) at(v, 1), (int) at(v, 2), (int) at(v, 3));
				break;
			case ShaderPackRuntime.T_BOOL:
				GlApi.glUniform1i(loc, v[0] != 0.0F ? 1 : 0);
				break;
			case ShaderPackRuntime.T_BVEC2:
				GlApi.glUniform2i(loc, v[0] != 0.0F ? 1 : 0, at(v, 1) != 0.0F ? 1 : 0);
				break;
			case ShaderPackRuntime.T_BVEC3:
				GlApi.glUniform3i(loc, v[0] != 0.0F ? 1 : 0, at(v, 1) != 0.0F ? 1 : 0, at(v, 2) != 0.0F ? 1 : 0);
				break;
			case ShaderPackRuntime.T_BVEC4:
				GlApi.glUniform4i(loc, v[0] != 0.0F ? 1 : 0, at(v, 1) != 0.0F ? 1 : 0, at(v, 2) != 0.0F ? 1 : 0, at(v, 3) != 0.0F ? 1 : 0);
				break;
			case ShaderPackRuntime.T_UINT:
				GlApi.glUniform1ui(loc, (int) v[0]);
				break;
			case ShaderPackRuntime.T_UVEC2:
				GlApi.glUniform2ui(loc, (int) v[0], (int) at(v, 1));
				break;
			case ShaderPackRuntime.T_UVEC3:
				GlApi.glUniform3ui(loc, (int) v[0], (int) at(v, 1), (int) at(v, 2));
				break;
			case ShaderPackRuntime.T_UVEC4:
				GlApi.glUniform4ui(loc, (int) v[0], (int) at(v, 1), (int) at(v, 2), (int) at(v, 3));
				break;
			case ShaderPackRuntime.T_MAT2:
				// GlApi does not expose glUniformMatrix2fv yet; guarded no-op to avoid GL_INVALID_OPERATION
				break;
			case ShaderPackRuntime.T_MAT3:
				if (v.length >= 9) {
					GlApi.glUniformMatrix3fv(loc, false, v);
				}
				break;
			case ShaderPackRuntime.T_MAT4:
				if (v.length >= 16) {
					GlApi.glUniformMatrix4fv(loc, false, v);
				}
				break;
			default:
				break;
		}
	}

	private static float at(final float[] v, final int i) {
		return i < v.length ? v[i] : v[v.length - 1];
	}

	/** Zero-alloc scalar setter for runtime-provided values (iris_* internals). */
	public void set(final String name, final float v0) {
		this.put1(name, v0);
	}

	/** Zero-alloc vec4 setter for runtime-provided values. */
	public void set(final String name, final float v0, final float v1, final float v2, final float v3) {
		this.put4(name, v0, v1, v2, v3);
	}

	/** Sets a runtime-provided value (iris_* internals). */
	public void set(final String name, final float... v) {
		if (v == null || v.length == 0) {
			return;
		}
		Slot s = this.slot(name, v.length);
		float[] dst = s.value;
		boolean diff = s.version == 0;
		for (int i = 0; i < v.length; i++) {
			if (dst[i] != v[i]) {
				dst[i] = v[i];
				diff = true;
			}
		}
		if (diff) {
			s.version++;
		}
	}

	/** Value lookup for debugging and the status line. */
	public float[] value(final String name) {
		Slot s = this.slots.get(name);
		return s == null ? null : s.value;
	}
}
