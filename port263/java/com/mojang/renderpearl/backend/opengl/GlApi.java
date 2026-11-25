// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.renderpearl.backend.opengl;

public final class GlApi {
	private GlApi() {}

	public static boolean ready() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void resetStateCaches() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static boolean hasColorBufferFloat() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static boolean hasFloatBlend() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static boolean hasFloatLinear() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static boolean hasParallelCompile() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static boolean glProgramDone(int program) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static boolean captureMode = false;
	public static boolean zeroCopyUploads = false;
	public static boolean legacyChurn = false;
	public static int[] churnCounters() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glEnable(int cap) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDisable(int cap) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGetError() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static float glGetFloat(int pname) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGetInteger(int pname) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glGetIntegerv(int pname, int[] values) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glGetFloatv(int pname, float[] values) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static long glGetInteger64(int pname) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static String glGetString(int name) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glPixelStorei(int pname, int param) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glFinish() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glViewport(int x, int y, int w, int h) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glScissor(int x, int y, int w, int h) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glClear(int mask) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glClearColor(float r, float g, float b, float a) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glClearDepth(double depth) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glClearBufferfv(int buffer, int drawbuffer, java.nio.FloatBuffer value) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glClearBufferfv(int buffer, int drawbuffer, float[] value) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glClearBufferiv(int buffer, int drawbuffer, int[] value) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glClearBufferuiv(int buffer, int drawbuffer, int[] value) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDepthFunc(int func) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDepthMask(boolean flag) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glColorMask(boolean r, boolean g, boolean b, boolean a) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glColorMaski(int index, boolean r, boolean g, boolean b, boolean a) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glEnablei(int cap, int index) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDisablei(int cap, int index) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBlendEquationSeparate(int modeRGB, int modeAlpha) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBlendFuncSeparateiARB(int index, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glLogicOp(int op) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glPolygonMode(int face, int mode) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glPolygonOffset(float factor, float units) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGenBuffers() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDeleteBuffers(int buffer) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBindBuffer(int target, int buffer) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBindBufferRange(int target, int index, int buffer, long offset, long size) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBindBufferBase(int target, int index, int buffer) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBufferData(int target, long size, int usage) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBufferData(int target, java.nio.ByteBuffer data, int usage) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBufferData(int target, float[] data, int usage) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBufferSubData(int target, long offset, java.nio.ByteBuffer data) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static java.nio.ByteBuffer glMapBufferRange(int target, long offset, long length, int access) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static boolean glUnmapBuffer(int target) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glFlushMappedBufferRange(int target, long offset, long length) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static long nglMapBufferRange(int target, long offset, long length, int access) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBufferStorage(int target, long size, int flags) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBufferStorage(int target, java.nio.ByteBuffer data, int flags) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glCreateBuffers() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glCreateFramebuffers() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glNamedBufferData(int buffer, long size, int usage) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glNamedBufferData(int buffer, java.nio.ByteBuffer data, int usage) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glNamedBufferStorage(int buffer, long size, int flags) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glNamedBufferStorage(int buffer, java.nio.ByteBuffer data, int flags) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glNamedBufferSubData(int buffer, long offset, java.nio.ByteBuffer data) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static java.nio.ByteBuffer glMapNamedBufferRange(int buffer, long offset, long length, int access) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static boolean glUnmapNamedBuffer(int buffer) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glFlushMappedNamedBufferRange(int buffer, long offset, long length) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glCopyNamedBufferSubData(int readBuffer, int writeBuffer, long readOffset, long writeOffset, long size) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glNamedFramebufferTexture(int framebuffer, int attachment, int texture, int level) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBlitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGenTextures() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDeleteTextures(int texture) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBindTexture(int target, int texture) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glActiveTexture(int texture) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glTexParameteri(int target, int pname, int param) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glTexParameterf(int target, int pname, float param) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glTexParameteriv(int target, int pname, int[] values) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGetTexParameteri(int target, int pname) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glGenerateMipmap(int target) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glCopyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGetTexLevelParameteri(int target, int level, int pname) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, java.nio.ByteBuffer pixels) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, java.nio.IntBuffer pixels) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glTexImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, java.nio.ByteBuffer pixels) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, java.nio.ByteBuffer pixels) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, java.nio.IntBuffer pixels) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glTexBuffer(int target, int internalformat, int buffer) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glReadPixels(int x, int y, int width, int height, int format, int type, java.nio.ByteBuffer pixels) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glReadPixels(int x, int y, int width, int height, int format, int type, float[] pixels) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glReadPixels(int x, int y, int width, int height, int format, int type, long pixels) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGenSamplers() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDeleteSamplers(int sampler) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBindSampler(int unit, int sampler) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glSamplerParameteri(int sampler, int pname, int param) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glSamplerParameteriv(int sampler, int pname, int[] values) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glSamplerParameterf(int sampler, int pname, float param) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGenFramebuffers() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDeleteFramebuffers(int framebuffer) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBindFramebuffer(int target, int framebuffer) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glCheckFramebufferStatus(int target) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glReadBuffer(int mode) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glInvalidateFramebuffer(int target, int attachment) { }
	public static void glInvalidateDrawDepth() { }
	public static boolean canInvalidatePresentedColor() { return false; }
	public static void glDrawBuffer(int buf) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDrawBuffers(int[] bufs) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDrawBuffers(int buf) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glCreateShader(int type) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDeleteShader(int shader) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glShaderSource(int shader, CharSequence source) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void nglShaderSource(int shader, int count, long strings, long lengths) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glCompileShader(int shader) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGetShaderi(int shader, int pname) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static String glGetShaderInfoLog(int shader) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static String glGetShaderInfoLog(int shader, int maxLength) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glCreateProgram() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDeleteProgram(int program) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glAttachShader(int program, int shader) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDetachShader(int program, int shader) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glLinkProgram(int program) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUseProgram(int program) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGetProgrami(int program, int pname) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glGetProgramiv(int program, int pname, int[] values) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static String glGetProgramInfoLog(int program) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static String glGetProgramInfoLog(int program, int maxLength) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBindAttribLocation(int program, int index, CharSequence name) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGetAttribLocation(int program, CharSequence name) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glShaderSourceRaw(int shader, String source) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static java.util.List<Object[]> activeUniforms(int program) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static String glGetActiveUniform(int program, int index, int maxLength, java.nio.IntBuffer size, java.nio.IntBuffer type) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGetUniformLocation(int program, CharSequence name) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform1f(int location, float v0) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform2f(int location, float v0, float v1) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform2i(int location, int v0, int v1) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform3f(int location, float v0, float v1, float v2) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform3i(int location, int v0, int v1, int v2) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform4f(int location, float v0, float v1, float v2, float v3) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform4i(int location, int v0, int v1, int v2, int v3) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniformMatrix3fv(int location, boolean transpose, java.nio.FloatBuffer value) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniformMatrix3fv(int location, boolean transpose, float[] value) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniformMatrix4fv(int location, boolean transpose, java.nio.FloatBuffer value) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniformMatrix4fv(int location, boolean transpose, float[] value) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform1i(int location, int v0) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform1ui(int location, int v0) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform2ui(int location, int v0, int v1) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform3ui(int location, int v0, int v1, int v2) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniform4ui(int location, int v0, int v1, int v2, int v3) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGetUniformBlockIndex(int program, CharSequence name) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static String glGetActiveUniformBlockName(int program, int uniformBlockIndex) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGenVertexArrays() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDeleteVertexArrays(int array) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBindVertexArray(int array) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glEnableVertexAttribArray(int index) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glVertexAttrib4f(int index, float x, float y, float z, float w) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glVertexAttribDivisor(int index, int divisor) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDrawArrays(int mode, int first, int count) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDrawArraysInstanced(int mode, int first, int count, int primcount) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDrawElements(int mode, int count, int type, long indices) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDrawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int primcount, int basevertex) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void nglMultiDrawArrays(int mode, long first, long count, int drawcount) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void nglMultiDrawElementsBaseVertex(int mode, long count, int type, long indices, int drawcount, long basevertex) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDrawArraysIndirect(int mode, long indirect) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDrawElementsIndirect(int mode, int type, long indirect) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glMultiDrawArraysIndirect(int mode, long indirect, int drawcount, int stride) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDrawArraysInstancedBaseInstance(int mode, int first, int count, int primcount, int baseinstance) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDrawElementsInstancedBaseVertexBaseInstance(int mode, int count, int type, long indices, int primcount, int basevertex, int baseinstance) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glBindVertexBuffer(int bindingindex, int buffer, long offset, int stride) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glVertexAttribBinding(int attribindex, int bindingindex) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glVertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glVertexAttribIFormat(int attribindex, int size, int type, int relativeoffset) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glVertexBindingDivisor(int bindingindex, int divisor) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glClipControl(int origin, int depth) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static long glFenceSync(int condition, int flags) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glClientWaitSync(long sync, int flags, long timeout) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDeleteSync(long sync) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGenQueries() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glGenQueries(int[] out) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDeleteQueries(int query) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glDeleteQueries(int[] qs) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glQueryCounter(int query, int target) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static int glGetQueryObjecti(int query, int pname) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static long glGetQueryObjectui64(int query, int pname) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glObjectLabel(int identifier, int name, CharSequence label) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glPushDebugGroup(int source, int id, CharSequence message) { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glPopDebugGroup() { throw new UnsupportedOperationException("browser GlApi only"); }
	public static void glLabelObjectEXT(int type, int object, CharSequence label) { throw new UnsupportedOperationException("browser GlApi only"); }
}
