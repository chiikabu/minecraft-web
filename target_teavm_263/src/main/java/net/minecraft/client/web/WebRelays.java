// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.List;
import org.teavm.jso.JSBody;

public final class WebRelays {
	private static boolean migrated;

	private WebRelays() {
	}

	public static int builtins() {
		return count();
	}

	public static List<String> customs() {
		migrate();
		return RelayStore.parse(lsGet(RelayStore.KEY_CUSTOM));
	}

	public static List<String> all() {
		return RelayStore.all(builtins(), customs());
	}

	public static String selected() {
		migrate();
		return RelayStore.normalize(lsGet(RelayStore.KEY_SELECTED));
	}

	public static void select(final String relay) {
		lsSet(RelayStore.KEY_SELECTED, relay == null ? RelayStore.AUTO : RelayStore.normalize(relay));
	}

	public static String current() {
		return RelayStore.resolve(selected(), builtins(), customs(), WebRelays::status);
	}

	public static void addCustom(final String url) {
		lsSet(RelayStore.KEY_CUSTOM, RelayStore.encode(RelayStore.addCustom(customs(), alias(url))));
	}

	public static void removeCustom(final String url) {
		String clean = RelayStore.normalize(url);
		lsSet(RelayStore.KEY_CUSTOM, RelayStore.encode(RelayStore.removeCustom(customs(), clean)));
		if (clean.equals(selected())) {
			select(RelayStore.AUTO);
		}
	}

	public static String label(final String relay) {
		return RelayStore.label(relay, builtins(), customs());
	}

	public static String status(final String relay) {
		return statusJs(RelayStore.normalize(relay));
	}

	public static String methods(final String relay) {
		return methodsJs(RelayStore.normalize(relay));
	}

	public static int ping(final String relay) {
		return pingJs(RelayStore.normalize(relay));
	}

	public static void probeAll() {
		probeJs(RelayStore.encode(all()), lsGet(WebRelayAuth.KEY_RELAY_TOKEN));
	}

	public static String unmask(final String url) {
		return unmaskJs(url);
	}

	private static String alias(final String url) {
		return aliasJs(url == null ? "" : url.trim());
	}

	private static void migrate() {
		if (migrated) {
			return;
		}
		migrated = true;
		String legacy = lsGet(RelayStore.KEY);
		if (legacy.isEmpty()) {
			return;
		}
		String[] moved = RelayStore.migrate(legacy, WebRelays::alias);
		List<String> customs = RelayStore.parse(lsGet(RelayStore.KEY_CUSTOM));
		for (String url : RelayStore.parse(moved[0])) {
			RelayStore.addCustom(customs, url);
		}
		lsSet(RelayStore.KEY_CUSTOM, RelayStore.encode(customs));
		if (lsGet(RelayStore.KEY_SELECTED).isEmpty()) {
			lsSet(RelayStore.KEY_SELECTED, moved[1]);
		}
		lsRemove(RelayStore.KEY);
	}

	private static final String NET = "var n=globalThis[Symbol.for('net.minecraft.client.n')];";

	@JSBody(script = NET + "try{return n?n.c()|0:0;}catch(e){return 0;}")
	private static native int count();

	@JSBody(params = { "u" }, script = NET + "try{return n?String(n.s(u)||''):'';}catch(e){return '';}")
	private static native String statusJs(String u);

	@JSBody(params = { "u" }, script = NET + "try{return n?String(n.m(u)||''):'';}catch(e){return '';}")
	private static native String methodsJs(String u);

	@JSBody(params = { "u" }, script = NET + "try{return n&&n.t?(n.t(u)|0):-1;}catch(e){return -1;}")
	private static native int pingJs(String u);

	@JSBody(params = { "list", "token" }, script = NET + "try{if(n)n.p(list,token);}catch(e){}")
	private static native void probeJs(String list, String token);

	@JSBody(params = { "u" }, script = NET + "try{return n?String(n.u(u)):u;}catch(e){return u;}")
	private static native String unmaskJs(String u);

	@JSBody(params = { "u" }, script = NET + "try{return n?String(n.a(u)):u;}catch(e){return u;}")
	private static native String aliasJs(String u);

	@JSBody(params = { "k" }, script = "try { return window.localStorage.getItem(k) || ''; } catch (e) { return ''; }")
	private static native String lsGet(String k);

	@JSBody(params = { "k", "v" }, script = "try { window.localStorage.setItem(k, v); } catch (e) {}")
	private static native void lsSet(String k, String v);

	@JSBody(params = { "k" }, script = "try { window.localStorage.removeItem(k); } catch (e) {}")
	private static native void lsRemove(String k);
}
