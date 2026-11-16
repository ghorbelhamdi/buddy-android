package app.buddy.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Buddy's accessibility service, switched on in Android's settings but not running.
 *
 * Android starts the service again by itself after Buddy's process was ended (to free memory, or because a permission
 * given "Only this time" ran out: Android ends the app to take it back). Some phones don't: their auto-start manager
 * (seen on a nubia RedMagic 11 Pro, ZTE's AutoLaunch; Xiaomi, Oppo and others have the like) refuses to start an app
 * in the background that the user hasn't allowed to, and the service stays listed as on while nothing runs. Without
 * it there are no phone tools and no server for the agent's events, so every run was cut off some seconds in, with a
 * message about Buddy's Linux that had nothing to do with it.
 *
 * Opening Buddy starts its process, but Android doesn't connect the service again on its own then. Switching it off
 * and on in the settings does, and Buddy can do that itself where it was given WRITE_SECURE_SETTINGS (Background
 * mode's pairing grants it over adb). Elsewhere it says what to do.
 */
final class ServiceLink {
    private static final String SECURE = "android.permission.WRITE_SECURE_SETTINGS";
    /** A fresh process waits this long for Android to connect the service by itself before it is called stuck. */
    private static final long GRACE_MS = 3000;
    /** Off, then on again after this long: the system has to see both changes. */
    private static final long TOGGLE_MS = 500;
    /** No second try within this long: connecting takes a moment. */
    private static final long RETRY_MS = 10_000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final long startedAt = SystemClock.elapsedRealtime();
    private static long triedAt;

    private ServiceLink() {
    }

    /** On in Android's settings, yet not connected to this process. */
    static boolean stuck(Context c) {
        return BuddyService.get() == null && listed(c);
    }

    private static boolean listed(Context c) {
        try {
            String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            ComponentName me = new ComponentName(c, BuddyService.class);
            for (String s : enabled.split(":")) if (me.equals(ComponentName.unflattenFromString(s))) return true;
        } catch (Exception ignored) { // a system that doesn't let an app read the setting (issue #41)
        }
        return false;
    }

    static boolean canReconnect(Context c) {
        return c.checkSelfPermission(SECURE) == PackageManager.PERMISSION_GRANTED;
    }

    /** A screen of Buddy came to the front: once Android had its chance, connect a stuck service again (if allowed). */
    static void check(Context c) {
        final Context app = c.getApplicationContext();
        long wait = Math.max(0, GRACE_MS - (SystemClock.elapsedRealtime() - startedAt));
        main.postDelayed(() -> {
            if (stuck(app)) reconnect(app);
        }, wait);
    }

    /**
     * Switches the service off and on again in Android's settings, which makes Android connect it to this (running)
     * process. Returns whether it was started; the service is there a moment later.
     */
    static boolean reconnect(Context c) {
        final Context app = c.getApplicationContext();
        if (!canReconnect(app) || !stuck(app)) return false;
        long now = SystemClock.elapsedRealtime();
        if (triedAt != 0 && now - triedAt < RETRY_MS) return true; // already on its way
        triedAt = now;
        try {
            final android.content.ContentResolver cr = app.getContentResolver();
            final String before = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            ComponentName me = new ComponentName(app, BuddyService.class);
            StringBuilder without = new StringBuilder();
            for (String s : before.split(":")) {
                if (s.isEmpty() || me.equals(ComponentName.unflattenFromString(s))) continue;
                if (without.length() > 0) without.append(':');
                without.append(s);
            }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without.toString());
            main.postDelayed(() -> {
                try {
                    // what is listed now (another service may have been switched meanwhile), with Buddy's back in it
                    String now2 = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    String mine = new ComponentName(app, BuddyService.class).flattenToString();
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            now2 == null || now2.isEmpty() ? mine : now2 + ":" + mine);
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                } catch (Exception ignored) {
                }
            }, TOGGLE_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Why a chat can't run without the service, in the user's words, or null when the service is there. Starts the
     * reconnect where Buddy may do it itself.
     */
    static String why(Context c) {
        if (BuddyService.get() != null) return null;
        if (!listed(c)) return c.getString(R.string.link_off);
        if (reconnect(c)) return c.getString(R.string.link_reconnecting);
        return c.getString(R.string.link_stuck);
    }
}
