// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.nio.file;

public final class TStandardWatchEventKinds {
	private TStandardWatchEventKinds() {
	}

	public static final TWatchEvent.Kind<TPath> ENTRY_CREATE = new StdKind<>("ENTRY_CREATE");
	public static final TWatchEvent.Kind<TPath> ENTRY_DELETE = new StdKind<>("ENTRY_DELETE");
	public static final TWatchEvent.Kind<TPath> ENTRY_MODIFY = new StdKind<>("ENTRY_MODIFY");
	public static final TWatchEvent.Kind<Object> OVERFLOW = new StdKind<>("OVERFLOW");

	private static final class StdKind<T> implements TWatchEvent.Kind<T> {
		private final String name;

		StdKind(String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public Class<T> type() {
			throw new UnsupportedOperationException();
		}

		@Override
		public String toString() {
			return name;
		}
	}
}
