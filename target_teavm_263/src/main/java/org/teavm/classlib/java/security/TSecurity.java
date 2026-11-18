// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

public final class TSecurity {
	private static final TProvider[] NONE = new TProvider[0];

	private TSecurity() {
	}

	public static String getProperty(String key) {
		return null;
	}

	public static void setProperty(String key, String value) {
	}

	public static TProvider[] getProviders() {
		return NONE;
	}

	public static TProvider[] getProviders(String filter) {
		return NONE;
	}

	public static TProvider getProvider(String name) {
		return null;
	}

	public static int addProvider(TProvider provider) {
		return -1;
	}

	public static int insertProviderAt(TProvider provider, int position) {
		return -1;
	}

	public static void removeProvider(String name) {
	}

	public static java.util.Set<String> getAlgorithms(String serviceName) {
		return java.util.Collections.emptySet();
	}
}
