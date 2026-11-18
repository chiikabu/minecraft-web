// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib;

import java.util.Objects;
import java.util.UUID;

import com.mojang.authlib.properties.PropertyMap;

public class GameProfile {
	private final UUID id;
	private final String name;
	private final PropertyMap properties;

	public GameProfile(UUID id, String name) {
		this(id, name, new PropertyMap());
	}

	public GameProfile(UUID id, String name, PropertyMap properties) {
		this.id = Objects.requireNonNull(id, "id");
		this.name = Objects.requireNonNull(name, "name");
		this.properties = Objects.requireNonNull(properties, "properties");
	}

	public UUID id() {
		return id;
	}

	public String name() {
		return name;
	}

	public PropertyMap properties() {
		return properties;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public PropertyMap getProperties() {
		return properties;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof GameProfile)) {
			return false;
		}
		GameProfile that = (GameProfile) o;
		return Objects.equals(id, that.id) && Objects.equals(name, that.name);
	}

	@Override
	public int hashCode() {
		int result = id != null ? id.hashCode() : 0;
		result = 31 * result + (name != null ? name.hashCode() : 0);
		return result;
	}

	@Override
	public String toString() {
		return "GameProfile{id=" + id + ", name=" + name + ", properties=" + properties + ", legacy=false}";
	}
}
