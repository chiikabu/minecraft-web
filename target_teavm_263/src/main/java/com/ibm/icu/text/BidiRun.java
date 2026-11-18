// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.ibm.icu.text;

public class BidiRun {
	private final int start;
	private final int length;
	private final boolean odd;

	BidiRun(int start, int length, boolean odd) {
		this.start = start;
		this.length = length;
		this.odd = odd;
	}

	public int getStart() {
		return this.start;
	}

	public int getLength() {
		return this.length;
	}

	public boolean isOddRun() {
		return this.odd;
	}
}
