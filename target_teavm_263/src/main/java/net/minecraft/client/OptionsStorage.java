// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.browser.Storage;
import org.teavm.jso.browser.Window;

public final class OptionsStorage {
	private static final String KEY_PREFIX = "mc:";

	private OptionsStorage() {
	}

	@Nullable
	public static String read(final File file) {
		String stored = readStored(file);
		if (stored != null) {
			return stored;
		}
		try {
			return Files.readString(file.toPath(), StandardCharsets.UTF_8);
		} catch (IOException var1) {
			return null;
		}
	}

	public static void write(final File file, final String contents) {
		Storage storage = localStorage();
		if (storage != null) {
			storage.setItem(key(file), contents);
		}
	}

	@Nullable
	public static byte[] readBytes(final File file) {
		String stored = readStored(file);
		if (stored != null) {
			return java.util.Base64.getDecoder().decode(stored);
		}
		try {
			return Files.readAllBytes(file.toPath());
		} catch (IOException var1) {
			return null;
		}
	}

	public static void writeBytes(final File file, final byte[] contents) {
		write(file, java.util.Base64.getEncoder().encodeToString(contents));
	}

	private static String key(final File file) {
		return KEY_PREFIX + file.getPath();
	}

	@Nullable
	private static String readStored(final File file) {
		Storage storage = localStorage();
		try {
			return storage == null ? null : storage.getItem(key(file));
		} catch (Throwable var1) {
			return null;
		}
	}

	@Nullable
	private static Storage localStorage() {
		try {
			return Window.current().getLocalStorage();
		} catch (Throwable var1) {
			System.out.println("[web-stub] localStorage unavailable, options will not persist");
			return null;
		}
	}
}
