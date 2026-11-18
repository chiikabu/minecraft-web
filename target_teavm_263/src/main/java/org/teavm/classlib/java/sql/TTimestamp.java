// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.sql;

public class TTimestamp extends java.util.Date {
	private int nanos;

	public TTimestamp(long time) {
		super(time);
	}

	public int getNanos() { return nanos; }
	public void setNanos(int n) { this.nanos = n; }
	public static TTimestamp valueOf(String s) { throw new IllegalArgumentException("unsupported in browser"); }
}
