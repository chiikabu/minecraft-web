// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebScreenshot {
	private WebScreenshot() {
	}

	public static void save(final String name, final NativeImage image, final Consumer<Component> callback) {
		final int width = image.getWidth();
		final int height = image.getHeight();
		final byte[] rgba;
		try (image) {
			ByteBuffer pixels = image.getPixelBytes().duplicate();
			rgba = new byte[pixels.remaining()];
			pixels.get(rgba);
		} catch (Throwable error) {
			callback.accept(Component.translatable("screenshot.failure", String.valueOf(error)));
			return;
		}

		downloadPng(name, width, height, Int8Array.copyFromJavaArray(rgba), error -> {
			if (error.isEmpty()) {
				Component fileName = Component.literal(name).withStyle(ChatFormatting.UNDERLINE);
				callback.accept(Component.translatable("screenshot.success", fileName));
			} else {
				callback.accept(Component.translatable("screenshot.failure", error));
			}
		});
	}

	@JSFunctor
	private interface DownloadResult extends JSObject {
		void complete(String error);
	}

	@JSBody(params = {"name", "width", "height", "data", "done"}, script = ""
		+ "try {"
		+ "  var bytes = new Uint8ClampedArray(data.buffer, data.byteOffset, data.byteLength);"
		+ "  var canvas = document.createElement('canvas');"
		+ "  canvas.width = width; canvas.height = height;"
		+ "  var context = canvas.getContext('2d');"
		+ "  if (!context) { done('2D canvas is unavailable'); return; }"
		+ "  context.putImageData(new ImageData(bytes, width, height), 0, 0);"
		+ "  canvas.toBlob(function(blob) {"
		+ "    if (!blob) { done('browser PNG encoder returned no data'); return; }"
		+ "    var url = URL.createObjectURL(blob);"
		+ "    var anchor = document.createElement('a');"
		+ "    anchor.href = url; anchor.download = name; anchor.style.display = 'none';"
		+ "    document.body.appendChild(anchor); anchor.click();"
		+ "    setTimeout(function() { document.body.removeChild(anchor); URL.revokeObjectURL(url); }, 1000);"
		+ "    done('');"
		+ "  }, 'image/png');"
		+ "} catch (error) {"
		+ "  done(String(error && error.message ? error.message : error));"
		+ "}")
	private static native void downloadPng(String name, int width, int height, Int8Array data, DownloadResult done);
}
