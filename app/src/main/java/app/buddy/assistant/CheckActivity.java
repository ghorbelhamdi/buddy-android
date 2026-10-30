package app.buddy.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import static app.buddy.assistant.Ui.*;

/**
 * Settings → Check Buddy: tests each part live (off the main thread, each with a short timeout) and says what's wrong
 * in a sentence, with a button that fixes it or opens the right settings. The report (to copy, or to start a GitHub
 * bug report with) holds versions and results only: never chats, screen text or notifications.
 */
public class CheckActivity extends Screen {
    private static final String ISSUES = "https://github.com/ghorbelhamdi/buddy-android/issues/new?template=bug_report.yml";
    /** About what an agent, node and Alpine's packages need to install or update. */
    private static final long SPACE_NEEDED = 1024L * 1024 * 1024;

    /** One check's result: ok, a sentence, and the fix (null: none). */
    private static final class Result {
        final String name, detail;
        final boolean ok;
        final String fixLabel;
        final Runnable fix;

        Result(String name, boolean ok, String detail, String fixLabel, Runnable fix) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
            this.fixLabel = fixLabel;
            this.fix = fix;
        }
    }

    private LinearLayout body;
    private final List<Result> results = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = title(this, getString(R.string.check_title), 18);
        t.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(t, weight1());
        root.addView(top, full());
        ScrollView sv = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 20), dp(this, 4), dp(this, 20), dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame));
    }

    @Override
    protected void onResume() {
        super.onResume();
        run(); // again after a fix in Android's settings
    }

    private void run() {
        body.removeAllViews();
        body.addView(text(this, getString(R.string.check_running), 14, MUTED), full());
        new Thread(() -> {
            List<Result> r = checks();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                results.clear();
                results.addAll(r);
                render();
            });
        }, "check").start();
    }

    private List<Result> checks() {
        List<Result> r = new ArrayList<>();
        BuddyService svc = BuddyService.get();
        r.add(new Result(getString(R.string.check_a11y), svc != null, svc != null ? null : getString(R.string.check_a11y_bad),
                getString(R.string.check_open_settings), () -> Ui.openAccessibilitySettings(this)));

        boolean rules = false;
        for (Rules.Rule rule : Rules.all(this)) rules |= rule.on;
        if (rules) {
            boolean granted = getSystemService(android.app.NotificationManager.class)
                    .isNotificationListenerAccessGranted(new ComponentName(this, NotificationWatcher.class));
            r.add(new Result(getString(R.string.check_notifications), granted, granted ? null : getString(R.string.check_notifications_bad),
                    getString(R.string.check_open_settings),
                    () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        }

        boolean battery = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        r.add(new Result(getString(R.string.check_battery), battery, battery ? null : getString(R.string.check_battery_bad),
                getString(R.string.check_fix), () -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())))));

        Runnable openSettings = () -> startActivity(new Intent(this, SetupActivity.class));
        if (!Linux.installed(this)) {
            r.add(new Result(getString(R.string.check_linux), false, getString(R.string.check_linux_missing),
                    getString(R.string.check_open_settings), openSettings));
        } else {
            long t0 = System.currentTimeMillis();
            String out;
            try {
                out = Linux.exec(this, "true", 20);
            } catch (Exception e) {
                out = String.valueOf(e.getMessage());
            }
            boolean ok = out.trim().endsWith("[exit 0]");
            r.add(new Result(getString(R.string.check_linux), ok, ok ? getString(R.string.check_linux_ok,
                    (System.currentTimeMillis() - t0) / 1000.0) : getString(R.string.check_linux_bad, clip(out.trim(), 200)),
                    null, null));
            int want = helperVersion(null), have = helperVersion(new File(Linux.root(this), "root/bin/buddy-run"));
            boolean current = want > 0 && want == have;
            r.add(new Result(getString(R.string.check_helper), current, current ? null : getString(R.string.check_helper_bad),
                    getString(R.string.check_fix), () -> new Thread(() -> {
                try {
                    Helper.resync(this);
                } catch (Exception ignored) {
                }
                runOnUiThread(this::run);
            }).start()));
        }

        boolean server = svc != null && health();
        r.add(new Result(getString(R.string.check_server), server, server ? null : getString(R.string.check_server_bad, McpServer.PORT),
                getString(R.string.check_open_settings), () -> Ui.openAccessibilitySettings(this)));

        String agent = Prefs.backend(this);
        Account acc = Account.of(agent);
        boolean installed = SetupState.installed(this, agent), signed = SetupState.signedIn(this, agent);
        r.add(new Result(acc.name(), signed, !installed ? getString(R.string.setup_agent_not_installed)
                : !signed ? getString(R.string.setup_agent_not_signed_in)
                : acc.version.isEmpty() ? null : getString(R.string.check_agent_version, acc.version),
                getString(R.string.check_fix), () -> startActivity(new Intent(this, SignInActivity.class).putExtra("agent", agent))));

        String host = null;
        for (String h : new String[]{"github.com", agentHost(agent)}) {
            try {
                InetAddress.getByName(h);
            } catch (Exception e) {
                host = h;
                break;
            }
        }
        r.add(new Result(getString(R.string.check_network), host == null, host == null ? null : getString(R.string.check_network_bad, host),
                null, null));

        long free = new StatFs(getFilesDir().getPath()).getAvailableBytes();
        String size = Formatter.formatShortFileSize(this, free);
        r.add(new Result(getString(R.string.check_space), free >= SPACE_NEEDED, getString(free >= SPACE_NEEDED
                ? R.string.check_space_ok : R.string.check_space_bad, size), null, null));
        return r;
    }

    /** The phone tools server answers with this install's token. */
    private boolean health() {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL("http://127.0.0.1:" + McpServer.PORT + "/health").openConnection();
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);
            con.setRequestProperty("Authorization", "Bearer " + Prefs.token(this));
            int code = con.getResponseCode();
            con.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** HELPER_VERSION in the helper copied into Linux (f), or in the app's own copy (null); 0 if not found. */
    private int helperVersion(File f) {
        try (BufferedReader in = f == null ? new BufferedReader(new InputStreamReader(getAssets().open("linux/buddy-run")))
                : new BufferedReader(new FileReader(f))) {
            for (int i = 0; i < 40; i++) {
                String l = in.readLine();
                if (l == null) break;
                if (l.startsWith("HELPER_VERSION=")) return Integer.parseInt(l.substring(15).trim());
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String agentHost(String agent) {
        switch (agent) {
            case "codex": return "chatgpt.com";
            case "opencode": return "opencode.ai";
            case "agy": return "generativelanguage.googleapis.com";
            case "grok": return "api.x.ai";
            default: return "api.anthropic.com";
        }
    }

    private void render() {
        body.removeAllViews();
        int failed = 0;
        for (Result r : results) if (!r.ok) failed++;
        TextView sum = title(this, failed == 0 ? getString(R.string.check_all_ok)
                : BuddyApp.plural(R.plurals.check_failed, failed, failed), 15);
        sum.setPaddingRelative(0, dp(this, 8), 0, dp(this, 12));
        body.addView(sum, full());
        LinearLayout card = column(this);
        card.setBackground(rounded(this, CARD, 16));
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            if (i > 0) card.addView(divider(), lineParams());
            card.addView(resultRow(r), full());
        }
        body.addView(card, full());
        Button copy = button(this, getString(R.string.check_copy), false);
        copy.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Buddy", report()));
            Toast.makeText(this, R.string.check_copied, Toast.LENGTH_SHORT).show();
        });
        body.addView(copy, topGap(16));
        Button bug = button(this, getString(R.string.check_report), false);
        bug.setOnClickListener(v -> reportBug());
        body.addView(bug, topGap(8));
    }

    private LinearLayout resultRow(Result r) {
        LinearLayout l = row(this);
        l.setPaddingRelative(dp(this, 14), dp(this, 12), dp(this, 12), dp(this, 12));
        l.addView(icon(this, r.ok ? R.drawable.ms_check : R.drawable.ms_error, r.ok ? OK : ERR, 20));
        LinearLayout t = column(this);
        t.setPaddingRelative(dp(this, 12), 0, dp(this, 8), 0);
        t.addView(title(this, r.name, 15));
        if (r.detail != null) t.addView(text(this, r.detail, 13, r.ok ? MUTED : ERR), topGap(2));
        l.addView(t, weight1());
        if (!r.ok && r.fix != null) {
            Button b = outlinedButton(this, r.fixLabel, ACCENT_TEXT);
            b.setOnClickListener(v -> r.fix.run());
            l.addView(b);
        }
        return l;
    }

    private static String phone() {
        return Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE;
    }

    private static String buddy() {
        return BuildInfo.VERSION + " (" + (Flavor.PLAY ? "Play" : "GitHub") + ")";
    }

    /** Agents that are installed, with their versions. */
    private String agents() {
        StringBuilder b = new StringBuilder();
        for (Account a : Account.ALL) {
            if (!SetupState.installed(this, a.agent)) continue;
            if (b.length() > 0) b.append(", ");
            b.append(a.name()).append(a.version.isEmpty() ? "" : " " + a.version)
                    .append(SetupState.signedIn(this, a.agent) ? "" : " (signed out)");
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    /** The checks that failed, one per line ("all OK" when none). */
    private String failures() {
        StringBuilder b = new StringBuilder();
        for (Result r : results) {
            if (r.ok) continue;
            b.append("- ").append(r.name).append(": ").append(r.detail == null ? "failed" : r.detail).append('\n');
        }
        return b.length() == 0 ? "All checks OK\n" : b.toString();
    }

    /** Plain text, no personal content: versions, the phone, the default agent and the results. */
    private String report() {
        StringBuilder b = new StringBuilder("Buddy " + buddy() + "\n" + phone() + "\nDefault agent: "
                + Account.nameOf(Prefs.backend(this)) + "\nAgents: " + agents() + "\n\n");
        for (Result r : results) {
            b.append(r.ok ? "OK    " : "FAIL  ").append(r.name).append(r.detail == null ? "" : ": " + r.detail).append('\n');
        }
        return b.toString();
    }

    /** A new GitHub issue with the form's fields filled in, after showing exactly what goes in them. */
    private void reportBug() {
        String extra = "Default agent: " + Account.nameOf(Prefs.backend(this)) + "\nAgents: " + agents()
                + "\n\nCheck Buddy:\n" + failures();
        String shown = getString(R.string.check_report_text) + "\n\n" + phone() + "\nBuddy " + buddy() + "\n" + extra;
        confirmSheet(this, getString(R.string.check_report_title), shown, getString(R.string.check_report_open), false, () -> {
            Uri u = Uri.parse(ISSUES).buildUpon()
                    .appendQueryParameter("phone", phone())
                    .appendQueryParameter("buddy", buddy())
                    .appendQueryParameter("agent", Account.nameOf(Prefs.backend(this))) // a dropdown may not take it: in extra too
                    .appendQueryParameter("extra", extra)
                    .build();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) {
            }
        });
    }

    private android.view.View divider() {
        android.view.View v = new android.view.View(this);
        v.setBackgroundColor(LINE);
        return v;
    }

    private LinearLayout.LayoutParams lineParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1));
        p.leftMargin = p.rightMargin = dp(this, 16);
        return p;
    }

    private LinearLayout.LayoutParams topGap(int dpv) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, dpv);
        return p;
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
