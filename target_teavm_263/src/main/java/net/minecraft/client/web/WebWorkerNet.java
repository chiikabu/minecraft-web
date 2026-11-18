// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import io.netty.channel.worker.WorkerChannel;
import io.netty.channel.worker.WorkerChannelRegistry;
import org.teavm.classlib.PlatformDetector;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

public final class WebWorkerNet {
	private static String serverModule() {
		return PlatformDetector.isWebAssemblyGC()
			? "classes263_server_wasm.js"
			: "classes263_server.js";
	}

	private static JSObject worker;
	private static String pageModuleUrl;
	private static boolean pageEmbedded;
	private static final WebWorkerInbox INBOUND = new WebWorkerInbox();
	private static final WebWorkerBoot BOOT = new WebWorkerBoot();
	private static boolean pageReady;
	private static boolean failed;
	private static final int BOOT_TIMEOUT_MS = 180000;
	private static final TimeoutCallback BOOT_TIMEOUT_LAST_CHANCE = () -> finishBootTimeout();
	private static final TimeoutCallback BOOT_TIMEOUT = () -> {
		if (!stillWaitingForBoot()) {
			return;
		}
		drainMessages();
		if (!stillWaitingForBoot()) {
			return;
		}
		setTimeout(BOOT_TIMEOUT_LAST_CHANCE, 0);
	};
	private static final TimeoutCallback PAGE_DRAIN = () -> {
		drainMessages();
		continuePageDrain();
	};
	private static boolean pageContinuationQueued;
	private static final TimeoutCallback PAGE_CONTINUE = () -> {
		pageContinuationQueued = false;
		drainMessages();
		continuePageDrain();
	};

	private static void continuePageDrain() {
		if (!pageContinuationQueued && INBOUND.size() > 0) {
			pageContinuationQueued = true;
			postContinuation(PAGE_CONTINUE);
		}
	}

	@JSBody(params = {"callback"}, script = "var c = window.__minecraftNetContinue;"
		+ " if (!c) { var ch = new MessageChannel(); c = window.__minecraftNetContinue = {port: ch.port2, cb: null};"
		+ "  ch.port1.onmessage = function() { var f = c.cb; if (f) { f(); } }; }"
		+ " c.cb = callback; c.port.postMessage(0);")
	private static native void postContinuation(TimeoutCallback callback);

	private static boolean stillWaitingForBoot() {
		return BOOT.waiting() && !pageReady && !failed;
	}

	private static void finishBootTimeout() {
		if (!stillWaitingForBoot()) {
			return;
		}
		drainMessages();
		if (!stillWaitingForBoot()) {
			return;
		}
		INBOUND.offer(WebWorkerBoot.CHANNEL, WebWorkerBoot.timeout());
	}

	private WebWorkerNet() {
	}

	public static boolean savesOwnedByWorker() {
		return WebWorkerTransport.supported() && !failed;
	}

	public static void startPage() {
		final String offline = offlineModuleUrl();
		final boolean embedded = offline != null && !offline.isEmpty();
		startPage(embedded ? offline : absoluteUrl(serverModule()), embedded);
	}

	public static void startPage(final String moduleUrl) {
		startPage(moduleUrl, WebWorkerTransport.embeddedAssetsAvailable());
	}

	private static void startPage(final String moduleUrl, final boolean embedded) {
		INBOUND.clear();
		INBOUND.wakeOnOffer(WebWorkerNet::schedulePageDrain);
		BOOT.begin();
		pageReady = false;
		failed = false;
		pageModuleUrl = moduleUrl;
		pageEmbedded = embedded;
		try {
			final JSObject spawned = WebWorkerTransport.startServer(moduleUrl, embedded);
			worker = spawned;
			bootInbound = WebWorkerNet::onPageBoot;
			WebWorkerTransport.receive(spawned, INBOUND);
			WebWorkerTransport.onError(spawned,
				() -> INBOUND.offer(WebWorkerBoot.CHANNEL, WebWorkerBoot.failed()));
			setTimeout(BOOT_TIMEOUT, BOOT_TIMEOUT_MS);
		} catch (Throwable e) {
			System.out.println("[26.3-web] worker could not start: " + e);
			disableWorker();
		}
	}

	private static void exposePageTransport() {
		final JSObject spawned = worker;
		if (spawned == null) {
			disableWorker();
			return;
		}
		WorkerChannelRegistry.enableClient(new WorkerChannel.Transport() {
			@Override
			public void send(final byte[] packet) {
				WebWorkerTransport.send(spawned, WebWorkerTransport.CH_PACKET, packet);
			}

			@Override
			public void sendBatch(final byte[] frame) {
				WebWorkerTransport.send(spawned, WebWorkerTransport.CH_PACKET_BATCH, frame);
			}

			@Override
			public void close() {
			}
		});
		WebSavesIpc.attach(message -> WebWorkerTransport.send(spawned, WebSavesIpc.CHANNEL, message));
		WebWorkerLifecycle.attach(message -> WebWorkerTransport.send(spawned, WebLifecycleMsg.CHANNEL, message));
		savesInbound = WebSavesIpc::receive;
		lifecycleInbound = WebWorkerLifecycle::receive;
		pageReady = true;
		System.out.println("[26.3-web] worker READY: server realm owns integrated-server worlds");
		System.out.println("[26.3-web] worker diagnostics currently unavailable:"
			+ " F3's TPS graph is flat; the chunk debug grid is empty.");
	}

	public static boolean pageStarted() {
		return pageReady;
	}

	private static void onPageBoot(final byte[] message) {
		int result = BOOT.receive(message);
		if (result == WebWorkerBoot.ACCEPT_READY) {
			try {
				exposePageTransport();
			} catch (Throwable e) {
				System.out.println("[26.3-web] worker READY activation failed: " + e);
				disableWorker();
			}
		} else if (result == WebWorkerBoot.ACCEPT_FAILED) {
			String detail = WebWorkerBoot.failureDetail(message);
			System.out.println("[26.3-web] worker did not reach READY"
				+ (detail.isEmpty() ? "" : ": " + detail) + "; singleplayer is unavailable");
			disableWorker();
		} else if (pageReady && WebWorkerBoot.isFailed(message)) {
			onWorkerError();
		}
	}

	private static void onWorkerError() {
		if (WebWorkerLifecycle.active()) {
			System.out.println("[26.3-web] worker crashed with a world open; cannot recover on the page thread");
			return;
		}
		disableWorker();
	}

	private static void disableWorker() {
		failed = true;
		INBOUND.wakeOnOffer(null);
		INBOUND.clear();
		BOOT.reset();
		pageReady = false;
		bootInbound = null;
		savesInbound = null;
		lifecycleInbound = null;
		WorkerChannelRegistry.reset();
		WebSavesIpc.attach(null);
		WebWorkerLifecycle.attach(null);
		if (worker != null) {
			WebWorkerTransport.terminate(worker);
			worker = null;
		}
		System.out.println("[26.3-web] worker unavailable; singleplayer remains worker-only");
	}

	public static void startWorker() {
		INBOUND.clear();
		INBOUND.wakeOnOffer(WebServerMain::wake);
		WorkerChannelRegistry.enableServer(new WorkerChannel.Transport() {
			@Override
			public void send(final byte[] packet) {
				WebWorkerTransport.selfSend(WebWorkerTransport.CH_PACKET, packet);
			}

			@Override
			public void sendBatch(final byte[] frame) {
				WebWorkerTransport.selfSend(WebWorkerTransport.CH_PACKET_BATCH, frame);
			}

			@Override
			public void close() {
			}
		});
		WebSavesIpc.attach(message -> WebWorkerTransport.selfSend(WebSavesIpc.CHANNEL, message));
		WebWorkerServer.attach(message -> WebWorkerTransport.selfSend(WebLifecycleMsg.CHANNEL, message));
		savesInbound = WebSavesIpc::respond;
		lifecycleInbound = WebWorkerServer::serve;
		WebWorkerTransport.selfReceive(INBOUND);
	}

	static void announceWorkerReady() {
		WebWorkerTransport.selfSend(WebWorkerBoot.CHANNEL, WebWorkerBoot.ready());
	}

	static void announceWorkerFailed(final Throwable error) {
		System.out.println("[26.3-server] worker boot failed: " + error);
		for (Throwable cause = error.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
			System.out.println("[26.3-server]   caused by: " + cause);
		}
		WebWorkerTransport.selfSend(WebWorkerBoot.CHANNEL, WebWorkerBoot.failed(String.valueOf(error)));
	}

	private static final long PAGE_DRAIN_BUDGET_NANOS = 4_000_000L;

	static int drainMessages() {
		return INBOUND.drain(WebWorkerNet::onMessage, WebWorkerTransport.inWorker() ? 0L : PAGE_DRAIN_BUDGET_NANOS);
	}

	private static void onMessage(final String channel, final byte[] data) {
		if (WebWorkerTransport.CH_PACKET.equals(channel)) {
			WorkerChannelRegistry.deliver(data);
		} else if (WebSavesIpc.CHANNEL.equals(channel)) {
			if (savesInbound != null) {
				savesInbound.post(data);
			}
		} else if (WebLifecycleMsg.CHANNEL.equals(channel)) {
			if (lifecycleInbound != null) {
				lifecycleInbound.post(data);
			}
		} else if (WebWorkerBoot.CHANNEL.equals(channel)) {
			if (bootInbound != null) {
				bootInbound.post(data);
			}
		}
	}

	private static WebLifecycleMsg.Pipe savesInbound;
	private static WebLifecycleMsg.Pipe lifecycleInbound;
	private static WebLifecycleMsg.Pipe bootInbound;

	@JSFunctor
	private interface TimeoutCallback extends JSObject {
		void run();
	}

	@JSBody(params = {"callback", "millis"}, script = "window.setTimeout(callback, millis);")
	private static native void setTimeout(TimeoutCallback callback, int millis);

	private static void schedulePageDrain() {
		queueMicrotask(PAGE_DRAIN);
	}

	@JSBody(params = {"callback"}, script = "queueMicrotask(callback);")
	private static native void queueMicrotask(TimeoutCallback callback);

	@JSBody(params = {"path"}, script = "return new URL(path, location.href).href;")
	private static native String absoluteUrl(String path);

	@JSBody(script = "return window.__minecraftServerModuleUrl ? window.__minecraftServerModuleUrl : '';")
	private static native String offlineModuleUrl();
}
