// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.minecraft;

public interface TelemetryEvent extends TelemetryPropertyContainer {
	TelemetryEvent EMPTY = new TelemetryEvent() {
		@Override
		public void send() {
		}

		@Override
		public void addProperty(String key, String value) {
		}

		@Override
		public void addProperty(String key, int value) {
		}

		@Override
		public void addProperty(String key, long value) {
		}

		@Override
		public void addProperty(String key, boolean value) {
		}

		@Override
		public void addNullProperty(String key) {
		}
	};

	void send();
}
