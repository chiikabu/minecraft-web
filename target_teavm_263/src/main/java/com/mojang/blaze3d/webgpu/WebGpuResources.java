// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.webgpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.teavm.jso.JSObject;

final class WebGpuIds {
	private static int next = 1;
	static int closeGeneration;

	static int next() {
		return next++;
	}

	private WebGpuIds() {
	}
}

final class WebGpuTexture extends BaseGpuTexture {
	final JSObject handle;
	final int id = WebGpuIds.next();
	final String wgpuFormat;
	boolean boundInGroup;
	private boolean closed;

	WebGpuTexture(int usage, String label, GpuFormat format, int width, int height, int depth, int mips, JSObject handle, String wgpuFormat) {
		super(usage, label, format, width, height, depth, mips);
		this.handle = handle;
		this.wgpuFormat = wgpuFormat;
	}

	boolean isCube() {
		return (usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 && getDepthOrLayers() == 6;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			if (boundInGroup) {
				WebGpuIds.closeGeneration++;
			}
			WebGpuNatives.destroy(handle);
		}
	}

	@Override
	public boolean isClosed() {
		return closed;
	}
}

final class WebGpuTextureView extends BaseGpuTextureView {
	final JSObject handle;
	private JSObject attachmentHandle;
	final int id = WebGpuIds.next();
	final WebGpuTexture tex;
	boolean boundInGroup;
	private boolean closed;

	WebGpuTextureView(WebGpuTexture texture, int baseMip, int mips) {
		super(texture, baseMip, mips);
		this.tex = texture;
		this.handle = WebGpuNatives.createView(texture.handle, baseMip, mips, texture.isCube() ? "cube" : "2d");
	}

	JSObject attachmentHandle() {
		if (attachmentHandle == null) {
			attachmentHandle = WebGpuNatives.createView(tex.handle, baseMipLevel(), 1, "2d");
		}
		return attachmentHandle;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			if (boundInGroup) {
				WebGpuIds.closeGeneration++;
			}
		}
	}

	@Override
	public boolean isClosed() {
		return closed || tex.isClosed();
	}
}

final class WebGpuBuffer extends BaseGpuBuffer {
	final JSObject handle;
	final int id = WebGpuIds.next();
	final int jsId;
	final boolean owned;
	byte[] shadow;
	long cpu;
	boolean boundInGroup;
	private boolean closed;

	WebGpuBuffer(int usage, long size, JSObject handle, boolean owned, byte[] shadow) {
		super(usage, size);
		this.handle = handle;
		this.jsId = WebGpuNatives.put(handle);
		this.owned = owned;
		this.shadow = shadow;
	}

	byte[] shadow() {
		if (shadow == null) {
			shadow = new byte[((int) size() + 3) & ~3];
		}
		return shadow;
	}

	boolean mirrored() {
		return cpu != 0 || shadow != null;
	}

	long mirror() {
		if (cpu == 0) {
			cpu = org.lwjgl.system.MemoryUtil.nmemCalloc(Math.max(4, ((int) size() + 3) & ~3), 1);
		}
		return cpu;
	}

	ByteBuffer cpuView(int off, int len) {
		if (cpu != 0 || org.lwjgl.system.MemoryUtil.LINEAR) {
			return org.lwjgl.system.MemoryUtil.memByteBuffer(mirror() + off, len);
		}
		return ByteBuffer.wrap(shadow(), off, len).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN);
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			if (boundInGroup) {
				WebGpuIds.closeGeneration++;
			}
			WebGpuNatives.dropLater(jsId);
			if (owned) {
				WebGpuNatives.destroy(handle);
				if (cpu != 0) {
					org.lwjgl.system.MemoryUtil.nmemFree(cpu);
					cpu = 0;
				}
			}
		}
	}

	@Override
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		int off = (int) offset;
		int len = (int) length;
		if (read) {
			ByteBuffer view = cpuView(off, len);
			return new GpuBufferSlice.MappedView(slice(offset, length), view, () -> {
				if (write && !closed) {
					WebGpuDevice.write(this, off, cpuView(off, len));
				}
			});
		}
		if (cpu != 0) {
			return new GpuBufferSlice.MappedView(slice(offset, length), cpuView(off, len), () -> {
				if (!closed) {
					WebGpuDevice.uploadLinear(this, off, cpu + off, len);
				}
			});
		}
		if (org.lwjgl.system.MemoryUtil.LINEAR) {
			long tmp = org.lwjgl.system.MemoryUtil.nmemAlloc(len);
			return new GpuBufferSlice.MappedView(slice(offset, length), org.lwjgl.system.MemoryUtil.memByteBuffer(tmp, len), () -> {
				if (!closed) {
					WebGpuDevice.uploadLinear(this, off, tmp, len);
				}
				org.lwjgl.system.MemoryUtil.nmemFree(tmp);
			});
		}
		if (shadow != null) {
			ByteBuffer view = ByteBuffer.wrap(shadow, off, len).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN);
			return new GpuBufferSlice.MappedView(slice(offset, length), view, () -> {
				if (!closed) {
					WebGpuDevice.upload(this, off, shadow, off, len);
				}
			});
		}
		byte[] data = new byte[len];
		return new GpuBufferSlice.MappedView(slice(offset, length), ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN), () -> {
			if (!closed) {
				WebGpuDevice.upload(this, off, data, 0, len);
			}
		});
	}
}

final class WebGpuSampler implements GpuSampler {
	final JSObject handle;
	final int id = WebGpuIds.next();
	private final AddressMode u;
	private final AddressMode v;
	private final FilterMode min;
	private final FilterMode mag;
	private final int aniso;
	private final OptionalDouble lod;
	final boolean filtering;
	boolean boundInGroup;
	private boolean closed;

	WebGpuSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int aniso, OptionalDouble lod) {
		this.u = u;
		this.v = v;
		this.min = min;
		this.mag = mag;
		this.aniso = aniso;
		this.lod = lod;
		this.filtering = min == FilterMode.LINEAR || mag == FilterMode.LINEAR;
		this.handle = WebGpuNatives.createSampler(
			address(u), address(v), filter(mag), filter(min), aniso, lod.isPresent(), lod.isPresent() ? (float) lod.getAsDouble() : 0f
		);
	}

	private static String address(AddressMode mode) {
		return mode == AddressMode.REPEAT ? "repeat" : "clamp-to-edge";
	}

	private static String filter(FilterMode mode) {
		return mode == FilterMode.LINEAR ? "linear" : "nearest";
	}

	@Override
	public AddressMode getAddressModeU() {
		return u;
	}

	@Override
	public AddressMode getAddressModeV() {
		return v;
	}

	@Override
	public FilterMode getMinFilter() {
		return min;
	}

	@Override
	public FilterMode getMagFilter() {
		return mag;
	}

	@Override
	public int getMaxAnisotropy() {
		return aniso;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return lod;
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			if (boundInGroup) {
				WebGpuIds.closeGeneration++;
			}
		}
	}
}

final class WebGpuFence implements GpuFence {
	@Override
	public void close() {
	}

	@Override
	public boolean awaitCompletion(long timeoutNS) {
		return true;
	}
}

final class WebGpuQueryPool implements GpuQueryPool {
	private final int size;

	WebGpuQueryPool(int size) {
		this.size = size;
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public OptionalLong getValue(int index) {
		return OptionalLong.empty();
	}

	@Override
	public OptionalLong[] getValues(int index, int count) {
		OptionalLong[] out = new OptionalLong[count];
		for (int i = 0; i < count; i++) {
			out[i] = OptionalLong.empty();
		}
		return out;
	}

	@Override
	public void close() {
	}
}
