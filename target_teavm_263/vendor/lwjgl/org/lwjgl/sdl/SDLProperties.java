// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.lwjgl.sdl;

import java.util.HashMap;
import java.util.Map;

public final class SDLProperties {
    private record Value(int type, Object value) { }
    private static final Map<Integer, Map<String, Value>> properties = new HashMap<>();
    private static int nextId;

    private SDLProperties() { }

    public static int SDL_CreateProperties() {
        do { nextId++; } while (nextId == 0 || properties.containsKey(nextId));
        properties.put(nextId, new HashMap<>());
        return nextId;
    }

    public static void SDL_DestroyProperties(int id) {
        properties.remove(id);
    }

    public static boolean SDL_SetStringProperty(int id, CharSequence name, CharSequence value) {
        return set(id, name, 2, value == null ? null : string(value));
    }

    public static boolean SDL_SetPointerProperty(int id, CharSequence name, long value) {
        return set(id, name, 1, value == 0 ? null : value);
    }

    public static boolean SDL_SetNumberProperty(int id, CharSequence name, long value) {
        return set(id, name, 3, value);
    }

    private static boolean set(int id, CharSequence name, int type, Object value) {
        Map<String, Value> group = properties.get(id);
        if (group == null || name == null || name.length() == 0) return false;
        String key = string(name);
        if (key.isEmpty()) return false;
        if (value == null) group.remove(key);
        else group.put(key, new Value(type, value));
        return true;
    }

    private static Value get(int id, CharSequence name) {
        Map<String, Value> group = properties.get(id);
        return group == null || name == null ? null : group.get(string(name));
    }

    public static String SDL_GetStringProperty(int id, CharSequence name, CharSequence fallback) {
        Value value = get(id, name);
        if (value != null && (value.type == 2 || value.type == 3)) return value.value.toString();
        return fallback == null ? null : string(fallback);
    }

    public static long SDL_GetPointerProperty(int id, CharSequence name, long fallback) {
        Value value = get(id, name);
        return value != null && value.type == 1 ? (Long) value.value : fallback;
    }

    public static long SDL_GetNumberProperty(int id, CharSequence name, long fallback) {
        Value value = get(id, name);
        if (value != null && value.type == 3) return (Long) value.value;
        if (value != null && value.type == 2) return number((String) value.value);
        return fallback;
    }

    private static String string(CharSequence value) {
        String text = value.toString();
        int end = text.indexOf('\0');
        return end < 0 ? text : text.substring(0, end);
    }

    private static long number(String text) {
        int i = 0;
        while (i < text.length() && (text.charAt(i) == ' ' || (text.charAt(i) >= '\t' && text.charAt(i) <= '\r'))) i++;
        boolean negative = i < text.length() && text.charAt(i) == '-';
        if (i < text.length() && (text.charAt(i) == '-' || text.charAt(i) == '+')) i++;
        int radix = 10;
        if (i < text.length() && text.charAt(i) == '0') {
            radix = 8;
            if (i + 1 < text.length() && (text.charAt(i + 1) == 'x' || text.charAt(i + 1) == 'X')) {
                radix = 16; i += 2;
            }
        }
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long result = 0;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            int digit = c <= 127 ? Character.digit(c, radix) : -1;
            if (digit < 0) break;
            if (result < limit / radix || result * radix < limit + digit) return negative ? Long.MIN_VALUE : Long.MAX_VALUE;
            result = result * radix - digit;
        }
        return negative ? result : -result;
    }

    static boolean exists(int id) { return properties.containsKey(id); }
}
