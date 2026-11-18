// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.server;

public final class Web263Lan {
	public static int remoteGuests;

	public static int holdMode = 2;

	public static int loadedMode = 1;

	public static int ticketSlots = 4;
	public static long ticketTurnNanos = 15_000_000_000L;
	public static int ticketTurnsDone;
	public static int ticketTurnsCut;
	public static int ticketsInExecution;

	public static int chunkWindowStart = 8;
	public static int chunkWindowMin = 2;
	public static int chunkWindowMax = 24;
	public static int chunkFastAckMs = 500;
	public static int chunkSlowAckMs = 1500;
	public static int chunkBudgetPerTick = 8;

	public static int holds;
	public static int chunksSent;
	public static int acks;
	public static int ackMillis;
	public static int chunkWaits;
	public static long chunkWaitNanos;
	public static int chunkSkips;
	public static int genSlicesSkipped;

	private Web263Lan() {
	}

	public static boolean strictLoaded() {
		return loadedMode == 2 || (loadedMode == 1 && remoteGuests > 0);
	}

	public static int chunksPerGuestPerTick() {
		return Math.max(1, chunkBudgetPerTick / Math.max(1, remoteGuests));
	}
}
