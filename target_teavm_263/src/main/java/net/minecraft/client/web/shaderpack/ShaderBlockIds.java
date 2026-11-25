// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Block ids and render layer overrides from a pack's {@code block.properties} ({@code mc_Entity.x})
 * and face normals ({@code gl_Normal}) for terrain, which 26.3's 28-byte BLOCK vertex format has
 * no room for.
 *
 * <p>While a pack's world program is active, the section mesher (SectionCompiler twin) sets
 * {@link #current} per block and the BufferBuilder twin ORs it, plus the face direction, into the
 * spare high byte of each lightmap coordinate (vanilla's are 0..240):
 * <pre>
 *   UV2.x bits 8..10  face + 1: 1 down, 2 up, 3 north, 4 south, 5 west, 6 east, 0 unknown (untagged)
 *   UV2.x bits 11..15 table index, low 5 bits
 *   UV2.y bits 8..15  table index, high 8 bits      (index 0 = no id, mc_Entity.x = -1)
 * </pre>
 * The world program's vertex shader decodes them (GlslTranslator) and reads the pack's id from
 * {@link #table} (a texture). Sections are rebuilt when this switches on or off, since vanilla's
 * terrain shader must never see the extra bits.
 */
public final class ShaderBlockIds {
	public static final int LAYER_DEFAULT = 0;
	public static final int LAYER_SOLID = 1;
	public static final int LAYER_CUTOUT = 2;
	public static final int LAYER_CUTOUT_MIPPED = 3;
	public static final int LAYER_TRANSLUCENT = 4;

	/** Tags are written into new section meshes. */
	public static volatile boolean active;
	/** True inside SectionCompiler.compile while {@link #active}. */
	public static boolean compiling;
	/** Light bits of the block being meshed (its table index). */
	public static int current;
	/** Table index per block state id (Block.getId); 0 = not listed. */
	private static int[] byState = new int[0];
	/** Optional render layer override per block state id (LAYER_DEFAULT..LAYER_TRANSLUCENT). */
	private static byte[] layerByState = new byte[0];
	/** Pack id per table index (index 0 unused). */
	public static float[] table = new float[] {-1.0F};
	public static int mapped;
	public static int layerOverrides;

	private ShaderBlockIds() {
	}

	public static int bitsFor(final BlockState state) {
		int id = Block.getId(state);
		int idx = id >= 0 && id < byState.length ? byState[id] : 0;
		return (idx & 31) << 11 | (idx >> 5 & 255) << 24;
	}

	/** Render layer override from {@code layer.<solid|cutout|cutout_mipped|translucent>}, or {@link #LAYER_DEFAULT}. */
	public static int layerFor(final BlockState state) {
		int id = Block.getId(state);
		return id >= 0 && id < layerByState.length ? layerByState[id] : LAYER_DEFAULT;
	}

	/** Face direction bits from a quad normal (dominant axis). */
	public static int normalBits(final float nx, final float ny, final float nz) {
		float ax = Math.abs(nx);
		float ay = Math.abs(ny);
		float az = Math.abs(nz);
		int face;
		if (ax < 1.0E-4F && ay < 1.0E-4F && az < 1.0E-4F) {
			face = -1;
		} else if (ay >= ax && ay >= az) {
			face = ny < 0.0F ? 0 : 1;
		} else if (az >= ax) {
			face = nz < 0.0F ? 2 : 3;
		} else {
			face = nx < 0.0F ? 4 : 5;
		}
		return face + 1 << 8;
	}

	/**
	 * Builds the state table and render layer overrides from {@code block.properties} entries
	 * ({@code block.<id> = <entries>} including negative IDs, and
	 * {@code layer.<solid|cutout|cutout_mipped|translucent> = <entries>}).
	 *
	 * <p>Supports {@code [namespace:]name[:property=v1,v2...]} and block tags
	 * ({@code #minecraft:leaves}, {@code %minecraft:leaves}, {@code #leaves}, with optional
	 * property filters). The first matching entry wins.
	 */
	public static void build(final Map<String, String> properties, final List<String> log) {
		Map<String, List<String[]>> byName = new HashMap<>();
		Map<String, List<String[]>> layersByName = new HashMap<>();
		List<Float> ids = new ArrayList<>();
		ids.add(-1.0F);
		Map<Float, Integer> indexOf = new HashMap<>();

		for (Map.Entry<String, String> e : properties.entrySet()) {
			String key = e.getKey();
			if (key.startsWith("block.")) {
				float packId;
				try {
					packId = Float.parseFloat(key.substring(6).trim());
				} catch (NumberFormatException ex) {
					continue;
				}
				parseEntries(e.getValue(), Float.toString(packId), byName);
			} else if (key.startsWith("layer.")) {
				int layer = parseLayerName(key.substring(6).trim());
				if (layer != LAYER_DEFAULT) {
					parseEntries(e.getValue(), Integer.toString(layer), layersByName);
				}
			}
		}

		int stateCount = Block.BLOCK_STATE_REGISTRY.size();
		int[] states = new int[stateCount];
		byte[] layers = new byte[stateCount];
		int listed = 0;
		int layerCount = 0;

		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier key = BuiltInRegistries.BLOCK.getKey(block);
			String blockName = key.toString();
			List<String[]> rules = byName.get(blockName);
			List<String[]> layerRules = layersByName.get(blockName);
			if (rules == null && layerRules == null) {
				continue;
			}
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				int sid = Block.getId(state);
				if (sid < 0 || sid >= stateCount) {
					continue;
				}
				if (rules != null) {
					for (String[] rule : rules) {
						if (!matches(state, rule)) {
							continue;
						}
						float packId = Float.parseFloat(rule[0]);
						Integer idx = indexOf.get(packId);
						if (idx == null) {
							if (ids.size() >= 8192) {
								break;
							}
							idx = ids.size();
							ids.add(packId);
							indexOf.put(packId, idx);
						}
						states[sid] = idx;
						listed++;
						break;
					}
				}
				if (layerRules != null) {
					for (String[] rule : layerRules) {
						if (!matches(state, rule)) {
							continue;
						}
						layers[sid] = (byte) Integer.parseInt(rule[0]);
						layerCount++;
						break;
					}
				}
			}
		}
		float[] t = new float[ids.size()];
		for (int i = 0; i < t.length; i++) {
			t[i] = ids.get(i);
		}
		byState = states;
		layerByState = layers;
		table = t;
		mapped = ids.size() - 1;
		layerOverrides = layerCount;
		log.add("block.properties: " + mapped + " ids over " + listed + " block states"
			+ (layerCount > 0 ? ", " + layerCount + " layer overrides" : ""));
	}

	private static int parseLayerName(final String name) {
		switch (name) {
			case "solid": return LAYER_SOLID;
			case "cutout": return LAYER_CUTOUT;
			case "cutout_mipped": return LAYER_CUTOUT_MIPPED;
			case "translucent": return LAYER_TRANSLUCENT;
			default: return LAYER_DEFAULT;
		}
	}

	private static void parseEntries(final String value, final String payload, final Map<String, List<String[]>> target) {
		for (String rawEntry : value.trim().split("\\s+")) {
			if (rawEntry.isEmpty()) {
				continue;
			}
			String entry = rawEntry.replace("\\:", ":");
			if (Character.isDigit(entry.charAt(0))) {
				continue; // legacy numeric block id
			}
			boolean isTag = entry.charAt(0) == '#' || entry.charAt(0) == '%';
			String body = isTag ? entry.substring(1) : entry;
			if (body.isEmpty()) {
				continue;
			}
			String[] parts = body.split(":");
			String name;
			int first;
			if (parts.length >= 2 && !parts[1].contains("=")) {
				name = parts[0] + ":" + parts[1];
				first = 2;
			} else {
				name = "minecraft:" + parts[0];
				first = 1;
			}
			if (isTag) {
				expandTagRules(name, payload, parts, first, target);
			} else {
				addRule(target, name, payload, parts, first);
			}
		}
	}

	private static void expandTagRules(final String tagName, final String payload, final String[] parts,
		final int first, final Map<String, List<String[]>> target) {
		try {
			Identifier id = Identifier.tryParse(tagName);
			if (id == null) {
				return;
			}
			TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, id);
			for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tagKey)) {
				Block block = holder.value();
				if (block == null) {
					continue;
				}
				Identifier key = BuiltInRegistries.BLOCK.getKey(block);
				if (key != null) {
					addRule(target, key.toString(), payload, parts, first);
				}
			}
		} catch (RuntimeException ignored) {
			// tags not bound yet or unsupported tag id
		}
	}

	private static void addRule(final Map<String, List<String[]>> target, final String blockName,
		final String payload, final String[] parts, final int first) {
		String[] rule = new String[parts.length - first + 2];
		rule[0] = payload;
		rule[1] = blockName;
		System.arraycopy(parts, first, rule, 2, parts.length - first);
		target.computeIfAbsent(blockName, k -> new ArrayList<>()).add(rule);
	}

	private static boolean matches(final BlockState state, final String[] rule) {
		for (int i = 2; i < rule.length; i++) {
			int eq = rule[i].indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String prop = rule[i].substring(0, eq);
			String want = rule[i].substring(eq + 1);
			boolean found = false;
			for (Property<?> p : state.getProperties()) {
				if (p.getName().equals(prop)) {
					found = true;
					String actual = valueName(state, p);
					if (!matchesValueList(actual, want)) {
						return false;
					}
				}
			}
			if (!found) {
				return false;
			}
		}
		return true;
	}

	private static boolean matchesValueList(final String actual, final String want) {
		if (want.indexOf(',') < 0) {
			return actual.equals(want);
		}
		for (String v : want.split(",")) {
			if (actual.equals(v.trim())) {
				return true;
			}
		}
		return false;
	}

	private static <T extends Comparable<T>> String valueName(final BlockState state, final Property<T> p) {
		return p.getName(state.getValue(p));
	}

	/**
	 * The tag tables as the mesh worker reads them (a kind-4 blob, little-endian ints): 4, on,
	 * state count, 0, then {@link #bitsFor} of every block state by id, then the same for each
	 * state's fluid (fluid quads are tagged with the fluid's own block, as SectionCompiler does).
	 * With {@code on} false it is the 16-byte header that turns tagging off.
	 */
	public static byte[] workerBlob(final boolean on) {
		int n = on ? Block.BLOCK_STATE_REGISTRY.size() : 0;
		int[] ints = new int[4 + n * 2];
		ints[0] = 4;
		ints[1] = on ? 1 : 0;
		ints[2] = n;
		if (on) {
			for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
				int id = Block.getId(state);
				if (id < 0 || id >= n) {
					continue;
				}
				ints[4 + id] = bitsFor(state);
				net.minecraft.world.level.material.FluidState fluid = state.getFluidState();
				if (!fluid.isEmpty()) {
					ints[4 + n + id] = bitsFor(fluid.createLegacyBlock());
				}
			}
		}
		byte[] out = new byte[ints.length * 4];
		for (int i = 0; i < ints.length; i++) {
			int v = ints[i];
			out[i * 4] = (byte) v;
			out[i * 4 + 1] = (byte) (v >> 8);
			out[i * 4 + 2] = (byte) (v >> 16);
			out[i * 4 + 3] = (byte) (v >>> 24);
		}
		return out;
	}

	public static void clear() {
		byState = new int[0];
		layerByState = new byte[0];
		table = new float[] {-1.0F};
		mapped = 0;
		layerOverrides = 0;
	}
}
