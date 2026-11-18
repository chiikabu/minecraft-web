// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.server;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

public final class Web263ServerLoad {
	public static boolean enabled = true;
	public static double RAISE_MS = 38.0;
	public static double LOWER_MS = 23.0;
	public static double NEAR_BLOCKS = 32.0;
	private static final int MAX_LEVEL = 3;
	private static final int TICKS_BETWEEN_CHANGES = 20;

	private static int level;
	private static double smoothedMs;
	private static int ticksSinceChange;
	private static int skipped;

	private Web263ServerLoad() {
	}

	public static void endTick(final long nanos) {
		double ms = nanos / 1.0E6;
		smoothedMs = smoothedMs == 0.0 ? ms : smoothedMs * 0.9 + ms * 0.1;
		if (!enabled) {
			level = 0;
			return;
		}

		if (++ticksSinceChange >= TICKS_BETWEEN_CHANGES) {
			if (smoothedMs > RAISE_MS && level < MAX_LEVEL) {
				level++;
				ticksSinceChange = 0;
			} else if (smoothedMs < LOWER_MS && level > 0) {
				level--;
				ticksSinceChange = 0;
			}
		}
	}

	public static boolean shouldSkip(final Entity entity, final ServerLevel serverLevel) {
		int current = level;
		if (current == 0 || !(entity instanceof Mob mob)) {
			return false;
		}

		if (((entity.getId() + serverLevel.getGameTime()) & ((1 << current) - 1)) == 0L) {
			return false;
		}

		if (entity.isVehicle() || mob.getTarget() != null) {
			return false;
		}

		double near = NEAR_BLOCKS * NEAR_BLOCKS;
		for (ServerPlayer player : serverLevel.players()) {
			if (player.distanceToSqr(entity) < near) {
				return false;
			}
		}

		skipped++;
		return true;
	}

	public static int level() {
		return level;
	}

	public static boolean spawnThisTick(final long gameTime) {
		return level == 0 || (gameTime & ((1 << level) - 1)) == 0L;
	}

	public static int scaleMobCap(final int cap) {
		return level >= 2 ? cap / 2 : level == 1 ? cap * 3 / 4 : cap;
	}

	public static double smoothedMs() {
		return smoothedMs;
	}

	public static int takeSkipped() {
		int value = skipped;
		skipped = 0;
		return value;
	}
}
