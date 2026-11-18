// minecraft-web by chiikabu (https://github.com/chiikabu)

package check;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.teavm.classlib.java.io.TReader;
import org.teavm.classlib.java.lang.TObject;
import org.teavm.classlib.java.nio.TByteBuffer;
import org.teavm.classlib.java.nio.TByteOrder;

public final class ClasslibApiBackportCheck {
    private static final Method READ_ALL_AS_STRING = readAllAsStringReference();

    public static void main(String[] args) throws Exception {
        checkReader();
        checkByteBuffer();
        checkDuration();
        System.out.println("OK: 26.3 class-library backports match Java 25");
    }

    private static void checkReader() throws Exception {
        for (String text : Arrays.asList("", "a\r\nb", "x\ud83d\ude00y", "z".repeat(9000))) {
            for (int consumed : new int[] { 0, Math.min(1, text.length()), text.length() }) {
                StringReader expected = new StringReader(text);
                Reader actual = new Reader(text);
                expected.skip(consumed);
                actual.skip(consumed);
                equal(readAllAsString(expected), actual.readAllAsString(), "reader");
                equal(readAllAsString(expected), actual.readAllAsString(), "reader EOF");
            }
        }
        try {
            new FailingReader().readAllAsString();
            throw new AssertionError("reader accepted an injected IOException");
        } catch (IOException expected) {
            equal("injected", expected.getMessage(), "reader exception");
        }
    }

    private static Method readAllAsStringReference() {
        try {
            return java.io.Reader.class.getMethod("readAllAsString");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Run this check on Java 25 or newer", e);
        }
    }

    private static String readAllAsString(java.io.Reader reader) {
        try {
            return (String) READ_ALL_AS_STRING.invoke(reader);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Java 25 Reader.readAllAsString invocation failed", e);
        }
    }

    private static void checkByteBuffer() {
        for (boolean direct : new boolean[] { false, true }) {
            ByteBuffer expected = direct ? ByteBuffer.allocateDirect(8) : ByteBuffer.allocate(8);
            TByteBuffer actual = direct ? TByteBuffer.allocateDirect(8) : TByteBuffer.allocate(8);
            for (int i = 0; i < 8; i++) {
                expected.put(i, (byte) i);
                actual.put(i, (byte) i);
            }
            expected.order(ByteOrder.LITTLE_ENDIAN);
            actual.order(TByteOrder.LITTLE_ENDIAN);
            expected.position(5).limit(7).mark();
            actual.position(5).limit(7).mark();
            ByteBuffer expectedSlice = expected.slice(2, 3);
            TByteBuffer actualSlice = actual.slice(2, 3);
            equal(0, actualSlice.position(), "slice position");
            equal(3, actualSlice.limit(), "slice limit");
            equal(3, actualSlice.capacity(), "slice capacity");
            equal(ByteOrder.BIG_ENDIAN.toString(), actualSlice.order().toString(), "slice order");
            equal(expectedSlice.isDirect(), actualSlice.isDirect(), "slice direct");
            equal(expectedSlice.isReadOnly(), actualSlice.isReadOnly(), "slice read-only");
            actualSlice.put(0, (byte) 42);
            equal((byte) 42, actual.get(2), "slice shared storage");
            equal(TByteOrder.LITTLE_ENDIAN, actual.order(), "original order");
            equal(5, actual.position(), "original position");
            equal(7, actual.limit(), "original limit");
            actual.reset();
            equal(5, actual.position(), "original mark");
            if (!direct) {
                equal(expectedSlice.arrayOffset(), actualSlice.arrayOffset(), "slice array offset");
            }
            compareSlice(expected, actual, 0, 0, "empty start");
            compareSlice(expected, actual, 7, 0, "empty boundary");
            compareSlice(expected, actual, 0, 7, "full limit");
        }
        TByteBuffer readOnly = TByteBuffer.wrap(new byte[4]).asReadOnlyBuffer().slice(1, 2);
        equal(true, readOnly.isReadOnly(), "slice read-only preservation");

        TByteBuffer base = TByteBuffer.allocate(10);
        for (int i = 0; i < 10; i++) {
            base.put(i, (byte) i);
        }
        TByteBuffer outer = base.slice(1, 8);
        outer.order(TByteOrder.LITTLE_ENDIAN).position(5).limit(7).mark();
        TByteBuffer inner = outer.slice(2, 3);
        inner.put(0, (byte) 99);
        equal((byte) 99, base.get(3), "nested slice shared storage");
        equal(TByteOrder.LITTLE_ENDIAN, outer.order(), "nested source order");
        equal(5, outer.position(), "nested source position");
        equal(7, outer.limit(), "nested source limit");
        outer.reset();
        equal(5, outer.position(), "nested source mark");

        for (int[] range : new int[][] {
                { -1, 1 }, { 0, -1 }, { 3, 2 }, { 1, Integer.MAX_VALUE }, { Integer.MAX_VALUE, 1 }
        }) {
            TByteBuffer source = TByteBuffer.allocate(4).position(2).limit(3).mark();
            try {
                source.slice(range[0], range[1]);
                throw new AssertionError("slice accepted " + Arrays.toString(range));
            } catch (IndexOutOfBoundsException expected) {
                equal(2, source.position(), "invalid range position");
                equal(3, source.limit(), "invalid range limit");
                source.reset();
                equal(2, source.position(), "invalid range mark");
            }
        }
    }

    private static void compareSlice(ByteBuffer expected, TByteBuffer actual, int index, int length, String label) {
        ByteBuffer expectedSlice = expected.slice(index, length);
        TByteBuffer actualSlice = actual.slice(index, length);
        equal(expectedSlice.position(), actualSlice.position(), label + " position");
        equal(expectedSlice.limit(), actualSlice.limit(), label + " limit");
        equal(expectedSlice.capacity(), actualSlice.capacity(), label + " capacity");
        equal(expectedSlice.order().toString(), actualSlice.order().toString(), label + " order");
    }

    private static void checkDuration() {
        long[][] values = {
                { 0, 0 }, { 0, 1 }, { 0, 999_999_999 }, { 1, 0 }, { Long.MAX_VALUE, 999_999_999 },
                { -1, 0 }, { -1, 999_999_999 }, { Long.MIN_VALUE, 0 }
        };
        for (long[] value : values) {
            boolean expected = java.time.Duration.ofSeconds(value[0], value[1]).isPositive();
            boolean actual = org.threeten.bp.Duration.ofSeconds(value[0], value[1]).isPositive();
            equal(expected, actual, "duration " + Arrays.toString(value));
        }
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class Reader extends TReader {
        private final String text;
        private int position;

        Reader(String text) {
            super(new TObject());
            this.text = text;
        }

        @Override
        public int read(char[] buffer, int offset, int length) {
            if (position == text.length()) {
                return -1;
            }
            int count = Math.min(Math.min(length, 3), text.length() - position);
            text.getChars(position, position + count, buffer, offset);
            position += count;
            return count;
        }

        @Override
        public void close() {
        }
    }

    private static final class FailingReader extends TReader {
        private int reads;

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            if (reads++ == 0) {
                buffer[offset] = 'x';
                return 1;
            }
            throw new IOException("injected");
        }

        @Override
        public void close() {
        }
    }
}
