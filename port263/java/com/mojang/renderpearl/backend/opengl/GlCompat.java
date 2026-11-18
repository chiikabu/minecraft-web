// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.renderpearl.backend.opengl;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

public final class GlCompat {
	public final boolean GL_ARB_base_instance;
	public final boolean GL_ARB_buffer_storage;
	public final boolean GL_ARB_clip_control;
	public final boolean GL_ARB_direct_state_access;
	public final boolean GL_ARB_draw_indirect;
	public final boolean GL_ARB_multi_draw_indirect;
	public final boolean GL_ARB_shader_draw_parameters;
	public final boolean GL_ARB_vertex_attrib_binding;
	public final boolean GL_EXT_texture_filter_anisotropic;
	public final boolean GL_ARB_debug_output;
	public final boolean GL_EXT_debug_label;
	public final boolean GL_KHR_debug;
	public final boolean GL_ARB_texture_buffer_object;

	private static GlCompat current;

	private GlCompat(final GLCapabilities caps) {
		this.GL_ARB_base_instance = caps.GL_ARB_base_instance;
		this.GL_ARB_buffer_storage = caps.GL_ARB_buffer_storage;
		this.GL_ARB_clip_control = caps.GL_ARB_clip_control;
		this.GL_ARB_direct_state_access = caps.GL_ARB_direct_state_access;
		this.GL_ARB_draw_indirect = caps.GL_ARB_draw_indirect;
		this.GL_ARB_multi_draw_indirect = caps.GL_ARB_multi_draw_indirect;
		this.GL_ARB_shader_draw_parameters = caps.GL_ARB_shader_draw_parameters;
		this.GL_ARB_vertex_attrib_binding = caps.GL_ARB_vertex_attrib_binding;
		this.GL_EXT_texture_filter_anisotropic = caps.GL_EXT_texture_filter_anisotropic;
		this.GL_ARB_debug_output = caps.GL_ARB_debug_output;
		this.GL_EXT_debug_label = caps.GL_EXT_debug_label;
		this.GL_KHR_debug = caps.GL_KHR_debug;
		this.GL_ARB_texture_buffer_object = caps.OpenGL31 || caps.GL_ARB_texture_buffer_object;
	}

	public static GlCompat create() {
		current = new GlCompat(GL.createCapabilities());
		return current;
	}

	public static GlCompat get() {
		if (current == null) {
			current = new GlCompat(GL.getCapabilities());
		}
		return current;
	}
}
