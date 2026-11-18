// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import static org.lwjgl.system.MemoryUtil.*;

import java.util.HashMap;
import java.util.Map;

public final class SDLPixels {
	public static final int SDL_ALPHA_OPAQUE = 255;
	public static final int SDL_ALPHA_TRANSPARENT = 0;
	public static final float SDL_ALPHA_OPAQUE_FLOAT = 1.0f;
	public static final float SDL_ALPHA_TRANSPARENT_FLOAT = 0.0f;
	public static final int SDL_PIXELTYPE_UNKNOWN = 0;
	public static final int SDL_PIXELTYPE_INDEX1 = 1;
	public static final int SDL_PIXELTYPE_INDEX4 = 2;
	public static final int SDL_PIXELTYPE_INDEX8 = 3;
	public static final int SDL_PIXELTYPE_PACKED8 = 4;
	public static final int SDL_PIXELTYPE_PACKED16 = 5;
	public static final int SDL_PIXELTYPE_PACKED32 = 6;
	public static final int SDL_PIXELTYPE_ARRAYU8 = 7;
	public static final int SDL_PIXELTYPE_ARRAYU16 = 8;
	public static final int SDL_PIXELTYPE_ARRAYU32 = 9;
	public static final int SDL_PIXELTYPE_ARRAYF16 = 10;
	public static final int SDL_PIXELTYPE_ARRAYF32 = 11;
	public static final int SDL_PIXELTYPE_INDEX2 = 12;
	public static final int SDL_BITMAPORDER_NONE = 0;
	public static final int SDL_BITMAPORDER_4321 = 1;
	public static final int SDL_BITMAPORDER_1234 = 2;
	public static final int SDL_PACKEDORDER_NONE = 0;
	public static final int SDL_PACKEDORDER_XRGB = 1;
	public static final int SDL_PACKEDORDER_RGBX = 2;
	public static final int SDL_PACKEDORDER_ARGB = 3;
	public static final int SDL_PACKEDORDER_RGBA = 4;
	public static final int SDL_PACKEDORDER_XBGR = 5;
	public static final int SDL_PACKEDORDER_BGRX = 6;
	public static final int SDL_PACKEDORDER_ABGR = 7;
	public static final int SDL_PACKEDORDER_BGRA = 8;
	public static final int SDL_ARRAYORDER_NONE = 0;
	public static final int SDL_ARRAYORDER_RGB = 1;
	public static final int SDL_ARRAYORDER_RGBA = 2;
	public static final int SDL_ARRAYORDER_ARGB = 3;
	public static final int SDL_ARRAYORDER_BGR = 4;
	public static final int SDL_ARRAYORDER_BGRA = 5;
	public static final int SDL_ARRAYORDER_ABGR = 6;
	public static final int SDL_PACKEDLAYOUT_NONE = 0;
	public static final int SDL_PACKEDLAYOUT_332 = 1;
	public static final int SDL_PACKEDLAYOUT_4444 = 2;
	public static final int SDL_PACKEDLAYOUT_1555 = 3;
	public static final int SDL_PACKEDLAYOUT_5551 = 4;
	public static final int SDL_PACKEDLAYOUT_565 = 5;
	public static final int SDL_PACKEDLAYOUT_8888 = 6;
	public static final int SDL_PACKEDLAYOUT_2101010 = 7;
	public static final int SDL_PACKEDLAYOUT_1010102 = 8;
	public static final int SDL_PIXELFORMAT_UNKNOWN = 0;
	public static final int SDL_PIXELFORMAT_INDEX1LSB = 286261504;
	public static final int SDL_PIXELFORMAT_INDEX1MSB = 287310080;
	public static final int SDL_PIXELFORMAT_INDEX2LSB = 470811136;
	public static final int SDL_PIXELFORMAT_INDEX2MSB = 471859712;
	public static final int SDL_PIXELFORMAT_INDEX4LSB = 303039488;
	public static final int SDL_PIXELFORMAT_INDEX4MSB = 304088064;
	public static final int SDL_PIXELFORMAT_INDEX8 = 318769153;
	public static final int SDL_PIXELFORMAT_RGB332 = 336660481;
	public static final int SDL_PIXELFORMAT_XRGB4444 = 353504258;
	public static final int SDL_PIXELFORMAT_XBGR4444 = 357698562;
	public static final int SDL_PIXELFORMAT_XRGB1555 = 353570562;
	public static final int SDL_PIXELFORMAT_XBGR1555 = 357764866;
	public static final int SDL_PIXELFORMAT_ARGB4444 = 355602434;
	public static final int SDL_PIXELFORMAT_RGBA4444 = 356651010;
	public static final int SDL_PIXELFORMAT_ABGR4444 = 359796738;
	public static final int SDL_PIXELFORMAT_BGRA4444 = 360845314;
	public static final int SDL_PIXELFORMAT_ARGB1555 = 355667970;
	public static final int SDL_PIXELFORMAT_RGBA5551 = 356782082;
	public static final int SDL_PIXELFORMAT_ABGR1555 = 359862274;
	public static final int SDL_PIXELFORMAT_BGRA5551 = 360976386;
	public static final int SDL_PIXELFORMAT_RGB565 = 353701890;
	public static final int SDL_PIXELFORMAT_BGR565 = 357896194;
	public static final int SDL_PIXELFORMAT_RGB24 = 386930691;
	public static final int SDL_PIXELFORMAT_BGR24 = 390076419;
	public static final int SDL_PIXELFORMAT_XRGB8888 = 370546692;
	public static final int SDL_PIXELFORMAT_RGBX8888 = 371595268;
	public static final int SDL_PIXELFORMAT_XBGR8888 = 374740996;
	public static final int SDL_PIXELFORMAT_BGRX8888 = 375789572;
	public static final int SDL_PIXELFORMAT_ARGB8888 = 372645892;
	public static final int SDL_PIXELFORMAT_RGBA8888 = 373694468;
	public static final int SDL_PIXELFORMAT_ABGR8888 = 376840196;
	public static final int SDL_PIXELFORMAT_BGRA8888 = 377888772;
	public static final int SDL_PIXELFORMAT_XRGB2101010 = 370614276;
	public static final int SDL_PIXELFORMAT_XBGR2101010 = 374808580;
	public static final int SDL_PIXELFORMAT_ARGB2101010 = 372711428;
	public static final int SDL_PIXELFORMAT_ABGR2101010 = 376905732;
	public static final int SDL_PIXELFORMAT_RGB48 = 403714054;
	public static final int SDL_PIXELFORMAT_BGR48 = 406859782;
	public static final int SDL_PIXELFORMAT_RGBA64 = 404766728;
	public static final int SDL_PIXELFORMAT_ARGB64 = 405815304;
	public static final int SDL_PIXELFORMAT_BGRA64 = 407912456;
	public static final int SDL_PIXELFORMAT_ABGR64 = 408961032;
	public static final int SDL_PIXELFORMAT_RGB48_FLOAT = 437268486;
	public static final int SDL_PIXELFORMAT_BGR48_FLOAT = 440414214;
	public static final int SDL_PIXELFORMAT_RGBA64_FLOAT = 438321160;
	public static final int SDL_PIXELFORMAT_ARGB64_FLOAT = 439369736;
	public static final int SDL_PIXELFORMAT_BGRA64_FLOAT = 441466888;
	public static final int SDL_PIXELFORMAT_ABGR64_FLOAT = 442515464;
	public static final int SDL_PIXELFORMAT_RGB96_FLOAT = 454057996;
	public static final int SDL_PIXELFORMAT_BGR96_FLOAT = 457203724;
	public static final int SDL_PIXELFORMAT_RGBA128_FLOAT = 455114768;
	public static final int SDL_PIXELFORMAT_ARGB128_FLOAT = 456163344;
	public static final int SDL_PIXELFORMAT_BGRA128_FLOAT = 458260496;
	public static final int SDL_PIXELFORMAT_ABGR128_FLOAT = 459309072;
	public static final int SDL_PIXELFORMAT_YV12 = 842094169;
	public static final int SDL_PIXELFORMAT_IYUV = 1448433993;
	public static final int SDL_PIXELFORMAT_YUY2 = 844715353;
	public static final int SDL_PIXELFORMAT_UYVY = 1498831189;
	public static final int SDL_PIXELFORMAT_YVYU = 1431918169;
	public static final int SDL_PIXELFORMAT_NV12 = 842094158;
	public static final int SDL_PIXELFORMAT_NV21 = 825382478;
	public static final int SDL_PIXELFORMAT_P010 = 808530000;
	public static final int SDL_PIXELFORMAT_EXTERNAL_OES = 542328143;
	public static final int SDL_PIXELFORMAT_MJPG = 1196444237;
	public static final int SDL_COLOR_TYPE_UNKNOWN = 0;
	public static final int SDL_COLOR_TYPE_RGB = 1;
	public static final int SDL_COLOR_TYPE_YCBCR = 2;
	public static final int SDL_COLOR_RANGE_UNKNOWN = 0;
	public static final int SDL_COLOR_RANGE_LIMITED = 1;
	public static final int SDL_COLOR_RANGE_FULL = 2;
	public static final int SDL_COLOR_PRIMARIES_UNKNOWN = 0;
	public static final int SDL_COLOR_PRIMARIES_BT709 = 1;
	public static final int SDL_COLOR_PRIMARIES_UNSPECIFIED = 2;
	public static final int SDL_COLOR_PRIMARIES_BT470M = 4;
	public static final int SDL_COLOR_PRIMARIES_BT470BG = 5;
	public static final int SDL_COLOR_PRIMARIES_BT601 = 6;
	public static final int SDL_COLOR_PRIMARIES_SMPTE240 = 7;
	public static final int SDL_COLOR_PRIMARIES_GENERIC_FILM = 8;
	public static final int SDL_COLOR_PRIMARIES_BT2020 = 9;
	public static final int SDL_COLOR_PRIMARIES_XYZ = 10;
	public static final int SDL_COLOR_PRIMARIES_SMPTE431 = 11;
	public static final int SDL_COLOR_PRIMARIES_SMPTE432 = 12;
	public static final int SDL_COLOR_PRIMARIES_EBU3213 = 22;
	public static final int SDL_COLOR_PRIMARIES_CUSTOM = 31;
	public static final int SDL_TRANSFER_CHARACTERISTICS_UNKNOWN = 0;
	public static final int SDL_TRANSFER_CHARACTERISTICS_BT709 = 1;
	public static final int SDL_TRANSFER_CHARACTERISTICS_UNSPECIFIED = 2;
	public static final int SDL_TRANSFER_CHARACTERISTICS_GAMMA22 = 4;
	public static final int SDL_TRANSFER_CHARACTERISTICS_GAMMA28 = 5;
	public static final int SDL_TRANSFER_CHARACTERISTICS_BT601 = 6;
	public static final int SDL_TRANSFER_CHARACTERISTICS_SMPTE240 = 7;
	public static final int SDL_TRANSFER_CHARACTERISTICS_LINEAR = 8;
	public static final int SDL_TRANSFER_CHARACTERISTICS_LOG100 = 9;
	public static final int SDL_TRANSFER_CHARACTERISTICS_LOG100_SQRT10 = 10;
	public static final int SDL_TRANSFER_CHARACTERISTICS_IEC61966 = 11;
	public static final int SDL_TRANSFER_CHARACTERISTICS_BT1361 = 12;
	public static final int SDL_TRANSFER_CHARACTERISTICS_SRGB = 13;
	public static final int SDL_TRANSFER_CHARACTERISTICS_BT2020_10BIT = 14;
	public static final int SDL_TRANSFER_CHARACTERISTICS_BT2020_12BIT = 15;
	public static final int SDL_TRANSFER_CHARACTERISTICS_PQ = 16;
	public static final int SDL_TRANSFER_CHARACTERISTICS_SMPTE428 = 17;
	public static final int SDL_TRANSFER_CHARACTERISTICS_HLG = 18;
	public static final int SDL_TRANSFER_CHARACTERISTICS_CUSTOM = 31;
	public static final int SDL_MATRIX_COEFFICIENTS_IDENTITY = 0;
	public static final int SDL_MATRIX_COEFFICIENTS_BT709 = 1;
	public static final int SDL_MATRIX_COEFFICIENTS_UNSPECIFIED = 2;
	public static final int SDL_MATRIX_COEFFICIENTS_FCC = 4;
	public static final int SDL_MATRIX_COEFFICIENTS_BT470BG = 5;
	public static final int SDL_MATRIX_COEFFICIENTS_BT601 = 6;
	public static final int SDL_MATRIX_COEFFICIENTS_SMPTE240 = 7;
	public static final int SDL_MATRIX_COEFFICIENTS_YCGCO = 8;
	public static final int SDL_MATRIX_COEFFICIENTS_BT2020_NCL = 9;
	public static final int SDL_MATRIX_COEFFICIENTS_BT2020_CL = 10;
	public static final int SDL_MATRIX_COEFFICIENTS_SMPTE2085 = 11;
	public static final int SDL_MATRIX_COEFFICIENTS_CHROMA_DERIVED_NCL = 12;
	public static final int SDL_MATRIX_COEFFICIENTS_CHROMA_DERIVED_CL = 13;
	public static final int SDL_MATRIX_COEFFICIENTS_ICTCP = 14;
	public static final int SDL_MATRIX_COEFFICIENTS_CUSTOM = 31;
	public static final int SDL_CHROMA_LOCATION_NONE = 0;
	public static final int SDL_CHROMA_LOCATION_LEFT = 1;
	public static final int SDL_CHROMA_LOCATION_CENTER = 2;
	public static final int SDL_CHROMA_LOCATION_TOPLEFT = 3;
	public static final int SDL_COLORSPACE_UNKNOWN = 0;
	public static final int SDL_COLORSPACE_SRGB = 301991328;
	public static final int SDL_COLORSPACE_SRGB_LINEAR = 301991168;
	public static final int SDL_COLORSPACE_HDR10 = 301999616;
	public static final int SDL_COLORSPACE_JPEG = 570426566;
	public static final int SDL_COLORSPACE_BT601_LIMITED = 554703046;
	public static final int SDL_COLORSPACE_BT601_FULL = 571480262;
	public static final int SDL_COLORSPACE_BT709_LIMITED = 554697761;
	public static final int SDL_COLORSPACE_BT709_FULL = 571474977;
	public static final int SDL_COLORSPACE_BT2020_LIMITED = 554706441;
	public static final int SDL_COLORSPACE_BT2020_FULL = 571483657;
	public static final int SDL_COLORSPACE_RGB_DEFAULT = 301991328;
	public static final int SDL_COLORSPACE_YUV_DEFAULT = 554703046;
	public static final int SDL_PIXELFORMAT_RGBA32 = SDL_PIXELFORMAT_ABGR8888;
	public static final int SDL_PIXELFORMAT_ARGB32 = SDL_PIXELFORMAT_BGRA8888;
	public static final int SDL_PIXELFORMAT_BGRA32 = SDL_PIXELFORMAT_ARGB8888;
	public static final int SDL_PIXELFORMAT_ABGR32 = SDL_PIXELFORMAT_RGBA8888;
	public static final int SDL_PIXELFORMAT_RGBX32 = SDL_PIXELFORMAT_XBGR8888;
	public static final int SDL_PIXELFORMAT_XRGB32 = SDL_PIXELFORMAT_BGRX8888;
	public static final int SDL_PIXELFORMAT_BGRX32 = SDL_PIXELFORMAT_XRGB8888;
	public static final int SDL_PIXELFORMAT_XBGR32 = SDL_PIXELFORMAT_RGBX8888;

	private static final String[] PACKED_ORDER = {"", "XRGB", "RGBX", "ARGB", "RGBA", "XBGR", "BGRX", "ABGR", "BGRA"};
	private static final Map<Integer, Long> details = new HashMap<>();

	private SDLPixels() {
	}

	private static boolean isPacked8888(int format) {
		return SDL_PIXELTYPE(format) == SDL_PIXELTYPE_PACKED32 && SDL_PIXELLAYOUT(format) == SDL_PACKEDLAYOUT_8888
			&& SDL_PIXELORDER(format) > 0 && SDL_PIXELORDER(format) < PACKED_ORDER.length;
	}

	public static SDL_PixelFormatDetails SDL_GetPixelFormatDetails(int format) {
		Long cached = details.get(format);
		if (cached != null) {
			return SDL_PixelFormatDetails.create(cached);
		}
		if (!isPacked8888(format)) {
			SDLError.set("Unsupported pixel format 0x" + Integer.toHexString(format));
			return null;
		}
		long d = nmemCalloc(1, SDL_PixelFormatDetails.SIZEOF);
		memPutInt(d + SDL_PixelFormatDetails.FORMAT, format);
		memPutByte(d + SDL_PixelFormatDetails.BITS_PER_PIXEL, (byte) 32);
		memPutByte(d + SDL_PixelFormatDetails.BYTES_PER_PIXEL, (byte) 4);
		String order = PACKED_ORDER[SDL_PIXELORDER(format)];
		for (int i = 0; i < 4; i++) {
			int shift = 24 - 8 * i;
			int mask = 0xFF << shift;
			switch (order.charAt(i)) {
				case 'R':
					component(d, SDL_PixelFormatDetails.RMASK, SDL_PixelFormatDetails.RBITS, SDL_PixelFormatDetails.RSHIFT, mask, shift);
					break;
				case 'G':
					component(d, SDL_PixelFormatDetails.GMASK, SDL_PixelFormatDetails.GBITS, SDL_PixelFormatDetails.GSHIFT, mask, shift);
					break;
				case 'B':
					component(d, SDL_PixelFormatDetails.BMASK, SDL_PixelFormatDetails.BBITS, SDL_PixelFormatDetails.BSHIFT, mask, shift);
					break;
				case 'A':
					component(d, SDL_PixelFormatDetails.AMASK, SDL_PixelFormatDetails.ABITS, SDL_PixelFormatDetails.ASHIFT, mask, shift);
					break;
				default:
					break;
			}
		}
		details.put(format, d);
		return SDL_PixelFormatDetails.create(d);
	}

	private static void component(long d, int maskOffset, int bitsOffset, int shiftOffset, int mask, int shift) {
		memPutInt(d + maskOffset, mask);
		memPutByte(d + bitsOffset, (byte) 8);
		memPutByte(d + shiftOffset, (byte) shift);
	}

	public static String SDL_GetPixelFormatName(int format) {
		if (isPacked8888(format)) {
			return "SDL_PIXELFORMAT_" + PACKED_ORDER[SDL_PIXELORDER(format)] + "8888";
		}
		return "SDL_PIXELFORMAT_UNKNOWN";
	}

	public static int SDL_DEFINE_PIXELFORMAT(int type, int order, int layout, int bits, int bytes) {
		return (1 << 28) | (type << 24) | (order << 20) | (layout << 16) | (bits << 8) | bytes;
	}

	public static int SDL_PIXELFLAG(int format) {
		return (format >> 28) & 0x0F;
	}

	public static int SDL_PIXELTYPE(int format) {
		return (format >> 24) & 0x0F;
	}

	public static int SDL_PIXELORDER(int format) {
		return (format >> 20) & 0x0F;
	}

	public static int SDL_PIXELLAYOUT(int format) {
		return (format >> 16) & 0x0F;
	}

	public static int SDL_BITSPERPIXEL(int format) {
		return SDL_ISPIXELFORMAT_FOURCC(format) ? 0 : (format >> 8) & 0xFF;
	}

	public static int SDL_BYTESPERPIXEL(int format) {
		return SDL_ISPIXELFORMAT_FOURCC(format) ? 0 : format & 0xFF;
	}

	public static boolean SDL_ISPIXELFORMAT_FOURCC(int format) {
		return format != 0 && SDL_PIXELFLAG(format) != 1;
	}
}
