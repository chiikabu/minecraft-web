// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public final class ShaderBlockIds {
	public static volatile boolean active;
	public static boolean compiling;
	public static int current;
	private static int[] byState = new int[0];
	public static float[] table = new float[] {-1.0F};
	public static int mapped;

	private ShaderBlockIds() {
	}

	public static int bitsFor(final BlockState state) {
		int id = Block.getId(state);
		int idx = id >= 0 && id < byState.length ? byState[id] : 0;
		return (idx & 31) << 11 | (idx >> 5 & 255) << 24;
	}

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

	public static void build(final Map<String, String> properties, final List<String> log) {
		Map<String, List<String[]>> byName = new HashMap<>();
		List<Float> ids = new ArrayList<>();
		ids.add(-1.0F);
		Map<Float, Integer> indexOf = new HashMap<>();
		for (Map.Entry<String, String> e : properties.entrySet()) {
			if (!e.getKey().startsWith("block.")) {
				continue;
			}
			float packId;
			try {
				packId = Float.parseFloat(e.getKey().substring(6).trim());
			} catch (NumberFormatException ex) {
				continue;
			}
			for (String entry : e.getValue().trim().split("\\s+")) {
				if (entry.isEmpty() || entry.startsWith("%") || Character.isDigit(entry.charAt(0))) {
					continue;
				}
				String[] parts = entry.split(":");
				String name;
				int first;
				if (parts.length >= 2 && !parts[1].contains("=")) {
					name = parts[0] + ":" + parts[1];
					first = 2;
				} else {
					name = "minecraft:" + parts[0];
					first = 1;
				}
				String[] rule = new String[parts.length - first + 2];
				rule[0] = Float.toString(packId);
				rule[1] = name;
				System.arraycopy(parts, first, rule, 2, parts.length - first);
				byName.computeIfAbsent(name, k -> new ArrayList<>()).add(rule);
			}
		}
		int[] states = new int[Block.BLOCK_STATE_REGISTRY.size()];
		int listed = 0;
		for (Block block : BuiltInRegistries.BLOCK) {
			Identifier key = BuiltInRegistries.BLOCK.getKey(block);
			List<String[]> rules = byName.get(key.toString());
			if (rules == null) {
				continue;
			}
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
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
					int sid = Block.getId(state);
					if (sid >= 0 && sid < states.length) {
						states[sid] = idx;
						listed++;
					}
					break;
				}
			}
		}
		float[] t = new float[ids.size()];
		for (int i = 0; i < t.length; i++) {
			t[i] = ids.get(i);
		}
		byState = states;
		table = t;
		mapped = ids.size() - 1;
		log.add("block.properties: " + mapped + " ids over " + listed + " block states");
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
					if (!valueName(state, p).equals(want)) {
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

	private static <T extends Comparable<T>> String valueName(final BlockState state, final Property<T> p) {
		return p.getName(state.getValue(p));
	}

	public static void clear() {
		byState = new int[0];
		table = new float[] {-1.0F};
		mapped = 0;
	}
}
