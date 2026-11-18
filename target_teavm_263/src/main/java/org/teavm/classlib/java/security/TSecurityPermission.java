// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

public final class TSecurityPermission extends TPermission {
	private final String actions;

	public TSecurityPermission(String name) {
		this(name, "");
	}

	public TSecurityPermission(String name, String actions) {
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
