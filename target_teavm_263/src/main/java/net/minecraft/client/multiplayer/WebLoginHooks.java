// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.multiplayer;

import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import net.minecraft.client.web.WebRelayAuth;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.util.Crypt;

public final class WebLoginHooks {
	private WebLoginHooks() {
	}

	@FunctionalInterface
	public interface EncryptionSink {
		void accept(ServerboundKeyPacket packet, Cipher decrypt, Cipher encrypt);
	}

	public static boolean beginRelayJoin(final ClientboundHelloPacket packet, final EncryptionSink onReady, final Consumer<Component> onFail) {
		if (!packet.shouldAuthenticate()) {
			return false;
		}
		if (WebRelayAuth.state() != WebRelayAuth.LINKED) {
			onFail.accept(Component.translatable(
				"disconnect.loginFailedInfo",
				Component.literal("Link a Microsoft account to join this server")
			));
			return true;
		}
		byte[] publicKeyDer;
		try {
			publicKeyDer = packet.getPublicKey().getEncoded();
		} catch (Exception e) {
			onFail.accept(Component.translatable("disconnect.loginFailedInfo", Component.literal("Protocol error")));
			return true;
		}
		WebRelayAuth.join(packet.getServerId(), publicKeyDer, packet.getChallenge(), result -> {
			if (result.error != null) {
				onFail.accept(Component.translatable("disconnect.loginFailedInfo", Component.literal(result.error)));
				return;
			}
			try {
				SecretKey secretKey = Crypt.secretKeyFrom(result.sharedSecret);
				Cipher decrypt = Crypt.getCipher(2, secretKey);
				Cipher encrypt = Crypt.getCipher(1, secretKey);
				onReady.accept(new ServerboundKeyPacket(result.encryptedSharedSecret, result.encryptedVerifyToken), decrypt, encrypt);
			} catch (Exception e) {
				onFail.accept(Component.translatable("disconnect.loginFailedInfo", Component.literal("Relay join failed")));
			}
		});
		return true;
	}
}
