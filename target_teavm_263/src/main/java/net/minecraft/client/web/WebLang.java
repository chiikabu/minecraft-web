// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.resources.language.LanguageInfo;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSString;
import org.teavm.jso.ajax.XMLHttpRequest;

public final class WebLang {
	private static final String INDEX = Credits.open(16151, 30244, 50378, 23866, 65117, 56930, 20172, 9235, 4528, 32885, 63702, 55465, 13951, 63348, 22922);
	private static final String PREFIX = "assets/minecraft/lang/";

	private static @Nullable Map<String, String> hashes;
	private static String[] assetPaths = new String[0];
	private static @Nullable Map<String, LanguageInfo> languages;

	private WebLang() {
	}

	private static Map<String, String> hashes() {
		if (hashes == null) {
			Map<String, String> map = new HashMap<>();
			Map<String, LanguageInfo> langs = new HashMap<>();
			langs.put("en_us", new LanguageInfo("US", "English", false));
			LangIndexJs parsed = fetch();
			if (parsed != null) {
				JSArrayReader<JSString> codes = parsed.getCodes();
				JSArrayReader<JSString> shas = parsed.getHashes();
				String[] paths = new String[codes.getLength()];
				for (int i = 0; i < paths.length; i++) {
					paths[i] = PREFIX + codes.get(i).stringValue() + ".json";
					map.put(paths[i], shas.get(i).stringValue());
				}
				assetPaths = paths;

				JSArrayReader<JSString> infoCodes = parsed.getInfoCodes();
				JSArrayReader<JSString> names = parsed.getNames();
				JSArrayReader<JSString> regions = parsed.getRegions();
				JSArrayReader<JSString> rtl = parsed.getBidirectional();
				for (int i = 0; i < infoCodes.getLength(); i++) {
					langs.put(
						infoCodes.get(i).stringValue(),
						new LanguageInfo(regions.get(i).stringValue(), names.get(i).stringValue(), "1".equals(rtl.get(i).stringValue()))
					);
				}
			}
			hashes = map;
			languages = langs;
			System.out.println("[26.3-web] lang_index.json: " + langs.size() + " languages, " + map.size() + " fetchable");
		}
		return hashes;
	}

	public static String[] assetPaths() {
		hashes();
		return assetPaths;
	}

	public static @Nullable String url(String assetPath) {
		if (!assetPath.startsWith(PREFIX)) {
			return null;
		}
		String sha1 = hashes().get(assetPath);
		return sha1 == null ? null : "objects/" + sha1.substring(0, 2) + "/" + sha1;
	}

	public static Map<String, LanguageInfo> languages() {
		hashes();
		return languages;
	}

	private static @Nullable LangIndexJs fetch() {
		XMLHttpRequest xhr = WebPackResources.getSync(INDEX, false);
		return xhr == null ? null : parse(xhr.getResponseText());
	}

	private interface LangIndexJs extends JSObject {
		@JSProperty
		JSArrayReader<JSString> getCodes();

		@JSProperty
		JSArrayReader<JSString> getHashes();

		@JSProperty
		JSArrayReader<JSString> getInfoCodes();

		@JSProperty
		JSArrayReader<JSString> getNames();

		@JSProperty
		JSArrayReader<JSString> getRegions();

		@JSProperty
		JSArrayReader<JSString> getBidirectional();
	}

	@JSBody(params = { "text" }, script = "var o = JSON.parse(text);"
		+ "var f = o.files || {}; var codes = Object.keys(f).sort();"
		+ "var hashes = new Array(codes.length);"
		+ "for (var i = 0; i < codes.length; i++) { hashes[i] = f[codes[i]]; }"
		+ "var l = o.languages || {}; var infoCodes = Object.keys(l);"
		+ "var names = [], regions = [], bidi = [];"
		+ "for (var j = 0; j < infoCodes.length; j++) {"
		+ "  var e = l[infoCodes[j]] || {};"
		+ "  names.push(e.name || infoCodes[j]);"
		+ "  regions.push(e.region || '');"
		+ "  bidi.push(e.bidirectional ? '1' : '0');"
		+ "}"
		+ "return { codes: codes, hashes: hashes, infoCodes: infoCodes,"
		+ "         names: names, regions: regions, bidirectional: bidi };")
	private static native LangIndexJs parse(String text);
}
