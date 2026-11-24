package app.buddy.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Mute apps on hidden screens" (Background mode settings): an app in front on a chat's hidden screen plays its sound
 * through the phone's speaker (a video that starts by itself). While it's there, and not also on the user's own
 * screen, Buddy silences it through background mode's helper with Android's PLAY_AUDIO app op (audioserver then plays
 * its sound at volume 0), and puts the op back as it was once the app leaves the hidden screen, comes to the real one,
 * the hidden screen closes or the switch goes off.
 *
 * What Buddy changed is written down before the change (a list kept in Buddy's storage), so it is undone after a crash
 * or a restart too, as soon as the helper answers again: the op outlives Buddy, the helper and a restart of the phone.
 * An app whose op someone else set (the user, with adb) is never touched, and one changed again since Buddy muted it is
 * left as it is then. Without the helper running Buddy does nothing and says nothing. One worker thread.
 */
final class HiddenMute {
    private static final Pattern PKG = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    private static final Pattern LINE = Pattern.compile("^(Uid mode: )?PLAY_AUDIO: ([a-z]+)\\b.*");
    /** Looks again this often while a hidden screen is up or something is still muted (the helper may be back). */
    private static final long TICK_MS = 10_000, IDLE_TICK_MS = 60_000, SETTLE_MS = 1500;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static volatile boolean ticking;

    private HiddenMute() {
    }

    /** Something may have changed (a window, a hidden screen, the switch): a look a moment later, once. Any thread. */
    static void check(Context c) {
        if (app == null) app = c.getApplicationContext();
        main.removeCallbacks(look);
        main.postDelayed(look, SETTLE_MS);
    }

    private static final Runnable look = () -> worker.execute(HiddenMute::reconcile);

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            look.run();
        }
    };

    /** Everything Buddy muted is put back now (the helper is about to quit). Blocks: not for the main thread. */
    static void restoreAllNow(Context c) {
        if (app == null) app = c.getApplicationContext();
        try {
            worker.submit(() -> {
                for (String pkg : muted().keySet()) restore(pkg);
            }).get();
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences("hidden_mute", Context.MODE_PRIVATE);
    }

    /** Package → the mode Buddy found and puts back ("allow" or "default"). */
    @SuppressWarnings("unchecked")
    private static Map<String, String> muted() {
        return (Map<String, String>) prefs().getAll();
    }

    private static void reconcile() {
        Set<String> want = wanted();
        Map<String, String> done = muted();
        for (String pkg : want) if (!done.containsKey(pkg)) mute(pkg);
        for (String pkg : done.keySet()) if (!want.contains(pkg)) restore(pkg);
        // while a hidden screen is up, or something waits to be put back, look again now and then
        boolean screens = !Background.hiddenChats().isEmpty();
        if ((screens || !muted().isEmpty()) && !ticking) {
            ticking = true;
            main.postDelayed(tick, screens ? TICK_MS : IDLE_TICK_MS);
        }
    }

    /** Apps in front on a hidden screen and not on the real one; none with the switch off or the service gone. */
    private static Set<String> wanted() {
        Set<String> out = new HashSet<>();
        BuddyService svc = BuddyService.get();
        if (svc == null || !Prefs.muteHidden(app) || !Background.setUp(app)) return out;
        for (String chat : Background.hiddenChats()) {
            String pkg = Background.appOn(svc, chat);
            if (pkg != null) out.add(pkg);
        }
        if (out.isEmpty()) return out;
        out.removeAll(onRealScreen(svc));
        out.remove(app.getPackageName());
        out.remove("android");
        out.remove("com.android.systemui");
        ResolveInfo home = app.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), 0);
        if (home != null && home.activityInfo != null) out.remove(home.activityInfo.packageName);
        return out;
    }

    private static Set<String> onRealScreen(BuddyService svc) {
        Set<String> out = new HashSet<>();
        try {
            List<AccessibilityWindowInfo> ws = svc.getWindowsOnAllDisplays().get(Display.DEFAULT_DISPLAY);
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null) out.add(r.getPackageName().toString());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ the op

    private static void mute(String pkg) {
        if (!PKG.matcher(pkg).matches()) return;
        String[] got = Background.execIfRunning(app, "cmd appops get " + pkg + " PLAY_AUDIO", 10);
        if (got == null || !"0".equals(got[0].trim())) return; // no helper: nothing done, nothing said
        String[] m = modes(got[1]);
        if (m == null) return; // an answer Buddy doesn't know: hands off
        if (!plain(m[0]) || !plain(m[1])) return; // set by someone else (the user muted it, or let it play only so)
        String before = m[1].isEmpty() ? "default" : m[1];
        prefs().edit().putString(pkg, before).commit(); // written down first: a crash in between still gets undone
        // (whatever this answers, the entry stays: putting it back reads the mode first, and lets go of an app
        // whose mode isn't Buddy's "ignore")
        Background.execIfRunning(app, "cmd appops set " + pkg + " PLAY_AUDIO ignore", 10);
    }

    private static void restore(String pkg) {
        if (!PKG.matcher(pkg).matches()) {
            prefs().edit().remove(pkg).commit();
            return;
        }
        String[] got = Background.execIfRunning(app, "cmd appops get " + pkg + " PLAY_AUDIO", 10);
        if (got == null) return; // no helper now: kept, for when it answers again
        if (!"0".equals(got[0].trim())) {
            if (!installed(pkg)) prefs().edit().remove(pkg).commit(); // uninstalled: nothing left to undo
            return; // otherwise a failed read: kept, tried again
        }
        String[] m = modes(got[1]);
        if (m == null) return; // an answer Buddy doesn't know: kept, tried again
        if (!"ignore".equals(m[1])) { // changed since by someone else (or never took): nothing of Buddy's to undo
            prefs().edit().remove(pkg).commit();
            return;
        }
        String before = muted().get(pkg);
        if (!"allow".equals(before)) before = "default";
        String[] set = Background.execIfRunning(app, "cmd appops set " + pkg + " PLAY_AUDIO " + before, 10);
        if (set != null && "0".equals(set[0].trim())) prefs().edit().remove(pkg).commit();
    }

    private static boolean installed(String pkg) {
        try {
            app.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** No mode of its own, or the usual one. */
    private static boolean plain(String mode) {
        return mode.isEmpty() || "allow".equals(mode) || "default".equals(mode);
    }

    /**
     * `cmd appops get <pkg> PLAY_AUDIO`: {the uid's mode, the package's mode} ("" where there is none), or null for an
     * answer that isn't one of AppOpsService's: "No operations." (then "Default mode: …"), "Uid mode: PLAY_AUDIO: x",
     * "PLAY_AUDIO: x; time=…".
     */
    static String[] modes(String out) {
        String uid = "", pkg = "";
        boolean known = false;
        for (String l : out.split("\n")) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("Default mode:")) continue;
            if (l.equals("No operations.")) {
                known = true;
                continue;
            }
            Matcher m = LINE.matcher(l);
            if (!m.matches()) return null;
            known = true;
            if (m.group(1) != null) uid = m.group(2);
            else pkg = m.group(2);
        }
        return known ? new String[]{uid, pkg} : null;
    }
}
