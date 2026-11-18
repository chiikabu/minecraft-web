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
package org.teavm.classlib.java.lang;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.teavm.backend.javascript.spi.InjectedBy;
import org.teavm.classlib.PlatformDetector;
import org.teavm.classlib.impl.Base64Impl;
import org.teavm.interop.NoSideEffects;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSIndexer;
import org.teavm.jso.JSObject;

public abstract class TClassLoader extends TObject {
    private TClassLoader parent;
    private static TSystemClassLoader systemClassLoader = new TSystemClassLoader();
    private static ResourceContainer resources;

    protected TClassLoader() {
        this(null);
    }

    protected TClassLoader(TClassLoader parent) {
        this.parent = parent;
    }

    // Browser addition: single-threaded, so parallel capability is trivially true.
    protected static boolean registerAsParallelCapable() {
        return true;
    }

    public TClassLoader getParent() {
        return parent;
    }

    public static TClassLoader getSystemClassLoader() {
        return systemClassLoader;
    }

    public static TClassLoader getPlatformClassLoader() {
        return getSystemClassLoader();
    }

    // ponytail: no bundled/served resources reachable by URL in this environment; getResourceAsStream
    // (backed by supplyResources()) is the real lookup path. Upgrade if a resource URL scheme is ever needed.
    public java.net.URL getResource(String name) {
        return null;
    }

    public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
        return java.util.Collections.emptyEnumeration();
    }

    public static java.net.URL getSystemResource(String name) {
        return getSystemClassLoader().getResource(name);
    }

    public static java.util.Enumeration<java.net.URL> getSystemResources(String name) throws java.io.IOException {
        return getSystemClassLoader().getResources(name);
    }

    public TClass<?> loadClass(String name) throws TClassNotFoundException {
        throw new TClassNotFoundException(name);
    }

    public InputStream getResourceAsStream(String name) {
        // Browser addition: there is no classpath here, but the vanilla jar's
        // resources are in the asset bundle. Serve them first so classpath
        // reads that run before the resource reload (Language's en_us.json)
        // still find their file.
        InputStream fromBundle = net.minecraft.client.web.WebPackResources.openClasspath(name);
        if (fromBundle != null) {
            return fromBundle;
        }
        // supplyResources() is @InjectedBy(ClassLoaderNativeGenerator), and an Injector is a
        // JavaScript-backend construct: the WasmGC backend has no counterpart, so on that lane
        // this tail can only ever be dead weight in the reachability graph. PlatformDetector
        // folds to a constant BEFORE dependency analysis (same lever as 3284703f), so the whole
        // branch — and the injected native with it — disappears from the wasm-gc link.
        if (PlatformDetector.isJavaScript()) {
            if (resources == null) {
                resources = supplyResources();
            }
            JSObject data = resources.getResource(name);
            String dataString = resourceToString(data);
            if (dataString == null) {
                return null;
            }
            byte[] bytes = new byte[dataString.length()];
            for (int i = 0; i < bytes.length; ++i) {
                bytes[i] = (byte) dataString.charAt(i);
            }
            return new ByteArrayInputStream(Base64Impl.decode(bytes));
        }
        return null;
    }

    public static InputStream getSystemResourceAsStream(String name) {
        return getSystemClassLoader().getResourceAsStream(name);
    }

    @JSBody(params = "resource", script = "return resource !== null && resource !== void 0 ? resource : null;")
    private static native String resourceToString(JSObject resource);

    @InjectedBy(ClassLoaderNativeGenerator.class)
    @NoSideEffects
    private static native ResourceContainer supplyResources();

    interface ResourceContainer extends JSObject {
        @JSIndexer
        JSObject getResource(String name);
    }
}
