// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.ArrayDeque;

public final class WebWorkerInbox {
	@FunctionalInterface
	public interface Sink {
		void message(String channel, byte[] data);
	}

	private static final long STARVE_LOG_MS = 250L;
	private static final long LOG_EVERY_MS = 1000L;

	static long clockMsForTest = -1L;

	private final ArrayDeque<Message> pending = new ArrayDeque<>();
	private Runnable wakeup;
	private long maxDwellMs;
	private long lastLogMs = -LOG_EVERY_MS;

	private static long now() {
		return clockMsForTest >= 0L ? clockMsForTest : System.currentTimeMillis();
	}

	public void offer(final String channel, final byte[] data) {
		if (channel != null && data != null) {
			this.pending.addLast(new Message(channel, data, now()));
			if (this.wakeup != null) {
				this.wakeup.run();
			}
		}
	}

	public void offerBatch(final String channel, final byte[] frame) {
		if (channel == null || frame == null) {
			return;
		}
		int at = 0;
		int count = 0;
		while (at + 4 <= frame.length) {
			int length = (frame[at] & 255) << 24 | (frame[at + 1] & 255) << 16 | (frame[at + 2] & 255) << 8 | frame[at + 3] & 255;
			at += 4;
			if (length < 0 || length > frame.length - at) {
				at = -1;
				break;
			}
			at += length;
			count++;
		}
		if (at != frame.length) {
			System.out.println("[26.3-inbox] dropped a malformed packet batch of " + frame.length + " bytes");
			return;
		}
		long now = now();
		at = 0;
		for (int i = 0; i < count; i++) {
			int length = (frame[at] & 255) << 24 | (frame[at + 1] & 255) << 16 | (frame[at + 2] & 255) << 8 | frame[at + 3] & 255;
			byte[] packet = new byte[length];
			System.arraycopy(frame, at + 4, packet, 0, length);
			at += 4 + length;
			this.pending.addLast(new Message(channel, packet, now));
		}
		if (count > 0 && this.wakeup != null) {
			this.wakeup.run();
		}
	}

	public void wakeOnOffer(final Runnable wakeup) {
		this.wakeup = wakeup;
	}

	public int drain(final Sink sink) {
		return this.drain(sink, 0L);
	}

	public int drain(final Sink sink, final long budgetNanos) {
		int count = this.pending.size();
		long now = now();
		long worst = 0L;
		String worstChannel = null;
		long start = budgetNanos > 0L ? System.nanoTime() : 0L;
		for (int i = 0; i < count; i++) {
			if (budgetNanos > 0L && i > 0 && System.nanoTime() - start > budgetNanos) {
				count = i;
				break;
			}
			Message message = this.pending.removeFirst();
			long dwell = now - message.arrivalMs;
			if (dwell > worst) {
				worst = dwell;
				worstChannel = message.channel;
			}
			sink.message(message.channel, message.data);
		}
		if (worst > this.maxDwellMs) {
			this.maxDwellMs = worst;
		}
		if (worst >= STARVE_LOG_MS && now - this.lastLogMs >= LOG_EVERY_MS) {
			this.lastLogMs = now;
			System.out.println("[26.3-inbox] receive loop starved ~" + worst + "ms: a '" + worstChannel
				+ "' message waited that long to be drained (" + count + " this turn). Interactive"
				+ " round-trip latency is bounded by this; a ~1s dwell is a timer-throttled owner loop.");
		}
		return count;
	}

	public long maxDwellMs() {
		return this.maxDwellMs;
	}

	public int size() {
		return this.pending.size();
	}

	public void clear() {
		this.pending.clear();
	}

	private static final class Message {
		final String channel;
		final byte[] data;
		final long arrivalMs;

		Message(final String channel, final byte[] data, final long arrivalMs) {
			this.channel = channel;
			this.data = data;
			this.arrivalMs = arrivalMs;
		}
	}
}
