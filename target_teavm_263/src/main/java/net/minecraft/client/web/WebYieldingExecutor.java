// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.concurrent.Executor;
import org.teavm.classlib.impl.Trampoline;

public final class WebYieldingExecutor implements Executor {
	private static final WebYieldingExecutor INSTANCE = new WebYieldingExecutor();

	private WebYieldingExecutor() {
	}

	public static Executor forName(String name) {
		switch (name) {
			case "resourceLoad":
			case "structureRings":
			case "loadLevelSummaries":
			case "init_biomes":
				return INSTANCE;
			case "sectionCompile":
				return WebChunkBuildQueue.instance();
			case "occlusionGraph":
				return WebOcclusionProbe.instance();
			default:
				return null;
		}
	}

	@Override
	public void execute(Runnable command) {
		Trampoline.defer(command);
	}
}
