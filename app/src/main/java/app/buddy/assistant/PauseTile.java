package app.buddy.assistant;

import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * A Quick Settings tile that pauses Buddy with one tap: banks and other guarded apps refuse to open while an
 * accessibility service runs (they take it for "this app has control"), so the user pauses before banking and
 * resumes after. It switches only Buddy's own accessibility service off and back on (other services stay on), which
 * needs the WRITE_SECURE_SETTINGS right Buddy was given over adb (or its own pairing); without it the tile opens
 * Android's accessibility settings. The phone tools server keeps running while paused, and the background mode's
 * helper isn't touched.
 */
public class PauseTile extends TileService {
    /** Buddy's entry in the enabled services list, as Android writes it and in the short form. */
    private static String[] entries(String pkg) {
        return new String[]{pkg + "/" + BuddyService.class.getName(), pkg + "/.BuddyService"};
    }

    /** The enabled services list without Buddy's entries (others kept), as it should be while paused. */
    private static String withoutBuddy(String list, String pkg) {
        StringBuilder out = new StringBuilder();
        for (String s : list.split(":")) {
            boolean mine = false;
            for (String e : entries(pkg)) mine |= s.contentEquals(e);
            if (!mine && !s.isEmpty()) {
                if (out.length() > 0) out.append(':');
                out.append(s);
            }
        }
        return out.toString();
    }

    private static boolean running(String list, String pkg) {
        for (String e : entries(pkg)) {
            for (String s : list.split(":")) if (s.contentEquals(e)) return true;
        }
        return false;
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = list != null && running(list, getPackageName());
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setSubtitle(getString(on ? R.string.pause_tile_running : R.string.pause_tile_paused));
        t.updateTile();
    }

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        String pkg = getPackageName();
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) list = "";
        if (running(list, pkg)) {
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    withoutBuddy(list, pkg));
        } else if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // back on: Buddy's entry goes at the end, after any others, as Android itself would keep them
            String back = list.isEmpty() ? entries(pkg)[0] : list + ":" + entries(pkg)[0];
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, back);
        } else {
            try {
                startActivityAndCollapse(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
        update();
    }
}
