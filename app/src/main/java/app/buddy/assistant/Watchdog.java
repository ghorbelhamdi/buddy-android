package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;

/**
 * TEMPORARY (2.5.0 tests): when the main thread is held up for more than 700 ms, where it is (its stack: classes and
 * methods only, nothing from chats) goes to /root/.buddy/main-stalls.log in Buddy's Linux, to find what makes taps go
 * missing. Take out before the release.
 */
final class Watchdog {
    private static boolean started;

    private Watchdog() {
    }

    static synchronized void start(Context c) {
        if (started) return;
        started = true;
        final Context app = c.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            final long[] ran = {0};
            for (;;) {
                long asked = SystemClock.elapsedRealtime();
                main.post(() -> ran[0] = SystemClock.elapsedRealtime());
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    return;
                }
                if (ran[0] >= asked) continue; // (it ran: the main thread is free)
                // held up: where it is now, and again while it stays so
                StringBuilder b = new StringBuilder();
                b.append("== ").append(new java.util.Date()).append(" held over 700 ms\n");
                int samples = 0;
                while (ran[0] < asked && samples < 6) {
                    for (StackTraceElement e : Looper.getMainLooper().getThread().getStackTrace())
                        b.append("  ").append(e.getClassName()).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber()).append('\n');
                    b.append("  --\n");
                    samples++;
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                b.append("  held for ").append(SystemClock.elapsedRealtime() - asked).append(" ms or more\n");
                try {
                    File f = FilePlaces.fromLinux(app, "/root/.buddy/main-stalls.log");
                    if (f.length() > 2_000_000) f.delete();
                    try (FileWriter w = new FileWriter(f, true)) {
                        w.write(b.toString());
                    }
                } catch (Exception ignored) {
                }
            }
        }, "buddy-watchdog");
        t.setDaemon(true);
        t.start();
    }
}
