// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.server;

import java.util.Arrays;

public final class Web263ServerTickProbe {
	public static final int SPAWN = 0;
	public static final int BLOCK_TICK = 1;
	public static final int DIST_MGR = 2;
	public static final int CHUNK_IO = 3;
	public static final int BROADCAST = 4;
	public static final int CHUNKMAP_TICK = 5;
	public static final int PENDING = 6;
	public static final int ENTITY = 7;
	public static final int BLOCK_ENTITY = 8;
	public static final int N = 9;
	private static final String[] NAMES = {
		"spawn", "blockTick", "distMgr", "chunkIO", "broadcast", "chunkMapTick", "pending", "entity", "blockEntity"
	};

	public static final int WINDOW = 40;

	public static boolean enabled = true;

	private static final long[] acc = new long[N];
	private static long totalAcc;
	private static long worstTotal;
	private static long lastPumpNanos;
	private static long worstPumpGapNanos;
	private static int chunksReady;
	private static int chunksSent;
	private static int ticks;
	private static final int GEN_STAGES = 16;
	private static final long[] genAcc = new long[GEN_STAGES];
	private static final int[] genCount = new int[GEN_STAGES];
	private static final String[] genNames = new String[GEN_STAGES];

	private Web263ServerTickProbe() {
	}

	public static void add(final int bucket, final long nanos) {
		if (enabled) {
			acc[bucket] += nanos;
		}
	}

	public static void endTick(final long totalNanos) {
		if (!enabled) {
			return;
		}
		totalAcc += totalNanos;
		if (totalNanos > worstTotal) {
			worstTotal = totalNanos;
		}
		if (++ticks >= WINDOW) {
			System.out.println(line(acc, totalAcc, worstTotal, ticks));
			reset();
		}
	}

	public static void pumpTurn() {
		if (!enabled) {
			return;
		}
		long now = System.nanoTime();
		if (lastPumpNanos != 0L) {
			long gap = now - lastPumpNanos;
			if (gap > worstPumpGapNanos) {
				worstPumpGapNanos = gap;
			}
		}
		lastPumpNanos = now;
	}

	public static void genStage(final int index, final String name, final long nanos) {
		if (enabled && index >= 0 && index < GEN_STAGES) {
			genAcc[index] += nanos;
			genCount[index]++;
			genNames[index] = name;
		}
	}

	public static void chunkReady() {
		if (enabled) {
			chunksReady++;
		}
	}

	public static void chunkSent() {
		if (enabled) {
			chunksSent++;
		}
	}

	static long otherNanos(final long[] buckets, final long total) {
		long sum = 0L;
		for (long v : buckets) {
			sum += v;
		}
		return total - sum;
	}

	static String line(final long[] buckets, final long total, final long worst, final int n) {
		long other = otherNanos(buckets, total);
		if (other < 0L) {
			other = 0L;
		}
		StringBuilder sb = new StringBuilder("[26.3-web] SRVTICK n=").append(n)
			.append(" mean=").append(ms(total / n))
			.append("ms worst=").append(ms(worst)).append("ms |");
		for (int i = 0; i < N; i++) {
			sb.append(' ').append(NAMES[i]).append('=').append(ms(buckets[i] / n)).append("ms");
		}
		sb.append(" other=").append(ms(other / n)).append("ms")
			.append(" ready=").append(chunksReady)
			.append(" sent=").append(chunksSent)
			.append(" pumpGap=").append(ms(worstPumpGapNanos))
			.append(" load=").append(Web263ServerLoad.level())
			.append(" farSkipped=").append(Web263ServerLoad.takeSkipped());
		long genTotal = 0L;
		for (int i = 0; i < GEN_STAGES; i++) {
			genTotal += genAcc[i];
		}
		if (genTotal > 0L) {
			sb.append(" | gen=").append(ms(genTotal)).append("ms");
			for (int i = 0; i < GEN_STAGES; i++) {
				if (genCount[i] > 0) {
					sb.append(' ').append(genNames[i]).append('=').append(ms(genAcc[i] / genCount[i])).append("ms*").append(genCount[i]);
				}
			}
		}
		return sb.toString();
	}

	private static String ms(final long nanos) {
		long hundredths = (nanos + 5_000L) / 10_000L;
		return (hundredths / 100L) + "." + pad2(hundredths % 100L);
	}

	private static String pad2(final long v) {
		return v < 10L ? "0" + v : Long.toString(v);
	}

	private static void reset() {
		Arrays.fill(acc, 0L);
		totalAcc = 0L;
		worstTotal = 0L;
		chunksReady = 0;
		chunksSent = 0;
		worstPumpGapNanos = 0L;
		ticks = 0;
		Arrays.fill(genAcc, 0L);
		Arrays.fill(genCount, 0);
	}

	static long[] snapshotForTest() {
		long[] out = new long[N + 2];
		System.arraycopy(acc, 0, out, 0, N);
		out[N] = totalAcc;
		out[N + 1] = ticks;
		return out;
	}

	static void resetForTest() {
		reset();
	}
}
