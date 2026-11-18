// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

public class TInvalidPathException extends IllegalArgumentException {
	public TInvalidPathException(String input, String reason) {
		super(reason + ": " + input);
	}
}
