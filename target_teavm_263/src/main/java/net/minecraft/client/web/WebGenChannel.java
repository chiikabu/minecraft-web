// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

public final class WebGenChannel {

	public static final String CH_GEN = "GEN";

	private WebGenChannel() {
	}

	public static final class Job {
		public final long worldSeed;
		public final int chunkX;
		public final int chunkZ;
		public final String dimensionSettingsKey;

		public Job(final long worldSeed, final int chunkX, final int chunkZ, final String dimensionSettingsKey) {
			this.worldSeed = worldSeed;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.dimensionSettingsKey = dimensionSettingsKey == null ? "" : dimensionSettingsKey;
		}

		@Override
		public boolean equals(final Object o) {
			if (!(o instanceof Job)) {
				return false;
			}
			Job j = (Job) o;
			return worldSeed == j.worldSeed && chunkX == j.chunkX && chunkZ == j.chunkZ
				&& dimensionSettingsKey.equals(j.dimensionSettingsKey);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(worldSeed, chunkX, chunkZ, dimensionSettingsKey);
		}
	}

	public static final class Section {
		public final int yIndex;
		public final int[] palette;
		public final int bits;
		public final long[] storage;

		public Section(final int yIndex, final int[] palette, final int bits, final long[] storage) {
			this.yIndex = yIndex;
			this.palette = palette;
			this.bits = bits;
			this.storage = storage;
		}
	}

	public static final class Result {
		public final int chunkX;
		public final int chunkZ;
		public final String reachedStatus;
		public final Section[] sections;

		public Result(final int chunkX, final int chunkZ, final String reachedStatus, final Section[] sections) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.reachedStatus = reachedStatus == null ? "" : reachedStatus;
			this.sections = sections;
		}
	}

	public static byte[] encodeJob(final Job job) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(32);
		try (DataOutputStream out = new DataOutputStream(bos)) {
			out.writeLong(job.worldSeed);
			out.writeInt(job.chunkX);
			out.writeInt(job.chunkZ);
			out.writeUTF(job.dimensionSettingsKey);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bos.toByteArray();
	}

	public static Job decodeJob(final byte[] bytes) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			long seed = in.readLong();
			int cx = in.readInt();
			int cz = in.readInt();
			String key = in.readUTF();
			return new Job(seed, cx, cz, key);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static byte[] encodeResult(final Result result) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
		try (DataOutputStream out = new DataOutputStream(bos)) {
			out.writeInt(result.chunkX);
			out.writeInt(result.chunkZ);
			out.writeUTF(result.reachedStatus);
			out.writeInt(result.sections.length);
			for (Section s : result.sections) {
				out.writeInt(s.yIndex);
				out.writeByte(s.bits);
				out.writeInt(s.palette.length);
				for (int id : s.palette) {
					out.writeInt(id);
				}
				out.writeInt(s.storage.length);
				for (long word : s.storage) {
					out.writeLong(word);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bos.toByteArray();
	}

	public static Result decodeResult(final byte[] bytes) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			int cx = in.readInt();
			int cz = in.readInt();
			String status = in.readUTF();
			int nSections = in.readInt();
			Section[] sections = new Section[nSections];
			for (int i = 0; i < nSections; i++) {
				int yIndex = in.readInt();
				int bits = in.readUnsignedByte();
				int paletteLen = in.readInt();
				int[] palette = new int[paletteLen];
				for (int p = 0; p < paletteLen; p++) {
					palette[p] = in.readInt();
				}
				int storageLen = in.readInt();
				long[] storage = new long[storageLen];
				for (int w = 0; w < storageLen; w++) {
					storage[w] = in.readLong();
				}
				sections[i] = new Section(yIndex, palette, bits, storage);
			}
			return new Result(cx, cz, status, sections);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static final int SECTION_CELLS = 4096;

	public static Section pack(final int yIndex, final int[] cellIds) {
		if (cellIds.length != SECTION_CELLS) {
			throw new IllegalArgumentException("section needs " + SECTION_CELLS + " cells, got " + cellIds.length);
		}
		int[] palette = new int[SECTION_CELLS];
		int[] cellIndex = new int[SECTION_CELLS];
		int paletteSize = 0;
		for (int c = 0; c < SECTION_CELLS; c++) {
			int id = cellIds[c];
			int idx = -1;
			for (int p = 0; p < paletteSize; p++) {
				if (palette[p] == id) {
					idx = p;
					break;
				}
			}
			if (idx < 0) {
				idx = paletteSize;
				palette[paletteSize++] = id;
			}
			cellIndex[c] = idx;
		}
		int[] trimmedPalette = Arrays.copyOf(palette, paletteSize);

		if (paletteSize <= 1) {
			return new Section(yIndex, trimmedPalette, 0, new long[0]);
		}

		int bits = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
		int valuesPerLong = 64 / bits;
		int words = (SECTION_CELLS + valuesPerLong - 1) / valuesPerLong;
		long[] storage = new long[words];
		long mask = (1L << bits) - 1L;
		for (int c = 0; c < SECTION_CELLS; c++) {
			int cell = c / valuesPerLong;
			int bitIndex = (c - cell * valuesPerLong) * bits;
			storage[cell] |= (cellIndex[c] & mask) << bitIndex;
		}
		return new Section(yIndex, trimmedPalette, bits, storage);
	}

	public static int[] reconstruct(final Section s) {
		int[] out = new int[SECTION_CELLS];
		if (s.bits == 0) {
			int id = s.palette.length == 0 ? 0 : s.palette[0];
			Arrays.fill(out, id);
			return out;
		}
		int valuesPerLong = 64 / s.bits;
		long mask = (1L << s.bits) - 1L;
		for (int c = 0; c < SECTION_CELLS; c++) {
			int cell = c / valuesPerLong;
			int bitIndex = (c - cell * valuesPerLong) * s.bits;
			int idx = (int) ((s.storage[cell] >>> bitIndex) & mask);
			out[c] = s.palette[idx];
		}
		return out;
	}
}
