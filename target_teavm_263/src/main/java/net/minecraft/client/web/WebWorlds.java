// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebWorlds {
	private static final String SAVES = WebSaves.SAVES;

	private static final String LOCK = "session.lock";

	private WebWorlds() {
	}

	public static void install() {
		installWorldInput(WebWorlds::acceptImport);
		SelectWorldScreen.importWorldOverride = WebWorlds::importWorld;
		SelectWorldScreen.importWorldTitleOverride = Component.literal("Import World");
		EditWorldScreen.openWorldFolderOverride = WebWorlds::exportWorld;
		EditWorldScreen.openWorldFolderTitleOverride = Component.literal("Download World (.zip)");
	}

	public static void importWorld() {
		clickWorldPicker();
	}

	private static void acceptImport(final String filename, final Int8Array data) {
		byte[] archive = data.copyToJavaArray();
		if (WebSaves.isSplit()) {
			WebSavesIpc.request(WebSavesIpc.importRequest(filename, archive));
			return;
		}
		String problem = importArchive(filename, archive);
		if (problem != null) {
			fail(problem);
			return;
		}
		refreshWorldList();
	}

	public static String importArchive(final String filename, final byte[] archive) {
		Map<String, byte[]> entries = WebZipPack.unzip(archive);
		if (entries == null) {
			return filename + " is not a readable .zip";
		}
		String root = levelRoot(entries.keySet());
		if (root == null) {
			return filename + " has no level.dat in it, so it is not a world save";
		}
		byte[] levelDat = entries.get(root + "level.dat");
		int version = dataVersion(levelDat);
		if (version != SharedConstants.WORLD_VERSION) {
			return "world data version " + (version < 0 ? "unreadable" : Integer.toString(version))
				+ ", this client is " + SharedConstants.WORLD_VERSION
				+ "; the browser build ships no data fixers, so it will not convert it";
		}

		String folder = freeFolder(folderName(filename, root), WebWorlds::exists);
		int written = 0;
		long bytes = 0;
		try {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				String relative = safeEntry(root, entry.getKey());
				if (relative == null || relative.equals(LOCK)) {
					continue;
				}
				WebSaves.writeFile(SAVES + "/" + folder + "/" + relative, entry.getValue());
				written++;
				bytes += entry.getValue().length;
			}
		} catch (Throwable e) {
			return "could not write " + folder + ": " + e;
		}
		System.out.println("[26.3-web] imported world " + folder + " (" + written + " files, " + bytes + " bytes)");
		WebSaves.flush();
		return null;
	}

	static void refreshWorldList() {
		Screen screen = currentScreen();
		if (screen instanceof SelectWorldScreen selectWorld) {
			selectWorld.reloadWorldList();
		}
	}

	static String levelRoot(final Collection<String> names) {
		String best = null;
		for (String name : names) {
			String normalized = name.replace('\\', '/');
			if (!normalized.endsWith("level.dat")) {
				continue;
			}
			String prefix = normalized.substring(0, normalized.length() - "level.dat".length());
			if (best == null || prefix.length() < best.length()) {
				best = prefix;
			}
		}
		return best;
	}

	static String safeEntry(final String root, final String name) {
		String normalized = name.replace('\\', '/');
		if (!normalized.startsWith(root)) {
			return null;
		}
		String relative = normalized.substring(root.length());
		if (relative.isEmpty() || relative.startsWith("/") || relative.endsWith("/") || relative.contains("//")
			|| relative.contains(":")) {
			return null;
		}
		for (String segment : relative.split("/")) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
				return null;
			}
		}
		return relative;
	}

	static String folderName(final String filename, final String root) {
		String candidate = root.isEmpty() ? filename : root.substring(0, root.length() - 1);
		int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf('\\'));
		candidate = candidate.substring(slash + 1);
		if (candidate.toLowerCase(Locale.ROOT).endsWith(".zip")) {
			candidate = candidate.substring(0, candidate.length() - 4);
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < candidate.length() && out.length() < 48; i++) {
			char c = candidate.charAt(i);
			out.append(c == '/' || c == '\\' || c == ':' || c == '.' || c < ' ' ? '_' : c);
		}
		String name = out.toString().trim();
		return name.isEmpty() ? "Imported World" : name;
	}

	static String freeFolder(final String name, final Predicate<String> taken) {
		if (!taken.test(name)) {
			return name;
		}
		for (int i = 1; i < 1000; i++) {
			String candidate = name + " (" + i + ")";
			if (!taken.test(candidate)) {
				return candidate;
			}
		}
		return name + " (" + System.currentTimeMillis() + ")";
	}

	private static boolean exists(final String folder) {
		return WebSaves.readFile(SAVES + "/" + folder + "/level.dat") != null;
	}

	private static int dataVersion(final byte[] levelDat) {
		if (levelDat == null) {
			return -1;
		}
		try {
			CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(levelDat), NbtAccounter.unlimitedHeap());
			return root.getCompoundOrEmpty("Data").getIntOr("DataVersion", -1);
		} catch (Throwable e) {
			return -1;
		}
	}

	public static void exportWorld(final String levelId) {
		if (WebSaves.isSplit()) {
			WebSavesIpc.request(WebSavesIpc.exportRequest(levelId));
			return;
		}
		byte[] zip = buildArchive(levelId);
		if (zip == null) {
			fail("nothing to export for " + levelId);
			return;
		}
		download(levelId + ".zip", zip);
	}

	public static byte[] buildArchive(final String levelId) {
		String dir = SAVES + "/" + levelId;
		Map<String, long[]> found = new TreeMap<>();
		WebSaves.walk(dir, found);
		Map<String, byte[]> entries = new LinkedHashMap<>();
		for (String path : found.keySet()) {
			if (path.endsWith("/" + LOCK)) {
				continue;
			}
			byte[] content = WebSaves.readFile(path);
			if (content != null) {
				entries.put(levelId + path.substring(dir.length()), content);
			}
		}
		if (entries.isEmpty()) {
			System.out.println("[26.3-web] nothing to export: " + dir + " is empty");
			return null;
		}
		byte[] zip = zipEntries(entries);
		if (zip != null) {
			System.out.println("[26.3-web] built archive for " + levelId + " ("
				+ entries.size() + " files, " + zip.length + " bytes)");
		}
		return zip;
	}

	public static void download(final String name, final byte[] zip) {
		downloadFile(name, Int8Array.copyFromJavaArray(zip));
	}

	static byte[] zipEntries(final Map<String, byte[]> entries) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.setLevel(1);
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		} catch (Throwable e) {
			System.out.println("[26.3-web] could not write zip: " + e);
			return null;
		}
		return out.toByteArray();
	}

	private static void fail(final String message) {
		System.out.println("[26.3-web] world import/export failed: " + message);
	}

	private static Screen currentScreen() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft == null ? null : minecraft.gui.screen();
	}

	public static List<String> levelIds() {
		Map<String, long[]> found = new TreeMap<>();
		WebSaves.walk(SAVES, found);
		List<String> ids = new ArrayList<>();
		for (String path : found.keySet()) {
			if (path.endsWith("/level.dat")) {
				ids.add(path.substring(SAVES.length() + 1, path.length() - "/level.dat".length()));
			}
		}
		return ids;
	}

	public static byte[] levelDat(final String levelId) {
		return WebSaves.readFile(SAVES + "/" + levelId + "/level.dat");
	}

	public static final String[] SHADOW_FILES = {"data/minecraft/world_gen_settings.dat", "data/minecraft/game_rules.dat"};

	public static byte[] levelFile(final String levelId, final String relative) {
		byte[] bytes = WebSaves.readFile(SAVES + "/" + levelId + "/" + relative);
		return bytes == null || bytes.length > (1 << 20) ? null : bytes;
	}

	private static boolean plainLevelId(final String levelId) {
		return levelId != null && !levelId.isEmpty() && !levelId.startsWith(".")
			&& levelId.indexOf('/') < 0 && levelId.indexOf('\\') < 0 && !levelId.contains("..");
	}

	public static boolean deleteLevel(final String levelId) {
		if (!plainLevelId(levelId) || levelDat(levelId) == null) {
			return false;
		}
		deleteTree(SAVES + "/" + levelId);
		WebSaves.flush();
		return levelDat(levelId) == null;
	}

	public static boolean renameLevel(final String levelId, final String name) {
		byte[] dat = plainLevelId(levelId) ? levelDat(levelId) : null;
		if (dat == null || name == null || name.trim().isEmpty()) {
			return false;
		}
		try {
			net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
				new java.io.ByteArrayInputStream(dat), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
			root.getCompoundOrEmpty("Data").putString("LevelName", name.trim());
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			net.minecraft.nbt.NbtIo.writeCompressed(root, out);
			WebSaves.writeFile(SAVES + "/" + levelId + "/level.dat", out.toByteArray());
			WebSaves.flush();
			return true;
		} catch (java.io.IOException | RuntimeException e) {
			System.out.println("[26.3-web] could not rename " + levelId + ": " + e);
			return false;
		}
	}

	public static String levelName(final byte[] levelDat) {
		try {
			net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
				new java.io.ByteArrayInputStream(levelDat), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
			String name = root.getCompoundOrEmpty("Data").getStringOr("LevelName", "");
			return name.isEmpty() ? null : name;
		} catch (java.io.IOException | RuntimeException e) {
			return null;
		}
	}

	public static void deleteTree(final String path) {
		java.io.File file = new java.io.File(path);
		java.io.File[] children = file.listFiles();
		if (children != null) {
			for (java.io.File child : children) {
				deleteTree(child.getPath().replace('\\', '/'));
			}
		}
		file.delete();
	}

	@JSFunctor
	private interface WorldHandler extends JSObject {
		void world(String filename, Int8Array data);
	}

	@JSBody(params = {"onWorld"}, script = ""
		+ "if (document.getElementById('minecraft-world-file')) { return; }"
		+ "var input = document.createElement('input');"
		+ "input.type = 'file'; input.accept = '.zip,application/zip';"
		+ "input.id = 'minecraft-world-file'; input.style.display = 'none';"
		+ "input.onchange = function() {"
		+ "  var f = input.files && input.files[0];"
		+ "  input.value = '';"
		+ "  if (!f) { return; }"
		+ "  var fr = new FileReader();"
		+ "  fr.onload = function() { onWorld(f.name, new Int8Array(fr.result)); };"
		+ "  fr.readAsArrayBuffer(f);"
		+ "};"
		+ "document.body.appendChild(input);")
	private static native void installWorldInput(WorldHandler onWorld);

	@JSBody(params = {}, script = ""
		+ "var input = document.getElementById('minecraft-world-file');"
		+ "if (!input) { console.error('[26.3-web] world file input missing'); return; }"
		+ "if (navigator.userActivation && !navigator.userActivation.isActive) {"
		+ "  console.error('[26.3-web] the browser will not open a file dialog here: no user activation');"
		+ "}"
		+ "input.click();")
	private static native void clickWorldPicker();

	@JSBody(params = {"name", "data"}, script = ""
		+ "try {"
		+ "  var blob = new Blob([data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength)],"
		+ "    {type: 'application/zip'});"
		+ "  var url = URL.createObjectURL(blob);"
		+ "  var a = document.createElement('a');"
		+ "  a.href = url; a.download = name; a.style.display = 'none';"
		+ "  document.body.appendChild(a);"
		+ "  a.click();"
		+ "  setTimeout(function() { document.body.removeChild(a); URL.revokeObjectURL(url); }, 1000);"
		+ "} catch (e) { console.error('[26.3-web] download failed', e); }")
	private static native void downloadFile(String name, Int8Array data);
}
