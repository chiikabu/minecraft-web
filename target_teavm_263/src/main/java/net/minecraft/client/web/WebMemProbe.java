// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public final class WebMemProbe {
	@JSFunctor
	private interface StatsFn extends JSObject {
		String stats();
	}

	@JSFunctor
	private interface ResetFn extends JSObject {
		void reset();
	}

	@JSFunctor
	private interface FlagFn extends JSObject {
		boolean set(String name, boolean value);
	}

	@JSFunctor
	private interface NumFn extends JSObject {
		double set(String name, double value);
	}

	private WebMemProbe() {
	}

	private static int uncompiledSections(net.minecraft.client.Minecraft minecraft) {
		int count = 0;
		for (SectionRenderDispatcher.RenderSection section : minecraft.levelRenderer.visibleSections()) {
			if (section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) count++;
		}
		return count;
	}

	public static void install() {
		register((StatsFn) WebMemProbe::snapshot);
		registerFlags((FlagFn) WebMemProbe::setFlag);
		registerTune((NumFn) WebMemProbe::setNum);
		registerChurn((StatsFn) WebMemProbe::churn);
		registerFrameReset((ResetFn) WebFrameProbe::reset);
		registerPerfActivity((ResetFn) WebMemProbe::markPerfActivity);
		registerSections((NumFn2) WebMemProbe::sections);
	}

	private static String churn() {
		int[] gl = com.mojang.renderpearl.backend.opengl.GlApi.churnCounters();
		int[] heap = MemoryUtil.churnCounters();
		int[] submit = net.minecraft.client.renderer.SubmitNodeStorage.churnCounters();
		int[] feature = net.minecraft.client.renderer.feature.FeatureRenderDispatcher.churnCounters();
		int[] chunkBuild = WebChunkBuildQueue.churnCounters();
		int[] occlusion = WebOcclusionProbe.churnCounters();
		int[] occlusionBudget = WebOcclusionProbe.budgetCounters();
		int[] decode = WebInflate.churnCounters();
		net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
		net.minecraft.client.Options options = minecraft.options;
		String worldName = WebWorkerLifecycle.currentWorldName();
		String worldType = WebWorkerLifecycle.currentWorldType();
		String dimension = minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();

		StringBuilder out = new StringBuilder(640);
		out.append("{\"frames\":").append(net.minecraft.client.web.WebMain.framesPresented())
			.append(",\"condition\":{")
			.append("\"renderDistance\":").append(options.renderDistance().get())
			.append(",\"effectiveRenderDistance\":").append(options.getEffectiveRenderDistance())
			.append(",\"simulationDistance\":").append(options.simulationDistance().get())
			.append(",\"serverSimulationDistance\":").append(minecraft.level == null ? 0 : minecraft.level.getServerSimulationDistance())
			.append(",\"mipmapLevels\":").append(options.mipmapLevels().get())
			.append(",\"clouds\":\"").append(options.cloudStatus().get().name()).append('"')
			.append(",\"ambientOcclusion\":").append(options.ambientOcclusion().get())
			.append(",\"entityShadows\":").append(options.entityShadows().get())
			.append(",\"fov\":").append(options.fov().get())
			.append(",\"guiScale\":").append(options.guiScale().get())
			.append(",\"x\":").append(minecraft.player == null ? 0 : minecraft.player.getX())
			.append(",\"y\":").append(minecraft.player == null ? 0 : minecraft.player.getY())
			.append(",\"z\":").append(minecraft.player == null ? 0 : minecraft.player.getZ())
			.append(",\"yaw\":").append(minecraft.player == null ? 0 : minecraft.player.getYRot())
			.append(",\"pitch\":").append(minecraft.player == null ? 0 : minecraft.player.getXRot())
			.append(",\"sectionsSettled\":").append(minecraft.level != null && minecraft.levelRenderer.hasRenderedAllSections())
			.append(",\"loadedChunks\":").append(minecraft.level == null ? 0 : minecraft.level.getChunkSource().getLoadedChunksCount())
			.append(",\"visibleUncompiledSections\":").append(uncompiledSections(minecraft))
			.append(",\"fpsLimitOption\":").append(options.framerateLimit().get())
			.append(",\"vsync\":").append(options.enableVsync().get())
			.append(",\"effectiveFpsLimit\":").append(minecraft.gameRenderer.gameRenderState().framerateLimit)
			.append(",\"throttleReason\":\"")
			.append(minecraft.getFramerateLimitTracker().getThrottleReason().name())
			.append("\",\"inactivityFpsLimit\":\"").append(options.inactivityFpsLimit().get().getSerializedName())
			.append("\",\"windowActive\":").append(minecraft.isWindowActive())
			.append(",\"overlayActive\":").append(minecraft.gui.overlay() != null)
			.append(",\"worldName\":\"").append(str(worldName))
			.append("\",\"worldType\":\"").append(worldType)
			.append("\",\"dimension\":\"").append(str(dimension)).append("\"")
			.append("},\"gl\":{")
			.append("\"bindBuffer\":").append(gl[0])
			.append(",\"mapRange\":").append(gl[1])
			.append(",\"nMapRange\":").append(gl[2])
			.append(",\"shadowReused\":").append(gl[3])
			.append(",\"shadowGrown\":").append(gl[4])
			.append(",\"uniform1i\":").append(gl[5])
			.append(",\"attribMinted\":").append(gl[6])
			.append(",\"attribReused\":").append(gl[7])
			.append(",\"baseVertexApplied\":").append(gl[8])
			.append(",\"viewUploads\":").append(gl[9])
			.append(",\"copyUploads\":").append(gl[10])
			.append("},\"submit\":{")
			.append("\"stored\":").append(submit[0])
			.append(",\"drained\":").append(submit[1])
			.append("},\"feature\":{")
			.append("\"created\":").append(feature[0])
			.append(",\"prepared\":").append(feature[1])
			.append(",\"collections\":").append(feature[2])
			.append(",\"executed\":").append(feature[3])
			.append(",\"draws\":").append(feature.length > 4 ? feature[4] : 0)
			.append(",\"indices\":").append(feature.length > 5 ? feature[5] : 0)
			.append(",\"empty\":").append(feature.length > 6 ? feature[6] : 0)
			.append("},\"target\":").append(net.minecraft.client.renderer.Web263TargetProbe.json())
			.append(",\"targetOverflow\":").append(net.minecraft.client.renderer.Web263TargetProbe.overflow)
			.append(",\"pipSeen\":").append(net.minecraft.client.renderer.Web263TargetProbe.pipSeen)
			.append(",\"pipMissing\":").append(net.minecraft.client.renderer.Web263TargetProbe.pipMissing)
			.append(",\"pipDollSeen\":").append(net.minecraft.client.renderer.Web263TargetProbe.pipDollSeen)
			.append(",\"pipDollMissing\":").append(net.minecraft.client.renderer.Web263TargetProbe.pipDollMissing)
			.append(",\"pipDollRequested\":").append(net.minecraft.client.renderer.Web263TargetProbe.pipDollRequested)
			.append(",\"pipFirstStateName\":\"")
			.append(net.minecraft.client.renderer.Web263TargetProbe.pipFirstStateName == null
				? "" : net.minecraft.client.renderer.Web263TargetProbe.pipFirstStateName)
			.append("\",\"pipDollClassName\":\"")
			.append(net.minecraft.client.renderer.Web263TargetProbe.pipDollClassName)
			.append("\",\"pipBlit\":\"").append(str(net.minecraft.client.renderer.Web263TargetProbe.pipBlit))
			.append("\",\"readbackRan\":").append(net.minecraft.client.renderer.Web263TargetProbe.readbackRan)
			.append(",\"readbackPip\":\"").append(str(net.minecraft.client.renderer.Web263TargetProbe.readbackPip))
			.append("\",\"readbackControl\":\"").append(str(net.minecraft.client.renderer.Web263TargetProbe.readbackControl))
			.append('"')
			.append(",\"fakeHeap\":{")
			.append("\"resolveHits\":").append(heap[0])
			.append(",\"resolveMisses\":").append(heap[1])
			.append(",\"blocksMinted\":").append(heap[2])
			.append(",\"foreignRegistered\":").append(heap[3])
			.append(",\"viewsMinted\":").append(heap[4])
			.append("},\"chunkBuild\":{")
			.append("\"micros\":").append(chunkBuild[0])
			.append(",\"tasks\":").append(chunkBuild[1])
			.append(",\"drainFrames\":").append(chunkBuild[2])
			.append(",\"queueDepth\":").append(chunkBuild[3])
			.append(",\"backlogFrames\":").append(chunkBuild[4])
			.append(",\"maxQueueDepth\":").append(chunkBuild[5])
			.append("},\"occlusion\":{")
			.append("\"micros\":").append(occlusion[0])
			.append(",\"runs\":").append(occlusion[1])
			.append(",\"worstMicros\":").append(occlusion[2])
			.append(",\"budgetMicros\":").append(occlusionBudget[0])
			.append(",\"budgetSlices\":").append(occlusionBudget[1])
			.append(",\"budgetWorstMicros\":").append(occlusionBudget[2])
			.append(",\"budgetBuilds\":").append(occlusionBudget[3])
			.append("},\"culling\":{\"fogSections\":").append(net.minecraft.client.renderer.Web263Culling.fogSections)
			.append(",\"hiddenEntities\":").append(net.minecraft.client.renderer.Web263Culling.hiddenEntities)
			.append(",\"animationDraws\":").append(net.minecraft.client.renderer.Web263Culling.animationDraws)
			.append(",\"skippedAnimationTicks\":").append(net.minecraft.client.renderer.Web263Culling.skippedAnimationTicks)
			.append(",\"culledEntityTicks\":").append(net.minecraft.client.renderer.Web263Culling.culledEntityTicks)
			.append(",\"fullEntityTicks\":").append(net.minecraft.client.renderer.Web263Culling.fullEntityTicks)
			.append("},\"frame\":").append(WebFrameProbe.toJson())
			.append(",\"decode\":{")
			.append("\"calls\":").append(decode[0])
			.append(",\"inBytes\":").append(decode[1])
			.append(",\"outBytes\":").append(decode[2])
			.append(",\"micros\":").append(decode[3])
			.append("}}");
		return out.toString();
	}

	private static String str(final String value) {
		return value == null ? "" : value.replace('\\', '/').replace('"', '\'');
	}

	private static boolean setFlag(String name, boolean value) {
		switch (name) {
			case "zeroCopyUploads":
				com.mojang.renderpearl.backend.opengl.GlApi.zeroCopyUploads = value;
				return true;
			case "legacyChurn":
				MemoryUtil.legacyChurn = value;
				return true;
			default:
				return false;
		}
	}

	private static double setNum(String name, double value) {
		switch (name) {
			case "guiScale":
				net.minecraft.client.Minecraft.getInstance().options.guiScale().set(Math.max(0, (int)value));
				return net.minecraft.client.Minecraft.getInstance().options.guiScale().get();
			case "enableVsync":
				net.minecraft.client.Minecraft.getInstance().options.enableVsync().set(value != 0);
				return value != 0 ? 1 : 0;
			case "framerateLimit":
				int limit = Math.max(10, Math.min(260, (int)value));
				net.minecraft.client.Minecraft.getInstance().options.framerateLimit().set(limit);
				return limit;
			case "simulationDistance":
				int simulationDistance = Math.max(2, Math.min(32, (int)value));
				net.minecraft.client.Minecraft.getInstance().options.simulationDistance().set(simulationDistance);
				return simulationDistance;
			case "sectionCopyBudgetMicros":
				net.minecraft.client.renderer.extract.LevelExtractor.web263SectionCopyBudgetMicros = Math.max(0, (int)value);
				return net.minecraft.client.renderer.extract.LevelExtractor.web263SectionCopyBudgetMicros;
			case "occlusionBudgetMicros":
				WebOcclusionProbe.budgetMicros = Math.max(0, (int)value);
				return WebOcclusionProbe.budgetMicros;
			case "renderDistance":
				int renderDistance = Math.max(2, Math.min(32, (int)value));
				net.minecraft.client.Minecraft.getInstance().options.renderDistance().set(renderDistance);
				return renderDistance;
			default:
				return Double.NaN;
		}
	}

	private static String snapshot() {
		long[] heap = MemoryUtil.debugStats();
		long[] images = NativeImage.debugStats();
		long[] top = MemoryUtil.debugTopBlocks(16);

		StringBuilder out = new StringBuilder(512);
		out.append("{\"byteArrayRepr\":\"").append(byteArrayRepr(new byte[4])).append("\",")
			.append("\"charArrayRepr\":\"").append(charArrayRepr(new char[4])).append("\",")
			.append("\"objArrayRepr\":\"").append(objArrayRepr(new Object[4])).append("\",")
			.append("\"fakeHeap\":{")
			.append("\"blocks\":").append(heap[0])
			.append(",\"bytes\":").append(heap[1])
			.append(",\"foreignBlocks\":").append(heap[2])
			.append(",\"foreignBytes\":").append(heap[3])
			.append(",\"typedViewEntries\":").append(heap[4])
			.append(",\"addressSpaceUsed\":").append(heap[5])
			.append("},\"nativeImages\":{")
			.append("\"openedEver\":").append(images[0])
			.append(",\"closedEver\":").append(images[1])
			.append(",\"live\":").append(images[2])
			.append(",\"liveBytes\":").append(images[3])
			.append("},\"topBlocks\":[");
		for (int i = 0; i < top.length; i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append("{\"bytes\":").append(top[i] >>> 1)
				.append(",\"foreign\":").append((top[i] & 1L) != 0L).append('}');
		}
		return out.append("]}").toString();
	}

	@JSFunctor
	private interface NumFn2 extends JSObject {
		String call(double v);
	}

	private static String sections(final double radius) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return "{\"err\":\"no world/player\"}";
		}

		int sectionRadius = Math.max(0, Math.min(8, (int)radius));
		int centerX = SectionPos.blockToSectionCoord(mc.player.getBlockX());
		int centerY = SectionPos.blockToSectionCoord(mc.player.getBlockY());
		int centerZ = SectionPos.blockToSectionCoord(mc.player.getBlockZ());
		int visible = 0;
		int loaded = 0;
		int nonAir = 0;
		int uncompiled = 0;
		int empty = 0;
		int renderable = 0;
		StringBuilder samples = new StringBuilder(512);
		int sampleCount = 0;
		ObjectListIterator<SectionRenderDispatcher.RenderSection> sections = mc.levelRenderer.visibleSections().iterator();

		while (sections.hasNext()) {
			SectionRenderDispatcher.RenderSection renderSection = sections.next();
			long node = renderSection.getSectionNode();
			int x = SectionPos.x(node);
			int y = SectionPos.y(node);
			int z = SectionPos.z(node);
			if (Math.abs(x - centerX) > sectionRadius || Math.abs(y - centerY) > 2 || Math.abs(z - centerZ) > sectionRadius) {
				continue;
			}

			visible++;
			LevelChunk chunk = mc.level.getChunkSource().getChunk(x, z, false);
			if (chunk == null) {
				continue;
			}
			int sectionIndex = mc.level.getSectionIndexFromSectionY(y);
			if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
				continue;
			}
			loaded++;
			LevelChunkSection blockSection = chunk.getSection(sectionIndex);
			boolean hasBlocks = !blockSection.hasOnlyAir();
			if (hasBlocks) {
				nonAir++;
			}

			SectionMesh mesh = renderSection.getSectionMesh();
			String meshState;
			if (mesh == CompiledSectionMesh.UNCOMPILED) {
				uncompiled++;
				meshState = "uncompiled";
			} else if (mesh.hasRenderableLayers()) {
				renderable++;
				meshState = "renderable";
			} else {
				empty++;
				meshState = "empty";
			}

			if (hasBlocks && sampleCount < 16) {
				int dirt = 0;
				int sand = 0;
				int water = 0;
				int other = 0;
				for (int blockY = 0; blockY < 16; blockY++) {
					for (int blockZ = 0; blockZ < 16; blockZ++) {
						for (int blockX = 0; blockX < 16; blockX++) {
							BlockState state = blockSection.getBlockState(blockX, blockY, blockZ);
							if (!state.isAir()) {
								if (state.getBlock() == Blocks.DIRT) {
									dirt++;
								} else if (state.getBlock() == Blocks.SAND || state.getBlock() == Blocks.RED_SAND) {
									sand++;
								} else if (state.getBlock() == Blocks.WATER) {
									water++;
								} else {
									other++;
								}
							}
						}
					}
				}
				if (sampleCount++ > 0) {
					samples.append(',');
				}
				samples.append("{\"p\":\"").append(x).append(',').append(y).append(',').append(z)
					.append("\",\"mesh\":\"").append(meshState).append("\",\"dirt\":").append(dirt)
					.append(",\"sand\":").append(sand).append(",\"water\":").append(water).append(",\"other\":").append(other).append('}');
			}
		}

		return new StringBuilder(256).append("{\"center\":\"").append(centerX).append(',').append(centerY).append(',').append(centerZ)
			.append("\",\"visible\":").append(visible).append(",\"loaded\":").append(loaded).append(",\"nonAir\":").append(nonAir)
			.append(",\"uncompiled\":").append(uncompiled).append(",\"empty\":").append(empty).append(",\"renderable\":")
			.append(renderable).append(",\"samples\":[").append(samples).append("]}").toString();
	}

	@JSBody(params = { "fn" }, script = "window.__minecraftSections = function(r) { return JSON.parse(fn(+r || 2)); };")
	private static native void registerSections(NumFn2 fn);

	@JSBody(params = { "fn" }, script = "window.__minecraftMem = function() { return fn(); };")
	private static native void register(StatsFn fn);

	@JSBody(params = { "fn" }, script = "window.__minecraftFlags = function(n, v) { return fn(n, !!v); };")
	private static native void registerFlags(FlagFn fn);

	@JSBody(params = { "fn" }, script = "window.__minecraftTune = function(n, v) { return fn(n, +v); };")
	private static native void registerTune(NumFn fn);

	@JSBody(params = { "fn" }, script = "window.__minecraftChurn = function() { return JSON.parse(fn()); };")
	private static native void registerChurn(StatsFn fn);

	@JSBody(params = { "fn" }, script = "window.__minecraftFrameReset = function() { fn(); return true; };")
	private static native void registerFrameReset(ResetFn fn);

	private static void markPerfActivity() {
		net.minecraft.client.Minecraft.getInstance().getFramerateLimitTracker().onInputReceived();
	}

	@JSBody(params = { "fn" }, script = "window.__minecraftPerfActivity = function() { fn(); return true; };")
	private static native void registerPerfActivity(ResetFn fn);

	@JSBody(params = { "a" }, script = "return Object.prototype.toString.call(a.data || a);")
	private static native String byteArrayRepr(byte[] a);

	@JSBody(params = { "a" }, script = "return Object.prototype.toString.call(a.data || a);")
	private static native String charArrayRepr(char[] a);

	@JSBody(params = { "a" }, script = "return Object.prototype.toString.call(a.data || a);")
	private static native String objArrayRepr(Object[] a);
}
