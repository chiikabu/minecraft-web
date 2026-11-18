/*
 *  Copyright 2015 Alexey Andreev.
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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.teavm.classlib.PlatformDetector;
import org.teavm.classlib.java.lang.TComparable;
import org.teavm.jso.crypto.Crypto;

public class TUUID implements TComparable<TUUID> {
    private String value;

    private TUUID(String value) {
        this.value = value;
    }

    public TUUID(long mostSigBits, long leastSigBits) {
        this.value = digits(mostSigBits >> 32, 8) + "-"
                + digits(mostSigBits >> 16, 4) + "-"
                + digits(mostSigBits, 4) + "-"
                + digits(leastSigBits >> 48, 4) + "-"
                + digits(leastSigBits, 12);
    }

    private static String digits(long val, int digits) {
        long hi = 1L << (digits * 4);
        return Long.toHexString(hi | (val & (hi - 1))).substring(1);
    }

    public long getMostSignificantBits() {
        String[] components = value.split("-");
        long msb = Long.parseLong(components[0], 16);
        msb <<= 16;
        msb |= Long.parseLong(components[1], 16);
        msb <<= 16;
        msb |= Long.parseLong(components[2], 16);
        return msb;
    }

    public long getLeastSignificantBits() {
        String[] components = value.split("-");
        long lsb = Long.parseLong(components[3], 16);
        lsb <<= 48;
        lsb |= Long.parseLong(components[4], 16);
        return lsb;
    }

    public int version() {
        return (int) ((getMostSignificantBits() >> 12) & 0x0f);
    }

    @Override
    public int compareTo(TUUID val) {
        int cmp = Long.compare(getMostSignificantBits(), val.getMostSignificantBits());
        return cmp != 0 ? cmp : Long.compare(getLeastSignificantBits(), val.getLeastSignificantBits());
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TUUID) {
            TUUID other = (TUUID) o;
            return value.equals(other.value);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    public static TUUID randomUUID() {
        if (PlatformDetector.isJavaScript() && Crypto.isSupported()) {
            return new TUUID(Crypto.current().randomUUID());
        } else {
            String value = s4() + s4() + "-" + s4() + "-" + s4() + "-" + s4() + "-" + s4() + s4() + s4();
            return new TUUID(value);
        }
    }

    private static String s4() {
        return Integer.toString((int) Math.floor((1 + Math.random()) * 65536), 16).substring(1);
    }

    /**
     * As {@code java.util.UUID.fromString}: five hex groups separated by dashes, anything else is an
     * {@link IllegalArgumentException}.
     *
     * <p>This used to wrap any string unchecked. Minecraft asks "is this a UUID or a player name?"
     * by calling it and catching the exception ({@code EntitySelectorParser.parseNameOrUUID}), so
     * every bare player name in a command was taken for a UUID: {@code /tp Alex}, {@code /kick Alex},
     * {@code /gamemode creative Alex} all answered "No entity was found". Nobody types another
     * player's name in singleplayer; a LAN host does. The result is also put into the canonical
     * lower-case form, because equality here compares the text.
     */
    public static TUUID fromString(String value) {
        int length = value.length();
        if (length == 36 && isCanonical(value)) {
            return new TUUID(value);
        }
        if (length > 36) {
            throw new IllegalArgumentException("UUID string too large");
        }
        int dash1 = value.indexOf('-');
        int dash2 = value.indexOf('-', dash1 + 1);
        int dash3 = value.indexOf('-', dash2 + 1);
        int dash4 = value.indexOf('-', dash3 + 1);
        int dash5 = value.indexOf('-', dash4 + 1);
        if (dash1 < 0 || dash2 < 0 || dash3 < 0 || dash4 < 0 || dash5 >= 0) {
            throw new IllegalArgumentException("Invalid UUID string: " + value);
        }
        // Long.parseLong throws NumberFormatException, an IllegalArgumentException, on a bad group.
        long msb = Long.parseLong(value.substring(0, dash1), 16) & 0xffffffffL;
        msb <<= 16;
        msb |= Long.parseLong(value.substring(dash1 + 1, dash2), 16) & 0xffffL;
        msb <<= 16;
        msb |= Long.parseLong(value.substring(dash2 + 1, dash3), 16) & 0xffffL;
        long lsb = Long.parseLong(value.substring(dash3 + 1, dash4), 16) & 0xffffL;
        lsb <<= 48;
        lsb |= Long.parseLong(value.substring(dash4 + 1), 16) & 0xffffffffffffL;
        return new TUUID(msb, lsb);
    }

    /** 8-4-4-4-12 lower-case hex: already the text {@link #TUUID(long, long)} would produce. */
    private static boolean isCanonical(String value) {
        for (int i = 0; i < 36; i++) {
            char c = value.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
            } else if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    public static TUUID nameUUIDFromBytes(byte[] name) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException nsae) {
            throw new RuntimeException("MD5 not supported", nsae);
        }
        byte[] md5Bytes = md.digest(name);
        md5Bytes[6] &= 0x0f;
        md5Bytes[6] |= 0x30;
        md5Bytes[8] &= 0x3f;
        md5Bytes[8] |= 0x80;
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (md5Bytes[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (md5Bytes[i] & 0xff);
        }
        return new TUUID(msb, lsb);
    }
}
