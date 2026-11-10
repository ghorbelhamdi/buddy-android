package app.buddy.assistant;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.Display;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * "View steps" for a chat's runs: each phone action an agent took (the chat's step line, its result) with a small
 * screenshot just after it, and the run's time and outcome. Kept in Buddy's own storage for the chat's last 3 runs,
 * deleted with the chat, never for an incognito chat. Screens an app protects come out black.
 */
final class StepLog {
    /** Steps kept per run (a runaway can't fill the phone), and screenshots: the latest ones (space). */
    private static final int KEEP_RUNS = 3, MAX_STEPS = 500, MAX_SHOTS = 80, SHORT_SIDE = 360;
    /** Screenshots not taken yet: fast steps (do_steps) skip theirs rather than queue up behind the screen. */
    private static final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    /** The folder of each chat's run in progress. */
    private static final Map<String, File> current = new ConcurrentHashMap<>();
    private static final Map<String, Integer> counts = new ConcurrentHashMap<>();
    private static final ExecutorService shots = Executors.newSingleThreadExecutor();

    private StepLog() {
    }

    static File dir(Context c, String chat) {
        return new File(c.getFilesDir(), "steps/" + chat);
    }

    static void runStarted(Context c, Sessions.Session s) {
        current.remove(s.id);
        counts.remove(s.id);
        if (s.incognito) return;
        File d = new File(dir(c, s.id), String.valueOf(System.currentTimeMillis()));
        if (!d.mkdirs()) return;
        current.put(s.id, d);
        File[] runs = dir(c, s.id).listFiles();
        if (runs != null && runs.length > KEEP_RUNS) {
            java.util.Arrays.sort(runs);
            for (int i = 0; i < runs.length - KEEP_RUNS; i++) Linux.deleteTree(runs[i]);
        }
    }

    static void runEnded(Context c, Sessions.Session s) {
        File d = current.remove(s.id);
        counts.remove(s.id);
        if (d == null) return;
        try {
            Linux.write(new File(d, "run.json"), new JSONObject().put("end", System.currentTimeMillis())
                    .put("outcome", s.outcome == null ? "done" : s.outcome).put("agent", s.brainName())
                    .put("context", s.ctxUsed).toString());
        } catch (Exception ignored) {
        }
    }

    static void deleteChat(Context c, String chat) {
        current.remove(chat);
        Linux.deleteTree(dir(c, chat));
    }

    /** One action of a chat's run, and a screenshot of how the screen looks right after it. */
    static void record(BuddyService svc, String chat, String tool, JSONObject args, String result) {
        File d = chat == null ? null : current.get(chat);
        if (d == null) return;
        int n = counts.merge(chat, 1, Integer::sum);
        if (n > MAX_STEPS) return;
        try {
            String label = ChatHub.describeTool(tool, args);
            String first = result == null ? "" : result.trim();
            int nl = first.indexOf('\n');
            if (nl > 0) first = first.substring(0, nl);
            if (first.length() > 160) first = first.substring(0, 157) + "…";
            String line = new JSONObject().put("n", n).put("t", System.currentTimeMillis()).put("tool", tool)
                    .put("label", label == null ? tool : label).put("result", first).toString() + "\n";
            try (FileOutputStream o = new FileOutputStream(new File(d, "steps.jsonl"), true)) {
                o.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        File shot = new File(d, n + ".jpg"), old = new File(d, (n - MAX_SHOTS) + ".jpg");
        if (pending.get() >= 2) return; // the screen moves faster than screenshots: this step goes without
        pending.incrementAndGet();
        shots.execute(() -> {
            try {
                shoot(svc, chat, shot);
                if (n > MAX_SHOTS) old.delete(); // a long run (Settings: 200 or no limit) keeps its latest screenshots
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    /** A small JPEG of the screen, a moment after the action (the screen needs it to settle). */
    private static void shoot(BuddyService svc, String chat, File out) {
        try {
            Thread.sleep(600);
            if (Background.on(svc, chat) && Background.displayOf(chat) != Display.DEFAULT_DISPLAY) {
                // a background chat's steps happen on its own display: the picture comes from there
                byte[] jpg = Background.frame(svc, chat, SHORT_SIDE, 60);
                if (jpg != null) try (FileOutputStream o = new FileOutputStream(out)) {
                    o.write(jpg);
                }
                return;
            }
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult r) {
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                        r.getHardwareBuffer().close();
                        if (hw != null) {
                            float scale = Math.min(1f, (float) SHORT_SIDE / Math.min(hw.getWidth(), hw.getHeight()));
                            Bitmap small = Bitmap.createScaledBitmap(hw.copy(Bitmap.Config.ARGB_8888, false),
                                    Math.round(hw.getWidth() * scale), Math.round(hw.getHeight() * scale), true);
                            try (FileOutputStream o = new FileOutputStream(out)) {
                                small.compress(Bitmap.CompressFormat.JPEG, 60, o);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    done.countDown();
                }

                @Override
                public void onFailure(int code) { // too soon after another screenshot: this step goes without
                    done.countDown();
                }
            });
            done.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
