// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class WebSavesIpc {

	public static final String CHANNEL = "SAV";

	public static final byte LIST = 0;
	public static final byte LIST_RESULT = 1;
	public static final byte EXPORT = 2;
	public static final byte EXPORT_RESULT = 3;
	public static final byte IMPORT = 4;
	public static final byte IMPORT_RESULT = 5;
	public static final byte DELETE = 6;
	public static final byte RENAME = 7;

	public static final class Msg {
		public byte op;
		public String text = "";
		public byte[] data;
		public boolean ok;
		public final List<String> ids = new ArrayList<>();
		public final List<byte[]> levelDats = new ArrayList<>();
		public final List<byte[][]> extras = new ArrayList<>();
		public String name = "";
	}

	public interface Pipe {
		void post(byte[] message);
	}

	private static Pipe pipe;

	private WebSavesIpc() {
	}

	public static void attach(final Pipe out) {
		pipe = out;
	}

	public static byte[] listRequest() {
		return encode(out -> out.writeByte(LIST));
	}

	public static byte[] listResult(final List<String> ids, final List<byte[]> levelDats) {
		return listResult(ids, levelDats, null);
	}

	public static byte[] listResult(final List<String> ids, final List<byte[]> levelDats, final List<byte[][]> extras) {
		return encode(out -> {
			out.writeByte(LIST_RESULT);
			out.writeInt(ids.size());
			for (int i = 0; i < ids.size(); i++) {
				out.writeUTF(ids.get(i));
				writeBlob(out, levelDats.get(i));
				byte[][] extra = extras == null ? null : extras.get(i);
				for (int j = 0; j < WebWorlds.SHADOW_FILES.length; j++) {
					writeBlob(out, extra == null ? null : extra[j]);
				}
			}
		});
	}

	public static byte[] deleteRequest(final String levelId) {
		return encode(out -> {
			out.writeByte(DELETE);
			out.writeUTF(levelId);
		});
	}

	public static byte[] renameRequest(final String levelId, final String name) {
		return encode(out -> {
			out.writeByte(RENAME);
			out.writeUTF(levelId);
			out.writeUTF(name);
		});
	}

	private static void writeBlob(final DataOutputStream out, final byte[] bytes) throws IOException {
		out.writeInt(bytes == null ? 0 : bytes.length);
		if (bytes != null) {
			out.write(bytes);
		}
	}

	public static byte[] exportRequest(final String levelId) {
		return encode(out -> {
			out.writeByte(EXPORT);
			out.writeUTF(levelId);
		});
	}

	public static byte[] exportResult(final String levelId, final byte[] zip) {
		return encode(out -> {
			out.writeByte(EXPORT_RESULT);
			out.writeUTF(levelId);
			out.writeInt(zip == null ? 0 : zip.length);
			if (zip != null) {
				out.write(zip);
			}
		});
	}

	public static byte[] importRequest(final String filename, final byte[] zip) {
		return encode(out -> {
			out.writeByte(IMPORT);
			out.writeUTF(filename);
			out.writeInt(zip.length);
			out.write(zip);
		});
	}

	public static byte[] importResult(final boolean ok, final String message) {
		return encode(out -> {
			out.writeByte(IMPORT_RESULT);
			out.writeBoolean(ok);
			out.writeUTF(message);
		});
	}

	public static Msg decode(final byte[] message) {
		if (message == null || message.length == 0) {
			return null;
		}
		Msg msg = new Msg();
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
			msg.op = in.readByte();
			switch (msg.op) {
				case LIST:
					break;
				case LIST_RESULT: {
					int n = in.readInt();
					if (n < 0 || n > message.length / 6 + 1) {
						return null;
					}
					for (int i = 0; i < n; i++) {
						msg.ids.add(in.readUTF());
						msg.levelDats.add(readBlob(in, message.length));
						byte[][] extra = new byte[WebWorlds.SHADOW_FILES.length][];
						for (int j = 0; j < extra.length; j++) {
							extra[j] = readBlob(in, message.length);
						}
						msg.extras.add(extra);
					}
					break;
				}
				case DELETE:
					msg.text = in.readUTF();
					break;
				case RENAME:
					msg.text = in.readUTF();
					msg.name = in.readUTF();
					break;
				case EXPORT:
					msg.text = in.readUTF();
					break;
				case EXPORT_RESULT:
					msg.text = in.readUTF();
					msg.data = readBlob(in, message.length);
					break;
				case IMPORT:
					msg.text = in.readUTF();
					msg.data = readBlob(in, message.length);
					break;
				case IMPORT_RESULT:
					msg.ok = in.readBoolean();
					msg.text = in.readUTF();
					break;
				default:
					return null;
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
		return msg;
	}

	private static byte[] readBlob(final DataInputStream in, final int limit) throws IOException {
		int len = in.readInt();
		if (len < 0 || len > limit) {
			throw new IOException("declared length " + len + " exceeds the message");
		}
		byte[] out = new byte[len];
		in.readFully(out);
		return out;
	}

	public static byte[] serve(final byte[] request) {
		Msg msg = decode(request);
		if (msg == null) {
			return null;
		}
		switch (msg.op) {
			case LIST:
				return currentList();
			case DELETE:
				System.out.println("[26.3-web] deleting world " + msg.text + ": " + WebWorlds.deleteLevel(msg.text));
				return currentList();
			case RENAME:
				System.out.println("[26.3-web] renaming world " + msg.text + ": " + WebWorlds.renameLevel(msg.text, msg.name));
				return currentList();
			case EXPORT:
				return exportResult(msg.text, WebWorlds.buildArchive(msg.text));
			case IMPORT: {
				String problem = WebWorlds.importArchive(msg.text, msg.data);
				return importResult(problem == null, problem == null ? "imported " + msg.text : problem);
			}
			default:
				return null;
		}
	}

	private static byte[] currentList() {
		List<String> ids = WebWorlds.levelIds();
		List<byte[]> dats = new ArrayList<>(ids.size());
		List<byte[][]> extras = new ArrayList<>(ids.size());
		for (String id : ids) {
			dats.add(WebWorlds.levelDat(id));
			byte[][] extra = new byte[WebWorlds.SHADOW_FILES.length][];
			for (int j = 0; j < extra.length; j++) {
				extra[j] = WebWorlds.levelFile(id, WebWorlds.SHADOW_FILES[j]);
			}
			extras.add(extra);
		}
		return listResult(ids, dats, extras);
	}

	private static final java.util.Map<String, byte[]> listed = new java.util.HashMap<>();

	public static void reconcile() {
		if (pipe == null || listed.isEmpty()) {
			return;
		}
		java.util.Iterator<java.util.Map.Entry<String, byte[]>> it = listed.entrySet().iterator();
		while (it.hasNext()) {
			java.util.Map.Entry<String, byte[]> entry = it.next();
			byte[] now = WebSaves.readFile(WebSaves.SAVES + "/" + entry.getKey() + "/level.dat");
			if (now == null) {
				it.remove();
				request(deleteRequest(entry.getKey()));
				return;
			}
			if (!java.util.Arrays.equals(now, entry.getValue())) {
				entry.setValue(now);
				String name = WebWorlds.levelName(now);
				if (name != null) {
					request(renameRequest(entry.getKey(), name));
					return;
				}
			}
		}
	}

	public static void receive(final byte[] reply) {
		Msg msg = decode(reply);
		if (msg == null) {
			return;
		}
		switch (msg.op) {
			case LIST_RESULT:
				for (String stale : new ArrayList<>(listed.keySet())) {
					if (!msg.ids.contains(stale)) {
						WebWorlds.deleteTree(WebSaves.SAVES + "/" + stale);
					}
				}
				listed.clear();
				for (int i = 0; i < msg.ids.size(); i++) {
					byte[] dat = msg.levelDats.get(i);
					if (dat == null || dat.length == 0) {
						continue;
					}
					String folder = WebSaves.SAVES + "/" + msg.ids.get(i) + "/";
					try {
						WebSaves.writeFile(folder + "level.dat", dat);
						listed.put(msg.ids.get(i), dat);
						byte[][] extra = msg.extras.get(i);
						for (int j = 0; j < extra.length; j++) {
							if (extra[j] != null && extra[j].length > 0) {
								WebSaves.writeFile(folder + WebWorlds.SHADOW_FILES[j], extra[j]);
							}
						}
					} catch (IOException e) {
						System.out.println("[26.3-web] could not shadow " + msg.ids.get(i) + ": " + e);
					}
				}
				System.out.println("[26.3-web] worker reports " + msg.ids.size() + " worlds");
				WebWorlds.refreshWorldList();
				break;
			case EXPORT_RESULT:
				if (msg.data == null || msg.data.length == 0) {
					System.out.println("[26.3-web] worker had nothing to export for " + msg.text);
					break;
				}
				WebWorlds.download(msg.text + ".zip", msg.data);
				break;
			case IMPORT_RESULT:
				System.out.println("[26.3-web] world import " + (msg.ok ? "ok: " : "failed: ") + msg.text);
				if (msg.ok) request(listRequest());
				break;
			default:
				break;
		}
	}

	public static void request(final byte[] message) {
		if (pipe == null) {
			System.out.println("[26.3-web] no worker channel attached; dropping a /saves request");
			return;
		}
		pipe.post(message);
	}

	public static void respond(final byte[] request) {
		byte[] reply = serve(request);
		if (reply != null && pipe != null) {
			pipe.post(reply);
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
			throw new IllegalStateException("encoding a /saves message cannot fail", e);
		}
		return bytes.toByteArray();
	}
}
