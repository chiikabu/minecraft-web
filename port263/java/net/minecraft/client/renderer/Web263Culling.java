// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.renderer;

import java.util.function.LongPredicate;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class Web263Culling {
   public static @Nullable BatchCullHook simdCullHook;

   @FunctionalInterface
   public interface BatchCullHook {
      boolean cullAabbsFrustumFog(
         float[] planes24,
         float camX,
         float camY,
         float camZ,
         float sphRadSq,
         float cylRadSq,
         float[] minX,
         float[] minY,
         float[] minZ,
         float[] maxX,
         float[] maxY,
         float[] maxZ,
         int count,
         int[] outVisibleMask
      );
   }

   public static int fogSections;
   public static int hiddenEntities;
   public static int animationDraws;
   public static int skippedAnimationTicks;
   public static int renderEpoch;
   public static int culledEntityTicks;
   public static int fullEntityTicks;
   private static final double TICK_CULL_NEAR_SQR = 12.0 * 12.0;

   private Web263Culling() {}

   public static boolean beyondFog(AABB box, Vec3 camera, FogData fog) {
      if (fog == null || !(fog.color.w >= 1.0F)) return false;
      double x = Math.max(0, Math.max(box.minX - camera.x, camera.x - box.maxX) - 1);
      double y = Math.max(0, Math.max(box.minY - camera.y, camera.y - box.maxY) - 1);
      double z = Math.max(0, Math.max(box.minZ - camera.z, camera.z - box.maxZ) - 1);
      if (!Double.isFinite(x + y + z)) return false;
      double spherical = Math.max(fog.environmentalStart, fog.environmentalEnd);
      double cylindrical = Math.max(fog.renderDistanceStart, fog.renderDistanceEnd);
      double horizontalSquared = x * x + z * z;
      return spherical > 0 && horizontalSquared + y * y > spherical * spherical
         || cylindrical > 0 && Math.max(horizontalSquared, y * y) > cylindrical * cylindrical;
   }

   public static boolean tickCulled(net.minecraft.world.entity.Entity entity, net.minecraft.world.entity.Entity viewer) {
      if (renderEpoch - entity.web263VisibleEpoch <= 1 || entity.tickCount < 4 || viewer == null) return false;
      if (!(entity instanceof net.minecraft.world.entity.Mob
         || entity instanceof net.minecraft.world.entity.item.ItemEntity
         || entity instanceof net.minecraft.world.entity.ExperienceOrb)) return false;
      if (entity.isVehicle() || entity.isCurrentlyGlowing()) return false;
      double dx = entity.getX() - viewer.getX(), dy = entity.getY() - viewer.getY(), dz = entity.getZ() - viewer.getZ();
      return dx * dx + dy * dy + dz * dz > TICK_CULL_NEAR_SQR;
   }

   public static boolean allSectionsHidden(AABB bounds, LongPredicate hidden) {
      if (!Double.isFinite(bounds.minX + bounds.minY + bounds.minZ + bounds.maxX + bounds.maxY + bounds.maxZ)) return false;
      int x0 = SectionPos.blockToSectionCoord(bounds.minX), x1 = SectionPos.blockToSectionCoord(bounds.maxX);
      int y0 = SectionPos.blockToSectionCoord(bounds.minY), y1 = SectionPos.blockToSectionCoord(bounds.maxY);
      int z0 = SectionPos.blockToSectionCoord(bounds.minZ), z1 = SectionPos.blockToSectionCoord(bounds.maxZ);
      if ((long)x1 - x0 > 3 || (long)y1 - y0 > 3 || (long)z1 - z0 > 3) return false;
      for (int x = x0; x <= x1; x++) {
         for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
               if (!hidden.test(SectionPos.asLong(x, y, z))) return false;
            }
         }
      }
      return true;
   }
}
