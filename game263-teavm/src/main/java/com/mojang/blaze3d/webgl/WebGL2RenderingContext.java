// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.webgl;

import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.core.JSString;
import org.teavm.jso.typedarrays.ArrayBufferView;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.webgl.WebGLBuffer;
import org.teavm.jso.webgl.WebGLActiveInfo;
import org.teavm.jso.webgl.WebGLFramebuffer;
import org.teavm.jso.webgl.WebGLProgram;
import org.teavm.jso.webgl.WebGLShader;
import org.teavm.jso.webgl.WebGLTexture;
import org.teavm.jso.webgl.WebGLUniformLocation;

public interface WebGL2RenderingContext extends JSObject {
	void enable(int cap);
	void disable(int cap);
	int getError();
	JSObject getParameter(int pname);
	void pixelStorei(int pname, int param);
	void finish();
	void viewport(int x, int y, int w, int h);
	void scissor(int x, int y, int w, int h);
	void clear(int mask);
	void clearColor(float r, float g, float b, float a);
	void clearDepth(float depth);
	void clearBufferfv(int buffer, int drawbuffer, float[] value);
	void clearBufferiv(int buffer, int drawbuffer, int[] value);
	void clearBufferuiv(int buffer, int drawbuffer, int[] value);
	void depthFunc(int func);
	void depthMask(boolean flag);
	void colorMask(boolean r, boolean g, boolean b, boolean a);
	void blendEquationSeparate(int modeRGB, int modeAlpha);
	void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);
	void polygonOffset(float factor, float units);

	WebGLBuffer createBuffer();
	void deleteBuffer(WebGLBuffer buffer);
	void bindBuffer(int target, WebGLBuffer buffer);
	void bindBufferBase(int target, int index, WebGLBuffer buffer);
	void bindBufferRange(int target, int index, WebGLBuffer buffer, int offset, int size);
	void bufferData(int target, int size, int usage);
	void bufferData(int target, ArrayBufferView data, int usage);
	void bufferSubData(int target, int offset, ArrayBufferView data);
	void copyBufferSubData(int readTarget, int writeTarget, int readOffset, int writeOffset, int size);
	void getBufferSubData(int target, int srcByteOffset, Int8Array dstBuffer);

	WebGLTexture createTexture();
	void deleteTexture(WebGLTexture texture);
	void bindTexture(int target, WebGLTexture texture);
	void activeTexture(int texture);
	void texParameteri(int target, int pname, int param);
	void texParameterf(int target, int pname, float param);
	JSObject getTexParameter(int target, int pname);
	void generateMipmap(int target);
	void copyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border);
	void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height);
	void texImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ArrayBufferView pixels);
	void texImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, ArrayBufferView pixels);
	void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ArrayBufferView pixels);
	void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, int unpackBufferOffset);
	void readPixels(int x, int y, int width, int height, int format, int type, ArrayBufferView pixels);
	void readPixels(int x, int y, int width, int height, int format, int type, int packBufferOffset);
	WebGLSampler createSampler();
	void deleteSampler(WebGLSampler sampler);
	void bindSampler(int unit, WebGLSampler sampler);
	void samplerParameteri(WebGLSampler sampler, int pname, int param);
	void samplerParameterf(WebGLSampler sampler, int pname, float param);

	WebGLFramebuffer createFramebuffer();
	void deleteFramebuffer(WebGLFramebuffer framebuffer);
	void bindFramebuffer(int target, WebGLFramebuffer framebuffer);
	int checkFramebufferStatus(int target);
	void readBuffer(int mode);
	void framebufferTexture2D(int target, int attachment, int textarget, WebGLTexture texture, int level);
	void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter);
	void invalidateFramebuffer(int target, int[] attachments);
	void drawBuffers(int[] bufs);

	WebGLShader createShader(int type);
	void deleteShader(WebGLShader shader);
	void shaderSource(WebGLShader shader, JSString source);
	void compileShader(WebGLShader shader);
	JSObject getShaderParameter(WebGLShader shader, int pname);
	JSString getShaderInfoLog(WebGLShader shader);
	WebGLProgram createProgram();
	void deleteProgram(WebGLProgram program);
	void attachShader(WebGLProgram program, WebGLShader shader);
	void detachShader(WebGLProgram program, WebGLShader shader);
	void linkProgram(WebGLProgram program);
	void useProgram(WebGLProgram program);
	JSObject getProgramParameter(WebGLProgram program, int pname);
	JSString getProgramInfoLog(WebGLProgram program);
	void bindAttribLocation(WebGLProgram program, int index, JSString name);
	int getAttribLocation(WebGLProgram program, JSString name);
	WebGLActiveInfo getActiveUniform(WebGLProgram program, int index);
	WebGLUniformLocation getUniformLocation(WebGLProgram program, JSString name);
	void uniform1i(WebGLUniformLocation location, int v0);
	void uniform1f(WebGLUniformLocation location, float v0);
	void uniform2f(WebGLUniformLocation location, float v0, float v1);
	void uniform2i(WebGLUniformLocation location, int v0, int v1);
	void uniform3f(WebGLUniformLocation location, float v0, float v1, float v2);
	void uniform3i(WebGLUniformLocation location, int v0, int v1, int v2);
	void uniform4f(WebGLUniformLocation location, float v0, float v1, float v2, float v3);
	void uniform4i(WebGLUniformLocation location, int v0, int v1, int v2, int v3);
	void uniformMatrix3fv(WebGLUniformLocation location, boolean transpose, float[] value);
	void uniformMatrix4fv(WebGLUniformLocation location, boolean transpose, float[] value);
	double getUniformBlockIndex(WebGLProgram program, JSString name);
	void uniformBlockBinding(WebGLProgram program, int uniformBlockIndex, int uniformBlockBinding);
	JSString getActiveUniformBlockName(WebGLProgram program, int uniformBlockIndex);

	WebGLVertexArrayObject createVertexArray();
	void deleteVertexArray(WebGLVertexArrayObject array);
	void bindVertexArray(WebGLVertexArrayObject array);
	void enableVertexAttribArray(int index);
	void vertexAttrib4f(int index, float x, float y, float z, float w);
	void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, int offset);
	void vertexAttribIPointer(int index, int size, int type, int stride, int offset);
	void vertexAttribDivisor(int index, int divisor);
	void drawArrays(int mode, int first, int count);
	void drawArraysInstanced(int mode, int first, int count, int instanceCount);
	void drawElements(int mode, int count, int type, int offset);
	void drawElementsInstanced(int mode, int count, int type, int offset, int instanceCount);

	WebGLSync fenceSync(int condition, int flags);
	int clientWaitSync(WebGLSync sync, int flags, int timeout);
	void deleteSync(WebGLSync sync);
	WebGLQueryObject createQuery();
	void deleteQuery(WebGLQueryObject query);

	JSObject getExtension(JSString name);

	@JSProperty
	int getDrawingBufferWidth();

	@JSProperty
	int getDrawingBufferHeight();
}
