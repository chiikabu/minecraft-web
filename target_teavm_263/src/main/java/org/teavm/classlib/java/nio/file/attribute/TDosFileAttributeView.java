// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file.attribute;

import java.io.IOException;

public interface TDosFileAttributeView extends TFileAttributeView {
	@Override
	default String name() {
		return "dos";
	}

	void setReadOnly(boolean value) throws IOException;
}
