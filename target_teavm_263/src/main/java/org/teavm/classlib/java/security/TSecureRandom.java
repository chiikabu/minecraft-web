/*
 *  Copyright 2023 Bernd Busse.
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
package org.teavm.classlib.java.security;

import org.teavm.classlib.PlatformDetector;
import org.teavm.classlib.java.util.TRandom;
import org.teavm.jso.crypto.Crypto;
import org.teavm.jso.typedarrays.Uint8Array;

// WEB in-tree override of the vendored TeaVM classlib TSecureRandom.
// Only the WASM-GC (non-JavaScript-backend) fallback in nextBytes() is changed; the JavaScript
// backend behaviour (crypto.getRandomValues) is byte-for-byte identical to the upstream class.
//
// Why: on the WASM-GC backend PlatformDetector.isJavaScript() is false, so the stock class fell
// through to super.nextBytes(). SecureRandom overrides next()->nextBytes(), and TRandom inherits
// RandomGenerator's default nextBytes() which calls nextInt()->next(), so super.nextBytes()
// recurses forever: "RangeError: Maximum call stack size exceeded" the moment any code touches
// SecureRandom (e.g. server encryption keygen). The JavaScript build never hit this because it
// takes the crypto branch.
public class TSecureRandom extends TRandom {

    /** stored instance for seed generation in getSeed() */
    private static TSecureRandom seedGenerator;

    public TSecureRandom() {
    }

    public TSecureRandom(@SuppressWarnings("unused") byte[] seed) {
    }

    public static TSecureRandom getInstance(String algorithm)
            throws TNoSuchAlgorithmException {
        if (!(algorithm.equals("NativePRNG")
                || algorithm.equals("NativePRNGBlocking")
                || algorithm.equals("NativePRNGNonBlocking"))) {
            throw new TNoSuchAlgorithmException();
        }
        return new TSecureRandom();
    }

    public String getAlgorithm() {
        if (PlatformDetector.isJavaScript() && Crypto.isSupported()) {
            return "NativePRNG";
        } else {
            return "unknown";
        }
    }

    @Override
    public void setSeed(@SuppressWarnings("unused") long seed) {
    }

    public void setSeed(@SuppressWarnings("unused") byte[] seed) {
    }

    public void reseed() {
    }

    protected int next(int bits) {
        int numBytes = (bits + 7) / 8;
        byte[] bytes = new byte[numBytes];
        nextBytes(bytes);

        int val = 0;
        for (int i = 0; i < numBytes; ++i) {
            val = (val << 8) | (bytes[i] & 0xFF);
        }

        return val >>> (numBytes * 8 - bits);
    }

    @Override
    public void nextBytes(byte[] bytes) {
        if (PlatformDetector.isJavaScript() && Crypto.isSupported()) {
            var buffer = new Uint8Array(bytes.length);
            Crypto.current().getRandomValues(buffer);

            for (int i = 0; i < bytes.length; ++i) {
                bytes[i] = (byte) buffer.get(i);
            }
        } else {
            // ponytail: WASM-GC has no crypto-entropy binding wired here, so fill directly from
            // Math.random() -- exactly the fallback TUUID.randomUUID() already uses on this backend.
            // MUST NOT call super.nextBytes()/next()/nextInt(): those route back through this
            // method and recurse infinitely (see class note above). Filling the array in place with
            // a self-contained source is the only non-recursive option.
            // Ceiling: Math.random() is not a CSPRNG. Upgrade path: bind crypto.getRandomValues via
            // JSO for the WASM-GC backend (and drop the isJavaScript() gate above) when available.
            for (int i = 0; i < bytes.length; ++i) {
                bytes[i] = (byte) (int) (Math.random() * 256.0);
            }
        }
    }

    @Override
    public int nextInt() {
        return next(32);
    }

    @Override
    public int nextInt(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException();
        }
        return (int) (nextDouble() * n);
    }

    @Override
    public long nextLong() {
        return ((long) nextInt() << 32) | nextInt();
    }

    @Override
    public float nextFloat() {
        return (float) nextDouble();
    }

    @Override
    public double nextDouble() {
        return (((long) next(26) << 27) + next(27)) / (double) (1L << 53);
    }

    public static byte[] getSeed(int numBytes) {
        if (seedGenerator == null) {
            seedGenerator = new TSecureRandom();
        }
        return seedGenerator.generateSeed(numBytes);
    }

    public byte[] generateSeed(int numBytes) {
        if (numBytes < 0) {
            throw new IllegalArgumentException();
        }

        byte[] bytes = new byte[numBytes];
        nextBytes(bytes);
        return bytes;
    }
}
