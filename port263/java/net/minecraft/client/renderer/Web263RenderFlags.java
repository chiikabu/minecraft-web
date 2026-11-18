// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.renderer;

import com.mojang.renderpearl.api.pipeline.IndexType;

public final class Web263RenderFlags {
   public static final boolean FOG_CULLING = flag("web263.fogCulling");
   public static final boolean ENTITY_CULLING = flag("web263.entityCulling");
   public static final boolean TICK_CULLING = !"false".equals(System.getProperty("web263.tickCulling"));
   public static final int ANIMATION_UPLOAD_INTERVAL = Math.max(1, size("web263.animationUploadInterval", 1));
   public static final boolean VISIBLE_ANIMATIONS = flag("web263.visibleAnimations");
   public static final boolean SODIUM_CHUNKS = flag("web263.sodiumChunks");
   public static final boolean FAST_GUI = flag("web263.fastGui");
   public static final boolean FAST_TERRAIN_SHADER = flag("web263.fastTerrainShader");
   public static final boolean HUD_CACHE = flag("web263.hudCache");
   public static final boolean TBDR_FUSE = flag("web263.tbdrFuse");

   public static final boolean SKIP_MAP_ERROR_DRAIN = flag("web263.skipMapErrorDrain");

   public static final int STAGING_BUFFER_SIZE = size("web263.stagingBufferSize", 102760448);
   public static final int VERTEX_HEAP_SIZE = size("web263.vertexHeapSize", 134217728);
   public static final int INDEX_HEAP_SIZE = size("web263.indexHeapSize", 33554432);

   private Web263RenderFlags() {
   }

   private static int size(final String name, final int fallback) {
      try {
         String raw = System.getProperty(name);
         if (raw == null) {
            return fallback;
         }
         int parsed = Integer.parseInt(raw.trim());
         return parsed > 0 ? parsed : fallback;
      } catch (Throwable var4) {
         return fallback;
      }
   }

   private static boolean flag(final String name) {
      try {
         return Boolean.parseBoolean(System.getProperty(name, "false"));
      } catch (Throwable var2) {
         return false;
      }
   }

   public static boolean canRebaseQuads(final int baseVertex) {
      return baseVertex >= 0 && (baseVertex & 3) == 0;
   }

   public static int quadFirstIndex(final int baseVertex) {
      return (baseVertex >> 2) * 6;
   }

   public static void main(final String[] args) {
      for (int baseVertex = 0; baseVertex <= 1 << 20; baseVertex += 4) {
         assert canRebaseQuads(baseVertex);
         int firstIndex = quadFirstIndex(baseVertex);
         assert firstIndex / 6 * 4 == baseVertex : "slot " + firstIndex + " -> " + firstIndex / 6 * 4;
         for (int q = 0; q < 3; q++) {
            int slot = firstIndex + 6 * q;
            assert slot / 6 * 4 == baseVertex + 4 * q;
            assert (slot + 5) / 6 * 4 + 3 == baseVertex + 4 * q + 3;
         }
      }

      int vertexSize = 28;
      int align = vertexSize * 4;
      int[] quadCounts = {100, 3000, 10000, 4000, 25};
      long byteOffset = 0L;
      int highestIndexSlot = 0;
      int vertexHighWater = 0;
      for (int quads : quadCounts) {
         int baseVertex = (int)(byteOffset / vertexSize);
         assert byteOffset % align == 0 : "align";
         assert canRebaseQuads(baseVertex) : "baseVertex " + baseVertex + " not quad-aligned";
         int firstIndex = quadFirstIndex(baseVertex);
         int indexCount = quads * 6;
         assert firstIndex / 6 * 4 == baseVertex;
         highestIndexSlot = Math.max(highestIndexSlot, firstIndex + indexCount);
         vertexHighWater = baseVertex + quads * 4;
         byteOffset += (long)quads * 4 * vertexSize;
      }
      assert highestIndexSlot == vertexHighWater / 4 * 6 : highestIndexSlot + " vs " + vertexHighWater;

      assert IndexType.least(65535) == IndexType.SHORT;
      assert IndexType.least(65536) == IndexType.INT;
      assert vertexHighWater > 65536 && IndexType.least(vertexHighWater) == IndexType.INT : "region must promote: " + vertexHighWater;
      assert IndexType.least(10000 * 4) == IndexType.SHORT;

      assert System.getProperty("web263.sodiumChunks") != null || !SODIUM_CHUNKS;
      assert System.getProperty("web263.fastGui") != null || !FAST_GUI;
      assert System.getProperty("web263.skipMapErrorDrain") != null || !SKIP_MAP_ERROR_DRAIN
         : "SKIP_MAP_ERROR_DRAIN must default OFF: desktop glGetError is free and its drain is real";
      assert flag("web263.selfcheck.absent") == false;
      System.setProperty("web263.selfcheck.tmp", "true");
      assert flag("web263.selfcheck.tmp");
      System.setProperty("web263.selfcheck.tmp", "TRUE");
      assert flag("web263.selfcheck.tmp") : "Boolean.parseBoolean is case-insensitive; WebMain writes \"true\"";
      System.setProperty("web263.selfcheck.tmp", "yes");
      assert !flag("web263.selfcheck.tmp") : "only true/TRUE enables a flag; a typo must read OFF";
      System.clearProperty("web263.selfcheck.tmp");

      boolean assertionsOn = false;
      assert assertionsOn = true;
      if (!assertionsOn) {
         throw new IllegalStateException("run with -ea");
      }

      System.out.println("Web263RenderFlags self-check OK (sodiumChunks=" + SODIUM_CHUNKS
         + ", fastGui=" + FAST_GUI + ", skipMapErrorDrain=" + SKIP_MAP_ERROR_DRAIN + ")");
   }
}
