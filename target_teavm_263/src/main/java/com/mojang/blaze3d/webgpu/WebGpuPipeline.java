// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blaze3d.webgpu;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.teavm.jso.JSObject;

final class WebGpuPipeline implements BackendRenderPipeline {
	static final int KIND_UNIFORM = 0;
	static final int KIND_STORAGE = 1;
	static final int KIND_SAMPLER = 2;
	static final int KIND_TEXTURE_2D = 3;
	static final int KIND_TEXTURE_CUBE = 4;

	final BackendRenderPipeline.CreateInfo info;
	final PrimitiveTopology topology;
	final String[] uniformNames;
	final int[] uniformBindings;
	final int vertexSlots;
	final boolean[] slotUsed = new boolean[16];
	private final WebGpuDevice device;
	private final JSObject vert;
	private final JSObject frag;
	final TreeMap<Integer, Integer> kinds = new TreeMap<>();
	private final Map<Integer, Integer> visibility = new HashMap<>();
	private final String label;
	private final Map<String, Variant> variants = new HashMap<>();
	private boolean closed;

	static final class Variant {
		final JSObject layout;
		final JSObject pipeline;
		final int pipelineId;
		final int[] bindings;
		final int[] kindOf;
		final boolean[] depthTexture;
		final int[] uniformIndex;
		final int dynamicCount;
		final Long2ObjectOpenHashMap<WebGpuRenderPass.BindGroup> groups = new Long2ObjectOpenHashMap<>();
		void clearGroups() {
			for (WebGpuRenderPass.BindGroup g : groups.values()) {
				WebGpuNatives.dropLater(g.id);
			}
			groups.clear();
			lastGroup = null;
			lastPartsLen = 0;
		}

		int[] lastParts;
		int lastPartsLen;
		WebGpuRenderPass.BindGroup lastGroup;

		Variant(JSObject layout, JSObject pipeline, int[] bindings, int[] kindOf, boolean[] depthTexture, int[] uniformIndex, int dynamicCount) {
			this.layout = layout;
			this.pipeline = pipeline;
			this.pipelineId = WebGpuNatives.put(pipeline);
			this.bindings = bindings;
			this.kindOf = kindOf;
			this.depthTexture = depthTexture;
			this.uniformIndex = uniformIndex;
			this.dynamicCount = dynamicCount;
		}
	}

	WebGpuPipeline(
		WebGpuDevice device, BackendRenderPipeline.CreateInfo info, JSObject vert, JSObject frag, int[][] vertBindings, int[][] fragBindings,
		Map<String, Integer> inputs, String label
	) {
		this.device = device;
		this.info = info;
		this.topology = info.primitiveTopology();
		this.vert = vert;
		this.frag = frag;
		this.label = label;
		for (int[] b : vertBindings) {
			kinds.put(b[0], b[1]);
			visibility.merge(b[0], WebGpuNatives.VIS_VERTEX, (a, c) -> a | c);
		}
		for (int[] b : fragBindings) {
			kinds.put(b[0], b[1]);
			visibility.merge(b[0], WebGpuNatives.VIS_FRAGMENT, (a, c) -> a | c);
		}
		List<BindGroupLayout.UniformDescription> uniforms = info.uniforms();
		uniformNames = new String[uniforms.size()];
		uniformBindings = new int[uniforms.size()];
		for (int i = 0; i < uniformNames.length; i++) {
			uniformNames[i] = uniforms.get(i).name();
			Integer b = device.bindingOf.get(uniformNames[i]);
			uniformBindings[i] = b == null ? -1 : b;
			if (b == null) {
				WebGpuDevice.warnOnce("uniform '" + uniformNames[i] + "' of " + label + " has no WGSL binding");
			}
		}
		int slots = 0;
		for (BackendRenderPipeline.CreateInfo.VertexBuffer vb : info.vertexBuffers()) {
			slotUsed[vb.bufferSlot()] = true;
			slots = Math.max(slots, vb.bufferSlot() + 1);
		}
		vertexSlots = slots;
		Set<Integer> provided = new HashSet<>();
		for (BackendRenderPipeline.CreateInfo.AttribBinding a : info.attribBindings()) {
			provided.add(a.location());
		}
		for (Map.Entry<String, Integer> e : inputs.entrySet()) {
			if (!provided.contains(e.getValue())) {
				WebGpuDevice.warnOnce(label + ": WGSL vertex input " + e.getKey() + "@" + e.getValue() + " has no vertex attribute");
			}
		}
	}

	static int kindCode(String kind) {
		return switch (kind) {
			case "uniform" -> KIND_UNIFORM;
			case "storage" -> KIND_STORAGE;
			case "sampler" -> KIND_SAMPLER;
			case "texture_cube" -> KIND_TEXTURE_CUBE;
			default -> KIND_TEXTURE_2D;
		};
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		if (!closed) {
			closed = true;
			for (Variant v : variants.values()) {
				v.clearGroups();
				WebGpuNatives.dropLater(v.pipelineId);
			}
			variants.clear();
			lastVariant = null;
		}
	}

	boolean isStrip() {
		return topology == PrimitiveTopology.TRIANGLE_STRIP || topology == PrimitiveTopology.DEBUG_LINE_STRIP;
	}

	private int uniformIndexFor(int binding, int kind) {
		int target = kind == KIND_SAMPLER ? binding - 1 : binding;
		for (int i = 0; i < uniformBindings.length; i++) {
			if (uniformBindings[i] == target) {
				return i;
			}
		}
		return -1;
	}

	private String lastDepthFormat;
	private String[] lastFormats;
	private long lastMask;
	private IndexType lastStrip;
	private Variant lastVariant;

	Variant variant(String depthFormat, String[] colorFormats, long depthTextureMask, IndexType stripIndex) {
		if (lastVariant != null && depthFormat == lastDepthFormat && depthTextureMask == lastMask
				&& (!isStrip() || stripIndex == lastStrip) && sameFormats(colorFormats, lastFormats)) {
			return lastVariant;
		}
		Variant v = variantSlow(depthFormat, colorFormats, depthTextureMask, stripIndex);
		lastDepthFormat = depthFormat;
		lastFormats = colorFormats.clone();
		lastMask = depthTextureMask;
		lastStrip = stripIndex;
		lastVariant = v;
		return v;
	}

	private static boolean sameFormats(String[] a, String[] b) {
		if (b == null || a.length != b.length) {
			return false;
		}
		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i] && (a[i] == null || !a[i].equals(b[i]))) {
				return false;
			}
		}
		return true;
	}

	private Variant variantSlow(String depthFormat, String[] colorFormats, long depthTextureMask, IndexType stripIndex) {
		StringBuilder key = new StringBuilder(depthFormat == null ? "-" : depthFormat);
		for (String f : colorFormats) {
			key.append('|').append(f == null ? "-" : f);
		}
		key.append('|').append(depthTextureMask);
		if (isStrip()) {
			key.append('|').append(stripIndex == IndexType.INT ? "uint32" : "uint16");
		}
		String k = key.toString();
		Variant v = variants.get(k);
		if (v == null) {
			v = build(depthFormat, colorFormats, depthTextureMask, stripIndex);
			variants.put(k, v);
		}
		return v;
	}

	private Variant build(String depthFormat, String[] colorFormats, long depthTextureMask, IndexType stripIndex) {
		int n = kinds.size();
		int[] bindings = new int[n];
		int[] kindOf = new int[n];
		int[] uniformIndex = new int[n];
		boolean[] depth = new boolean[n];
		int dynamicCount = 0;
		StringBuilder layout = new StringBuilder("[");
		int i = 0;
		int texIndex = 0;
		for (Map.Entry<Integer, Integer> e : kinds.entrySet()) {
			int binding = e.getKey();
			int kind = e.getValue();
			bindings[i] = binding;
			kindOf[i] = kind;
			uniformIndex[i] = uniformIndexFor(binding, kind);
			boolean d = false;
			if (kind == KIND_TEXTURE_2D) {
				d = (depthTextureMask & (1L << texIndex)) != 0;
				texIndex++;
			} else if (kind == KIND_SAMPLER) {
				Integer texKind = kinds.get(binding - 1);
				d = texKind != null && texKind == KIND_TEXTURE_2D && i > 0 && depth[i - 1];
			}
			depth[i] = d;
			if (kind == KIND_UNIFORM) {
				dynamicCount++;
			}
			if (i > 0) {
				layout.append(',');
			}
			layout.append("{\"b\":").append(binding).append(",\"v\":").append(visibility.get(binding))
				.append(",\"k\":\"").append(kindName(kind)).append("\",\"d\":").append(d).append('}');
			i++;
		}
		layout.append(']');
		JSObject layoutObj = WebGpuNatives.createLayout(layout.toString(), label);
		JSObject pipeline = WebGpuNatives.createPipeline(vert, frag, layoutObj, descriptor(depthFormat, colorFormats, stripIndex), label);
		return new Variant(layoutObj, pipeline, bindings, kindOf, depth, uniformIndex, dynamicCount);
	}

	private static String kindName(int kind) {
		return switch (kind) {
			case KIND_UNIFORM -> "uniform";
			case KIND_STORAGE -> "storage";
			case KIND_SAMPLER -> "sampler";
			case KIND_TEXTURE_CUBE -> "texture_cube";
			default -> "texture_2d";
		};
	}

	private String descriptor(String depthFormat, String[] colorFormats, IndexType stripIndex) {
		StringBuilder d = new StringBuilder("{\"buffers\":[");
		for (int slot = 0; slot < vertexSlots; slot++) {
			if (slot > 0) {
				d.append(',');
			}
			BackendRenderPipeline.CreateInfo.VertexBuffer vb = null;
			for (BackendRenderPipeline.CreateInfo.VertexBuffer candidate : info.vertexBuffers()) {
				if (candidate.bufferSlot() == slot) {
					vb = candidate;
				}
			}
			if (vb == null) {
				d.append("null");
				continue;
			}
			d.append("{\"arrayStride\":").append(vb.stride()).append(",\"stepMode\":\"").append(vb.stepRate() > 0 ? "instance" : "vertex")
				.append("\",\"attributes\":[");
			boolean first = true;
			for (BackendRenderPipeline.CreateInfo.AttribBinding a : info.attribBindings()) {
				if (a.bufferSlot() != slot) {
					continue;
				}
				if (!first) {
					d.append(',');
				}
				first = false;
				d.append("{\"format\":\"").append(WebGpuDevice.vertexFormat(a.format())).append("\",\"offset\":").append(a.offset())
					.append(",\"shaderLocation\":").append(a.location()).append('}');
			}
			d.append("]}");
		}
		d.append("],\"topology\":\"").append(topologyName(topology)).append('"');
		if (isStrip()) {
			d.append(",\"strip\":\"").append(stripIndex == IndexType.INT ? "uint32" : "uint16").append('"');
		}
		d.append(",\"cull\":\"").append(info.cull() ? "back" : "none").append('"');
		DepthStencilState ds = info.depthStencilState();
		if (depthFormat != null) {
			if (ds != null) {
				d.append(",\"depth\":{\"format\":\"").append(depthFormat).append("\",\"write\":").append(ds.writeDepth()).append(",\"compare\":\"")
					.append(compare(ds.depthTest())).append("\",\"bias\":").append((int) ds.depthBiasConstant()).append(",\"slope\":")
					.append(ds.depthBiasScaleFactor()).append('}');
			} else {
				d.append(",\"depth\":{\"format\":\"").append(depthFormat).append("\",\"write\":false,\"compare\":\"always\",\"bias\":0,\"slope\":0}");
			}
		}
		d.append(",\"targets\":[");
		List<ColorTargetState> states = info.colorTargetStates();
		for (int i = 0; i < colorFormats.length; i++) {
			if (i > 0) {
				d.append(',');
			}
			if (colorFormats[i] == null) {
				d.append("null");
				continue;
			}
			ColorTargetState s = i < states.size() && states.get(i) != null ? states.get(i) : ColorTargetState.DEFAULT;
			d.append("{\"format\":\"").append(colorFormats[i]).append("\",\"writeMask\":").append(s.writeMask() & 15);
			Optional<BlendFunction> blend = s.blendFunction();
			if (blend.isPresent()) {
				d.append(",\"blend\":{\"color\":").append(equation(blend.get().color())).append(",\"alpha\":").append(equation(blend.get().alpha())).append('}');
			}
			d.append('}');
		}
		d.append("]}");
		return d.toString();
	}

	private static String equation(BlendEquation eq) {
		return "{\"srcFactor\":\"" + factor(eq.sourceFactor()) + "\",\"dstFactor\":\"" + factor(eq.destFactor()) + "\",\"operation\":\"" + op(eq.op()) + "\"}";
	}

	private static String factor(BlendFactor f) {
		return switch (f) {
			case ZERO -> "zero";
			case ONE -> "one";
			case SRC_COLOR -> "src";
			case ONE_MINUS_SRC_COLOR -> "one-minus-src";
			case SRC_ALPHA -> "src-alpha";
			case ONE_MINUS_SRC_ALPHA -> "one-minus-src-alpha";
			case DST_COLOR -> "dst";
			case ONE_MINUS_DST_COLOR -> "one-minus-dst";
			case DST_ALPHA -> "dst-alpha";
			case ONE_MINUS_DST_ALPHA -> "one-minus-dst-alpha";
			case SRC_ALPHA_SATURATE -> "src-alpha-saturated";
			case CONSTANT_COLOR, CONSTANT_ALPHA -> "constant";
			case ONE_MINUS_CONSTANT_COLOR, ONE_MINUS_CONSTANT_ALPHA -> "one-minus-constant";
		};
	}

	private static String op(BlendOp op) {
		return switch (op) {
			case ADD -> "add";
			case SUBTRACT -> "subtract";
			case REVERSE_SUBTRACT -> "reverse-subtract";
			case MIN -> "min";
			case MAX -> "max";
		};
	}

	static String compare(CompareOp op) {
		return switch (op) {
			case ALWAYS_PASS -> "always";
			case LESS_THAN -> "less";
			case LESS_THAN_OR_EQUAL -> "less-equal";
			case EQUAL -> "equal";
			case NOT_EQUAL -> "not-equal";
			case GREATER_THAN_OR_EQUAL -> "greater-equal";
			case GREATER_THAN -> "greater";
			case NEVER_PASS -> "never";
		};
	}

	static String topologyName(PrimitiveTopology t) {
		return switch (t) {
			case DEBUG_LINES -> "line-list";
			case DEBUG_LINE_STRIP -> "line-strip";
			case POINTS -> "point-list";
			case TRIANGLE_STRIP -> "triangle-strip";
			default -> "triangle-list";
		};
	}
}
