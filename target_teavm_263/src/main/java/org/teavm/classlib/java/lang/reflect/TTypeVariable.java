// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.lang.reflect;

import java.lang.reflect.Type;

public interface TTypeVariable<D extends TGenericDeclaration> extends Type {
	Type[] getBounds();
	D getGenericDeclaration();
	String getName();
}
