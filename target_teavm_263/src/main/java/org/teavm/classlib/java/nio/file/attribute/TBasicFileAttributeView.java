// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file.attribute;

import java.io.IOException;

public interface TBasicFileAttributeView extends TFileAttributeView {
	@Override
	String name();

	TBasicFileAttributes readAttributes() throws IOException;

	void setTimes(TFileTime lastModifiedTime, TFileTime lastAccessTime, TFileTime createTime) throws IOException;
}
