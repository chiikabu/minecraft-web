// minecraft-web by chiikabu (https://github.com/chiikabu)

package io.netty.util;

public final class AsciiString {
    private AsciiString() { }
    public static char b2c(byte value) { return (char) (value & 0xff); }
}
