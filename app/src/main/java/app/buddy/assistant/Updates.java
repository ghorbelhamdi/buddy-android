package app.buddy.assistant;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static app.buddy.assistant.Ui.*;

/**
 * Buddy's own updates, only ever from its official GitHub release (Android also refuses an APK that isn't signed
 * like the installed one). Used by the update_buddy tool, the daily check behind the "update available"
 * notification (Settings → Update notices), and the sheet that notification opens.
 */
public final class Updates extends BroadcastReceiver {
    static final String REPO = "https://github.com/ghorbelhamdi/buddy-android";
    /** For the agent (update_buddy), so English; the sheet shows R.string.update_failed_manual instead. */
    static final String MANUAL = "You can also get it at " + REPO + "/releases/latest (buddy.apk), or with Obtainium "
            + "for automatic updates (see " + REPO + "#updating).";
    private static final String API = "https://api.github.com/repos/ghorbelhamdi/buddy-android/releases/latest";
    private static final String CHANNEL = "updates", ACTION_MUTE = "app.buddy.assistant.MUTE_UPDATES";
    private static final int ID = 3;
    private static final long DAY = 24 * 3600_000L;

    /** The latest release. */
    static final class Release {
        final String version, notes, apkUrl;

        Release(String version, String notes, String apkUrl) {
            this.version = version;
            this.notes = notes;
            this.apkUrl = apkUrl;
        }

        boolean newer() {
            return compare(version, BuildInfo.VERSION) > 0;
        }
    }

    /** Asks GitHub (network: call off the main thread). */
    static Release latest() throws Exception {
        JSONObject rel = new JSONObject(get(API));
        String apk = null;
        JSONArray assets = rel.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String url = a.optString("browser_download_url");
            if ("buddy.apk".equals(a.optString("name")) && url.startsWith(REPO + "/releases/download/")) apk = url;
        }
        String notes = rel.optString("body").trim();
        if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
        return new Release(rel.optString("tag_name").replaceFirst("^v", ""), notes, apk);
    }

    /** Result of {@link #install}: the installer is open, or Android's "allow installs" page is (then try again). */
    static final String OPENED = "opened", NEEDS_PERMISSION = "permission";

    /**
     * Downloads the release's buddy.apk, checks it's a newer Buddy, and opens Android's installer (network: off the
     * main thread). Returns OPENED, NEEDS_PERMISSION, or what went wrong (in English, for the agent).
     */
    static String install(Context c, Release r) {
        return install(c, r, false);
    }

    /** {@link #install(Context, Release)}; with user, what went wrong is in the user's language (for the sheet). */
    private static String install(Context c, Release r, boolean user) {
        if (r.apkUrl == null) return user ? c.getString(R.string.update_error_no_apk) : "The release has no buddy.apk yet.";
        File out = FilesProvider.apkFile(c);
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(r.apkUrl).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(60000);
            try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                copy(in, o);
            } finally {
                h.disconnect();
            }
        } catch (IOException e) {
            return user ? c.getString(R.string.update_error_download, e.getMessage())
                    : "The download failed (" + e.getMessage() + ").";
        }
        android.content.pm.PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(out.getPath(), 0);
        if (pi == null || !c.getPackageName().equals(pi.packageName) || pi.getLongVersionCode() <= BuildInfo.VERSION_CODE) {
            return user ? c.getString(R.string.update_error_not_newer)
                    : "The downloaded file isn't a newer Buddy, so it wasn't opened.";
        }
        if (!c.getPackageManager().canRequestPackageInstalls()) {
            Background.startOnUserScreen(c, new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return NEEDS_PERMISSION;
        }
        Background.startOnUserScreen(c, new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FilesProvider.apkUri(), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
        cancel(c);
        return OPENED;
    }

    // ------------------------------------------------------------ daily check

    /** At most once a day (if Update notices is on): notify once per new version. */
    static void maybeCheck(Context c) {
        if (!Prefs.updateNotices(c)) return;
        long now = System.currentTimeMillis();
        if (now - Prefs.updateCheckedAt(c) < DAY) return;
        new Thread(() -> {
            try {
                Release r = latest();
                Prefs.setUpdateCheckedAt(c, now);
                if (r.newer() && r.apkUrl != null && !r.version.equals(Prefs.updateNotified(c))) {
                    notify(c, r);
                    Prefs.setUpdateNotified(c, r.version);
                }
            } catch (Exception ignored) {
                // offline: try again at the next check
            }
        }).start();
    }

    private static void notify(Context c, Release r) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 30,
                new Intent(c, MainActivity.class).putExtra("update", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent mute = PendingIntent.getBroadcast(c, 31,
                new Intent(c, Updates.class).setAction(ACTION_MUTE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = c.getString(R.string.update_notification_text, BuildInfo.VERSION);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(R.string.update_available_title, r.version))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.update_action), open).build())
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.update_mute), mute).build())
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(ID, n);
        } catch (SecurityException ignored) {
            // notifications not allowed
        }
    }

    static void cancel(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (!ACTION_MUTE.equals(intent.getAction())) return;
        Prefs.setUpdateNotices(c, false);
        cancel(c);
        Toast.makeText(c, R.string.update_muted, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------ the sheet

    /** "Buddy X is available": what's new, and Update. Checks GitHub first; says so if Buddy is up to date. */
    static void showSheet(Activity a) {
        Handler main = new Handler(Looper.getMainLooper());
        Toast checking = Toast.makeText(a, R.string.update_checking, Toast.LENGTH_SHORT);
        checking.show();
        new Thread(() -> {
            Release r;
            try {
                r = latest();
            } catch (Exception e) {
                main.post(() -> Toast.makeText(a, R.string.update_check_failed, Toast.LENGTH_LONG).show());
                return;
            }
            main.post(() -> {
                checking.cancel();
                if (a.isFinishing() || a.isDestroyed()) return;
                if (!r.newer()) {
                    Toast.makeText(a, a.getString(R.string.update_up_to_date, BuildInfo.VERSION), Toast.LENGTH_LONG).show();
                    cancel(a);
                    return;
                }
                sheet(a, r);
            });
        }).start();
    }

    private static void sheet(Activity a, Release r) {
        LinearLayout c = column(a);
        c.addView(title(a, a.getString(R.string.update_available_title, r.version), 18));
        TextView sub = text(a, a.getString(R.string.update_sheet_text, BuildInfo.VERSION), 13, MUTED);
        sub.setPaddingRelative(0, dp(a, 4), 0, dp(a, 12));
        c.addView(sub);
        if (!r.notes.isEmpty()) {
            ScrollView sv = new ScrollView(a);
            TextView notes = text(a, r.notes.replace("**", "").replace("## ", "").replace("### ", ""), 14, TEXT);
            notes.setPaddingRelative(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
            sv.addView(notes);
            sv.setBackground(rounded(a, BG, 12));
            c.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    Math.min(dp(a, 280), (int) (a.getResources().getDisplayMetrics().heightPixels * 0.4f))));
        }
        LinearLayout btns = row(a);
        Button later = button(a, a.getString(R.string.update_later), false);
        Button update = button(a, a.getString(R.string.update_action), true);
        btns.addView(later, new LinearLayout.LayoutParams(0, dp(a, 44), 1));
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(0, dp(a, 44), 1);
        up.setMarginStart(dp(a, 10));
        btns.addView(update, up);
        LinearLayout.LayoutParams bp = full();
        bp.topMargin = dp(a, 14);
        c.addView(btns, bp);
        Dialog d = bottomSheet(a, c);
        later.setOnClickListener(v -> d.dismiss());
        Handler main = new Handler(Looper.getMainLooper());
        update.setOnClickListener(v -> {
            update.setEnabled(false);
            update.setText(R.string.update_downloading);
            new Thread(() -> {
                String res = install(a, r, true);
                main.post(() -> {
                    if (OPENED.equals(res)) {
                        d.dismiss();
                    } else if (NEEDS_PERMISSION.equals(res)) {
                        Toast.makeText(a, R.string.update_needs_permission, Toast.LENGTH_LONG).show();
                        update.setEnabled(true);
                        update.setText(R.string.update_action);
                    } else {
                        Toast.makeText(a, a.getString(R.string.update_failed_manual, res, REPO + "/releases/latest",
                                REPO + "#updating"), Toast.LENGTH_LONG).show();
                        update.setEnabled(true);
                        update.setText(R.string.update_action);
                    }
                });
            }).start();
        });
        d.show();
    }

    // ------------------------------------------------------------ helpers

    private static String get(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(20000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(in, b);
            return b.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    static void copy(InputStream in, OutputStream o) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
    }

    /** "2.1.10" vs "2.1.9": compares number by number. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
