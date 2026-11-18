// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

public final class TAllPermission extends TPermission {
	public TAllPermission() {
		super("<all permissions>");
	}

	public TAllPermission(String name, String actions) {
		super("<all permissions>");
	}

	@Override
	public boolean implies(TPermission permission) {
		return true;
	}

	@Override
	public String getActions() {
		return "<all actions>";
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof TAllPermission;
	}

	@Override
	public int hashCode() {
		return 1;
	}
}
