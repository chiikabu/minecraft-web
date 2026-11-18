/*
 *  Copyright 2023 ihromant.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.classlib.java.util;

import org.teavm.classlib.impl.Base64Impl;

public final class TBase64 {
    private static final byte[] MIME_CRLF = { (byte) '\r', (byte) '\n' };
    private static final int MIME_DEFAULT_LINE_LENGTH = 76;

    private TBase64() {
    }

    public static Encoder getEncoder() {
        return new Encoder(Base64Impl.alphabet, true, -1, null);
    }

    public static Encoder getUrlEncoder() {
        return new Encoder(Base64Impl.urlAlphabet, false, -1, null);
    }

    public static Encoder getMimeEncoder() {
        return new Encoder(Base64Impl.alphabet, true, MIME_DEFAULT_LINE_LENGTH, MIME_CRLF);
    }

    public static Encoder getMimeEncoder(int lineLength, byte[] lineSeparator) {
        for (byte b : lineSeparator) {
            int c = b & 0xFF;
            if (Base64Impl.reverse[c] != -1 || b == '=') {
                throw new IllegalArgumentException("Illegal base64 line separator character 0x"
                        + Integer.toString(c, 16));
            }
        }
        return new Encoder(Base64Impl.alphabet, true, (lineLength / 4) * 4, lineSeparator.clone());
    }

    public static class Encoder {
        private final byte[] mapping;
        private final boolean padding;
        private final int lineLength;
        private final byte[] lineSeparator;

        private Encoder(byte[] mapping, boolean padding, int lineLength, byte[] lineSeparator) {
            this.mapping = mapping;
            this.padding = padding;
            this.lineLength = lineLength;
            this.lineSeparator = lineSeparator;
        }

        public byte[] encode(byte[] src) {
            var out = Base64Impl.encode(src, mapping, padding);
            if (lineLength <= 0 || lineSeparator == null || out.length <= lineLength) {
                return out;
            }
            var sepCount = (out.length - 1) / lineLength;
            var result = new byte[out.length + sepCount * lineSeparator.length];
            var srcPos = 0;
            var dstPos = 0;
            for (var i = 0; i < sepCount; ++i) {
                System.arraycopy(out, srcPos, result, dstPos, lineLength);
                srcPos += lineLength;
                dstPos += lineLength;
                System.arraycopy(lineSeparator, 0, result, dstPos, lineSeparator.length);
                dstPos += lineSeparator.length;
            }
            System.arraycopy(out, srcPos, result, dstPos, out.length - srcPos);
            return result;
        }

        public String encodeToString(byte[] src) {
            var bytes = encode(src);
            var chars = new char[bytes.length];
            for (var i = 0; i < bytes.length; ++i) {
                chars[i] = (char) (bytes[i] & 0xFF);
            }
            return new String(chars);
        }

        public Encoder withoutPadding() {
            return new Encoder(mapping, false, lineLength, lineSeparator);
        }
    }

    public static Decoder getDecoder() {
        return new Decoder(Base64Impl.reverse, false);
    }

    public static Decoder getUrlDecoder() {
        return new Decoder(Base64Impl.urlReverse, false);
    }

    public static Decoder getMimeDecoder() {
        return new Decoder(Base64Impl.reverse, true);
    }

    public static class Decoder {
        private final int[] mapping;
        private final boolean mime;

        private Decoder(int[] mapping, boolean mime) {
            this.mapping = mapping;
            this.mime = mime;
        }

        public byte[] decode(byte[] src) {
            return Base64Impl.decode(mime ? stripNonAlphabet(src) : src, mapping);
        }

        public byte[] decode(String src) {
            var bytes = new byte[src.length()];
            for (var i = 0; i < bytes.length; ++i) {
                bytes[i] = (byte) src.charAt(i);
            }
            return decode(bytes);
        }

        // mime decode: drop anything not in the alphabet (line separators, whitespace, ...) and keep padding
        private byte[] stripNonAlphabet(byte[] src) {
            var tmp = new byte[src.length];
            var n = 0;
            for (var b : src) {
                int c = b & 0xFF;
                if (mapping[c] != -1 || b == '=') {
                    tmp[n++] = b;
                }
            }
            if (n == tmp.length) {
                return tmp;
            }
            var out = new byte[n];
            System.arraycopy(tmp, 0, out, 0, n);
            return out;
        }
    }
}
