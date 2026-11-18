// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WebPackResources {
	private WebPackResources() {
	}

	public static byte[] rawAsset(String path) {
		if (!path.equals("assets/minecraft/shaders/baked263.bin")) return null;
		try {
			return Files.readAllBytes(Path.of(System.getProperty("shaderbake.bin")));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
