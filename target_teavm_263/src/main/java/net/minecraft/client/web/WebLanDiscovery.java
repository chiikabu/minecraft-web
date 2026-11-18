// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import net.minecraft.client.server.LanServerDetection;
import net.minecraft.client.server.LanServerDetection.LanServerList;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public final class WebLanDiscovery {
	private WebLanDiscovery() {
	}

	@JSFunctor
	public interface ServerSink extends JSObject {
		void add(String motd, String address);
	}

	public static void install() {
		LanServerDetection.webDiscovery = list -> poll(list);
	}

	private static final int REPOLL_MS = 3000;
	private static int generation;

	private static void poll(final LanServerList list) {
		pollChain(list, ++generation);
	}

	private static void pollChain(final LanServerList list, final int gen) {
		if (gen != generation || WebRelayAuth.relaySocketUrl().isEmpty()) {
			return;
		}
		WebRelayRpc.post("/lan", "{}", (status, body) -> {
			if (gen != generation) {
				return;
			}
			if (status == 200) {
				acceptLan(body, (motd, address) -> list.addWebServer(motd, address));
			}
			System.out.println("[26.3-web] lan poll: status " + status + " " + (body == null ? "" : body.length() > 200 ? body.substring(0, 200) : body));
			if (net.minecraft.client.Minecraft.getInstance().gui.screen()
					instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen) {
				setTimeoutJs(() -> pollChain(list, gen), REPOLL_MS);
			}
		});
	}

	@JSFunctor
	private interface Task extends JSObject {
		void run();
	}

	@JSBody(params = { "cb", "ms" }, script = "setTimeout(function () { cb(); }, ms);")
	private static native void setTimeoutJs(Task cb, int ms);

	@JSBody(params = {"body", "sink"}, script = ""
		+ "try { var j=JSON.parse(body);"
		+ "  var s = (j && j.servers) || [];"
		+ "  for (var i = 0; i < s.length; i++) {"
		+ "    var e = s[i];"
		+ "    if (e && e.host != null && e.port != null) {"
		+ "      sink(String(e.name == null ? '' : e.name), e.host + ':' + e.port);"
		+ "    }"
		+ "  }"
		+ "} catch (e) {}")
	private static native void acceptLan(String body, ServerSink sink);
}
