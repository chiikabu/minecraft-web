// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.renderpearl.backend.opengl;

public final class GlCompat {
	public final boolean GL_ARB_base_instance = false;
	public final boolean GL_ARB_buffer_storage = false;
	public final boolean GL_ARB_clip_control = false;
	public final boolean GL_ARB_direct_state_access = false;
	public final boolean GL_ARB_draw_indirect = false;
	public final boolean GL_ARB_multi_draw_indirect = false;
	public final boolean GL_ARB_shader_draw_parameters = false;
	public final boolean GL_ARB_vertex_attrib_binding = false;
	public final boolean GL_EXT_texture_filter_anisotropic;
	public final boolean GL_ARB_debug_output = false;
	public final boolean GL_EXT_debug_label = false;
	public final boolean GL_KHR_debug = false;
	public final boolean GL_ARB_texture_buffer_object = false;

	private static GlCompat current;
	private static boolean anisotropic;

	private GlCompat() {
		this.GL_EXT_texture_filter_anisotropic = anisotropic;
	}

	public static void setup(final boolean hasAnisotropic) {
		anisotropic = hasAnisotropic;
		current = null;
	}

	public static GlCompat create() {
		return get();
	}

	public static GlCompat get() {
		if (current == null) {
			current = new GlCompat();
		}
		return current;
	}
}
