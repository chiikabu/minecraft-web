// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.ibm.icu.util;

public class TimeZone {
	public static final TimeZone UNKNOWN_ZONE = new TimeZone(null, "Etc/Unknown");

	private final java.util.TimeZone delegate;
	private final String id;

	private TimeZone(java.util.TimeZone delegate, String id) {
		this.delegate = delegate;
		this.id = id;
	}

	public static TimeZone getTimeZone(String id) {
		java.util.TimeZone zone = java.util.TimeZone.getTimeZone(id);
		return zone == null || !zone.getID().equals(id) ? UNKNOWN_ZONE : new TimeZone(zone, id);
	}

	public String getID() {
		return this.id;
	}

	public java.util.TimeZone toJavaTimeZone() {
		return this.delegate;
	}
}
