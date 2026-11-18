// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public final class WebLifecycleMsg {

	public static final String CHANNEL = "LFC";

	public static final byte OPEN = 0;
	public static final byte READY = 1;
	public static final byte STOP = 2;
	public static final byte STOPPED = 3;
	public static final byte FATAL = 4;
	public static final byte PROGRESS = 5;
	public static final byte HOST_OPEN = 6;
	public static final byte HOST_CLOSE = 7;
	public static final byte HOST_STATE = 8;
	public static final byte HOST_SETTINGS = 9;
	public static final byte DISTANCES = 10;
	public static final byte PAUSE = 11;

	public static final byte HOST_IDLE = 0;
	public static final byte HOST_OPENING = 1;
	public static final byte HOST_BOUND = 2;
	public static final byte HOST_ERROR = 3;

	public static final byte PROGRESS_START = 0;
	public static final byte PROGRESS_UPDATE = 1;
	public static final byte PROGRESS_FINISH = 2;
	private static final int MAX_PROGRESS_RADIUS = 32;

	public static final class OpenSpec {
		public String levelId = "";
		public String levelName = "";
		public boolean create;
		public int gameType;
		public int difficulty = 2;
		public boolean hardcore;
		public boolean difficultyLocked;
		public boolean allowCommands;
		public long seed;
		public boolean generateStructures = true;
		public boolean bonusChest;
		public String preset = "";
		public String playerName = "Player";
		public UUID playerId = new UUID(0L, 0L);
		public int viewDistance = 10;
		public int simulationDistance = 10;
		public java.util.List<String> enabledPacks = new java.util.ArrayList<>();
		public java.util.List<String> disabledPacks = new java.util.ArrayList<>();
		public java.util.List<String> features = new java.util.ArrayList<>();
		public java.util.List<String> packNames = new java.util.ArrayList<>();
		public java.util.List<byte[]> packData = new java.util.ArrayList<>();
	}

	public static final class ProgressSnapshot {
		public byte event;
		public byte stage;
		public int currentChunks;
		public int totalChunks;
		public int radius;
		public byte[] statuses = new byte[0];
	}

	public static final class HostSpec {
		public String socketUrl = "";
		public int port;
		public String motd = "";
		public boolean announce = true;
		public int gameType = -1;
		public int commands = -1;
		public String passwordHash = "";
		public int maxPlayers = 8;
		public byte[] icon = new byte[0];
	}

	public static final class HostState {
		public byte state;
		public String address = "";
		public int port = -1;
		public String message = "";
	}

	public interface Pipe {
		void post(byte[] message);
	}

	private WebLifecycleMsg() {
	}

	public static byte[] encodeOpen(final OpenSpec spec) {
		return encode(out -> {
			out.writeByte(OPEN);
			out.writeUTF(spec.levelId);
			out.writeUTF(spec.levelName);
			out.writeBoolean(spec.create);
			out.writeByte(spec.gameType);
			out.writeByte(spec.difficulty);
			out.writeBoolean(spec.hardcore);
			out.writeBoolean(spec.difficultyLocked);
			out.writeBoolean(spec.allowCommands);
			out.writeLong(spec.seed);
			out.writeBoolean(spec.generateStructures);
			out.writeBoolean(spec.bonusChest);
			out.writeUTF(spec.preset);
			out.writeUTF(spec.playerName);
			out.writeLong(spec.playerId.getMostSignificantBits());
			out.writeLong(spec.playerId.getLeastSignificantBits());
			out.writeInt(spec.viewDistance);
			out.writeInt(spec.simulationDistance);
			writeStrings(out, spec.enabledPacks);
			writeStrings(out, spec.disabledPacks);
			writeStrings(out, spec.features);
			out.writeInt(spec.packNames.size());
			for (int i = 0; i < spec.packNames.size(); i++) {
				out.writeUTF(spec.packNames.get(i));
				byte[] data = spec.packData.get(i);
				out.writeInt(data.length);
				out.write(data);
			}
		});
	}

	private static void writeStrings(final DataOutputStream out, final java.util.List<String> values) throws IOException {
		out.writeInt(values.size());
		for (String value : values) {
			out.writeUTF(value);
		}
	}

	private static boolean readStrings(final DataInputStream in, final java.util.List<String> into) throws IOException {
		int count = in.readInt();
		if (count < 0 || count > 4096) {
			return false;
		}
		for (int i = 0; i < count; i++) {
			into.add(in.readUTF());
		}
		return true;
	}

	static boolean safePackName(final String name) {
		return !name.isEmpty() && name.length() <= 128 && !name.contains("/") && !name.contains("\\")
			&& !name.contains(":") && !name.contains("..") && name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
	}

	public static byte[] encodeSimple(final byte op) {
		return encode(out -> out.writeByte(op));
	}

	public static byte[] encodeFatal(final String message) {
		return encodeText(FATAL, message == null ? "unknown error" : message);
	}

	public static byte[] encodeProgress(final ProgressSnapshot snapshot) {
		return encode(out -> {
			out.writeByte(PROGRESS);
			out.writeByte(snapshot.event);
			out.writeByte(snapshot.stage);
			out.writeInt(snapshot.currentChunks);
			out.writeInt(snapshot.totalChunks);
			out.writeByte(snapshot.radius);
			out.write(snapshot.statuses);
		});
	}

	public static byte[] encodeHostOpen(final HostSpec spec) {
		return encode(out -> {
			out.writeByte(HOST_OPEN);
			out.writeUTF(spec.socketUrl);
			out.writeInt(spec.port);
			out.writeUTF(truncate(spec.motd));
			out.writeBoolean(spec.announce);
			out.writeInt(spec.gameType);
			out.writeInt(spec.commands);
			out.writeUTF(spec.passwordHash);
			out.writeInt(spec.maxPlayers);
			out.writeInt(spec.icon.length);
			out.write(spec.icon);
		});
	}

	public static byte[] encodeHostSettings(final int gameType, final int commands, final String passwordHash, final int maxPlayers) {
		return encode(out -> {
			out.writeByte(HOST_SETTINGS);
			out.writeInt(gameType);
			out.writeInt(commands);
			out.writeUTF(passwordHash == null ? "" : passwordHash);
			out.writeInt(maxPlayers);
		});
	}

	public static byte[] encodeDistances(final int viewDistance, final int simulationDistance) {
		return encode(out -> {
			out.writeByte(DISTANCES);
			out.writeInt(viewDistance);
			out.writeInt(simulationDistance);
		});
	}

	public static byte[] encodePause(final boolean paused) {
		return encode(out -> {
			out.writeByte(PAUSE);
			out.writeBoolean(paused);
		});
	}

	public static Boolean decodePause(final byte[] message) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			if (in.readByte() != PAUSE) {
				return null;
			}
			boolean paused = in.readBoolean();
			return in.available() == 0 ? Boolean.valueOf(paused) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public static int[] decodeDistances(final byte[] message) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			if (in.readByte() != DISTANCES) {
				return null;
			}
			int view = in.readInt();
			int simulation = in.readInt();
			return in.available() == 0 && view >= 2 && view <= 32 && simulation >= 2 && simulation <= 32
				? new int[] {view, simulation} : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public static HostSpec decodeHostSettings(final byte[] message) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			if (in.readByte() != HOST_SETTINGS) {
				return null;
			}
			HostSpec out = new HostSpec();
			out.gameType = in.readInt();
			out.commands = in.readInt();
			out.passwordHash = in.readUTF();
			out.maxPlayers = in.readInt();
			return in.available() == 0 && validMaxPlayers(out.maxPlayers) ? out : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public static final int MAX_ICON_BYTES = 65536;

	public static boolean validMaxPlayers(final int maxPlayers) {
		return maxPlayers >= 1 && maxPlayers <= 100;
	}

	public static byte[] encodeHostState(final HostState state) {
		return encode(out -> {
			out.writeByte(HOST_STATE);
			out.writeByte(state.state);
			out.writeUTF(truncate(state.address));
			out.writeInt(state.port);
			out.writeUTF(truncate(state.message));
		});
	}

	private static byte[] encodeText(final byte op, final String message) {
		return encode(out -> {
			out.writeByte(op);
			out.writeUTF(truncate(message));
		});
	}

	private static String truncate(final String s) {
		return s.length() <= 4096 ? s : s.substring(0, 4096) + "...";
	}

	public static int opOf(final byte[] message) {
		return message == null || message.length == 0 ? -1 : message[0];
	}

	public static OpenSpec decodeOpen(final byte[] message) {
		OpenSpec spec = new OpenSpec();
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			if (in.readByte() != OPEN) {
				return null;
			}
			spec.levelId = in.readUTF();
			spec.levelName = in.readUTF();
			spec.create = in.readBoolean();
			spec.gameType = in.readByte();
			spec.difficulty = in.readByte();
			spec.hardcore = in.readBoolean();
			spec.difficultyLocked = in.readBoolean();
			spec.allowCommands = in.readBoolean();
			spec.seed = in.readLong();
			spec.generateStructures = in.readBoolean();
			spec.bonusChest = in.readBoolean();
			spec.preset = in.readUTF();
			spec.playerName = in.readUTF();
			spec.playerId = new UUID(in.readLong(), in.readLong());
			spec.viewDistance = in.readInt();
			spec.simulationDistance = in.readInt();
			if (in.available() > 0) {
				if (!readStrings(in, spec.enabledPacks) || !readStrings(in, spec.disabledPacks) || !readStrings(in, spec.features)) {
					return null;
				}
				int packs = in.readInt();
				if (packs < 0 || packs > 256) {
					return null;
				}
				for (int i = 0; i < packs; i++) {
					String name = in.readUTF();
					int length = in.readInt();
					if (length < 0 || length > in.available()) {
						return null;
					}
					byte[] data = new byte[length];
					in.readFully(data);
					if (safePackName(name)) {
						spec.packNames.add(name);
						spec.packData.add(data);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
		if (spec.levelId.isEmpty() || spec.levelId.contains("/") || spec.levelId.contains("\\")
				|| spec.levelId.contains("..")) {
			return null;
		}
		return spec;
	}

	public static String decodeFatal(final byte[] message) {
		return decodeText(FATAL, message);
	}

	public static ProgressSnapshot decodeProgress(final byte[] message) {
		ProgressSnapshot snapshot = new ProgressSnapshot();
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			if (in.readByte() != PROGRESS) {
				return null;
			}
			snapshot.event = in.readByte();
			snapshot.stage = in.readByte();
			snapshot.currentChunks = in.readInt();
			snapshot.totalChunks = in.readInt();
			snapshot.radius = in.readUnsignedByte();
			if (snapshot.event < PROGRESS_START || snapshot.event > PROGRESS_FINISH
					|| snapshot.stage < 0 || snapshot.stage > 3
					|| snapshot.currentChunks < 0 || snapshot.totalChunks < 0
					|| snapshot.radius > MAX_PROGRESS_RADIUS) {
				return null;
			}
			int diameter = snapshot.radius * 2 + 1;
			int cells = diameter * diameter;
			if (in.available() != cells) {
				return null;
			}
			snapshot.statuses = new byte[cells];
			in.readFully(snapshot.statuses);
			for (byte status : snapshot.statuses) {
				if (status < -1 || status > 11) {
					return null;
				}
			}
			return snapshot;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	public static HostSpec decodeHostOpen(final byte[] message) {
		HostSpec spec = new HostSpec();
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			if (in.readByte() != HOST_OPEN) {
				return null;
			}
			spec.socketUrl = in.readUTF();
			spec.port = in.readInt();
			spec.motd = in.readUTF();
			spec.announce = in.readBoolean();
			spec.gameType = in.readInt();
			spec.commands = in.readInt();
			spec.passwordHash = in.readUTF();
			spec.maxPlayers = in.readInt();
			int iconLength = in.readInt();
			if (iconLength < 0 || iconLength > MAX_ICON_BYTES) {
				return null;
			}
			spec.icon = new byte[iconLength];
			in.readFully(spec.icon);
			if (in.available() != 0) {
				return null;
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
		if ((!spec.socketUrl.startsWith("ws://") && !spec.socketUrl.startsWith("wss://"))
				|| spec.port < 0 || spec.port > 65535 || !validMaxPlayers(spec.maxPlayers)) {
			return null;
		}
		return spec;
	}

	public static HostState decodeHostState(final byte[] message) {
		HostState state = new HostState();
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			if (in.readByte() != HOST_STATE) {
				return null;
			}
			state.state = in.readByte();
			state.address = in.readUTF();
			state.port = in.readInt();
			state.message = in.readUTF();
			if (in.available() != 0 || state.state < HOST_IDLE || state.state > HOST_ERROR
					|| state.port < -1 || state.port > 65535) {
				return null;
			}
			return state;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static String decodeText(final byte op, final byte[] message) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			return in.readByte() == op ? in.readUTF() : "";
		} catch (IOException | RuntimeException e) {
			return "";
		}
	}

	private interface Body {
		void write(DataOutputStream out) throws IOException;
	}

	private static byte[] encode(final Body body) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			body.write(out);
		} catch (IOException e) {
			throw new IllegalStateException("encoding a lifecycle message cannot fail", e);
		}
		return bytes.toByteArray();
	}
}
