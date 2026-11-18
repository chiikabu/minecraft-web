// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

public class TDirectoryNotEmptyException extends TFileSystemException {
	public TDirectoryNotEmptyException(final String file) {
		super(file);
	}
}
