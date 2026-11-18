// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.security;

import java.util.Properties;

public class TProvider extends Properties {
	private final String name;
	private final String info;

	protected TProvider(String name, String versionStr, String info) {
		this.name = name;
		this.info = info;
	}

	public String getName() {
		return name;
	}

	public String getInfo() {
		return info;
	}

	public double getVersion() {
		return 0.0;
	}

	public String getVersionStr() {
		return "0";
	}

	public Service getService(String type, String algorithm) {
		return null;
	}

	protected void putService(Service service) {
	}

	protected void removeService(Service service) {
	}

	public TProvider configure(String configArg) {
		return this;
	}

	public boolean isConfigured() {
		return true;
	}

	public java.util.Set<Service> getServices() {
		return java.util.Collections.emptySet();
	}

	@Override
	public String toString() {
		return name;
	}

	public static class Service {
		private final TProvider provider;
		private final String type;
		private final String algorithm;
		private final String className;

		public Service(TProvider provider, String type, String algorithm, String className,
				java.util.List<String> aliases, java.util.Map<String, String> attributes) {
			this.provider = provider;
			this.type = type;
			this.algorithm = algorithm;
			this.className = className;
		}

		public final TProvider getProvider() {
			return provider;
		}

		public final String getType() {
			return type;
		}

		public final String getAlgorithm() {
			return algorithm;
		}

		public final String getClassName() {
			return className;
		}

		public final String getAttribute(String name) {
			return null;
		}

		public Object newInstance(Object constructorParameter) throws TNoSuchAlgorithmException {
			throw new TNoSuchAlgorithmException("no security providers in the browser");
		}

		public boolean supportsParameter(Object parameter) {
			return false;
		}

		@Override
		public String toString() {
			return type + "." + algorithm;
		}
	}
}
