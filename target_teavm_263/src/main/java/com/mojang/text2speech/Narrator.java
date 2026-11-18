// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.text2speech;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public interface Narrator {
	Logger LOGGER = LogUtils.getLogger();
	Narrator EMPTY = new Narrator() {
	};

	default void say(final String message, final boolean interrupt, final float volume) {
	}

	default void clear() {
	}

	default boolean active() {
		return false;
	}

	default void destroy() {
	}

	static Narrator getNarrator() {
		return EMPTY;
	}
}
