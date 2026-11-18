// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import dev.browserport.image.PngImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

public final class FaceIcon {
	private static final int SIZE = 64;
	private static final int SCALE = 8;

	private FaceIcon() {
	}

	public static byte[] fromSkinPng(final byte[] skinPng) throws IOException {
		PngImage skin = PngImage.decode(skinPng, 4);
		if (skin.width != 64 || (skin.height != 64 && skin.height != 32)) {
			throw new IOException("not a skin: " + skin.width + "x" + skin.height);
		}
		boolean hatLayer = false;
		for (int y = 0; y < 16 && !hatLayer; y++) {
			for (int x = 32; x < 64; x++) {
				if ((skin.pixels[(y * 64 + x) * 4 + 3] & 255) < 128) {
					hatLayer = true;
					break;
				}
			}
		}
		byte[] face = new byte[8 * 8 * 4];
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				int base = ((8 + y) * 64 + 8 + x) * 4;
				int hat = ((8 + y) * 64 + 40 + x) * 4;
				int out = (y * 8 + x) * 4;
				int alpha = hatLayer ? skin.pixels[hat + 3] & 255 : 0;
				for (int c = 0; c < 3; c++) {
					int under = skin.pixels[base + c] & 255;
					int over = skin.pixels[hat + c] & 255;
					face[out + c] = (byte)((over * alpha + under * (255 - alpha)) / 255);
				}
				face[out + 3] = (byte)255;
			}
		}
		return encode(face);
	}

	private static byte[] encode(final byte[] face) throws IOException {
		int stride = SIZE * 4 + 1;
		byte[] raw = new byte[stride * SIZE];
		for (int y = 0; y < SIZE; y++) {
			raw[y * stride] = 0;
			for (int x = 0; x < SIZE; x++) {
				System.arraycopy(face, ((y / SCALE) * 8 + x / SCALE) * 4, raw, y * stride + 1 + x * 4, 4);
			}
		}
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		deflater.setInput(raw);
		deflater.finish();
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		while (!deflater.finished()) {
			idat.write(buffer, 0, deflater.deflate(buffer));
		}
		deflater.end();

		ByteArrayOutputStream png = new ByteArrayOutputStream();
		png.write(new byte[] { (byte)0x89, 'P', 'N', 'G', 13, 10, 26, 10 });
		byte[] header = new byte[13];
		putInt(header, 0, SIZE);
		putInt(header, 4, SIZE);
		header[8] = 8;
		header[9] = 6;
		chunk(png, "IHDR", header);
		chunk(png, "IDAT", idat.toByteArray());
		chunk(png, "IEND", new byte[0]);
		return png.toByteArray();
	}

	private static void chunk(final ByteArrayOutputStream out, final String type, final byte[] data) {
		byte[] length = new byte[4];
		putInt(length, 0, data.length);
		out.write(length, 0, 4);
		byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		out.write(typeBytes, 0, 4);
		out.write(data, 0, data.length);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		byte[] sum = new byte[4];
		putInt(sum, 0, (int)crc.getValue());
		out.write(sum, 0, 4);
	}

	private static void putInt(final byte[] out, final int at, final int value) {
		out[at] = (byte)(value >>> 24);
		out[at + 1] = (byte)(value >>> 16);
		out[at + 2] = (byte)(value >>> 8);
		out[at + 3] = (byte)value;
	}
}
