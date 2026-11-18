// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.renderpearl.backend.opengl;

import com.mojang.blaze3d.webgl.WebGL2RenderingContext;
import com.mojang.blaze3d.webgl.WebGLDrawBuffersIndexed;
import com.mojang.blaze3d.webgl.WebGLQueryObject;
import com.mojang.blaze3d.webgl.WebGLSampler;
import com.mojang.blaze3d.webgl.WebGLSync;
import com.mojang.blaze3d.webgl.WebGLVertexArrayObject;
import com.mojang.renderpearl.backend.browser.RenderPearlGlslEs;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSString;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Uint8Array;
import org.teavm.jso.typedarrays.ArrayBufferView;
import org.teavm.jso.typedarrays.Int16Array;
import org.teavm.jso.typedarrays.Uint16Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Uint32Array;
import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.webgl.WebGLBuffer;
import org.teavm.jso.webgl.WebGLActiveInfo;
import org.teavm.jso.webgl.WebGLFramebuffer;
import org.teavm.jso.webgl.WebGLProgram;
import org.teavm.jso.webgl.WebGLShader;
import org.teavm.jso.webgl.WebGLTexture;
import org.teavm.jso.webgl.WebGLUniformLocation;

public final class GlApi {
	private static WebGL2RenderingContext gl;
	private static WebGLDrawBuffersIndexed drawBuffersIndexed;
	private static boolean colorBufferFloat;
	private static boolean floatBlend;
	private static boolean floatLinear;
	public static boolean captureMode;

	private static final List<WebGLBuffer> buffers = newTable();
	private static final List<WebGLTexture> textures = newTable();
	private static final List<WebGLProgram> programs = newTable();
	private static final List<WebGLShader> shaders = newTable();
	private static final Map<Integer, Integer> shaderTypes = new HashMap<>();
	private static final List<WebGLFramebuffer> framebuffers = newTable();
	private static final List<WebGLVertexArrayObject> vertexArrays = newTable();
	private static final List<WebGLSampler> samplers = newTable();
	private static final List<WebGLQueryObject> queries = newTable();
	private static final Map<Long, WebGLSync> syncs = new HashMap<>();
	private static long nextSync = 1L;

	private static final int TARGET_SLOTS = 16;
	private static final int[] targetKey = new int[TARGET_SLOTS];
	private static final int[] targetBoundBuffer = new int[TARGET_SLOTS];
	private static final MappedRange[] targetMapped = new MappedRange[TARGET_SLOTS];
	private static int targetCount;

	private static int targetSlot(final int target) {
		for (int i = 0; i < targetCount; i++) {
			if (targetKey[i] == target) {
				return i;
			}
		}
		if (targetCount == TARGET_SLOTS) {
			throw new IllegalStateException("GlApi: more than " + TARGET_SLOTS + " distinct GL buffer targets seen; grow TARGET_SLOTS");
		}
		targetKey[targetCount] = target;
		return targetCount++;
	}

	private static int targetSlotOrMinusOne(final int target) {
		for (int i = 0; i < targetCount; i++) {
			if (targetKey[i] == target) {
				return i;
			}
		}
		return -1;
	}

	private static boolean bindFilter = true;
	static int churnBindSkipped;
	private static int[] elementBufferByVao = filledArray(64);
	private static int[][] divisorByVao = new int[64][];
	private static final int[] samplerByUnit = filledArray(32);
	private static final int[] scissorShadow = { -1, -1, -1, -1 };
	private static final int[] viewportShadow = { -1, -1, -1, -1 };
	private static double clearDepthShadow = Double.NaN;

	private static int[] filledArray(final int n) {
		int[] a = new int[n];
		java.util.Arrays.fill(a, -1);
		return a;
	}

	private static int elementBufferOf(final int vao) {
		return vao < elementBufferByVao.length ? elementBufferByVao[vao] : -1;
	}

	private static void setElementBufferOf(final int vao, final int buffer) {
		if (vao >= elementBufferByVao.length) {
			int old = elementBufferByVao.length;
			elementBufferByVao = java.util.Arrays.copyOf(elementBufferByVao, Math.max(vao + 1, old * 2));
			java.util.Arrays.fill(elementBufferByVao, old, elementBufferByVao.length, -1);
		}
		elementBufferByVao[vao] = buffer;
	}

	private static boolean sameRect(final int[] shadow, final int x, final int y, final int w, final int h) {
		if (bindFilter && shadow[0] == x && shadow[1] == y && shadow[2] == w && shadow[3] == h) {
			churnBindSkipped++;
			return true;
		}
		shadow[0] = x;
		shadow[1] = y;
		shadow[2] = w;
		shadow[3] = h;
		return false;
	}

	private static int boundBuffer(final int target) {
		int slot = targetSlotOrMinusOne(target);
		return slot < 0 ? 0 : targetBoundBuffer[slot];
	}

	private static final List<List<WebGLUniformLocation>> uniformLocationList = new ArrayList<>();

	private GlApi() {
	}

	public static void init(final WebGL2RenderingContext context) {
		gl = context;
		bindFilter = !"false".equals(System.getProperty("web263.glBindFilter"));
		JSObject extension = context.getExtension(JSString.valueOf("OES_draw_buffers_indexed"));
		drawBuffersIndexed = extension == null ? null : extension.cast();
		colorBufferFloat = context.getExtension(JSString.valueOf("EXT_color_buffer_float")) != null;
		floatBlend = context.getExtension(JSString.valueOf("EXT_float_blend")) != null;
		floatLinear = context.getExtension(JSString.valueOf("OES_texture_float_linear")) != null;
	}

	public static boolean ready() {
		return gl != null;
	}

	public static boolean hasColorBufferFloat() {
		return colorBufferFloat;
	}

	public static boolean hasFloatBlend() {
		return floatBlend;
	}

	public static boolean hasFloatLinear() {
		return floatLinear;
	}

	private static <T extends JSObject> List<T> newTable() {
		List<T> t = new ArrayList<>();
		t.add(null);
		return t;
	}

	private static <T extends JSObject> int register(final List<T> table, final T obj) {
		for (int i = 1; i < table.size(); i++) {
			if (table.get(i) == null) {
				table.set(i, obj);
				return i;
			}
		}
		table.add(obj);
		return table.size() - 1;
	}

	private static byte[] toArray(final ByteBuffer data) {
		byte[] out = new byte[data.remaining()];
		data.duplicate().get(out);
		return out;
	}

	public static boolean zeroCopyUploads = "true".equals(System.getProperty("eagler.gl.zeroCopyUploads"));

	@org.teavm.jso.JSBody(params = { "a", "off", "len" },
		script = "return a.subarray ? a.subarray(off, off + len) : null;")
	private static native Int8Array viewOf(@org.teavm.jso.JSByRef(optional = true) byte[] a, int off, int len);

	@org.teavm.jso.JSBody(params = { "a", "off", "len" },
		script = "return a.buffer ? new Uint8Array(a.buffer, a.byteOffset + off, len) : null;")
	private static native Uint8Array pixelViewOf(@org.teavm.jso.JSByRef(optional = true) byte[] a, int off, int len);

	private static Int8Array copyToJs(final ByteBuffer data) {
		long address = org.lwjgl.system.MemoryUtil.memLinearAddress(data);
		if (address >= 0) {
			churnViewUploads++;
			return linearView((int)address, data.remaining());
		}
		if (zeroCopyUploads && data.hasArray()) {
			Int8Array view = viewOf(data.array(), data.arrayOffset() + data.position(), data.remaining());
			if (view != null) {
				churnViewUploads++;
				return view;
			}
		}
		churnCopyUploads++;
		byte[] src = toArray(data);
		Int8Array arr = Int8Array.create(src.length);
		arr.set(src);
		return arr;
	}

	@org.teavm.jso.JSBody(params = {"address", "length"}, script =
		"return new Int8Array(globalThis.__teavmMemory.buffer, address >>> 0, length);")
	private static native Int8Array linearView(int address, int length);

	private static ArrayBufferView copyToJsPixels(final ByteBuffer data, final int type) {
		if (type != 5121) {
			Int8Array bytes = Int8Array.create(data.remaining());
			bytes.set(toArray(data));
			return switch (type) {
				case 5120 -> bytes;
				case 5122 -> Int16Array.create(bytes.getBuffer(), 0, data.remaining() / 2);
				case 5123, 5131, 33635, 32819, 32820 -> Uint16Array.create(bytes.getBuffer(), 0, data.remaining() / 2);
				case 5124 -> Int32Array.create(bytes.getBuffer(), 0, data.remaining() / 4);
				case 5125, 33640, 34042, 35899, 35902 -> new Uint32Array(bytes.getBuffer(), 0, data.remaining() / 4);
				case 5126 -> Float32Array.create(bytes.getBuffer(), 0, data.remaining() / 4);
				default -> throw unsupported("client-memory pixel transfer with GL type 0x" + Integer.toHexString(type));
			};
		}
		if (zeroCopyUploads && data.hasArray()) {
			Uint8Array view = pixelViewOf(data.array(), data.arrayOffset() + data.position(), data.remaining());
			if (view != null) {
				churnViewUploads++;
				return view;
			}
		}
		churnCopyUploads++;
		byte[] src = toArray(data);
		Uint8Array arr = Uint8Array.create(src.length);
		arr.set(src);
		return arr;
	}

	private static void copyFromJs(final Int8Array src, final ByteBuffer dst) {
		int n = dst.remaining();
		int pos = dst.position();
		for (int i = 0; i < n; i++) {
			dst.put(pos + i, src.get(i));
		}
	}

	public static boolean legacyChurn;

	static Object churnSink;

	static int churnBindBuffer;
	static int churnMapRange;
	static int churnNMapRange;
	static int churnShadowReused;
	static int churnShadowGrown;
	static int churnUniform1i;
	static int churnAttribMinted;
	static int churnAttribReused;
	static int churnBaseVertexApplied;
	static int churnViewUploads;
	static int churnCopyUploads;

	public static int[] churnCounters() {
		return new int[] { churnBindBuffer, churnMapRange, churnNMapRange, churnShadowReused, churnShadowGrown,
			churnUniform1i, churnAttribMinted, churnAttribReused, churnBaseVertexApplied, churnViewUploads, churnCopyUploads };
	}

	private static UnsupportedOperationException unsupported(final String what) {
		return new UnsupportedOperationException(what + " is not available on WebGL2; this path must stay disabled via GlCompat");
	}

	static boolean DEBUG_GL_ERRORS = false;

	private static void probe(final String what) {
		if (DEBUG_GL_ERRORS) {
			int err = gl.getError();
			if (err != 0) {
				System.out.println("[GlApi] GL error 0x" + Integer.toHexString(err) + " after " + what);
			}
		}
	}

	public static void glEnable(int cap) {
		if (cap == 34895 || cap == 34370) {
			return;
		}
		gl.enable(cap);
	}
	public static void glDisable(int cap) { gl.disable(cap); }
	public static int glGetError() { return DEBUG_GL_ERRORS ? gl.getError() : 0; }
	public static float glGetFloat(int pname) { return (float)jsNumber(gl.getParameter(pname)); }
	public static int glGetInteger(int pname) { return (int)jsNumber(gl.getParameter(pname)); }
	public static void glGetIntegerv(int pname, int[] values) {
		JSObject value = gl.getParameter(pname);
		int length = parameterLength(value);
		if (length < 0) throw unsupported("non-numeric glGetIntegerv result");
		if (values.length < length) throw new IllegalArgumentException("glGetIntegerv destination too small");
		for (int i = 0; i < length; i++) values[i] = (int)parameterNumber(value, i);
	}
	public static void glGetFloatv(int pname, float[] values) {
		JSObject value = gl.getParameter(pname);
		int length = parameterLength(value);
		if (length < 0) throw unsupported("non-numeric glGetFloatv result");
		if (values.length < length) throw new IllegalArgumentException("glGetFloatv destination too small");
		for (int i = 0; i < length; i++) values[i] = (float)parameterNumber(value, i);
	}
	public static long glGetInteger64(int pname) {
		if (pname == 36392) {
			return 0L;
		}
		return (long)jsNumber(gl.getParameter(pname));
	}
	public static String glGetString(int name) { return gl.getParameter(name).<JSString>cast().stringValue(); }
	public static void glPixelStorei(int pname, int param) { gl.pixelStorei(pname, param); }
	public static void glFinish() { gl.finish(); }
	public static void glViewport(int x, int y, int w, int h) { if (!sameRect(viewportShadow, x, y, w, h)) { gl.viewport(x, y, w, h); } }
	public static void glScissor(int x, int y, int w, int h) { if (!sameRect(scissorShadow, x, y, w, h)) { gl.scissor(x, y, w, h); } }
	public static void glClear(int mask) { gl.clear(mask); }
	public static void glClearColor(float r, float g, float b, float a) { gl.clearColor(r, g, b, a); }
	public static void glClearDepth(double depth) {
		if (bindFilter && depth == clearDepthShadow) {
			churnBindSkipped++;
			return;
		}
		clearDepthShadow = depth;
		gl.clearDepth((float)depth);
	}
	public static void glClearBufferfv(int buffer, int drawbuffer, FloatBuffer value) {
		float[] v = new float[value.remaining()];
		for (int i = 0; i < v.length; i++) {
			v[i] = value.get(value.position() + i);
		}
		gl.clearBufferfv(buffer, drawbuffer, v);
	}
	public static void glClearBufferfv(int buffer, int drawbuffer, float[] value) {
		gl.clearBufferfv(buffer, drawbuffer, value);
		if (DEBUG_GL_ERRORS) probe("glClearBufferfv(buffer=" + buffer + ", drawbuffer=" + drawbuffer + ", n=" + value.length + ")");
	}
	public static void glClearBufferiv(int buffer, int drawbuffer, int[] value) {
		int required = buffer == 6144 ? 4 : buffer == 6146 ? 1 : 0;
		if (required == 0) throw unsupported("integer clear buffer 0x" + Integer.toHexString(buffer));
		if (value.length < required) throw new IllegalArgumentException("integer clear value too small");
		gl.clearBufferiv(buffer, drawbuffer, value);
	}
	public static void glClearBufferuiv(int buffer, int drawbuffer, int[] value) {
		if (buffer != 6144) throw unsupported("unsigned integer clear buffer 0x" + Integer.toHexString(buffer));
		if (value.length < 4) throw new IllegalArgumentException("integer clear value too small");
		gl.clearBufferuiv(buffer, drawbuffer, value);
	}
	public static void glDepthFunc(int func) { gl.depthFunc(func); }
	public static void glDepthMask(boolean flag) { gl.depthMask(flag); }
	public static void glColorMaski(int index, boolean r, boolean g, boolean b, boolean a) {
		if (drawBuffersIndexed != null) {
			drawBuffersIndexed.colorMaskiOES(index, r, g, b, a);
		} else if (index == 0) {
			gl.colorMask(r, g, b, a);
		} else {
			throw unsupported("indexed color masks without OES_draw_buffers_indexed");
		}
	}
	public static void glEnablei(int cap, int index) {
		if (drawBuffersIndexed != null) {
			drawBuffersIndexed.enableiOES(cap, index);
		} else if (index == 0) {
			gl.enable(cap);
		} else {
			throw unsupported("indexed enable without OES_draw_buffers_indexed");
		}
	}
	public static void glDisablei(int cap, int index) {
		if (drawBuffersIndexed != null) {
			drawBuffersIndexed.disableiOES(cap, index);
		} else if (index == 0) {
			gl.disable(cap);
		} else {
			throw unsupported("indexed disable without OES_draw_buffers_indexed");
		}
	}
	public static void glBlendEquationSeparate(int modeRGB, int modeAlpha) { gl.blendEquationSeparate(modeRGB, modeAlpha); }
	public static void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { gl.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha); }
	public static void glBlendFuncSeparateiARB(int index, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
		if (drawBuffersIndexed != null) {
			drawBuffersIndexed.blendFuncSeparateiOES(index, srcRGB, dstRGB, srcAlpha, dstAlpha);
		} else if (index == 0) {
			gl.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
		} else {
			throw unsupported("indexed blend functions without OES_draw_buffers_indexed");
		}
	}
	public static void glLogicOp(int op) { throw unsupported("glLogicOp"); }
	public static void glPolygonMode(int face, int mode) {
		if (mode != 6914) {
			throw unsupported("glPolygonMode(GL_LINE/GL_POINT)");
		}
	}
	public static void glPolygonOffset(float factor, float units) { gl.polygonOffset(factor, units); }

	public static int glGenBuffers() { return register(buffers, gl.createBuffer()); }

	public static void glDeleteBuffers(int buffer) {
		if (buffer != 0) {
			gl.deleteBuffer(buffers.get(buffer));
			buffers.set(buffer, null);
			if (buffer < bufferShadows.length) {
				org.lwjgl.system.MemoryUtil.memFree(bufferShadows[buffer]);
				bufferShadows[buffer] = null;
			}
			invalidateShadow(buffer);
			if (buffer < elementBuffers.length) {
				elementBuffers[buffer] = false;
			}
			if (buffer < bufferSizes.length) {
				bufferSizes[buffer] = 0;
			}
			for (int i = 0; i < targetCount; i++) {
				if (targetBoundBuffer[i] == buffer) {
					targetBoundBuffer[i] = 0;
				}
			}
			for (int i = 0; i < elementBufferByVao.length; i++) {
				if (elementBufferByVao[i] == buffer) {
					elementBufferByVao[i] = i == currentVertexArray ? 0 : -1;
				}
			}
		}
	}
	public static void glBindBuffer(int target, int buffer) {
		int slot = targetSlot(target);
		if (target == 34963) {
			if (bindFilter && elementBufferOf(currentVertexArray) == buffer) {
				churnBindSkipped++;
				targetBoundBuffer[slot] = buffer;
				return;
			}
			setElementBufferOf(currentVertexArray, buffer);
		} else if (bindFilter && targetBoundBuffer[slot] == buffer) {
			churnBindSkipped++;
			return;
		}
		targetBoundBuffer[slot] = buffer;
		if (target == 34963) {
			markElementBuffer(buffer);
		}
		churnBindBuffer++;
		if (legacyChurn) {
			churnSink = Integer.valueOf(target);
			churnSink = Integer.valueOf(buffer);
		}
		gl.bindBuffer(target, buffer == 0 ? null : buffers.get(buffer));
	}
	public static void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
		gl.bindBufferRange(target, index, buffer == 0 ? null : buffers.get(buffer), (int)offset, (int)size);
		targetBoundBuffer[targetSlot(target)] = buffer;
		if (DEBUG_GL_ERRORS) probe("glBindBufferRange(index=" + index + ", offset=" + offset + ", size=" + size + ")");
	}
	public static void glBindBufferBase(int target, int index, int buffer) {
		if (target != 35345) throw unsupported("glBindBufferBase target 0x" + Integer.toHexString(target));
		WebGLBuffer object = buffer == 0 ? null : buffers.get(buffer);
		gl.bindBufferBase(target, index, object);
		targetBoundBuffer[targetSlot(target)] = buffer;
	}
	public static void glBufferData(int target, long size, int usage) {
		int buffer = boundBuffer(target);
		setBufferSize(buffer, (int)size);
		invalidateShadow(buffer);
		gl.bufferData(target, (int)size, usage);
	}
	public static void glBufferData(int target, ByteBuffer data, int usage) {
		int buffer = boundBuffer(target);
		setBufferSize(buffer, data.remaining());
		invalidateShadow(buffer);
		gl.bufferData(target, copyToJs(data), usage);
	}
	public static void glBufferData(int target, float[] data, int usage) {
		int size = Math.multiplyExact(data.length, Float.BYTES);
		Float32Array values = Float32Array.create(data.length);
		for (int i = 0; i < data.length; i++) values.set(i, data[i]);
		int buffer = boundBuffer(target);
		setBufferSize(buffer, size);
		invalidateShadow(buffer);
		gl.bufferData(target, values, usage);
	}
	public static void glBufferSubData(int target, long offset, ByteBuffer data) { invalidateShadow(boundBuffer(target)); gl.bufferSubData(target, (int)offset, copyToJs(data)); }

	private static int[] shadowValidEnd = new int[64];
	private static int[] bufferSizes = new int[64];

	private static void setBufferSize(final int buffer, final int size) {
		if (buffer >= bufferSizes.length) {
			bufferSizes = java.util.Arrays.copyOf(bufferSizes, Math.max(buffer + 1, bufferSizes.length * 2));
		}
		bufferSizes[buffer] = size;
	}
	static int churnShadowServedCopies;

	private static void invalidateShadow(final int buffer) {
		if (buffer >= 0 && buffer < shadowValidEnd.length) {
			shadowValidEnd[buffer] = 0;
		}
	}

	private static void markShadowValid(final int buffer, final long end) {
		if (buffer >= shadowValidEnd.length) {
			shadowValidEnd = java.util.Arrays.copyOf(shadowValidEnd, Math.max(buffer + 1, shadowValidEnd.length * 2));
		}
		shadowValidEnd[buffer] = (int)end;
	}
	public static void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
		invalidateShadow(boundBuffer(writeTarget));
		if (isElementBuffer(boundBuffer(readTarget)) != isElementBuffer(boundBuffer(writeTarget))) {
			int source = boundBuffer(readTarget);
			ByteBuffer shadow = source < bufferShadows.length ? bufferShadows[source] : null;
			if (shadow != null && source < shadowValidEnd.length && readOffset + size <= shadowValidEnd[source]) {
				churnShadowServedCopies++;
				gl.bufferSubData(writeTarget, (int)writeOffset,
					copyToJs(shadow.duplicate().position((int)readOffset).limit((int)(readOffset + size))));
				return;
			}
			Int8Array bounce = Int8Array.create((int)size);
			gl.getBufferSubData(readTarget, (int)readOffset, bounce);
			gl.bufferSubData(writeTarget, (int)writeOffset, bounce);
			return;
		}
		gl.copyBufferSubData(readTarget, writeTarget, (int)readOffset, (int)writeOffset, (int)size);
	}

	private static boolean[] elementBuffers = new boolean[64];

	private static void markElementBuffer(final int buffer) {
		if (buffer == 0) {
			return;
		}
		if (buffer >= elementBuffers.length) {
			elementBuffers = java.util.Arrays.copyOf(elementBuffers, Math.max(buffer + 1, elementBuffers.length * 2));
		}
		elementBuffers[buffer] = true;
	}

	private static boolean isElementBuffer(final int buffer) {
		return buffer > 0 && buffer < elementBuffers.length && elementBuffers[buffer];
	}

	private static final class MappedRange {
		ByteBuffer shadow;
		long offset;
		int access;
		long fakeAddr;
		boolean live;
	}

	private static MappedRange rangeFor(final int target) {
		int slot = targetSlot(target);
		MappedRange m = targetMapped[slot];
		if (m == null) {
			m = new MappedRange();
			targetMapped[slot] = m;
		}
		return m;
	}

	private static ByteBuffer[] bufferShadows = new ByteBuffer[64];

	private static ByteBuffer shadowFor(final int buffer, final int offset, final int length) {
		if (legacyChurn) {
			churnSink = new byte[length];
		}
		if (buffer >= bufferShadows.length) {
			bufferShadows = java.util.Arrays.copyOf(bufferShadows, Math.max(buffer + 1, bufferShadows.length * 2));
		}
		ByteBuffer pool = bufferShadows[buffer];
		if (pool == null || pool.capacity() < offset + length) {
			ByteBuffer grown = org.lwjgl.system.MemoryUtil.memCalloc(1, offset + length);
			if (pool != null) {
				grown.put(pool.duplicate().clear()).clear();
				org.lwjgl.system.MemoryUtil.memFree(pool);
			}
			pool = grown;
			bufferShadows[buffer] = pool;
			churnShadowGrown++;
		} else {
			churnShadowReused++;
		}
		return pool.duplicate().position(offset).limit(offset + length).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN);
	}

	public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access) {
		MappedRange m = rangeFor(target);
		ByteBuffer shadow = shadowFor(boundBuffer(target), (int)offset, (int)length);
		if ((access & 0x1) != 0) {
			Int8Array tmp = Int8Array.create((int)length);
			gl.getBufferSubData(target, (int)offset, tmp);
			copyFromJs(tmp, shadow.duplicate());
		}
		m.shadow = shadow;
		m.offset = offset;
		m.access = access;
		m.fakeAddr = 0L;
		m.live = true;
		churnMapRange++;
		if ((access & 0x2) != 0) {
			org.lwjgl.system.MemoryUtil.sliceWatch = shadow;
			org.lwjgl.system.MemoryUtil.sliceLo = Integer.MAX_VALUE;
			org.lwjgl.system.MemoryUtil.sliceHi = 0;
		}
		return shadow;
	}

	public static boolean glUnmapBuffer(int target) {
		int slot = targetSlotOrMinusOne(target);
		MappedRange m = slot < 0 ? null : targetMapped[slot];
		if (m == null || !m.live) {
			return false;
		}
		m.live = false;
		if ((m.access & 0x2) != 0) {
			ByteBuffer flush = m.shadow.duplicate();
			flush.clear();
			if (org.lwjgl.system.MemoryUtil.sliceWatch == m.shadow) {
				org.lwjgl.system.MemoryUtil.sliceWatch = null;
				if (org.lwjgl.system.MemoryUtil.sliceHi > org.lwjgl.system.MemoryUtil.sliceLo) {
					flush.position(org.lwjgl.system.MemoryUtil.sliceLo).limit(org.lwjgl.system.MemoryUtil.sliceHi);
				}
			}
			int lo = flush.position(), hi = flush.limit();
			gl.bufferSubData(target, (int)m.offset + lo, copyToJs(flush));
			if (m.fakeAddr == 0L) {
				int buffer = boundBuffer(target);
				int valid = buffer < shadowValidEnd.length ? shadowValidEnd[buffer] : 0;
				markShadowValid(buffer, m.offset != 0L ? 0L : lo <= valid ? Math.max(valid, hi) : valid);
			}
		}
		if (m.fakeAddr != 0L) {
			org.lwjgl.system.MemoryUtil.nmemFree(m.fakeAddr);
			m.fakeAddr = 0L;
		}
		m.shadow = null;
		return true;
	}

	public static void glFlushMappedBufferRange(int target, long offset, long length) {
		int slot = targetSlotOrMinusOne(target);
		MappedRange m = slot < 0 ? null : targetMapped[slot];
		if (m != null && m.live) {
			ByteBuffer flush = m.shadow.duplicate();
			flush.position((int)offset).limit((int)(offset + length));
			gl.bufferSubData(target, (int)(m.offset + offset), copyToJs(flush));
		}
	}

	public static long nglMapBufferRange(int target, long offset, long length, int access) {
		long addr = org.lwjgl.system.MemoryUtil.nmemAlloc(length);
		ByteBuffer shadow = org.lwjgl.system.MemoryUtil.memByteBuffer(addr, (int)length);
		if ((access & 0x1) != 0) {
			Int8Array tmp = Int8Array.create((int)length);
			gl.getBufferSubData(target, (int)offset, tmp);
			copyFromJs(tmp, shadow.duplicate());
		}
		MappedRange m = rangeFor(target);
		m.shadow = shadow;
		m.offset = offset;
		m.access = access;
		m.fakeAddr = addr;
		m.live = true;
		churnNMapRange++;
		return addr;
	}
	public static void glBufferStorage(int target, long size, int flags) { throw unsupported("glBufferStorage"); }
	public static void glBufferStorage(int target, ByteBuffer data, int flags) { throw unsupported("glBufferStorage"); }

	public static int glCreateBuffers() { throw unsupported("DSA"); }
	public static int glCreateFramebuffers() { throw unsupported("DSA"); }
	public static void glNamedBufferData(int buffer, long size, int usage) { throw unsupported("DSA"); }
	public static void glNamedBufferData(int buffer, ByteBuffer data, int usage) { throw unsupported("DSA"); }
	public static void glNamedBufferStorage(int buffer, long size, int flags) { throw unsupported("DSA"); }
	public static void glNamedBufferStorage(int buffer, ByteBuffer data, int flags) { throw unsupported("DSA"); }
	public static void glNamedBufferSubData(int buffer, long offset, ByteBuffer data) { throw unsupported("DSA"); }
	public static ByteBuffer glMapNamedBufferRange(int buffer, long offset, long length, int access) { throw unsupported("DSA"); }
	public static boolean glUnmapNamedBuffer(int buffer) { throw unsupported("DSA"); }
	public static void glFlushMappedNamedBufferRange(int buffer, long offset, long length) { throw unsupported("DSA"); }
	public static void glCopyNamedBufferSubData(int readBuffer, int writeBuffer, long readOffset, long writeOffset, long size) { throw unsupported("DSA"); }
	public static void glNamedFramebufferTexture(int framebuffer, int attachment, int texture, int level) { throw unsupported("DSA"); }
	public static void glBlitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) { throw unsupported("DSA"); }

	public static int glGenTextures() {
		int texture = register(textures, gl.createTexture());
		forgetMipRange(texture);
		return texture;
	}
	public static void glDeleteTextures(int texture) {
		if (texture != 0) {
			gl.deleteTexture(textures.get(texture));
			textures.set(texture, null);
			forgetMipRange(texture);
			for (int i = 0; i < boundTexture2D.length; i++) {
				if (boundTexture2D[i] == texture) {
					boundTexture2D[i] = 0;
				}
				if (boundTextureCube[i] == texture) {
					boundTextureCube[i] = 0;
				}
			}
		}
	}
	public static void glBindTexture(int target, int texture) {
		int glTarget = target == 35882 ? 3553 : target;
		if (activeUnit < TEXTURE_UNITS) {
			if (glTarget == 3553) {
				boundTexture2D[activeUnit] = texture;
			} else if (glTarget == 34067) {
				boundTextureCube[activeUnit] = texture;
			}
		}
		gl.bindTexture(glTarget, texture == 0 ? null : textures.get(texture));
	}
	public static void glActiveTexture(int texture) {
		activeUnit = texture - 33984;
		gl.activeTexture(texture);
	}
	public static void glTexParameteri(int target, int pname, int param) {
		if ((pname == 33084 || pname == 33085) && sameMipRange(target, pname, param)) {
			return;
		}
		gl.texParameteri(target, pname, param);
	}

	private static final int TEXTURE_UNITS = 32;
	private static int activeUnit;
	private static final int[] boundTexture2D = new int[TEXTURE_UNITS];
	private static final int[] boundTextureCube = new int[TEXTURE_UNITS];
	private static int[] baseLevelByTexture = filledArray(256);
	private static int[] maxLevelByTexture = filledArray(256);

	private static void forgetMipRange(final int texture) {
		if (texture >= 0 && texture < baseLevelByTexture.length) {
			baseLevelByTexture[texture] = -1;
			maxLevelByTexture[texture] = -1;
		}
	}

	private static boolean sameMipRange(final int target, final int pname, final int param) {
		if (activeUnit < 0 || activeUnit >= TEXTURE_UNITS) {
			return false;
		}
		int texture = target == 3553 ? boundTexture2D[activeUnit] : target == 34067 ? boundTextureCube[activeUnit] : 0;
		if (texture <= 0) {
			return false;
		}
		if (texture >= baseLevelByTexture.length) {
			int old = baseLevelByTexture.length;
			int grown = Math.max(texture + 1, old * 2);
			baseLevelByTexture = java.util.Arrays.copyOf(baseLevelByTexture, grown);
			maxLevelByTexture = java.util.Arrays.copyOf(maxLevelByTexture, grown);
			java.util.Arrays.fill(baseLevelByTexture, old, grown, -1);
			java.util.Arrays.fill(maxLevelByTexture, old, grown, -1);
		}
		int[] shadow = pname == 33084 ? baseLevelByTexture : maxLevelByTexture;
		if (bindFilter && shadow[texture] == param) {
			churnBindSkipped++;
			return true;
		}
		shadow[texture] = param;
		return false;
	}
	public static void glTexParameterf(int target, int pname, float param) { gl.texParameterf(target, pname, param); }
	private static void checkSamplerParameter(int pname) {
		switch (pname) {
			case 10240, 10241, 10242, 10243, 32882, 33082, 33083, 34892, 34893, 34046 -> { }
			default -> throw unsupported("sampler parameter 0x" + Integer.toHexString(pname));
		}
	}
	public static void glTexParameteriv(int target, int pname, int[] values) {
		if (pname != 33084 && pname != 33085) checkSamplerParameter(pname);
		if (values.length == 0) throw new IllegalArgumentException("texture parameter value missing");
		if ((pname == 33084 || pname == 33085) && sameMipRange(target, pname, values[0])) {
			return;
		}
		gl.texParameteri(target, pname, values[0]);
	}
	public static int glGetTexParameteri(int target, int pname) {
		JSObject value = gl.getTexParameter(target, pname);
		if (parameterLength(value) != 1) throw unsupported("non-scalar glGetTexParameteri result");
		return (int)parameterNumber(value, 0);
	}
	public static void glGenerateMipmap(int target) { gl.generateMipmap(target); }
	public static void glCopyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) {
		gl.copyTexImage2D(target, level, internalformat, x, y, width, height, border);
	}
	public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
		gl.copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
	}
	public static int glGetTexLevelParameteri(int target, int level, int pname) {
		if (target == 32868 && pname == 4096) {
			return proxyProbeWidth <= glGetInteger(3379) ? proxyProbeWidth : 0;
		}
		throw unsupported("glGetTexLevelParameteri");
	}
	private static int proxyProbeWidth;

	public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
		if (target == 32868) {
			proxyProbeWidth = width;
			return;
		}
		gl.texImage2D(target, level, internalformat, width, height, border, format, type, pixels == null ? null : copyToJsPixels(pixels, type));
		if (DEBUG_GL_ERRORS) probe("glTexImage2D(" + width + "x" + height + ", internal=" + internalformat + ", format=" + format + ", type=" + type + ")");
	}
	public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {
		throw unsupported("glTexImage2D(IntBuffer)");
	}
	public static void glTexImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, ByteBuffer pixels) {
		gl.texImage3D(target, level, internalformat, width, height, depth, border, format, type, pixels == null ? null : copyToJsPixels(pixels, type));
	}
	public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
		gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, copyToJsPixels(pixels, type));
		if (DEBUG_GL_ERRORS) probe("glTexSubImage2D(ByteBuffer)");
	}
	public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels) {
		if (boundBuffer(35052) == 0) {
			throw unsupported("glTexSubImage2D(client pointer) without a bound GL_PIXEL_UNPACK_BUFFER");
		}
		gl.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, (int)pixels);
		if (DEBUG_GL_ERRORS) probe("glTexSubImage2D(unpack buffer offset " + pixels + ")");
	}
	public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
		throw unsupported("glTexSubImage2D(IntBuffer)");
	}
	public static void glTexBuffer(int target, int internalformat, int buffer) {
		if (target != 35882 || internalformat != 33329) {
			throw unsupported("texture buffer format 0x" + Integer.toHexString(internalformat));
		}
		int size = buffer < bufferSizes.length ? bufferSizes[buffer] : 0;
		if (size <= 0) {
			throw new IllegalStateException("Texture buffer has no storage");
		}
		int maxWidth = glGetInteger(3379);
		int width = Math.min(size, maxWidth);
		int height = (size + width - 1) / width;
		if (height > maxWidth) {
			throw unsupported("texture buffer larger than WebGL2 2D texture capacity");
		}

		Int8Array source = Int8Array.create(size);
		int restore = boundBuffer(36662);
		gl.bindBuffer(36662, buffers.get(buffer));
		gl.getBufferSubData(36662, 0, source);
		gl.bindBuffer(36662, restore == 0 ? null : buffers.get(restore));
		Int8Array pixels = source;
		if (width * height != size) {
			pixels = Int8Array.create(width * height);
			for (int i = 0; i < size; i++) {
				pixels.set(i, source.get(i));
			}
		}
		gl.texParameteri(3553, 10241, 9728);
		gl.texParameteri(3553, 10240, 9728);
		gl.texParameteri(3553, 10242, 33071);
		gl.texParameteri(3553, 10243, 33071);
		gl.texImage2D(3553, 0, 33329, width, height, 0, 36244, 5120, pixels);
	}
	public static void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
		if (type != 5121) {
			throw unsupported("glReadPixels into client memory with GL type 0x" + Integer.toHexString(type));
		}
		Uint8Array tmp = Uint8Array.create(pixels.remaining());
		gl.readPixels(x, y, width, height, format, type, tmp);
		copyFromJs(Int8Array.create(tmp.getBuffer()), pixels);
	}
	public static void glReadPixels(int x, int y, int width, int height, int format, int type, float[] pixels) {
		if (type != 5126 || !colorBufferFloat) throw unsupported("floating-point readPixels without GL_FLOAT and EXT_color_buffer_float");
		Float32Array values = Float32Array.create(pixels.length);
		for (int i = 0; i < pixels.length; i++) values.set(i, pixels[i]);
		gl.readPixels(x, y, width, height, format, type, values);
		for (int i = 0; i < pixels.length; i++) pixels[i] = values.get(i);
	}
	public static void glReadPixels(int x, int y, int width, int height, int format, int type, long pixels) {
		if (boundBuffer(35051) == 0) {
			throw unsupported("glReadPixels(client pointer) without a bound GL_PIXEL_PACK_BUFFER");
		}
		gl.readPixels(x, y, width, height, format, type, (int)pixels);
		if (DEBUG_GL_ERRORS) probe("glReadPixels(pack buffer offset " + pixels + ")");
	}
	public static int glGenSamplers() { return register(samplers, gl.createSampler()); }
	public static void glDeleteSamplers(int sampler) {
		if (sampler != 0) {
			gl.deleteSampler(samplers.get(sampler));
			samplers.set(sampler, null);
			for (int i = 0; i < samplerByUnit.length; i++) {
				if (samplerByUnit[i] == sampler) {
					samplerByUnit[i] = -1;
				}
			}
		}
	}
	public static void glBindSampler(int unit, int sampler) {
		if (unit >= 0 && unit < samplerByUnit.length) {
			if (bindFilter && samplerByUnit[unit] == sampler) {
				churnBindSkipped++;
				return;
			}
			samplerByUnit[unit] = sampler;
		}
		gl.bindSampler(unit, sampler == 0 ? null : samplers.get(sampler));
	}
	public static void glSamplerParameteri(int sampler, int pname, int param) { gl.samplerParameteri(samplers.get(sampler), pname, param); }
	public static void glSamplerParameteriv(int sampler, int pname, int[] values) {
		checkSamplerParameter(pname);
		if (values.length == 0) throw new IllegalArgumentException("sampler parameter value missing");
		gl.samplerParameteri(samplers.get(sampler), pname, values[0]);
	}
	public static void glSamplerParameterf(int sampler, int pname, float param) { gl.samplerParameterf(samplers.get(sampler), pname, param); }

	public static int glGenFramebuffers() {
		int framebuffer = register(framebuffers, gl.createFramebuffer());
		forgetDrawBuffers(framebuffer);
		return framebuffer;
	}
	public static void glDeleteFramebuffers(int framebuffer) {
		if (framebuffer != 0) {
			gl.deleteFramebuffer(framebuffers.get(framebuffer));
			framebuffers.set(framebuffer, null);
			forgetDrawBuffers(framebuffer);
			if (drawFramebuffer == framebuffer) {
				drawFramebuffer = 0;
			}
		}
	}
	public static void glBindFramebuffer(int target, int framebuffer) {
		if (target == 36160 || target == 36009) {
			drawFramebuffer = framebuffer;
		}
		gl.bindFramebuffer(target, framebuffer == 0 ? null : framebuffers.get(framebuffer));
	}

	private static int drawFramebuffer;
	private static int[][] drawBuffersByFramebuffer = new int[64][];

	private static void forgetDrawBuffers(final int framebuffer) {
		if (framebuffer >= 0 && framebuffer < drawBuffersByFramebuffer.length) {
			drawBuffersByFramebuffer[framebuffer] = null;
		}
	}

	private static boolean sameDrawBuffers(final int[] bufs) {
		int fb = drawFramebuffer;
		if (fb < 0) {
			return false;
		}
		if (fb >= drawBuffersByFramebuffer.length) {
			drawBuffersByFramebuffer = java.util.Arrays.copyOf(drawBuffersByFramebuffer, Math.max(fb + 1, drawBuffersByFramebuffer.length * 2));
		}
		int[] shadow = drawBuffersByFramebuffer[fb];
		if (bindFilter && shadow != null && java.util.Arrays.equals(shadow, bufs)) {
			churnBindSkipped++;
			return true;
		}
		drawBuffersByFramebuffer[fb] = bufs.clone();
		return false;
	}
	public static int glCheckFramebufferStatus(int target) { return gl.checkFramebufferStatus(target); }
	public static void glReadBuffer(int mode) { gl.readBuffer(mode); }
	public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
		gl.framebufferTexture2D(target, attachment, textarget, texture == 0 ? null : textures.get(texture), level);
	}
	public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
		gl.blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
		if (DEBUG_GL_ERRORS) probe("glBlitFramebuffer(" + srcX0 + "," + srcY0 + "," + srcX1 + "," + srcY1 + " -> " + dstX0 + "," + dstY0 + "," + dstX1 + "," + dstY1 + ")");
	}
	private static final int[] oneInvalidateAttachment = new int[1];
	public static void glInvalidateFramebuffer(int target, int attachment) {
		if (!bindFilter) {
			return;
		}
		oneInvalidateAttachment[0] = attachment;
		gl.invalidateFramebuffer(target, oneInvalidateAttachment);
	}
	public static void glInvalidateDrawDepth() {
		if (bindFilter && drawFramebuffer != 0) {
			glInvalidateFramebuffer(36009, 36096);
		}
	}
	public static boolean canInvalidatePresentedColor() {
		return bindFilter && !captureMode;
	}
	private static final int[] oneDrawBuffer = new int[1];
	public static void glDrawBuffer(int buf) { if (legacyChurn) { churnSink = new int[]{buf}; } oneDrawBuffer[0] = buf; if (!sameDrawBuffers(oneDrawBuffer)) { gl.drawBuffers(oneDrawBuffer); } }
	public static void glDrawBuffers(int[] bufs) { if (!sameDrawBuffers(bufs)) { gl.drawBuffers(bufs); } }
	public static void glDrawBuffers(int buf) { if (legacyChurn) { churnSink = new int[]{buf}; } oneDrawBuffer[0] = buf; if (!sameDrawBuffers(oneDrawBuffer)) { gl.drawBuffers(oneDrawBuffer); } }

	public static int glCreateShader(int type) {
		int shader = register(shaders, gl.createShader(type));
		shaderTypes.put(shader, type);
		return shader;
	}
	public static void glDeleteShader(int shader) {
		if (shader != 0) {
			gl.deleteShader(shaders.get(shader));
			shaders.set(shader, null);
			shaderTypes.remove(shader);
		}
	}
	public static void glShaderSource(int shader, CharSequence source) {
		String src = source.toString();
		if (src.replace("/*#moj_import", "").contains("#moj_import")) {
			throw new IllegalStateException("glShaderSource: unresolved #moj_import in shader " + shader + "; resolve imports before calling glShaderSource");
		}
		boolean fragment = shaderTypes.getOrDefault(shader, 0) == 35632;
		gl.shaderSource(shaders.get(shader), JSString.valueOf(RenderPearlGlslEs.translate(src, fragment)));
	}
	private static String shaderSourceStrings(int count, long strings, long lengths) {
		if (count < 0) throw new IllegalArgumentException("negative shader source count");
		if (count == 0) return "";
		Math.multiplyExact(count, Long.BYTES);
		LongBuffer pointers = org.lwjgl.system.MemoryUtil.memLongBuffer(strings, count);
		IntBuffer sizes = lengths == 0 ? null : org.lwjgl.system.MemoryUtil.memIntBuffer(lengths, count);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (int i = 0; i < count; i++) {
			long pointer = pointers.get(i);
			int length = sizes == null ? -1 : sizes.get(i);
			if (length < 0) {
				for (int j = 0; ; j++) {
					byte value = org.lwjgl.system.MemoryUtil.memGetByte(pointer + j);
					if (value == 0) break;
					bytes.write(value);
				}
			} else if (length > 0) {
				byte[] part = toArray(org.lwjgl.system.MemoryUtil.memByteBuffer(pointer, length));
				bytes.write(part, 0, part.length);
			}
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}
	public static void nglShaderSource(int shader, int count, long strings, long lengths) {
		glShaderSource(shader, shaderSourceStrings(count, strings, lengths));
	}
	public static void glCompileShader(int shader) { gl.compileShader(shaders.get(shader)); }
	public static int glGetShaderi(int shader, int pname) { return (int)jsNumber(gl.getShaderParameter(shaders.get(shader), pname)); }
	public static String glGetShaderInfoLog(int shader) { return gl.getShaderInfoLog(shaders.get(shader)).stringValue(); }
	public static String glGetShaderInfoLog(int shader, int maxLength) { return glGetShaderInfoLog(shader); }
	public static int glCreateProgram() { return register(programs, gl.createProgram()); }
	public static void glDeleteProgram(int program) {
		if (program != 0) {
			gl.deleteProgram(programs.get(program));
			programs.set(program, null);
			if (program < uniformLocationList.size()) {
				uniformLocationList.set(program, null);
			}
		}
	}
	public static void glAttachShader(int program, int shader) { gl.attachShader(programs.get(program), shaders.get(shader)); }
	public static void glDetachShader(int program, int shader) { gl.detachShader(programs.get(program), shaders.get(shader)); }
	public static void glLinkProgram(int program) { gl.linkProgram(programs.get(program)); }
	public static void glUseProgram(int program) { gl.useProgram(program == 0 ? null : programs.get(program)); }
	public static int glGetProgrami(int program, int pname) { return (int)jsNumber(gl.getProgramParameter(programs.get(program), pname)); }
	public static void glGetProgramiv(int program, int pname, int[] values) {
		if (values.length == 0) throw new IllegalArgumentException("program parameter destination missing");
		JSObject value = gl.getProgramParameter(programs.get(program), pname);
		if (parameterLength(value) != 1) throw unsupported("non-scalar glGetProgramiv result");
		values[0] = (int)parameterNumber(value, 0);
	}
	public static String glGetProgramInfoLog(int program) { return gl.getProgramInfoLog(programs.get(program)).stringValue(); }
	public static String glGetProgramInfoLog(int program, int maxLength) { return glGetProgramInfoLog(program); }
	public static void glBindAttribLocation(int program, int index, CharSequence name) { gl.bindAttribLocation(programs.get(program), index, JSString.valueOf(name.toString())); }
	public static int glGetAttribLocation(int program, CharSequence name) { return gl.getAttribLocation(programs.get(program), JSString.valueOf(name.toString())); }
	public static void glShaderSourceRaw(int shader, String source) {
		gl.shaderSource(shaders.get(shader), JSString.valueOf(source));
	}

	public static List<Object[]> activeUniforms(int program) {
		List<Object[]> out = new ArrayList<>();
		int count = (int) jsNumber(gl.getProgramParameter(programs.get(program), 35718));
		for (int i = 0; i < count; i++) {
			WebGLActiveInfo info = gl.getActiveUniform(programs.get(program), i);
			if (info != null) {
				out.add(new Object[] {info.getName(), info.getType()});
			}
		}
		return out;
	}

	public static String glGetActiveUniform(int program, int index, int maxLength, IntBuffer size, IntBuffer type) {
		if (maxLength < 0 || !size.hasRemaining() || !type.hasRemaining()) throw new IllegalArgumentException("invalid active uniform destination");
		WebGLActiveInfo info = gl.getActiveUniform(programs.get(program), index);
		if (info == null) throw new IllegalArgumentException("invalid active uniform index");
		size.put(size.position(), info.getSize());
		type.put(type.position(), info.getType());
		String name = info.getName();
		return name.substring(0, Math.min(name.length(), Math.max(0, maxLength - 1)));
	}
	public static int glGetUniformLocation(int program, CharSequence name) {
		WebGLUniformLocation loc = gl.getUniformLocation(programs.get(program), JSString.valueOf(name.toString()));
		if (loc == null) {
			return -1;
		}
		while (uniformLocationList.size() <= program) {
			uniformLocationList.add(null);
		}
		List<WebGLUniformLocation> list = uniformLocationList.get(program);
		if (list == null) {
			list = new ArrayList<>();
			uniformLocationList.set(program, list);
		}
		list.add(loc);
		return (program << 16) | (list.size() - 1);
	}
	private static WebGLUniformLocation uniformLocation(int location) {
		return uniformLocationList.get(location >> 16).get(location & 0xFFFF);
	}
	public static void glUniform1f(int location, float v0) { if (location >= 0) gl.uniform1f(uniformLocation(location), v0); }
	public static void glUniform2f(int location, float v0, float v1) { if (location >= 0) gl.uniform2f(uniformLocation(location), v0, v1); }
	public static void glUniform2i(int location, int v0, int v1) { if (location >= 0) gl.uniform2i(uniformLocation(location), v0, v1); }
	public static void glUniform3f(int location, float v0, float v1, float v2) { if (location >= 0) gl.uniform3f(uniformLocation(location), v0, v1, v2); }
	public static void glUniform3i(int location, int v0, int v1, int v2) { if (location >= 0) gl.uniform3i(uniformLocation(location), v0, v1, v2); }
	public static void glUniform4f(int location, float v0, float v1, float v2, float v3) { if (location >= 0) gl.uniform4f(uniformLocation(location), v0, v1, v2, v3); }
	public static void glUniform4i(int location, int v0, int v1, int v2, int v3) { if (location >= 0) gl.uniform4i(uniformLocation(location), v0, v1, v2, v3); }
	private static void checkMatrixTranspose(boolean transpose) {
		if (transpose) throw unsupported("transposed uniform matrices");
	}
	private static float[] matrixValues(FloatBuffer value, int elements) {
		float[] copy = new float[value.remaining() / elements * elements];
		for (int i = 0; i < copy.length; i++) copy[i] = value.get(value.position() + i);
		return copy;
	}
	private static float[] matrixValues(float[] value, int elements) {
		return value.length % elements == 0 ? value : java.util.Arrays.copyOf(value, value.length / elements * elements);
	}
	public static void glUniformMatrix3fv(int location, boolean transpose, FloatBuffer value) {
		checkMatrixTranspose(transpose);
		if (location >= 0 && value.remaining() >= 9) gl.uniformMatrix3fv(uniformLocation(location), false, matrixValues(value, 9));
	}
	public static void glUniformMatrix3fv(int location, boolean transpose, float[] value) {
		checkMatrixTranspose(transpose);
		if (location >= 0 && value.length >= 9) gl.uniformMatrix3fv(uniformLocation(location), false, matrixValues(value, 9));
	}
	public static void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) {
		checkMatrixTranspose(transpose);
		if (location >= 0 && value.remaining() >= 16) gl.uniformMatrix4fv(uniformLocation(location), false, matrixValues(value, 16));
	}
	public static void glUniformMatrix4fv(int location, boolean transpose, float[] value) {
		checkMatrixTranspose(transpose);
		if (location >= 0 && value.length >= 16) gl.uniformMatrix4fv(uniformLocation(location), false, matrixValues(value, 16));
	}
	public static void glUniform1i(int location, int v0) {
		if (location >= 0) {
			churnUniform1i++;
			gl.uniform1i(uniformLocationList.get(location >> 16).get(location & 0xFFFF), v0);
		}
	}
	public static int glGetUniformBlockIndex(int program, CharSequence name) {
		double index = gl.getUniformBlockIndex(programs.get(program), JSString.valueOf(name.toString()));
		return index > Integer.MAX_VALUE ? -1 : (int)index;
	}
	public static void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
		gl.uniformBlockBinding(programs.get(program), uniformBlockIndex, uniformBlockBinding);
		if (DEBUG_GL_ERRORS) probe("glUniformBlockBinding(" + uniformBlockIndex + ", " + uniformBlockBinding + ")");
	}
	public static String glGetActiveUniformBlockName(int program, int uniformBlockIndex) { return gl.getActiveUniformBlockName(programs.get(program), uniformBlockIndex).stringValue(); }

	private static final class Attrib {
		int size;
		int type;
		boolean normalized;
		int stride;
		long pointer;
		boolean integer;
		int buffer;
		int divisor;
		boolean set;
	}

	private static int currentVertexArray;

	private static final int MAX_ATTRIBS = 16;
	private static Attrib[][] vertexArrayAttribs = new Attrib[64][];
	private static int[] appliedBaseVertex = new int[64];

	private static Attrib[] attribsFor(final int vertexArray, final boolean create) {
		if (vertexArray >= vertexArrayAttribs.length) {
			if (!create) {
				return null;
			}
			int grown = Math.max(vertexArray + 1, vertexArrayAttribs.length * 2);
			vertexArrayAttribs = java.util.Arrays.copyOf(vertexArrayAttribs, grown);
			appliedBaseVertex = java.util.Arrays.copyOf(appliedBaseVertex, grown);
		}
		Attrib[] a = vertexArrayAttribs[vertexArray];
		if (a == null && create) {
			a = new Attrib[MAX_ATTRIBS];
			vertexArrayAttribs[vertexArray] = a;
		}
		return a;
	}

	public static int glGenVertexArrays() { return register(vertexArrays, gl.createVertexArray()); }
	public static void glDeleteVertexArrays(int array) {
		if (array != 0) {
			gl.deleteVertexArray(vertexArrays.get(array));
			vertexArrays.set(array, null);
			if (array < elementBufferByVao.length) {
				elementBufferByVao[array] = -1;
			}
			if (array < divisorByVao.length) {
				divisorByVao[array] = null;
			}
		}
	}
	public static void glBindVertexArray(int array) {
		currentVertexArray = array;
		gl.bindVertexArray(array == 0 ? null : vertexArrays.get(array));
	}
	public static void glEnableVertexAttribArray(int index) { gl.enableVertexAttribArray(index); }
	public static void glVertexAttrib4f(int index, float x, float y, float z, float w) { gl.vertexAttrib4f(index, x, y, z, w); }
	public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
		recordAttrib(index, size, type, normalized, stride, pointer, false);
		gl.vertexAttribPointer(index, size, type, normalized, stride, (int)pointer);
	}
	public static void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer) {
		recordAttrib(index, size, type, false, stride, pointer, true);
		gl.vertexAttribIPointer(index, size, type, stride, (int)pointer);
	}
	public static void glVertexAttribDivisor(int index, int divisor) {
		boolean redundant = false;
		if (index >= 0 && index < MAX_ATTRIBS) {
			int vao = currentVertexArray;
			if (vao >= divisorByVao.length) {
				divisorByVao = java.util.Arrays.copyOf(divisorByVao, Math.max(vao + 1, divisorByVao.length * 2));
			}
			int[] shadow = divisorByVao[vao];
			if (shadow == null) {
				shadow = filledArray(MAX_ATTRIBS);
				divisorByVao[vao] = shadow;
			}
			redundant = bindFilter && shadow[index] == divisor;
			shadow[index] = divisor;
		}
		Attrib[] attribs = attribsFor(currentVertexArray, false);
		Attrib a = attribs == null || index >= MAX_ATTRIBS ? null : attribs[index];
		if (a != null) {
			a.divisor = divisor;
		}
		if (redundant) {
			churnBindSkipped++;
			return;
		}
		gl.vertexAttribDivisor(index, divisor);
	}

	private static void recordAttrib(final int index, final int size, final int type, final boolean normalized,
			final int stride, final long pointer, final boolean integer) {
		if (index >= MAX_ATTRIBS) {
			throw new IllegalStateException("GlApi: attribute index " + index + " exceeds MAX_ATTRIBS " + MAX_ATTRIBS);
		}
		Attrib[] attribs = attribsFor(currentVertexArray, true);
		Attrib a = attribs[index];
		if (a == null) {
			a = new Attrib();
			attribs[index] = a;
			churnAttribMinted++;
		} else {
			churnAttribReused++;
			if (legacyChurn) {
				churnSink = new Attrib();
			}
		}
		a.size = size;
		a.type = type;
		a.normalized = normalized;
		a.stride = stride;
		a.pointer = pointer;
		a.integer = integer;
		a.buffer = boundBuffer(34962);
		a.divisor = 0;
		a.set = true;
		appliedBaseVertex[currentVertexArray] = 0;
	}

	private static void applyBaseVertex(final int baseVertex) {
		int applied = currentVertexArray < appliedBaseVertex.length ? appliedBaseVertex[currentVertexArray] : 0;
		if (applied == baseVertex) {
			return;
		}
		Attrib[] attribs = attribsFor(currentVertexArray, false);
		if (attribs == null) {
			throw new IllegalStateException("baseVertex " + baseVertex + " on vertex array " + currentVertexArray + " with no recorded attributes");
		}
		int restoreBuffer = boundBuffer(34962);
		churnBaseVertexApplied++;
		int arrayBinding = bindFilter ? restoreBuffer : -1;
		for (int index = 0; index < MAX_ATTRIBS; index++) {
			Attrib a = attribs[index];
			if (a == null || !a.set || a.divisor != 0) {
				continue;
			}
			if (a.stride == 0) {
				throw unsupported("baseVertex on a tightly packed attribute (stride 0)");
			}
			int offset = (int)(a.pointer + (long)baseVertex * a.stride);
			if (a.buffer != arrayBinding) {
				gl.bindBuffer(34962, a.buffer == 0 ? null : buffers.get(a.buffer));
				arrayBinding = bindFilter ? a.buffer : -1;
			} else {
				churnBindSkipped++;
			}
			if (a.integer) {
				gl.vertexAttribIPointer(index, a.size, a.type, a.stride, offset);
			} else {
				gl.vertexAttribPointer(index, a.size, a.type, a.normalized, a.stride, offset);
			}
		}
		if (arrayBinding != restoreBuffer) {
			gl.bindBuffer(34962, restoreBuffer == 0 ? null : buffers.get(restoreBuffer));
		}
		appliedBaseVertex[currentVertexArray] = baseVertex;
	}

	public static void glDrawArrays(int mode, int first, int count) { gl.drawArrays(mode, first, count); }
	public static void glDrawArraysInstanced(int mode, int first, int count, int primcount) { gl.drawArraysInstanced(mode, first, count, primcount); }
	public static void glDrawElements(int mode, int count, int type, long indices) { gl.drawElements(mode, count, type, (int)indices); }
	public static void glDrawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int primcount, int basevertex) {
		applyBaseVertex(basevertex);
		if (primcount == 1) {
			gl.drawElements(mode, count, type, (int)indices);
		} else {
			gl.drawElementsInstanced(mode, count, type, (int)indices, primcount);
		}
		if (DEBUG_GL_ERRORS) probe("glDrawElementsInstanced(count=" + count + ", baseVertex=" + basevertex + ")");
	}
	public static void nglMultiDrawArrays(int mode, long first, long count, int drawcount) {
		IntBuffer firsts = org.lwjgl.system.MemoryUtil.memIntBuffer(first, drawcount);
		IntBuffer counts = org.lwjgl.system.MemoryUtil.memIntBuffer(count, drawcount);
		for (int i = 0; i < drawcount; i++) {
			gl.drawArrays(mode, firsts.get(i), counts.get(i));
		}
	}
	public static void nglMultiDrawElementsBaseVertex(int mode, long count, int type, long indices, int drawcount, long basevertex) {
		IntBuffer counts = org.lwjgl.system.MemoryUtil.memIntBuffer(count, drawcount);
		java.nio.LongBuffer offsets = org.lwjgl.system.MemoryUtil.memLongBuffer(indices, drawcount);
		IntBuffer bases = org.lwjgl.system.MemoryUtil.memIntBuffer(basevertex, drawcount);
		for (int i = 0; i < drawcount; i++) {
			applyBaseVertex(bases.get(i));
			gl.drawElements(mode, counts.get(i), type, (int)offsets.get(i));
		}
	}
	public static void glDrawArraysIndirect(int mode, long indirect) { throw unsupported("indirect draw"); }
	public static void glDrawElementsIndirect(int mode, int type, long indirect) { throw unsupported("indirect draw"); }
	public static void glMultiDrawArraysIndirect(int mode, long indirect, int drawcount, int stride) { throw unsupported("indirect draw"); }
	public static void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) { throw unsupported("indirect draw"); }
	public static void glDrawArraysInstancedBaseInstance(int mode, int first, int count, int primcount, int baseinstance) { throw unsupported("base instance"); }
	public static void glDrawElementsInstancedBaseVertexBaseInstance(int mode, int count, int type, long indices, int primcount, int basevertex, int baseinstance) { throw unsupported("base instance"); }
	public static void glBindVertexBuffer(int bindingindex, int buffer, long offset, int stride) { throw unsupported("vertex attrib binding"); }
	public static void glVertexAttribBinding(int attribindex, int bindingindex) { throw unsupported("vertex attrib binding"); }
	public static void glVertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset) { throw unsupported("vertex attrib binding"); }
	public static void glVertexAttribIFormat(int attribindex, int size, int type, int relativeoffset) { throw unsupported("vertex attrib binding"); }
	public static void glVertexBindingDivisor(int bindingindex, int divisor) { throw unsupported("vertex attrib binding"); }
	public static void glClipControl(int origin, int depth) { throw unsupported("clip control"); }

	public static long glFenceSync(int condition, int flags) {
		long handle = nextSync++;
		syncs.put(handle, gl.fenceSync(condition, flags));
		return handle;
	}
	public static int glClientWaitSync(long sync, int flags, long timeout) {
		int result = gl.clientWaitSync(syncs.get(sync), flags, 0);
		if (result == 37147 && timeout != 0L) {
			return 37148;
		}
		return result;
	}
	public static void glDeleteSync(long sync) {
		WebGLSync s = syncs.remove(sync);
		if (s != null) {
			gl.deleteSync(s);
		}
	}
	public static int glGenQueries() { return register(queries, gl.createQuery()); }
	public static void glGenQueries(int[] out) {
		for (int i = 0; i < out.length; i++) {
			out[i] = glGenQueries();
		}
	}
	public static void glDeleteQueries(int query) {
		if (query != 0) {
			gl.deleteQuery(queries.get(query));
			queries.set(query, null);
		}
	}
	public static void glDeleteQueries(int[] qs) {
		for (int q : qs) {
			glDeleteQueries(q);
		}
	}
	public static void glQueryCounter(int query, int target) {
	}
	public static int glGetQueryObjecti(int query, int pname) { return 1; }
	public static long glGetQueryObjectui64(int query, int pname) { return 0L; }

	public static void glObjectLabel(int identifier, int name, CharSequence label) { }
	public static void glPushDebugGroup(int source, int id, CharSequence message) { }
	public static void glPopDebugGroup() { }
	public static void glLabelObjectEXT(int type, int object, CharSequence label) { }

	@JSBody(params = "value", script = "return +value;")
	private static native double jsNumber(JSObject value);
	@JSBody(params = "value", script = "return typeof value === 'number' || typeof value === 'boolean' ? 1 : Array.isArray(value) || ArrayBuffer.isView(value) ? value.length : -1;")
	private static native int parameterLength(JSObject value);
	@JSBody(params = {"value", "index"}, script = "return +(typeof value === 'number' || typeof value === 'boolean' ? value : value[index]);")
	private static native double parameterNumber(JSObject value, int index);
}
