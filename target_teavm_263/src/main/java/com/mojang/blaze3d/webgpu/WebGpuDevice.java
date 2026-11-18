// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.webgpu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.web.WebPackResources;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

final class WebGpuDevice implements GpuDeviceBackend {
	static final String WGSL_ROOT = "assets/minecraft/shaders/wgsl/";
	private static final Set<String> WARNED = new HashSet<>();
	static WebGpuDevice current;

	private final DeviceInfo info;
	private final WebGpuCommandEncoder encoder;
	private final Map<String, JSObject> modules = new HashMap<>();
	private final JsonObject manifest;
	final Map<String, Integer> bindingOf = new HashMap<>();
	final int uniformAlignment;
	final WebGpuBuffer dummyUniform;
	final WebGpuTextureView dummyView;
	final WebGpuTextureView dummyCubeView;
	final WebGpuSampler dummySampler;
	int pipelinesCompiled;
	int pipelinesFailed;

	WebGpuDevice() {
		WebGpuNatives.install();
		JsonObject m = loadManifest();
		this.manifest = m;
		if (m != null) {
			for (Map.Entry<String, JsonElement> e : m.getAsJsonObject("bindings").entrySet()) {
				bindingOf.put(e.getKey(), e.getValue().getAsInt());
			}
		}
		String ident = WebGpuNatives.adapterIdentity();
		String[] parts = ident.split("\\|", 3);
		int maxTex = (int) WebGpuNatives.limit("maxTextureDimension2D", 8192);
		uniformAlignment = (int) WebGpuNatives.limit("minUniformBufferOffsetAlignment", 256);
		long maxAlloc = Math.max((long) WebGpuNatives.limit("maxBufferSize", 1L << 28), 1L << 28);
		int maxColor = (int) WebGpuNatives.limit("maxColorAttachments", 8);
		String vendor = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "unknown";
		String name = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "WebGPU";
		info = new DeviceInfo(
			name,
			vendor,
			"WebGPU " + (parts.length > 2 ? parts[2] : ""),
			true,
			"WebGPU",
			1.0f,
			new DeviceLimits(16, uniformAlignment, maxTex, maxAlloc, 1, Math.min(8, maxColor), 1),
			new DeviceFeatures(true, false, false, false, false, false, false, false),
			Set.of(),
			new HintsAndWorkarounds(false, false, false, false),
			name.toLowerCase().contains("swiftshader") ? DeviceType.CPU : DeviceType.INTEGRATED
		);
		current = this;
		dummyUniform = (WebGpuBuffer) createBuffer(() -> "dummy uniform", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 65536);
		WebGpuTexture tex = (WebGpuTexture) createTexture("dummy texture", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
		dummyView = new WebGpuTextureView(tex, 0, 1);
		WebGpuTexture cube = (WebGpuTexture) createTexture(
			"dummy cube", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_CUBEMAP_COMPATIBLE, GpuFormat.RGBA8_UNORM, 1, 1, 6, 1
		);
		dummyCubeView = new WebGpuTextureView(cube, 0, 1);
		dummySampler = new WebGpuSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
		encoder = new WebGpuCommandEncoder(this);
		System.out.println("[26.3-web] webgpu: device up - " + info.name() + " (" + info.vendorName() + "), uniform align " + uniformAlignment
			+ ", max texture " + maxTex + ", WGSL pipelines in bundle: " + (m == null ? 0 : m.getAsJsonObject("pipelines").size()));
	}

	@Nullable
	private static JsonObject loadManifest() {
		byte[] bytes = WebPackResources.rawAsset(WGSL_ROOT + "manifest.json");
		if (bytes == null) {
			System.out.println("[26.3-web] webgpu: " + WGSL_ROOT + "manifest.json missing from the asset bundle; no pipeline can compile");
			return null;
		}
		return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	static boolean manifestPresent() {
		return WebPackResources.rawAsset(WGSL_ROOT + "manifest.json") != null;
	}

	static void warnOnce(String message) {
		if (WARNED.add(message)) {
			System.out.println("[26.3-web] webgpu: " + message);
		}
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
		return new WebGpuSurface();
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return encoder;
	}

	@Override
	public GpuSampler createSampler(
		AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod
	) {
		return new WebGpuSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		String fmt = wgpuFormat(format);
		int layers = Math.max(1, depthOrLayers);
		int mips = Math.max(1, mipLevels);
		String l = label == null ? "" : label;
		JSObject handle = WebGpuNatives.createTexture(width, height, layers, mips, fmt, texUsage(usage, format), l);
		return new WebGpuTexture(usage, l, format, width, height, layers, mips, handle, fmt);
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
		return new WebGpuTextureView((WebGpuTexture) texture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
		int bytes = (int) Math.min(Math.max(4, size), Integer.MAX_VALUE);
		bytes = (usage & GpuBuffer.USAGE_UNIFORM) != 0 ? (bytes + 15) & ~15 : (bytes + 3) & ~3;
		JSObject handle = WebGpuNatives.createBuffer(bytes, bufUsage(usage), label == null ? "" : label.get());
		boolean mirrored = (usage & (GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_MAP_READ)) != 0;
		WebGpuBuffer buf = new WebGpuBuffer(usage, size, handle, true, mirrored && !MemoryUtil.LINEAR ? new byte[bytes] : null);
		if (mirrored && MemoryUtil.LINEAR) {
			buf.cpu = MemoryUtil.nmemCalloc(bytes, 1);
		}
		return buf;
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
		WebGpuBuffer buf = (WebGpuBuffer) createBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
		int len = data.remaining();
		long addr = MemoryUtil.memLinearAddress(data);
		if (addr >= 0 && (len & 3) == 0) {
			if (buf.cpu != 0) {
				MemoryUtil.memCopy(addr, buf.cpu, len);
			} else if (buf.shadow != null) {
				MemoryUtil.copyOut(addr, buf.shadow, 0, len);
			}
			WebGpuNatives.writeBufferMem(buf.handle, 0, (int) addr, len);
			return buf;
		}
		byte[] bytes = new byte[(len + 3) & ~3];
		data.duplicate().get(bytes, 0, len);
		if (buf.cpu != 0) {
			MemoryUtil.copyIn(bytes, 0, buf.cpu, len);
		} else if (buf.shadow != null) {
			System.arraycopy(bytes, 0, buf.shadow, 0, len);
		}
		WebGpuNatives.writeBuffer(buf.handle, 0, toJs(bytes, 0, bytes.length));
		return buf;
	}

	@Override
	public List<String> getLastDebugMessages() {
		String err = WebGpuNatives.lastError();
		return err == null || err.isEmpty() ? Collections.emptyList() : List.of(err);
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo createInfo) {
		WebGpuPipeline pipeline = compile(createInfo);
		if (pipeline == null) {
			pipelinesFailed++;
			return BackendRenderPipeline.Pending.NULL;
		}
		pipelinesCompiled++;
		return () -> pipeline;
	}

	@Override
	public void close() {
		modules.clear();
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new WebGpuQueryPool(size);
	}

	@Override
	public long getTimestampCalibrationOffset() {
		return 0L;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return info;
	}

	@Nullable
	static String pipelineKey(BackendRenderPipeline.CreateInfo createInfo) {
		String vertex = null;
		String fragment = null;
		for (BackendRenderPipeline.CreateInfo.Shader shader : createInfo.shaders()) {
			if (!(shader.module() instanceof SPIRVModule module) || module.bakeKey == null) {
				return null;
			}
			if (module.type() == ShaderType.VERTEX) {
				vertex = module.bakeKey;
			} else if (module.type() == ShaderType.FRAGMENT) {
				fragment = module.bakeKey;
			}
		}
		if (vertex == null || fragment == null) {
			return null;
		}
		String[] v = vertex.split("\\|", -1);
		String[] f = fragment.split("\\|", -1);
		if (v.length != 4 || f.length != 4) {
			return null;
		}
		return v[0] + "|" + f[0] + "|" + v[2].replace(',', ';') + "|" + v[3].replace(',', ';');
	}

	@Nullable
	private WebGpuPipeline compile(BackendRenderPipeline.CreateInfo createInfo) {
		String label = createInfo.name();
		if (manifest == null) {
			return null;
		}
		String key = pipelineKey(createInfo);
		if (key == null) {
			warnOnce("pipeline " + label + " has no bake key; cannot pick its WGSL");
			return null;
		}
		JsonObject entry = manifest.getAsJsonObject("pipelines").getAsJsonObject(key);
		if (entry == null) {
			warnOnce("no WGSL for " + label + " (key " + key + "); regenerate with scripts/webgpu_generate_wgsl.py");
			return null;
		}
		if (createInfo.pushConstantsSize() > 0) {
			warnOnce("pipeline " + label + " uses push constants, which the WGSL bundle has no slot for");
		}
		String vertFile = entry.get("vert").getAsString();
		String fragFile = entry.get("frag").getAsString();
		JSObject vert = module(vertFile);
		JSObject frag = module(fragFile);
		if (vert == null || frag == null) {
			return null;
		}
		JsonObject shaders = manifest.getAsJsonObject("shaders");
		JsonObject v = shaders.getAsJsonObject(vertFile);
		JsonObject f = shaders.getAsJsonObject(fragFile);
		Map<String, Integer> inputs = new HashMap<>();
		if (v.has("inputs")) {
			for (Map.Entry<String, JsonElement> e : v.getAsJsonObject("inputs").entrySet()) {
				inputs.put(e.getKey(), e.getValue().getAsInt());
			}
		}
		return new WebGpuPipeline(this, createInfo, vert, frag, bindings(v), bindings(f), inputs, label);
	}

	private static int[][] bindings(JsonObject shader) {
		List<int[]> out = new ArrayList<>();
		for (JsonElement e : shader.getAsJsonArray("bindings")) {
			int binding = e.getAsJsonArray().get(0).getAsInt();
			String kind = e.getAsJsonArray().get(1).getAsString();
			out.add(new int[]{binding, WebGpuPipeline.kindCode(kind)});
		}
		return out.toArray(new int[0][]);
	}

	@Nullable
	private JSObject module(String file) {
		JSObject m = modules.get(file);
		if (m != null) {
			return m;
		}
		byte[] bytes = WebPackResources.rawAsset(WGSL_ROOT + file.replace('\\', '/'));
		if (bytes == null) {
			warnOnce("missing module " + file);
			return null;
		}
		m = WebGpuNatives.createModule(new String(bytes, StandardCharsets.UTF_8), file);
		modules.put(file, m);
		return m;
	}

	static void write(WebGpuBuffer buf, int offset, ByteBuffer data) {
		int len = data.remaining();
		if (data.hasArray()) {
			upload(buf, offset, data.array(), data.arrayOffset() + data.position(), len);
		} else {
			long addr = MemoryUtil.memLinearAddress(data);
			if (addr >= 0) {
				uploadLinear(buf, offset, addr, len);
				return;
			}
			byte[] bytes = new byte[len];
			data.duplicate().get(bytes);
			upload(buf, offset, bytes, 0, len);
		}
	}

	static void upload(WebGpuBuffer buf, int offset, byte[] src, int srcOff, int len) {
		if (len <= 0) {
			return;
		}
		int start = offset & ~3;
		int end = (offset + len + 3) & ~3;
		if (buf.cpu != 0) {
			MemoryUtil.copyIn(src, srcOff, buf.cpu + offset, len);
			pushLinear(buf, start, buf.cpu + start, end - start);
			return;
		}
		if (buf.shadow != null && !(src == buf.shadow && srcOff == offset)) {
			System.arraycopy(src, srcOff, buf.shadow, offset, len);
		}
		byte[] bytes;
		int off;
		if (start == offset && end == offset + len) {
			bytes = src;
			off = srcOff;
		} else if (buf.shadow != null) {
			bytes = buf.shadow;
			off = start;
		} else {
			warnOnce("unaligned write to a buffer without a shadow (offset " + offset + ", len " + len + "); neighbour bytes zeroed");
			bytes = new byte[end - start];
			System.arraycopy(src, srcOff, bytes, offset - start, len);
			off = 0;
		}
		int n = end - start;
		WebGpuCommandEncoder enc = current == null ? null : current.encoder;
		if (enc != null && enc.frameHasDraws() && !enc.passOpen() && buf.owned) {
			enc.stagedWrite(buf, start, bytes, off, n);
		} else {
			WebGpuNatives.writeBuffer(buf.handle, start, toJs(bytes, off, n));
		}
	}

	static void uploadLinear(WebGpuBuffer buf, int offset, long addr, int len) {
		if (len <= 0) {
			return;
		}
		int start = offset & ~3;
		int end = (offset + len + 3) & ~3;
		if (buf.cpu != 0) {
			if (addr != buf.cpu + offset) {
				MemoryUtil.memCopy(addr, buf.cpu + offset, len);
			}
			pushLinear(buf, start, buf.cpu + start, end - start);
			return;
		}
		if (buf.shadow != null) {
			MemoryUtil.copyOut(addr, buf.shadow, offset, len);
		}
		if (start != offset || end != offset + len) {
			byte[] bytes = new byte[len];
			MemoryUtil.copyOut(addr, bytes, 0, len);
			upload(buf, offset, bytes, 0, len);
			return;
		}
		pushLinear(buf, start, addr, len);
	}

	static void pushLinear(WebGpuBuffer buf, int start, long addr, int len) {
		if (len <= 0) {
			return;
		}
		WebGpuCommandEncoder enc = current == null ? null : current.encoder;
		if (enc != null && enc.frameHasDraws() && !enc.passOpen() && buf.owned) {
			enc.stagedWriteLinear(buf, start, addr, len);
		} else {
			WebGpuNatives.writeBufferMem(buf.handle, start, (int) addr, len);
		}
	}

	static Int8Array toJs(byte[] bytes, int off, int len) {
		if (off == 0 && len == bytes.length) {
			return Int8Array.copyFromJavaArray(bytes);
		}
		byte[] tmp = new byte[len];
		System.arraycopy(bytes, off, tmp, 0, len);
		return Int8Array.copyFromJavaArray(tmp);
	}

	private static Int8Array toJsLegacy(byte[] bytes, int off, int len) {
		Int8Array arr = WebGpuNatives.alloc(len);
		if (off == 0 && len == bytes.length) {
			arr.set(bytes);
		} else {
			byte[] tmp = new byte[len];
			System.arraycopy(bytes, off, tmp, 0, len);
			arr.set(tmp);
		}
		return arr;
	}

	static void bridgeBench() {
		byte[] src = new byte[65536];
		for (int i = 0; i < src.length; i++) {
			src[i] = (byte) i;
		}
		long t0 = System.nanoTime();
		int sink = 0;
		for (int i = 0; i < 200; i++) {
			sink += toJsLegacy(src, 0, src.length).get(i);
		}
		long t1 = System.nanoTime();
		for (int i = 0; i < 200; i++) {
			sink += toJs(src, 0, src.length).get(i);
		}
		long t2 = System.nanoTime();
		System.out.println("[26.3-web] bridgebench 64KB x200: set(byte[]) " + (t1 - t0) / 1_000_000 + " ms, copyFromJavaArray " + (t2 - t1) / 1_000_000 + " ms (sink " + sink + ")");
	}

	static void writeTex(WebGpuTexture tex, ByteBuffer data, int mip, int layer, int x, int y, int w, int h) {
		int bpp = Math.max(1, tex.getFormat().blockSize());
		int len = w * h * bpp;
		long addr = data.hasArray() ? -1L : MemoryUtil.memLinearAddress(data);
		if (addr >= 0) {
			WebGpuNatives.writeTextureMem(tex.handle, mip, x, y, layer, w, h, (int) addr, w * bpp);
			return;
		}
		Int8Array js;
		if (data.hasArray()) {
			js = toJs(data.array(), data.arrayOffset() + data.position(), len);
		} else {
			byte[] bytes = new byte[len];
			data.duplicate().get(bytes);
			js = toJs(bytes, 0, len);
		}
		WebGpuNatives.writeTexture(tex.handle, mip, x, y, layer, w, h, js, w * bpp);
	}

	static String wgpuFormat(GpuFormat format) {
		return switch (format) {
			case RGBA8_UNORM -> "rgba8unorm";
			case R8_UNORM -> "r8unorm";
			case RG8_UNORM -> "rg8unorm";
			case R8_SINT -> "r8sint";
			case R8_UINT -> "r8uint";
			case R16_FLOAT -> "r16float";
			case RG16_FLOAT -> "rg16float";
			case RGBA16_FLOAT -> "rgba16float";
			case R32_FLOAT -> "r32float";
			case RG32_FLOAT -> "rg32float";
			case RGBA32_FLOAT -> "rgba32float";
			case D32_FLOAT -> "depth32float";
			case D24_UNORM_S8_UINT -> "depth24plus-stencil8";
			case D32_FLOAT_S8_UINT -> "depth32float-stencil8";
			case D16_UNORM -> "depth16unorm";
			case RGBA8_SNORM -> "rgba8snorm";
			case RG8_SNORM -> "rg8snorm";
			case R8_SNORM -> "r8snorm";
			case RGBA8_UINT -> "rgba8uint";
			case RGBA8_SINT -> "rgba8sint";
			case R16_UINT -> "r16uint";
			case R16_SINT -> "r16sint";
			case RGBA16_UINT -> "rgba16uint";
			case R32_UINT -> "r32uint";
			case R32_SINT -> "r32sint";
			case RG32_UINT -> "rg32uint";
			case RGBA32_UINT -> "rgba32uint";
			case RGB10A2_UNORM -> "rgb10a2unorm";
			case RGB10A2_UINT -> "rgb10a2uint";
			case RG11B10_FLOAT -> "rg11b10ufloat";
			default -> {
				warnOnce("texture format " + format + " mapped to rgba8unorm");
				yield "rgba8unorm";
			}
		};
	}

	static String vertexFormat(GpuFormat format) {
		int n = format.componentCount();
		String base = switch (format.componentType()) {
			case UNORM_8 -> "unorm8";
			case SNORM_8 -> "snorm8";
			case UINT_8 -> "uint8";
			case SINT_8 -> "sint8";
			case UNORM_16 -> "unorm16";
			case SNORM_16 -> "snorm16";
			case UINT_16 -> "uint16";
			case SINT_16 -> "sint16";
			case FLOAT_16 -> "float16";
			case UINT_32 -> "uint32";
			case SINT_32 -> "sint32";
			case FLOAT_32 -> "float32";
			default -> "float32";
		};
		boolean wide = base.endsWith("32");
		if (n == 1) {
			return base;
		}
		if (n == 3 && !wide) {
			warnOnce("vertex format " + format + " has no 3-component WebGPU form; reading 4");
			n = 4;
		}
		return base + "x" + n;
	}

	private static int texUsage(int usage, GpuFormat format) {
		int out = 0;
		if ((usage & GpuTexture.USAGE_COPY_SRC) != 0) {
			out |= WebGpuNatives.TEX_COPY_SRC;
		}
		if ((usage & GpuTexture.USAGE_COPY_DST) != 0) {
			out |= WebGpuNatives.TEX_COPY_DST;
		}
		if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
			out |= WebGpuNatives.TEX_BINDING;
		}
		if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
			out |= WebGpuNatives.TEX_RENDER;
		}
		if (out == 0) {
			out = WebGpuNatives.TEX_BINDING | WebGpuNatives.TEX_COPY_DST;
		}
		if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0 || (usage & GpuTexture.USAGE_COPY_DST) != 0) {
			out |= WebGpuNatives.TEX_RENDER | WebGpuNatives.TEX_COPY_DST | WebGpuNatives.TEX_COPY_SRC;
		}
		return out;
	}

	private static int bufUsage(int usage) {
		int out = WebGpuNatives.BUF_COPY_DST | WebGpuNatives.BUF_COPY_SRC;
		if ((usage & GpuBuffer.USAGE_VERTEX) != 0) {
			out |= WebGpuNatives.BUF_VERTEX;
		}
		if ((usage & GpuBuffer.USAGE_INDEX) != 0) {
			out |= WebGpuNatives.BUF_INDEX;
		}
		if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) {
			out |= WebGpuNatives.BUF_UNIFORM;
		}
		if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
			out |= WebGpuNatives.BUF_STORAGE;
		}
		if ((usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0) {
			out |= WebGpuNatives.BUF_INDIRECT;
		}
		return out;
	}
}
