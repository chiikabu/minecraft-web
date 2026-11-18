// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebBlobAssetStore {

	private WebBlobAssetStore() {
	}

	@JSBody(params = {}, script = "return typeof __minecraftBlobStore !== 'undefined' && !!__minecraftBlobStore"
		+ " && typeof __minecraftHydrateAssets === 'function';")
	public static native boolean available();

	@JSBody(params = { "key", "bytes" }, script = "if (typeof __minecraftBlobStore !== 'undefined' && __minecraftBlobStore) {"
		+ "  try { Promise.resolve(__minecraftBlobStore.put(key, bytes)).catch(function () {}); } catch (e) {}"
		+ "}")
	public static native void persist(String key, Int8Array bytes);
}
