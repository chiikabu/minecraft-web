// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file.spi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.teavm.classlib.java.nio.file.TFileSystem;

public abstract class TFileSystemProvider {
	private static final List<TFileSystemProvider> INSTALLED = List.of(new JarStubProvider());

	public static List<TFileSystemProvider> installedProviders() {
		return INSTALLED;
	}

	public abstract String getScheme();

	public TFileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
		throw new IOException("zip filesystem unavailable in browser (VFS stage pending)");
	}

	public Path getPath(java.net.URI uri) {
		throw new UnsupportedOperationException("zip filesystem unavailable in browser");
	}

	static final class JarStubProvider extends TFileSystemProvider {
		@Override
		public String getScheme() {
			return "jar";
		}
	}
}
