// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.ibm.icu.text;

import java.util.Comparator;
import java.util.Locale;

public class Collator implements Comparator<Object> {
	public static Collator getInstance(Locale locale) {
		return new Collator();
	}

	@Override
	public int compare(Object a, Object b) {
		String left = String.valueOf(a);
		String right = String.valueOf(b);
		int byCase = left.compareToIgnoreCase(right);
		return byCase != 0 ? byCase : left.compareTo(right);
	}
}
