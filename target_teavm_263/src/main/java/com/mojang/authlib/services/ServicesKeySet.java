// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.services;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public interface ServicesKeySet {
	ServicesKeySet EMPTY = type -> List.of();

	static ServicesKeySet lazy(Supplier<ServicesKeySet> supplier) {
		return type -> supplier.get().keys(type);
	}

	Collection<ServicesKeyInfo> keys(ServicesKeyType type);
}
