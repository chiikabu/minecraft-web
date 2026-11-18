// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.Base64;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;

public final class WebRelayAuth {
	public static final int UNLINKED = 0;
	public static final int PENDING = 1;
	public static final int LINKED = 2;

	private static final String KEY_ACCOUNT = "minecraft_web:account";
	private static final String KEY_NAME = "minecraft_web:account.name";
	public static final String KEY_RELAY_TOKEN = "minecraft_web:relay.token";

	private static int state = UNLINKED;
	private static String error = "";
	private static String userCode = "";
	private static String verificationUri = "";
	private static String verificationUriComplete = "";
	private static String deviceCode = "";
	private static String linkedName = "";
	private static double nextPollMs;
	private static double expiresAtMs;
	private static long pollIntervalMs = 5000;
	private static boolean inFlight;
	private static long authEpoch;
	@Nullable
	private static Certificates cachedCertificates;
	private static long certificatesInFlightEpoch = -1L;
	@Nullable
	private static Runnable listener;

	private WebRelayAuth() {
	}

	public static int state() {
		return state;
	}

	public static String error() {
		return error;
	}

	public static String userCode() {
		return userCode;
	}

	public static String verificationUri() {
		return verificationUri;
	}

	public static String verificationUriComplete() {
		if (!verificationUriComplete.isEmpty()) {
			return verificationUriComplete;
		}
		if (verificationUri.isEmpty() || userCode.isEmpty()) {
			return verificationUri;
		}
		return verificationUri + "?otc=" + userCode;
	}

	public static String linkedName() {
		return linkedName;
	}

	public static boolean hasStoredAccount() {
		return !storageGet(KEY_ACCOUNT).isEmpty();
	}

	public static void setListener(@Nullable final Runnable onChange) {
		listener = onChange;
	}

	public static String relayUrl() {
		return WebRelays.current();
	}

	public static String relaySocketUrl() {
		return socketUrl(relayUrl(), storageGet(KEY_RELAY_TOKEN), storageGet(RelayTransport.KEY));
	}

	static String relaySocketUrl(final String relay) {
		return socketUrl(relay, storageGet(KEY_RELAY_TOKEN), storageGet(RelayTransport.KEY));
	}

	public static UUID linkedUuid() {
		String account = storageGet(KEY_ACCOUNT);
		String id = field(field(account, "profile"), "id").replace("-", "");
		if (id.length() != 32) {
			return null;
		}
		try {
			return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
				+ id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static void restore() {
		nextEpoch();
		inFlight = false;
		error = "";
		String account = storageGet(KEY_ACCOUNT);
		linkedName = storageGet(KEY_NAME);
		if (account.isEmpty()) {
			state = UNLINKED;
			linkedName = "";
			return;
		}
		if (!jsonObject(account)) {
			unlink();
			error = "Stored Relay account is invalid -- link again";
			changed();
			return;
		}
		state = LINKED;
		if (WebProfile.microsoftMode() && !linkedName.isEmpty()) {
			WebProfile.applySession(linkedName);
		}
	}

	public static void startLink() {
		if (inFlight || state == PENDING) {
			return;
		}
		final long epoch = nextEpoch();
		error = "";
		inFlight = true;
		post("/auth/device/start", "{}", (status, body) -> {
			if (!current(epoch)) {
				return;
			}
			inFlight = false;
			if (status != 200) {
				fail(status, body);
				return;
			}
			deviceCode = field(body, "deviceCode");
			userCode = field(body, "userCode");
			verificationUri = field(body, "verificationUri");
			verificationUriComplete = field(body, "verificationUriComplete");
			long interval = parseLong(field(body, "interval"), 5);
			long expiresIn = parseLong(field(body, "expiresIn"), 900);
			pollIntervalMs = Math.max(1, interval) * 1000L;
			nextPollMs = now() + pollIntervalMs;
			expiresAtMs = now() + expiresIn * 1000.0;
			state = PENDING;
			changed();
		});
	}

	public static void tick() {
		if (state != PENDING || inFlight) {
			return;
		}
		if (now() > expiresAtMs) {
			state = UNLINKED;
			error = "The sign-in code expired -- try again";
			changed();
			return;
		}
		if (now() < nextPollMs) {
			return;
		}
		inFlight = true;
		final long epoch = authEpoch;
		post("/auth/device/poll", "{\"deviceCode\":\"" + escape(deviceCode) + "\"}", (status, body) -> {
			if (!current(epoch)) {
				return;
			}
			inFlight = false;
			if (status == 200 && "pending".equals(field(body, "status"))) {
				nextPollMs = now() + pollIntervalMs;
				return;
			}
			if (status == 200) {
				if (!storeRequiredAccount(body)) {
					error = "Relay sign-in returned an invalid account";
					state = UNLINKED;
					changed();
					return;
				}
				state = LINKED;
				applyIdentity(epoch, () -> {
					prefetchCertificates(epoch);
					changed();
				});
				return;
			}
			if ("slow_down".equals(field(body, "error"))) {
				pollIntervalMs += 5000;
				nextPollMs = now() + pollIntervalMs;
				return;
			}
			fail(status, body);
		});
	}

	public static void cancel() {
		nextEpoch();
		inFlight = false;
		state = UNLINKED;
		deviceCode = "";
		userCode = "";
		verificationUriComplete = "";
		error = "";
		changed();
	}

	public static void join(final String serverId, final byte[] publicKeyDer, final byte[] challenge, final JoinCallback callback) {
		final long epoch = authEpoch;
		String account = storageGet(KEY_ACCOUNT);
		if (account.isEmpty() || !jsonObject(account)) {
			callback.done(JoinResult.fail("Link a Microsoft account to join this server"));
			return;
		}
		String body = "{\"account\":" + account
			+ ",\"serverId\":\"" + escape(serverId)
			+ "\",\"publicKey\":\"" + b64(publicKeyDer)
			+ "\",\"verifyToken\":\"" + b64(challenge)
			+ "\"}";
		post("/auth/join", body, (status, text) -> {
			if (!current(epoch)) {
				callback.done(JoinResult.fail("Relay account was unlinked"));
				return;
			}
			if (status == 401) {
				unlink();
				error = "Relay sign-in expired -- link again to restore it";
				callback.done(JoinResult.fail(error));
				return;
			}
			if (status != 200) {
				String msg = message(text);
				callback.done(JoinResult.fail(msg.isEmpty() ? "Relay join failed (HTTP " + status + ")" : msg));
				return;
			}
			try {
				byte[] sharedSecret = b64d(field(text, "sharedSecret"));
				byte[] encryptedSharedSecret = b64d(field(text, "encryptedSharedSecret"));
				byte[] encryptedVerifyToken = b64d(field(text, "encryptedVerifyToken"));
				if (sharedSecret.length != 16 || encryptedSharedSecret.length == 0 || encryptedVerifyToken.length == 0) {
					throw new IllegalArgumentException("invalid join blobs");
				}
				if (!storeRequiredAccount(text)) {
					throw new IllegalArgumentException("invalid rotated account");
				}
				callback.done(JoinResult.ok(sharedSecret, encryptedSharedSecret, encryptedVerifyToken));
			} catch (RuntimeException e) {
				callback.done(JoinResult.fail("Relay join returned unreadable blobs"));
			}
		});
	}

	@Nullable
	public static Certificates certificatesSync() {
		Certificates cached = cachedCertificates;
		if (cached != null && cached.usable()) {
			return cached;
		}
		cachedCertificates = null;
		String account = storageGet(KEY_ACCOUNT);
		if (account.isEmpty() || !jsonObject(account)) {
			return null;
		}
		prefetchCertificates(authEpoch);
		return null;
	}

	private static void prefetchCertificates(final long epoch) {
		if (!current(epoch)) {
			return;
		}
		String account = storageGet(KEY_ACCOUNT);
		if (account.isEmpty() || certificatesInFlightEpoch == epoch) {
			return;
		}
		certificatesInFlightEpoch = epoch;
		post("/auth/certificates", "{\"account\":" + account + "}", (status, text) -> {
			if (!current(epoch)) {
				return;
			}
			certificatesInFlightEpoch = -1L;
			if (status == 401) {
				unlink();
				error = "Relay sign-in expired -- link again to restore it";
				System.out.println("[26.3-web] relay: " + error);
				return;
			}
			if (status != 200) {
				System.out.println("[26.3-web] relay: certificates unavailable (relay status " + status + " " + message(text) + ")");
				return;
			}
			String sig = field(text, "publicKeySignatureV2");
			if (sig.isEmpty()) {
				sig = field(text, "publicKeySignature");
			}
			String priv = field(text, "privateKey");
			String pub = field(text, "publicKey");
			Certificates certificates = new Certificates(priv, pub, sig, field(text, "expiresAt"), field(text, "refreshedAfter"));
			if (priv.isEmpty() || pub.isEmpty() || sig.isEmpty() || !certificates.usable()) {
				System.out.println("[26.3-web] relay: certificates were missing or expired; will retry when chat asks again");
				return;
			}
			if (!storeRequiredAccount(text)) {
				System.out.println("[26.3-web] relay: certificates returned an invalid rotated account");
				return;
			}
			cachedCertificates = certificates;
			changed();
		});
	}

	@FunctionalInterface
	public interface JoinCallback {
		void done(JoinResult result);
	}

	public static final class JoinResult {
		@Nullable
		public final String error;
		public final byte[] sharedSecret;
		public final byte[] encryptedSharedSecret;
		public final byte[] encryptedVerifyToken;

		private JoinResult(@Nullable final String error, final byte[] sharedSecret, final byte[] encryptedSharedSecret, final byte[] encryptedVerifyToken) {
			this.error = error;
			this.sharedSecret = sharedSecret;
			this.encryptedSharedSecret = encryptedSharedSecret;
			this.encryptedVerifyToken = encryptedVerifyToken;
		}

		static JoinResult fail(final String error) {
			return new JoinResult(error, new byte[0], new byte[0], new byte[0]);
		}

		static JoinResult ok(final byte[] sharedSecret, final byte[] encryptedSharedSecret, final byte[] encryptedVerifyToken) {
			return new JoinResult(null, sharedSecret, encryptedSharedSecret, encryptedVerifyToken);
		}
	}

	public static final class Certificates {
		public final String privateKey;
		public final String publicKey;
		public final String publicKeySignature;
		public final String expiresAt;
		public final String refreshedAfter;

		Certificates(final String privateKey, final String publicKey, final String publicKeySignature, final String expiresAt, final String refreshedAfter) {
			this.privateKey = privateKey;
			this.publicKey = publicKey;
			this.publicKeySignature = publicKeySignature;
			this.expiresAt = expiresAt;
			this.refreshedAfter = refreshedAfter;
		}

		boolean usable() {
			return expiresAtMs(this.expiresAt) > now();
		}
	}

	public static void unlink() {
		nextEpoch();
		inFlight = false;
		state = UNLINKED;
		linkedName = "";
		deviceCode = "";
		userCode = "";
		verificationUriComplete = "";
		cachedCertificates = null;
		certificatesInFlightEpoch = -1L;
		storageRemove(KEY_ACCOUNT);
		storageRemove(KEY_NAME);
		changed();
	}

	private static boolean store(final String body) {
		String account = account(body);
		if (!account.isEmpty() && !jsonObject(account)) {
			return false;
		}
		if (!account.isEmpty()) {
			storageSet(KEY_ACCOUNT, account);
		}
		String name = field(field(body, "profile"), "name");
		if (!name.isEmpty() && validName(name)) {
			linkedName = name;
			storageSet(KEY_NAME, name);
		}
		return true;
	}

	private static boolean storeRequiredAccount(final String body) {
		return !account(body).isEmpty() && store(body);
	}

	private static String account(final String body) {
		String account = field(body, "account");
		return account.isEmpty() ? field(body, "blob") : account;
	}

	private static void applyIdentity(final long epoch, @Nullable final Runnable afterSkin) {
		if (!current(epoch)) {
			return;
		}
		if (linkedName.isEmpty()) {
			afterSkin(afterSkin);
			return;
		}
		WebProfile.applySession(linkedName);
		String account = storageGet(KEY_ACCOUNT);
		if (account.isEmpty()) {
			afterSkin(afterSkin);
			return;
		}
		post("/auth/skin", "{\"account\":" + account + "}", (status, body) -> {
			if (!current(epoch)) {
				return;
			}
			if (status != 200) {
				System.out.println("[26.3-web] relay: skin unavailable (HTTP " + status + " " + message(body) + ")");
				afterSkin(afterSkin);
				return;
			}
			if (!storeRequiredAccount(body)) {
				System.out.println("[26.3-web] relay: skin returned an invalid rotated account");
				afterSkin(afterSkin);
				return;
			}
			String png = field(body, "skinPng");
			if (png.isEmpty()) {
				System.out.println("[26.3-web] relay: account has no skin set; keeping the chosen one");
				afterSkin(afterSkin);
				return;
			}
			WebProfile.applyLinkedSkin("data:image/png;base64," + png, "slim".equals(field(body, "model")));
			changed();
			afterSkin(afterSkin);
		});
	}

	private static void afterSkin(@Nullable final Runnable callback) {
		if (callback != null) {
			callback.run();
		}
	}

	private static void fail(final int status, final String body) {
		String code = field(body, "error");
		if (status == 0) {
			error = relayUrl().isEmpty()
				? "No Relay selected -- add a /socket/ URL in Relays"
				: WebRelays.label(relayUrl()) + " is not reachable -- pick another in Relays";
		} else if ("no_profile".equals(code)) {
			error = "This Microsoft account doesn't own Minecraft: Java Edition";
		} else if ("declined".equals(code)) {
			error = "Sign-in was declined at Microsoft";
		} else if ("expired".equals(code)) {
			error = "The sign-in code expired -- try again";
		} else if ("reauth_required".equals(code)) {
			error = "Relay sign-in expired -- link again to restore it";
		} else {
			String message = message(body);
			error = message.isEmpty() ? "The Relay refused the sign-in (HTTP " + status + ")" : message;
		}
		state = UNLINKED;
		System.out.println("[26.3-web] relay link failed: " + error);
		changed();
	}

	private static String message(final String body) {
		return field(body, "message");
	}

	private static void changed() {
		if (listener != null) {
			listener.run();
		}
	}

	private static long parseLong(final String raw, final long fallback) {
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	static String socketUrl(final String relay, final String token) {
		return socketUrl(relay, token, RelayTransport.AUTO);
	}

	static String socketUrl(final String relay, final String token, final String transport) {
		String socket = RelayStore.normalize(relay);
		if (socket.isEmpty() || socket.startsWith("ws://") && !loopbackSocketRelay(socket)) {
			return "";
		}
		String query = token.isEmpty() ? "" : tokenQuery(token);
		String mode = RelayTransport.normalize(transport);
		if (!RelayTransport.AUTO.equals(mode)) {
			query += (query.isEmpty() ? "?" : "&") + "transport=" + mode;
		}
		return socket + query;
	}

	private static boolean loopbackSocketRelay(final String relay) {
		String authority = relay.substring("ws://".length());
		int slash = authority.indexOf('/');
		if (slash >= 0) {
			authority = authority.substring(0, slash);
		}
		int at = authority.lastIndexOf('@');
		if (at >= 0) {
			authority = authority.substring(at + 1);
		}
		String host = authority;
		if (host.startsWith("[")) {
			int close = host.indexOf(']');
			if (close < 0) {
				return false;
			}
			host = host.substring(0, close + 1);
		} else {
			int colon = host.indexOf(':');
			if (colon >= 0) {
				host = host.substring(0, colon);
			}
		}
		return "localhost".equalsIgnoreCase(host) || "localhost.".equalsIgnoreCase(host)
			|| "[::1]".equalsIgnoreCase(host) || "[0:0:0:0:0:0:0:1]".equalsIgnoreCase(host)
			|| ipv4Loopback(host);
	}

	private static boolean ipv4Loopback(final String host) {
		String[] parts = host.split("\\.", -1);
		if (parts.length != 4 || !"127".equals(parts[0])) {
			return false;
		}
		for (int i = 1; i < parts.length; i++) {
			try {
				int octet = Integer.parseInt(parts[i]);
				if (octet < 0 || octet > 255 || !Integer.toString(octet).equals(parts[i])) {
					return false;
				}
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return true;
	}

	private static long nextEpoch() {
		cachedCertificates = null;
		certificatesInFlightEpoch = -1L;
		return ++authEpoch;
	}

	private static boolean current(final long epoch) {
		return epoch == authEpoch;
	}

	private static boolean validName(final String name) {
		if (name.length() < 3 || name.length() > 16) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (!(c == '_' || c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z')) {
				return false;
			}
		}
		return true;
	}

	private static String escape(final String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '"' || c == '\\') {
				out.append('\\');
			}
			if (c >= ' ') {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static String b64(final byte[] raw) {
		return Base64.getEncoder().encodeToString(raw);
	}

	private static byte[] b64d(final String raw) {
		if (raw == null || raw.isEmpty()) {
			throw new IllegalArgumentException("empty base64");
		}
		return Base64.getDecoder().decode(raw);
	}

	private static void post(final String path, final String body, final ResponseHandler handler) {
		WebRelayRpc.post(path, body, handler::response);
	}

	private interface ResponseHandler {
		void response(int status, String body);
	}

	@JSBody(params = { "text", "name" }, script = "try {"
		+ "  var v = JSON.parse(text)[name];"
		+ "  if (v === undefined || v === null) { return ''; }"
		+ "  return typeof v === 'string' ? v : JSON.stringify(v);"
		+ "} catch (e) { return ''; }")
	private static native String field(String text, String name);

	@JSBody(params = {}, script = "return Date.now();")
	private static native double now();

	@JSBody(params = { "value" }, script = "var ms = Date.parse(value); return isFinite(ms) ? ms : -1;")
	private static native double expiresAtMs(String value);

	@JSBody(params = { "value" }, script = "try { var parsed = JSON.parse(value); return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed); } catch (e) { return false; }")
	private static native boolean jsonObject(String value);

	@JSBody(params = { "token" }, script = "return token ? '?token=' + encodeURIComponent(token) : ''; ")
	private static native String tokenQuery(String token);

	@JSBody(params = { "k" }, script = "try { return window.localStorage.getItem(k) || ''; } catch (e) { return ''; }")
	private static native String storageGet(String k);

	@JSBody(params = { "k", "v" }, script = "try { window.localStorage.setItem(k, v); } catch (e) {}")
	private static native void storageSet(String k, String v);

	@JSBody(params = { "k" }, script = "try { window.localStorage.removeItem(k); } catch (e) {}")
	private static native void storageRemove(String k);
}
