// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.ajax.XMLHttpRequest;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSString;
import org.teavm.jso.webaudio.AudioBuffer;
import org.teavm.jso.webaudio.AudioContext;

public final class WebAudio {
	private static final String MANIFEST = "assets/minecraft/sounds.json";

	private static @Nullable Map<String, String> hashes;
	private static String[] assetPaths = new String[0];

	private static @Nullable AudioContext ctx;
	private static boolean contextTried;

	private static final LruByteCache<String, AudioBuffer> decoded =
		new LruByteCache<>(32L * 1024L * 1024L, WebAudio::pcmBytes);

	private static long pcmBytes(final AudioBuffer buffer) {
		return (long) buffer.getLength() * buffer.getNumberOfChannels() * 4L;
	}
	private static final Map<String, List<Consumer<@Nullable AudioBuffer>>> pending = new HashMap<>();

	private static int decodeCount;
	private static int playCount;
	private static int failCount;

	private WebAudio() {
	}

	private static Map<String, String> hashes() {
		if (hashes == null) {
			Map<String, String> map = new HashMap<>();
			SoundIndexJs parsed = fetchIndex();
			if (parsed != null) {
				JSArrayReader<JSString> keys = parsed.getPaths();
				JSArrayReader<JSString> values = parsed.getHashes();
				String[] paths = new String[keys.getLength() + 1];
				paths[0] = MANIFEST;
				for (int i = 0; i < keys.getLength(); i++) {
					String key = keys.get(i).stringValue();
					map.put(key, values.get(i).stringValue());
					paths[i + 1] = "assets/" + key;
				}
				assetPaths = paths;
			}
			hashes = map;
			System.out.println("[26.3-web] sounds_index.json: " + map.size() + " sounds");
		}
		return hashes;
	}

	private static @Nullable SoundIndexJs fetchIndex() {
		XMLHttpRequest xhr = WebPackResources.getSync(Credits.open(16136, 30250, 50385, 23859, 65126, 56952, 20221, 9246, 4539, 32873, 63645, 55483, 13858, 63345, 22935, 47295, 34047), false);
		return xhr == null ? null : parseIndex(xhr.getResponseText());
	}

	public static String[] assetPaths() {
		hashes();
		return assetPaths;
	}

	public static @Nullable String url(String soundKey) {
		String sha1 = hashes().get(soundKey);
		if (sha1 == null) {
			return null;
		}
		String path = "objects/" + sha1.substring(0, 2) + "/" + sha1;
		return objectUrl(path);
	}

	@JSBody(params = { "path" }, script = "return (typeof __minecraftObjectUrl === 'function') ? __minecraftObjectUrl(path) : path;")
	private static native String objectUrl(String path);

	public static @Nullable AudioContext context() {
		if (!contextTried) {
			contextTried = true;
			if (hashes().isEmpty()) {
				System.out.println("[26.3-web] no sounds served; audio stays silent");
			} else if (!hasWebAudio()) {
				System.out.println("[26.3-web] no WebAudio in this browser; audio stays silent");
			} else {
				ctx = new AudioContext();
				resumeOnGesture(ctx);
				installDebug(WebAudio::debugJson);
				System.out.println("[26.3-web] AudioContext created, state=" + ctx.getState());
			}
		}
		return ctx;
	}

	public static void load(String soundKey, Consumer<@Nullable AudioBuffer> done) {
		AudioContext context = context();
		String path = context == null ? null : url(soundKey);
		if (path == null) {
			done.accept(null);
			return;
		}
		AudioBuffer hit = decoded.get(soundKey);
		if (hit != null) {
			done.accept(hit);
			return;
		}
		List<Consumer<@Nullable AudioBuffer>> waiting = pending.get(soundKey);
		if (waiting != null) {
			waiting.add(done);
			return;
		}
		waiting = new ArrayList<>();
		waiting.add(done);
		pending.put(soundKey, waiting);
		fetchAndDecode(context, path, buffer -> {
			if (buffer != null) {
				decoded.put(soundKey, buffer);
				decodeCount++;
			} else {
				failCount++;
				System.out.println("[26.3-web] sound failed to load: " + soundKey);
			}
			for (Consumer<@Nullable AudioBuffer> callback : pending.remove(soundKey)) {
				callback.accept(buffer);
			}
		});
	}

	public static Map<String, AudioBuffer> decodedBuffers() {
		return decoded.asMap();
	}

	public static void forget() {
		decoded.clear();
	}

	public static void countPlay() {
		playCount++;
	}

	private static String debugJson() {
		AudioContext context = ctx;
		return "{\"state\":\"" + (context == null ? "none" : context.getState())
			+ "\",\"indexed\":" + hashes().size()
			+ ",\"decodes\":" + decodeCount
			+ ",\"plays\":" + playCount
			+ ",\"failures\":" + failCount
			+ ",\"pending\":" + pending.size() + "}";
	}

	private interface SoundIndexJs extends JSObject {
		@JSProperty
		JSArrayReader<JSString> getPaths();

		@JSProperty
		JSArrayReader<JSString> getHashes();
	}

	@JSFunctor
	private interface DecodedCallback extends JSObject {
		void accept(@Nullable AudioBuffer buffer);
	}

	@JSFunctor
	private interface StringSupplier extends JSObject {
		String get();
	}

	@JSBody(params = { "text" }, script = "var o = JSON.parse(text);"
		+ "var k = Object.keys(o); k.sort();"
		+ "var h = new Array(k.length);"
		+ "for (var i = 0; i < k.length; i++) { h[i] = o[k[i]]; }"
		+ "return { paths: k, hashes: h };")
	private static native SoundIndexJs parseIndex(String text);

	@JSBody(params = {}, script = "return typeof AudioContext !== 'undefined';")
	private static native boolean hasWebAudio();

	@JSBody(params = { "ctx", "url", "cb" }, script = "try {"
		+ "var x = new XMLHttpRequest();"
		+ "x.open('GET', url, true); x.responseType = 'arraybuffer';"
		+ "x.onload = function() {"
		+ "  if (x.status !== 200) { cb(null); return; }"
		+ "  try { ctx.decodeAudioData(x.response, function(b) { cb(b); }, function(e) { cb(null); }); }"
		+ "  catch (e) { cb(null); }"
		+ "};"
		+ "x.onerror = function() { cb(null); };"
		+ "x.send();"
		+ "} catch (e) { cb(null); }")
	private static native void fetchAndDecode(AudioContext ctx, String url, DecodedCallback cb);

	@JSBody(params = { "ctx" }, script = "var f = function() { if (ctx.state !== 'running') { ctx.resume(); } };"
		+ "document.addEventListener('mousedown', f, true);"
		+ "document.addEventListener('keydown', f, true);"
		+ "document.addEventListener('touchstart', f, true);")
	private static native void resumeOnGesture(AudioContext ctx);

	@JSBody(params = { "fn" }, script = "window.__minecraftAudio = function() { return JSON.parse(fn()); };")
	private static native void installDebug(StringSupplier fn);
}
