// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.jtracy;

public final class GpuContext {
	public GpuZone beginZone(String name, String file, int line) { return new GpuZone(); }
	public void beginZone(int queryId, String name, String file, String function, int line) { }
	public void endZone(int queryId) { }
	public GpuContext setName(String name) { return this; }
	public void submitQueryTimestamp(int queryId, long gpuTime) { }

	public static final class GpuZone {
		public void end(long gpuTimeStart, long gpuTimeEnd) { }
	}
}
