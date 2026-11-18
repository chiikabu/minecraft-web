// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.webgpu;

import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Collection;
import java.util.List;

final class WebGpuSurface implements GpuSurfaceBackend {
	private boolean configured;

	@Override
	public void configure(GpuSurface.Configuration config) throws SurfaceException {
		configured = true;
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (!configured) {
			throw new SurfaceException("surface not configured");
		}
	}

	@Override
	public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
		if (textureView instanceof WebGpuTextureView view && !view.isClosed()) {
			WebGpuNatives.blit(view.handle);
		}
	}

	@Override
	public void present() {
	}

	@Override
	public void close() {
		configured = false;
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.FIFO);
	}
}
