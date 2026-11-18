// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.reflect;

import java.lang.reflect.Type;

public interface TWildcardType extends Type {
	Type[] getUpperBounds();
	Type[] getLowerBounds();
}
