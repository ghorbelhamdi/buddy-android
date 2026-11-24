package app.buddy.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;

/** Setup progress from saved state: used for the Chats header, the Settings card and the guide. */
final class SetupState {
    /** The steps' short names as string resources, in order. */
    private static final int[] STEP_NAMES = {
            R.string.setup_step_name_a11y, R.string.setup_step_name_install, R.string.setup_step_signin_title,
            R.string.setup_step_name_background, R.string.setup_step_try_title,
    };

    /** Short name of step n (0 to 4) in the app's language, e.g. after "Next:" in the Chats header. */
    static String step(int n) {
        return BuddyApp.str(STEP_NAMES[n]);
    }

    final boolean[] done = new boolean[5];

    SetupState(Context c) {
        String agent = Prefs.backend(c);
        done[0] = accessibilityEnabled(c);
        done[1] = installed(c, agent);
        done[2] = signedIn(c, agent);
        done[3] = batteryOk(c);
        done[4] = Prefs.hadReply(c);
    }

    static boolean installed(Context c, String agent) {
        String s = Prefs.agentState(c, agent);
        return Linux.installed(c) && s != null && !"NEED_INSTALL".equals(s);
    }

    static boolean signedIn(Context c, String agent) {
        return Linux.installed(c) && "SIGNED_IN".equals(Prefs.agentState(c, agent));
    }

    /**
     * Signed in with the plan itself, not an API key: what voice input, live voice, Remote Control and the ChatGPT
     * app's Codex session need.
     */
    static boolean planSignedIn(Context c, String agent) {
        return signedIn(c, agent) && Prefs.agentPlan(c, agent);
    }

    /** The agent's own models can run chats: it's signed in with its plan, or (Claude Code) with a token. */
    static boolean ownModels(Context c, String agent) {
        return signedIn(c, agent) && (Prefs.agentPlan(c, agent) || Prefs.agentToken(c, agent));
    }

    int count() {
        int n = 0;
        for (boolean d : done) if (d) n++;
        return n;
    }

    /** Index of the first unfinished step, or -1. */
    int next() {
        for (int i = 0; i < done.length; i++) if (!done[i]) return i;
        return -1;
    }

    /** Chats can run once the service is on and the default agent is installed and signed in. */
    boolean canRun() {
        return done[0] && done[2] && BuddyService.get() != null;
    }

    /**
     * Is Buddy's accessibility service on? The service being connected says so by itself; before it has connected,
     * the setting that names the enabled services does. Some systems (seen on a custom Android 17 ROM, issue #41)
     * don't let an app read that setting, and Buddy then kept asking for a switch that was on already.
     */
    static boolean accessibilityEnabled(Context c) {
        if (BuddyService.get() != null) return true;
        ComponentName me = new ComponentName(c, BuddyService.class);
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        TextUtils.SimpleStringSplitter s = new TextUtils.SimpleStringSplitter(':');
        s.setString(enabled);
        while (s.hasNext()) {
            if (me.equals(ComponentName.unflattenFromString(s.next()))) return true;
        }
        return false;
    }

    /** Buddy may keep working in the background: Android doesn't hold it back to save battery. */
    static boolean batteryOk(Context c) {
        return c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
    }

    // ------------------------------------------------------------------ permissions

    /**
     * The permissions Buddy can be missing, in the order "Set up what's missing" goes through them (Check Buddy): the
     * accessibility service, notifications, battery, then the ones only some choices need.
     */
    static final String A11Y = "a11y", NOTIFY = "notify", BATTERY = "battery", LISTENER = "listener", EXACT = "exact",
            BLUETOOTH = "bluetooth", LOCATION = "location";
    private static final String[] PERMISSIONS = {A11Y, NOTIFY, BATTERY, LISTENER, EXACT, BLUETOOTH, LOCATION};

    /**
     * Does the user's setup need it? (Notification access only for rules or pausing media, exact alarms for schedules,
     * Nearby devices and location only for schedules that start on a Bluetooth device or a named Wi-Fi network.)
     */
    static boolean needed(Context c, String id) {
        switch (id) {
            case NOTIFY: return android.os.Build.VERSION.SDK_INT >= 33;
            case LISTENER: return rulesWanted(c) || Prefs.pauseMediaForSpeech(c);
            case EXACT: return android.os.Build.VERSION.SDK_INT >= 31 && timedTasks(c);
            // a schedule that starts when a Bluetooth device connects, or on a named Wi-Fi network (see Events)
            case BLUETOOTH: return android.os.Build.VERSION.SDK_INT >= 31 && Events.anyWants(c, true);
            case LOCATION: return Events.anyWants(c, false);
            default: return true;
        }
    }

    /** Is it granted? */
    static boolean granted(Context c, String id) {
        switch (id) {
            case A11Y: return accessibilityEnabled(c);
            case NOTIFY: return android.os.Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            case BATTERY: return batteryOk(c);
            case LISTENER: return NotificationWatcher.enabled(c);
            case EXACT: return Schedules.exact(c);
            case BLUETOOTH: return Events.bluetoothAllowed(c);
            case LOCATION: return Events.locationAllowed(c);
            default: return true;
        }
    }

    /** A permission Buddy asks Android for itself, in Android's own dialog: its names; null for the others. */
    static String[] asked(String id) {
        switch (id) {
            case NOTIFY: return new String[]{android.Manifest.permission.POST_NOTIFICATIONS};
            case BLUETOOTH: return new String[]{android.Manifest.permission.BLUETOOTH_CONNECT};
            case LOCATION: return new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION};
            default: return null;
        }
    }

    /** The system screen where the user grants it; null for a permission Buddy asks for itself (see asked). */
    static Intent screen(Context c, String id) {
        Uri me = Uri.parse("package:" + c.getPackageName());
        switch (id) {
            case A11Y: return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            case BATTERY: return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, me);
            case LISTENER: return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            case EXACT: return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, me);
            default: return null;
        }
    }

    /** Can this phone grant it? A screen some phones leave out (notification access on low-memory ones) can't. */
    static boolean grantable(Context c, String id) {
        Intent i = screen(c, id);
        return i == null || i.resolveActivity(c.getPackageManager()) != null;
    }

    /** What the user's setup needs, isn't granted and this phone can grant, in order. */
    static java.util.List<String> missing(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : PERMISSIONS) if (needed(c, id) && !granted(c, id) && grantable(c, id)) out.add(id);
        return out;
    }

    /** Its name as the user knows it. */
    static String name(Context c, String id) {
        switch (id) {
            case A11Y: return c.getString(R.string.check_a11y);
            case NOTIFY: return c.getString(R.string.walk_notify);
            case BATTERY: return c.getString(R.string.check_battery);
            case LISTENER: return c.getString(R.string.check_notifications);
            case BLUETOOTH: return c.getString(R.string.walk_bluetooth);
            case LOCATION: return c.getString(R.string.walk_location);
            default: return c.getString(R.string.walk_exact);
        }
    }

    /** A notification rule is switched on (they all need notification access). */
    static boolean rulesWanted(Context c) {
        for (Rules.Rule r : Rules.all(c)) if (r.on) return true;
        return false;
    }

    /** A chat has a schedule at set times, or a message its agent scheduled: their alarms should be on time. */
    private static boolean timedTasks(Context c) {
        for (Sessions.Session s : new java.util.ArrayList<>(ChatHub.get(c).sessions.all)) {
            if (s.schedule != null && s.schedule.timed && !s.schedule.paused || !s.later.isEmpty()) return true;
        }
        return false;
    }
}
