// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a shader pack's world and post-processing programs (gbuffers_*, shadow*, prepare*,
 * deferred*, composite*, final) into GLSL ES 3.00 sources plus the settings the runtime needs.
 * Pure Java: the game and the desktop harness (scripts/shaderpack/ShaderPackCheck.java) share it.
 */
public final class PackPrograms {
	/** One translated program, before it is compiled. */
	public static final class Program {
		public final String name;
		public String vertex;
		public String fragment;
		/** colortex index per fragment output location (-1 = GL_NONE). */
		public int[] drawBuffers;
		/** Fragment output variable per location. */
		public Map<Integer, String> outputs = new HashMap<>();
		/** Fragment output GLSL base type per location (vec4, vec3, ivec4, uvec4, ...). */
		public Map<Integer, String> outputTypes = new HashMap<>();
		public final List<String> notes = new ArrayList<>();
		public final List<String> errors = new ArrayList<>();
		/** Pass-level flip overrides from shaders.properties (colortex index -> flip). */
		public final Map<Integer, Boolean> flips = new HashMap<>();
		public float scale = 1.0F;
		public float scaleX = 1.0F;
		public float scaleY = 1.0F;
		public String blend;
		/** Per-buffer blend overrides from {@code blend.<prog>.<buf>}. */
		public final Map<Integer, String> bufferBlends = new HashMap<>();
		/** Alpha test override from {@code alphaTest.<prog>}. */
		public String alphaTest;
		/** Dynamic per-frame enable expression when {@code program.<name>.enabled} references runtime uniforms, or null. */
		public PackExpression enabledExpr;

		Program(final String name) {
			this.name = name;
		}
	}

	/** Iris fallback chains for world & shadow programs (program -> immediate fallback). */
	private static final Map<String, String> FALLBACKS = new LinkedHashMap<>();

	static {
		FALLBACKS.put("gbuffers_line", "gbuffers_basic");
		FALLBACKS.put("gbuffers_textured", "gbuffers_basic");
		FALLBACKS.put("gbuffers_textured_lit", "gbuffers_textured");
		FALLBACKS.put("gbuffers_skybasic", "gbuffers_basic");
		FALLBACKS.put("gbuffers_skytextured", "gbuffers_textured");
		FALLBACKS.put("gbuffers_clouds", "gbuffers_textured");
		FALLBACKS.put("gbuffers_terrain", "gbuffers_textured_lit");
		FALLBACKS.put("gbuffers_terrain_solid", "gbuffers_terrain");
		FALLBACKS.put("gbuffers_terrain_cutout", "gbuffers_terrain");
		FALLBACKS.put("gbuffers_damagedblock", "gbuffers_terrain");
		FALLBACKS.put("gbuffers_block", "gbuffers_terrain");
		FALLBACKS.put("gbuffers_beaconbeam", "gbuffers_textured");
		FALLBACKS.put("gbuffers_entities", "gbuffers_textured_lit");
		FALLBACKS.put("gbuffers_entities_translucent", "gbuffers_entities");
		FALLBACKS.put("gbuffers_entities_glowing", "gbuffers_entities");
		FALLBACKS.put("gbuffers_spidereyes", "gbuffers_textured");
		FALLBACKS.put("gbuffers_armor_glint", "gbuffers_textured");
		FALLBACKS.put("gbuffers_hand", "gbuffers_textured_lit");
		FALLBACKS.put("gbuffers_water", "gbuffers_terrain");
		FALLBACKS.put("gbuffers_hand_water", "gbuffers_hand");
		FALLBACKS.put("gbuffers_weather", "gbuffers_textured_lit");
		FALLBACKS.put("gbuffers_particles", "gbuffers_textured_lit");
		FALLBACKS.put("shadow_solid", "shadow");
		FALLBACKS.put("shadow_cutout", "shadow");
		FALLBACKS.put("shadow_entities", "shadow");
		FALLBACKS.put("shadow_block", "shadow");
	}

	/** All additional Iris world/shadow programs to discover and map via {@link #worldPrograms}. */
	private static final String[] EXTRA_WORLD_PROGRAMS = {
		"gbuffers_terrain_solid", "gbuffers_terrain_cutout", "gbuffers_skybasic", "gbuffers_skytextured",
		"gbuffers_clouds", "gbuffers_weather", "gbuffers_particles", "gbuffers_block",
		"gbuffers_entities_translucent", "gbuffers_hand", "gbuffers_hand_water", "gbuffers_spidereyes",
		"gbuffers_armor_glint", "gbuffers_entities_glowing", "gbuffers_beaconbeam", "gbuffers_line",
		"gbuffers_damagedblock", "shadow_solid", "shadow_cutout", "shadow_entities", "shadow_block"
	};

	/** World programs drawn with chunk-offset terrain vertex layout (BLOCK format). */
	private static final Set<String> TERRAIN_LAYOUT_PROGRAMS = new HashSet<>(Arrays.asList(
		"gbuffers_terrain", "gbuffers_terrain_solid", "gbuffers_terrain_cutout",
		"gbuffers_water", "gbuffers_damagedblock", "shadow", "shadow_solid", "shadow_cutout"
	));

	/** Known Iris/OptiFine runtime uniform names for detecting dynamic {@code program.*.enabled} expressions. */
	private static final Set<String> RUNTIME_UNIFORMS = new HashSet<>(Arrays.asList(
		"rainStrength", "thunderStrength", "wetness", "worldTime", "worldDay", "moonPhase",
		"sunAngle", "shadowAngle", "frameCounter", "frameTime", "frameTimeCounter", "isEyeInWater",
		"blindness", "nightVision", "darknessFactor", "darknessLightFactor", "hideGUI",
		"screenBrightness", "eyeAltitude", "cameraPosition", "previousCameraPosition",
		"cameraPositionInt", "cameraPositionFract", "previousCameraPositionInt", "previousCameraPositionFract",
		"sunPosition", "moonPosition", "shadowLightPosition", "upPosition", "viewWidth", "viewHeight",
		"aspectRatio", "near", "far", "fogColor", "skyColor", "fogStart", "fogEnd", "fogDensity",
		"fogMode", "fogShape", "eyeBrightness", "eyeBrightnessSmooth", "centerDepthSmooth",
		"heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2",
		"is_on_ground", "is_sneaking", "is_sprinting", "is_burning", "is_invisible", "is_hurt",
		"is_alive", "is_in_water", "currentPlayerHealth", "maxPlayerHealth", "currentPlayerHunger",
		"currentPlayerAir", "playerMood", "endFlashIntensity", "endFlashPosition",
		"biome", "biome_category", "biome_precipitation", "temperature", "rainfall",
		"isElytraFlying", "eyePosition", "relativeEyePosition", "playerLookVector",
		"entityId", "blockEntityId", "currentRenderedItemId", "renderStage", "cloudHeight",
		"hasSkylight", "hasCeiling", "bedrockLevel", "heightLimit", "logicalHeightLimit",
		"lightningBoltPosition", "entityColor", "atlasSize", "gbufferModelView", "gbufferProjection",
		"shadowModelView", "shadowProjection"
	));

	public final ShaderPack pack;
	public final String folder;
	public final Map<String, String> macros;
	public final Map<String, String> options;
	public final ShaderPack.Directives directives = new ShaderPack.Directives();
	public ShaderPack.Properties properties = new ShaderPack.Properties();
	/** block.properties after preprocessing ({@code block.<id>} -> entries), empty without one. */
	public Map<String, String> blockProperties = new HashMap<>();
	/** prepare*, deferred*, composite*, final, in run order. Disabled and missing ones left out. */
	public final List<Program> programs = new ArrayList<>();
	public final List<String> log = new ArrayList<>();
	/** Names the pack's programs use for things this runtime cannot provide (shadow maps, ...). */
	public final List<String> unsupported = new ArrayList<>();
	/**
	 * The colortex the pack's own world programs write their colour to (location 0 of
	 * gbuffers_terrain, falling back like Iris: textured_lit, textured, basic).
	 */
	public int sceneTarget;
	/** The pack's world program for solid and cutout terrain (gbuffers_terrain or its fallback), or null. */
	public Program terrain;
	/** gbuffers_water for translucent terrain, or null (the runtime then uses {@link #terrain}). */
	public Program water;
	/** The pack's program for vanilla's entity pipelines (gbuffers_entities, falling back like Iris), or null. */
	public Program entities;
	/** The shadow program (shadow.vsh/fsh) for the shadow pass, or null. Built without the reversed-depth flip. */
	public Program shadow;
	/** All resolved Iris world/shadow programs keyed by requested program id (e.g. {@code gbuffers_clouds}). */
	public final Map<String, Program> worldPrograms = new LinkedHashMap<>();
	/**
	 * Every option's current value as a macro (defaults filled in), for shaders.properties.
	 * Set by the caller before {@link #load}; when empty, populated via {@link PackOptions#parse}.
	 */
	public final Map<String, String> optionMacros = new LinkedHashMap<>();
	/** Optional pre-parsed PackOptions to avoid re-scanning option sources. */
	public PackOptions packOptions;
	/** Programs disabled by the active profile ({@code !program.X}). */
	public final Set<String> disabledPrograms = new LinkedHashSet<>();

	private final Map<String, PackExpression> dynamicEnabled = new HashMap<>();
	private final Set<String> customUniformNames = new HashSet<>();
	private final Map<String, String> staticOptionValues = new HashMap<>();
	private final Map<String, Program> builtWorldCache = new HashMap<>();
	private final Map<String, String> virtualFiles = new HashMap<>();

	public PackPrograms(final ShaderPack pack, final String dimensionId, final Map<String, String> options) {
		this.pack = pack;
		this.folder = pack.dimensionFolder(dimensionId);
		this.options = options == null ? new HashMap<>() : options;
		this.macros = standardMacros(dimensionId);
	}

	public Program worldProgram(final String name) {
		return this.worldPrograms.get(name);
	}

	/** Iris's standard macros, with values that describe WebGL2 honestly. */
	public static Map<String, String> standardMacros(final String dimensionId) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("MC_VERSION", "260300");
		m.put("MC_GL_VERSION", "330");
		m.put("MC_GLSL_VERSION", "330");
		m.put("IS_IRIS", "");
		m.put("IRIS_VERSION", "10900");
		m.put("IRIS_TAG_SUPPORT", "2");
		m.put("MC_OS_UNKNOWN", "");
		m.put("MC_GL_VENDOR_OTHER", "");
		m.put("MC_GL_RENDERER_OTHER", "");
		m.put("MC_RENDER_QUALITY", "1.0");
		m.put("MC_SHADOW_QUALITY", "1.0");
		m.put("MC_HAND_DEPTH", "0.125");
		m.put("MC_MIPMAP_LEVEL", "4");
		m.put("MAX_COLOR_BUFFERS", "16");
		// Do not define MC_NORMAL_MAP / MC_SPECULAR_MAP: dummy flat normals & black specular
		// are bound when absent, and defining these macros disables procedural normals/reflections.
		m.put("MC_OLD_LIGHTING", "");
		m.put("MC_OLD_HAND_LIGHT", "");
		// Precipitation macros matching Biome.Precipitation ordinals
		m.put("PPT_NONE", "0");
		m.put("PPT_RAIN", "1");
		m.put("PPT_SNOW", "2");
		// Iris biome category macros
		String[] cats = {"NONE", "TAIGA", "EXTREME_HILLS", "JUNGLE", "MESA", "PLAINS", "SAVANNA", "ICY",
			"THE_END", "BEACH", "FOREST", "OCEAN", "DESERT", "RIVER", "SWAMP", "MUSHROOM", "NETHER",
			"UNDERGROUND", "MOUNTAIN"};
		for (int i = 0; i < cats.length; i++) {
			m.put("CAT_" + cats[i], Integer.toString(i));
		}
		String[] stages = {"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID",
			"TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG",
			"HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT"};
		for (int i = 0; i < stages.length; i++) {
			m.put("MC_RENDER_STAGE_" + stages[i], Integer.toString(i));
		}
		return m;
	}

	/** Loads shaders.properties and every enabled program. */
	public void load() {
		this.loadBegin();
		while (this.loadStep()) {
			// one program per step
		}
	}

	private final List<Runnable> steps = new ArrayList<>();
	private int nextStep;

	public int stepsDone() {
		return this.nextStep;
	}

	public int stepsTotal() {
		return this.steps.size();
	}

	public boolean loadStep() {
		if (this.nextStep >= this.steps.size()) {
			return false;
		}
		this.steps.get(this.nextStep++).run();
		return this.nextStep < this.steps.size();
	}

	/** Reads shaders.properties and block.properties and lists the programs; {@link #loadStep} translates them. */
	public void loadBegin() {
		this.steps.clear();
		this.nextStep = 0;
		this.dynamicEnabled.clear();
		this.customUniformNames.clear();
		this.staticOptionValues.clear();
		this.builtWorldCache.clear();
		this.worldPrograms.clear();

		// Ensure optionMacros & disabledPrograms are populated without double-preprocessing shader files
		if (this.optionMacros.isEmpty()) {
			PackOptions po = this.packOptions != null ? this.packOptions : PackOptions.parse(this.pack);
			this.packOptions = po;
			this.optionMacros.putAll(po.macros(this.options));
			if (this.disabledPrograms.isEmpty()) {
				this.disabledPrograms.addAll(po.disabledPrograms(po.values(this.options)));
			}
			for (Map.Entry<String, String> e : po.values(this.options).entrySet()) {
				this.staticOptionValues.put(e.getKey(), e.getValue());
			}
		} else if (this.packOptions != null) {
			if (this.disabledPrograms.isEmpty()) {
				this.disabledPrograms.addAll(this.packOptions.disabledPrograms(this.packOptions.values(this.options)));
			}
			for (Map.Entry<String, String> e : this.packOptions.values(this.options).entrySet()) {
				this.staticOptionValues.put(e.getKey(), e.getValue());
			}
		}
		for (Map.Entry<String, String> e : this.optionMacros.entrySet()) {
			this.staticOptionValues.putIfAbsent(e.getKey(), e.getValue().isEmpty() ? "true" : e.getValue());
		}

		Map<String, String> propertyMacros = new LinkedHashMap<>(this.macros);
		for (Map.Entry<String, String> e : this.optionMacros.entrySet()) {
			propertyMacros.put(e.getKey(), e.getValue().isEmpty() ? "1" : e.getValue());
		}

		// Preprocess shaders.properties in propertiesMode (keeps \ continuations, ignores comments, no body expansion)
		String props = this.pack.text("/shaders.properties");
		if (props != null) {
			GlslPreprocessor pp = GlslPreprocessor.forProperties(this::loadFile, propertyMacros, this.options);
			this.virtualFiles.put("/shaders.properties", props);
			this.properties = ShaderPack.parseProperties(pp.process("/shaders.properties").code.toString());
		}
		this.parsePackProperties();
		// Adjust MC_OLD_LIGHTING / MC_OLD_HAND_LIGHT according to shaders.properties
		if (Boolean.FALSE.equals(this.directives.oldLighting)) {
			this.macros.remove("MC_OLD_LIGHTING");
		}
		if (Boolean.FALSE.equals(this.directives.oldHandLight)) {
			this.macros.remove("MC_OLD_HAND_LIGHT");
		}
		// Collect custom uniform/variable names so enabled() knows which identifiers are dynamic
		for (String k : this.properties.asMap().keySet()) {
			if (k.startsWith("uniform.") || k.startsWith("variable.")) {
				String[] parts = k.split("\\.", 3);
				if (parts.length >= 3) {
					this.customUniformNames.add(parts[2]);
				}
			}
		}

		// Preprocess block.properties in propertiesMode
		String blocks = this.pack.text("/block.properties");
		if (blocks != null) {
			GlslPreprocessor bp = GlslPreprocessor.forProperties(this::loadFile, new LinkedHashMap<>(propertyMacros), this.options);
			this.virtualFiles.put("/block.properties", blocks);
			this.blockProperties = ShaderPack.parseProperties(bp.process("/block.properties").code.toString()).asMap();
		}

		// Step 1: primary terrain program (also determines sceneTarget without double-preprocessing!)
		this.steps.add(() -> {
			String resolved = this.resolveWorldName("gbuffers_terrain");
			if (resolved != null && this.enabled(resolved)) {
				this.terrain = this.buildCachedWorld(resolved, false);
				this.worldPrograms.put("gbuffers_terrain", this.terrain);
				this.log.add("world program for terrain: " + resolved);
				if (this.terrain != null && this.terrain.drawBuffers != null) {
					this.sceneTarget = firstValidTarget(this.terrain.drawBuffers);
				}
			} else {
				this.sceneTarget = this.findSceneTargetFallback();
			}
		});

		// Step 2: water (gbuffers_water)
		this.steps.add(() -> {
			if (this.terrain != null) {
				if (this.hasProgramFiles("gbuffers_water") && this.enabled("gbuffers_water")) {
					this.water = this.buildCachedWorld("gbuffers_water", false);
					this.worldPrograms.put("gbuffers_water", this.water);
					this.log.add("world program for translucent terrain: gbuffers_water");
				} else {
					this.worldPrograms.put("gbuffers_water", this.terrain);
				}
			}
		});

		// Step 3: shadow pass
		this.steps.add(() -> {
			if (this.terrain != null && this.hasProgramFiles("shadow") && this.enabled("shadow")) {
				this.shadow = this.buildCachedWorld("shadow", false);
				this.worldPrograms.put("shadow", this.shadow);
				this.log.add("shadow program: shadow");
			}
		});

		// Step 4: entities
		this.steps.add(() -> {
			if (this.terrain != null) {
				String resolved = this.resolveWorldName("gbuffers_entities");
				if (resolved != null && this.enabled(resolved)) {
					this.entities = this.buildCachedWorld(resolved, true);
					this.worldPrograms.put("gbuffers_entities", this.entities);
					this.log.add("world program for entities: " + resolved);
				}
			}
			this.log.add("scene colour goes to colortex" + this.sceneTarget);
		});

		// Additional Iris world & shadow programs: queue build steps ONLY for distinct present programs
		List<String> extraPresent = new ArrayList<>();
		for (String req : EXTRA_WORLD_PROGRAMS) {
			if (this.hasProgramFiles(req)) {
				extraPresent.add(req);
			}
		}
		for (String req : extraPresent) {
			this.steps.add(() -> {
				if (this.terrain == null) {
					return;
				}
				if (!this.enabled(req)) {
					this.log.add(req + ": disabled by shaders.properties");
					return;
				}
				boolean entityLayout = !TERRAIN_LAYOUT_PROGRAMS.contains(req);
				Program p = this.buildCachedWorld(req, entityLayout);
				if (p != null) {
					this.worldPrograms.put(req, p);
				}
			});
		}
		// Resolve fallback mappings for all remaining EXTRA_WORLD_PROGRAMS (fast O(1) map lookups)
		this.steps.add(this::populateWorldFallbacks);

		// Post-processing programs: prepare*, deferred*, composite*, final
		List<String> names = new ArrayList<>();
		for (String group : new String[] {"prepare", "deferred", "composite"}) {
			if (this.pack.programFile(this.folder, group, ".fsh") != null) {
				names.add(group);
			}
			for (int i = 1; i < 100; i++) {
				if (this.pack.programFile(this.folder, group + i, ".fsh") != null) {
					names.add(group + i);
				}
			}
		}
		if (this.pack.programFile(this.folder, "final", ".fsh") != null) {
			names.add("final");
		}
		for (String name : names) {
			this.steps.add(() -> {
				if (!this.enabled(name)) {
					this.log.add(name + ": disabled by shaders.properties");
					return;
				}
				Program p = this.build(name);
				if (p != null) {
					this.programs.add(p);
				}
			});
		}
		// Final step: re-apply shaders.properties buffer overrides so they win over shader consts
		this.steps.add(this::applyPropertyBufferOverrides);
	}

	private boolean hasProgramFiles(final String name) {
		return this.pack.programFile(this.folder, name, ".fsh") != null
			&& this.pack.programFile(this.folder, name, ".vsh") != null;
	}

	private String resolveWorldName(final String requested) {
		String cur = requested;
		Set<String> seen = new HashSet<>();
		while (cur != null && seen.add(cur)) {
			if (this.hasProgramFiles(cur)) {
				return cur;
			}
			cur = FALLBACKS.get(cur);
		}
		return null;
	}

	private Program buildCachedWorld(final String actualName, final boolean entityLayout) {
		String key = actualName + "|" + (entityLayout ? "E" : "T");
		Program existing = this.builtWorldCache.get(key);
		if (existing != null) {
			return existing;
		}
		Program p = this.build(actualName, true, entityLayout);
		this.builtWorldCache.put(key, p);
		return p;
	}

	private void populateWorldFallbacks() {
		if (this.terrain == null) {
			return;
		}
		for (String req : EXTRA_WORLD_PROGRAMS) {
			if (this.worldPrograms.containsKey(req)) {
				continue;
			}
			String resolved = this.resolveWorldName(req);
			if (resolved == null || !this.enabled(req) || !this.enabled(resolved)) {
				continue;
			}
			boolean entityLayout = !TERRAIN_LAYOUT_PROGRAMS.contains(req);
			String key = resolved + "|" + (entityLayout ? "E" : "T");
			Program cached = this.builtWorldCache.get(key);
			if (cached == null) {
				// Avoid building duplicate variants unless this resolved program was not yet built in this layout
				cached = this.buildCachedWorld(resolved, entityLayout);
			}
			if (cached != null) {
				this.worldPrograms.put(req, cached);
			}
		}
	}

	private static int firstValidTarget(final int[] draw) {
		if (draw == null) {
			return 0;
		}
		for (int id : draw) {
			if (id >= 0 && id < ShaderPack.MAX_COLOR_TARGETS) {
				return id;
			}
		}
		return 0;
	}

	/** Only called if gbuffers_terrain was disabled or had no .vsh (avoids double-preprocessing). */
	private int findSceneTargetFallback() {
		for (String name : new String[] {"gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"}) {
			String fsh = this.pack.programFile(this.folder, name, ".fsh");
			if (fsh == null) {
				continue;
			}
			try {
				GlslPreprocessor.Result r = new GlslPreprocessor(this::loadFile, new LinkedHashMap<>(this.macros), this.options).process(fsh);
				return firstValidTarget(ShaderPack.drawBuffers(r));
			} catch (RuntimeException e) {
				return 0;
			}
		}
		return 0;
	}

	private String loadFile(final String path) {
		String v = this.virtualFiles.get(path);
		return v != null ? v : this.pack.text(path);
	}

	// ---- shaders.properties parsing ----

	private String dimProperty(final String prefix, final String suffix) {
		if (!this.folder.isEmpty()) {
			String v = this.properties.get(prefix + this.folder + "/" + suffix);
			if (v != null) {
				return v;
			}
		}
		return this.properties.get(prefix + suffix);
	}

	private void parsePackProperties() {
		this.applyPropertyBufferOverrides();
		Map<String, String> map = this.properties.asMap();
		for (Map.Entry<String, String> e : map.entrySet()) {
			String k = e.getKey();
			String v = e.getValue().trim();
			switch (k) {
				case "separateAo":
					this.directives.separateAo = v.equals("true");
					break;
				case "oldLighting":
					if (v.equals("true") || v.equals("false")) {
						this.directives.oldLighting = v.equals("true");
					}
					break;
				case "oldHandLight":
					if (v.equals("true") || v.equals("false")) {
						this.directives.oldHandLight = v.equals("true");
					}
					break;
				case "shadowFrustumCulling":
					this.directives.shadowFrustumCulling = v.equals("true");
					break;
				case "particles.before.deferred":
					this.directives.particlesBeforeDeferred = v.equals("true");
					break;
				case "separateHardwareSamplers":
					this.directives.separateHardwareSamplers = v.equals("true");
					break;
				case "shadowHardwareFiltering":
					this.directives.shadowHardwareFiltering[0] = v.equals("true");
					this.directives.shadowHardwareFiltering[1] = v.equals("true");
					break;
				case "shadowHardwareFiltering0":
					this.directives.shadowHardwareFiltering[0] = v.equals("true");
					break;
				case "shadowHardwareFiltering1":
					this.directives.shadowHardwareFiltering[1] = v.equals("true");
					break;
				case "shadowtexNearest":
				case "shadowtex0Nearest":
					this.directives.shadowTexNearest[0] = v.equals("true");
					break;
				case "shadowtex1Nearest":
					this.directives.shadowTexNearest[1] = v.equals("true");
					break;
				case "shadowtexMipmap":
				case "shadowtex0Mipmap":
					this.directives.shadowTexMipmap[0] = v.equals("true");
					break;
				case "shadowtex1Mipmap":
					this.directives.shadowTexMipmap[1] = v.equals("true");
					break;
				default:
					break;
			}
			if (k.startsWith("size.buffer.")) {
				int idx = ShaderPack.colorTargetIndex(k.substring("size.buffer.".length()));
				if (idx >= 0) {
					ShaderPack.BufferSize bs = parseBufferSize(v);
					if (bs != null) {
						this.directives.bufferSizes[idx] = bs;
					}
				}
			}
		}
	}

	private void applyPropertyBufferOverrides() {
		for (Map.Entry<String, String> e : this.properties.asMap().entrySet()) {
			String k = e.getKey();
			String v = e.getValue().trim();
			int dot = k.indexOf('.');
			if (dot <= 0) {
				continue;
			}
			String head = k.substring(0, dot);
			String prop = k.substring(dot + 1);
			int idx = ShaderPack.colorTargetIndex(head);
			if (idx >= 0) {
				switch (prop) {
					case "format":
						this.directives.formats[idx] = v.toUpperCase(Locale.ROOT);
						break;
					case "clear":
						this.directives.clear[idx] = v.equals("true");
						break;
					case "clearColor":
						this.directives.clearColor[idx] = ShaderPack.parseVec4(v, this.directives.consts);
						break;
					case "mipmap":
						this.directives.mipmap[idx] = v.equals("true");
						break;
					case "nearest":
						this.directives.nearest[idx] = v.equals("true");
						break;
					default:
						break;
				}
				continue;
			}
			int sidx = ShaderPack.shadowColorTargetIndex(head);
			if (sidx >= 0) {
				switch (prop) {
					case "format":
						this.directives.shadowColorFormats[sidx] = v.toUpperCase(Locale.ROOT);
						break;
					case "clear":
						this.directives.shadowColorClear[sidx] = v.equals("true");
						break;
					case "clearColor":
						this.directives.shadowColorClearColor[sidx] = ShaderPack.parseVec4(v, this.directives.consts);
						break;
					case "mipmap":
						this.directives.shadowColorMipmap[sidx] = v.equals("true");
						break;
					case "nearest":
						this.directives.shadowColorNearest[sidx] = v.equals("true");
						break;
					default:
						break;
				}
			}
		}
	}

	private static ShaderPack.BufferSize parseBufferSize(final String v) {
		String[] parts = v.trim().split("\\s+");
		if (parts.length == 0 || parts[0].isEmpty()) {
			return null;
		}
		try {
			String p0 = parts[0];
			String p1 = parts.length > 1 ? parts[1] : p0;
			boolean relative = p0.indexOf('.') >= 0 || p1.indexOf('.') >= 0;
			float w = Float.parseFloat(p0);
			float h = Float.parseFloat(p1);
			if (w <= 0.0F || h <= 0.0F) {
				return null;
			}
			return new ShaderPack.BufferSize(relative, w, h);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---- program.<name>.enabled ----

	/**
	 * Evaluates {@code !program.<name>} (profile disable) and {@code program.<name>.enabled}
	 * (optionally with dimension folder) using real option values (not wrapping value options in
	 * {@code defined(...)}). Expressions referencing runtime uniforms are parsed into
	 * {@link Program#enabledExpr} for per-frame evaluation.
	 */
	private boolean enabled(final String name) {
		if (this.disabledPrograms.contains(name)) {
			return false;
		}
		String expr = this.dimProperty("program.", name + ".enabled");
		if (expr == null) {
			return true;
		}
		String trimmed = expr.trim();
		if (trimmed.isEmpty()) {
			return true;
		}
		// Resolve any `defined(NAME)` / `defined NAME` statically first
		String resolved = resolveStaticDefined(trimmed);
		try {
			PackExpression parsed = PackExpression.parse(resolved);
			boolean dynamic = false;
			for (String var : parsed.referencedVars()) {
				if (RUNTIME_UNIFORMS.contains(var) || this.customUniformNames.contains(var)) {
					dynamic = true;
					break;
				}
			}
			if (dynamic) {
				// Substitute static option/macro values into the expression so runtime scope only needs uniforms
				String substituted = this.substituteStaticVars(resolved);
				this.dynamicEnabled.put(name, PackExpression.parse(substituted));
				return true;
			}
			PackExpression.Context ctx = new PackExpression.Context();
			ctx.scope = this::staticValueFor;
			return parsed.eval(ctx)[0] != 0.0F;
		} catch (RuntimeException e) {
			return true;
		}
	}

	private String resolveStaticDefined(final String expr) {
		if (!expr.contains("defined")) {
			return expr;
		}
		List<String> tokens = GlslPreprocessor.tokenize(expr);
		StringBuilder out = new StringBuilder(expr.length());
		for (int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);
			if (!t.equals("defined")) {
				out.append(t);
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
			boolean isDef = this.optionMacros.containsKey(name) || this.macros.containsKey(name);
			out.append(isDef ? " 1 " : " 0 ");
			i = j;
		}
		return out.toString();
	}

	private String substituteStaticVars(final String expr) {
		List<String> tokens = GlslPreprocessor.tokenize(expr);
		StringBuilder out = new StringBuilder(expr.length());
		for (int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);
			if (!t.isEmpty() && GlslPreprocessor.isIdentStart(t.charAt(0))
				&& !RUNTIME_UNIFORMS.contains(t) && !this.customUniformNames.contains(t)
				&& !t.equals("true") && !t.equals("false") && !t.equals("pi")) {
				// Do not replace function call names
				int j = i + 1;
				while (j < tokens.size() && tokens.get(j).trim().isEmpty()) {
					j++;
				}
				boolean isCall = j < tokens.size() && tokens.get(j).equals("(");
				if (!isCall && (this.staticOptionValues.containsKey(t) || this.macros.containsKey(t))) {
					float[] v = this.staticValueFor(t);
					out.append(v != null ? Float.toString(v[0]) : "0");
					continue;
				}
			}
			out.append(t);
		}
		return out.toString();
	}

	private float[] staticValueFor(final String name) {
		String opt = this.staticOptionValues.get(name);
		if (opt != null) {
			return parseScalarOption(opt);
		}
		String macro = this.optionMacros.get(name);
		if (macro != null) {
			return macro.isEmpty() ? new float[] {1.0F} : parseScalarOption(macro);
		}
		String std = this.macros.get(name);
		if (std != null) {
			return std.isEmpty() ? new float[] {1.0F} : parseScalarOption(std);
		}
		return new float[] {0.0F};
	}

	private static float[] parseScalarOption(final String raw) {
		String t = raw.trim();
		if (t.equals("true") || t.isEmpty()) {
			return new float[] {1.0F};
		}
		if (t.equals("false")) {
			return new float[] {0.0F};
		}
		while (t.length() >= 2 && t.charAt(0) == '(' && t.charAt(t.length() - 1) == ')') {
			t = t.substring(1, t.length() - 1).trim();
		}
		try {
			return new float[] {(float) GlslPreprocessor.parseNumber(t)};
		} catch (RuntimeException e) {
			return new float[] {1.0F};
		}
	}

	private static final String DEFAULT_VERTEX = "#version 120\n"
		+ "varying vec2 texcoord;\nvoid main() { gl_Position = ftransform(); texcoord = gl_MultiTexCoord0.xy; }\n";

	Program build(final String name) {
		return this.build(name, false);
	}

	Program build(final String name, final boolean gbuffers) {
		return this.build(name, gbuffers, false);
	}

	Program build(final String name, final boolean gbuffers, final boolean entity) {
		Program p = new Program(name);
		p.enabledExpr = this.dynamicEnabled.get(name);
		String fsh = this.pack.programFile(this.folder, name, ".fsh");
		String vsh = this.pack.programFile(this.folder, name, ".vsh");
		GlslPreprocessor.Result frag = new GlslPreprocessor(this::loadFile, new LinkedHashMap<>(this.macros), this.options).process(fsh);
		GlslPreprocessor.Result vert;
		if (vsh != null) {
			vert = new GlslPreprocessor(this::loadFile, new LinkedHashMap<>(this.macros), this.options).process(vsh);
		} else {
			this.virtualFiles.put("/__default.vsh", DEFAULT_VERTEX);
			vert = new GlslPreprocessor(this::loadFile, new LinkedHashMap<>(this.macros), this.options).process("/__default.vsh");
			p.notes.add("no vertex shader; using a generated one");
		}
		for (GlslPreprocessor.Result r : new GlslPreprocessor.Result[] {vert, frag}) {
			for (String e : r.errors) {
				if (e.startsWith("#error")) {
					p.errors.add(e);
				} else {
					p.notes.add(e);
				}
			}
		}
		if (!p.errors.isEmpty()) {
			return p;
		}
		// Scan BOTH .vsh and .fsh for const directives
		ShaderPack.collectDirectives(vert, this.directives);
		ShaderPack.collectDirectives(frag, this.directives);
		int[] draw = ShaderPack.drawBuffers(frag);
		String fragCode = frag.code.toString();
		String vertCode = vert.code.toString();
		this.noteUnsupported(fragCode + vertCode);

		// Shadow programs use standard depth (packs compare against it); world programs use 26.3 reversed depth
		boolean flipZ = !name.startsWith("shadow");
		GlslTranslator.Output v = GlslTranslator.translate(vertCode, true, vert.version, gbuffers, flipZ, entity);
		GlslTranslator.Output f = GlslTranslator.translate(fragCode, false, frag.version, gbuffers, flipZ, entity);
		p.notes.addAll(v.notes);
		p.notes.addAll(f.notes);
		p.vertex = matchVaryings(v.source, f.source);
		p.fragment = f.source;
		p.outputs = f.outputsByLocation;
		p.outputTypes = f.outputTypesByLocation;
		if (draw == null) {
			draw = new int[] {0};
		}
		int outputs = Math.max(f.fragOutputs, 1);
		if (draw.length > outputs) {
			int[] trimmed = new int[outputs];
			System.arraycopy(draw, 0, trimmed, 0, outputs);
			draw = trimmed;
		}
		p.drawBuffers = draw;
		this.applyPassProperties(p, name);
		return p;
	}

	private void applyPassProperties(final Program p, final String name) {
		String dimPrefix = this.folder.isEmpty() ? null : this.folder + "/";
		// flip.<prog>.<buf> (base first, then dimension-prefixed override)
		this.scanFlipPrefix(p, "flip." + name + ".");
		if (dimPrefix != null) {
			this.scanFlipPrefix(p, "flip." + dimPrefix + name + ".");
		}
		// scale.<prog> = sx [sy]
		String scale = this.dimProperty("scale.", name);
		if (scale != null) {
			String[] parts = scale.trim().split("\\s+");
			try {
				float sx = Float.parseFloat(parts[0]);
				float sy = parts.length > 1 ? Float.parseFloat(parts[1]) : sx;
				p.scale = sx;
				p.scaleX = sx;
				p.scaleY = sy;
			} catch (NumberFormatException ignored) {
				// keep 1
			}
		}
		// blend.<prog> and blend.<prog>.<buf>
		p.blend = this.dimProperty("blend.", name);
		this.scanBlendPrefix(p, "blend." + name + ".");
		if (dimPrefix != null) {
			this.scanBlendPrefix(p, "blend." + dimPrefix + name + ".");
		}
		// alphaTest.<prog>
		p.alphaTest = this.dimProperty("alphaTest.", name);
	}

	private void scanFlipPrefix(final Program p, final String prefix) {
		for (Map.Entry<String, String> e : this.properties.asMap().entrySet()) {
			if (e.getKey().startsWith(prefix)) {
				int idx = ShaderPack.colorTargetIndex(e.getKey().substring(prefix.length()));
				if (idx >= 0) {
					p.flips.put(idx, e.getValue().trim().equals("true"));
				}
			}
		}
	}

	private void scanBlendPrefix(final Program p, final String prefix) {
		for (Map.Entry<String, String> e : this.properties.asMap().entrySet()) {
			if (e.getKey().startsWith(prefix)) {
				int idx = ShaderPack.colorTargetIndex(e.getKey().substring(prefix.length()));
				if (idx >= 0) {
					p.bufferBlends.put(idx, e.getValue().trim());
				}
			}
		}
	}

	private static final Pattern[] UNSUPPORTED_PATTERNS = {
		Pattern.compile("\\bshadowtex0\\b"), Pattern.compile("\\bshadowtex1\\b"),
		Pattern.compile("\\bshadowcolor0\\b"), Pattern.compile("\\bshadowcolor1\\b"),
		Pattern.compile("\\bshadowcolor\\b"), Pattern.compile("\\bwatershadow\\b")
	};
	private static final String[] UNSUPPORTED_NAMES = {
		"shadowtex0", "shadowtex1", "shadowcolor0", "shadowcolor1", "shadowcolor", "watershadow"
	};

	private void noteUnsupported(final String code) {
		for (int i = 0; i < UNSUPPORTED_NAMES.length; i++) {
			String s = UNSUPPORTED_NAMES[i];
			if (code.contains(s) && UNSUPPORTED_PATTERNS[i].matcher(code).find() && !this.unsupported.contains(s)) {
				this.unsupported.add(s);
			}
		}
	}

	private static final Pattern VARYING_DECL = Pattern.compile(
		"^(?:layout\\s*\\([^)]*\\)\\s*)?"
		+ "((?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\\s+)*)"
		+ "(in|out)\\s+"
		+ "(?:(?:highp|mediump|lowp)\\s+)?"
		+ "(\\w+)\\s+"
		+ "(.+);$");

	private static final Pattern DECL_NAME_AND_ARRAY = Pattern.compile("^(\\w+)\\s*(\\[[^\\]]*\\])?");

	private static final class Varying {
		final String qualifiers;
		final String type;
		final String name;
		final String array;

		Varying(final String qualifiers, final String type, final String name, final String array) {
			this.qualifiers = qualifiers;
			this.type = type;
			this.name = name;
			this.array = array;
		}
	}

	/**
	 * ES rejects a fragment input the vertex shader does not write, and requires {@code flat}
	 * qualifiers to match between stages. Handles comma-separated declarations, arrays, qualifier
	 * matching, and inserts synthesized outputs after global {@code const}/{@code struct}
	 * declarations (before the first function definition).
	 */
	static String matchVaryings(String vertex, final String fragment) {
		Map<String, Varying> fragIns = collectStageVaryings(fragment, "in");
		if (fragIns.isEmpty()) {
			return vertex;
		}
		Map<String, Varying> vertOuts = collectStageVaryings(vertex, "out");
		StringBuilder extra = new StringBuilder();
		Set<String> needFlatOnVertex = new LinkedHashSet<>();

		for (Varying f : fragIns.values()) {
			if (f.name.startsWith("gl_")) {
				continue;
			}
			Varying v = vertOuts.get(f.name);
			if (v == null) {
				if (f.qualifiers.contains("flat")) {
					extra.append("flat ");
				}
				if (f.qualifiers.contains("centroid")) {
					extra.append("centroid ");
				}
				extra.append("out ").append(f.type).append(' ').append(f.name).append(f.array).append(";\n");
			} else {
				boolean fFlat = f.qualifiers.contains("flat");
				boolean vFlat = v.qualifiers.contains("flat");
				if (fFlat && !vFlat) {
					needFlatOnVertex.add(f.name);
				}
			}
		}

		if (!needFlatOnVertex.isEmpty()) {
			vertex = addFlatToVertexOuts(vertex, needFlatOnVertex);
		}
		if (extra.length() == 0) {
			return vertex;
		}
		int insert = findSafeInsertOffset(vertex);
		return vertex.substring(0, insert) + extra + vertex.substring(insert);
	}

	private static Map<String, Varying> collectStageVaryings(final String src, final String direction) {
		Map<String, Varying> out = new LinkedHashMap<>();
		for (String stmt : topLevelStatements(src)) {
			String trimmed = stmt.trim();
			if (!trimmed.contains(direction + " ") && !trimmed.contains(direction + "\t")) {
				continue;
			}
			Matcher m = VARYING_DECL.matcher(trimmed);
			if (!m.matches() || !m.group(2).equals(direction)) {
				continue;
			}
			String quals = m.group(1) == null ? "" : m.group(1);
			String type = m.group(3);
			String declList = m.group(4);
			for (String item : GlslTranslator.splitTopLevel(declList, ',')) {
				String t = item.trim();
				int eq = t.indexOf('=');
				if (eq >= 0) {
					t = t.substring(0, eq).trim();
				}
				Matcher nm = DECL_NAME_AND_ARRAY.matcher(t);
				if (nm.find()) {
					String name = nm.group(1);
					String arr = nm.group(2) == null ? "" : nm.group(2);
					out.put(name, new Varying(quals, type, name, arr));
				}
			}
		}
		return out;
	}

	private static List<String> topLevelStatements(final String src) {
		List<String> out = new ArrayList<>();
		int depth = 0;
		int parens = 0;
		int start = 0;
		int n = src.length();
		for (int i = 0; i < n; i++) {
			char c = src.charAt(i);
			if (c == '#') {
				if (depth == 0 && parens == 0) {
					int nl = src.indexOf('\n', i);
					start = nl < 0 ? n : nl + 1;
					i = start - 1;
				}
				continue;
			}
			if (c == '(') {
				parens++;
			} else if (c == ')') {
				parens--;
			} else if (c == '{') {
				if (depth == 0) {
					start = i + 1;
				}
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					start = i + 1;
				}
			} else if (c == ';' && depth == 0 && parens == 0) {
				out.add(src.substring(start, i + 1));
				start = i + 1;
			}
		}
		return out;
	}

	private static String addFlatToVertexOuts(final String vertex, final Set<String> names) {
		StringBuilder out = new StringBuilder(vertex.length() + names.size() * 6);
		int depth = 0;
		int parens = 0;
		int stmtStart = 0;
		int n = vertex.length();
		for (int i = 0; i < n; i++) {
			char c = vertex.charAt(i);
			if (c == '(') {
				parens++;
			} else if (c == ')') {
				parens--;
			} else if (c == '{') {
				if (depth == 0) {
					out.append(vertex, stmtStart, i + 1);
					stmtStart = i + 1;
				}
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					out.append(vertex, stmtStart, i + 1);
					stmtStart = i + 1;
				}
			} else if (c == ';' && depth == 0 && parens == 0) {
				String stmt = vertex.substring(stmtStart, i + 1);
				String trimmed = stmt.trim();
				Matcher m = VARYING_DECL.matcher(trimmed);
				if (m.matches() && m.group(2).equals("out") && !m.group(1).contains("flat")) {
					boolean hit = false;
					for (String item : GlslTranslator.splitTopLevel(m.group(4), ',')) {
						Matcher nm = DECL_NAME_AND_ARRAY.matcher(item.trim());
						if (nm.find() && names.contains(nm.group(1))) {
							hit = true;
							break;
						}
					}
					if (hit) {
						int lead = stmt.indexOf(trimmed);
						out.append(stmt, 0, Math.max(0, lead)).append("flat ").append(trimmed);
						stmtStart = i + 1;
						continue;
					}
				}
				out.append(stmt);
				stmtStart = i + 1;
			}
		}
		out.append(vertex.substring(stmtStart));
		return out.toString();
	}

	/**
	 * Finds a safe insertion point after #version/precision and global const/struct declarations,
	 * right before the first function definition (so const array sizes & struct types are in scope).
	 */
	private static int findSafeInsertOffset(final String vertex) {
		int afterHeader = 0;
		int pos = 0;
		int n = vertex.length();
		while (pos < n) {
			int nl = vertex.indexOf('\n', pos);
			int lineEnd = nl < 0 ? n : nl + 1;
			String t = vertex.substring(pos, lineEnd).trim();
			if (t.isEmpty() || t.startsWith("#version") || t.startsWith("precision")) {
				afterHeader = lineEnd;
				pos = lineEnd;
			} else {
				break;
			}
		}
		// Walk top-level items until the first function body `{` (preceded by `)`)
		int depth = 0;
		int parens = 0;
		int stmtStart = afterHeader;
		for (int i = afterHeader; i < n; i++) {
			char c = vertex.charAt(i);
			if (c == '(') {
				parens++;
			} else if (c == ')') {
				parens--;
			} else if (c == '{') {
				if (depth == 0) {
					String head = vertex.substring(stmtStart, i).trim();
					if (head.endsWith(")")) {
						// First function definition starts at stmtStart!
						return stmtStart;
					}
				}
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					stmtStart = i + 1;
				}
			} else if (c == ';' && depth == 0 && parens == 0) {
				stmtStart = i + 1;
			}
		}
		return afterHeader;
	}
}
