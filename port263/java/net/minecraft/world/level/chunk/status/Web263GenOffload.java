// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.world.level.chunk.status;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;

public final class Web263GenOffload {
   public interface Handler {
      default void onBlockingWait() {
      }

      CompletableFuture<ChunkAccess> offload(
         ChunkStatus stage,
         WorldGenContext context,
         ChunkStep step,
         StaticCache2D<GenerationChunkHolder> cache,
         WorldGenRegion region,
         ChunkAccess chunk
      );
   }

   public static volatile Handler handler;
   public static volatile java.util.function.Supplier<String> debugState;
   public static volatile java.util.concurrent.Executor dispatcherExecutor;

   public static java.util.concurrent.Executor dispatcherExecutor(final java.util.concurrent.Executor engineExecutor) {
      java.util.concurrent.Executor e = dispatcherExecutor;
      return e != null ? e : engineExecutor;
   }
   private static int blockingDepth;

   private Web263GenOffload() {
   }

   public static void enterBlocking() {
      blockingDepth++;
      Handler h = handler;
      if (h != null) {
         h.onBlockingWait();
      }
   }

   public static void exitBlocking() {
      blockingDepth--;
   }

   static CompletableFuture<ChunkAccess> tryOffload(
      final ChunkStatus stage,
      final WorldGenContext context,
      final ChunkStep step,
      final StaticCache2D<GenerationChunkHolder> cache,
      final WorldGenRegion region,
      final ChunkAccess chunk
   ) {
      Handler h = handler;
      return h == null || blockingDepth > 0
         ? null
         : h.offload(stage, context, step, cache, region, chunk);
   }
}
