// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.ibm.icu.text;

import com.ibm.icu.util.Calendar;
import com.ibm.icu.util.ULocale;
import java.util.Date;

public class SimpleDateFormat extends DateFormat {
	private final java.text.SimpleDateFormat delegate;

	public SimpleDateFormat(String pattern, ULocale locale) {
		this.delegate = new java.text.SimpleDateFormat(pattern, locale.toLocale());
	}

	@Override
	public String format(Date date) {
		return this.delegate.format(date);
	}

	@Override
	public void setCalendar(Calendar calendar) {
		this.delegate.setCalendar(calendar.toJavaCalendar());
	}

	@Override
	public String toString() {
		return this.delegate.toPattern();
	}
}
