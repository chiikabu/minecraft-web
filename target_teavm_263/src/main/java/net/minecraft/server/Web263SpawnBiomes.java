// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MobSpawnSettings;

public final class Web263SpawnBiomes {
	public static boolean enabled = true;
	static final long MAX_AGE_TICKS = 600L;
	static final int MAX_ENTRIES = 32768;

	private static MinecraftServer server;
	private static final Map<Level, Web263SpawnBiomes> PER_LEVEL = new IdentityHashMap<>();

	private final Long2ObjectOpenHashMap<MobSpawnSettings> values = new Long2ObjectOpenHashMap<>();
	private long filledAt = Long.MIN_VALUE;

	private Web263SpawnBiomes() {
	}

	public static MobSpawnSettings naturalMobSpawns(final Level level, final BlockPos pos) {
		if (!enabled || !(level instanceof ServerLevel serverLevel)) {
			return level.environmentAttributes().getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos);
		}
		MinecraftServer owner = serverLevel.getServer();
		if (owner != server) {
			PER_LEVEL.clear();
			server = owner;
		}
		Web263SpawnBiomes cache = PER_LEVEL.get(level);
		if (cache == null) {
			cache = new Web263SpawnBiomes();
			PER_LEVEL.put(level, cache);
		}
		return cache.get(serverLevel, pos);
	}

	private MobSpawnSettings get(final ServerLevel level, final BlockPos pos) {
		long now = level.getGameTime();
		if (now < this.filledAt || now - this.filledAt >= MAX_AGE_TICKS || this.values.size() >= MAX_ENTRIES) {
			this.values.clear();
			this.filledAt = now;
		}
		long key = BlockPos.asLong(pos.getX() >> 3, pos.getY() >> 3, pos.getZ() >> 3);
		MobSpawnSettings value = this.values.get(key);
		if (value == null) {
			value = level.environmentAttributes().getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, pos);
			this.values.put(key, value);
		}
		return value;
	}
}
