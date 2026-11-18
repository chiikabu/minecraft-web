// minecraft-web by chiikabu (https://github.com/chiikabu)

package dev.browserport.image;

import java.io.IOException;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.JSProperty;
import org.teavm.jso.typedarrays.Float64Array;
import org.teavm.jso.typedarrays.Int8Array;
import org.teavm.jso.typedarrays.Int32Array;

public final class BrowserAnimatedImage {
    public record Decoded(int width, int height, int columns, int rows, int[] pixels, double[] delays) { }

    public interface Frames extends JSObject {
        @JSProperty int getWidth();
        @JSProperty int getHeight();
        @JSProperty int getColumns();
        @JSProperty int getRows();
        @JSProperty Int32Array getPixels();
        @JSProperty Float64Array getDelays();
    }

    public static Decoded decode(byte[] bytes, String mime, int maxTextureSize) throws IOException {
        if (bytes.length > 16 * 1024 * 1024) throw new IOException("Animation input exceeds 16 MiB");
        Frames frames = decodeAsync(Int8Array.copyFromJavaArray(bytes), mime, maxTextureSize);
        return new Decoded(frames.getWidth(), frames.getHeight(), frames.getColumns(), frames.getRows(),
                frames.getPixels().copyToJavaArray(), frames.getDelays().copyToJavaArray());
    }

    @Async
    private static native Frames decodeAsync(Int8Array input, String mime, int limit) throws IOException;

    private static void decodeAsync(Int8Array input, String mime, int limit, AsyncCallback<Frames> callback) {
        decodeNative(input, mime, limit, callback::complete, message -> callback.error(new IOException(message)));
    }

    @JSFunctor private interface Success extends JSObject { void accept(Frames frames); }
    @JSFunctor private interface Failure extends JSObject { void accept(String message); }

    @JSBody(params = {"input", "mime", "limit", "success", "failure"}, script = """
        Promise.resolve().then(function() {
            var bytes = new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
            var frames = [];
            var ready = Promise.resolve();
            var width = 0, height = 0, background = [0, 0, 0, 0];
            function require(ok, message) { if (!ok) throw new Error('Animation: ' + message); }
            function range(p, n) { require(p >= 0 && n >= 0 && p + n <= bytes.length, 'truncated data'); }
            function u16(p) { range(p, 2); return bytes[p] | bytes[p + 1] << 8; }
            function u24(p) { range(p, 3); return bytes[p] | bytes[p + 1] << 8 | bytes[p + 2] << 16; }
            function u32(p) { range(p, 4); return (u24(p) + bytes[p + 3] * 16777216) >>> 0; }
            function tag(p) { range(p, 4); return String.fromCharCode(bytes[p], bytes[p + 1], bytes[p + 2], bytes[p + 3]); }
            function dimensions() {
                require(Number.isInteger(limit) && limit > 0 && limit <= 16384, 'invalid texture limit');
                require(width > 0 && height > 0 && width <= limit && height <= limit, 'image exceeds texture limit');
                require(width * height <= 16777216, 'decoded image exceeds 64 MiB');
            }
            function add(frame) {
                dimensions();
                require(frame.w > 0 && frame.h > 0 && frame.x + frame.w <= width && frame.y + frame.h <= height,
                    'frame outside canvas');
                // ponytail: eager preview atlas capped at 64 MiB; stream frames for larger animations.
                require((frames.length + 1) * width * height <= 16777216, 'decoded frames exceed 64 MiB');
                require(frames.length < 4096, 'animation exceeds 4096 frames');
                frames.push(frame);
            }
            if (mime === 'image/gif') {
                range(0, 13);
                require(tag(0) === 'GIF8' && (bytes[4] === 55 || bytes[4] === 57) && bytes[5] === 97, 'invalid GIF header');
                width = u16(6); height = u16(8); dimensions();
                var tableSize = bytes[10] & 128 ? 3 * (2 << (bytes[10] & 7)) : 0;
                range(13, tableSize);
                var palette = bytes.subarray(13, 13 + tableSize);
                if (tableSize) {
                    require(bytes[11] * 3 + 3 <= tableSize, 'invalid background index');
                    var index = bytes[11] * 3;
                    background = [palette[index], palette[index + 1], palette[index + 2], 255];
                }
                var p = 13 + tableSize, control = null, ended = false;
                function subblocks() {
                    while (true) { range(p, 1); var n = bytes[p++]; range(p, n); p += n; if (!n) return; }
                }
                while (p < bytes.length) {
                    var block = bytes[p++];
                    if (block === 59) { ended = true; break; }
                    if (block === 33) {
                        range(p, 1); var extension = bytes[p++];
                        if (extension === 249) {
                            range(p, 6); require(bytes[p] === 4 && bytes[p + 5] === 0, 'invalid GIF control');
                            control = bytes.slice(p - 2, p + 6); p += 6;
                        } else {
                            require(extension !== 1, 'GIF plain-text frames are unsupported');
                            subblocks();
                        }
                        continue;
                    }
                    require(block === 44, 'invalid GIF block');
                    range(p, 9);
                    var x = u16(p), y = u16(p + 2), w = u16(p + 4), h = u16(p + 6);
                    var localSize = bytes[p + 8] & 128 ? 3 * (2 << (bytes[p + 8] & 7)) : 0;
                    var descriptor = bytes.slice(p - 1, p + 9);
                    descriptor.fill(0, 1, 5);
                    p += 9; range(p, localSize + 1);
                    var imageStart = p; p += localSize;
                    require(bytes[p] >= 2 && bytes[p] <= 8, 'invalid GIF code size'); p++; subblocks();
                    var header = bytes.slice(0, 13);
                    header[6] = w & 255; header[7] = w >> 8; header[8] = h & 255; header[9] = h >> 8;
                    var transparent = !!(control && (control[3] & 1));
                    var disposal = control ? (control[3] >> 2) & 7 : 0;
                    require(disposal <= 3, 'reserved GIF disposal');
                    var clear = transparent ? [0, 0, 0, 0] : background;
                    if (!frames.length && transparent) background = clear;
                    var parts = [header, palette];
                    if (control) parts.push(control);
                    parts.push(descriptor, bytes.subarray(imageStart, p), new Uint8Array([59]));
                    add({x: x, y: y, w: w, h: h, delay: control ? (control[4] | control[5] << 8) * 10 : 0,
                        disposal: disposal, clear: clear, blend: true, blob: new Blob(parts, {type: mime})});
                    control = null;
                }
                require(ended && frames.length, 'GIF has no complete frames');
            } else if (mime === 'image/webp') {
                require(tag(0) === 'RIFF' && tag(8) === 'WEBP', 'invalid WebP header');
                var end = u32(4) + 8; require(end === bytes.length, 'invalid WebP size');
                var extended = null, profile = null, animation = false, animHeader = false;
                function riff(parts) {
                    var header = new Uint8Array(12);
                    header.set([82, 73, 70, 70]); header.set([87, 69, 66, 80], 8);
                    new DataView(header.buffer).setUint32(4, 4 + parts.reduce(function(n, b) { return n + b.length; }, 0), true);
                    return new Blob([header].concat(parts), {type: mime});
                }
                for (var p = 12; p < end;) {
                    var name = tag(p), size = u32(p + 4), start = p + 8;
                    range(start, size + (size & 1));
                    if (name === 'VP8X') {
                        require(p === 12 && size === 10, 'invalid WebP extended header');
                        extended = bytes.slice(p, start + size);
                        animation = !!(bytes[start] & 2);
                        width = u24(start + 4) + 1; height = u24(start + 7) + 1; dimensions();
                    } else if (name === 'ICCP') {
                        require(!profile && !frames.length, 'invalid WebP color profile order');
                        profile = bytes.slice(p, start + size + (size & 1));
                    } else if (name === 'ANIM' && animation) {
                        require(size === 6 && !animHeader && !frames.length, 'invalid WebP animation header');
                        background = [bytes[start + 2], bytes[start + 1], bytes[start], bytes[start + 3]];
                        animHeader = true;
                    } else if (name === 'ANMF') {
                        require(animation && animHeader && size >= 16, 'invalid WebP frame');
                        var w = u24(start + 6) + 1, h = u24(start + 9) + 1;
                        var header = extended.slice();
                        header[8] = (extended[8] & 16) | (profile ? 32 : 0);
                        for (var i = 0; i < 3; i++) { header[12 + i] = (w - 1) >>> (8 * i); header[15 + i] = (h - 1) >>> (8 * i); }
                        var payload = bytes.subarray(start + 16, start + size);
                        add({x: u24(start) * 2, y: u24(start + 3) * 2, w: w, h: h, delay: u24(start + 12),
                            disposal: bytes[start + 15] & 1 ? 2 : 0, clear: background,
                            blend: !(bytes[start + 15] & 2),
                            blob: riff([header].concat(profile ? [profile] : [], [payload]))});
                    } else if (!extended && name === 'VP8 ') {
                        require(size >= 10 && bytes[start + 3] === 157 && bytes[start + 4] === 1
                            && bytes[start + 5] === 42, 'invalid WebP key frame');
                        width = u16(start + 6) & 16383; height = u16(start + 8) & 16383; dimensions();
                    } else if (!extended && name === 'VP8L') {
                        require(size >= 5 && bytes[start] === 47, 'invalid lossless WebP header');
                        var bits = u32(start + 1);
                        width = (bits & 16383) + 1; height = ((bits >>> 14) & 16383) + 1; dimensions();
                    }
                    p = start + size + (size & 1);
                }
                if (!animation) {
                    dimensions();
                    ready = createImageBitmap(new Blob([bytes], {type: mime})).then(function(bitmap) {
                        try {
                            width = bitmap.width; height = bitmap.height;
                            add({x: 0, y: 0, w: width, h: height, delay: 0, disposal: 0, blend: false,
                                blob: new Blob([bytes], {type: mime})});
                        } finally { bitmap.close(); }
                    });
                }
            } else { throw new Error('Animation: unsupported image type ' + mime); }
            return ready.then(function() {
            require(frames.length, 'image has no frames');
            var columns = Math.min(frames.length, Math.floor(limit / width));
            var rows = Math.ceil(frames.length / columns);
            require(rows * height <= limit, 'animation atlas exceeds texture limit');
            var atlasWidth = columns * width, atlasHeight = rows * height;
            require(atlasWidth * atlasHeight <= 16777216, 'animation atlas exceeds 64 MiB');
            function canvas(w, h) {
                var result = document.createElement('canvas'); result.width = w; result.height = h;
                var context = result.getContext('2d', {willReadFrequently: true});
                require(context, 'canvas unavailable'); return context;
            }
            var context = canvas(width, height), atlas = canvas(atlasWidth, atlasHeight);
            function clear(frame) {
                context.clearRect(frame.x, frame.y, frame.w, frame.h);
                var color = frame.clear;
                if (color[3]) {
                    context.fillStyle = 'rgba(' + color[0] + ',' + color[1] + ',' + color[2] + ',' + color[3] / 255 + ')';
                    context.fillRect(frame.x, frame.y, frame.w, frame.h);
                }
            }
            clear({x: 0, y: 0, w: width, h: height, clear: background});
            var delays = new Float64Array(frames.length);
            function render(i) {
                if (i === frames.length) {
                    var rgba = atlas.getImageData(0, 0, atlasWidth, atlasHeight).data;
                    var pixels = new Int32Array(atlasWidth * atlasHeight);
                    for (var j = 0; j < pixels.length; j++) {
                        var p = j * 4; pixels[j] = rgba[p + 3] << 24 | rgba[p] << 16 | rgba[p + 1] << 8 | rgba[p + 2];
                    }
                    return {width: width, height: height, columns: columns, rows: rows, pixels: pixels, delays: delays};
                }
                var frame = frames[i];
                var previous = frame.disposal === 3 ? context.getImageData(0, 0, width, height) : null;
                return createImageBitmap(frame.blob).then(function(bitmap) {
                    try {
                        require(bitmap.width === frame.w && bitmap.height === frame.h, 'decoded frame dimensions differ');
                        if (!frame.blend) context.clearRect(frame.x, frame.y, frame.w, frame.h);
                        context.drawImage(bitmap, frame.x, frame.y);
                    } finally { bitmap.close(); }
                    atlas.drawImage(context.canvas, (i % columns) * width, Math.floor(i / columns) * height);
                    delays[i] = frame.delay;
                    if (frame.disposal === 2) clear(frame);
                    else if (previous) context.putImageData(previous, 0, 0);
                    return render(i + 1);
                });
            }
            return render(0);
            });
        }).then(success, function(error) { failure(String(error && error.message || error)); });
        """)
    private static native void decodeNative(Int8Array input, String mime, int limit, Success success, Failure failure);
}
