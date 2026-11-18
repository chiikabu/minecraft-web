// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

public final class WebFrameProbe {
	public static final int FRAME = 0;
	public static final int DEFERRED = 1;
	public static final int CHUNKS = 2;
	public static final int RUNFRAME = 3;
	public static final int SERVER = 4;
	public static final int SAVES = 5;
	private static final int PHASES = 6;

	static final String[] NAMES = {"frame", "deferred", "chunks", "runFrame", "server", "saves"};

	private static final int[] BUCKET_CEILING_US = {16_700, 25_000, 33_400, 50_000, 100_000};
	static final String[] BUCKET_NAMES = {"le17ms", "le25ms", "le33ms", "le50ms", "le100ms", "gt100ms"};

	private static final int INNER = net.minecraft.client.ClientPhaseProbe.PHASES;
	private static final int[] innerMicros = new int[INNER];
	private static final int[] innerWorst = new int[INNER];
	private static final int[] innerWorstSplit = new int[INNER];
	private static int innerUnattributed;
	private static int innerUnattributedWorst;
	private static int innerWorstSplitUnattributed;

	private static final int[] micros = new int[PHASES];
	private static final int[] worst = new int[PHASES];
	private static final int[] worstSplit = new int[PHASES];
	private static final int[] buckets = new int[BUCKET_NAMES.length];
	private static int frames;
	private static final int[] periodBuckets = new int[10001];
	private static long lastCompletion;
	private static int periods;

	private static int collapses;
	private static int worstFrameCollapses;
	private static int lastCollapseCount;
	private static int worstCleanUs;
	private static int cleanFrames;

	private WebFrameProbe() {
	}

	public static int spikeLogMs;

	public static void record(final long[] t, final int[] inner) {
		long completion = t[PHASES - 1];
		if (lastCompletion != 0 && completion > lastCompletion) {
			long bucket = (completion - lastCompletion + 9999L) / 10000L;
			periodBuckets[(int)Math.min(bucket, periodBuckets.length - 1)]++;
			periods++;
		}
		lastCompletion = completion;
		int frameUs = (int)((t[PHASES - 1] - t[0]) / 1000L);
		if (spikeLogMs > 0 && frameUs >= spikeLogMs * 1000) {
			StringBuilder sb = new StringBuilder("[26.3-web] spike ").append(frameUs / 1000).append(" ms:");
			for (int i = 1; i < PHASES; i++) {
				sb.append(' ').append(NAMES[i]).append('=').append((t[i] - t[i - 1]) / 1_000_000L);
			}
			sb.append(" | inner:");
			for (int i = 0; i < inner.length; i++) {
				if (inner[i] >= 1000) {
					sb.append(' ').append(net.minecraft.client.ClientPhaseProbe.NAMES[i]).append('=').append(inner[i] / 1000);
				}
			}
			System.out.println(sb);
		}

		int collapseTotal = org.teavm.classlib.impl.Trampoline.collapseCount();
		int collapsedThisFrame = collapseTotal - lastCollapseCount;
		lastCollapseCount = collapseTotal;
		collapses += collapsedThisFrame;
		boolean clean = collapsedThisFrame == 0;

		accumulate(FRAME, frameUs);
		boolean newWorstFrame = frameUs >= worst[FRAME];
		if (newWorstFrame) {
			worstFrameCollapses = collapsedThisFrame;
		}

		boolean newWorstClean = clean && frameUs >= worstCleanUs;
		if (newWorstClean) {
			worstCleanUs = frameUs;
			worstSplit[FRAME] = frameUs;
		}

		for (int phase = DEFERRED; phase < PHASES; phase++) {
			int phaseUs = (int)((t[phase] - t[phase - 1]) / 1000L);
			accumulate(phase, phaseUs);
			if (newWorstClean) {
				worstSplit[phase] = phaseUs;
			}
		}

		int claimed = 0;
		for (int i = 0; i < INNER; i++) {
			innerMicros[i] += inner[i];
			if (inner[i] > innerWorst[i]) {
				innerWorst[i] = inner[i];
			}
			claimed += inner[i];
			if (newWorstClean) {
				innerWorstSplit[i] = inner[i];
			}
		}
		int unattributed = Math.max(0, (int)((t[RUNFRAME] - t[RUNFRAME - 1]) / 1000L) - claimed);
		innerUnattributed += unattributed;
		if (unattributed > innerUnattributedWorst) {
			innerUnattributedWorst = unattributed;
		}
		if (newWorstClean) {
			innerWorstSplitUnattributed = unattributed;
		}

		if (clean) {
			cleanFrames++;
		}
		buckets[bucketOf(frameUs)]++;
		frames++;
	}

	private static void accumulate(final int phase, final int us) {
		micros[phase] += us;
		if (us > worst[phase]) {
			worst[phase] = us;
		}
	}

	static int bucketOf(final int us) {
		for (int i = 0; i < BUCKET_CEILING_US.length; i++) {
			if (us <= BUCKET_CEILING_US[i]) {
				return i;
			}
		}
		return BUCKET_CEILING_US.length;
	}

	public static void reset() {
		java.util.Arrays.fill(periodBuckets, 0);
		lastCompletion = 0;
		periods = 0;
		java.util.Arrays.fill(micros, 0);
		java.util.Arrays.fill(worst, 0);
		java.util.Arrays.fill(worstSplit, 0);
		java.util.Arrays.fill(buckets, 0);
		java.util.Arrays.fill(innerMicros, 0);
		java.util.Arrays.fill(innerWorst, 0);
		java.util.Arrays.fill(innerWorstSplit, 0);
		innerUnattributed = 0;
		innerUnattributedWorst = 0;
		innerWorstSplitUnattributed = 0;
		collapses = 0;
		worstFrameCollapses = 0;
		worstCleanUs = 0;
		cleanFrames = 0;
		lastCollapseCount = org.teavm.classlib.impl.Trampoline.collapseCount();
		WebChunkBuildQueue.resetPeak();
		frames = 0;
	}

	public static String toJson() {
		StringBuilder out = new StringBuilder(384);
		out.append("{\"innerProbeEnabled\":").append(net.minecraft.client.ClientPhaseProbe.enabled)
			.append(",\"frames\":").append(frames)
			.append(",\"periodSamples\":").append(periods)
			.append(",\"p95PeriodMicros\":").append(p95PeriodMicros());
		appendGroup(out, ",\"micros\":", micros);
		appendGroup(out, ",\"worstMicros\":", worst);
		appendGroup(out, ",\"worstSplit\":", worstSplit);
		appendNamed(out, ",\"innerMicros\":", innerMicros, net.minecraft.client.ClientPhaseProbe.NAMES,
			"unattributed", innerUnattributed);
		appendNamed(out, ",\"innerWorstMicros\":", innerWorst, net.minecraft.client.ClientPhaseProbe.NAMES,
			"unattributed", innerUnattributedWorst);
		appendNamed(out, ",\"innerWorstSplit\":", innerWorstSplit, net.minecraft.client.ClientPhaseProbe.NAMES,
			"unattributed", innerWorstSplitUnattributed);
		out.append(",\"collapses\":").append(collapses)
			.append(",\"cleanFrames\":").append(cleanFrames)
			.append(",\"worstFrameCollapses\":").append(worstFrameCollapses);
		out.append(",\"buckets\":{");
		for (int i = 0; i < buckets.length; i++) {
			out.append(i == 0 ? "" : ",").append('"').append(BUCKET_NAMES[i]).append("\":").append(buckets[i]);
		}
		return out.append("}}").toString();
	}

	private static int p95PeriodMicros() {
		int rank = (int)Math.ceil(periods * 0.95);
		if (rank == 0) return -1;
		for (int i = 0; i < periodBuckets.length - 1; i++) {
			rank -= periodBuckets[i];
			if (rank <= 0) return i * 10;
		}
		return -1;
	}

	private static void appendNamed(final StringBuilder out, final String key, final int[] values,
			final String[] names, final String extraKey, final int extraValue) {
		out.append(key).append('{');
		for (int i = 0; i < values.length; i++) {
			out.append(i == 0 ? "" : ",").append('"').append(names[i]).append("\":").append(values[i]);
		}
		out.append(",\"").append(extraKey).append("\":").append(extraValue).append('}');
	}

	private static void appendGroup(final StringBuilder out, final String key, final int[] values) {
		out.append(key).append('{');
		for (int i = 0; i < values.length; i++) {
			out.append(i == 0 ? "" : ",").append('"').append(NAMES[i]).append("\":").append(values[i]);
		}
		out.append('}');
	}
}
