/*
 *  Copyright 2013 Steve Hannah.
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

import org.teavm.interop.DelegateTo;
import org.teavm.interop.Platforms;
import org.teavm.interop.SupportedOn;
import org.teavm.jso.browser.Navigator;
import org.teavm.runtime.GC;

/**
 * Browser runtime services. Explicit exit runs registered hooks; the browser
 * cannot provide JVM process termination or guaranteed tab-close cleanup.
 *
 * @author shannah
 */
public class TRuntime {
    private static final TRuntime instance = new TRuntime();
    private final java.util.IdentityHashMap<Thread, Boolean> shutdownHooks = new java.util.IdentityHashMap<>();
    private boolean shuttingDown;

    public synchronized void addShutdownHook(Thread hook) {
        if (shuttingDown) throw new IllegalStateException("Shutdown in progress");
        java.util.Objects.requireNonNull(hook);
        if (hook.isAlive()) throw new IllegalArgumentException("Hook already running");
        if (shutdownHooks.containsKey(hook)) throw new IllegalArgumentException("Hook already registered");
        shutdownHooks.put(hook, Boolean.TRUE);
    }

    public synchronized boolean removeShutdownHook(Thread hook) {
        if (shuttingDown) throw new IllegalStateException("Shutdown in progress");
        java.util.Objects.requireNonNull(hook);
        return shutdownHooks.remove(hook) != null;
    }

    /**
     * Terminates the currently running Java application. This method never returns normally.
     * The argument serves as a status code; by convention, a nonzero status code indicates abnormal termination.
     */
    public void halt(int status) {
        throw new IllegalStateException("Runtime.halt(" + status + ") in browser");
    }

    public void exit(int status) {
        Thread[] hooks;
        synchronized (this) {
            if (shuttingDown) throw new IllegalStateException("Shutdown already in progress");
            shuttingDown = true;
            hooks = shutdownHooks.keySet().toArray(new Thread[0]);
            shutdownHooks.clear();
        }
        for (Thread hook : hooks) hook.start();
        boolean interrupted = false;
        for (Thread hook : hooks) {
            boolean finished = false;
            while (!finished) {
                try { hook.join(); finished = true; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        throw new IllegalStateException("Runtime.exit(" + status + ") in browser");
    }

    /**
     * Returns the amount of free memory in the system. Calling the gc method
     * may result in increasing the value returned by freeMemory.
     */
    @DelegateTo("freeMemoryLowLevel")
    public long freeMemory() {
        return Integer.MAX_VALUE;
    }

    private long freeMemoryLowLevel() {
        return GC.getFreeMemory();
    }

    /**
     * Runs the garbage collector. Calling this method suggests that the Java
     * Virtual Machine expend effort toward recycling unused objects in order to
     * make the memory they currently occupy available for quick reuse. When
     * control returns from the method call, the Java Virtual Machine has made
     * its best effort to recycle all discarded objects. The name gc stands for
     * "garbage collector". The Java Virtual Machine performs this recycling
     * process automatically as needed even if the gc method is not invoked
     * explicitly. The method System.js.gc() is the conventional and convenient
     * means of invoking this method.
     */
    public void gc() {
        System.gc();
    }

    /**
     * Returns the runtime object associated with the current Java application.
     * Most of the methods of class Runtime are instance methods and must be
     * invoked with respect to the current runtime object.
     */
    public static TRuntime getRuntime() {
        return instance;
    }

    /**
     * Returns the total amount of memory in the Java Virtual Machine. The value
     * returned by this method may vary over time, depending on the host
     * environment. Note that the amount of memory required to hold an object of
     * any given type may be implementation-dependent.
     */
    @DelegateTo("totalMemoryLowLevel")
    public long totalMemory() {
        return Integer.MAX_VALUE;
    }

    private long totalMemoryLowLevel() {
        return GC.availableBytes();
    }

    /**
     * 512 MiB. Callers (section-builder pool, etc.) size themselves off this as if it
     * were {@code -Xmx}. 1 GiB made them budget for a desktop heap; the tab is
     * competing with a ~100 MiB JS bundle and a worker on a 4 GB device.
     */
    public long maxMemory() {
        return 536870912L;
    }

    /**
     * One. Not because the machine has one core, but because this platform has
     * one thread: TeaVM compiles to single-threaded JavaScript, and every task
     * the engine believes it is running "in parallel" is drained on the same
     * trampoline as the frame loop.
     *
     * <p>Reporting {@code navigator.hardwareConcurrency} made every caller size
     * itself for parallelism that cannot happen, and one of them charges real
     * memory for the belief: {@code GameRenderer} passes this straight to
     * {@code SectionBufferBuilderPool.allocate}, which builds one
     * {@link net.minecraft.client.renderer.SectionBufferBuilderPack} per worker
     * — 4 MB solid + 4 MB cutout + 0.75 MB translucent each. On an 8-core host
     * that is 70 MB of chunk-meshing scratch space allocated during
     * {@code Minecraft}'s constructor, before a world exists, and MEM-1
     * measured all sixteen of those 4 MB blocks sitting in the fake heap at the
     * title screen. With one worker it is 8.75 MB.
     *
     * <p>Every other caller is correct at 1 as well:
     * {@code Util.maxAllowedExecutorThreads} clamps to at least 1 and the port
     * has no executor threads to hand out; {@code Minecraft.run} and
     * {@code MinecraftServer} only use it to raise thread priority, which does
     * nothing in a browser; {@code SystemReport}'s "CPUs" line is cosmetic, and
     * 1 is the honest answer for what this build can use.
     *
     * <p>Escape hatch: {@code -Dminecraft_web.reportHardwareConcurrency=true} restores
     * the old value in one step if some future consumer really wants the core
     * count rather than the thread count.
     */
    @SupportedOn({Platforms.JAVASCRIPT, Platforms.WEBASSEMBLY_GC})
    public int availableProcessors() {
        return "true".equals(TSystem.getProperty("minecraft_web.reportHardwareConcurrency"))
                ? Navigator.hardwareConcurrency()
                : 1;
    }

    /**
     * There are no subprocesses in a browser tab. The one caller
     * (Util.OS.openUri, which shells out to open/xdg-open) already logs and
     * swallows IOException, so failing this way is exactly what it expects.
     */
    public Process exec(String[] command) throws java.io.IOException {
        throw new java.io.IOException("cannot exec in the browser");
    }
}
