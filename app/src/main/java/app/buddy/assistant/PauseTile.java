package app.buddy.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Bank mode: a Quick Settings tile that turns every accessibility service off with one tap, and puts them back after.
 * Banks and other guarded apps refuse to open while any accessibility service runs (they take it for "an app has
 * control"), and Buddy's isn't the only one: Link to Windows and the like count too. The screen shows nothing about
 * this, so the whole list is saved and restored, never just Buddy's entry. Writing those settings needs the
 * WRITE_SECURE_SETTINGS right Buddy was given over adb (or its own pairing); without it the tile opens Android's
 * accessibility settings instead.
 *
 * While paused, Buddy's own service is off too: its bubble, screen control and phone tools server stop (the background
 * mode's helper and chatting go on). That's what makes the bank open, and resuming brings them back.
 */
public class PauseTile extends TileService {
    private static final String WRITE = "android.permission.WRITE_SECURE_SETTINGS";

    /** The enabled services list, as Android keeps it ("" when empty). */
    static String enabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s == null ? "" : s;
    }

    /** True while Bank mode holds the services it turned off (they come back on resume). */
    static boolean paused(Context c) {
        return Prefs.savedA11y(c) != null;
    }

    /** Buddy may write secure settings (given over adb, or by its own pairing). */
    static boolean allowed(Context c) {
        return c.checkSelfPermission(WRITE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Turns every accessibility service off, remembering the list; false when Buddy may not write settings. */
    static boolean pause(Context c) {
        if (!allowed(c)) return false;
        Prefs.setSavedA11y(c, enabled(c));
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
        Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        return true;
    }

    /** Puts the paused services back, plus any that were switched on in between; false when it may not write. */
    static boolean resume(Context c) {
        if (!allowed(c)) return false;
        String saved = Prefs.savedA11y(c);
        if (saved == null) return true;
        Prefs.setSavedA11y(c, null);
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merge(enabled(c), saved));
        Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        return true;
    }

    /** Two ":"-separated lists joined, no entry twice (the current ones first, in their order). */
    private static String merge(String now, String saved) {
        StringBuilder out = new StringBuilder();
        for (String list : new String[]{now, saved}) {
            for (String s : list.split(":")) {
                if (s.isEmpty()) continue;
                boolean seen = false;
                for (String had : out.toString().split(":")) seen |= had.contentEquals(s);
                if (seen) continue;
                if (out.length() > 0) out.append(':');
                out.append(s);
            }
        }
        return out.toString();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean off = paused(this);
        t.setState(off ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(getString(off ? R.string.pause_tile_paused : R.string.pause_tile_running));
        t.updateTile();
    }

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        boolean done = paused(this) ? resume(this) : pause(this);
        if (!done) {
            try {
                startActivityAndCollapse(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
        update();
    }
}
