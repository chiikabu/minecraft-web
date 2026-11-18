// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.io;

import org.teavm.classlib.java.lang.TClass;

public class TObjectStreamField {
	private final String name;
	private final TClass<?> type;

	public TObjectStreamField(String name, TClass<?> type) {
		this.name = name;
		this.type = type;
	}

	public TObjectStreamField(String name, TClass<?> type, boolean unshared) {
		this(name, type);
	}

	public String getName() {
		return name;
	}

	public TClass<?> getType() {
		return type;
	}

	public char getTypeCode() {
		return 'L';
	}

	public boolean isPrimitive() {
		return false;
	}

	@Override
	public String toString() {
		return name;
	}
}
