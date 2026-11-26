/*
 * web263 override of TeaVM 0.12.1's TTemplateCollections: Set.of/Set.copyOf and Map.of/Map.copyOf
 * with 3+ elements kept an open-addressing table with NO empty slot (table size == element count), so
 * every miss probed all N elements with equals(). Holder.tags is such a set and BlockState.is(tag)
 * misses are the common case: ~5% of the integrated server's time was Identifier.equals inside
 * NElementSet.contains. This copy adds a power-of-two probe index (load <= 0.5) beside the dense
 * array; construction semantics (duplicate handling, iteration order) are unchanged.
 */
/*
 *  Copyright 2020 Alexey Andreev.
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

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

public final class TTemplateCollections {

    private TTemplateCollections() {
    }

    public static class ImmutableArrayList<T> extends AbstractImmutableList<T> implements RandomAccess {
        private final T[] list;

        @SafeVarargs
        public ImmutableArrayList(T... list) {
            this.list = list;
        }

        @SuppressWarnings("unchecked")
        public ImmutableArrayList(TCollection<? extends T> collection) {
            T[] list = (T[]) new Object[collection.size()];

            TIterator<? extends T> iter = collection.iterator();
            int index = 0;
            while (iter.hasNext()) {
                T element = iter.next();
                if (element == null) {
                    throw new NullPointerException();
                }
                list[index++] = element;
            }

            this.list = list;
        }

        @Override
        public T get(int index) {
            return list[index];
        }

        @Override
        public int size() {
            return list.length;
        }
    }

    static class SingleElementList<T> extends AbstractImmutableList<T> implements RandomAccess {
        private T value;

        SingleElementList(T value) {
            this.value = value;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public T get(int index) {
            if (index == 0) {
                return value;
            }
            throw new IndexOutOfBoundsException();
        }

        @Override
        public void sort(TComparator<? super T> c) {
        }
    }

    static class TwoElementsList<T> extends AbstractImmutableList<T> implements RandomAccess {
        private T first;
        private T second;

        TwoElementsList(T first, T second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public int size() {
            return 2;
        }

        @Override
        public T get(int index) {
            if (index == 0) {
                return first;
            } else if (index == 1) {
                return second;
            }
            throw new IndexOutOfBoundsException();
        }
    }

    static class SingleElementSet<T> extends AbstractImmutableSet<T> {
        private T element;

        SingleElementSet(T element) {
            this.element = element;
        }

        @Override
        public TIterator<T> iterator() {
            return new TIterator<T>() {
                private boolean more = true;

                @Override
                public boolean hasNext() {
                    return more;
                }

                @Override
                public T next() {
                    if (!more) {
                        throw new NoSuchElementException();
                    }
                    more = false;
                    return element;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public boolean contains(Object o) {
            return Objects.equals(o, element);
        }
    }

    static class TwoElementsSet<T> extends AbstractImmutableSet<T> {
        private T first;
        private T second;

        TwoElementsSet(T first, T second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public boolean contains(Object o) {
            return Objects.equals(o, first) || Objects.equals(o, second);
        }

        @Override
        public TIterator<T> iterator() {
            return new TIterator<T>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < 2;
                }

                @Override
                public T next() {
                    switch (index++) {
                        case 0:
                            return first;
                        case 1:
                            return second;
                        default:
                            throw new NoSuchElementException();
                    }
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public int size() {
            return 2;
        }
    }

    private static int nextPow2(int minCap) {
        int cap = 4;
        while (cap < minCap) {
            cap <<= 1;
        }
        return cap;
    }

    private static int mixHash(int h) {
        return h ^ (h >>> 16);
    }

    static class NElementSet<T> extends AbstractImmutableSet<T> {
        private T[] data;
        private Object[] index;
        private int mask;

        private void buildIndex() {
            int cap = nextPow2(Math.max(4, data.length * 2));
            Object[] idx = new Object[cap];
            int m = cap - 1;
            for (T element : data) {
                int i = mixHash(element.hashCode()) & m;
                while (idx[i] != null) {
                    i = (i + 1) & m;
                }
                idx[i] = element;
            }
            this.index = idx;
            this.mask = m;
        }

        /**
         * Throws an exception on duplicate elements.
         */
        @SuppressWarnings("unchecked")
        @SafeVarargs
        NElementSet(T... data) {
            int n = data.length;
            int cap = nextPow2(Math.max(4, n * 2));
            Object[] idx = new Object[cap];
            int m = cap - 1;
            T[] dense = (T[]) new Object[n];

            for (int k = 0; k < n; k++) {
                T element = Objects.requireNonNull(data[k]);
                int i = mixHash(element.hashCode()) & m;
                while (true) {
                    Object existing = idx[i];
                    if (existing == null) {
                        idx[i] = element;
                        dense[k] = element;
                        break;
                    } else if (existing == element || existing.equals(element)) {
                        throw new IllegalArgumentException();
                    }
                    i = (i + 1) & m;
                }
            }

            this.data = dense;
            this.index = idx;
            this.mask = m;
        }

        /**
         * Duplicate elements will be ignored (does NOT throw an exception).
         */
        @SuppressWarnings("unchecked")
        NElementSet(TCollection<T> collection) {
            Object[] raw = collection.toArray();
            int n = raw.length;
            int cap = nextPow2(Math.max(4, n * 2));
            Object[] idx = new Object[cap];
            int m = cap - 1;
            T[] temp = (T[]) new Object[n];

            int count = 0;
            outerLoop:
            for (int k = 0; k < n; k++) {
                T element = (T) raw[k];
                if (element == null) {
                    throw new NullPointerException(String.format("Element at index %s is null", count));
                }
                int i = mixHash(element.hashCode()) & m;
                while (true) {
                    Object existing = idx[i];
                    if (existing == null) {
                        idx[i] = element;
                        temp[count++] = element;
                        break;
                    } else if (existing == element || existing.equals(element)) {
                        continue outerLoop;
                    }
                    i = (i + 1) & m;
                }
            }

            this.data = count == n ? temp : Arrays.copyOf(temp, count);
            if (cap > 4 && count * 4 < cap) {
                buildIndex();
            } else {
                this.index = idx;
                this.mask = m;
            }
        }

        @Override
        public TIterator<T> iterator() {
            return new TIterator<T>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < data.length;
                }

                @Override
                public T next() {
                    if (index >= data.length) {
                        throw new NoSuchElementException();
                    }
                    return data[index++];
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public boolean contains(Object o) {
            if (data.length == 0 || o == null) {
                return false;
            }
            Object[] idx = index;
            int m = mask;
            for (int i = mixHash(o.hashCode()) & m; ; i = (i + 1) & m) {
                Object e = idx[i];
                if (e == null) {
                    return false;
                }
                if (e == o || e.equals(o)) {
                    return true;
                }
            }
        }
    }

    static class SingleEntryMap<K, V> extends AbstractImmutableMap<K, V> {
        private Entry<K, V> entry;
        private AbstractImmutableSet<Entry<K, V>> entrySet;
        private AbstractImmutableSet<K> keySet;
        private AbstractImmutableList<V> values;

        SingleEntryMap(K key, V value) {
            this.entry = new ImmutableEntry<>(key, value);
        }

        @Override
        public TSet<Entry<K, V>> entrySet() {
            if (entrySet == null) {
                entrySet = new SingleElementSet<>(entry);
            }
            return entrySet;
        }

        @Override
        public V get(Object key) {
            return entry.getKey().equals(key) ? entry.getValue() : null;
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean containsValue(Object value) {
            return entry.getValue().equals(value);
        }

        @Override
        public boolean containsKey(Object key) {
            return entry.getKey().equals(key);
        }

        @Override
        public TSet<K> keySet() {
            if (keySet == null) {
                keySet = new SingleElementSet<>(entry.getKey());
            }
            return keySet;
        }

        @Override
        public TCollection<V> values() {
            if (values == null) {
                values = new SingleElementList<>(entry.getValue());
            }
            return values;
        }
    }

    static class TwoEntriesMap<K, V> extends AbstractImmutableMap<K, V> {
        private Entry<K, V> first;
        private Entry<K, V> second;
        private AbstractImmutableSet<Entry<K, V>> entrySet;
        private AbstractImmutableSet<K> keySet;
        private AbstractImmutableList<V> values;

        TwoEntriesMap(K k1, V v1, K k2, V v2) {
            this.first = new ImmutableEntry<>(k1, v1);
            this.second = new ImmutableEntry<>(k2, v2);
        }

        @Override
        public TSet<Entry<K, V>> entrySet() {
            if (entrySet == null) {
                entrySet = new TwoElementsSet<>(first, second);
            }
            return entrySet;
        }

        @Override
        public V get(Object key) {
            return first.getKey().equals(key)
                    ? first.getValue()
                    : second.getKey().equals(key) ? second.getValue() : null;
        }

        @Override
        public int size() {
            return 2;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean containsValue(Object value) {
            return first.getValue().equals(value) || second.getValue().equals(value);
        }

        @Override
        public boolean containsKey(Object key) {
            return first.getKey().equals(key) || second.getKey().equals(key);
        }

        @Override
        public TSet<K> keySet() {
            if (keySet == null) {
                keySet = new TwoElementsSet<>(first.getKey(), second.getKey());
            }
            return keySet;
        }


        @Override
        public TCollection<V> values() {
            if (values == null) {
                values = new TwoElementsList<>(first.getValue(), second.getValue());
            }
            return values;
        }
    }

    static class NEtriesMap<K, V> extends AbstractImmutableMap<K, V> {
        private Entry<K, V>[] data;
        private AbstractImmutableSet<Entry<K, V>> entrySet;
        private Entry<K, V>[] index;
        private int mask;

        private Entry<K, V> find(Object key) {
            if (key == null || data.length == 0) {
                return null;
            }
            Entry<K, V>[] idx = index;
            int m = mask;
            for (int i = mixHash(key.hashCode()) & m; ; i = (i + 1) & m) {
                Entry<K, V> e = idx[i];
                if (e == null) {
                    return null;
                }
                Object k = e.getKey();
                if (k == key || k.equals(key)) {
                    return e;
                }
            }
        }

        @SuppressWarnings("unchecked")
        @SafeVarargs
        NEtriesMap(Entry<K, V>... data) {
            initFromEntries(data);
        }

        @SuppressWarnings("unchecked")
        NEtriesMap(TMap<? extends K, ? extends V> map) {
            initFromEntries(map.entrySet().toArray(new TMap.Entry[0]));
        }

        /**
         * Validates non-null keys/values and rejects duplicate keys in O(N) using a power-of-two
         * open-addressing table (load <= 0.5), populating both {@link #data} and {@link #index}.
         */
        @SuppressWarnings("unchecked")
        private void initFromEntries(Entry<K, V>[] entries) {
            int n = entries.length;
            int cap = nextPow2(Math.max(4, n * 2));
            Entry<K, V>[] idx = new Entry[cap];
            int m = cap - 1;
            Entry<K, V>[] dense = new Entry[n];

            for (int k = 0; k < n; k++) {
                Entry<K, V> entry = entries[k];
                K key = Objects.requireNonNull(entry.getKey());
                V value = Objects.requireNonNull(entry.getValue());
                Entry<K, V> immutable = entry instanceof ImmutableEntry
                        ? entry
                        : new ImmutableEntry<>(key, value);

                int i = mixHash(key.hashCode()) & m;
                while (true) {
                    Entry<K, V> existing = idx[i];
                    if (existing == null) {
                        idx[i] = immutable;
                        dense[k] = immutable;
                        break;
                    }
                    K existingKey = existing.getKey();
                    if (existingKey == key || existingKey.equals(key)) {
                        throw new IllegalArgumentException();
                    }
                    i = (i + 1) & m;
                }
            }

            this.data = dense;
            this.index = idx;
            this.mask = m;
        }

        @Override
        public int size() {
            return data.length;
        }

        @Override
        public boolean isEmpty() {
            return data.length == 0;
        }

        @Override
        public boolean containsValue(Object value) {
            if (value == null) {
                return false;
            }
            for (Entry<K, V> entry : data) {
                if (entry.getValue().equals(value)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean containsKey(Object key) {
            return find(key) != null;
        }

        @Override
        public V get(Object key) {
            Entry<K, V> e = find(key);
            return e != null ? e.getValue() : null;
        }

        @Override
        public TSet<Entry<K, V>> entrySet() {
            if (entrySet == null) {
                entrySet = new AbstractImmutableSet<Entry<K, V>>() {
                    @Override
                    public int size() {
                        return data.length;
                    }

                    @Override
                    public TIterator<Entry<K, V>> iterator() {
                        return new TIterator<Entry<K, V>>() {
                            int index;

                            @Override
                            public boolean hasNext() {
                                return index < data.length;
                            }

                            @Override
                            public Entry<K, V> next() {
                                if (index == data.length) {
                                    throw new NoSuchElementException();
                                }
                                return data[index++];
                            }

                            @Override
                            public void remove() {
                                throw new UnsupportedOperationException();
                            }
                        };
                    }

                    @Override
                    public boolean contains(Object o) {
                        if (!(o instanceof Entry)) {
                            return false;
                        }
                        Entry<?, ?> e = (Entry<?, ?>) o;

                        Object key = e.getKey();
                        if (key == null) {
                            return false;
                        }
                        Entry<K, V> entry = find(key);
                        return entry != null && entry.getValue().equals(e.getValue());
                    }
                };
            }
            return entrySet;
        }
    }

    static abstract class AbstractImmutableList<T> extends TAbstractList<T> implements RandomAccess {
        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T remove(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        protected void removeRange(int start, int end) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(TCollection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeIf(Predicate<? super T> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(TCollection<?> c) {
            throw new UnsupportedOperationException();
        }
    }

    static abstract class AbstractImmutableSet<T> extends TAbstractSet<T> {
        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(TCollection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeIf(Predicate<? super T> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(TCollection<?> c) {
            throw new UnsupportedOperationException();
        }
    }

    static abstract class AbstractImmutableMap<K, V> extends TAbstractMap<K, V> {
        @Override
        public V put(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putAll(TMap<? extends K, ? extends V> m) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V remove(Object key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object key, Object value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean replace(K key, V value, V newValue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V replace(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V putIfAbsent(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            throw new UnsupportedOperationException();
        }
    }

    static class ImmutableEntry<K, V> implements TMap.Entry<K, V>, Cloneable {
        K key;
        V value;

        ImmutableEntry(K theKey, V theValue) {
            key = theKey;
            value = theValue;
        }

        @Override
        public Object clone() {
            try {
                return super.clone();
            } catch (CloneNotSupportedException e) {
                return null;
            }
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (object instanceof TMap.Entry) {
                TMap.Entry<?, ?> entry = (TMap.Entry<?, ?>) object;
                return TObjects.equals(key, entry.getKey()) && TObjects.equals(value, entry.getValue());
            }
            return false;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public int hashCode() {
            return TObjects.hashCode(key) ^ TObjects.hashCode(value);
        }

        @Override
        public V setValue(V object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }
}
