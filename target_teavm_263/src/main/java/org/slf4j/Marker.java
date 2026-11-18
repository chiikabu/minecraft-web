// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.slf4j;

import java.io.Serializable;

public interface Marker extends Serializable {

	String ANY_MARKER = "*";
	String ANY_NON_NULL_MARKER = "+";

	String getName();

	void add(Marker reference);

	boolean remove(Marker reference);

	boolean contains(Marker other);

	boolean contains(String name);
}
