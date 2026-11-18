// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import java.util.Collections;
import java.util.Enumeration;

public abstract class TPermissionCollection {
	private boolean readOnly;

	public abstract void add(TPermission permission);

	public abstract boolean implies(TPermission permission);

	public abstract Enumeration<TPermission> elements();

	public void setReadOnly() {
		readOnly = true;
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	@Override
	public String toString() {
		return getClass().getName() + " ()";
	}

	protected static Enumeration<TPermission> emptyElements() {
		return Collections.emptyEnumeration();
	}
}
