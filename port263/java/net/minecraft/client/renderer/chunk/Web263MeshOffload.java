// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.Web263RenderFlags;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MangroveRootsBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

public final class Web263MeshOffload {
   public interface Bridge {
      int caps();

      int capacity();

      void post(long address, int length);

      void reference(long address, int length);

      int peek();

      void take(long address);

      void note(int kind, int a, int b);
   }

   public interface Job {
      boolean web263Cancelled();

      void web263Complete(long resultAddress);
   }

   public static final int CAP_ACTIVE = 1;
   public static final int CAP_VERIFY = 2;
   public static final int CAP_AO = 4;
   public static final int NEED_NONCUBE = 1 << 8;
   public static final int NEED_TINT = 1 << 9;
   public static final int NEED_SHAPES = 1 << 10;
   public static final int NEED_FLUID = 1 << 11;
   public static final int NEED_TRANSLUCENT = 1 << 12;
   public static final int NEED_OFFSET = 1 << 13;
   public static final int NEED_SEED = 1 << 14;
   public static final int NEED_CUTOUT = 1 << 15;
   public static final int NEED_DYNAMIC_NEIGHBOUR = 1 << 17;
   public static final int NEED_NEVER_NEIGHBOUR = 1 << 29;
   public static final int NEED_NEVER = 1 << 30;
   private static final int NEED_MASK = 0x3FFFFF00;

   public static final int NOTE_SUBMITTED = 1;
   public static final int NOTE_FALLBACK = 2;
   public static final int NOTE_STALE = 3;
   public static final int NOTE_DECLINED = 4;
   public static final int NOTE_ERROR = 5;
   public static final int NOTE_PARKED = 6;
   public static final int NOTE_RESET = 7;
   public static final int NOTE_SNAPSHOT_MICROS = 8;
   public static final int NOTE_PUBLISH_MICROS = 9;

   private static final int MSG_DEFS = 1;
   private static final int MSG_JOB = 2;
   private static final int MSG_RESET = 3;
   private static final int MSG_REFERENCE = 4;

   private static final int REC_SHAPE = 1;
   private static final int REC_PART = 2;
   private static final int REC_MODEL = 3;
   private static final int REC_FLUID_MODEL = 4;
   private static final int REC_STATE = 5;

   private static final int F_AIR = 1;
   private static final int F_SOLID_RENDER = 1 << 1;
   private static final int F_MODEL = 1 << 2;
   private static final int F_EMISSIVE = 1 << 3;
   private static final int F_FORCE_OPAQUE = 1 << 4;
   private static final int F_LIGHT_PERMEABLE = 1 << 5;
   private static final int F_COLLISION_FULL = 1 << 6;
   private static final int F_HAS_OFFSET = 1 << 7;
   private static final int F_CUSTOM_SEED = 1 << 8;
   private static final int F_LEAVES = 1 << 9;
   private static final int F_SOLID = 1 << 10;
   private static final int F_BLOCKS_FLUID_FLOW = 1 << 11;
   private static final int F_ICE = 1 << 12;
   private static final int F_OVERLAY_NEIGHBOUR = 1 << 13;
   private static final int F_CAN_OCCLUDE = 1 << 14;
   private static final int F_FLUID_SOURCE = 1 << 15;
   private static final int F_FLUID_FALLING = 1 << 16;
   private static final int F_FLUID_TINT = 1 << 17;
   private static final int F_DYNAMIC_SHAPE = 1 << 18;

   private static final int BIT_BLOCK_ENTITY = 1;
   private static final int BIT_EXTRA = 2;
   private static final int EX_OFFSET = 1;
   private static final int EX_SEED = 2;

   private static final int MAX_PAD = 2;
   private static final int JOB_HEADER = 64;
   private static final int JOB_STATES = JOB_HEADER;
   private static final int JOB_CAPACITY = JOB_HEADER + (16 + 2 * MAX_PAD) * (16 + 2 * MAX_PAD) * (16 + 2 * MAX_PAD) * 3 + 4096 * 56;
   private static final int PROTOCOL = 2;
   public static final int NOTE_HELLO = 10;
   private static final int RESULT_HEADER = 64;

   private static final Direction[] DIRECTIONS = Direction.values();
   private static ChunkSectionLayer[] layers;

   private static ChunkSectionLayer[] layers() {
      if (layers == null) {
         layers = ChunkSectionLayer.values();
      }

      return layers;
   }
   private static final BlockTintSource[] NO_TINTS = new BlockTintSource[0];

   public static Bridge bridge;

   private static SectionCompiler epochCompiler;
   private static int epoch;
   private static boolean broken;
   private static byte[] known = new byte[0];
   private static int[] stateNeed = new int[0];
   private static byte[] stateBits = new byte[0];
   private static byte[] stateExtra = new byte[0];
   private static byte[] stateOccludes = new byte[0];
   private static byte[] stateUnculled = new byte[0];
   private static final short[] idMirror = new short[(16 + 2 * 2) * (16 + 2 * 2) * (16 + 2 * 2)];
   private static final int[] extraCells = new int[4096];
   private static BlockTintSource[][] stateTints = new BlockTintSource[0][];
   private static BlockTintSource[] stateFluidTint = new BlockTintSource[0];
   private static int uniformTintGeneration = 1;
   private static int[] stateUniformTintGen = new int[0];
   private static int[] stateUniformTint0 = new int[0];
   private static int[] stateUniformFluidTint = new int[0];
   private static int airId = -1;
   private static final IdentityHashMap<BlockStateModelPart, Integer> partIds = new IdentityHashMap<>();
   private static final IdentityHashMap<BlockStateModel, Integer> modelIds = new IdentityHashMap<>();
   private static final IdentityHashMap<BlockStateModel, Integer> modelTraits = new IdentityHashMap<>();
   private static final IdentityHashMap<BlockStateModelPart, Integer> partTraits = new IdentityHashMap<>();
   private static final IdentityHashMap<VoxelShape, Integer> shapeIds = new IdentityHashMap<>();
   private static final HashMap<String, Integer> shapeIdsByMask = new HashMap<>();
   private static final IdentityHashMap<SpriteContents, Integer> spriteIds = new IdentityHashMap<>();
   private static final ArrayList<SpriteContents> sprites = new ArrayList<>();
   private static final IdentityHashMap<FluidModel, Integer> fluidModelIds = new IdentityHashMap<>();
   private static final IdentityHashMap<Fluid, Integer> fluidGroups = new IdentityHashMap<>();

   private static final int TRAIT_UNCULLED = 1;
   private static final int TRAIT_RANDOM = 2;
   private static final int TRAIT_LAYER_SHIFT = 4;

   private static long defAddress;
   private static int defCapacity;
   private static int defLength;
   private static int recordStart = -1;
   private static long jobAddress;
   private static long resultAddress;
   private static int resultCapacity;
   private static long referenceAddress;
   private static int referenceCapacity;
   private static SectionBufferBuilderPack verifyPack;
   private static int nextJob = 1;
   private static final HashMap<Integer, Pending> pending = new HashMap<>();
   private static final ArrayList<Runnable> parked = new ArrayList<>();
   private static Pending current;
   private static final boolean[] skyFlat = new boolean[1];
   private static final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

   private static final class Pending {
      final Job job;
      final List<BlockEntity> blockEntities;

      Pending(final Job job, final List<BlockEntity> blockEntities) {
         this.job = job;
         this.blockEntities = blockEntities;
      }
   }

   private Web263MeshOffload() {
   }

   public static boolean saturated() {
      Bridge b = bridge;
      return b != null && !broken && (b.caps() & CAP_ACTIVE) != 0 && b.capacity() <= 0;
   }

   public static void park(final Runnable rearm) {
      parked.add(rearm);
      Bridge b = bridge;
      if (b != null) {
         b.note(NOTE_PARKED, parked.size(), 0);
      }
   }

   public static void frame() {
      Bridge b = bridge;
      if (b == null) {
         return;
      }

      int length;
      while ((length = b.peek()) >= 0) {
         if (length > resultCapacity || resultAddress == 0L) {
            resultCapacity = Math.max(length, Math.max(1 << 20, resultCapacity * 2));
            resultAddress = resultAddress == 0L ? MemoryUtil.nmemAlloc(resultCapacity) : MemoryUtil.nmemRealloc(resultAddress, resultCapacity);
         }

         b.take(resultAddress);
         if (length < RESULT_HEADER) {
            continue;
         }

         long r = resultAddress;
         int jobId = MemoryUtil.memGetInt(r + 4L);
         int resultEpoch = MemoryUtil.memGetInt(r + 8L);
         int status = MemoryUtil.memGetInt(r + 12L);
         Pending p = pending.remove(jobId);
         if (p == null) {
            continue;
         }

         if (p.job.web263Cancelled()) {
            b.note(NOTE_STALE, jobId, 0);
            continue;
         }

         boolean usable = status == 0 && resultEpoch == epoch;
         if (!usable) {
            b.note(NOTE_DECLINED, jobId, status);
         }

         long start = System.nanoTime();
         current = p;

         try {
            p.job.web263Complete(usable ? r : 0L);
         } catch (Throwable t) {
            System.out.println("[26.3-web] mesh worker: publishing job " + jobId + " failed: " + t);
            b.note(NOTE_ERROR, jobId, 1);
         } finally {
            current = null;
         }

         b.note(NOTE_PUBLISH_MICROS, (int)((System.nanoTime() - start) / 1000L), 0);
      }

      if (!parked.isEmpty() && !saturated()) {
         Runnable[] rearm = parked.toArray(new Runnable[0]);
         parked.clear();

         for (Runnable r : rearm) {
            r.run();
         }
      }
   }

   public static boolean trySubmit(final Job job, final SectionPos sectionPos, final RenderSectionRegion region, final SectionCompiler compiler) {
      Bridge b = bridge;
      if (b == null || broken) {
         return false;
      }

      int caps = b.caps();
      if ((caps & CAP_ACTIVE) == 0) {
         return false;
      }

      boolean ambientOcclusion = compiler.web263AmbientOcclusion();
      if (ambientOcclusion && (caps & CAP_AO) == 0) {
         return false;
      }

      SectionCopy[] sections = region.web263Sections();
      SectionCopy center = sections[13];
      BlockState[] centerFlat = center.web263Flat();
      if (centerFlat == null || center.web263Debug()) {
         return false;
      }

      try {
         if (compiler != epochCompiler) {
            reset(compiler, b);
         }

         return snapshot(b, caps, job, sectionPos, region, compiler, sections, centerFlat, ambientOcclusion);
      } catch (Throwable t) {
         broken = true;
         System.out.println("[26.3-web] mesh worker disabled, snapshot failed: " + t);
         b.note(NOTE_ERROR, 0, 2);
         return false;
      }
   }

   private static boolean snapshot(
      final Bridge b,
      final int caps,
      final Job job,
      final SectionPos sectionPos,
      final RenderSectionRegion region,
      final SectionCompiler compiler,
      final SectionCopy[] sections,
      final BlockState[] centerFlat,
      final boolean ambientOcclusion
   ) {
      long startNanos = System.nanoTime();
      final int pad = ambientOcclusion ? 2 : 1;
      final int HALO = 16 + 2 * pad;
      final int cells = HALO * HALO * HALO;
      long base = jobAddress;
      long statesAt = base + JOB_STATES;
      long lightAt = statesAt + 2L * cells;
      long extraBase = lightAt + cells;
      int originX = sectionPos.minBlockX();
      int originY = sectionPos.minBlockY();
      int originZ = sectionPos.minBlockZ();
      int haloMask = NEED_NEVER_NEIGHBOUR | NEED_SHAPES | (ambientOcclusion ? NEED_DYNAMIC_NEIGHBOUR : 0);
      int missing = ~caps & NEED_MASK;
      int need = 0;
      int offender = -1;
      int air = airId;
      BlockState last = null;
      int lastId = air;

      for (int sz = 0; sz < 3; sz++) {
         int z0 = sz == 0 ? 16 - pad : 0;
         int z1 = sz == 2 ? pad - 1 : 15;
         int dz = sz == 0 ? 0 : (sz == 1 ? pad : 16 + pad);

         for (int sy = 0; sy < 3; sy++) {
            int y0 = sy == 0 ? 16 - pad : 0;
            int y1 = sy == 2 ? pad - 1 : 15;
            int dy = sy == 0 ? 0 : (sy == 1 ? pad : 16 + pad);

            for (int sx = 0; sx < 3; sx++) {
               if (sx == 1 && sy == 1 && sz == 1) {
                  continue;
               }

               int x0 = sx == 0 ? 16 - pad : 0;
               int x1 = sx == 2 ? pad - 1 : 15;
               int dx = sx == 0 ? 0 : (sx == 1 ? pad : 16 + pad);
               BlockState[] flat = sections[sx + sy * 3 + sz * 9].web263Flat();

               for (int ly = y0; ly <= y1; ly++) {
                  for (int lz = z0; lz <= z1; lz++) {
                     int index = ((dy + ly - y0) * HALO + (dz + lz - z0)) * HALO + dx;
                     long rowAt = statesAt + 2L * index;
                     if (flat == null) {
                        for (int lx = x0; lx <= x1; lx++) {
                           MemoryUtil.memPutShort(rowAt, (short)air);
                           idMirror[index++] = (short)air;
                           rowAt += 2L;
                        }
                     } else {
                        int row = ly << 8 | lz << 4;

                        for (int lx = x0; lx <= x1; lx++) {
                           BlockState s = flat[row | lx];
                           if (s != last) {
                              last = s;
                              lastId = idOf(s, compiler);
                              int n = stateNeed[lastId] & haloMask;
                              if ((n & (missing | NEED_NEVER_NEIGHBOUR)) != 0 && offender < 0) {
                                 offender = lastId;
                              }

                              need |= n;
                           }

                           MemoryUtil.memPutShort(rowAt, (short)lastId);
                           idMirror[index++] = (short)lastId;
                           rowAt += 2L;
                        }
                     }
                  }
               }
            }
         }
      }

      List<BlockEntity> blockEntities = null;
      long extraAt = extraBase;
      BlockPos.MutableBlockPos pos = scratchPos;
      int extraCount = 0;
      last = null;
      int lastBits = 0;

      for (int lz = 0; lz < 16; lz++) {
         for (int ly = 0; ly < 16; ly++) {
            int row = ly << 8 | lz << 4;
            int index = ((pad + ly) * HALO + (pad + lz)) * HALO + pad;
            long rowAt = statesAt + 2L * index;

            for (int lx = 0; lx < 16; lx++) {
               BlockState s = centerFlat[row | lx];
               if (s != last) {
                  last = s;
                  lastId = idOf(s, compiler);
                  lastBits = stateBits[lastId];
                  int n = stateNeed[lastId];
                  if ((n & (missing | NEED_NEVER | NEED_NEVER_NEIGHBOUR)) != 0 && offender < 0) {
                     offender = lastId;
                  }

                  need |= n & ~NEED_DYNAMIC_NEIGHBOUR;
               }

               MemoryUtil.memPutShort(rowAt, (short)lastId);
               idMirror[index++] = (short)lastId;
               rowAt += 2L;
               if (lastBits != 0 && offender < 0) {
                  if ((lastBits & BIT_BLOCK_ENTITY) != 0) {
                     BlockEntity blockEntity = region.getBlockEntity(pos.set(originX + lx, originY + ly, originZ + lz));
                     if (blockEntity != null) {
                        if (blockEntities == null) {
                           blockEntities = new ArrayList<>();
                        }

                        blockEntities.add(blockEntity);
                     }
                  }

                  if ((lastBits & BIT_EXTRA) != 0) {
                     extraCells[extraCount++] = lx | lz << 4 | ly << 8;
                  }
               }
            }
         }
      }

      if (offender >= 0 || (need & (NEED_NEVER | NEED_NEVER_NEIGHBOUR)) != 0 || (need & missing) != 0) {
         b.note(NOTE_FALLBACK, need, offender);
         flushDefinitions(b);
         return false;
      }

      boolean uniformBiome = extraCount > 1 && isUniformNonSwampBiome(sections);
      int tintGen = 0;
      if (uniformBiome) {
         tintGen = ++uniformTintGeneration;
         if (stateUniformTintGen.length < stateTints.length) {
            int grown = stateTints.length;
            stateUniformTintGen = java.util.Arrays.copyOf(stateUniformTintGen, grown);
            stateUniformTint0 = java.util.Arrays.copyOf(stateUniformTint0, grown);
            stateUniformFluidTint = java.util.Arrays.copyOf(stateUniformFluidTint, grown);
         }
      }
      for (int i = 0; i < extraCount; i++) {
         int cell = extraCells[i];
         int lx = cell & 15;
         int lz = cell >> 4 & 15;
         int ly = cell >> 8;
         int index = ((pad + ly) * HALO + (pad + lz)) * HALO + pad + lx;
         int id = idMirror[index] & 65535;
         if (stateExtra[id] == 0 && !canDraw(id, index, HALO)) {
            continue;
         }

         extraAt = writeExtra(extraAt, id, centerFlat[ly << 8 | lz << 4 | lx], cell, region, pos.set(originX + lx, originY + ly, originZ + lz), tintGen);
      }

      writeLight(lightAt, region, pad);
      boolean verify = (caps & CAP_VERIFY) != 0;
      int jobId = nextJob++;
      CardinalLighting lighting = region.cardinalLighting();
      MemoryUtil.memPutInt(base, MSG_JOB);
      MemoryUtil.memPutInt(base + 4L, jobId);
      MemoryUtil.memPutInt(base + 8L, epoch);
      MemoryUtil.memPutInt(base + 12L, (ambientOcclusion ? 1 : 0) | (verify ? 2 : 0));
      MemoryUtil.memPutFloat(base + 16L, lighting.down());
      MemoryUtil.memPutFloat(base + 20L, lighting.up());
      MemoryUtil.memPutFloat(base + 24L, lighting.north());
      MemoryUtil.memPutFloat(base + 28L, lighting.south());
      MemoryUtil.memPutFloat(base + 32L, lighting.west());
      MemoryUtil.memPutFloat(base + 36L, lighting.east());
      MemoryUtil.memPutInt(base + 40L, originX);
      MemoryUtil.memPutInt(base + 44L, originY);
      MemoryUtil.memPutInt(base + 48L, originZ);
      int extraBytes = (int)(extraAt - extraBase);
      MemoryUtil.memPutInt(base + 52L, extraBytes);
      MemoryUtil.memPutInt(base + 56L, HALO);
      MemoryUtil.memPutInt(base + 60L, 0);
      flushDefinitions(b);
      if (!verify) {
         pending.put(jobId, new Pending(job, blockEntities));
      }

      b.post(base, (int)(extraAt - base));
      b.note(NOTE_SNAPSHOT_MICROS, (int)((System.nanoTime() - startNanos) / 1000L), 0);
      b.note(NOTE_SUBMITTED, jobId, need);
      if (verify) {
         sendReference(b, jobId, sectionPos, region, compiler);
         return false;
      }

      return true;
   }

   private static boolean canDraw(final int id, final int index, final int halo) {
      int down = idMirror[index - halo * halo] & 65535;
      int up = idMirror[index + halo * halo] & 65535;
      int north = idMirror[index - halo] & 65535;
      int south = idMirror[index + halo] & 65535;
      int west = idMirror[index - 1] & 65535;
      int east = idMirror[index + 1] & 65535;
      boolean downFull = (stateOccludes[down] & 2) != 0;
      boolean upFull = (stateOccludes[up] & 1) != 0;
      boolean northFull = (stateOccludes[north] & 8) != 0;
      boolean southFull = (stateOccludes[south] & 4) != 0;
      boolean westFull = (stateOccludes[west] & 32) != 0;
      boolean eastFull = (stateOccludes[east] & 16) != 0;
      if (stateTints[id].length > 0 && (stateUnculled[id] != 0 || !(downFull && upFull && northFull && southFull && westFull && eastFull))) {
         return true;
      }

      return stateFluidTint[id] != null
         && (up != id || down != id && !downFull || north != id && !northFull || south != id && !southFull || west != id && !westFull || east != id && !eastFull);
   }

   private static boolean isUniformNonSwampBiome(final SectionCopy[] sections) {
      net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b = sections[13].web263SingleBiome();
      if (b == null
         || b.value().getSpecialEffects().grassColorModifier() == net.minecraft.world.level.biome.BiomeSpecialEffects.GrassColorModifier.SWAMP) {
         return false;
      }
      for (int i = 0; i < 27; i++) {
         if (sections[i].web263SingleBiome() != b) {
            return false;
         }
      }
      return true;
   }

   private static long writeExtra(
      long at, final int id, final BlockState s, final int cell, final RenderSectionRegion region, final BlockPos pos, final int tintGen
   ) {
      int extra = stateExtra[id];
      BlockTintSource[] tints = stateTints[id];
      BlockTintSource fluidTint = stateFluidTint[id];
      int colours = tints.length + (fluidTint != null ? 1 : 0);
      MemoryUtil.memPutShort(at, (short)cell);
      MemoryUtil.memPutByte(at + 2L, (byte)extra);
      MemoryUtil.memPutByte(at + 3L, (byte)colours);
      at += 4L;
      if ((extra & EX_OFFSET) != 0) {
         Vec3 offset = s.getOffset(pos);
         MemoryUtil.memPutFloat(at, (float)offset.x);
         MemoryUtil.memPutFloat(at + 4L, (float)offset.y);
         MemoryUtil.memPutFloat(at + 8L, (float)offset.z);
         at += 12L;
      }

      if ((extra & EX_SEED) != 0) {
         long seed = s.getSeed(pos);
         MemoryUtil.memPutInt(at, (int)seed);
         MemoryUtil.memPutInt(at + 4L, (int)(seed >>> 32));
         at += 8L;
      }

      if (tintGen != 0 && tints.length <= 1) {
         if (stateUniformTintGen[id] == tintGen) {
            if (tints.length == 1) {
               MemoryUtil.memPutInt(at, stateUniformTint0[id]);
               at += 4L;
            }
            if (fluidTint != null) {
               MemoryUtil.memPutInt(at, stateUniformFluidTint[id]);
               at += 4L;
            }
            return at;
         }
         int c0 = 0;
         if (tints.length == 1) {
            c0 = tints[0].colorInWorld(s, region, pos);
            MemoryUtil.memPutInt(at, c0);
            at += 4L;
         }
         int cf = 0;
         if (fluidTint != null) {
            cf = fluidTint.colorInWorld(s, region, pos);
            MemoryUtil.memPutInt(at, cf);
            at += 4L;
         }
         stateUniformTint0[id] = c0;
         stateUniformFluidTint[id] = cf;
         stateUniformTintGen[id] = tintGen;
         return at;
      }

      for (BlockTintSource tint : tints) {
         MemoryUtil.memPutInt(at, tint.colorInWorld(s, region, pos));
         at += 4L;
      }

      if (fluidTint != null) {
         MemoryUtil.memPutInt(at, fluidTint.colorInWorld(s, region, pos));
         at += 4L;
      }

      return at;
   }

   private static void writeLight(final long lightAt, final RenderSectionRegion region, final int pad) {
      final int HALO = 16 + 2 * pad;
      LevelLightEngine engine = region.getLightEngine();
      LayerLightEventListener skyListener = engine.getLayerListener(LightLayer.SKY);
      LayerLightEventListener blockListener = engine.getLayerListener(LightLayer.BLOCK);
      SkyLightSectionStorage skyStorage = null;
      LayerLightSectionStorage<?> blockStorage = null;
      boolean skyGeneric = false;
      boolean blockGeneric = false;
      if (skyListener instanceof LightEngine<?, ?> lightEngine) {
         if (lightEngine.web263Storage() instanceof SkyLightSectionStorage storage) {
            skyStorage = storage;
         } else {
            skyGeneric = true;
         }
      } else if (skyListener != LayerLightEventListener.DummyLightLayerEventListener.INSTANCE) {
         skyGeneric = true;
      }

      if (blockListener instanceof LightEngine<?, ?> lightEngine) {
         blockStorage = lightEngine.web263Storage();
      } else if (blockListener != LayerLightEventListener.DummyLightLayerEventListener.INSTANCE) {
         blockGeneric = true;
      }

      int minX = region.web263MinSectionX();
      int minY = region.web263MinSectionY();
      int minZ = region.web263MinSectionZ();
      BlockPos.MutableBlockPos pos = scratchPos;

      for (int sz = 0; sz < 3; sz++) {
         int z0 = sz == 0 ? 16 - pad : 0;
         int z1 = sz == 2 ? pad - 1 : 15;
         int dz = sz == 0 ? 0 : (sz == 1 ? pad : 16 + pad);

         for (int sy = 0; sy < 3; sy++) {
            int y0 = sy == 0 ? 16 - pad : 0;
            int y1 = sy == 2 ? pad - 1 : 15;
            int dy = sy == 0 ? 0 : (sy == 1 ? pad : 16 + pad);

            for (int sx = 0; sx < 3; sx++) {
               int x0 = sx == 0 ? 16 - pad : 0;
               int x1 = sx == 2 ? pad - 1 : 15;
               int dx = sx == 0 ? 0 : (sx == 1 ? pad : 16 + pad);
               long node = SectionPos.asLong(minX + sx, minY + sy, minZ + sz);
               byte[] skyData = null;
               int skyConstant = 0;
               boolean flatSky = false;
               if (skyStorage != null) {
                  DataLayer layer = skyStorage.web263LayerFor(node, skyFlat);
                  if (layer == null) {
                     skyConstant = 15;
                  } else if (layer.isDefinitelyHomogenous()) {
                     skyConstant = layer.get(0, 0, 0);
                  } else {
                     skyData = layer.getData();
                     flatSky = skyFlat[0];
                  }
               }

               byte[] blockData = null;
               int blockConstant = 0;
               if (blockStorage != null) {
                  DataLayer layer = blockStorage.web263VisibleLayer(node);
                  if (layer != null) {
                     if (layer.isDefinitelyHomogenous()) {
                        blockConstant = layer.get(0, 0, 0);
                     } else {
                        blockData = layer.getData();
                     }
                  }
               }

               int blockX = SectionPos.sectionToBlockCoord(minX + sx);
               int blockY = SectionPos.sectionToBlockCoord(minY + sy);
               int blockZ = SectionPos.sectionToBlockCoord(minZ + sz);

               for (int ly = y0; ly <= y1; ly++) {
                  for (int lz = z0; lz <= z1; lz++) {
                     long at = lightAt + (((dy + ly - y0) * HALO + (dz + lz - z0)) * HALO + dx);
                     int row = ly << 8 | lz << 4;
                     int skyRow = flatSky ? lz << 4 : row;

                     for (int lx = x0; lx <= x1; lx++) {
                        int sky;
                        if (skyGeneric) {
                           sky = skyListener.getLightValue(pos.set(blockX + lx, blockY + ly, blockZ + lz));
                        } else if (skyData != null) {
                           int index = skyRow | lx;
                           sky = skyData[index >> 1] >> 4 * (index & 1) & 15;
                        } else {
                           sky = skyConstant;
                        }

                        int block;
                        if (blockGeneric) {
                           block = blockListener.getLightValue(pos.set(blockX + lx, blockY + ly, blockZ + lz));
                        } else if (blockData != null) {
                           int index = row | lx;
                           block = blockData[index >> 1] >> 4 * (index & 1) & 15;
                        } else {
                           block = blockConstant;
                        }

                        MemoryUtil.memPutByte(at, (byte)(sky << 4 | block));
                        at++;
                     }
                  }
               }
            }
         }
      }
   }

   public static SectionCompiler.Results buildResults(final long r, final SectionBufferBuilderPack pack, final VertexSorting sorting) {
      Pending p = current;
      SectionCompiler.Results results = new SectionCompiler.Results();
      long visibility = MemoryUtil.memGetInt(r + 16L) & 4294967295L | (long)MemoryUtil.memGetInt(r + 20L) << 32;
      VisibilitySet visibilitySet = new VisibilitySet();

      for (int d1 = 0; d1 < 6; d1++) {
         for (int d2 = 0; d2 < 6; d2++) {
            if ((visibility >>> d1 + d2 * 6 & 1L) != 0L) {
               visibilitySet.set(DIRECTIONS[d1], DIRECTIONS[d2], true);
            }
         }
      }

      results.visibilitySet = visibilitySet;
      int animated = MemoryUtil.memGetInt(r + 36L);
      if (Web263RenderFlags.VISIBLE_ANIMATIONS) {
         for (int i = 0; i < animated; i++) {
            int id = MemoryUtil.memGetInt(r + RESULT_HEADER + 4L * i);
            if (id > 0 && id <= sprites.size()) {
               results.animatedSprites.add(sprites.get(id - 1));
            }
         }
      }

      if (p != null && p.blockEntities != null) {
         results.blockEntities.addAll(p.blockEntities);
      }

      long source = r + RESULT_HEADER + 4L * animated;

      for (int i = 0; i < 3; i++) {
         int bytes = MemoryUtil.memGetInt(r + 24L + 4L * i);
         if (bytes > 0) {
            ChunkSectionLayer layer = layers()[i];
            VertexFormat format = layer.vertexFormat();
            ByteBufferBuilder builder = pack.buffer(layer);
            long target = builder.reserve(bytes);
            MemoryUtil.memCopy(source, target, bytes);
            ByteBufferBuilder.Result vertexBuffer = builder.build();
            int vertices = bytes / format.getVertexSize();
            MeshData mesh = new MeshData(
               vertexBuffer,
               new MeshData.DrawState(format, vertices, PrimitiveTopology.QUADS.indexCount(vertices), PrimitiveTopology.QUADS, IndexType.least(vertices))
            );
            if (layer == ChunkSectionLayer.TRANSLUCENT) {
               results.transparencyState = mesh.sortQuads(builder, sorting);
            }

            results.renderedLayers.put(layer, mesh);
            source += bytes;
         }
      }

      return results;
   }

   private static void sendReference(final Bridge b, final int jobId, final SectionPos sectionPos, final RenderSectionRegion region, final SectionCompiler compiler) {
      if (verifyPack == null) {
         verifyPack = new SectionBufferBuilderPack();
      }

      SectionCompiler.Results reference = compiler.compile(sectionPos, region, VertexSorting.byDistance(0.0F, 0.0F, 0.0F), verifyPack);

      try {
         int animated = reference.animatedSprites.size();
         int total = RESULT_HEADER + 4 * animated;

         for (MeshData mesh : reference.renderedLayers.values()) {
            total += mesh.vertexBuffer().remaining();
         }

         if (total > referenceCapacity) {
            referenceCapacity = Math.max(total, Math.max(1 << 20, referenceCapacity * 2));
            referenceAddress = referenceAddress == 0L
               ? MemoryUtil.nmemAlloc(referenceCapacity)
               : MemoryUtil.nmemRealloc(referenceAddress, referenceCapacity);
         }

         long r = referenceAddress;
         long visibility = 0L;

         for (int d1 = 0; d1 < 6; d1++) {
            for (int d2 = 0; d2 < 6; d2++) {
               if (reference.visibilitySet.visibilityBetween(DIRECTIONS[d1], DIRECTIONS[d2])) {
                  visibility |= 1L << d1 + d2 * 6;
               }
            }
         }

         MemoryUtil.memPutInt(r, MSG_REFERENCE);
         MemoryUtil.memPutInt(r + 4L, jobId);
         MemoryUtil.memPutInt(r + 8L, epoch);
         MemoryUtil.memPutInt(r + 12L, 0);
         MemoryUtil.memPutInt(r + 16L, (int)visibility);
         MemoryUtil.memPutInt(r + 20L, (int)(visibility >>> 32));
         MemoryUtil.memPutInt(r + 36L, animated);
         MemoryUtil.memPutInt(r + 40L, reference.blockEntities.size());

         for (long at = r + 44L; at < r + RESULT_HEADER; at += 4L) {
            MemoryUtil.memPutInt(at, 0);
         }

         long at = r + RESULT_HEADER;

         for (SpriteContents sprite : reference.animatedSprites) {
            MemoryUtil.memPutInt(at, spriteId(sprite));
            at += 4L;
         }

         for (int i = 0; i < 3; i++) {
            MeshData mesh = reference.renderedLayers.get(layers()[i]);
            int bytes = 0;
            if (mesh != null) {
               java.nio.ByteBuffer vertices = mesh.vertexBuffer();
               bytes = vertices.remaining();
               MemoryUtil.memCopy(MemoryUtil.memAddress(vertices), at, bytes);
               at += bytes;
            }

            MemoryUtil.memPutInt(r + 24L + 4L * i, bytes);
         }

         b.reference(r, total);
      } finally {
         reference.release();
         verifyPack.discardAll();
      }
   }

   private static void reset(final SectionCompiler compiler, final Bridge b) {
      epochCompiler = compiler;
      epoch++;
      int states = Block.BLOCK_STATE_REGISTRY.size();
      if (states > 65535) {
         throw new IllegalStateException("too many block states for 16-bit snapshot ids: " + states);
      }

      known = new byte[states];
      stateNeed = new int[states];
      stateBits = new byte[states];
      stateExtra = new byte[states];
      stateOccludes = new byte[states];
      stateUnculled = new byte[states];
      stateTints = new BlockTintSource[states][];
      stateFluidTint = new BlockTintSource[states];
      partIds.clear();
      modelIds.clear();
      modelTraits.clear();
      partTraits.clear();
      shapeIds.clear();
      shapeIdsByMask.clear();
      spriteIds.clear();
      sprites.clear();
      fluidModelIds.clear();
      fluidGroups.clear();
      if (jobAddress == 0L) {
         jobAddress = MemoryUtil.nmemAlloc(JOB_CAPACITY);
      }

      if (defAddress == 0L) {
         defCapacity = 1 << 18;
         defAddress = MemoryUtil.nmemAlloc(defCapacity);
      }

      defLength = 0;
      d32(MSG_RESET);
      d32(epoch);
      d32(compiler.web263CutoutLeaves() ? 1 : 0);
      d32(states);
      b.post(defAddress, defLength);
      beginDefinitions();
      b.note(NOTE_RESET, epoch, states);
      b.note(NOTE_HELLO, PROTOCOL, 16 + 2 * MAX_PAD);
      airId = -1;
      airId = idOf(Blocks.AIR.defaultBlockState(), compiler);
   }

   private static void beginDefinitions() {
      defLength = 0;
      d32(MSG_DEFS);
      d32(epoch);
   }

   private static void flushDefinitions(final Bridge b) {
      if (defLength > 8) {
         b.post(defAddress, defLength);
         beginDefinitions();
      }
   }

   private static int idOf(final BlockState s, final SectionCompiler compiler) {
      int id = s.web263Id;
      if (id < 0) {
         id = Block.getId(s);
         s.web263Id = id;
      }

      if (known[id] == 0) {
         known[id] = 1;
         define(s, id, compiler);
      }

      return id;
   }

   private static void define(final BlockState s, final int id, final SectionCompiler compiler) {
      int need = 0;
      int flags = 0;
      int bits = 0;
      int extra = 0;
      int modelId = 65535;
      int fluidGroup = 0;
      int fluidModelId = 65535;
      int amount = 0;
      float ownHeight = 0.0F;
      int skipRule = 0;
      int skipKey = 0;
      int[] occlusion = new int[6];
      BlockTintSource[] tints = NO_TINTS;
      BlockTintSource fluidTint = null;
      Block block = s.getBlock();
      int blockId = BuiltInRegistries.BLOCK.getId(block);
      float shade = 1.0F;
      int sturdy = 0;
      int emission = 0;
      int occludes = 0;
      boolean unculled = true;

      try {
         boolean dynamic = block.hasDynamicShape();
         emission = s.getLightEmission();
         shade = s.getShadeBrightness(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
         if (s.isAir()) flags |= F_AIR;
         if (s.isSolidRender()) flags |= F_SOLID_RENDER;
         if (s.emissiveRendering()) flags |= F_EMISSIVE;
         if (s.isLightPermeable()) flags |= F_LIGHT_PERMEABLE;
         if (s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) flags |= F_COLLISION_FULL;
         if (s.isSolid()) flags |= F_SOLID;
         if (s.canOcclude()) flags |= F_CAN_OCCLUDE;
         if (s.is(BlockTags.BLOCKS_FLUID_FLOW)) flags |= F_BLOCKS_FLUID_FLOW;
         if (block instanceof IceBlock) flags |= F_ICE;
         if (block instanceof LeavesBlock) flags |= F_LEAVES;
         if (block instanceof HalfTransparentBlock || block instanceof LeavesBlock) flags |= F_OVERLAY_NEIGHBOUR;
         if (ModelBlockRenderer.forceOpaque(compiler.web263CutoutLeaves(), s)) flags |= F_FORCE_OPAQUE;
         if (dynamic) {
            flags |= F_DYNAMIC_SHAPE;
            if (s.hasBlockEntity()) {
               need |= NEED_NEVER | NEED_DYNAMIC_NEIGHBOUR;
            }
         }

         if (s.hasBlockEntity()) bits |= BIT_BLOCK_ENTITY;

         for (Direction direction : DIRECTIONS) {
            if (!dynamic && s.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction)) {
               sturdy |= 1 << direction.ordinal();
            }

            VoxelShape shape = s.getFaceOcclusionShape(direction);
            int shapeId;
            if (shape == Shapes.empty()) {
               shapeId = 0;
            } else if (shape == Shapes.block()) {
               shapeId = 1;
            } else {
               shapeId = shapeId(shape, direction.getAxis());
               need |= shapeId == 65535 ? NEED_NEVER_NEIGHBOUR : NEED_SHAPES;
            }

            occlusion[direction.ordinal()] = shapeId;
            if (shapeId == 1) {
               occludes |= 1 << direction.ordinal();
            }
         }

         if (block instanceof LiquidBlock) {
            skipRule = 3;
         } else if (block instanceof IronBarsBlock) {
            skipRule = 5;
            need |= NEED_NEVER;
         } else if (block instanceof MangroveRootsBlock) {
            skipRule = 4;
            skipKey = BuiltInRegistries.BLOCK.getId(Blocks.MANGROVE_ROOTS);
         } else if (block instanceof HalfTransparentBlock || block instanceof PowderSnowBlock) {
            skipRule = 1;
            skipKey = blockId;
         } else if (block instanceof LeavesBlock) {
            skipRule = compiler.web263CutoutLeaves() ? 0 : 2;
         }

         if (s.hasOffsetFunction()) {
            flags |= F_HAS_OFFSET;
            extra |= EX_OFFSET;
            need |= NEED_OFFSET | NEED_NONCUBE;
         }

         if (s.getRenderShape() == RenderShape.MODEL) {
            flags |= F_MODEL;
            BlockStateModel model = compiler.web263BlockModels().get(s);
            modelId = modelId(model);
            if (modelId == 65535) {
               need |= NEED_NEVER;
            } else {
               int traits = modelTraits.get(model);
               unculled = (traits & TRAIT_UNCULLED) != 0;
               int layers = traits >> TRAIT_LAYER_SHIFT;
               if ((layers & 1 << ChunkSectionLayer.TRANSLUCENT.ordinal()) != 0) need |= NEED_TRANSLUCENT;
               if ((layers & 1 << ChunkSectionLayer.CUTOUT.ordinal()) != 0) need |= NEED_CUTOUT;
               boolean cube = (traits & TRAIT_UNCULLED) == 0 && (flags & F_SOLID_RENDER) != 0;

               for (int i = 0; i < 6 && cube; i++) {
                  cube = occlusion[i] == 1;
               }

               if (!cube) need |= NEED_NONCUBE;
               if ((traits & TRAIT_RANDOM) != 0 && customSeed(s)) {
                  flags |= F_CUSTOM_SEED;
                  extra |= EX_SEED;
                  need |= NEED_SEED;
               }
            }

            List<BlockTintSource> sources = compiler.web263BlockColors().getTintSources(s);
            if (!sources.isEmpty()) {
               if (sources.size() > 7) {
                  need |= NEED_NEVER;
               } else {
                  tints = sources.toArray(new BlockTintSource[0]);
                  need |= NEED_TINT;
               }
            }
         }

         FluidState fluid = s.getFluidState();
         if (!fluid.isEmpty()) {
            need |= NEED_FLUID | NEED_NONCUBE;
            Fluid type = fluid.getType();
            Fluid groupKey = type instanceof FlowingFluid flowing ? flowing.getSource() : type;
            Integer group = fluidGroups.get(groupKey);
            if (group == null) {
               group = fluidGroups.size() + 1;
               fluidGroups.put(groupKey, group);
            }

            fluidGroup = group;
            amount = fluid.getAmount();
            ownHeight = fluid.getOwnHeight();
            if (fluid.isSource()) flags |= F_FLUID_SOURCE;
            if (fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING)) flags |= F_FLUID_FALLING;
            FluidModel fluidModel = compiler.web263FluidModels().get(fluid);
            fluidModelId = fluidModelId(fluidModel);
            if (fluidModel.layer() == ChunkSectionLayer.TRANSLUCENT) need |= NEED_TRANSLUCENT;
            if (fluidModel.tintSource() != null) {
               fluidTint = fluidModel.tintSource();
               flags |= F_FLUID_TINT;
               need |= NEED_TINT;
            }
         }
      } catch (Throwable t) {
         if (recordStart >= 0) {
            defLength = recordStart;
            recordStart = -1;
         }

         System.out.println("[26.3-web] mesh worker: cannot flatten " + s + ": " + t);
         need |= NEED_NEVER | NEED_NEVER_NEIGHBOUR;
         modelId = 65535;
         tints = NO_TINTS;
         fluidTint = null;
      }

      if (tints.length > 0 || fluidTint != null || extra != 0) {
         bits |= BIT_EXTRA;
      }

      stateNeed[id] = need;
      stateBits[id] = (byte)bits;
      stateExtra[id] = (byte)extra;
      stateOccludes[id] = (byte)occludes;
      stateUnculled[id] = (byte)(unculled ? 1 : 0);
      stateTints[id] = tints;
      stateFluidTint[id] = fluidTint;
      byte[] name = s.toString().getBytes(StandardCharsets.UTF_8);
      if (name.length > 200) {
         name = java.util.Arrays.copyOf(name, 200);
      }

      d8(REC_STATE);
      d16(id);
      d32(flags);
      d32(need);
      d8(emission);
      df(shade);
      d8(skipRule);
      d16(skipKey);
      d16(blockId);

      for (int i = 0; i < 6; i++) {
         d16(occlusion[i]);
      }

      d8(tints.length);
      d16(modelId);
      d8(fluidGroup);
      d16(fluidModelId);
      d8(amount);
      df(ownHeight);
      d8(sturdy);
      d8(extra);
      d16(name.length);

      for (byte c : name) {
         d8(c);
      }
   }

   private static boolean customSeed(final BlockState s) {
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

      for (int i = 0; i < 6; i++) {
         pos.set(37 * i - 91, 13 * i - 20, 61 - 29 * i);
         if (s.getSeed(pos) != net.minecraft.util.Mth.getSeed(pos)) {
            return true;
         }
      }

      return false;
   }

   private static int modelId(final BlockStateModel model) {
      Integer existing = modelIds.get(model);
      if (existing != null) {
         return existing;
      }

      int traits;
      int id;
      if (model instanceof SingleVariant single) {
         BlockStateModelPart part = single.web263Part();
         int part0 = partId(part);
         traits = partTraits.get(part);
         id = modelIds.size();
         recordStart = defLength;
         d8(REC_MODEL);
         d16(id);
         d8(0);
         d16(part0);
      } else if (model instanceof WeightedVariants weighted) {
         List<Weighted<BlockStateModel>> entries = weighted.web263List().unwrap();
         int[] children = new int[entries.size()];
         int total = 0;
         traits = entries.size() > 1 ? TRAIT_RANDOM : 0;

         for (int i = 0; i < children.length; i++) {
            BlockStateModel child = entries.get(i).value();
            children[i] = modelId(child);
            if (children[i] == 65535) {
               return 65535;
            }

            traits |= modelTraits.get(child);
            total += entries.get(i).weight();
         }

         id = modelIds.size();
         recordStart = defLength;
         d8(REC_MODEL);
         d16(id);
         d8(1);
         d16(children.length);
         d32(total);

         for (int i = 0; i < children.length; i++) {
            d32(entries.get(i).weight());
            d16(children[i]);
         }
      } else if (model instanceof MultiPartModel multi) {
         List<BlockStateModel> models = multi.web263Models();
         int[] children = new int[models.size()];
         traits = 0;

         for (int i = 0; i < children.length; i++) {
            children[i] = modelId(models.get(i));
            if (children[i] == 65535) {
               return 65535;
            }

            traits |= modelTraits.get(models.get(i));
         }

         id = modelIds.size();
         recordStart = defLength;
         d8(REC_MODEL);
         d16(id);
         d8(2);
         d16(children.length);

         for (int child : children) {
            d16(child);
         }
      } else {
         return 65535;
      }

      if (id >= 65535) {
         throw new IllegalStateException("model table full");
      }

      recordStart = -1;
      modelIds.put(model, id);
      modelTraits.put(model, traits);
      return id;
   }

   private static int partId(final BlockStateModelPart part) {
      Integer existing = partIds.get(part);
      if (existing != null) {
         return existing;
      }

      int id = partIds.size();
      if (id >= 65535) {
         throw new IllegalStateException("part table full");
      }

      int traits = 0;
      recordStart = defLength;
      d8(REC_PART);
      d16(id);
      d8(part.useAmbientOcclusion() ? 1 : 0);

      for (int bucket = 0; bucket < 7; bucket++) {
         List<BakedQuad> quads = part.getQuads(bucket < 6 ? DIRECTIONS[bucket] : null);
         d16(quads.size());
         if (bucket == 6 && !quads.isEmpty()) {
            traits |= TRAIT_UNCULLED;
         }

         for (BakedQuad quad : quads) {
            BakedQuad.MaterialInfo material = quad.materialInfo();

            for (int v = 0; v < 4; v++) {
               Vector3fc position = quad.position(v);
               df(position.x());
               df(position.y());
               df(position.z());
            }

            for (int v = 0; v < 4; v++) {
               long packed = quad.packedUV(v);
               df(UVPair.unpackU(packed));
               df(UVPair.unpackV(packed));
            }

            Direction shade = material.shadeDirectionOverride();
            TextureAtlasSprite sprite = material.sprite();
            int layer = material.layer().ordinal();
            traits |= 1 << layer + TRAIT_LAYER_SHIFT;
            d8(quad.direction().ordinal());
            d8(layer);
            d8(material.tintIndex());
            d8(shade == null ? -1 : shade.ordinal());
            d8(material.lightEmission());
            d8(0);
            d16(sprite.isAnimated() ? spriteId(sprite.contents()) : 0);
         }
      }

      recordStart = -1;
      partIds.put(part, id);
      partTraits.put(part, traits);
      return id;
   }

   private static int fluidModelId(final FluidModel model) {
      Integer existing = fluidModelIds.get(model);
      if (existing != null) {
         return existing;
      }

      int id = fluidModelIds.size();
      recordStart = defLength;
      d8(REC_FLUID_MODEL);
      d16(id);
      d8(model.layer().ordinal());
      d8(model.overlayMaterial() != null ? 1 : 0);
      d8(model.tintSource() != null ? 1 : 0);
      dSprite(model.stillMaterial().sprite());
      dSprite(model.flowingMaterial().sprite());
      dSprite(model.overlayMaterial() != null ? model.overlayMaterial().sprite() : model.stillMaterial().sprite());
      recordStart = -1;
      fluidModelIds.put(model, id);
      return id;
   }

   private static void dSprite(final TextureAtlasSprite sprite) {
      df(sprite.getU0());
      df(sprite.getU1());
      df(sprite.getV0());
      df(sprite.getV1());
      d16(sprite.isAnimated() ? spriteId(sprite.contents()) : 0);
   }

   private static int spriteId(final SpriteContents contents) {
      Integer existing = spriteIds.get(contents);
      if (existing != null) {
         return existing;
      }

      sprites.add(contents);
      int id = sprites.size();
      spriteIds.put(contents, id);
      return id;
   }

   private static int shapeId(final VoxelShape shape, final Direction.Axis axis) {
      Integer existing = shapeIds.get(shape);
      if (existing != null) {
         return existing;
      }

      final int[] rows = new int[16];
      final boolean[] offGrid = new boolean[1];
      shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> {
         double u1;
         double u2;
         double v1;
         double v2;
         switch (axis) {
            case X:
               u1 = z1;
               u2 = z2;
               v1 = y1;
               v2 = y2;
               break;
            case Y:
               u1 = x1;
               u2 = x2;
               v1 = z1;
               v2 = z2;
               break;
            default:
               u1 = x1;
               u2 = x2;
               v1 = y1;
               v2 = y2;
         }

         int a1 = grid(u1, offGrid);
         int a2 = grid(u2, offGrid);
         int b1 = grid(v1, offGrid);
         int b2 = grid(v2, offGrid);

         for (int v = b1; v < b2; v++) {
            for (int u = a1; u < a2; u++) {
               rows[v] |= 1 << u;
            }
         }
      });
      int id;
      if (offGrid[0]) {
         id = 65535;
      } else {
         StringBuilder key = new StringBuilder(32);

         for (int row : rows) {
            key.append((char)row);
         }

         Integer byMask = shapeIdsByMask.get(key.toString());
         if (byMask != null) {
            id = byMask;
         } else {
            id = shapeIdsByMask.size() + 2;
            if (id >= 65535) {
               throw new IllegalStateException("shape table full");
            }

            shapeIdsByMask.put(key.toString(), id);
            recordStart = defLength;
            d8(REC_SHAPE);
            d16(id);

            for (int row : rows) {
               d16(row);
            }

            recordStart = -1;
         }
      }

      shapeIds.put(shape, id);
      return id;
   }

   private static int grid(final double value, final boolean[] offGrid) {
      double scaled = value * 16.0;
      int rounded = (int)Math.round(scaled);
      if (Math.abs(scaled - rounded) > 1.0E-6 || rounded < 0 || rounded > 16) {
         offGrid[0] = true;
         return 0;
      }

      return rounded;
   }

   private static void dEnsure(final int more) {
      if (defLength + more > defCapacity) {
         defCapacity = Math.max(defCapacity * 2, defLength + more);
         defAddress = MemoryUtil.nmemRealloc(defAddress, defCapacity);
      }
   }

   private static void d8(final int value) {
      dEnsure(1);
      MemoryUtil.memPutByte(defAddress + defLength, (byte)value);
      defLength++;
   }

   private static void d16(final int value) {
      dEnsure(2);
      MemoryUtil.memPutShort(defAddress + defLength, (short)value);
      defLength += 2;
   }

   private static void d32(final int value) {
      dEnsure(4);
      MemoryUtil.memPutInt(defAddress + defLength, value);
      defLength += 4;
   }

   private static void df(final float value) {
      d32(Float.floatToRawIntBits(value));
   }
}
