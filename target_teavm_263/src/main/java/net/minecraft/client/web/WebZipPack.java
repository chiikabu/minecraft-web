// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class WebZipPack {
	private WebZipPack() {
	}

	@Nullable
	public static Map<String, byte[]> unzip(final byte[] archive) {
		try {
			return unzip(archive, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException utf8Error) {
			// Windows Explorer / CP437 zips may contain non-UTF-8 entry names; retry with ISO-8859-1.
			try {
				return unzip(archive, StandardCharsets.ISO_8859_1);
			} catch (Throwable e) {
				System.out.println("[26.3-web] not a readable zip: " + e);
				return null;
			}
		} catch (Throwable e) {
			System.out.println("[26.3-web] not a readable zip: " + e);
			return null;
		}
	}

	@Nullable
	private static Map<String, byte[]> unzip(final byte[] archive, final Charset charset) throws java.io.IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(archive), charset)) {
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
		}
		return entries.isEmpty() ? null : entries;
	}
}
