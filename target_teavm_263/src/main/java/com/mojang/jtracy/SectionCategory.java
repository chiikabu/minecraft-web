// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.jtracy;

public final class SectionCategory {
	private static final Section SECTION = new Section();

	public Section enterSection(String name) { return SECTION; }
}
