// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

import java.io.IOException;

public interface TWatchable {
	TWatchKey register(TWatchService watcher, TWatchEvent.Kind<?>... events) throws IOException;
}
