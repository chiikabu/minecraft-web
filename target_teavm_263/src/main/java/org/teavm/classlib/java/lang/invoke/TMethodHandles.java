/*
 *  Copyright 2017 Alexey Andreev.
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
package org.teavm.classlib.java.lang.invoke;

import org.teavm.classlib.java.lang.TClass;
import org.teavm.classlib.java.lang.TIllegalAccessException;
import org.teavm.classlib.java.lang.TNoSuchMethodException;
import org.teavm.classlib.java.lang.TString;
import org.teavm.classlib.java.lang.TObject;

public class TMethodHandles {
    public static TMethodHandle constant(TClass<?> type, Object value) {
        java.util.Objects.requireNonNull(type);
        if (type.getName().equals("void")) throw new IllegalArgumentException("void type");
        // shortcut: reference constants cover Iris; add primitive conversions when a reached caller needs them.
        if (type.isPrimitive()) throw new UnsupportedOperationException("Primitive method-handle constants");
        return new ConstantHandle(TMethodType.methodType(type), type.cast((TObject) value));
    }

    private static final class ConstantHandle extends TMethodHandle {
        private final Object value;

        ConstantHandle(TMethodType type, Object value) {
            super(type);
            this.value = value;
        }

        @Override public Object invokeWithArguments(Object... arguments) {
            if (arguments != null && arguments.length != 0)
                throw new java.lang.invoke.WrongMethodTypeException("Constant handle requires zero arguments");
            return value;
        }
    }

    /**
     * Added for the browser port. The reachable caller is
     * jdk.internal.access.SharedSecrets' static initialiser (dragged in by the
     * JDK's JNDI plumbing), which only needs a Lookup object to exist — it
     * never resolves a handle through it. Anything that does will fail on the
     * Lookup methods, which TeaVM does not have either.
     */
    public static Lookup lookup() {
        return LOOKUP;
    }

    private static final Lookup LOOKUP = new Lookup();

    public static final class Lookup {
        /**
         * Always "absent". Guava's ChecksumHashFunction catches
         * NoSuchMethodException and falls back to a byte[] copy, which is the
         * behaviour needed for the unsupported checksum lookup.
         */
        /** Classes initialise on first use here; there is nothing to force. */
        public TClass<?> ensureInitialized(TClass<?> cls) {
            return cls;
        }

        public TMethodHandle findVirtual(TClass<?> refc, TString name, TMethodType type)
                throws TNoSuchMethodException, TIllegalAccessException {
            throw new TNoSuchMethodException("no method handles in the browser");
        }
    }
}
