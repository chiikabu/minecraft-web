// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.slf4j;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.helpers.BasicMarker;

public class MarkerFactory {

	private static final Map<String, Marker> MARKER_MAP = new HashMap<String, Marker>();

	private MarkerFactory() {
	}

	public static Marker getMarker(String name) {
		Marker marker = MARKER_MAP.get(name);
		if (marker == null) {
			marker = new BasicMarker(name);
			MARKER_MAP.put(name, marker);
		}
		return marker;
	}
}
