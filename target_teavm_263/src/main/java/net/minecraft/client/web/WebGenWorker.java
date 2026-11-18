// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DynamicOps;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebGenWorker {
	private static final Map<String, Dimension> dimensions = new HashMap<>();
	private static int workerIndex;

	private WebGenWorker() {
	}

	@JSBody(script = "return !!self.__minecraftGenWorker;")
	public static native boolean isGenRole();

	@JSBody(script = "return self.__minecraftGenWorkerIndex | 0;")
	private static native int genIndex();

	public static void run() {
		workerIndex = genIndex();
		System.setProperty(Credits.open(16150, 30252, 50378, 23864, 65121, 56953, 20163, 9233, 4513, 32803, 63638, 55468, 13928, 63354, 22928, 47281, 34039, 2192, 25633, 23918, 62245, 16541), "true");
		System.setProperty("java.awt.headless", "true");
		SharedConstants.CHECK_DATA_FIXER_SCHEMA = false;
		SharedConstants.tryDetectVersion();
		long t = System.currentTimeMillis();
		Bootstrap.bootStrap();
		WebPackResources.preload();
		log("gen worker " + workerIndex + " bootstrapped in " + (System.currentTimeMillis() - t) + "ms");
		listen(data -> onMessage(data.copyToJavaArray()));
		FriendlyByteBuf ready = WebGenCodec.buf();
		ready.writeByte(WebGenCodec.READY);
		ready.writeVarInt(workerIndex);
		reply(WebGenCodec.bytes(ready));
	}

	public static void fail(final Throwable e) {
		log("gen worker " + workerIndex + " failed: " + e);
		throw new RuntimeException(e);
	}

	private static void onMessage(final byte[] msg) {
		FriendlyByteBuf buf = WebGenCodec.wrap(msg);
		byte tag = buf.readByte();
		try {
			if (tag == WebGenCodec.INIT) {
				init(buf);
			} else if (tag == WebGenCodec.JOB) {
				job(buf);
			} else {
				throw new IllegalStateException("unknown message " + tag);
			}
		} catch (Throwable e) {
			log("gen worker " + workerIndex + ": " + e);
			throw new RuntimeException(e);
		}
	}

	private static void init(final FriendlyByteBuf buf) throws Exception {
		int protocol = buf.readVarInt();
		if (protocol != WebGenCodec.PROTOCOL) {
			throw new IllegalStateException("protocol " + protocol);
		}
		String dimension = buf.readUtf();
		long seed = buf.readLong();
		int minY = buf.readInt();
		int height = buf.readInt();
		String fingerprint = buf.readUtf();
		Tag configTag = buf.readNbt();
		Tag generatorTag = buf.readNbt();
		long t = System.currentTimeMillis();
		WorldDataConfiguration config = WorldDataConfiguration.CODEC.parse(NbtOps.INSTANCE, configTag).getOrThrow();
		RegistryAccess.Frozen access = loadWorldgenRegistries(config);
		String mine = WebGenCodec.registryFingerprint(WebGenCodec.biomes(access));
		Dimension d = new Dimension();
		d.name = dimension;
		if (!mine.equals(fingerprint)) {
			d.declined = "registry fingerprint " + mine + " != server " + fingerprint;
			dimensions.put(dimension, d);
			log("gen worker " + workerIndex + ": " + d.declined);
			return;
		}
		DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, access);
		ChunkGenerator generator = ChunkGenerator.CODEC.parse(ops, generatorTag).getOrThrow();
		if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
			d.declined = "generator " + generator.getClass().getSimpleName();
			dimensions.put(dimension, d);
			return;
		}
		d.generator = noise;
		d.randomState = RandomState.create(access.lookupOrThrow(Registries.NOISE), seed, noise.generatorSettings().value());
		d.heights = LevelHeightAccessor.create(minY, height);
		d.containers = PalettedContainerFactory.create(access);
		d.biomeSeed = BiomeManager.obfuscateSeed(seed);
		dimensions.put(dimension, d);
		log("gen worker " + workerIndex + ": " + dimension + " ready in " + (System.currentTimeMillis() - t) + "ms");
	}

	private static RegistryAccess.Frozen loadWorldgenRegistries(final WorldDataConfiguration config) {
		PackRepository repository = ServerPacksSource.createVanillaTrustedRepository();
		WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(repository, config, false, false);
		Pair<WorldDataConfiguration, CloseableResourceManager> opened = packConfig.createResourceManager();
		CloseableResourceManager resources = opened.getSecond();
		LayeredRegistryAccess<RegistryLayer> initial = RegistryLayer.createRegistryAccess();
		List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(resources, initial.getLayer(RegistryLayer.STATIC));
		RegistryAccess.Frozen loadContext = initial.getAccessForLoading(RegistryLayer.WORLD);
		List<HolderLookup.RegistryLookup<?>> lookups = TagLoader.buildUpdatedLookups(loadContext, staticTags);
		RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, lookups, RegistryDataLoader.WORLD_REGISTRIES, Util.backgroundExecutor()).join();
		staticTags.forEach(Registry.PendingTags::apply);
		return initial.replaceFrom(RegistryLayer.WORLD, worldgen).compositeAccess();
	}

	private static void job(final FriendlyByteBuf buf) {
		int id = buf.readInt();
		String dimension = buf.readUtf();
		int x = buf.readInt();
		int z = buf.readInt();
		byte kind = buf.readByte();
		Dimension d = dimensions.get(dimension);
		FriendlyByteBuf out = WebGenCodec.buf();
		out.writeByte(WebGenCodec.RESULT);
		out.writeInt(id);
		if (d == null || d.declined != null) {
			out.writeBoolean(false);
			out.writeUtf(d == null ? "no INIT for " + dimension : d.declined);
			reply(WebGenCodec.bytes(out));
			return;
		}
		try {
			ChunkPos pos = new ChunkPos(x, z);
			ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, d.heights, d.containers, null);
			if (kind == WebGenCodec.KIND_BIOMES) {
				d.generator.createBiomes(d.randomState, Blender.empty(), null, chunk).join();
				out.writeBoolean(true);
				WebGenCodec.writeBiomes(out, chunk);
			} else {
				Beardifier beardifier = WebGenCodec.readBeardifier(buf);
				ChunkAccess[] around = new ChunkAccess[9];
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						ChunkAccess c = dx == 0 && dz == 0 ? chunk : new ProtoChunk(new ChunkPos(x + dx, z + dz), UpgradeData.EMPTY, d.heights, d.containers, null);
						WebGenCodec.readBiomes(buf, c);
						((ProtoChunk) c).setPersistedStatus(ChunkStatus.BIOMES);
						around[(dz + 1) * 3 + (dx + 1)] = c;
					}
				}
				BiomeManager biomes = new BiomeManager((qx, qy, qz) -> {
					int cx = QuartPos.toSection(qx) - x + 1;
					int cz = QuartPos.toSection(qz) - z + 1;
					if (cx < 0 || cx > 2 || cz < 0 || cz > 2) {
						return around[4].getNoiseBiome(qx, qy, qz);
					}
					return around[cz * 3 + cx].getNoiseBiome(qx, qy, qz);
				}, d.biomeSeed);
				biomes.enablePositionCache();
				Set<Holder<Biome>> possible = new ReferenceOpenHashSet<>();
				for (ChunkAccess c : around) {
					c.collectBiomesInPalette(possible);
				}
				d.generator.web263BuildTerrain(chunk, beardifier, d.randomState, biomes, possible);
				out.writeBoolean(true);
				WebGenCodec.writeTerrain(out, chunk);
			}
		} catch (Throwable e) {
			out = WebGenCodec.buf();
			out.writeByte(WebGenCodec.RESULT);
			out.writeInt(id);
			out.writeBoolean(false);
			out.writeUtf(String.valueOf(e));
			log("gen worker " + workerIndex + ": job " + id + " failed: " + e);
		}
		reply(WebGenCodec.bytes(out));
	}

	private static final class Dimension {
		String name;
		String declined;
		NoiseBasedChunkGenerator generator;
		RandomState randomState;
		LevelHeightAccessor heights;
		PalettedContainerFactory containers;
		long biomeSeed;
	}

	@JSFunctor
	private interface DataHandler extends JSObject {
		void data(Int8Array data);
	}

	@JSBody(params = {"onData"}, script = "self.onmessage = function(e) { onData(new Int8Array(e.data)); };")
	private static native void listen(DataHandler onData);

	private static void reply(final byte[] bytes) {
		post(Int8Array.copyFromJavaArray(bytes));
	}

	@JSBody(params = {"dat"}, script = "self.postMessage(dat.buffer, [dat.buffer]);")
	private static native void post(Int8Array dat);

	private static void log(final String line) {
		System.out.println("[26.3-gen] " + line);
		postLog("[26.3-gen] " + line);
	}

	@JSBody(params = {"line"}, script = "self.postMessage({__minecraftGenLog: line});")
	private static native void postLog(String line);
}
