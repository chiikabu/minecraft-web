// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.authlib.minecraft;

import com.google.gson.JsonObject;

public interface TelemetryPropertyContainer {
	void addProperty(String key, String value);

	void addProperty(String key, int value);

	void addProperty(String key, long value);

	void addProperty(String key, boolean value);

	void addNullProperty(String key);

	static TelemetryPropertyContainer forJsonObject(JsonObject json) {
		return new TelemetryPropertyContainer() {
			@Override
			public void addProperty(String key, String value) {
				json.addProperty(key, value);
			}

			@Override
			public void addProperty(String key, int value) {
				json.addProperty(key, value);
			}

			@Override
			public void addProperty(String key, long value) {
				json.addProperty(key, value);
			}

			@Override
			public void addProperty(String key, boolean value) {
				json.addProperty(key, value);
			}

			@Override
			public void addNullProperty(String key) {
				json.add(key, com.google.gson.JsonNull.INSTANCE);
			}
		};
	}
}
