package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;

/**
 * TEMPORARY (2.5.0 tests): what the main thread spends its time on, to find what makes taps go missing. Every task it
 * runs is timed (Looper's own dispatch lines: which handler and callback, never chat text): one over 100 ms is
 * written down with its time, and every 10 s the totals per kind of task, when the main thread was busy for more than
 * a second of them. A stall over 300 ms also gets the main thread's stack (classes and methods). All of it to
 * /root/.buddy/main-stalls.log in Buddy's Linux. Take out before the release.
 */
final class Watchdog {
    private static boolean started;
    private static Context app;
    private static final StringBuilder out = new StringBuilder();
    private static final java.util.Map<String, long[]> totals = new java.util.HashMap<>();
    private static long windowAt, busy;

    private Watchdog() {
    }

    static synchronized void start(Context c) {
        if (started) return;
        started = true;
        app = c.getApplicationContext();
        final long[] at = {0};
        final String[] what = {null};
        windowAt = SystemClock.uptimeMillis();
        Looper.getMainLooper().setMessageLogging(line -> {
            if (line.startsWith(">>>>> Dispatching")) {
                at[0] = SystemClock.uptimeMillis();
                what[0] = line;
                return;
            }
            if (!line.startsWith("<<<<< Finished") || what[0] == null) return;
            long took = SystemClock.uptimeMillis() - at[0];
            String key = kind(what[0]);
            what[0] = null;
            synchronized (out) {
                busy += took;
                long[] t = totals.computeIfAbsent(key, k -> new long[2]);
                t[0] += took;
                t[1]++;
                if (took > 100) out.append(new java.util.Date()).append("  ").append(took).append(" ms  ").append(key).append('\n');
                long now = SystemClock.uptimeMillis();
                if (now - windowAt >= 10_000) {
                    if (busy > 1000) {
                        out.append("-- last ").append(now - windowAt).append(" ms: main thread busy ").append(busy).append(" ms\n");
                        java.util.List<java.util.Map.Entry<String, long[]>> l = new java.util.ArrayList<>(totals.entrySet());
                        l.sort((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]));
                        for (int i = 0; i < Math.min(8, l.size()); i++)
                            out.append("   ").append(l.get(i).getValue()[0]).append(" ms in ").append(l.get(i).getValue()[1])
                                    .append("×  ").append(l.get(i).getKey()).append('\n');
                    }
                    totals.clear();
                    busy = 0;
                    windowAt = now;
                }
                if (out.length() > 0) flush();
            }
        });
        // a long stall: where it is
        final Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            final long[] ran = {0};
            for (;;) {
                long asked = SystemClock.elapsedRealtime();
                main.post(() -> ran[0] = SystemClock.elapsedRealtime());
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
                if (ran[0] >= asked) continue;
                StringBuilder b = new StringBuilder("== stall over 300 ms, the main thread at:\n");
                for (StackTraceElement e : Looper.getMainLooper().getThread().getStackTrace())
                    b.append("  ").append(e.getClassName()).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber()).append('\n');
                synchronized (out) {
                    out.append(b);
                }
                while (ran[0] < asked) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "buddy-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** The handler and callback of a dispatch line, without object ids (they differ each time). */
    private static String kind(String line) {
        String s = line.replaceFirst("^>>>>> Dispatching to ", "").replaceAll("@[0-9a-f]+", "").replaceAll("\\{[0-9a-f]+\\}", "");
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static long flushedAt;

    private static void flush() { // (holding out)
        long now = SystemClock.uptimeMillis();
        if (now - flushedAt < 2000) return;
        flushedAt = now;
        final String text = out.toString();
        out.setLength(0);
        new Thread(() -> {
            try {
                File f = FilePlaces.fromLinux(app, "/root/.buddy/main-stalls.log");
                if (f.length() > 2_000_000) f.delete();
                try (FileWriter w = new FileWriter(f, true)) {
                    w.write(text);
                }
            } catch (Exception ignored) {
            }
        }).start();
    }
}
