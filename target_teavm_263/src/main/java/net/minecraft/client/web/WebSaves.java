// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.teavm.classlib.java.nio.file.TVfs;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;
import org.teavm.runtime.fs.VirtualFileSystem;
import org.teavm.runtime.fs.VirtualFileSystemProvider;

public final class WebSaves {
	private static final String DB = "minecraft_web263";

	private static final int SCHEMA = Credits.n(572475321);

	static final String SAVES = "/saves";
	static final String PACKS = "/resourcepacks";
	static final String DOWNLOADS = "/downloads";
	static final String CONFIG = "/config";

	private static final String[] ROOTS = {SAVES, PACKS, DOWNLOADS, CONFIG};

	private static String[] roots = ROOTS;

	private static boolean split;

	private static final int FLUSH_INTERVAL_FRAMES = 120;

	private static final Map<String, long[]> snapshot = new HashMap<>();

	static final int PIECE = 262144;

	private static final Map<String, int[]> pieceHashes = new HashMap<>();

	private static boolean enabled;
	private static int sinceFlush;

	private WebSaves() {
	}

	public static void restore(final Runnable next) {
		restore(next, true);
	}

	private static void restore(final Runnable next, final boolean allowWipe) {
		splitRoots();
		for (String root : ROOTS) {
			mkdirs(root);
		}
		final long start = System.currentTimeMillis();
		final int[] files = {0};
		final boolean[] booted = {false};
		final boolean wipe = allowWipe && wipeRequested() && !split;
		if (wipe) {
			System.out.println("[26.3-web] ?wipe=1: clearing persisted worlds");
		} else if (split && wipeRequested()) {
			System.out.println("[26.3-web] ?wipe=1 ignored: worker mode owns /saves in the other realm");
		}
		openAndRestore(DB, SCHEMA, wipe, (key, data) -> {
			try {
				int index = pieceIndex(key);
				String path = index < 0 ? key : piecePath(key);
				if (path == null || !mirrored(path)) {
					return;
				}
				if (index < 0) {
					writeFile(key, data.copyToJavaArray());
				} else {
					writePiece(path, index, data.copyToJavaArray());
				}
				files[0]++;
			} catch (Throwable e) {
				System.out.println("[26.3-web] could not restore " + key + ": " + e);
			}
		}, ok -> {
			enabled = ok;
			if (ok) {
				System.out.println("[26.3-web] restored " + files[0] + " persisted entries in "
					+ (System.currentTimeMillis() - start) + "ms");
				takeSnapshot();
			} else {
				System.out.println("[26.3-web] IndexedDB unavailable; worlds will not survive a reload");
			}
			if (!booted[0]) {
				booted[0] = true;
				next.run();
			}
		});
	}

	private static void splitRoots() {
		boolean inWorker = WebWorkerTransport.inWorker();
		splitRoots(inWorker, !inWorker && WebWorkerNet.savesOwnedByWorker());
	}

	static void splitRoots(boolean inWorker, boolean savesOwnedByWorker) {
		if (inWorker) {
			roots = new String[]{SAVES};
			TVfs.disown(PACKS);
			TVfs.disown(DOWNLOADS);
			split = true;
		} else if (savesOwnedByWorker) {
			roots = new String[]{PACKS, DOWNLOADS, CONFIG};
			TVfs.disown(SAVES);
			split = true;
		}
	}

	static boolean handSavesToWorker(final boolean workerRequested, final boolean workerSupported,
			final boolean fellBack) {
		return workerRequested && workerSupported && !fellBack;
	}

	public static void reclaimSavesForInProcess() {
		TVfs.reown(SAVES);
		roots = ROOTS;
		split = false;
	}

	public static void restoreReclaimedSaves(final Runnable next) {
		reclaimSavesForInProcess();
		restore(next, false);
	}

	public static boolean isSplit() {
		return split;
	}

	static boolean mirrored(final String path) {
		for (String root : roots) {
			if (path.startsWith(root + "/")) {
				return true;
			}
		}
		return false;
	}

	public static void tick() {
		if (enabled && ++sinceFlush >= FLUSH_INTERVAL_FRAMES) {
			sinceFlush = 0;
			flush();
		}
	}

	public static void flush() {
		if (!enabled) {
			return;
		}
		Map<String, long[]> current = new HashMap<>();
		for (String root : roots) {
			walk(root, current);
		}
		List<String> changed = new ArrayList<>();
		List<String> gone = new ArrayList<>();
		diff(snapshot, current, changed, gone);
		beginTx();
		try {
			for (String path : changed) {
				mirror(path);
			}
			for (String path : gone) {
				forget(path);
			}
		} finally {
			endTx();
		}
		snapshot.clear();
		snapshot.putAll(current);
	}

	private static final byte[] pieceBuffer = new byte[PIECE];

	private static void mirror(final String path) {
		VirtualFileAccessor accessor = fs().getFile(path).createAccessor(true, false, false);
		if (accessor == null) {
			return;
		}
		try {
			int size;
			try {
				size = accessor.size();
			} catch (IOException e) {
				return;
			}
			int pieces = pieceCount(size);
			int[] was = pieceHashes.get(path);
			if (was == null) {
				remove(path);
			}
			int[] now = new int[pieces * 2];
			for (int i = 0; i < pieces; i++) {
				int len = Math.min(PIECE, Math.max(0, size - i * PIECE));
				if (!readInto(accessor, i * PIECE, len)) {
					return;
				}
				hashPiece(pieceBuffer, len, now, i);
				if (pieceChanged(was, now, i)) {
					byte[] exact = len == PIECE ? pieceBuffer : java.util.Arrays.copyOf(pieceBuffer, len);
					put(pieceKey(path, i), Int8Array.copyFromJavaArray(exact));
				}
			}
			if (was != null) {
				for (int i = pieces; i < was.length / 2; i++) {
					remove(pieceKey(path, i));
				}
			}
			pieceHashes.put(path, now);
		} finally {
			close(accessor);
		}
	}

	private static boolean readInto(final VirtualFileAccessor accessor, final int offset, final int len) {
		try {
			accessor.seek(offset);
			int at = 0;
			while (at < len) {
				int n = accessor.read(pieceBuffer, at, len - at);
				if (n <= 0) {
					return false;
				}
				at += n;
			}
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static void forget(final String path) {
		remove(path);
		int[] was = pieceHashes.remove(path);
		int pieces = was == null ? 0 : was.length / 2;
		for (int i = 0; i < pieces; i++) {
			remove(pieceKey(path, i));
		}
	}

	static String pieceKey(final String path, final int index) {
		return new StringBuilder().append('\u0001').append(path).append('\u0001')
				.append(index).toString();
	}

	static String piecePath(final String key) {
		if (key.isEmpty() || key.charAt(0) != '\u0001') {
			return null;
		}
		int split = key.lastIndexOf('\u0001');
		return split <= 1 ? null : key.substring(1, split);
	}

	static int pieceIndex(final String key) {
		if (key.isEmpty() || key.charAt(0) != '\u0001') {
			return -1;
		}
		int split = key.lastIndexOf('\u0001');
		if (split <= 1 || split + 1 >= key.length()) {
			return -1;
		}
		int out = 0;
		for (int i = split + 1; i < key.length(); i++) {
			char c = key.charAt(i);
			if (c < '0' || c > '9') {
				return -1;
			}
			out = out * 10 + (c - '0');
		}
		return out;
	}

	static int pieceCount(final int size) {
		return Math.max(1, (size + PIECE - 1) / PIECE);
	}

	static void hashPiece(final byte[] buf, final int len, final int[] out, final int index) {
		int h1 = 0x811c9dc5;
		int h2 = 0x01000193;
		for (int at = 0; at < len; at++) {
			int b = buf[at] & 0xFF;
			h1 = (h1 ^ b) * 0x01000193;
			h2 = (h2 + b) * 0x85ebca6b ^ (h2 >>> 15);
		}
		out[index * 2] = h1 ^ len;
		out[index * 2 + 1] = h2 ^ len;
	}

	static boolean pieceChanged(final int[] was, final int[] now, final int i) {
		if (was == null || i * 2 + 1 >= was.length) {
			return true;
		}
		return was[i * 2] != now[i * 2] || was[i * 2 + 1] != now[i * 2 + 1];
	}

	static void diff(final Map<String, long[]> was, final Map<String, long[]> now,
			final List<String> changed, final List<String> gone) {
		for (Map.Entry<String, long[]> entry : now.entrySet()) {
			long[] before = was.get(entry.getKey());
			long[] after = entry.getValue();
			if (before == null || before[0] != after[0] || before[1] != after[1]) {
				changed.add(entry.getKey());
			}
		}
		for (String path : was.keySet()) {
			if (!now.containsKey(path)) {
				gone.add(path);
			}
		}
	}

	private static void takeSnapshot() {
		snapshot.clear();
		for (String root : roots) {
			walk(root, snapshot);
		}
	}

	static void walk(final String path, final Map<String, long[]> out) {
		VirtualFile file = fs().getFile(path);
		if (file.isFile()) {
			out.put(path, new long[]{file.length(), file.lastModified()});
			return;
		}
		if (!file.isDirectory()) {
			return;
		}
		String[] names = file.listFiles();
		if (names == null) {
			return;
		}
		for (String name : names) {
			walk(path + "/" + name, out);
		}
	}

	private static VirtualFileSystem fs() {
		return VirtualFileSystemProvider.getInstance();
	}

	public static byte[] readFile(final String path) {
		VirtualFile file = fs().getFile(path);
		VirtualFileAccessor accessor = file.createAccessor(true, false, false);
		if (accessor == null) {
			return null;
		}
		try {
			byte[] out = new byte[accessor.size()];
			int off = 0;
			while (off < out.length) {
				int n = accessor.read(out, off, out.length - off);
				if (n <= 0) {
					break;
				}
				off += n;
			}
			return out;
		} catch (IOException e) {
			return null;
		} finally {
			close(accessor);
		}
	}

	public static void writeFile(final String path, final byte[] bytes) throws IOException {
		VirtualFileAccessor accessor = openForWrite(path);
		try {
			accessor.resize(0);
			accessor.seek(0);
			accessor.write(bytes, 0, bytes.length);
		} finally {
			close(accessor);
		}
	}

	public static void trimFiles(final String path, final int maxFiles) {
		VirtualFile directory = fs().getFile(path);
		String[] names = directory.listFiles();
		if (names == null) {
			return;
		}
		List<VirtualFile> files = new ArrayList<>();
		for (String name : names) {
			VirtualFile file = fs().getFile(path + "/" + name);
			if (file.isFile()) {
				files.add(file);
			}
		}
		files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
		for (int i = Math.max(0, maxFiles); i < files.size(); i++) {
			files.get(i).delete();
		}
	}

	static void writePiece(final String path, final int index, final byte[] bytes) throws IOException {
		VirtualFileAccessor accessor = openForWrite(path);
		try {
			int offset = index * PIECE;
			int need = offset + bytes.length;
			if (accessor.size() < need) {
				accessor.resize(need);
			}
			accessor.seek(offset);
			accessor.write(bytes, 0, bytes.length);
		} finally {
			close(accessor);
		}
	}

	private static VirtualFileAccessor openForWrite(final String path) throws IOException {
		int slash = path.lastIndexOf('/');
		VirtualFile dir = mkdirs(slash <= 0 ? "/" : path.substring(0, slash));
		String name = path.substring(slash + 1);
		if (!fs().getFile(path).isFile()) {
			dir.createFile(name);
		}
		VirtualFileAccessor accessor = fs().getFile(path).createAccessor(false, true, true);
		if (accessor == null) {
			throw new IOException("cannot open " + path);
		}
		return accessor;
	}

	public static VirtualFile mkdirs(final String path) {
		VirtualFile file = fs().getFile(path);
		if (file.isDirectory()) {
			return file;
		}
		List<String> missing = new ArrayList<>();
		String at = path;
		while (!at.equals("/") && !fs().getFile(at).isDirectory()) {
			int slash = at.lastIndexOf('/');
			missing.add(at.substring(slash + 1));
			at = slash <= 0 ? "/" : at.substring(0, slash);
		}
		VirtualFile dir = fs().getFile(at);
		for (int i = missing.size() - 1; i >= 0; i--) {
			dir.createDirectory(missing.get(i));
			at = at.equals("/") ? "/" + missing.get(i) : at + "/" + missing.get(i);
			dir = fs().getFile(at);
		}
		return dir;
	}

	private static void close(final VirtualFileAccessor accessor) {
		try {
			accessor.close();
		} catch (IOException e) {
		}
	}

	@JSFunctor
	private interface EntryHandler extends JSObject {
		void entry(String path, Int8Array data);
	}

	@JSFunctor
	private interface DoneHandler extends JSObject {
		void done(boolean ok);
	}

	@JSBody(params = {"name", "schema", "wipe", "onEntry", "onDone"}, script = ""
		+ "var settled = 0;"
		+ "var finish = function(ok) { var lvl = ok ? 2 : 1; if (settled >= lvl) { return; }"
		+ " settled = lvl; try { onDone(ok); } catch (e) {} };"
		+ "var fail = function() { finish(false); };"
		+ "var rq = null;"
		+ "var deadline = function() {"
		+ "  if (settled) { return; }"
		+ "  if (rq && rq.readyState === 'done') { setTimeout(deadline, 1000); return; }"
		+ "  console.error('[26.3-web] IndexedDB never answered (file:// origin?);"
		+ " continuing without persistence'); finish(false);"
		+ "};"
		+ "setTimeout(deadline, 5000);"
		+ "try {"
		+ "  if (!globalThis.indexedDB) { fail(); return; }"
		+ "  if (wipe) { globalThis.indexedDB.deleteDatabase(name); }"
		+ "  rq = globalThis.indexedDB.open(name, schema);"
		+ "  rq.onerror = fail;"
		+ "  rq.onblocked = fail;"
		+ "  rq.onupgradeneeded = function() {"
		+ "    var db = rq.result;"
		+ "    if (db.objectStoreNames.contains('files')) { db.deleteObjectStore('files'); }"
		+ "    db.createObjectStore('files');"
		+ "  };"
		+ "  rq.onsuccess = function() {"
		+ "    var db = rq.result;"
		+ "    globalThis.__minecraftDb = db;"
		+ "    try {"
		+ "      var cur = db.transaction('files', 'readonly').objectStore('files').openCursor();"
		+ "      cur.onerror = function() { finish(true); };"
		+ "      cur.onsuccess = function() {"
		+ "        var c = cur.result;"
		+ "        if (!c) { finish(true); return; }"
		+ "        try { onEntry(c.key, new Int8Array(c.value)); } catch (e) { console.error(e); }"
		+ "        c.continue();"
		+ "      };"
		+ "    } catch (e) { finish(true); }"
		+ "  };"
		+ "} catch (e) { fail(); }")
	private static native void openAndRestore(String name, int schema, boolean wipe, EntryHandler onEntry, DoneHandler onDone);

	@JSBody(params = {}, script = ""
		+ "try {"
		+ "  var db = globalThis.__minecraftDb; if (!db) { return; }"
		+ "  var tx = db.transaction('files', 'readwrite');"
		+ "  tx.onerror = function(e) {"
		+ "    if (e && e.preventDefault) { e.preventDefault(); }"
		+ "    if (!globalThis.__minecraftStoreWarned) {"
		+ "      globalThis.__minecraftStoreWarned = 1;"
		+ "      console.info('[26.3-web] IndexedDB write failed (quota?); saves stay in RAM for this session');"
		+ "    }"
		+ "  };"
		+ "  globalThis.__minecraftTx = tx;"
		+ "} catch (e) { globalThis.__minecraftTx = null; }")
	private static native void beginTx();

	@JSBody(params = {}, script = "globalThis.__minecraftTx = null;")
	private static native void endTx();

	@JSBody(params = {"path", "data"}, script = ""
		+ "try {"
		+ "  var tx = globalThis.__minecraftTx;"
		+ "  if (!tx) {"
		+ "    var db = globalThis.__minecraftDb; if (!db) { return; }"
		+ "    tx = db.transaction('files', 'readwrite');"
		+ "    tx.onerror = function(e) {"
		+ "      if (e && e.preventDefault) { e.preventDefault(); }"
		+ "      if (!globalThis.__minecraftStoreWarned) {"
		+ "        globalThis.__minecraftStoreWarned = 1;"
		+ "        console.info('[26.3-web] IndexedDB write failed (quota?); saves stay in RAM for this session');"
		+ "      }"
		+ "    };"
		+ "  }"
		+ "  tx.objectStore('files').put(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength), path);"
		+ "} catch (e) {}")
	private static native void put(String path, Int8Array data);

	@JSBody(params = {"path"}, script = ""
		+ "try {"
		+ "  var tx = globalThis.__minecraftTx;"
		+ "  if (!tx) {"
		+ "    var db = globalThis.__minecraftDb; if (!db) { return; }"
		+ "    tx = db.transaction('files', 'readwrite');"
		+ "    tx.onerror = function(e) { if (e && e.preventDefault) { e.preventDefault(); } };"
		+ "  }"
		+ "  tx.objectStore('files').delete(path);"
		+ "} catch (e) {}")
	private static native void remove(String path);

	@JSBody(params = {}, script = "try { var s = globalThis.location && globalThis.location.search;"
		+ " return !!s && /[?&]wipe=1/.test(s); } catch (e) { return false; }")
	private static native boolean wipeRequested();
}
