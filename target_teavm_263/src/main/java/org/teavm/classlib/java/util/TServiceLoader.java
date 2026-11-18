/*
 *  Copyright 2014 Alexey Andreev.
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

import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.teavm.classlib.PlatformDetector;
import org.teavm.classlib.java.lang.*;
import org.teavm.platform.PlatformClass;

public final class TServiceLoader<S> extends TObject implements TIterable<S> {
    private Object[] services;

    private TServiceLoader(Object[] services) {
        this.services = services;
    }

    @Override
    public TIterator<S> iterator() {
        return new TIterator<S>() {
            private int index;
            @Override public boolean hasNext() {
                return index < services.length;
            }
            @SuppressWarnings("unchecked") @Override public S next() {
                if (index == services.length) {
                    throw new TNoSuchElementException();
                }
                return (S) services[index++];
            }
            @Override public void remove() {
                throw new TUnsupportedOperationException();
            }
        };
    }

    public Optional<S> findFirst() {
        TIterator<S> iterator = iterator();
        return iterator.hasNext() ? Optional.of(iterator.next()) : Optional.empty();
    }

    // Added for the browser port: the JDK's JNDI plumbing reaches
    // ServiceLoader.stream(). Services are bound at build time and TeaVM binds
    // none of the ones JNDI is looking for, so an empty stream is honest.
    public Stream<Provider<S>> stream() {
        // Was Stream.empty(), which suited JNDI (none of its services are bound) but hid every provider the
        // build did bind from callers that use stream() rather than iterator(): Fabric mods do (ImmediatelyFast's
        // ServiceUtil.load). Unbound services still give an empty stream.
        java.util.List<Provider<S>> providers = new java.util.ArrayList<>(services.length);
        for (Object service : services) {
            @SuppressWarnings("unchecked")
            S instance = (S) service;
            providers.add(new Provider<S>() {
                @Override
                public S get() {
                    return instance;
                }

                @SuppressWarnings("unchecked")
                @Override
                public Class<? extends S> type() {
                    return (Class<? extends S>) instance.getClass();
                }
            });
        }
        return providers.stream();
    }

    public interface Provider<S> extends Supplier<S> {
        @Override
        S get();

        Class<? extends S> type();
    }

    public static <S> TServiceLoader<S> load(TClass<S> service) {
        // Providers a Fabric bake bound (net.minecraft.client.web.WebServices) come first.
        Object[] baked = net.minecraft.client.web.WebServices.providers((Class<?>) (Object) service);
        if (baked != null) {
            return new TServiceLoader<>(baked);
        }
        return new TServiceLoader<>(PlatformDetector.isWebAssemblyGC()
                ? doLoadServices(service)
                : doLoadServices(service.getPlatformClass()));
    }

    public static <S> TServiceLoader<S> load(TClass<S> service, @SuppressWarnings("unused") TClassLoader loader) {
        return load(service);
    }

    public static <S> TServiceLoader<S> loadInstalled(TClass<S> service) {
        return load(service);
    }

    private static Object[] doLoadServices(PlatformClass cls) {
        Object[] result = loadServices(cls);
        if (result == null) {
            result = new Object[0];
        }
        return result;
    }

    private static native Object[] loadServices(PlatformClass cls);

    private static Object[] doLoadServices(TClass<?> cls) {
        Object[] result = loadServices(cls);
        if (result == null) {
            result = new Object[0];
        }
        return result;
    }

    private static native Object[] loadServices(TClass<?> cls);

    public void reload() {
        // Do nothing, services are bound at build time
    }
}
