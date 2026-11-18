// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.properties;

import java.util.Objects;

public final class Property {
	private final String name;
	private final String value;
	private final String signature;

	public Property(String name, String value) {
		this(name, value, null);
	}

	public Property(String name, String value, String signature) {
		this.name = name;
		this.value = value;
		this.signature = signature;
	}

	public String name() {
		return name;
	}

	public String value() {
		return value;
	}

	public String signature() {
		return signature;
	}

	public String getName() {
		return name;
	}

	public String getValue() {
		return value;
	}

	public String getSignature() {
		return signature;
	}

	public boolean hasSignature() {
		return signature != null;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Property)) {
			return false;
		}
		Property that = (Property) o;
		return Objects.equals(name, that.name) && Objects.equals(value, that.value)
				&& Objects.equals(signature, that.signature);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, value, signature);
	}

	@Override
	public String toString() {
		return "Property{name=" + name + ", value=" + value + ", signature=" + signature + "}";
	}
}
