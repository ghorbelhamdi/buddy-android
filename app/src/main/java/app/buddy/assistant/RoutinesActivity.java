package app.buddy.assistant;

import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import static app.buddy.assistant.Ui.*;

/**
 * Settings → Routines: the routines agents saved (see Routines). Run one here, without an agent: Buddy does its steps
 * on the screen, with the usual approval cards, and says how it went in a notification; when a step doesn't fit, that
 * notification opens a new chat with what's left, for an agent to finish. Tap one to see its steps.
 */
public class RoutinesActivity extends Screen {
    private static final String CHANNEL = "routines", TAG = "routine";
    /** The routine Buddy is doing now from this page (one at a time: they'd share the screen). */
    // (the routine running now: Routines.RUNNING, shared with the page for other devices)
    private LinearLayout body;
    private final Runnable refresh = this::render;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String draft = getIntent().getStringExtra("finish");
        if (draft != null) { // from the "stopped" notification: a new chat with what's left, for an agent
            ChatHub hub = ChatHub.get(this);
            Sessions.Session s = hub.create(Prefs.backend(this));
            startActivity(new Intent(this, ChatActivity.class).putExtra("id", s.id).putExtra("draft", draft));
            finish();
            return;
        }
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = title(this, getString(R.string.routines_title), 18);
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
        setContentView(protect(frame)); // only the user runs or deletes them
    }

    @Override
    protected void onResume() {
        super.onResume();
        Routines.Listeners.add(refresh);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Routines.Listeners.remove(refresh);
    }

    private void render() {
        if (body == null) return;
        body.removeAllViews();
        body.addView(text(this, getString(R.string.routines_intro), 13, MUTED), full());
        if (Background.setUp(this)) { // where Run here does them: the user's screen, or a hidden one of their own
            LinearLayout sw = switchRow(this, getString(R.string.routines_hidden), getString(R.string.routines_hidden_info),
                    Prefs.routinesHidden(this), on -> Prefs.setRoutinesHidden(this, on));
            sw.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
            body.addView(sw, gap(14));
        }
        List<JSONObject> all = Routines.all(this);
        int n = 0;
        for (JSONObject r : all) body.addView(card(r), gap(n++ == 0 ? 14 : 8));
        if (n == 0) body.addView(text(this, getString(R.string.routines_none), 15, TEXT), gap(18));
    }

    private LinearLayout card(JSONObject r) {
        String name = r.optString("name");
        JSONArray steps = r.optJSONArray("steps");
        List<String> blanks = Routines.blanks(steps);
        LinearLayout card = column(this);
        card.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
        card.setPaddingRelative(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 10));
        card.addView(single(title(this, name, 15)), full());
        if (!r.optString("about").isEmpty()) card.addView(text(this, r.optString("about"), 13, MUTED), gap(2));
        StringBuilder meta = new StringBuilder(getResources().getQuantityString(R.plurals.routines_steps,
                steps == null ? 0 : steps.length(), steps == null ? 0 : steps.length()));
        if (!blanks.isEmpty()) meta.append(" · ").append(getString(R.string.routines_blanks, android.text.TextUtils.join(", ", blanks)));
        if (r.optLong("ran") > 0) {
            meta.append(" · ").append(getString(R.string.routines_last_run, android.text.format.DateUtils
                    .getRelativeTimeSpanString(r.optLong("ran"), System.currentTimeMillis(), 60000)));
            if (r.optInt("stopped") > 0) meta.append(" · ").append(getString(R.string.routines_stopped_at, r.optInt("stopped")));
        }
        card.addView(text(this, meta.toString(), 13, ACCENT_TEXT), gap(8));
        card.setOnClickListener(v -> stepsSheet(r));
        LinearLayout btns = row(this);
        String running = Routines.RUNNING.get();
        Button run = button(this, getString(name.equals(running) ? R.string.routines_running : R.string.routines_run), true);
        run.setEnabled(running == null);
        run.setOnClickListener(v -> {
            if (blanks.isEmpty()) start(name, new JSONObject());
            else blanksSheet(name, blanks);
        });
        Button del = button(this, getString(R.string.routines_delete), false);
        del.setOnClickListener(v -> confirmSheet(this, getString(R.string.routines_delete_title, name),
                getString(R.string.routines_delete_text), getString(R.string.routines_delete), true, () -> Routines.delete(this, name)));
        btns.addView(run, new LinearLayout.LayoutParams(0, dp(this, 40), 1));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, dp(this, 40), 1);
        dp.setMarginStart(dp(this, 8));
        btns.addView(del, dp);
        card.addView(btns, gap(10));
        return card;
    }

    /** The steps, in plain words. */
    private void stepsSheet(JSONObject r) {
        LinearLayout c = column(this);
        c.addView(title(this, r.optString("name"), 18));
        JSONArray steps = r.optJSONArray("steps");
        StringBuilder b = new StringBuilder();
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject st = steps.optJSONObject(i);
            if (st == null) continue;
            b.append(i + 1).append(". ").append(describe(st)).append('\n');
        }
        TextView list = text(this, b.toString().trim(), 14, TEXT);
        list.setTextIsSelectable(true);
        c.addView(list, gap(12));
        bottomSheet(this, protect(c)).show();
    }

    private String describe(JSONObject st) {
        String text = st.optString("text"), what = !text.isEmpty() ? "“" + text + "”" : !st.optString("id").isEmpty()
                ? st.optString("id").replaceFirst(".*/", "") : st.has("x") ? st.optInt("x") + ", " + st.optInt("y") : "";
        switch (st.optString("action")) {
            case "open_app": return getString(R.string.routines_step_open, label(st.optString("app")));
            case "open_url": return getString(R.string.routines_step_open, st.optString("url"));
            case "tap": return getString(R.string.routines_step_tap, what);
            case "long_press": return getString(R.string.routines_step_hold, what);
            case "type": return getString(R.string.routines_step_type, text,
                    st.optString("target").isEmpty() ? "" : " → “" + st.optString("target") + "”");
            case "scroll": return getString(R.string.routines_step_scroll);
            case "swipe": return getString(R.string.routines_step_swipe);
            case "press": return getString("back".equals(st.optString("button")) ? R.string.routines_step_back : R.string.routines_step_press);
            case "find": return getString(R.string.routines_step_find, text);
            default: return getString(R.string.routines_step_wait);
        }
    }

    private String label(String pkg) {
        try {
            return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    /** What to type into the routine's blanks this time. */
    private void blanksSheet(String name, List<String> blanks) {
        LinearLayout c = column(this);
        c.addView(title(this, name, 18));
        c.addView(text(this, getString(R.string.routines_blanks_info), 13, MUTED), gap(4));
        List<EditText> fields = new ArrayList<>();
        for (String b : blanks) {
            EditText et = new EditText(this);
            et.setHint(b);
            et.setHintTextColor(MUTED);
            et.setTextColor(TEXT);
            et.setTextSize(14);
            et.setPaddingRelative(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
            et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
            fields.add(et);
            c.addView(et, gap(8));
        }
        Button go = button(this, getString(R.string.routines_run), true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        sp.topMargin = dp(this, 14);
        c.addView(go, sp);
        Dialog d = bottomSheet(this, protect(c));
        go.setOnClickListener(v -> {
            JSONObject values = new JSONObject();
            try {
                for (int i = 0; i < blanks.size(); i++) {
                    String val = fields.get(i).getText().toString();
                    if (val.trim().isEmpty()) {
                        Toast.makeText(this, R.string.routines_blanks_need, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    values.put(blanks.get(i), val);
                }
            } catch (Exception ignored) {
            }
            d.dismiss();
            start(name, values);
        });
        d.show();
    }

    /** Runs it now, off the main thread; the notification says how it went. */
    private void start(String name, JSONObject values) {
        BuddyService svc = BuddyService.get();
        if (svc == null || svc.tools() == null) {
            Toast.makeText(this, R.string.routines_need_access, Toast.LENGTH_LONG).show();
            return;
        }
        if (!Routines.RUNNING.compareAndSet(null, name)) return;
        if (svc.bubble() != null && svc.bubble().confirming()) { // it would wait behind that card: say so first
            Toast.makeText(this, R.string.routines_card_waiting, Toast.LENGTH_LONG).show();
        }
        render();
        Context app = getApplicationContext();
        new Thread(() -> {
            String result;
            try {
                result = svc.tools().runFromPage(name, values, Background.setUp(app) && Prefs.routinesHidden(app));
            } finally {
                Routines.RUNNING.set(null);
            }
            done(app, name, result);
            Routines.Listeners.changed();
        }, "routine").start();
    }

    /** How the run went: a short notice when it's done, and a way on when it stopped. */
    static void done(Context c, String name, String result) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.routines_title),
                NotificationManager.IMPORTANCE_DEFAULT));
        boolean ok = result.contains("Done: all");
        String why = ok ? c.getString(R.string.routines_done_text) : reason(result);
        Notification.Builder n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(ok ? R.string.routines_done_title : R.string.routines_stopped_title, name))
                .setContentText(why)
                .setStyle(new Notification.BigTextStyle().bigText(why))
                .setAutoCancel(true);
        if (!ok) {
            // the agent gets what Buddy did and the steps left; the user sends it (or doesn't)
            String draft = "The routine \"" + name + "\" stopped before it was done. Finish it from where it stopped, "
                    + "on the screen as it is now.\n\n" + result;
            n.setContentIntent(PendingIntent.getActivity(c, name.hashCode(), new Intent(c, RoutinesActivity.class)
                            .putExtra("finish", draft).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            n.addAction(new Notification.Action.Builder(null, c.getString(R.string.routines_finish),
                    PendingIntent.getActivity(c, name.hashCode() + 1, new Intent(c, RoutinesActivity.class)
                                    .putExtra("finish", draft).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        } else {
            n.setContentIntent(PendingIntent.getActivity(c, name.hashCode(), new Intent(c, RoutinesActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        try {
            nm.notify(TAG, name.hashCode(), n.build());
        } catch (SecurityException ignored) { // notifications off
        }
    }

    /** The line of the step it stopped at, as Buddy wrote it (the original words, not rewritten). */
    private static String reason(String result) {
        String last = null;
        for (String line : result.split("\n")) {
            if (line.matches("^\\d+\\. .*")) last = line;
            if (line.startsWith("Stopped at step")) break;
        }
        if (last == null) {
            int nl = result.indexOf('\n');
            last = nl > 0 ? result.substring(0, nl) : result;
        }
        return ChatHub.clip(last, 300);
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }
}
