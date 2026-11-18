// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

public final class FramePacer {
	private int previousLimit;
	private long previousStartMs;
	private double deadlineMs;

	static final int UNLIMITED_CUTOFF = 260;

	static int backgroundLimit(int limit, boolean background) {
		return background ? (limit > 0 ? Math.min(limit, 20) : 20) : limit;
	}

	long nextDelayMs(final boolean vsync, final int framerateLimit, final long lastFrameStartMs, final long nowMs) {
		if (vsync && (framerateLimit >= 60 || framerateLimit <= 0)) {
			previousLimit = 0;
			return -1L;
		}
		if (framerateLimit >= UNLIMITED_CUTOFF || framerateLimit <= 0) {
			previousLimit = 0;
			return 0L;
		}
		final double periodMs = 1000.0 / framerateLimit;
		if (previousLimit != framerateLimit || lastFrameStartMs < previousStartMs
				|| lastFrameStartMs > deadlineMs + periodMs) {
			deadlineMs = lastFrameStartMs;
			previousLimit = framerateLimit;
		}
		previousStartMs = lastFrameStartMs;
		deadlineMs += periodMs;
		return Math.max(0L, (long) Math.ceil(deadlineMs - nowMs));
	}

	public static void main(final String[] args) {
		FramePacer pacer = new FramePacer();
		long start = 0;
		for (int frame = 0; frame < 120; frame++) {
			long end = start + 5;
			long delay = pacer.nextDelayMs(false, 120, start, end);
			start = end + (delay > 0 ? Math.max(4, delay) : 0);
		}
		if (start < 1000 || start > 1004) {
			throw new AssertionError("120 frames with a 4ms timer floor took " + start + "ms, expected 1000..1004");
		}
		check(backgroundLimit(260, true), 20, "unfocused unlimited -> 20 fps");
		check(backgroundLimit(10, true), 10, "preserve stricter AFK limit");
		check(backgroundLimit(0, true), 20, "unfocused invalid limit cannot spin");
		check(backgroundLimit(120, false), 120, "focus restores player limit");
		check(new FramePacer().nextDelayMs(true, backgroundLimit(120, true), 0L, 10L), 40L, "background vsync still sleeps");
		check(new FramePacer().nextDelayMs(true, 60, 0L, 999_999L), -1L, "vsync on -> rAF");
		check(new FramePacer().nextDelayMs(true, 30, 0L, 10L), 24L, "vsync on but AFK-throttled -> wait out the remainder");
		check(new FramePacer().nextDelayMs(true, 260, 0L, 0L), -1L, "vsync on ignores unlimited");

		check(new FramePacer().nextDelayMs(false, 260, 1000L, 1000L), 0L, "unlimited -> ASAP");

		check(new FramePacer().nextDelayMs(false, 60, 1000L, 1005L), 12L, "60fps, 5ms work -> wait 12");
		check(new FramePacer().nextDelayMs(false, 10, 1000L, 1000L), 100L, "10fps fresh -> wait 100");
		check(new FramePacer().nextDelayMs(false, 100, 1000L, 1010L), 0L, "on target -> 0");
		check(new FramePacer().nextDelayMs(false, 30, 1000L, 1040L), 0L, "behind target -> ASAP");

		check(new FramePacer().nextDelayMs(false, 0, 1000L, 1000L), 0L, "bogus limit -> ASAP");
		check(pacer.nextDelayMs(false, 20, 2000, 2005), 45, "limit change resets deadline");
		check(pacer.nextDelayMs(false, 120, 2100, 2200), 0, "long frame does not wait");
		check(pacer.nextDelayMs(false, 120, 2200, 2205), 4, "long frame does not cause a catch-up burst");
		check(pacer.nextDelayMs(true, 120, 2210, 2215), -1, "enable vsync resets deadline");
		check(pacer.nextDelayMs(false, 120, 2220, 2225), 4, "disable vsync starts a fresh interval");
		check(pacer.nextDelayMs(false, 120, 0, 5), 4, "clock reversal resets deadline");

		System.out.println("FramePacer self-check OK: 120 frames in " + start + "ms with a 4ms timer floor");
	}

	private static void check(final long actual, final long expected, final String label) {
		if (actual != expected) {
			throw new AssertionError(label + ": expected " + expected + " but got " + actual);
		}
	}
}
