// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.multiplayer;

import java.util.function.Consumer;
import javax.crypto.Cipher;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;

public final class WebLoginHooks {
	private WebLoginHooks() {
	}

	@FunctionalInterface
	public interface EncryptionSink {
		void accept(ServerboundKeyPacket packet, Cipher decrypt, Cipher encrypt);
	}

	public static boolean beginRelayJoin(final ClientboundHelloPacket packet, final EncryptionSink onReady, final Consumer<Component> onFail) {
		return false;
	}
}
