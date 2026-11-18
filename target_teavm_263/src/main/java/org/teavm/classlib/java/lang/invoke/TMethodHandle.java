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

import org.teavm.classlib.java.nio.TByteBuffer;
import org.teavm.classlib.java.util.zip.TChecksum;

public abstract class TMethodHandle {
    private final TMethodType type;

    protected TMethodHandle(TMethodType type) {
        this.type = type;
    }

    public final TMethodType type() {
        return type;
    }

    public abstract Object invokeWithArguments(Object... arguments) throws Throwable;

    /**
     * Added for the browser port. invokeExact is signature-polymorphic, so each
     * call site needs its own descriptor to link; this is the only one reachable
     * (Guava ChecksumHashFunction$ChecksumMethodHandles.updateByteBuffer). It is
     * never entered: TMethodHandles.Lookup.findVirtual throws
     * NoSuchMethodException, which Guava catches and turns into a null handle,
     * steering the caller onto its byte[]-copy fallback.
     */
    public final void invokeExact(TChecksum checksum, TByteBuffer buffer) {
        throw new UnsupportedOperationException("Unsupported checksum method handle");
    }
}
