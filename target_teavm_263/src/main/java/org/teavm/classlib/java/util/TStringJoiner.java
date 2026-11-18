// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.util;

import java.util.Objects;

public final class TStringJoiner {
    private final String delimiter;
    private final String prefix;
    private final String suffix;
    private String emptyValue;
    private StringBuilder contents;

    public TStringJoiner(CharSequence delimiter) {
        this(delimiter, "", "");
    }

    public TStringJoiner(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        this.delimiter = Objects.requireNonNull(delimiter).toString();
        this.prefix = Objects.requireNonNull(prefix).toString();
        this.suffix = Objects.requireNonNull(suffix).toString();
        emptyValue = this.prefix + this.suffix;
    }

    public TStringJoiner setEmptyValue(CharSequence value) {
        emptyValue = Objects.requireNonNull(value).toString();
        return this;
    }

    public TStringJoiner add(CharSequence value) {
        String element = String.valueOf(value);
        if (contents == null) contents = new StringBuilder();
        else contents.append(delimiter);
        contents.append(element);
        return this;
    }

    public TStringJoiner merge(TStringJoiner other) {
        Objects.requireNonNull(other);
        if (other.contents != null) add(other.contents.toString());
        return this;
    }

    public int length() {
        return contents == null ? emptyValue.length() : prefix.length() + contents.length() + suffix.length();
    }

    @Override
    public String toString() {
        return contents == null ? emptyValue : prefix + contents + suffix;
    }
}
