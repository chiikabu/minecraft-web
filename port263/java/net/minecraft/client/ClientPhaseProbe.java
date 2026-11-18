// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client;

public final class ClientPhaseProbe {
   public static final int CLIENT_TICK = 0;
   public static final int UPDATE = 1;
   public static final int EXTRACT = 2;
   public static final int RENDER = 3;
   public static final int PRESENT = 4;
   public static final int LIMITER = 5;
   public static final int PHASES = 6;

   public static final String[] NAMES = {"clientTick", "update", "extract", "render", "present", "limiter"};

   public static boolean enabled;

   private static final int[] frameMicros = new int[PHASES];
   private static long openNanos;
   private static int openPhase = -1;

   private ClientPhaseProbe() {
   }

   public static void begin(final int phase) {
      if (enabled) {
         openPhase = phase;
         openNanos = System.nanoTime();
      }
   }

   public static void end() {
      if (enabled && openPhase >= 0) {
         frameMicros[openPhase] += (int)((System.nanoTime() - openNanos) / 1000L);
         openPhase = -1;
      }
   }

   public static void takeFrame(final int[] out) {
      for (int i = 0; i < PHASES; i++) {
         out[i] = frameMicros[i];
         frameMicros[i] = 0;
      }
   }
}
