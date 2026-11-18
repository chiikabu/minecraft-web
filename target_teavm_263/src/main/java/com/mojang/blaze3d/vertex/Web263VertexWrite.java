// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.vertex;

import org.lwjgl.system.MemoryUtil;

public final class Web263VertexWrite {
	private Web263VertexWrite() {
	}

	public static void block(final long pointer, final int x, final int y, final int z, final int abgr, final int u, final int v, final int light) {
		MemoryUtil.memPutBlockVertex(pointer, x, y, z, abgr, u, v, light);
	}

	public static void entity(final long pointer, final int x, final int y, final int z, final int abgr, final int u, final int v, final int overlay, final int light, final int normal) {
		MemoryUtil.memPutEntityVertex(pointer, x, y, z, abgr, u, v, overlay, light, normal);
	}
}
