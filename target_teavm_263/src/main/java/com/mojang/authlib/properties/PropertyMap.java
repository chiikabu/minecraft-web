// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.properties;

import com.google.common.collect.ForwardingMultimap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

public class PropertyMap extends ForwardingMultimap<String, Property> {
	public static final PropertyMap EMPTY = new PropertyMap(ImmutableMultimap.of());

	private final Multimap<String, Property> properties;

	public PropertyMap() {
		this.properties = LinkedHashMultimap.create();
	}

	public PropertyMap(Multimap<String, Property> properties) {
		this.properties = properties;
	}

	@Override
	protected Multimap<String, Property> delegate() {
		return properties;
	}
}
