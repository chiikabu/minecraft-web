/*
 *  Copyright 2013 Alexey Andreev.
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

import java.lang.reflect.Array;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.teavm.backend.c.intrinsic.RuntimeInclude;
import org.teavm.backend.c.runtime.Memory;
import org.teavm.backend.c.runtime.fs.CFileSystem;
import org.teavm.backend.javascript.spi.GeneratedBy;
import org.teavm.backend.wasm.runtime.WasmSupport;
import org.teavm.classlib.PlatformDetector;
import org.teavm.classlib.impl.console.JSStderrPrintStream;
import org.teavm.classlib.impl.console.JSStdoutPrintStream;
import org.teavm.classlib.impl.console.StderrOutputStream;
import org.teavm.classlib.impl.console.StdoutOutputStream;
import org.teavm.classlib.java.io.TConsole;
import org.teavm.classlib.java.io.TInputStream;
import org.teavm.classlib.java.io.TPrintStream;
import org.teavm.classlib.java.lang.reflect.TArray;
import org.teavm.dependency.PluggableDependency;
import org.teavm.interop.Address;
import org.teavm.interop.DelegateTo;
import org.teavm.interop.Import;
import org.teavm.interop.NoSideEffects;
import org.teavm.interop.Unmanaged;
import org.teavm.jso.browser.Performance;
import org.teavm.runtime.GC;
import org.teavm.runtime.RuntimeArray;
import org.teavm.runtime.RuntimeClass;
import org.teavm.runtime.fs.VirtualFileSystemProvider;

public final class TSystem extends TObject {
    private static TPrintStream outCache;
    private static TPrintStream errCache;
    private static TInputStream inCache;
    private static Properties properties;

    private TSystem() {
    }

    public static Logger getLogger(String name) {
        return new SystemLogger(Objects.requireNonNull(name));
    }

    // shortcut: only the selected Caffeine Logger overloads are provided; add other JDK overloads when callers require them.
    public interface Logger {
        enum Level {
            ALL(Integer.MIN_VALUE), TRACE(400), DEBUG(500), INFO(800), WARNING(900), ERROR(1000), OFF(Integer.MAX_VALUE);

            private final int severity;

            Level(int severity) { this.severity = severity; }
            public String getName() { return name(); }
            public int getSeverity() { return severity; }
        }

        String getName();
        boolean isLoggable(Level level);
        void log(Level level, String message);
        void log(Level level, String message, Throwable thrown);
        void log(Level level, String format, Object... params);
    }

    private static final class SystemLogger implements Logger {
        private final org.slf4j.Logger backend;

        SystemLogger(String name) { backend = org.slf4j.LoggerFactory.getLogger(name); }
        @Override public String getName() { return backend.getName(); }

        @Override
        public boolean isLoggable(Level level) {
            return switch (Objects.requireNonNull(level)) {
                case ALL, TRACE -> backend.isTraceEnabled();
                case DEBUG -> backend.isDebugEnabled();
                case INFO -> backend.isInfoEnabled();
                case WARNING -> backend.isWarnEnabled();
                case ERROR -> backend.isErrorEnabled();
                case OFF -> false;
            };
        }

        @Override public void log(Level level, String message) { log(level, message, (Throwable) null); }

        @Override
        public void log(Level level, String message, Throwable thrown) {
            if (!isLoggable(level)) return;
            switch (level) {
                case ALL, TRACE -> backend.trace(message, thrown);
                case DEBUG -> backend.debug(message, thrown);
                case INFO -> backend.info(message, thrown);
                case WARNING -> backend.warn(message, thrown);
                case ERROR -> backend.error(message, thrown);
                case OFF -> { }
            }
        }

        @Override
        public void log(Level level, String format, Object... params) {
            if (!isLoggable(level)) return;
            String message = format;
            if (format != null && params != null && params.length != 0) {
                for (int i = 0; i < format.length() - 2; i++) {
                    if (format.charAt(i) == '{' && format.charAt(i + 1) >= '0' && format.charAt(i + 1) <= '9') {
                        try { message = new MessageFormat(format).format(params); }
                        catch (Exception error) { message = format; }
                        break;
                    }
                }
            }
            log(level, message, (Throwable) null);
        }
    }

    public static TPrintStream out() {
        if (outCache == null) {
            if (PlatformDetector.isJavaScript() || PlatformDetector.isWebAssemblyGC()) {
                outCache = (TPrintStream) (Object) new JSStdoutPrintStream();
            } else {
                outCache = new TPrintStream(StdoutOutputStream.INSTANCE, false);
            }
        }
        return outCache;
    }

    public static TPrintStream err() {
        if (errCache == null) {
            if (PlatformDetector.isJavaScript() || PlatformDetector.isWebAssemblyGC()) {
                errCache = (TPrintStream) (Object) new JSStderrPrintStream();
            } else {
                errCache = new TPrintStream(StderrOutputStream.INSTANCE, false);
            }
        }
        return errCache;
    }

    public static TInputStream in() {
        if (inCache == null) {
            inCache = new TConsoleInputStream();
        }
        return inCache;
    }

    public static TConsole console() {
        return null;
    }

    public static TSecurityManager getSecurityManager() {
        return new TSecurityManager();
    }

    public static void arraycopy(TObject src, int srcPos, TObject dest, int destPos, int length) {
        if (src == null || dest == null) {
            throw new TNullPointerException("Either src or dest is null");
        }
        if (srcPos < 0 || destPos < 0 || length < 0 || srcPos + length > TArray.getLength(src)
                || destPos + length > TArray.getLength(dest)) {
            throw new TIndexOutOfBoundsException();
        }
        if (src != dest) {
            Class<?> srcType = src.getClass().getComponentType();
            Class<?> targetType = dest.getClass().getComponentType();
            if (srcType == null || targetType == null) {
                throw new TArrayStoreException();
            }
            if (srcType != targetType) {
                if (!srcType.isPrimitive() && !targetType.isPrimitive()) {
                    Object[] srcArray = (Object[]) (Object) src;
                    int pos = srcPos;
                    for (int i = 0; i < length; ++i) {
                        Object elem = srcArray[pos++];
                        if (!targetType.isInstance(elem)) {
                            doArrayCopy(src, srcPos, dest, destPos, i);
                            throw new TArrayStoreException();
                        }
                    }
                    doArrayCopy(src, srcPos, dest, destPos, length);
                    return;
                } else if (!srcType.isPrimitive() || !targetType.isPrimitive()) {
                    throw new TArrayStoreException();
                }
            }
        }
        doArrayCopy(src, srcPos, dest, destPos, length);
    }

    static void fastArraycopy(Object src, int srcPos, Object dest, int destPos, int length) {
        if (srcPos < 0 || destPos < 0 || length < 0 || srcPos + length > Array.getLength(src)
                || destPos + length > Array.getLength(dest)) {
            throw new TIndexOutOfBoundsException();
        }
        doArrayCopy(src, srcPos, dest, destPos, length);
    }

    @GeneratedBy(SystemNativeGenerator.class)
    @PluggableDependency(SystemDependencyPlugin.class)
    @DelegateTo("doArrayCopyLowLevel")
    @NoSideEffects
    static native void doArrayCopy(Object src, int srcPos, Object dest, int destPos, int length);

    @Unmanaged
    static void doArrayCopyLowLevel(RuntimeArray src, int srcPos, RuntimeArray dest, int destPos, int length) {
        RuntimeClass type = RuntimeClass.getClass(src);
        int itemSize = type.itemType.size;
        if ((type.itemType.flags & RuntimeClass.PRIMITIVE) == 0) {
            itemSize = Address.sizeOf();
            GC.writeBarrier(dest);
        }

        var offset = Address.align(Address.fromInt(0).add(RuntimeArray.class, 1), itemSize).toInt();
        Address srcAddress = src.toAddress().add(offset);
        srcAddress = srcAddress.add(itemSize * srcPos);

        Address destAddress = dest.toAddress().add(offset);
        destAddress = destAddress.add(itemSize * destPos);

        Address.moveMemoryBlock(srcAddress, destAddress, length * itemSize);
    }

    @DelegateTo("currentTimeMillisLowLevel")
    @GeneratedBy(SystemNativeGenerator.class)
    @NoSideEffects
    public static native long currentTimeMillis();

    private static long currentTimeMillisLowLevel() {
        if (PlatformDetector.isWebAssembly()) {
            return WasmSupport.currentTimeMillis();
        } else {
            return currentTimeMillisC();
        }
    }

    @Import(name = "teavm_currentTimeMillis")
    @RuntimeInclude("time.h")
    private static native long currentTimeMillisC();

    private static void initPropertiesIfNeeded() {
        if (properties == null) {
            Properties defaults = new Properties();
            defaults.put("java.version", "25 (TeaVM WasmGC)");
            defaults.put("java.vendor", "TeaVM");
            defaults.put("os.version", "web");
            defaults.put("os.arch", "wasm32");
            defaults.put("os.name", "TeaVM");
            defaults.put("file.separator", "/");
            defaults.put("path.separator", ":");
            defaults.put("line.separator", lineSeparator());
            defaults.put("java.io.tmpdir", getTempDir());
            defaults.put("java.vm.version", "25");
            defaults.put("user.home", getHomeDir());
            properties = new Properties(defaults);
        }
    }

    private static String getTempDir() {
        if (!PlatformDetector.isC()) {
            return "/tmp";
        }
        Address resultPtr = Memory.malloc(Address.sizeOf());
        int length = CFileSystem.tempDirectory(resultPtr);
        return VirtualFileSystemProvider.getInstance().canonicalize(toJavaString(resultPtr, length));
    }

    private static String getHomeDir() {
        if (!PlatformDetector.isC()) {
            return "/";
        }

        Address resultPtr = Memory.malloc(Address.sizeOf());
        int length = CFileSystem.homeDirectory(resultPtr);
        return VirtualFileSystemProvider.getInstance().canonicalize(toJavaString(resultPtr, length));
    }

    private static String toJavaString(Address resultPtr, int length) {
        Address result = resultPtr.getAddress();
        Memory.free(resultPtr);

        char[] chars = new char[length];
        Memory.memcpy(Address.ofData(chars), result, chars.length * 2);
        Memory.free(result);

        return new String(chars);
    }

    public static String getProperty(@SuppressWarnings("unused") String key) {
        initPropertiesIfNeeded();
        return properties.getProperty(key);
    }

    public static String getProperty(String key, String def) {
        String value = getProperty(key);
        return value != null ? value : def;
    }

    public static Properties getProperties() {
        initPropertiesIfNeeded();
        Properties result = new Properties();
        copyProperties(properties, result);
        return result;
    }

    public static void setProperties(Properties props) {
        initPropertiesIfNeeded();
        copyProperties(props, properties);
    }

    private static void copyProperties(Properties from, Properties to) {
        to.clear();
        if (from != null) {
            Enumeration<?> e = from.propertyNames();
            while (e.hasMoreElements()) {
                String key = (String) e.nextElement();
                to.setProperty(key, from.getProperty(key));
            }
        }
    }

    public static String setProperty(String key, String value) {
        initPropertiesIfNeeded();
        return (String) properties.put(key, value);
    }

    public static String clearProperty(String key) {
        return (String) properties.remove(key);
    }

    public static void setErr(TPrintStream err) {
        errCache = err;
    }

    public static void setOut(TPrintStream out) {
        outCache = out;
    }

    @DelegateTo("gcLowLevel")
    public static void gc() {
        // Do nothing
    }

    private static void gcLowLevel() {
        GC.collectGarbageFull();
    }

    public static void runFinalization() {
        // Do nothing
    }

    public static long nanoTime() {
        if (PlatformDetector.isWebAssembly()) {
            return (long) (nanoTimeWasm() * 1000000);
        } else if (PlatformDetector.isLowLevel()) {
            return nanoTimeLowLevel();
        } else {
            return (long) (Performance.now() * 1000000);
        }
    }

    @Import(module = "teavm", name = "nanoTime")
    private static native double nanoTimeWasm();

    @Import(name = "teavm_currentTimeNano")
    @RuntimeInclude("time.h")
    private static native long nanoTimeLowLevel();

    public static int identityHashCode(Object x) {
        return x == null ? 0 : ((TObject) x).identity();
    }

    // Browser addition: there are no native libraries; the JDK's NativeLibraries
    // is only in the graph via the coarse reachability of the security providers.
    public static String mapLibraryName(String libname) {
        return "lib" + libname + ".so";
    }

    public static String lineSeparator() {
        return "\n";
    }
    
    public static String getenv(String name) {
        return null;
    }

    public static Map<String, String> getenv() {
        return Collections.emptyMap();
    }

    public static void exit(int status) {
        TRuntime.getRuntime().exit(status);
    }
}
