// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.net.InetSocketAddress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import org.teavm.jso.JSBody;

public final class LanClientWall {
	private static int wanted = -1;
	private static ClientPacketListener checkedFor;
	private static boolean guest;

	private LanClientWall() {
	}

	@JSBody(script = "try { return !/[?&]lanwall=0(?:&|$)/.test(location.search || ''); } catch (e) { return true; }")
	private static native boolean wantedByUrl();

	public static boolean active() {
		if (wanted < 0) {
			wanted = wantedByUrl() ? 1 : 0;
		}
		if (wanted == 0) {
			return false;
		}
		ClientPacketListener listener = Minecraft.getInstance().getConnection();
		if (listener == null) {
			return false;
		}
		if (listener != checkedFor) {
			checkedFor = listener;
			guest = lanGuest(listener.getConnection());
		}
		if (guest) {
			return true;
		}
		return WebWorkerLifecycle.lanStatus() == WebLifecycleMsg.HOST_BOUND && listener.getOnlinePlayers().size() > 1;
	}

	private static boolean lanGuest(final Connection connection) {
		return connection != null && !connection.isLocalTransport()
			&& connection.getRemoteAddress() instanceof InetSocketAddress address
			&& LanServers.LOOPBACK.equals(address.getHostString());
	}
}
