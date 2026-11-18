// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.ibm.icu.util;

import java.util.Locale;

public class ULocale {
	private final Locale locale;

	@SuppressWarnings("deprecation")
	public ULocale(String localeId) {
		String[] parts = (localeId == null ? "" : localeId.replace('-', '_')).split("_", 3);
		this.locale = parts[0].isEmpty()
			? Locale.ROOT
			: new Locale(parts[0], parts.length > 1 ? parts[1] : "", parts.length > 2 ? parts[2] : "");
	}

	public Locale toLocale() {
		return this.locale;
	}

	@Override
	public String toString() {
		return this.locale.toString();
	}
}
