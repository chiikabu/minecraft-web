// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.vertex;

import org.lwjgl.system.MemoryUtil;

public final class Web263VertexWrite {
   private Web263VertexWrite() {
   }

   public static void block(final long pointer, final int x, final int y, final int z, final int abgr, final int u, final int v, final int light) {
      MemoryUtil.memIntBuffer(pointer, 7).put(x).put(y).put(z).put(abgr).put(u).put(v).put(light);
   }

   public static void entity(final long pointer, final int x, final int y, final int z, final int abgr, final int u, final int v, final int overlay, final int light, final int normal) {
      MemoryUtil.memIntBuffer(pointer, 9).put(x).put(y).put(z).put(abgr).put(u).put(v).put(overlay).put(light).put(normal);
   }
}
