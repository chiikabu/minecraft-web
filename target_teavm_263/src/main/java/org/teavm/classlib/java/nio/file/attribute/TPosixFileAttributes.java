// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file.attribute;

import java.util.Set;

public interface TPosixFileAttributes extends TBasicFileAttributes {
	Set<TPosixFilePermission> permissions();
}
