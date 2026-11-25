// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import io.netty.channel.local.LocalAddress;
import java.net.SocketAddress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldOptions;

public final class WebWorkerLifecycle {

	public static final String CHANNEL = WebLifecycleMsg.CHANNEL;

	private static WebLifecycleMsg.Pipe pipe;

	private static WebLifecycleMsg.OpenSpec pending;

	private static WebLifecycleMsg.OpenSpec current;

	private static boolean stopping;

	private static LevelLoadTracker loadTracker;

	private static boolean open;

	private static WebLifecycleMsg.HostState lanState = new WebLifecycleMsg.HostState();

	private static int pauseSent = -1;

	private WebWorkerLifecycle() {
	}

	public static void attach(final WebLifecycleMsg.Pipe out) {
		pipe = out;
	}

	private static void post(final byte[] message) {
		if (pipe == null) {
			System.out.println("[26.3-web] no worker channel attached; dropping a lifecycle message");
			return;
		}
		pipe.post(message);
	}

	public static final Minecraft.WorkerWorldHost HOST = new Minecraft.WorkerWorldHost() {
		@Override
		public boolean hosted() {
			return WebWorkerLifecycle.hosted();
		}

		@Override
		public boolean active() {
			return WebWorkerLifecycle.active();
		}

		@Override
		public boolean canPause() {
			return WebWorkerLifecycle.canPause();
		}

		@Override
		public void setPaused(final boolean paused) {
			WebWorkerLifecycle.setPaused(paused);
		}

		@Override
		public void openExisting(final String levelId) {
			requestOpen(loadSpec(levelId));
		}

		@Override
		public void createNew(final String levelId, final LevelSettings settings, final WorldOptions options,
				final String preset) {
			requestOpen(createSpec(levelId, settings, options, preset));
		}

		@Override
		public void requestStop(final Screen afterStop) {
			WebWorkerLifecycle.requestStop();
			WebWorkerLifecycle.afterStop = afterStop;
		}
	};

	public static boolean hosted() {
		return true;
	}

	public static void createNewWorld(final String levelId, final LevelSettings settings, final WorldOptions options,
			final String preset) {
		requestOpen(createSpec(levelId, settings, options, preset));
	}

	private static WebLifecycleMsg.OpenSpec forPlayer(final String levelId) {
		WebLifecycleMsg.OpenSpec spec = new WebLifecycleMsg.OpenSpec();
		spec.levelId = levelId;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null) {
			spec.playerName = minecraft.getUser().getName();
			spec.playerId = minecraft.getUser().getProfileId();
			spec.viewDistance = minecraft.options.renderDistance().get();
			spec.simulationDistance = minecraft.options.simulationDistance().get();
		}
		return spec;
	}

	public static WebLifecycleMsg.OpenSpec loadSpec(final String levelId) {
		WebLifecycleMsg.OpenSpec spec = forPlayer(levelId);
		spec.create = false;
		return spec;
	}

	public static WebLifecycleMsg.OpenSpec createSpec(final String levelId, final LevelSettings settings, final WorldOptions options,
			final String preset) {
		WebLifecycleMsg.OpenSpec spec = forPlayer(levelId);
		spec.create = true;
		spec.levelName = settings.levelName();
		spec.gameType = settings.gameType().getId();
		spec.difficulty = settings.difficultySettings().difficulty().getId();
		spec.hardcore = settings.difficultySettings().hardcore();
		spec.difficultyLocked = settings.difficultySettings().locked();
		spec.allowCommands = settings.allowCommands();
		spec.seed = options.seed();
		spec.generateStructures = options.generateStructures();
		spec.bonusChest = options.generateBonusChest();
		spec.preset = preset == null ? "" : preset;
		net.minecraft.world.level.WorldDataConfiguration dataConfiguration = settings.dataConfiguration();
		spec.enabledPacks.addAll(dataConfiguration.dataPacks().getEnabled());
		spec.disabledPacks.addAll(dataConfiguration.dataPacks().getDisabled());
		for (net.minecraft.resources.Identifier flag
				: net.minecraft.world.flag.FeatureFlags.REGISTRY.toNames(dataConfiguration.enabledFeatures())) {
			spec.features.add(flag.toString());
		}
		collectDataPacks(spec);
		return spec;
	}

	private static void collectDataPacks(final WebLifecycleMsg.OpenSpec spec) {
		java.nio.file.Path dir = net.minecraft.client.gui.screens.worldselection.CreateWorldScreen.webDataPackDir;
		if (dir != null) {
			String key = dir.toAbsolutePath().normalize().toString();
			org.teavm.runtime.fs.VirtualFile directory = org.teavm.runtime.fs.VirtualFileSystemProvider.getInstance().getFile(key);
			String[] names = directory.isDirectory() ? directory.listFiles() : null;
			if (names != null) {
				long bytes = 0;
				for (String name : names) {
					if (!WebLifecycleMsg.safePackName(name)) {
						continue;
					}
					byte[] data = WebSaves.readFile(key + "/" + name);
					if (data != null) {
						spec.packNames.add(name);
						spec.packData.add(data);
						bytes += data.length;
					}
				}
				if (!spec.packNames.isEmpty()) {
					System.out.println("[26.3-web] sending " + spec.packNames.size() + " data pack(s) to the worker (" + bytes + " bytes): " + spec.packNames);
				}
			}
		}
		net.minecraft.client.web.mods.WebMods.appendDataPacksTo(spec);
	}

	public static void requestOpen(final WebLifecycleMsg.OpenSpec spec) {
		if (!WebWorkerNet.pageStarted()) {
			System.out.println("[26.3-web] cannot open a singleplayer world: server worker is not ready");
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null) {
			minecraft.disconnectWithProgressScreen();
			loadTracker = new LevelLoadTracker(spec.create ? 500L : 0L);
			minecraft.setScreenAndShow(new LevelLoadingScreen(loadTracker, LevelLoadingScreen.Reason.OTHER));
		}
		pending = spec;
		System.out.println("[26.3-web] ?worker: asking the worker to " + (spec.create ? "create " : "open ") + spec.levelId);
		post(WebLifecycleMsg.encodeOpen(spec));
	}

	public static void updateDistances(final int viewDistance, final int simulationDistance) {
		if (current == null || stopping
			|| (current.viewDistance == viewDistance && current.simulationDistance == simulationDistance)) {
			return;
		}
		post(WebLifecycleMsg.encodeDistances(viewDistance, simulationDistance));
		current.viewDistance = viewDistance;
		current.simulationDistance = simulationDistance;
	}

	public static void requestStop() {
		if (stopping) {
			return;
		}
		stopping = true;
		open = false;
		pauseSent = -1;
		loadTracker = null;
		System.out.println("[26.3-web] ?worker: asking the worker to save and stop");
		post(WebLifecycleMsg.encodeSimple(WebLifecycleMsg.STOP));
	}

	public static boolean active() {
		return pending != null || open || stopping;
	}

	public static boolean canPause() {
		return open && !stopping && lanState.state != WebLifecycleMsg.HOST_BOUND;
	}

	public static void setPaused(final boolean paused) {
		if (!open || stopping) {
			pauseSent = -1;
			return;
		}
		int next = paused ? 1 : 0;
		if (pauseSent != next) {
			post(WebLifecycleMsg.encodePause(paused));
			pauseSent = next;
		}
	}

	public static String currentWorldName() {
		return current == null || current.levelName == null ? "" : current.levelName;
	}

	public static String currentWorldType() {
		if (current == null || current.preset == null) {
			return "remote";
		}
		return current.preset.endsWith(":flat") ? "superflat" : "normal";
	}

	public static int lanGameType = -1;
	public static int lanCommands = -1;
	public static String lanPassword = "";
	public static boolean lanPrivate;
	public static int lanMaxPlayers = 8;

	public static void requestLanSettings() {
		if (open) {
			post(WebLifecycleMsg.encodeHostSettings(lanGameType, lanCommands, LanGate.hash(lanPassword), lanMaxPlayers));
		}
	}

	public static void requestLanOpen(final String socketUrl) {
		if (!open) {
			setLanError("no local world is open");
			return;
		}
		WebLifecycleMsg.HostSpec spec = new WebLifecycleMsg.HostSpec();
		spec.socketUrl = socketUrl == null ? "" : socketUrl;
		spec.port = 0;
		spec.announce = !lanPrivate;
		spec.gameType = lanGameType;
		spec.commands = lanCommands;
		spec.passwordHash = LanGate.hash(lanPassword);
		spec.maxPlayers = lanMaxPlayers;
		spec.icon = WebProfile.faceIcon();
		lanState = hostState(WebLifecycleMsg.HOST_OPENING, "", -1, "");
		post(WebLifecycleMsg.encodeHostOpen(spec));
	}

	public static void requestLanClose() {
		post(WebLifecycleMsg.encodeSimple(WebLifecycleMsg.HOST_CLOSE));
	}

	public static byte lanStatus() {
		return lanState.state;
	}

	public static String lanAddress() {
		return lanState.address;
	}

	public static int lanPort() {
		return lanState.port;
	}

	public static String lanMessage() {
		return lanState.message;
	}

	private static WebLifecycleMsg.HostState hostState(final byte state, final String address,
			final int port, final String message) {
		WebLifecycleMsg.HostState value = new WebLifecycleMsg.HostState();
		value.state = state;
		value.address = address == null ? "" : address;
		value.port = port;
		value.message = message == null ? "" : message;
		return value;
	}

	private static void setLanError(final String message) {
		lanState = hostState(WebLifecycleMsg.HOST_ERROR, "", -1, message);
	}

	private static void onReady() {
		WebLifecycleMsg.OpenSpec spec = pending;
		pending = null;
		open = spec != null;
		current = spec;
		pauseSent = -1;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || spec == null) {
			return;
		}
		System.out.println("[26.3-web] ?worker: worker reports ready; connecting");
		SocketAddress address = new LocalAddress("worker:server");
		minecraft.connectToWorkerServer(address, spec.create);
	}

	private static Screen afterStop;

	private static void onStopped() {
		stopping = false;
		current = null;
		loadTracker = null;
		lanState = new WebLifecycleMsg.HostState();
		pauseSent = -1;
		System.out.println("[26.3-web] ?worker: worker saved and stopped; worker kept alive for the world list");
		Screen next = afterStop;
		afterStop = null;
		Minecraft minecraft = Minecraft.getInstance();
		if (next != null && minecraft != null && minecraft.gui.screen() instanceof GenericMessageScreen) {
			minecraft.setScreenAndShow(next);
		}
	}

	private static void onFatal(final String message) {
		afterStop = null;
		pending = null;
		current = null;
		stopping = false;
		open = false;
		loadTracker = null;
		lanState = new WebLifecycleMsg.HostState();
		pauseSent = -1;
		org.slf4j.LoggerFactory.getLogger("26.3-web").error("worker server failed: " + message);
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null) {
			minecraft.gui.setScreen(new AlertScreen(
				() -> minecraft.gui.setScreen(new TitleScreen()),
				Component.translatable("selectWorld.futureworld.error.title"),
				Component.literal(message)
			));
		}
	}

	private static void onProgress(final WebLifecycleMsg.ProgressSnapshot snapshot) {
		LevelLoadTracker tracker = loadTracker;
		if (tracker == null || snapshot == null) {
			return;
		}
		ChunkLoadStatusView statusView = tracker.statusView();
		RemoteChunkStatusView remote;
		if (statusView instanceof RemoteChunkStatusView existing && existing.radius() == snapshot.radius) {
			remote = existing;
		} else {
			remote = new RemoteChunkStatusView(snapshot.radius);
			tracker.setServerChunkStatusView(remote);
		}
		remote.update(snapshot.statuses);
		LevelLoadListener.Stage[] stages = LevelLoadListener.Stage.values();
		LevelLoadListener.Stage stage = stages[snapshot.stage];
		switch (snapshot.event) {
			case WebLifecycleMsg.PROGRESS_START:
				tracker.start(stage, snapshot.totalChunks);
				break;
			case WebLifecycleMsg.PROGRESS_UPDATE:
				tracker.update(stage, snapshot.currentChunks, snapshot.totalChunks);
				break;
			case WebLifecycleMsg.PROGRESS_FINISH:
				tracker.finish(stage);
				break;
			default:
				break;
		}
	}

	private static final class RemoteChunkStatusView implements ChunkLoadStatusView {
		private static final java.util.List<ChunkStatus> STATUSES = ChunkStatus.getStatusList();
		private final int radius;
		private byte[] statuses;

		RemoteChunkStatusView(final int radius) {
			this.radius = radius;
			int diameter = radius * 2 + 1;
			this.statuses = new byte[diameter * diameter];
			java.util.Arrays.fill(this.statuses, (byte)-1);
		}

		void update(final byte[] statuses) {
			this.statuses = statuses.clone();
		}

		@Override
		public void moveTo(final ResourceKey<Level> dimension, final ChunkPos centerChunk) {
		}

		@Override
		public ChunkStatus get(final int x, final int z) {
			int diameter = this.radius * 2 + 1;
			int index = this.statuses[z * diameter + x];
			return index < 0 ? null : STATUSES.get(index);
		}

		@Override
		public int radius() {
			return this.radius;
		}
	}

	public static void receive(final byte[] message) {
		switch (WebLifecycleMsg.opOf(message)) {
			case WebLifecycleMsg.READY:
				onReady();
				break;
			case WebLifecycleMsg.STOPPED:
				onStopped();
				break;
			case WebLifecycleMsg.FATAL:
				onFatal(WebLifecycleMsg.decodeFatal(message));
				break;
			case WebLifecycleMsg.PROGRESS:
				onProgress(WebLifecycleMsg.decodeProgress(message));
				break;
			case WebLifecycleMsg.HOST_STATE: {
				WebLifecycleMsg.HostState state = WebLifecycleMsg.decodeHostState(message);
				if (state != null) {
					lanState = state;
				}
				break;
			}
			default:
				break;
		}
	}
}
