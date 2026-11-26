// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebPacks {
	private static final String DIR = "/resourcepacks";

	private WebPacks() {
	}

	public static void install() {
		installFileInput(WebPacks::accept);
		net.minecraft.client.gui.screens.packs.PackSelectionScreen.openPackFolderOverride = WebPacks::choosePack;
		net.minecraft.client.gui.screens.packs.PackSelectionScreen.openPackFolderTitleOverride =
			net.minecraft.network.chat.Component.literal("Add Pack...");
		WebWorlds.install();
		CreditsScreen.install();
		net.minecraft.client.web.shaderpack.ShaderPacks.install();
		net.minecraft.client.web.mods.WebMods.install();
		net.minecraft.client.gui.screens.TitleScreen.modsScreen = net.minecraft.client.web.mods.ModsScreen::create;
	}

	public static void choosePack() {
		clickPicker();
	}

	/** Install and enable a resource pack zip directly from raw bytes (e.g., downloaded from Modrinth). Returns null on success or an error message. */
	public static String installResourcePack(final String filename, final byte[] bytes) {
		String name = sanitize(filename);
		String problem = whyNotAPack(bytes);
		if (SHADER_PACK.equals(problem)) {
			String err = net.minecraft.client.web.shaderpack.ShaderPacks.accept(filename, bytes);
			if (err == null) {
				toast("Added shader pack " + filename, "Turn it on in Options > Video Settings > Shader Packs.");
			}
			return err;
		}
		if (problem != null) {
			toast("Could not add " + filename, problem);
			return problem;
		}
		try {
			WebSaves.mkdirs(DIR);
			WebSaves.writeFile(DIR + "/" + name, bytes);
		} catch (Throwable e) {
			toast("Could not add " + filename, "The browser refused to store it (out of space?)");
			return "Storage write failed";
		}
		System.out.println("[26.3-web] added pack " + name + " to " + DIR + " (" + bytes.length + " bytes)");
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null) {
			PackRepository repository = minecraft.getResourcePackRepository();
			repository.reload();
			String id = "file/" + name;
			if (repository.getAvailableIds().contains(id)) {
				java.util.List<String> selected = new java.util.ArrayList<>(repository.getSelectedIds());
				if (!selected.contains(id)) {
					selected.add(id);
				}
				repository.setSelected(selected);
				java.util.List<String> previous = java.util.List.copyOf(minecraft.options.resourcePacks);
				minecraft.options.updateResourcePacks(repository);
				if (previous.equals(minecraft.options.resourcePacks)) {
					minecraft.reloadResourcePacks();
				}
			}
			toast("Installed resource pack", name);
		}
		return null;
	}

	private static void accept(final String filename, final Int8Array data) {
		String name = sanitize(filename);
		byte[] bytes = data.copyToJavaArray();
		if (net.minecraft.client.web.mods.WebMods.looksLikeMod(filename, bytes)) {
			net.minecraft.client.web.mods.WebMods.accept(filename, bytes);
			return;
		}
		String problem = whyNotAPack(bytes);
		if (SHADER_PACK.equals(problem)) {
			if (net.minecraft.client.web.shaderpack.ShaderPacks.accept(filename, bytes) == null) {
				toast("Added shader pack " + filename, "Turn it on in Options > Video Settings > Shader Packs.");
			}
			return;
		}
		if (problem != null) {
			System.out.println("[26.3-web] " + filename + " was not added: " + problem);
			toast("Could not add " + filename, problem);
			return;
		}
		net.minecraft.client.gui.screens.packs.PackSelectionScreen screen = openPackScreen();
		String dir = screen == null ? DIR : screen.packDir().toString();
		try {
			WebSaves.mkdirs(dir);
			WebSaves.writeFile(dir + "/" + name, bytes);
		} catch (Throwable e) {
			System.out.println("[26.3-web] could not store pack " + name + ": " + e);
			toast("Could not add " + filename, "The browser refused to store it (out of space?)");
			return;
		}
		System.out.println("[26.3-web] added pack " + name + " to " + dir + " (" + bytes.length + " bytes)");

		if (screen != null) {
			screen.reload();
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return;
		}
		PackRepository repository = minecraft.getResourcePackRepository();
		repository.reload();
		String id = "file/" + name;
		if (!repository.getAvailableIds().contains(id)) {
			System.out.println("[26.3-web] " + name + " has no valid pack.mcmeta; left disabled");
			return;
		}
		java.util.List<String> selected = new java.util.ArrayList<>(repository.getSelectedIds());
		if (!selected.contains(id)) {
			selected.add(id);
		}
		repository.setSelected(selected);
		java.util.List<String> previous = java.util.List.copyOf(minecraft.options.resourcePacks);
		minecraft.options.updateResourcePacks(repository);
		if (previous.equals(minecraft.options.resourcePacks)) {
			minecraft.reloadResourcePacks();
		}
	}

	private static void toast(final String title, final String message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return;
		}
		net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(
			minecraft.gui.toastManager(),
			net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PACK_LOAD_FAILURE,
			net.minecraft.network.chat.Component.literal(title),
			net.minecraft.network.chat.Component.literal(message));
	}

	private static final String SHADER_PACK = "This is an OptiFine/Iris shader pack. Add it in Video Settings > Shader Packs.";

	static String whyNotAPack(final byte[] zip) {
		java.util.List<String> names = zipEntryNames(zip);
		if (names == null) {
			return "Not a .zip file. Packs must be zipped.";
		}
		if (names.isEmpty()) {
			return null;
		}
		if (names.contains("pack.mcmeta")
			|| !net.minecraft.server.packs.FilePackResources.findRootPrefix(names).isEmpty()) {
			return null;
		}
		if (net.minecraft.client.web.shaderpack.ShaderPack.findRoot(names) != null) {
			return SHADER_PACK;
		}
		for (String name : names) {
			if (name.equals("fabric.mod.json") || name.equals("META-INF/mods.toml") || name.endsWith(".class")) {
				return "This is a mod, not a pack.";
			}
			if (name.equals("manifest.json")) {
				return "This is a Bedrock Edition pack. Only Java Edition packs work here.";
			}
		}
		return "No pack.mcmeta inside the .zip.";
	}

	private static java.util.List<String> zipEntryNames(final byte[] zip) {
		int end = -1;
		for (int i = zip.length - 22, stop = Math.max(0, zip.length - 22 - 65535); i >= stop; i--) {
			if (zip[i] == 0x50 && zip[i + 1] == 0x4b && zip[i + 2] == 5 && zip[i + 3] == 6) {
				end = i;
				break;
			}
		}
		if (end < 0) {
			return null;
		}
		int count = u16(zip, end + 10);
		long offset = u32(zip, end + 16);
		java.util.List<String> names = new java.util.ArrayList<>();
		if (count == 0xFFFF || offset == 0xFFFFFFFFL) {
			return names;
		}
		int at = (int) offset;
		for (int i = 0; i < count; i++) {
			if (at < 0 || at + 46 > zip.length || u32(zip, at) != 0x02014b50L) {
				return new java.util.ArrayList<>();
			}
			int nameLength = u16(zip, at + 28);
			if (at + 46 + nameLength > zip.length) {
				return new java.util.ArrayList<>();
			}
			names.add(new String(zip, at + 46, nameLength, java.nio.charset.StandardCharsets.UTF_8));
			at += 46 + nameLength + u16(zip, at + 30) + u16(zip, at + 32);
		}
		return names;
	}

	private static int u16(final byte[] b, final int at) {
		return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
	}

	private static long u32(final byte[] b, final int at) {
		return (u16(b, at) | (long) u16(b, at + 2) << 16) & 0xFFFFFFFFL;
	}

	private static net.minecraft.client.gui.screens.packs.PackSelectionScreen openPackScreen() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return null;
		}
		return minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.packs.PackSelectionScreen screen
			? screen : null;
	}

	private static String sanitize(final String filename) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < filename.length() && out.length() < 64; i++) {
			char c = filename.charAt(i);
			out.append(c == '/' || c == '\\' || c == ':' || c < ' ' ? '_' : c);
		}
		String name = out.toString();
		return name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? name : name + ".zip";
	}

	@JSFunctor
	private interface PackHandler extends JSObject {
		void pack(String filename, Int8Array data);
	}

	@JSBody(params = {"onPack"}, script = ""
		+ "if (document.getElementById('minecraft-pack-file')) { return; }"
		+ "var input = document.createElement('input');"
		+ "input.type = 'file'; input.accept = '.zip,application/zip';"
		+ "input.id = 'minecraft-pack-file'; input.style.display = 'none';"
		+ "input.onchange = function() {"
		+ "  var f = input.files && input.files[0];"
		+ "  input.value = '';"
		+ "  if (!f) { return; }"
		+ "  var fr = new FileReader();"
		+ "  fr.onload = function() { onPack(f.name, new Int8Array(fr.result)); };"
		+ "  fr.readAsArrayBuffer(f);"
		+ "};"
		+ "document.body.appendChild(input);"
		+ "document.addEventListener('dragover', function(e) { e.preventDefault(); });"
		+ "document.addEventListener('drop', function(e) {"
		+ "  e.preventDefault();"
		+ "  var files = e.dataTransfer && e.dataTransfer.files;"
		+ "  if (files) { Array.prototype.forEach.call(files, function(f) {"
		+ "    var fr = new FileReader();"
		+ "    fr.onload = function() { onPack(f.name, new Int8Array(fr.result)); };"
		+ "    fr.readAsArrayBuffer(f);"
		+ "  }); }"
		+ "});")
	private static native void installFileInput(PackHandler onPack);

	@JSBody(params = {}, script = ""
		+ "var input = document.getElementById('minecraft-pack-file');"
		+ "if (!input) { console.error('[26.3-web] pack file input missing'); return; }"
		+ "if (navigator.userActivation && !navigator.userActivation.isActive) {"
		+ "  console.error('[26.3-web] the browser will not open a file dialog here: no user activation');"
		+ "}"
		+ "input.click();")
	private static native void clickPicker();
}
