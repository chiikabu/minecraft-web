// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.ibm.icu.text;

public class Bidi {
	private final String text;

	public Bidi(String text, int paragraphLevel) {
		this.text = text;
	}

	public void setReorderingMode(int mode) {
	}

	public int countRuns() {
		return 1;
	}

	public BidiRun getVisualRun(int index) {
		return new BidiRun(0, this.text.length(), false);
	}

	public String writeReordered(int options) {
		return this.text;
	}
}
