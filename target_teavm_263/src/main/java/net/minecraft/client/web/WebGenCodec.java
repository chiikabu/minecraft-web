// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

final class WebGenCodec {
	static final int PROTOCOL = 1;
	static final byte INIT = 1;
	static final byte JOB = 2;
	static final byte READY = 3;
	static final byte RESULT = 4;

	static final byte KIND_BIOMES = 1;
	static final byte KIND_TERRAIN = 2;

	private WebGenCodec() {
	}

	static FriendlyByteBuf buf() {
		return new FriendlyByteBuf(Unpooled.buffer());
	}

	static FriendlyByteBuf wrap(final byte[] bytes) {
		return new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
	}

	static byte[] bytes(final FriendlyByteBuf buf) {
		byte[] out = new byte[buf.readableBytes()];
		buf.readBytes(out);
		buf.release();
		return out;
	}

	static String registryFingerprint(final Registry<Biome> biomes) {
		StringBuilder sb = new StringBuilder();
		sb.append(BuiltInRegistries.BLOCK.size()).append(':').append(Block.BLOCK_STATE_REGISTRY.size()).append(':');
		int n = biomes.size();
		for (int i = 0; i < n; i++) {
			Biome b = biomes.byId(i);
			sb.append(b == null ? "?" : String.valueOf(biomes.getKey(b))).append(',');
		}
		return n + ":" + sb.toString().hashCode();
	}

	static void writeBeardifier(final FriendlyByteBuf buf, final Beardifier b) {
		List<Beardifier.Rigid> pieces = b.web263Pieces();
		buf.writeVarInt(pieces.size());
		for (Beardifier.Rigid r : pieces) {
			writeBox(buf, r.box());
			buf.writeVarInt(r.terrainAdjustment().ordinal());
			buf.writeInt(r.groundLevelDelta());
		}
		List<JigsawJunction> junctions = b.web263Junctions();
		buf.writeVarInt(junctions.size());
		for (JigsawJunction j : junctions) {
			buf.writeInt(j.getSourceX());
			buf.writeInt(j.getSourceGroundY());
			buf.writeInt(j.getSourceZ());
			buf.writeInt(j.getDeltaY());
			buf.writeVarInt(j.getDestProjection().ordinal());
		}
		BoundingBox box = b.web263AffectedBox();
		buf.writeBoolean(box != null);
		if (box != null) {
			writeBox(buf, box);
		}
	}

	static Beardifier readBeardifier(final FriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<Beardifier.Rigid> pieces = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			BoundingBox box = readBox(buf);
			TerrainAdjustment adj = TerrainAdjustment.values()[buf.readVarInt()];
			pieces.add(new Beardifier.Rigid(box, adj, buf.readInt()));
		}
		int m = buf.readVarInt();
		List<JigsawJunction> junctions = new ArrayList<>(m);
		for (int i = 0; i < m; i++) {
			int sx = buf.readInt(), sy = buf.readInt(), sz = buf.readInt(), dy = buf.readInt();
			junctions.add(new JigsawJunction(sx, sy, sz, dy, StructureTemplatePool.Projection.values()[buf.readVarInt()]));
		}
		BoundingBox affected = buf.readBoolean() ? readBox(buf) : null;
		return new Beardifier(pieces, junctions, affected);
	}

	private static void writeBox(final FriendlyByteBuf buf, final BoundingBox b) {
		buf.writeInt(b.minX()); buf.writeInt(b.minY()); buf.writeInt(b.minZ());
		buf.writeInt(b.maxX()); buf.writeInt(b.maxY()); buf.writeInt(b.maxZ());
	}

	private static BoundingBox readBox(final FriendlyByteBuf buf) {
		return new BoundingBox(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
	}

	static void writeBiomes(final FriendlyByteBuf buf, final ChunkAccess chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		buf.writeVarInt(sections.length);
		for (LevelChunkSection s : sections) {
			s.getBiomes().write(buf);
		}
	}

	static void readBiomes(final FriendlyByteBuf buf, final ChunkAccess chunk) {
		int n = buf.readVarInt();
		LevelChunkSection[] sections = chunk.getSections();
		if (n != sections.length) {
			throw new IllegalStateException("section count " + n + " != " + sections.length);
		}
		for (LevelChunkSection s : sections) {
			s.readBiomes(buf);
		}
	}

	static void writeTerrain(final FriendlyByteBuf buf, final ChunkAccess chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		buf.writeVarInt(sections.length);
		for (LevelChunkSection s : sections) {
			s.write(buf);
		}
		writeHeightmap(buf, chunk, Heightmap.Types.WORLD_SURFACE_WG);
		writeHeightmap(buf, chunk, Heightmap.Types.OCEAN_FLOOR_WG);
		ShortList[] post = chunk.getPostProcessing();
		buf.writeVarInt(post.length);
		for (ShortList list : post) {
			int n = list == null ? 0 : list.size();
			buf.writeVarInt(n);
			for (int i = 0; i < n; i++) {
				buf.writeShort(list.getShort(i));
			}
		}
	}

	static void readTerrain(final FriendlyByteBuf buf, final ChunkAccess chunk) {
		int n = buf.readVarInt();
		LevelChunkSection[] sections = chunk.getSections();
		if (n != sections.length) {
			throw new IllegalStateException("section count " + n + " != " + sections.length);
		}
		for (LevelChunkSection s : sections) {
			s.read(buf);
		}
		readHeightmap(buf, chunk, Heightmap.Types.WORLD_SURFACE_WG);
		readHeightmap(buf, chunk, Heightmap.Types.OCEAN_FLOOR_WG);
		int m = buf.readVarInt();
		if (m != chunk.getPostProcessing().length) {
			throw new IllegalStateException("post-processing section count " + m + " != " + chunk.getPostProcessing().length);
		}
		for (int i = 0; i < m; i++) {
			int k = buf.readVarInt();
			if (k > 0) {
				ShortList list = new ShortArrayList(k);
				for (int j = 0; j < k; j++) {
					list.add(buf.readShort());
				}
				chunk.addPackedPostProcess(list, i);
			}
		}
	}

	private static void writeHeightmap(final FriendlyByteBuf buf, final ChunkAccess chunk, final Heightmap.Types type) {
		buf.writeLongArray(chunk.getOrCreateHeightmapUnprimed(type).getRawData());
	}

	private static void readHeightmap(final FriendlyByteBuf buf, final ChunkAccess chunk, final Heightmap.Types type) {
		chunk.setHeightmap(type, buf.readLongArray());
	}

	static Registry<Biome> biomes(final net.minecraft.core.RegistryAccess access) {
		return access.lookupOrThrow(Registries.BIOME);
	}

	@SuppressWarnings("unchecked")
	static PalettedContainer<Holder<Biome>> mutableBiomes(final LevelChunkSection s) {
		return (PalettedContainer<Holder<Biome>>) s.getBiomes();
	}
}
