// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.slf4j.helpers;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Marker;

public class BasicMarker implements Marker {
	private static final long serialVersionUID = 1L;

	private final String name;
	private final List<Marker> referenceList = new ArrayList<Marker>();

	public BasicMarker(String name) {
		if (name == null) {
			throw new IllegalArgumentException("A marker name cannot be null");
		}
		this.name = name;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void add(Marker reference) {
		if (reference == null) {
			throw new IllegalArgumentException("A null value cannot be added to a Marker as reference.");
		}
		if (this.contains(reference)) {
			return;
		}
		if (reference.contains(this)) {
			return;
		}
		referenceList.add(reference);
	}

	@Override
	public boolean remove(Marker reference) {
		return referenceList.remove(reference);
	}

	@Override
	public boolean contains(Marker other) {
		if (other == null) {
			throw new IllegalArgumentException("Other cannot be null");
		}
		if (this.equals(other)) {
			return true;
		}
		for (Marker ref : referenceList) {
			if (ref.contains(other)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean contains(String name) {
		if (name == null) {
			throw new IllegalArgumentException("Other cannot be null");
		}
		if (this.name.equals(name)) {
			return true;
		}
		for (Marker ref : referenceList) {
			if (ref.contains(name)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof Marker)) {
			return false;
		}
		return name.equals(((Marker) obj).getName());
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	@Override
	public String toString() {
		return getName();
	}
}
