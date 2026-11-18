// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PackPrograms {
	public static final class Program {
		public final String name;
		public String vertex;
		public String fragment;
		public int[] drawBuffers;
		public Map<Integer, String> outputs = new HashMap<>();
		public final List<String> notes = new ArrayList<>();
		public final List<String> errors = new ArrayList<>();
		public final Map<Integer, Boolean> flips = new HashMap<>();
		public float scale = 1.0F;
		public String blend;

		Program(final String name) {
			this.name = name;
		}
	}

	public final ShaderPack pack;
	public final String folder;
	public final Map<String, String> macros;
	public final Map<String, String> options;
	public final ShaderPack.Directives directives = new ShaderPack.Directives();
	public ShaderPack.Properties properties = new ShaderPack.Properties();
	public Map<String, String> blockProperties = new HashMap<>();
	public final List<Program> programs = new ArrayList<>();
	public final List<String> log = new ArrayList<>();
	public final List<String> unsupported = new ArrayList<>();
	public int sceneTarget;
	public Program terrain;
	public Program water;
	public Program entities;
	public Program shadow;
	public final Map<String, String> optionMacros = new LinkedHashMap<>();

	public PackPrograms(final ShaderPack pack, final String dimensionId, final Map<String, String> options) {
		this.pack = pack;
		this.folder = pack.dimensionFolder(dimensionId);
		this.options = options == null ? new HashMap<>() : options;
		this.macros = standardMacros(dimensionId);
	}

	public static Map<String, String> standardMacros(final String dimensionId) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("MC_VERSION", "260300");
		m.put("MC_GL_VERSION", "330");
		m.put("MC_GLSL_VERSION", "330");
		m.put("IS_IRIS", "");
		m.put("IRIS_VERSION", "10900");
		m.put("MC_OS_UNKNOWN", "");
		m.put("MC_GL_VENDOR_OTHER", "");
		m.put("MC_GL_RENDERER_OTHER", "");
		m.put("MC_RENDER_QUALITY", "1.0");
		m.put("MC_SHADOW_QUALITY", "1.0");
		m.put("MC_HAND_DEPTH", "0.125");
		m.put("MC_MIPMAP_LEVEL", "4");
		m.put("MAX_COLOR_BUFFERS", "16");
		m.put("MC_NORMAL_MAP", "");
		m.put("MC_SPECULAR_MAP", "");
		String[] stages = {"NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID",
			"TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG",
			"HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER", "HAND_TRANSLUCENT"};
		for (int i = 0; i < stages.length; i++) {
			m.put("MC_RENDER_STAGE_" + stages[i], Integer.toString(i));
		}
		return m;
	}

	public void load() {
		Map<String, String> propertyMacros = new LinkedHashMap<>(this.macros);
		for (Map.Entry<String, String> e : this.optionMacros.entrySet()) {
			propertyMacros.put(e.getKey(), e.getValue().isEmpty() ? "1" : e.getValue());
		}
		String props = this.pack.text("/shaders.properties");
		if (props != null) {
			GlslPreprocessor pp = new GlslPreprocessor(this::loadFile, propertyMacros, this.options);
			this.virtualFiles.put("/shaders.properties.pp", props);
			this.properties = ShaderPack.parseProperties(pp.process("/shaders.properties.pp").code.toString());
		}
		String blocks = this.pack.text("/block.properties");
		if (blocks != null) {
			GlslPreprocessor bp = new GlslPreprocessor(this::loadFile, new LinkedHashMap<>(propertyMacros), this.options);
			this.virtualFiles.put("/block.properties.pp", blocks);
			this.blockProperties = ShaderPack.parseProperties(bp.process("/block.properties.pp").code.toString()).asMap();
		}
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
		this.sceneTarget = this.findSceneTarget();
		for (String name : new String[] {"gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"}) {
			if (this.pack.programFile(this.folder, name, ".fsh") != null && this.pack.programFile(this.folder, name, ".vsh") != null) {
				if (this.enabled(name)) {
					this.terrain = this.build(name, true);
					this.log.add("world program for terrain: " + name);
				}
				break;
			}
		}
		if (this.terrain != null && this.pack.programFile(this.folder, "gbuffers_water", ".fsh") != null
			&& this.pack.programFile(this.folder, "gbuffers_water", ".vsh") != null && this.enabled("gbuffers_water")) {
			this.water = this.build("gbuffers_water", true);
			this.log.add("world program for translucent terrain: gbuffers_water");
		}
		if (this.terrain != null && this.pack.programFile(this.folder, "shadow", ".fsh") != null
			&& this.pack.programFile(this.folder, "shadow", ".vsh") != null && this.enabled("shadow")) {
			this.shadow = this.build("shadow", true);
			this.log.add("shadow program: shadow");
		}
		if (this.terrain != null) {
			for (String name : new String[] {"gbuffers_entities", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"}) {
				if (this.pack.programFile(this.folder, name, ".fsh") != null && this.pack.programFile(this.folder, name, ".vsh") != null) {
					if (this.enabled(name)) {
						this.entities = this.build(name, true, true);
						this.log.add("world program for entities: " + name);
					}
					break;
				}
			}
		}
		this.log.add("scene colour goes to colortex" + this.sceneTarget);
		if (this.pack.programFile(this.folder, "final", ".fsh") != null) {
			names.add("final");
		}
		for (String name : names) {
			if (!this.enabled(name)) {
				this.log.add(name + ": disabled by shaders.properties");
				continue;
			}
			Program p = this.build(name);
			if (p != null) {
				this.programs.add(p);
			}
		}
	}

	private final Map<String, String> virtualFiles = new HashMap<>();

	private int findSceneTarget() {
		for (String name : new String[] {"gbuffers_terrain", "gbuffers_textured_lit", "gbuffers_textured", "gbuffers_basic"}) {
			String fsh = this.pack.programFile(this.folder, name, ".fsh");
			if (fsh == null) {
				continue;
			}
			try {
				GlslPreprocessor.Result r = new GlslPreprocessor(this::loadFile, new LinkedHashMap<>(this.macros), this.options).process(fsh);
				int[] draw = ShaderPack.drawBuffers(r);
				return draw != null && draw.length > 0 && draw[0] < ShaderPack.MAX_COLOR_TARGETS ? draw[0] : 0;
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

	private boolean enabled(final String name) {
		String expr = this.properties.get("program." + (this.folder.isEmpty() ? "" : this.folder + "/") + name + ".enabled");
		if (expr == null) {
			expr = this.properties.get("program." + name + ".enabled");
		}
		if (expr == null) {
			return true;
		}
		GlslPreprocessor pp = this.optionScope();
		StringBuilder rewritten = new StringBuilder();
		for (String t : GlslPreprocessor.tokenize(expr)) {
			if (GlslPreprocessor.isIdentStart(t.charAt(0)) && !t.equals("defined") && !t.equals("true") && !t.equals("false")) {
				rewritten.append("defined(").append(t).append(")");
			} else {
				rewritten.append(t);
			}
		}
		try {
			return pp.evaluate(rewritten.toString());
		} catch (RuntimeException e) {
			return true;
		}
	}

	private GlslPreprocessor optionScopeCache;

	private GlslPreprocessor optionScope() {
		if (this.optionScopeCache == null) {
			GlslPreprocessor pp = new GlslPreprocessor(this::loadFile, this.macros, this.options);
			String file = this.pack.programFile(this.folder, "final", ".fsh");
			if (file == null) {
				file = this.pack.programFile(this.folder, "composite", ".fsh");
			}
			if (file != null) {
				pp.process(file);
			}
			this.optionScopeCache = pp;
		}
		return this.optionScopeCache;
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
		String fsh = this.pack.programFile(this.folder, name, ".fsh");
		String vsh = this.pack.programFile(this.folder, name, ".vsh");
		Map<String, String> stageMacros = new LinkedHashMap<>(this.macros);
		GlslPreprocessor.Result frag = new GlslPreprocessor(this::loadFile, stageMacros, this.options).process(fsh);
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
		ShaderPack.collectDirectives(frag, this.directives);
		int[] draw = ShaderPack.drawBuffers(frag);
		String fragCode = frag.code.toString();
		String vertCode = vert.code.toString();
		this.noteUnsupported(fragCode + vertCode);

		boolean flipZ = !name.equals("shadow");
		GlslTranslator.Output v = GlslTranslator.translate(vertCode, true, vert.version, gbuffers, flipZ, entity);
		GlslTranslator.Output f = GlslTranslator.translate(fragCode, false, frag.version, gbuffers, flipZ, entity);
		p.notes.addAll(v.notes);
		p.notes.addAll(f.notes);
		p.vertex = matchVaryings(v.source, f.source);
		p.fragment = f.source;
		p.outputs = f.outputsByLocation;
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
		for (Map.Entry<String, String> e : this.properties.asMap().entrySet()) {
			String k = e.getKey();
			String prefix = "flip." + name + ".";
			if (k.startsWith(prefix)) {
				int idx = ShaderPack.colorTargetIndex(k.substring(prefix.length()));
				if (idx >= 0) {
					p.flips.put(idx, e.getValue().trim().equals("true"));
				}
			}
		}
		String scale = this.properties.get("scale." + name);
		if (scale != null) {
			try {
				p.scale = Float.parseFloat(scale.trim().split("\\s+")[0]);
			} catch (NumberFormatException ignored) {
			}
		}
		p.blend = this.properties.get("blend." + name);
		return p;
	}

	private static final String[] UNSUPPORTED_SAMPLERS = {"shadowtex0", "shadowtex1", "shadowcolor0", "shadowcolor1", "shadowcolor",
		"watershadow", "colortex8", "colortex9", "colortex10", "colortex11", "colortex12", "colortex13", "colortex14", "colortex15"};

	private void noteUnsupported(final String code) {
		for (String s : new String[] {"shadowtex0", "shadowtex1", "shadowcolor0", "shadowcolor1", "shadowcolor", "watershadow"}) {
			if (Pattern.compile("\\b" + s + "\\b").matcher(code).find() && !this.unsupported.contains(s)) {
				this.unsupported.add(s);
			}
		}
	}

	private static final Pattern IN_DECL = Pattern.compile(
		"(?m)(?:^|;|\\n)\\s*((?:flat\\s+|smooth\\s+|centroid\\s+|invariant\\s+|highp\\s+|mediump\\s+|lowp\\s+)*)in\\s+(\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*;");

	static String matchVaryings(final String vertex, final String fragment) {
		Matcher m = IN_DECL.matcher(fragment);
		StringBuilder extra = new StringBuilder();
		while (m.find()) {
			String qual = m.group(1) == null ? "" : m.group(1);
			String type = m.group(2);
			String name = m.group(3);
			if (name.startsWith("gl_")) {
				continue;
			}
			if (!Pattern.compile("\\bout\\s+(?:\\w+\\s+)*\\w+\\s+" + name + "\\b").matcher(vertex).find()) {
				String array = m.group(4) == null ? "" : m.group(4);
				extra.append(qual.contains("flat") ? "flat " : "").append("out ").append(type).append(' ').append(name).append(array).append(";\n");
			}
		}
		if (extra.length() == 0) {
			return vertex;
		}
		int insert = 0;
		String[] lines = vertex.split("\n", -1);
		int offset = 0;
		for (String line : lines) {
			if (line.startsWith("#version") || line.startsWith("precision")) {
				offset += line.length() + 1;
				insert = offset;
			} else {
				break;
			}
		}
		return vertex.substring(0, insert) + extra + vertex.substring(insert);
	}
}
