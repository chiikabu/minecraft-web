// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.jtracy;

public final class TracyClient {
	private static final Zone ZONE = new Zone();
	private static final Plot PLOT = new Plot();
	private static final MemoryPool POOL = new MemoryPool();
	private static final ContinuousFrame CFRAME = new ContinuousFrame();
	private static final DiscontinuousFrame DFRAME = new DiscontinuousFrame();
	private static final GpuContext GPU = new GpuContext();

	private TracyClient() {
	}

	public static void load() { }
	public static boolean isAvailable() { return false; }
	public static Zone beginZone(String name, boolean active) { return ZONE; }
	public static Zone beginZone(String name, String function, String file, int line) { return ZONE; }
	public static Plot createPlot(String name) { return PLOT; }
	public static MemoryPool createMemoryPool(String name) { return POOL; }
	public static ContinuousFrame createContinuousFrame(String name) { return CFRAME; }
	public static DiscontinuousFrame createDiscontinuousFrame(String name) { return DFRAME; }
	public static GpuContext createGpuContext(GpuApi api, long gpuTimestamp, float period, String name) { return GPU; }
	private static final SectionCategory SECTIONS = new SectionCategory();
	public static SectionCategory createSectionCategory(String name) { return SECTIONS; }
	public static GpuContext createGpuContext(GpuApi api, long gpuTimestamp, float period) { return GPU; }
	public static void markFrame() { }
	public static void message(String message) { }
	public static void reportAppInfo(String info) { }
	public static void setThreadName(String name, int group) { }
	public static void frameImage(java.nio.ByteBuffer image, int width, int height, int offset, boolean flip) { }
}
