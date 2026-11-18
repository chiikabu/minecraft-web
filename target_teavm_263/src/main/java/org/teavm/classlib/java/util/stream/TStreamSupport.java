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
package org.teavm.classlib.java.util.stream;

import java.util.Spliterator;
import java.util.function.Supplier;
import org.teavm.classlib.java.util.TSpliterator;
import org.teavm.classlib.java.util.stream.impl.TStreamOverSpliterator;
import org.teavm.classlib.java.util.stream.impl.TStreamOverSpliteratorSupplier;

public final class TStreamSupport {
    private TStreamSupport() {
    }

    public static <T> TStream<T> stream(TSpliterator<T> spliterator, boolean parallel) {
        return new TStreamOverSpliterator<>(spliterator);
    }

    public static <T> TStream<T> stream(Supplier<Spliterator<T>> spliterator, int characteristics, boolean parallel) {
        return new TStreamOverSpliteratorSupplier<>(spliterator, characteristics);
    }

    // ponytail: no TIntStream/TLongStream/TDoubleStream counterpart to TStreamOverSpliterator
    // exists in this fork, so these buffer the spliterator eagerly into the stream instead of
    // wrapping it lazily. Ceiling: a spliterator that never terminates hangs here instead of
    // streaming; upgrade path is a lazy TXxxStreamOverSpliterator if that's ever needed.
    public static TIntStream intStream(TSpliterator.OfInt spliterator, boolean parallel) {
        TIntStream.Builder builder = TIntStream.builder();
        spliterator.forEachRemaining(builder);
        return builder.build();
    }

    public static TLongStream longStream(TSpliterator.OfLong spliterator, boolean parallel) {
        TLongStream.Builder builder = TLongStream.builder();
        spliterator.forEachRemaining(builder);
        return builder.build();
    }

    public static TDoubleStream doubleStream(TSpliterator.OfDouble spliterator, boolean parallel) {
        TDoubleStream.Builder builder = TDoubleStream.builder();
        spliterator.forEachRemaining(builder);
        return builder.build();
    }
}
