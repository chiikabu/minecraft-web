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
package org.teavm.classlib.java.util.stream.impl;

import java.util.Spliterator;
import java.util.function.Consumer;

public class TSimpleStreamSpliterator<T> implements Spliterator<T> {
    private TSimpleStreamImpl<T> stream;
    private boolean foundItems;
    private boolean done;

    public TSimpleStreamSpliterator(TSimpleStreamImpl<T> stream) {
        this.stream = stream;
    }

    @Override
    public void forEachRemaining(Consumer<? super T> action) {
        while (true) {
            boolean hasMore = stream.next(x -> {
                action.accept(x);
                return true;
            });
            if (!hasMore) {
                break;
            }
        }
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        if (done) {
            return false;
        }
        foundItems = false;
        while (!foundItems && !done) {
            done = !stream.next(x -> {
                action.accept(x);
                foundItems = true;
                return false;
            });
        }
        // Classlib twin: upstream returns an unconditional `true` here, and that is the ONLY
        // change in this file.
        //
        // The loop exits when `foundItems || done`. On the LAST call, when the stream is already
        // exhausted, `action` is never invoked and `done` goes true with `foundItems` still
        // false -- and upstream then reported `true` anyway. Spliterator.tryAdvance's contract is
        // "returns true if an element was consumed", so that is a false yes: the caller is told
        // it received an element that was never produced.
        //
        // Callers that hold the element in a field are the ones this destroys. Guava's
        // Streams.mapWithIndex does exactly that -- tryAdvance(holder), then
        // function.apply(holder.value, index) -- so a false yes applies the mapper to a NULL
        // holder and hands a null downstream as a legitimate-looking element.
        //
        // That is the chunk-generation crash. TagValueInput$CompoundListWrapper.stream() is
        // `Streams.mapWithIndex(this.list.stream(), (value, index) -> wrapChild(index, value))`,
        // so the phantom element arrives at wrapChild as a null CompoundTag and dies on
        // `compoundTag.isEmpty()`. It fires on the LAST entity of a chunk's list, which is why
        // the list is non-empty yet a null appears: the null is the TERMINATOR, not a stored
        // element.
        return foundItems;
    }

    @Override
    public Spliterator<T> trySplit() {
        return null;
    }

    @Override
    public long estimateSize() {
        return stream.estimateSize();
    }

    @Override
    public int characteristics() {
        return 0;
    }
}
