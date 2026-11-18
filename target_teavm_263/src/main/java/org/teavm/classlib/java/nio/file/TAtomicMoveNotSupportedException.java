// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

public class TAtomicMoveNotSupportedException extends TFileSystemException {
	public TAtomicMoveNotSupportedException(String source, String target, String reason) {
		super(source, target, reason);
	}
}
