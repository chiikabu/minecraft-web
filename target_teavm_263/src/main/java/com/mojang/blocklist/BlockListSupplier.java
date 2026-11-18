// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.blocklist;

import java.util.function.Predicate;

public interface BlockListSupplier {
	Predicate<String> createBlockList();
}
