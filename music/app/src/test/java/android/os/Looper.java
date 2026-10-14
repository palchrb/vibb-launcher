package android.os;

import android.util.Printer;

/*
 * A JVM test's stand-in for android.os.Looper (GPL-3.0-or-later, this project's own): the unit tests run
 * against AGP's mockable android.jar, whose Looper is final and answers null to everything - so no Media3
 * player could ever be driven through its Player API there (SimpleBasePlayer checks
 * `Thread.currentThread() == looper.getThread()` on every call). This class comes first on the test
 * classpath (the mockable jar is last) and behaves exactly as the mockable one (every static null, every
 * call a no-op) except [forCurrentThread] and [getThread]: SpotifyRendererTest's session player
 * (emulator check §6l A, F1) runs on the test's own thread, with android.util.Pair and
 * SparseBooleanArray's stand-ins beside it. Test sources only - never in an APK.
 */
public final class Looper {
    private final Thread thread;

    private Looper(Thread thread) {
        this.thread = thread;
    }

    /** A looper whose thread is the caller's: a Media3 player made with it may be called from this thread. */
    public static Looper forCurrentThread() {
        return new Looper(Thread.currentThread());
    }

    public static Looper getMainLooper() {
        return null;
    }

    public static Looper myLooper() {
        return null;
    }

    public static MessageQueue myQueue() {
        return null;
    }

    public static void prepare() {
    }

    public static void prepareMainLooper() {
    }

    public static void loop() {
    }

    public Thread getThread() {
        return thread;
    }

    public MessageQueue getQueue() {
        return null;
    }

    public boolean isCurrentThread() {
        return thread == Thread.currentThread();
    }

    public void quit() {
    }

    public void quitSafely() {
    }

    public void setMessageLogging(Printer printer) {
    }

    public void dump(Printer pw, String prefix) {
    }
}
