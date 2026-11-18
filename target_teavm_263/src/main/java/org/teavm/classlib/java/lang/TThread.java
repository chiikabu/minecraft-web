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

import org.teavm.classlib.PlatformDetector;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.platform.Platform;
import org.teavm.platform.PlatformRunnable;
import org.teavm.runtime.EventQueue;
import org.teavm.runtime.Fiber;

public class TThread extends TObject implements TRunnable {
    private static TThread mainThread = new TThread("main");
    private static TThread currentThread = mainThread;
    private static int nextId = 1;
    private static int activeCount = 1;
    private static UncaughtExceptionHandler defaultUncaughtExceptionHandler = new TDefaultUncaughtExceptionHandler();
    private static final java.util.Set<TThread> liveThreads = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    static {
        mainThread.started = true;
        mainThread.alive = true;
        liveThreads.add(mainThread);
    }
    private UncaughtExceptionHandler uncaughtExceptionHandler;
    private long id;
    private int priority;
    private boolean daemon;
    private boolean virtual;
    private long timeSliceStart;
    private int yieldCount;
    private final Object finishedLock = new Object();
    private boolean interruptedFlag;
    public TThreadInterruptHandler interruptHandler;
    public Object key;

    private String name;
    private boolean alive;
    private boolean started;
    TRunnable target;

    public TThread() {
        this(null, "Thread-" + nextId);
    }

    public TThread(String name) {
        this(null, name);
    }

    public TThread(TRunnable target) {
        this(target, "Thread-" + nextId);
    }

    /** Added for the browser port: jdk.internal.misc.InnocuousThread's ctor. */
    TThread(TThreadGroup group, TRunnable target, String name, long stackSize, boolean inheritThreadLocals) {
        this(target, name);
    }

    public TThread(TRunnable target, String name) {
        this.name = java.util.Objects.requireNonNull(name);
        this.target = target;
        priority = currentThread == null ? 5 : currentThread.getPriority();
        id = nextId++;
    }

    public void start() {
        synchronized (finishedLock) {
            if (started) throw new IllegalThreadStateException("Thread already started");
            started = true;
            alive = true;
        }
        synchronized (liveThreads) { liveThreads.add(this); }
        if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
            boolean daemon = this.daemon;
            if (!daemon) {
                Fiber.userThreadCount++;
            }
            EventQueue.offer(() -> Fiber.start(this::runThread, daemon));
        } else {
            Platform.startThread(this::runThread);
        }
    }

    public static TThread startVirtualThread(TRunnable task) {
        TThread thread = new TThread(java.util.Objects.requireNonNull(task), "");
        thread.virtual = true;
        thread.daemon = true;
        thread.priority = 5;
        // shortcut: virtual threads share the fiber scheduler; use workers for CPU parallelism.
        thread.start();
        return thread;
    }

    public final boolean isVirtual() {
        return virtual;
    }

    private void runThread() {
        try {
            activeCount++;
            setCurrentThread(TThread.this);
            key = new Object();
            TThread.this.run();
        } catch (Throwable t) {
            getUncaughtExceptionHandler().uncaughtException(this, t);
        } finally {
            activeCount--;
            key = null;
            setCurrentThread(mainThread);
            synchronized (finishedLock) {
                alive = false;
                finishedLock.notifyAll();
            }
            synchronized (liveThreads) { liveThreads.remove(this); }
        }
    }

    static void setCurrentThread(TThread thread) {
        if (currentThread != thread) {
            currentThread = thread;
        }
        currentThread.timeSliceStart = System.currentTimeMillis();
    }

    static TThread getMainThread() {
        return mainThread;
    }

    @Override
    public void run() {
        if (target != null) {
            target.run();
        }
    }

    public static TThread currentThread() {
        return currentThread;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = java.util.Objects.requireNonNull(name);
    }

    public final boolean isDaemon() {
        return daemon;
    }

    public final void setDaemon(boolean daemon) {
        if (virtual && !daemon) throw new IllegalArgumentException("Virtual threads are daemon threads");
        if (alive) throw new IllegalThreadStateException("Thread already started");
        this.daemon = daemon;
    }

    public final void join(long millis, int nanos) throws InterruptedException {
        if (millis < 0 || nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException();
        }
        if (currentThread() == this || !alive) {
            return;
        }
        // [26.3-web] Never Object.wait here. A browser cannot block one fiber on another, and this
        // single call site of Object.wait made TeaVM's AsyncMethodFinder mark 121,376 methods
        // suspendable (join <- Runtime.exit <- System.exit <- class initializers <- everything), so
        // ~90% of hot WASM code ran as resumable state machines (measured 2026-11-10, see
        // docs/PHONE_PERFORMANCE.md). The only joins in this port are shutdown paths (exit hooks,
        // MinecraftServer.halt, rcon); the joined thread keeps running on the event queue afterwards,
        // as it would have anyway. Same reasoning as yield() below.
        org.teavm.classlib.impl.Trampoline.yield();
        if (alive) {
            System.out.println("[26.3-web] Thread.join returned while \"" + getName()
                    + "\" is still running: joins cannot block in the browser");
        }
    }

    public final void join(long millis) throws InterruptedException {
        join(millis, 0);
    }

    public final void join() throws InterruptedException {
        join(0);
    }

    public static void yield() {
        // One JS thread: suspending from a native callback is fatal, so advance queued work instead.
        org.teavm.classlib.impl.Trampoline.yield();
    }

    @Async
    static native void switchContext(TThread thread);

    private static void switchContext(final TThread thread, final AsyncCallback<Void> callback) {
        if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
            EventQueue.offer(() -> {
                setCurrentThread(thread);
                callback.complete(null);
            });
        } else {
            Platform.postpone(() -> {
                setCurrentThread(thread);
                callback.complete(null);
            });
        }
    }

    public void interrupt() {
        interruptedFlag = true;
        if (interruptHandler != null) {
            interruptHandler.interrupted();
            interruptHandler = null;
        }
    }

    public static boolean interrupted() {
        TThread thread = currentThread();
        boolean result = thread.interruptedFlag;
        thread.interruptedFlag = false;
        return result;
    }

    public boolean isInterrupted() {
        return interruptedFlag;
    }

    public boolean isAlive() {
        return alive;
    }

    public static int activeCount() {
        return activeCount;
    }

    public long getId() {
        return id;
    }

    /** Added for the browser port: Java 19+ spelling of getId(). */
    public final long threadId() {
        return id;
    }

    public State getState() {
        return !started ? State.NEW : alive ? State.RUNNABLE : State.TERMINATED;
    }

    public enum State {
        NEW,
        RUNNABLE,
        BLOCKED,
        WAITING,
        TIMED_WAITING,
        TERMINATED
    }

    public static boolean holdsLock(TObject obj) {
        return TObject.holdsLock(obj);
    }

    /** Added for the browser port: Java 19+ overload, used by the shutdown watchdog. */
    public static void sleep(org.threeten.bp.Duration duration) throws TInterruptedException {
        sleep(duration.toMillis());
    }

    @Async
    public static native void sleep(long millis) throws TInterruptedException;

    private static void sleep(long millis, AsyncCallback<Void> callback) {
        TThread current = currentThread();
        SleepHandler handler = new SleepHandler(current, callback);
        if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
            if (current.interruptedFlag) {
                handler.interrupted();
            } else {
                handler.scheduleId = EventQueue.offer(handler, System.currentTimeMillis() + millis);
                current.interruptHandler = handler;
            }
        } else {
            int intMillis = millis < Integer.MAX_VALUE ? (int) millis : Integer.MAX_VALUE;
            handler.scheduleId = Platform.schedule(handler, intMillis);
            current.interruptHandler = handler;
        }
    }

    static class SleepHandler implements PlatformRunnable, EventQueue.Event, TThreadInterruptHandler {
        private TThread thread;
        private AsyncCallback<Void> callback;
        private boolean isInterrupted;
        int scheduleId;

        SleepHandler(TThread thread, AsyncCallback<Void> callback) {
            this.thread = thread;
            this.callback = callback;
        }

        @Override
        public void interrupted() {
            thread.interruptedFlag = false;
            isInterrupted = true;
            if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
                EventQueue.kill(scheduleId);
                EventQueue.offer(() -> callback.error(new TInterruptedException()));
            } else {
                Platform.killSchedule(scheduleId);
                Platform.postpone(() -> callback.error(new TInterruptedException()));
            }
        }

        @Override
        public void run() {
            if (!isInterrupted) {
                thread.interruptHandler = null;
                setCurrentThread(thread);
                callback.complete(null);
            }
        }
    }

    public final void setPriority(int newPriority) {
        if (newPriority < 1 || newPriority > 10) throw new IllegalArgumentException("Invalid priority");
        if (!virtual) this.priority = newPriority;
    }

    public final int getPriority() {
        return this.priority;
    }

    public StackTraceElement[] getStackTrace() {
        if (this == currentThread()) return new Throwable().getStackTrace();
        // TeaVM does not expose stacks belonging to suspended fibers.
        return new StackTraceElement[0];
    }

    public static java.util.Map<TThread, StackTraceElement[]> getAllStackTraces() {
        java.util.Map<TThread, StackTraceElement[]> result = new java.util.HashMap<>();
        synchronized (liveThreads) {
            for (TThread thread : liveThreads) {
                if (thread.isAlive()) result.put(thread, thread.getStackTrace());
            }
        }
        return result;
    }

    public TClassLoader getContextClassLoader() {
        return TClassLoader.getSystemClassLoader();
    }
    
    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        if (this.uncaughtExceptionHandler != null) {
            return this.uncaughtExceptionHandler;
        }
        return defaultUncaughtExceptionHandler;
    }
    
    public void setUncaughtExceptionHandler(UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
    }
    
    public static UncaughtExceptionHandler getDefaultUncaughtExceptionHandler() {
        return defaultUncaughtExceptionHandler;
    }
    
    public static void setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler handler) {
        defaultUncaughtExceptionHandler = handler;
    }
    
    public interface UncaughtExceptionHandler {
        void uncaughtException(TThread t, Throwable e);
    }
}
