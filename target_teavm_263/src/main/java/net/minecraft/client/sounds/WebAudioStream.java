// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.sounds;

import java.nio.ByteBuffer;

public record WebAudioStream(String url) implements AudioStream {
	@Override
	public ByteBuffer read(final int expectedSize) {
		return null;
	}

	@Override
	public void close() {
	}
}
