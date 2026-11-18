// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.io.IOException;

public abstract class TFileStore {
	public abstract long getUsableSpace() throws IOException;
	public abstract long getTotalSpace() throws IOException;
	public abstract String name();
}
