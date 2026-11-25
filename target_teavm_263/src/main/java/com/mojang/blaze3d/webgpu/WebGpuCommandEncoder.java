// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.webgpu;

import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSObject;

final class WebGpuCommandEncoder implements CommandEncoderBackend {
	final WebGpuDevice device;
	private final WebGpuTransientMemory transientMemory;
	private final WebGpuRenderPass reusablePass;
	private final String[][] colorFormatsByLen = new String[9][];
	private WebGpuRenderPass current;
	final CommandStream cmds = new CommandStream();
	private int drawsThisFrame;
	private WebGpuBuffer fanIndices;
	private int fanVertices;

	WebGpuCommandEncoder(WebGpuDevice device) {
		this.device = device;
		this.transientMemory = new WebGpuTransientMemory(device);
		this.reusablePass = new WebGpuRenderPass(this);
	}

	boolean frameHasDraws() {
		return drawsThisFrame > 0;
	}

	boolean passOpen() {
		return passOpen;
	}

	private boolean passOpen;

	void countDraw() {
		drawsThisFrame++;
	}

	void stagedWrite(WebGpuBuffer buf, int offset, byte[] bytes, int off, int len) {
		WebGpuTransientMemory.Region r = transientMemory.region(len, 4);
		if (r.block().cpu != 0) {
			MemoryUtil.copyIn(bytes, off, r.block().cpu + r.offset(), len);
			WebGpuNatives.writeBufferMem(r.block().handle, r.offset(), (int) (r.block().cpu + r.offset()), len);
		} else {
			System.arraycopy(bytes, off, r.block().shadow, r.offset(), len);
			WebGpuNatives.writeBuffer(r.block().handle, r.offset(), WebGpuDevice.toJs(r.block().shadow, r.offset(), len));
		}
		WebGpuNatives.copyBuffer(r.block().handle, r.offset(), buf.handle, offset, len);
	}

	void stagedWriteLinear(WebGpuBuffer buf, int offset, long addr, int len) {
		WebGpuTransientMemory.Region r = transientMemory.region(len, 4);
		long dst = r.block().cpu + r.offset();
		MemoryUtil.memCopy(addr, dst, len);
		WebGpuNatives.writeBufferMem(r.block().handle, r.offset(), (int) dst, len);
		WebGpuNatives.copyBuffer(r.block().handle, r.offset(), buf.handle, offset, len);
	}

	@Override
	public void submit() {
		WebGpuNatives.submit();
		transientMemory.rotate();
		drawsThisFrame = 0;
	}

	@Override
	public TransientMemory transientMemory() {
		return transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		passOpen = true;
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
		int colorCount = colors.size();
		String[] formats;
		if (colorCount < colorFormatsByLen.length) {
			formats = colorFormatsByLen[colorCount];
			if (formats == null) {
				formats = new String[colorCount];
				colorFormatsByLen[colorCount] = formats;
			}
		} else {
			formats = new String[colorCount];
		}
		int width = 0;
		int height = 0;
		WebGpuNatives.passBegin();
		for (int i = 0; i < colorCount; i++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> a = colors.get(i);
			if (a == null) {
				formats[i] = null;
				WebGpuNatives.passColorNull();
				continue;
			}
			WebGpuTextureView view = (WebGpuTextureView) a.textureView();
			formats[i] = view.tex.wgpuFormat;
			width = view.getWidth(0);
			height = view.getHeight(0);
			Optional<Vector4fc> clear = a.clearValue();
			if (clear.isPresent()) {
				Vector4fc c = clear.get();
				WebGpuNatives.passColor(view.attachmentHandle(), view.tex.handle, true, c.x(), c.y(), c.z(), c.w());
			} else {
				WebGpuNatives.passColor(view.attachmentHandle(), view.tex.handle, false, 0, 0, 0, 0);
			}
		}
		String depthFormat = null;
		if (depth != null) {
			WebGpuTextureView view = (WebGpuTextureView) depth.textureView();
			depthFormat = view.tex.wgpuFormat;
			if (width == 0) {
				width = view.getWidth(0);
				height = view.getHeight(0);
			}
			OptionalDouble clear = depth.clearValue();
			WebGpuNatives.passDepth(view.attachmentHandle(), clear.isPresent(), clear.isPresent() ? (float) clear.getAsDouble() : 0f);
		}
		String label = descriptor.label() == null ? "pass" : descriptor.label().get();
		JSObject passHandle = WebGpuNatives.passStart(label);
		reusablePass.reset(passHandle, depthFormat, formats, colorCount, width, height);
		current = reusablePass;
		RenderPass.RenderArea area = descriptor.renderArea();
		if (area != null) {
			current.setDefaultScissor(area.x(), area.y(), area.width(), area.height());
		}
		return current;
	}

	@Override
	public void submitRenderPass() {
		passOpen = false;
		if (current != null) {
			current.flush();
			current.release();
		}
		WebGpuNatives.passEnd();
		current = null;
	}

	@Override
	public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
		if (colorTexture instanceof WebGpuTexture tex) {
			WebGpuNatives.clearColor(tex.handle, clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w());
		}
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
		if (colorTexture instanceof WebGpuTexture color && depthTexture instanceof WebGpuTexture depth
			&& color.getMipLevels() == depth.getMipLevels()
			&& color.getWidth(0) == depth.getWidth(0)
			&& color.getHeight(0) == depth.getHeight(0)) {
			WebGpuNatives.clearColorAndDepth(color.handle, clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), depth.handle, (float) clearDepth);
		} else {
			clearColorTexture(colorTexture, clearColor);
			clearDepthTexture(depthTexture, clearDepth);
		}
	}

	@Override
	public void clearColorAndDepthTextures(
		GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth, int regionX, int regionY, int regionWidth, int regionHeight,
		int mipLevel
	) {
		if (colorTexture instanceof WebGpuTexture tex && depthTexture instanceof WebGpuTexture depth) {
			WebGpuNatives.clearRegion(tex.handle, depth.handle, clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), (float) clearDepth,
				regionX, regionY, regionWidth, regionHeight, mipLevel);
		} else {
			clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth);
		}
	}

	@Override
	public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
		if (depthTexture instanceof WebGpuTexture tex) {
			WebGpuNatives.clearDepth(tex.handle, (float) clearDepth);
		}
	}

	@Override
	public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
		if (destination.buffer() instanceof WebGpuBuffer buf) {
			WebGpuDevice.write(buf, (int) destination.offset(), data);
		}
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
		if (!(source.buffer() instanceof WebGpuBuffer src) || !(target.buffer() instanceof WebGpuBuffer dst)) {
			return;
		}
		int len = (int) source.length();
		int so = (int) source.offset();
		int to = (int) target.offset();
		if (src.cpu != 0) {
			if (dst.cpu != 0) {
				MemoryUtil.memCopy(src.cpu + so, dst.cpu + to, len);
			} else if (dst.shadow != null) {
				MemoryUtil.copyOut(src.cpu + so, dst.shadow, to, len);
			}
			if (passOpen || (so & 3) != 0 || (to & 3) != 0 || (len & 3) != 0) {
				WebGpuDevice.uploadLinear(dst, to, src.cpu + so, len);
			} else {
				WebGpuNatives.copyBuffer(src.handle, so, dst.handle, to, len);
			}
			return;
		}
		if (src.shadow != null) {
			WebGpuDevice.upload(dst, to, src.shadow, so, len);
			return;
		}
		if (dst.shadow != null || dst.cpu != 0) {
			WebGpuDevice.warnOnce("copyToBuffer from a buffer without a mirror leaves the destination mirror stale");
		}
		if ((so & 3) == 0 && (to & 3) == 0 && (len & 3) == 0) {
			WebGpuNatives.copyBuffer(src.handle, so, dst.handle, to, len);
		} else {
			WebGpuDevice.warnOnce("unaligned copyToBuffer skipped (offset " + so + "/" + to + ", len " + len + ")");
		}
	}

	@Override
	public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height) {
		if (destination instanceof WebGpuTexture tex) {
			WebGpuDevice.writeTex(tex, source, mipLevel, depthOrLayer, destX, destY, width, height);
		}
	}

	@Override
	public void copyBufferToTexture(
		GpuBufferSlice source,
		int sourceX,
		int sourceY,
		int sourceWidth,
		int sourceHeight,
		GpuTexture destination,
		int destinationX,
		int destinationY,
		int copyWidth,
		int copyHeight,
		int mipLevel,
		int arrayLayer
	) {
		if (!(source.buffer() instanceof WebGpuBuffer src) || !(destination instanceof WebGpuTexture tex)) {
			return;
		}
		int bpp = Math.max(1, tex.getFormat().blockSize());
		int base = (int) source.offset();
		if (src.cpu != 0) {
			long addr = src.cpu + base + ((long) sourceY * sourceWidth + sourceX) * bpp;
			WebGpuNatives.writeTextureMem(tex.handle, mipLevel, destinationX, destinationY, arrayLayer, copyWidth, copyHeight, (int) addr, sourceWidth * bpp);
			return;
		}
		if (src.shadow == null) {
			WebGpuDevice.warnOnce("copyBufferToTexture from a buffer without a shadow (" + tex.getLabel() + ") skipped");
			return;
		}
		if (sourceX == 0 && sourceWidth == copyWidth) {
			int start = base + sourceY * sourceWidth * bpp;
			ByteBuffer view = ByteBuffer.wrap(src.shadow, start, copyWidth * copyHeight * bpp).slice();
			WebGpuDevice.writeTex(tex, view, mipLevel, arrayLayer, destinationX, destinationY, copyWidth, copyHeight);
			return;
		}
		byte[] packed = new byte[copyWidth * copyHeight * bpp];
		int rowBytes = copyWidth * bpp;
		for (int r = 0; r < copyHeight; r++) {
			int from = base + ((sourceY + r) * sourceWidth + sourceX) * bpp;
			System.arraycopy(src.shadow, from, packed, r * rowBytes, rowBytes);
		}
		WebGpuDevice.writeTex(tex, ByteBuffer.wrap(packed), mipLevel, arrayLayer, destinationX, destinationY, copyWidth, copyHeight);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
		copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y, int width, int height) {
		if (!(source instanceof WebGpuTexture tex) || !(destination instanceof WebGpuBuffer dst)) {
			callback.run();
			return;
		}
		int bpp = Math.max(1, tex.getFormat().blockSize());
		int off = (int) offset;
		WebGpuNatives.readTexture(tex.handle, mipLevel, x, y, width, height, bpp, data -> {
			if (MemoryUtil.LINEAR) {
				int n = Math.min(data.getLength(), (int) dst.size() - off);
				if (n > 0) {
					WebGpuNatives.copyToMem((int) (dst.mirror() + off), data, n);
				}
				callback.run();
				return;
			}
			byte[] shadow = dst.shadow();
			int n = Math.min(data.getLength(), shadow.length - off);
			for (int i = 0; i < n; i++) {
				shadow[off + i] = data.get(i);
			}
			callback.run();
		});
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width, int height) {
		if (source instanceof WebGpuTexture src && destination instanceof WebGpuTexture dst) {
			WebGpuNatives.copyTexture(src.handle, mipLevel, sourceX, sourceY, dst.handle, mipLevel, destX, destY, width, height);
		}
	}

	@Override
	public GpuFence createFence() {
		return new WebGpuFence();
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}

	WebGpuBuffer fanIndices(int vertices) {
		if (fanIndices == null || fanVertices < vertices) {
			int n = Math.max(vertices, Math.max(64, fanVertices * 2));
			ByteBuffer data = ByteBuffer.allocate((n - 2) * 3 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < n - 2; i++) {
				data.putInt(0).putInt(i + 1).putInt(i + 2);
			}
			data.flip();
			if (fanIndices != null) {
				fanIndices.close();
			}
			fanIndices = (WebGpuBuffer) device.createBuffer(() -> "fan indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, data);
			fanVertices = n;
		}
		return fanIndices;
	}
}

final class CommandStream {
	private static final int CAPACITY = 16384;
	private final long base = MemoryUtil.nmemAlloc(CAPACITY * 4L);
	private int n;

	void reserve(JSObject pass, int ints) {
		if (n + ints > CAPACITY) {
			flush(pass);
		}
	}

	void put(int v) {
		MemoryUtil.memPutInt(base + ((long) n << 2), v);
		n++;
	}

	void flush(JSObject pass) {
		if (n > 0) {
			WebGpuNatives.replay(pass, (int) base, n);
			n = 0;
		}
	}
}

final class WebGpuTransientMemory implements TransientMemory {
	private static final int BLOCK = 4 << 20;
	private final WebGpuDevice device;
	private final List<WebGpuBuffer> blocks = new ArrayList<>();
	private int[] offsets = new int[8];
	private int[] lastUsed = new int[8];
	private int frame;
	private static final int IDLE_FRAMES = 600;

	record Region(WebGpuBuffer block, int offset, int size) {
	}

	WebGpuTransientMemory(WebGpuDevice device) {
		this.device = device;
	}

	void rotate() {
		frame++;
		int count = blocks.size();
		for (int i = 0; i < count; i++) {
			offsets[i] = 0;
		}
		for (int i = count - 1; i > 0; i--) {
			if (frame - lastUsed[i] > IDLE_FRAMES) {
				WebGpuBuffer b = blocks.remove(i);
				int tail = blocks.size() - i;
				if (tail > 0) {
					System.arraycopy(offsets, i + 1, offsets, i, tail);
					System.arraycopy(lastUsed, i + 1, lastUsed, i, tail);
				}
				b.close();
				b.shadow = null;
				if (b.cpu != 0) {
					MemoryUtil.nmemFree(b.cpu);
					b.cpu = 0;
				}
				WebGpuNatives.destroy(b.handle);
				System.out.println("[26.3-web] transient block released: " + (b.size() >> 20) + " MB, " + blocks.size() + " left");
			}
		}
	}

	private static int align(int value, long alignment) {
		int a = (int) Math.max(4, alignment);
		return (a & (a - 1)) == 0 ? (value + a - 1) & ~(a - 1) : (value + a - 1) / a * a;
	}

	Region region(long size, long alignment) {
		int need = align((int) Math.max(1, size), 4);
		int count = blocks.size();
		for (int i = 0; i < count; i++) {
			WebGpuBuffer b = blocks.get(i);
			int start = align(offsets[i], alignment);
			if (start + need <= b.size()) {
				offsets[i] = start + need;
				lastUsed[i] = frame;
				return new Region(b, start, need);
			}
		}
		int cap = Math.max(BLOCK, align(need, 256));
		JSObject handle = WebGpuNatives.createBuffer(
			cap,
			WebGpuNatives.BUF_COPY_DST | WebGpuNatives.BUF_COPY_SRC | WebGpuNatives.BUF_VERTEX | WebGpuNatives.BUF_INDEX | WebGpuNatives.BUF_UNIFORM | WebGpuNatives.BUF_STORAGE,
			"transient block " + count
		);
		int usage = GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_UNIFORM
			| GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE;
		WebGpuBuffer block = new WebGpuBuffer(usage, cap, handle, false, MemoryUtil.LINEAR ? null : new byte[cap]);
		if (MemoryUtil.LINEAR) {
			block.cpu = MemoryUtil.nmemAlloc(cap);
		}
		if (count == offsets.length) {
			int nextLen = offsets.length * 2;
			offsets = java.util.Arrays.copyOf(offsets, nextLen);
			lastUsed = java.util.Arrays.copyOf(lastUsed, nextLen);
		}
		blocks.add(block);
		offsets[count] = need;
		lastUsed[count] = frame;
		return new Region(block, 0, need);
	}

	private static GpuBufferSlice slice(Region r, long size) {
		return new GpuBufferSlice(r.block(), r.offset(), size);
	}

	private static void flush(Region r, int len) {
		if (r.block().cpu != 0) {
			WebGpuNatives.writeBufferMem(r.block().handle, r.offset(), (int) (r.block().cpu + r.offset()), align(len, 4));
			return;
		}
		WebGpuNatives.writeBuffer(r.block().handle, r.offset(), WebGpuDevice.toJs(r.block().shadow, r.offset(), align(len, 4)));
	}

	private static ByteBuffer cpuView(Region r, int off, int size) {
		if (r.block().cpu != 0) {
			return MemoryUtil.memByteBuffer(r.block().cpu + r.offset() + off, size);
		}
		return ByteBuffer.wrap(r.block().shadow, r.offset() + off, size).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN);
	}

	@Override
	public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
		return ByteBuffer.allocate((int) Math.max(size, 1)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		Region r = region(size, alignment);
		ByteBuffer view = cpuView(r, 0, (int) size);
		return new GpuBufferSlice.MappedView(slice(r, size), view, () -> flush(r, (int) size));
	}

	@Override
	public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return slice(region(size, alignment), size);
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return allocateStaging(size, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		return uploadGpu(data, alignment, usage, minimumAllocation, elementSize);
	}

	@Override
	public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		int total = 0;
		for (ByteBuffer b : data) {
			total = align(total + b.remaining(), alignment);
		}
		Region r = region(Math.max(total, 4), alignment);
		int off = 0;
		for (ByteBuffer b : data) {
			int n = b.remaining();
			MemoryUtil.memCopy(b.duplicate(), cpuView(r, off, n));
			off = align(off + n, alignment);
		}
		flush(r, total);
		return slice(r, total);
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
		return multiUploadGpu(data, alignment, usage);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
		List<GpuBufferSlice> out = new ArrayList<>(data.size());
		for (ByteBuffer b : data) {
			out.add(uploadGpu(List.of(b), alignment, usage, b.remaining(), 1L));
		}
		return out;
	}
}

final class WebGpuRenderPass implements RenderPassBackend {
	static final class BindGroup {
		final JSObject group;
		final List<GpuBuffer> buffers = new ArrayList<>(4);
		final List<WebGpuTextureView> views = new ArrayList<>(4);

		final int id;
		int[] parts;
		int partsLen;

		BindGroup(JSObject group) {
			this.group = group;
			this.id = WebGpuNatives.put(group);
		}

		int verifiedAt = -1;

		boolean alive() {
			if (verifiedAt == WebGpuIds.closeGeneration) {
				return true;
			}
			boolean ok = aliveSlow();
			if (ok) {
				verifiedAt = WebGpuIds.closeGeneration;
			}
			return ok;
		}

		private boolean aliveSlow() {
			for (GpuBuffer b : buffers) {
				if (b.isClosed()) {
					return false;
				}
			}
			for (WebGpuTextureView v : views) {
				if (v.isClosed()) {
					return false;
				}
			}
			return true;
		}
	}

	private final WebGpuCommandEncoder encoder;
	private final WebGpuDevice device;
	private final int uniformAlignment;
	private final int alignMask;
	private JSObject pass;
	String depthFormat;
	String[] colorFormats;
	int colorCount;
	int width;
	int height;
	private boolean hasDefaultScissor;
	private int defSx, defSy, defSw, defSh;
	private boolean hasScissor;
	private int sx, sy, sw, sh;
	private boolean hasLastScissor;
	private int lastSx, lastSy, lastSw, lastSh;
	private boolean scissorDirty = true;
	@Nullable
	private WebGpuPipeline pipeline;
	private WebGpuPipeline.Variant bound;
	private Object[] uniformValues = new Object[16];
	private int uniformLen;
	private final GpuBufferSlice[] vertexBuffers = new GpuBufferSlice[16];
	private boolean vertexBuffersDirty = true;
	@Nullable
	private GpuBuffer indexBuffer;
	private IndexType indexType = IndexType.SHORT;
	private boolean indexDirty = true;
	private boolean bindingsDirty = true;
	private final int[] offsets = new int[64];
	private int lastBoundGroupId = -1;
	private int lastBoundDyn = -1;
	private final int[] lastBoundOffsets = new int[64];
	private final int[] keyParts = new int[256];
	private int texturesVersion;
	private int maskVersion = -1;
	private WebGpuPipeline maskPipeline;
	private long cachedDepthMask;

	WebGpuRenderPass(WebGpuCommandEncoder encoder) {
		this.encoder = encoder;
		this.device = encoder.device;
		int align = encoder.device.uniformAlignment;
		this.uniformAlignment = align;
		this.alignMask = align > 0 && (align & (align - 1)) == 0 ? (align - 1) : -1;
	}

	void reset(JSObject pass, String depthFormat, String[] colorFormats, int colorCount, int width, int height) {
		this.pass = pass;
		this.depthFormat = depthFormat;
		this.colorFormats = colorFormats;
		this.colorCount = colorCount;
		this.width = width;
		this.height = height;
		this.hasDefaultScissor = false;
		this.defSx = 0;
		this.defSy = 0;
		this.defSw = 0;
		this.defSh = 0;
		this.hasScissor = false;
		this.sx = 0;
		this.sy = 0;
		this.sw = 0;
		this.sh = 0;
		this.hasLastScissor = false;
		this.lastSx = 0;
		this.lastSy = 0;
		this.lastSw = 0;
		this.lastSh = 0;
		this.scissorDirty = true;
		this.pipeline = null;
		this.bound = null;
		if (this.uniformLen > 0) {
			java.util.Arrays.fill(this.uniformValues, 0, this.uniformLen, null);
			this.uniformLen = 0;
		}
		java.util.Arrays.fill(this.vertexBuffers, null);
		this.vertexBuffersDirty = true;
		this.indexBuffer = null;
		this.indexType = IndexType.SHORT;
		this.indexDirty = true;
		this.bindingsDirty = true;
		this.lastBoundGroupId = -1;
		this.lastBoundDyn = -1;
		this.maskPipeline = null;
		this.maskVersion = -1;
		this.cachedDepthMask = 0L;
		this.texturesVersion++;
	}

	void release() {
		this.pass = null;
		this.pipeline = null;
		this.bound = null;
		this.maskPipeline = null;
		this.indexBuffer = null;
		if (this.uniformLen > 0) {
			java.util.Arrays.fill(this.uniformValues, 0, this.uniformLen, null);
			this.uniformLen = 0;
		}
		java.util.Arrays.fill(this.vertexBuffers, null);
	}

	void setDefaultScissor(int x, int y, int w, int h) {
		hasDefaultScissor = true;
		defSx = x;
		defSy = y;
		defSw = w;
		defSh = h;
		enableScissor(x, y, w, h);
	}

	private boolean isUniformAligned(int off) {
		return alignMask >= 0 ? (off & alignMask) == 0 : (off % uniformAlignment == 0);
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(BackendRenderPipeline pipeline) {
		WebGpuPipeline p = (WebGpuPipeline) pipeline;
		if (this.pipeline != p) {
			this.pipeline = p;
			int len = p.uniformNames.length;
			if (uniformValues.length < len) {
				uniformValues = new Object[len];
			} else {
				int clearEnd = Math.max(uniformLen, len);
				if (clearEnd > 0) {
					java.util.Arrays.fill(uniformValues, 0, clearEnd, null);
				}
			}
			uniformLen = len;
			bound = null;
			bindingsDirty = true;
			vertexBuffersDirty = true;
			texturesVersion++;
		}
	}

	@Override
	public void setUniform(int index, @Nullable Object value) {
		if (index < 0 || index >= uniformLen) {
			return;
		}
		if (uniformValues[index] != value) {
			uniformValues[index] = value;
			bindingsDirty = true;
			if (value == null || value instanceof TextureViewAndSampler) {
				texturesVersion++;
			}
		}
	}

	@Override
	public void pushConstants(ByteBuffer value) {
	}

	@Override
	public void enableScissor(int x, int y, int w, int h) {
		if (!hasScissor || sx != x || sy != y || sw != w || sh != h) {
			hasScissor = true;
			sx = x;
			sy = y;
			sw = w;
			sh = h;
			scissorDirty = true;
		}
	}

	@Override
	public void disableScissor() {
		if (hasDefaultScissor) {
			enableScissor(defSx, defSy, defSw, defSh);
		} else if (hasScissor) {
			hasScissor = false;
			scissorDirty = true;
		}
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
		GpuBufferSlice old = vertexBuffers[slot];
		if (old != vertexBuffer && (old == null || vertexBuffer == null || old.buffer() != vertexBuffer.buffer() || old.offset() != vertexBuffer.offset()
			|| old.length() != vertexBuffer.length())) {
			vertexBuffersDirty = true;
		}
		vertexBuffers[slot] = vertexBuffer;
	}

	@Override
	public void setIndexBuffer(@Nullable GpuBuffer indexBuffer, IndexType indexType) {
		if (this.indexBuffer != indexBuffer || this.indexType != indexType) {
			indexDirty = true;
		}
		this.indexBuffer = indexBuffer;
		this.indexType = indexType;
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
		if (prepare(indexType)) {
			cmdDrawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
			encoder.countDraw();
		}
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		throw new UnsupportedOperationException("WebGPU backend does not support the multiDrawDirectInterleaved device feature");
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		throw new UnsupportedOperationException("WebGPU backend does not support the multiDrawDirectSeparate device feature");
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		throw new UnsupportedOperationException("WebGPU backend does not support the drawIndirect device feature");
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (pipeline != null && pipeline.topology == PrimitiveTopology.TRIANGLE_FAN) {
			if (vertexCount < 3) {
				return;
			}
			GpuBuffer savedIndex = indexBuffer;
			IndexType savedType = indexType;
			setIndexBuffer(encoder.fanIndices(vertexCount), IndexType.INT);
			if (prepare(IndexType.INT)) {
				cmdDrawIndexed((vertexCount - 2) * 3, instanceCount, 0, firstVertex, firstInstance);
				encoder.countDraw();
			}
			indexBuffer = savedIndex;
			indexType = savedType;
			indexDirty = true;
			return;
		}
		if (prepare(null)) {
			cmd4(WebGpuNatives.CMD_DRAW, vertexCount, instanceCount, firstVertex, firstInstance);
			encoder.countDraw();
		}
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		throw new UnsupportedOperationException("WebGPU backend does not support the multiDrawDirectInterleaved device feature");
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		throw new UnsupportedOperationException("WebGPU backend does not support the multiDrawDirectSeparate device feature");
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		throw new UnsupportedOperationException("WebGPU backend does not support the drawIndirect device feature");
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}

	@Nullable
	private Object value(int uniformIndex) {
		return uniformIndex >= 0 && uniformIndex < uniformLen ? uniformValues[uniformIndex] : null;
	}

	@Nullable
	private TextureViewAndSampler texture(int uniformIndex) {
		return value(uniformIndex) instanceof TextureViewAndSampler t ? t : null;
	}

	@Nullable
	private GpuBufferSlice bufferSlice(int uniformIndex) {
		return value(uniformIndex) instanceof GpuBufferSlice s && s.buffer() instanceof WebGpuBuffer b && !b.isClosed() ? s : null;
	}

	private long depthTextureMaskCached() {
		if (maskPipeline != pipeline || maskVersion != texturesVersion) {
			cachedDepthMask = depthTextureMask();
			maskPipeline = pipeline;
			maskVersion = texturesVersion;
		}
		return cachedDepthMask;
	}

	private long depthTextureMask() {
		long mask = 0;
		int texIndex = 0;
		for (Map.Entry<Integer, Integer> e : pipeline.kinds.entrySet()) {
			if (e.getValue() == WebGpuPipeline.KIND_TEXTURE_2D) {
				int binding = e.getKey();
				for (int i = 0; i < pipeline.uniformBindings.length; i++) {
					if (pipeline.uniformBindings[i] == binding) {
						TextureViewAndSampler t = texture(i);
						if (t != null && t.view().texture().getFormat().hasDepthAspect()) {
							mask |= 1L << texIndex;
						}
						break;
					}
				}
				texIndex++;
			}
		}
		return mask;
	}

	private boolean prepare(@Nullable IndexType type) {
		if (pipeline == null || pipeline.isClosed()) {
			return false;
		}
		long depthMask = depthTextureMaskCached();
		WebGpuPipeline.Variant v = pipeline.variant(depthFormat, colorFormats, depthMask, type == null ? IndexType.SHORT : type);
		if (v != bound) {
			CommandStream c = encoder.cmds;
			c.reserve(pass, 2);
			c.put(WebGpuNatives.CMD_PIPELINE);
			c.put(v.pipelineId);
			bound = v;
			bindingsDirty = true;
			lastBoundGroupId = -1;
			lastBoundDyn = -1;
		}
		if (bindingsDirty) {
			bindGroup(v);
			bindingsDirty = false;
		}
		if (vertexBuffersDirty) {
			for (int slot = 0; slot < pipeline.vertexSlots; slot++) {
				GpuBufferSlice s = vertexBuffers[slot];
				if (s != null && pipeline.slotUsed[slot] && s.buffer() instanceof WebGpuBuffer b) {
					cmd4(WebGpuNatives.CMD_VERTEX_BUFFER, slot, b.jsId, (int) s.offset(), (int) s.length());
				}
			}
			vertexBuffersDirty = false;
		}
		if (type != null && indexDirty && indexBuffer instanceof WebGpuBuffer ib) {
			cmd4(WebGpuNatives.CMD_INDEX_BUFFER, ib.jsId, type == IndexType.INT ? 1 : 0, 0, (int) ib.size() & ~1);
			indexDirty = false;
		}
		if (scissorDirty) {
			applyScissor();
			scissorDirty = false;
		}
		return true;
	}

	private void applyScissor() {
		int x, y, w, h;
		if (!hasScissor) {
			x = 0;
			y = 0;
			w = width;
			h = height;
		} else {
			x = Math.max(0, Math.min(sx, width));
			w = Math.max(0, Math.min(sw, width - x));
			y = Math.max(0, Math.min(sy, height));
			h = Math.max(0, Math.min(sh + Math.min(0, sy), height - y));
		}
		if (hasLastScissor && x == lastSx && y == lastSy && w == lastSw && h == lastSh) {
			return;
		}
		hasLastScissor = true;
		lastSx = x;
		lastSy = y;
		lastSw = w;
		lastSh = h;
		cmd4(WebGpuNatives.CMD_SCISSOR, x, y, w, h);
	}

	private void bindGroup(WebGpuPipeline.Variant v) {
		int[] parts = keyParts;
		int n = 0;
		int dyn = 0;
		for (int i = 0; i < v.bindings.length; i++) {
			int ui = v.uniformIndex[i];
			switch (v.kindOf[i]) {
				case WebGpuPipeline.KIND_UNIFORM -> {
					GpuBufferSlice s = bufferSlice(ui);
					if (s != null) {
						WebGpuBuffer b = (WebGpuBuffer) s.buffer();
						int off = (int) s.offset();
						boolean aligned = isUniformAligned(off);
						offsets[dyn++] = aligned ? off : 0;
						parts[n++] = b.id;
						parts[n++] = aligned ? -1 : off;
						parts[n++] = (int) s.length();
					} else {
						offsets[dyn++] = 0;
						parts[n++] = 0;
						parts[n++] = 0;
						parts[n++] = 0;
					}
				}
				case WebGpuPipeline.KIND_STORAGE -> {
					GpuBufferSlice s = bufferSlice(ui);
					if (s != null) {
						parts[n++] = ((WebGpuBuffer) s.buffer()).id;
						parts[n++] = (int) s.offset();
						parts[n++] = (int) s.length();
					} else {
						parts[n++] = 0;
						parts[n++] = 0;
						parts[n++] = 0;
					}
				}
				case WebGpuPipeline.KIND_SAMPLER -> {
					TextureViewAndSampler t = texture(ui);
					parts[n++] = t == null || (v.depthTexture[i] && ((WebGpuSampler) t.sampler()).filtering) ? 0 : ((WebGpuSampler) t.sampler()).id;
				}
				default -> {
					TextureViewAndSampler t = texture(ui);
					parts[n++] = t == null || t.view().isClosed() ? 0 : ((WebGpuTextureView) t.view()).id;
				}
			}
		}
		BindGroup group = v.lastGroup;
		if (group != null && group.alive() && v.lastPartsLen == n && sameParts(parts, v.lastParts, n)) {
			setBindGroup(group, dyn);
			return;
		}
		long h = 0xcbf29ce484222325L;
		for (int p = 0; p < n; p++) {
			h = (h ^ parts[p]) * 0x100000001b3L;
		}
		group = v.groups.get(h);
		if (group == null || !group.alive() || group.partsLen != n || !sameParts(parts, group.parts, n)) {
			if (group != null) {
				WebGpuNatives.dropLater(group.id);
			}
			group = buildGroup(v);
			group.parts = java.util.Arrays.copyOf(parts, n);
			group.partsLen = n;
			if (v.groups.size() > 2048) {
				v.clearGroups();
			}
			v.groups.put(h, group);
		}
		if (v.lastParts == null || v.lastParts.length < n) {
			v.lastParts = new int[Math.max(n, 16)];
		}
		System.arraycopy(parts, 0, v.lastParts, 0, n);
		v.lastPartsLen = n;
		v.lastGroup = group;
		setBindGroup(group, dyn);
	}

	private void setBindGroup(final BindGroup group, final int dyn) {
		if (group.id == lastBoundGroupId && dyn == lastBoundDyn) {
			boolean same = true;
			for (int i = 0; i < dyn; i++) {
				if (offsets[i] != lastBoundOffsets[i]) {
					same = false;
					break;
				}
			}
			if (same) {
				return;
			}
		}
		lastBoundGroupId = group.id;
		lastBoundDyn = dyn;
		for (int i = 0; i < dyn; i++) {
			lastBoundOffsets[i] = offsets[i];
		}
		CommandStream c = encoder.cmds;
		c.reserve(pass, 3 + dyn);
		c.put(WebGpuNatives.CMD_BIND_GROUP);
		c.put(group.id);
		c.put(dyn);
		for (int i = 0; i < dyn; i++) {
			c.put(offsets[i]);
		}
	}

	void flush() {
		encoder.cmds.flush(pass);
	}

	private void cmdDrawIndexed(int count, int instances, int first, int baseVertex, int firstInstance) {
		CommandStream c = encoder.cmds;
		c.reserve(pass, 6);
		c.put(WebGpuNatives.CMD_DRAW_INDEXED);
		c.put(count);
		c.put(instances);
		c.put(first);
		c.put(baseVertex);
		c.put(firstInstance);
	}

	private void cmd4(int op, int a, int b, int d, int e) {
		CommandStream c = encoder.cmds;
		c.reserve(pass, 5);
		c.put(op);
		c.put(a);
		c.put(b);
		c.put(d);
		c.put(e);
	}

	private static boolean sameParts(final int[] a, final int[] b, final int n) {
		for (int i = 0; i < n; i++) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
	}

	private String nameOf(int uniformIndex, int binding) {
		return uniformIndex >= 0 ? pipeline.uniformNames[uniformIndex] : "@" + binding;
	}

	private BindGroup buildGroup(WebGpuPipeline.Variant v) {
		WebGpuNatives.bindGroupBegin();
		List<GpuBuffer> buffers = new ArrayList<>(4);
		List<WebGpuTextureView> views = new ArrayList<>(4);
		for (int i = 0; i < v.bindings.length; i++) {
			int binding = v.bindings[i];
			int ui = v.uniformIndex[i];
			switch (v.kindOf[i]) {
				case WebGpuPipeline.KIND_UNIFORM -> {
					GpuBufferSlice s = bufferSlice(ui);
					if (s != null) {
						WebGpuBuffer b = (WebGpuBuffer) s.buffer();
						int off = (int) s.offset();
						boolean dynamic = isUniformAligned(off);
						WebGpuNatives.bindGroupBuffer(binding, b.handle, dynamic ? 0 : off, (int) s.length());
						buffers.add(b);
					} else {
						WebGpuDevice.warnOnce("uniform '" + nameOf(ui, binding) + "' never set for " + pipeline.info.name() + "; bound zeros");
						WebGpuNatives.bindGroupBuffer(binding, device.dummyUniform.handle, 0, 65536);
					}
				}
				case WebGpuPipeline.KIND_STORAGE -> {
					GpuBufferSlice s = bufferSlice(ui);
					if (s != null) {
						WebGpuBuffer b = (WebGpuBuffer) s.buffer();
						WebGpuNatives.bindGroupBuffer(binding, b.handle, (int) s.offset(), ((int) s.length() + 3) & ~3);
						buffers.add(b);
					} else {
						WebGpuNatives.bindGroupBuffer(binding, device.dummyUniform.handle, 0, 65536);
					}
				}
				case WebGpuPipeline.KIND_SAMPLER -> {
					TextureViewAndSampler t = texture(ui);
					boolean useDummy = t == null || (v.depthTexture[i] && ((WebGpuSampler) t.sampler()).filtering);
					WebGpuNatives.bindGroupSampler(binding, useDummy ? device.dummySampler.handle : ((WebGpuSampler) t.sampler()).handle);
				}
				case WebGpuPipeline.KIND_TEXTURE_CUBE -> {
					TextureViewAndSampler t = texture(ui);
					WebGpuTextureView view = t == null ? null : (WebGpuTextureView) t.view();
					boolean ok = view != null && !view.isClosed() && view.tex.isCube();
					WebGpuNatives.bindGroupTexture(binding, ok ? view.handle : device.dummyCubeView.handle);
					if (ok) {
						views.add(view);
					}
				}
				default -> {
					TextureViewAndSampler t = texture(ui);
					WebGpuTextureView view = t == null ? null : (WebGpuTextureView) t.view();
					boolean ok = view != null && !view.isClosed() && !view.tex.isCube();
					if (!ok && t == null) {
						WebGpuDevice.warnOnce("texture '" + nameOf(ui, binding) + "' never set for " + pipeline.info.name() + "; bound a black texel");
					}
					WebGpuNatives.bindGroupTexture(binding, ok ? view.handle : device.dummyView.handle);
					if (ok) {
						views.add(view);
					}
				}
			}
		}
		BindGroup g = new BindGroup(WebGpuNatives.bindGroupEnd(v.layout));
		g.buffers.addAll(buffers);
		g.views.addAll(views);
		return g;
	}
}
