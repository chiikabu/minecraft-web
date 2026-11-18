// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import com.mojang.serialization.DynamicOps;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.Web263GenOffload;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebGenLane implements Web263GenOffload.Handler {
	private static final EnumSet<Heightmap.Types> TERRAIN_HEIGHTMAPS = EnumSet.of(
		Heightmap.Types.MOTION_BLOCKING,
		Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
		Heightmap.Types.OCEAN_FLOOR,
		Heightmap.Types.WORLD_SURFACE
	);
	private static WebGenLane instance;

	private final List<GenWorker> workers = new ArrayList<>();
	private final Map<Integer, Pending> pending = new HashMap<>();
	private final Map<Integer, GenWorker> owed = new HashMap<>();
	private final ArrayDeque<byte[]> results = new ArrayDeque<>();
	private final Map<ResourceKey<Level>, byte[]> dimensionInits = new HashMap<>();
	private final Set<ResourceKey<Level>> dimensionsDeclined = new HashSet<>();
	private int nextId = 1;
	private boolean disabled;
	private int completed;
	private int fallbacks;
	private int blockedFailovers;
	private long lastReportMillis;

	private WebGenLane() {
	}

	public static void start() {
		if (instance != null && !instance.disabled) {
			return;
		}
		Web263GenOffload.debugState = () -> stats() + " deferred=" + org.teavm.classlib.impl.Trampoline.hasDeferred();
		int n = requestedWorkers();
		if (n <= 0) {
			System.out.println("[26.3-server] gen lane off (genworkers=" + n + ")");
			return;
		}
		WebGenLane lane = new WebGenLane();
		for (int i = 0; i < n; i++) {
			GenWorker w = lane.new GenWorker(i);
			try {
				w.spawn();
			} catch (Throwable e) {
				System.out.println("[26.3-server] gen worker " + i + " failed to spawn: " + e);
				break;
			}
			lane.workers.add(w);
		}
		if (lane.workers.isEmpty()) {
			return;
		}
		if (instance != null) {
			instance.disable();
		}
		instance = lane;
		Web263GenOffload.handler = lane;
		System.out.println("[26.3-server] gen lane: " + lane.workers.size() + " worker(s) spawned (shared Wasm module)");
	}

	public static void stop() {
		WebGenLane lane = instance;
		if (lane != null && !lane.disabled) {
			System.out.println("[26.3-server] gen lane stopping: " + stats());
			lane.disable();
		}
	}

	private static int requestedWorkers() {
		int override = WebWorkerTransport.pageGenWorkersRequested();
		if (override >= 0) {
			return Math.min(override, 8);
		}
		return defaultWorkers();
	}

	@JSBody(script = "var n = typeof navigator !== 'undefined' ? navigator : {};"
		+ "var cores = n.hardwareConcurrency || 2, memory = n.deviceMemory || 8;"
		+ "if (cores <= 2) return 0;"
		+ "return memory <= 4 || cores <= 4 ? 1 : 2;")
	private static native int defaultWorkers();

	public static void drainResults() {
		WebGenLane lane = instance;
		if (lane != null) {
			lane.drain();
		}
	}

	public static String stats() {
		WebGenLane lane = instance;
		if (lane == null) {
			return "off";
		}
		return (lane.disabled ? "DISABLED " : "") + "workers=" + lane.workers.size() + " done=" + lane.completed
			+ " inflight=" + lane.pending.size() + " fallbacks=" + lane.fallbacks + " blocked=" + lane.blockedFailovers;
	}

	@Override
	public CompletableFuture<ChunkAccess> offload(
		final ChunkStatus stage,
		final WorldGenContext context,
		final ChunkStep step,
		final StaticCache2D<GenerationChunkHolder> cache,
		final WorldGenRegion region,
		final ChunkAccess chunk
	) {
		if (disabled || (stage != ChunkStatus.BIOMES && stage != ChunkStatus.TERRAIN)) {
			return null;
		}
		ServerLevel level = context.level();
		if (!(chunk instanceof ProtoChunk) || chunk.getBelowZeroRetrogen() != null || chunk.isUpgrading()
			|| Blender.of(region) != Blender.empty()
			|| !(context.generator() instanceof NoiseBasedChunkGenerator)
			|| dimensionsDeclined.contains(level.dimension())) {
			return null;
		}
		byte[] init = dimensionInits.get(level.dimension());
		if (init == null) {
			try {
				init = encodeInit(level);
			} catch (Throwable e) {
				System.out.println("[26.3-server] gen lane: cannot describe " + level.dimension().identifier() + " (" + e + "); inline");
				dimensionsDeclined.add(level.dimension());
				return null;
			}
			dimensionInits.put(level.dimension(), init);
		}
		GenWorker worker = leastLoaded();
		if (worker == null) {
			return null;
		}
		int id = nextId++;
		FriendlyByteBuf buf = WebGenCodec.buf();
		buf.writeByte(WebGenCodec.JOB);
		buf.writeInt(id);
		buf.writeUtf(level.dimension().identifier().toString());
		ChunkPos pos = chunk.getPos();
		buf.writeInt(pos.x());
		buf.writeInt(pos.z());
		if (stage == ChunkStatus.BIOMES) {
			buf.writeByte(WebGenCodec.KIND_BIOMES);
		} else {
			buf.writeByte(WebGenCodec.KIND_TERRAIN);
			Beardifier beardifier = Beardifier.forStructuresInChunk(level.structureManager().forWorldGenRegion(region), pos);
			WebGenCodec.writeBeardifier(buf, beardifier);
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					WebGenCodec.writeBiomes(buf, region.getChunk(pos.x() + dx, pos.z() + dz));
				}
			}
		}
		byte[] job = WebGenCodec.bytes(buf);
		Pending p = new Pending(id, stage, context, step, cache, chunk, worker);
		pending.put(id, p);
		owed.put(id, worker);
		worker.inflight++;
		if (!worker.initialized.contains(level.dimension())) {
			worker.initialized.add(level.dimension());
			worker.post(init);
		}
		worker.post(job);
		return p.future;
	}

	private GenWorker leastLoaded() {
		GenWorker best = null;
		for (GenWorker w : workers) {
			if (w.ready && (best == null || w.inflight < best.inflight)) {
				best = w;
			}
		}
		return best != null && best.inflight < 6 ? best : null;
	}

	private static byte[] encodeInit(final ServerLevel level) {
		WorldDataConfiguration config = level.getServer().getWorldData().getDataConfiguration();
		for (String pack : config.dataPacks().getEnabled()) {
			if (pack.startsWith("file/")) {
				throw new IllegalStateException("world uses file data pack " + pack);
			}
		}
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
		Tag generatorTag = ChunkGenerator.CODEC.encodeStart(ops, generator).getOrThrow();
		Tag configTag = WorldDataConfiguration.CODEC.encodeStart(NbtOps.INSTANCE, config).getOrThrow();
		FriendlyByteBuf buf = WebGenCodec.buf();
		buf.writeByte(WebGenCodec.INIT);
		buf.writeVarInt(WebGenCodec.PROTOCOL);
		buf.writeUtf(level.dimension().identifier().toString());
		buf.writeLong(level.getSeed());
		buf.writeInt(level.getMinY());
		buf.writeInt(level.getHeight());
		buf.writeUtf(WebGenCodec.registryFingerprint(WebGenCodec.biomes(level.registryAccess())));
		buf.writeNbt(configTag);
		buf.writeNbt(generatorTag);
		return WebGenCodec.bytes(buf);
	}

	private void drain() {
		byte[] msg;
		while ((msg = results.poll()) != null) {
			try {
				handleResult(msg);
			} catch (Throwable e) {
				System.out.println("[26.3-server] gen lane: bad result (" + e + "); disabling");
				disable();
			}
		}
		if (completed > 0 && System.currentTimeMillis() - lastReportMillis > 10_000L) {
			lastReportMillis = System.currentTimeMillis();
			System.out.println("[26.3-server] gen lane: " + stats());
		}
	}

	private void handleResult(final byte[] msg) {
		FriendlyByteBuf buf = WebGenCodec.wrap(msg);
		byte tag = buf.readByte();
		if (tag == WebGenCodec.READY) {
			int index = buf.readVarInt();
			System.out.println("[26.3-server] gen worker " + index + " ready");
			for (GenWorker w : workers) {
				if (w.index == index) {
					w.ready = true;
				}
			}
			return;
		}
		if (tag != WebGenCodec.RESULT) {
			throw new IllegalStateException("unknown gen message " + tag);
		}
		int id = buf.readInt();
		boolean ok = buf.readBoolean();
		GenWorker owner = owed.remove(id);
		if (owner != null) {
			owner.inflight--;
		}
		Pending p = pending.remove(id);
		if (p == null) {
			return;
		}
		if (!ok) {
			String reason = buf.readUtf();
			System.out.println("[26.3-server] gen worker declined job " + id + " (" + reason + "); running inline");
			fallbacks++;
			runInline(p);
			return;
		}
		if (p.stage == ChunkStatus.BIOMES) {
			WebGenCodec.readBiomes(buf, p.chunk);
		} else {
			WebGenCodec.readTerrain(buf, p.chunk);
			Heightmap.primeHeightmaps(p.chunk, TERRAIN_HEIGHTMAPS);
		}
		completed++;
		p.future.complete(p.chunk);
	}

	private void runInline(final Pending p) {
		Web263GenOffload.Handler saved = Web263GenOffload.handler;
		Web263GenOffload.handler = null;
		try {
			CompletableFuture<ChunkAccess> inline = p.stage == ChunkStatus.BIOMES
				? ChunkStatusTasks.generateBiomes(p.context, p.step, p.cache, p.chunk)
				: ChunkStatusTasks.buildTerrain(p.context, p.step, p.cache, p.chunk);
			inline.whenComplete((c, t) -> {
				if (t != null) {
					p.future.completeExceptionally(t);
				} else {
					p.future.complete(c);
				}
			});
		} catch (Throwable t) {
			p.future.completeExceptionally(t);
		} finally {
			Web263GenOffload.handler = saved;
		}
	}

	@Override
	public void onBlockingWait() {
		if (pending.isEmpty()) {
			return;
		}
		List<Pending> orphans = new ArrayList<>(pending.values());
		pending.clear();
		blockedFailovers += orphans.size();
		for (Pending p : orphans) {
			fallbacks++;
			runInline(p);
		}
	}

	private void disable() {
		if (disabled) {
			return;
		}
		disabled = true;
		List<Pending> orphans = new ArrayList<>(pending.values());
		pending.clear();
		for (GenWorker w : workers) {
			w.terminate();
		}
		System.out.println("[26.3-server] gen lane disabled; " + orphans.size() + " stage(s) run inline");
		for (Pending p : orphans) {
			fallbacks++;
			runInline(p);
		}
	}

	private void onWorkerFailed(final GenWorker w, final String reason) {
		System.out.println("[26.3-server] gen worker " + w.index + " failed: " + reason);
		disable();
	}

	private record Pending(
		int id, ChunkStatus stage, WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache,
		ChunkAccess chunk, GenWorker worker, CompletableFuture<ChunkAccess> future
	) {
		Pending(int id, ChunkStatus stage, WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache,
			ChunkAccess chunk, GenWorker worker) {
			this(id, stage, context, step, cache, chunk, worker, new CompletableFuture<>());
		}
	}

	private final class GenWorker {
		final int index;
		final Set<ResourceKey<Level>> initialized = new HashSet<>();
		JSObject handle;
		int inflight;
		boolean ready;

		GenWorker(final int index) {
			this.index = index;
		}

		void spawn() {
			handle = WebWorkerTransport.startGeneration();
			attach(handle, data -> {
				results.add(data.copyToJavaArray());
				WebServerMain.wake();
			}, reason -> onWorkerFailed(this, reason));
			if (!sendInit(handle, index)) {
				throw new IllegalStateException("compiled server module/URL unavailable for gen worker");
			}
		}

		void post(final byte[] bytes) {
			postBytes(handle, Int8Array.copyFromJavaArray(bytes));
		}

		void terminate() {
			if (handle != null) {
				WebWorkerTransport.terminate(handle);
				handle = null;
			}
		}
	}

	@JSFunctor
	private interface DataHandler extends JSObject {
		void data(Int8Array data);
	}

	@JSFunctor
	private interface FailHandler extends JSObject {
		void failed(String reason);
	}

	@JSBody(params = {"w", "index"}, script =
		"var gen = self.__minecraftGenModule && self.__minecraftGenRuntimeSource;"
		+ "var mod = gen ? self.__minecraftGenModule : (self.__minecraftServerModule || null),"
		+ "    rt = gen ? self.__minecraftGenRuntimeSource : (self.__minecraftServerRuntimeSource || null),"
		+ "    url = self.__minecraftServerModuleUrl || null;"
		+ "if (!(mod || url)) return false;"
		+ "w.postMessage({__minecraftGenInit: true, index: self.__minecraftGenIndex || null, assets: self.__minecraftGenAssets || null,"
		+ "  workerIndex: index, module: mod, runtime: rt, moduleUrl: url});"
		+ "return true;")
	private static native boolean sendInit(JSObject w, int index);

	@JSBody(params = {"w", "dat"}, script = "w.postMessage(dat.buffer, [dat.buffer]);")
	private static native void postBytes(JSObject w, Int8Array dat);

	@JSBody(params = {"w", "onData", "onFail"}, script =
		"w.onmessage = function(e) { if (e.data && e.data.__minecraftGenLog) { console.log(e.data.__minecraftGenLog); return; } onData(new Int8Array(e.data)); };"
		+ "w.onerror = function(e) { onFail(String((e && e.message) || e)); };"
		+ "w.onmessageerror = function() { onFail('messageerror'); };")
	private static native void attach(JSObject w, DataHandler onData, FailHandler onFail);
}
