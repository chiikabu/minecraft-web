// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class WebZipPack {
	private WebZipPack() {
	}

	@Nullable
	public static Map<String, byte[]> unzip(final byte[] archive) {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(archive))) {
			java.util.zip.ZipEntry entry;
			byte[] buffer = new byte[8192];
			while ((entry = in.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				int n;
				while ((n = in.read(buffer, 0, buffer.length)) > 0) {
					out.write(buffer, 0, n);
				}
				entries.put(entry.getName(), out.toByteArray());
			}
		} catch (Throwable e) {
			System.out.println("[26.3-web] not a readable zip: " + e);
			return null;
		}
		return entries.isEmpty() ? null : entries;
	}
}
