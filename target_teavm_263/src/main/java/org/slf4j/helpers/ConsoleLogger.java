// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.slf4j.helpers;

import org.slf4j.Logger;
import org.slf4j.Marker;

public class ConsoleLogger implements Logger {
	private static final java.io.PrintStream RAW_OUT = System.out;
	private static final java.io.PrintStream RAW_ERR = System.err;

	private final String name;

	public ConsoleLogger(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		return name;
	}

	private boolean enabled(String level) {
		if ("TRACE".equals(level)) {
			return isTraceEnabled();
		}
		if ("DEBUG".equals(level)) {
			return isDebugEnabled();
		}
		if ("INFO".equals(level)) {
			return isInfoEnabled();
		}
		if ("WARN".equals(level)) {
			return isWarnEnabled();
		}
		if ("ERROR".equals(level)) {
			return isErrorEnabled();
		}
		return true;
	}

	private void print(String level, String msg, Throwable t) {
		if (!enabled(level)) {
			return;
		}
		if (org.apache.logging.log4j.LogManager.isFiltered(name, msg, t)) return;
		java.io.PrintStream out = "ERROR".equals(level) || "FATAL".equals(level) ? RAW_ERR : RAW_OUT;
		out.println("[" + level + "] " + name + " - " + msg);
		printChain(out, t);
	}

	private static void printChain(java.io.PrintStream out, Throwable t) {
		for (int depth = 0; t != null && depth < 12; depth++) {
			out.println(depth == 0 ? String.valueOf(t) : "Caused by: " + t);
			StackTraceElement[] frames = t.getStackTrace();
			if (frames != null) {
				for (int i = 0; i < frames.length && i < 24; i++) {
					out.println("\tat " + frames[i]);
				}
			}

			if (t instanceof net.minecraft.ReportedException reported) {
				out.println(reported.getReport().getDetails());
			}
			Throwable cause = t.getCause();
			t = cause == t ? null : cause;
		}
	}

	private void log(String level, String format, Object arg) {
		FormattingTuple ft = MessageFormatter.format(format, arg);
		print(level, ft.getMessage(), ft.getThrowable());
	}

	private void log(String level, String format, Object arg1, Object arg2) {
		FormattingTuple ft = MessageFormatter.format(format, arg1, arg2);
		print(level, ft.getMessage(), ft.getThrowable());
	}

	protected final void log(String level, String format, Object... arguments) {
		FormattingTuple ft = MessageFormatter.arrayFormat(format, arguments);
		print(level, ft.getMessage(), ft.getThrowable());
	}

	@Override
	public boolean isTraceEnabled() {
		return false;
	}

	@Override
	public void trace(String msg) {
		print("TRACE", msg, null);
	}

	@Override
	public void trace(String format, Object arg) {
		log("TRACE", format, arg);
	}

	@Override
	public void trace(String format, Object arg1, Object arg2) {
		log("TRACE", format, arg1, arg2);
	}

	@Override
	public void trace(String format, Object... arguments) {
		log("TRACE", format, arguments);
	}

	@Override
	public void trace(String msg, Throwable t) {
		print("TRACE", msg, t);
	}

	@Override
	public boolean isTraceEnabled(Marker marker) {
		return false;
	}

	@Override
	public void trace(Marker marker, String msg) {
		trace(msg);
	}

	@Override
	public void trace(Marker marker, String format, Object arg) {
		trace(format, arg);
	}

	@Override
	public void trace(Marker marker, String format, Object arg1, Object arg2) {
		trace(format, arg1, arg2);
	}

	@Override
	public void trace(Marker marker, String format, Object... argArray) {
		trace(format, argArray);
	}

	@Override
	public void trace(Marker marker, String msg, Throwable t) {
		trace(msg, t);
	}

	@Override
	public boolean isDebugEnabled() {
		return false;
	}

	@Override
	public void debug(String msg) {
		print("DEBUG", msg, null);
	}

	@Override
	public void debug(String format, Object arg) {
		log("DEBUG", format, arg);
	}

	@Override
	public void debug(String format, Object arg1, Object arg2) {
		log("DEBUG", format, arg1, arg2);
	}

	@Override
	public void debug(String format, Object... arguments) {
		log("DEBUG", format, arguments);
	}

	@Override
	public void debug(String msg, Throwable t) {
		print("DEBUG", msg, t);
	}

	@Override
	public boolean isDebugEnabled(Marker marker) {
		return false;
	}

	@Override
	public void debug(Marker marker, String msg) {
		debug(msg);
	}

	@Override
	public void debug(Marker marker, String format, Object arg) {
		debug(format, arg);
	}

	@Override
	public void debug(Marker marker, String format, Object arg1, Object arg2) {
		debug(format, arg1, arg2);
	}

	@Override
	public void debug(Marker marker, String format, Object... argArray) {
		debug(format, argArray);
	}

	@Override
	public void debug(Marker marker, String msg, Throwable t) {
		debug(msg, t);
	}

	@Override
	public boolean isInfoEnabled() {
		return true;
	}

	@Override
	public void info(String msg) {
		print("INFO", msg, null);
	}

	@Override
	public void info(String format, Object arg) {
		log("INFO", format, arg);
	}

	@Override
	public void info(String format, Object arg1, Object arg2) {
		log("INFO", format, arg1, arg2);
	}

	@Override
	public void info(String format, Object... arguments) {
		log("INFO", format, arguments);
	}

	@Override
	public void info(String msg, Throwable t) {
		print("INFO", msg, t);
	}

	@Override
	public boolean isInfoEnabled(Marker marker) {
		return true;
	}

	@Override
	public void info(Marker marker, String msg) {
		info(msg);
	}

	@Override
	public void info(Marker marker, String format, Object arg) {
		info(format, arg);
	}

	@Override
	public void info(Marker marker, String format, Object arg1, Object arg2) {
		info(format, arg1, arg2);
	}

	@Override
	public void info(Marker marker, String format, Object... argArray) {
		info(format, argArray);
	}

	@Override
	public void info(Marker marker, String msg, Throwable t) {
		info(msg, t);
	}

	@Override
	public boolean isWarnEnabled() {
		return true;
	}

	@Override
	public void warn(String msg) {
		print("WARN", msg, null);
	}

	@Override
	public void warn(String format, Object arg) {
		log("WARN", format, arg);
	}

	@Override
	public void warn(String format, Object... arguments) {
		log("WARN", format, arguments);
	}

	@Override
	public void warn(String format, Object arg1, Object arg2) {
		log("WARN", format, arg1, arg2);
	}

	@Override
	public void warn(String msg, Throwable t) {
		print("WARN", msg, t);
	}

	@Override
	public boolean isWarnEnabled(Marker marker) {
		return true;
	}

	@Override
	public void warn(Marker marker, String msg) {
		warn(msg);
	}

	@Override
	public void warn(Marker marker, String format, Object arg) {
		warn(format, arg);
	}

	@Override
	public void warn(Marker marker, String format, Object arg1, Object arg2) {
		warn(format, arg1, arg2);
	}

	@Override
	public void warn(Marker marker, String format, Object... argArray) {
		warn(format, argArray);
	}

	@Override
	public void warn(Marker marker, String msg, Throwable t) {
		warn(msg, t);
	}

	@Override
	public boolean isErrorEnabled() {
		return true;
	}

	@Override
	public void error(String msg) {
		print("ERROR", msg, null);
	}

	@Override
	public void error(String format, Object arg) {
		log("ERROR", format, arg);
	}

	@Override
	public void error(String format, Object arg1, Object arg2) {
		log("ERROR", format, arg1, arg2);
	}

	@Override
	public void error(String format, Object... arguments) {
		log("ERROR", format, arguments);
	}

	@Override
	public void error(String msg, Throwable t) {
		print("ERROR", msg, t);
	}

	@Override
	public boolean isErrorEnabled(Marker marker) {
		return true;
	}

	@Override
	public void error(Marker marker, String msg) {
		error(msg);
	}

	@Override
	public void error(Marker marker, String format, Object arg) {
		error(format, arg);
	}

	@Override
	public void error(Marker marker, String format, Object arg1, Object arg2) {
		error(format, arg1, arg2);
	}

	@Override
	public void error(Marker marker, String format, Object... argArray) {
		error(format, argArray);
	}

	@Override
	public void error(Marker marker, String msg, Throwable t) {
		error(msg, t);
	}
}
