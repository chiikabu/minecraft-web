// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.renderer.Web263Culling;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSBody;

/**
 * Zero-copy bridge from TeaVM WasmGC Java to the standalone WASM-SIMD128 compute module
 * published at {@code globalThis.__mcWebSimd} (or {@code __twitSimd}) over shared
 * {@code globalThis.__teavmMemory}.
 */
public final class WebSimdKernels {
	public static final int MIN_BATCH_SIZE = 16;

	private static final int FLOAT_EXACT_INT_MAX = 16_777_216;
	private static final int BEARD_KERNEL_FLOATS = 13_824;

	private static int simdState;
	private static int bigIntState;
	private static boolean worldgenInstalled;
	private static boolean clientInstalled;
	private static long wasmStackPtr;

	private static long noisePermPtr;
	private static long noiseOutPtr;
	private static int noiseOutCap;

	private static long aquiferCellPtr;
	private static int aquiferCellCap;
	private static long aquiferQueryPtr;
	private static int aquiferQueryCap;
	private static long aquiferOutPtr;
	private static int aquiferOutCap;

	private static long beardKernelPtr;
	private static long beardRigidsPtr;
	private static int beardRigidsCap;
	private static long beardJunctionsPtr;
	private static int beardJunctionsCap;
	private static long beardOutPtr;
	private static int beardOutCap;

	private static long biomeArenaPtr;
	private static int biomeArenaCap;

	private static long cullPassPlanesPtr;
	private static long cullPlanesPtr;
	private static long cullBoxesPtr;
	private static int cullBoxesCap;
	private static long cullMaskPtr;
	private static int cullMaskWordsCap;

	private static long sortScratchPtr;
	private static int sortScratchCap;
	private static long sortCentroidsVertsPtr;
	private static int sortCentroidsCap;

	private static long mat4ArenaPtr;
	private static int mat4ArenaCap;

	private WebSimdKernels() {
	}

	public static void install() {
		if (worldgenInstalled) {
			return;
		}
		worldgenInstalled = true;
		if (!MemoryUtil.LINEAR) {
			simdState = -1;
			return;
		}
		boolean ok = ready();
		if (!ok) {
			System.out.println("[26.3-simd] worldgen SIMD unavailable; using vanilla Java fallback");
			return;
		}
		Aquifer.SimdHooks.voronoiHook = WebSimdKernels::aquiferVoronoiBatch;
		Beardifier.simdVolumeHook = WebSimdKernels::beardifierVolume;
		BiomeManager.simdFiddleBatchHook = WebSimdKernels::biomeFiddleBatch;
		System.out.println("[26.3-simd] worldgen SIMD kernels installed (Aquifer, Beardifier, BiomeManager)");
	}

	public static void installClient() {
		install();
		if (clientInstalled) {
			return;
		}
		clientInstalled = true;
		if (!MemoryUtil.LINEAR || !ready()) {
			return;
		}
		Web263Culling.simdCullHook = WebSimdKernels::cullAabbsFrustumFog;
		Web263Culling.simdSortHook = WebSimdKernels::createDistanceSorting;
		System.out.println("[26.3-simd] client SIMD kernels installed (Fog/Frustum Cull, Translucent Sort)");
	}

	public static boolean ready() {
		int s = simdState;
		if (s != 0) {
			return s > 0;
		}
		if (!MemoryUtil.LINEAR) {
			simdState = -1;
			return false;
		}
		if (ensureJs() != 0) {
			if (wasmStackPtr == MemoryUtil.NULL) {
				wasmStackPtr = MemoryUtil.nmemAlloc(4096L);
			}
			setScratchJs((int) ((wasmStackPtr + 4096L) & ~15L));
			simdState = 1;
			return true;
		}
		simdState = -1;
		return false;
	}

	public static void resetProbe() {
		simdState = 0;
		bigIntState = 0;
	}

	private static boolean hasBigInt() {
		int b = bigIntState;
		if (b != 0) {
			return b > 0;
		}
		int avail = hasBigIntJs();
		bigIntState = avail != 0 ? 1 : -1;
		return avail != 0;
	}

	private static long align16(final long bytes) {
		return (bytes + 15L) & ~15L;
	}

	private static int growCap(final int current, final int needed, final int floor) {
		int cap = current > 0 ? current : floor;
		while (cap < needed) {
			int next = cap << 1;
			if (next <= cap) {
				return needed;
			}
			cap = next;
		}
		return cap;
	}

	// ---- Kernel 1: simd_improved_noise_batch ----

	public static boolean improvedNoiseBatch(
		final byte[] perm,
		final double xo, final double yo, final double zo,
		final double x0, final double y0, final double z0,
		final double dx, final double dy, final double dz,
		final int nx, final int ny, final int nz,
		final double freq, final double amp,
		final float[] out, final boolean accumulate
	) {
		if (!MemoryUtil.LINEAR || !ready() || perm == null || perm.length < 256 || out == null) {
			return false;
		}
		if (nx <= 0 || ny <= 0 || nz <= 0) {
			return true;
		}
		long totalLong = (long) nx * (long) ny * (long) nz;
		if (totalLong < MIN_BATCH_SIZE || totalLong > Integer.MAX_VALUE || out.length < (int) totalLong) {
			return false;
		}
		int total = (int) totalLong;
		if (noisePermPtr == MemoryUtil.NULL) {
			noisePermPtr = MemoryUtil.nmemAlloc(256L);
		}
		if (noiseOutPtr == MemoryUtil.NULL || noiseOutCap < total) {
			if (noiseOutPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(noiseOutPtr);
			}
			int cap = growCap(noiseOutCap, total, 1024);
			noiseOutPtr = MemoryUtil.nmemAlloc((long) cap * 4L);
			noiseOutCap = cap;
		}
		MemoryUtil.copyIn(perm, 0, noisePermPtr, 256);
		if (accumulate) {
			long p = noiseOutPtr;
			for (int i = 0; i < total; i++) {
				MemoryUtil.memPutFloat(p, out[i]);
				p += 4L;
			}
		}
		jsImprovedNoiseBatch(
			(int) noisePermPtr,
			xo, yo, zo,
			x0, y0, z0,
			dx, dy, dz,
			nx, ny, nz,
			freq, amp,
			(int) noiseOutPtr,
			accumulate ? 1 : 0
		);
		long p = noiseOutPtr;
		for (int i = 0; i < total; i++) {
			out[i] = MemoryUtil.memGetFloat(p);
			p += 4L;
		}
		return true;
	}

	public static boolean improvedNoiseBatchLinear(
		final long permPtr,
		final double xo, final double yo, final double zo,
		final double x0, final double y0, final double z0,
		final double dx, final double dy, final double dz,
		final int nx, final int ny, final int nz,
		final double freq, final double amp,
		final long outPtr, final boolean accumulate
	) {
		if (!MemoryUtil.LINEAR || !ready() || permPtr == MemoryUtil.NULL || outPtr == MemoryUtil.NULL) {
			return false;
		}
		if (nx <= 0 || ny <= 0 || nz <= 0) {
			return true;
		}
		jsImprovedNoiseBatch(
			(int) permPtr,
			xo, yo, zo,
			x0, y0, z0,
			dx, dy, dz,
			nx, ny, nz,
			freq, amp,
			(int) outPtr,
			accumulate ? 1 : 0
		);
		return true;
	}

	// ---- Kernel 2: simd_aquifer_voronoi_batch ----

	public static boolean aquiferVoronoiBatch(
		final int[] cellXyz, final int cellCount,
		final int[] queryXyz, final int queryStart, final int queryCount,
		final int[] outBestIdx4, final int[] outBestDist4
	) {
		if (!MemoryUtil.LINEAR || !ready() || cellXyz == null || queryXyz == null || outBestIdx4 == null || outBestDist4 == null) {
			return false;
		}
		if (cellCount <= 0 || queryCount < MIN_BATCH_SIZE || queryStart < 0) {
			return false;
		}
		if (cellXyz.length < cellCount * 3 || queryXyz.length < (queryStart + queryCount) * 3
			|| outBestIdx4.length < queryCount * 4 || outBestDist4.length < queryCount * 4) {
			return false;
		}
		if (aquiferCellPtr == MemoryUtil.NULL || aquiferCellCap < cellCount) {
			if (aquiferCellPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(aquiferCellPtr);
			}
			int cap = growCap(aquiferCellCap, cellCount, 16);
			aquiferCellPtr = MemoryUtil.nmemAlloc((long) cap * 12L);
			aquiferCellCap = cap;
		}
		if (aquiferQueryPtr == MemoryUtil.NULL || aquiferQueryCap < queryCount) {
			if (aquiferQueryPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(aquiferQueryPtr);
			}
			int cap = growCap(aquiferQueryCap, queryCount, 256);
			aquiferQueryPtr = MemoryUtil.nmemAlloc((long) cap * 12L);
			aquiferQueryCap = cap;
		}
		if (aquiferOutPtr == MemoryUtil.NULL || aquiferOutCap < queryCount) {
			if (aquiferOutPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(aquiferOutPtr);
			}
			int cap = growCap(aquiferOutCap, queryCount, 256);
			long halfBytes = align16((long) cap * 16L);
			aquiferOutPtr = MemoryUtil.nmemAlloc(halfBytes * 2L);
			aquiferOutCap = cap;
		}

		long cp = aquiferCellPtr;
		int cellInts = cellCount * 3;
		for (int i = 0; i < cellInts; i++) {
			MemoryUtil.memPutInt(cp, cellXyz[i]);
			cp += 4L;
		}
		long qp = aquiferQueryPtr;
		int qBase = queryStart * 3;
		int qInts = queryCount * 3;
		for (int i = 0; i < qInts; i++) {
			MemoryUtil.memPutInt(qp, queryXyz[qBase + i]);
			qp += 4L;
		}

		long outIdxPtr = aquiferOutPtr;
		long outDistPtr = aquiferOutPtr + align16((long) aquiferOutCap * 16L);
		jsAquiferVoronoiBatch(
			(int) aquiferCellPtr, cellCount,
			(int) aquiferQueryPtr, queryCount,
			(int) outIdxPtr, (int) outDistPtr
		);

		int outInts = queryCount * 4;
		long ip = outIdxPtr;
		long dp = outDistPtr;
		for (int i = 0; i < outInts; i++) {
			outBestIdx4[i] = MemoryUtil.memGetInt(ip);
			outBestDist4[i] = MemoryUtil.memGetInt(dp);
			ip += 4L;
			dp += 4L;
		}
		return true;
	}

	public static boolean aquiferVoronoiBatchLinear(
		final long cellPtr, final int cellCount,
		final long queryPtr, final int queryCount,
		final long outBestIdx4Ptr, final long outBestDist4Ptr
	) {
		if (!MemoryUtil.LINEAR || !ready()
			|| cellPtr == MemoryUtil.NULL || queryPtr == MemoryUtil.NULL
			|| outBestIdx4Ptr == MemoryUtil.NULL || outBestDist4Ptr == MemoryUtil.NULL) {
			return false;
		}
		if (cellCount <= 0 || queryCount <= 0) {
			return true;
		}
		jsAquiferVoronoiBatch(
			(int) cellPtr, cellCount,
			(int) queryPtr, queryCount,
			(int) outBestIdx4Ptr, (int) outBestDist4Ptr
		);
		return true;
	}

	// ---- Kernel 3: simd_beardifier_volume ----

	private static boolean exceedsFloatExactInt(final int v) {
		return v < -FLOAT_EXACT_INT_MAX || v > FLOAT_EXACT_INT_MAX;
	}

	private static int terrainAdjustmentCode(final TerrainAdjustment adj) {
		return switch (adj) {
			case NONE -> 0;
			case BURY -> 1;
			case BEARD_THIN -> 2;
			case BEARD_BOX -> 3;
			case ENCAPSULATE -> 4;
		};
	}

	public static boolean beardifierVolume(
		final List<Beardifier.Rigid> pieces,
		final List<JigsawJunction> junctions,
		final float[] beardKernel,
		final int minBlockX, final int minBlockY, final int minBlockZ,
		final int nx, final int ny, final int nz,
		final DensityBuffer outputBuffer,
		final DensityVolume volume,
		final int startX, final int startY, final int startZ
	) {
		if (!MemoryUtil.LINEAR || !ready() || beardKernel == null || beardKernel.length < BEARD_KERNEL_FLOATS) {
			return false;
		}
		if (nx <= 0 || ny <= 0 || nz <= 0) {
			return true;
		}
		int rigidCount = pieces.size();
		int junctionCount = junctions.size();
		if (rigidCount == 0 && junctionCount == 0) {
			return true;
		}
		long totalLong = (long) nx * (long) ny * (long) nz;
		if (totalLong < MIN_BATCH_SIZE || totalLong > Integer.MAX_VALUE) {
			return false;
		}
		int total = (int) totalLong;

		if (beardKernelPtr == MemoryUtil.NULL) {
			long kPtr = MemoryUtil.nmemAlloc((long) BEARD_KERNEL_FLOATS * 4L);
			long p = kPtr;
			for (int i = 0; i < BEARD_KERNEL_FLOATS; i++) {
				MemoryUtil.memPutFloat(p, beardKernel[i]);
				p += 4L;
			}
			beardKernelPtr = kPtr;
		}

		if (rigidCount > 0 && (beardRigidsPtr == MemoryUtil.NULL || beardRigidsCap < rigidCount)) {
			if (beardRigidsPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(beardRigidsPtr);
			}
			int cap = growCap(beardRigidsCap, rigidCount, 64);
			beardRigidsPtr = MemoryUtil.nmemAlloc((long) cap * 32L);
			beardRigidsCap = cap;
		}
		if (junctionCount > 0 && (beardJunctionsPtr == MemoryUtil.NULL || beardJunctionsCap < junctionCount)) {
			if (beardJunctionsPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(beardJunctionsPtr);
			}
			int cap = growCap(beardJunctionsCap, junctionCount, 64);
			beardJunctionsPtr = MemoryUtil.nmemAlloc((long) cap * 12L);
			beardJunctionsCap = cap;
		}
		if (beardOutPtr == MemoryUtil.NULL || beardOutCap < total) {
			if (beardOutPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(beardOutPtr);
			}
			int cap = growCap(beardOutCap, total, 4096);
			beardOutPtr = MemoryUtil.nmemAlloc((long) cap * 4L);
			beardOutCap = cap;
		}

		if (rigidCount > 0) {
			long rp = beardRigidsPtr;
			for (int i = 0; i < rigidCount; i++) {
				Beardifier.Rigid rigid = pieces.get(i);
				BoundingBox box = rigid.box();
				int bMinX = box.minX();
				int bMinY = box.minY();
				int bMinZ = box.minZ();
				int bMaxX = box.maxX();
				int bMaxY = box.maxY();
				int bMaxZ = box.maxZ();
				int gDelta = rigid.groundLevelDelta();
				if (exceedsFloatExactInt(bMinX) || exceedsFloatExactInt(bMinY) || exceedsFloatExactInt(bMinZ)
					|| exceedsFloatExactInt(bMaxX) || exceedsFloatExactInt(bMaxY) || exceedsFloatExactInt(bMaxZ)
					|| exceedsFloatExactInt(gDelta)) {
					return false;
				}
				int adjCode = terrainAdjustmentCode(rigid.terrainAdjustment());
				MemoryUtil.memPutFloat(rp, (float) bMinX);
				MemoryUtil.memPutFloat(rp + 4L, (float) bMinY);
				MemoryUtil.memPutFloat(rp + 8L, (float) bMinZ);
				MemoryUtil.memPutFloat(rp + 12L, (float) bMaxX);
				MemoryUtil.memPutFloat(rp + 16L, (float) bMaxY);
				MemoryUtil.memPutFloat(rp + 20L, (float) bMaxZ);
				MemoryUtil.memPutFloat(rp + 24L, (float) gDelta);
				MemoryUtil.memPutFloat(rp + 28L, (float) adjCode);
				rp += 32L;
			}
		}

		if (junctionCount > 0) {
			long jp = beardJunctionsPtr;
			for (int i = 0; i < junctionCount; i++) {
				JigsawJunction junction = junctions.get(i);
				int jx = junction.getSourceX();
				int jy = junction.getSourceGroundY();
				int jz = junction.getSourceZ();
				if (exceedsFloatExactInt(jx) || exceedsFloatExactInt(jy) || exceedsFloatExactInt(jz)) {
					return false;
				}
				MemoryUtil.memPutFloat(jp, (float) jx);
				MemoryUtil.memPutFloat(jp + 4L, (float) jy);
				MemoryUtil.memPutFloat(jp + 8L, (float) jz);
				jp += 12L;
			}
		}

		jsBeardifierVolume(
			rigidCount > 0 ? (int) beardRigidsPtr : 0, rigidCount,
			junctionCount > 0 ? (int) beardJunctionsPtr : 0, junctionCount,
			(int) beardKernelPtr,
			minBlockX, minBlockY, minBlockZ,
			nx, ny, nz,
			(int) beardOutPtr
		);

		long op = beardOutPtr;
		for (int iz = 0; iz < nz; iz++) {
			int vz = startZ + iz;
			for (int ix = 0; ix < nx; ix++) {
				int baseIdx = volume.indexUnchecked(startX + ix, startY, vz);
				for (int iy = 0; iy < ny; iy++) {
					float v = MemoryUtil.memGetFloat(op);
					op += 4L;
					if (v != 0.0F) {
						outputBuffer.set(baseIdx + iy, v);
					}
				}
			}
		}
		return true;
	}

	public static boolean beardifierVolumeLinear(
		final long rigidsPtr, final int rigidCount,
		final long junctionsPtr, final int junctionCount,
		final long kernelPtr,
		final int minBlockX, final int minBlockY, final int minBlockZ,
		final int nx, final int ny, final int nz,
		final long outPtr
	) {
		if (!MemoryUtil.LINEAR || !ready() || kernelPtr == MemoryUtil.NULL || outPtr == MemoryUtil.NULL) {
			return false;
		}
		if (nx <= 0 || ny <= 0 || nz <= 0) {
			return true;
		}
		jsBeardifierVolume(
			rigidCount > 0 ? (int) rigidsPtr : 0, rigidCount,
			junctionCount > 0 ? (int) junctionsPtr : 0, junctionCount,
			(int) kernelPtr,
			minBlockX, minBlockY, minBlockZ,
			nx, ny, nz,
			(int) outPtr
		);
		return true;
	}

	// ---- Kernel 4: simd_biome_fiddle_batch ----

	public static boolean biomeFiddleBatch(
		final long seed,
		final int[] coordsXyz,
		final int count,
		final int[] outQuartXyz
	) {
		if (!MemoryUtil.LINEAR || !ready() || !hasBigInt() || coordsXyz == null || outQuartXyz == null) {
			return false;
		}
		if (count < MIN_BATCH_SIZE || coordsXyz.length < count * 3 || outQuartXyz.length < count * 3) {
			return false;
		}
		if (biomeArenaPtr == MemoryUtil.NULL || biomeArenaCap < count) {
			if (biomeArenaPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(biomeArenaPtr);
			}
			int cap = growCap(biomeArenaCap, count, 256);
			long halfBytes = align16((long) cap * 12L);
			biomeArenaPtr = MemoryUtil.nmemAlloc(halfBytes * 2L);
			biomeArenaCap = cap;
		}

		long coordsPtr = biomeArenaPtr;
		long outPtr = biomeArenaPtr + align16((long) biomeArenaCap * 12L);
		int ints = count * 3;
		long cp = coordsPtr;
		for (int i = 0; i < ints; i++) {
			MemoryUtil.memPutInt(cp, coordsXyz[i]);
			cp += 4L;
		}

		jsBiomeFiddleBatch((int) seed, (int) (seed >>> 32), (int) coordsPtr, count, (int) outPtr);

		long op = outPtr;
		for (int i = 0; i < ints; i++) {
			outQuartXyz[i] = MemoryUtil.memGetInt(op);
			op += 4L;
		}
		return true;
	}

	public static boolean biomeFiddleBatchLinear(
		final long seed,
		final long coordsPtr,
		final int count,
		final long outQuartPtr
	) {
		if (!MemoryUtil.LINEAR || !ready() || !hasBigInt()
			|| coordsPtr == MemoryUtil.NULL || outQuartPtr == MemoryUtil.NULL) {
			return false;
		}
		if (count <= 0) {
			return true;
		}
		jsBiomeFiddleBatch((int) seed, (int) (seed >>> 32), (int) coordsPtr, count, (int) outQuartPtr);
		return true;
	}

	// ---- Kernel 5: simd_cull_aabb_frustum_fog ----

	private static void ensureCullArena(final int count) {
		if (cullPassPlanesPtr == MemoryUtil.NULL) {
			long p = MemoryUtil.nmemAlloc(96L);
			for (int plane = 0; plane < 6; plane++) {
				long off = p + (long) plane * 16L;
				MemoryUtil.memPutFloat(off, 0.0F);
				MemoryUtil.memPutFloat(off + 4L, 0.0F);
				MemoryUtil.memPutFloat(off + 8L, 0.0F);
				MemoryUtil.memPutFloat(off + 12L, 1.0F);
			}
			cullPassPlanesPtr = p;
		}
		if (cullPlanesPtr == MemoryUtil.NULL) {
			cullPlanesPtr = MemoryUtil.nmemAlloc(96L);
		}
		if (cullBoxesPtr == MemoryUtil.NULL || cullBoxesCap < count) {
			if (cullBoxesPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(cullBoxesPtr);
			}
			int cap = growCap(cullBoxesCap, count, 512);
			long stride = align16((long) cap * 4L);
			cullBoxesPtr = MemoryUtil.nmemAlloc(stride * 6L);
			cullBoxesCap = cap;
		}
		int words = (count + 31) >>> 5;
		if (cullMaskPtr == MemoryUtil.NULL || cullMaskWordsCap < words) {
			if (cullMaskPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(cullMaskPtr);
			}
			int cap = growCap(cullMaskWordsCap, words, 64);
			cullMaskPtr = MemoryUtil.nmemAlloc((long) cap * 4L);
			cullMaskWordsCap = cap;
		}
	}

	public static boolean isMaskBitVisible(final long maskPtr, final int index) {
		int word = MemoryUtil.memGetInt(maskPtr + ((long) (index >>> 5) * 4L));
		return ((word >>> (index & 31)) & 1) != 0;
	}

	public static boolean cullAabbsFrustumFog(
		final float[] planes24,
		final float camX, final float camY, final float camZ,
		final float sphRadSq, final float cylRadSq,
		final float[] minX, final float[] minY, final float[] minZ,
		final float[] maxX, final float[] maxY, final float[] maxZ,
		final int count,
		final int[] outVisibleMask
	) {
		if (!MemoryUtil.LINEAR || !ready() || count < MIN_BATCH_SIZE || outVisibleMask == null) {
			return false;
		}
		int words = (count + 31) >>> 5;
		if (minX.length < count || minY.length < count || minZ.length < count
			|| maxX.length < count || maxY.length < count || maxZ.length < count
			|| outVisibleMask.length < words) {
			return false;
		}
		ensureCullArena(count);
		long planesAddr;
		if (planes24 != null && planes24.length >= 24) {
			long pp = cullPlanesPtr;
			for (int i = 0; i < 24; i++) {
				MemoryUtil.memPutFloat(pp, planes24[i]);
				pp += 4L;
			}
			planesAddr = cullPlanesPtr;
		} else {
			planesAddr = cullPassPlanesPtr;
		}

		long stride = align16((long) cullBoxesCap * 4L);
		long minXPtr = cullBoxesPtr;
		long minYPtr = minXPtr + stride;
		long minZPtr = minYPtr + stride;
		long maxXPtr = minZPtr + stride;
		long maxYPtr = maxXPtr + stride;
		long maxZPtr = maxYPtr + stride;
		for (int i = 0; i < count; i++) {
			long off = (long) i * 4L;
			MemoryUtil.memPutFloat(minXPtr + off, minX[i]);
			MemoryUtil.memPutFloat(minYPtr + off, minY[i]);
			MemoryUtil.memPutFloat(minZPtr + off, minZ[i]);
			MemoryUtil.memPutFloat(maxXPtr + off, maxX[i]);
			MemoryUtil.memPutFloat(maxYPtr + off, maxY[i]);
			MemoryUtil.memPutFloat(maxZPtr + off, maxZ[i]);
		}

		jsCullAabbFrustumFog(
			(int) planesAddr,
			camX, camY, camZ,
			sphRadSq, cylRadSq,
			(int) minXPtr, (int) minYPtr, (int) minZPtr,
			(int) maxXPtr, (int) maxYPtr, (int) maxZPtr,
			count, (int) cullMaskPtr
		);

		for (int w = 0; w < words; w++) {
			outVisibleMask[w] = MemoryUtil.memGetInt(cullMaskPtr + (long) w * 4L);
		}
		return true;
	}

	public static boolean cullAabbsFrustumFogLinear(
		final long planes24Ptr,
		final float camX, final float camY, final float camZ,
		final float sphRadSq, final float cylRadSq,
		final long minXPtr, final long minYPtr, final long minZPtr,
		final long maxXPtr, final long maxYPtr, final long maxZPtr,
		final int count,
		final long outVisibleMaskPtr
	) {
		if (!MemoryUtil.LINEAR || !ready()
			|| planes24Ptr == MemoryUtil.NULL || outVisibleMaskPtr == MemoryUtil.NULL
			|| minXPtr == MemoryUtil.NULL || minYPtr == MemoryUtil.NULL || minZPtr == MemoryUtil.NULL
			|| maxXPtr == MemoryUtil.NULL || maxYPtr == MemoryUtil.NULL || maxZPtr == MemoryUtil.NULL) {
			return false;
		}
		if (count <= 0) {
			return true;
		}
		jsCullAabbFrustumFog(
			(int) planes24Ptr,
			camX, camY, camZ,
			sphRadSq, cylRadSq,
			(int) minXPtr, (int) minYPtr, (int) minZPtr,
			(int) maxXPtr, (int) maxYPtr, (int) maxZPtr,
			count, (int) outVisibleMaskPtr
		);
		return true;
	}

	// ---- Kernel 6: simd_sort_translucent_quads ----

	private static void ensureSortScratch(final int quadCount) {
		if (sortScratchPtr == MemoryUtil.NULL || sortScratchCap < quadCount) {
			if (sortScratchPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(sortScratchPtr);
			}
			int cap = growCap(sortScratchCap, quadCount, 256);
			long distBytes = align16((long) cap * 4L);
			long orderBytes = align16((long) cap * 4L);
			long idxBytes = align16((long) cap * 24L);
			sortScratchPtr = MemoryUtil.nmemAlloc(distBytes + orderBytes + idxBytes);
			sortScratchCap = cap;
		}
	}

	private static void ensureSortCentroidsVerts(final int quadCount) {
		if (sortCentroidsVertsPtr == MemoryUtil.NULL || sortCentroidsCap < quadCount) {
			if (sortCentroidsVertsPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(sortCentroidsVertsPtr);
			}
			int cap = growCap(sortCentroidsCap, quadCount, 256);
			sortCentroidsVertsPtr = MemoryUtil.nmemAlloc((long) cap * 112L);
			sortCentroidsCap = cap;
		}
	}

	public static int @Nullable [] sortTranslucentQuadsOrder(
		final ByteBuffer vertexBuffer,
		final int vertexCount,
		final VertexFormat format,
		final float camX, final float camY, final float camZ
	) {
		int quadCount = vertexCount >>> 2;
		if (quadCount < MIN_BATCH_SIZE || !MemoryUtil.LINEAR || !ready() || vertexBuffer == null || format == null) {
			return null;
		}
		if (format.getVertexSize() != 28) {
			return null;
		}
		VertexFormatElement posElem = format.getElement("Position");
		if (posElem == null || posElem.offset() != 0) {
			return null;
		}
		long vertsAddr = MemoryUtil.memLinearAddress(vertexBuffer);
		if (vertsAddr < 0L || vertexBuffer.remaining() < quadCount * 112) {
			return null;
		}
		ensureSortScratch(quadCount);
		long distBytes = align16((long) sortScratchCap * 4L);
		long orderBytes = align16((long) sortScratchCap * 4L);
		long distPtr = sortScratchPtr;
		long orderPtr = distPtr + distBytes;
		long idxPtr = orderPtr + orderBytes;

		jsSortTranslucentQuads(
			(int) vertsAddr, quadCount,
			camX, camY, camZ,
			(int) distPtr, (int) orderPtr, (int) idxPtr
		);

		int[] order = new int[quadCount];
		long p = orderPtr;
		for (int i = 0; i < quadCount; i++) {
			order[i] = MemoryUtil.memGetInt(p);
			p += 4L;
		}
		return order;
	}

	public static int @Nullable [] sortCentroidsOrder(
		final CompactVectorArray centroids,
		final float camX, final float camY, final float camZ
	) {
		if (centroids == null) {
			return null;
		}
		int quadCount = centroids.size();
		if (quadCount < MIN_BATCH_SIZE || !MemoryUtil.LINEAR || !ready()) {
			return null;
		}
		ensureSortScratch(quadCount);
		ensureSortCentroidsVerts(quadCount);

		long vp = sortCentroidsVertsPtr;
		for (int q = 0; q < quadCount; q++) {
			float cx = centroids.getX(q);
			float cy = centroids.getY(q);
			float cz = centroids.getZ(q);
			MemoryUtil.memPutFloat(vp, cx);
			MemoryUtil.memPutFloat(vp + 4L, cy);
			MemoryUtil.memPutFloat(vp + 8L, cz);
			MemoryUtil.memPutFloat(vp + 56L, cx);
			MemoryUtil.memPutFloat(vp + 60L, cy);
			MemoryUtil.memPutFloat(vp + 64L, cz);
			vp += 112L;
		}

		long distBytes = align16((long) sortScratchCap * 4L);
		long orderBytes = align16((long) sortScratchCap * 4L);
		long distPtr = sortScratchPtr;
		long orderPtr = distPtr + distBytes;
		long idxPtr = orderPtr + orderBytes;

		jsSortTranslucentQuads(
			(int) sortCentroidsVertsPtr, quadCount,
			camX, camY, camZ,
			(int) distPtr, (int) orderPtr, (int) idxPtr
		);

		int[] order = new int[quadCount];
		long p = orderPtr;
		for (int i = 0; i < quadCount; i++) {
			order[i] = MemoryUtil.memGetInt(p);
			p += 4L;
		}
		return order;
	}

	public static ByteBufferBuilder.@Nullable Result buildSortedIndexBuffer(
		final MeshData.SortState state,
		final ByteBufferBuilder target,
		final float camX, final float camY, final float camZ
	) {
		if (state == null || target == null) {
			return null;
		}
		CompactVectorArray centroids = state.centroids();
		int quadCount = centroids.size();
		if (quadCount < MIN_BATCH_SIZE || !MemoryUtil.LINEAR || !ready()) {
			return null;
		}
		ensureSortScratch(quadCount);
		ensureSortCentroidsVerts(quadCount);

		long vp = sortCentroidsVertsPtr;
		for (int q = 0; q < quadCount; q++) {
			float cx = centroids.getX(q);
			float cy = centroids.getY(q);
			float cz = centroids.getZ(q);
			MemoryUtil.memPutFloat(vp, cx);
			MemoryUtil.memPutFloat(vp + 4L, cy);
			MemoryUtil.memPutFloat(vp + 8L, cz);
			MemoryUtil.memPutFloat(vp + 56L, cx);
			MemoryUtil.memPutFloat(vp + 60L, cy);
			MemoryUtil.memPutFloat(vp + 64L, cz);
			vp += 112L;
		}

		long distBytes = align16((long) sortScratchCap * 4L);
		long orderBytes = align16((long) sortScratchCap * 4L);
		long distPtr = sortScratchPtr;
		long orderPtr = distPtr + distBytes;
		long scratchIdxPtr = orderPtr + orderBytes;

		IndexType indexType = state.indexType();
		if (indexType == IndexType.INT) {
			long outPtr = target.reserve(quadCount * 6 * 4);
			jsSortTranslucentQuads(
				(int) sortCentroidsVertsPtr, quadCount,
				camX, camY, camZ,
				(int) distPtr, (int) orderPtr, (int) outPtr
			);
			return target.build();
		} else {
			jsSortTranslucentQuads(
				(int) sortCentroidsVertsPtr, quadCount,
				camX, camY, camZ,
				(int) distPtr, (int) orderPtr, (int) scratchIdxPtr
			);
			long outPtr = target.reserve(quadCount * 6 * 2);
			long op = orderPtr;
			long dst = outPtr;
			for (int i = 0; i < quadCount; i++) {
				int base = MemoryUtil.memGetInt(op) << 2;
				op += 4L;
				MemoryUtil.memPutShort(dst, (short) base);
				MemoryUtil.memPutShort(dst + 2L, (short) (base + 1));
				MemoryUtil.memPutShort(dst + 4L, (short) (base + 2));
				MemoryUtil.memPutShort(dst + 6L, (short) (base + 2));
				MemoryUtil.memPutShort(dst + 8L, (short) (base + 3));
				MemoryUtil.memPutShort(dst + 10L, (short) base);
				dst += 12L;
			}
			return target.build();
		}
	}

	public static boolean sortTranslucentQuadsLinear(
		final long verts28BPtr,
		final int quadCount,
		final float camX, final float camY, final float camZ,
		final long outIndicesPtr
	) {
		if (!MemoryUtil.LINEAR || !ready() || verts28BPtr == MemoryUtil.NULL || outIndicesPtr == MemoryUtil.NULL) {
			return false;
		}
		if (quadCount <= 0) {
			return true;
		}
		ensureSortScratch(quadCount);
		long distBytes = align16((long) sortScratchCap * 4L);
		long distPtr = sortScratchPtr;
		long orderPtr = distPtr + distBytes;
		jsSortTranslucentQuads(
			(int) verts28BPtr, quadCount,
			camX, camY, camZ,
			(int) distPtr, (int) orderPtr, (int) outIndicesPtr
		);
		return true;
	}

	public static VertexSorting createDistanceSorting(final float camX, final float camY, final float camZ) {
		if (MemoryUtil.LINEAR && ready()) {
			return new DistanceVertexSorting(camX, camY, camZ);
		}
		return VertexSorting.byDistance(camX, camY, camZ);
	}

	public static final class DistanceVertexSorting implements VertexSorting {
		public final float camX;
		public final float camY;
		public final float camZ;

		public DistanceVertexSorting(final float camX, final float camY, final float camZ) {
			this.camX = camX;
			this.camY = camY;
			this.camZ = camZ;
		}

		@Override
		public int[] sort(final CompactVectorArray points) {
			int[] simd = sortCentroidsOrder(points, this.camX, this.camY, this.camZ);
			if (simd != null) {
				return simd;
			}
			return VertexSorting.byDistance(this.camX, this.camY, this.camZ).sort(points);
		}
	}

	// ---- Kernel 7: simd_mat4_mul_batch ----

	public static boolean mat4MulBatch(final float[] a, final float[] b, final float[] out, final int count) {
		if (!MemoryUtil.LINEAR || !ready() || a == null || b == null || out == null || count < MIN_BATCH_SIZE) {
			return false;
		}
		int floats = count * 16;
		if (a.length < floats || b.length < floats || out.length < floats) {
			return false;
		}
		if (mat4ArenaPtr == MemoryUtil.NULL || mat4ArenaCap < count) {
			if (mat4ArenaPtr != MemoryUtil.NULL) {
				MemoryUtil.nmemFree(mat4ArenaPtr);
			}
			int cap = growCap(mat4ArenaCap, count, 64);
			long segBytes = align16((long) cap * 64L);
			mat4ArenaPtr = MemoryUtil.nmemAlloc(segBytes * 3L);
			mat4ArenaCap = cap;
		}
		long segBytes = align16((long) mat4ArenaCap * 64L);
		long aPtr = mat4ArenaPtr;
		long bPtr = aPtr + segBytes;
		long oPtr = bPtr + segBytes;
		for (int i = 0; i < floats; i++) {
			long off = (long) i * 4L;
			MemoryUtil.memPutFloat(aPtr + off, a[i]);
			MemoryUtil.memPutFloat(bPtr + off, b[i]);
		}
		jsMat4MulBatch((int) aPtr, (int) bPtr, (int) oPtr, count);
		for (int i = 0; i < floats; i++) {
			out[i] = MemoryUtil.memGetFloat(oPtr + (long) i * 4L);
		}
		return true;
	}

	public static boolean mat4MulBatchLinear(final long aPtr, final long bPtr, final long outPtr, final int count) {
		if (!MemoryUtil.LINEAR || !ready()
			|| aPtr == MemoryUtil.NULL || bPtr == MemoryUtil.NULL || outPtr == MemoryUtil.NULL) {
			return false;
		}
		if (count <= 0) {
			return true;
		}
		jsMat4MulBatch((int) aPtr, (int) bPtr, (int) outPtr, count);
		return true;
	}

	// ---- Strict ES5 @JSBody bindings to (globalThis.__mcWebSimd || globalThis.__twitSimd).exports ----

	@JSBody(script = "var G = typeof globalThis !== 'undefined' ? globalThis : self; var S = G && (G.__mcWebSimd || G.__twitSimd); return (S && typeof S.ensure === 'function' && S.ensure()) ? 1 : 0;")
	private static native int ensureJs();

	@JSBody(params = {"ptr"}, script = "var G = typeof globalThis !== 'undefined' ? globalThis : self; var S = G && (G.__mcWebSimd || G.__twitSimd); if (S && typeof S.setScratch === 'function') { S.setScratch(ptr >>> 0); }")
	private static native void setScratchJs(int ptr);

	@JSBody(script = "return typeof BigInt === 'function' ? 1 : 0;")
	private static native int hasBigIntJs();

	@JSBody(
		params = {"permPtr", "xo", "yo", "zo", "x0", "y0", "z0", "dx", "dy", "dz", "nx", "ny", "nz", "freq", "amp", "outPtr", "accumulate"},
		script = "var G = typeof globalThis !== 'undefined' ? globalThis : self; var S = G.__mcWebSimd || G.__twitSimd; S.exports.simd_improved_noise_batch(permPtr >>> 0, xo, yo, zo, x0, y0, z0, dx, dy, dz, nx | 0, ny | 0, nz | 0, freq, amp, outPtr >>> 0, accumulate | 0);"
	)
	private static native void jsImprovedNoiseBatch(
		int permPtr,
		double xo, double yo, double zo,
		double x0, double y0, double z0,
		double dx, double dy, double dz,
		int nx, int ny, int nz,
		double freq, double amp,
		int outPtr, int accumulate
	);

	@JSBody(
		params = {"cellPtr", "cellCount", "queryPtr", "queryCount", "outIdxPtr", "outDistPtr"},
		script = "var G = typeof globalThis !== 'undefined' ? globalThis : self; var S = G.__mcWebSimd || G.__twitSimd; S.exports.simd_aquifer_voronoi_batch(cellPtr >>> 0, cellCount | 0, queryPtr >>> 0, queryCount | 0, outIdxPtr >>> 0, outDistPtr >>> 0);"
	)
	private static native void jsAquiferVoronoiBatch(
		int cellPtr, int cellCount,
		int queryPtr, int queryCount,
		int outIdxPtr, int outDistPtr
	);

	@JSBody(
		params = {"rigidsPtr", "rigidCount", "junctionsPtr", "junctionCount", "kernelPtr", "minX", "minY", "minZ", "nx", "ny", "nz", "outPtr"},
		script = "var G = typeof globalThis !== 'undefined' ? globalThis : self; var S = G.__mcWebSimd || G.__twitSimd; S.exports.simd_beardifier_volume(rigidsPtr >>> 0, rigidCount | 0, junctionsPtr >>> 0, junctionCount | 0, kernelPtr >>> 0, minX | 0, minY | 0, minZ | 0, nx | 0, ny | 0, nz | 0, outPtr >>> 0);"
	)
	private static native void jsBeardifierVolume(
		int rigidsPtr, int rigidCount,
		int junctionsPtr, int junctionCount,
		int kernelPtr,
		int minX, int minY, int minZ,
		int nx, int ny, int nz,
		int outPtr
	);

	@JSBody(
		params = {"seedLo", "seedHi", "coordsPtr", "count", "outQuartPtr"},
		script = "var G = typeof globalThis !== 'undefined' ? globalThis : self; var S = G.__mcWebSimd || G.__twitSimd; var seed = (BigInt(seedHi | 0) << BigInt(32)) | BigInt(seedLo >>> 0); S.exports.simd_biome_fiddle_batch(seed, coordsPtr >>> 0, count | 0, outQuartPtr >>> 0);"
	)
	private static native void jsBiomeFiddleBatch(
		int seedLo, int seedHi,
		int coordsPtr, int count, int outQuartPtr
	);

	@JSBody(
		params = {"planesPtr", "camX", "camY", "camZ", "sphRadSq", "cylRadSq", "minXPtr", "minYPtr", "minZPtr", "maxXPtr", "maxYPtr", "maxZPtr", "count", "outMaskPtr"},
		script = "var G = typeof globalThis !== 'undefined' ? globalThis : self; var S = G.__mcWebSimd || G.__twitSimd; S.exports.simd_cull_aabb_frustum_fog(planesPtr >>> 0, camX, camY, camZ, sphRadSq, cylRadSq, minXPtr >>> 0, minYPtr >>> 0, minZPtr >>> 0, maxXPtr >>> 0, maxYPtr >>> 0, maxZPtr >>> 0, count | 0, outMaskPtr >>> 0);"
	)
	private static native void jsCullAabbFrustumFog(
		int planesPtr,
		float camX, float camY, float camZ,
		float sphRadSq, float cylRadSq,
		int minXPtr, int minYPtr, int minZPtr,
		int maxXPtr, int maxYPtr, int maxZPtr,
		int count, int outMaskPtr
	);

	@JSBody(
		params = {"vertsPtr", "quadCount", "camX", "camY", "camZ", "scratchDistPtr", "scratchOrderPtr", "outIndicesPtr"},
		script = "var G = typeof globalThis !== 'undefined' ? globalThis : self; var S = G.__mcWebSimd || G.__twitSimd; S.exports.simd_sort_translucent_quads(vertsPtr >>> 0, quadCount | 0, camX, camY, camZ, scratchDistPtr >>> 0, scratchOrderPtr >>> 0, outIndicesPtr >>> 0);"
	)
	private static native void jsSortTranslucentQuads(
		int vertsPtr, int quadCount,
		float camX, float camY, float camZ,
		int scratchDistPtr, int scratchOrderPtr, int outIndicesPtr
	);

	@JSBody(
		params = {"aPtr", "bPtr", "outPtr", "count"},
		script = "var G = typeof globalThis !== 'undefined' ? globalThis : self; var S = G.__mcWebSimd || G.__twitSimd; S.exports.simd_mat4_mul_batch(aPtr >>> 0, bPtr >>> 0, outPtr >>> 0, count | 0);"
	)
	private static native void jsMat4MulBatch(int aPtr, int bPtr, int outPtr, int count);
}
