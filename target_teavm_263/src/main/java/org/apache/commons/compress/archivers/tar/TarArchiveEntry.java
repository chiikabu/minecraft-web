// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.apache.commons.compress.archivers.tar;

import java.io.File;

public class TarArchiveEntry {
	public TarArchiveEntry(File file, String entryName) {
		throw new UnsupportedOperationException("tar archives are not available in the browser");
	}

	public String getName() {
		throw new UnsupportedOperationException("tar archives are not available in the browser");
	}

	public boolean isDirectory() {
		throw new UnsupportedOperationException("tar archives are not available in the browser");
	}
}
