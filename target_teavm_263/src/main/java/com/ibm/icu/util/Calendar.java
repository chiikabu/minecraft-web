// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.ibm.icu.util;

public class Calendar {
	private final java.util.Calendar delegate;

	private Calendar(java.util.Calendar delegate) {
		this.delegate = delegate;
	}

	public static Calendar getInstance(ULocale locale) {
		return new Calendar(java.util.Calendar.getInstance(locale.toLocale()));
	}

	public static Calendar getInstance(TimeZone zone, ULocale locale) {
		return new Calendar(java.util.Calendar.getInstance(zone.toJavaTimeZone(), locale.toLocale()));
	}

	public java.util.Calendar toJavaCalendar() {
		return this.delegate;
	}
}
