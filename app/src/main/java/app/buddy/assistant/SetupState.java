package app.buddy.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;

/** Setup progress from saved state: used for the Chats header, the Settings card and the guide. */
final class SetupState {
    static final String[] STEPS = {
            "Turn on the accessibility service", "Install your agent", "Sign in", "Allow background running", "Try it",
    };

    final boolean[] done = new boolean[5];

    SetupState(Context c) {
        String agent = Prefs.backend(c);
        done[0] = accessibilityEnabled(c);
        done[1] = installed(c, agent);
        done[2] = signedIn(c, agent);
        done[3] = c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
        done[4] = Prefs.hadReply(c);
    }

    static boolean installed(Context c, String agent) {
        String s = Prefs.agentState(c, agent);
        return Linux.installed(c) && s != null && !"NEED_INSTALL".equals(s);
    }

    static boolean signedIn(Context c, String agent) {
        return Linux.installed(c) && "SIGNED_IN".equals(Prefs.agentState(c, agent));
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
