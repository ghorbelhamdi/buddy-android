package app.buddy.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;

/** Quick setup progress from saved state (no ping): used for the Chats header and the Settings card. */
final class SetupState {
    static final String[] STEPS = {
            "Turn on the accessibility service", "Allow commands in Termux", "Set up the Termux helper",
            "Allow background running", "Try it",
    };
    /** Must match HELPER_VERSION in termux/buddy-run. */
    static final int HELPER_VERSION = 9;

    final boolean[] done = new boolean[5];

    SetupState(Context c) {
        done[0] = accessibilityEnabled(c);
        done[1] = Termux.installed(c) && Termux.permitted(c);
        done[2] = helperCurrent(c) && agentReady(c, Prefs.backend(c));
        PowerManager pm = c.getSystemService(PowerManager.class);
        done[3] = pm.isIgnoringBatteryOptimizations(c.getPackageName())
                && Termux.installed(c) && pm.isIgnoringBatteryOptimizations(Termux.PACKAGE);
        done[4] = Prefs.hadReply(c);
    }

    static boolean helperCurrent(Context c) {
        return Prefs.helperStatus(c) != null && Prefs.helperVersion(c) >= HELPER_VERSION;
    }

    static boolean agentInstalled(Context c, String agent) {
        String h = Prefs.helperStatus(c);
        return h != null && h.contains(agent);
    }

    /** Installed and signed in. */
    static boolean agentReady(Context c, String agent) {
        String s = Prefs.signedAgents(c);
        return agentInstalled(c, agent) && s != null && s.contains(agent);
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

    /** Chats can run once the service, Termux permission and helper are in place. */
    boolean canRun() {
        return done[0] && done[1] && BuddyService.get() != null;
    }

    static boolean accessibilityEnabled(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(c, BuddyService.class);
        TextUtils.SimpleStringSplitter s = new TextUtils.SimpleStringSplitter(':');
        s.setString(enabled);
        while (s.hasNext()) {
            if (me.equals(ComponentName.unflattenFromString(s.next()))) return true;
        }
        return false;
    }
}
