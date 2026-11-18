// minecraft-web by chiikabu (https://github.com/chiikabu)

package com.mojang.util;

import java.io.IOException;
import java.util.UUID;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

public class UUIDTypeAdapter extends TypeAdapter<UUID> {
	@Override
	public void write(JsonWriter out, UUID value) throws IOException {
		out.value(value == null ? null : UndashedUuid.toString(value));
	}

	@Override
	public UUID read(JsonReader in) throws IOException {
		return UndashedUuid.fromStringLenient(in.nextString());
	}
}
