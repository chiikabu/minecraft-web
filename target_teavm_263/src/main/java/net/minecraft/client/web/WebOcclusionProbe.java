// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.concurrent.Executor;

public final class WebOcclusionProbe implements Executor {
	private static final WebOcclusionProbe INSTANCE = new WebOcclusionProbe();

	private static int churnMicros;
	private static int churnRuns;
	private static int churnWorstMicros;

	public static int budgetMicros = 2000;

	private static int budgetTotalMicros;
	private static int budgetSlices;
	private static int budgetWorstMicros;
	private static int budgetBuilds;

	private WebOcclusionProbe() {
	}

	public static WebOcclusionProbe instance() {
		return INSTANCE;
	}

	@Override
	public void execute(final Runnable command) {
		long start = System.nanoTime();
		try {
			command.run();
		} finally {
			int micros = (int)((System.nanoTime() - start) / 1000L);
			churnMicros += micros;
			churnRuns++;
			if (micros > churnWorstMicros) {
				churnWorstMicros = micros;
			}
		}
	}

	public static void recordSlice(final int micros, final boolean finished) {
		budgetTotalMicros += micros;
		budgetSlices++;
		if (micros > budgetWorstMicros) {
			budgetWorstMicros = micros;
		}
		if (finished) {
			budgetBuilds++;
		}
	}

	public static int[] budgetCounters() {
		return new int[] {budgetTotalMicros, budgetSlices, budgetWorstMicros, budgetBuilds};
	}

	public static int[] churnCounters() {
		return new int[] {churnMicros, churnRuns, churnWorstMicros};
	}

}
