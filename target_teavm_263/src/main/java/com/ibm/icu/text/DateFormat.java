// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.ibm.icu.text;

import com.ibm.icu.util.Calendar;
import java.util.Date;

public abstract class DateFormat {
	public abstract String format(Date date);

	public abstract void setCalendar(Calendar calendar);
}
