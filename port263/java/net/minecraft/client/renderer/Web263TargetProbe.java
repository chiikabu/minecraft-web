// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.renderer;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

public final class Web263TargetProbe {
   public static final int SLOTS = 8;

   private static final Object[] views = new Object[SLOTS];
   private static final String[] labels = new String[SLOTS];
   private static final int[] draws = new int[SLOTS];
   private static final int[] indices = new int[SLOTS];
   private static int used;
   public static int overflow;

   private static final boolean READBACK_ARMED = Boolean.parseBoolean(System.getProperty("web263.readback", "false"));

   public static boolean readbackRan;
   public static String readbackPip;
   public static String readbackControl;
   private static boolean readbackDone;

   public static void readback(final String pipLabel, final GpuTexture pipTexture, final GpuTexture controlTexture) {
      if (!READBACK_ARMED || readbackDone || !"entity".equals(pipLabel) || pipTexture == null || controlTexture == null) {
         return;
      }

      readbackDone = true;
      capture(pipTexture, true);
      capture(controlTexture, false);
   }

   private static void capture(final GpuTexture texture, final boolean isPip) {
      int width = texture.getWidth(0);
      int height = texture.getHeight(0);
      int stride = texture.getFormat().blockSize();

      String pending = width + "x" + height + " PENDING (copy not completed yet)";
      report(isPip, pending);

      try {
         GpuBuffer buffer = RenderSystem.getDevice()
            .createBuffer(() -> "web263 readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long)width * height * stride);
         RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
            try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
               int nonBlack = 0;
               int first = 0;
               for (int i = 0; i < width * height; i++) {
                  int argb = read.data().getInt(i * stride);
                  if ((argb & 0x00FFFFFF) != 0) {
                     if (nonBlack == 0) {
                        first = argb;
                     }
                     nonBlack++;
                  }
               }

               report(isPip, width + "x" + height + " nonblack=" + nonBlack + "/" + width * height
                  + " first=" + Integer.toHexString(first));
            } catch (Throwable t) {
               report(isPip, "ERROR in callback " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
               buffer.close();
               readbackRan = true;
            }
         }, 0);
      } catch (Throwable t) {
         report(isPip, "ERROR " + t.getClass().getSimpleName() + ": " + t.getMessage());
      }
   }

   private static void report(final boolean isPip, final String summary) {
      if (isPip) {
         readbackPip = summary;
      } else {
         readbackControl = summary;
      }
   }

   public static String pipBlit;

   public static int pipSeen;
   public static int pipMissing;
   public static int pipDollSeen;
   public static int pipDollMissing;

   private static final String DOLL_STATE =
      net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState.class.getSimpleName();

   public static final String pipDollClassName = DOLL_STATE;

   public static int pipDollRequested;

   public static String pipFirstStateName;

   public static void pip(final Object state, final boolean found) {
      String stateName = state.getClass().getSimpleName();
      if (pipFirstStateName == null) {
         pipFirstStateName = stateName;
      }

      boolean doll = DOLL_STATE.equals(stateName);
      pipSeen++;
      if (doll) {
         pipDollSeen++;
      }

      if (!found) {
         pipMissing++;
         if (doll) {
            pipDollMissing++;
         }
      }
   }

   private Web263TargetProbe() {
   }

   public static void record(final GpuTextureView colorTexture, final int indexCount) {
      for (int i = 0; i < used; i++) {
         if (views[i] == colorTexture) {
            draws[i]++;
            indices[i] += indexCount;
            return;
         }
      }

      if (used == SLOTS) {
         overflow++;
         return;
      }

      views[used] = colorTexture;
      labels[used] = colorTexture.texture().getLabel()
         + " " + colorTexture.getWidth(0) + "x" + colorTexture.getHeight(0);
      draws[used] = 1;
      indices[used] = indexCount;
      used++;
   }

   public static String json() {
      StringBuilder out = new StringBuilder(256).append('[');
      for (int i = 0; i < used; i++) {
         if (i > 0) {
            out.append(',');
         }
         out.append("{\"label\":\"").append(labels[i] == null ? "?" : labels[i].replace('"', '\''))
            .append("\",\"draws\":").append(draws[i])
            .append(",\"indices\":").append(indices[i])
            .append('}');
      }
      return out.append(']').toString();
   }

   public static void main(final String[] args) {
      Object[] fakes = new Object[SLOTS + 2];
      for (int i = 0; i < fakes.length; i++) {
         fakes[i] = new Object();
      }

      views[0] = fakes[0];
      labels[0] = "main";
      draws[0] = 0;
      indices[0] = 0;
      used = 1;
      recordRaw(fakes[0], 100);
      recordRaw(fakes[0], 200);
      assert used == 1 : "same target must not claim a second slot";
      assert draws[0] == 2 : draws[0];
      assert indices[0] == 300 : indices[0];

      recordRaw(fakes[1], 7);
      assert used == 2 : used;
      assert draws[1] == 1 && indices[1] == 7;

      for (int i = 2; i < fakes.length; i++) {
         recordRaw(fakes[i], 1);
      }
      assert used == SLOTS : used;
      assert overflow == fakes.length - SLOTS : overflow;

      assert json().startsWith("[{\"label\":\"main\",\"draws\":2,\"indices\":300}") : json();

      boolean assertionsOn = false;
      assert assertionsOn = true;
      if (!assertionsOn) {
         throw new IllegalStateException("run with -ea");
      }

      System.out.println("Web263TargetProbe self-check OK: " + json() + " overflow=" + overflow);
   }

   private static void recordRaw(final Object target, final int indexCount) {
      for (int i = 0; i < used; i++) {
         if (views[i] == target) {
            draws[i]++;
            indices[i] += indexCount;
            return;
         }
      }

      if (used == SLOTS) {
         overflow++;
         return;
      }

      views[used] = target;
      labels[used] = "target" + used;
      draws[used] = 1;
      indices[used] = indexCount;
      used++;
   }
}
