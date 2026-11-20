// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Dynamic;
import java.io.File;
import java.net.Proxy;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SystemReport;
import net.minecraft.commands.Commands;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

public final class WebWorkerServer extends MinecraftServer {

	private static WebWorkerServer current;

	private final LocalSampleLogger sampleLogger = new LocalSampleLogger(4);
	private int viewDistance;
	private int simulationDistance;
	private int previousSimulationDistance;
	private boolean published;
	private boolean pauseRequested;
	private boolean paused;
	private GameType gameTypeForOtherPlayers;
	private Boolean commandsAllowedForOtherPlayers;

	void applyOtherPlayerSettings(final int gameType, final int commands) {
		GameType type = gameType < 0 ? null : GameType.byId(gameType);
		if (type != null) {
			this.gameTypeForOtherPlayers = type;
			for (net.minecraft.server.level.ServerPlayer player : this.getPlayerList().getPlayers()) {
				if (!this.isSingleplayerOwner(player.nameAndId())) {
					player.setGameMode(type);
				}
			}
		}
		if (commands >= 0) {
			this.commandsAllowedForOtherPlayers = commands != 0;
			for (net.minecraft.server.level.ServerPlayer player : this.getPlayerList().getPlayers()) {
				this.getPlayerList().sendPlayerPermissionLevel(player);
			}
		}
	}

	@Override
	public GameType getForcedGameType() {
		return this.published && !this.isHardcore() && this.gameTypeForOtherPlayers != null ? this.gameTypeForOtherPlayers : null;
	}
	private static JsWispHost lanHost;

	private WebWorkerServer(
		final Thread serverThread,
		final LevelStorageSource.LevelStorageAccess storage,
		final PackRepository packRepository,
		final WorldStem worldStem,
		final Services services,
		final LevelLoadListener loadListener,
		final GameProfile host,
		final int viewDistance,
		final int simulationDistance
	) {
		super(
			serverThread,
			storage,
			packRepository,
			worldStem,
			Optional.empty(),
			Proxy.NO_PROXY,
			DataFixers.getDataFixer(),
			services,
			loadListener,
			false,
			new NotificationManager()
		);
		this.viewDistance = viewDistance;
		this.simulationDistance = simulationDistance;
		this.setSingleplayerProfile(host);
		this.setDemo(false);
		this.setPlayerList(new WorkerPlayerList(this, this.registries(), this.playerDataStorage));
	}

	public static MinecraftServer current() {
		return current;
	}

	public static void open(final WebLifecycleMsg.OpenSpec spec) throws Exception {
		if (current != null) {
			throw new IllegalStateException("a world is already open in this worker");
		}
		LevelStorageSource levelSource = LevelStorageSource.createDefault(Path.of(WebSaves.SAVES));
		LevelStorageSource.LevelStorageAccess access = levelSource.createAccess(spec.levelId);
		if (spec.create && !spec.packNames.isEmpty()) {
			try {
				String dir = access.getLevelPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
					.toAbsolutePath().normalize().toString();
				WebSaves.mkdirs(dir);
				for (int i = 0; i < spec.packNames.size(); i++) {
					WebSaves.writeFile(dir + "/" + spec.packNames.get(i), spec.packData.get(i));
				}
				System.out.println("[26.3-server] stored " + spec.packNames.size() + " data pack(s) in " + dir);
			} catch (Exception e) {
				access.safeClose();
				throw e;
			}
		}
		PackRepository packRepository = ServerPacksSource.createPackRepository(access);
		WorldStem stem;
		try {
			stem = spec.create ? createStem(spec, packRepository) : openStem(access, packRepository);
		} catch (Exception e) {
			access.safeClose();
			throw e;
		}
		if (spec.create) {
			access.saveDataTag(stem.worldDataAndGenSettings().data());
		}
		GameProfile host = new GameProfile(spec.playerId, spec.playerName);
		ServerProgressRelay listener = new ServerProgressRelay(LoggingLevelLoadListener.forSingleplayer());
		applyLanFlags(false);
		WebServerPump.install();
		try {
			current = MinecraftServer.spin(thread -> {
				WebWorkerServer server = new WebWorkerServer(thread, access, packRepository, stem, offlineServices(), listener, host,
					spec.viewDistance, spec.simulationDistance);
				listener.bind(server);
				current = server;
				return server;
			});
		} catch (RuntimeException | Error e) {
			current = null;
			throw e;
		}
		WebGenLane.start();
	}

	public static void stop() {
		MinecraftServer server = current;
		closeLan();
		current = null;
		WebGenLane.stop();
		if (server != null) {
			server.halt(false);
		}
	}

	static void drainLan() {
		JsWispHost host = lanHost;
		if (host != null) {
			host.drain();
		}
	}

	private static final int LAN_COMPRESSION_ANNOUNCED = 1 << 20;
	private static boolean lanCompress = true;
	private static int lanGenSharePercent = 25;

	static int lanGenShare() {
		return net.minecraft.server.Web263Lan.remoteGuests > 0 && lanGenSharePercent < 100 ? lanGenSharePercent : 0;
	}

	private static void applyLanFlags(final boolean hosting) {
		boolean weakHost = WebWorkerTransport.pageCores() <= 2;
		lanCompress = WebWorkerTransport.queryInt("lancompress", 1) != 0;
		JsWispHost.deflateBytes = lanCompress ? Math.max(256, WebWorkerTransport.queryInt("landeflate", 2048)) : 0;
		io.netty.channel.relay.RelayHostChannel.largePacketBytes = Math.max(256, JsWispHost.deflateBytes);
		WispHostSession.batchFrames = WebWorkerTransport.queryInt("lanbatch", 1) != 0;
		net.minecraft.server.Web263Lan.holdMode = Math.max(0, Math.min(2, WebWorkerTransport.queryInt("lanhold", 2)));
		net.minecraft.server.Web263Lan.loadedMode = Math.max(0, Math.min(2, WebWorkerTransport.queryInt("lanloaded", 1)));
		net.minecraft.server.Web263Lan.chunkWindowMax = Math.max(2, Math.min(256, WebWorkerTransport.queryInt("lanwindow", 24)));
		net.minecraft.server.Web263Lan.chunkWindowStart = Math.min(8, net.minecraft.server.Web263Lan.chunkWindowMax);
		net.minecraft.server.Web263Lan.chunkBudgetPerTick = Math.max(1, Math.min(64, WebWorkerTransport.queryInt("lanchunks", weakHost ? 2 : 8)));
		net.minecraft.server.Web263Lan.ticketSlots = Math.max(1, Math.min(4, WebWorkerTransport.queryInt("lanslots", weakHost ? 1 : 4)));
		net.minecraft.server.Web263Lan.ticketTurnNanos = Math.max(1, Math.min(120, WebWorkerTransport.queryInt("lanturn", 15))) * 1_000_000_000L;
		lanGenSharePercent = Math.max(5, Math.min(100, WebWorkerTransport.queryInt("langenshare", 25)));
		playerSaveSeconds = Math.max(0, Math.min(300, WebWorkerTransport.queryInt("playersave", 10)));
		int raise = WebWorkerTransport.queryInt("srvraise", 0);
		int lower = WebWorkerTransport.queryInt("srvlower", 0);
		if (raise > 0) {
			net.minecraft.server.Web263ServerLoad.RAISE_MS = raise;
		}
		if (lower > 0) {
			net.minecraft.server.Web263ServerLoad.LOWER_MS = lower;
		}
		if (!hosting) {
			return;
		}
		System.out.println("[26.3-lan] hosting: compress=" + lanCompress + " (over " + JsWispHost.deflateBytes + " B) batch=" + WispHostSession.batchFrames
			+ " hold=" + net.minecraft.server.Web263Lan.holdMode + " loaded=" + net.minecraft.server.Web263Lan.loadedMode
			+ " chunkWindow=" + net.minecraft.server.Web263Lan.chunkWindowStart + ".." + net.minecraft.server.Web263Lan.chunkWindowMax
			+ " chunksPerTick=" + net.minecraft.server.Web263Lan.chunkBudgetPerTick + " genShare=" + lanGenSharePercent + " ticketSlots=" + net.minecraft.server.Web263Lan.ticketSlots
			+ " cores=" + WebWorkerTransport.pageCores());
	}

	private static void openLan(final WebLifecycleMsg.HostSpec spec) {
		WebWorkerServer server = current;
		if (server == null) {
			replyHostState(WebLifecycleMsg.HOST_ERROR, "", -1, "no integrated server is running");
			return;
		}
		closeLan();
		applyLanFlags(true);
		server.applyOtherPlayerSettings(spec.gameType, spec.commands);
		server.lanMaxPlayers = spec.maxPlayers;
		server.lanIcon = spec.icon.length > 0 ? new net.minecraft.network.protocol.status.ServerStatus.Favicon(spec.icon) : null;
		String motd = spec.motd.isEmpty() ? server.getMotd() : spec.motd;
		if (!spec.passwordHash.isEmpty()) {
			motd = motd + LanServers.LOCKED_SUFFIX;
		}
		lanHost = new JsWispHost(spec.socketUrl,
			channel -> channel.pipeline().addLast(server.getConnection().newRemoteChildHandler()),
			spec.port, motd, spec.announce, spec.passwordHash,
			(state, address, port, message) -> {
				if (current != server) {
					return;
				}
				server.published = state == WebLifecycleMsg.HOST_BOUND;
				server.setPort(server.published ? port : -1);
				replyHostState(state, address, port, message);
			});
	}

	private static void closeLan() {
		JsWispHost host = lanHost;
		lanHost = null;
		if (host != null) {
			host.close();
		} else if (current != null && current.published) {
			current.published = false;
			current.setPort(-1);
			replyHostState(WebLifecycleMsg.HOST_IDLE, "", -1, "");
		}
	}

	private static void replyHostState(final byte state, final String address, final int port, final String message) {
		WebLifecycleMsg.HostState value = new WebLifecycleMsg.HostState();
		value.state = state;
		value.address = address == null ? "" : address;
		value.port = port;
		value.message = message == null ? "" : message;
		reply(WebLifecycleMsg.encodeHostState(value));
	}

	private static WebLifecycleMsg.Pipe pipe;

	public static void attach(final WebLifecycleMsg.Pipe out) {
		pipe = out;
	}

	private static void reply(final byte[] message) {
		if (pipe == null) {
			System.out.println("[26.3-server] no page channel attached; dropping a lifecycle reply");
			return;
		}
		pipe.post(message);
	}

	private static final class ServerProgressRelay implements LevelLoadListener {
		private static final int RADIUS = 11;
		private final LevelLoadListener delegate;
		private ChunkLoadStatusView statusView;
		private Stage stage = Stage.START_SERVER;
		private int currentChunks;
		private int totalChunks;

		ServerProgressRelay(final LevelLoadListener delegate) {
			this.delegate = delegate;
		}

		void bind(final MinecraftServer server) {
			this.statusView = server.createChunkLoadStatusView(RADIUS);
		}

		@Override
		public void start(final Stage stage, final int totalChunks) {
			this.delegate.start(stage, totalChunks);
			this.stage = stage;
			this.currentChunks = 0;
			this.totalChunks = totalChunks;
			send(WebLifecycleMsg.PROGRESS_START);
		}

		@Override
		public void update(final Stage stage, final int currentChunks, final int totalChunks) {
			this.delegate.update(stage, currentChunks, totalChunks);
			this.stage = stage;
			this.currentChunks = currentChunks;
			this.totalChunks = totalChunks;
			send(WebLifecycleMsg.PROGRESS_UPDATE);
		}

		@Override
		public void finish(final Stage stage) {
			this.delegate.finish(stage);
			this.stage = stage;
			send(WebLifecycleMsg.PROGRESS_FINISH);
		}

		@Override
		public void updateFocus(final ResourceKey<net.minecraft.world.level.Level> dimension, final ChunkPos chunkPos) {
			this.delegate.updateFocus(dimension, chunkPos);
			if (this.statusView != null) {
				this.statusView.moveTo(dimension, chunkPos);
			}
			send(WebLifecycleMsg.PROGRESS_UPDATE);
		}

		private void send(final byte event) {
			if (this.statusView == null) {
				return;
			}
			WebLifecycleMsg.ProgressSnapshot snapshot = new WebLifecycleMsg.ProgressSnapshot();
			snapshot.event = event;
			snapshot.stage = (byte)this.stage.ordinal();
			snapshot.currentChunks = this.currentChunks;
			snapshot.totalChunks = this.totalChunks;
			snapshot.radius = RADIUS;
			int diameter = RADIUS * 2 + 1;
			snapshot.statuses = new byte[diameter * diameter];
			for (int z = 0; z < diameter; z++) {
				for (int x = 0; x < diameter; x++) {
					ChunkStatus status = this.statusView.get(x, z);
					snapshot.statuses[z * diameter + x] = status == null ? -1 : (byte)status.getIndex();
				}
			}
			reply(WebLifecycleMsg.encodeProgress(snapshot));
		}
	}

	public static void serve(final byte[] message) {
		switch (WebLifecycleMsg.opOf(message)) {
			case WebLifecycleMsg.OPEN: {
				WebLifecycleMsg.OpenSpec spec = WebLifecycleMsg.decodeOpen(message);
				if (spec == null) {
					reply(WebLifecycleMsg.encodeFatal("malformed world open request"));
					return;
				}
				try {
					open(spec);
					MinecraftServer server = current;
					if (server == null) {
						reply(WebLifecycleMsg.encodeFatal("the server did not start"));
						return;
					}
					server.getConnection().startMemoryChannel();
					reply(WebLifecycleMsg.encodeSimple(WebLifecycleMsg.READY));
				} catch (Throwable e) {
					org.slf4j.LoggerFactory.getLogger("26.3-server").error("world open failed", e);
					reply(WebLifecycleMsg.encodeFatal(String.valueOf(e)));
				}
				break;
			}
			case WebLifecycleMsg.STOP:
				try {
					stop();
				} catch (Throwable e) {
					org.slf4j.LoggerFactory.getLogger("26.3-server").error("shutdown failed", e);
				}
				WebSaves.flush();
				WebServerMain.pushWorldList();
				reply(WebLifecycleMsg.encodeSimple(WebLifecycleMsg.STOPPED));
				break;
			case WebLifecycleMsg.HOST_OPEN: {
				WebLifecycleMsg.HostSpec spec = WebLifecycleMsg.decodeHostOpen(message);
				if (spec == null) {
					replyHostState(WebLifecycleMsg.HOST_ERROR, "", -1, "malformed relay host request");
					return;
				}
				try {
					openLan(spec);
				} catch (Throwable e) {
					org.slf4j.LoggerFactory.getLogger("26.3-server").error("relay hosting failed", e);
					replyHostState(WebLifecycleMsg.HOST_ERROR, "", -1, String.valueOf(e));
				}
				break;
			}
			case WebLifecycleMsg.HOST_CLOSE:
				closeLan();
				replyHostState(WebLifecycleMsg.HOST_IDLE, "", -1, "");
				break;
			case WebLifecycleMsg.HOST_SETTINGS: {
				WebLifecycleMsg.HostSpec settings = WebLifecycleMsg.decodeHostSettings(message);
				if (settings != null && current != null) {
					current.applyOtherPlayerSettings(settings.gameType, settings.commands);
					current.lanMaxPlayers = settings.maxPlayers;
					if (lanHost != null) {
						lanHost.setPasswordHash(settings.passwordHash);
					}
				}
				break;
			}
			case WebLifecycleMsg.DISTANCES: {
				int[] distances = WebLifecycleMsg.decodeDistances(message);
				if (distances != null && current != null) {
					current.viewDistance = distances[0];
					current.simulationDistance = distances[1];
				}
				break;
			}
			case WebLifecycleMsg.PAUSE: {
				Boolean requested = WebLifecycleMsg.decodePause(message);
				if (requested != null && current != null) {
					current.pauseRequested = requested.booleanValue();
				}
				break;
			}
			default:
				break;
		}
	}

	private static WorldStem createStem(final WebLifecycleMsg.OpenSpec spec, final PackRepository packRepository)
			throws Exception {
		WorldDataConfiguration dataConfiguration = dataConfigurationOf(spec);
		LevelSettings settings = new LevelSettings(
			spec.levelName,
			GameType.byId(spec.gameType),
			new LevelSettings.DifficultySettings(Difficulty.byId(spec.difficulty), spec.hardcore, spec.difficultyLocked),
			spec.allowCommands,
			dataConfiguration
		);
		long seed = spec.seed;
		String fixed = WebWorkerTransport.pageFixedSeed();
		if (!fixed.isEmpty()) {
			seed = Long.parseLong(fixed);
			System.out.println("[26.3-server] using fixed seed " + seed);
		}
		WorldOptions options = new WorldOptions(seed, spec.generateStructures, spec.bonusChest);
		return load(
			new WorldLoader.PackConfig(packRepository, dataConfiguration, false, false),
			context -> {
				WorldDimensions dimensions = dimensionsFor(spec.preset, context.datapackWorldRegistries());
				WorldDimensions.Complete complete = dimensions.bake(context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM));
				return new WorldLoader.DataLoadOutput<>(
					new LevelDataAndDimensions.WorldDataAndGenSettings(
						new PrimaryLevelData(settings.withDataConfiguration(context.dataConfiguration()),
							complete.specialWorldProperty(), complete.lifecycle()),
						new WorldGenSettings(options, dimensions)
					),
					complete.dimensionsRegistryAccess()
				);
			}
		);
	}

	private static WorldDataConfiguration dataConfigurationOf(final WebLifecycleMsg.OpenSpec spec) {
		if (spec.enabledPacks.isEmpty() && spec.features.isEmpty()) {
			return WorldDataConfiguration.DEFAULT;
		}
		List<Identifier> flags = new java.util.ArrayList<>();
		for (String name : spec.features) {
			Identifier id = Identifier.tryParse(name);
			if (id != null) {
				flags.add(id);
			}
		}
		net.minecraft.world.flag.FeatureFlagSet features = flags.isEmpty()
			? net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS
			: net.minecraft.world.flag.FeatureFlags.REGISTRY.fromNames(flags);
		net.minecraft.world.level.DataPackConfig packs = spec.enabledPacks.isEmpty()
			? net.minecraft.world.level.DataPackConfig.DEFAULT
			: new net.minecraft.world.level.DataPackConfig(List.copyOf(spec.enabledPacks), List.copyOf(spec.disabledPacks));
		System.out.println("[26.3-server] creating with data packs " + packs.getEnabled() + ", features " + spec.features);
		return new WorldDataConfiguration(packs, features);
	}

	private static WorldStem openStem(final LevelStorageSource.LevelStorageAccess access, final PackRepository packRepository)
			throws Exception {
		Dynamic<?> levelDataTag = access.getUnfixedDataTag(false);
		return load(
			LevelStorageSource.getPackConfig(levelDataTag, packRepository, false),
			context -> {
				Registry<LevelStem> datapackDimensions = context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM);
				LevelDataAndDimensions data = LevelStorageSource.getLevelDataAndDimensions(
					access, levelDataTag, context.dataConfiguration(), datapackDimensions, context.datapackWorldRegistries()
				);
				return new WorldLoader.DataLoadOutput<>(data.worldDataAndGenSettings(), data.dimensions().dimensionsRegistryAccess());
			}
		);
	}

	private static WorldStem load(
		final WorldLoader.PackConfig packConfig,
		final WorldLoader.WorldDataSupplier<LevelDataAndDimensions.WorldDataAndGenSettings> supplier
	) throws Exception {
		WorldLoader.InitConfig config = new WorldLoader.InitConfig(
			packConfig, Commands.CommandSelection.INTEGRATED, LevelBasedPermissionSet.GAMEMASTER);
		CompletableFuture<WorldStem> future = WorldLoader.load(
			config, supplier, WorldStem::new, net.minecraft.util.Util.backgroundExecutor(), Runnable::run);
		return future.join();
	}

	private static WorldDimensions dimensionsFor(final String preset, final HolderLookup.Provider registries) {
		Identifier id = Identifier.tryParse(preset);
		if (id != null) {
			ResourceKey<WorldPreset> key = ResourceKey.create(Registries.WORLD_PRESET, id);
			Optional<WorldDimensions> dimensions = registries.lookup(Registries.WORLD_PRESET)
				.flatMap(lookup -> lookup.get(key))
				.map(holder -> holder.value().createWorldDimensions());
			if (dimensions.isPresent()) {
				return dimensions.get();
			}
			System.out.println("[26.3-server] unknown world preset " + preset + "; falling back to normal");
		}
		return net.minecraft.world.level.levelgen.presets.WorldPresets.createNormalWorldDimensions(registries);
	}

	private static Services offlineServices() {
		return Services.create(com.mojang.authlib.services.MinecraftServicesDiscoveryService.createOffline(Proxy.NO_PROXY), new File("/"));
	}

	@Override
	protected boolean initServer() {
		this.setUsesAuthentication(false);
		this.loadLevel();
		GameProfile host = this.getSingleplayerProfile();
		String levelName = this.getWorldData().getLevelName();
		this.setMotd(host != null ? host.name() + " - " + levelName : levelName);
		this.saveEverything(false, true, true);
		return true;
	}

	@Override
	protected void tickServer(final java.util.function.BooleanSupplier haveTime) {
		boolean wasPaused = this.paused;
		this.paused = shouldPause(this.pauseRequested, this.published);
		if (!wasPaused && this.paused) {
			net.minecraft.util.profiling.ProfilerFiller profiler = net.minecraft.util.profiling.Profiler.get();
			profiler.push("autoSave");
			this.saveEverything(false, false, false);
			profiler.pop();
		}
		if (this.paused) {
			this.tickPaused();
		} else {
			if (wasPaused) {
				this.forceGameTimeSynchronization();
			}
			super.tickServer(haveTime);
			this.tickLan();
			this.savePlayersInTurn();
			if (this.viewDistance != this.getPlayerList().getViewDistance()) {
				this.getPlayerList().setViewDistance(this.viewDistance);
			}
			if (this.simulationDistance != this.previousSimulationDistance) {
				this.getPlayerList().setSimulationDistance(this.simulationDistance);
				this.previousSimulationDistance = this.simulationDistance;
			}
		}
	}

	static boolean shouldPause(final boolean requested, final boolean published) {
		return requested && !published;
	}

	private static int playerSaveSeconds = 10;
	private int playerSaveTicks;
	private int playerSaveNext;

	private void savePlayersInTurn() {
		if (playerSaveSeconds <= 0) {
			return;
		}
		java.util.List<net.minecraft.server.level.ServerPlayer> players = this.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			this.playerSaveTicks = 0;
			return;
		}
		if (++this.playerSaveTicks < Math.max(20, playerSaveSeconds * 20 / players.size())) {
			return;
		}
		this.playerSaveTicks = 0;
		if (this.playerSaveNext >= players.size()) {
			this.playerSaveNext = 0;
		}
		this.playerDataStorage.save(players.get(this.playerSaveNext++));
	}

	private static final int LAN_REPORT_TICKS = 200;
	private int lanReportTick;
	private long lanReportNanos;
	private double[] lanReportCounters = new double[7];
	private int lanReportPackets;
	private int lanReportHolds;
	private int lanReportChunks;
	private int lanReportAcks;
	private int lanReportAckMillis;
	private int lanReportWaits;
	private long lanReportWaitNanos;
	private int waitReportTick;
	private int waitReportWaits;
	private long waitReportNanos;
	private int waitReportSkips;
	private int lanReportSkips;
	private int lanReportSlices;
	private int lanReportTurnsDone;
	private int lanReportTurnsCut;
	private static int chunkWaitReportFlag = -1;

	private static boolean chunkWaitReport() {
		if (chunkWaitReportFlag < 0) {
			chunkWaitReportFlag = WebWorkerTransport.queryInt("chunkwaits", 0) != 0 ? 1 : 0;
		}
		return chunkWaitReportFlag == 1;
	}

	private void tickLan() {
		int guests = 0;
		java.util.List<net.minecraft.server.level.ServerPlayer> players = this.getPlayerList().getPlayers();
		for (int i = 0; i < players.size(); i++) {
			if (!this.isSingleplayerOwner(players.get(i).nameAndId())) {
				guests++;
			}
		}
		net.minecraft.server.Web263Lan.remoteGuests = guests;
		if (chunkWaitReport() && ++this.waitReportTick >= LAN_REPORT_TICKS) {
			this.waitReportTick = 0;
			System.out.println("[26.3-wait] chunkWaits=" + (net.minecraft.server.Web263Lan.chunkWaits - this.waitReportWaits)
				+ " ms=" + (net.minecraft.server.Web263Lan.chunkWaitNanos - this.waitReportNanos) / 1000000L
				+ " notWaited=" + (net.minecraft.server.Web263Lan.chunkSkips - this.waitReportSkips) + " in " + LAN_REPORT_TICKS + " ticks");
			this.waitReportSkips = net.minecraft.server.Web263Lan.chunkSkips;
			this.waitReportWaits = net.minecraft.server.Web263Lan.chunkWaits;
			this.waitReportNanos = net.minecraft.server.Web263Lan.chunkWaitNanos;
		}
		JsWispHost host = lanHost;
		if (host == null || guests == 0 && this.lanReportNanos == 0L) {
			return;
		}
		if (++this.lanReportTick < LAN_REPORT_TICKS && this.lanReportNanos != 0L) {
			return;
		}
		this.lanReportTick = 0;
		long now = System.nanoTime();
		double[] counters = new double[7];
		String[] parts = WebRelayRpc.lanCounters().split(",");
		for (int i = 0; i < counters.length && i < parts.length; i++) {
			try {
				counters[i] = Double.parseDouble(parts[i]);
			} catch (NumberFormatException e) {
				counters[i] = 0.0;
			}
		}
		int packets = host.session().packetsOut();
		if (this.lanReportNanos != 0L) {
			double seconds = Math.max(0.001, (now - this.lanReportNanos) / 1.0E9);
			double[] last = this.lanReportCounters;
			int acks = net.minecraft.server.Web263Lan.acks - this.lanReportAcks;
			StringBuilder line = new StringBuilder("[26.3-lan] guests=").append(guests)
				.append(" wire=").append(oneDecimal((counters[0] - last[0]) / 1024.0 / seconds)).append("KB/s")
				.append(" raw=").append(oneDecimal((counters[1] - last[1]) / 1024.0 / seconds)).append("KB/s")
				.append(" frames=").append(oneDecimal((counters[2] - last[2]) / seconds)).append("/s")
				.append(" packets=").append(oneDecimal((packets - this.lanReportPackets) / seconds)).append("/s")
				.append(" deflated=").append((int)(counters[3] - last[3]))
				.append(" (").append((int)((counters[4] - last[4]) / 1024.0)).append("->").append((int)((counters[5] - last[5]) / 1024.0)).append("KB)")
				.append(" socketBuffered=").append((int)(counters[6] / 1024.0)).append("KB")
				.append(" chunks=").append(oneDecimal((net.minecraft.server.Web263Lan.chunksSent - this.lanReportChunks) / seconds)).append("/s")
				.append(" ackMs=").append(acks > 0 ? (net.minecraft.server.Web263Lan.ackMillis - this.lanReportAckMillis) / acks : 0)
				.append(" holds=").append(net.minecraft.server.Web263Lan.holds - this.lanReportHolds)
				.append(" chunkWaits=").append(net.minecraft.server.Web263Lan.chunkWaits - this.lanReportWaits)
				.append("/").append((net.minecraft.server.Web263Lan.chunkWaitNanos - this.lanReportWaitNanos) / 1000000L).append("ms")
				.append(" notWaited=").append(net.minecraft.server.Web263Lan.chunkSkips - this.lanReportSkips)
				.append(" ticksFirst=").append(net.minecraft.server.Web263Lan.genSlicesSkipped - this.lanReportSlices)
				.append(" turns=").append(net.minecraft.server.Web263Lan.ticketTurnsDone - this.lanReportTurnsDone)
				.append("+").append(net.minecraft.server.Web263Lan.ticketTurnsCut - this.lanReportTurnsCut)
				.append("cut,").append(net.minecraft.server.Web263Lan.ticketsInExecution).append("now")
				.append(" batched=").append(host.session().batchedPeers()).append("/").append(host.session().peerCount());
			for (int i = 0; i < players.size(); i++) {
				net.minecraft.server.level.ServerPlayer player = players.get(i);
				if (!this.isSingleplayerOwner(player.nameAndId())) {
					line.append(" ").append(player.getGameProfile().name())
						.append(":ping=").append(player.connection.latency())
						.append(",window=").append(player.connection.chunkSender.web263InFlight()).append("/").append(player.connection.chunkSender.web263Window());
				}
			}
			System.out.println(line);
		}
		this.lanReportNanos = guests == 0 ? 0L : now;
		this.lanReportCounters = counters;
		this.lanReportPackets = packets;
		this.lanReportHolds = net.minecraft.server.Web263Lan.holds;
		this.lanReportChunks = net.minecraft.server.Web263Lan.chunksSent;
		this.lanReportAcks = net.minecraft.server.Web263Lan.acks;
		this.lanReportAckMillis = net.minecraft.server.Web263Lan.ackMillis;
		this.lanReportWaits = net.minecraft.server.Web263Lan.chunkWaits;
		this.lanReportWaitNanos = net.minecraft.server.Web263Lan.chunkWaitNanos;
		this.lanReportSkips = net.minecraft.server.Web263Lan.chunkSkips;
		this.lanReportSlices = net.minecraft.server.Web263Lan.genSlicesSkipped;
		this.lanReportTurnsDone = net.minecraft.server.Web263Lan.ticketTurnsDone;
		this.lanReportTurnsCut = net.minecraft.server.Web263Lan.ticketTurnsCut;
	}

	private static String oneDecimal(final double value) {
		long tenths = Math.round(value * 10.0);
		return (tenths / 10L) + "." + Math.abs(tenths % 10L);
	}

	private void tickPaused() {
		this.tickConnection();
		for (net.minecraft.server.level.ServerPlayer player : this.getPlayerList().getPlayers()) {
			player.awardStat(net.minecraft.stats.Stats.TOTAL_WORLD_TIME);
		}
	}

	@Override
	protected SampleLogger getTickTimeLogger() {
		return this.sampleLogger;
	}

	@Override
	public boolean isTickTimeLoggingEnabled() {
		return false;
	}

	@Override
	public SystemReport fillServerSystemReport(final SystemReport systemReport) {
		systemReport.setDetail("Type", "Worker integrated server");
		return systemReport;
	}

	@Override
	public LevelBasedPermissionSet getProfilePermissions(final net.minecraft.server.players.NameAndId nameAndId) {
		if (!this.isSingleplayerOwner(nameAndId) && this.commandsAllowedForOtherPlayers != null) {
			return this.commandsAllowedForOtherPlayers ? LevelBasedPermissionSet.GAMEMASTER : LevelBasedPermissionSet.ALL;
		}
		return super.getProfilePermissions(nameAndId);
	}

	@Override
	public LevelBasedPermissionSet operatorUserPermissions() {
		return LevelBasedPermissionSet.GAMEMASTER;
	}

	@Override
	public PermissionSet getFunctionCompilationPermissions() {
		return LevelBasedPermissionSet.GAMEMASTER;
	}

	@Override
	public boolean shouldRconBroadcast() {
		return true;
	}

	@Override
	public boolean shouldInformAdmins() {
		return true;
	}

	@Override
	public boolean isDedicatedServer() {
		return false;
	}

	@Override
	public int getRateLimitPacketsPerSecond() {
		return 0;
	}

	@Override
	public int getCommandSpamThresholdSeconds() {
		return 0;
	}

	@Override
	public int getChatSpamThresholdSeconds() {
		return 0;
	}

	@Override
	public boolean useNativeTransport() {
		return false;
	}

	@Override
	public boolean isPublished() {
		return this.published;
	}

	@Override
	public boolean isPaused() {
		return this.paused;
	}

	@Override
	public boolean forceSynchronousWrites() {
		return false;
	}

	int lanMaxPlayers = 8;
	net.minecraft.network.protocol.status.ServerStatus.@org.jspecify.annotations.Nullable Favicon lanIcon;

	@Override
	public int getCompressionThreshold() {
		return lanCompress ? LAN_COMPRESSION_ANNOUNCED : -1;
	}

	@Override
	public net.minecraft.network.protocol.status.@org.jspecify.annotations.Nullable ServerStatus getStatus() {
		net.minecraft.network.protocol.status.ServerStatus status = super.getStatus();
		if (status == null || !this.published) {
			return status;
		}
		return new net.minecraft.network.protocol.status.ServerStatus(
			net.minecraft.network.chat.Component.literal(this.getWorldData().getLevelName()), status.players(), status.version(),
			this.lanIcon != null ? java.util.Optional.of(this.lanIcon) : status.favicon(), status.enforcesSecureChat());
	}

	@Override
	public int getMaxPlayers() {
		return this.lanMaxPlayers;
	}

	@Override
	public boolean isSingleplayerOwner(final NameAndId nameAndId) {
		GameProfile host = this.getSingleplayerProfile();
		return host != null && nameAndId.name().equalsIgnoreCase(host.name());
	}

	@Override
	public void reportChunkLoadFailure(final Throwable throwable, final RegionStorageInfo storageInfo, final ChunkPos pos) {
		org.slf4j.LoggerFactory.getLogger("26.3-server").error("failed to load chunk " + pos, throwable);
	}

	@Override
	public void reportChunkSaveFailure(final Throwable throwable, final RegionStorageInfo storageInfo, final ChunkPos pos) {
		org.slf4j.LoggerFactory.getLogger("26.3-server").error("failed to save chunk " + pos, throwable);
	}

	@Override
	public void sendLowDiskSpaceWarning() {
	}

	private static final class WorkerPlayerList extends PlayerList {
		WorkerPlayerList(
			final MinecraftServer server,
			final LayeredRegistryAccess<RegistryLayer> registries,
			final PlayerDataStorage playerDataStorage
		) {
			super(server, registries, playerDataStorage, server.notificationManager());
			this.setViewDistance(10);
		}

		@Override
		public Component canPlayerLogin(final SocketAddress address, final NameAndId nameAndId) {
			return this.getServer().isSingleplayerOwner(nameAndId) && this.getPlayerByName(nameAndId.name()) != null
				? Component.translatable("multiplayer.disconnect.name_taken")
				: super.canPlayerLogin(address, nameAndId);
		}
	}
}
