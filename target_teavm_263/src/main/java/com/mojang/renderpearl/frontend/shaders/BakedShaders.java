// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.renderpearl.frontend.shaders;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.web.WebPackResources;
import org.jspecify.annotations.Nullable;

public final class BakedShaders {
	public static final String ASSET_PATH = "assets/minecraft/shaders/baked263.bin";
	private static final int MAGIC = 0x4D435342;
	private static final int VERSION = 1;
	private static @Nullable BakedShaders instance;

	private final byte[] data;
	private final Map<String, int[]> spv = new HashMap<>();
	private final Map<String, Module> reflection = new HashMap<>();
	private final Map<String, int[]> glsl = new HashMap<>();
	private int pos;

	private BakedShaders(byte[] data) {
		this.data = data;
		if (this.i32() != MAGIC) throw new IllegalStateException("baked263.bin: bad magic");
		int version = this.i32();
		if (version != VERSION) throw new IllegalStateException("baked263.bin: unsupported version " + version);
		this.i32();
		this.i32();
		int blobCount = this.i32();
		int[][] blobs = new int[blobCount][];
		for (int i = 0; i < blobCount; i++) {
			int len = this.i32();
			blobs[i] = new int[] {this.pos, len};
			this.pos += len;
		}
		int spvCount = this.i32();
		for (int i = 0; i < spvCount; i++) {
			String key = this.str();
			this.spv.put(key, blobs[this.i32()]);
		}
		int reflCount = this.i32();
		for (int i = 0; i < reflCount; i++) {
			String key = this.moduleKey();
			this.reflection.put(key, this.module());
		}
		int glslCount = this.i32();
		for (int i = 0; i < glslCount; i++) {
			String key = this.moduleKey();
			int len = this.i32();
			this.glsl.put(key, new int[] {this.pos, len});
			this.pos += len;
		}
	}

	public static synchronized BakedShaders get() throws ShaderCompileException {
		if (instance == null) {
			byte[] bytes = WebPackResources.rawAsset(ASSET_PATH);
			if (bytes == null) {
				throw new ShaderCompileException("Missing " + ASSET_PATH + " (run scripts/bake_shaders_263.py and bundle its output)");
			}
			instance = new BakedShaders(bytes);
		}
		return instance;
	}

	public @Nullable ByteBuffer spirv(String key) {
		int[] at = this.spv.get(key);
		if (at == null) return null;
		ByteBuffer copy = ByteBuffer.allocate(at[1]).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		copy.put(this.data, at[0], at[1]);
		copy.flip();
		return copy;
	}

	public @Nullable Module reflection(long hash, ShaderType type) {
		return this.reflection.get(moduleKey(hash, type));
	}

	public @Nullable String glsl(long hash, ShaderType type) {
		int[] at = this.glsl.get(moduleKey(hash, type));
		return at == null ? null : new String(this.data, at[0], at[1], StandardCharsets.UTF_8);
	}

	public static String spvKey(String name, ShaderType type, ShaderDefines defines) {
		StringBuilder sb = new StringBuilder(name).append('|').append(type.name()).append('|');
		boolean first = true;
		for (Map.Entry<String, String> e : new TreeMap<>(defines.values()).entrySet()) {
			if (!first) sb.append(',');
			sb.append(e.getKey()).append('=').append(e.getValue());
			first = false;
		}
		sb.append('|');
		first = true;
		for (String f : new TreeSet<>(defines.flags())) {
			if (!first) sb.append(',');
			sb.append(f);
			first = false;
		}
		return sb.toString();
	}

	public static long fnv1a64(ByteBuffer buf) {
		long h = 0xcbf29ce484222325L;
		for (int i = buf.position(), end = buf.limit(); i < end; i++) {
			h ^= buf.get(i) & 0xFF;
			h *= 0x100000001b3L;
		}
		return h;
	}

	static String moduleKey(long hash, ShaderType type) {
		return Long.toHexString(hash) + ":" + (type == ShaderType.VERTEX ? 0 : 1);
	}

	private int i32() {
		byte[] d = this.data;
		int p = this.pos;
		this.pos = p + 4;
		return (d[p] & 0xFF) << 24 | (d[p + 1] & 0xFF) << 16 | (d[p + 2] & 0xFF) << 8 | d[p + 3] & 0xFF;
	}

	private String str() {
		int len = this.i32();
		String s = new String(this.data, this.pos, len, StandardCharsets.UTF_8);
		this.pos += len;
		return s;
	}

	private String moduleKey() {
		long hi = this.i32() & 0xFFFFFFFFL;
		long lo = this.i32() & 0xFFFFFFFFL;
		int type = this.data[this.pos++];
		return Long.toHexString(hi << 32 | lo) + ":" + type;
	}

	private Module module() {
		List<Var> inputs = this.vars();
		List<Var> outputs = this.vars();
		int typeCount = this.i32();
		int[] resourceTypes = new int[typeCount];
		List<List<Desc>> descriptors = new ArrayList<>(typeCount);
		for (int t = 0; t < typeCount; t++) {
			int resourceType = this.i32();
			resourceTypes[t] = resourceType;
			int n = this.i32();
			List<Desc> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(new Desc(this.str(), this.type(), resourceType, this.i32(), this.i32()));
			}
			descriptors.add(list);
		}
		int pushCount = this.i32();
		int[] pushSizes = new int[pushCount];
		for (int i = 0; i < pushCount; i++) pushSizes[i] = this.i32();
		return new Module(inputs, outputs, resourceTypes, descriptors, pushSizes);
	}

	private List<Var> vars() {
		int n = this.i32();
		List<Var> list = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			String name = this.str();
			TypeInfo type = this.type();
			int locationOffset = this.i32();
			int decCount = this.i32();
			int[] decorations = new int[decCount * 2];
			for (int k = 0; k < decorations.length; k++) decorations[k] = this.i32();
			list.add(new Var(name, type, locationOffset, decorations));
		}
		return Collections.unmodifiableList(list);
	}

	private TypeInfo type() {
		int baseType = this.i32();
		int dimensions = this.i32();
		int vectorSize = this.i32();
		int arrayDimensions = this.i32();
		int[] lengths = new int[arrayDimensions];
		for (int i = 0; i < arrayDimensions; i++) lengths[i] = this.i32();
		return new TypeInfo(baseType, dimensions, vectorSize, lengths);
	}

	public static final class Module {
		final List<Var> inputs;
		final List<Var> outputs;
		final int[] resourceTypes;
		final List<List<Desc>> descriptors;
		final int[] pushConstantSizes;

		Module(List<Var> inputs, List<Var> outputs, int[] resourceTypes, List<List<Desc>> descriptors, int[] pushConstantSizes) {
			this.inputs = inputs;
			this.outputs = outputs;
			this.resourceTypes = resourceTypes;
			this.descriptors = descriptors;
			this.pushConstantSizes = pushConstantSizes;
		}
	}

	static final class Var {
		final String name;
		final TypeInfo type;
		final int locationOffset;
		final int[] decorations;

		Var(String name, TypeInfo type, int locationOffset, int[] decorations) {
			this.name = name;
			this.type = type;
			this.locationOffset = locationOffset;
			this.decorations = decorations;
		}

		int decoration(int decoration) {
			for (int i = 0; i < this.decorations.length; i += 2) {
				if (this.decorations[i] == decoration) return this.decorations[i + 1];
			}
			return 0;
		}
	}

	static final class Desc {
		final String name;
		final TypeInfo type;
		final int resourceType;
		final int setOffset;
		final int bindingOffset;

		Desc(String name, TypeInfo type, int resourceType, int setOffset, int bindingOffset) {
			this.name = name;
			this.type = type;
			this.resourceType = resourceType;
			this.setOffset = setOffset;
			this.bindingOffset = bindingOffset;
		}
	}

	static final class TypeInfo implements SpvModule.Reflection.Type {
		private final int baseType;
		private final int dimensions;
		private final int vectorSize;
		private final int[] arrayLengths;

		TypeInfo(int baseType, int dimensions, int vectorSize, int[] arrayLengths) {
			this.baseType = baseType;
			this.dimensions = dimensions;
			this.vectorSize = vectorSize;
			this.arrayLengths = arrayLengths;
		}

		@Override
		public int baseType() {
			return this.baseType;
		}

		@Override
		public int dimensions() {
			return this.dimensions;
		}

		@Override
		public int vectorSize() {
			return this.vectorSize;
		}

		@Override
		public int arrayDimensions() {
			return this.arrayLengths.length;
		}

		@Override
		public int arrayLength(int dimensionIndex) {
			return dimensionIndex >= 0 && dimensionIndex < this.arrayLengths.length ? this.arrayLengths[dimensionIndex] : 0;
		}
	}
}
