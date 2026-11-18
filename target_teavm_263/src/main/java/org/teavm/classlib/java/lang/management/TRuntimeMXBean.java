// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.management;

import java.util.Collections;
import java.util.List;

public class TRuntimeMXBean {
	static final TRuntimeMXBean INSTANCE = new TRuntimeMXBean();

	public List<String> getInputArguments() { return Collections.emptyList(); }
	public String getName() { return "browser@teavm"; }
	public long getStartTime() { return 0L; }
	public long getUptime() { return 0L; }
}
