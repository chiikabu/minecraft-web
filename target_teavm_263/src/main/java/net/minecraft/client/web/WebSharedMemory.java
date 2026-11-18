// minecraft-web by chiikabu (https://github.com/chiikabu)

package net.minecraft.client.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

public final class WebSharedMemory {

    private WebSharedMemory() {
    }

    @JSBody(script = "return typeof SharedArrayBuffer === 'function' && !!self.crossOriginIsolated;")
    public static native boolean crossOriginIsolated();

    @JSBody(params = {"bytes"}, script = "return new SharedArrayBuffer(bytes);")
    public static native JSObject allocate(int bytes);

    @JSBody(params = {"sab", "slot", "value"}, script =
        "Atomics.store(new Int32Array(sab), slot, value);")
    public static native void atomicStoreI32(JSObject sab, int slot, int value);

    @JSBody(params = {"sab", "slot"}, script =
        "return Atomics.load(new Int32Array(sab), slot);")
    public static native int atomicLoadI32(JSObject sab, int slot);

    @JSBody(params = {"sab", "slot", "count"}, script =
        "return Atomics.notify(new Int32Array(sab), slot, count);")
    public static native int notifyI32(JSObject sab, int slot, int count);

    @JSBody(params = {"sab", "slot", "expected", "timeoutMs"}, script =
        "return Atomics.wait(new Int32Array(sab), slot, expected, timeoutMs);")
    public static native String waitI32(JSObject sab, int slot, int expected, double timeoutMs);

    public static void copyIn(final JSObject sab, final int offset, final byte[] data) {
        copyInBytes(sab, offset, Int8Array.copyFromJavaArray(data));
    }

    @JSBody(params = {"sab", "offset", "view"}, script =
        "new Int8Array(sab).set(view, offset);")
    private static native void copyInBytes(JSObject sab, int offset, Int8Array view);

    public static byte[] copyOut(final JSObject sab, final int offset, final int length) {
        return sliceBytes(sab, offset, length).copyToJavaArray();
    }

    @JSBody(params = {"sab", "offset", "length"}, script =
        "return new Int8Array(new Int8Array(sab).subarray(offset, offset + length));")
    private static native Int8Array sliceBytes(JSObject sab, int offset, int length);

    @JSBody(params = {"worker", "sab"}, script = "worker.postMessage({__minecraftSab: sab});")
    public static native void shareWith(JSObject worker, JSObject sab);
}
