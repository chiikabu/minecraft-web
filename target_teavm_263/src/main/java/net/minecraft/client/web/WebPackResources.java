// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.ajax.XMLHttpRequest;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSString;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int8Array;

public class WebPackResources implements PackResources {
	private static String[] index;
	private static int[] offsets;
	private static int[] lengths;
	private static Int8Array jsBundle;

	private final PackLocationInfo location;
	private final ResourceMetadata builtInMetadata;
	private final Set<String> namespaces;
	private final LruByteCache<String, byte[]> cache =
		new LruByteCache<>(2L * 1024L * 1024L, bytes -> bytes.length);

	public WebPackResources(PackLocationInfo location, ResourceMetadata builtInMetadata, Set<String> namespaces) {
		this.location = location;
		this.builtInMetadata = builtInMetadata;
		this.namespaces = namespaces;
	}

	public static void preload() {
		index();
	}

	private static String[] index() {
		if (index == null) {
			boolean embedded = hasEmbedded();
			IndexJs parsed;
			if (embedded) {
				parsed = parseEmbeddedIndex();
			} else {
				XMLHttpRequest xhr = getSync(Credits.open(16154, 30262, 50391, 23864, 65142, 56952, 20221, 9246, 4539, 32873, 63645, 55483, 13858, 63345, 22935, 47295, 34047), false);
				if (xhr == null) {
					throw new IllegalStateException("assets_index.json unavailable (not served, or this"
						+ " page is file:// and was packaged without the embedded asset globals)");
				}
				parsed = parseIndex(xhr);
			}
			JSArrayReader<JSString> keys = parsed.getPaths();
			Int32Array off = parsed.getOffsets();
			Int32Array len = parsed.getLengths();
			String[] paths = new String[keys.getLength()];
			int[] o = new int[paths.length];
			int[] l = new int[paths.length];
			for (int i = 0; i < paths.length; i++) {
				paths[i] = keys.get(i).stringValue();
				o[i] = off.get(i);
				l[i] = len.get(i);
			}
			index = paths;
			offsets = o;
			lengths = l;
			mergeExternal();
			System.out.println("[26.3-web] fetching assets.bin");
			jsBundle = embedded ? decodeEmbeddedBundle() : fetchInt8("assets.bin");
			System.out.println("[26.3-web] assets.bin " + (jsBundle == null ? "absent" : jsBundle.getLength() + " bytes (js)"));
			if (jsBundle == null) {
				System.out.println("[26.3-web] assets.bin absent; falling back to one XHR per resource");
			}
		}
		return index;
	}

	private static void mergeExternal() {
		merge(WebAudio.assetPaths());
		merge(WebLang.assetPaths());
	}

	private static void merge(String[] extra) {
		if (extra.length == 0) {
			return;
		}
		String[] merged = new String[index.length + extra.length];
		int[] o = new int[merged.length];
		int[] l = new int[merged.length];
		int a = 0;
		int b = 0;
		for (int i = 0; i < merged.length; i++) {
			boolean takeExtra = a == index.length || (b < extra.length && extra[b].compareTo(index[a]) < 0);
			if (takeExtra) {
				merged[i] = extra[b++];
				o[i] = -1;
				l[i] = merged[i].endsWith(".ogg") ? 0 : 1;
			} else {
				merged[i] = index[a];
				o[i] = offsets[a];
				l[i] = lengths[a];
				a++;
			}
		}
		index = merged;
		offsets = o;
		lengths = l;
	}

	private interface IndexJs extends JSObject {
		@JSProperty
		JSArrayReader<JSString> getPaths();

		@JSProperty
		Int32Array getOffsets();

		@JSProperty
		Int32Array getLengths();
	}

	@JSBody(params = { "xhr" }, script = "var o = JSON.parse(xhr.responseText);"
		+ "var k = Object.keys(o); k.sort();"
		+ "var n = k.length, off = new Int32Array(n), len = new Int32Array(n);"
		+ "for (var i = 0; i < n; i++) { var e = o[k[i]]; off[i] = e[0]; len[i] = e[1]; }"
		+ "return { paths: k, offsets: off, lengths: len };")
	private static native IndexJs parseIndex(XMLHttpRequest xhr);

	@JSBody(params = { "xhr" }, script = "var s = xhr.responseText, n = s.length, a = new Int8Array(n);"
		+ "for (var i = 0; i < n; i++) { a[i] = s.charCodeAt(i); }"
		+ "return a;")
	private static native Int8Array responseBytes(XMLHttpRequest xhr);

	@JSBody(params = {}, script = "return typeof __minecraftAssetsIndex !== 'undefined'"
		+ " && (typeof __minecraftAssetsBinRaw !== 'undefined' || typeof __minecraftAssetsBin !== 'undefined');")
	private static native boolean hasEmbedded();

	@JSBody(params = {}, script = "var o = __minecraftAssetsIndex;"
		+ "if (typeof o === 'string') { o = JSON.parse(o); }"
		+ "var k = Object.keys(o); k.sort();"
		+ "var n = k.length, off = new Int32Array(n), len = new Int32Array(n);"
		+ "for (var i = 0; i < n; i++) { var e = o[k[i]]; off[i] = e[0]; len[i] = e[1]; }"
		+ "return { paths: k, offsets: off, lengths: len };")
	private static native IndexJs parseEmbeddedIndex();

	@JSBody(params = {}, script = "if (typeof __minecraftAssetsBinRaw !== 'undefined' && __minecraftAssetsBinRaw) {"
		+ "  var r = __minecraftAssetsBinRaw;"
		+ "  var v = new Int8Array(r.buffer, r.byteOffset, r.byteLength);"
			+ "  globalThis.__minecraftAssetsBinRaw = undefined;"
		+ "  return v;"
		+ "}"
		+ "var s = atob(__minecraftAssetsBin), n = s.length, a = new Int8Array(n);"
		+ "for (var i = 0; i < n; i++) { a[i] = s.charCodeAt(i); }"
			+ "globalThis.__minecraftAssetsBin = undefined;"
		+ "return a;")
	private static native Int8Array decodeEmbeddedBundle();

	private static byte[] copyOut(Int8Array src) {
		return src.copyToJavaArray();
	}

	@JSBody(params = { "src", "off", "len" }, script = "return src.subarray(off, off + len);")
	private static native Int8Array subarray(Int8Array src, int off, int len);

	private static Int8Array fetchInt8(String path) {
		XMLHttpRequest xhr = getSync(path, true);
		return xhr == null ? null : responseBytes(xhr);
	}

	static @Nullable XMLHttpRequest getSync(String path, boolean binary) {
		if (skipEmbeddedWorkerCatalog(path)) {
			return null;
		}
		try {
			XMLHttpRequest xhr = new XMLHttpRequest();
			xhr.open("GET", WebWorkerTransport.resolve(path), false);
			if (binary) {
				xhr.overrideMimeType("text/plain; charset=x-user-defined");
			}
			xhr.send();
			return xhr.getStatus() == 200 ? xhr : null;
		} catch (Throwable e) {
			return null;
		}
	}

	@JSBody(params = { "path" }, script = "return !!globalThis.__minecraftEmbeddedWorker"
		+ " && (path === 'lang_index.json' || path === 'sounds_index.json');")
	private static native boolean skipEmbeddedWorkerCatalog(String path);

	private static byte @Nullable [] fetchBytes(String path) {
		XMLHttpRequest xhr = getSync(path, true);
		return xhr == null ? null : copyOut(responseBytes(xhr));
	}

	@Nullable
	public static InputStream openClasspath(String path) {
		int i = find(path);
		if (i < 0) {
			return openModClasspath(path);
		}
		byte[] bytes = bytesAt(i);
		return bytes != null ? new ByteArrayInputStream(bytes) : null;
	}

	private static final Map<String, java.io.File> MOD_CLASSPATH = new java.util.HashMap<>();

	public static void registerClasspathResource(String name, java.io.File file) {
		MOD_CLASSPATH.putIfAbsent(name, file);
	}

	@Nullable
	private static InputStream openModClasspath(String path) {
		java.io.File file = MOD_CLASSPATH.get(path.startsWith("/") ? path.substring(1) : path);
		if (file == null) {
			return null;
		}
		try {
			return new java.io.FileInputStream(file);
		} catch (IOException e) {
			return null;
		}
	}

	private static byte @Nullable [] bytesAt(int i) {
		if (offsets[i] < 0) {
			return lengths[i] == 0 ? NO_BYTES : fetchBytes(url(i));
		}
		return jsBundle != null
			? copyOut(subarray(jsBundle, offsets[i], lengths[i]))
			: fetchBytes(url(i));
	}

	private static String url(int i) {
		String object = WebLang.url(index[i]);
		return object != null ? object : index[i];
	}

	private static int find(String path) {
		return Arrays.binarySearch(index(), path);
	}

	private static int lowerBound(String prefix) {
		int at = find(prefix);
		return at >= 0 ? at : -at - 1;
	}

	public static byte @Nullable [] rawAsset(String path) {
		int i = find(path);
		if (i < 0) {
			return null;
		}
		return bytesAt(i);
	}

	private byte[] read(String path) throws IOException {
		byte[] hit = this.cache.get(path);
		if (hit == null) {
			hit = fetchBytes(path);
			if (hit == null) {
				throw new IOException("indexed but not served: " + path);
			}
			this.cache.put(path, hit);
		}
		return hit;
	}

	private static final byte[] NO_BYTES = new byte[0];

	private IoSupplier<InputStream> supplier(int i) {
		if (offsets[i] < 0) {
			return lengths[i] == 0
				? () -> new ByteArrayInputStream(NO_BYTES)
				: () -> new ByteArrayInputStream(this.read(url(i)));
		}
		return () -> new ByteArrayInputStream(bytesAt(i));
	}

	@Nullable
	@Override
	public IoSupplier<InputStream> getRootResource(String... path) {
		return null;
	}

	@Nullable
	@Override
	public IoSupplier<InputStream> getResource(PackType type, Identifier location) {
		String path = type.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath();
		int i = find(path);
		return i >= 0 ? this.supplier(i) : null;
	}

	@Override
	public void listResources(PackType type, String namespace, String directory, PackResources.ResourceOutput output) {
		String root = type.getDirectory() + "/" + namespace + "/";
		String prefix = root + directory + "/";
		String[] paths = index();
		for (int i = lowerBound(prefix); i < paths.length && paths[i].startsWith(prefix); i++) {
			Identifier id = Identifier.tryBuild(namespace, paths[i].substring(root.length()));
			if (id != null) {
				output.accept(id, this.supplier(i));
			}
		}
	}

	@Override
	public Set<String> getNamespaces(PackType type) {
		return this.namespaces;
	}

	@Nullable
	@Override
	public <T> T getMetadataSection(MetadataSectionType<T> metadataSerializer) {
		return this.builtInMetadata.getSection(metadataSerializer).orElse(null);
	}

	@Override
	public PackLocationInfo location() {
		return this.location;
	}

	@Override
	public void close() {
	}
}
