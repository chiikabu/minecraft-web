// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

import org.teavm.classlib.java.security.TPermission;

public final class TNetPermission extends TPermission {
	private final String actions;

	public TNetPermission(String name) {
		this(name, "");
	}

	public TNetPermission(String name, String actions) {
		super(name);
		this.actions = actions;
	}

	@Override
	public boolean implies(TPermission permission) {
		return permission != null && getName().equals(permission.getName());
	}

	@Override
	public String getActions() {
		return actions;
	}
}
