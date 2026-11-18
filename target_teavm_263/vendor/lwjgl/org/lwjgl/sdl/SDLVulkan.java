// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

public final class SDLVulkan {
	private static final String UNAVAILABLE = "Vulkan is not available in the browser";

	private SDLVulkan() {
	}

	public static boolean SDL_Vulkan_LoadLibrary(CharSequence path) {
		return SDLError.SDL_SetError(UNAVAILABLE);
	}

	public static void SDL_Vulkan_UnloadLibrary() {
	}

	public static long SDL_Vulkan_GetVkGetInstanceProcAddr() {
		SDLError.set(UNAVAILABLE);
		return 0L;
	}

	public static PointerBuffer SDL_Vulkan_GetInstanceExtensions() {
		SDLError.set(UNAVAILABLE);
		return null;
	}

	public static boolean SDL_Vulkan_CreateSurface(long window, VkInstance instance, VkAllocationCallbacks allocator, LongBuffer surface) {
		return SDLError.SDL_SetError(UNAVAILABLE);
	}

	public static void SDL_Vulkan_DestroySurface(VkInstance instance, long surface, VkAllocationCallbacks allocator) {
	}

	public static boolean SDL_Vulkan_GetPresentationSupport(VkInstance instance, VkPhysicalDevice physicalDevice, int queueFamilyIndex) {
		return false;
	}
}
